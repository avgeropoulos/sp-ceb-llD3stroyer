/**
 * Pooled particles for gfx/fx.js (DESIGN.md §11): three instanced draws sharing one budget of
 * 800 particles.
 *   - streaks: lit, alpha-blended camera-facing segments (blood drops, water drops, grit,
 *     tumbling splinters), stretched along their velocity like a camera shutter would.
 *   - sparks: additive HDR segments (bronze sparks, impact flashes) that cool as they age.
 *   - soft: lit, alpha-blended noisy billboards (blood mist, dust puffs, wisps, spray, foam, steam).
 * Particles advance with game time; they freeze during hit-stop and pause.
 */
import * as THREE from 'three';
import { createRng, clamp } from '../../core/math.js';

/** Pool sizes: lit streaks (drops, splinters, grit), additive sparks, soft puffs. */
export const CAP_STREAK = 440;
export const CAP_SPARK = 120;
export const CAP_SOFT = 240;
/** Total particle budget (DESIGN.md §11: ≤ 800). */
export const PARTICLE_CAP = CAP_STREAK + CAP_SPARK + CAP_SOFT;

const G = 9.81;
/** Dust bursts this close to the camera (m, on the ground plane) emit half their puffs. */
export const DUST_NEAR = 1.5;

// Kinds.
const BLOOD = 1;
const SPARK = 2;
const FLASH = 3;
const GRIT = 4;
const SPLINTER = 5;
const WATER = 6;
const MIST = 7;
const DUST = 8;
const SPRAY = 9;
const FOAM = 10;
const WISP = 11;
const STEAM = 12;

// Flags.
const RESTING = 1;
const FIXED_GROUND = 2;
const BOUNCED = 4;

const FLOATS = ['px', 'py', 'pz', 'vx', 'vy', 'vz', 'age', 'life', 'w0', 'w1', 'len', 'r', 'g', 'b', 'a',
  'drag', 'grav', 'ux', 'uy', 'uz', 'wx', 'wy', 'wz', 'spin', 'seed', 'gx', 'gz', 'gy', 'emit'];

/** Struct-of-arrays pool, kept compact (swap-remove), recycling the oldest slots when full. */
class Pool {
  constructor(cap) {
    this.cap = cap;
    this.count = 0;
    this.cursor = 0;
    this.arrays = [];
    for (let i = 0; i < FLOATS.length; i++) {
      const arr = new Float32Array(cap);
      this[FLOATS[i]] = arr;
      this.arrays.push(arr);
    }
    this.kind = new Uint8Array(cap);
    this.flags = new Uint8Array(cap);
    this.tag = new Int8Array(cap);
    this.arrays.push(this.kind, this.flags, this.tag);
  }

  alloc() {
    if (this.count < this.cap) return this.count++;
    const i = this.cursor;
    this.cursor = (this.cursor + 1) % this.cap;
    return i;
  }

  remove(i) {
    const last = --this.count;
    if (i !== last) {
      const arrays = this.arrays;
      for (let k = 0; k < arrays.length; k++) arrays[k][i] = arrays[k][last];
    }
    if (this.cursor >= this.count) this.cursor = 0;
  }

  clear() {
    this.count = 0;
    this.cursor = 0;
  }
}

// ---------------------------------------------------------------------------------------------
// Shaders

const STREAK_VERT = /* glsl */ `
#include <common>
#include <fog_pars_vertex>
attribute vec3 iPos;
attribute vec3 iAxis;
attribute vec4 iCol;
attribute vec2 iPar;
uniform vec2 uRes;
varying vec4 vCol;
varying vec4 vSeg;
varying float vLen;
varying vec2 vMisc;
void main() {
  vec4 h = modelViewMatrix * vec4(iPos, 1.0);
  vec4 t = modelViewMatrix * vec4(iPos - iAxis, 1.0);
  const float NZ = -0.05;
  if (h.z > NZ && t.z < NZ) h.xyz = mix(t.xyz, h.xyz, (NZ - t.z) / (h.z - t.z));
  else if (t.z > NZ && h.z < NZ) t.xyz = mix(h.xyz, t.xyz, (NZ - h.z) / (t.z - h.z));
  vec4 hc = projectionMatrix * h;
  vec4 tc = projectionMatrix * t;
  vec2 halfRes = uRes * 0.5;
  vec2 hs = hc.xy / hc.w * halfRes;
  vec2 ts = tc.xy / tc.w * halfRes;
  vec2 d = hs - ts;
  float len = length(d);
  vec2 dir = len > 1e-3 ? d / len : vec2(0.0, 1.0);
  vec2 nrm = vec2(-dir.y, dir.x);
  float pxPerM = projectionMatrix[1][1] * halfRes.y;
  float wRaw = iPar.x * pxPerM / max(-mix(t.z, h.z, position.y), 0.05);
  float hw = max(wRaw, 1.4) * 0.5;
  vec4 c = mix(tc, hc, position.y);
  vec2 off = nrm * (position.x * hw) + dir * ((position.y * 2.0 - 1.0) * hw);
  c.xy += off / halfRes * c.w;
  gl_Position = c;
  // Screen-linear varyings (multiplied by w here, divided in the fragment shader).
  float along = position.y * len + (position.y * 2.0 - 1.0) * hw;
  vSeg = vec4(position.x * c.w, along * c.w, hw * c.w, c.w);
  vLen = len * c.w;
  vCol = iCol;
  vMisc = vec2(clamp(wRaw / 1.4, 0.12, 1.0), iPar.y);
  vec4 mvPosition = mix(t, h, position.y);
  #include <fog_vertex>
}
`;

