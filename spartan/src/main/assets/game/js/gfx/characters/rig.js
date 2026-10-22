/**
 * Skeleton layout shared by every kind. All bones have identity rotation at rest, so the
 * rest pose is "standing straight, arms hanging", and animation writes absolute local
 * rotations. Positions are in mesh space for a body of height H (feet at y = 0, facing +Z,
 * the actor's left is +X).
 */
import * as THREE from 'three';

/** [name, parentIndex] in creation order (parents first). */
export const BONES = [
  ['pelvis', -1], ['spine', 0], ['chest', 1], ['neck', 2], ['head', 3],
  ['clavL', 2], ['upperArmL', 5], ['forearmL', 6], ['handL', 7], ['itemL', 8], ['nock', 9],
  ['clavR', 2], ['upperArmR', 11], ['forearmR', 12], ['handR', 13], ['weaponR', 14],
  ['thighL', 0], ['shinL', 16], ['footL', 17], ['thighR', 0], ['shinR', 19], ['footR', 20],
  ['back', 2], ['cloak', 2], ['skirtFL', 0], ['skirtFR', 0], ['skirtBL', 0], ['skirtBR', 0],
];
export const B = {};
BONES.forEach(([n], i) => {
  B[n] = i;
});

/**
 * Rest joint positions (mesh space) for a body. build: { H, shoulders, hips }.
 * Returns { pos: name → [x,y,z], len: { upperArm, forearm, thigh, shin }, grip: [x,y,z] offset of
 * the fist centre from the wrist in hand space, ankle }.
 */
export function restPose(build) {
  const s = build.H / 1.76;
  const sw = build.shoulders || 1;
  const hw = build.hips || 1;
  const P = {};
  const set = (n, x, y, z) => {
    P[n] = [x * s, y * s, z * s];
  };
  set('pelvis', 0, 0.95, 0);
  set('spine', 0, 1.05, -0.005);
  set('chest', 0, 1.22, -0.012);
  set('neck', 0, 1.455, -0.028);
  set('head', 0, 1.585, -0.012);
  for (const [side, k] of [['L', 1], ['R', -1]]) {
    set('clav' + side, k * 0.02, 1.40, -0.012);
    set('upperArm' + side, k * 0.183 * sw, 1.432, -0.03);
    set('forearm' + side, k * 0.183 * sw, 1.132, -0.03);
    set('hand' + side, k * 0.183 * sw, 0.877, -0.03);
    set('thigh' + side, k * 0.092 * hw, 0.90, 0);
    set('shin' + side, k * 0.092 * hw, 0.48, 0.004);
    set('foot' + side, k * 0.092 * hw, 0.085, -0.004);
    set('skirtF' + side, k * 0.092 * hw, 0.90, 0);
    set('skirtB' + side, k * 0.092 * hw, 0.90, 0);
  }
  const grip = [0, -0.066 * s, 0.012 * s];
  const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
  P.itemL = add(P.handL, grip);
  P.nock = P.itemL.slice();
  P.weaponR = add(P.handR, grip);
  set('back', 0, 1.28, -0.14);
  set('cloak', 0, 1.425, -0.105);
  return {
    pos: P,
    s,
    grip,
    len: {
      upperArm: 0.30 * s,
      forearm: 0.255 * s,
      thigh: 0.42 * s,
      shin: 0.395 * s,
    },
    ankle: 0.085 * s,
  };
}

/** Creates a fresh bone hierarchy for one instance; returns { bones, root }. */
export function createBones(rest, overrides) {
  const bones = [];
  for (let i = 0; i < BONES.length; i++) {
    const [name, parent] = BONES[i];
    const b = new THREE.Bone();
    b.name = name;
    const p = (overrides && overrides[name]) || rest.pos[name];
    if (parent < 0) {
      b.position.set(p[0], p[1], p[2]);
    } else {
      const pp = (overrides && overrides[BONES[parent][0]]) || rest.pos[BONES[parent][0]];
      b.position.set(p[0] - pp[0], p[1] - pp[1], p[2] - pp[2]);
      bones[parent].add(b);
    }
    bones.push(b);
  }
  return { bones, root: bones[0] };
}
