/**
 * Sky and atmosphere: the Preetham `Sky`, a CPU port of its scattering model (so fog, sun and
 * hemisphere colours match the rendered sky exactly), time-of-day presets, and the PMREM
 * environment map captured from a sky scene with the sun disc hidden (DESIGN.md §11).
 */
import * as THREE from 'three';
import { Sky } from 'three/addons/Sky.js';
import { clamp, lerp } from '../../core/math.js';

const DEG = Math.PI / 180;

/**
 * Time-of-day presets. The pass runs east–west: −Z is west (the Persians), −X south (the
 * mountain), +X north (the gulf). Sun azimuths are nudged so the cliff never shades the line.
 * az: horizontal direction toward the sun (x, z). Intensities are artistic multipliers.
 */
/** The Sky shader's radiance is scaled by this so the scene sits at ordinary light levels. */
export const SKY_SCALE = 0.2;
/** Colour saturation of the sky as captured into the environment map. */
export const ENV_SATURATION = 0.5;

export const PRESETS = {
  dawn: { elev: 3.5, az: [0.5, 0.87], turbidity: 6.5, rayleigh: 2.6, mie: 0.0062, mieG: 0.85, exposure: 2.4, fog: 0.0009, sun: 2.6, hemi: 0.32, env: 0.85, clouds: 0.12, cloudDensity: 0.45, haze: 0.9 },
  morning: { elev: 22, az: [0.36, 0.93], turbidity: 4.4, rayleigh: 1.5, mie: 0.0048, mieG: 0.82, exposure: 1.1, fog: 0.00072, sun: 3.3, hemi: 0.2, env: 0.5, clouds: 0, cloudDensity: 0.38, haze: 0.75 },
  noon: { elev: 58, az: [0.5, 0.35], turbidity: 3.6, rayleigh: 1.2, mie: 0.0042, mieG: 0.8, exposure: 0.92, fog: 0.00068, sun: 3.6, hemi: 0.2, env: 0.55, clouds: 0, cloudDensity: 0.35, haze: 0.7 },
  afternoon: { elev: 30, az: [0.66, -0.75], turbidity: 4.6, rayleigh: 1.45, mie: 0.0052, mieG: 0.83, exposure: 1.02, fog: 0.00075, sun: 3.4, hemi: 0.2, env: 0.52, clouds: 0, cloudDensity: 0.38, haze: 0.8 },
  dusk: { elev: 4, az: [0.42, -0.91], turbidity: 8, rayleigh: 2.8, mie: 0.0066, mieG: 0.86, exposure: 2.0, fog: 0.00095, sun: 3.0, hemi: 0.32, env: 0.85, clouds: 0.12, cloudDensity: 0.45, haze: 1 },
};

// Constants of three's Sky shader.
const TOTAL_RAYLEIGH = [5.804542996261093e-6, 1.3562911419845635e-5, 3.0265902468824876e-5];
const MIE_CONST = [1.8399918514433978e14, 2.7798023919660528e14, 4.0790479543861094e14];
const CUTOFF = 1.6110731556870734;
const STEEPNESS = 1.5;
const EE = 1000;

/** Sun direction (unit, toward the sun) for a preset. */
export function sunDirectionFor(p, out = [0, 0, 0]) {
  const e = p.elev * DEG;
  const l = Math.hypot(p.az[0], p.az[1]);
  out[0] = (Math.cos(e) * p.az[0]) / l;
  out[1] = Math.sin(e);
  out[2] = (Math.cos(e) * p.az[1]) / l;
  return out;
}