const STREAK_FRAG = /* glsl */ `
#include <common>
#include <fog_pars_fragment>
uniform vec3 uAmb;
uniform vec3 uSun;
varying vec4 vCol;
varying vec4 vSeg;
varying float vLen;
varying vec2 vMisc;
void main() {
  float w = vSeg.w;
  float across = vSeg.x / w;
  float along = vSeg.y / w;
  float hw = vSeg.z / w;
  float len = vLen / w;
  // 0 at the tail, 1 at the head; motion-blurred drops taper toward the tail.
  float f = len > 0.5 ? clamp(along / len, 0.0, 1.0) : 1.0;
  float taper = mix(0.45, 1.0, f);
  float dist = abs(across) / taper;
  if (along < 0.0) dist = length(vec2(across / taper, along / hw));
  else if (along > len) dist = length(vec2(across, (along - len) / hw));
  float a = (1.0 - smoothstep(0.45, 1.0, dist)) * vCol.a * vMisc.x * mix(0.35, 1.0, f);
  if (a < 0.004) discard;
#ifdef ADDITIVE
  vec3 col = vCol.rgb * mix(0.25, 1.0, f) * (1.0 + 0.5 * (1.0 - dist));
  gl_FragColor = vec4(col, a);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
  #ifdef USE_FOG
    #ifdef FOG_EXP2
      float fogF = 1.0 - exp(- fogDensity * fogDensity * vFogDepth * vFogDepth);
    #else
      float fogF = smoothstep(fogNear, fogFar, vFogDepth);
    #endif
    gl_FragColor.a *= 1.0 - fogF;
  #endif
#else
  vec3 col = vCol.rgb * (uAmb + uSun * 0.7) * RECIPROCAL_PI;
  // A wet glint on the leading end of drops.
  col += uSun * (vMisc.y * 0.05 * f * f * pow(max(1.0 - dist * 1.6, 0.0), 3.0));
  gl_FragColor = vec4(col, a);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
  #include <fog_fragment>
#endif
}
`;

const SOFT_VERT = /* glsl */ `
#include <common>
#include <fog_pars_vertex>
attribute vec3 iPos;
attribute vec2 iSize;
attribute vec4 iCol;
attribute vec3 iPar;
uniform vec3 uSunDir;
varying vec4 vCol;
varying vec2 vUv;
varying float vLight;
varying vec3 vGround;
void main() {
  vec4 mv = modelViewMatrix * vec4(iPos, 1.0);
  vec2 corner = vec2(position.x, position.y * 2.0 - 1.0);
  float c = cos(iSize.y);
  float s = sin(iSize.y);
  // Cap the on-screen size (about half the view height) so a puff at the eye can't flood the
  // screen with overdraw; such puffs are faded out below anyway.
  float radius = min(iSize.x, max(-mv.z, 0.0) * 0.35 + 0.02);
  vec2 q = vec2(c * corner.x - s * corner.y, s * corner.x + c * corner.y) * radius;
  mv.xy += q;
  gl_Position = projectionMatrix * mv;
  // World height of this corner (camera right/up are the view matrix rows), so the sprite can
  // fade out where it meets the ground instead of showing a hard intersection line.
  vec3 camUp = vec3(viewMatrix[0][1], viewMatrix[1][1], viewMatrix[2][1]);
  vec3 camRight = vec3(viewMatrix[0][0], viewMatrix[1][0], viewMatrix[2][0]);
  float wy = iPos.y + camRight.y * q.x + camUp.y * q.y;
  vGround = vec3(wy, iPar.z, max(radius * 0.45, 0.05));
  float v = floor(iPar.x + 0.5);
  vUv = vec2(mod(v, 2.0), floor(v * 0.5)) * 0.5 + (corner * 0.5 + 0.5) * 0.5;
  vec3 viewDir = normalize(iPos - cameraPosition);
  float mu = max(dot(viewDir, uSunDir), 0.0);
  vLight = 0.8 + iPar.y * pow(mu, 6.0);
  // Fade sprites that reach the eye instead of letting them fill the screen.
  float near = smoothstep(0.12, 0.9, -mv.z + radius * 0.2);
  vCol = vec4(iCol.rgb, iCol.a * near);
  vec4 mvPosition = mv;
  #include <fog_vertex>
}
`;

const SOFT_FRAG = /* glsl */ `
#include <common>
#include <fog_pars_fragment>
uniform sampler2D uTex;
uniform vec3 uAmb;
uniform vec3 uSun;
varying vec4 vCol;
varying vec2 vUv;
varying float vLight;
varying vec3 vGround;
void main() {
  float d = texture2D(uTex, vUv).r;
  float a = d * vCol.a * smoothstep(vGround.y, vGround.y + vGround.z, vGround.x);
  if (a < 0.003) discard;
  vec3 col = vCol.rgb * (uAmb * 1.25 + uSun * vLight) * RECIPROCAL_PI;
  gl_FragColor = vec4(col, a);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
  #include <fog_fragment>
}
`;

// ---------------------------------------------------------------------------------------------

