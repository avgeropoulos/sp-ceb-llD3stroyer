// The hilt grip's portrait UI (docs-hilt-grip.md §H.2 #1, package P8): every screen on the real game
// at 412×915 (with a simulated cutout and gesture bar) and 360×800, through a fake Android bridge, with
// layout checks in each state; then the rotate prompt (a landscape viewport with the hilt grip), and the
// same screens in landscape with the window grip (915×412) to show that nothing there regressed.
// Usage: node spartan/tests/e2e/hilt-shots.mjs [outDir] [filter]   (filter: a substring of the pass name)
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const outDir = process.argv[2] || null;
const filter = process.argv[3] || '';
if (outDir) fs.mkdirSync(outDir, { recursive: true });

const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};

// A fake window.SpartanNative with the bridge's arities (NativeBridge.kt): motion is available, the
// orientation requests are recorded, and window.__upd is the app updater's state (§2.2).
const NATIVE = String.raw`(() => {
  const SIG = { motionAvailable: 0, startMotion: 0, stopMotion: 0, pollMotion: 0, motionInfo: 0, vibrate: 2, vibratePattern: 1,
    say: 3, stopSpeech: 0, rotation: 0, lockOrientation: 2, updateState: 0, checkForUpdate: 0, installUpdate: 0, exit: 0 };
  window.__orient = [];
  window.__arity = [];
  window.__upd = { state: 'idle', enabled: true, installed: 41, available: 0, name: '', notes: '', progress: 0, error: '' };
  const impl = {
    motionAvailable: () => true, startMotion() {}, stopMotion() {}, pollMotion: () => '',
    motionInfo: () => '{"gyroRange":34.9,"accRange":78.5,"rateHz":200.0}', vibrate() {}, vibratePattern() {}, say() {}, stopSpeech() {},
    rotation: () => (innerWidth > innerHeight ? 90 : 0), lockOrientation: (b, o) => window.__orient.push([b, o]),
    updateState: () => JSON.stringify(window.__upd), checkForUpdate() {}, installUpdate() {}, exit() {},
  };
  const N = {};
  for (const k of Object.keys(SIG)) {
    N[k] = function () {
      if (arguments.length !== SIG[k]) { window.__arity.push(k + '(' + arguments.length + ')'); throw new Error('Method not found'); }
      return impl[k].apply(null, arguments);
    };
  }
  window.SpartanNative = N;
})();`;

/** Init script: merges `seed` into the stored settings and stores a best run (so Continue shows). */
function seedStorage(seed) {
  try {
    const key = 'thermopylae.settings';
    const s = JSON.parse(localStorage.getItem(key) || '{}');
    localStorage.setItem(key, JSON.stringify(Object.assign(s, seed)));
    if (!localStorage.getItem('thermopylae.best')) localStorage.setItem('thermopylae.best', JSON.stringify({ score: 15420, kills: 87, day: 2 }));
  } catch {
    /* about:blank */
  }
}

/**
 * Layout audit, run in the page. `fight`: a battle or Agoge state (the thumb-arc rule applies).
 * Returns the list of problems (empty when the state is clean).
 */
