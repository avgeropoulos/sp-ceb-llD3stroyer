/**
 * GestureRecognizer (DESIGN.md §5.3). PURE.
 *
 * Consumes screen-frame Samples from MotionFrames and recognizes slashes (with direction),
 * thrusts, throw wind-ups and throws, swing-like candidates for calibration, and turns deliberate
 * body rotation into gyro look. push() allocates nothing except the (rare) event objects it returns.
 *
 * Body frame. Directions do not depend on how the phone is gripped (tilted back, rolled, screen
 * facing partly sideways):
 * - world up comes from gravity (sample.up);
 * - the body-right axis R is captured from the phone's attitude while it is quiet between swings,
 *   and is carried with the gyro as a world-fixed vector during swings (never re-derived from the
 *   instantaneous blade mid-swing);
 * - rotation about up is horizontal (wh, positive = turning left), rotation about R is vertical
 *   (wv, positive = tip up), and roll about the body-forward axis (wrist roll) is ignored.
 * The blade velocity across the view is (vx, vy) = (−wh, wv); its integral since the swing began is
 * the sweep, whose angle is the slash direction (§3.4).
 */

const DEG = Math.PI / 180;
const HALF_PI = Math.PI / 2;
const UP_SECTOR = 60 * DEG; // §5.3: upward swings and wind-ups
// Only a swing this close to straight up may be a raise (held, see _swingStep): an up-diagonal
// from the guard fires early like any other cut.
const UP_HOLD = 35 * DEG;
const AUTO_RESUME = 2.5; // s: failsafe when a throw starts no spear cam (no holdLook/resumeLook follows)
const HIST = 256; // planar-rate (and elevation) history: the 'still accelerating' test, the top before a dip
const DELAY = 512; // look delay line (≥ 0.5 s at 1 kHz)
const RELEASED = 512; // released-look history, for undoing a cock-back

/**
 * Every threshold of the recognizer. Sensitivity s divides swingStart, swingPeak, thrustAcc and
 * thrustMaxRate, divides swingSweep (not below swingSweepMin), swingSweepFast and swingSweepLate by
 * √s, and divides throwPeak, throwFull and throwMin by max(1, s). Rates are the body-frame planar
 * rate m (rad/s).
 *
 * Detection (tuned against the synthetic matrix plus gentle, sloppy, oddly gripped swings; a
 * cautious 3.5–4 rad/s swing of 0.8–1 rad registers at s = 1):
 * - A swing starts at swingStart. Its sweep also counts the rising ramp just before that
 *   (from preRate, while the rate keeps climbing), so the direction is known sooner.
 * - It fires as soon as the direction is reliable: running peak ≥ swingPeak and either the sweep
 *   is ≥ swingSweep while the stroke is still accelerating (m rose by riseRatio over riseWindow,
 *   and since the last sample) and its direction is clear of a token boundary by dirMargin, or the
 *   sweep is ≥ swingSweepLate at any phase. A cock-back (wind-up) is short: it stops accelerating at half its arc, so
 *   cock-backs of up to ~0.5 rad never fire, while a stroke of 0.6 rad or more fires early.
 *   Right after its own cock-back (a slower motion the other way, within fastWindow, at most
 *   1/fastRatio as fast) a stroke needs only swingSweepFast.
 * - A swing back against the last slash, within reverseWindow of its end or before the arm has
 *   come to rest since, is its return unless it reaches reverseRatio of that slash's peak and a
 *   sweep of reverseSweep. Returns run at 20–50% of the strike, deliberate reverse slashes 70%+.
 * - An upward swing within UP_HOLD of vertical, from at or above the guard, may be a raise: it
 *   fires once it is past its peak and clearly stopping short of windupBeta (else see _upPending).
 * - A downward swing within dipSector of vertical from the guard (the blade no more than dipRaise
 *   above it within dipLook before the swing: a chop is raised first, if only a little) may be the
 *   dip to a low guard before an uppercut, which peaks at half its short arc: it is a chop once
 *   its sweep reaches dipEarlySweep while still clearly speeding up (by dipRise over riseWindow),
 *   or dipSweep at any phase (neither scaled: how far one dips is a matter of style, not
 *   strength). A chop from a raised blade fires early as usual.
 * - The emitted dir snaps to the nearest cardinal within cardinalSnap (rawDir keeps the angle):
 *   real horizontal slashes dip a little, and a tilted animation reads as the wrong direction.
 * - Power is measured at fire time, early in the stroke: while it still accelerates it counts on
 *   powerRise × the current rate.
 * Elevations (upHoldStartBeta, windupBeta) are for a guard held at guardBeta; the guard the
 * player actually holds is learned while settled, so a phone held tilted back raises the same.
 * Body-right is re-captured whenever the phone is quiet (|w| < quietRate) or has not been for
 * rightStale (a long body turn), never during a swing.
 */
export const GESTURE_DEFAULTS = Object.freeze({
  swingStart: 2.0, swingPeak: 2.8, swingSweep: 0.24, swingSweepMin: 0.2, swingSweepFast: 0.16, swingSweepLate: 0.55, riseRatio: 0.04, riseWindow: 0.025,
  exRate: 0.8, exSweep: 0.08, fastWindow: 0.35, fastRatio: 1.5,
  preRate: 0.6, swingEnd: 1.5, endHold: 0.04, maxSwing: 0.7, maxRaise: 1.6,
  reverseWindow: 0.6, reverseRatio: 0.55, reverseSweep: 0.4, reverseSector: 60 * DEG, cardinalSnap: 22 * DEG,
  dirMargin: 8 * DEG, dipSector: 35 * DEG, dipRaise: 0.07, dipLook: 0.3, dipSweep: 0.55, dipEarlySweep: 0.3, dipRise: 0.08,
  quietRate: 1.0, rightStale: 0.5,
  thrustAcc: 12, thrustMaxRate: 3, thrustVel: 0.15, thrustWindow: 0.06, thrustRefractory: 0.35, vfLeak: 0.5, postThrustSwingLock: 0.15,
  vfLeakBack: 4, calmHold: 0.1,
  guardBeta: -0.39, guardTau: 1.5, upHoldStartBeta: -0.45, windupBeta: 1.6, windupTime: 0.1, windupMemory: 1.0,
  throwPeak: 7, throwFull: 13, throwMin: 4.5, throwMinSweep: 0.8, throwSector: 65 * DEG, throwReleaseRatio: 0.6,
  throwMaxSweep: 1.4, postThrowLock: 1.0,
  settleRate: 1.0, settleTime: 0.15,
  // Gyro look: only deliberate body rotation turns the view, 1:1 (see _lookStep).
  lookDead0: 0.1, lookDead1: 0.3, lookMax: 2.0, lookStrike: 2.5, lookDelay: 0.28, lookAfter: 0.6,
  lookRestRate: 0.15, lookRestTime: 0.35, lookAfterMax: 3.0, lookCockGap: 0.3, lookHoldRun: 0.45, lookRunMin: 0.03, lookTau: 0.07, lookRate: 1.4, lookStep: 0.026,
  // Calibration candidates: any excursion above candStart with a sweep ≥ candSweep (not scaled).
  candStart: 1.8, candEnd: 1.2, candSweep: 0.3, candMax: 0.9,
  slashPowerSpan: 10, powerRise: 1.3, thrustPowerSpan: 18,
});

/** Sensitivity range (the Agoge calibration may suggest up to 2.2). */
export const SENSITIVITY_MIN = 0.5;
export const SENSITIVITY_MAX = 2.2;

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

