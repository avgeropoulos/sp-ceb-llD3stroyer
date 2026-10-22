/**
 * Pose channels, key poses per style and keyframed clips.
 *
 * A pose is a Float32Array of N channels. Hands are grip targets in chest space (unit body,
 * metres; the chest bone at the origin, +Y up the spine, +Z forward, +X the actor's left).
 * Feet are ankle targets in mesh space [x, lift above rest ankle, z, yaw, pitch(heel up +)].
 * Rotations are authored in degrees and stored in radians (Euler order YXZ: z roll, x pitch
 * (+ leans forward), y yaw (+ turns left)). Aims are directions in actor space when
 * aimSpace = 1 (weapons stay on target whatever the torso does) and in chest space when 0
 * (they move with the body: deaths, falls).
 */

export const CH = {
  pelP: 0, pelR: 3, spine: 6, chest: 9, neck: 12, head: 15,
  handL: 18, elbL: 21, handR: 24, elbR: 27,
  footL: 30, footR: 35,
  aimR: 40, upR: 43, aimL: 46, upL: 49,
  legFK: 52, thighL: 53, shinL: 56, footFKL: 57, thighR: 58, shinR: 61, footFKR: 62,
  wscale: 63, lscale: 64, cloak: 65, draw: 66, ground: 67, aimSpace: 68,
};
export const N = 69;
const DEG = Math.PI / 180;
const ANG = new Set(['pelR', 'spine', 'chest', 'neck', 'head', 'thighL', 'shinL', 'footFKL', 'thighR', 'shinR', 'footFKR', 'cloak']);

/** Channels driven by an 'upper' layer clip. */
export const UPPER = new Uint8Array(N);
for (let i = CH.spine; i < CH.footL; i++) UPPER[i] = 1;
for (let i = CH.aimR; i < CH.legFK; i++) UPPER[i] = 1;
UPPER[CH.wscale] = 1;
UPPER[CH.lscale] = 1;
UPPER[CH.draw] = 1;
UPPER[CH.aimSpace] = 1;

/** Channels holding angles (wrapped when blending is not needed; listed for tooling). */
export const ANGLE_CH = new Uint8Array(N);

function restPoseArray() {
  const p = new Float32Array(N);
  p.set([0.183, -0.409, -0.006], CH.handL);
  p.set([0.25, 0, -1], CH.elbL);
  p.set([-0.183, -0.409, -0.006], CH.handR);
  p.set([-0.25, 0, -1], CH.elbR);
  p.set([0.092, 0, 0, 0, 0], CH.footL);
  p.set([-0.092, 0, 0, 0, 0], CH.footR);
  p.set([0, 0, 1], CH.aimR);
  p.set([0, 0, 1], CH.aimL);
  p.set([0, 1, 0], CH.upL);
  p[CH.wscale] = 1;
  p[CH.lscale] = 1;
  p[CH.aimSpace] = 1;
  return p;
}
export const REST = restPoseArray();

/** New pose from base with overrides (null entries keep the base value); o.add adds deltas. */
export function P(base, o = {}) {
  const p = Float32Array.from(base || REST);
  const write = (k, v, delta) => {
    const ch = CH[k];
    if (ch === undefined) throw new Error(`pose: unknown channel ${k}`);
    const isFoot = k === 'footL' || k === 'footR';
    const vals = typeof v === 'number' ? [v] : v;
    for (let i = 0; i < vals.length; i++) {
      if (vals[i] === null || vals[i] === undefined) continue;
      const ang = ANG.has(k) || (isFoot && i >= 3);
      const x = ang ? vals[i] * DEG : vals[i];
      p[ch + i] = delta ? p[ch + i] + x : x;
    }
  };
  for (const k in o) if (k !== 'add') write(k, o[k], false);
  if (o.add) for (const k in o.add) write(k, o.add[k], true);
  return p;
}

// ---------------------------------------------------------------------------------------
// Style key poses

const hoplite = {};
hoplite.stance = P(null, {
  pelP: [0, -0.04, 0], pelR: [2, -14, 0], spine: [5, 0, 0], chest: [3, 9, 0], neck: [-3, 0, 0], head: [-3, 5, 0],
  footL: [0.12, 0, 0.17, 6, 0], footR: [-0.15, 0, -0.17, -30, 0],
  handL: [-0.05, -0.27, 0.22], elbL: [1, -0.5, -0.2], aimL: [0.15, 0.12, 1], upL: [0, 1, 0],
  handR: [-0.26, -0.31, 0.10], elbR: [-0.4, 0, -1], aimR: [0.04, 0.35, 1],
});
hoplite.guard = P(hoplite.stance, {
  spine: [8, 0, 0], chest: [4, 10, 0], neck: [-6, 0, 0], head: [-4, 4, 0],
  handL: [-0.10, -0.09, 0.30], elbL: [1, -0.6, -0.1], aimL: [0.1, 0.06, 1],
  handR: [-0.27, 0.36, 0.08], elbR: [-1, 0.1, -0.55], aimR: [0.03, -0.08, 1],
});

