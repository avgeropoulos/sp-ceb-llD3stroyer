// Review (device lens): a fake window.SpartanNative with the Kotlin bridge's exact arities and
// Java-bridge type coercion, injected before the page loads, for "phone-like" Playwright runs.
// Exported for the review-device-*.mjs scripts. Not part of the shipped game.

/** The init script: installs window.SpartanNative and window.__nat (test control). */
export const NATIVE_INIT = String.raw`(() => {
  // Kotlin signatures (NativeBridge.kt). Android looks methods up by name AND argument count:
  // a wrong count throws "Error: Method not found".
  const SIG = {
    motionAvailable: [], startMotion: [], stopMotion: [], pollMotion: [], motionInfo: [],
    vibrate: ['int', 'int'], vibratePattern: ['String?'], say: ['String?', 'float', 'float'],
    stopSpeech: [], rotation: [], lockOrientation: ['boolean', 'String?'], exit: [],
  };
  // orient: every lockOrientation call as [lock, orientation]; locked/orientation: the last one.
  const st = {
    wanted: false, resumed: true, ring: [], dropped: 0, rot: 90, locked: false, orientation: null,
    vib: [], said: [], exits: 0, warn: [], calls: {}, orient: [], hidden: false, t: 0,
  };
  const conv = (type, v, name, i) => {
    const w = (m) => st.warn.push(name + ' arg' + i + ': ' + m);
    switch (type) {
      case 'int':
        if (typeof v !== 'number') { w(typeof v + ' -> int 0'); return 0; }
        if (!Number.isInteger(v)) w('non-integer ' + v + ' truncated');
        return Math.trunc(v) | 0;
      case 'float':
        if (typeof v !== 'number') { w(typeof v + ' -> float 0'); return 0; }
        return Math.fround(v);
      case 'boolean':
        if (typeof v !== 'boolean') { w(typeof v + ' -> boolean false'); return false; }
        return v;
      case 'String?':
        if (v === null || v === undefined) return null;
        if (typeof v !== 'string') { w(typeof v + ' -> String'); return String(v); }
        return v;
      default:
        return v;
    }
  };
  const line = (s) => {
    const f = (v) => (v == null || !Number.isFinite(v) ? 'NaN' : String(Math.fround(v)));
    const tri = (a) => (a ? f(a[0]) + ',' + f(a[1]) + ',' + f(a[2]) : 'NaN,NaN,NaN');
    return s.t + ',' + tri(s.gyro) + ',' + tri(s.acc) + ',' + tri(s.accG) + ',' + tri(s.grav) + ',' + st.rot + '\n';
  };
  const impl = {
    motionAvailable: () => true,
    startMotion: () => { if (!st.wanted) { st.wanted = true; st.ring.length = 0; } },
    stopMotion: () => { st.wanted = false; },
    pollMotion: () => { const s = st.ring.join(''); st.ring.length = 0; return s; },
    motionInfo: () => '{"gyroRange":34.9,"accRange":78.5,"rateHz":200.0}',
    vibrate: (ms, amp) => { st.vib.push({ k: 'one', ms, amp, at: performance.now(), g: window.__game && window.__game.ctx ? window.__game.ctx.time.now : 0 }); },
    vibratePattern: (csv) => { st.vib.push({ k: 'pat', csv, at: performance.now(), g: window.__game && window.__game.ctx ? window.__game.ctx.time.now : 0 }); },
    say: (text, p, r) => { st.said.push({ text, p, r }); },
    stopSpeech: () => {},
    rotation: () => st.rot,
    lockOrientation: (b, o) => { st.locked = b; st.orientation = o; st.orient.push([b, o]); },
    exit: () => { st.exits++; },
  };
  const N = {};
  for (const name of Object.keys(SIG)) {
    const sig = SIG[name];
    N[name] = function () {
      st.calls[name] = (st.calls[name] || 0) + 1;
      if (arguments.length !== sig.length) {
        st.warn.push('ARITY ' + name + '(' + arguments.length + ') expected ' + sig.length);
        throw new Error('Method not found');
      }
      const a = [];
      for (let i = 0; i < sig.length; i++) a.push(conv(sig[i], arguments[i], name, i));
      return impl[name].apply(null, a);
    };
  }
  window.SpartanNative = N;

  // Page visibility as WebView.onPause()/onResume() would set it.
  Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: () => (st.hidden ? 'hidden' : 'visible') });
  Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: () => st.hidden });

  window.__nat = {
    st,
    /** Pushes RawSamples (game synth format) as sensor lines, like the sensor thread (512-line ring). */
    feed(samples) {
      if (!st.wanted || !st.resumed) return 0;
      let n = 0;
      for (const s of samples) {
        st.ring.push(line(s));
        if (st.ring.length > 512) { st.ring.shift(); st.dropped++; }
        n++;
      }
      return n;
    },
    /** MainActivity.onPause order: nativepause JS, bridge.onPause, wv.onPause (hidden). */
    activityPause() {
      window.dispatchEvent(new Event('nativepause'));
      st.resumed = false;
      st.hidden = true;
      document.dispatchEvent(new Event('visibilitychange'));
    },
    /** MainActivity.onResume order: wv.onResume (visible), bridge.onResume (clear ring), nativeresume JS. */
    activityResume() {
      st.hidden = false;
      document.dispatchEvent(new Event('visibilitychange'));
      st.resumed = true;
      st.ring.length = 0;
      window.dispatchEvent(new Event('nativeresume'));
    },
    /** MainActivity.handleBack: BACK_SCRIPT, as evaluateJavascript would JSON-encode its result. */
    back() {
      const r = (function(){try{return !!(window.__onBack&&window.__onBack())}catch(e){return false}})();
      return JSON.stringify(r);
    },
  };
  // A mid-range phone: 8 cores, 4 GB.
  try { Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { configurable: true, get: () => 8 }); } catch (e) {}
  try { Object.defineProperty(Navigator.prototype, 'deviceMemory', { configurable: true, get: () => 4 }); } catch (e) {}
})();`;