/** |wrap(a − b)| ≤ lim (returns a boolean, so nothing is boxed in the hot path) */
function within(a, b, lim) {
  let d = (a - b) % (2 * Math.PI);
  if (d > Math.PI) d -= 2 * Math.PI;
  else if (d < -Math.PI) d += 2 * Math.PI;
  return d <= lim && d >= -lim;
}

/** The nearest cardinal direction if `dir` is within `lim` of it, else dir. */
export function snapCardinal(dir, lim) {
  const c = Math.round(dir / HALF_PI) * HALF_PI;
  if (!within(dir, c, lim)) return dir;
  return c > Math.PI ? c - 2 * Math.PI : c <= -Math.PI ? c + 2 * Math.PI : c === 0 ? 0 : c;
}

const s5 = (x) => x * x * x * (10 + x * (-15 + 6 * x));

/**
 * Minimum-jerk stroke past its peak, at rate m = r·peak: the arc still to come as a fraction of
 * the arc swept so far.
 */
function remainingRatio(r) {
  if (r >= 1) return 1;
  if (r <= 0) return 0;
  const tau = 0.5 * (1 + Math.sqrt(Math.max(0, 1 - Math.sqrt(r))));
  const done = s5(tau);
  return done > 1e-6 ? (1 - done) / done : 0;
}

const smoothstep = (a, b, x) => {
  const k = clamp((x - a) / (b - a), 0, 1);
  return k * k * (3 - 2 * k);
};

// Swing modes
const NORMAL = 0, UP = 1, THROW = 2, DONE = 3;
const PHASES = ['swing', 'hold-up', 'throw', 'done'];

export class GestureRecognizer {
  /**
   * @param {{sensitivity?: number, weapon?: 'sword'|'spear', gyroRange?: number, params?: object}} [opts]
   *   params optionally overrides GESTURE_DEFAULTS (tests, tuning)
   */
  constructor({ sensitivity = 1, weapon = 'sword', gyroRange = 34.9, params = null } = {}) {
    this.p = Object.assign({}, GESTURE_DEFAULTS, params || {});
    this.gyroRange = gyroRange > 0 ? gyroRange : 34.9;
    this.weapon = 'sword';
    this.setWeapon(weapon);
    this.sens = 0;
    this.setSensitivity(sensitivity);
    this.flipX = false;
    this.flipY = false;
    this._events = [];
    this._candOut = [];
    this._candRead = [];
    this._lookOut = { yaw: 0 };
    this._dbg = {
      m: 0, yawRate: 0, pitchRate: 0, beta: 0, phase: 'idle', peak: 0, fwdAcc: 0, fwdVel: 0, lookState: 'settled', lastEvent: null,
      guardBeta: this.p.guardBeta, lastCandidatePeak: 0, lastCandidate: null,
    };
    this._upY = 1;
    this._upZ = 0;
    this._tLast = NaN;
    this._t = 0;
    this._m = this._beta = this._vx = this._vy = 0;
    this._dtNow = 0;
    this._rising = false;
    this._riseAmt = 0;
    // body-right axis (screen frame), world-fixed between captures
    this._R = [1, 0, 0];
    this._Rok = false;
    this._quietT = -Infinity;
    this._wp0 = this._wp1 = this._wp2 = 0;
    // learned guard elevation (kept across reset: it describes the grip)
    this._guardBeta = this.p.guardBeta;
    this._guardInit = false;
    // planar-rate history
    this._hT = new Float64Array(HIST);
    this._hM = new Float64Array(HIST);
    this._hB = new Float64Array(HIST);
    this._hHead = 0;
    this._hN = 0;
    // look (L, the released view heading, is kept across reset)
    this._L = 0;
    this._Lc = 0;
    this._target = 0;
    this._dlT = new Float64Array(DELAY);
    this._dlA = new Float64Array(DELAY);
    this._dlHead = 0;
    this._dlN = 0;
    this._rlT = new Float64Array(RELEASED);
    this._rlA = new Float64Array(RELEASED);
    this._rlHead = 0;
    this._rlN = 0;
    this.reset();
  }

  /**
   * Sensitivity 0.5–2.2: divides swingStart, swingPeak, thrustAcc; swingSweep (down to
   * swingSweepMin), swingSweepFast and swingSweepLate by √s. throwPeak and throwFull are divided by
   * max(1, s): a hard slasher (s < 1) does not have to throw harder too.
   */
  setSensitivity(s) {
    s = clamp(Number(s) || 1, SENSITIVITY_MIN, SENSITIVITY_MAX);
    if (s === this.sens) return;
    this.sens = s;
    const p = this.p;
    const rs = Math.sqrt(s);
    this._start = p.swingStart / s;
    // Never below lookMax: a body turn that gyro look accepts (up to lookMax) must not slash, or a
    // high sensitivity would turn every turn into a strike and stop the view from following it.
    this._peak = Math.max(p.swingPeak / s, p.lookMax);
    // (never below swingSweepMin: a short, fast wind-up must not pass for a stroke at a high sensitivity)
    this._sweep = Math.max(p.swingSweepMin, p.swingSweep / rs);
    this._sweepFast = p.swingSweepFast / rs;
    this._sweepLate = p.swingSweepLate / rs;
    this._end = Math.min(p.swingEnd, 0.7 * this._start); // below the start, or a slowing swing restarts
    this._throwPeak = p.throwPeak / Math.max(1, s);
    this._throwMin = p.throwMin / Math.max(1, s);
    this._throwFull = p.throwFull / Math.max(1, s);
    this._thrustAcc = p.thrustAcc / s;
    this._thrustMaxRate = p.thrustMaxRate / s;
    this._slashSpan = Math.max(0.5, Math.min(p.slashPowerSpan, 0.9 * this.gyroRange - this._peak));
    this._strikeRate = Math.min(p.lookStrike, this._peak);
  }

  /** 'sword' | 'spear' (throws only happen with the spear). */
  setWeapon(w) {
    this.weapon = w === 'spear' ? 'spear' : 'sword';
  }

  /**
   * Corrects a mirrored sensor setup (settings motionFlipX / motionFlipY): flipX mirrors the
   * horizontal body rate (dir → π − dir, and gyro look turns the other way), flipY the vertical one
   * (dir → −dir). Applied to the rates, so uppercut, chop, wind-up and throw logic stay consistent.
   */
  setFlip(x, y) {
    this.flipX = !!x;
    this.flipY = !!y;
  }