/** A 2×2 atlas of noisy puffs in one red channel (no external texture needed). */
function makePuffTexture() {
  const S = 128;
  const C = 64;
  const data = new Uint8Array(S * S);
  const rng = createRng(911);
  const L = 9;
  const lattice = new Float32Array(L * L);
  const smooth = (t) => t * t * (3 - 2 * t);
  const sample = (x, y) => {
    const xi = Math.floor(x);
    const yi = Math.floor(y);
    const fx = smooth(x - xi);
    const fy = smooth(y - yi);
    const at = (i, j) => lattice[((j % L + L) % L) * L + ((i % L + L) % L)];
    const a = at(xi, yi) + (at(xi + 1, yi) - at(xi, yi)) * fx;
    const b = at(xi, yi + 1) + (at(xi + 1, yi + 1) - at(xi, yi + 1)) * fx;
    return a + (b - a) * fy;
  };
  for (let v = 0; v < 4; v++) {
    for (let i = 0; i < lattice.length; i++) lattice[i] = rng.next();
    const ox = (v % 2) * C;
    const oy = Math.floor(v / 2) * C;
    for (let y = 0; y < C; y++) {
      for (let x = 0; x < C; x++) {
        const u = ((x + 0.5) / C) * 2 - 1;
        const w = ((y + 0.5) / C) * 2 - 1;
        const r = Math.sqrt(u * u + w * w) * (1.08 + 0.02 * v);
        let n = 0;
        let amp = 0.5;
        let f = 1.6;
        for (let o = 0; o < 4; o++) {
          n += amp * sample(u * f + 4.5 + v * 1.7, w * f + 4.5 - v);
          f *= 2.03;
          amp *= 0.5;
        }
        n /= 0.9375;
        const body = clamp(1 - r, 0, 1);
        const edge = clamp((1 - r + (n - 0.5) * 0.55) / 0.35, 0, 1);
        const dens = Math.pow(body, 0.85) * (0.45 + 0.75 * n) * edge * edge * (3 - 2 * edge);
        // Leave a one-texel margin so the atlas cells don't bleed into each other.
        const margin = x === 0 || y === 0 || x === C - 1 || y === C - 1;
        data[(oy + y) * S + ox + x] = margin ? 0 : Math.round(clamp(dens * 1.45, 0, 1) * 255);
      }
    }
  }
  const tex = new THREE.DataTexture(data, S, S, THREE.RedFormat, THREE.UnsignedByteType);
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  tex.magFilter = THREE.LinearFilter;
  tex.generateMipmaps = true;
  tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
  tex.needsUpdate = true;
  return tex;
}

/** Floats per instance in every batch (one interleaved buffer, one upload per frame). */
const STRIDE = 12;

function makeBatch(cap, layout, material, renderOrder) {
  const geom = new THREE.InstancedBufferGeometry();
  geom.setAttribute('position', new THREE.Float32BufferAttribute([-1, 0, 0, 1, 0, 0, -1, 1, 0, 1, 1, 0], 3));
  geom.setIndex([0, 1, 2, 2, 1, 3]);
  const data = new Float32Array(cap * STRIDE);
  const buffer = new THREE.InstancedInterleavedBuffer(data, STRIDE, 1);
  buffer.setUsage(THREE.DynamicDrawUsage);
  let offset = 0;
  for (let i = 0; i < layout.length; i++) {
    const [name, size] = layout[i];
    geom.setAttribute(name, new THREE.InterleavedBufferAttribute(buffer, size, offset));
    offset += size;
  }
  geom.instanceCount = 0;
  const mesh = new THREE.Mesh(geom, material);
  mesh.frustumCulled = false;
  mesh.matrixAutoUpdate = false;
  mesh.castShadow = false;
  mesh.receiveShadow = false;
  mesh.renderOrder = renderOrder;
  // Visible (with no instances) until the first update, so main.js's pre-warm compileAsync,
  // which skips invisible objects, compiles these programs behind the boot screen.
  mesh.visible = true;
  mesh.name = 'fx-particles';
  return { geom, mesh, buffer, data };
}

/**
 * Creates the particle system and adds its meshes to `ctx.scene`.
 * `hooks.onBloodLand(x, y, z, width)` is called when a blood drop that may stain lands.
 */
