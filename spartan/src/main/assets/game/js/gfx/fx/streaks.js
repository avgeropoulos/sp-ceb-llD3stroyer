/**
 * Motion-blurred volley arrows (DESIGN.md §1 "archers whose volleys hide the sun"): one instanced
 * draw of dark screen-space streaks, one packet per flying arrow. A 9 mm shaft 30–50 m away is a
 * tenth of a pixel wide, so the streaks keep a minimum on-screen width and opacity whatever the
 * distance: 300 arrows read as a dark cloud against the sky instead of a few hairlines.
 *
 * Each arrow's packet is its own streak (head at the arrow tip, tail `blur` seconds back along
 * its velocity) plus PACK fainter companions scattered around it, since one visual arrow stands
 * for many in a real volley. Companions fade out near the camera, where a streak with no arrow
 * at its head would show. combat.js writes head, tail and opacity per arrow (one upload a frame).
 */
import * as THREE from 'three';

/** Companion streaks drawn around each arrow's own streak. */
export const PACK = 3;
/** Floats per instance in `data`: iHead xyz (0–2), iTail xyz (3–5), opacity (6), seed (7). */
export const STREAK_STRIDE = 8;
const STRIDE = STREAK_STRIDE;

const VERT = /* glsl */ `
#include <common>
#include <fog_pars_vertex>
attribute vec3 iHead;
attribute vec3 iTail;
attribute vec2 iPar;
attribute float aSub;
uniform vec2 uRes;
uniform float uMinPx;
uniform float uWidth;
varying float vAlpha;
varying float vAcross;
varying float vHalf;
varying float vAlong;
float sHash(float n) {
  return fract(sin(n) * 43758.5453);
}
void main() {
  vec3 head = iHead;
  vec3 tail = iTail;
  float alpha = iPar.x;
  if (aSub > 0.5) {
    // A companion: scattered around the arrow across and behind its flight line.
    vec3 ax = head - tail;
    vec3 d = ax / max(length(ax), 1e-4);
    vec3 side = cross(d, vec3(0.0, 1.0, 0.0));
    side = dot(side, side) > 1e-6 ? normalize(side) : vec3(1.0, 0.0, 0.0);
    vec3 up = cross(side, d);
    float s = iPar.y * 61.7 + aSub * 13.1;
    vec3 off = side * (sHash(s) - 0.5) * 3.2 + up * (sHash(s + 3.7) - 0.5) * 1.6 - d * sHash(s + 7.9) * 2.5;
    head += off;
    tail += off;
    alpha *= 0.75 * smoothstep(9.0, 18.0, distance(head, cameraPosition));
  }
  vec4 h = modelViewMatrix * vec4(head, 1.0);
  vec4 t = modelViewMatrix * vec4(tail, 1.0);
  // Clip the segment against a plane just in front of the eye.
  const float NZ = -0.05;
  if (h.z > NZ && t.z > NZ) alpha = 0.0;
  else if (h.z > NZ) h.xyz = mix(t.xyz, h.xyz, (NZ - t.z) / (h.z - t.z));
  else if (t.z > NZ) t.xyz = mix(h.xyz, t.xyz, (NZ - h.z) / (t.z - h.z));
  vec4 hc = projectionMatrix * h;
  vec4 tc = projectionMatrix * t;
  vec2 halfRes = uRes * 0.5;
  vec2 hs = hc.xy / hc.w * halfRes;
  vec2 ts = tc.xy / tc.w * halfRes;
  vec2 dd = hs - ts;
  float len = length(dd);
  vec2 dir = len > 1e-3 ? dd / len : vec2(0.0, 1.0);
  vec2 nrm = vec2(-dir.y, dir.x);
  float pxPerM = projectionMatrix[1][1] * halfRes.y;
  vec4 m = mix(t, h, position.y);
  float wRaw = uWidth * pxPerM / max(-m.z, 0.05);
  // Thinner than uMinPx: drawn uMinPx wide and a little fainter, never invisible.
  alpha *= clamp(wRaw / uMinPx, 0.6, 1.0);
  float core = max(wRaw, uMinPx) * 0.5;
  float hw = core + 0.75; // room for the anti-aliased edge
  vec4 c = mix(tc, hc, position.y);
  vec2 off2 = nrm * (position.x * hw) + dir * ((position.y * 2.0 - 1.0) * 0.75);
  c.xy += off2 / halfRes * c.w;
  gl_Position = c;
  vAlpha = alpha;
  vAcross = position.x * hw;
  vHalf = core;
  vAlong = position.y;
  vec4 mvPosition = m;
  #include <fog_vertex>
}
`;