function audit({ fight, hand }) {
  const W = innerWidth;
  const H = innerHeight;
  const out = [];
  // The safe areas as the game sees them (--safe-*).
  const probe = document.createElement('div');
  probe.style.cssText = 'position:fixed;left:var(--safe-l);top:var(--safe-t);right:var(--safe-r);bottom:var(--safe-b);visibility:hidden;pointer-events:none';
  document.body.appendChild(probe);
  const sa = probe.getBoundingClientRect();
  probe.remove();
  const visible = (el) => {
    for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
      const s = getComputedStyle(e);
      if (s.display === 'none' || s.visibility === 'hidden' || +s.opacity < 0.05) return false;
    }
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  // The part of an element a scroll container shows (null when scrolled out of view). Full-screen
  // layers clip with overflow: hidden, which must not hide a layout fault, so only scrollers count.
  const clipped = (el) => {
    let r = el.getBoundingClientRect();
    let x0 = r.left, y0 = r.top, x1 = r.right, y1 = r.bottom;
    for (let e = el.parentElement; e && e !== document.body; e = e.parentElement) {
      const s = getComputedStyle(e);
      if (!/(auto|scroll)/.test(s.overflowX + s.overflowY)) continue;
      r = e.getBoundingClientRect();
      x0 = Math.max(x0, r.left); y0 = Math.max(y0, r.top); x1 = Math.min(x1, r.right); y1 = Math.min(y1, r.bottom);
    }
    return x1 - x0 > 1 && y1 - y0 > 1 ? { left: x0, top: y0, right: x1, bottom: y1 } : null;
  };
  const name = (el) => (el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).join('.')}` : el.tagName.toLowerCase()) + (el.textContent ? ` "${el.textContent.trim().slice(0, 24)}"` : '');
  const fmt = (r) => `[${Math.round(r.left)},${Math.round(r.top)} ${Math.round(r.right - r.left)}×${Math.round(r.bottom - r.top)}]`;

  if (document.documentElement.scrollWidth > W) out.push(`scrollWidth ${document.documentElement.scrollWidth} > ${W}`);

  // Every visible button: inside the viewport less the safe areas, and at least 44×44.
  const buttons = [...document.querySelectorAll('#overlay button')].filter(visible);
  for (const b of buttons) {
    const r = b.getBoundingClientRect();
    const c = clipped(b);
    if (!c) continue;
    if (r.width < 43.5 || r.height < 43.5) out.push(`button ${name(b)} is ${Math.round(r.width)}×${Math.round(r.height)} ${fmt(r)}`);
    if (c.left < sa.left - 0.5 || c.top < sa.top - 0.5 || c.right > sa.right + 0.5 || c.bottom > sa.bottom + 0.5) out.push(`button ${name(b)} ${fmt(c)} outside the safe viewport ${fmt(sa)}`);
  }

  // Visible text: ≥ 11 px (SVG diagram labels scale with their figure and are left out), and never
  // across the left or right edge.
  const texts = [];
  for (const el of document.querySelectorAll('#overlay *')) {
    if (el.closest('svg')) continue;
    let own = '';
    for (const n of el.childNodes) if (n.nodeType === 3) own += n.textContent;
    if (!own.trim() || !visible(el)) continue;
    const c = clipped(el);
    if (!c) continue;
    texts.push(el);
    const fs = parseFloat(getComputedStyle(el).fontSize);
    if (fs < 10.95) out.push(`text ${name(el)} at ${fs}px`);
    if (c.left < -1 || c.right > W + 1) out.push(`text ${name(el)} ${fmt(c)} crosses the edge`);
  }

  // No overlap among the HUD rows, toasts, subtitles, the Agoge panel, the volley warning, title
  // cards and the respite row; the closing ring and the lock brackets stay off the panel, the
  // subtitles, the title cards and the combo popup.
  const rects = (sel) => [...document.querySelectorAll(sel)].filter(visible).map((e) => ({ e, r: e.getBoundingClientRect() }));
  const hit = (a, b) => a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1;
  const groups = {
    row: rects('#hud .hud-health, #hud .hud-spears, #hud .hud-score, #hud .hud-meta'),
    toast: rects('#hud .hud-toast.is-shown'),
    sub: rects('#hud .hud-sub.is-shown'),
    panel: rects('#training.is-on .tr-panel'),
    volley: rects('#hud .hud-volley.is-shown'),
    banner: rects('#hud .hud-banner.is-shown'),
    respite: rects('#hud .hud-respite.is-shown .hud-respite-l, #hud .hud-respite.is-shown .hud-ready'),
  };
  const keys = Object.keys(groups);
  for (let i = 0; i < keys.length; i++) {
    for (let j = i; j < keys.length; j++) {
      const A = groups[keys[i]], B = groups[keys[j]];
      for (const a of A) for (const b of B) {
        if (a.e === b.e || a.e.contains(b.e) || b.e.contains(a.e)) continue;
        if (keys[i] === 'row' && keys[j] === 'row') continue; // the rows are laid out together
        if (hit(a.r, b.r)) out.push(`overlap ${name(a.e)} ${fmt(a.r)} × ${name(b.e)} ${fmt(b.r)}`);
      }
    }
  }
  const ring = rects('#hud .hud-chev.is-shown:not(.is-off) .hud-chev-ring');
  const brackets = rects('#hud .hud-lock.is-shown:not(.is-off) .hud-lock-c, #hud .hud-lock.is-shown.has-hp .hud-lock-hp');
  const avoid = groups.panel.concat(groups.sub, groups.banner, rects('#hud .hud-combo.is-shown .hud-combo-in'));
  for (const a of ring.concat(brackets)) for (const b of avoid) if (hit(a.r, b.r)) out.push(`${name(a.e)} ${fmt(a.r)} covers ${name(b.e)} ${fmt(b.r)}`);

  // In a fight: the weapon button and READY above 35% of the height (out of the thumb's arc), and
  // no button in the thumb's band (y 40–80%, x 35–100% for a right hand, mirrored for the left).
  if (fight) {
    for (const b of buttons) {
      if (!b.closest('#controls, #hud, #training')) continue;
      const r = b.getBoundingClientRect();
      if ((b.matches('.ctl-swap') || b.matches('.hud-ready')) && r.bottom > 0.35 * H + 0.5) out.push(`${name(b)} ${fmt(r)} reaches below 35% of the height`);
      const bx0 = hand === 'left' ? 0 : 0.35 * W, bx1 = hand === 'left' ? 0.65 * W : W;
      if (r.left < bx1 && r.right > bx0 && r.top < 0.8 * H && r.bottom > 0.4 * H) out.push(`${name(b)} ${fmt(r)} lies in the thumb's band`);
    }
  }
  return out;
}