export function createParticles(ctx, hooks = {}) {
  const { scene, camera, renderer } = ctx;
  // Visual randomness has its own stream so effects never perturb gameplay's ctx.rng.
  const rng = createRng(0x5eed);
  const R = rng.range;
  const gauss = rng.gauss;

  const streaks = new Pool(CAP_STREAK);
  const sparks = new Pool(CAP_SPARK);
  const soft = new Pool(CAP_SOFT);

  // Spot budgets for blood bursts: tag → how many landing drops may still leave a stain.
  const BURSTS = 32;
  const budgets = new Int8Array(BURSTS);
  let burstCursor = 0;

  // Shared uniforms (lights are refreshed by fx every frame).
  const shared = {
    uAmb: { value: new THREE.Color(0.55, 0.6, 0.68) },
    uSun: { value: new THREE.Color(2.4, 2.25, 2.0) },
    uSunDir: { value: new THREE.Vector3(-0.5, 0.7, 0.4).normalize() },
    uRes: { value: new THREE.Vector2(960, 540) },
    uTex: { value: makePuffTexture() },
  };
  function makeMaterial(vert, frag, opts) {
    const uniforms = THREE.UniformsUtils.merge([THREE.UniformsLib.fog]);
    uniforms.uAmb = shared.uAmb;
    uniforms.uSun = shared.uSun;
    uniforms.uSunDir = shared.uSunDir;
    uniforms.uRes = shared.uRes;
    uniforms.uTex = shared.uTex;
    return new THREE.ShaderMaterial(Object.assign({
      uniforms,
      vertexShader: vert,
      fragmentShader: frag,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide, // quads are built facing the camera in the shader, either winding
      fog: true,
    }, opts));
  }
  const streakMat = makeMaterial(STREAK_VERT, STREAK_FRAG, { blending: THREE.NormalBlending });
  const sparkMat = makeMaterial(STREAK_VERT, STREAK_FRAG, { blending: THREE.AdditiveBlending, defines: { ADDITIVE: 1 } });
  const softMat = makeMaterial(SOFT_VERT, SOFT_FRAG, { blending: THREE.NormalBlending });
  streakMat.name = 'fx-streaks';
  sparkMat.name = 'fx-sparks';
  softMat.name = 'fx-soft';

  const STREAK_LAYOUT = [['iPos', 3], ['iAxis', 3], ['iCol', 4], ['iPar', 2]];
  const SOFT_LAYOUT = [['iPos', 3], ['iSize', 2], ['iCol', 4], ['iPar', 3]];
  const bStreak = makeBatch(CAP_STREAK, STREAK_LAYOUT, streakMat, 12);
  const bSoft = makeBatch(CAP_SOFT, SOFT_LAYOUT, softMat, 13);
  const bSpark = makeBatch(CAP_SPARK, STREAK_LAYOUT, sparkMat, 14);
  scene.add(bStreak.mesh, bSoft.mesh, bSpark.mesh);

  let dirty = true; // the first update hides the empty batches

  // Scratch values for spawners (no per-call allocation).
  let ox = 0, oy = 0, oz = 0; // origin
  let dx = 0, dy = 0, dz = 0; // main direction
  let ax = 0, ay = 0, az = 0; // basis 1
  let bx = 0, by = 0, bz = 0; // basis 2

  function readPoint(p) {
    if (!p) return false;
    if (Array.isArray(p)) {
      ox = +p[0] || 0;
      oy = +p[1] || 0;
      oz = +p[2] || 0;
    } else {
      ox = +p.x || 0;
      oy = +p.y || 0;
      oz = +p.z || 0;
    }
    return true;
  }

  /** Reads a direction ({x,z}, {x,y,z} or [x,y,z]); lift adds an upward bias; builds a basis. */
  function readDir(d, lift, fallbackUp) {
    if (Array.isArray(d)) {
      dx = +d[0] || 0;
      dy = +d[1] || 0;
      dz = +d[2] || 0;
    } else if (d) {
      dx = +d.x || 0;
      dy = +d.y || 0;
      dz = +d.z || 0;
    } else {
      dx = dy = dz = 0;
    }
    dy += lift;
    let l = Math.hypot(dx, dy, dz);
    if (l < 1e-5) {
      if (fallbackUp) {
        dx = 0;
        dy = 1;
        dz = 0;
      } else {
        const a = rng.next() * Math.PI * 2;
        dx = Math.cos(a);
        dy = 0.2;
        dz = Math.sin(a);
      }
      l = Math.hypot(dx, dy, dz);
    }
    dx /= l;
    dy /= l;
    dz /= l;
    // a = normalize(d × up) (or × x if d is vertical), b = a × d
    if (Math.abs(dy) > 0.95) {
      ax = 0;
      ay = -dz;
      az = dy;
    } else {
      ax = -dz;
      ay = 0;
      az = dx;
    }
    l = Math.hypot(ax, ay, az) || 1;
    ax /= l;
    ay /= l;
    az /= l;
    bx = ay * dz - az * dy;
    by = az * dx - ax * dz;
    bz = ax * dy - ay * dx;
  }

  function tooFar() {
    const c = camera.position;
    const ddx = ox - c.x;
    const ddy = oy - c.y;
    const ddz = oz - c.z;
    return ddx * ddx + ddy * ddy + ddz * ddz > 90 * 90;
  }

  /** Particle count scaled for ctx.quality (fewer on low). */
  function n(base) {
    const q = ctx.quality;
    return Math.max(0, Math.round(base * (q === 'low' ? 0.6 : q === 'medium' ? 0.85 : 1)));
  }

  function emit(pool, kind, x, y, z, vx, vy, vz, life, w0, w1, r, g, b, a, drag, grav) {
    const i = pool.alloc();
    pool.kind[i] = kind;
    pool.flags[i] = 0;
    pool.tag[i] = -1;
    pool.px[i] = x;
    pool.py[i] = y;
    pool.pz[i] = z;
    pool.vx[i] = vx;
    pool.vy[i] = vy;
    pool.vz[i] = vz;
    pool.age[i] = 0;
    pool.life[i] = life;
    pool.w0[i] = w0;
    pool.w1[i] = w1;
    pool.r[i] = r;
    pool.g[i] = g;
    pool.b[i] = b;
    pool.a[i] = a;
    pool.drag[i] = drag;
    pool.grav[i] = grav;
    pool.len[i] = 0;
    pool.ux[i] = pool.uy[i] = pool.uz[i] = 0;
    pool.wx[i] = pool.wy[i] = pool.wz[i] = 0;
    pool.spin[i] = 0;
    pool.seed[i] = rng.next();
    pool.gx[i] = 1e9;
    pool.gz[i] = 1e9;
    pool.gy[i] = -1e9;
    pool.emit[i] = 0;
    dirty = true;
    return i;
  }

  function softPuff(kind, x, y, z, vx, vy, vz, life, r0, r1, r, g, b, a, drag, grav, scatter, spin) {
    const i = emit(soft, kind, x, y, z, vx, vy, vz, life, r0, r1, r, g, b, a, drag, grav);
    soft.emit[i] = scatter;
    soft.spin[i] = spin;
    return i;
  }

  // --- spawners ---------------------------------------------------------------------------------

  /** Blood spray along `dir` (attacker → target), with back-spray, dribble and mist. */
  function blood(point, dir, amount = 1) {
    if (!readPoint(point) || tooFar()) return;
    amount = clamp(+amount || 1, 0.1, 3);
    readDir(dir, 0.15, false);
    const tag = burstCursor;
    burstCursor = (burstCursor + 1) % BURSTS;
    budgets[tag] = Math.round(1 + 1.6 * amount);
    const count = n(8 + 16 * amount);
    for (let k = 0; k < count; k++) {
      const back = rng.next() < 0.2;
      const sp = back ? R(0.6, 2.2) : R(1.4, 4.6) * (0.75 + 0.25 * amount);
      const cone = back ? 0.85 : 0.38;
      const s = back ? -0.55 : 1;
      const c1 = gauss() * cone;
      const c2 = gauss() * cone;
      let vx = dx * s + ax * c1 + bx * c2;
      let vy = dy * s + ay * c1 + by * c2;
      let vz = dz * s + az * c1 + bz * c2;
      const l = Math.hypot(vx, vy, vz) || 1;
      vx = (vx / l) * sp;
      vy = (vy / l) * sp + R(0.3, 1.5);
      vz = (vz / l) * sp;
      const w = R(0.007, 0.017) * (sp < 2 ? 1.3 : 1);
      const i = emit(streaks, BLOOD, ox + R(-0.03, 0.03), oy + R(-0.04, 0.04), oz + R(-0.03, 0.03), vx, vy, vz,
        2.2, w, w, R(0.13, 0.22), R(0.005, 0.013), R(0.004, 0.01), 0.97, R(0.25, 0.8), 1);
      streaks.len[i] = 0.016;
      streaks.emit[i] = 0.8;
      streaks.tag[i] = tag;
    }
    const drib = n(2 + 2 * amount);
    for (let k = 0; k < drib; k++) {
      const w = R(0.01, 0.017);
      const i = emit(streaks, BLOOD, ox + R(-0.05, 0.05), oy + R(-0.08, 0.02), oz + R(-0.05, 0.05),
        dx * R(0.1, 0.6) + gauss() * 0.15, R(-0.6, 0.3), dz * R(0.1, 0.6) + gauss() * 0.15,
        2.2, w, w, R(0.14, 0.2), R(0.007, 0.013), R(0.005, 0.01), 0.97, 0.4, 1);
      streaks.len[i] = 0.02;
      streaks.emit[i] = 0.8;
      streaks.tag[i] = tag;
    }
    const mist = Math.max(1, n(1.5 + amount * 1.5));
    for (let k = 0; k < mist; k++) {
      const sp = R(0.6, 1.8);
      softPuff(MIST, ox + R(-0.04, 0.04), oy + R(-0.04, 0.04), oz + R(-0.04, 0.04),
        dx * sp + gauss() * 0.25, dy * sp + 0.15, dz * sp + gauss() * 0.25,
        R(0.35, 0.65), 0.05, R(0.25, 0.42) * Math.sqrt(amount), 0.2, 0.016, 0.011, R(0.45, 0.6), 5, 0.05, 0.6, R(-1, 1));
    }
  }

  /** Bronze sparks off a struck surface, spraying around `normal`, with a hot flash. */
  function sparksFn(point, normal, amount = 1) {
    if (!readPoint(point) || tooFar()) return;
    amount = clamp(+amount || 1, 0.1, 3);
    readDir(normal, 0, true);
    const count = n(5 + 12 * amount);
    for (let k = 0; k < count; k++) {
      let vx = dx * R(0.25, 1) + ax * gauss() * 0.75 + bx * gauss() * 0.75;
      let vy = dy * R(0.25, 1) + ay * gauss() * 0.75 + by * gauss() * 0.75 + 0.3;
      let vz = dz * R(0.25, 1) + az * gauss() * 0.75 + bz * gauss() * 0.75;
      if (vx * dx + vy * dy + vz * dz < 0) {
        vx = -vx;
        vy = -vy;
        vz = -vz;
      }
      const l = Math.hypot(vx, vy, vz) || 1;
      const sp = R(2.5, 9) * (0.8 + 0.2 * amount);
      const i = emit(sparks, SPARK, ox + dx * 0.02, oy + dy * 0.02, oz + dz * 0.02, (vx / l) * sp, (vy / l) * sp, (vz / l) * sp,
        R(0.16, 0.55), R(0.006, 0.011), 0, 1, 1, 1, 1, R(0.8, 2.2), 1);
      sparks.emit[i] = R(1.4, 2.6);
      sparks.len[i] = 0.018;
    }
    const f = emit(sparks, FLASH, ox + dx * 0.07, oy + dy * 0.07, oz + dz * 0.07, 0, 0, 0,
      0.075, 0.07 + 0.04 * amount, 0.13 + 0.06 * amount, 1, 0.72, 0.4, 1, 0, 0);
    sparks.emit[f] = 2.6;
    softPuff(DUST, ox + dx * 0.05, oy + dy * 0.05, oz + dz * 0.05, dx * 0.4, dy * 0.4 + 0.2, dz * 0.4,
      0.6, 0.04, 0.22, 0.32, 0.3, 0.27, 0.12, 4, -0.05, 0.8, R(-1, 1));
  }

  /**
   * How much of a dust burst to emit at the origin: half within DUST_NEAR m (on the ground plane)
   * of the camera, where each puff covers a large part of the view, ramping to all of it at twice
   * that distance.
   */
  function nearDustScale() {
    const c = camera.position;
    const ddx = ox - c.x;
    const ddz = oz - c.z;
    const d = Math.sqrt(ddx * ddx + ddz * ddz);
    return d <= DUST_NEAR ? 0.5 : d >= 2 * DUST_NEAR ? 1 : 0.5 + (0.5 * (d - DUST_NEAR)) / DUST_NEAR;
  }

  /** Dust puffs with grit, and a slow wisp for bigger amounts. */
  function dust(point, amount = 1) {
    if (!readPoint(point) || tooFar()) return;
    amount = clamp(+amount || 1, 0.1, 3);
    const sq = Math.sqrt(amount);
    const near = nearDustScale();
    const puffs = Math.max(1, Math.round(n(2 + 3 * amount) * near));
    for (let k = 0; k < puffs; k++) {
      const a = rng.next() * Math.PI * 2;
      const sp = R(0.3, 1.3) * sq;
      const tint = R(0.9, 1.08);
      softPuff(DUST, ox + R(-0.1, 0.1), oy + R(0, 0.1), oz + R(-0.1, 0.1),
        Math.cos(a) * sp, R(0.3, 0.9), Math.sin(a) * sp,
        R(1.0, 2.0), 0.12, R(0.5, 1.0) * (0.6 + 0.4 * amount), 0.72 * tint, 0.64 * tint, 0.52 * tint,
        R(0.6, 0.8), 2.2, -0.04, 1.6, R(-0.6, 0.6));
    }
    const grit = n(3 * amount);
    for (let k = 0; k < grit; k++) {
      const a = rng.next() * Math.PI * 2;
      const sp = R(0.4, 1.6);
      const w = R(0.006, 0.012);
      const i = emit(streaks, GRIT, ox, oy + 0.03, oz, Math.cos(a) * sp, R(1.2, 3.0), Math.sin(a) * sp,
        1.4, w, w, 0.22, 0.18, 0.13, 1, 0.3, 1);
      streaks.len[i] = 0.015;
    }
    if (amount >= 0.6 && near > 0.75) wisp(WISP, ox, oy + 0.15, oz, 0.72, 0.65, 0.53, 0.16);
  }

  function wisp(kind, x, y, z, r, g, b, alpha) {
    const a = rng.next() * Math.PI * 2;
    softPuff(kind, x, y, z, Math.cos(a) * 0.15, kind === STEAM ? R(0.3, 0.6) : R(0.08, 0.18), Math.sin(a) * 0.15,
      R(2.5, 3.6), 0.35, R(1.2, 1.7), r, g, b, alpha, 0.6, kind === STEAM ? -0.08 : -0.01, kind === STEAM ? 2 : 1.8, R(-0.3, 0.3));
  }

  /** Wicker/wood splinters thrown along `dir`, tumbling, then lying on the ground. */
  function splinters(point, dir, amount = 1) {
    if (!readPoint(point) || tooFar()) return;
    amount = clamp(+amount || 1, 0.1, 3);
    readDir(dir, 0.2, false);
    const count = n(4 + 8 * amount);
    for (let k = 0; k < count; k++) {
      const sp = R(1.0, 3.5);
      const vx = dx * sp + gauss() * 0.9;
      const vy = dy * sp + gauss() * 0.9 + R(0.8, 2.4);
      const vz = dz * sp + gauss() * 0.9;
      const shade = rng.next();
      // Freshly split willow is pale inside; the weathered outer skin is darker.
      const r = shade < 0.45 ? 0.78 : shade < 0.8 ? 0.6 : 0.4;
      const g = shade < 0.45 ? 0.66 : shade < 0.8 ? 0.47 : 0.29;
      const b = shade < 0.45 ? 0.42 : shade < 0.8 ? 0.27 : 0.16;
      const w = R(0.008, 0.016);
      const i = emit(streaks, SPLINTER, ox + R(-0.05, 0.05), oy + R(-0.05, 0.05), oz + R(-0.05, 0.05), vx, vy, vz,
        R(2.4, 3.6), w, w, r, g, b, 1, 0.9, 1);
      streaks.len[i] = R(0.04, 0.12);
      streaks.spin[i] = R(8, 26) * (rng.next() < 0.5 ? -1 : 1);
      // Random orthonormal pair (u, w): the splinter axis turns in their plane.
      let ux = gauss(), uy = gauss(), uz = gauss();
      let l = Math.hypot(ux, uy, uz) || 1;
      ux /= l;
      uy /= l;
      uz /= l;
      let wx = gauss(), wy = gauss(), wz = gauss();
      const d = wx * ux + wy * uy + wz * uz;
      wx -= d * ux;
      wy -= d * uy;
      wz -= d * uz;
      l = Math.hypot(wx, wy, wz) || 1;
      streaks.ux[i] = ux;
      streaks.uy[i] = uy;
      streaks.uz[i] = uz;
      streaks.wx[i] = wx / l;
      streaks.wy[i] = wy / l;
      streaks.wz[i] = wz / l;
    }
    softPuff(DUST, ox, oy, oz, dx * 0.5, 0.2, dz * 0.5, 0.7, 0.06, 0.38, 0.62, 0.55, 0.42, 0.2, 3, -0.03, 1.2, R(-1, 1));
  }

  /** A sea splash at the water surface: a column of drops, rising spray, spreading foam. */
  function splash(point, amount = 1) {
    if (!readPoint(point) || tooFar()) return;
    amount = clamp(+amount || 1, 0.3, 2.5);
    const sa = Math.sqrt(amount);
    const wy = oy;
    const drops = n(22 + 18 * amount);
    for (let k = 0; k < drops; k++) {
      const a = rng.next() * Math.PI * 2;
      const rad = R(0.2, 2.2);
      const w = R(0.02, 0.045);
      const i = emit(streaks, WATER, ox + Math.cos(a) * 0.2, wy + 0.05, oz + Math.sin(a) * 0.2,
        Math.cos(a) * rad * sa, R(2.5, 8) * sa, Math.sin(a) * rad * sa, 2.6, w, w, 0.82, 0.88, 0.92, 0.75, 0.5, 1);
      streaks.len[i] = 0.035;
      streaks.emit[i] = 1.4;
      streaks.flags[i] = FIXED_GROUND;
      streaks.gy[i] = wy - 0.05;
    }
    const spray = Math.max(3, n(5 + 5 * amount));
    for (let k = 0; k < spray; k++) {
      const a = rng.next() * Math.PI * 2;
      const column = k < 3; // a few rise high in the middle
      softPuff(SPRAY, ox + Math.cos(a) * 0.25, wy + R(0.1, 0.5), oz + Math.sin(a) * 0.25,
        Math.cos(a) * R(0.3, 0.9) * sa, (column ? R(4.5, 6.5) : R(1.5, 4.5)) * sa, Math.sin(a) * R(0.3, 0.9) * sa,
        R(0.9, 1.5), 0.2, R(0.8, 1.4) * sa, 0.92, 0.94, 0.96, 0.8, 1.3, column ? 0.8 : 0.55, 1.2, R(-0.8, 0.8));
    }
    const foam = Math.max(2, n(3 + 2 * amount));
    for (let k = 0; k < foam; k++) {
      const a = rng.next() * Math.PI * 2;
      softPuff(FOAM, ox + Math.cos(a) * 0.35, wy + 0.1, oz + Math.sin(a) * 0.35,
        Math.cos(a) * 0.4, 0, Math.sin(a) * 0.4,
        R(2.2, 3.2), 0.4, R(1.2, 2.0) * sa, 0.88, 0.92, 0.94, 0.65, 1.5, 0, 0.5, R(-0.3, 0.3));
    }
  }

  /** Pale steam wisps rising (e.g. from the hot springs). */
  function steam(point, amount = 1) {
    if (!readPoint(point) || tooFar()) return;
    const count = Math.max(1, n(2 * clamp(+amount || 1, 0.1, 3)));
    for (let k = 0; k < count; k++) wisp(STEAM, ox + R(-0.2, 0.2), oy + R(0, 0.2), oz + R(-0.2, 0.2), 0.85, 0.86, 0.84, 0.12);
  }

  // --- simulation -------------------------------------------------------------------------------

  const world = () => ctx.world;
  function ground(x, z) {
    const w = world();
    return w && w.groundHeight ? w.groundHeight(x, z) : 0;
  }

  function step(pool, gdt) {
    for (let i = pool.count - 1; i >= 0; i--) {
      const age = pool.age[i] + gdt;
      if (age >= pool.life[i]) {
        pool.remove(i);
        continue;
      }
      pool.age[i] = age;
      const flags = pool.flags[i];
      if (flags & RESTING) continue;
      const kind = pool.kind[i];
      if (kind === FLASH) continue;
      const damp = Math.exp(-pool.drag[i] * gdt);
      let vx = pool.vx[i] * damp;
      let vy = (pool.vy[i] - G * pool.grav[i] * gdt) * damp;
      let vz = pool.vz[i] * damp;
      const x = pool.px[i] + vx * gdt;
      let y = pool.py[i] + vy * gdt;
      const z = pool.pz[i] + vz * gdt;
      let gy;
      const moved = Math.abs(x - pool.gx[i]) + Math.abs(z - pool.gz[i]);
      if (flags & FIXED_GROUND) {
        gy = pool.gy[i];
      } else if (moved > 0.25 && (moved > 1.5 || y < pool.gy[i] + 1)) {
        // Re-sampled every 0.25 m near the ground; a particle a metre or more above its last
        // sample can't reach the ground within 1.5 m, so it skips the (allocating) call.
        gy = ground(x, z);
        pool.gx[i] = x;
        pool.gz[i] = z;
        pool.gy[i] = gy;
      } else {
        gy = pool.gy[i];
      }
      if (y <= gy) {
        if (kind === BLOOD) {
          const tag = pool.tag[i];
          if (tag >= 0 && budgets[tag] > 0 && gy > -2.9 && hooks.onBloodLand) {
            budgets[tag]--;
            hooks.onBloodLand(x, gy, z, pool.w0[i]);
          }
          pool.remove(i);
          continue;
        }
        if (kind === WATER || kind === GRIT) {
          pool.remove(i);
          continue;
        }
        if (kind === SPARK) {
          if ((flags & BOUNCED) || vy > -0.8) {
            pool.remove(i);
            continue;
          }
          pool.flags[i] = flags | BOUNCED;
          vy = -vy * 0.3;
          vx *= 0.55;
          vz *= 0.55;
          y = gy + 0.005;
        } else if (kind === SPLINTER) {
          // Freeze the current axis, flattened onto the ground.
          const ang = pool.seed[i] * 6.283 + pool.spin[i] * age;
          const c = Math.cos(ang);
          const s = Math.sin(ang);
          let lx = pool.ux[i] * c + pool.wx[i] * s;
          let lz = pool.uz[i] * c + pool.wz[i] * s;
          const l = Math.hypot(lx, lz);
          if (l < 1e-3) {
            lx = 1;
            lz = 0;
          } else {
            lx /= l;
            lz /= l;
          }
          pool.ux[i] = lx;
          pool.uy[i] = 0;
          pool.uz[i] = lz;
          pool.wx[i] = pool.wy[i] = pool.wz[i] = 0;
          pool.spin[i] = 0;
          pool.seed[i] = 0;
          pool.flags[i] = flags | RESTING;
          pool.px[i] = x;
          pool.py[i] = gy + 0.03; // clear of the coarser ground mesh between its vertices
          pool.pz[i] = z;
          pool.vx[i] = pool.vy[i] = pool.vz[i] = 0;
          continue;
        } else {
          // Soft puffs slide along the ground (or the water) instead of sinking into it.
          y = gy + 0.02;
          if (vy < 0) vy = 0;
        }
      }
      pool.vx[i] = vx;
      pool.vy[i] = vy;
      pool.vz[i] = vz;
      pool.px[i] = x;
      pool.py[i] = y;
      pool.pz[i] = z;
    }
  }

  // --- GPU upload -----------------------------------------------------------------------------

  function writeStreaks(pool, batch, additive) {
    const D = batch.data;
    const count = pool.count;
    for (let i = 0; i < count; i++) {
      const kind = pool.kind[i];
      const age = pool.age[i];
      const life = pool.life[i];
      const u = age / life;
      let px = pool.px[i];
      let py = pool.py[i];
      let pz = pool.pz[i];
      let lx = 0, ly = 0, lz = 0;
      let w = pool.w0[i];
      let r = pool.r[i], g = pool.g[i], b = pool.b[i];
      let a = pool.a[i];
      let q = pool.emit[i];
      if (kind === SPARK) {
        // Cooling: yellow-white → orange → dull red (kept saturated so tone mapping keeps the hue).
        const heat = 1 - u;
        r = 1;
        g = 0.1 + 0.36 * heat * heat;
        b = 0.015 + 0.07 * heat * heat * heat;
        const k = pool.emit[i] * Math.pow(heat, 1.3);
        r *= k;
        g *= k;
        b *= k;
        w *= 1 - 0.4 * u;
        const s = pool.len[i];
        lx = pool.vx[i] * s;
        ly = pool.vy[i] * s;
        lz = pool.vz[i] * s;
        q = 0;
      } else if (kind === FLASH) {
        const k = pool.emit[i] * (1 - u) * (1 - u);
        r *= k;
        g *= k;
        b *= k;
        w = pool.w0[i] + (pool.w1[i] - pool.w0[i]) * Math.sqrt(u);
        q = 0;
      } else if (kind === SPLINTER) {
        const ang = pool.seed[i] * 6.283 + pool.spin[i] * age;
        const c = Math.cos(ang);
        const s = Math.sin(ang);
        const len = pool.len[i];
        lx = (pool.ux[i] * c + pool.wx[i] * s) * len;
        ly = (pool.uy[i] * c + pool.wy[i] * s) * len;
        lz = (pool.uz[i] * c + pool.wz[i] * s) * len;
        px += lx * 0.5;
        py += ly * 0.5;
        pz += lz * 0.5;
        a *= Math.min(1, (life - age) / 0.6);
      } else {
        const s = pool.len[i];
        lx = pool.vx[i] * s;
        ly = pool.vy[i] * s;
        lz = pool.vz[i] * s;
        const l2 = lx * lx + ly * ly + lz * lz;
        if (l2 > 0.0625) {
          const k = 0.25 / Math.sqrt(l2);
          lx *= k;
          ly *= k;
          lz *= k;
        }
        a *= Math.min(1, (life - age) / 0.25);
      }
      // iPos 0-2, iAxis 3-5, iCol 6-9, iPar 10-11
      const o = i * STRIDE;
      D[o] = px;
      D[o + 1] = py;
      D[o + 2] = pz;
      D[o + 3] = lx;
      D[o + 4] = ly;
      D[o + 5] = lz;
      D[o + 6] = r;
      D[o + 7] = g;
      D[o + 8] = b;
      D[o + 9] = additive ? Math.min(1, a) : a;
      D[o + 10] = w;
      D[o + 11] = q;
    }
    finish(batch, count);
  }

  function writeSoft(pool, batch) {
    const D = batch.data;
    const count = pool.count;
    for (let i = 0; i < count; i++) {
      const kind = pool.kind[i];
      const u = pool.age[i] / pool.life[i];
      const e = 1 - (1 - u) * (1 - u);
      const radius = pool.w0[i] + (pool.w1[i] - pool.w0[i]) * e;
      const fadeIn = kind === WISP || kind === STEAM ? 0.25 : kind === FOAM ? 0.12 : 0.06;
      const inA = u < fadeIn ? u / fadeIn : 1;
      const outA = Math.pow(1 - u, kind === MIST ? 2 : 1.4);
      // iPos 0-2, iSize 3-4, iCol 5-8, iPar 9-11
      const o = i * STRIDE;
      D[o] = pool.px[i];
      D[o + 1] = pool.py[i];
      D[o + 2] = pool.pz[i];
      D[o + 3] = radius;
      D[o + 4] = pool.seed[i] * 6.283 + pool.spin[i] * pool.age[i];
      D[o + 5] = pool.r[i];
      D[o + 6] = pool.g[i];
      D[o + 7] = pool.b[i];
      D[o + 8] = pool.a[i] * inA * outA;
      D[o + 9] = Math.floor(pool.seed[i] * 4) % 4;
      D[o + 10] = pool.emit[i];
      D[o + 11] = pool.gy[i] > -1e8 ? pool.gy[i] : -1e4;
    }
    finish(batch, count);
  }

  function finish(batch, count) {
    batch.geom.instanceCount = count;
    batch.mesh.visible = count > 0;
    // One whole-buffer upload (≤ 21 KB). Update ranges would allocate a range object per call.
    if (count > 0) batch.buffer.needsUpdate = true;
  }

  return {
    blood,
    sparks: sparksFn,
    dust,
    splinters,
    splash,
    steam,
    uniforms: shared,
    meshes: [bStreak.mesh, bSoft.mesh, bSpark.mesh],
    get count() {
      return streaks.count + sparks.count + soft.count;
    },
    /** Pool sizes, for tests: [streaks, sparks, soft]. */
    counts(out) {
      out[0] = streaks.count;
      out[1] = sparks.count;
      out[2] = soft.count;
      return out;
    },
    /** Advances with game time and uploads what changed. */
    update(gdt) {
      if (gdt > 0) {
        step(streaks, gdt);
        step(sparks, gdt);
        step(soft, gdt);
        dirty = true;
      }
      if (renderer && renderer.getDrawingBufferSize) renderer.getDrawingBufferSize(shared.uRes.value);
      if (!dirty) return;
      dirty = false;
      writeStreaks(streaks, bStreak, false);
      writeStreaks(sparks, bSpark, true);
      writeSoft(soft, bSoft);
    },
    reset() {
      streaks.clear();
      sparks.clear();
      soft.clear();
      budgets.fill(0);
      dirty = true;
    },
  };
}