  /** Clears swing/thrust/wind-up state, recoil memory, locks and look state (not the released heading). */
  reset() {
    this._swActive = false;
    this._swMode = NORMAL;
    this._swT0 = 0;
    this._dx = this._dy = 0;
    this._swPeak = 0;
    this._swBeta0 = 0;
    this._swBetaTop = 0;
    this._swLow = -1;
    this._swRev = 0;
    this._swRevWin = false;
    this._swWindMem = false;
    this._swFired = false;
    this._swCut = false;
    this._swStrike = false;
    this._pValid = false;
    this._pDir = 0;
    this._pPeak = 0;
    this._pEnd = -Infinity;
    this._preX = this._preY = 0;
    this._preN = 0;
    this._preMax = 0;
    this._preMaxT = 0;
    this._preBeta0 = 0;
    this._exX = this._exY = 0;
    this._exPeak = 0;
    this._exDir = 0;
    this._exPk = 0;
    this._exEnd = -Infinity;
    this._vf = 0;
    this._calmRun = 0;
    this._fPrev = 0;
    this._cand = false;
    this._candT0 = 0;
    this._candPeak = 0;
    this._lastThrust = -Infinity;
    this._lastWindup = -Infinity;
    this._raisedSince = NaN;
    this._raisedEmitted = false;
    this._raiseArmed = false; // a raise counts only after the phone was seen lower
    this._cutRaise = false; // this raise was an uppercut: no wind-up event for it
    this._upPend = false; // a held-up swing ended overhead: uppercut or wind-up? (see _upPending)
    this._upReb = false;
    this._upT0 = 0;
    this._upTop = 0;
    this._upRebN = 0;
    this._upRebPeak = 0;
    this._upDir = this._upPeak = this._upSweep = 0;
    this._lockUntil = -Infinity;
    this._stillAcc = 0;
    this._settled = false;
    this._hN = 0;
    // calibration candidate
    this._cActive = false;
    this._cX = this._cY = 0;
    this._cPeak = 0;
    this._cT0 = 0;
    this._cLow = -1;
    this._cRev = 0;
    // body-right: re-captured at the next sample
    this._Rok = false;
    this._quietT = -Infinity;
    this._wp0 = this._wp1 = this._wp2 = 0;
    // look
    this._hold = false;
    this._holdAuto = false;
    this._holdSince = 0;
    this._strikeT = -Infinity;
    this._strikeQuiet = true;
    // the same, for the recoil rule: a strike other than a stroke's own fast run (see _lookRun)
    this._armT = -Infinity;
    this._armQuiet = true;
    this._striking = false;
    this._quietRun = 0;
    this._wLP = this._wLP || [0, 0, 0];
    this._wLP[0] = this._wLP[1] = this._wLP[2] = 0;
    this._prePend = false;
    this._preAmt = this._undoAmt = 0;
    this._lxIdle = 1;
    this._lxSign = 0;
    this._lxT0 = this._lxT1 = 0;
    this._lxAcc = 0;
    this._lxFast = false;
    this._lpSign = 0;
    this._lpT0 = this._lpT1 = 0;
    this._clearLook();
    this._events.length = 0;
  }

  /**
   * Feeds one Sample (from MotionFrames.process). Returns a reused array of GestureEvents.
   * @param {{t:number, dt:number, w:number[], a:number[], up:number[], calm:boolean, gap:boolean}} sample
   */
  push(sample) {
    const ev = this._events;
    ev.length = 0;
    const p = this.p;
    const t = sample.t;
    const dt = sample.dt > 0 ? sample.dt : 0;
    if (sample.gap) this._gap(t);
    this._tLast = t;
    this._t = t;
    this._dtNow = dt;
    const u = sample.up, w = sample.w;
    const wl = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
    this._upY = u[1];
    this._upZ = u[2];

    // Blade elevation, unwrapped past vertical (β > π/2: over the shoulder), and relative to the
    // guard the player holds (expressed as if that guard were at guardBeta).
    const s = u[1] >= 0 ? 1 : -1;
    const hxy = Math.sqrt(u[0] * u[0] + u[1] * u[1]);
    const betaAbs = Math.atan2(-u[2], s * hxy);
    const locked = t < this._lockUntil;
    const still = wl < p.settleRate && !this._swActive;
    this._stillAcc = still ? this._stillAcc + dt : 0;
    this._settled = this._stillAcc >= p.settleTime - 1e-9;
    if (this._settled && !locked && !this._upPend) this._learnGuard(betaAbs, dt);
    const beta = betaAbs - (this._guardBeta - p.guardBeta);

    // Body frame rates
    this._stepRight(w, u, dt, wl, t);
    const R = this._R;
    let wh = w[0] * u[0] + w[1] * u[1] + w[2] * u[2];
    let wv = w[0] * R[0] + w[1] * R[1] + w[2] * R[2];
    if (this.flipX) wh = -wh;
    if (this.flipY) wv = -wv;
    const vx = -wh, vy = wv;
    const m = Math.sqrt(vx * vx + vy * vy);
    // per-sample values for the helpers (fields, not arguments: no boxed doubles at 200 Hz)
    this._m = m;
    this._beta = beta;
    this._vx = vx;
    this._vy = vy;
    this._rising = this._riseStep(t, m);
    this._exStep(t, dt, m, vx, vy);
    this._lookRun(t, dt, wh);

    // Wind-up memory: any period of ≥ windupTime with β ≥ windupBeta (emits once per raise).
    if (beta >= p.windupBeta) {
      if (this._raisedSince !== this._raisedSince) this._raisedSince = t;
      if (this._raiseArmed && !locked && t - this._raisedSince >= p.windupTime - 1e-9) {
        this._lastWindup = t;
        // (a held-up swing and its aftermath decide for themselves: see _upPending)
        const deciding = this._upPend || (this._swActive && (this._swMode === DONE || this._swMode === UP));
        if (!this._raisedEmitted && !this._cutRaise && !deciding) this._emitWindup();
      }
    } else {
      this._raisedSince = NaN;
      if (beta < p.windupBeta - 0.3) {
        this._raisedEmitted = false;
        this._raiseArmed = true;
        if (!this._swActive) this._cutRaise = false;
      }
    }

    if (this._upPend && !this._swActive) this._upPending();

    // ---- swing ----
    if (this._swActive) {
      this._dx += vx * dt;
      this._dy += vy * dt;
      if (m > this._swPeak) this._swPeak = m;
      this._swingStep();
    } else {
      // the rising ramp before swingStart counts toward the sweep
      this._preStep(t, dt, m, vx, vy);
      if (!locked && m > this._start && (t - this._lastThrust >= p.postThrustSwingLock || m >= 6)) {
        this._startSwing();
        this._swingStep();
      }
    }

    // ---- thrust ----
    // The push along the phone's back axis (§5.3), as long as it is forward for the body too
    // (up × R): a phone held tilted back moves along its back axis when it chops down, which is
    // no thrust. Only a swing that is a cut blocks a thrust: the blade dipping as the arm extends
    // is a small swing.
    const a = sample.a;
    const fBody = a[0] * (u[1] * R[2] - u[2] * R[1]) + a[1] * (u[2] * R[0] - u[0] * R[2]) + a[2] * (u[0] * R[1] - u[1] * R[0]);
    const f = Math.min(-a[2], 2 * fBody);
    const cutting = this._swActive && (this._swCut || this._swMode !== NORMAL);
    // Zero vf at rest: calm must persist, since a phone moving at constant velocity also reads calm.
    this._calmRun = sample.calm ? this._calmRun + dt : 0;
    if (cutting || (sample.calm && this._calmRun >= p.calmHold - 1e-9)) this._vf = 0;
    else this._vf = this._vf * Math.exp(-dt / (this._vf > 0 ? p.vfLeak : p.vfLeakBack)) + 0.5 * (f + this._fPrev) * dt;
    this._fPrev = f;
    if (this._cand) {
      if (cutting || m >= this._thrustMaxRate || locked) {
        this._cand = false;
      } else {
        if (f > this._candPeak) this._candPeak = f;
        const el = t - this._candT0;
        if (el >= p.thrustWindow - 1e-9 || (el >= 0.03 - 1e-9 && f < 0.8 * this._candPeak)) {
          this._cand = false;
          this._lastThrust = t;
          const power = clamp((this._candPeak - this._thrustAcc) / p.thrustPowerSpan, 0, 1);
          this._strike(t, 'release');
          if (this._swActive && this._swMode === NORMAL) this._swMode = DONE; // that swing was the thrust's dip
          this._emit({ type: 'thrust', power, peak: this._candPeak, t });
        }
      }
    } else if (
      !locked && !cutting && m < this._thrustMaxRate && f > this._thrustAcc && this._vf > p.thrustVel &&
      t - this._lastThrust >= p.thrustRefractory
    ) {
      this._cand = true;
      this._candT0 = t;
      this._candPeak = f;
    }

    // ---- calibration candidates ----
    this._candStep(t, dt, m, vx, vy);

    // ---- look ----
    this._lookStep(t, dt, wh, w, wl, locked);

    const d = this._dbg;
    d.m = m;
    d.yawRate = wh;
    d.pitchRate = wv;
    d.beta = beta;
    d.peak = this._swActive ? this._swPeak : 0;
    d.fwdAcc = f;
    d.fwdVel = this._vf;
    d.guardBeta = this._guardBeta;
    return ev;
  }

