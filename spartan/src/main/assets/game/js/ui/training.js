// ui/training.js: the Agoge, guided training and calibration (DESIGN.md §11 ui/*).
// A UI-driven script: it spawns dummies and a trainer through ctx.ai, calls ctx.combat.volley,
// counts events, and speaks as the Narrator. Its panel lives in its own #training root (z 25),
// above #controls so its buttons work in the motion layout.
// The hilt grip (docs-hilt-grip.md §G) runs its own step list (agogeSteps): every step has a hilt
// instruction and demo, the grip step checks the guard and the grip, and a `lock` step teaches the
// thumb swipe. Touch and the window grip keep STEPS exactly.
import { clamp, angleDiff, yawTo } from '../core/math.js';
import { effectiveGrip, sensitivityKey, flipKeys } from '../core/grip.js';
import { dirToken, TOKEN_SETS } from '../game/rules.js';
import { GESTURE_DEFAULTS, SENSITIVITY_MIN, SENSITIVITY_MAX, slashThreshold } from '../motion/gestures.js';
import { DIAGRAMS } from './menus.js';
import { INPUT } from './input.js';

const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII', 'XIII'];
const WORD = { R: 'right', UR: 'up-right', U: 'up', UL: 'up-left', L: 'left', DL: 'down-left', D: 'down', DR: 'down-right' };
const NEAR = { x: 0, z: -2.6 };
const FAR = { x: 1.5, z: -18 };
// In step XI the near dummy hid the far target (and stole the throw lock): once the two thrusts
// are done it steps aside to ASIDE_X, out of the line and the 0.262 rad lock cone, and comes back after.
const ASIDE_X = -1.4;
const ASIDE_SPEED = 3.5; // m/s
// The hilt lock step's two extra dummies, either side of the near one. From the near dummy a right
// swipe ranks the right one first (lockon.js), and the far target hides behind its sideGap.
const FLANKS = [{ x: -1.6, z: -2.2 }, { x: 1.6, z: -2.2 }];
/**
 * The hilt grip step's guard and grip check (§G): the guard held still for `time` s with no touch on
 * the glass. A touch held `resting` s is a resting finger; within `near` px of the finger-side edge
 * the finger strip grows to its distance + `pad` (at most INPUT.hiltFingerStripMax). Without a
 * learned guard, the "hold it upright" hint repeats every `remind` s.
 */
