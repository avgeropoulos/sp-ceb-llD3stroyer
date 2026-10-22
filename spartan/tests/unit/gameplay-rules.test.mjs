// Unit tests for game/rules.js (DESIGN.md §7). Run: node --test spartan/tests/unit/gameplay-rules.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  TIMING, WEAPONS, ENEMIES, DIRECTOR, dirToken, TOKEN_SETS, slashProfile, sectorHit, pickTarget,
  segmentCapsule, ballisticPitch, computeDamage,
} from '../../src/main/assets/game/js/game/rules.js';
import { yawTo, facingToYaw, facingTo, forward3, createRng } from '../../src/main/assets/game/js/core/math.js';

const P = (x, y, z) => ({ x, y, z });
const near = (a, b, eps = 1e-9, msg) => assert.ok(Math.abs(a - b) <= eps, msg || `${a} ≉ ${b} (±${eps})`);

test('tables carry the §7 numbers', () => {
  assert.deepEqual(TIMING.slash, { strike: 0.10, total: 0.34, cancelAfter: 0.16 });
  assert.deepEqual(TIMING.thrust, { strike: 0.11, total: 0.38, cancelAfter: 0.20 });
  assert.deepEqual(TIMING.spearThrust, { strike: 0.13, total: 0.44, cancelAfter: 0.24 });
  assert.deepEqual(TIMING.bash, { strike: 0.09, total: 0.45, cancelAfter: 0.30, cooldown: 0.9 });
  assert.deepEqual(TIMING.throw, { windup: 0.14, total: 0.60, redraw: 0.6 });
  assert.equal(TIMING.shieldRaise, 0.14);
  assert.equal(TIMING.shieldLower, 0.18);
  assert.equal(TIMING.shieldDip, 0.45);
  assert.equal(TIMING.perfectBlockWindow, 0.22);
  assert.equal(TIMING.perfectNeedsDown, 0.25);
  assert.equal(TIMING.critWindow, 1.0);
  assert.equal(WEAPONS.sword.reach, 2.1);
  assert.equal(WEAPONS.spear.throwDamage, 200);
  assert.deepEqual(WEAPONS.spear.throwSpeed, [20, 34]);
  assert.equal(WEAPONS.shield.rebashImmunity, 2.5);
  assert.equal(ENEMIES.captain.heavy, true);
  assert.equal(ENEMIES.captain.hp, 180);
  assert.equal(ENEMIES.archer.reach, 45);
  assert.equal(ENEMIES.dummy.hp, Infinity);
  assert.equal(ENEMIES.helot.walk, 3.5);
  assert.equal(ENEMIES.immortal.attackClip, 'overhead');
  assert.equal(DIRECTOR[3].rateMax, 0.9);
  assert.equal(DIRECTOR[1].engaged, 2);
});

test('dirToken: sector centres', () => {
  const q = Math.PI / 4;
  const expect = { R: 0, UR: q, U: 2 * q, UL: 3 * q, L: Math.PI, DL: -3 * q, D: -2 * q, DR: -q };
  for (const [tok, a] of Object.entries(expect)) assert.equal(dirToken(a), tok, tok);
  assert.equal(dirToken(-Math.PI), 'L');
  assert.equal(dirToken(Math.PI - 1e-9), 'L');
  assert.equal(dirToken(-Math.PI + 1e-9), 'L');
});

test('dirToken: boundaries at ±22.5° around each centre, and wrapping', () => {
  const order = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
  const e = 1e-6;
  for (let i = 0; i < 8; i++) {
    const c = (i * Math.PI) / 4;
    assert.equal(dirToken(c + Math.PI / 8 - e), order[i], `${order[i]} just below its upper edge`);
    assert.equal(dirToken(c - Math.PI / 8 + e), order[i], `${order[i]} just above its lower edge`);
    assert.equal(dirToken(c + Math.PI / 8 + e), order[(i + 1) % 8], `past the edge of ${order[i]}`);
    assert.equal(dirToken(c + 4 * Math.PI), order[i], 'wraps by 2π multiples');
    assert.equal(dirToken(c - 6 * Math.PI), order[i], 'wraps negative');
  }
  assert.equal(dirToken(NaN), 'R');
  assert.equal(dirToken(Infinity), 'R');
});