const levy = {};
levy.stance = P(null, {
  pelP: [0, -0.03, 0], pelR: [2, -8, 0], spine: [3, 0, 0], chest: [2, 5, 0], neck: [-2, 0, 0], head: [-2, 3, 0],
  footL: [0.12, 0, 0.12, 6, 0], footR: [-0.13, 0, -0.13, -22, 0],
  handL: [0.2, -0.3, 0.13], elbL: [1, -0.2, -0.6], aimL: [0.5, 0.02, 1], upL: [0, 1, 0],
  handR: [-0.25, -0.27, 0.10], elbR: [-0.3, 0, -1], aimR: [0.03, 0.5, 1],
});
levy.guard = P(levy.stance, {
  spine: [7, 0, 0], chest: [3, 6, 0], neck: [-5, 0, 0], head: [-3, 3, 0],
  handL: [0.08, -0.08, 0.32], elbL: [1, -0.4, -0.5], aimL: [0.08, 0.0, 1],
  handR: [-0.26, 0.33, 0.06], elbR: [-1, 0.1, -0.5], aimR: [0.03, -0.07, 1],
});

const sparabara = {};
sparabara.stance = P(levy.stance, {
  pelP: [0, -0.05, -0.02], spine: [8, 0, 0], footL: [0.13, 0, 0.14, 4, 0], footR: [-0.13, 0, -0.16, -20, 0],
  handL: [0.06, -0.08, 0.36], elbL: [1, -0.6, -0.3], aimL: [0, 0, 1], upL: [0, 1, 0],
  handR: [-0.25, -0.24, 0.10], aimR: [0.02, 0.62, 1],
});
sparabara.guard = P(sparabara.stance, {
  spine: [11, 0, 0], neck: [-8, 0, 0],
  handR: [-0.25, 0.33, 0.10], elbR: [-1, 0.1, -0.5], aimR: [0.04, -0.08, 1],
});

const archer = {};
archer.stance = P(null, {
  pelP: [0, -0.02, 0], pelR: [1, -6, 0], spine: [2, 0, 0], chest: [1, 3, 0], head: [-2, 3, 0],
  footL: [0.11, 0, 0.09, 6, 0], footR: [-0.12, 0, -0.09, -16, 0],
  handL: [0.2, -0.33, 0.08], elbL: [1, 0, -0.6], aimL: [0.1, 1, 0.45], upL: [0.15, 0, 1],
  handR: [-0.2, -0.38, 0.02], elbR: [-0.3, 0, -1], aimR: [0, -0.3, 1],
  wscale: 0,
});
archer.guard = P(archer.stance, { handL: [0.2, -0.22, 0.2], aimL: [0.1, 1, 0.2] });

const captain = {};
captain.stance = P(null, {
  pelP: [0, -0.05, 0], pelR: [3, -10, 0], spine: [6, 0, 0], chest: [3, 8, 0], neck: [-4, 0, 0], head: [-4, 3, 0],
  footL: [0.14, 0, 0.15, 10, 0], footR: [-0.16, 0, -0.17, -26, 0],
  handL: [0.2, -0.22, 0.17], elbL: [1, -0.3, -0.5],
  handR: [-0.26, -0.22, 0.12], elbR: [-0.4, 0, -1], aimR: [0.0, 0.75, 0.65], upR: [0, -0.6, 0.8],
});
captain.guard = P(captain.stance, {
  spine: [9, 0, 0], neck: [-6, 0, 0],
  handL: [0.18, -0.02, 0.30], elbL: [1, -0.4, -0.4],
  handR: [-0.17, 0.10, 0.30], elbR: [-1, -0.2, -0.4], aimR: [0.4, 0.75, 0.45], upR: [0, -0.2, 1],
});

const helot = {};
helot.stance = P(null, {
  pelP: [0, -0.01, 0], spine: [3, 0, 0], footL: [0.1, 0, 0.03, 5, 0], footR: [-0.1, 0, -0.03, -8, 0],
  handL: [0.2, -0.39, 0.03], elbL: [0.3, 0, -1],
  handR: [-0.2, 0.2, 0.14], elbR: [-0.6, -1, -0.3], aimR: [0.05, 0.45, -1], upR: [0, 1, 0],
});
helot.guard = helot.stance;

const dummy = { stance: P(null, {}), guard: P(null, {}) };

export const STYLES = { hoplite, levy, sparabara, archer, captain, helot, dummy };

// ---------------------------------------------------------------------------------------
// Clips: CLIPS (public timing) and per-style keyframes.

/**
 * name → { strikeAt, duration (default seconds), layer, loop: false, hold?, rigid?: [a, b] }
 * rigid: the normalised window around the strike that keeps its absolute timing when the
 * caller stretches the duration (a long, readable wind-up keeps a fast, crisp strike).
 */
