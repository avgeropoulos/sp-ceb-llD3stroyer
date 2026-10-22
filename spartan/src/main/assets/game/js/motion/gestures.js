/**
 * GestureRecognizer (DESIGN.md §5.3). PURE.
 *
 * Consumes screen-frame Samples from MotionFrames and recognizes slashes (with direction),
 * thrusts, throw wind-ups and throws, and releases the gyro look heading. Rates are taken in a
 * gravity-aligned frame so the blade direction is independent of how the phone is rolled:
 * pitch about the horizontal axis r ⟂ blade, and the sideways rate about the view-up axis
 * U = r × b (vertical, tilted with the blade). push() allocates nothing except the (rare) event
 * objects it returns.
 */

const DEG = Math.PI / 180;
const HALF_PI = Math.PI / 2;
const UP_SECTOR = 60 * DEG; // §5.3: upward swings and wind-ups
const AUTO_RESUME = 2.5; // s: failsafe when a throw starts no spear cam (no holdLook/resumeLook follows)
const RING = 256; // committed-look history (≥ 1.28 s at 200 Hz) for the provisional window
const HOLDS = 4; // headings the phone was held at before an excursion's first swing
const HOLD_MIN = 0.04; // s of stillness that count as a hold
const HOLD_SEP = 0.1; // rad: a hold this close to the excursion's start is no turn
const HOLD_NEAR = 0.35; // a strike 'came back to a hold' within this fraction of the hold's offset

/**
 * Every threshold of the recognizer (§5.3). Sensitivity scales some of them (see setSensitivity).
 * Tuned against the §5.3 test matrix (tests/unit/motion-gestures.test.mjs); differences from the
 * §5.3 table:
 * - upHoldStartBeta −0.2 → −0.45: a brisk throw wind-up (8–12 rad/s) from the default guard
 *   (β0 ≈ −0.39) is detected at β ≈ −0.3; it must be held, not fired as an uppercut. Uppercuts
 *   started lower still fire early; others fire at swing end, never with a wrong token.
 * - windupBeta 1.0 → 1.6: an uppercut from a level guard ends at β ≈ 1.4; a throw wind-up
 *   goes past vertical (β ≈ 2.1–2.6).
 * - A held-up swing that reaches windupBeta is not a wind-up at once: a cut that ends overhead
 *   rebounds, a raise is held. It becomes a wind-up only if the phone stays up, without moving
 *   back down, for windupTime after the swing; otherwise it is the uppercut (see _upPending).
 *   A held-up swing also fires early once it is clearly braking well below windupBeta.
 * - vfLeakBack (new): backward vf leaks slowly so a retraction's final deceleration cannot push
 *   vf above thrustVel (vfLeak still bounds positive drift).
 * - calmHold (new): vf is zeroed only after calm persists; a phone moving at constant velocity
 *   also reads calm for a sample or two.
 * - swingSettleTime (new): after an excursion that contained a swing, the look waits this long
 *   before treating the new heading as a body turn (strikes pause before their return).
 * - prepRate, prepMax, prepSettleTime, prepWindow (new): a cock-back before a strike is often
 *   slower than swingStart, so it is no swing, yet its heading change must not stay behind as a
 *   turn. A brisk (peak ≥ prepRate/s), short (|δ| ≤ prepMax) excursion waits prepSettleTime
 *   before it counts as a turn; and look changes released within prepWindow before a swing are
 *   provisional (see _rollBack).
 */
export const GESTURE_DEFAULTS = Object.freeze({
  swingStart: 3.5, swingPeak: 5.0, swingSweep: 0.45, swingEnd: 1.5, endHold: 0.04,
  maxSwing: 0.7, reverseWindow: 0.45, reverseRatio: 0.6, reverseSweep: 0.5, reverseSector: 60 * DEG,
  thrustAcc: 12, thrustVel: 0.15, thrustWindow: 0.06, thrustRefractory: 0.35, vfLeak: 0.5, postThrustSwingLock: 0.15,
  vfLeakBack: 4, calmHold: 0.1,
  upHoldStartBeta: -0.45, windupBeta: 1.6, windupTime: 0.1, windupMemory: 1.0,
  throwPeak: 7, throwFull: 13, throwSector: 65 * DEG, throwReleaseRatio: 0.6, throwMaxSweep: 1.4, postThrowLock: 1.0,
  settleRate: 1.0, settleTime: 0.15, swingSettleTime: 0.6, absorb: 0.35, lookEase: 0.25, deadZone: 0.02,
  prepRate: 1.75, prepMax: 0.6, prepSettleTime: 0.45, prepWindow: 0.8,
  slashPowerSpan: 10, thrustPowerSpan: 18,
});

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