  /**
   * Swing candidates (calibration) completed since the last call, oldest first: every swing-like
   * excursion with m above candStart and a sweep ≥ candSweep, slash or not, as
   * { type: 'swingCandidate', peak, sweep, dir, t0, t } (peak: the whole excursion's, unscaled).
   * Returns a reused array; copy what you keep.
   */
  consumeCandidates() {
    const out = this._candRead;
    out.length = 0;
    for (let i = 0; i < this._candOut.length; i++) out.push(this._candOut[i]);
    this._candOut.length = 0;
    return out;
  }

  /**
   * View heading change released since the last call (radians, positive = turn left), then zeroed.
   * `frameDt`: the frame's duration in seconds (optional; see the per-call limit below).
   */
  consumeLook(frameDt = 0) {
    // At most lookStep per call (a 60 fps frame), so no single frame turns the view by more (the
    // rest follows). A longer frame (`frameDt`, s) may take its share of lookRate, so at a lower
    // frame rate the view keeps up with the body instead of trailing behind it.
    const lim = Math.max(this.p.lookStep, 1.1 * this.p.lookRate * Math.min(frameDt || 0, 0.1));
    const y = clamp(this._L - this._Lc, -lim, lim);
    this._Lc += y;
    this._lookOut.yaw = y;
    return this._lookOut;
  }

  /** Freezes the view heading until resumeLook() (a turn still being eased in stops where it is). */
  holdLook() {
    this._hold = true;
    this._holdAuto = false;
    this._clearLook();
    this._Lc = this._L;
  }

  /** Follows body turns again, from the current view (nothing jumps). */
  resumeLook() {
    this._hold = false;
    this._holdAuto = false;
    this._clearLook();
    this._Lc = this._L;
  }

  /**
   * The player recentred the view (player.js turns it back to the line). Look is relative (body
   * rotation since the last frame), so there is no heading to re-anchor: a turn under way carries on.
   */
  recenter() {
    this._prePend = false;
  }

  /** Phone settled (still, no swing) and held in front of the player: screen upright or tilted back toward the eyes. */
  get settled() {
    return this._settled && (this._upY >= 0.5 || (this._upY >= 0 && this._upZ >= 0.5));
  }

  /** Live diagnostics (reused object). */
  get debug() {
    const d = this._dbg;
    d.phase = this._tLast < this._lockUntil ? 'locked' : this._swActive ? PHASES[this._swMode] : this._cand ? 'thrust' : 'idle';
    d.lookState = this._hold
      ? 'hold'
      : this._dlN > 0 || this._target !== this._L
        ? 'turning'
        : this._striking || this._tLast - this._strikeT < this.p.lookAfter || !this._strikeQuiet
          ? 'blocked'
          : this._settled
            ? 'settled'
            : 'moving';
    return d;
  }

  // ---- internals ----

  _emit(e) {
    this._events.push(e);
    this._dbg.lastEvent = e;
  }

  _emitWindup() {
    const t = this._t;
    this._raisedEmitted = true;
    this._lastWindup = t;
    this._strike(t, 'release');
    this._emit({ type: 'windup', t });
  }

  _gap(t) {
    // time may have jumped backwards (e.g. injected → real samples): shift the timers
    if (t < this._tLast) {
      const shift = t - this._tLast;
      this._lockUntil += shift;
      this._lastThrust += shift;
      this._lastWindup += shift;
      this._pEnd += shift;
      this._holdSince += shift;
      this._quietT += shift;
    }
    // a slash cut off by the gap ends here (else _pEnd stays Infinity and every later swing against
    // it would be judged as its return)
    if (this._pEnd === Infinity) this._pEnd = t;
    this._swActive = false;
    this._upPend = false;
    this._cutRaise = false;
    this._cand = false;
    this._cActive = false;
    this._vf = 0;
    this._raisedSince = NaN;
    this._raiseArmed = false;
    this._stillAcc = 0;
    this._settled = false;
    this._preN = 0;
    this._preX = this._preY = 0;
    this._hN = 0;
    this._Rok = false;
    this._wp0 = this._wp1 = this._wp2 = 0;
    // where the phone went during the gap is unknown: no turn from it, and wait for a settle
    this._strikeT = this._armT = t;
    this._strikeQuiet = this._armQuiet = false;
    this._quietRun = 0;
    this._wLP[0] = this._wLP[1] = this._wLP[2] = 0;
    this._lxIdle = 1;
    this._lxSign = this._lpSign = 0;
    this._lxFast = false;
    this._exX = this._exY = this._exPeak = 0;
    this._exEnd = -Infinity;
    this._clearLook();
  }

  /** Drops every look change not yet shown (the delay line, a held-back run-up, the eased remainder). */
  _clearLook() {
    this._dlN = 0;
    this._rlN = 0;
    this._prePend = false;
    this._target = this._L;
  }

  /**
   * Body-right axis R (screen frame). Carried with the gyro as a world-fixed horizontal vector;
   * re-captured from the attitude whenever no swing is active and the phone is quiet (or has not
   * been quiet for rightStale: a long body turn). A cock-back before a strike is not quiet, so the
   * strike keeps the axis of the guard it started from.
   */
  _stepRight(w, u, dt, wl, t) {
    const R = this._R;
    if (this._Rok) {
      const mx = 0.5 * (w[0] + this._wp0), my = 0.5 * (w[1] + this._wp1), mz = 0.5 * (w[2] + this._wp2);
      const ml = Math.sqrt(mx * mx + my * my + mz * mz);
      const th = ml * dt;
      if (th > 1e-12) {
        // world-fixed vectors turn by −θ about w in the body frame (as frames.js does for up)
        const kx = mx / ml, ky = my / ml, kz = mz / ml;
        const co = Math.cos(th), sn = -Math.sin(th);
        const fx = R[0], fy = R[1], fz = R[2];
        const kd = (kx * fx + ky * fy + kz * fz) * (1 - co);
        R[0] = fx * co + (ky * fz - kz * fy) * sn + kx * kd;
        R[1] = fy * co + (kz * fx - kx * fz) * sn + ky * kd;
        R[2] = fz * co + (kx * fy - ky * fx) * sn + kz * kd;
      }
    }
    this._wp0 = w[0];
    this._wp1 = w[1];
    this._wp2 = w[2];
    const quiet = wl < this.p.quietRate && !this._swActive;
    if (quiet) this._quietT = t;
    if (!this._Rok || (!this._swActive && (quiet || t - this._quietT > this.p.rightStale))) {
      this._captureRight(u);
      return;
    }
    // keep it horizontal and unit (up is corrected by the accelerometer)
    const ru = R[0] * u[0] + R[1] * u[1] + R[2] * u[2];
    R[0] -= ru * u[0];
    R[1] -= ru * u[1];
    R[2] -= ru * u[2];
    const rl = Math.sqrt(R[0] * R[0] + R[1] * R[1] + R[2] * R[2]);
    if (rl < 0.3) this._captureRight(u);
    else {
      R[0] /= rl;
      R[1] /= rl;
      R[2] /= rl;
    }
  }

