// ui/input.js: touch, keyboard and mouse → actions and continuous ctx.input (DESIGN.md §6).
// Owns the #controls root (z 20). Three touch layouts: 'motion' (hold the glass = shield, small
// corner buttons), 'touch' (stick, SHIELD/BASH, swipe/tap/drag zone, THROW) and 'buttons' (a move
// pad, SHIELD/BASH, a sword pad of eight cuts around STAB, target buttons and THROW, with the
// lock-on camera, so nothing is swiped or dragged), mirrored when settings.leftHanded. Keyboard and mouse work in both. In the hilt grip (docs-hilt-grip.md §D)
// the motion layout gains `ctl-hilt`: reject strips shaped for a fist, and horizontal thumb
// strokes on a shield pointer that switch the lock-on target once the phone is calm.
import { clamp } from '../core/math.js';
import { effectiveGrip } from '../core/grip.js';

/** Thresholds (DESIGN.md §6). Pixels are CSS px, times are ms unless noted. */
export const INPUT = Object.freeze({
  swipeMinPx: 40,
  swipeWindowMs: 180,
  swipeMinSpeed: 0.5,     // px/ms
  tapMaxPx: 12,
  tapMaxMs: 200,
  clickMaxMs: 250,        // a quick left click thrusts
  lookPerPx: 0.006,       // touch drag, rad/px
  mousePerPx: 0.0025,     // pointer-locked mouse, rad/px
  slowDragSpeed: 0.35,    // px/ms: a pending touch slower than this…
  slowDragMs: 80,         // …for this long since its last pause becomes a look drag
  lookTravelPx: 90,       // …as does one that has travelled this far without a swipe
  pauseMs: 90,            // a gap between move events this long counts as a pause
  stickRadius: 56,
  stickDead: 0.12,
  throwFullS: 0.7,        // THROW hold: power 0 → 1
  throwSlackPx: 18,       // dragging this far off THROW cancels it
  edgeStrip: 12,          // motion layout: touches starting this close to an edge don't raise the shield
  maxContactRadius: 40,   // motion layout: a larger contact (palm) doesn't raise the shield
  tipHoldS: 5,
  tipSlashes: 3,
  rearmAngle: 1.75,       // rad: a second swipe in the same touch must turn at least this much
  rearmPauseMs: 110,      // …or follow a pause
  keyPower: 0.7,
  buttonPower: 0.8,       // the buttons layout's cuts, stabs and bashes
  maxMouseStep: 300,      // px per mousemove; larger jumps are discarded
  // Hilt layout reject strips, for a right hand (mirrored with ctl-left):
  hiltTopStrip: 12,
  hiltBottomStrip: 24,    // the fist's lower fingers
  hiltThumbStrip: 20,     // thumb side: the thumb's base and the palm heel
  hiltFingerStrip: 44,    // finger side, where the fingertips curl onto the glass: settings.hiltFingerStrip
  hiltFingerStripMax: 120, //   (44–120, widened by the Agoge grip check) over the lower part…
  hiltFingerFrom: 0.4,    // …below this share of the height,
  hiltFingerTop: 16,      // …and this wide above it
  // Hilt thumb strokes on a shield pointer: a switch is |dx| this far…
  lockSwipePx: 44,
  lockSwipeMs: 220,       // …within this long,
  lockSwipeSpeed: 0.35,   // …at least this fast (px/ms, horizontal),
  lockSwipeRatio: 0.8,    // …and |dx| ≥ this·|dy| (≈ 51° off horizontal: a thumb in a fist strokes in arcs)
  strokeRearmPx: 20,      // one action per stroke, re-armed after reversing this far,
  strokeRearmMs: 150,     // …a pause this long, or a lift
  strokeGapMs: 10,        // thumb trail samples kept at least this far apart (high-rate digitizers)
  strokeCommitMs: 100,    // a classified stroke waits this long (real time) for the swing's samples…
  strokeLookbackMs: 50,   // …watching the phone from this long before its first sample…
  strokeCalm: 2.5,        // …and is dropped if it turned faster than this (rad/s, gestures.debug.m) or a gesture fired
  fingerSide: 0.35,       // the hilt tip: a shield pointer in this outer share of the width opposite the thumb is a finger
});

export const MOTION_TIP = 'Your shield is up while you swing. Rest your thumb on the frame, and press the glass to raise the shield.';
export const HILT_TIP = 'Your shield is up while you swing. Rest your thumb on the edge of the phone, and press the glass to block.';
export const HILT_TIP_FINGER = 'A finger is touching the glass at the edge. Keep your fingers on the back of the phone.';

/** Fixed-capacity pointer trail (oldest first). */
export class Trail {
  constructor(cap = 24) {
    this.cap = cap;
    this.t = new Float64Array(cap);
    this.x = new Float64Array(cap);
    this.y = new Float64Array(cap);
    this.n = 0;
  }

  clear() {
    this.n = 0;
  }

  push(t, x, y) {
    if (this.n === this.cap) {
      this.t.copyWithin(0, 1);
      this.x.copyWithin(0, 1);
      this.y.copyWithin(0, 1);
      this.n--;
    }
    this.t[this.n] = t;
    this.x[this.n] = x;
    this.y[this.n] = y;
    this.n++;
  }

  /**
   * push(), except that the newest sample is replaced while it is less than `gapMs` after the one
   * before it, so the kept samples stay `gapMs` apart and the trail spans ≥ (cap − 1)·gapMs even
   * at 240 Hz touch rates. The first sample is always kept.
   */
  pushSpaced(t, x, y, gapMs) {
    const n = this.n;
    if (n >= 2 && this.t[n - 1] - this.t[n - 2] < gapMs) {
      this.t[n - 1] = t;
      this.x[n - 1] = x;
      this.y[n - 1] = y;
      return;
    }
    this.push(t, x, y);
  }

  /** Keeps only the newest sample. */
  restart() {
    if (this.n > 1) {
      const i = this.n - 1;
      this.t[0] = this.t[i];
      this.x[0] = this.x[i];
      this.y[0] = this.y[i];
      this.n = 1;
    }
  }
}

/**
 * Looks for a fast swipe ending at the newest trail sample: more than `minPx` within `windowMs`
 * at `minSpeed` px/ms or faster. The oldest qualifying start is used, so the direction covers
 * the whole stroke. Screen-y is inverted: `dir` = atan2(−dy, dx), so swiping up gives π/2.
 * @returns {{dir:number, speed:number, dist:number}|null} `out` when found.
 */
