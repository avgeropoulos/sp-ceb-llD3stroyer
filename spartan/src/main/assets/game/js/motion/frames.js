/**
 * Device → screen frame remap and gravity/up estimation (DESIGN.md §5.2). PURE.
 *
 * Screen frame (§3.3): +x the right edge as the user sees it, +y the top edge, +z out of the
 * screen toward the user. On the web it also self-calibrates the browser quirks: the sign of
 * the accelerometer (gravitySign), a screen angle reported 180° off (rotFix), and the
 * rotationRate axis order (axisMap). process() allocates nothing.
 *
 * The grip (docs-hilt-grip.md B.2) changes only those web heuristics: the hilt grip latches the sign
 * on its own swing direction, tests its own guard for the provisional sign, and never fixes the
 * screen angle (a calm blade-down pose is a rest, and portrait is reported right).
 */

const G = 9.81;
const DIAG = Math.SQRT1_2;
const AXIS_WIN = 4; // the axis check's verdict: the winner leaves ≤ 1/4 of the loser's residual

/**
 * Per-grip web heuristics. latch: the direction the centripetal acceleration of a fast swing points
 * in (toward the user, or in the fist toward the elbow and the body); restUp: up at the grip's
 * guard, for the provisional sign; rotFix: may detect and apply a screen angle reported 180° off;
 * latchRuns: the latch decides only at the end of a fast run. In the fist a chop or a cock-back
 * turns about x, so its tangential acceleration lies along the latch too: over a whole run (speeding
 * up, then slowing down) it cancels, while over its first 12 samples (a 60 ms part at 200 Hz) it can
 * outweigh the centripetal part. (A window swing's tangential acceleration is across +z.)
 */
export const FRAME_GRIPS = Object.freeze({
  window: Object.freeze({ latch: Object.freeze([0, 0, 1]), restUp: Object.freeze([0, DIAG, DIAG]), rotFix: true, latchRuns: false }),
  hilt: Object.freeze({ latch: Object.freeze([0, -0.6, 0.8]), restUp: Object.freeze([0, 0.89, 0.45]), rotFix: false, latchRuns: true }),
});

/**
 * Remaps a device-frame vector into the screen frame of display rotation `rotDeg`
 * (0|90|180|270): sx = cos r·dx − sin r·dy, sy = sin r·dx + cos r·dy, sz = dz.
 * @param {number[]} v device vector [x, y, z]
 * @param {number} rotDeg display rotation in degrees
 * @param {number[]} [out] optional output array (may be `v`)
 * @returns {number[]} [x, y, z] in the screen frame
 */
export function remapToScreen(v, rotDeg, out = [0, 0, 0]) {
  const r = ((rotDeg % 360) + 360) % 360;
  let c, s;
  if (r === 0) (c = 1), (s = 0);
  else if (r === 90) (c = 0), (s = 1);
  else if (r === 180) (c = -1), (s = 0);
  else if (r === 270) (c = 0), (s = -1);
  else (c = Math.cos((r * Math.PI) / 180)), (s = Math.sin((r * Math.PI) / 180));
  const x = v[0], y = v[1];
  out[0] = c * x - s * y;
  out[1] = s * x + c * y;
  out[2] = v[2];
  return out;
}

function normalizeInto(v) {
  const l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
  if (l > 1e-9) {
    v[0] /= l;
    v[1] /= l;
    v[2] /= l;
  } else {
    v[0] = 0;
    v[1] = 1;
    v[2] = 0;
  }
  return v;
}

/** Turns RawSamples (§5.1) into screen-frame Samples with a gravity-referenced `up`. */
export class MotionFrames {
  /**
   * @param {{tiltTau?: number, platformKind?: 'web'|'android', grip?: 'window'|'hilt'}} [opts]
   */
  constructor({ tiltTau = 0.5, platformKind = 'web', grip = 'window' } = {}) {
    this.tiltTau = tiltTau;
    this.web = platformKind !== 'android';
    this._grip = grip === 'hilt' ? 'hilt' : 'window';
    this._fg = FRAME_GRIPS[this._grip];
    /** The reused output Sample: { t, dt, w, a, up, calm, gap }. */
    this.sample = { t: 0, dt: 0, w: [0, 0, 0], a: [0, 0, 0], up: [0, 1, 0], calm: false, gap: false };
    this._dev = [0, 0, 0];
    this._acc = [0, 0, 0];
    this._accG = [0, 0, 0];
    this._grav = [0, 0, 0];
    // Device calibration (web): survives reset(), since it describes the browser, not the moment.
    this._sign = 1;
    this._signLatched = false;
    this._contra = 0;
    this._rotFix = 0;
    this._perm = false;
    this._axisOk = false; // the axis check has confirmed the current mapping this session
    this._g0 = [0, 0, 0];
    this._gPrev = [0, 0, 0];
    this._wPrev = [0, 0, 0];
    this.reset();
  }