test('TOKEN_SETS are the §3.4 sets', () => {
  const s = (set) => [...set].sort();
  assert.deepEqual(s(TOKEN_SETS.Rish), ['DR', 'R', 'UR']);
  assert.deepEqual(s(TOKEN_SETS.Lish), ['DL', 'L', 'UL']);
  assert.deepEqual(s(TOKEN_SETS.Uish), ['U', 'UL', 'UR']);
  assert.deepEqual(s(TOKEN_SETS.Dish), ['D', 'DL', 'DR']);
  assert.ok(TOKEN_SETS.Rish instanceof Set);
});

test('slashProfile categories, widths and multipliers', () => {
  const h = slashProfile(0);
  assert.deepEqual({ ...h }, { token: 'R', category: 'horizontal', halfAngle: 0.70, damageMul: 1.0, overShield: false });
  assert.equal(slashProfile(Math.PI).category, 'horizontal');
  const d = slashProfile(Math.PI / 4);
  assert.equal(d.category, 'diagonal');
  assert.equal(d.halfAngle, 0.55);
  assert.equal(d.damageMul, 1.15);
  assert.equal(slashProfile(-3 * Math.PI / 4).token, 'DL');
  const up = slashProfile(Math.PI / 2);
  assert.equal(up.category, 'vertical');
  assert.equal(up.halfAngle, 0.35);
  assert.equal(up.damageMul, 1.35);
  assert.equal(up.overShield, false);
  const down = slashProfile(-Math.PI / 2);
  assert.equal(down.token, 'D');
  assert.equal(down.overShield, true);
  for (const a of [0.3, 1, 2, 3, -1, -2, -3]) assert.equal(slashProfile(a).overShield, slashProfile(a).token === 'D');
});

test('sectorHit: camera yaw convention (yaw 0 faces -Z, positive = left)', () => {
  const o = P(0, 0, 0);
  let r = sectorHit(o, 0, 2, 0.5, P(0, 0, -1.5), 0.32);
  assert.equal(r.hit, true);
  near(r.dist, 1.5);
  near(r.angle, 0);
  // A target on the left (-X) has a positive angle.
  r = sectorHit(o, 0, 3, 1, P(-1, 0, -1), 0);
  near(r.angle, Math.PI / 4, 1e-12);
  r = sectorHit(o, 0, 3, 1, P(1, 0, -1), 0);
  near(r.angle, -Math.PI / 4, 1e-12);
  // Turning left by 90° faces -X.
  r = sectorHit(o, Math.PI / 2, 2, 0.2, P(-1.8, 0, 0), 0);
  assert.equal(r.hit, true);
  near(r.angle, 0, 1e-12);
  // Behind is ±π.
  r = sectorHit(o, 0, 5, 3, P(0, 0, 2), 0);
  near(Math.abs(r.angle), Math.PI, 1e-12);
  // Y is ignored.
  r = sectorHit(o, 0, 2, 0.2, P(0, 50, -1.9), 0);
  assert.equal(r.hit, true);
  near(r.dist, 1.9);
});

test('sectorHit: reach includes the radius; angle tolerance is atan2(radius, dist)', () => {
  const o = P(1, 0, 2);
  const yaw = 0.3;
  const at = (dist, ang) => P(o.x - Math.sin(yaw + ang) * dist, 0, o.z - Math.cos(yaw + ang) * dist);
  assert.equal(sectorHit(o, yaw, 2.1, 0.7, at(2.1 + 0.32 - 1e-6, 0), 0.32).hit, true);
  assert.equal(sectorHit(o, yaw, 2.1, 0.7, at(2.1 + 0.32 + 1e-6, 0), 0.32).hit, false);
  const d = 1.5;
  const tol = Math.atan2(0.32, d);
  assert.equal(sectorHit(o, yaw, 2.1, 0.7, at(d, 0.7 + tol - 1e-6), 0.32).hit, true);
  assert.equal(sectorHit(o, yaw, 2.1, 0.7, at(d, 0.7 + tol + 1e-6), 0.32).hit, false);
  assert.equal(sectorHit(o, yaw, 2.1, 0.7, at(d, -(0.7 + tol - 1e-6)), 0.32).hit, true);
  assert.equal(sectorHit(o, yaw, 2.1, 0.7, at(d, -(0.7 + tol + 1e-6)), 0.32).hit, false);
  // Coincident points never divide by zero.
  const r = sectorHit(o, yaw, 1, 0.1, P(1, 0, 2), 0.32);
  assert.equal(r.hit, true);
  assert.equal(r.dist, 0);
});