export function findSwipe(trail, out, minPx = INPUT.swipeMinPx, windowMs = INPUT.swipeWindowMs, minSpeed = INPUT.swipeMinSpeed) {
  const n = trail.n;
  if (n < 2) return null;
  const j = n - 1;
  const tj = trail.t[j], xj = trail.x[j], yj = trail.y[j];
  for (let i = 0; i < j; i++) {
    const dtMs = tj - trail.t[i];
    if (dtMs > windowMs || dtMs <= 0) continue;
    const dx = xj - trail.x[i];
    const dy = yj - trail.y[i];
    const d = Math.hypot(dx, dy);
    if (d > minPx && d / dtMs >= minSpeed) {
      out.dir = Math.atan2(-dy, dx);
      out.speed = d / dtMs;
      out.dist = d;
      return out;
    }
  }
  return null;
}

/** Touch/mouse slash power from swipe speed (px/ms). */
export const swipePower = (speed) => clamp(0.45 + (speed - 0.5) * 0.35, 0.45, 1);

/**
 * Hilt grip: looks for a horizontal thumb stroke ending at the newest trail sample: |dx| ≥
 * lockSwipePx within lockSwipeMs, at lockSwipeSpeed px/ms or faster horizontally, and |dx| ≥
 * lockSwipeRatio·|dy|. The oldest qualifying start is used, as in findSwipe.
 * @returns {{dir:number, dist:number, speed:number, t0:number, t1:number}|null} `out` when found:
 *   dir +1 right / −1 left, t0 and t1 the stroke's first and last sample times (ms).
 */
export function findThumbStroke(trail, out, P = INPUT) {
  const n = trail.n;
  if (n < 2) return null;
  const j = n - 1;
  const tj = trail.t[j], xj = trail.x[j], yj = trail.y[j];
  for (let i = 0; i < j; i++) {
    const dtMs = tj - trail.t[i];
    if (dtMs > P.lockSwipeMs || dtMs <= 0) continue;
    const dx = xj - trail.x[i];
    const adx = Math.abs(dx);
    if (adx >= P.lockSwipePx && adx / dtMs >= P.lockSwipeSpeed && adx >= P.lockSwipeRatio * Math.abs(yj - trail.y[i])) {
      out.dir = dx > 0 ? 1 : -1;
      out.dist = adx;
      out.speed = adx / dtMs;
      out.t0 = trail.t[i];
      out.t1 = tj;
      return out;
    }
  }
  return null;
}

/**
 * Hilt grip: does a touch-down at (x, y) start in a reject strip (§D hand model: the thumb on the
 * right edge, the fingertips curled onto the left edge over the lower part)? `left` mirrors it
 * for a left hand; `fingerStrip` is the finger-side width over the lower part (px).
 */
export function inHiltStrip(x, y, vw, vh, left, fingerStrip = INPUT.hiltFingerStrip, P = INPUT) {
  if (y < P.hiltTopStrip || y > vh - P.hiltBottomStrip) return true;
  if ((left ? x : vw - x) < P.hiltThumbStrip) return true;
  return (left ? vw - x : x) < (y > vh * P.hiltFingerFrom ? fingerStrip : P.hiltFingerTop);
}

/** Radial dead zone with rescale: stick offset (px) → [x, y] in −1..1 (y up = forward). */
export function stickVector(dx, dy, radius = INPUT.stickRadius, dead = INPUT.stickDead, out = [0, 0]) {
  const len = Math.hypot(dx, dy) / radius;
  if (len <= dead) {
    out[0] = 0;
    out[1] = 0;
    return out;
  }
  const m = Math.min(1, (len - dead) / (1 - dead)) / len;
  out[0] = (dx / radius) * m;
  out[1] = (-dy / radius) * m;
  return out;
}

const ICONS = {
  pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 4h4v16H6zM14 4h4v16h-4z" fill="currentColor"/><path d="M5 3h6M13 3h6M5 21h6M13 21h6" stroke="currentColor" stroke-width="1.4"/></svg>',
  recenter: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="12" cy="12" r="2.2" fill="currentColor"/><path d="M12 1v5M12 18v5M1 12h5M18 12h5" stroke="currentColor" stroke-width="1.8"/></svg>',
  spear: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20 18.2 5.8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/><path d="M17 4.5 21.5 2.5 19.5 7Z" fill="currentColor"/><path d="M3 21l2.2-.6-.6 2.2z" fill="currentColor"/></svg>',
  sword: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16.8 3.2c2-.9 3.4-.6 4 0 .6.6.9 2 0 4L10 18l-4-4Z" fill="currentColor"/><path d="M4.5 13.5l6 6M3 21l3.5-3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  // The buttons layout: an arrow pointing right, turned per button by CSS (--a).
  cut: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h11" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/><path d="M13 6.5 20.5 12 13 17.5Z" fill="currentColor"/></svg>',
  step: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 12 8 5.5v13Z" fill="currentColor"/></svg>',
  stab: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="3.2" fill="currentColor"/></svg>',
  shield: '<svg viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="17" fill="none" stroke="currentColor" stroke-width="2.4"/><circle cx="20" cy="20" r="12.5" fill="none" stroke="currentColor" stroke-width="1" opacity=".6"/><circle cx="20" cy="20" r="3" fill="currentColor"/></svg>',
};

/**
 * The buttons layout's sword pad, row by row: each cut's direction (rad, the slash action's
 * convention: 0 right, π/2 up) or 'stab' for the middle. The arrows point the way the blade cuts.
 */
export const PAD = Object.freeze([
  ['upLeft', 3 * Math.PI / 4], ['up', Math.PI / 2], ['upRight', Math.PI / 4],
  ['left', Math.PI], ['stab', null], ['right', 0],
  ['downLeft', -3 * Math.PI / 4], ['down', -Math.PI / 2], ['downRight', -Math.PI / 4],
]);
const PAD_LABEL = {
  upLeft: 'Cut up and left', up: 'Rising cut', upRight: 'Cut up and right', left: 'Slash left', stab: 'Stab',
  right: 'Slash right', downLeft: 'Cut down and left', down: 'Overhead chop', downRight: 'Cut down and right',
};
/** The buttons layout's move pad: [id, moveX, moveZ, label]. */
const MOVES = Object.freeze([['f', 0, 1, 'Step forward'], ['l', -1, 0, 'Step left'], ['r', 1, 0, 'Step right'], ['b', 0, -1, 'Step back']]);