const GRIP_CHECK = Object.freeze({ time: 3, resting: 0.5, near: 160, pad: 16, remind: 6 });
// The hilt calibrate and slashLR steps: at most one "windscreen wiper" hint per this long (s), and a
// swing candidate within SLASH_NEAR s of a slash is that slash's own run or its return, not a slow cut.
const WIPER_GAP = 4;
const SLASH_NEAR = 1;

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
 * The steps (§11). `n` repetitions complete a step; `motion`/`touch`/`buttons` are the
 * instructions (the buttons control falls back to the touch text).
 * `demo` returns a synth script, or is null when the move isn't a phone motion.
 * `hilt` and `demoHilt` are the hilt grip's instruction and demo (synthesized with hold 'hilt';
 * docs-hilt-grip.md §G). The hilt overhead is a chop straight from the guard (a cock-back would be
 * the spear's wind-up), and its uppercut starts from a lowered blade.
 */
export const STEPS = [
  { id: 'grip', title: 'Grip and safety', n: 1, confirm: true,
    motion: 'Hold the phone in landscape in your sword hand, screen toward you. Grip it firmly, clear the space around you, and never let go.',
    hilt: 'Hold the phone upright in your sword hand like the hilt of a xiphos, screen toward you. Rest your thumb on the edge; your fingers stay on the back, off the glass. Grip firmly; a wrist strap is strongly recommended. Clear the space around you, behind you too. Then hold your guard still.',
    touch: 'Left thumb: the stick moves you, SHIELD blocks. Right thumb: swipe to slash, tap to stab, drag slowly to look.',
    buttons: 'Left thumb: the arrows step you, SHIELD blocks, BASH bashes. Right thumb: the sword pad cuts the way each arrow points; STAB is in the middle. The view turns with your foe: tap another foe to face him.',
    voice: 'Welcome to the Agoge. Take up your arms.', demo: null, demoHilt: null },
  { id: 'calibrate', title: 'Calibrate', n: 5, motionOnly: true,
    motion: 'Slash 5 times, the way you will in battle. Gentle is fine.', touch: '',
    hilt: 'Cut 5 times the way you will in battle: let the top of the phone lead, like a blade. A firm flick is enough.',
    voice: 'Strike five times, as you would in battle.',
    demo: () => [idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 0 }), idle(0.3), swing(0), idle(0.3)],
    demoHilt: () => [idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 0 }), idle(0.3), swing(0), idle(0.3)] },
  { id: 'slashLR', title: 'Right and left', n: 4,
    motion: 'Swing right twice, then left twice.', touch: 'Swipe right twice, then left twice.', buttons: 'Tap → twice, then ← twice.',
    hilt: 'Cut right twice, then left twice: sweep the top of the phone across, like a windscreen wiper.',
    voice: 'Cut to the right. Then back to the left.',
    demo: () => [idle(0.3), swing(0, { ret: 4 }), idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 4 }), idle(0.3), swing(PI), idle(0.3)],
    demoHilt: () => [idle(0.3), swing(0, { ret: 4 }), idle(0.3), swing(0, { ret: 0 }), idle(0.3), swing(PI, { ret: 4 }), idle(0.3), swing(PI), idle(0.3)] },
  { id: 'overhead', title: 'The overhead chop', n: 2,
    motion: 'Raise the phone, then chop straight down. Twice. It goes over a wicker shield.', touch: 'Swipe straight down. Twice. It goes over a wicker shield.',
    buttons: 'Tap ↓ to chop straight down. Twice. It goes over a wicker shield.',
    hilt: 'Chop straight down from your guard: tip the top of the phone forward and down. Twice. It goes over a wicker shield.',
    voice: 'Bring it down from above. The chop goes over their shields.',
    demo: () => [idle(0.3), { kind: 'raise' }, swing(-PI / 2), idle(0.5), { kind: 'raise' }, swing(-PI / 2), idle(0.3)],
    demoHilt: () => [idle(0.3), swing(-PI / 2), idle(0.5), swing(-PI / 2), idle(0.3)] },
  { id: 'uppercut', title: 'The uppercut', n: 1,
    motion: 'From a low guard, swing upward.', touch: 'Swipe up.', buttons: 'Tap ↑ to cut upward.',
    hilt: 'Lower the blade slowly toward the ground ahead, then swing it up.',
    voice: 'From low, strike upward.',
    demo: () => [idle(0.3), swing(PI / 2, { windup: 0.4 }), idle(0.3)],
    demoHilt: () => [idle(0.3), { kind: 'lower', beta: 0.1 }, idle(0.3), swing(PI / 2), idle(0.3)] },
  { id: 'thrust', title: 'The thrust', n: 3,
    motion: 'Push the phone straight forward, then draw it back. Three times.', touch: 'Tap to stab. Three times.', buttons: 'Tap STAB. Three times.',
    hilt: 'Punch the phone straight forward, then draw it back. Three times.',
    voice: 'Thrust. Short and straight.',
    demo: () => [idle(0.3), { kind: 'thrust' }, idle(0.4), { kind: 'thrust' }, idle(0.4), { kind: 'thrust' }, idle(0.3)],
    demoHilt: () => [idle(0.3), { kind: 'thrust' }, idle(0.4), { kind: 'thrust' }, idle(0.4), { kind: 'thrust' }, idle(0.3)] },
  { id: 'block', title: 'Block', n: 3, trainer: true,
    motion: 'Press the glass with your thumb to raise the shield before each blow lands.', touch: 'Hold SHIELD before each blow lands.',
    hilt: 'Press the glass with your thumb to raise the shield before each blow lands. Lift it to lower the shield.',
    voice: 'A Persian comes at you. Shield up before he strikes.', demo: null, demoHilt: null },
  { id: 'perfect', title: 'The perfect block', n: 2, trainer: true,
    motion: 'Raise the shield at the last moment, as the red ring closes. Twice.', touch: 'Press SHIELD at the last moment, as the red ring closes. Twice.',
    hilt: 'Press at the last moment, as the red ring around his head closes. Twice.',
    voice: 'Now raise it at the last moment. Let the ring close.', demo: null, demoHilt: null },
  { id: 'bash', title: 'Shield bash', n: 2, trainer: true,
    motion: 'Shield up, then push the phone forward. Twice.', touch: 'Tap BASH, or swipe up from SHIELD. Twice.', buttons: 'Tap BASH. Twice.',
    hilt: 'Thumb on the glass, then punch forward. Twice.',
    voice: 'Shield up, and drive it into him.',
    demo: () => [idle(0.4), { kind: 'thrust' }, idle(0.5), { kind: 'thrust' }, idle(0.3)],
    demoHilt: () => [idle(0.4), { kind: 'thrust' }, idle(0.5), { kind: 'thrust' }, idle(0.3)] },
  { id: 'volley', title: 'Arrows', n: 1,
    motion: 'When the archers loose, keep your thumb on the glass until the arrows have fallen.', touch: 'When the archers loose, hold SHIELD until the arrows have fallen.',
    hilt: 'When the archers loose, keep your thumb on the glass until the arrows have fallen.',
    voice: 'Archers! Shields up, and hold.', demo: null, demoHilt: null },
  { id: 'spear', title: 'The dory', n: 4,
    motion: 'Take the spear with the weapon button. Thrust twice. Then raise it beside your head and throw at the far target. Never let go.',
    touch: 'Take the spear with the weapon button. Tap twice to thrust. Then hold THROW and release at the far target.',
    buttons: 'Take the spear with the weapon button. Tap STAB twice. Then hold THROW and release: it flies at the far target.',
    // The weapon swap is the top button only (the lead's decision: no thumb flick).
    hilt: 'Tap the weapon button at the top to take the spear. Thrust twice. Then cock the phone back behind your shoulder like a javelin, and snap it forward, fist closed.',
    voice: 'Take up the spear.',
    demo: () => [idle(0.9), { kind: 'thrust' }, idle(0.45), { kind: 'thrust' }, idle(0.6), { kind: 'throw' }, idle(0.4)],
    demoHilt: () => [idle(0.9), { kind: 'thrust' }, idle(0.45), { kind: 'thrust' }, idle(0.6), { kind: 'throw' }, idle(0.4)] },
  { id: 'twin', title: 'Twin Fangs', n: 1,
    motion: 'Slash right, then left, fast, into the dummy.', touch: 'Swipe right, then left, fast.', buttons: 'Tap →, then ←, fast.',
    hilt: 'Cut right, then left, fast, into the dummy.',
    voice: 'Right and left, quick as a viper. Twin Fangs.',
    demo: () => [idle(0.3), swing(0, { arc: 1.0, speed: 9 }), swing(PI, { arc: 1.0, speed: 9 }), idle(0.4)],
    demoHilt: () => [idle(0.3), swing(0, { arc: 1.0, speed: 9 }), swing(PI, { arc: 1.0, speed: 9 }), idle(0.4)] },
];

