/**
 * PURE time control for fx (DESIGN.md §3.2, §11 gfx/fx.js): hit-stop, slow motion and the
 * spear-cam time schedule. It is the only writer of `time.scale`, `time.hitStop` and
 * `time.playerRealtime`. main.js decrements `time.hitStop` by the real dt.
 *
 * All durations here are REAL seconds and advance only through `update(dt)`, so a paused game
 * (dt = 0) freezes them.
 */
import { clamp, lerp, smoothstep } from '../../core/math.js';

/** A new hit-stop can't start within this many seconds of the last one ending. */
export const HITSTOP_REARM = 0.08;

/** Spear-cam timing (real seconds unless noted). */
export const SPEAR_CAM = Object.freeze({
  holdScale: 0.1, // motion throws trail at this scale until the phone settles
  settleNeed: 0.1, // phoneSettled must hold this long to end the hold
  holdMax: 1.4, // the hold never lasts longer than this
  ramp: 0.15, // ramp from holdScale to the flight scale
  motionFlight: 1.6, // after the hold, the remaining flight takes about this long
  motionMin: 0.3,
  touchFlight: 1.2, // touch/key throws: the whole flight takes about this long
  touchMin: 0.15,
  blendIn: 0.2, // camera: eye → trail blend
  freeze: 0.12, // impact freeze (a hit-stop)
  orbit: 0.7, // orbit around the impact after the freeze
  orbitScale: 0.2,
  ease: 0.35, // back to the eyes
  maxAge: 3, // projectile game age that ends the trail
  minY: -2.5, // projectile height that ends the trail (sunk)
  maxReal: 10, // safety net
});

/** Slow-mo eases back to scale 1 over the last part of its duration. */
const SLOW_EASE = 0.12;
/** Tolerance for timers summed from frame deltas (6 × 1/60 is just under 0.1). */
const EPS = 1e-6;

/**
 * Owns the time-scale state machine.
 * Spear-cam phases: 'off' | 'trail' | 'impact' (freeze + orbit) | 'ease'.
 */
export class TimeControl {
  /** @param {{now:number, scale:number, hitStop:number, playerRealtime:boolean}} time ctx.time */
  constructor(time) {
    this.time = time;
    this.hsEndAt = -Infinity;
    this.slowLeft = 0;
    this.slowTotal = 0;
    this.slowScale = 1;
    this.slowRealtime = false;
    this.camPhase = 'off';
    this.camT = 0;
    this.phaseT = 0;
    this.camMotion = false;
    this.holdDone = true;
    this.settledT = 0;
    this.rampT = 0;
    this.rampFrom = 1;
    this.rampTo = 1;
    this.easeFrom = 1;
    this.remaining = null;
    this.reset();
  }

  /** Restores scale 1, no hit-stop, no player realtime, and stops slow-mo and the spear cam. */
  reset() {
    const t = this.time;
    t.scale = 1;
    t.hitStop = 0;
    t.playerRealtime = false;
    this.hsEndAt = -Infinity;
    this.slowLeft = 0;
    this.slowTotal = 0;
    this.slowScale = 1;
    this.slowRealtime = false;
    this.camPhase = 'off';
    this.camT = 0;
    this.phaseT = 0;
    this.camMotion = false;
    this.holdDone = true;
    this.settledT = 0;
    this.rampT = 0;
    this.remaining = null;
  }

  get camActive() {
    return this.camPhase !== 'off';
  }

  get slowActive() {
    return this.slowLeft > 0;
  }

  /**
   * Max-merges a hit-stop. While one runs, remaining = max(remaining, s). Otherwise a new one
   * starts unless the last one ended less than HITSTOP_REARM ago. Returns true if applied.
   */
  hitStop(s) {
    if (!(s > 0)) return false;
    const t = this.time;
    if (t.hitStop > 0) {
      if (s > t.hitStop) t.hitStop = s;
      this.hsEndAt = t.now + t.hitStop;
      return true;
    }
    if (t.now - this.hsEndAt < HITSTOP_REARM) return false;
    t.hitStop = s;
    this.hsEndAt = t.now + s;
    return true;
  }

  /**
   * Ends a running hit-stop now. Used when a motion swing starts: the player has moved on, and the
   * new attack (and its viewmodel animation, which runs on pdt) must not wait out the old freeze.
   * The freeze was cut short, so the re-arm rule does not apply to the next one: the new blow
   * strikes ~50 ms later and gets its own hit-stop. Never ends the spear cam's impact freeze.
   * Returns true if a hit-stop was running.
   */
  endHitStop() {
    const t = this.time;
    if (!(t.hitStop > 0) || this.camPhase !== 'off') return false;
    t.hitStop = 0;
    this.hsEndAt = -Infinity;
    return true;
  }

  /** Forces a freeze of at least `s` (spear-cam impact). Ignores the re-arm rule. */
  freeze(s) {
    const t = this.time;
    if (s > t.hitStop) t.hitStop = s;
    this.hsEndAt = t.now + t.hitStop;
  }