test('sectorHit: actor facing convention via facingToYaw, and out reuse', () => {
  const o = P(0, 0, 0);
  const out = {};
  // An actor facing +Z (facing 0) looks at the player standing at +Z.
  let r = sectorHit(o, facingToYaw(0), 2, 0.3, P(0, 0, 1.6), 0.32, out);
  assert.equal(r, out);
  assert.equal(r.hit, true);
  near(r.angle, 0, 1e-12);
  // Facing +X.
  r = sectorHit(o, facingToYaw(Math.PI / 2), 2, 0.3, P(1.6, 0, 0), 0.32, out);
  assert.equal(r.hit, true);
  near(r.angle, 0, 1e-12);
  // facingTo agrees.
  const f = facingTo(-2, 1);
  r = sectorHit(o, facingToYaw(f), 3, 0.05, P(-2, 0, 1), 0, out);
  assert.equal(r.hit, true);
  near(r.angle, 0, 1e-12);
  // ...and the same facing misses a target behind it.
  assert.equal(sectorHit(o, facingToYaw(f), 3, 1.0, P(2, 0, -1), 0.32).hit, false);
});

test('pickTarget: min |angle| + 0.15·dist, alive only, filter, limits', () => {
  const mk = (x, z, extra = {}) => ({ pos: P(x, 0, z), alive: true, team: 'persian', ...extra });
  const o = P(0, 0, 0);
  const a = mk(0, -3); // angle 0, dist 3 → 0.45
  const b = mk(-0.5, -1.2); // angle ~0.395, dist 1.3 → ~0.59
  assert.equal(pickTarget([b, a], o, 0, 0.7, 4), a);
  const c = mk(-0.2, -1.5); // angle ~0.133, dist ~1.513 → ~0.36
  assert.equal(pickTarget([a, b, c], o, 0, 0.7, 4), c);
  c.alive = false;
  assert.equal(pickTarget([a, b, c], o, 0, 0.7, 4), a);
  assert.equal(pickTarget([a, b], o, 0, 0.7, 2.5), b, 'maxDist excludes a');
  assert.equal(pickTarget([a, b], o, 0, 0.3, 2.5), null, 'maxAngle excludes b');
  const ally = mk(0, -1, { team: 'greek' });
  assert.equal(pickTarget([ally, a], o, 0, 0.7, 4, (x) => x.team === 'persian'), a);
  assert.equal(pickTarget([], o, 0, 0.7, 4), null);
  assert.equal(pickTarget(null, o, 0, 0.7, 4), null);
  // Rotated view: yaw π/2 looks at -X.
  const left = mk(-2, 0);
  assert.equal(pickTarget([a, left], o, Math.PI / 2, 0.7, 4), left);
});

test('segmentCapsule: basic crossings', () => {
  const a = P(0, 0.32, 0);
  const b = P(0, 1.43, 0);
  const r = 0.32;
  // Straight through the body along +X from x=-2 to x=2 at chest height: enters at x=-0.32.
  near(segmentCapsule(P(-2, 1, 0), P(2, 1, 0), a, b, r), (2 - 0.32) / 4, 1e-9);
  // Parallel offset: misses.
  assert.equal(segmentCapsule(P(-2, 1, 0.33), P(2, 1, 0.33), a, b, r), -1);
  assert.ok(segmentCapsule(P(-2, 1, 0.31), P(2, 1, 0.31), a, b, r) > 0);
  // Too short to reach.
  assert.equal(segmentCapsule(P(-2, 1, 0), P(-0.4, 1, 0), a, b, r), -1);
  // Starting inside → 0.
  assert.equal(segmentCapsule(P(0.1, 1, 0), P(3, 1, 0), a, b, r), 0);
  // From above, down the axis: enters the top cap at y = 1.43 + 0.32.
  near(segmentCapsule(P(0, 3, 0), P(0, 1, 0), a, b, r), (3 - 1.75) / 2, 1e-9);
  // Grazing the bottom sphere from below the body.
  near(segmentCapsule(P(-1, 0.32, 0), P(1, 0.32, 0), a, b, r), (1 - 0.32) / 2, 1e-9);
  // Pointing away.
  assert.equal(segmentCapsule(P(1, 1, 0), P(3, 1, 0), a, b, r), -1);
  // Degenerate segment outside.
  assert.equal(segmentCapsule(P(1, 1, 0), P(1, 1, 0), a, b, r), -1);
  // Degenerate capsule (a = b) is a sphere.
  near(segmentCapsule(P(-2, 1, 0), P(2, 1, 0), P(0, 1, 0), P(0, 1, 0), 0.5), 1.5 / 4, 1e-9);
  // Segment parallel to the axis beside it: misses; inside the radius: hits the bottom cap.
  assert.equal(segmentCapsule(P(0.5, -1, 0), P(0.5, 3, 0), a, b, r), -1);
  near(segmentCapsule(P(0, -1, 0), P(0, 3, 0), a, b, r), 1 / 4, 1e-9);
});

