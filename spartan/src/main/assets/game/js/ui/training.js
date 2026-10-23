// ui/training.js: the Agoge, guided training and calibration (DESIGN.md §11 ui/*).
// A UI-driven script: it spawns dummies and a trainer through ctx.ai, calls ctx.combat.volley,
// counts events, and speaks as the Narrator. Its panel lives in its own #training root (z 25),
// above #controls so its buttons work in the motion layout.
import { clamp } from '../core/math.js';
import { dirToken, TOKEN_SETS } from '../game/rules.js';
import { GESTURE_DEFAULTS, SENSITIVITY_MIN, SENSITIVITY_MAX, slashThreshold } from '../motion/gestures.js';

const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII'];
const WORD = { R: 'right', UR: 'up-right', U: 'up', UL: 'up-left', L: 'left', DL: 'down-left', D: 'down', DR: 'down-right' };
const NEAR = { x: 0, z: -2.6 };
const FAR = { x: 1.5, z: -18 };
// In step XI the near dummy hid the far target (and stole the throw lock): once the two thrusts
// are done it steps aside to ASIDE_X, out of the line and the 0.262 rad lock cone, and comes back after.
const ASIDE_X = -1.4;
const ASIDE_SPEED = 3.5; // m/s

/**
 * Sensitivity from calibration peaks: clamp(target / median(peak), min, max), to 0.05. The
 * defaults are the original contract (9 rad/s, 0.6–1.8); the Agoge passes CALIBRATION.
 */
export function suggestSensitivity(peaks, { target = 9, min = 0.6, max = 1.8 } = {}) {
  if (!peaks || !peaks.length) return 1;
  const s = peaks.slice().sort((a, b) => a - b);
  const mid = s.length >> 1;
  const median = s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
  if (!(median > 0)) return 1;
  return Math.round(clamp(target / median, min, max) * 20) / 20;
}

/**
 * The Agoge's calibration: the threshold a slash must reach (swingPeak / s) lands at 60% of the
 * player's median swing, so their weaker swings still count while returns and cock-backs do not.
 */
export const CALIBRATION = Object.freeze({ target: GESTURE_DEFAULTS.swingPeak / 0.6, min: 0.6, max: 2.2 });

/**
 * Picks the strokes out of the calibration's swing candidates ({ peak, t0, t } from gestures,
 * oldest first): a stroke is at least STROKE_SHARE as fast as every candidate that touches it in
 * time (within STROKE_GAP before its start or after its end). A cock-back just before a stroke
 * and the return after it are slower, so they drop out; strokes of a steady rhythm all stay.
 * Returns the strokes' peaks.
 */
const STROKE_SHARE = 0.8;
export const STROKE_GAP = 0.45;
function isStroke(cands, i) {
  const c = cands[i];
  let best = 0;
  for (let j = 0; j < cands.length; j++) {
    if (j === i) continue;
    const o = cands[j];
    const near = o.t0 <= c.t + STROKE_GAP && o.t >= c.t0 - STROKE_GAP;
    if (near && o.peak > best) best = o.peak;
  }
  return c.peak >= STROKE_SHARE * best;
}
export function pickStrokes(cands) {
  const out = [];
  for (let i = 0; i < cands.length; i++) if (isStroke(cands, i)) out.push(cands[i].peak);
  return out;
}

const swing = (dir, extra) => Object.assign({ kind: 'swing', dir }, extra);
const idle = (duration) => ({ kind: 'idle', duration });
const PI = Math.PI;

/**
 * The steps (§11). `n` repetitions complete a step; `motion`/`touch` are the instructions.
 * `demo` returns a synth script, or is null when the move isn't a phone motion.
 */