  /**
   * Body-right from the attitude: forward F is the horizontal part of the phone's back axis (−z,
   * the blade), which wrist roll does not change; when the phone lies nearly flat it blends in the
   * horizontal part of the screen's top edge (+y). Past vertical (raised over the shoulder, the
   * screen upside down) the back axis points backward, so it is flipped. R = F × up.
   */
  _captureRight(u) {
    const uz = u[2], uy = u[1];
    const sg = uy >= 0 ? 1 : -1;
    // horizontal part of −z: (0,0,−1) + uz·u
    let fx = sg * uz * u[0], fy = sg * uz * uy, fz = sg * (uz * uz - 1);
    const bl = Math.sqrt(Math.max(0, 1 - uz * uz));
    // the top edge leans forward when the blade points down, backward when it points up
    const wy = clamp((0.35 - bl) / 0.25, 0, 1) * (uz >= 0 ? 1 : -1);
    if (wy !== 0) {
      // horizontal part of +y: (0,1,0) − uy·u
      fx += wy * -uy * u[0];
      fy += wy * (1 - uy * uy);
      fz += wy * -uy * uz;
    }
    // R = F × up
    let rx = fy * u[2] - fz * u[1], ry = fz * u[0] - fx * u[2], rz = fx * u[1] - fy * u[0];
    let rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
    if (rl < 1e-6) {
      // degenerate: the horizontal part of the screen's right edge
      rx = 1 - u[0] * u[0];
      ry = -u[0] * u[1];
      rz = -u[0] * u[2];
      rl = Math.sqrt(rx * rx + ry * ry + rz * rz) || 1;
    }
    const R = this._R;
    R[0] = rx / rl;
    R[1] = ry / rl;
    R[2] = rz / rl;
    this._Rok = true;
  }

  /** Learns the elevation of the guard the player holds (first settle snaps, then slowly). */
  _learnGuard(betaAbs, dt) {
    if (!this._guardInit) {
      if (betaAbs > -1.45 && betaAbs < 0.9) {
        this._guardBeta = betaAbs;
        this._guardInit = true;
      }
      return;
    }
    if (Math.abs(betaAbs - this._guardBeta) > 0.6) return; // raised, or pointing at the floor: not the guard
    this._guardBeta += (betaAbs - this._guardBeta) * (dt / this.p.guardTau);
  }

  /** Records m and says whether it rose by riseRatio over the last riseWindow. */
  _riseStep(t, m) {
    // and still now: a short fast cock-back is past its peak before the window notices
    const up = this._hN === 0 || m >= this._hM[this._hHead];
    const h = (this._hHead + 1) % HIST;
    this._hHead = h;
    this._hT[h] = t;
    this._hM[h] = m;
    this._hB[h] = this._beta;
    if (this._hN < HIST) this._hN++;
    const tw = t - this.p.riseWindow;
    let i = h;
    for (let k = 1; k < this._hN; k++) {
      i = (i + HIST - 1) % HIST;
      if (this._hT[i] <= tw + 1e-9) {
        this._riseAmt = m > 0 ? (m - this._hM[i]) / m : 0;
        return up && m - this._hM[i] >= this.p.riseRatio * m;
      }
    }
    this._riseAmt = 1;
    return true; // too little history: the motion has only just begun
  }

  /**
   * The last completed motion (a run of m ≥ exRate in one direction with a sweep ≥ exSweep): a
   * stroke that reverses a recent, slower one (its cock-back) is surely a stroke, and may fire
   * with less sweep (swingSweepFast).
   */
  _exStep(t, dt, m, vx, vy) {
    const p = this.p;
    const going = m >= p.exRate;
    if (going && vx * this._exX + vy * this._exY >= 0) {
      this._exX += vx * dt;
      this._exY += vy * dt;
      if (m > this._exPeak) this._exPeak = m;
      return;
    }
    // the run ended (slow, or reversed): remember it
    if (this._exX * this._exX + this._exY * this._exY >= p.exSweep * p.exSweep) {
      this._exDir = Math.atan2(this._exY, this._exX);
      this._exPk = this._exPeak;
      this._exEnd = t;
    }
    this._exX = going ? vx * dt : 0;
    this._exY = going ? vy * dt : 0;
    this._exPeak = going ? m : 0;
  }

  /** The rising ramp before a swing starts: integrated while the rate keeps climbing in one direction. */
  _preStep(t, dt, m, vx, vy) {
    if (m >= this.p.preRate && vx * this._preX + vy * this._preY >= 0) {
      if (this._preN === 0 || m > this._preMax) {
        this._preMax = m;
        this._preMaxT = t;
      }
      if (this._preN === 0) this._preBeta0 = this._beta;
      if (t - this._preMaxT <= 0.03 && m >= this._preMax - (0.03 + 0.02 * m)) {
        this._preX += vx * dt;
        this._preY += vy * dt;
        this._preN++;
        return;
      }
    }
    this._preX = this._preY = 0;
    this._preN = 0;
  }

  /**
   * A swing-like excursion (calibration): starts above candStart, ends when slow, reversed or
   * after candMax. Emitted with its full peak and sweep whatever the thresholds, so the Agoge can
   * calibrate a player whose swings are all too gentle to slash.
   */
  _candStep(t, dt, m, vx, vy) {
    const p = this.p;
    if (!this._cActive) {
      if (m > p.candStart) {
        this._cActive = true;
        this._cX = vx * dt;
        this._cY = vy * dt;
        this._cPeak = m;
        this._cT0 = t;
        this._cLow = -1;
        this._cRev = 0;
      }
      return;
    }
    this._cX += vx * dt;
    this._cY += vy * dt;
    if (m > this._cPeak) this._cPeak = m;
    const sweep = Math.sqrt(this._cX * this._cX + this._cY * this._cY);
    let end = false;
    if (m < p.candEnd) {
      if (this._cLow < 0) this._cLow = t;
      if (t - this._cLow >= p.endHold - 1e-9) end = true;
    } else this._cLow = -1;
    if (sweep >= 0.15 && vx * this._cX + vy * this._cY < 0) {
      if (++this._cRev >= 2) end = true;
    } else this._cRev = 0;
    if (t - this._cT0 >= p.candMax - 1e-9) end = true;
    if (!end) return;
    this._cActive = false;
    if (sweep < p.candSweep) return;
    const e = { type: 'swingCandidate', peak: this._cPeak, sweep, dir: Math.atan2(this._cY, this._cX), t0: this._cT0, t };
    this._dbg.lastCandidatePeak = this._cPeak;
    this._dbg.lastCandidate = e;
    if (this._candOut.length >= 32) this._candOut.shift(); // nobody is reading: keep the newest
    this._candOut.push(e);
  }