  /**
   * Slows game time to `scale` for `seconds` of real time, easing back over the last 0.12 s.
   * Overlapping calls merge: the deeper scale and the longer remainder win, and playerRealtime
   * stays on if either asked for it. Ignored while the spear cam runs. Returns true if applied.
   */
  slowMo(scale, seconds, playerRealtime = false) {
    if (this.camPhase !== 'off') return false;
    if (!(seconds > 0) || !(scale > 0)) return false;
    scale = clamp(scale, 0.02, 1);
    if (this.slowLeft > 0) {
      this.slowScale = Math.min(this.slowScale, scale);
      if (seconds > this.slowLeft) {
        this.slowLeft = seconds;
        this.slowTotal = seconds;
      }
      this.slowRealtime = this.slowRealtime || !!playerRealtime;
    } else {
      this.slowScale = scale;
      this.slowLeft = seconds;
      this.slowTotal = seconds;
      this.slowRealtime = !!playerRealtime;
    }
    this._applySlow();
    return true;
  }

  _applySlow() {
    const t = this.time;
    if (this.slowLeft <= 0) {
      t.scale = 1;
      t.playerRealtime = false;
      return;
    }
    const ease = Math.min(SLOW_EASE, this.slowTotal * 0.3);
    t.scale = this.slowLeft >= ease ? this.slowScale : lerp(1, this.slowScale, smoothstep(0, ease, this.slowLeft));
    t.playerRealtime = this.slowRealtime;
  }

  /**
   * Starts the spear cam. `flightTime` is the predicted total game time of flight (used for
   * touch/key throws); `remaining()` predicts the remaining game time (used after a motion hold).
   */
  camStart(motion, flightTime, remaining) {
    const t = this.time;
    this.slowLeft = 0;
    this.camPhase = 'trail';
    this.camT = 0;
    this.phaseT = 0;
    this.camMotion = !!motion;
    this.holdDone = !motion;
    this.settledT = 0;
    this.rampT = SPEAR_CAM.ramp;
    this.remaining = remaining || null;
    t.playerRealtime = false;
    if (motion) t.scale = SPEAR_CAM.holdScale;
    else t.scale = clamp((flightTime > 0 ? flightTime : 0) / SPEAR_CAM.touchFlight, SPEAR_CAM.touchMin, 1);
  }

  /** The spear hit an actor: freeze, then orbit at orbitScale. Returns true if it applied. */
  camImpact() {
    if (this.camPhase !== 'trail') return false;
    this.camPhase = 'impact';
    this.phaseT = 0;
    this.time.scale = SPEAR_CAM.orbitScale;
    this.freeze(SPEAR_CAM.freeze);
    return true;
  }

  /** Eases back to the eyes now (miss, splash, timeout, or the end of the orbit). */
  camEase() {
    if (this.camPhase === 'off' || this.camPhase === 'ease') return false;
    this.camPhase = 'ease';
    this.phaseT = 0;
    this.easeFrom = this.time.scale;
    return true;
  }

  /** Ends the spear cam at once: scale 1, playerRealtime false. */
  camEnd() {
    if (this.camPhase === 'off') return false;
    this.camPhase = 'off';
    this.remaining = null;
    this.time.scale = 1;
    this.time.playerRealtime = false;
    return true;
  }

  /**
   * Advances real-time timers. `settled` is ctx.input.phoneSettled and `gone` is true when the
   * tracked spear stopped flying without an impact (dead, stuck, too old or sunk).
   * Returns 'ease' when the cam started easing back this frame, 'end' when it ended, else ''.
   */
  update(dt, settled = true, gone = false) {
    const t = this.time;
    if (t.hitStop > 0) this.hsEndAt = t.now + t.hitStop;
    if (!(dt > 0)) return '';
    if (this.camPhase !== 'off') return this._camUpdate(dt, settled, gone);
    if (this.slowLeft > 0) {
      this.slowLeft = this.slowLeft - dt > EPS ? this.slowLeft - dt : 0;
      this._applySlow();
    }
    return '';
  }

  _camUpdate(dt, settled, gone) {
    const t = this.time;
    const C = SPEAR_CAM;
    this.camT += dt;
    this.phaseT += dt;
    if (this.camPhase === 'trail') {
      if (gone || this.camT > C.maxReal) {
        this.camEase();
        return 'ease';
      }
      if (!this.holdDone) {
        this.settledT = settled ? this.settledT + dt : 0;
        if (this.settledT >= C.settleNeed - EPS || this.camT >= C.holdMax - EPS) {
          this.holdDone = true;
          this.rampT = 0;
          this.rampFrom = t.scale;
          const left = this.remaining ? this.remaining() : C.motionFlight;
          this.rampTo = clamp(left / C.motionFlight, C.motionMin, 1);
        } else {
          t.scale = C.holdScale;
        }
      } else if (this.rampT < C.ramp) {
        this.rampT = Math.min(C.ramp, this.rampT + dt);
        t.scale = lerp(this.rampFrom, this.rampTo, smoothstep(0, C.ramp, this.rampT));
      }
      return '';
    }
    if (this.camPhase === 'impact') {
      if (this.phaseT >= C.freeze + C.orbit - EPS) {
        this.camEase();
        return 'ease';
      }
      t.scale = C.orbitScale;
      return '';
    }
    // ease
    if (this.phaseT >= C.ease - EPS) {
      this.camEnd();
      return 'end';
    }
    t.scale = lerp(this.easeFrom, 1, smoothstep(0, C.ease, this.phaseT));
    return '';
  }
}