/**
 * The hilt grip's extra step, between the thrust and the block (§G): the lock-on and the thumb
 * swipe (the buttons control's target buttons). It is not in STEPS, so touch and the window grip
 * keep their 12 steps and indices.
 */
export const HILT_LOCK_STEP = Object.freeze({
  id: 'lock', title: 'Change foe', n: 2, hiltOnly: true,
  hilt: 'Swipe your thumb right to turn to the next foe. Then swipe left.',
  motion: 'Swipe your thumb right to turn to the next foe. Then swipe left.', touch: '',
  buttons: 'Tap a foe to face him, or tap FOE › to turn to the next one on your right. Then turn back with ‹ FOE.',
  voice: 'Turn to the next man with your thumb. Then turn back.', demo: null, demoHilt: null,
});

const HILT_STEPS = Object.freeze([...STEPS.slice(0, 6), HILT_LOCK_STEP, ...STEPS.slice(6)]);

/**
 * The Agoge's runtime step list for a grip (effectiveGrip) or 'buttons': the lock-on controls (the
 * hilt grip and the buttons) get the lock step at index 6 (13 steps); touch and the window grip get
 * STEPS itself.
 * @param {'hilt'|'window'|'buttons'|null} grip
 * @returns {ReadonlyArray<object>}
 */
export function agogeSteps(grip) {
  return grip === 'hilt' || grip === 'buttons' ? HILT_STEPS : STEPS;
}

const PRAISE = ['Good.', 'Well struck.', 'Again, that is the way.', 'Good. Like a Spartan.', 'Yes.'];
const ARROW = '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M-34 0H22" stroke="currentColor" stroke-width="9" stroke-linecap="round"/><path d="M14-19 38 0 14 19Z" fill="currentColor"/></svg>';
const ICON = {
  thrust: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><circle r="30" fill="none" stroke="currentColor" stroke-width="6"/><circle r="15" fill="none" stroke="currentColor" stroke-width="5"/><circle r="5" fill="currentColor"/></svg>',
  bash: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><circle r="34" fill="none" stroke="currentColor" stroke-width="7"/><circle r="22" fill="none" stroke="currentColor" stroke-width="3" opacity=".6"/><circle r="7" fill="currentColor"/></svg>',
  throw: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M-30 26C-30-20 10-34 30-10" fill="none" stroke="currentColor" stroke-width="7" stroke-linecap="round"/><path d="M18-22 38 0 12 2Z" fill="currentColor"/></svg>',
  windup: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M0 34V-22" stroke="currentColor" stroke-width="8" stroke-linecap="round"/><path d="M-18-12 0-38 18-12Z" fill="currentColor"/></svg>',
  // The hilt lock step: a thumb on the glass swiping right, then left (SMIL, so no stylesheet rule).
  swipe: '<svg viewBox="-50 -50 100 100" aria-hidden="true"><path d="M10-40H26V-48L44-34 26-20V-28H10Z" fill="currentColor"/><path d="M-10-40H-26V-48L-44-34-26-20V-28H-10Z" fill="currentColor"/>'
    + '<g><path d="M-14 46C-14 22-13 2-10-5Q0-18 10-5C13 2 14 22 14 46Z" fill="currentColor"/><path d="M-6-2Q0-9 6-2V6Q0 9-6 6Z" fill="none"/>'
    + '<animateTransform attributeName="transform" type="translate" values="0 0;18 0;0 0;-18 0;0 0" keyTimes="0;0.25;0.5;0.75;1" dur="2.2s" repeatCount="indefinite"/></g></svg>',
};

