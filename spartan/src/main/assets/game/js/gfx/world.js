/**
 * The world (DESIGN.md §11): the narrow coastal pass at Thermopylae, 480 BC. Kallidromos on the
 * left (−X), the Malian Gulf on the right (+X), the Phocian Wall behind (+Z) and Xerxes' army far
 * down the pass (−Z), under a Preetham sky with matching haze, sun and environment light.
 *
 * Sub-builders live in ./world/*.js. Nothing here changes program-affecting state after
 * construction: setTimeOfDay and the volley shade only touch colours, intensities and uniforms.
 */
import * as THREE from 'three';
import { damp } from '../core/math.js';
import { createLayout, WALL, CLIFF_X } from './world/layout.js';
import { createSky, PRESETS } from './world/sky.js';
import { patchMaterial } from './world/shaders.js';
import { CUTOUT_ALPHA } from './textures.js';
import { buildTerrain } from './world/terrain.js';
import { buildCliff } from './world/cliff.js';
import { buildSeaGeometry, makeWaterMaterial, buildFoamGeometry, buildMountainGeometry } from './world/sea.js';
import { buildRocks, buildVegetation, buildWall, buildSprings, makeSteamMaterial } from './world/props.js';
import { buildHorde } from './world/horde.js';

export { PRESETS as TIME_PRESETS };

const SHADOW_HALF = 18;
/** Volley shade at full strength: fractions taken off the sun, its shadows, sky light, env and exposure. */
export const SHADE = Object.freeze({ sun: 0.85, shadow: 0.75, hemi: 0.18, env: 0.15, exposure: 0.2 });

/**
 * @param {object} ctx §4 ctx (scene, renderer, camera, events, textures, quality)
 */
