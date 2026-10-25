// The Agoge in the hilt grip on the real game (docs-hilt-grip.md §G, §H.2 #4), in portrait.
// Every hilt step is run with synthesized hilt motion (hold 'hilt', rot 0) through the fake Android
// sensor source, real pointer events on the glass, and switchTarget actions for the lock step; each
// step with a demo first plays it (the demo's moves never count). Checked on the way:
//  - the grip step's guard and grip check: a touch held at the finger edge is marked, widens
//    hiltFingerStrip and restarts the check, which then passes 3 s after the glass is clear;
//  - calibration writes sensitivityHilt (the window grip's sensitivity is untouched);
//  - the slow-cut, held-up-swing and over-speed hints; the compact panel and its tap-to-open head row;
//  - the lock step counts a right swipe, then a left one ('none' gives a hint), and its extra
//    dummies are gone afterwards; the spear step locks the far target as the near dummy steps
//    aside, and a spear wind-up reads "Armed";
//  - the label reads "XIII / XIII" at the last step, and finishing sets hiltAgogeDone;
//  - the spear demo locks the far target before its throw; a grip change mid-Agoge moves on from
//    the lock step; the window grip keeps its 12 steps.
// Usage: node spartan/tests/e2e/hilt-agoge.mjs [outDir] [WxH]
//   outDir: a portrait screenshot of each step; WxH: the viewport (412x915, or e.g. 360x800).
// Run it with the shell's cwd outside the repository.
import fs from 'node:fs';
import path from 'node:path';
import { openGame } from './review-motion-lib.mjs';

const outDir = process.argv[2] && process.argv[2] !== '-' ? path.resolve(process.argv[2]) : null;
const [W, H] = (process.argv[3] || '412x915').split('x').map(Number);
if (outDir) fs.mkdirSync(outDir, { recursive: true });
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}

/** Page-side helpers (window.__A), installed after the harness. */
function installAgoge() {
  const R = window.__R;
  const g = window.__game;
  const ctx = g.ctx;
  const PI = Math.PI;
  const $ = (s) => document.querySelector(s);
  const A = {
    opts: { hold: 'hilt', rot: 0 },
    THUMB: { x: 300, y: 600 }, // the thumb's pad on the glass (right hand)
    get tr() {
      return ctx.training;
    },
    idx: () => ctx.training.stepIndex,
    title: () => $('#training .tr-title').textContent,
    num: () => $('#training .tr-num').textContent,
    text: () => $('#training .tr-text').textContent,
    hint: () => ($('#training .tr-hint').classList.contains('is-shown') ? $('#training .tr-hint').textContent : ''),
    pips: () => document.querySelectorAll('#training .tr-pips .is-on').length,
    has: (cls) => $('#training').classList.contains(cls),
    play(script, each) {
      return R.play(script, A.opts, { source: 'android', each });
    },
    moves: () => R.evs('gesture').filter((e) => e.type !== 'overSpeed').map((e) => (e.type === 'slash' ? R.tok(e.dir) : e.type)).join(','),
    /** Advances until the step changes (the 1.3 s praise included). */
    waitNext(idx, max = 240) {
      R.until(() => ctx.training.stepIndex !== idx, max);
      return ctx.training.stepIndex !== idx;
    },
    /**
     * Clicks Demo. training.demo() awaits the synth module, so the demo starts once this
     * evaluate has returned: demoRun() (a later call) plays it out.
     */
    demoClick() {
      A.demoIdx = A.idx();
      R.clearLog();
      const b = $('#training [data-act="demo"]');
      A.demoShown = !!b && getComputedStyle(b).display !== 'none';
      if (A.demoShown) b.click();
      return A.demoShown;
    },
    /** Runs the demo until it is over: its moves, and whether the step counted any. */
    demoRun() {
      const started = ctx.motionDemo === true;
      R.until(() => !ctx.motionDemo, 60 * 15);
      R.frames(20);
      const out = { shown: A.demoShown, started, moves: A.moves(), pips: A.pips(), same: A.idx() === A.demoIdx, done: A.has('is-done') };
      R.clearLog();
      return out;
    },
    dummies: () => ctx.actors.filter((a) => a.role === 'dummy'),
    near: () => A.dummies().find((a) => a.homeX === 0 && a.homeZ === -2.6),
    far: () => A.dummies().find((a) => a.homeZ === -18),
    lockX: () => (ctx.player.lockTarget ? +ctx.player.lockTarget.pos.x.toFixed(2) : null),
    duel: null,
    /**
     * The shield steps: reacts to the trainer's wind-ups with a thumb on the glass (block: early;
     * perfect: 0.15 s before the strike). Resumable: with `pauseBefore` it returns 'paused' that
     * long before the first strike (a screenshot of the closing ring), and carries on when called again.
     */
    shieldDuel(mode, pauseBefore = 0) {
      if (!A.duel) {
        const d = { mode, pending: null, held: false, releaseAt: Infinity, presses: 0, paused: !pauseBefore, strikeAt: Infinity, f: 0, idx: A.idx() };
        d.off = ctx.events.on('enemyWindup', (e) => {
          if (!e.targetIsPlayer || d.pending) return;
          const now = ctx.time.gnow;
          d.pending = mode === 'perfect' ? { at: now + e.timeToStrike - 0.15, strike: now + e.timeToStrike } : { at: now + 0.3, strike: now + e.timeToStrike };
          if (d.strikeAt === Infinity) d.strikeAt = d.pending.strike;
        });
        A.duel = d;
      }
      const d = A.duel;
      while (A.idx() === d.idx && d.f++ < 60 * 30) {
        R.frames(1);
        const now = ctx.time.gnow;
        if (!d.paused && now >= d.strikeAt - pauseBefore) {
          d.paused = true;
          return 'paused';
        }
        if (d.pending && !d.held && now >= d.pending.at) {
          R.touch('pointerdown', A.THUMB.x, A.THUMB.y, 61);
          d.held = true;
          d.presses++;
          d.releaseAt = d.pending.strike + 0.2;
          d.pending = null;
        }
        if (d.held && now >= d.releaseAt) {
          R.touch('pointerup', A.THUMB.x, A.THUMB.y, 61);
          d.held = false;
        }
      }
      if (d.held) R.touch('pointerup', A.THUMB.x, A.THUMB.y, 61);
      d.off();
      A.duel = null;
      return { presses: d.presses, done: A.idx() !== d.idx };
    },
    tap(sel, id) {
      const rc = $(sel).getBoundingClientRect();
      R.touch('pointerdown', rc.left + rc.width / 2, rc.top + rc.height / 2, id);
      R.frames(2);
      R.touch('pointerup', rc.left + rc.width / 2, rc.top + rc.height / 2, id);
      R.frames(2);
    },
    skipTo(id) {
      for (let i = 0; i < 20 && A.title() !== id; i++) {
        $('#training [data-act="skip"]').click();
        R.frames(2);
      }
      return A.title() === id;
    },
    swing: (dir, extra) => Object.assign({ kind: 'swing', dir }, extra),
    idle: (duration) => ({ kind: 'idle', duration }),
    PI,
  };
  window.__A = A;
  return true;
}