const FRAG = /* glsl */ `
#include <common>
#include <fog_pars_fragment>
uniform vec3 uColor;
varying float vAlpha;
varying float vAcross;
varying float vHalf;
varying float vAlong;
void main() {
  // Solid core, one-pixel soft edge; motion blur fades toward the tail.
  float a = vAlpha * clamp(vHalf + 0.5 - abs(vAcross), 0.0, 1.0) * mix(0.12, 1.0, vAlong * vAlong);
  if (a < 0.004) discard;
  gl_FragColor = vec4(uColor, a);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
  #include <fog_fragment>
}
`;

/**
 * Creates the streak batch for up to `cap` arrows.
 * @param {number} cap arrows (each draws 1 + PACK streaks)
 * @param {object} [opts] { renderer, color (linear), width (m), minPx, renderOrder }
 * @returns {{ mesh, data, set(i, hx, hy, hz, tx, ty, tz, alpha, seed), commit(n) }} `data` is the
 *   instance array (STREAK_STRIDE floats per arrow), for hot loops that write it in place.
 */
export function createArrowStreaks(cap, opts = {}) {
  const geom = new THREE.InstancedBufferGeometry();
  const pos = [];
  const sub = [];
  const index = [];
  for (let k = 0; k <= PACK; k++) {
    const b = k * 4;
    pos.push(-1, 0, 0, 1, 0, 0, -1, 1, 0, 1, 1, 0);
    sub.push(k, k, k, k);
    index.push(b, b + 1, b + 2, b + 2, b + 1, b + 3);
  }
  geom.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geom.setAttribute('aSub', new THREE.Float32BufferAttribute(sub, 1));
  geom.setIndex(index);
  const data = new Float32Array(cap * STRIDE);
  const buffer = new THREE.InstancedInterleavedBuffer(data, STRIDE, 1);
  buffer.setUsage(THREE.DynamicDrawUsage);
  geom.setAttribute('iHead', new THREE.InterleavedBufferAttribute(buffer, 3, 0));
  geom.setAttribute('iTail', new THREE.InterleavedBufferAttribute(buffer, 3, 3));
  geom.setAttribute('iPar', new THREE.InterleavedBufferAttribute(buffer, 2, 6));
  geom.instanceCount = 0;

  const renderer = opts.renderer || null;
  const c = opts.color || [0.03, 0.024, 0.018];
  const uniforms = THREE.UniformsUtils.merge([THREE.UniformsLib.fog]);
  uniforms.uRes = { value: new THREE.Vector2(960, 540) };
  uniforms.uMinPx = { value: opts.minPx || 1.5 };
  uniforms.uWidth = { value: opts.width || 0.03 };
  uniforms.uColor = { value: new THREE.Color().setRGB(c[0], c[1], c[2], THREE.LinearSRGBColorSpace) };
  const material = new THREE.ShaderMaterial({
    name: 'volley-streaks',
    uniforms,
    vertexShader: VERT,
    fragmentShader: FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    fog: true,
  });
  const mesh = new THREE.Mesh(geom, material);
  mesh.name = 'volleyStreaks';
  mesh.frustumCulled = false;
  mesh.matrixAutoUpdate = false;
  mesh.castShadow = false;
  mesh.receiveShadow = false;
  mesh.renderOrder = opts.renderOrder || 11;
  // Visible with no instances until the first commit, so the boot pre-warm compiles it.
  mesh.visible = true;

  return {
    mesh,
    data,
    /** Writes arrow i: head (the tip), tail (back along the flight), opacity 0..1, seed 0..1. */
    set(i, hx, hy, hz, tx, ty, tz, alpha, seed) {
      const o = i * STRIDE;
      data[o] = hx;
      data[o + 1] = hy;
      data[o + 2] = hz;
      data[o + 3] = tx;
      data[o + 4] = ty;
      data[o + 5] = tz;
      data[o + 6] = alpha;
      data[o + 7] = seed;
    },
    /** Draws arrows [0, n) this frame (one whole-buffer upload). */
    commit(n) {
      geom.instanceCount = n;
      mesh.visible = n > 0;
      if (n > 0) buffer.needsUpdate = true;
      if (renderer && renderer.getDrawingBufferSize) renderer.getDrawingBufferSize(uniforms.uRes.value);
    },
  };
}
