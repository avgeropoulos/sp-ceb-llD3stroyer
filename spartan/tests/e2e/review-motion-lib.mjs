// Review harness (motion lens): drives the REAL game deterministically with synthesized phone motion.
//  - performance.now() is replaced by a virtual clock that advances with each game frame, so
//    platform.inject() (released on wall-clock time) and the fake Android bridge are paced exactly.
//  - rendering is skipped except when a screenshot is requested (SwiftShader is slow).
//  - sources: 'inject' (g.injectMotion → platform queue, web-kind frames), 'android' (a fake
//    window.SpartanNative whose pollMotion() returns CSV lines, android-kind frames), 'web'
//    (DeviceMotionEvents dispatched on window, with screen.orientation.angle overridden).
import { launch } from './lib.mjs';

const INIT_ANDROID = () => {
  const q = [];
  window.__nativeQ = q;
  window.__nativeRot = 90;
  window.__nativeCalls = [];
  window.SpartanNative = {
    motionAvailable: () => true,
    startMotion: () => window.__nativeCalls.push('start'),
    stopMotion: () => window.__nativeCalls.push('stop'),
    pollMotion: () => {
      const now = performance.now() / 1000;
      let n = 0;
      while (n < q.length && q[n].t <= now + 1e-9) n++;
      if (!n) return '';
      // the native ring (HostLogic.RING_LINES = 512) drops the oldest lines when full
      const due = q.splice(0, n);
      return due.slice(Math.max(0, due.length - 512)).map((e) => e.line).join('\n') + '\n';
    },
    motionInfo: () => '{"gyroRange":34.9,"accRange":78.4,"rateHz":100}',
    vibrate: () => {},
    vibratePattern: () => {},
    say: () => {},
    stopSpeech: () => {},
    rotation: () => window.__nativeRot,
    lockOrientation: () => {},
    exit: () => {},
  };
};

const INIT_WEB = () => {
  window.__webRot = 90;
  try {
    Object.defineProperty(ScreenOrientation.prototype, 'angle', { configurable: true, get: () => window.__webRot });
  } catch (e) {
    /* ignore */
  }
};