export const STEPS = [
  { id: 'grip', title: 'Grip and safety', n: 1, confirm: true,
    motion: 'Hold the phone in landscape in your sword hand, screen toward you. Grip it firmly, clear the space around you, and never let go.',
    touch: 'Left thumb: the stick moves you, SHIELD blocks. Right thumb: swipe to slash, tap to stab, drag slowly to look.',
    voice: 'Welcome to the Agoge. Take up your arms.', demo: null },
  { id: 'calibrate', title: 'Calibrate', n: 5, motionOnly: true,
    motion: 'Slash 5 times, the way you will in battle. Gentle is fine.', touch: '',
    voice: 'Strike five times, as you would in battle.',
    demo: () => [idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 0 }), idle(0.3), swing(0), idle(0.3)] },
  { id: 'slashLR', title: 'Right and left', n: 4,
    motion: 'Swing right twice, then left twice.', touch: 'Swipe right twice, then left twice.',
    voice: 'Cut to the right. Then back to the left.',
    demo: () => [idle(0.3), swing(0, { ret: 4 }), idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 4 }), idle(0.3), swing(PI), idle(0.3)] },
  { id: 'overhead', title: 'The overhead chop', n: 2,
    motion: 'Raise the phone, then chop straight down. Twice. It goes over a wicker shield.', touch: 'Swipe straight down. Twice. It goes over a wicker shield.',
    voice: 'Bring it down from above. The chop goes over their shields.',
    demo: () => [idle(0.3), { kind: 'raise' }, swing(-PI / 2), idle(0.5), { kind: 'raise' }, swing(-PI / 2), idle(0.3)] },
  { id: 'uppercut', title: 'The uppercut', n: 1,
    motion: 'From a low guard, swing upward.', touch: 'Swipe up.',
    voice: 'From low, strike upward.',
    demo: () => [idle(0.3), swing(PI / 2, { windup: 0.4 }), idle(0.3)] },
  { id: 'thrust', title: 'The thrust', n: 3,
    motion: 'Push the phone straight forward, then draw it back. Three times.', touch: 'Tap to stab. Three times.',
    voice: 'Thrust. Short and straight.',
    demo: () => [idle(0.3), { kind: 'thrust' }, idle(0.4), { kind: 'thrust' }, idle(0.4), { kind: 'thrust' }, idle(0.3)] },
  { id: 'block', title: 'Block', n: 3, trainer: true,
    motion: 'Press the glass with your thumb to raise the shield before each blow lands.', touch: 'Hold SHIELD before each blow lands.',
    voice: 'A Persian comes at you. Shield up before he strikes.', demo: null },
  { id: 'perfect', title: 'The perfect block', n: 2, trainer: true,
    motion: 'Raise the shield at the last moment, as the red ring closes. Twice.', touch: 'Press SHIELD at the last moment, as the red ring closes. Twice.',
    voice: 'Now raise it at the last moment. Let the ring close.', demo: null },
  { id: 'bash', title: 'Shield bash', n: 2, trainer: true,
    motion: 'Shield up, then push the phone forward. Twice.', touch: 'Tap BASH, or swipe up from SHIELD. Twice.',
    voice: 'Shield up, and drive it into him.',
    demo: () => [idle(0.4), { kind: 'thrust' }, idle(0.5), { kind: 'thrust' }, idle(0.3)] },
  { id: 'volley', title: 'Arrows', n: 1,
    motion: 'When the archers loose, keep your thumb on the glass until the arrows have fallen.', touch: 'When the archers loose, hold SHIELD until the arrows have fallen.',
    voice: 'Archers! Shields up, and hold.', demo: null },
  { id: 'spear', title: 'The dory', n: 4,
    motion: 'Take the spear with the weapon button. Thrust twice. Then raise it beside your head and throw at the far target. Never let go.',
    touch: 'Take the spear with the weapon button. Tap twice to thrust. Then hold THROW and release at the far target.',
    voice: 'Take up the spear.',
    demo: () => [idle(0.9), { kind: 'thrust' }, idle(0.45), { kind: 'thrust' }, idle(0.6), { kind: 'throw' }, idle(0.4)] },
  { id: 'twin', title: 'Twin Fangs', n: 1,
    motion: 'Slash right, then left, fast, into the dummy.', touch: 'Swipe right, then left, fast.',
    voice: 'Right and left, quick as a viper. Twin Fangs.',
    demo: () => [idle(0.3), swing(0, { arc: 1.0, speed: 9 }), swing(PI, { arc: 1.0, speed: 9 }), idle(0.4)] },
];

const PRAISE = ['Good.', 'Well struck.', 'Again, that is the way.', 'Good. Like a Spartan.', 'Yes.'];
const ARROW = '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M-34 0H22" stroke="currentColor" stroke-width="9" stroke-linecap="round"/><path d="M14-19 38 0 14 19Z" fill="currentColor"/></svg>';
const ICON = {
  thrust: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><circle r="30" fill="none" stroke="currentColor" stroke-width="6"/><circle r="15" fill="none" stroke="currentColor" stroke-width="5"/><circle r="5" fill="currentColor"/></svg>',
  bash: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><circle r="34" fill="none" stroke="currentColor" stroke-width="7"/><circle r="22" fill="none" stroke="currentColor" stroke-width="3" opacity=".6"/><circle r="7" fill="currentColor"/></svg>',
  throw: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M-30 26C-30-20 10-34 30-10" fill="none" stroke="currentColor" stroke-width="7" stroke-linecap="round"/><path d="M18-22 38 0 12 2Z" fill="currentColor"/></svg>',
  windup: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M0 34V-22" stroke="currentColor" stroke-width="8" stroke-linecap="round"/><path d="M-18-12 0-38 18-12Z" fill="currentColor"/></svg>',
};