export const CLIPS = {
  thrust: { strikeAt: 0.6, duration: 1.4, layer: 'full', loop: false, rigid: [0.5, 0.74] },
  overhead: { strikeAt: 0.56, duration: 1.3, layer: 'full', loop: false, rigid: [0.48, 0.72] },
  slash: { strikeAt: 0.5, duration: 1.0, layer: 'full', loop: false, rigid: [0.42, 0.66] },
  axe: { strikeAt: 0.6, duration: 1.7, layer: 'full', loop: false, rigid: [0.52, 0.74] },
  bash: { strikeAt: 0.42, duration: 0.8, layer: 'full', loop: false, rigid: [0.3, 0.6] },
  shoot: { strikeAt: 0.6, duration: 2.0, layer: 'full', loop: false, rigid: [0.56, 0.72] },
  throw: { strikeAt: 0.55, duration: 1.2, layer: 'full', loop: false, rigid: [0.46, 0.7] },
  hit: { strikeAt: 0, duration: 0.45, layer: 'upper', loop: false },
  stagger: { strikeAt: 0, duration: 1.2, layer: 'full', loop: false },
  knockdown: { strikeAt: 0.5, duration: 1.3, layer: 'full', loop: false, hold: true },
  getup: { strikeAt: 0.8, duration: 1.6, layer: 'full', loop: false },
  block_hit: { strikeAt: 0, duration: 0.5, layer: 'upper', loop: false },
  cheer: { strikeAt: 0.3, duration: 2.0, layer: 'upper', loop: false },
  die_back: { strikeAt: 0.62, duration: 1.6, layer: 'full', loop: false, hold: true },
  die_forward: { strikeAt: 0.66, duration: 1.7, layer: 'full', loop: false, hold: true },
  die_crumple: { strikeAt: 0.7, duration: 1.9, layer: 'full', loop: false, hold: true },
  die_spear: { strikeAt: 0.72, duration: 2.1, layer: 'full', loop: false, hold: true },
  fall_sea: { strikeAt: 0.5, duration: 1.6, layer: 'full', loop: false, hold: true },
};

const K = (t, pose, stop = false) => ({ t, pose, stop });

/** A pose lying on the back, ground-corrected (shared by deaths and knockdown). */
function lyingBack(st, o = {}) {
  return P(st.stance, Object.assign({
    aimSpace: 0,
    pelP: [0, -0.84, -0.42], pelR: [-88, 8, 0], spine: [-4, 0, 6], chest: [-3, -6, 0], neck: [6, 0, 0], head: [10, 35, 0],
    legFK: 1, thighL: [-14, 0, 9], shinL: [10, 0, 0], footFKL: [28, 0, 0], thighR: [-6, 0, -12], shinR: [24, 0, 0], footFKR: [32, 0, 0],
    handL: [0.56, 0.12, 0.02], elbL: [1, 0, -1], aimL: [0.1, 0.1, 1], upL: [0, 1, 0],
    handR: [-0.52, 0.0, 0.0], elbR: [-1, 0, -1], aimR: [-0.3, 0.95, 0.0], upR: [0, 0, 1],
    ground: 1, cloak: 0,
  }, o));
}

function lyingFront(st, o = {}) {
  return P(st.stance, Object.assign({
    aimSpace: 0,
    pelP: [0.02, -0.84, 0.46], pelR: [86, -10, 0], spine: [2, 0, -4], chest: [2, 8, 0], neck: [-12, 0, 0], head: [-10, -70, 0],
    legFK: 1, thighL: [-8, 0, 10], shinL: [26, 0, 0], footFKL: [55, 0, 0], thighR: [4, 0, -8], shinR: [10, 0, 0], footFKR: [60, 0, 0],
    handL: [0.42, 0.26, 0.02], elbL: [1, 0, 0.6], aimL: [0, 0, 1], upL: [0, 1, 0],
    handR: [-0.3, -0.36, 0.0], elbR: [-1, 0, 0.5], aimR: [0.2, 0.98, 0.0], upR: [0, 0, 1],
    ground: 1,
  }, o));
}

function lyingSide(st, o = {}) {
  return P(st.stance, Object.assign({
    aimSpace: 0,
    pelP: [0.36, -0.79, 0.05], pelR: [6, 10, -86], spine: [16, 0, 0], chest: [10, 0, 4], neck: [6, 0, -10], head: [10, 0, -14],
    legFK: 1, thighL: [-50, 0, 0], shinL: [78, 0, 0], footFKL: [30, 0, 0], thighR: [-36, 0, 4], shinR: [60, 0, 0], footFKR: [30, 0, 0],
    handL: [0.1, -0.18, 0.34], elbL: [1, -1, 0], aimL: [1, 0, 0.1], upL: [0, 1, 0],
    handR: [-0.05, -0.12, 0.40], elbR: [-0.5, -1, 0], aimR: [0, 1, 0.1], upR: [0, 0, 1],
    ground: 1,
  }, o));
}

const cache = new Map();

