// A minimal stand-in for main.js's ctx, so a single module can be developed and screenshot
// in isolation. It follows DESIGN.md §4. Real modules are loaded when they exist; otherwise
// light stubs are used.
//
// URL params: manual=1 (no RAF; drive with window.__sandbox.step(n)), seed=N, quality=low|medium|high.
import * as THREE from 'three';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';
import { createRng } from '../../src/main/assets/game/js/core/math.js';

const GAME = '../../src/main/assets/game/js/';

async function tryImport(path) {
  try {
    return await import(GAME + path);
  } catch (err) {
    if (!String(err).includes('Failed to fetch') && !String(err).includes('404')) console.warn(`sandbox: ${path} failed to load`, err);
    return null;
  }
}

export async function createSandboxCtx({ withWorld = false, withLights = true } = {}) {
  const params = new URLSearchParams(location.search);
  const canvas = document.getElementById('scene');
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  renderer.setSize(window.innerWidth, window.innerHeight, false);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.autoClear = false;

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x9fb4c8);
  const camera = new THREE.PerspectiveCamera(70, window.innerWidth / window.innerHeight, 0.05, 2000);
  camera.rotation.order = 'YXZ';
  camera.position.set(0, 1.66, 0);
  const vm = { scene: new THREE.Scene(), camera: new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.01, 10) };

  const settings = {
    control: 'touch', sensitivity: 1, gyroLook: true, aimAssist: true, leftHanded: false,
    haptics: true, voice: false, blood: true, helmetView: true, quality: params.get('quality') || 'high',
    master: 0.9, music: 0.55, sfx: 1, sensorDebug: false,
  };
  const actionsQ = [];
  const ctx = {
    THREE, renderer, scene, camera, vm,
    events: new EventBus(),
    settings,
    saveSettings() {},
    time: { now: 0, dt: 0, gdt: 0, scale: 1, hitStop: 0 },
    rng: createRng(Number(params.get('seed') || 1)),
    quality: settings.quality === 'auto' ? 'high' : settings.quality,
    input: { moveX: 0, moveZ: 0, lookYaw: 0, lookPitch: 0, shieldHeld: false, firing: false },
    actions: { push: (a) => actionsQ.push(a), drain: () => actionsQ.splice(0, actionsQ.length) },
    mode: 'battle',
    setMode(m) { ctx.mode = m; },
    actors: [],
    overlay: document.getElementById('overlay'),
    platform: {
      kind: 'web',
      haptics: { pulse() {}, pattern() {} },
      voice: { say(t) { console.log('voice:', t); } },
      storage: { load: (k, f) => f, save() {} },
      motion: { isSupported: async () => false, requestPermission: async () => true, start() {}, stop() {}, poll: () => [], inject() {}, rate: () => 0 },
      onPause() {}, onResume() {}, onBack() {}, exit() {},
    },
  };

  if (withLights) {
    const hemi = new THREE.HemisphereLight(0xcfe0ff, 0x6b5a45, 0.9);
    scene.add(hemi);
    const sun = new THREE.DirectionalLight(0xfff1d6, 2.6);
    sun.position.set(-30, 40, 25);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -15, right: 15, top: 15, bottom: -15, near: 1, far: 120 });
    scene.add(sun);
    ctx.sandboxLights = { hemi, sun };
    vm.scene.add(new THREE.HemisphereLight(0xcfe0ff, 0x6b5a45, 1.0));
    const vmSun = new THREE.DirectionalLight(0xfff1d6, 2.2);
    vmSun.position.set(-3, 4, 2.5);
    vm.scene.add(vmSun);
  }

  const texMod = await tryImport('gfx/textures.js');
  ctx.textures = texMod ? texMod.createTextures(ctx) : { get: () => null };

  if (withWorld) {
    const worldMod = await tryImport('gfx/world.js');
    if (worldMod) ctx.world = worldMod.createWorld(ctx);
  }
  if (!ctx.world) {
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(200, 200), new THREE.MeshStandardMaterial({ color: 0x9c8466, roughness: 1 }));
    ground.rotation.x = -Math.PI / 2;
    ground.receiveShadow = true;
    scene.add(ground);
    ctx.world = {
      groundHeight: () => 0,
      bounds: { minX: -7, maxX: 8, minZ: -6, maxZ: 3 },
      colliders: [],
      sunDirection: new THREE.Vector3(-30, 40, 25).normalize(),
      envMap: null,
      setTimeOfDay() {}, setArmyDensity() {}, setVolleyShade() {},
      update() {},
    };
  }

  function onResize() {
    const w = window.innerWidth, h = window.innerHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    vm.camera.aspect = w / h;
    vm.camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', onResize);

  const updaters = [];
  ctx.render = () => {
    renderer.clear();
    renderer.render(scene, camera);
    if (vm.scene.children.length > 0 && !ctx.hideViewmodel) {
      renderer.clearDepth();
      renderer.render(vm.scene, vm.camera);
    }
  };
  ctx.step = (n = 1, dt = 1 / 60) => {
    for (let i = 0; i < n; i++) {
      ctx.time.dt = dt;
      ctx.time.now += dt;
      if (ctx.time.hitStop > 0) {
        ctx.time.hitStop -= dt;
        ctx.time.gdt = 0;
      } else ctx.time.gdt = dt * ctx.time.scale;
      for (const fn of updaters) fn(ctx.time.dt, ctx.time.gdt);
    }
    ctx.render();
  };
  ctx.loop = (fn) => {
    updaters.push(fn);
    if (params.get('manual') === '1' || ctx._looping) return;
    ctx._looping = true;
    let last = performance.now();
    const frame = (t) => {
      const dt = Math.min(0.05, (t - last) / 1000);
      last = t;
      ctx.step(1, dt);
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  };
  window.__sandbox = { ctx, step: ctx.step, errors: [] };
  window.__game = window.__sandbox; // EventBus reports handler errors here
  return ctx;
}