/**
 * Creates the Agoge.
 * @param {object} ctx shared context (§4)
 * @returns {{ start():void, stop():void, update(dt:number):void, demo(stepId:number|string):Promise<boolean>, readonly active:boolean, readonly stepIndex:number, readonly demoShield:boolean }}
 */
export function createTraining(ctx) {
  const settings = ctx.settings;
  const root = document.createElement('div');
  root.id = 'training';
  root.innerHTML = `
    <div class="tr-big"><span class="tr-big-g"></span><b class="tr-big-t"></b></div>
    <div class="tr-panel">
      <div class="tr-head"><span class="tr-num"></span><h3 class="tr-title"></h3><span class="tr-pips"></span><span class="tr-check">✓</span></div>
      <p class="tr-hint"></p><p class="tr-text"></p>
      <div class="tr-calib"><span class="tr-calib-t"></span><button type="button" class="tr-btn tr-primary" data-act="apply">Apply</button><button type="button" class="tr-btn" data-act="keep">Keep</button></div>
      <div class="tr-tools">
        <div class="tr-last"><span class="tr-last-g"></span><span class="tr-last-t">No move yet</span></div>
        <div class="tr-meters"><i class="tr-meter"><b class="tr-mm"></b><em class="tr-mt"></em></i><i class="tr-meter"><b class="tr-mf"></b><em class="tr-ft"></em></i></div>
        <label class="tr-sens"><span>Sens.</span><input type="range" min="${SENSITIVITY_MIN}" max="${SENSITIVITY_MAX}" step="0.05" aria-label="Sensitivity"><output></output></label>
        <span class="tr-gap"></span>
        <button type="button" class="tr-btn tr-primary tr-ok" data-act="ok">I'm ready</button>
        <button type="button" class="tr-btn tr-demo" data-act="demo">Demo</button>
        <button type="button" class="tr-btn tr-quiet" data-act="skip">Skip</button>
      </div>
    </div>
    <div class="tr-end">
      <div class="tr-end-in">
        <div class="tr-end-k">The Agoge</div>
        <h2 class="tr-end-h">Your training is done</h2>
        <p>You are ready to stand in the line.</p>
        <div class="tr-end-btns"><button type="button" class="tr-btn tr-primary" data-act="battle">To the Hot Gates</button><button type="button" class="tr-btn" data-act="again">Again</button><button type="button" class="tr-btn tr-quiet" data-act="title">Title</button></div>
      </div>
    </div>`;
  ctx.overlay.appendChild(root);
  const q = (s) => root.querySelector(s);
  const el = {
    panel: q('.tr-panel'), num: q('.tr-num'), title: q('.tr-title'), pips: q('.tr-pips'), text: q('.tr-text'), hint: q('.tr-hint'),
    calib: q('.tr-calib'), calibT: q('.tr-calib-t'), lastG: q('.tr-last-g'), lastT: q('.tr-last-t'),
    mm: q('.tr-mm'), mf: q('.tr-mf'), mt: q('.tr-mt'), ft: q('.tr-ft'),
    sens: q('.tr-sens input'), sensOut: q('.tr-sens output'), big: q('.tr-big'), bigG: q('.tr-big-g'), bigT: q('.tr-big-t'),
  };

  // ---- state ----
  let active = false;
  let index = 0;
  let step = STEPS[0];
  let count = 0;
  let sub = null;           // per-step counters
  let doneWait = 0;         // > 0: step complete, advancing soon
  let hintLeft = 0;
  let bigLeft = 0;
  let demoLeft = 0;          // > 0 while a demo runs (a safety cap on its length)
  let demoEndT = 0;          // sensor time of the last injected sample
  let demoTail = 0;          // settle time once every injected sample has been delivered
  let demoShield = false;
  let meterClock = 0;
  let dummy = null;
  let farDummy = null;
  let dummyGoalX = NEAR.x;
  let trainer = null;
  let trainerWait = 0;
  let volleyWait = 0;
  let volleyHit = false;
  let volleyLive = false;
  let peaks = [];
  let cands = []; // calibration swing candidates (see pickStrokes)
  let candAt = 0; // real time of the last one
  let suggestion = 1;
  let calibrating = false;
  let finished = false;
  let expects = [];         // [{ at }] game times of telegraphed strikes on the player
  let synthPromise = null;
  let praiseI = 0;
  let pendingBig = false;

  const motionMode = () => settings.control === 'motion';
  const counting = () => active && !finished && doneWait <= 0 && !ctx.motionDemo && demoLeft <= 0;
  const say = (text) => ctx.events.emit('voice', { text, speaker: 'Narrator' });

  function loadSynth() {
    if (!synthPromise) synthPromise = import('../motion/synth.js').catch(() => null);
    return synthPromise;
  }

  // ---- panel ----
  function paintSens(v) {
    el.sens.value = String(v);
    el.sensOut.textContent = `${(+v).toFixed(2)}×`;
    el.sens.style.backgroundSize = `${(((v - SENSITIVITY_MIN) / (SENSITIVITY_MAX - SENSITIVITY_MIN)) * 100).toFixed(1)}% 3px, 100% 3px`;
  }

  function renderStep() {
    const motion = motionMode();
    root.classList.toggle('is-motion', motion);
    root.classList.toggle('is-left', !!settings.leftHanded);
    el.num.textContent = `${ROMAN[index]} / ${ROMAN[STEPS.length - 1]}`;
    el.title.textContent = step.title;
    el.text.textContent = motion ? step.motion : step.touch;
    el.pips.innerHTML = '<i></i>'.repeat(step.n);
    root.classList.toggle('has-confirm', !!step.confirm);
    root.classList.toggle('has-demo', !!step.demo);
    root.classList.remove('is-done');
    el.calib.classList.remove('is-shown');
    paintSens(settings.sensitivity);
    renderCount();
    setHint('');
  }

  function renderCount() {
    const pips = el.pips.children;
    for (let i = 0; i < pips.length; i++) pips[i].classList.toggle('is-on', i < count);
  }

  function setHint(text, seconds = 2.6) {
    el.hint.textContent = text;
    el.hint.classList.toggle('is-shown', !!text);
    hintLeft = text ? seconds : 0;
  }

  function showGesture(kind, dir, power, label) {
    let glyph = ARROW;
    let rot = 0;
    let words = '';
    if (kind === 'slash') {
      rot = -dir; // screen-y is down
      const tok = dirToken(dir);
      words = `Slash ${WORD[tok]}`;
    } else {
      glyph = ICON[kind] || ICON.thrust;
      words = label || kind.charAt(0).toUpperCase() + kind.slice(1);
    }
    const p = Number.isFinite(power) ? ` · ${Math.round(power * 100)}%` : '';
    el.lastG.innerHTML = glyph;
    el.lastG.style.transform = `rotate(${rot.toFixed(3)}rad)`;
    el.lastT.textContent = words + p;
    el.bigG.innerHTML = glyph;
    el.bigG.style.transform = `rotate(${rot.toFixed(3)}rad)`;
    el.bigT.textContent = words;
    el.big.classList.remove('is-shown');
    bigLeft = 0.9;
    pendingBig = true;
  }

  // ---- step flow ----
  function goTo(i) {
    leaveStep();
    index = i;
    if (index >= STEPS.length) {
      finish();
      return;
    }
    step = STEPS[index];
    if (step.motionOnly && !motionMode()) {
      goTo(index + 1);
      return;
    }
    count = 0;
    doneWait = 0;
    sub = { r: 0, l: 0, switched: false, thrusts: 0, thrown: false, dirs: [] };
    peaks = [];
    cands = [];
    calibrating = false;
    expects = [];
    enterStep();
    renderStep();
    say(step.voice);
  }

  function enterStep() {
    if (step.trainer) ensureTrainer();
    if (step.id === 'volley') {
      volleyWait = 2.0;
      volleyHit = false;
      volleyLive = false;
    }
    if (step.id === 'spear') {
      if (ctx.player && ctx.player.weapon === 'spear') sub.switched = true;
      // The throw is at the far target: never lock onto the near dummy.
      if (dummy) dummy.noThrowLock = true;
    }
  }

  function leaveStep() {
    // The trainer only lives through the shield steps.
    const next = STEPS[index + 1];
    if (step && step.trainer && !(next && next.trainer) && trainer) removeTrainer();
    volleyWait = 0;
    if (step && step.id === 'spear') stepDummyAside(false);
  }

  // Moves the near dummy out of the far target's line (eased in update) or back to its place.
  function stepDummyAside(aside) {
    dummyGoalX = aside ? ASIDE_X : NEAR.x;
    if (dummy && !aside) dummy.noThrowLock = false;
  }

  function slideDummy(dt) {
    const p = dummy && dummy.pos;
    if (!p || p.x === dummyGoalX) return;
    const d = dummyGoalX - p.x;
    const stepX = ASIDE_SPEED * dt;
    p.x = Math.abs(d) <= stepX ? dummyGoalX : p.x + Math.sign(d) * stepX;
  }

  function complete() {
    if (doneWait > 0) return;
    count = step.n;
    renderCount();
    root.classList.add('is-done');
    doneWait = 1.3;
    say(PRAISE[praiseI++ % PRAISE.length]);
  }

  function progress(text) {
    if (doneWait > 0) return;
    count = Math.min(step.n, count + 1);
    renderCount();
    if (count >= step.n) complete();
    else if (text) setHint(text, 1.6);
  }

  function finish() {
    finished = true;
    root.classList.add('is-finished');
    say('You are ready. To the Hot Gates.');
  }

  // ---- actors ----
  function spawnDummies() {
    const ai = ctx.ai;
    if (!ai || !ai.spawnDummy) return;
    dummy = ai.spawnDummy(NEAR.x, NEAR.z, {});
    farDummy = ai.spawnDummy(FAR.x, FAR.z, { far: true });
    dummyGoalX = NEAR.x;
  }

  function ensureTrainer() {
    if (trainer && trainer.alive !== false) return;
    if (ctx.ai && ctx.ai.spawnEnemy) trainer = ctx.ai.spawnEnemy('levy', 0, -1.6, { trainer: true });
  }

  function removeTrainer() {
    trainer = null;
    // ai has no single-actor removal: clear and put the dummies back.
    if (ctx.ai && ctx.ai.clear) {
      ctx.ai.clear();
      spawnDummies();
    }
  }

  // ---- events ----
  const ev = ctx.events;
  ev.on('gesture', (g) => {
    if (!active || !g) return;
    showGesture(g.type, g.dir, g.power, g.type === 'windup' ? 'Wind-up' : '');
  });

  // Calibration counts every swing-like motion, slash or not: a gentle swinger below the
  // thresholds still calibrates (the 'gesture' slashes would never come).
  ev.on('swingCandidate', (c) => {
    if (!active || !c || !counting() || step.id !== 'calibrate' || calibrating) return;
    cands.push({ peak: c.peak || 0, t0: Number.isFinite(c.t0) ? c.t0 : c.t, t: c.t });
    candAt = ctx.time.now;
    calibrateFromCandidates();
  });

  /**
   * Counts the strokes among the candidates whose neighbours are all known: a later one started
   * more than STROKE_GAP after it ended, or that long has passed (real time) since the last one.
   */
  function calibrateFromCandidates() {
    const n = cands.length;
    const quiet = ctx.time.now - candAt > STROKE_GAP + 0.05;
    const out = [];
    for (let i = 0; i < n; i++) {
      const known = quiet || cands[n - 1].t0 > cands[i].t + STROKE_GAP;
      if (known && isStroke(cands, i)) out.push(cands[i].peak);
    }
    peaks = out;
    count = Math.min(step.n, peaks.length);
    renderCount();
    if (peaks.length < step.n) return;
    calibrating = true;
    suggestion = suggestSensitivity(peaks, CALIBRATION);
    const med = peaks.slice().sort((a, b) => a - b)[peaks.length >> 1];
    el.calibT.textContent = `Your swings peak near ${med.toFixed(1)} rad/s. Suggested sensitivity ${suggestion.toFixed(2)}× (now ${(+settings.sensitivity).toFixed(2)}×).`;
    el.calib.classList.add('is-shown');
    root.classList.add('is-calib');
  }

  /**
   * Mirrored-setup check (motion only): in the directional steps, each slash is compared with
   * what the instruction asks for at that moment. If a step's first three telling slashes all went
   * the opposite way, the matching setting (motionFlipX / motionFlipY) is toggled and saved; one
   * that went the asked way ends the check for the step (a player who only took the sides in another
   * order is not mirrored). "Right and left" asks right until two rights have counted, then left;
   * the overhead step D; the uppercut step U.
   */
  function checkMirrored(s, tok) {
    const id = step.id;
    if (s.src !== 'motion' || (id !== 'slashLR' && id !== 'overhead' && id !== 'uppercut')) return false;
    let opposite;
    if (id === 'slashLR') {
      if (!TOKEN_SETS.Rish.has(tok) && !TOKEN_SETS.Lish.has(tok)) return false;
      const want = sub.r < 2 ? 'R' : 'L';
      opposite = want === 'R' ? TOKEN_SETS.Lish.has(tok) : TOKEN_SETS.Rish.has(tok);
    } else {
      if (!TOKEN_SETS.Uish.has(tok) && !TOKEN_SETS.Dish.has(tok)) return false;
      opposite = id === 'overhead' ? TOKEN_SETS.Uish.has(tok) : TOKEN_SETS.Dish.has(tok);
    }
    if (sub.dirs.length >= 3 || sub.dirs.includes(false)) return false;
    sub.dirs.push(opposite);
    if (sub.dirs.length < 3 || sub.dirs.includes(false)) return false;
    const key = id === 'slashLR' ? 'motionFlipX' : 'motionFlipY';
    settings[key] = !settings[key];
    if (ctx.saveSettings) ctx.saveSettings();
    if (ctx.gestures && ctx.gestures.setFlip) ctx.gestures.setFlip(!!settings.motionFlipX, !!settings.motionFlipY);
    // the slashes counted so far were mirrored: count them the right way round
    if (id === 'slashLR') {
      const r = sub.r;
      sub.r = sub.l;
      sub.l = r;
    } else {
      count = 0;
      renderCount();
    }
    sub.dirs = [];
    setHint('Swing direction corrected', 3);
    return true;
  }

  ev.on('swing', (s) => {
    if (!active || !s) return;
    if (s.src !== 'motion') showGesture(s.kind === 'spearThrust' ? 'thrust' : s.kind, s.dir, s.power, s.kind === 'spearThrust' ? 'Spear thrust' : '');
    if (!counting()) return;
    const id = step.id;
    if (s.kind === 'slash') {
      const tok = dirToken(s.dir);
      if (checkMirrored(s, tok)) return;
      if (id === 'slashLR') {
        if (TOKEN_SETS.Rish.has(tok)) {
          if (sub.r < 2) {
            sub.r++;
            progress(sub.r === 2 ? 'Now to the left.' : '');
          } else setHint('Now to the left.');
        } else if (TOKEN_SETS.Lish.has(tok)) {
          if (sub.l < 2) {
            sub.l++;
            progress(sub.l === 2 && sub.r < 2 ? 'Now to the right.' : '');
          } else setHint('Now to the right.');
        } else setHint('Sideways: right, or left.');
      } else if (id === 'overhead') {
        if (tok === 'D') progress('');
        else if (TOKEN_SETS.Dish.has(tok)) setHint('Straighter. Bring it straight down.');
        else setHint('From above: chop straight down.');
      } else if (id === 'uppercut') {
        if (TOKEN_SETS.Uish.has(tok)) progress('');
        else setHint('Start low and swing upward.');
      }
    } else if (s.kind === 'thrust' || s.kind === 'spearThrust') {
      if (id === 'thrust') progress('');
      else if (id === 'spear' && s.kind === 'spearThrust' && sub.thrusts < 2) {
        sub.thrusts++;
        if (sub.thrusts === 2) stepDummyAside(true);
        progress(sub.thrusts === 2 ? 'Now throw it at the far target.' : '');
      } else if (id === 'spear' && !sub.switched) setHint('Take the spear first.');
    } else if (s.kind === 'bash') {
      if (id === 'bash') progress('');
      else if (id === 'thrust') setHint('Lower the shield to stab.');
    } else if (s.kind === 'throw' && id === 'spear') {
      sub.thrown = true;
      if (sub.thrusts < 2) setHint('Thrust twice before you throw.');
    }
  });

  ev.on('weaponSwitch', (w) => {
    if (!active || !w || !counting() || step.id !== 'spear') return;
    if (w.weapon === 'spear' && !sub.switched) {
      sub.switched = true;
      progress('Now thrust. Twice.');
    }
  });

  ev.on('spearImpact', (e) => {
    if (!active || !e || !counting() || step.id !== 'spear') return;
    const p = e.point;
    const near = (farDummy && e.target === farDummy) || (p && Math.hypot(p.x - FAR.x, p.z - FAR.z) <= 2);
    if (near) {
      if (!sub.switched) sub.switched = true;
      sub.thrusts = Math.max(sub.thrusts, 2);
      complete();
    } else setHint('Wide. Another spear is drawn: aim at the far target.');
  });

  ev.on('enemyWindup', (e) => {
    if (!active || !e || !e.targetIsPlayer || !step.trainer) return;
    expects.push(ctx.time.gnow + (e.timeToStrike || 1));
  });

  ev.on('block', (b) => {
    if (!active || !b || b.by !== 'player') return;
    expects.length = 0;
    if (!counting()) return;
    if (step.id === 'block') progress('Blocked.');
    else if (step.id === 'perfect') {
      if (b.perfect) progress('Perfect.');
      else setHint('Blocked, but too early. Wait for the ring to close.');
    }
  });

  ev.on('volley', (v) => {
    if (!active || !v || step.id !== 'volley') return;
    if (v.phase === 'warning') volleyLive = true;
    if (v.phase === 'end' && volleyLive) {
      volleyLive = false;
      if (!counting()) return;
      if (!volleyHit) progress('');
      else {
        setHint('Arrows got through. Hold the shield until they have fallen.');
        volleyWait = 2.5;
      }
    }
  });

  ev.on('arrowHit', (a) => {
    if (active && a && a.target === 'player' && step.id === 'volley') volleyHit = true;
  });

  ev.on('combo', (c) => {
    if (!active || !c || !counting()) return;
    if (step.id === 'twin' && c.id === 'twin') complete();
  });

  // ---- buttons ----
  root.addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b || !active) return;
    if (ctx.audio && ctx.audio.play) ctx.audio.play('uiTap');
    switch (b.dataset.act) {
      case 'ok':
        complete();
        break;
      case 'skip':
        goTo(index + 1);
        break;
      case 'demo':
        api.demo(index);
        break;
      case 'apply':
        settings.sensitivity = suggestion;
        if (ctx.gestures && ctx.gestures.setSensitivity) ctx.gestures.setSensitivity(suggestion);
        ctx.saveSettings();
        paintSens(suggestion);
        root.classList.remove('is-calib');
        el.calib.classList.remove('is-shown');
        complete();
        break;
      case 'keep':
        root.classList.remove('is-calib');
        el.calib.classList.remove('is-shown');
        complete();
        break;
      case 'battle':
        ctx.setMode('battle', { day: 1 });
        break;
      case 'again':
        ctx.setMode('training', {});
        break;
      case 'title':
        ctx.setMode('title');
        break;
      default:
        break;
    }
  });
  el.sens.addEventListener('input', () => {
    const v = clamp(parseFloat(el.sens.value), SENSITIVITY_MIN, SENSITIVITY_MAX);
    settings.sensitivity = v;
    if (ctx.gestures && ctx.gestures.setSensitivity) ctx.gestures.setSensitivity(v);
    paintSens(v);
  });
  el.sens.addEventListener('change', () => ctx.saveSettings());

  function screenRotation() {
    const o = typeof screen !== 'undefined' && screen.orientation;
    let a = o && Number.isFinite(o.angle) ? o.angle : Number(window.orientation);
    if (!Number.isFinite(a)) a = 90;
    return ((Math.round(a / 90) * 90) % 360 + 360) % 360;
  }

  function updateMeters(dt) {
    const show = motionMode() || ctx.motionDemo;
    root.classList.toggle('has-meters', !!show);
    if (!show) return;
    meterClock -= dt;
    if (meterClock > 0) return;
    meterClock = 1 / 20;
    const d = (ctx.gestures && ctx.gestures.debug) || {};
    const s = clamp(settings.sensitivity || 1, SENSITIVITY_MIN, SENSITIVITY_MAX);
    el.mm.style.transform = `scaleX(${clamp((d.m || 0) / 12, 0, 1).toFixed(3)})`;
    el.mf.style.transform = `scaleX(${clamp((d.fwdAcc || 0) / 30, 0, 1).toFixed(3)})`;
    // the mark is where a swing becomes a slash at this sensitivity
    el.mt.style.transform = `translateX(${clamp((slashThreshold(s) / 12) * 100, 0, 100).toFixed(1)}%)`;
    el.ft.style.transform = `translateX(${clamp((GESTURE_DEFAULTS.thrustAcc / s / 30) * 100, 0, 100).toFixed(1)}%)`;
  }

  const api = {
    start() {
      active = true;
      finished = false;
      praiseI = 0;
      root.classList.add('is-on');
      root.classList.remove('is-finished', 'is-calib');
      const w = ctx.world;
      if (w) {
        if (w.setTimeOfDay) w.setTimeOfDay('morning');
        if (w.setArmyDensity) w.setArmyDensity(0);
      }
      if (ctx.audio) {
        if (ctx.audio.setAmbience) ctx.audio.setAmbience('calm');
        if (ctx.audio.setMusic) ctx.audio.setMusic('none');
      }
      trainer = null;
      spawnDummies();
      loadSynth();
      step = null;
      index = -1;
      goTo(0);
    },
    stop() {
      if (ctx.motionDemo && demoLeft > 0) ctx.motionDemo = false;
      active = false;
      demoLeft = 0;
      demoShield = false;
      doneWait = 0;
      volleyWait = 0;
      trainer = null;
      dummy = farDummy = null;
      expects = [];
      root.classList.remove('is-on', 'is-finished', 'is-calib', 'is-done');
      el.big.classList.remove('is-shown');
    },
    update(dt) {
      if (!active) return;
      if (pendingBig) {
        pendingBig = false;
        el.big.classList.add('is-shown');
      }
      if (bigLeft > 0) {
        bigLeft -= dt;
        if (bigLeft <= 0) el.big.classList.remove('is-shown');
      }
      if (hintLeft > 0) {
        hintLeft -= dt;
        if (hintLeft <= 0) el.hint.classList.remove('is-shown');
      }
      if (demoLeft > 0) {
        // Injected samples are released in real time; the demo ends once the last one has been
        // delivered (and the recognizer has had a moment), or at the cap.
        demoLeft -= dt;
        const m = ctx.platform && ctx.platform.motion;
        const delivered = !m || typeof m.lastT !== 'function' || m.lastT() >= demoEndT - 1e-6;
        if (delivered || demoTail < 0.3) demoTail -= dt;
        if (demoTail <= 0) demoLeft = 0;
        if (demoLeft <= 0) {
          demoLeft = 0;
          ctx.motionDemo = false;
          demoShield = false;
          root.classList.remove('is-demo');
        }
      }
      updateMeters(dt);
      slideDummy(dt);
      if (finished) return;
      if (step && step.id === 'calibrate' && !calibrating && cands.length && counting()) calibrateFromCandidates();
      if (doneWait > 0) {
        doneWait -= dt;
        if (doneWait <= 0) goTo(index + 1);
        return;
      }
      if (step.trainer) {
        if (trainer && trainer.alive === false) {
          trainer = null;
          trainerWait = 1.5;
        }
        if (!trainer) {
          trainerWait -= dt;
          if (trainerWait <= 0) ensureTrainer();
        }
        // A telegraphed strike that landed without a block.
        const now = ctx.time.gnow;
        for (let i = expects.length - 1; i >= 0; i--) {
          if (now > expects[i] + 0.35) {
            expects.splice(i, 1);
            if (step.id === 'block' || step.id === 'perfect') setHint('Too late. Raise the shield before the blow lands.');
          }
        }
      }
      if (volleyWait > 0) {
        volleyWait -= dt;
        if (volleyWait <= 0 && ctx.combat && ctx.combat.volley) {
          volleyHit = false;
          ctx.combat.volley({ count: 140, duration: 1.0, warn: 2.5 });
        }
      }
    },
    /** Plays a synthetic motion demo of a step (index or id), even in touch mode. */
    async demo(stepId = index) {
      const def = typeof stepId === 'number' ? STEPS[stepId] : STEPS.find((s) => s.id === stepId);
      if (!def || !def.demo || !active) return false;
      const mod = await loadSynth();
      const motion = ctx.platform && ctx.platform.motion;
      if (!mod || !mod.synthesize || !motion || !active) {
        setHint('The demonstration is unavailable.');
        return false;
      }
      if (def.id === 'spear' && ctx.player && ctx.player.weapon !== 'spear') ctx.actions.push({ type: 'switchWeapon', weapon: 'spear', src: 'test' });
      if (def.id === 'spear' && step && step.id === 'spear') stepDummyAside(true); // the demo throws at the far target
      const script = def.demo();
      const info = motion.info ? motion.info() : null;
      const samples = mod.synthesize(script, {
        rot: screenRotation(),
        hand: settings.leftHanded ? 'left' : 'right',
        gyroRange: (info && info.gyroRange) || 34.9,
        seed: 7 + index,
      });
      ctx.motionDemo = true;
      demoShield = def.id === 'bash';
      // inject() re-bases the samples to start just after lastT(), keeping their spacing.
      const t0 = motion.lastT ? motion.lastT() : 0;
      motion.inject(samples);
      const span = samples.length ? samples[samples.length - 1].t - samples[0].t : 0;
      demoEndT = t0 + 0.01 + span;
      const dur = mod.scriptDuration ? mod.scriptDuration(script) : span;
      demoTail = 0.3;
      demoLeft = dur + 4;
      root.classList.add('is-demo');
      return true;
    },
    get active() {
      return active;
    },
    get stepIndex() {
      return index;
    },
    /** True while a bash demo runs: input.js treats it as a held shield. */
    get demoShield() {
      return demoShield && demoLeft > 0;
    },
  };
  return api;
}
