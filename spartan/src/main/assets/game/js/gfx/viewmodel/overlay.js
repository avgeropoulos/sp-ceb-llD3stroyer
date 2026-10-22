/**
 * Full-screen overlay quad for the viewmodel pass (DESIGN.md §11): low-HP blood vignette, red hurt
 * flash and the Corinthian-helmet eye-slit mask. Drawn last, depthTest off, in clip space, so it
 * covers the canvas at render scale whatever the vm camera does. Output is premultiplied and in
 * display space (no tone mapping).
 */
import * as THREE from 'three';

const vertexShader = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}`;

const fragmentShader = /* glsl */ `
precision highp float;
uniform float uVignette;
uniform float uHurt;
uniform float uHelmet;
uniform float uAspect;
uniform float uTime;
varying vec2 vUv;

float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float vnoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  return 0.55 * vnoise(p) + 0.3 * vnoise(p * 2.1 + 3.7) + 0.15 * vnoise(p * 4.3 + 7.1);
}
// Premultiplied "over".
vec4 over(vec4 top, vec4 under) {
  return top + under * (1.0 - top.a);
}

void main() {
  vec2 p = vUv * 2.0 - 1.0;
  vec4 col = vec4(0.0);

  if (uHelmet > 0.5) {
    // The view through a Corinthian helmet: the two almond eye-openings merge into one wide
    // opening (binocular), the brow dips to a point over the nose, the cheek-pieces close in
    // from the lower corners and the nasal guard is a faint, very blurred shadow. Everything is
    // ~3 cm from the eye, so all edges are strongly out of focus.
    float ax = max(uAspect, 1.0);
    vec2 q = vec2(p.x * ax, p.y);
    float side = abs(q.x) / ax;                 // 0 centre .. 1 screen edge
    float brow = 0.86 - 0.16 * exp(-pow(q.x / 0.32, 2.0)) - 0.2 * pow(side, 2.2);
    float cheek = -0.9 + 0.48 * pow(side, 1.8) + 0.06 * exp(-pow(q.x / 0.5, 2.0));
    float top = smoothstep(brow - 0.22, brow + 0.1, q.y);
    float bottom = 1.0 - smoothstep(cheek - 0.12, cheek + 0.2, q.y);
    float sides = smoothstep(0.86, 1.08, side + 0.1 * pow(abs(q.y), 2.0));
    float mask = clamp(max(max(top, bottom), sides), 0.0, 1.0);
    float nasal = 0.2 * exp(-pow(q.x / 0.09, 2.0)) * smoothstep(0.35, 0.8, q.y);
    mask = max(mask, nasal);
    // Out-of-focus bronze edge catching light just inside the opening.
    float rim = mask * (1.0 - mask) * 4.0;
    vec3 inside = vec3(0.028, 0.02, 0.014) + vec3(0.2, 0.13, 0.06) * rim * 0.35;
    col = over(vec4(inside * mask, mask), col);
  }

  if (uVignette > 0.003) {
    vec2 q = vec2(p.x * 0.82, p.y);
    float r = length(q);
    float n = fbm(vec2(p.x * uAspect, p.y) * 2.2 + vec2(0.0, uTime * 0.04));
    float beat = pow(0.5 + 0.5 * sin(uTime * 7.2), 6.0) + 0.6 * pow(0.5 + 0.5 * sin(uTime * 7.2 - 1.1), 10.0);
    float reach = mix(1.25, 0.55, uVignette) - 0.08 * beat * uVignette;
    float e = smoothstep(reach, reach + 0.6, r + (n - 0.5) * 0.45);
    float a = clamp(e * (0.55 + 0.45 * uVignette), 0.0, 0.92);
    vec3 blood = mix(vec3(0.32, 0.015, 0.01), vec3(0.12, 0.0, 0.0), smoothstep(0.3, 1.0, e));
    col = over(vec4(blood * a, a), col);
  }

  if (uHurt > 0.003) {
    float r = length(vec2(p.x * 0.85, p.y));
    float a = uHurt * (0.16 + 0.5 * smoothstep(0.35, 1.3, r));
    col = over(vec4(vec3(0.55, 0.03, 0.02) * a, a), col);
  }

  if (col.a < 0.002) discard;
  gl_FragColor = col;
}`;

/** Creates the overlay mesh (add it to vm.scene). */
export function createOverlay() {
  const material = new THREE.ShaderMaterial({
    uniforms: {
      uVignette: { value: 0 },
      uHurt: { value: 0 },
      uHelmet: { value: 0 },
      uAspect: { value: 16 / 9 },
      uTime: { value: 0 },
    },
    vertexShader,
    fragmentShader,
    transparent: true,
    premultipliedAlpha: true,
    depthTest: false,
    depthWrite: false,
    toneMapped: false,
  });
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), material);
  mesh.frustumCulled = false;
  mesh.renderOrder = 10000;
  mesh.name = 'vmOverlay';
  return mesh;
}
