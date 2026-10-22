/**
 * Motion-trail ribbon for blade sweeps: a strip between the blade's base and tip sampled over the
 * last few hundredths of a second (the caller evaluates the analytic swing, so the arc stays smooth
 * at any frame rate). Alpha fades with age and toward the base. One draw call.
 */
import * as THREE from 'three';

const vertexShader = /* glsl */ `
attribute vec2 fade;
varying vec2 vFade;
void main() {
  vFade = fade;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}`;

const fragmentShader = /* glsl */ `
precision highp float;
uniform vec3 uColor;
uniform float uOpacity;
varying vec2 vFade;
void main() {
  float age = clamp(vFade.x, 0.0, 1.0);
  float across = clamp(vFade.y, 0.0, 1.0);
  float edge = smoothstep(0.0, 0.85, across) * (1.0 - 0.35 * smoothstep(0.9, 1.0, across));
  float a = uOpacity * pow(1.0 - age, 2.2) * edge * edge;
  if (a < 0.003) discard;
  gl_FragColor = vec4(uColor * a, a);
}`;

/** Creates a trail with `samples` cross-sections. Returns { mesh, set(i, base, tip, age), commit(n), hide() }. */
export function createTrail(samples = 28) {
  const pos = new Float32Array(samples * 2 * 3);
  const fade = new Float32Array(samples * 2 * 2);
  const idx = [];
  for (let i = 0; i < samples - 1; i++) {
    const a = i * 2;
    idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2);
  }
  const geo = new THREE.BufferGeometry();
  const posAttr = new THREE.BufferAttribute(pos, 3).setUsage(THREE.DynamicDrawUsage);
  const fadeAttr = new THREE.BufferAttribute(fade, 2).setUsage(THREE.DynamicDrawUsage);
  geo.setAttribute('position', posAttr);
  geo.setAttribute('fade', fadeAttr);
  geo.setIndex(idx);
  geo.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 10);
  const material = new THREE.ShaderMaterial({
    uniforms: { uColor: { value: new THREE.Color(0.9, 0.9, 0.88) }, uOpacity: { value: 0.32 } },
    vertexShader,
    fragmentShader,
    transparent: true,
    premultipliedAlpha: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    toneMapped: false,
  });
  const mesh = new THREE.Mesh(geo, material);
  mesh.frustumCulled = false;
  mesh.renderOrder = 50;
  mesh.name = 'vmTrail';
  return {
    mesh,
    samples,
    /** Writes cross-section i (base and tip are Vector3s in the mesh's parent space). */
    set(i, base, tip, age) {
      const k = i * 6;
      pos[k] = base.x;
      pos[k + 1] = base.y;
      pos[k + 2] = base.z;
      pos[k + 3] = tip.x;
      pos[k + 4] = tip.y;
      pos[k + 5] = tip.z;
      const f = i * 4;
      fade[f] = age;
      fade[f + 1] = 0;
      fade[f + 2] = age;
      fade[f + 3] = 1;
    },
    /** Uses the first n cross-sections (n ≥ 2 to show anything). */
    commit(n) {
      if (n < 2) {
        geo.setDrawRange(0, 0);
        return;
      }
      geo.setDrawRange(0, (n - 1) * 6);
      posAttr.needsUpdate = true;
      fadeAttr.needsUpdate = true;
    },
    hide() {
      geo.setDrawRange(0, 0);
    },
  };
}