const l = await launch();
try {
  // ------------------------------------------------------------------ portrait, hilt grip
  for (const size of [
    { name: '412x915', width: 412, height: 915, insets: { top: 28, bottom: 24 } },
    { name: '360x800', width: 360, height: 800, insets: null },
  ]) {
    if (filter && !size.name.includes(filter)) continue;
    await portraitPass(size);
  }
  // ------------------------------------------------------------------ rotate prompt
  if (!filter || 'rotate'.includes(filter)) await rotatePass();
  // ------------------------------------------------------------------ landscape, window grip
  if (!filter || '915x412'.includes(filter)) await landscapePass();
} finally {
  await l.close();
}

/** A phone page on the real game: the fake bridge, seeded settings, booted and on the title. */
async function phone({ width, height, seed, query }) {
  const context = await l.browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1, isMobile: true, hasTouch: true });
  await context.addInitScript(NATIVE);
  await context.addInitScript(seedStorage, seed);
  const page = await context.newPage();
  page.errors = [];
  page.on('pageerror', (e) => page.errors.push(`pageerror: ${e.stack || e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error') page.errors.push(`console: ${m.text()}`);
  });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium${query}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  return page;
}

/** strict: audit problems fail the run; otherwise (the landscape pass) they are printed as notes. */
function helpers(page, tag, strict = true) {
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const step = (n = 1) => ev((k) => window.__game.step(k), n);
  // CSS transitions run in real time: let them finish before a screenshot or an audit. With
  // ?manual=1 nothing asks for animation frames, and headless Chromium then stops ticking the
  // document timeline, so real frames are requested while waiting.
  const frames = (ms) => ev((t) => new Promise((resolve) => {
    const t0 = performance.now();
    const tick = () => (performance.now() - t0 > t ? resolve() : requestAnimationFrame(tick));
    requestAnimationFrame(tick);
  }), ms);
  // …and then until every finite transition or animation has ended (at most 4 s).
  const settle = async (n = 6) => {
    await step(n);
    await frames(300);
    await ev(() => new Promise((resolve) => {
      const t0 = performance.now();
      const busy = () => document.getAnimations().some((a) => a.playState === 'running' && Number.isFinite(a.effect.getComputedTiming().endTime));
      const tick = () => (!busy() || performance.now() - t0 > 4000 ? resolve() : requestAnimationFrame(tick));
      requestAnimationFrame(tick);
    }));
    await step(1);
  };
  const click = async (sel) => {
    const ok = await ev((s) => {
      const b = [...document.querySelectorAll(s)].find((e) => e.getBoundingClientRect().width > 0 && getComputedStyle(e).visibility !== 'hidden');
      if (b) b.click();
      return !!b;
    }, sel);
    if (!ok) throw new Error(`no visible ${sel}`);
    await step(2);
  };
  const shot = async (name, opts = {}) => {
    await ev(() => window.__game.render());
    if (outDir) await page.screenshot({ path: path.join(outDir, `${tag}-${name}.png`), timeout: 180000 });
    const problems = await ev(audit, { fight: !!opts.fight, hand: 'right' });
    const errs = page.errors.concat(await ev(() => window.__game.errors.slice()));
    if (strict) check(problems.length === 0, `${tag} ${name}: layout${problems.length ? `\n      ${problems.join('\n      ')}` : ''}`);
    else for (const p of problems) console.log(`note ${tag} ${name}: ${p}`);
    if (errs.length) check(false, `${tag} ${name}: page errors\n      ${errs.join('\n      ')}`);
    page.errors.length = 0;
  };
  return { ev, step, frames, settle, click, shot };
}

async function portraitPass(size) {
  const tag = size.name;
  const page = await phone({
    width: size.width,
    height: size.height,
    seed: { safetyAccepted: true, hiltSafetyAccepted: true, hiltAgogeDone: false },
    query: '&control=motion&grip=hilt',
  });
  const { ev, step, frames, settle, click, shot } = helpers(page, tag);
  if (size.insets) {
    // Android passes the cutout and the gesture bar as --sai-* (platform.js).
    await ev((i) => {
      document.documentElement.style.setProperty('--sai-top', `${i.top}px`);
      document.documentElement.style.setProperty('--sai-bottom', `${i.bottom}px`);
    }, size.insets);
  }
  await settle(30);
  check(await ev(() => window.__game.ctx.mode === 'title' && document.documentElement.dataset.grip === 'hilt' && window.__game.ctx.input.grip === 'hilt'),
    `${tag} boots to the title in the hilt grip (data-grip, ctx.input.grip)`);
  check(await ev(() => window.__orient.some(([lock, o]) => !lock && o === 'portrait')), `${tag} the title requests portrait (false, 'portrait')`);
  check(await ev(() => !!document.querySelector('.mn-title.is-motion .mn-grippick') && getComputedStyle(document.querySelector('.mn-grippick')).display !== 'none'),
    `${tag} the title shows the Grip segment with Motion on`);
  await shot('title');

  // The update banner (title only), available and downloading.
  await ev(() => {
    Object.assign(window.__upd, { state: 'available', available: 42, name: '1.42' });
    window.dispatchEvent(new CustomEvent('appupdate', { detail: JSON.stringify(window.__upd) }));
  });
  await settle();
  check(await ev(() => document.querySelector('#app-update').classList.contains('is-on')), `${tag} the update banner shows on the title`);
  await shot('update');
  await ev(() => {
    Object.assign(window.__upd, { state: 'downloading', progress: 0.42 });
    window.dispatchEvent(new CustomEvent('appupdate', { detail: JSON.stringify(window.__upd) }));
  });
  await settle();
  await shot('update-downloading');
  await ev(() => {
    Object.assign(window.__upd, { state: 'idle', available: 0 });
    window.dispatchEvent(new CustomEvent('appupdate', { detail: JSON.stringify(window.__upd) }));
  });

  // Settings (Controls): the Grip and Auto-turn rows; Gyro look greyed; the hilt sensitivity.
  await click('.mn-title [data-act="settings"]');
  await settle();
  check(await ev(() => {
    const row = (k) => document.querySelector(`.mn-settings .mn-field[data-key="${k}"]`);
    const s = window.__game.ctx.settings;
    return row('grip') && row('grip').querySelector('[data-v="hilt"]').classList.contains('is-on')
      && !row('lockAuto').classList.contains('is-off') && row('gyroLook').classList.contains('is-off')
      && /locks onto your foe/.test(row('gyroLook').textContent)
      && row('sensitivity').querySelector('output').textContent === `${(+s.sensitivityHilt).toFixed(2)}×`;
  }), `${tag} Settings: Grip (hilt on), Auto-turn live, Gyro look greyed with the hilt hint, the hilt sensitivity`);
  await shot('settings');
  await ev(() => {
    const b = document.querySelector('.mn-settings .mn-body');
    b.scrollTop = b.scrollHeight;
  });
  await settle(2);
  await shot('settings-end');
  await click('.mn-settings [data-act="back"]');

  // How to play, Motion: the hilt cards first, the window set behind the switch.
  await click('.mn-title [data-act="howto"]');
  await settle();
  check(await ev(() => document.querySelector('.mn-howset[data-set="hilt"]').classList.contains('is-on') && !document.querySelector('.mn-howset[data-set="window"]').classList.contains('is-on')),
    `${tag} How to play opens on the hilt grip's cards`);
  await shot('howto');
  await click('.mn-howto [data-act="back"]');

  // First Motion choice once the hilt grip is released (the menus' test hook stands in for HILT_RELEASED):
  // the chooser, the hilt safety notice, then motion on in the hilt grip.
  await ev(() => {
    const c = window.__game.ctx;
    Object.assign(c.settings, { control: 'touch', gripChosen: false, safetyAccepted: false, hiltSafetyAccepted: false });
    c.applyControls();
    c.menus.forceRelease = true;
    c.menus.show('title');
  });
  await step(2);
  await click('.mn-title .mn-seg [data-v="motion"]');
  await settle();
  check(await ev(() => document.querySelector('.mn-grip').classList.contains('is-on')), `${tag} first Motion → the grip chooser`);
  await shot('grip');
  await click('.mn-grip [data-act="grip-hilt"]');
  await settle();
  check(await ev(() => document.querySelector('.mn-safety').classList.contains('is-on') && document.querySelector('.mn-safety').classList.contains('is-hilt')
    && /strongly recommended/.test(document.querySelector('.mn-safe-hilt').textContent)), `${tag} Hilt → the hilt safety notice (strap "strongly recommended")`);
  await shot('safety');
  await click('.mn-safety [data-act="safety-accept"]');
  for (let i = 0; i < 20 && (await ev(() => window.__game.ctx.settings.control)) !== 'motion'; i++) await step(3);
  await settle();
  check(await ev(() => {
    const s = window.__game.ctx.settings;
    return s.control === 'motion' && s.grip === 'hilt' && s.gripChosen === true && s.safetyAccepted && s.hiltSafetyAccepted
      && window.__game.ctx.input.grip === 'hilt' && /Hilt grip on/.test(document.querySelector('.mn-toast').textContent);
  }), `${tag} accept → motion on in the hilt grip, both notices accepted, "Hilt grip on" toast`);
  await shot('title-hilt-on');
  await ev(() => {
    window.__game.ctx.menus.forceRelease = false;
  });

  // The first hilt Fight before the hilt Agoge: "The Agoge first / Fight now".
  await click('.mn-title [data-act="fight"]');
  await settle();
  check(await ev(() => document.querySelector('.mn-agogefirst').classList.contains('is-on') && window.__game.ctx.mode === 'title'), `${tag} first hilt Fight → the Agoge-first notice`);
  await shot('agogefirst');
  await click('.mn-agogefirst [data-act="fight-now"]');
  await step(30);
  check(await ev(() => window.__game.ctx.mode === 'battle' && window.__orient.some(([lock, o]) => lock && o === 'portrait')), `${tag} Fight now → battle, locked portrait`);

  // Battle: three foes at 4 m (40° left, ahead, 35° right); the lock takes the one ahead.
  await ev(() => {
    const g = window.__game;
    const at = (deg) => [4 * Math.sin((deg * Math.PI) / 180), -4 * Math.cos((deg * Math.PI) / 180)];
    window.__foes = [at(-40), at(0), at(35)].map(([x, z]) => g.spawn('immortal', x, z));
  });
  for (let i = 0; i < 30 && !(await ev(() => !!window.__game.ctx.player.lockTarget)); i++) await step(6);
  await settle(20);
  const lockInfo = await ev(() => {
    const c = window.__game.ctx;
    const t = c.player.lockTarget;
    if (!t) return null;
    const v = new c.THREE.Vector3(t.pos.x, t.pos.y + 0.62 * (t.height || 1.75), t.pos.z).project(c.camera);
    const want = { x: (v.x + 1) * 0.5 * innerWidth, y: (1 - v.y) * 0.5 * innerHeight };
    const el = document.querySelector('.hud-lock');
    const m = /translate3d\(([-\d.]+)px,\s*([-\d.]+)px/.exec(el.style.transform || '');
    return { ahead: t === window.__foes[1], shown: el.classList.contains('is-shown'), dx: m ? +m[1] - want.x : NaN, dy: m ? +m[2] - want.y : NaN, focus: document.querySelector('.hud-focus').classList.contains('is-shown') };
  });
  check(!!lockInfo && lockInfo.shown && !lockInfo.focus, `${tag} the lock marker shows (the focus marker does not) ${JSON.stringify(lockInfo)}`);
  check(!!lockInfo && Math.hypot(lockInfo.dx, lockInfo.dy) <= 12, `${tag} the lock marker sits within 12 px of the target's projected point`);
  await shot('battle-guard', { fight: true });
  await ev(() => {
    const c = window.__game.ctx;
    c.input.shieldHeld = true;
    c.input.shieldPressedAt = c.time.gnow;
  });
  await settle(20);
  await shot('battle-shield', { fight: true });
  await ev(() => {
    window.__game.ctx.input.shieldHeld = false;
  });
  // A strike at the lock: the last-strike chip.
  await ev(() => window.__game.action({ type: 'slash', dir: 0, power: 0.9 }));
  await step(12);
  await frames(250);
  const chip = await ev(() => document.querySelector('.hud-lock-res').textContent);
  check(/^(KILL|HIT|GUARDED|MISS)$/.test(chip), `${tag} the last-strike chip reads ${chip}`);
  await shot('battle-lock', { fight: true });
  // The closing ring on a wind-up at the player.
  let ring = false;
  for (let i = 0; i < 60 && !ring; i++) {
    await step(5);
    ring = await ev(() => !!document.querySelector('.hud-chev.is-shown:not(.is-off)'));
  }
  check(ring, `${tag} a wind-up at the player shows the closing ring`);
  if (ring) {
    await frames(150);
    await shot('battle-ring', { fight: true });
  }

  // The volley warning with its spoken line, then subtitles with Greek and a combo.
  await ev(() => {
    const c = window.__game.ctx;
    c.combat.volley({ count: 60, duration: 1, warn: 2.5 });
    c.events.emit('voice', { speaker: 'Leonidas', text: 'Shields up!', greek: 'ΑΣΠΙΔΑΣ ΠΡΟΒΑΛΕΣΘΕ' });
  });
  await settle(6);
  await shot('battle-volley', { fight: true });
  await step(400);
  await ev(() => {
    const c = window.__game.ctx;
    c.events.emit('voice', { speaker: 'Leonidas', text: 'Spartans! Prepare for glory!', greek: 'ΣΠΑΡΤΙΑΤΑΙ' });
    c.events.emit('voice', { speaker: 'Dienekes', text: 'Then we will fight in the shade. Their arrows will not reach us under our shields.', greek: 'ΟΥΚΟΥΝ ΥΠΟ ΣΚΙΗΙ ΚΑΙ ΟΥΚ ΕΝ ΗΛΙΩΙ ΜΑΧΕΣΟΜΕΘΑ' });
    c.events.emit('combo', { id: 'fury', name: 'Fury of Lakedaimon', bonus: 180, seq: [1, 2, 3] });
  });
  await settle(4);
  await shot('battle-subs', { fight: true });
  // The spear in hand: the reticle and the coach toast.
  await ev(() => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }));
  await settle(40);
  await shot('battle-spear', { fight: true });

  // The respite row: label left of centre, READY right of it, out of the thumb's arc.
  await ev(() => {
    const c = window.__game.ctx;
    const w = c.waves;
    window.__realWaves = w;
    c.waves = Object.create(w, { inRespite: { value: true }, respiteLeft: { value: 12 }, canReady: { value: true }, state: { value: 'respite' } });
    c.events.emit('waveCleared', { day: 1, wave: 2, title: 'They fall back', subtitle: 'Wave 2 of 4', seconds: 3 });
    c.events.emit('respite', { day: 1, wave: 3, title: 'Respite', subtitle: 'Bind your wounds. Take up fresh spears.', seconds: 18 });
  });
  await settle(8);
  check(await ev(() => document.querySelector('.hud-ready').classList.contains('is-shown')), `${tag} READY shows in the respite`);
  await shot('battle-respite', { fight: true });
  await ev(() => {
    window.__game.ctx.waves = window.__realWaves;
  });

  // Pause, then game over.
  await ev(() => window.__game.ctx.setMode('paused'));
  await settle();
  await shot('pause');
  await click('.mn-pause [data-act="resume"]');
  await ev(() => window.__game.ctx.events.emit('gameOver', { day: 1, wave: 3, kills: 23, score: 4210 }));
  await settle();
  check(await ev(() => window.__game.ctx.mode === 'gameover'), `${tag} game over`);
  await shot('gameover');

  // The Agoge: the grip step, then the lock, perfect and spear steps (as far as training.js has them).
  await ev(() => window.__game.startTraining());
  await settle(20);
  await shot('agoge-grip', { fight: true });
  const ids = await ev(async () => {
    const m = await import('./js/ui/training.js');
    const hilt = window.__game.ctx.input.grip === 'hilt' && m.HILT_LOCK_STEP;
    return (hilt ? [...m.STEPS.slice(0, 6), m.HILT_LOCK_STEP, ...m.STEPS.slice(6)] : m.STEPS).map((s) => s.id);
  });
  for (const id of ['lock', 'perfect', 'spear']) {
    const want = ids.indexOf(id);
    if (want < 0) {
      console.log(`note ${tag} the Agoge has no '${id}' step yet (training.js, P9)`);
      continue;
    }
    for (let i = 0; i < 20 && (await ev(() => window.__game.ctx.training.stepIndex)) < want; i++) await click('#training [data-act="skip"]');
    await settle(30);
    if (id === 'perfect') {
      let r = false;
      for (let i = 0; i < 80 && !r; i++) {
        await step(5);
        r = await ev(() => !!document.querySelector('.hud-chev.is-shown:not(.is-off)'));
      }
      check(r, `${tag} the Agoge perfect step shows the closing ring`);
      await frames(150);
    }
    await shot(`agoge-${id}`, { fight: true });
  }
  await ev(() => window.__game.ctx.setMode('title'));
  const arity = await ev(() => window.__arity);
  check(arity.length === 0, `${tag} no bridge arity errors ${JSON.stringify(arity)}`);
  await page.context().close();
}