/** Page-side harness installed after boot. */
function installHarness() {
  const g = window.__game;
  const ctx = g.ctx;
  const realNow = performance.now.bind(performance);
  let vt = realNow();
  performance.now = () => vt;
  const r = ctx.renderer;
  const realRender = r.render.bind(r);
  let drawing = false;
  r.render = (...a) => (drawing ? realRender(...a) : undefined);
  const log = [];
  const brief = (name, e) => {
    if (!e || typeof e !== 'object') return {};
    const o = {};
    for (const k of ['type', 'kind', 'dir', 'power', 'peak', 'sweep', 'src', 'weapon', 'phase', 'id', 'name', 'perfect', 'by', 'damage', 'timeToStrike', 'targetIsPlayer', 'mode', 'prev', 'up', 'cause', 'spear']) {
      if (e[k] !== undefined && typeof e[k] !== 'object') o[k] = e[k];
    }
    if (e.target && typeof e.target === 'object') o.target = e.target.kind || e.target.role || 'obj';
    else if (typeof e.target === 'string') o.target = e.target;
    if (e.actor && e.actor.kind) o.actor = e.actor.kind;
    if (e.attacker && e.attacker.kind) o.attacker = e.attacker.kind;
    return o;
  };
  const NAMES = ['gesture', 'swing', 'combo', 'hit', 'spearCam', 'block', 'playerHurt', 'enemyWindup', 'kill', 'spearImpact', 'weaponSwitch', 'modeChanged', 'shield', 'voice'];
  for (const n of NAMES) ctx.events.on(n, (e) => log.push(Object.assign({ ev: n, t: +(vt / 1000).toFixed(4), gnow: +ctx.time.gnow.toFixed(4), frame: R.frameNo }, brief(n, e))));

  // web DeviceMotion feed: [{ t(sec, virtual), init }]
  const webQ = [];
  const D = 180 / Math.PI;
  // Real sensors stream continuously: between scripts the android/web sources keep sending
  // "holding still" samples (the last attitude, zero rate) so no artificial gap appears.
  let lastFed = null;
  let lastFedT = -1;
  let srcKind = null;
  function pushRaw(s, t) {
    lastFed = s;
    lastFedT = t;
    if (srcKind === 'android') {
      const f = (v) => (v ? `${v[0]},${v[1]},${v[2]}` : 'NaN,NaN,NaN');
      window.__nativeQ.push({ t, line: `${t.toFixed(6)},${s.gyro[0]},${s.gyro[1]},${s.gyro[2]},${f(s.acc)},${f(s.accG)},${f(s.grav)},${s.rot}` });
    } else {
      webQ.push({
        t,
        init: {
          // R.w3cOrder: alpha about z, beta about x, gamma about y (the spec order) instead of x, y, z
          rotationRate: R.w3cOrder ? { alpha: s.gyro[2] * D, beta: s.gyro[0] * D, gamma: s.gyro[1] * D } : { alpha: s.gyro[0] * D, beta: s.gyro[1] * D, gamma: s.gyro[2] * D },
          acceleration: s.acc ? { x: s.acc[0], y: s.acc[1], z: s.acc[2] } : null,
          accelerationIncludingGravity: s.accG ? { x: s.accG[0], y: s.accG[1], z: s.accG[2] } : null,
          interval: 10,
        },
      });
    }
  }
  function filler() {
    if (!lastFed || R.noFiller) return;
    const now = vt / 1000;
    const still = { gyro: [0, 0, 0], acc: lastFed.acc ? [0, 0, 0] : null, accG: lastFed.accG && lastFed.grav ? lastFed.grav.slice() : lastFed.accG, grav: lastFed.grav, rot: lastFed.rot };
    if (lastFed.gyro[0] || lastFed.gyro[1] || lastFed.gyro[2]) {
      // the last scripted sample ended at rest (scripts end with idle): reuse its gravity
      still.accG = lastFed.grav ? lastFed.grav.slice() : lastFed.accG;
    }
    while (lastFedT + 0.01 <= now + 1e-9) pushRaw(still, lastFedT + 0.01);
    lastFed = still;
  }
  function feedWeb() {
    const now = vt / 1000;
    while (webQ.length && webQ[0].t <= now + 1e-9) {
      const s = webQ.shift();
      const ev = new DeviceMotionEvent('devicemotion', s.init);
      Object.defineProperty(ev, 'timeStamp', { value: s.t * 1000 });
      window.dispatchEvent(ev);
    }
  }

  const R = {
    frameNo: 0,
    log,
    get vt() {
      return vt / 1000;
    },
    clearLog() {
      log.length = 0;
    },
    /** Advance n frames of dt (virtual time + game). */
    frames(n = 1, dt = 1 / 60, each) {
      for (let i = 0; i < n; i++) {
        vt += dt * 1000;
        filler();
        feedWeb();
        R.frameNo++;
        g.step(1, dt);
        if (each) each(i);
      }
    },
    /** Advance until fn() is true or max frames. Returns frames advanced. */
    until(fn, max = 600, dt = 1 / 60) {
      let i = 0;
      while (i < max && !fn()) {
        R.frames(1, dt);
        i++;
      }
      return i;
    },
    draw() {
      drawing = true;
      try {
        g.render();
      } finally {
        drawing = false;
      }
    },
    /** Feed synthesized samples through a source. Returns { start (virtual s), dur }. */
    feed(samples, source = 'inject') {
      const t0 = samples[0].t;
      const dur = samples[samples.length - 1].t - t0;
      const base = vt / 1000 + 0.01;
      if (source === 'inject') {
        g.injectMotion(samples);
      } else {
        srcKind = source;
        const b = lastFed && lastFedT > vt / 1000 - 0.05 ? lastFedT + 0.01 : base;
        for (const smp of samples) pushRaw(smp, b + (smp.t - t0));
        return { start: b, dur };
      }
      return { start: vt / 1000, dur }; // inject releases offset o at the current virtual time + o
    },
    /** Synthesize + feed + run frames until the motion is consumed (plus tail seconds). */
    play(script, opts = {}, { source = 'inject', tail = 0.3, dt = 1 / 60, each } = {}) {
      const samples = g.synth(script, opts);
      const { start, dur } = R.feed(samples, source);
      const n = Math.ceil((dur + 0.02 + tail) / dt);
      R.frames(n, dt, each);
      return { start, dur, n };
    },
    /** Clean arena: battle with no waves and no actors, player reset at the line. */
    arena({ mode = 'battle', day = 1 } = {}) {
      // headless Chromium has no gyroscope: the title screen's isSupported() probe reverts the
      // setting to touch. A phone with a gyro keeps 'motion'.
      ctx.settings.control = 'motion';
      if (mode === 'battle') g.startBattle(day);
      else g.startTraining();
      ctx.waves.stop();
      if (mode !== 'battle') ctx.training.stop();
      ctx.ai.clear();
      R.frames(5);
      ctx.player.pos.set(0, 0, 0);
      ctx.player.yaw = 0;
      R.clearLog();
    },
    evs(name, filter) {
      return log.filter((e) => e.ev === name && (!filter || filter(e)));
    },
    tok(dir) {
      const a = Math.round(((dir + Math.PI * 2) % (Math.PI * 2)) / (Math.PI / 4)) % 8;
      return ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'][a];
    },
    /** Dispatch a pointer event on the #controls surface (a touch on the glass). */
    touch(type, x, y, id = 7, extra = {}) {
      const el = document.elementFromPoint(x, y) || document.querySelector('#controls .ctl-surface');
      const ev = new PointerEvent(type, Object.assign({ bubbles: true, cancelable: true, composed: true, pointerId: id, pointerType: 'touch', clientX: x, clientY: y, isPrimary: true, width: 20, height: 20, pressure: 0.5 }, extra));
      el.dispatchEvent(ev);
      return el.className || el.tagName;
    },
  };
  window.__R = R;
  return true;
}