test('segmentCapsule agrees with dense sampling on random cases', () => {
  const rng = createRng(7);
  const distToCapsule = (p, a, b) => {
    const bx = b.x - a.x, by = b.y - a.y, bz = b.z - a.z;
    const px = p.x - a.x, py = p.y - a.y, pz = p.z - a.z;
    const bb = bx * bx + by * by + bz * bz;
    const h = bb > 0 ? Math.max(0, Math.min(1, (px * bx + py * by + pz * bz) / bb)) : 0;
    return Math.hypot(px - bx * h, py - by * h, pz - bz * h);
  };
  let hits = 0;
  for (let k = 0; k < 400; k++) {
    const a = P(rng.range(-1, 1), rng.range(-1, 1), rng.range(-1, 1));
    const b = P(a.x + rng.range(-1, 1), a.y + rng.range(-1, 2), a.z + rng.range(-1, 1));
    const r = rng.range(0.1, 0.6);
    const p0 = P(rng.range(-3, 3), rng.range(-3, 3), rng.range(-3, 3));
    // Half the segments are aimed near the capsule so plenty of them hit.
    let p1 = P(rng.range(-3, 3), rng.range(-3, 3), rng.range(-3, 3));
    if (k % 2 === 0) {
      const h = rng.range(-0.2, 1.2);
      const aim = P(a.x + (b.x - a.x) * h + rng.range(-r, r), a.y + (b.y - a.y) * h + rng.range(-r, r), a.z + (b.z - a.z) * h + rng.range(-r, r));
      const m = rng.range(0.5, 2);
      p1 = P(p0.x + (aim.x - p0.x) * m, p0.y + (aim.y - p0.y) * m, p0.z + (aim.z - p0.z) * m);
    }
    const t = segmentCapsule(p0, p1, a, b, r);
    // Brute force: first sample inside.
    const N = 4000;
    let first = -1;
    for (let i = 0; i <= N; i++) {
      const s = i / N;
      const p = P(p0.x + (p1.x - p0.x) * s, p0.y + (p1.y - p0.y) * s, p0.z + (p1.z - p0.z) * s);
      if (distToCapsule(p, a, b) <= r) {
        first = s;
        break;
      }
    }
    if (first < 0) {
      // Allow a tangential touch that sampling skipped.
      if (t >= 0) {
        const p = P(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t, p0.z + (p1.z - p0.z) * t);
        near(distToCapsule(p, a, b), r, 1e-3, `case ${k}: analytic hit ${t} not on the surface`);
      }
    } else {
      hits++;
      assert.ok(t >= 0, `case ${k}: missed a hit at ${first}`);
      assert.ok(Math.abs(t - first) <= 1.5 / N + 1e-9, `case ${k}: t ${t} vs sampled ${first}`);
    }
  }
  assert.ok(hits > 80, `enough hitting cases (${hits})`);
});

// Exact flight of a point launched at (yaw, pitch, speed) under gravity; returns the height
// relative to the launch point when it has covered dxz horizontally.
function heightAt(speed, pitch, dxz, g = 9.81) {
  const vh = speed * Math.cos(pitch);
  const t = dxz / vh;
  return speed * Math.sin(pitch) * t - 0.5 * g * t * t;
}

test('ballisticPitch: the low arc lands on the target (simulated)', () => {
  for (const speed of [20, 27, 34, 38]) {
    for (const dxz of [2, 8, 12, 20, 30, 35]) {
      for (const dy of [-1.5, -0.3, 0, 0.5, 2]) {
        const p = ballisticPitch(speed, dxz, dy);
        if (p === null) continue;
        near(heightAt(speed, p, dxz), dy, 1e-6, `v${speed} x${dxz} y${dy}`);
        // Step-integrated flight (as combat.js does: exact constant-gravity steps) ends within 1 cm.
        const dt = 1 / 60;
        const vx = speed * Math.cos(p);
        let x = 0;
        let y = 0;
        let vy = speed * Math.sin(p);
        while (x + vx * dt < dxz) {
          x += vx * dt;
          y += vy * dt - 0.5 * 9.81 * dt * dt;
          vy -= 9.81 * dt;
        }
        const rem = (dxz - x) / vx;
        y += vy * rem - 0.5 * 9.81 * rem * rem;
        near(y, dy, 0.01, `integrated v${speed} x${dxz} y${dy}`);
        // The low arc is at most the 45°-ish max-range angle.
        assert.ok(p <= Math.PI / 4 + Math.atan2(dy, dxz) / 2 + 1e-9);
      }
    }
  }
});