async function rotatePass() {
  const page = await phone({ width: 915, height: 412, seed: { safetyAccepted: true, hiltSafetyAccepted: true, hiltAgogeDone: true }, query: '&control=motion&grip=hilt' });
  // The pages behind the prompt are the landscape layout (notes only); the prompt itself is checked here.
  const { ev, step, settle, shot } = helpers(page, 'rotate', false);
  const promptOk = () => ev(() => {
    const b = document.querySelector('#rotate [data-act="rotate-window"]').getBoundingClientRect();
    const t = document.querySelector('#rotate .mn-rotate-t');
    return b.width >= 44 && b.height >= 44 && b.left >= 0 && b.right <= innerWidth && b.bottom <= innerHeight
      && parseFloat(getComputedStyle(t).fontSize) >= 11 && t.getBoundingClientRect().right <= innerWidth;
  });
  await step(20);
  check(!(await ev(() => document.getElementById('rotate').classList.contains('is-on'))), 'rotate: not before 600 ms');
  await settle(30);
  check(await ev(() => document.getElementById('rotate').classList.contains('is-on')), 'rotate: a landscape display with the hilt grip shows the prompt on the title');
  check(await promptOk(), 'rotate: the prompt text and its button fit (≥ 44 px, ≥ 11 px type)');
  await shot('title');
  await ev(() => window.__game.startBattle(1));
  await settle(50);
  check(await ev(() => window.__game.ctx.mode === 'paused' && document.getElementById('rotate').classList.contains('is-on')), 'rotate: in battle it pauses');
  await shot('battle');
  await ev(() => document.querySelector('#rotate [data-act="rotate-window"]').click());
  await settle(4);
  check(await ev(() => !document.getElementById('rotate').classList.contains('is-on') && window.__game.ctx.settings.grip === 'window' && window.__game.ctx.input.grip === 'window'),
    'rotate: "Use the window grip" switches the grip and clears the prompt');
  await shot('window-paused');
  await page.context().close();
}