export function createWorld(ctx) {
  const { scene, renderer, events } = ctx;
  const quality = ctx.quality || 'medium';
  const tex = ctx.textures;
  const layout = createLayout(480);
  const skySys = createSky(ctx);
  const time = { value: 0 };
  const root = new THREE.Group();
  root.name = 'world';
  root.matrixAutoUpdate = false;
  scene.add(root);

  const patch = (mat, opts) => patchMaterial(mat, { ...opts, fog: opts.fog ? { uniforms: skySys.fogUniforms, ...opts.fog } : undefined });
  const meanLum = (t) => (t && t.userData && t.userData.meanLum) || 0.3;

  // --- Atmosphere and light ---
  scene.fog = new THREE.FogExp2(0xb8c4cc, 0.0011);
  root.add(skySys.sky);
  const hemi = new THREE.HemisphereLight(0xdde8ff, 0x8a7458, 0.55);
  hemi.name = 'worldHemi';
  const sun = new THREE.DirectionalLight(0xffffff, 3);
  sun.name = 'worldSun';
  sun.castShadow = true;
  const mapSize = quality === 'high' ? 2048 : 1024;
  sun.shadow.mapSize.set(mapSize, mapSize);
  Object.assign(sun.shadow.camera, { left: -SHADOW_HALF, right: SHADOW_HALF, top: SHADOW_HALF, bottom: -SHADOW_HALF, near: 1, far: 220 });
  sun.shadow.camera.updateProjectionMatrix();
  sun.shadow.bias = -0.00025;
  // Enough normal offset that faces lit at a grazing angle (the cliff under a low sun) don't
  // self-shadow in triangle-shaped patches.
  sun.shadow.normalBias = quality === 'high' ? 0.055 : 0.07;
  sun.shadow.radius = 1.5;
  scene.add(hemi, sun, sun.target);

  // --- Materials ---
  const dirt = tex.get('dirt');
  const terrainMat = new THREE.MeshStandardMaterial({
    name: 'terrain', map: dirt, normalMap: tex.get('dirtNormal'), roughness: 1, metalness: 0, vertexColors: true,
  });
  terrainMat.normalScale.set(1.15, 1.15);
  patch(terrainMat, {
    key: 'terrain', fog: {}, ao: true,
    ground: { mean: meanLum(dirt), splatMap: tex.get('sand'), splatNormal: tex.get('sandNormal'), splatScale: 1.6, antiTile: 0.7 },
  });
  const cliffTex = tex.get('cliffStrata');
  const cliffMat = new THREE.MeshStandardMaterial({
    name: 'cliff', map: cliffTex, normalMap: tex.get('cliffNormal'), roughness: 0.94, metalness: 0, vertexColors: true,
  });
  cliffMat.normalScale.set(1.3, 1.3);
  patch(cliffMat, { key: 'cliff', fog: {}, ao: true, ground: { mean: meanLum(cliffTex), antiTile: 0.45 } });
  const rockMat = new THREE.MeshStandardMaterial({
    name: 'rock', map: tex.get('rock'), normalMap: tex.get('rockNormal'), roughness: 0.9, metalness: 0, vertexColors: true,
  });
  patch(rockMat, { key: 'rock', fog: {}, ao: true, aoDirect: 0.45 });
  const wallMat = new THREE.MeshStandardMaterial({
    name: 'wall', map: tex.get('stone'), normalMap: tex.get('stoneNormal'), roughness: 0.92, metalness: 0, vertexColors: true,
  });
  wallMat.normalScale.set(1.4, 1.4);
  patch(wallMat, { key: 'wall', fog: {}, ao: true });
  const a2c = quality !== 'low';
  const grassMat = new THREE.MeshStandardMaterial({
    name: 'grass', map: tex.get('grass'), alphaTest: CUTOUT_ALPHA, alphaToCoverage: a2c, side: THREE.DoubleSide, roughness: 1, metalness: 0, vertexColors: true,
  });
  patch(grassMat, { key: 'grass', fog: {}, ao: true, foliage: true, wind: { time, amp: 1 } });
  const scrubMat = new THREE.MeshStandardMaterial({
    name: 'scrub', map: tex.get('scrub'), alphaTest: CUTOUT_ALPHA, alphaToCoverage: a2c, side: THREE.DoubleSide, roughness: 0.95, metalness: 0, vertexColors: true,
  });
  patch(scrubMat, { key: 'scrub', fog: {}, ao: true, foliage: true, wind: { time, amp: 0.45 } });
  const seaMat = makeWaterMaterial(tex.get('waterNormal'), time, patch);
  const foamTex = tex.get('foam').clone();
  foamTex.needsUpdate = true;
  const foamMat = new THREE.MeshStandardMaterial({
    name: 'foam', map: foamTex, transparent: true, depthWrite: false, roughness: 0.7, metalness: 0,
    polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -2,
  });
  patch(foamMat, { key: 'foam', fog: {} });
  const mountainMat = new THREE.MeshStandardMaterial({ name: 'mountains', vertexColors: true, roughness: 1, metalness: 0 });
  patch(mountainMat, { key: 'mountains', fog: { scale: 0.85 } });
  const poolMat = new THREE.MeshStandardMaterial({
    name: 'springPool', color: 0x6fa59c, roughness: 0.12, metalness: 0, normalMap: tex.get('waterNormal'), vertexColors: true,
  });
  poolMat.normalScale.set(0.12, 0.12);
  patch(poolMat, { key: 'pool', fog: {} });
  const crustMat = new THREE.MeshStandardMaterial({
    name: 'springCrust', map: tex.get('sulphur'), transparent: true, depthWrite: false, roughness: 0.9, metalness: 0, vertexColors: true,
    polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -4,
  });
  patch(crustMat, { key: 'crust', fog: {} });
  const lit = {
    light: { value: new THREE.Color(1, 1, 1) },
    shade: { value: new THREE.Color(0.6, 0.65, 0.7) },
    sunDir: { value: new THREE.Vector3(0, 1, 0) },
    shaft: { value: new THREE.Color(0.1, 0.07, 0.05) },
    tip: { value: new THREE.Color(0.5, 0.48, 0.45) },
    dust: { value: new THREE.Color(0.6, 0.5, 0.4) },
    dustAlpha: { value: 0.25 },
  };
  const steamMat = makeSteamMaterial(tex.get('smoke'), { time, light: lit.light, shade: lit.shade, sunDir: lit.sunDir });

  // --- Geometry ---
  const terrain = buildTerrain(layout, terrainMat);
  root.add(terrain.mesh);
  const cliff = buildCliff(layout, cliffMat);
  for (const m of cliff.meshes) root.add(m);
  const sea = new THREE.Mesh(buildSeaGeometry(layout), seaMat);
  sea.name = 'sea';
  sea.renderOrder = -10;
  const foam = new THREE.Mesh(buildFoamGeometry(layout), foamMat);
  foam.name = 'foam';
  foam.renderOrder = -9;
  const mountains = new THREE.Mesh(buildMountainGeometry(layout), mountainMat);
  mountains.name = 'mountains';
  for (const m of [sea, foam, mountains]) {
    m.matrixAutoUpdate = false;
    m.updateMatrix();
    m.receiveShadow = m !== mountains;
    root.add(m);
  }
  const wall = buildWall(layout, wallMat);
  const rocks = buildRocks(layout, rockMat, quality, wall.stones);
  const veg = buildVegetation(layout, grassMat, scrubMat, cliff, quality);
  const springs = buildSprings(layout, poolMat, crustMat, steamMat, quality);
  for (const m of [rocks.near, rocks.far, veg.grass, veg.scrub, wall.mesh, springs.pools, springs.crust, springs.steam]) if (m) root.add(m);
  const horde = buildHorde(layout, { patch, time, quality, dustMap: tex.get('cloud'), lit });
  root.add(horde.group);

  const colliders = [
    { type: 'plane', point: [CLIFF_X, 0, 0], normal: [1, 0, 0] },
    ...wall.colliders,
    ...rocks.colliders,
  ];

  // --- Time of day ---
  const sunDirection = new THREE.Vector3(0, 1, 0);
  const base = { sunI: 3, hemiI: 0.55, exposure: 0.5, env: 0.75 };
  let phase = 'morning';
  let shade = 0;
  // Volleys in the air, as expiry times on the world clock: overlapping volleys keep the shade
  // until the last one ends, and one whose 'end' never comes (a reset mid-volley) times out.
  const volleys = [];

  // The volley shade: the arrows hide the sun (direct light down to 15%, its shadows almost gone)
  // and the whole view, sky included, drops a fifth in exposure, so the player sees the day go
  // dark under the cloud rather than reading it off a banner.
  function applyLight() {
    const s = shade;
    sun.intensity = base.sunI * (1 - SHADE.sun * s);
    sun.shadow.intensity = 1 - SHADE.shadow * s;
    hemi.intensity = base.hemiI * (1 - SHADE.hemi * s);
    scene.environmentIntensity = base.env * (1 - SHADE.env * s);
    renderer.toneMappingExposure = base.exposure * (1 - SHADE.exposure * s);
  }

  function setTimeOfDay(name) {
    const key = Object.prototype.hasOwnProperty.call(PRESETS, name) ? name : 'morning';
    if (key !== name) console.warn(`world: unknown time of day "${name}"`);
    phase = key;
    const p = PRESETS[key];
    const st = skySys.compute(p);
    sunDirection.set(st.sunDir[0], st.sunDir[1], st.sunDir[2]);
    sun.color.copy(st.sunColor);
    hemi.color.copy(st.hemiSky);
    hemi.groundColor.copy(st.hemiGround);
    base.sunI = st.sunIntensity;
    base.hemiI = p.hemi;
    base.exposure = p.exposure;
    base.env = p.env;
    scene.fog.color.copy(st.fogColor);
    scene.fog.density = p.fog;
    // Unlit pieces (spears, dust, steam) take colours from the same light.
    const a = st.skyAvg;
    const sc = st.sunColor;
    const li = st.sunIntensity;
    const lr = sc.r * li * 0.32 + a[0] * 2.2;
    const lg = sc.g * li * 0.32 + a[1] * 2.2;
    const lb = sc.b * li * 0.32 + a[2] * 2.2;
    lit.shaft.value.setRGB(0.09 * lr, 0.065 * lg, 0.045 * lb);
    lit.tip.value.setRGB(0.2 * lr, 0.19 * lg, 0.17 * lb);
    lit.dust.value.setRGB(0.3 * lr, 0.25 * lg, 0.19 * lb);
    lit.dustAlpha.value = 0.08 + p.haze * 0.08;
    lit.light.value.setRGB(0.9 * lr, 0.9 * lg, 0.9 * lb);
    lit.shade.value.setRGB(a[0] * 3, a[1] * 3, a[2] * 3);
    lit.sunDir.value.copy(sunDirection);
    const env = skySys.captureEnvironment();
    scene.environment = env;
    // Water mirrors the sky at full strength, independent of the ambient env intensity.
    seaMat.envMap = env;
    poolMat.envMap = env;
    applyLight();
    followShadow(true);
  }

  // --- Volley: "then we shall fight in the shade" ---
  events.on('volley', (e) => {
    if (!e) return;
    if (e.phase === 'loosed') {
      if (volleys.length < 8) volleys.push(time.value + 2.0 + (Number.isFinite(e.duration) ? e.duration : 1) + 0.75);
    } else if (e.phase === 'end') {
      if (volleys.length) volleys.shift();
    }
  });
  // Training hides the army and doesn't bring it back; every day of battle starts with it.
  events.on('dayStart', () => horde.setDensity(1));

  // --- Shadow camera: follows the view, snapped to shadow-map texels ---
  const _fwd = new THREE.Vector3();
  const _lx = new THREE.Vector3();
  const _ly = new THREE.Vector3();
  const _c = new THREE.Vector3();
  const _up = new THREE.Vector3(0, 1, 0);
  const lastCenter = new THREE.Vector3(1e9, 0, 0);
  function followShadow(force) {
    const cam = ctx.camera;
    if (!cam) return;
    cam.getWorldDirection(_fwd);
    _fwd.y = 0;
    if (_fwd.lengthSq() < 1e-6) _fwd.set(0, 0, -1);
    _fwd.normalize();
    const px = cam.position.x + _fwd.x * 6;
    const pz = cam.position.z + _fwd.z * 6;
    _c.set(px, layout.groundHeight(px, pz), pz);
    _lx.crossVectors(_up, sunDirection);
    if (_lx.lengthSq() < 1e-6) _lx.set(1, 0, 0);
    _lx.normalize();
    _ly.crossVectors(sunDirection, _lx);
    const texel = (2 * SHADOW_HALF) / mapSize;
    const a = Math.round(_c.dot(_lx) / texel) * texel;
    const b = Math.round(_c.dot(_ly) / texel) * texel;
    const d = _c.dot(sunDirection);
    _c.copy(_lx).multiplyScalar(a).addScaledVector(_ly, b).addScaledVector(sunDirection, d);
    if (!force && _c.distanceToSquared(lastCenter) < 1e-10) return;
    lastCenter.copy(_c);
    sun.target.position.copy(_c);
    sun.position.copy(_c).addScaledVector(sunDirection, 110);
    sun.target.updateMatrixWorld();
    sun.updateMatrixWorld();
  }

  function update(dt, gdt) {
    const g = Number.isFinite(gdt) ? gdt : 0;
    const r = Number.isFinite(dt) ? dt : 0;
    time.value += g;
    skySys.sky.material.uniforms.time.value = time.value;
    foamTex.offset.set(time.value * 0.004, -0.07 + 0.07 * Math.sin(time.value * 0.55));
    while (volleys.length && volleys[0] <= time.value) volleys.shift();
    const shadeTarget = volleys.length ? 1 : 0;
    if (Math.abs(shade - shadeTarget) > 1e-4) {
      shade = damp(shade, shadeTarget, shadeTarget > shade ? 3.2 : 1.6, r);
      if (Math.abs(shade - shadeTarget) < 0.002) shade = shadeTarget;
      applyLight();
    }
    followShadow(false);
  }

  function setArmyDensity(d) {
    horde.setDensity(d);
  }

  function rebuildEnvironment() {
    const env = skySys.rebuild();
    scene.environment = env;
    seaMat.envMap = env;
    poolMat.envMap = env;
  }

  setTimeOfDay('morning');

  return {
    groundHeight: layout.groundHeight,
    bounds: { minX: -7, maxX: 8.4, minZ: -6, maxZ: 3 },
    colliders,
    sunDirection,
    get envMap() {
      return skySys.envTexture;
    },
    setTimeOfDay,
    setArmyDensity,
    rebuildEnvironment,
    /** Clears battle leftovers (volley shade) when main.js resets for a new mode. */
    reset() {
      volleys.length = 0;
      shade = 0;
      applyLight();
    },
    update,
    // Extras (debugging, tests and other modules that want them).
    get timeOfDay() {
      return phase;
    },
    get shade() {
      return shade;
    },
    layout,
    root,
    sun,
    hemi,
    horde,
    wall: { z: WALL.z, gap: [WALL.gapMinX, WALL.gapMaxX], height: WALL.height },
    springs: layout.springs.map((s) => ({ x: s.x, z: s.z, y: s.level })),
  };
}