  /** Clears the filter state (timing, up estimate, timers). Calibration is kept. */
  reset() {
    this._tPrev = NaN;
    this._rotPrev = -1;
    this._upOk = false;
    this._calmT = 0;
    this._wPrev[0] = this._wPrev[1] = this._wPrev[2] = 0;
    this._latchSum = 0;
    this._latchN = 0;
    this._latchFast = false;
    this._resetAxisCheck();
    const s = this.sample;
    s.t = 0;
    s.dt = 0;
    s.w[0] = s.w[1] = s.w[2] = 0;
    s.a[0] = s.a[1] = s.a[2] = 0;
    s.up[0] = 0;
    s.up[1] = 1;
    s.up[2] = 0;
    s.calm = false;
    s.gap = false;
  }

  /**
   * 'window' | 'hilt' (anything else is 'window'). A change resets the filters; the device
   * calibration (axisMap, gravity sign, the window grip's rotFix) is kept. No-op if unchanged.
   */
  setGrip(grip) {
    const g = grip === 'hilt' ? 'hilt' : 'window';
    if (g === this._grip) return;
    this._grip = g;
    this._fg = FRAME_GRIPS[g];
    this.reset();
  }

  get grip() {
    return this._grip;
  }

  /** +1 or −1: the sign applied to acc, accG and grav (always +1 on Android). */
  get gravitySign() {
    return this._sign;
  }

  /** True once the web gravity sign has been confirmed by fast swings. */
  get gravityLatched() {
    return !this.web || this._signLatched;
  }

  /** Degrees added to the reported screen angle (0 or 180), in the window grip (stored, never applied, in the hilt grip). */
  get rotFix() {
    return this._rotFix;
  }

  /** Where rotationRate alpha, beta, gamma go: 'xyz' (default) or 'zxy' (W3C order). */
  get axisMap() {
    return this._perm ? 'zxy' : 'xyz';
  }

  /**
   * The web calibration as a plain object, for storage: { axisMap, gravitySign, rotFix, confirmed }.
   * `confirmed` is true once this session's own data has settled both the gravity sign (latched
   * by fast swings) and the axis order (the self-check decided for the current mapping); only
   * then is it worth persisting. Always unconfirmed on Android, which has nothing to calibrate.
   */
  exportCalibration() {
    return {
      axisMap: this.axisMap,
      gravitySign: this._sign,
      rotFix: this._rotFix,
      confirmed: this.web && this._signLatched && this._axisOk,
    };
  }

  /**
   * Seeds the web calibration from a stored exportCalibration() object, so the first strikes of a
   * session already use the right axis order. Invalid fields are ignored; Android ignores it all.
   * Nothing is taken as confirmed: the self-checks keep running, so a changed browser or device
   * overrides it as it would from scratch (the first fast-swing verdict sets the sign, the axis
   * check switches the order, and a latched sign re-checks the screen angle).
   * @param {{axisMap?: string, gravitySign?: number, rotFix?: number}|null} c
   * @returns {boolean} true if anything was applied
   */
  importCalibration(c) {
    if (!this.web || !c || typeof c !== 'object') return false;
    let applied = false;
    if (c.axisMap === 'xyz' || c.axisMap === 'zxy') {
      this._perm = c.axisMap === 'zxy';
      applied = true;
    }
    if (c.gravitySign === 1 || c.gravitySign === -1) {
      if (c.gravitySign !== this._sign) this._flipSign();
      applied = true;
    }
    if (c.rotFix === 0 || c.rotFix === 180) {
      this._rotFix = c.rotFix;
      applied = true;
    }
    if (applied) {
      // a seed, not a verdict: the next evidence decides
      this._signLatched = false;
      this._contra = 0;
      this._axisOk = false;
      this._upOk = false;
      this._latchSum = this._latchN = 0;
      this._latchFast = false;
      this._resetAxisCheck();
    }
    return applied;
  }

