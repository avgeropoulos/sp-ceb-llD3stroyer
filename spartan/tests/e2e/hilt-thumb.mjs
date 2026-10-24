// The hilt grip's thumb input on the real game (docs-hilt-grip.md §D, §H.2 #3): ui/input.js's
// reject strips, the shield on a thumb press, horizontal thumb strokes that switch the lock-on
// target once the phone is calm, the weapon button at the top (fires on a tap's release), and the
// window grip left as it was. Touches go through CDP Input.dispatchTouchEvent at 412×915 (mobile).
//  - Timed strokes carry explicit CDP timestamps, so their sample spacing is exact however slowly
//    SwiftShader acks the input; node paces them in real time, and while they play the page steps
//    the game every 16 ms (rendering skipped), as the real frame loop would.
//  - Swings are synthesized (hold 'hilt') and injected through platform.motion, which releases
//    them in real time, so a stroke and a swing line up on one clock.
// Usage: node spartan/tests/e2e/hilt-thumb.mjs
import { launch } from './lib.mjs';
import { INPUT, Trail, findThumbStroke, inHiltStrip, HILT_TIP, HILT_TIP_FINGER } from '../../src/main/assets/game/js/ui/input.js';

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sleepUntil = async (at) => {
  const d = at - Date.now();
  if (d > 0) await sleep(d);
};
const W = 412;
const H = 915;

// ------------------------------------------------------------------ pure helpers (Node)
{
  // A stroke of `dx`, `dy` px over `ms`, sampled every `gap` ms.
  const trail = (dx, dy, ms, gap = 20) => {
    const tr = new Trail();
    const n = Math.round(ms / gap);
    for (let i = 0; i <= n; i++) tr.push(1000 + i * gap, 200 + (dx * i) / n, 500 + (dy * i) / n);
    return tr;
  };
  const out = {};
  const r = findThumbStroke(trail(60, 0, 120), out);
  check('findThumbStroke: 60 px / 120 ms right → dir +1 from the first sample', r && r.dir === 1 && r.t0 === 1000 && r.t1 === 1120, JSON.stringify(r));
  check('findThumbStroke: left → dir −1', findThumbStroke(trail(-60, 0, 120), {})?.dir === -1);
  check('findThumbStroke: 43 px is short, 44 px enough', !findThumbStroke(trail(43, 0, 60), {}) && !!findThumbStroke(trail(44, 0, 60), {}));
  const two = (dt) => {
    const tr = new Trail();
    tr.push(1000, 200, 500);
    tr.push(1000 + dt, 300, 500);
    return tr;
  };
  check('findThumbStroke: a 0.2 px/ms drag gives none; 100 px counts within 220 ms, not over 230 ms',
    !findThumbStroke(trail(80, 0, 400), {}) && !!findThumbStroke(two(210), {}) && !findThumbStroke(two(230), {}));
  const tan = (deg) => Math.tan((deg * Math.PI) / 180);
  check('findThumbStroke: 35° and 45° off horizontal switch, 60° does not, straight up does not',
    !!findThumbStroke(trail(60, -60 * tan(35), 120), {}) && !!findThumbStroke(trail(60, -60 * tan(45), 120), {})
      && !findThumbStroke(trail(60, -60 * tan(60), 120), {}) && !findThumbStroke(trail(0, -80, 150), {}));
  // pushSpaced keeps ≥ 220 ms of a 240 Hz stroke in the 24-sample trail.
  const tr = new Trail();
  for (let i = 0; i <= 60; i++) tr.pushSpaced(1000 + i * 4.17, 200 + i * 1.5, 500, INPUT.strokeGapMs);
  const span = tr.t[tr.n - 1] - tr.t[0];
  check('Trail.pushSpaced: a 240 Hz trail spans ≥ 220 ms', span >= 220 && tr.n <= 24, `span ${span.toFixed(0)} ms, n ${tr.n}`);
  const s = (x, y, left = false, fs = 44) => inHiltStrip(x, y, W, H, left, fs);
  check('inHiltStrip: top 12, bottom 24, thumb side 20 px (right hand)', s(200, 11) && !s(200, 12) && s(200, H - 23) && !s(200, H - 25) && s(W - 19, 500) && !s(W - 21, 500));
  check('inHiltStrip: finger side 44 px over the lower 60%, 16 px above', s(43, 0.7 * H) && !s(45, 0.7 * H) && s(15, 0.3 * H) && !s(17, 0.3 * H));
  check('inHiltStrip: hiltFingerStrip 80, and mirrored for the left hand', s(79, 0.7 * H, false, 80) && !s(81, 0.7 * H, false, 80)
    && s(19, 500, true) && s(W - 43, 0.7 * H, true) && !s(45, 0.7 * H, true));
}