/** Per-sun scattering coefficients, exactly as the Sky vertex shader computes them. */
export function skyCoefficients(sun, turbidity, rayleigh, mie) {
  const zc = clamp(sun[1], -1, 1);
  const sunE = EE * Math.max(0, 1 - Math.exp(-((CUTOFF - Math.acos(zc)) / STEEPNESS)));
  const sunfade = 1 - clamp(1 - Math.exp(sun[1] / 450000), 0, 1);
  const rc = rayleigh - (1 - sunfade);
  const c = 0.2 * turbidity * 10e-18;
  return {
    sunE,
    betaR: TOTAL_RAYLEIGH.map((v) => v * rc),
    betaM: MIE_CONST.map((v) => 0.434 * c * v * mie),
  };
}

/** Sky radiance (linear, pre-exposure) in a world direction; the Sky fragment shader without clouds or sun disc. */
export function skyRadiance(dir, sun, coef, mieG, out = [0, 0, 0]) {
  const za = Math.acos(Math.max(0, dir[1]));
  const inv = 1 / (Math.cos(za) + 0.15 * Math.pow(93.885 - za / DEG, -1.253));
  const sR = 8.4e3 * inv;
  const sM = 1.25e3 * inv;
  const ct = dir[0] * sun[0] + dir[1] * sun[1] + dir[2] * sun[2];
  const x = ct * 0.5 + 0.5;
  const rp = 0.05968310365946075 * (1 + x * x);
  const g2 = mieG * mieG;
  const mp = (0.07957747154594767 * (1 - g2)) / Math.pow(1 - 2 * mieG * ct + g2, 1.5);
  const fade = clamp(Math.pow(1 - sun[1], 5), 0, 1);
  const add = [0, 0.0003, 0.00075];
  for (let i = 0; i < 3; i++) {
    const bR = coef.betaR[i];
    const bM = coef.betaM[i];
    const fex = Math.exp(-(bR * sR + bM * sM));
    const base = (coef.sunE * (bR * rp + bM * mp)) / (bR + bM);
    let lin = Math.pow(base * (1 - fex), 1.5);
    lin *= lerp(1, Math.pow(base * fex, 0.5), fade);
    out[i] = ((lin + 0.1 * fex) * 0.04 + add[i]) * SKY_SCALE;
  }
  return out;
}

/** Atmospheric transmittance toward a direction (the colour of direct sunlight). */
export function transmittance(dir, coef, out = [0, 0, 0]) {
  const za = Math.acos(Math.max(0, dir[1]));
  const inv = 1 / (Math.cos(za) + 0.15 * Math.pow(93.885 - za / DEG, -1.253));
  for (let i = 0; i < 3; i++) out[i] = Math.exp(-(coef.betaR[i] * 8.4e3 * inv + coef.betaM[i] * 1.25e3 * inv));
  return out;
}

/** three's ACESFilmicToneMapping on the CPU (linear in, linear display-referred out). */
export function acesFilmic(c, exposure, out = [0, 0, 0]) {
  const s = exposure / 0.6;
  const r = c[0] * s;
  const g = c[1] * s;
  const b = c[2] * s;
  // ACESInputMat (column-major in GLSL).
  const ir = 0.59719 * r + 0.35458 * g + 0.04823 * b;
  const ig = 0.076 * r + 0.90834 * g + 0.01566 * b;
  const ib = 0.0284 * r + 0.13383 * g + 0.83777 * b;
  const fit = (v) => (v * (v + 0.0245786) - 0.000090537) / (v * (0.983729 * v + 0.432951) + 0.238081);
  const fr = fit(ir);
  const fg = fit(ig);
  const fb = fit(ib);
  out[0] = clamp(1.60475 * fr - 0.53108 * fg - 0.07367 * fb, 0, 1);
  out[1] = clamp(-0.10208 * fr + 1.10813 * fg - 0.00605 * fb, 0, 1);
  out[2] = clamp(-0.00327 * fr - 0.07276 * fg + 1.07602 * fb, 0, 1);
  return out;
}