export async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    return await import('/opt/node22/lib/node_modules/playwright/index.mjs');
  }
}

export const BROWSER_ARGS = ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'];

/**
 * Init script of phonePage({ grip }), the same seed as lib.mjs's newPage({ grip }): before each
 * load, merges { grip, gripChosen: true } into the stored settings unless they already hold a grip,
 * so the review-device scripts keep the window grip whatever DEFAULT_GRIP is (docs-hilt-grip.md
 * §H.2). Unreadable stored settings are left alone.
 */
function seedGrip(grip) {
  try {
    const key = 'thermopylae.settings';
    const str = localStorage.getItem(key);
    const s = str == null ? {} : JSON.parse(str);
    if (!s || typeof s !== 'object' || Array.isArray(s) || 'grip' in s) return;
    s.grip = grip;
    s.gripChosen = true;
    localStorage.setItem(key, JSON.stringify(s));
  } catch {
    /* no storage on this page (about:blank), or not JSON */
  }
}

/**
 * A phone-like context: 915×412 CSS px landscape at DPR 2.75, touch, mobile.
 * grip seeds the stored settings' grip: 'window' (default), 'hilt', or null for none.
 */
export async function phonePage(browser, { width = 915, height = 412, dpr = 2.75, native = true, grip = 'window' } = {}) {
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: dpr, isMobile: true, hasTouch: true });
  if (native) await context.addInitScript(NATIVE_INIT);
  if (grip) await context.addInitScript(seedGrip, grip);
  const page = await context.newPage();
  page.errors = [];
  page.logs = [];
  page.on('pageerror', (e) => page.errors.push(`pageerror: ${e.stack || e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error') page.errors.push(`console: ${m.text()}`);
    else if (m.type() === 'warning') page.logs.push(`warn: ${m.text()}`);
  });
  return page;
}

/** Taps the centre of the first visible element matching sel with the touchscreen. */
export async function tap(page, sel) {
  const box = await page.evaluate((s) => {
    const els = [...document.querySelectorAll(s)];
    for (const e of els) {
      const r = e.getBoundingClientRect();
      const cs = getComputedStyle(e);
      if (r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none') return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    }
    return null;
  }, sel);
  if (!box) throw new Error(`tap: no visible ${sel}`);
  await page.touchscreen.tap(box.x, box.y);
  return box;
}