  /**
   * Processes one RawSample. Returns the reused Sample object.
   * @param {{t:number, gyro:number[], acc:number[]|null, accG:number[]|null, grav:number[]|null, rot:number}} raw
   */
  process(raw) {
    const o = this.sample;
    const t = raw.t;
    const dtRaw = this._tPrev === this._tPrev ? t - this._tPrev : 0;
    this._tPrev = t;
    o.t = t;
    o.dt = dtRaw < 0 ? 0 : dtRaw > 0.05 ? 0.05 : dtRaw;
    o.gap = dtRaw > 0.1 || dtRaw < -0.1; // a jump back in time (another clock) is a gap too
    const dt = o.dt;

    let rot = raw.rot | 0;
    rot = ((rot % 360) + 360) % 360;
    let reinit = o.gap;
    if (rot !== this._rotPrev) {
      if (this._rotPrev >= 0) {
        // The frame mapping changed discontinuously: treat it like a gap downstream too.
        o.gap = true;
        reinit = true;
      }
      this._rotPrev = rot;
    }
    const fg = this._fg;
    const rotEff = fg.rotFix ? rot + this._rotFix : rot;

    // 1. gyro (axis order self-check on the web), remapped; never sign-flipped
    const g = raw.gyro;
    const dev = this._dev;
    if (this._perm) {
      dev[0] = g[1];
      dev[1] = g[2];
      dev[2] = g[0];
    } else {
      dev[0] = g[0];
      dev[1] = g[1];
      dev[2] = g[2];
    }
    const w = remapToScreen(dev, rotEff, o.w);
    const wMag = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);

    const acc = raw.acc ? remapToScreen(raw.acc, rotEff, this._acc) : null;
    const accG = raw.accG ? remapToScreen(raw.accG, rotEff, this._accG) : null;
    const grav = raw.grav ? remapToScreen(raw.grav, rotEff, this._grav) : null;

    // 2. web (a): latch the gravity sign from the centripetal acceleration of fast swings, which
    // points along the grip's latch direction whatever the rotation (window: toward the user, +z).
    if (this.web && wMag > 7 && (acc || accG)) {
      const v = acc || accG, L = fg.latch;
      this._latchSum += L[0] * v[0] + L[1] * v[1] + L[2] * v[2];
      if (++this._latchN >= 12 && !fg.latchRuns) this._latchStep();
      this._latchFast = fg.latchRuns;
    } else if (this._latchFast) {
      // latchRuns: a fast run just ended (an unfinished one keeps adding to the next)
      this._latchFast = false;
      if (this._latchN >= 12) this._latchStep();
    }

    // 3. sign (acc, accG and grav only)
    const sg = this._sign;
    if (sg < 0) {
      if (acc) (acc[0] = -acc[0]), (acc[1] = -acc[1]), (acc[2] = -acc[2]);
      if (accG) (accG[0] = -accG[0]), (accG[1] = -accG[1]), (accG[2] = -accG[2]);
      if (grav) (grav[0] = -grav[0]), (grav[1] = -grav[1]), (grav[2] = -grav[2]);
    }

    // 4. up
    const up = o.up;
    if (grav) {
      up[0] = grav[0];
      up[1] = grav[1];
      up[2] = grav[2];
      normalizeInto(up);
      this._upOk = true;
    } else if (!this._upOk || reinit) {
      if (accG) {
        up[0] = accG[0];
        up[1] = accG[1];
        up[2] = accG[2];
        normalizeInto(up);
        this._upOk = true;
      }
    } else {
      // Propagate with the gyro: d(up)/dt = up × w. Integrated exactly as a rotation by the
      // interval's mean rate (Rodrigues), which stays accurate at 50 Hz during 15 rad/s throws.
      const mx = 0.5 * (w[0] + this._wPrev[0]), my = 0.5 * (w[1] + this._wPrev[1]), mz = 0.5 * (w[2] + this._wPrev[2]);
      const ml = Math.sqrt(mx * mx + my * my + mz * mz);
      const th = ml * dt;
      if (th > 1e-12) {
        const kx = mx / ml, ky = my / ml, kz = mz / ml;
        const c = Math.cos(th), sn = -Math.sin(th); // rotate by −θ about k
        const ux = up[0], uy = up[1], uz = up[2];
        const kd = (kx * ux + ky * uy + kz * uz) * (1 - c);
        up[0] = ux * c + (ky * uz - kz * uy) * sn + kx * kd;
        up[1] = uy * c + (kz * ux - kx * uz) * sn + ky * kd;
        up[2] = uz * c + (kx * uy - ky * ux) * sn + kz * kd;
      }
      normalizeInto(up);
      if (accG && wMag < 2) {
        // Correct slowly toward the accelerometer's gravity: accG − acc when the linear part is
        // reported, else accG. Beyond §5.2's magnitude weight, while moving also distrust a
        // reading that disagrees with up (linear acceleration across gravity barely changes |accG|).
        const gx = acc ? accG[0] - acc[0] : accG[0];
        const gy = acc ? accG[1] - acc[1] : accG[1];
        const gz = acc ? accG[2] - acc[2] : accG[2];
        const gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
        let wgt = 1 - Math.abs(gl - G) / 2.5;
        if (wgt < 0) wgt = 0;
        if (!(wMag < 0.5 && Math.abs(gl - G) < 0.3)) {
          const rx = gx - G * up[0], ry = gy - G * up[1], rz = gz - G * up[2];
          const rw = 1 - Math.sqrt(rx * rx + ry * ry + rz * rz) / 4;
          wgt *= rw > 0 ? rw : 0;
        }
        if (wgt > 0 && gl > 1e-6) {
          const k = (dt / this.tiltTau) * wgt;
          up[0] += k * (gx / gl - up[0]);
          up[1] += k * (gy / gl - up[1]);
          up[2] += k * (gz / gl - up[2]);
          normalizeInto(up);
        }
      }
    }