/** GLSL twin of skyRadiance, used by the sky-matched fog on world materials. */
export const SKY_GLSL = /* glsl */ `
uniform vec3 wSunDir;
uniform vec3 wBetaR;
uniform vec3 wBetaM;
uniform float wSunE;
uniform float wMieG;
vec3 wSkyRadiance( vec3 dir ) {
  float za = acos( max( 0.0, dir.y ) );
  float inv = 1.0 / ( cos( za ) + 0.15 * pow( 93.885 - za * 57.2957795, -1.253 ) );
  vec3 fex = exp( -( wBetaR * ( 8.4e3 * inv ) + wBetaM * ( 1.25e3 * inv ) ) );
  float ct = dot( dir, wSunDir );
  float x = ct * 0.5 + 0.5;
  float rp = 0.0596831037 * ( 1.0 + x * x );
  float g2 = wMieG * wMieG;
  float mp = 0.0795774715 * ( 1.0 - g2 ) / pow( 1.0 - 2.0 * wMieG * ct + g2, 1.5 );
  vec3 base = wSunE * ( wBetaR * rp + wBetaM * mp ) / ( wBetaR + wBetaM );
  vec3 lin = pow( base * ( 1.0 - fex ), vec3( 1.5 ) );
  lin *= mix( vec3( 1.0 ), pow( base * fex, vec3( 0.5 ) ), clamp( pow( 1.0 - wSunDir.y, 5.0 ), 0.0, 1.0 ) );
  return ( ( lin + 0.1 * fex ) * 0.04 + vec3( 0.0, 0.0003, 0.00075 ) ) * ${SKY_SCALE.toFixed(4)};
}
`;

/**
 * A Preetham Sky whose output is multiplied by SKY_SCALE (patched once, before compilation).
 * With `saturation` < 1 (the environment-capture copy) the colour is pulled toward its own
 * luminance: Preetham skylight is far bluer than real skylight, and lighting with it turns every
 * shadow cyan.
 */
function scaledSky(saturation = 1) {
  const s = new Sky();
  const m = s.material;
  const out = saturation < 1
    ? `vec3 skyC = texColor * ${SKY_SCALE.toFixed(4)};
      gl_FragColor = vec4( mix( vec3( dot( skyC, vec3( 0.2126, 0.7152, 0.0722 ) ) ), skyC, ${saturation.toFixed(3)} ), 1.0 );`
    : `gl_FragColor = vec4( texColor * ${SKY_SCALE.toFixed(4)}, 1.0 );`;
  m.fragmentShader = m.fragmentShader.replace('gl_FragColor = vec4( texColor, 1.0 );', out);
  if (!m.fragmentShader.includes(`* ${SKY_SCALE.toFixed(4)}`)) console.warn('world: Sky shader patch did not apply');
  return s;
}

/**
 * Creates the sky meshes, the env-capture scene and the shared atmosphere uniforms.
 * @returns {object} sky system; see the returned fields.
 */