  /**
   * Gyro look. Only deliberate body rotation turns the view, 1:1:
   * - the rotation about up counts only while no strike is under way (a swing past lookStrike,
   *   or the post-throw lock), not within lookAfter of one and not before the arm has come to rest
   *   since (low-passed |w| < lookRestRate for lookRestTime, or lookAfterMax at the latest: a slow, sloppy
   *   return to guard is part of the strike), above a soft dead zone (lookDead0…lookDead1);
   * - a run of rotation one way that gets faster than lookMax is no deliberate turn: none of it
   *   counts (what was already shown of it is taken back);
   * - it is shown lookDelay later, so a strike's run-up never shows; and a run of rotation is held
   *   back until it has lasted lookHoldRun (then it is a deliberate turn) or has been over for
   *   lookCockGap with no strike after it, because it may be a cock-back. A run that ended at most
   *   lookCockGap before a strike is decided by the slash: turned against it, it was the cock-back
   *   and is dropped (with anything of it already shown); otherwise it was a turn and is shown;
   * - the view eases toward that heading (lookTau), at most lookRate fast and, in consumeLook(),
   *   lookStep per frame.
   * Rotation during strikes and their aftermath is discarded, never released later.
   */
  _lookStep(t, dt, wh, w, wl, locked) {
    const p = this.p;
    const striking = locked || (this._swActive && this._swStrike);
    this._striking = striking;
    if (striking) {
      this._strikeT = this._armT = t;
      this._strikeQuiet = this._armQuiet = false;
    }
    // at rest: the low-passed rotation (tremor averages out) stays below lookRestRate
    const k = dt > 0 ? dt / (0.06 + dt) : 0;
    const lp = this._wLP;
    lp[0] += k * (w[0] - lp[0]);
    lp[1] += k * (w[1] - lp[1]);
    lp[2] += k * (w[2] - lp[2]);
    const rest = Math.sqrt(lp[0] * lp[0] + lp[1] * lp[1] + lp[2] * lp[2]) < p.lookRestRate && wl < 2 * p.lookRestRate + 0.2;
    this._quietRun = rest ? this._quietRun + dt : 0;
    if (!this._strikeQuiet && !striking && (this._quietRun >= p.lookRestTime - 1e-9 || t - this._strikeT >= p.lookAfterMax)) this._strikeQuiet = true;
    if (!this._armQuiet && !striking && (this._quietRun >= p.lookRestTime - 1e-9 || t - this._armT >= p.lookAfterMax)) this._armQuiet = true;
    if (this._hold && this._holdAuto && t - this._holdSince > AUTO_RESUME) this.resumeLook();
    const after = t - this._strikeT < p.lookAfter || !this._strikeQuiet;
    const r = Math.abs(wh);
    if (!this._hold && !striking && !after && !this._lxFast && dt > 0) {
      const g = smoothstep(p.lookDead0, p.lookDead1, r);
      if (g > 0) {
        if (this._dlN === DELAY) this._release(); // full: show the oldest now
        const i = (this._dlHead + this._dlN) % DELAY;
        this._dlT[i] = t;
        this._dlA[i] = wh * g * dt;
        this._dlN++;
      }
    }
    // Release what is lookDelay old, unless its run may still turn out to be a cock-back: a run is
    // held until it has lasted lookHoldRun (a deliberate turn) or has been over for lookCockGap
    // with no strike after it.
    const curOver = this._lxIdle >= 0.05;
    // the run under way followed the last one closely: that one may be its cock-back
    const chained = this._lpSign !== 0 && this._lxT0 - this._lpT1 <= p.lookCockGap;
    while (this._dlN > 0) {
      const et = this._dlT[this._dlHead];
      if (et > t - p.lookDelay + 1e-9) break;
      let free;
      if (curOver) free = t - this._lxT1 >= p.lookCockGap;
      else if (et >= this._lxT0 || chained) free = t - this._lxT0 >= p.lookHoldRun;
      else free = true;
      if (!free) break;
      this._release();
    }
    const d = this._target - this._L;
    if (d !== 0) {
      let st = dt > 0 ? d * (1 - Math.exp(-dt / p.lookTau)) : 0;
      const lim = p.lookRate * dt;
      st = clamp(st, -lim, lim);
      if (Math.abs(d - st) < 1e-6) st = d;
      this._L += st;
    }
  }

  /**
   * The current run of rotation one way (before the swing logic, so a strike starting on this
   * sample already sees it): once it gets faster than lookMax, none of it is a turn.
   */
  _lookRun(t, dt, wh) {
    const p = this.p;
    const r = Math.abs(wh);
    if (r >= p.lookDead0) {
      const sg = wh > 0 ? 1 : -1;
      if (this._lxIdle >= 0.05 || sg !== this._lxSign) {
        if (this._lxSign !== 0 && Math.abs(this._lxAcc) >= p.lookRunMin) {
          // remember the run that just ended (a candidate cock-back for a strike that follows);
          // a blip of sensor noise is no run
          this._lpT0 = this._lxT0;
          this._lpT1 = this._lxT1;
          this._lpSign = this._lxSign;
        }
        this._lxT0 = t;
        this._lxSign = sg;
        this._lxFast = false;
        this._lxAcc = 0;
      }
      this._lxIdle = 0;
      this._lxT1 = t;
      this._lxAcc += wh * dt;
      if (r > p.lookMax && !this._lxFast) {
        this._lxFast = true;
        this._dropSince(this._lxT0);
        // A stroke, slash or not (one too gentle to slash still swings the arm): it is a strike
        // for the view. The run just before it is decided by its direction now (turned against it,
        // it was the cock-back: dropped), and its follow-through and return are its aftermath.
        // (Not for the recoil rule: this run may be the start of the very swing it judges.)
        if (!(this._swActive && this._swStrike)) {
          this._strike(t, 'swing', true);
          this._decidePre(-sg, 1);
        }
      }
    } else {
      this._lxIdle += dt;
    }
  }

  /** Shows the oldest delayed look change (and remembers it, in case it must be taken back). */
  _release() {
    const h = this._dlHead;
    const a = this._dlA[h];
    this._target += a;
    const j = (this._rlHead + this._rlN) % RELEASED;
    if (this._rlN === RELEASED) this._rlHead = (this._rlHead + 1) % RELEASED;
    else this._rlN++;
    this._rlT[j] = this._dlT[h];
    this._rlA[j] = a;
    this._dlHead = (h + 1) % DELAY;
    this._dlN--;
  }

  /** A run of rotation since t0 was too fast for a turn: drop what is delayed, take back what was shown. */
  _dropSince(t0) {
    while (this._dlN > 0 && this._dlT[(this._dlHead + this._dlN - 1) % DELAY] >= t0) this._dlN--;
    let sum = 0;
    while (this._rlN > 0) {
      const i = (this._rlHead + this._rlN - 1) % RELEASED;
      if (this._rlT[i] < t0) break;
      sum += this._rlA[i];
      this._rlN--;
    }
    if (!this._hold) this._target -= sum;
  }