    // 5. linear acceleration
    const a = o.a;
    if (acc) {
      a[0] = acc[0];
      a[1] = acc[1];
      a[2] = acc[2];
    } else if (accG) {
      a[0] = accG[0] - G * up[0];
      a[1] = accG[1] - G * up[1];
      a[2] = accG[2] - G * up[2];
    } else {
      a[0] = a[1] = a[2] = 0;
    }

    // 6. calm
    o.calm = wMag < 0.6 && a[0] * a[0] + a[1] * a[1] + a[2] * a[2] < 2.25;
    this._calmT = o.calm ? this._calmT + dt : 0;

    if (this.web) {
      const ru = fg.restUp;
      if (!this._signLatched) {
        // (b) provisional: calm for 1 s and up seems opposite to the grip's guard (window: the
        // screen seems to face down and away)
        if (this._calmT >= 1 && ru[0] * up[0] + ru[1] * up[1] + ru[2] * up[2] < -0.5) {
          this._flipSign();
          a[0] = -a[0];
          a[1] = -a[1];
          a[2] = -a[2];
          this._calmT = 0;
        }
      } else if (fg.rotFix && this._calmT >= 1.5 && up[1] < -0.6) {
        // (c) the sign is known, yet the phone looks upside down: the screen angle is off by 180°
        // (not in the hilt grip, where a calm blade-down pose is a rest)
        this._rotFix = (this._rotFix + 180) % 360;
        up[0] = -up[0];
        up[1] = -up[1];
        w[0] = -w[0];
        w[1] = -w[1];
        a[0] = -a[0];
        a[1] = -a[1];
        this._calmT = 0;
      }
      if (raw.accG) this._axisStep(raw);
    }
    this._wPrev[0] = w[0];
    this._wPrev[1] = w[1];
    this._wPrev[2] = w[2];
    return o;
  }

  _flipSign() {
    this._sign = -this._sign;
    const up = this.sample.up;
    up[0] = -up[0];
    up[1] = -up[1];
    up[2] = -up[2];
  }

  /** Called once ≥ 12 fast samples are summed: latch the sign on a clear verdict. */
  _latchStep() {
    const mean = this._latchSum / this._latchN;
    const decision = mean < -4 ? -1 : mean > 4 ? 1 : 0;
    if (decision === 0) {
      if (this._latchN >= 48) this._latchSum = this._latchN = 0;
      return;
    }
    this._latchSum = this._latchN = 0;
    if (!this._signLatched) {
      this._signLatched = true;
      this._contra = 0;
      if (decision !== this._sign) this._flipSign();
    } else if (decision !== this._sign) {
      // a latched sign only changes on two consecutive contrary verdicts
      if (++this._contra >= 2) {
        this._contra = 0;
        this._flipSign();
      }
    } else {
      this._contra = 0;
    }
  }

  _resetAxisCheck() {
    this._axT = 0; // seconds of qualifying data
    this._axN = 0; // chunks
    this._axEc = this._axEa = 0; // residuals of the current and the alternative mapping
    this._chT = -1; // current chunk duration (−1 = none)
    this._chPc0 = this._chPc1 = this._chPc2 = 0;
    this._chPa0 = this._chPa1 = this._chPa2 = 0;
  }

  /**
   * axisMap self-check (web): in the device frame a world-fixed gravity direction ĝ obeys
   * dĝ/dt = ĝ × w. Over short chunks of slow rotation, compare the measured change of ĝ with the
   * change each gyro mapping predicts (the current one and the W3C order alpha→z, beta→x,
   * gamma→y). The mapping whose prediction leaves a quarter of the residual or less over ≥ 1 s
   * wins (§5.2 says half: without a linear-acceleration sensor, the swing's own acceleration in
   * accG let the wrong order win by 2.5× during slow returns; a true verdict wins by 5–18×).
   * Residuals (not correlations) keep noise-only data, e.g. turns about gravity, inconclusive.
   */
  _axisStep(raw) {
    const dt = this.sample.dt;
    const aG = raw.accG, aL = raw.acc, gyro = raw.gyro;
    // gravity as the browser sees it: accG − acc if the linear part is reported (sign-invariant)
    const gx0 = aL ? aG[0] - aL[0] : aG[0];
    const gy0 = aL ? aG[1] - aL[1] : aG[1];
    const gz0 = aL ? aG[2] - aL[2] : aG[2];
    const gl = Math.sqrt(gx0 * gx0 + gy0 * gy0 + gz0 * gz0);
    const wl = Math.sqrt(gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]);
    if (!(dt > 0) || !(gl > 1e-6) || wl <= 0.5 || wl >= 3 || Math.abs(gl - G) >= 1) {
      this._chT = -1;
      return;
    }
    const gx = gx0 / gl, gy = gy0 / gl, gz = gz0 / gl;
    if (this._chT < 0) {
      this._g0[0] = gx;
      this._g0[1] = gy;
      this._g0[2] = gz;
      this._chT = 0;
      this._chPc0 = this._chPc1 = this._chPc2 = this._chPa0 = this._chPa1 = this._chPa2 = 0;
    } else {
      // integrate the predicted change with the previous ĝ (current map: as given; alt: permuted)
      const px = this._gPrev[0], py = this._gPrev[1], pz = this._gPrev[2];
      let cx, cy, cz, ax, ay, az;
      if (this._perm) {
        (cx = gyro[1]), (cy = gyro[2]), (cz = gyro[0]);
        (ax = gyro[0]), (ay = gyro[1]), (az = gyro[2]);
      } else {
        (cx = gyro[0]), (cy = gyro[1]), (cz = gyro[2]);
        (ax = gyro[1]), (ay = gyro[2]), (az = gyro[0]);
      }
      this._chPc0 += (py * cz - pz * cy) * dt;
      this._chPc1 += (pz * cx - px * cz) * dt;
      this._chPc2 += (px * cy - py * cx) * dt;
      this._chPa0 += (py * az - pz * ay) * dt;
      this._chPa1 += (pz * ax - px * az) * dt;
      this._chPa2 += (px * ay - py * ax) * dt;
      this._chT += dt;
      if (this._chT >= 0.08) {
        const d0 = gx - this._g0[0], d1 = gy - this._g0[1], d2 = gz - this._g0[2];
        this._axEc += (d0 - this._chPc0) ** 2 + (d1 - this._chPc1) ** 2 + (d2 - this._chPc2) ** 2;
        this._axEa += (d0 - this._chPa0) ** 2 + (d1 - this._chPa1) ** 2 + (d2 - this._chPa2) ** 2;
        this._axT += this._chT;
        this._axN++;
        this._chT = -1;
        if (this._axT >= 1 - 1e-6) {
          if (this._axEc > AXIS_WIN * this._axEa && this._axEc - this._axEa > 0.004 * this._axN) {
            this._perm = !this._perm;
            this._axisOk = true;
            this._upOk = false; // the propagated up used the wrong axes
            this._resetAxisCheck();
          } else if (this._axEa > AXIS_WIN * this._axEc && this._axEa - this._axEc > 0.004 * this._axN) {
            this._axisOk = true; // the current mapping wins as clearly: keep checking, but it's confirmed
            this._resetAxisCheck();
          } else if (this._axT >= 3) {
            this._resetAxisCheck();
          }
        }
      }
    }
    this._gPrev[0] = gx;
    this._gPrev[1] = gy;
    this._gPrev[2] = gz;
  }
}