async function landscapePass() {
  const tag = '915x412';
  const page = await phone({ width: 915, height: 412, seed: { safetyAccepted: true, grip: 'window', gripChosen: true }, query: '&control=motion' });
  const { ev, step, settle, click, shot } = helpers(page, tag, false);
  await settle(30);
  check(await ev(() => document.documentElement.dataset.grip === 'window' && !document.getElementById('rotate').classList.contains('is-on')), `${tag} the window grip: no rotate prompt`);
  await shot('title');
  await ev(() => {
    Object.assign(window.__upd, { state: 'available', available: 42, name: '1.42' });
    window.dispatchEvent(new CustomEvent('appupdate', { detail: JSON.stringify(window.__upd) }));
  });
  await settle();
  await shot('update');
  await ev(() => {
    Object.assign(window.__upd, { state: 'idle', available: 0 });
    window.dispatchEvent(new CustomEvent('appupdate', { detail: JSON.stringify(window.__upd) }));
  });
  await click('.mn-title [data-act="settings"]');
  await settle();
  check(await ev(() => document.querySelector('.mn-settings .mn-field[data-key="lockAuto"]').classList.contains('is-off')
    && !document.querySelector('.mn-settings .mn-field[data-key="gyroLook"]').classList.contains('is-off')), `${tag} Settings: Auto-turn greyed, Gyro look live in the window grip`);
  await shot('settings');
  await click('.mn-settings [data-act="back"]');
  await click('.mn-title [data-act="howto"]');
  await settle();
  await shot('howto');
  await click('.mn-howto [data-act="back"]');
  await click('.mn-title [data-act="fight"]');
  await step(30);
  check(await ev(() => window.__game.ctx.mode === 'battle'), `${tag} Fight in the window grip goes straight to battle`);
  await ev(() => {
    const g = window.__game;
    g.spawn('immortal', 0, -2.2);
  });
  await settle(60);
  check(await ev(() => !document.querySelector('.hud-lock').classList.contains('is-shown')), `${tag} no lock marker in the window grip`);
  await shot('battle');
  await ev(() => {
    const c = window.__game.ctx;
    c.combat.volley({ count: 60, duration: 1, warn: 2.5 });
    c.events.emit('voice', { speaker: 'Leonidas', text: 'Shields up!', greek: 'ΑΣΠΙΔΑΣ ΠΡΟΒΑΛΕΣΘΕ' });
  });
  await settle(6);
  await shot('battle-volley');
  await step(400);
  await ev(() => {
    const c = window.__game.ctx;
    c.events.emit('voice', { speaker: 'Dienekes', text: 'Then we will fight in the shade. Their arrows will not reach us under our shields.', greek: 'ΟΥΚΟΥΝ ΥΠΟ ΣΚΙΗΙ ΚΑΙ ΟΥΚ ΕΝ ΗΛΙΩΙ ΜΑΧΕΣΟΜΕΘΑ' });
    c.events.emit('combo', { id: 'fury', name: 'Fury of Lakedaimon', bonus: 180, seq: [1, 2, 3] });
  });
  await settle(4);
  await shot('battle-subs');
  await ev(() => window.__game.ctx.setMode('paused'));
  await settle();
  await shot('pause');
  await click('.mn-pause [data-act="resume"]');
  await ev(() => window.__game.ctx.events.emit('gameOver', { day: 1, wave: 3, kills: 23, score: 4210 }));
  await settle();
  await shot('gameover');
  await page.context().close();
}

if (failures.length) {
  console.log(`\n${failures.length} FAILED`);
  process.exitCode = 1;
} else {
  console.log('\nall checks passed');
}