/**
 * Creates the input controller.
 * @param {object} ctx shared context (§4)
 * @returns {{ update(dt:number):void, reset():void, readonly shieldPointers:number,
 *   lastShieldDown:{x:number, y:number, t:number} }} shieldPointers: the motion layout's shield
 *   pointers now down; lastShieldDown: where and when (ctx.time.now, s) the latest one started
 *   (one object, mutated in place; t is −1 before the first). Both are for the Agoge grip check.
 */
export function createInput(ctx) {
  const input = ctx.input;
  const settings = ctx.settings;

  // ---- DOM ----
  const root = document.createElement('div');
  root.id = 'controls';
  root.className = 'ctl-touch';
  root.innerHTML = `
    <div class="ctl-surface"></div>
    <div class="ctl-stick-home"></div>
    <div class="ctl-stick"><div class="ctl-stick-ring"></div><div class="ctl-stick-knob"></div></div>
    <button class="ctl-btn ctl-pause" aria-label="Pause">${ICONS.pause}</button>
    <button class="ctl-btn ctl-recenter" aria-label="Recenter">${ICONS.recenter}</button>
    <button class="ctl-btn ctl-swap" aria-label="Swap weapon"><span class="ctl-swap-icon ctl-swap-sword">${ICONS.sword}</span><span class="ctl-swap-icon ctl-swap-spear">${ICONS.spear}</span><b class="ctl-count">0</b></button>
    <button class="ctl-shield" aria-label="Shield"><span class="ctl-shield-face">${ICONS.shield}<em>Shield</em></span><i class="ctl-shield-up">⌃ bash</i></button>
    <button class="ctl-bash" aria-label="Bash"><em>Bash</em></button>
    <button class="ctl-throw" aria-label="Throw">
      <span class="ctl-ring"><span class="ctl-ring-h ctl-ring-r"><i></i></span><span class="ctl-ring-h ctl-ring-l"><i></i></span></span>
      <span class="ctl-throw-face">${ICONS.spear}<em>Throw</em></span>
    </button>
    <div class="ctl-dpad">${MOVES.map(([id, , , label]) => `<button class="ctl-key ctl-mv ctl-mv-${id}" data-mv="${id}" aria-label="${label}">${ICONS.step}</button>`).join('')}</div>
    <div class="ctl-pad">${PAD.map(([id, dir]) => `<button class="ctl-key ctl-cut ctl-cut-${id}" data-cut="${id}" aria-label="${PAD_LABEL[id]}"${dir === null ? '' : ` style="--a:${(-dir * 180 / Math.PI).toFixed(0)}deg"`}>${dir === null ? `${ICONS.stab}<em>Stab</em>` : ICONS.cut}</button>`).join('')}</div>
    <button class="ctl-key ctl-tgt ctl-tgt-l" aria-label="Turn to the foe on your left"><b>‹</b><em>Foe</em></button>
    <button class="ctl-key ctl-tgt ctl-tgt-r" aria-label="Turn to the foe on your right"><em>Foe</em><b>›</b></button>
    <div class="ctl-tip" role="status"><b>Shield tip</b><span></span></div>`;
  ctx.overlay.appendChild(root);
  const q = (s) => root.querySelector(s);
  const surface = q('.ctl-surface');
  const stickEl = q('.ctl-stick');
  const stickHome = q('.ctl-stick-home');
  const knobEl = q('.ctl-stick-knob');
  const btnPause = q('.ctl-pause');
  const btnRecenter = q('.ctl-recenter');
  const btnSwap = q('.ctl-swap');
  const countEl = q('.ctl-count');
  const btnShield = q('.ctl-shield');
  const btnBash = q('.ctl-bash');
  const btnThrow = q('.ctl-throw');
  const ringR = q('.ctl-ring-r i');
  const ringL = q('.ctl-ring-l i');
  const tipEl = q('.ctl-tip');
  const tipTextEl = q('.ctl-tip span');
  tipTextEl.textContent = MOTION_TIP;

  // ---- state ----
  let active = false;
  let layout = '';           // 'touch' | 'motion' | 'buttons'
  let hilt = false;          // the motion layout in the hilt grip (ctl-hilt)
  let mirrored = null;
  let hasTouch = (navigator.maxTouchPoints || 0) > 0;
  let vw = window.innerWidth;
  let vh = window.innerHeight;
  const trackers = new Map(); // pointerId → tracker
  const pool = [];
  const keys = { w: false, a: false, s: false, d: false, shiftL: false, shiftR: false, k: false };
  const mv = { f: 0, b: 0, l: 0, r: 0 }; // the buttons layout's move pad: pointers holding each
  let mouseShield = false;
  let stroke = null;          // left-button mouse stroke
  const mouseTrail = new Trail(24);
  let lockedByUs = false;
  let stickPtr = null;        // tracker driving the stick
  let shieldBtnPtr = null;
  let throwPtr = null;
  let throwT0 = 0;
  let throwShown = -2;        // last ring progress drawn (×1000, int)
  let cancelFlashUntil = 0;
  let shieldBtnLit = false;
  let motionShieldCount = 0;
  let holdStart = -1;         // ctx.time.now when the current motion shield hold began
  let holdSlashes = 0;
  let tipShown = false;
  let tipUntil = 0;
  const stickOut = [0, 0];
  let stickX = 0;
  let stickZ = 0;
  let homeX = 0;
  let homeY = 0;
  let lastSpearVis = null;
  let lastCount = -1;
  let lastWeapon = '';
  let lastCine = false;
  let skipMoves = 0;
  let tipText = MOTION_TIP;
  const sw = { dir: 0, speed: 0, dist: 0 };
  const lastShieldDown = { x: 0, y: 0, t: -1 };

  // Hilt thumb strokes (§D). The thumb slides on the glass when the phone is swung, so a
  // classified stroke is queued, and update() (after the frame's pumpMotion) commits it once
  // strokeCommitMs of real time have passed, if the phone stayed calm from strokeLookbackMs
  // before the stroke's first sample on and no gesture fired; otherwise it is dropped silently.
  // Times are real ms (performance.now() and event timeStamps share that base).
  const thumb = { dir: 0, dist: 0, speed: 0, t0: 0, t1: 0 };
  const RING = 8;
  const ringT = new Float64Array(RING); // per-frame gestures.debug.m and when it was read
  const ringM = new Float64Array(RING);
  let ringHead = 0;
  let lastGestureAt = -Infinity;
  const QUEUE = 4;
  const queue = [];                     // FIFO of classified strokes (fixed slots)
  for (let i = 0; i < QUEUE; i++) queue.push({ dir: 0, at: 0, since: 0, m: 0, swung: false });
  let qHead = 0;
  let qLen = 0;

  const isActiveMode = () => ctx.mode === 'battle' || ctx.mode === 'training';
  const now = () => ctx.time.now;
  const push = (a) => ctx.actions.push(a);

  function tracker(id) {
    const t = pool.pop() || { trail: new Trail(24) };
    t.id = id;
    t.kind = '';
    t.x0 = 0; t.y0 = 0; t.t0 = 0;
    t.x = 0; t.y = 0;
    t.state = '';
    t.lookX = 0; t.lookY = 0;
    t.lastSlashDir = 0;
    t.armed = true;
    t.mt = 0; t.mx = 0; t.my = 0;
    t.el = null;
    t.travel = 0;
    t.sdir = 0; t.ext = 0;      // hilt: the last stroke's direction and furthest x since it fired
    t.trail.clear();
    trackers.set(id, t);
    return t;
  }

  function release(t) {
    trackers.delete(t.id);
    pool.push(t);
  }

  function setShieldHeld(v) {
    if (v === input.shieldHeld) return;
    input.shieldHeld = v;
    if (v) input.shieldPressedAt = ctx.time.gnow;
  }

  function refreshShield() {
    const demo = !!(ctx.training && ctx.training.demoShield && ctx.motionDemo);
    setShieldHeld(active && (motionShieldCount > 0 || shieldBtnPtr !== null || keys.shiftL || keys.shiftR || keys.k || mouseShield || demo));
    const lit = shieldBtnPtr !== null;
    if (lit !== shieldBtnLit) {
      shieldBtnLit = lit;
      btnShield.classList.toggle('is-held', lit);
    }
  }

  /** Forgets every pointer, key and stick; zeroes ctx.input's continuous fields (§6). */
  function zero() {
    for (const t of trackers.values()) pool.push(t);
    trackers.clear();
    keys.w = keys.a = keys.s = keys.d = keys.shiftL = keys.shiftR = keys.k = false;
    mv.f = mv.b = mv.l = mv.r = 0;
    mouseShield = false;
    stroke = null;
    stickPtr = null;
    shieldBtnPtr = null;
    throwPtr = null;
    motionShieldCount = 0;
    holdStart = -1;
    holdSlashes = 0;
    qLen = 0;
    stickX = stickZ = 0;
    input.moveX = 0;
    input.moveZ = 0;
    input.lookYaw = 0;
    input.lookPitch = 0;
    input.shieldHeld = false;
    drawStick(homeX, homeY, 0, 0, false);
    drawThrow(-1);
    shieldBtnLit = false;
    for (const el of root.querySelectorAll('.is-down, .is-held')) el.classList.remove('is-down', 'is-held');
  }

  // ---- layout ----
  function measure() {
    vw = window.innerWidth;
    vh = window.innerHeight;
    // Stick home: an invisible anchor that CSS places in the bottom corner, inside the safe area.
    const r = stickHome.getBoundingClientRect();
    if (r.width > 0) {
      homeX = r.left + r.width / 2;
      homeY = r.top + r.height / 2;
    }
    if (stickPtr === null) drawStick(homeX, homeY, 0, 0, false);
  }

  const layoutOf = () => (settings.control === 'motion' ? 'motion' : settings.control === 'buttons' ? 'buttons' : 'touch');

  function applyLayout() {
    layout = layoutOf();
    hilt = effectiveGrip(settings) === 'hilt';
    mirrored = !!settings.leftHanded;
    root.classList.toggle('ctl-motion', layout === 'motion');
    root.classList.toggle('ctl-touch', layout === 'touch');
    root.classList.toggle('ctl-buttons', layout === 'buttons');
    root.classList.toggle('ctl-hilt', hilt);
    root.classList.toggle('ctl-left', mirrored);
    zero();
    measure();
  }

  function setActive(v) {
    active = v;
    root.classList.toggle('is-active', v);
    if (v) measure();
    if (!v) {
      if (document.pointerLockElement === surface) {
        lockedByUs = false;
        try { document.exitPointerLock(); } catch (e) { /* ignore */ }
      }
      tipEl.classList.remove('is-shown');
    }
  }

  function drawStick(cx, cy, ox, oy, on) {
    stickEl.style.transform = `translate(${cx.toFixed(1)}px, ${cy.toFixed(1)}px)`;
    knobEl.style.transform = `translate(${ox.toFixed(1)}px, ${oy.toFixed(1)}px)`;
    stickEl.classList.toggle('is-on', on);
  }

  // THROW ring: two clipped half rings rotated (transform only).
  function drawThrow(p) {
    const k = p < 0 ? -1 : Math.round(clamp(p, 0, 1) * 1000);
    if (k === throwShown) return;
    throwShown = k;
    btnThrow.classList.toggle('is-held', k >= 0);
    btnThrow.classList.toggle('is-full', k >= 1000);
    const f = k < 0 ? 0 : k / 1000;
    const a = Math.min(f, 0.5) * 360 - 135;           // right half: −135° (empty) → 45° (full)
    const b = Math.max(f - 0.5, 0) * 360 - 135;       // left half
    ringR.style.transform = `rotate(${a.toFixed(1)}deg)`;
    ringL.style.transform = `rotate(${b.toFixed(1)}deg)`;
  }

  // ---- HUD button forwarding (e.g. READY sits in #hud, below this layer) ----
  function hudButtonAt(x, y) {
    const list = ctx.overlay.querySelectorAll('#hud [data-hit]');
    for (let i = 0; i < list.length; i++) {
      const el = list[i];
      if (!el.classList.contains('is-shown')) continue;
      const r = el.getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return el;
    }
    return null;
  }

  function inside(el, x, y, slack = 0) {
    const r = el.getBoundingClientRect();
    return x >= r.left - slack && x <= r.right + slack && y >= r.top - slack && y <= r.bottom + slack;
  }

  // ---- touch zones on the surface ----
  function inStickZone(x) {
    return mirrored ? x > vw * 0.58 : x < vw * 0.42;
  }

  function onSurfaceDown(e) {
    if (e.pointerType === 'mouse' || !active) return;
    if (!hasTouch) {
      hasTouch = true;
      root.classList.add('ctl-hastouch');
      measure();
    }
    const x = e.clientX, y = e.clientY;
    const t = tracker(e.pointerId);
    t.x0 = t.x = x;
    t.y0 = t.y = y;
    t.t0 = e.timeStamp;
    const hud = hudButtonAt(x, y);
    if (hud) {
      t.kind = 'hud';
      t.el = hud;
      hud.classList.add('is-down');
      return;
    }
    if (layout === 'buttons') {
      t.kind = 'rest'; // nothing is swiped or dragged: only the buttons act
      return;
    }
    if (layout === 'motion') {
      const r = Math.max(e.width || 0, e.height || 0) / 2;
      const edge = INPUT.edgeStrip;
      const onEdge = hilt
        ? inHiltStrip(x, y, vw, vh, mirrored, fingerStrip())
        : x < edge || y < edge || x > vw - edge || y > vh - edge;
      if (onEdge || r > INPUT.maxContactRadius) {
        t.kind = 'rest';
        return;
      }
      t.kind = 'shield';
      if (hilt) t.trail.push(e.timeStamp, x, y);
      lastShieldDown.x = x;
      lastShieldDown.y = y;
      lastShieldDown.t = now();
      if (motionShieldCount === 0) {
        holdStart = now();
        holdSlashes = 0;
      }
      motionShieldCount++;
      refreshShield();
      return;
    }
    if (inStickZone(x) && stickPtr === null) {
      t.kind = 'stick';
      stickPtr = t;
      // Floating stick: the ring re-centres under the thumb, kept on screen.
      const m = INPUT.stickRadius + 14;
      t.x0 = clamp(x, m, vw - m);
      t.y0 = clamp(y, m, vh - m);
      moveStick(t, x, y);
      return;
    }
    t.kind = 'swipe';
    t.state = 'pending';
    t.mt = e.timeStamp;
    t.mx = x;
    t.my = y;
    t.trail.push(e.timeStamp, x, y);
  }

  function moveStick(t, x, y) {
    let ox = x - t.x0;
    let oy = y - t.y0;
    const len = Math.hypot(ox, oy);
    const R = INPUT.stickRadius;
    if (len > R) {
      ox *= R / len;
      oy *= R / len;
    }
    stickVector(ox, oy, R, INPUT.stickDead, stickOut);
    stickX = stickOut[0];
    stickZ = stickOut[1];
    drawStick(t.x0, t.y0, ox, oy, true);
  }

  function addLook(dx, dy, perPx) {
    input.lookYaw -= dx * perPx;
    input.lookPitch -= dy * perPx;
  }

  function slash(dir, speed, src) {
    push({ type: 'slash', dir, power: swipePower(speed), src });
  }

  function swipeSample(t, ts, x, y) {
    const dx = x - t.x;
    const dy = y - t.y;
    const n0 = t.trail.n;
    // A pause (no movement events) restarts the speed measurement for look classification.
    if (n0 === 0 || ts - t.trail.t[n0 - 1] > INPUT.pauseMs) {
      t.mt = ts;
      t.mx = x;
      t.my = y;
    }
    t.x = x;
    t.y = y;
    t.travel = Math.max(t.travel, Math.hypot(x - t.x0, y - t.y0));
    if (t.state === 'look') {
      addLook(dx, dy, INPUT.lookPerPx);
      return;
    }
    t.trail.push(ts, x, y);
    if (t.state === 'pending') {
      t.lookX += dx;
      t.lookY += dy;
    }
    // Re-arm a used touch after a pause.
    if (!t.armed) {
      const n = t.trail.n;
      if (n >= 2 && ts - t.trail.t[n - 2] > INPUT.rearmPauseMs) t.armed = true;
    }
    if (findSwipe(t.trail, sw)) {
      const turn = Math.abs(Math.atan2(Math.sin(sw.dir - t.lastSlashDir), Math.cos(sw.dir - t.lastSlashDir)));
      if (t.armed || turn >= INPUT.rearmAngle) {
        slash(sw.dir, sw.speed, 'touch');
        t.state = 'swiped';
        t.lastSlashDir = sw.dir;
        t.armed = false;
        t.lookX = t.lookY = 0;
        t.trail.restart();
      }
      return;
    }
    if (t.state === 'pending' && t.travel > INPUT.tapMaxPx) {
      // Sustained slow movement (measured since the last pause) is a look drag; its buffered
      // movement is released at once.
      const span = ts - t.mt;
      const slow = span >= INPUT.slowDragMs && Math.hypot(x - t.mx, y - t.my) / span < INPUT.slowDragSpeed;
      if (slow || t.travel > INPUT.lookTravelPx) {
        t.state = 'look';
        addLook(t.lookX, t.lookY, INPUT.lookPerPx);
        t.lookX = t.lookY = 0;
      }
    }
  }

  // ---- hilt thumb strokes (§D) ----
  /** The finger-side reject strip over the lower part (settings.hiltFingerStrip, 44–120 px). */
  function fingerStrip() {
    const v = Number(settings.hiltFingerStrip);
    return Number.isFinite(v) ? clamp(v, INPUT.hiltFingerStrip, INPUT.hiltFingerStripMax) : INPUT.hiltFingerStrip;
  }

  /** The largest gestures.debug.m read at or after `since` (real ms). */
  function ringMax(since) {
    let m = 0;
    for (let i = 0; i < RING; i++) if (ringT[i] > 0 && ringT[i] >= since && ringM[i] > m) m = ringM[i];
    return m;
  }

  /**
   * Queues a classified stroke (dir ±1; t0, t1 its first and last sample times). A future
   * timeStamp (synthetic input) is clamped to now, and the lookback keeps the stroke's own span.
   */
  function queueStroke(dir, t0, t1) {
    if (qLen === QUEUE) return; // four strokes inside 100 ms: the rest are slips
    const s = queue[(qHead + qLen) % QUEUE];
    qLen++;
    const rnow = performance.now();
    s.dir = dir;
    s.at = Math.min(t1, rnow);
    s.since = Math.min(t0, rnow - (t1 - t0)) - INPUT.strokeLookbackMs;
    s.m = ringMax(s.since);
    s.swung = lastGestureAt >= s.since;
  }

  /** One move sample of a hilt shield pointer: one switch per stroke, re-armed by a reversal or a pause. */
  function strokeSample(t, ts, x, y) {
    const n = t.trail.n;
    if (!t.armed && n > 0 && ts - t.trail.t[n - 1] >= INPUT.strokeRearmMs) t.armed = true;
    t.trail.pushSpaced(ts, x, y, INPUT.strokeGapMs);
    t.x = x;
    t.y = y;
    if (!t.armed) {
      if ((x - t.ext) * t.sdir > 0) t.ext = x;
      else if ((t.ext - x) * t.sdir >= INPUT.strokeRearmPx) t.armed = true;
      if (!t.armed) return;
    }
    if (findThumbStroke(t.trail, thumb)) {
      queueStroke(thumb.dir, thumb.t0, thumb.t1);
      t.armed = false;
      t.sdir = thumb.dir;
      t.ext = x;
      t.trail.restart();
    }
  }

  /** update(): records this frame's phone rate, then commits or drops the strokes that have waited long enough. */
  function stepStrokes() {
    const rnow = performance.now();
    const d = ctx.gestures && ctx.gestures.debug;
    const m = d && d.m > 0 ? d.m : 0;
    ringT[ringHead] = rnow;
    ringM[ringHead] = m;
    ringHead = (ringHead + 1) % RING;
    for (let i = 0; i < qLen; i++) {
      const s = queue[(qHead + i) % QUEUE];
      if (m > s.m) s.m = m;
    }
    while (qLen > 0) {
      const s = queue[qHead];
      if (rnow - s.at < INPUT.strokeCommitMs) break;
      qHead = (qHead + 1) % QUEUE;
      qLen--;
      if (!s.swung && s.m <= INPUT.strokeCalm) push({ type: 'switchTarget', dir: s.dir, src: 'touch' });
    }
  }

  function onSurfaceMove(e) {
    if (e.pointerType === 'mouse') return;
    const t = trackers.get(e.pointerId);
    if (!t) return;
    if (t.kind === 'stick') {
      moveStick(t, e.clientX, e.clientY);
    } else if (t.kind === 'swipe') {
      const list = e.getCoalescedEvents ? e.getCoalescedEvents() : null;
      if (list && list.length > 1) {
        for (let i = 0; i < list.length; i++) swipeSample(t, list[i].timeStamp, list[i].clientX, list[i].clientY);
      } else {
        swipeSample(t, e.timeStamp, e.clientX, e.clientY);
      }
    } else if (t.kind === 'shield' && hilt) {
      const list = e.getCoalescedEvents ? e.getCoalescedEvents() : null;
      if (list && list.length > 1) {
        for (let i = 0; i < list.length; i++) strokeSample(t, list[i].timeStamp, list[i].clientX, list[i].clientY);
      } else {
        strokeSample(t, e.timeStamp, e.clientX, e.clientY);
      }
    }
  }

  function onSurfaceUp(e, cancelled) {
    if (e.pointerType === 'mouse') return;
    const t = trackers.get(e.pointerId);
    if (!t) return;
    if (t.kind === 'shield') {
      motionShieldCount = Math.max(0, motionShieldCount - 1);
      if (motionShieldCount === 0) holdStart = -1;
      refreshShield();
    } else if (t.kind === 'stick') {
      stickPtr = null;
      stickX = stickZ = 0;
      drawStick(homeX, homeY, 0, 0, false);
    } else if (t.kind === 'swipe' && !cancelled) {
      swipeSample(t, e.timeStamp, e.clientX, e.clientY);
      if (t.state === 'pending' && t.travel < INPUT.tapMaxPx && e.timeStamp - t.t0 < INPUT.tapMaxMs) {
        push({ type: 'thrust', power: 0.6, src: 'touch' });
      }
    } else if (t.kind === 'hud') {
      t.el.classList.remove('is-down');
      if (!cancelled && inside(t.el, e.clientX, e.clientY, 8)) t.el.click();
    }
    release(t);
  }

  surface.addEventListener('pointerdown', onSurfaceDown);
  surface.addEventListener('pointermove', onSurfaceMove);
  surface.addEventListener('pointerup', (e) => onSurfaceUp(e, false));
  surface.addEventListener('pointercancel', (e) => onSurfaceUp(e, true));

  // ---- buttons (pointer events: touchstart is preventDefault-ed, so no clicks) ----
  function bindButton(el, { down, move, up }) {
    el.addEventListener('pointerdown', (e) => {
      if (!active || (e.pointerType === 'mouse' && e.button !== 0)) return;
      e.stopPropagation();
      try { el.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      const t = tracker(e.pointerId);
      t.kind = 'button';
      t.el = el;
      t.x0 = t.x = e.clientX;
      t.y0 = t.y = e.clientY;
      t.t0 = e.timeStamp;
      el.classList.add('is-down');
      if (down) down(t, e);
    });
    el.addEventListener('pointermove', (e) => {
      const t = trackers.get(e.pointerId);
      if (!t || t.el !== el) return;
      if (move) move(t, e);
    });
    const end = (e, cancelled) => {
      const t = trackers.get(e.pointerId);
      if (!t || t.el !== el) return;
      el.classList.remove('is-down');
      if (up) up(t, e, cancelled || !inside(el, e.clientX, e.clientY, 10));
      release(t);
    };
    el.addEventListener('pointerup', (e) => end(e, false));
    el.addEventListener('pointercancel', (e) => end(e, true));
    el.addEventListener('mousedown', (e) => e.stopPropagation());
  }

  const srcOf = (e) => (e.pointerType === 'mouse' ? 'key' : 'touch');

  bindButton(btnPause, {
    up: (t, e, outside) => {
      if (!outside && isActiveMode()) ctx.setMode('paused');
    },
  });
  bindButton(btnRecenter, { down: (t, e) => push({ type: 'recenter', src: srcOf(e) }) });
  // The hilt layout's swap sits at the top for the off hand and fires on a tap's release inside
  // it (sliding off cancels); elsewhere it fires on press.
  bindButton(btnSwap, {
    down: (t, e) => {
      if (!hilt) push({ type: 'switchWeapon', src: srcOf(e) });
    },
    up: (t, e, outside) => {
      if (hilt && !outside) push({ type: 'switchWeapon', src: srcOf(e) });
    },
  });
  bindButton(btnBash, { down: (t, e) => push({ type: 'bash', power: layout === 'buttons' ? INPUT.buttonPower : INPUT.keyPower, src: srcOf(e) }) });
  // The buttons layout: each pad button strikes once per press; a move button walks while held.
  for (const el of root.querySelectorAll('.ctl-cut')) {
    const dir = PAD.find(([id]) => id === el.dataset.cut)[1];
    bindButton(el, {
      down: (t, e) => push(dir === null
        ? { type: 'thrust', power: INPUT.buttonPower, src: srcOf(e) }
        : { type: 'slash', dir, power: INPUT.buttonPower, src: srcOf(e) }),
    });
  }
  for (const el of root.querySelectorAll('.ctl-mv')) {
    const id = el.dataset.mv;
    bindButton(el, {
      down: () => { mv[id]++; },
      up: () => { mv[id] = Math.max(0, mv[id] - 1); },
    });
  }
  bindButton(q('.ctl-tgt-l'), { down: (t, e) => push({ type: 'switchTarget', dir: -1, src: srcOf(e) }) });
  bindButton(q('.ctl-tgt-r'), { down: (t, e) => push({ type: 'switchTarget', dir: 1, src: srcOf(e) }) });
  bindButton(btnShield, {
    down: (t, e) => {
      shieldBtnPtr = t;
      t.armed = true;
      t.trail.push(e.timeStamp, e.clientX, e.clientY);
      refreshShield();
    },
    move: (t, e) => {
      t.trail.push(e.timeStamp, e.clientX, e.clientY);
      // Swiping up from the shield bashes (once, re-armed when the thumb comes back down).
      if (t.armed && findSwipe(t.trail, sw) && Math.abs(sw.dir - Math.PI / 2) < 0.9) {
        push({ type: 'bash', power: clamp(0.55 + (sw.speed - 0.5) * 0.3, 0.55, 1), src: srcOf(e) });
        t.armed = false;
        t.trail.restart();
      } else if (!t.armed && e.clientY > t.y0 - 16) {
        t.armed = true;
        t.trail.restart();
      }
    },
    up: () => {
      shieldBtnPtr = null;
      refreshShield();
    },
  });
  bindButton(btnThrow, {
    down: (t, e) => {
      throwPtr = t;
      throwT0 = e.timeStamp;
      drawThrow(0);
    },
    move: (t, e) => {
      if (throwPtr === t && !inside(btnThrow, e.clientX, e.clientY, INPUT.throwSlackPx)) {
        throwPtr = null; // dragging off the button cancels
        drawThrow(-1);
        btnThrow.classList.add('is-cancel');
        cancelFlashUntil = performance.now() + 350;
      }
    },
    up: (t, e, cancelled) => {
      if (throwPtr === t && !cancelled) {
        const power = clamp((e.timeStamp - throwT0) / 1000 / INPUT.throwFullS, 0, 1);
        push({ type: 'throw', power, src: srcOf(e) });
      }
      if (throwPtr === t) throwPtr = null;
      drawThrow(-1);
    },
  });

  // ---- touch hygiene (§6): no context menu, no scrolling/zoom/long-press ----
  root.addEventListener('contextmenu', (e) => e.preventDefault());
  root.addEventListener('touchstart', (e) => {
    if (e.cancelable) e.preventDefault();
  }, { passive: false });

  // ---- mouse ----
  surface.addEventListener('mousedown', (e) => {
    if (!active) return;
    const hud = hudButtonAt(e.clientX, e.clientY);
    if (hud && e.button === 0) {
      hud.click();
      return;
    }
    if (layout === 'buttons') return; // the pointer stays free to click the buttons
    if (e.button === 0) {
      stroke = { t0: e.timeStamp, x: 0, y: 0, travel: 0, armed: true, lastDir: 0, fired: false };
      mouseTrail.clear();
      mouseTrail.push(e.timeStamp, 0, 0);
    } else if (e.button === 2) {
      mouseShield = true;
      refreshShield();
    } else if (e.button === 1) {
      e.preventDefault();
      push({ type: 'throw', power: INPUT.keyPower, src: 'key' });
    }
    if (document.pointerLockElement !== surface && surface.requestPointerLock) {
      try {
        const p = surface.requestPointerLock();
        if (p && p.catch) p.catch(() => {});
      } catch (err) { /* ignore */ }
    }
  });

  document.addEventListener('mousemove', (e) => {
    if (!active) return;
    const mx = e.movementX || 0;
    const my = e.movementY || 0;
    // Browsers can report a bogus jump when the pointer lock engages or the window refocuses.
    if (skipMoves > 0) {
      skipMoves--;
      return;
    }
    if (Math.abs(mx) > INPUT.maxMouseStep || Math.abs(my) > INPUT.maxMouseStep) return;
    if (stroke) {
      // Left-drag: movement draws the slash instead of looking.
      stroke.x += mx;
      stroke.y += my;
      stroke.travel = Math.max(stroke.travel, Math.hypot(stroke.x, stroke.y));
      mouseTrail.push(e.timeStamp, stroke.x, stroke.y);
      const n = mouseTrail.n;
      if (!stroke.armed && n >= 2 && e.timeStamp - mouseTrail.t[n - 2] > INPUT.rearmPauseMs) stroke.armed = true;
      if (findSwipe(mouseTrail, sw)) {
        const turn = Math.abs(Math.atan2(Math.sin(sw.dir - stroke.lastDir), Math.cos(sw.dir - stroke.lastDir)));
        if (stroke.armed || turn >= INPUT.rearmAngle) {
          slash(sw.dir, sw.speed, 'key');
          stroke.armed = false;
          stroke.fired = true;
          stroke.lastDir = sw.dir;
          mouseTrail.restart();
        }
      }
      return;
    }
    if (document.pointerLockElement === surface) addLook(mx, my, INPUT.mousePerPx);
  });

  window.addEventListener('mouseup', (e) => {
    if (e.button === 0 && stroke) {
      if (active && !stroke.fired && stroke.travel < INPUT.tapMaxPx && e.timeStamp - stroke.t0 < INPUT.clickMaxMs) {
        push({ type: 'thrust', power: INPUT.keyPower, src: 'key' });
      }
      stroke = null;
    } else if (e.button === 2 && mouseShield) {
      mouseShield = false;
      refreshShield();
    }
  });

  document.addEventListener('pointerlockchange', () => {
    skipMoves = 1;
    if (document.pointerLockElement === surface) {
      lockedByUs = true;
    } else if (lockedByUs) {
      lockedByUs = false;
      // Esc releases the lock before the page sees the key: treat it as pause.
      if (isActiveMode()) ctx.setMode('paused');
    }
  });

  // ---- keyboard ----
  const SLASH_KEYS = { ArrowRight: 0, ArrowUp: Math.PI / 2, ArrowLeft: Math.PI, ArrowDown: -Math.PI / 2 };

  window.addEventListener('keydown', (e) => {
    if (e.defaultPrevented || !active || e.ctrlKey || e.metaKey || e.altKey) return;
    const c = e.code;
    let handled = true;
    switch (c) {
      case 'KeyW': keys.w = true; break;
      case 'KeyA': keys.a = true; break;
      case 'KeyS': keys.s = true; break;
      case 'KeyD': keys.d = true; break;
      case 'ShiftLeft': keys.shiftL = true; refreshShield(); break;
      case 'ShiftRight': keys.shiftR = true; refreshShield(); break;
      case 'KeyK': keys.k = true; refreshShield(); break;
      default: {
        if (e.repeat) {
          handled = c in SLASH_KEYS || c === 'Space';
          break;
        }
        if (c in SLASH_KEYS) push({ type: 'slash', dir: SLASH_KEYS[c], power: INPUT.keyPower, src: 'key' });
        else if (c === 'Space') push({ type: 'thrust', power: INPUT.keyPower, src: 'key' });
        else if (c === 'KeyF') push({ type: 'bash', power: INPUT.keyPower, src: 'key' });
        else if (c === 'KeyQ') push({ type: 'switchWeapon', src: 'key' });
        else if (c === 'KeyR') push({ type: 'throw', power: INPUT.keyPower, src: 'key' });
        else if (c === 'KeyC') push({ type: 'recenter', src: 'key' });
        else if (c === 'KeyZ' || c === 'KeyX') push({ type: 'switchTarget', dir: c === 'KeyX' ? 1 : -1, src: 'key' }); // the player ignores it outside the hilt grip
        else if (c === 'Escape' || c === 'KeyP') ctx.setMode('paused');
        else handled = false;
      }
    }
    if (handled) e.preventDefault();
  });

  window.addEventListener('keyup', (e) => {
    switch (e.code) {
      case 'KeyW': keys.w = false; break;
      case 'KeyA': keys.a = false; break;
      case 'KeyS': keys.s = false; break;
      case 'KeyD': keys.d = false; break;
      case 'ShiftLeft': keys.shiftL = false; refreshShield(); break;
      case 'ShiftRight': keys.shiftR = false; refreshShield(); break;
      case 'KeyK': keys.k = false; refreshShield(); break;
      default: break;
    }
  });

  window.addEventListener('blur', () => {
    if (active) zero();
  });
  window.addEventListener('resize', measure);

  // ---- events ----
  ctx.events.on('modeChanged', ({ mode, prev }) => {
    zero();
    setActive(mode === 'battle' || mode === 'training');
    if (mode === 'battle' && prev !== 'paused') tipShown = false;
  });
  ctx.events.on('gesture', (g) => {
    if (g && g.type === 'slash' && holdStart >= 0) holdSlashes++;
    // any gesture means the phone was swung: queued thumb strokes are slips
    lastGestureAt = performance.now();
    for (let i = 0; i < qLen; i++) queue[(qHead + i) % QUEUE].swung = true;
  });

  if (hasTouch) root.classList.add('ctl-hastouch');
  applyLayout();
  setActive(isActiveMode());

  return {
    update(dt) {
      if (!active) {
        if (isActiveMode()) setActive(true);
        else return;
      }
      if (layoutOf() !== layout || (effectiveGrip(settings) === 'hilt') !== hilt || !!settings.leftHanded !== mirrored) applyLayout();
      if (hilt) stepStrokes();

      // Player-driven button state (DOM only on change).
      const p = ctx.player;
      if (p) {
        const spearVis = p.alive !== false && p.weapon === 'spear' && !!p.hasSpearInHand;
        if (spearVis !== lastSpearVis) {
          lastSpearVis = spearVis;
          root.classList.toggle('ctl-spear', spearVis);
          if (!spearVis && throwPtr) {
            throwPtr = null;
            drawThrow(-1);
          }
        }
        const count = p.spearsLeft | 0;
        if (count !== lastCount) {
          lastCount = count;
          countEl.textContent = String(count);
          btnSwap.classList.toggle('is-empty', count === 0);
        }
        if (p.weapon !== lastWeapon) {
          lastWeapon = p.weapon;
          btnSwap.classList.toggle('is-spear', p.weapon === 'spear');
        }
      }

      // Continuous movement: stick + WASD + the move pad.
      let mx = stickX + (keys.d || mv.r ? 1 : 0) - (keys.a || mv.l ? 1 : 0);
      let mz = stickZ + (keys.w || mv.f ? 1 : 0) - (keys.s || mv.b ? 1 : 0);
      const len = Math.hypot(mx, mz);
      if (len > 1) {
        mx /= len;
        mz /= len;
      }
      input.moveX = mx;
      input.moveZ = mz;

      const cine = !!(ctx.fx && ctx.fx.cameraOverridden);
      if (cine !== lastCine) {
        lastCine = cine;
        root.classList.toggle('is-cinematic', cine);
      }
      if (throwPtr) drawThrow((performance.now() - throwT0) / 1000 / INPUT.throwFullS);
      if (cancelFlashUntil > 0 && performance.now() > cancelFlashUntil) {
        cancelFlashUntil = 0;
        btnThrow.classList.remove('is-cancel');
      }
      refreshShield();

      // The resting-thumb tip (motion layout, once per battle). In the hilt grip it names the
      // cause from where the longest-held shield pointer is: a finger at the edge, or the thumb.
      if (layout === 'motion' && !tipShown && holdStart >= 0 && now() - holdStart > INPUT.tipHoldS && holdSlashes >= INPUT.tipSlashes) {
        tipShown = true;
        tipUntil = now() + 8;
        let text = MOTION_TIP;
        if (hilt) {
          let first = null;
          for (const t of trackers.values()) if (t.kind === 'shield' && (!first || t.t0 < first.t0)) first = t;
          const finger = !!first && (mirrored ? first.x > vw * (1 - INPUT.fingerSide) : first.x < vw * INPUT.fingerSide);
          text = finger ? HILT_TIP_FINGER : HILT_TIP;
        }
        if (text !== tipText) {
          tipText = text;
          tipTextEl.textContent = text;
        }
        tipEl.classList.add('is-shown');
      }
      if (tipUntil > 0 && now() > tipUntil) {
        tipUntil = 0;
        tipEl.classList.remove('is-shown');
      }
      void dt;
    },
    reset() {
      zero();
      tipShown = false;
      tipUntil = 0;
      tipEl.classList.remove('is-shown');
    },
    get shieldPointers() {
      return motionShieldCount;
    },
    lastShieldDown,
  };
}