  /**
   * A strike begins. mode 'swing' (a swing past lookStrike): the run under way is its own run-up
   * (never shown). The run just before it, if it ended within lookCockGap, may have been its
   * cock-back: what of it is still delayed is held back, and what of it was shown remembered, for
   * the slash to decide (_decidePre). Anything older was a turn and is shown now. mode 'release'
   * (a thrust, a wind-up): everything delayed was a turn: show it. mode 'drop' (a throw): nothing
   * delayed is shown. run: a stroke's own fast run (see _lookRun), a strike for the view only.
   */
  _strike(t, mode, run = false) {
    this._strikeT = t;
    this._strikeQuiet = false;
    if (!run) {
      this._armT = t;
      this._armQuiet = false;
    }
    if (mode === 'swing' && this._lxIdle < 0.05) {
      while (this._dlN > 0 && this._dlT[(this._dlHead + this._dlN - 1) % DELAY] >= this._lxT0) this._dlN--;
    }
    // (measured to the start of the strike's own run-up when it is under way)
    const from = this._lxIdle < 0.05 ? this._lxT0 : t;
    const cockT0 = mode === 'swing' && this._lpSign !== 0 && from - this._lpT1 <= this.p.lookCockGap ? this._lpT0 : Infinity;
    let pre = 0, older = 0;
    for (let k = 0; k < this._dlN; k++) {
      const i = (this._dlHead + k) % DELAY;
      if (this._dlT[i] >= cockT0) pre += this._dlA[i];
      else older += this._dlA[i];
    }
    this._dlN = 0;
    if (mode !== 'drop' && !this._hold) this._target += older;
    let undo = 0;
    if (cockT0 < Infinity) {
      for (let k = 0; k < this._rlN; k++) {
        const i = (this._rlHead + k) % RELEASED;
        if (this._rlT[i] >= cockT0) undo += this._rlA[i];
      }
    }
    this._rlN = 0;
    if (mode === 'release') {
      if (!this._hold) this._target += pre;
      return;
    }
    this._prePend = mode === 'swing' && (pre !== 0 || undo !== 0);
    this._preAmt = pre;
    this._undoAmt = undo;
  }

  /**
   * The strike's slash is known (dx: its sideways sweep). A run just before it that turned against
   * it was its cock-back: drop it, and take back what of it was shown. Otherwise it was a turn: show it.
   */
  _decidePre(dx, sweep) {
    if (!this._prePend) return;
    this._prePend = false;
    if (this._hold) return;
    // positive look = left; dx > 0 = blade moving right: a cock-back for it turned left first
    const cock = Math.abs(dx) >= 0.3 * sweep && (this._preAmt + this._undoAmt) * dx > 0;
    if (cock) this._target -= this._undoAmt;
    else this._target += this._preAmt;
  }

  _startSwing() {
    const p = this.p;
    const t = this._t, m = this._m, beta = this._beta;
    // A new swing right after an uppercut that ended overhead: with the sword it was the cut
    // (fire it first, so the recoil rule sees it); with the spear this swing decides (_swingStep).
    if (this._upPend && this.weapon !== 'spear') this._fireUp();
    this._swActive = true;
    this._swMode = NORMAL;
    this._swT0 = t;
    this._swPeak = m;
    // where the motion began: the rising ramp's start, if it led here
    this._swBeta0 = this._preN > 0 ? Math.min(beta, this._preBeta0) : beta;
    // the highest the blade was just before (a chop is raised first, if only a little; a dip is not)
    let top = this._swBeta0;
    for (let k = 0, i = this._hHead; k < this._hN; k++, i = (i + HIST - 1) % HIST) {
      if (this._hT[i] < t - p.dipLook) break;
      if (this._hB[i] > top) top = this._hB[i];
    }
    this._swBetaTop = top;
    this._swLow = -1;
    this._swRev = 0;
    this._swFired = false;
    this._swCut = false;
    this._swStrike = false;
    // a reverse swing soon after a slash, or before the arm has come to rest since, is its way back
    this._swRevWin = this._pValid && (t - this._pEnd <= p.reverseWindow || !this._armQuiet);
    this._swWindMem = t - this._lastWindup <= p.windupMemory || (this._raiseArmed && beta >= p.windupBeta);
    // the sweep so far: the rising ramp that led here (this sample included), or just this sample
    if (this._preN > 0) {
      this._dx = this._preX;
      this._dy = this._preY;
    } else {
      this._dx = this._vx * this._dtNow;
      this._dy = this._vy * this._dtNow;
    }
    this._preX = this._preY = 0;
    this._preN = 0;
  }

  /** May the current swing produce a slash now (the recoil rule)? Thresholds are checked by the caller. */
  _slashOk() {
    const dir = this._dir, sweep = this._sweepNow, peak = this._swPeak;
    if (peak < this._peak) return false;
    if (this._swRevWin && within(dir, this._pDir + Math.PI, this.p.reverseSector)) {
      return peak >= Math.max(this._peak, this.p.reverseRatio * this._pPeak) && sweep >= this.p.reverseSweep;
    }
    return true;
  }

  _slashEvent(dir, peak, sweep, t, own = false) {
    // Fired early, the stroke is still speeding up: its power counts on a little more to come.
    // (`own`: a pending uppercut fired during the next swing: that swing's speed is not its own.)
    const est = !own && this._swActive && this._rising ? Math.max(peak, this.p.powerRise * this._m) : peak;
    const power = clamp((est - this._peak) / this._slashSpan, 0, 1);
    return { type: 'slash', dir: snapCardinal(dir, this.p.cardinalSnap), rawDir: dir, power, peak, sweep, t };
  }

  _fireSlash() {
    const t = this._t, dir = this._dir, sweep = this._sweepNow;
    const peak = this._swPeak;
    this._swMode = DONE;
    this._swFired = true;
    this._pValid = true;
    this._pDir = dir;
    this._pPeak = peak;
    this._pEnd = Infinity;
    if (within(dir, HALF_PI, UP_SECTOR)) this._cutRaise = true; // a raise is a cut or a wind-up, never both
    this._decidePre(this._dx, sweep);
    this._emit(this._slashEvent(dir, peak, sweep, t));
  }

  _resolveThrow() {
    const t = this._t;
    const peak = this._swPeak;
    // A remembered wind-up plus a downward swing is a throw: below throwPeak it is a weak one
    // (power 0), never a chop. A short, slow one (below throwMin and throwMinSweep) is no throw:
    // after an uppercut that ended overhead it is that cut's rebound.
    this._swMode = DONE;
    if (peak >= this._peak && (peak >= this._throwMin || this._sweepNow >= this.p.throwMinSweep)) {
      if (this._upPend) this._upWindup(); // the overhead swing was this throw's wind-up
      const power = clamp((peak - this._throwPeak) / Math.max(1e-6, this._throwFull - this._throwPeak), 0, 1);
      this._lockUntil = t + this.p.postThrowLock;
      this._lastWindup = -Infinity;
      this._cand = false;
      this._vf = 0;
      this._pValid = false;
      this._strike(t, 'drop');
      if (!this._hold) {
        this._hold = true;
        this._holdAuto = true;
        this._holdSince = t;
        this._clearLook();
      }
      this._emit({ type: 'throw', power, peak, t });
    } else if (this._upPend) {
      this._fireUp();
    }
  }