/**
 * Opens the game. opts: { source: 'inject'|'android'|'web', query, width, height, mobile }.
 * Returns { l, page, ev(fn, arg), close() } where ev runs fn(R, g, ctx, arg) in the page.
 */
export async function openGame({ source = 'inject', query = '', width = 960, height = 540, mobile = true, l: shared } = {}) {
  const l = shared || (await launch());
  const page = await l.newPage({ width, height, mobile });
  if (source === 'android') await page.addInitScript(INIT_ANDROID);
  if (source === 'web') await page.addInitScript(INIT_WEB);
  await page.goto(`${l.game}?manual=1&seed=7&quality=low&control=motion${query ? `&${query}` : ''}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  await page.waitForTimeout(2000); // let the title screen's motion probe (1.5 s) resolve first
  const errs0 = await page.evaluate(() => window.__game.errors);
  if (errs0.length) throw new Error('boot errors: ' + errs0.join('\n'));
  await page.evaluate(installHarness);
  const ev = (fn, arg) =>
    page.evaluate(
      ([src, a]) => {
        const R = window.__R;
        const g = window.__game;
        // eslint-disable-next-line no-new-func
        const f = new Function('R', 'g', 'ctx', 'arg', `return (${src})(R, g, ctx, arg);`);
        return f(R, g, g.ctx, a);
      },
      [fn.toString(), arg],
    );
  return {
    l,
    page,
    ev,
    async errors() {
      return page.errors.concat(await page.evaluate(() => window.__game.errors));
    },
    async close() {
      await page.context().close();
      if (!shared) await l.close();
    },
  };
}
