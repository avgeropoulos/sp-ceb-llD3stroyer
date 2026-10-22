/**
 * PURE rotational camera shake (DESIGN.md §11 gfx/fx.js): smoothed value noise on pitch, yaw
 * and roll, in radians, plus short directional kicks. Advances with real dt.
 */
import { createRng } from '../../core/math.js';

const SLOTS = 8;
const TABLE = 256;
const MAX_AMP = 0.08;
const FREQ = 17; // noise cycles per second
const ATTACK = 0.012;

/** Sums up to 8 decaying shakes (energy-wise) plus one kick into pitch/yaw/roll offsets. */
export class Shake {
  constructor(seed = 7) {
    const rng = createRng(seed);
    this.table = new Float32Array(TABLE);
    for (let i = 0; i < TABLE; i++) this.table[i] = rng.next() * 2 - 1;
    this.amp = new Float32Array(SLOTS);
    this.dur = new Float32Array(SLOTS);
    this.t = new Float32Array(SLOTS);
    this.kick = new Float32Array(3);
    this.kickT = 0;
    this.kickTau = 0.06;
    this.phase = 0;
    /** Current offsets in radians: pitch (x), yaw (y), roll (z). */
    this.x = 0;
    this.y = 0;
    this.z = 0;
  }

  reset() {
    this.amp.fill(0);
    this.t.fill(0);
    this.kick.fill(0);
    this.kickT = 0;
    this.x = this.y = this.z = 0;
  }

  /** Adds a shake of `amp` radians decaying over `dur` seconds. */
  add(amp, dur) {
    if (!(amp > 0) || !(dur > 0)) return;
    // Use a free slot, else replace the one with the least energy left.
    let best = 0;
    let bestE = Infinity;
    for (let i = 0; i < SLOTS; i++) {
      const e = this.amp[i] > 0 ? this.amp[i] * this._env(i) : 0;
      if (e < bestE) {
        bestE = e;
        best = i;
      }
    }
    this.amp[best] = amp;
    this.dur[best] = dur;
    this.t[best] = 0;
  }

  /** A directional jolt (radians per axis) that peaks after `tau` and dies out. */
  addKick(pitch, yaw, roll, tau = 0.06) {
    // Keep whatever is left of the previous kick if it is still stronger.
    const left = this._kickEnv();
    const k = this.kick;
    if (Math.abs(pitch) + Math.abs(yaw) + Math.abs(roll) < (Math.abs(k[0]) + Math.abs(k[1]) + Math.abs(k[2])) * left) return;
    k[0] = pitch;
    k[1] = yaw;
    k[2] = roll;
    this.kickT = 0;
    this.kickTau = tau;
  }

  get active() {
    for (let i = 0; i < SLOTS; i++) if (this.amp[i] > 0) return true;
    return this.kickT < this.kickTau * 8 && (this.kick[0] !== 0 || this.kick[1] !== 0 || this.kick[2] !== 0);
  }

  _env(i) {
    const u = this.t[i] / this.dur[i];
    if (u >= 1) return 0;
    const d = 1 - u;
    return d * d * Math.min(1, this.t[i] / ATTACK + 0.35);
  }

  _kickEnv() {
    const s = this.kickT / this.kickTau;
    return s >= 8 ? 0 : s * Math.exp(1 - s);
  }

  _noise(p) {
    const i = Math.floor(p);
    const f = p - i;
    const a = this.table[i & (TABLE - 1)];
    const b = this.table[(i + 1) & (TABLE - 1)];
    const s = f * f * f * (f * (f * 6 - 15) + 10);
    return a + (b - a) * s;
  }

  /** Advances by real `dt` (0 freezes) and refreshes x, y, z. */
  update(dt) {
    if (dt > 0) {
      for (let i = 0; i < SLOTS; i++) {
        if (this.amp[i] <= 0) continue;
        this.t[i] += dt;
        if (this.t[i] >= this.dur[i]) this.amp[i] = 0;
      }
      this.kickT += dt;
      this.phase += dt * FREQ;
      if (this.phase > 12800) this.phase -= 12800; // a multiple of TABLE / 0.8 keeps every channel continuous
    }
    let e2 = 0;
    for (let i = 0; i < SLOTS; i++) {
      if (this.amp[i] <= 0) continue;
      const a = this.amp[i] * this._env(i);
      e2 += a * a;
    }
    const a = Math.min(MAX_AMP, Math.sqrt(e2));
    const k = this._kickEnv();
    const p = this.phase;
    this.x = a * this._noise(p) + this.kick[0] * k;
    this.y = a * 0.85 * this._noise(p + 85.3) + this.kick[1] * k;
    this.z = a * 0.6 * this._noise(p * 0.8 + 170.7) + this.kick[2] * k;
  }
}