test('ballisticPitch: range limits, symmetry and edge cases', () => {
  // 20 m/s on flat ground reaches v²/g = 40.77 m at 45°.
  const max = (20 * 20) / 9.81;
  near(ballisticPitch(20, max, 0), Math.PI / 4, 1e-6);
  assert.equal(ballisticPitch(20, max + 0.1, 0), null);
  assert.equal(ballisticPitch(20, 45, 0), null, '45 m is out of range at minimum power');
  assert.notEqual(ballisticPitch(34, 45, 0), null, 'but in range at full power');
  assert.equal(ballisticPitch(0, 10, 0), null);
  assert.ok(ballisticPitch(20, 0, 1) > 1.5);
  assert.ok(ballisticPitch(20, 0, -1) < -1.5);
  near(ballisticPitch(20, 10, 0), ballisticPitch(20, -10, 0), 1e-12);
  // Flat throws barely rise; downhill throws point down.
  assert.ok(ballisticPitch(34, 3, 0) < 0.02);
  assert.ok(ballisticPitch(34, 3, -2) < 0);
  // Gravity parameter.
  near(heightAt(20, ballisticPitch(20, 10, 1, 1.62), 10, 1.62), 1, 1e-6);
});

test('ballisticPitch: a thrown spear from the §7 release point reaches a chest at 12 m and 30 m', () => {
  const eyeY = 1.66;
  const originY = eyeY + 0.10;
  const chestY = 1.3;
  for (const dist of [12, 30]) {
    for (const power of [0, 0.5, 1]) {
      const speed = 20 + 14 * power;
      const dxz = dist - 0.35;
      const p = ballisticPitch(speed, dxz, chestY - originY);
      assert.notEqual(p, null);
      const [fx, fy, fz] = forward3(0, p);
      near(Math.hypot(fx, fz), Math.cos(p), 1e-12);
      near(fy, Math.sin(p), 1e-12);
      near(originY + heightAt(speed, p, dxz), chestY, 1e-6);
    }
  }
});

test('computeDamage', () => {
  near(computeDamage({ base: 100, power: 0 }), 65);
  near(computeDamage({ base: 100, power: 1 }), 135);
  near(computeDamage({ base: 34, power: 0.5 }), 34);
  near(computeDamage({ base: 40, power: 0.5, comboMul: 1.5 }), 60);
  near(computeDamage({ base: 40, power: 0.5, crit: true }), 64);
  near(computeDamage({ base: 40, power: 0.5, comboMul: 2.4, crit: true }), 40 * 2.4 * 1.6);
  near(computeDamage({ base: 10, power: 7 }), 13.5, 1e-12, 'power clamps at 1');
  near(computeDamage({ base: 10, power: -1 }), 6.5, 1e-12, 'power clamps at 0');
  near(computeDamage({ base: 10, power: NaN }), 6.5, 1e-12);
  // The spear throw at minimum power fells a 90 hp Immortal but not always the captain.
  assert.ok(computeDamage({ base: WEAPONS.spear.throwDamage, power: 0 }) > ENEMIES.immortal.hp);
  assert.ok(computeDamage({ base: WEAPONS.spear.throwDamage, power: 1 }) > ENEMIES.captain.hp);
});

test('yawTo / sectorHit consistency over a full circle', () => {
  for (let i = 0; i < 36; i++) {
    const yaw = -Math.PI + (i * Math.PI) / 18;
    const [x, , z] = forward3(yaw, 0);
    const r = sectorHit(P(0, 0, 0), yaw, 2, 0.01, P(x * 1.5, 0, z * 1.5), 0);
    assert.equal(r.hit, true, `yaw ${yaw}`);
    near(r.angle, 0, 1e-9);
    near(yawTo(x, z), yaw, 1e-9);
  }
});
