/**
 * Full-screen overlay quad for the viewmodel pass (DESIGN.md §11): low-HP blood vignette, red hurt
 * flash and the Corinthian-helmet eye-slit mask. Drawn last, depthTest off, in clip space, so it
 * covers the canvas at render scale whatever the vm camera does. Output is premultiplied and in
 * display space (no tone mapping). uPortrait (the hilt grip's portrait view, docs-hilt-grip.md
 * §E.2) switches the helmet to a portrait mask; it is a uniform, so there is no second program.
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
uniform float uPortrait;
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
    // The view out of a Corinthian helmet. The two almond eye-openings merge into one wide opening
    // (binocular vision); the brow arches and dips to the nasal guard, which both eyes see past as
    // a faint blurred shadow; the cheek-pieces close in only at the lower outer corners. The bronze
    // is ~3 cm from the eye, so every edge is far out of focus, with a dull glint on its inner lip.
    // The mask keeps to the outer frame (≤ ~4% of a phone screen darkened by more than 40%): the top
    // edge and the corners are where flankers and the HUD sit.
    float side = abs(p.x);                     // 0 centre .. 1 screen edge
    float mask;
    if (uPortrait > 0.5) {
      // Portrait (~41° across): the eye-opening is wider than the view, so the cheek walls are out
      // of sight. The brow arches over the top edge, darkest at the corners; the cheek-pieces clip
      // the lower corners; the edges darken only toward the top and bottom. ≤ ~4% of the screen is
      // darkened by more than 40%, none of it inside the central 70% × 70%.
      vec2 q = vec2(p.x * uAspect, p.y);       // 1 unit = half the screen height
      vec2 n = q.xx / vec2(0.12, 0.07);        // (pow is undefined for a negative base)
      float y2 = q.y * q.y;
      float brow = 1.05 - 0.04 * exp(-n.x * n.x) - 0.22 * side * side * side;
      float top = smoothstep(brow - 0.1, brow + 0.08, q.y);
      float cheekEdge = -1.14 + 0.34 * smoothstep(0.55, 1.0, side);
      float bottom = 1.0 - smoothstep(cheekEdge - 0.08, cheekEdge + 0.14, q.y);
      float sides = smoothstep(0.97, 1.12, side + 0.08 * y2 * y2);
      float nasal = 0.12 * exp(-n.y * n.y) * smoothstep(0.4, 1.0, q.y);
      mask = max(max(max(top, bottom), sides), nasal) * 0.85;
    } else {
      float ax = max(uAspect, 1.0);
      vec2 q = vec2(p.x * ax, p.y);            // 1 unit = half the screen height
      float brow = 1.08 - 0.07 * exp(-pow(q.x / 0.3, 2.0)) - 0.3 * pow(side, 3.0);
      float top = smoothstep(brow - 0.15, brow + 0.08, q.y);
      float cheekEdge = -1.25 + 0.45 * smoothstep(0.62, 1.0, side);
      float bottom = 1.0 - smoothstep(cheekEdge - 0.1, cheekEdge + 0.18, q.y);
      float sides = smoothstep(0.95, 1.08, side + 0.05 * q.y * q.y);
      mask = max(max(top, bottom), sides);
      float nasal = 0.16 * exp(-pow(q.x / 0.13, 2.0)) * smoothstep(0.2, 1.0, q.y);
      mask = max(mask, nasal) * 0.85;
    }
    float rim = clamp(mask * (1.0 - mask) * 4.0, 0.0, 1.0);
    vec3 inside = vec3(0.03, 0.022, 0.015) + vec3(0.3, 0.19, 0.08) * rim * 0.2;
    col = over(vec4(inside * mask, mask), col);
  }

  if (uVignette > 0.003) {
    vec2 q = vec2(p.x * 0.82, p.y);
    float r = length(q);
    // hud drives the heartbeat through uVignette; the blood itself only creeps slowly.
    float n = fbm(vec2(p.x * uAspect, p.y) * 2.2 + vec2(0.0, uTime * 0.04));
    float reach = mix(1.25, 0.55, uVignette);
    float e = smoothstep(reach, reach + 0.6, r + (n - 0.5) * 0.45);
    float a = clamp(e * (0.55 + 0.45 * uVignette), 0.0, 0.92);
    vec3 blood = mix(vec3(0.32, 0.015, 0.01), vec3(0.12, 0.0, 0.0), smoothstep(0.3, 1.0, e));
    col = over(vec4(blood * a, a), col);
  }

  if (uHurt > 0.003) {
    float r = length(vec2(p.x * 0.85, p.y));
    float a = uHurt * (0.07 + 0.55 * smoothstep(0.3, 1.3, r));
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
      uPortrait: { value: 0 },
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