/**
 * The resting pictures of the hilt grip (§G), shown while no move's glyph is: the grip step's hilt
 * diagram, the "sweep the top" diagram for slow cuts (calibrate, slashLR), and the animated thumb of
 * the lock step. A `card` is a menus.js diagram, black-figure on terracotta like the how-to cards,
 * hung under the panel on the finger side (the panel's height varies with the phone's width, so a
 * card in the fixed glyph slot would slip under it); the others sit in the big glyph slot.
 */
const FIGURES = {
  grip: { card: true, html: DIAGRAMS.hiltGrip, label: '' },
  cut: { card: true, html: DIAGRAMS.hiltCut, label: '' },
  swipe: { card: false, html: ICON.swipe, label: 'Swipe' },
};
// The card's frame: the how-to card's look (inline: style.css has no rule for it yet).
const CARD_STYLE = 'display:none;position:absolute;top:0;width:150px;pointer-events:none;'
  + 'background:radial-gradient(ellipse at 50% 40%,var(--terracotta-hi),var(--terracotta) 70%);'
  + 'border:1px solid var(--edge);box-shadow:0 6px 18px rgba(0,0,0,0.5)';
const CARD_GAP = 14; // px between the panel and the card
// The grip check's mark where something rests on the glass.
const DOT_STYLE = 'display:none;position:absolute;left:0;top:0;width:36px;height:36px;margin:-18px 0 0 -18px;'
  + 'box-sizing:border-box;border:3px solid #ff6a4a;border-radius:50%;pointer-events:none;'
  + 'box-shadow:0 0 0 2px rgba(0,0,0,0.6),0 0 16px #ff6a4a';

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
    <div class="tr-fig" style="${CARD_STYLE}"></div>
    <i class="tr-dot" style="${DOT_STYLE}"></i>
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
    card: q('.tr-fig'), dot: q('.tr-dot'), head: q('.tr-head'),
  };

  // ---- state ----
  let active = false;
  let grip = null;          // listKey() the step list was built for: the effective grip, or 'buttons'
  let steps = STEPS;        // the runtime step list: agogeSteps(grip)
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
  // the hilt grip (§G)
  let figure = null;        // the big slot's resting picture (FIGURES key), shown while no glyph is
  let figureLeft = 0;       // s it stays (Infinity: the whole step)
  let figurePainted = null;
  let check = null;         // the grip step's guard and grip check, once "I'm ready" was tapped
  let expanded = false;     // the collapsed panel was opened by a tap on its head row (until the next step)
  let fastHint = false;     // an over-speed swing this frame: its hint goes up after the frame's events
  let lastSlashAt = -Infinity; // ctx.time.now of the last slash gesture
  let wiperAt = -Infinity;  // ctx.time.now of the last "windscreen wiper" hint
  let demoClock = 0;        // s since the demo started
  let demoCue = -1;         // > 0: the hilt spear demo locks the far target at this demoClock

  const motionMode = () => settings.control === 'motion';
  const hiltGrip = () => grip === 'hilt';
  const buttonsMode = () => grip === 'buttons';
  // The lock-on controls: the view faces the lock, and strikes and throws go at it.
  const lockMode = () => grip === 'hilt' || grip === 'buttons';
  // The step list's key: the effective grip, or 'buttons' (agogeSteps).
  const listKey = () => effectiveGrip(settings) || (settings.control === 'buttons' ? 'buttons' : null);
  const counting = () => active && !finished && doneWait <= 0 && !ctx.motionDemo && demoLeft <= 0;
  const say = (text) => ctx.events.emit('voice', { text, speaker: 'Narrator' });
  // The effective grip's instruction and demo (touch reads the touch text, and its demos are the window grip's).
  const textOf = (s) => (buttonsMode() ? s.buttons || s.touch : !motionMode() ? s.touch : hiltGrip() && s.hilt ? s.hilt : s.motion);
  const demoOf = (s) => (hiltGrip() ? s.demoHilt || null : s.demo);
  const sensKey = () => sensitivityKey(settings);
  const spearInHand = () => !!(ctx.player && ctx.player.weapon === 'spear' && ctx.player.hasSpearInHand);
  /** The hilt grip's lock (player.setLock): the view turns to `actor`. */
  const lockTo = (actor) => {
    if (actor && ctx.player && ctx.player.setLock) ctx.player.setLock(actor);
  };

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
    root.classList.toggle('is-hilt', hiltGrip());
    root.classList.toggle('is-buttons', buttonsMode());
    root.classList.toggle('is-left', !!settings.leftHanded);
    // Collapsed during the shield steps (the stylesheet does it in portrait only): the target's head ring stays clear.
    root.classList.toggle('is-compact', !!(step.trainer || step.id === 'volley') && !expanded);
    el.num.textContent = `${ROMAN[index]} / ${ROMAN[steps.length - 1]}`;
    el.title.textContent = step.title;
    el.text.textContent = textOf(step);
    el.pips.innerHTML = '<i></i>'.repeat(step.n);
    root.classList.toggle('has-confirm', !!step.confirm);
    root.classList.toggle('has-demo', !!demoOf(step));
    root.classList.remove('is-done');
    el.calib.classList.remove('is-shown');
    paintSens(settings[sensKey()]);
    renderCount();
    setHint('');
    setFigure(!hiltGrip() ? null : step.id === 'grip' ? 'grip' : step.id === 'lock' ? 'swipe' : null);
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
    // the move's glyph takes the slot from a resting picture (painted again when it fades)
    if (figurePainted && !FIGURES[figurePainted].card) figurePainted = null;
    el.card.style.display = 'none';
    el.bigG.innerHTML = glyph;
    el.bigG.style.transform = `rotate(${rot.toFixed(3)}rad)`;
    el.bigT.textContent = words;
    el.big.classList.remove('is-shown');
    bigLeft = 0.9;
    pendingBig = true;
  }

  /** Removes the resting picture: only move glyphs show. */
  function clearFigure() {
    figure = figurePainted = null;
    figureLeft = 0;
    el.card.style.display = 'none';
  }

  /** Sets the resting picture (a FIGURES key, or null) for `seconds`. */
  function setFigure(kind, seconds = Infinity) {
    figure = kind && FIGURES[kind] ? kind : null;
    figureLeft = figure ? seconds : 0;
    if (bigLeft <= 0) paintFigure();
  }

  /** Shows the resting picture (no move's glyph is up): a card under the panel, or a glyph in the slot. */
  function paintFigure() {
    const f = figure && FIGURES[figure];
    if (f && figurePainted !== figure) {
      figurePainted = figure;
      if (f.card) {
        el.card.innerHTML = f.html;
        const svg = el.card.firstElementChild;
        if (svg) svg.style.cssText = 'display:block;width:100%;height:auto';
      } else {
        el.bigG.innerHTML = f.html;
        el.bigG.style.transform = '';
        el.bigT.textContent = f.label;
      }
    }
    const card = !!f && f.card;
    el.big.classList.toggle('is-shown', !!f && !card);
    el.card.style.display = card ? 'block' : 'none';
    if (card) placeCard();
  }

  /**
   * Hangs the card under the panel on the finger side (above the panel when that sits low, as in
   * landscape). Runs when the card shows and whenever the panel changes size (its text, the hints).
   */
  function placeCard() {
    if (el.card.style.display === 'none') return;
    const left = !!settings.leftHanded;
    el.card.style.left = left ? 'auto' : 'calc(var(--safe-l) + 10px)';
    el.card.style.right = left ? 'calc(var(--safe-r) + 10px)' : 'auto';
    const p = el.panel.getBoundingClientRect();
    const h = el.card.offsetHeight;
    const below = p.bottom + CARD_GAP;
    const top = below + h <= window.innerHeight - 10 ? below : Math.max(10, p.top - CARD_GAP - h);
    el.card.style.top = `${Math.round(top)}px`;
  }
  if (typeof ResizeObserver === 'function') new ResizeObserver(placeCard).observe(el.panel);

  // ---- the hilt grip step: guard and grip check (§G) ----
  function startGripCheck() {
    const down = ctx.inputCtl && ctx.inputCtl.lastShieldDown;
    check = { held: 0, waited: 0, seen: down ? down.t : -1, flagged: -1 };
    root.classList.remove('has-confirm');
    el.text.textContent = 'Hold your guard still, thumb on the edge.';
  }

  /**
   * Passes once the recognizer has learned the guard and nothing has touched the glass for
   * GRIP_CHECK.time. Any shield pointer restarts it; one held GRIP_CHECK.resting is a resting
   * finger, marked with the dot (and the finger strip widened to cover it on the finger side).
   */
  function stepGripCheck(dt) {
    const ip = ctx.inputCtl;
    const down = ip && ip.lastShieldDown;
    const t = down ? down.t : -1;
    const touching = !!ip && ip.shieldPointers > 0;
    if (touching || t !== check.seen) {
      check.seen = t;
      check.held = 0;
      if (touching && ctx.time.now - t >= GRIP_CHECK.resting - 1e-9 && t !== check.flagged) {
        check.flagged = t;
        flagTouch(down.x, down.y);
      }
      return;
    }
    check.held += dt;
    check.waited += dt;
    const g = ctx.gestures;
    const learned = !g || g.guardLearned !== false;
    if (learned && check.held >= GRIP_CHECK.time - 1e-9) {
      check = null;
      el.dot.style.display = 'none';
      complete();
    } else if (!learned && check.waited >= GRIP_CHECK.remind) {
      check.waited = 0;
      setHint('Hold the phone upright in front of your chest, screen toward you, and keep it still.', 4);
    }
  }

  function flagTouch(x, y) {
    el.dot.style.transform = `translate3d(${Math.round(x)}px, ${Math.round(y)}px, 0)`;
    el.dot.style.display = 'block';
    setHint('Something touches the glass here. Keep your fingers on the back of the phone.', 4);
    // On the finger side the reject strip grows to cover it (input.js reads it at every touch-down).
    const vw = window.innerWidth;
    const fromEdge = settings.leftHanded ? vw - x : x;
    if (!(fromEdge >= 0 && fromEdge <= GRIP_CHECK.near)) return;
    const strip = Math.min(INPUT.hiltFingerStripMax, Math.ceil(fromEdge + GRIP_CHECK.pad));
    if (strip > (Number(settings.hiltFingerStrip) || INPUT.hiltFingerStrip)) {
      settings.hiltFingerStrip = strip;
      if (ctx.saveSettings) ctx.saveSettings();
    }
  }

  // ---- step flow ----
  function goTo(i) {
    leaveStep();
    index = i;
    if (index >= steps.length) {
      finish();
      return;
    }
    step = steps[index];
    if ((step.motionOnly && !motionMode()) || (step.hiltOnly && !lockMode())) {
      goTo(index + 1);
      return;
    }
    count = 0;
    doneWait = 0;
    sub = { r: 0, l: 0, switched: false, thrusts: 0, thrown: false, atFar: true, dirs: [] };
    peaks = [];
    cands = [];
    calibrating = false;
    expects = [];
    check = null;
    expanded = false;
    el.dot.style.display = 'none';
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
    if (step.id === 'lock') {
      // Two more dummies either side of the near one, which the lock starts on.
      const ai = ctx.ai;
      if (ai && ai.spawnDummy) for (const f of FLANKS) ai.spawnDummy(f.x, f.z, {});
      lockTo(dummy);
    }
  }

  function leaveStep() {
    // The trainer only lives through the shield steps.
    const next = steps[index + 1];
    if (step && step.trainer && !(next && next.trainer) && trainer) removeTrainer();
    volleyWait = 0;
    if (step && step.id === 'spear') {
      stepDummyAside(false);
      if (lockMode()) lockTo(dummy);
    }
    // The lock step's extra dummies go the way the trainer does; the player drops the stale lock
    // (no longer in ctx.actors) on its next update and acquires the near dummy again.
    if (step && step.id === 'lock' && ctx.ai && ctx.ai.clear) {
      ctx.ai.clear();
      spawnDummies();
    }
  }

  /**
   * The effective grip changed mid-Agoge (pause → Settings, or the rotate prompt's "Use the window
   * grip"): the step carries on in the new grip's list and texts; the hilt-only lock step gives way
   * to the step after it.
   */
  function syncGrip() {
    const g = listKey();
    if (g === grip) return;
    grip = g;
    const list = agogeSteps(g);
    const old = steps;
    steps = list;
    if (!step || finished) return;
    const i = list.indexOf(step);
    if (i < 0) {
      // goTo leaves the lock step (its look-ahead only matters for the trainer steps)
      goTo(list.indexOf(old[index + 1]));
      return;
    }
    index = i;
    check = null;
    el.dot.style.display = 'none';
    if (doneWait <= 0) renderStep();
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
    setFigure(null);
    say('You are ready. To the Hot Gates.');
    // The first hilt Fight no longer suggests the Agoge (menus.js, §A.6).
    if (hiltGrip() && !settings.hiltAgogeDone) {
      settings.hiltAgogeDone = true;
      if (ctx.saveSettings) ctx.saveSettings();
    }
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
    // Over-speed (the hilt grip): a slash or throw at 14 rad/s or more, or the notice that follows
    // one (§B.3). The hint goes up after the frame's events, over the step's own hints.
    if (g.fast && !ctx.motionDemo) fastHint = true;
    if (g.type === 'overSpeed') return;
    if (g.type === 'slash') lastSlashAt = ctx.time.now;
    // a spear wind-up in the hilt grip is the cock-back that arms the throw
    const label = g.type === 'windup' ? (hiltGrip() && spearInHand() ? 'Armed' : 'Wind-up') : '';
    showGesture(g.type, g.dir, g.power, label);
  });

  // Calibration counts every swing-like motion, slash or not: a gentle swinger below the
  // thresholds still calibrates (the 'gesture' slashes would never come).
  ev.on('swingCandidate', (c) => {
    if (!active || !c || !counting()) return;
    if (hiltGrip()) hiltCandidate(c);
    if (step.id !== 'calibrate' || calibrating) return;
    cands.push({ peak: c.peak || 0, t0: Number.isFinite(c.t0) ? c.t0 : c.t, t: c.t });
    candAt = ctx.time.now;
    calibrateFromCandidates();
  });

  /**
   * The hilt grip's swing hints (§G). An up swing from the upright guard (heldUp) is the cock-back,
   * never a cut: in the uppercut step, lower the blade first. A swing too slow to cut, with no slash
   * near it, in calibrate or slashLR: most often the bottom of the phone led, so sweep the top.
   */
  function hiltCandidate(c) {
    const id = step.id;
    if (c.heldUp) {
      if (id === 'uppercut') setHint('Lower the blade first, then swing up.', 3);
      return;
    }
    if (id !== 'calibrate' && id !== 'slashLR') return;
    const now = ctx.time.now;
    const s = clamp(settings[sensKey()] || 1, SENSITIVITY_MIN, SENSITIVITY_MAX);
    if (c.peak >= slashThreshold(s) || now - lastSlashAt < SLASH_NEAR || now - wiperAt < WIPER_GAP) return;
    wiperAt = now;
    setHint('Sweep the top across, like a windscreen wiper.', 3);
    setFigure('cut', 3);
  }

  // The hilt lock step: a swipe to the right (the lock moves to a foe right of the last), then one
  // to the left, in either order (§G).
  ev.on('lockOn', (e) => {
    if (!active || !e || !counting() || step.id !== 'lock') return;
    if (e.reason === 'none') {
      setHint(buttonsMode() ? 'Nobody stands that way. Tap the other arrow.' : 'Nobody stands that way. Swipe the other way.');
      return;
    }
    const p = ctx.player && ctx.player.pos;
    if (e.reason !== 'switch' || !e.target || !e.target.pos || !p) return;
    const bearing = (a) => yawTo(a.pos.x - p.x, a.pos.z - p.z);
    const from = e.prev && e.prev.pos ? bearing(e.prev) : ctx.player.yaw || 0;
    if (angleDiff(from, bearing(e.target)) < 0) {
      if (sub.r < 1) {
        sub.r++;
        progress(sub.l ? '' : 'Now left.');
      } else setHint('Now left.');
    } else if (sub.l < 1) {
      sub.l++;
      progress(sub.r ? '' : 'Now right.');
    } else setHint('Now right.');
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
    el.calibT.textContent = `Your swings peak near ${med.toFixed(1)} rad/s. Suggested sensitivity ${suggestion.toFixed(2)}× (now ${(+settings[sensKey()]).toFixed(2)}×).`;
    el.calib.classList.add('is-shown');
    root.classList.add('is-calib');
  }

  /**
   * Mirrored-setup check (motion only): in the directional steps, each slash is compared with
   * what the instruction asks for at that moment. If a step's first three telling slashes all went
   * the opposite way, the matching setting (the effective grip's flipKeys: motionFlipX / motionFlipY,
   * or motionFlipXHilt / motionFlipYHilt) is toggled and saved; one
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
    const keys = flipKeys(settings);
    const key = id === 'slashLR' ? keys.x : keys.y;
    settings[key] = !settings[key];
    if (ctx.saveSettings) ctx.saveSettings();
    if (ctx.gestures && ctx.gestures.setFlip) ctx.gestures.setFlip(!!settings[keys.x], !!settings[keys.y]);
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
        if (sub.thrusts === 2 && lockMode()) {
          // The hilt grip throws at the lock: the view turns to the far target at once, so the near
          // dummy stepping aside never takes the view with it (§G).
          lockTo(farDummy);
          progress('');
          setHint('The far target is locked. Now throw.', 3);
        } else progress(sub.thrusts === 2 ? 'Now throw it at the far target.' : '');
      } else if (id === 'spear' && !sub.switched) setHint('Take the spear first.');
    } else if (s.kind === 'bash') {
      if (id === 'bash') progress('');
      else if (id === 'thrust') setHint('Lower the shield to stab.');
    } else if (s.kind === 'throw' && id === 'spear') {
      sub.thrown = true;
      const lock = lockMode() && ctx.player ? ctx.player.lockTarget : null;
      sub.atFar = !lock || lock === farDummy;
      if (sub.thrusts < 2) setHint('Thrust twice before you throw.');
      else if (!sub.atFar) setHint('Throw at the far target.');
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
    } else if (lockMode()) {
      // the lock-on controls throw at the lock: put it back on the far target for the next spear
      setHint(sub.atFar ? 'Wide. Another spear is drawn: throw again.' : 'Throw at the far target.');
      if (sub.thrusts >= 2) lockTo(farDummy);
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
    if (!active) return;
    // The collapsed panel (portrait, the shield steps): a tap on its head row opens it until the next step.
    if (root.classList.contains('is-compact') && el.head.contains(e.target)) {
      expanded = true;
      root.classList.remove('is-compact');
      return;
    }
    const b = e.target.closest('[data-act]');
    if (!b) return;
    if (ctx.audio && ctx.audio.play) ctx.audio.play('uiTap');
    switch (b.dataset.act) {
      case 'ok':
        // The hilt grip step goes on to the guard and grip check before it completes.
        if (hiltGrip() && step.id === 'grip' && doneWait <= 0) {
          if (!check) startGripCheck();
        } else complete();
        break;
      case 'skip':
        goTo(index + 1);
        break;
      case 'demo':
        api.demo(index);
        break;
      case 'apply':
        settings[sensKey()] = suggestion;
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
    settings[sensKey()] = v;
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
    const s = clamp(settings[sensKey()] || 1, SENSITIVITY_MIN, SENSITIVITY_MAX);
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
      // The step list is the effective grip's (the hilt grip adds the lock step); syncGrip follows a change.
      grip = listKey();
      steps = agogeSteps(grip);
      clearFigure();
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
      check = null;
      fastHint = false;
      demoCue = -1;
      root.classList.remove('is-on', 'is-finished', 'is-calib', 'is-done', 'is-compact');
      el.big.classList.remove('is-shown');
      el.dot.style.display = 'none';
      clearFigure();
    },
    update(dt) {
      if (!active) return;
      syncGrip();
      if (fastHint) {
        fastHint = false;
        setHint('Easy: a firm flick is enough.', 3);
      }
      if (pendingBig) {
        pendingBig = false;
        el.big.classList.add('is-shown');
      }
      if (figure && figureLeft !== Infinity) {
        figureLeft -= dt;
        if (figureLeft <= 0) setFigure(null);
      }
      if (bigLeft > 0) {
        bigLeft -= dt;
        // back to the step's resting picture, if it has one
        if (bigLeft <= 0) paintFigure();
      }
      if (hintLeft > 0) {
        hintLeft -= dt;
        if (hintLeft <= 0) el.hint.classList.remove('is-shown');
      }
      if (demoLeft > 0) {
        // Injected samples are released in real time; the demo ends once the last one has been
        // delivered (and the recognizer has had a moment), or at the cap.
        demoLeft -= dt;
        demoClock += dt;
        if (demoCue > 0 && demoClock >= demoCue) {
          // the hilt spear demo: the near dummy steps aside and the far target is locked before the throw
          demoCue = -1;
          stepDummyAside(true);
          lockTo(farDummy);
        }
        const m = ctx.platform && ctx.platform.motion;
        const delivered = !m || typeof m.lastT !== 'function' || m.lastT() >= demoEndT - 1e-6;
        if (delivered || demoTail < 0.3) demoTail -= dt;
        if (demoTail <= 0) demoLeft = 0;
        if (demoLeft <= 0) {
          demoLeft = 0;
          demoCue = -1;
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
      if (check && counting()) stepGripCheck(dt);
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
      const def = typeof stepId === 'number' ? steps[stepId] : steps.find((s) => s.id === stepId);
      const make = def && demoOf(def);
      if (!make || !active) return false;
      const mod = await loadSynth();
      const motion = ctx.platform && ctx.platform.motion;
      if (!mod || !mod.synthesize || !motion || !active) {
        setHint('The demonstration is unavailable.');
        return false;
      }
      const hold = hiltGrip() ? 'hilt' : 'window';
      const script = make();
      demoCue = -1;
      if (def.id === 'spear' && ctx.player && ctx.player.weapon !== 'spear') ctx.actions.push({ type: 'switchWeapon', weapon: 'spear', src: 'test' });
      if (def.id === 'spear' && step && step.id === 'spear') {
        // The demo throws at the far target. The hilt grip thrusts at the near dummy first, then
        // the far target is locked as the cock-back starts (update), as in the step itself.
        const at = script.findIndex((s) => s.kind === 'throw');
        if (hold === 'hilt' && at > 0 && mod.scriptDuration) demoCue = Math.max(0.01, mod.scriptDuration(script.slice(0, at), { hold }));
        else stepDummyAside(true);
      }
      const info = motion.info ? motion.info() : null;
      const samples = mod.synthesize(script, {
        hold,
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
      const dur = mod.scriptDuration ? mod.scriptDuration(script, { hold }) : span;
      demoClock = 0;
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