/** |wrap(a − b)| ≤ lim (returns a boolean, so nothing is boxed in the hot path) */
function within(a, b, lim) {
  let d = (a - b) % (2 * Math.PI);
  if (d > Math.PI) d -= 2 * Math.PI;
  else if (d < -Math.PI) d += 2 * Math.PI;
  return d <= lim && d >= -lim;
}

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
    this._events = [];
    this._lookOut = { yaw: 0 };
    this._dbg = { m: 0, yawRate: 0, pitchRate: 0, beta: 0, phase: 'idle', peak: 0, fwdAcc: 0, fwdVel: 0, lookState: 'settled', lastEvent: null };
    // look heading (kept across reset)
    this._hYaw = 0;
    this._O = 0;
    this._L = 0;
    this._Lc = 0;
    this._pending = 0;
    this._upY = 1;
    this._tLast = NaN;
    this._t = 0;
    this._m = this._beta = this._vx = this._vy = 0;
    this._fRef = [0, 0, 0]; // world-fixed horizontal reference in the body frame (0 = unset)
    this._wp0 = this._wp1 = this._wp2 = 0;
    this._psi = 0;
    this._psiOk = false;
    this._dir = this._sweepNow = 0;
    // committed look (L + pending) per sample, for _rollBack
    this._ringT = new Float64Array(RING);
    this._ringC = new Float64Array(RING);
    this._ringHead = 0;
    this._ringN = 0;
    this._holdY = new Float64Array(HOLDS);
    this._holdN = 0;
    this.reset();
  }

  /**
   * Sensitivity 0.5–2.0: divides swingStart, swingPeak, thrustAcc; swingSweep by √s. throwPeak and
   * throwFull are divided by max(1, s): a hard slasher (s < 1) does not have to throw harder too.
   */
  setSensitivity(s) {
    s = clamp(Number(s) || 1, 0.5, 2);
    if (s === this.sens) return;
    this.sens = s;
    const p = this.p;
    this._start = p.swingStart / s;
    this._peak = p.swingPeak / s;
    this._sweep = p.swingSweep / Math.sqrt(s);
    this._throwPeak = p.throwPeak / Math.max(1, s);
    this._throwFull = p.throwFull / Math.max(1, s);
    this._thrustAcc = p.thrustAcc / s;
    this._slashSpan = Math.max(0.5, Math.min(p.slashPowerSpan, 0.9 * this.gyroRange - this._peak));
    this._prepRate = p.prepRate / s;
  }

  /** 'sword' | 'spear' (throws only happen with the spear). */
  setWeapon(w) {
    this.weapon = w === 'spear' ? 'spear' : 'sword';
  }

  /** Clears swing/thrust/wind-up state, recoil memory, locks and look state (not the released heading). */
  reset() {
    this._swActive = false;
    this._swMode = NORMAL;
    this._swT0 = 0;
    this._dx = this._dy = 0;
    this._swPeak = 0;
    this._swBeta0 = 0;
    this._swLow = -1;
    this._swRev = 0;
    this._swRevWin = false;
    this._swWindMem = false;
    this._swFired = false;
    this._pValid = false;
    this._pDir = 0;
    this._pPeak = 0;
    this._pEnd = -Infinity;
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
    this._hold = false;
    this._holdAuto = false;
    this._holdSince = 0;
    this._settleAcc = 0;
    this._stillAcc = 0;
    this._settled = false;
    this._lookSettled = false;
    this._excSwing = false;
    this._excPeak = 0;
    this._swStarted = false;
    this._gapAnchor = false;
    this._pending = 0;
    this._O = this._hYaw - this._L;
    this._anchorLook();
    this._events.length = 0;
    this._fRef[0] = this._fRef[1] = this._fRef[2] = 0;
    this._wp0 = this._wp1 = this._wp2 = 0;
    this._psiOk = false;
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
    const u = sample.up, w = sample.w;

    // Rates in the gravity-aligned frame (§5.3)
    const s = u[1] >= 0 ? 1 : -1;
    const hxy = Math.sqrt(u[0] * u[0] + u[1] * u[1]);
    const beta = Math.atan2(-u[2], s * hxy);
    const yawRate = w[0] * u[0] + w[1] * u[1] + w[2] * u[2];
    // pitch axis r (horizontal, ⟂ blade) and the view-up axis U = r × b (vertical tilted with the blade)
    let rx, ry, rz;
    if (hxy >= 0.35) {
      rx = (s * u[1]) / hxy;
      ry = (-s * u[0]) / hxy;
      rz = 0;
    } else {
      rx = 1 - u[0] * u[0];
      ry = -u[0] * u[1];
      rz = -u[0] * u[2];
      const rl = Math.sqrt(rx * rx + ry * ry + rz * rz) || 1;
      rx /= rl;
      ry /= rl;
      rz /= rl;
    }
    const pitchRate = w[0] * rx + w[1] * ry + w[2] * rz;
    // Sideways blade rate: w·U. For a turn about vertical it equals §5.3's yawRate·cos β; unlike
    // it, it is exact for swings about the phone's own tilted axis and ignores roll about the blade.
    const sideRate = -w[0] * ry + w[1] * rx;
    const m = Math.sqrt(sideRate * sideRate + pitchRate * pitchRate);
    const vx = -sideRate, vy = pitchRate;
    const locked = t < this._lockUntil;
    this._upY = u[1];
    // per-sample values for the helpers (fields, not arguments: no boxed doubles at 200 Hz)
    this._m = m;
    this._beta = beta;
    this._vx = vx;
    this._vy = vy;

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
    } else if (!locked && m > this._start && (t - this._lastThrust >= p.postThrustSwingLock || m >= 6)) {
      this._startSwing();
      this._dx = vx * dt;
      this._dy = vy * dt;
      this._swingStep();
    }

    // ---- thrust ----
    const f = -sample.a[2];
    // Zero vf at rest: calm must persist, since a phone moving at constant velocity also reads calm.
    this._calmRun = sample.calm ? this._calmRun + dt : 0;
    if (this._swActive || (sample.calm && this._calmRun >= p.calmHold - 1e-9)) this._vf = 0;
    else this._vf = this._vf * Math.exp(-dt / (this._vf > 0 ? p.vfLeak : p.vfLeakBack)) + 0.5 * (f + this._fPrev) * dt;
    this._fPrev = f;
    if (this._cand) {
      if (this._swActive || m >= 4 || locked) {
        this._cand = false;
      } else {
        if (f > this._candPeak) this._candPeak = f;
        const el = t - this._candT0;
        if (el >= p.thrustWindow - 1e-9 || (el >= 0.03 - 1e-9 && f < 0.8 * this._candPeak)) {
          this._cand = false;
          this._lastThrust = t;
          const power = clamp((this._candPeak - this._thrustAcc) / p.thrustPowerSpan, 0, 1);
          this._emit({ type: 'thrust', power, peak: this._candPeak, t });
        }
      }
    } else if (
      !locked && !this._swActive && m < 4 && f > this._thrustAcc && this._vf > p.thrustVel &&
      t - this._lastThrust >= p.thrustRefractory
    ) {
      this._cand = true;
      this._candT0 = t;
      this._candPeak = f;
    }

    // ---- look: follow the settled heading ----
    // The heading is the azimuth about up of the screen's right edge, measured against a
    // world-fixed horizontal reference carried by the gyro. For a turn it equals ∫(w·up)dt, but
    // it is a function of attitude, so an arm motion that returns to the guard leaves nothing
    // behind (∫(w·up)dt does not: e.g. an overhead chop that passes the blade over vertical in a
    // slightly different plane than the raise).
    const fr = this._fRef;
    {
      const mx = 0.5 * (w[0] + this._wp0), my = 0.5 * (w[1] + this._wp1), mz = 0.5 * (w[2] + this._wp2);
      const ml = Math.sqrt(mx * mx + my * my + mz * mz);
      const th = ml * dt;
      if (th > 1e-12) {
        // world-fixed vectors turn by −θ about w in the body frame (as frames.js does for up)
        const kx = mx / ml, ky = my / ml, kz = mz / ml;
        const co = Math.cos(th), sn = -Math.sin(th);
        const fx = fr[0], fy = fr[1], fz = fr[2];
        const kd = (kx * fx + ky * fy + kz * fz) * (1 - co);
        fr[0] = fx * co + (ky * fz - kz * fy) * sn + kx * kd;
        fr[1] = fy * co + (kz * fx - kx * fz) * sn + ky * kd;
        fr[2] = fz * co + (kx * fy - ky * fx) * sn + kz * kd;
      }
      this._wp0 = w[0];
      this._wp1 = w[1];
      this._wp2 = w[2];
    }
    // keep the reference horizontal (up may have been corrected by the accelerometer)
    const fu = fr[0] * u[0] + fr[1] * u[1] + fr[2] * u[2];
    fr[0] -= fu * u[0];
    fr[1] -= fu * u[1];
    fr[2] -= fu * u[2];
    let fl = Math.sqrt(fr[0] * fr[0] + fr[1] * fr[1] + fr[2] * fr[2]);
    // the screen-right edge projected on the horizontal plane
    const hx = 1 - u[0] * u[0], hy = -u[0] * u[1], hz = -u[0] * u[2];
    const hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
    if (fl < 0.5 && hl > 0.2) {
      fr[0] = hx;
      fr[1] = hy;
      fr[2] = hz;
      fl = hl;
      this._psiOk = false;
    }
    if (fl > 1e-9) {
      fr[0] /= fl;
      fr[1] /= fl;
      fr[2] /= fl;
    }
    let dHead = yawRate * dt; // fallback when the phone stands on its side
    if (hl > 0.2) {
      const cosA = fr[0] * hx + fr[1] * hy + fr[2] * hz;
      const cx = fr[1] * hz - fr[2] * hy, cy = fr[2] * hx - fr[0] * hz, cz = fr[0] * hy - fr[1] * hx;
      const psi = Math.atan2(u[0] * cx + u[1] * cy + u[2] * cz, cosA);
      if (this._psiOk) {
        dHead = psi - this._psi;
        if (dHead > Math.PI) dHead -= 2 * Math.PI;
        else if (dHead < -Math.PI) dHead += 2 * Math.PI;
      }
      this._psi = psi;
      this._psiOk = true;
    } else {
      this._psiOk = false;
    }
    if (dt > 0) {
      const rate = dHead / dt;
      const dz = p.deadZone;
      this._hYaw += (rate > dz ? rate - dz : rate < -dz ? rate + dz : 0) * dt;
    }
    // `settled` (the spear cam's 'looked back') is plain stillness; the post-throw lock only
    // keeps the look from settling, so the cam lets go as soon as the phone is still.
    const still = m < p.settleRate && !this._swActive;
    this._stillAcc = still ? this._stillAcc + dt : 0;
    const moving = !still || locked;
    if (moving) {
      this._settleAcc = 0;
      this._lookSettled = false;
      if (this._swActive || locked) this._excSwing = true;
      if (m > this._excPeak) this._excPeak = m;
    } else {
      this._settleAcc += dt;
    }
    if (this._swStarted) {
      this._swStarted = false;
      if (!this._excStruck) {
        // the first swing of this excursion: the holds before it are now known
        this._excStruck = true;
        if (!this._hold) this._rollBack(t);
      }
    }
    if (this._rollPend && (this._sweepNow >= 0.2 || !this._swActive)) this._rollDecide();
    if (this._hold) {
      if (this._holdAuto && t - this._holdSince > AUTO_RESUME) this.resumeLook();
    } else {
      if (this._pending !== 0) {
        // release a body turn, eased over lookEase
        const k = 1 - Math.exp((-3 * dt) / p.lookEase);
        let rel = this._pending * k;
        if (Math.abs(this._pending - rel) < 1e-4) rel = this._pending;
        this._L += rel;
        this._pending -= rel;
      }
      const target = this._hYaw - this._O;
      if (this._lookSettled) {
        this._L = target - this._pending; // slow turns are followed 1:1
      } else if (!moving && this._settleAcc >= p.settleTime - 1e-9) {
        // Settled after an excursion: a strike that came back near its start was an imprecise
        // return to guard (absorb); anything else is a body turn (release). After a data gap the
        // rotation in between is unknown: re-anchor instead. A brisk, short excursion may be the
        // cock-back of a strike: it waits prepSettleTime for that strike before it counts as a turn.
        let delta = target - this._L - this._pending;
        const prep = this._excPeak >= this._prepRate && Math.abs(delta) <= p.prepMax;
        const wait = this._excSwing ? p.swingSettleTime : prep ? p.prepSettleTime : p.settleTime;
        if (this._settleAcc >= wait - 1e-9) {
          if (this._rolled !== 0) {
            // A strike took back a look change as its cock-back (_rollBack). Where the phone came
            // back to decides: nearer the changed heading, it was a body turn after all.
            if (Math.abs(delta - this._rolled) < Math.abs(delta)) {
              this._pending += this._rolled;
              delta -= this._rolled;
            }
            this._rolled = 0;
          }
          const absorb = this._excSwing && Math.abs(delta) <= p.absorb && !this._turnedFirst(delta);
          if (this._gapAnchor || absorb) this._O += delta;
          else this._pending += delta;
          this._lookSettled = true;
          this._excSwing = false;
          this._excPeak = 0;
          this._excStruck = false;
          this._holdN = 0;
          this._holdLive = false;
          this._gapAnchor = false;
        }
      }
    }
    this._settled = this._stillAcc >= p.settleTime - 1e-9;
    // Holds before the excursion's first swing: candidates for the guard a strike returns to
    if (!still) {
      this._holdLive = false;
    } else if (!this._lookSettled && !this._excStruck && this._stillAcc >= HOLD_MIN - 1e-9) {
      if (!this._holdLive) {
        this._holdLive = true; // a new hold
        if (this._holdN === HOLDS) this._holdY.copyWithin(0, 1);
        else this._holdN++;
      }
      this._holdY[this._holdN - 1] = this._hYaw;
    }
    // committed-look history
    const h = (this._ringHead + 1) % RING;
    this._ringHead = h;
    this._ringT[h] = t;
    this._ringC[h] = this._L + this._pending;
    if (this._ringN < RING) this._ringN++;

    const d = this._dbg;
    d.m = m;
    d.yawRate = yawRate;
    d.pitchRate = pitchRate;
    d.beta = beta;
    d.peak = this._swActive ? this._swPeak : 0;
    d.fwdAcc = f;
    d.fwdVel = this._vf;
    return ev;
  }

  /** Heading change released since the last call (radians, positive = turn left), then zeroed. */
  consumeLook() {
    this._lookOut.yaw = this._L - this._Lc;
    this._Lc = this._L;
    return this._lookOut;
  }

  /** Freezes the released heading until resumeLook(). */
  holdLook() {
    this._hold = true;
    this._holdAuto = false;
    this._anchorLook();
  }

  /** Re-anchors (O = hYaw − L) so nothing jumps, and follows again. */
  resumeLook() {
    this._hold = false;
    this._holdAuto = false;
    this._pending = 0;
    this._O = this._hYaw - this._L;
    this._anchorLook();
  }

  /** Makes the current phone heading the reference for the current view. */
  recenter() {
    this._pending = 0;
    this._O = this._hYaw - this._L;
    this._anchorLook();
  }

  /** Phone settled and the screen upright, facing the player. */
  get settled() {
    return this._settled && this._upY >= 0.5;
  }

  /** Live diagnostics (reused object). */
  get debug() {
    const d = this._dbg;
    d.phase = this._tLast < this._lockUntil ? 'locked' : this._swActive ? PHASES[this._swMode] : this._cand ? 'thrust' : 'idle';
    d.lookState = this._hold ? 'hold' : this._pending !== 0 ? 'easing' : this._settled ? 'settled' : 'moving';
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
    }
    this._swActive = false;
    this._upPend = false;
    this._cutRaise = false;
    this._cand = false;
    this._vf = 0;
    this._raisedSince = NaN;
    this._raiseArmed = false;
    this._settleAcc = 0;
    this._stillAcc = 0;
    this._settled = false;
    this._lookSettled = false;
    this._excSwing = true; // where the phone went during the gap is unknown: wait like after a swing
    this._gapAnchor = true;
    this._fRef[0] = this._fRef[1] = this._fRef[2] = 0;
    this._wp0 = this._wp1 = this._wp2 = 0;
    this._psiOk = false;
    if (!this._hold) {
      this._pending = 0;
      this._O = this._hYaw - this._L;
    }
    this._excPeak = 0;
    this._anchorLook();
  }

  /** Forgets the look history: nothing before now can be rolled back. */
  _anchorLook() {
    this._ringN = 0;
    this._rolled = 0;
    this._rollCand = 0;
    this._rollPend = false;
    this._excStruck = false;
    this._holdN = 0;
    this._holdLive = false;
  }

  /**
   * A strike returns to the guard it left. If the phone came back close to a heading it was held
   * at before the strike (well away from where the excursion began), the body turned first and
   * the strike came before the turn settled: a turn, not an imprecise return. (A cock-back's hold
   * is a candidate too, but the strike returns to the start, away from it.)
   */
  _turnedFirst(delta) {
    if (!this._excStruck) return false;
    const e = this._hYaw - delta; // where the excursion began, in heading units
    for (let i = 0; i < this._holdN; i++) {
      const off = Math.abs(this._holdY[i] - e);
      if (off >= HOLD_SEP && Math.abs(this._hYaw - this._holdY[i]) <= HOLD_NEAR * off) return true;
    }
    return false;
  }

  /**
   * The first swing of an excursion has just started. A look change released in the last
   * prepWindow (an excursion's release, or a slow turn followed 1:1) may have been its cock-back:
   * note it (≤ prepMax) and let _rollDecide judge once the swing's direction is known.
   */
  _rollBack(t) {
    const n = this._ringN;
    this._ringN = 0; // once per excursion: later swings in it see no history
    if (n === 0) return;
    const T = this._ringT, C = this._ringC;
    const tw = t - this.p.prepWindow;
    let i = this._ringHead;
    let k = 1;
    while (k < n && T[i] > tw) {
      i = (i + RING - 1) % RING;
      k++;
    }
    const roll = this._L + this._pending - C[i];
    if (roll !== 0 && Math.abs(roll) <= this.p.prepMax) {
      this._rollCand = roll;
      this._rollPend = true;
    }
  }

  /**
   * A cock-back turns the heading against the strike's sideways direction (positive look = left,
   * dx > 0 = blade moving right). If so, ease the change back now and remember it in _rolled: the
   * settle after the strike decides from where the phone came back whether it was a turn after all.
   */
  _rollDecide() {
    this._rollPend = false;
    const roll = this._rollCand, dx = this._dx;
    if (this._hold || this._sweepNow < 0.2 || roll * dx <= 0 || Math.abs(dx) < 0.3 * this._sweepNow) return;
    this._pending -= roll;
    this._rolled += roll;
    this._ringN = 0;
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
    this._swBeta0 = beta;
    this._swLow = -1;
    this._swRev = 0;
    this._swFired = false;
    this._swRevWin = this._pValid && t - this._pEnd <= p.reverseWindow;
    this._swWindMem = t - this._lastWindup <= p.windupMemory || (this._raiseArmed && beta >= p.windupBeta);
    this._vf = 0;
    this._cand = false;
    this._swStarted = true;
  }

  /** May the current swing produce a slash now (thresholds + the recoil rule)? */
  _slashOk() {
    const dir = this._dir, sweep = this._sweepNow, peak = this._swPeak;
    if (sweep < this._sweep || peak < this._peak) return false;
    if (this._swRevWin && within(dir, this._pDir + Math.PI, this.p.reverseSector)) {
      return peak >= Math.max(this._peak, this.p.reverseRatio * this._pPeak) && sweep >= this.p.reverseSweep;
    }
    return true;
  }

  _fireSlash() {
    const t = this._t, dir = this._dir, sweep = this._sweepNow;
    const peak = this._swPeak;
    const power = clamp((peak - this._peak) / this._slashSpan, 0, 1);
    this._swMode = DONE;
    this._swFired = true;
    this._pValid = true;
    this._pDir = dir;
    this._pPeak = peak;
    this._pEnd = Infinity;
    if (within(dir, HALF_PI, UP_SECTOR)) this._cutRaise = true; // a raise is a cut or a wind-up, never both
    this._emit({ type: 'slash', dir, power, peak, sweep, t });
  }

  _resolveThrow() {
    const t = this._t;
    const peak = this._swPeak;
    // A remembered wind-up plus a downward swing is a throw: below throwPeak it is a weak one
    // (power 0), never a chop.
    if (peak >= Math.min(this._peak, this._throwPeak)) {
      this._swMode = DONE;
      const power = clamp((peak - this._throwPeak) / Math.max(1e-6, this._throwFull - this._throwPeak), 0, 1);
      this._lockUntil = t + this.p.postThrowLock;
      this._lastWindup = -Infinity;
      this._cand = false;
      this._vf = 0;
      this._pValid = false;
      if (!this._hold) {
        this._hold = true;
        this._holdAuto = true;
        this._holdSince = t;
      }
      this._emit({ type: 'throw', power, peak, t });
    } else if (this._slashOk()) {
      this._fireSlash();
    } else {
      this._swMode = DONE;
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
    this._lastWindup = this._t;
    if (!this._raisedEmitted) this._emitWindup();
  }

  /** The pending overhead swing was the uppercut: emit it now (it ended at _upT0). */
  _fireUp() {
    this._upPend = false;
    this._cutRaise = true; // a raise is a cut or a wind-up, never both
    const t = this._t;
    const peak = this._upPeak;
    const power = clamp((peak - this._peak) / this._slashSpan, 0, 1);
    this._pValid = true;
    this._pDir = this._upDir;
    this._pPeak = peak;
    this._pEnd = t;
    this._emit({ type: 'slash', dir: this._upDir, power, peak, sweep: this._upSweep, t });
  }

  _swingStep() {
    const p = this.p;
    const t = this._t, m = this._m, beta = this._beta, vx = this._vx, vy = this._vy;
    const dx = this._dx, dy = this._dy;
    const sweep = Math.sqrt(dx * dx + dy * dy);
    const dir = Math.atan2(dy, dx);
    this._sweepNow = sweep;
    this._dir = dir;

    // end conditions: (a) slow for endHold, (b) reversal, (c) maxSwing
    let end = false;
    if (m < p.swingEnd) {
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
    if (t - this._swT0 >= p.maxSwing - 1e-9) end = true;

    const mode = this._swMode;
    if (mode === NORMAL) {
      const upHeld = this._swBeta0 >= p.upHoldStartBeta && within(dir, HALF_PI, UP_SECTOR);
      const cut = sweep >= this._sweep && this._swPeak >= this._peak;
      if (upHeld && sweep >= 0.15 && beta >= p.windupBeta && this._raiseArmed && !cut) {
        // phone raised beside or behind the head, too gently for a cut: a wind-up, never a slash
        if (this._upPend) this._fireUp();
        this._swMode = DONE;
        if (!this._raisedEmitted) this._emitWindup();
        else this._lastWindup = t;
      } else if (
        this.weapon === 'spear' && this._swWindMem && sweep >= this._sweep &&
        this._swPeak >= Math.min(this._peak, this._throwPeak) && within(dir, -HALF_PI, p.throwSector)
      ) {
        // a throw right after an overhead uppercut (spear): that swing was its wind-up
        if (this._upPend) this._upWindup();
        this._swMode = THROW;
      } else if (cut) {
        if (this._upPend) this._fireUp(); // otherwise it was the uppercut, which comes first
        if (upHeld) {
          this._swMode = UP;
        } else if (this._slashOk()) {
          this._fireSlash();
        }
      }
    }
    if (this._swMode === UP && beta < p.windupBeta - 0.3 && sweep >= 2 * this._sweep && m <= 0.85 * this._swPeak) {
      // Braking: a min-jerk stroke has about ½(m/peak)²·sweep still to go. Well short of
      // windupBeta it is an uppercut, not a raise: fire now rather than at the swing's end.
      const r = m / this._swPeak;
      if (beta + 0.5 * r * r * sweep < p.windupBeta - 0.3 && this._slashOk()) this._fireSlash();
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
      this._swActive = false;
      this._vf = 0;
    }
  }
}