  /**
   * After a held-up swing that ended overhead (not in a swing): held still up for windupTime, it
   * was a raise (wind-up); moving back down, it was a cut that rebounds (the uppercut). With the
   * spear a downward motion may also be the throw itself, so it waits to see whether that
   * becomes a swing (_startSwing/_swingStep decide) or stays a slow rebound.
   */
  _upPending() {
    const p = this.p;
    const t = this._t, beta = this._beta;
    if (t < this._lockUntil) {
      this._upPend = false;
      return;
    }
    if (beta > this._upTop) this._upTop = beta;
    if (!this._upReb) {
      this._upRebN = this._vy < -0.5 ? this._upRebN + 1 : 0;
      if (this._upRebN >= 2 || beta < this._upTop - 0.1 || beta < p.windupBeta) {
        if (this.weapon !== 'spear') {
          this._fireUp();
          return;
        }
        this._upReb = true;
        this._upRebPeak = this._m;
      } else if (t - this._upT0 >= p.windupTime - 1e-9) {
        this._upWindup();
      }
      return;
    }
    // spear, moving back down: a rebound peaks below swingStart (else a swing starts and decides)
    const m = this._m;
    if (m > this._upRebPeak) this._upRebPeak = m;
    if ((this._upRebPeak > 1 && m < 0.7 * this._upRebPeak) || beta < p.windupBeta - 0.3 || t - this._upT0 > 0.5) this._fireUp();
  }

  /** The pending overhead swing was a raise: a wind-up. */
  _upWindup() {
    this._upPend = false;
    this._prePend = false;
    this._lastWindup = this._t;
    if (!this._raisedEmitted) this._emitWindup();
  }

  /** The pending overhead swing was the uppercut: emit it now (it ended at _upT0). */
  _fireUp() {
    this._upPend = false;
    this._cutRaise = true; // a raise is a cut or a wind-up, never both
    const t = this._t;
    const peak = this._upPeak;
    this._pValid = true;
    this._pDir = this._upDir;
    this._pPeak = peak;
    this._pEnd = t;
    this._decidePre(Math.cos(this._upDir) * this._upSweep, this._upSweep);
    this._emit(this._slashEvent(this._upDir, peak, this._upSweep, t, true));
  }

  _swingStep() {
    const p = this.p;
    const t = this._t, m = this._m, beta = this._beta, vx = this._vx, vy = this._vy;
    const dx = this._dx, dy = this._dy;
    const sweep = Math.sqrt(dx * dx + dy * dy);
    const dir = Math.atan2(dy, dx);
    this._sweepNow = sweep;
    this._dir = dir;
    if (!this._swStrike && this._swPeak >= this._strikeRate) {
      this._swStrike = true; // a strike, not a turn: its run-up never reaches the view
      this._strike(t, 'swing');
    }

    // end conditions: (a) slow for endHold, (b) reversal, (c) maxSwing
    let end = false;
    if (m < this._end) {
      if (this._swLow < 0) this._swLow = t;
      if (t - this._swLow >= p.endHold - 1e-9) end = true;
    } else {
      this._swLow = -1;
    }
    if (sweep >= 0.2 && vx * dx + vy * dy < 0) {
      if (++this._swRev >= 2) end = true;
    } else {
      this._swRev = 0;
    }
    // (a held-up swing may be a slow raise: it runs until it slows down, up to maxRaise)
    if (t - this._swT0 >= (this._swMode === UP ? p.maxRaise : p.maxSwing) - 1e-9) end = true;

    // A cut: the direction is reliable. Early while the stroke still accelerates (a cock-back
    // stops accelerating at half its short arc), or once the sweep is long whatever the phase.
    // Early, the direction must also be clear of a token boundary (a stroke that is still bending
    // there, e.g. a diagonal with wrist roll, waits a little).
    if (!this._swCut) {
      const pk = this._swPeak;
      // (still speeding up, and still fast: a flicker of the rate as a swing dies out is no stroke)
      const clear = this._rising && m >= this._start && !within(dir, Math.round((dir - Math.PI / 8) / (Math.PI / 4)) * (Math.PI / 4) + Math.PI / 8, p.dirMargin);
      if (within(dir, -HALF_PI, p.dipSector) && this._swBetaTop <= p.guardBeta + p.dipRaise) {
        // Down from the guard: maybe a dip to a low guard (an uppercut's cock-back), which peaks
        // at half its short arc. A chop is still clearly speeding up a little further on.
        if (pk >= this._peak && (sweep >= p.dipSweep || (clear && sweep >= p.dipEarlySweep && this._riseAmt >= p.dipRise))) this._swCut = true;
      } else if (pk >= this._peak && (sweep >= this._sweepLate || (clear && sweep >= this._sweep))) {
        this._swCut = true;
      } else if (
        pk >= this._peak && clear && sweep >= this._sweepFast &&
        t - this._exEnd <= p.fastWindow && within(dir, this._exDir + Math.PI, UP_SECTOR) && pk >= p.fastRatio * this._exPk
      ) {
        // after its own cock-back (a recent, much slower motion the other way) a stroke is surely a
        // stroke: it needs less sweep
        this._swCut = true;
      }
      if (this._swCut) {
        this._vf = 0;
        this._cand = false;
      }
    }
    const cut = this._swCut;

    const mode = this._swMode;
    if (mode === NORMAL) {
      const upHeld = this._swBeta0 >= p.upHoldStartBeta && within(dir, HALF_PI, UP_HOLD);
      if (upHeld && sweep >= 0.15 && beta >= p.windupBeta && this._raiseArmed && !cut) {
        // phone raised beside or behind the head, too gently for a cut: a wind-up, never a slash
        if (this._upPend) this._fireUp();
        this._swMode = DONE;
        if (!this._raisedEmitted) this._emitWindup();
        else this._lastWindup = t;
      } else if (this.weapon === 'spear' && this._swWindMem && within(dir, -HALF_PI, p.throwSector)) {
        // a downward swing after a wind-up (spear): never a chop; it is a throw, or the rebound of
        // an uppercut that ended overhead, decided at its release (_resolveThrow)
        if (sweep >= 0.12) this._swMode = THROW;
      } else if (cut) {
        if (this._upPend) this._fireUp(); // otherwise it was the uppercut, which comes first
        if (upHeld) {
          this._swMode = UP;
        } else if (this._slashOk()) {
          this._fireSlash();
        }
      }
    }
    if (this._swMode === UP && beta < p.windupBeta - 0.3 && sweep >= this._sweep && m <= 0.97 * this._swPeak && !this._rising) {
      // Braking: a minimum-jerk stroke past its peak has remainingRatio(m/peak)·sweep still to
      // go. If it will stop well short of windupBeta it is an uppercut, not a raise: fire now.
      if (beta + remainingRatio(m / this._swPeak) * sweep < p.windupBeta - 0.35 && this._slashOk()) this._fireSlash();
    }
    if (this._swMode === THROW) {
      const pk = this._swPeak;
      if ((pk >= this._throwPeak && m < p.throwReleaseRatio * pk) || sweep >= p.throwMaxSweep || end) {
        this._resolveThrow();
      }
    }
    if (end) {
      if (this._upPend) this._fireUp(); // the spear's next swing was no throw: that was the uppercut
      if (this._swMode === UP && this._slashOk()) {
        if (beta >= p.windupBeta && this._raiseArmed) {
          // ended overhead: an uppercut that will rebound, or a raise that will be held
          this._upPend = true;
          this._upReb = false;
          this._upT0 = t;
          this._upTop = beta;
          this._upRebN = 0;
          this._upRebPeak = 0;
          this._upDir = dir;
          this._upPeak = this._swPeak;
          this._upSweep = sweep;
        } else {
          this._fireSlash();
        }
      }
      if (this._swFired) {
        this._pEnd = t;
        this._pPeak = this._swPeak;
      }
      if (!this._upPend) this._prePend = false; // no slash: what it held back was its own run-up
      this._swActive = false;
      this._vf = 0;
    }
  }
}