/** Keyframes for (style, clip): [{ t, pose, stop }]. Cached. */
export function clipKeys(styleName, clip) {
  const key = styleName + ':' + clip;
  let keys = cache.get(key);
  if (!keys) {
    keys = makeKeys(styleName, clip);
    cache.set(key, keys);
  }
  return keys;
}

function makeKeys(sn, clip) {
  const st = STYLES[sn] || STYLES.levy;
  const g = st.guard, s0 = st.stance;
  const shieldL = sn === 'hoplite' || sn === 'levy' || sn === 'sparabara';
  switch (clip) {
    case 'thrust': {
      const back = P(g, {
        add: { pelP: [0, -0.03, -0.07], pelR: [0, -10, 0], chest: [0, -16, 0], spine: [-2, 0, 0], head: [0, 12, 0] },
        handR: [-0.31, 0.37, -0.16], elbR: [-1, -0.25, -0.8], aimR: [0.04, -0.05, 1],
      });
      const coil = P(back, { handR: [-0.32, 0.37, -0.19], add: { chest: [0, -4, 0] } });
      const strike = P(g, {
        add: { pelP: [0, -0.05, 0.13], pelR: [3, 10, 0], chest: [0, 14, 0], spine: [8, 0, 0], head: [0, -14, 0] },
        footL: [null, 0, (g[CH.footL + 2] || 0.15) + 0.2],
        handR: [-0.08, 0.31, 0.56], elbR: [-0.6, -0.5, -0.6], aimR: [0.0, -0.1, 1],
      });
      const follow = P(strike, { handR: [-0.05, 0.3, 0.6], add: { chest: [0, 4, 0], spine: [2, 0, 0] } });
      const rec = P(g, { add: { pelP: [0, -0.02, 0.06] }, footL: [null, 0, (g[CH.footL + 2] || 0.15) + 0.12], handR: [-0.22, 0.32, 0.26] });
      const step = P(back, { footL: [null, 0.06, (g[CH.footL + 2] || 0.15) + 0.1] });
      return [K(0, g), K(0.36, back), K(0.5, coil, true), K(0.545, step), K(0.6, strike), K(0.7, follow, true), K(0.84, rec), K(1, g, true)];
    }
    case 'overhead': {
      const raise = P(g, {
        add: { pelP: [0, 0, -0.05], chest: [-6, -8, 0], spine: [-3, 0, 0], head: [0, 8, 0] },
        handR: [-0.17, 0.56, -0.02], elbR: [-1, 0.35, -0.3], aimR: [0.02, -0.5, 1],
      });
      const hold = P(raise, { handR: [-0.16, 0.6, -0.07] });
      const step = P(hold, { footL: [null, 0.06, (g[CH.footL + 2] || 0.12) + 0.1] });
      const strike = P(g, {
        add: { pelP: [0, -0.08, 0.12], spine: [12, 0, 0], chest: [8, 6, 0], head: [-6, -6, 0] },
        footL: [null, 0, (g[CH.footL + 2] || 0.12) + 0.2],
        handR: [-0.06, 0.17, 0.52], elbR: [-0.7, -0.4, -0.5], aimR: [0.03, -0.55, 1],
      });
      const follow = P(strike, { handR: [-0.03, 0.08, 0.55], aimR: [0.03, -0.68, 1], add: { spine: [3, 0, 0] } });
      const rec = P(g, { handR: [-0.2, 0.3, 0.2] });
      return [K(0, g), K(0.36, raise), K(0.47, hold, true), K(0.515, step), K(0.56, strike), K(0.68, follow, true), K(0.84, rec), K(1, g, true)];
    }
    case 'slash': {
      const wind = P(g, {
        add: { pelR: [0, -12, 0], chest: [0, -24, 0], head: [0, 18, 0], pelP: [0, -0.02, -0.04] },
        handR: [-0.33, 0.38, -0.1], elbR: [-1, 0.5, -0.3], aimR: [-0.45, 0.7, -0.55], upR: [-1, 0, 0],
      });
      const hold = P(wind, { handR: [-0.34, 0.4, -0.14] });
      const strike = P(g, {
        add: { pelR: [0, 10, 0], chest: [4, 24, 0], spine: [8, 0, 0], head: [0, -20, 0], pelP: [0, -0.04, 0.08] },
        footL: [null, 0, (g[CH.footL + 2] || 0.15) + 0.14],
        handR: [0.04, 0.06, 0.46], elbR: [-0.6, -0.5, -0.6], aimR: [0.85, -0.2, 0.5], upR: [0, 1, 0],
      });
      const follow = P(strike, { handR: [0.22, -0.12, 0.32], aimR: [0.7, -0.55, -0.2], add: { chest: [0, 8, 0] } });
      return [K(0, g), K(0.34, wind), K(0.42, hold, true), K(0.5, strike), K(0.62, follow, true), K(0.82, P(g, { handR: [-0.2, 0.2, 0.2] })), K(1, g, true)];
    }
    case 'axe': {
      const raise = P(g, {
        add: { pelP: [0, 0.0, -0.08], pelR: [-2, -8, 0], chest: [-10, -14, 0], spine: [-6, 0, 0], head: [6, 10, 0] },
        handR: [-0.2, 0.5, -0.16], elbR: [-0.8, 0.6, -0.4], aimR: [0.1, 0.35, -1], upR: [0, 1, 0.2],
        handL: [0.2, 0.1, 0.38], elbL: [1, -0.2, -0.4],
      });
      const hold = P(raise, { handR: [-0.19, 0.53, -0.22], aimR: [0.1, 0.1, -1] });
      const step = P(hold, { footL: [null, 0.07, (g[CH.footL + 2] || 0.15) + 0.12] });
      const strike = P(g, {
        add: { pelP: [0, -0.12, 0.16], pelR: [4, 8, 0], spine: [16, 0, 0], chest: [10, 14, 0], head: [-10, -8, 0] },
        footL: [null, 0, (g[CH.footL + 2] || 0.15) + 0.26],
        handR: [-0.02, 0.02, 0.5], elbR: [-0.7, -0.3, -0.5], aimR: [0.12, -0.45, 1], upR: [0, -1, 0.3],
        handL: [0.26, -0.2, -0.08], elbL: [1, 0, -0.3],
      });
      const follow = P(strike, { handR: [0.12, -0.24, 0.38], aimR: [0.3, -0.9, 0.35], add: { spine: [6, 0, 0] } });
      return [K(0, g), K(0.38, raise), K(0.5, hold, true), K(0.555, step), K(0.6, strike), K(0.72, follow, true), K(0.88, P(g, { handR: [-0.22, 0.0, 0.3] })), K(1, g, true)];
    }
    case 'bash': {
      const coil = P(g, {
        add: { pelP: [0, -0.06, -0.05], chest: [0, -12, 0], spine: [4, 0, 0] },
        handL: [g[CH.handL] + 0.02, g[CH.handL + 1] - 0.02, g[CH.handL + 2] - 0.14],
      });
      const strike = P(g, {
        add: { pelP: [0, -0.06, 0.16], chest: [0, 18, 0], spine: [12, 0, 0] },
        footL: [null, 0, (g[CH.footL + 2] || 0.15) + 0.24],
        handL: [g[CH.handL] - 0.02, g[CH.handL + 1] + 0.01, g[CH.handL + 2] + 0.2],
      });
      const step = P(coil, { footL: [null, 0.06, (g[CH.footL + 2] || 0.15) + 0.12] });
      return [K(0, g), K(0.28, coil, true), K(0.36, step), K(0.42, strike), K(0.56, strike, true), K(1, g, true)];
    }
    case 'shoot': {
      // archer: draw an arrow from the hip quiver, nock, raise, draw, loose, recover
      const side = { pelR: [1, -24, 0], chest: [2, -16, 0], neck: [-2, 18, 0], head: [-4, 22, 0], footL: [0.1, 0, 0.12, 30, 0], footR: [-0.14, 0, -0.08, -40, 0] };
      const reach = P(s0, Object.assign({}, side, { handR: [0.1, -0.33, 0.02], elbR: [-0.2, 0.2, -1], wscale: 0 }));
      const take = P(reach, { handR: [0.04, -0.12, 0.2], wscale: 1, aimR: [0.6, 0.8, 0.2] });
      const nock = P(reach, {
        handL: [0.36, 0.14, 0.36], elbL: [1, -0.4, -0.2], aimL: [0.05, 1, 0.1], upL: [0.3, 0.05, 1],
        handR: [0.2, 0.17, 0.27], elbR: [-0.6, -0.6, -0.6], wscale: 1, draw: 1,
      });
      const full = P(nock, {
        handL: [0.48, 0.36, 0.42], elbL: [1, -0.6, -0.1], aimL: [0.05, 1, 0.08], upL: [0.55, 0.18, 1],
        handR: [-0.08, 0.37, 0.05], elbR: [-1, 0.1, -0.5], draw: 1, wscale: 1,
      });
      const aim = P(full, { handR: [-0.1, 0.375, 0.035] });
      const loose = P(full, { handR: [-0.16, 0.39, -0.04], draw: 0, wscale: 0 });
      const rec = P(loose, { handL: [0.36, 0.1, 0.32], handR: [-0.2, 0.0, 0.0], draw: 0, wscale: 0 });
      return [K(0, s0), K(0.12, reach), K(0.22, take), K(0.32, nock), K(0.5, full), K(0.585, aim, true), K(0.6, loose), K(0.7, loose, true), K(0.86, rec), K(1, s0, true)];
    }
    case 'throw': {
      const back = P(g, {
        add: { pelP: [0, -0.02, -0.08], pelR: [0, -18, 0], chest: [-4, -26, 0], head: [0, 30, 0] },
        handR: [-0.32, 0.30, -0.33], elbR: [-1, 0, -0.5], aimR: [0, 0.25, 1],
        handL: shieldL ? [0.0, 0.0, 0.38] : [0.3, 0.1, 0.4],
      });
      const step = P(back, { footL: [null, 0.06, (g[CH.footL + 2] || 0.15) + 0.12] });
      const rel = P(g, {
        add: { pelP: [0, -0.04, 0.1], pelR: [2, 12, 0], chest: [4, 20, 0], spine: [10, 0, 0], head: [0, -20, 0] },
        footL: [null, 0, (g[CH.footL + 2] || 0.15) + 0.22],
        handR: [-0.08, 0.32, 0.42], elbR: [-0.8, 0, -0.5], aimR: [0, 0.15, 1],
      });
      const gone = P(rel, { wscale: 0 });
      const follow = P(gone, { handR: [0.14, -0.06, 0.38], elbR: [-0.5, -0.6, -0.5], aimR: [0.3, -0.6, 0.6], add: { spine: [4, 0, 0] } });
      const back2 = P(g, { wscale: 0 });
      return [K(0, g), K(0.38, back), K(0.47, back, true), K(0.51, step), K(0.55, rel), K(0.556, gone), K(0.7, follow, true), K(0.94, back2), K(1, g, true)];
    }
    case 'hit': {
      const recoil = P(g, {
        add: { spine: [-7, 0, 0], chest: [-9, 4, 0], neck: [-8, 0, 0], head: [-10, -6, 0], handR: [0, -0.04, -0.04], handL: [0, -0.03, -0.06] },
      });
      return [K(0, g), K(0.2, recoil), K(0.45, P(recoil, { add: { spine: [3, 0, 0], head: [4, 0, 0] } })), K(1, g, true)];
    }
    case 'block_hit': {
      const jolt = P(g, { add: { spine: [-5, 0, 0], chest: [-6, -6, 0], head: [6, 0, 0], handL: [-0.01, 0.02, -0.1] } });
      return [K(0, g), K(0.16, jolt), K(0.4, P(jolt, { add: { handL: [0, 0, 0.05], chest: [3, 3, 0] } })), K(1, g, true)];
    }
    case 'stagger': {
      const open = {
        handL: [0.32, -0.12, 0.14], elbL: [1, -0.4, -0.3], aimL: [0.6, 0.35, 0.75],
        handR: [-0.38, -0.05, 0.1], elbR: [-1, -0.2, -0.4], aimR: [-0.3, 0.75, 0.6],
      };
      const jolt = P(s0, Object.assign({ add: { pelP: [0, -0.02, -0.08], pelR: [-6, 0, 0], spine: [-12, 0, 0], chest: [-10, 0, 0], head: [-14, 10, 0] } }, open, { footR: [-0.14, 0.07, -0.3, -20, 0] }));
      const step1 = P(jolt, { add: { pelP: [0, -0.02, -0.04], spine: [4, 0, 0] }, footR: [-0.15, 0, -0.38, -20, 0], footL: [0.12, 0.06, 0.0, 6, 0] });
      const step2 = P(step1, { add: { spine: [8, 0, 0], head: [10, -10, 0] }, footL: [0.12, 0, -0.06, 6, 0] });
      const catchP = P(s0, { add: { pelP: [0, -0.05, -0.06], spine: [10, 0, 0], head: [4, 0, 0] }, footL: [0.12, 0, -0.06, 6, 0], footR: [-0.15, 0, -0.38, -20, 0] });
      return [K(0, s0), K(0.12, jolt), K(0.3, step1), K(0.5, step2), K(0.74, catchP, true), K(1, g, true)];
    }
    case 'knockdown': {
      const jolt = P(s0, { add: { pelP: [0, -0.02, -0.12], spine: [-16, 0, 0], chest: [-8, 0, 0], head: [-18, 0, 0] }, handL: [0.35, 0.05, 0.2], handR: [-0.4, 0.15, 0.1], aimL: [0.4, 0.6, 0.6], footR: [-0.15, 0.06, -0.3, -20, 0] });
      const fall = P(jolt, { pelP: [0, -0.42, -0.36], pelR: [-42, -6, 0], legFK: 0.7, thighL: [-40, 0, 6], shinL: [60, 0, 0], thighR: [-25, 0, -6], shinR: [40, 0, 0], head: [12, 0, 0], ground: 0.6 });
      const down = lyingBack(st, { thighL: [-36, 0, 8], shinL: [52, 0, 0], thighR: [-20, 0, -8], shinR: [30, 0, 0], head: [16, 10, 0] });
      const bounce = P(down, { add: { pelP: [0, 0.03, 0], head: [10, 0, 0] } });
      const rest = lyingBack(st, { thighL: [-52, 0, 6], shinL: [88, 0, 0], footFKL: [-30, 0, 0], thighR: [-12, 0, -10], shinR: [24, 0, 0], head: [22, 20, 0], handL: [0.5, 0.0, 0.1], handR: [-0.45, -0.1, 0.12] });
      return [K(0, s0), K(0.12, jolt), K(0.34, fall), K(0.6, down), K(0.68, bounce), K(1, rest, true)];
    }
    case 'getup': {
      const from = clipKeys(sn, 'knockdown');
      const lying = from[from.length - 1].pose;
      const roll = P(lying, { pelP: [0.2, -0.72, -0.3], pelR: [-60, 0, -40], spine: [20, 0, 0], chest: [10, 0, 0], head: [0, 0, 0], handL: [0.3, -0.2, 0.25], handR: [-0.1, -0.1, 0.4], thighL: [-70, 0, 0], shinL: [100, 0, 0], thighR: [-40, 0, 0], shinR: [80, 0, 0], ground: 1 });
      const kneel = P(s0, { pelP: [0, -0.48, -0.1], pelR: [10, 0, 0], spine: [20, 0, 0], legFK: 1, thighL: [-80, 0, 4], shinL: [80, 0, 0], footFKL: [-5, 0, 0], thighR: [10, 0, -4], shinR: [110, 0, 0], footFKR: [60, 0, 0], handR: [-0.25, -0.35, 0.2], handL: [0.15, -0.3, 0.2], ground: 1 });
      const rise = P(s0, { pelP: [0, -0.2, -0.05], spine: [14, 0, 0], footL: [0.12, 0, 0.2, 6, 0], footR: [-0.14, 0, -0.18, -24, 0], ground: 0 });
      return [K(0, lying), K(0.28, roll), K(0.55, kneel), K(0.8, rise), K(1, s0, true)];
    }
    case 'cheer': {
      const up = P(g, {
        handR: [-0.22, 0.62, 0.12], elbR: [-1, 0, -0.2], aimR: [0.05, 1, 0.3], upR: [0, 0, 1],
        add: { neck: [-12, 0, 0], head: [-10, 0, 0], chest: [-4, 0, 0] },
      });
      const pump = P(up, { handR: [-0.22, 0.5, 0.1], add: { head: [6, 0, 0] } });
      return [K(0, g), K(0.18, up), K(0.32, pump), K(0.46, up), K(0.6, pump), K(0.74, up, true), K(1, g, true)];
    }
    case 'die_back': {
      const jolt = P(s0, { add: { pelP: [0, -0.02, -0.08], spine: [-14, 0, 0], chest: [-10, 0, 0], head: [-20, 0, 0] }, handL: [0.3, -0.05, 0.2], handR: [-0.35, 0.0, 0.15], aimL: [0.5, 0.5, 0.7], footR: [-0.15, 0.05, -0.28, -20, 0] });
      const buckle = P(jolt, { add: { pelP: [0, -0.18, -0.12] }, footR: [-0.15, 0, -0.36, -20, 0], legFK: 0.3, thighL: [-30, 0, 4], shinL: [50, 0, 0], head: [10, 0, 0] });
      const fall = P(buckle, { pelP: [0, -0.55, -0.32], pelR: [-52, 6, 0], legFK: 0.8, thighL: [-30, 0, 8], shinL: [30, 0, 0], thighR: [-15, 0, -8], shinR: [20, 0, 0], handL: [0.5, 0.25, 0.05], handR: [-0.5, 0.25, 0.0], ground: 0.5 });
      const down = lyingBack(st);
      const bounce = P(down, { add: { pelP: [0, 0.035, 0], head: [-8, 0, 0], thighL: [-4, 0, 0] } });
      const rest = lyingBack(st, { head: [12, 42, 0] });
      return [K(0, s0), K(0.1, jolt), K(0.32, buckle), K(0.5, fall), K(0.62, down), K(0.7, bounce), K(1, rest, true)];
    }
    case 'die_forward': {
      const fold = P(s0, { add: { pelP: [0, -0.1, 0.0], spine: [24, 0, 0], chest: [18, 0, 0], head: [12, 0, 0] }, handR: [-0.06, -0.22, 0.24], handL: [0.08, -0.22, 0.22], aimL: [0.2, -0.6, 0.7], aimR: [0.2, -0.9, 0.3] });
      const knees = P(fold, { pelP: [0, -0.42, 0.04], legFK: 1, thighL: [-10, 0, 4], shinL: [105, 0, 0], footFKL: [55, 0, 0], thighR: [-4, 0, -4], shinR: [110, 0, 0], footFKR: [55, 0, 0], add: { spine: [8, 0, 0] }, ground: 1 });
      const fall = P(knees, { pelP: [0, -0.6, 0.3], pelR: [50, 0, 0], thighL: [-30, 0, 4], shinL: [60, 0, 0], thighR: [-20, 0, -4], shinR: [70, 0, 0], handL: [0.3, 0.1, 0.4], handR: [-0.3, 0.1, 0.4] });
      const down = lyingFront(st);
      const bounce = P(down, { add: { pelP: [0, 0.03, 0] } });
      return [K(0, s0), K(0.14, fold), K(0.4, knees), K(0.58, fall), K(0.66, down), K(0.74, bounce), K(1, lyingFront(st, { head: [-10, -78, 0] }), true)];
    }
    case 'die_crumple': {
      const sag = P(s0, { add: { pelP: [0, -0.16, 0.0], spine: [14, 0, 0], chest: [8, 0, 0], head: [24, 0, 0] }, handL: [0.2, -0.38, 0.1], handR: [-0.2, -0.38, 0.06], aimL: [0.3, -0.3, 0.9], aimR: [0, -1, 0.3] });
      const knees = P(sag, { pelP: [0, -0.5, 0.0], legFK: 1, thighL: [-6, 0, 6], shinL: [112, 0, 0], footFKL: [50, 0, 0], thighR: [0, 0, -6], shinR: [118, 0, 0], footFKR: [50, 0, 0], add: { spine: [8, 0, 0] }, ground: 1 });
      const tip = P(knees, { pelP: [0.18, -0.62, 0.05], pelR: [10, 4, -45] });
      const down = lyingSide(st);
      const bounce = P(down, { add: { pelP: [0, 0.03, 0] } });
      return [K(0, s0), K(0.18, sag), K(0.46, knees, true), K(0.62, tip), K(0.72, down), K(0.8, bounce), K(1, lyingSide(st, { head: [16, 0, -20] }), true)];
    }
    case 'die_spear': {
      const jolt = P(s0, { add: { pelP: [0, -0.03, -0.12], spine: [-18, 0, 0], chest: [-10, -8, 0], head: [-16, 0, 0] }, handR: [-0.04, -0.02, 0.18], elbR: [-1, -0.5, 0], aimR: [-0.1, -1, 0.2], footR: [-0.15, 0.06, -0.3, -20, 0] });
      const clutch = P(jolt, { add: { spine: [16, 0, 0], head: [20, 0, 0] }, footR: [-0.15, 0, -0.4, -20, 0], handL: [0.1, -0.2, 0.25], aimL: [0.2, -0.5, 0.8] });
      const knees = P(clutch, { pelP: [0, -0.45, -0.18], legFK: 1, thighL: [-50, 0, 6], shinL: [100, 0, 0], footFKL: [10, 0, 0], thighR: [0, 0, -6], shinR: [120, 0, 0], footFKR: [55, 0, 0], ground: 1 });
      const fall = P(knees, { pelP: [0, -0.68, -0.42], pelR: [-55, 4, 0], thighL: [-50, 0, 6], shinL: [80, 0, 0], thighR: [-30, 0, -6], shinR: [90, 0, 0] });
      const down = lyingBack(st, { thighL: [-48, 0, 6], shinL: [74, 0, 0], footFKL: [-20, 0, 0], thighR: [-30, 0, -10], shinR: [60, 0, 0], footFKR: [-10, 0, 0], handR: [-0.06, 0.05, 0.16], aimR: [-0.6, 0.0, 0.0] });
      const bounce = P(down, { add: { pelP: [0, 0.03, 0] } });
      const rest = lyingBack(st, { thighL: [-36, 0, 12], shinL: [56, 0, 0], footFKL: [-12, 0, 0], thighR: [-10, 0, -14], shinR: [16, 0, 0], footFKR: [20, 0, 0], handR: [-0.1, 0.0, 0.14], aimR: [-0.6, 0.0, 0.0], head: [14, 50, 0] });
      return [K(0, s0), K(0.08, jolt), K(0.26, clutch), K(0.5, knees, true), K(0.72, fall), K(0.8, down), K(0.86, bounce), K(1, rest, true)];
    }
    case 'fall_sea': {
      const jolt = P(s0, { add: { pelP: [0, 0, -0.1], spine: [-20, 0, 0], head: [-20, 0, 0] }, handL: [0.4, 0.3, 0.1], handR: [-0.45, 0.4, 0.1], aimL: [0.6, 0.6, 0.4] });
      const flail = P(jolt, { pelR: [-40, 0, 10], pelP: [0, -0.1, -0.3], handL: [0.3, 0.55, -0.1], handR: [-0.5, 0.2, 0.2], legFK: 0.8, thighL: [-50, 0, 10], shinL: [60, 0, 0], thighR: [-10, 0, -10], shinR: [20, 0, 0] });
      const flail2 = P(flail, { pelR: [-80, 10, 6], handL: [0.5, 0.2, 0.2], handR: [-0.3, 0.6, -0.1], thighL: [-20, 0, 10], shinL: [30, 0, 0], thighR: [-60, 0, -10], shinR: [80, 0, 0] });
      const end = lyingBack(st, { ground: 0, pelP: [0, -0.7, -0.4] });
      return [K(0, s0), K(0.12, jolt), K(0.35, flail), K(0.6, flail2), K(1, end, true)];
    }
    default:
      return [K(0, g), K(1, g, true)];
  }
}