const G = await openGame({ source: 'android', grip: 'hilt', query: 'grip=hilt', width: W, height: H });
const page = G.page;
// Each call runs fn(R, g, ctx, A) in the page.
const ev = (fn, arg) => G.ev(new Function('R', 'g', 'ctx', 'arg', `return (${fn.toString()})(R, g, ctx, arg, window.__A);`), arg);
/** Clicks the step's Demo and plays it out (two evaluates: the demo starts asynchronously). */
async function demo(before) {
  await ev((R, g, ctx, a, A) => A.demoClick());
  await page.waitForFunction(() => window.__game.ctx.motionDemo === true, null, { timeout: 5000 }).catch(() => {});
  return ev((R, g, ctx, a, A) => A.demoRun());
}
async function shot(name) {
  if (!outDir) return;
  await G.ev((R) => R.draw());
  // Headless Chromium only ticks CSS transitions while frames are requested (the game is stepped by hand).
  await page.evaluate(() => new Promise((r) => {
    let n = 0;
    const f = () => (++n >= 24 ? r() : requestAnimationFrame(f));
    requestAnimationFrame(f);
  }));
  await G.ev((R) => R.draw());
  await page.screenshot({ path: path.join(outDir, `hilt-agoge-${W}x${H}-${name}.png`) });
}