export function createSky(ctx) {
  const { renderer } = ctx;
  const quality = ctx.quality || 'medium';

  const sky = scaledSky();
  sky.name = 'sky';
  sky.scale.setScalar(3000);
  sky.frustumCulled = false;
  // Drawn after all opaque geometry: its depth is the far plane, so only uncovered pixels shade.
  sky.renderOrder = 100000;
  sky.matrixAutoUpdate = false;
  sky.updateMatrix();

  // Environment capture: a second Sky without the sun disc, plus coarse proxies of the ground,
  // the cliff and the sea so bronze reflects the place instead of an endless horizon.
  const envScene = new THREE.Scene();
  const envSky = scaledSky(ENV_SATURATION);
  envSky.scale.setScalar(900);
  envSky.material.uniforms.showSunDisc.value = 0;
  envScene.add(envSky);
  const proxyMat = (c) => new THREE.MeshBasicMaterial({ color: c, side: THREE.DoubleSide, fog: false });
  const groundMat = proxyMat(0x7a6a55);
  const ground = new THREE.Mesh(new THREE.CircleGeometry(700, 32).rotateX(-Math.PI / 2), groundMat);
  ground.position.y = -1.7;
  envScene.add(ground);
  const seaMat = proxyMat(0x203040);
  const sea = new THREE.Mesh(new THREE.PlaneGeometry(690, 1400).rotateX(-Math.PI / 2), seaMat);
  sea.position.set(9.5 + 345, -1.65, 0);
  envScene.add(sea);
  const cliffMat = proxyMat(0x908878);
  const cliff = new THREE.Mesh(new THREE.PlaneGeometry(1400, 160).rotateY(Math.PI / 2), cliffMat);
  cliff.position.set(-9.5, 78, 0);
  envScene.add(cliff);

  let pmrem = new THREE.PMREMGenerator(renderer);
  let envRT = null;
  const envSize = quality === 'high' ? 256 : 128;

  // Shared uniforms of the sky-matched fog (patched world materials reference these objects).
  const fogUniforms = {
    wSunDir: { value: new THREE.Vector3(0, 1, 0) },
    wBetaR: { value: new THREE.Vector3() },
    wBetaM: { value: new THREE.Vector3() },
    wSunE: { value: 0 },
    wMieG: { value: 0.8 },
  };

  const state = {
    sunDir: [0, 1, 0],
    coef: null,
    preset: PRESETS.morning,
    sunColor: new THREE.Color(),
    sunIntensity: 3,
    hemiSky: new THREE.Color(),
    hemiGround: new THREE.Color(),
    fogColor: new THREE.Color(),
    horizon: new THREE.Color(),
    skyAvg: [0, 0, 0],
  };

  function applyUniforms(s, p, sun) {
    const u = s.material.uniforms;
    u.turbidity.value = p.turbidity;
    u.rayleigh.value = p.rayleigh;
    u.mieCoefficient.value = p.mie;
    u.mieDirectionalG.value = p.mieG;
    u.sunPosition.value.set(sun[0], sun[1], sun[2]);
    u.cloudCoverage.value = quality === 'low' ? 0 : p.clouds;
    u.cloudDensity.value = p.cloudDensity;
    u.cloudElevation.value = 0.75;
    u.cloudScale.value = 0.0003;
    u.cloudSpeed.value = 0.00002;
  }

  /** Recomputes everything derived from a preset (no GPU work except uniforms). */
  function compute(p) {
    const sun = sunDirectionFor(p, state.sunDir);
    const coef = skyCoefficients(sun, p.turbidity, p.rayleigh, p.mie);
    state.coef = coef;
    state.preset = p;
    applyUniforms(sky, p, sun);
    applyUniforms(envSky, p, sun);
    fogUniforms.wSunDir.value.set(sun[0], sun[1], sun[2]);
    fogUniforms.wBetaR.value.fromArray(coef.betaR);
    fogUniforms.wBetaM.value.fromArray(coef.betaM);
    fogUniforms.wSunE.value = coef.sunE;
    fogUniforms.wMieG.value = p.mieG;

    // Direct sun: transmittance along the sun ray (golden at low elevations).
    // Raised to a power < 1 so a very low sun stays golden rather than blood red.
    const tr = transmittance(sun, coef).map((v) => Math.pow(v, 0.38));
    const tmax = Math.max(tr[0], tr[1], tr[2], 1e-6);
    state.sunColor.setRGB(tr[0] / tmax, tr[1] / tmax, tr[2] / tmax, THREE.LinearSRGBColorSpace);
    state.sunIntensity = p.sun;

    // Average sky radiance over the upper hemisphere (for the hemisphere light and proxies).
    const avg = [0, 0, 0];
    const tmp = [0, 0, 0];
    let n = 0;
    for (let i = 0; i < 6; i++) {
      const el = (8 + i * 14) * DEG;
      for (let j = 0; j < 12; j++) {
        const a = (j / 12) * Math.PI * 2;
        const w = Math.sin(el + 0.1);
        skyRadiance([Math.cos(el) * Math.cos(a), Math.sin(el), Math.cos(el) * Math.sin(a)], sun, coef, p.mieG, tmp);
        avg[0] += tmp[0] * w;
        avg[1] += tmp[1] * w;
        avg[2] += tmp[2] * w;
        n += w;
      }
    }
    avg[0] /= n;
    avg[1] /= n;
    avg[2] /= n;
    state.skyAvg = avg;
    const hl = Math.max(avg[0], avg[1], avg[2], 1e-6);
    state.hemiSky.setRGB(lerp(1, avg[0] / hl, 0.35), lerp(1, avg[1] / hl, 0.35), lerp(1, avg[2] / hl, 0.35), THREE.LinearSRGBColorSpace);

    // Ground bounce: dusty limestone soil lit by sun and sky.
    const albedo = [0.36, 0.3, 0.22];
    const eSun = state.sunIntensity * Math.max(0, sun[1]);
    const eSky = Math.PI * (avg[0] + avg[1] + avg[2]) / 3;
    const g = [0, 0, 0];
    for (let i = 0; i < 3; i++) g[i] = (albedo[i] * (eSun * state.sunColor.toArray()[i] + eSky * 1.6)) / Math.PI;
    groundMat.color.setRGB(g[0], g[1], g[2], THREE.LinearSRGBColorSpace);
    const gl = Math.max(g[0], g[1], g[2], 1e-6);
    state.hemiGround.setRGB(g[0] / gl, g[1] / gl, g[2] / gl, THREE.LinearSRGBColorSpace);
    // Cliff face (faces +X): lit by the sun when it is over the gulf.
    const rock = [0.5, 0.46, 0.4];
    const eCliff = state.sunIntensity * Math.max(0, sun[0]) * 0.8 + eSky;
    cliffMat.color.setRGB((rock[0] * eCliff) / Math.PI, (rock[1] * eCliff) / Math.PI, (rock[2] * eCliff) / Math.PI, THREE.LinearSRGBColorSpace);
    // Fog colour for unpatched materials: the tone-mapped forward horizon (fog mixes after tone mapping).
    const hz = skyRadiance([0, 0.03, -1], sun, coef, p.mieG, [0, 0, 0]);
    // Sea seen at a grazing angle mirrors the horizon; water reflections that dip below the
    // horizon must not pick up something darker.
    const hzR = skyRadiance([1, 0.02, 0], sun, coef, p.mieG, [0, 0, 0]);
    seaMat.color.setRGB(hzR[0] * 0.8, hzR[1] * 0.82, hzR[2] * 0.85, THREE.LinearSRGBColorSpace);
    state.horizon.setRGB(hz[0], hz[1], hz[2], THREE.LinearSRGBColorSpace);
    const tm = acesFilmic(hz, p.exposure);
    state.fogColor.setRGB(tm[0], tm[1], tm[2], THREE.LinearSRGBColorSpace);
    return state;
  }

  /** Captures the PMREM environment (call after compute). Disposes the previous one. */
  function captureEnvironment() {
    const old = envRT;
    envRT = pmrem.fromScene(envScene, 0, 0.1, 1000, { size: envSize });
    envRT.texture.name = 'worldEnv';
    if (old) old.dispose();
    return envRT.texture;
  }

  /** After a WebGL context restore: fresh generator and capture. */
  function rebuild() {
    try {
      pmrem.dispose();
    } catch {
      /* context was lost; nothing to free */
    }
    pmrem = new THREE.PMREMGenerator(renderer);
    if (envRT) {
      try {
        envRT.dispose();
      } catch {
        /* ignore */
      }
      envRT = null;
    }
    return captureEnvironment();
  }

  return {
    sky,
    envSky,
    fogUniforms,
    state,
    compute,
    captureEnvironment,
    rebuild,
    get envTexture() {
      return envRT ? envRT.texture : null;
    },
  };
}