// ------------------------------------------------------------------ real game
const l = await launch();
try {
  const page = await l.newPage({ width: W, height: H, mobile: true, grip: 'hilt' });
  // A loaded headless SwiftShader can stall boot steps: keep index.html's boot watchdog away.
  await page.addInitScript(() => { window.__booted = true; });
  await page.goto(`${l.game}?manual=1&seed=5&quality=low&grip=hilt`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  // Headless Chromium has no gyroscope: menus' support probe (1.5 s after the title shows) would
  // reset a motion setting to touch. Let it finish first, then choose motion.
  await sleep(2000);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    ctx.renderer.render = () => {}; // the controls and the HUD are DOM; skip SwiftShader frames
    ctx.settings.control = 'motion';
    ctx.settings.grip = 'hilt';
    ctx.settings.leftHanded = false;
    ctx.settings.hiltFingerStrip = 44;
    ctx.applyControls();
    g.startBattle(1);
    ctx.waves.stop();
    ctx.ai.clear();
    ctx.player.pos.set(0, 0, 0);
    ctx.player.yaw = 0;
    // Three dummies 4 m away: 40° left, ahead, 35° right (forward is −z, right is +x).
    const d = Math.PI / 180;
    ctx.ai.spawnDummy(-4 * Math.sin(40 * d), -4 * Math.cos(40 * d));
    ctx.ai.spawnDummy(0, -4);
    ctx.ai.spawnDummy(4 * Math.sin(35 * d), -4 * Math.cos(35 * d));
    const now = () => performance.timeOrigin + performance.now(); // epoch ms, the clock CDP timestamps use
    window.__acts = [];
    const push = ctx.actions.push;
    ctx.actions.push = (a) => {
      window.__acts.push({ type: a.type, dir: a.dir, src: a.src, t: now() });
      return push(a);
    };
    window.__ev = [];
    ctx.events.on('gesture', (e) => window.__ev.push({ n: 'gesture', type: e.type, t: now() }));
    ctx.events.on('lockOn', (e) => window.__ev.push({ n: 'lockOn', reason: e.reason, x: e.target ? +e.target.pos.x.toFixed(2) : null, t: now() }));
    // The real-time frame loop for timed strokes: a step every 16 ms, and the phone's peak rate.
    window.__mMax = 0;
    window.__loop = 0;
    window.__run = (on) => {
      clearInterval(window.__loop);
      window.__loop = on ? setInterval(() => {
        g.step(1, 1 / 60);
        window.__mMax = Math.max(window.__mMax, ctx.gestures.debug.m || 0);
      }, 16) : 0;
    };
    window.__now = now;
  });
  const step = (n = 1) => ev((k) => window.__game.step(k, 1 / 60), n);
  await step(30);
  const cdp = await page.context().newCDPSession(page);
  const touch = (type, pts, at) => cdp.send('Input.dispatchTouchEvent', Object.assign(
    { type, touchPoints: pts.map((p) => ({ id: p.id ?? 1, x: p.x, y: p.y, radiusX: p.r ?? 4, radiusY: p.r ?? 4, force: 1 })) },
    at === undefined ? {} : { timestamp: at / 1000 },
  ));
  const st = () => ev(() => {
    const c = window.__game.ctx;
    return { held: c.input.shieldHeld, pressedAt: c.input.shieldPressedAt, gnow: c.time.gnow, ptrs: c.inputCtl.shieldPointers, last: Object.assign({}, c.inputCtl.lastShieldDown), mode: c.mode };
  });
  const lockX = () => ev(() => {
    const t = window.__game.ctx.player.lockTarget;
    return t ? +t.pos.x.toFixed(2) : null;
  });
  const clearLogs = () => ev(() => { window.__acts.length = 0; window.__ev.length = 0; window.__mMax = 0; });
  const acts = (type) => ev((ty) => window.__acts.filter((a) => !ty || a.type === ty), type);
  const evs = (n) => ev((k) => window.__ev.filter((e) => e.n === k), n);
  const center = (sel) => ev((s) => {
    const r = document.querySelector(s).getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height, top: r.top, left: r.left, right: r.right };
  }, sel);
  // A timed stroke from (x0, y0) by (dx, dy) over `ms` in `moves` samples, starting at epoch `at`.
  // Returns the epoch times of its first sample and of each move.
  async function stroke(x0, y0, dx, dy, ms, { moves = 6, at = Date.now() + 40, lift = true } = {}) {
    const times = [];
    await sleepUntil(at);
    await touch('touchStart', [{ x: x0, y: y0 }], at);
    for (let i = 1; i <= moves; i++) {
      const ti = at + (ms * i) / moves;
      times.push(ti);
      await sleepUntil(ti);
      await touch('touchMove', [{ x: x0 + (dx * i) / moves, y: y0 + (dy * i) / moves }], ti);
    }
    if (lift) {
      const te = at + ms + 16;
      await sleepUntil(te);
      await touch('touchEnd', [], te);
    }
    return { at, times };
  }
  const PAD = { x: Math.round(W * 0.62), y: Math.round(H * 0.6) }; // where the thumb pad rests (§1 hand model)

  // ------------------------------------------------------------------ layout
  const cls = await ev(() => document.getElementById('controls').className);
  check('hilt grip → #controls has ctl-motion and ctl-hilt (not ctl-touch)', /\bctl-motion\b/.test(cls) && /\bctl-hilt\b/.test(cls) && !/\bctl-touch\b/.test(cls), cls);
  const swap = await center('.ctl-swap');
  const pause = await center('.ctl-pause');
  check('weapon button at the top right, pause at the top left (off hand, out of the thumb\'s arc)',
    swap.top >= 0 && swap.y < 0.15 * H && swap.x > 0.8 * W && pause.y < 0.15 * H && pause.x < 0.2 * W && swap.w >= 44 && pause.w >= 44,
    `swap ${swap.x.toFixed(0)},${swap.y.toFixed(0)} ${swap.w}px; pause ${pause.x.toFixed(0)},${pause.y.toFixed(0)}`);
  const lock0 = await lockX();
  check('the lock starts on the dummy ahead', lock0 === 0, `lock x ${lock0}`);

  // ------------------------------------------------------------------ shield
  const s0 = await st();
  await touch('touchStart', [PAD]);
  const s1 = await st();
  check('a thumb press raises the shield in the same step (shieldPressedAt = gnow, no frame stepped)',
    s1.held === true && s1.gnow === s0.gnow && Math.abs(s1.pressedAt - s1.gnow) < 1e-9, `held ${s1.held}, pressedAt ${s1.pressedAt}, gnow ${s0.gnow} → ${s1.gnow}`);
  check('inputCtl.shieldPointers = 1 and lastShieldDown is where it started', s1.ptrs === 1 && Math.abs(s1.last.x - PAD.x) < 1 && Math.abs(s1.last.y - PAD.y) < 1 && s1.last.t >= 0,
    JSON.stringify(s1.last));
  await touch('touchEnd', []);
  const s2 = await st();
  check('lifting the thumb lowers it at once', s2.held === false && s2.ptrs === 0);

  // ------------------------------------------------------------------ reject strips
  async function raises(x, y, r = 4) {
    await touch('touchStart', [{ x, y, r }]);
    const h = (await st()).held;
    await touch('touchEnd', []);
    return h;
  }
  const yLow = Math.round(0.7 * H);
  const strips = {
    top: await raises(PAD.x, 6),
    bottom: await raises(PAD.x, H - 12),
    thumb: await raises(W - 10, PAD.y),
    palm: await raises(PAD.x, PAD.y, 41),
  };
  check('top, bottom and thumb-side strips and a 41 px contact radius do not raise the shield', !strips.top && !strips.bottom && !strips.thumb && !strips.palm, JSON.stringify(strips));
  const finger = [await raises(20, yLow), await raises(30, yLow), await raises(40, yLow)];
  check('touches 20–40 px from the finger-side edge in the lower part do not raise it', finger.every((h) => !h), JSON.stringify(finger));
  const open = [await raises(52, yLow), await raises(24, Math.round(0.25 * H)), await raises(PAD.x, PAD.y)];
  check('…while 52 px in, 24 px in above the lower part, and the thumb pad do', open.every(Boolean), JSON.stringify(open));
  await ev(() => { window.__game.ctx.settings.hiltFingerStrip = 80; });
  const wide = [await raises(60, yLow), await raises(92, yLow)];
  check('hiltFingerStrip = 80: a touch 60 px in does not raise it, 92 px in does', !wide[0] && wide[1], JSON.stringify(wide));
  await ev(() => { window.__game.ctx.settings.hiltFingerStrip = 44; window.__game.ctx.settings.leftHanded = true; });
  await step(1);
  const mirrored = { thumbLeft: await raises(10, PAD.y), fingerRight: await raises(W - 30, yLow), oldFinger: await raises(30, yLow) };
  const leftCls = await ev(() => document.getElementById('controls').className);
  check('left hand (ctl-left): the strips mirror', /\bctl-left\b/.test(leftCls) && !mirrored.thumbLeft && !mirrored.fingerRight && mirrored.oldFinger, JSON.stringify(mirrored));
  await ev(() => { window.__game.ctx.settings.leftHanded = false; });
  await step(1);

  // ------------------------------------------------------------------ multi-touch
  await touch('touchStart', [{ id: 1, ...PAD }]);
  await touch('touchStart', [{ id: 1, ...PAD }, { id: 2, x: Math.round(W * 0.5), y: Math.round(H * 0.3) }]);
  const m2 = await st();
  await touch('touchEnd', [{ id: 2, x: Math.round(W * 0.5), y: Math.round(H * 0.3) }]);
  const m1 = await st();
  await touch('touchEnd', []);
  const m0 = await st();
  check('two pointers hold the shield until both lift', m2.held && m2.ptrs === 2 && m1.held && m1.ptrs === 1 && !m0.held && m0.ptrs === 0,
    `${m2.ptrs}/${m2.held} → ${m1.ptrs}/${m1.held} → ${m0.ptrs}/${m0.held}`);

  // ------------------------------------------------------------------ thumb strokes (real-time loop)
  await ev(() => window.__run(true));
  // The motion pipeline starts from rest: a still guard first.
  await ev(() => window.__game.injectMotion(window.__game.synth([{ kind: 'idle', duration: 1.2 }], { hold: 'hilt', seed: 2 })));
  await sleep(1400);

  await clearLogs();
  const r1 = await stroke(PAD.x, PAD.y, 60, 0, 120, { lift: false });
  const heldMid = (await st()).held;
  await sleep(300);
  await touch('touchEnd', []);
  await sleep(100);
  let sw = await acts('switchTarget');
  // |dx| ≥ 44 px at the 5th move (50 px, 100 ms): the stroke is classified on that sample.
  const delay = sw.length ? sw[0].t - r1.times[4] : NaN;
  check('a 60 px / 120 ms swipe right → exactly one switchTarget +1 (touch), the shield up throughout',
    sw.length === 1 && sw[0].dir === 1 && sw[0].src === 'touch' && heldMid, `${JSON.stringify(sw.map((a) => a.dir))}, held ${heldMid}`);
  check('…committed ≈ 100 ms after the stroke is classified, not before', delay >= 99 && delay < 400, `delay ${delay.toFixed(0)} ms`);
  await sleep(300);
  check('…and the lock moves to the right dummy (lockOn switch)', (await lockX()) > 1 && (await evs('lockOn')).some((e) => e.reason === 'switch' && e.x > 1),
    JSON.stringify(await evs('lockOn')));

  await clearLogs();
  await stroke(Math.round(W * 0.75), PAD.y, -60, 0, 120);
  await sleep(400);
  sw = await acts('switchTarget');
  check('a swipe left → exactly one switchTarget −1, the lock back on the dummy ahead', sw.length === 1 && sw[0].dir === -1 && (await lockX()) === 0,
    `${JSON.stringify(sw.map((a) => a.dir))}, lock x ${await lockX()}`);

  const tan = (deg) => Math.tan((deg * Math.PI) / 180);
  const angled = [];
  for (const [deg, dir] of [[35, 1], [45, -1], [60, 1]]) {
    await clearLogs();
    const x0 = dir > 0 ? PAD.x - 30 : PAD.x + 30;
    await stroke(x0, PAD.y, 60 * dir, -60 * tan(deg), 120);
    await sleep(350);
    angled.push((await acts('switchTarget')).map((a) => a.dir));
  }
  check('60 px across at 35° and 45° off horizontal also switch; at 60° it does not', angled[0].join() === '1' && angled[1].join() === '-1' && angled[2].length === 0,
    JSON.stringify(angled));

  await clearLogs();
  await stroke(PAD.x - 40, PAD.y, 80, 0, 400, { moves: 10 });
  await sleep(300);
  check('a 0.2 px/ms drag gives no switch', (await acts('switchTarget')).length === 0);

  await clearLogs();
  await stroke(PAD.x, Math.round(H * 0.65), 0, -80, 150, { lift: false });
  const heldFlick = (await st()).held;
  await sleep(250);
  await touch('touchEnd', []);
  await sleep(100);
  const flick = await acts();
  check('an 80 px / 150 ms upward flick does nothing (weapon swap is the top button only), the shield stays up',
    heldFlick && !flick.some((a) => a.type === 'switchWeapon' || a.type === 'switchTarget'), `held ${heldFlick}, ${JSON.stringify(flick.map((a) => a.type))}`);

  // ------------------------------------------------------------------ swings
  // A swipe that starts 30 ms before a hilt swing: the commit waits for the swing's samples.
  await clearLogs();
  const lockBefore = await lockX();
  const tInj = await ev(() => {
    const g = window.__game;
    g.injectMotion(g.synth([{ kind: 'idle', duration: 0.6 }, { kind: 'swing', dir: 0, speed: 9, ret: 1.5 }, { kind: 'idle', duration: 0.8 }], { hold: 'hilt', seed: 3 }));
    return window.__now();
  });
  await stroke(PAD.x, PAD.y, 60, 0, 120, { at: tInj + 600 - 30 });
  await sleep(1200);
  const swingA = { sw: await acts('switchTarget'), slash: (await evs('gesture')).filter((e) => e.type === 'slash'), m: await ev(() => window.__mMax) };
  check('the injected swing really swung the phone (a slash, peak rate > 2.5 rad/s)', swingA.slash.length >= 1 && swingA.m > INPUT.strokeCalm,
    `slashes ${swingA.slash.length}, peak ${swingA.m.toFixed(1)} rad/s`);
  check('a swipe starting 30 ms before the swing gives no switch, silently', swingA.sw.length === 0 && (await lockX()) === lockBefore,
    `${JSON.stringify(swingA.sw)}, lock ${lockBefore} → ${await lockX()}`);
  await sleep(1000); // the swing's slow return to guard (≤ 1.5 rad/s) runs out

  // A swipe 0.4 s after a swing's slash, the phone calm (returning at ≤ 1.5 rad/s): it counts.
  await clearLogs();
  await ev(() => {
    const g = window.__game;
    g.injectMotion(g.synth([{ kind: 'idle', duration: 0.4 }, { kind: 'swing', dir: Math.PI, speed: 9, ret: 1.5 }, { kind: 'idle', duration: 1.0 }], { hold: 'hilt', seed: 4 }));
  });
  let slashT = null;
  for (let i = 0; i < 100 && slashT === null; i++) {
    await sleep(20);
    const sl = (await evs('gesture')).find((e) => e.type === 'slash');
    if (sl) slashT = sl.t;
  }
  const lockB = await lockX();
  if (slashT !== null) await stroke(PAD.x, PAD.y, lockB > 0 ? -60 : 60, 0, 120, { at: slashT + 400 });
  await sleep(500);
  const swB = await acts('switchTarget');
  const gB = (await evs('gesture')).map((e) => e.type);
  check('a swipe 0.4 s after the slash, the phone calm, gives exactly one switch', slashT !== null && swB.length === 1,
    `slash ${slashT !== null}, switches ${JSON.stringify(swB.map((a) => a.dir))}, gestures ${JSON.stringify(gB)}`);
  await sleep(900);
  await ev(() => window.__run(false));

  // ------------------------------------------------------------------ keys
  await clearLogs();
  await page.keyboard.press('KeyX');
  await page.keyboard.press('KeyZ');
  const keys = await acts('switchTarget');
  check('keys X / Z → switchTarget +1 / −1 (src key)', keys.length === 2 && keys[0].dir === 1 && keys[1].dir === -1 && keys.every((a) => a.src === 'key'), JSON.stringify(keys));
  await step(2);

  // ------------------------------------------------------------------ weapon and pause buttons
  await clearLogs();
  await touch('touchStart', [{ x: swap.x, y: swap.y }]);
  const swDown = { held: (await st()).held, n: (await acts('switchWeapon')).length };
  await touch('touchEnd', []);
  const swUp = { held: (await st()).held, acts: await acts('switchWeapon') };
  check('the weapon button never raises the shield and swaps on the tap\'s release',
    !swDown.held && swDown.n === 0 && !swUp.held && swUp.acts.length === 1 && swUp.acts[0].src === 'touch', `down ${JSON.stringify(swDown)}, up ${JSON.stringify(swUp.acts)}`);
  await clearLogs();
  await touch('touchStart', [{ x: swap.x, y: swap.y }]);
  await touch('touchMove', [{ x: swap.x - 90, y: swap.y + 120 }]);
  const swDrag = (await st()).held;
  await touch('touchEnd', []);
  check('sliding off the weapon button cancels the swap, and the shield stays down', (await acts('switchWeapon')).length === 0 && !swDrag);
  await step(20); // let the swap animation settle
  await touch('touchStart', [{ x: pause.x, y: pause.y }]);
  const pDown = await st();
  await touch('touchEnd', []);
  const pUp = await st();
  check('pause never raises the shield; its tap pauses', !pDown.held && pDown.mode === 'battle' && !pUp.held && pUp.mode === 'paused', `${pDown.mode} → ${pUp.mode}`);
  await ev(() => window.__game.ctx.resume());
  await step(2);

  // ------------------------------------------------------------------ the resting-thumb tip
  async function tipFor(x, y) {
    await ev(() => window.__game.ctx.inputCtl.reset());
    await touch('touchStart', [{ x, y }]);
    const text = await ev(() => {
      const g = window.__game;
      for (let i = 0; i < 3; i++) g.ctx.events.emit('gesture', { type: 'slash', dir: 0, power: 0.8 });
      g.step(320, 1 / 60);
      const tip = document.querySelector('.ctl-tip');
      return tip.classList.contains('is-shown') ? tip.querySelector('span').textContent : '';
    });
    await touch('touchEnd', []);
    return text;
  }
  const tipF = await tipFor(64, yLow);
  const tipT = await tipFor(PAD.x, PAD.y);
  check('shield held 5 s through 3 slashes: the tip names a finger on the finger side…', tipF === HILT_TIP_FINGER, tipF);
  check('…and the resting thumb elsewhere', tipT === HILT_TIP, tipT);
  await ev(() => window.__game.ctx.inputCtl.reset());

  // ------------------------------------------------------------------ the window grip is unchanged
  await ev(() => { window.__game.ctx.settings.grip = 'window'; window.__game.ctx.applyControls(); });
  await step(2);
  const wcls = await ev(() => document.getElementById('controls').className);
  const wStrip = await raises(30, yLow);
  const wEdge = await raises(8, yLow);
  await clearLogs();
  await touch('touchStart', [{ x: swap.x, y: swap.y }]);
  const wSwapDown = (await acts('switchWeapon')).length;
  await touch('touchEnd', []);
  await ev(() => window.__run(true));
  await clearLogs();
  await stroke(PAD.x, PAD.y, 60, 0, 120);
  await sleep(300);
  await ev(() => window.__run(false));
  const wSw = (await acts('switchTarget')).length;
  check('window grip: no ctl-hilt, only the 12 px edge strip, the swap fires on press, a thumb swipe switches nothing',
    /\bctl-motion\b/.test(wcls) && !/\bctl-hilt\b/.test(wcls) && wStrip && !wEdge && wSwapDown === 1 && wSw === 0,
    `${wcls}; 30 px ${wStrip}, 8 px ${wEdge}, swap on press ${wSwapDown}, switches ${wSw}`);

  const errors = page.errors.concat(await ev(() => window.__game.errors));
  check('no page errors', errors.length === 0, errors.join(' | ').slice(0, 1500));
} catch (err) {
  check(`script error: ${err && (err.stack || err)}`, false);
} finally {
  await l.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