try {
  await G.ev(installAgoge);
  const boot = await ev((R, g, ctx) => {
    window.__nativeRot = 0; // portrait-primary
    ctx.settings.control = 'motion';
    ctx.settings.sensitivity = 1;
    ctx.settings.sensitivityHilt = 1;
    ctx.settings.hiltFingerStrip = 44;
    ctx.settings.hiltAgogeDone = false;
    ctx.applyControls();
    g.startTraining();
    R.frames(10);
    return { grip: ctx.input.grip, w: innerWidth, h: innerHeight };
  });
  check(`the game runs in the hilt grip at ${W}×${H}`, boot.grip === 'hilt' && boot.w === W && boot.h === H, JSON.stringify(boot));

  // ------------------------------------------------------------ I grip: guard and grip check
  let r = await ev((R, g, ctx, a, A) => {
    A.play([A.idle(0.5)]);
    const card = document.querySelector('#training .tr-fig');
    const pr = document.querySelector('#training .tr-panel').getBoundingClientRect();
    const cr = card.getBoundingClientRect();
    return {
      num: A.num(), title: A.title(), text: A.text(), hilt: A.has('is-hilt'), confirm: A.has('has-confirm'), demo: A.has('has-demo'),
      fig: getComputedStyle(card).display === 'block' && !!card.querySelector('svg') && cr.top >= pr.bottom && cr.left < innerWidth / 2 && cr.height > 60,
    };
  });
  check('I: label I / XIII, the hilt grip text, is-hilt, "I\'m ready", no Demo', r.num === 'I / XIII' && /upright in your sword hand/.test(r.text) && /strongly recommended/.test(r.text) && r.hilt && r.confirm && !r.demo, JSON.stringify(r));
  check('I: the hilt grip diagram hangs under the panel on the finger side', r.fig);
  await shot('01-grip');
  r = await ev((R, g, ctx, a, A) => {
    document.querySelector('#training [data-act="ok"]').click();
    A.play([A.idle(0.5)]);
    const started = { text: A.text(), confirm: A.has('has-confirm'), idx: A.idx() };
    // a finger resting on the glass, 60 px from the finger-side (left) edge, lower half: held 0.8 s
    R.touch('pointerdown', 60, 690, 71);
    const shield = ctx.inputCtl.shieldPointers;
    A.play([A.idle(0.8)]);
    const dot = document.querySelector('#training .tr-dot');
    const flagged = {
      hint: A.hint(), dot: getComputedStyle(dot).display === 'block', transform: dot.style.transform,
      strip: ctx.settings.hiltFingerStrip, saved: JSON.parse(localStorage.getItem('thermopylae.settings') || '{}').hiltFingerStrip, idx: A.idx(),
    };
    return { started, shield, flagged };
  });
  check('I: "I\'m ready" starts the check ("Hold your guard still, thumb on the edge."), the button goes',
    /Hold your guard still, thumb on the edge/.test(r.started.text) && !r.started.confirm && r.started.idx === 0, JSON.stringify(r.started));
  check('I: a touch held 0.5 s at the finger edge is a resting finger: dot, hint, still step I',
    r.shield === 1 && r.flagged.dot && /translate3d\(60px, 690px/.test(r.flagged.transform) && /Something touches the glass here/.test(r.flagged.hint) && r.flagged.idx === 0, JSON.stringify(r.flagged));
  check('I: hiltFingerStrip grows to cover it (60 + 16 = 76) and is saved', r.flagged.strip === 76 && r.flagged.saved === 76, `${r.flagged.strip} / saved ${r.flagged.saved}`);
  await shot('02-grip-finger');
  r = await ev((R, g, ctx, a, A) => {
    R.touch('pointerup', 60, 690, 71);
    const t0 = R.vt; // the glass is clear from here
    A.play([A.idle(0.2)]);
    // the same spot is in the widened strip now: no shield
    R.touch('pointerdown', 60, 690, 72);
    const rejected = ctx.inputCtl.shieldPointers === 0 && !ctx.input.shieldHeld;
    let at = null;
    A.play([A.idle(4.0)], () => {
      if (at === null && A.has('is-done')) at = R.vt;
    });
    R.touch('pointerup', 60, 690, 72);
    const learned = ctx.gestures.guardLearned;
    const next = A.waitNext(0);
    return { rejected, wait: at === null ? null : +(at - t0).toFixed(2), learned, next, title: A.title() };
  });
  check('I: the same touch is now in the finger strip (no shield)', r.rejected);
  check('I: the check passes 3 s after the glass is clear, with the guard learned', r.wait !== null && r.wait >= 2.95 && r.wait <= 3.4 && r.learned, `passed after ${r.wait} s, guardLearned ${r.learned}`);
  check('I → II calibrate', r.next && r.title === 'Calibrate', r.title);

  // ------------------------------------------------------------ II calibrate
  r = await demo();
  check('II: Demo gives R,L,R and counts nothing', r.shown && r.started && r.moves === 'R,L,R' && r.pips === 0 && r.same && !r.done, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const cal = [];
    for (let i = 0; i < 5; i++) cal.push(A.idle(0.35), A.swing(i % 2 ? A.PI : 0, { speed: 6 }));
    A.play(cal.concat([A.idle(0.8)]));
    return { calib: A.has('is-calib'), text: document.querySelector('#training .tr-calib-t').textContent, text2: A.text() };
  });
  check('II: 5 cuts → the calibration panel (the hilt text first)', r.calib && /Suggested sensitivity/.test(r.text), JSON.stringify(r));
  await shot('03-calibrate');
  r = await ev((R, g, ctx, a, A) => {
    document.querySelector('#training [data-act="apply"]').click();
    const s = { hilt: ctx.settings.sensitivityHilt, window: ctx.settings.sensitivity, rec: ctx.gestures.sens, saved: JSON.parse(localStorage.getItem('thermopylae.settings') || '{}').sensitivityHilt };
    const next = A.waitNext(1);
    return Object.assign(s, { next, title: A.title(), slider: document.querySelector('#training .tr-sens output').textContent });
  });
  check('II: Apply writes sensitivityHilt (saved, the recognizer told), not the window grip\'s sensitivity',
    r.hilt !== 1 && r.window === 1 && Math.abs(r.rec - r.hilt) < 1e-9 && r.saved === r.hilt, JSON.stringify(r));
  check('II → III, the slider shows the hilt sensitivity', r.next && r.title === 'Right and left' && r.slider === `${r.hilt.toFixed(2)}×`, `${r.title} ${r.slider}`);

  // ------------------------------------------------------------ III right and left
  r = await ev((R, g, ctx, a, A) => {
    R.clearLog();
    // a cut too slow to slash: the "windscreen wiper" hint and the cut diagram
    A.play([A.idle(0.3), A.swing(0, { speed: 2.6, arc: 1.0 }), A.idle(0.4)]);
    const card = document.querySelector('#training .tr-fig');
    const below = card.getBoundingClientRect().top >= document.querySelector('#training .tr-panel').getBoundingClientRect().bottom;
    return { moves: A.moves(), hint: A.hint(), fig: !!card.querySelector('svg') && getComputedStyle(card).display === 'block' && below, text: A.text() };
  });
  check('III: a slow cut gives no slash, the wiper hint and the "sweep the top" diagram', r.moves === '' && /windscreen wiper/.test(r.hint) && r.fig, JSON.stringify(r));
  check('III: the hilt instruction', /sweep the top of the phone across/.test(r.text), r.text);
  await shot('04-slashLR-wiper');
  r = await demo();
  check('III: Demo gives R,R,L,L and counts nothing', r.moves === 'R,R,L,L' && r.pips === 0 && r.same, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const { swing, idle, PI } = A;
    A.play([idle(0.3), swing(0, { ret: 4 }), idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 4 }), idle(0.3), swing(PI), idle(0.4)]);
    return { moves: A.moves(), next: A.waitNext(2), title: A.title() };
  });
  check('III: R,R,L,L → IV', r.next && r.title === 'The overhead chop', JSON.stringify(r));

  // ------------------------------------------------------------ IV overhead
  r = await demo();
  check('IV: Demo (a chop from the guard, no cock-back) gives D,D', r.moves === 'D,D' && r.pips === 0 && r.same, JSON.stringify(r));
  await shot('05-overhead');
  r = await ev((R, g, ctx, a, A) => {
    const { swing, idle, PI } = A;
    R.clearLog();
    // a cock-back before the first chop (a wind-up with the sword) does not count against it
    A.play([idle(0.3), { kind: 'raise' }, swing(-PI / 2), idle(0.6)]);
    const first = { moves: A.moves(), pips: A.pips(), hint: A.hint() };
    A.play([idle(0.3), swing(-PI / 2), idle(0.5)]);
    return { first, moves: A.moves(), next: A.waitNext(3), title: A.title() };
  });
  check('IV: cock-back + chop counts the chop (windup,D: one pip, no hint)', r.first.moves === 'windup,D' && r.first.pips === 1 && r.first.hint === '', JSON.stringify(r.first));
  check('IV: a second chop → V', r.next && r.title === 'The uppercut', JSON.stringify(r));

  // ------------------------------------------------------------ V uppercut
  r = await ev((R, g, ctx, a, A) => {
    const { swing, idle, PI } = A;
    R.clearLog();
    // an up swing from the upright guard is the cock-back, never a cut
    A.play([idle(0.3), swing(PI / 2, { speed: 6, arc: 0.8 }), idle(0.5)]);
    return { moves: A.moves(), hint: A.hint(), pips: A.pips(), text: A.text() };
  });
  check('V: an up swing from the upright guard: no slash, "Lower the blade first, then swing up."', r.moves === '' && /Lower the blade first, then swing up/.test(r.hint) && r.pips === 0, JSON.stringify(r));
  await shot('06-uppercut-hint');
  r = await demo();
  check('V: Demo (lower, then up) gives U', r.moves === 'U' && r.pips === 0 && r.same, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const { swing, idle, PI } = A;
    A.play([idle(0.3), { kind: 'lower', beta: 0.1 }, idle(0.3), swing(PI / 2), idle(0.5)]);
    return { moves: A.moves(), next: A.waitNext(4), title: A.title() };
  });
  check('V: lower + up → VI', r.next && r.title === 'The thrust', JSON.stringify(r));

  // ------------------------------------------------------------ VI thrust
  r = await demo();
  check('VI: Demo gives 3 thrusts', r.moves === 'thrust,thrust,thrust' && r.pips === 0 && r.same, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const { idle } = A;
    A.play([idle(0.3), { kind: 'thrust' }, idle(0.45), { kind: 'thrust' }, idle(0.45), { kind: 'thrust' }, idle(0.4)]);
    return { moves: A.moves(), next: A.waitNext(5), title: A.title() };
  });
  check('VI: 3 thrusts → VII (the lock step)', r.next && r.title === 'Change foe', JSON.stringify(r));

  // ------------------------------------------------------------ VII lock
  r = await ev((R, g, ctx, a, A) => {
    A.play([A.idle(1.2)]);
    return {
      num: A.num(), text: A.text(), demo: A.has('has-demo'), dummies: A.dummies().length, lockX: A.lockX(), lockZ: ctx.player.lockTarget && ctx.player.lockTarget.pos.z,
      fig: document.querySelector('#training .tr-big').classList.contains('is-shown') && !!document.querySelector('#training .tr-big-g animateTransform'),
      label: document.querySelector('#training .tr-big-t').textContent,
    };
  });
  check('VII: label VII / XIII, the swipe text, no Demo, two flank dummies (4 in all), the near dummy locked',
    r.num === 'VII / XIII' && /Swipe your thumb right/.test(r.text) && !r.demo && r.dummies === 4 && r.lockX === 0 && r.lockZ === -2.6, JSON.stringify(r));
  check('VII: the big slot animates a swiping thumb', r.fig && r.label === 'Swipe', JSON.stringify(r));
  await shot('07-lock');
  r = await ev((R, g, ctx, a, A) => {
    R.clearLog();
    g.action({ type: 'switchTarget', dir: 1 });
    R.frames(3);
    const right = { lockX: A.lockX(), hint: A.hint(), pips: A.pips(), reason: R.evs('lockOn').map((e) => e.reason).join(',') };
    A.play([A.idle(1.0)]);
    right.yaw = +ctx.player.yaw.toFixed(3);
    return right;
  });
  check('VII: a right swipe locks the right dummy (not the far target): one pip, "Now left."', r.lockX === 1.6 && r.pips === 1 && /Now left/.test(r.hint) && r.reason === 'switch', JSON.stringify(r));
  await shot('08-lock-right');
  r = await ev((R, g, ctx, a, A) => {
    R.clearLog();
    g.action({ type: 'switchTarget', dir: 1 });
    R.frames(3);
    const none = { lockX: A.lockX(), hint: A.hint(), pips: A.pips() };
    g.action({ type: 'switchTarget', dir: -1 });
    R.frames(3);
    const left = { lockX: A.lockX(), pips: A.pips(), done: A.has('is-done') };
    const next = A.waitNext(6);
    A.play([A.idle(0.3)]);
    const lt = ctx.player.lockTarget;
    return { none, left, next, title: A.title(), dummies: A.dummies().length, lockListed: !!lt && ctx.actors.includes(lt), lockRole: lt && (lt.role + (lt.trainer ? ' trainer' : '')) };
  });
  check('VII: nobody further right: "Nobody stands that way", no pip', r.none.lockX === 1.6 && /Nobody stands that way/.test(r.none.hint) && r.none.pips === 1, JSON.stringify(r.none));
  check('VII: then a left swipe completes the step', r.left.pips === 2 && r.left.done, JSON.stringify(r.left));
  check('VII → VIII block: the flank dummies are gone, the lock is on a listed actor', r.next && r.title === 'Block' && r.dummies === 2 && r.lockListed, JSON.stringify(r));

  // ------------------------------------------------------------ VIII block, IX perfect
  r = await ev((R, g, ctx, a, A) => ({ compact: A.has('is-compact'), num: A.num(), text: A.text(), head: getComputedStyle(document.querySelector('#training .tr-head')).pointerEvents, h: Math.round(document.querySelector('#training .tr-panel').getBoundingClientRect().height) }));
  // §E.5 asks ≤ 56 px; the stylesheet (P8) gives 57 at this size: allow 2 px of font metrics
  check('VIII: the panel is compact (about 56 px), its head row takes taps', r.compact && r.head === 'auto' && r.h <= 58, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => A.shieldDuel('block', 0.5));
  await shot('09-block');
  r = await ev((R, g, ctx, a, A) => A.shieldDuel('block'));
  let t = await ev((R, g, ctx, a, A) => ({ title: A.title() }));
  check('VIII: three blocks with the thumb on the glass → IX', r.done && t.title === 'The perfect block', JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => A.shieldDuel('perfect', 0.3));
  await shot('10-perfect-ring');
  r = await ev((R, g, ctx, a, A) => A.shieldDuel('perfect'));
  t = await ev((R, g, ctx, a, A) => ({ title: A.title(), compact: A.has('is-compact') }));
  check('IX: two perfect blocks → X bash (compact)', r.done && t.title === 'Shield bash' && t.compact, JSON.stringify(Object.assign(r, t)));

  // ------------------------------------------------------------ X bash
  r = await ev((R, g, ctx, a, A) => {
    document.querySelector('#training .tr-head').click();
    R.frames(2);
    return { compact: A.has('is-compact'), tools: getComputedStyle(document.querySelector('#training .tr-tools')).display };
  });
  check('X: a tap on the collapsed head row opens the panel until the next step', !r.compact && r.tools !== 'none', JSON.stringify(r));
  await shot('11-bash-open');
  r = await demo();
  check('X: Demo gives 2 thrusts (bashes behind the demo shield) and counts nothing', r.moves === 'thrust,thrust' && r.pips === 0 && r.same, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const { idle } = A;
    R.touch('pointerdown', A.THUMB.x, A.THUMB.y, 62);
    R.frames(15);
    A.play([idle(0.2), { kind: 'thrust' }, idle(0.9), { kind: 'thrust' }, idle(0.5)]);
    R.touch('pointerup', A.THUMB.x, A.THUMB.y, 62);
    const swings = R.evs('swing').map((e) => e.kind).join(',');
    return { swings, next: A.waitNext(9), title: A.title(), compact: A.has('is-compact') };
  });
  check('X: thumb on the glass + 2 punches → XI arrows (compact again)', r.next && r.title === 'Arrows' && r.compact, JSON.stringify(r));

  // ------------------------------------------------------------ XI volley
  r = await ev((R, g, ctx, a, A) => {
    const idx = A.idx();
    let warned = false;
    const off = ctx.events.on('volley', (v) => {
      if (v.phase === 'warning') warned = true;
    });
    R.until(() => warned, 60 * 6);
    off();
    R.touch('pointerdown', A.THUMB.x, A.THUMB.y, 63);
    R.frames(30);
    return { warned, idx };
  });
  await shot('12-volley');
  r = await ev((R, g, ctx, a, A) => {
    const idx = A.idx();
    let ended = false;
    const off = ctx.events.on('volley', (v) => {
      if (v.phase === 'end') ended = true;
    });
    R.until(() => ended, 60 * 8);
    off();
    R.touch('pointerup', A.THUMB.x, A.THUMB.y, 63);
    return { next: A.waitNext(idx), title: A.title() };
  });
  check('XI: the thumb held through the volley → XII the dory', r.next && r.title === 'The dory', JSON.stringify(r));

  // ------------------------------------------------------------ XII spear
  r = await ev((R, g, ctx, a, A) => {
    A.play([A.idle(0.5)]);
    const text = A.text();
    const near = A.near();
    const before = { lock: ctx.player.lockTarget === near, weapon: ctx.player.weapon };
    A.tap('#controls .ctl-swap', 64);
    R.frames(40);
    return { text, before, weapon: ctx.player.weapon, pips: A.pips(), hint: A.hint() };
  });
  check('XII: the hilt text (the weapon button at the top, cock back, snap forward)', /weapon button at the top/.test(r.text) && /cock the phone back/.test(r.text) && !/[Ff]lick your thumb/.test(r.text), r.text);
  check('XII: the weapon button (tap) takes the spear: one pip; the near dummy is locked', r.before.lock && r.before.weapon === 'sword' && r.weapon === 'spear' && r.pips === 1, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const { idle } = A;
    R.clearLog();
    A.play([idle(0.3), { kind: 'thrust' }, idle(0.45), { kind: 'thrust' }, idle(0.3)]);
    const far = A.far();
    const near = A.near();
    const out = { swings: R.evs('swing').map((e) => e.kind).join(','), farLocked: ctx.player.lockTarget === far, hint: A.hint(), nearGoal: near && near.pos.x, pips: A.pips() };
    A.play([idle(1.0)]);
    out.nearX = near && +near.pos.x.toFixed(2);
    const b = Math.atan2(-(far.pos.x - ctx.player.pos.x), -(far.pos.z - ctx.player.pos.z));
    out.yawErr = +Math.abs(b - ctx.player.yaw).toFixed(3);
    return out;
  });
  check('XII: after the second thrust the near dummy steps aside and the far target is locked ("The far target is locked.")',
    r.farLocked && /The far target is locked/.test(r.hint) && r.pips === 3 && r.nearX === -1.4, JSON.stringify(r));
  check('XII: the view faces the far target', r.yawErr < 0.05, `yaw error ${r.yawErr}`);
  await shot('13-spear-farlock');
  r = await ev((R, g, ctx, a, A) => {
    const { idle } = A;
    R.clearLog();
    const labels = new Set();
    A.play([idle(0.3), { kind: 'throw' }, idle(0.5)], () => labels.add(document.querySelector('#training .tr-last-t').textContent));
    R.frames(60 * 3);
    return { moves: A.moves(), labels: [...labels], impacts: R.evs('spearImpact').map((e) => e.target).join(','), done: A.has('is-done') || A.title() !== 'The dory', next: A.waitNext(A.idx()), title: A.title() };
  });
  check('XII: the cock-back reads "Armed"', r.labels.some((l) => /^Armed/.test(l)), r.labels.join(' | '));
  check('XII: the throw hits the far target → XIII Twin Fangs', /windup,throw/.test(r.moves) && r.done && r.title === 'Twin Fangs', JSON.stringify(r));

  // ------------------------------------------------------------ XIII twin
  r = await ev((R, g, ctx, a, A) => {
    A.play([A.idle(0.6)]);
    const near = A.near();
    return { num: A.num(), text: A.text(), nearLocked: ctx.player.lockTarget === near, weapon: ctx.player.weapon };
  });
  check('XIII: label XIII / XIII, the near dummy locked again', r.num === 'XIII / XIII' && r.nearLocked, JSON.stringify(r));
  await shot('14-twin');
  r = await demo();
  check('XIII: Demo gives R,L', r.moves === 'R,L' && r.same && !r.done, JSON.stringify(r));
  r = await ev((R, g, ctx, a, A) => {
    const { swing, idle, PI } = A;
    // the spear stays in hand from the dory step, as it would for a player
    A.play([idle(0.3), swing(0, { arc: 1.0, speed: 9 }), swing(PI, { arc: 1.0, speed: 9 }), idle(0.6)]);
    R.frames(120);
    return {
      moves: A.moves(), combos: R.evs('combo').map((e) => e.id).join(','), finished: A.has('is-finished'),
      done: ctx.settings.hiltAgogeDone, saved: JSON.parse(localStorage.getItem('thermopylae.settings') || '{}').hiltAgogeDone,
    };
  });
  check('XIII: right, left → Twin Fangs → the end card', r.finished, JSON.stringify(r));
  check('finishing the hilt Agoge sets hiltAgogeDone (saved)', r.done === true && r.saved === true, JSON.stringify(r));
  await shot('15-end');

  // ------------------------------------------------------------ over-speed hint
  r = await ev((R, g, ctx, a, A) => {
    g.startTraining();
    R.frames(5);
    A.skipTo('Right and left');
    A.play([A.idle(1.0)]);
    R.clearLog();
    A.play([A.idle(0.3), A.swing(0, { speed: 16, arc: 1.4 }), A.idle(0.6)]);
    return { fast: R.evs('gesture').filter((e) => e.fast).map((e) => e.type).join(','), hint: A.hint() };
  });
  check('a cut at 16 rad/s: "Easy: a firm flick is enough."', r.fast.length > 0 && /Easy: a firm flick is enough/.test(r.hint), JSON.stringify(r));
  await shot('16-overspeed');

  // ------------------------------------------------------------ a mirrored setup: the hilt grip's own flip
  r = await ev((R, g, ctx, a, A) => {
    g.startTraining();
    R.frames(5);
    A.skipTo('Right and left');
    A.play([A.idle(1.0)]);
    R.clearLog();
    // asked for right, three cuts go left: the hilt grip's flip toggles, the window grip's stays
    const { swing, idle, PI } = A;
    A.play([idle(0.3), swing(PI), idle(0.4), swing(PI), idle(0.4), swing(PI), idle(0.5)]);
    const out = { moves: A.moves(), hint: A.hint(), xHilt: ctx.settings.motionFlipXHilt, x: ctx.settings.motionFlipX, pips: A.pips() };
    ctx.settings.motionFlipXHilt = false;
    ctx.applyControls();
    return out;
  });
  check('a mirrored setup in the hilt grip toggles motionFlipXHilt (not motionFlipX), "Swing direction corrected"',
    r.xHilt === true && r.x === false && /Swing direction corrected/.test(r.hint), JSON.stringify(r));

  // ------------------------------------------------------------ the spear demo locks the far target
  r = await ev((R, g, ctx, a, A) => {
    A.skipTo('The dory');
    A.play([A.idle(0.5)]);
    const near = A.near();
    const far = A.far();
    const before = ctx.player.lockTarget === near;
    A.farAtWindup = null;
    A.offWindup = ctx.events.on('gesture', (e) => {
      if (e.type === 'windup' && A.farAtWindup === null) A.farAtWindup = ctx.player.lockTarget === far;
    });
    return { before };
  });
  t = await demo();
  r = Object.assign(r, t, await ev((R, g, ctx, a, A) => {
    A.offWindup();
    return { farAtWindup: A.farAtWindup, title: A.title() };
  }));
  check('the hilt spear demo: thrust, thrust, then the far target locked before the cock-back, throw; nothing counted',
    r.before && r.farAtWindup === true && r.moves === 'thrust,thrust,windup,throw' && r.pips === 0 && r.title === 'The dory', JSON.stringify(r));

  // ------------------------------------------------------------ left hand: the finger side is the right edge
  r = await ev((R, g, ctx, a, A) => {
    ctx.settings.leftHanded = true;
    ctx.settings.hiltFingerStrip = 44;
    g.startTraining();
    R.frames(5);
    A.play([A.idle(0.5)]);
    document.querySelector('#training [data-act="ok"]').click();
    A.play([A.idle(0.3)]);
    R.touch('pointerdown', innerWidth - 50, 700, 81);
    A.play([A.idle(0.8)]);
    const out = { left: A.has('is-left'), strip: ctx.settings.hiltFingerStrip, hint: A.hint(), idx: A.idx(), cardGap: Math.round(innerWidth - document.querySelector('#training .tr-fig').getBoundingClientRect().right) };
    return out;
  });
  check('left hand: a finger resting 50 px from the right edge widens the strip to 66', r.left && r.strip === 66 && /Something touches/.test(r.hint) && r.idx === 0, JSON.stringify(r));
  check('left hand: the grip diagram hangs on the finger side (right)', r.cardGap >= 0 && r.cardGap <= 16, `${r.cardGap} px from the right edge`);
  await shot('17-grip-left');
  await ev((R, g, ctx, a, A) => {
    R.touch('pointerup', innerWidth - 50, 700, 81);
    ctx.settings.leftHanded = false;
    R.frames(2);
  });

  // ------------------------------------------------------------ a grip change mid-Agoge
  r = await ev((R, g, ctx, a, A) => {
    g.startTraining();
    R.frames(5);
    A.skipTo('Change foe');
    R.frames(5);
    const at = { num: A.num(), dummies: A.dummies().length };
    ctx.settings.grip = 'window';
    ctx.applyControls();
    R.frames(5);
    return { at, num: A.num(), title: A.title(), idx: A.idx(), dummies: A.dummies().length, hilt: A.has('is-hilt'), text: A.text() };
  });
  check('switching to the window grip on the lock step goes on to Block in the 12-step list',
    r.at.num === 'VII / XIII' && r.at.dummies === 4 && r.title === 'Block' && r.num === 'VII / XII' && r.idx === 6 && r.dummies === 2 && !r.hilt, JSON.stringify(r));

  // ------------------------------------------------------------ the window grip keeps its 12 steps
  r = await ev((R, g, ctx, a, A) => {
    window.__nativeRot = 90;
    g.startTraining();
    R.frames(5);
    const titles = [A.title()];
    const nums = [A.num()];
    const first = { text: A.text(), fig: document.querySelector('#training .tr-big').classList.contains('is-shown') };
    // a move's glyph: no diagram card left over from the hilt sessions
    ctx.events.emit('gesture', { type: 'thrust', power: 0.5 });
    R.frames(2);
    first.glyph = document.querySelector('#training .tr-big').classList.contains('is-shown') && getComputedStyle(document.querySelector('#training .tr-fig')).display === 'none';
    document.querySelector('#training [data-act="ok"]').click(); // no grip check in the window grip
    R.frames(100);
    for (let i = 0; i < 14 && !A.has('is-finished'); i++) {
      titles.push(A.title());
      nums.push(A.num());
      document.querySelector('#training [data-act="skip"]').click();
      R.frames(2);
    }
    return { first, titles, nums, hilt: A.has('is-hilt') };
  });
  check('the window grip: the landscape text, no hilt figure (a move shows its glyph alone), "I\'m ready" completes at once',
    /landscape/.test(r.first.text) && !r.first.fig && r.first.glyph && r.titles[1] === 'Calibrate', JSON.stringify(r.first));
  check('the window grip: 12 steps, no lock step, I / XII … XII / XII',
    r.nums[0] === 'I / XII' && r.nums.at(-1) === 'XII / XII' && !r.titles.includes('Change foe') && new Set(r.titles).size === 12 && !r.hilt, JSON.stringify(r.titles));

  const errs = await G.errors();
  check('no page errors', errs.length === 0, errs.slice(0, 5).join(' | '));
} finally {
  await G.close();
}
const failed = results.filter((x) => !x.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed${outDir ? `; screenshots in ${outDir}` : ''}`);
if (failed.length) process.exitCode = 1;
