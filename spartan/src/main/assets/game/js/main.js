// Thermopylae: bootstrap, shared ctx, game loop and mode state machine (DESIGN.md §4, §10, §13).
import * as THREE from 'three';
import { EventBus } from './core/events.js';
import { createRng, clamp } from './core/math.js';
import { createPlatform } from './platform.js';
import { MotionFrames } from './motion/frames.js';
import { GestureRecognizer } from './motion/gestures.js';
import { synthesize } from './motion/synth.js';
import { createTextures } from './gfx/textures.js';
import { createWorld } from './gfx/world.js';
import { createCharacters } from './gfx/characters.js';
import { createFx } from './gfx/fx.js';
import { createViewmodel } from './gfx/viewmodel.js';
import { createAudio } from './audio/audio.js';
import { createCombat } from './game/combat.js';
import { createAI } from './game/ai.js';
import { createPlayer } from './game/player.js';
import { createWaves } from './game/waves.js';
import { createHud } from './ui/hud.js';
import { createMenus } from './ui/menus.js';
import { createInput } from './ui/input.js';
import { createTraining } from './ui/training.js';

const params = new URLSearchParams(location.search);
const MANUAL = params.get('manual') === '1';

const DEFAULT_SETTINGS = {
  control: 'touch',
  sensitivity: 1.0,
  gyroLook: true,
  aimAssist: true,
  leftHanded: false,
  haptics: true,
  voice: true,
  blood: true,
  helmetView: true,
  quality: 'auto',
  master: 0.9,
  music: 0.55,
  sfx: 1.0,
  sensorDebug: false,
};

const hook = {
  ready: false,
  errors: [],
};
window.__game = hook;
window.addEventListener('error', (e) => hook.errors.push(`error: ${e.message} @ ${e.filename}:${e.lineno}`));
window.addEventListener('unhandledrejection', (e) => hook.errors.push(`rejection: ${e.reason && (e.reason.stack || e.reason)}`));

function bootMessage(text) {
  const el = document.getElementById('boot-msg');
  if (el) el.textContent = text;
}

/** Picks a quality tier for 'auto' from the GPU and screen. */
function autoQuality(renderer) {
  const gl = renderer.getContext();
  const dbg = gl.getExtension('WEBGL_debug_renderer_info');
  const gpu = dbg ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)) : '';
  const mobile = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);
  if (/SwiftShader|llvmpipe|Mali-(T|G5[0-9])|Adreno \(TM\) [345]\d\d|PowerVR/i.test(gpu)) return 'low';
  if (mobile) return 'medium';
  return 'high';
}

async function boot() {
  bootMessage('Forging bronze…');
  const platform = createPlatform();
  const settings = Object.assign({}, DEFAULT_SETTINGS, platform.storage.load('thermopylae.settings', {}));
  if (params.get('control')) settings.control = params.get('control');
  if (params.get('quality')) settings.quality = params.get('quality');

  const canvas = document.getElementById('scene');
  const lowEnd = settings.quality === 'low';
  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: !lowEnd,
    powerPreference: 'high-performance',
    stencil: false,
    preserveDrawingBuffer: MANUAL,
  });
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.autoClear = false;

  const quality = settings.quality === 'auto' ? autoQuality(renderer) : settings.quality;
  if (quality === 'low') renderer.shadowMap.type = THREE.PCFShadowMap;
  const maxPixelRatio = quality === 'high' ? 1.5 : quality === 'medium' ? 1.25 : 1.0;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(70, 16 / 9, 0.05, 2000);
  camera.rotation.order = 'YXZ';
  camera.position.set(0, 1.66, 0);
  const vm = {
    scene: new THREE.Scene(),
    camera: new THREE.PerspectiveCamera(60, 16 / 9, 0.01, 10),
  };

  const actionQueue = [];
  const ctx = {
    THREE,
    renderer,
    scene,
    camera,
    vm,
    events: new EventBus(),
    settings,
    saveSettings() {
      platform.storage.save('thermopylae.settings', settings);
    },
    platform,
    time: { now: 0, dt: 0, gdt: 0, scale: 1, hitStop: 0 },
    rng: createRng(Number(params.get('seed') || Date.now() % 100000)),
    quality,
    input: { moveX: 0, moveZ: 0, lookYaw: 0, lookPitch: 0, shieldHeld: false, firing: false },
    actions: {
      push: (a) => actionQueue.push(a),
      drain: () => actionQueue.splice(0, actionQueue.length),
      clear: () => {
        actionQueue.length = 0;
      },
    },
    mode: 'title',
    actors: [],
    overlay: document.getElementById('overlay'),
    debug: params.get('debug') === '1',
  };

  // Motion pipeline (DESIGN.md §5).
  const frames = new MotionFrames({});
  const gestures = new GestureRecognizer({ sensitivity: settings.sensitivity, weapon: 'sword' });
  ctx.motionFrames = frames;
  ctx.gestures = gestures;

  bootMessage('Raising the Phocian Wall…');
  ctx.textures = createTextures(ctx);
  ctx.world = createWorld(ctx);
  bootMessage('Mustering the Three Hundred…');
  ctx.characters = createCharacters(ctx);
  ctx.fx = createFx(ctx);
  ctx.audio = createAudio(ctx);
  ctx.viewmodel = createViewmodel(ctx);
  ctx.combat = createCombat(ctx);
  ctx.ai = createAI(ctx);
  ctx.player = createPlayer(ctx);
  ctx.waves = createWaves(ctx);
  ctx.hud = createHud(ctx);
  ctx.menus = createMenus(ctx);
  ctx.inputCtl = createInput(ctx);
  ctx.training = createTraining(ctx);

  // ---- Mode state machine ----
  let pausedFrom = null;
  ctx.setMode = (mode, opts = {}) => {
    const prev = ctx.mode;
    if (mode === prev && !opts.force) return;
    ctx.mode = mode;
    if (mode === 'battle' && prev !== 'paused') startBattle(opts.day || 1);
    if (mode === 'training' && prev !== 'paused') startTraining();
    if (mode === 'paused') pausedFrom = prev;
    if (mode === 'title') {
      ctx.training.stop?.();
      ctx.ai.clear();
      ctx.player.reset({ title: true });
      ctx.audio.setAmbience('calm');
      ctx.audio.setMusic('title');
      ctx.world.setTimeOfDay('afternoon');
    }
    if (mode === 'battle' || mode === 'training') {
      ctx.time.scale = 1;
      if (settings.control === 'motion') platform.motion.start();
    } else if (mode !== 'paused') {
      platform.motion.stop();
    }
    ctx.events.emit('modeChanged', { mode, prev });
  };
  ctx.resume = () => {
    if (ctx.mode === 'paused' && pausedFrom) ctx.setMode(pausedFrom);
  };

  function startBattle(day) {
    ctx.training.stop?.();
    ctx.ai.clear();
    ctx.actions.clear();
    frames.reset();
    ctx.player.reset({});
    ctx.audio.setAmbience('battle');
    ctx.audio.setMusic('battle');
    ctx.waves.start(day);
  }

  function startTraining() {
    ctx.ai.clear();
    ctx.actions.clear();
    frames.reset();
    ctx.player.reset({ training: true });
    ctx.audio.setAmbience('calm');
    ctx.audio.setMusic('none');
    ctx.world.setTimeOfDay('morning');
    ctx.training.start();
  }

  ctx.events.on('playerDeath', () => {
    setTimeout(() => {
      if (ctx.mode !== 'battle') return;
      ctx.setMode('gameover');
    }, MANUAL ? 0 : 3200);
  });

  platform.onPause(() => {
    if (ctx.mode === 'battle' || ctx.mode === 'training') ctx.setMode('paused');
    ctx.audio.suspend?.();
  });
  platform.onResume(() => {
    ctx.audio.resume?.();
  });
  platform.onBack(() => {
    if (ctx.menus.back?.()) return true;
    if (ctx.mode === 'battle' || ctx.mode === 'training') {
      ctx.setMode('paused');
      return true;
    }
    if (ctx.mode === 'paused') {
      ctx.resume();
      return true;
    }
    return false;
  });

  // ---- Resize and dynamic resolution ----
  let renderScale = 1;
  let slowTime = 0;
  let fastTime = 0;
  function applySize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, maxPixelRatio) * renderScale);
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    vm.camera.aspect = w / h;
    vm.camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', applySize);
  applySize();

  function adaptResolution(frameMs) {
    if (MANUAL) return;
    if (frameMs > 22) {
      slowTime += frameMs / 1000;
      fastTime = 0;
    } else if (frameMs < 14) {
      fastTime += frameMs / 1000;
      slowTime = 0;
    } else {
      slowTime = Math.max(0, slowTime - frameMs / 2000);
      fastTime = Math.max(0, fastTime - frameMs / 2000);
    }
    if (slowTime > 2 && renderScale > 0.6) {
      renderScale = Math.max(0.6, renderScale - 0.1);
      slowTime = 0;
      applySize();
    } else if (fastTime > 4 && renderScale < 1) {
      renderScale = Math.min(1, renderScale + 0.1);
      fastTime = 0;
      applySize();
    }
  }

  // ---- Frame ----
  const motionActive = () =>
    settings.control === 'motion' && (ctx.mode === 'battle' || ctx.mode === 'training');

  function pumpMotion() {
    const raw = platform.motion.poll();
    if (!motionActive()) return;
    const player = ctx.player;
    gestures.setSensitivity(settings.sensitivity);
    gestures.setWeapon(player.weapon === 'spear' && player.hasSpearInHand ? 'spear' : 'sword');
    for (const r of raw) {
      const s = frames.process(r);
      for (const g of gestures.push(s)) {
        ctx.events.emit('gesture', g);
        if (g.type === 'slash') ctx.actions.push({ type: 'slash', dir: g.dir, power: g.power, src: 'motion' });
        else if (g.type === 'thrust') ctx.actions.push({ type: 'thrust', power: g.power, src: 'motion' });
        else if (g.type === 'throw') ctx.actions.push({ type: 'throw', power: g.power, src: 'motion' });
      }
    }
    const look = gestures.consumeLook();
    if (settings.gyroLook && !ctx.fx.cameraOverridden) {
      ctx.input.lookYaw += look.yaw;
      ctx.input.lookPitch += look.pitch;
    }
  }

  let fps = 60;
  function frame(dt) {
    const t = ctx.time;
    t.dt = dt;
    t.now += dt;
    if (t.hitStop > 0) {
      t.hitStop = Math.max(0, t.hitStop - dt);
      t.gdt = 0;
    } else {
      t.gdt = ctx.mode === 'paused' || ctx.mode === 'gameover' ? 0 : dt * t.scale;
    }
    const gdt = t.gdt;

    pumpMotion();
    ctx.inputCtl.update(dt);

    if (ctx.mode === 'battle' || ctx.mode === 'training') {
      ctx.player.update(gdt);
      ctx.ai.update(gdt);
      ctx.combat.update(gdt);
      if (ctx.mode === 'battle') ctx.waves.update(gdt);
      else ctx.training.update(dt);
    } else if (ctx.mode === 'title' || ctx.mode === 'gameover') {
      ctx.ai.update(gdt);
      ctx.player.idleCamera?.(dt);
    }

    ctx.viewmodel.update(dt, gdt);
    ctx.world.update(dt, gdt);
    ctx.fx.update(dt, gdt);
    ctx.audio.update(dt);

    ctx.player.applyCamera(camera);
    ctx.fx.applyCamera(camera);

    ctx.hud.update(dt);
    ctx.menus.update?.(dt);
    render();
  }

  function render() {
    renderer.clear();
    renderer.render(scene, camera);
    if (!ctx.fx.cameraOverridden && (ctx.mode === 'battle' || ctx.mode === 'training' || ctx.mode === 'paused')) {
      renderer.clearDepth();
      renderer.render(vm.scene, vm.camera);
    }
  }

  // ---- Test hooks (DESIGN.md §13) ----
  Object.assign(hook, {
    ctx,
    step(n = 1, dt = 1 / 60) {
      for (let i = 0; i < n; i++) frame(dt);
    },
    render,
    action: (a) => ctx.actions.push(Object.assign({ src: 'test' }, a)),
    injectMotion: (samples) => platform.motion.inject(samples),
    synth: (script, opts) => synthesize(script, opts),
    startBattle: (day = 1) => ctx.setMode('battle', { day, force: true }),
    startTraining: () => ctx.setMode('training', { force: true }),
    spawn: (kind, x, z) => ctx.ai.spawnEnemy(kind, x, z, {}),
    stats: () => ({
      fps,
      calls: renderer.info.render.calls,
      triangles: renderer.info.render.triangles,
      actors: ctx.actors.length,
      particles: ctx.fx.particleCount ?? 0,
      renderScale,
    }),
  });

  // ---- Start ----
  ctx.setMode('title', { force: true });
  const startMode = params.get('mode');
  if (startMode === 'battle') ctx.setMode('battle', { day: Number(params.get('day') || 1) });
  else if (startMode === 'training') ctx.setMode('training');
  if (params.get('nohud') === '1') ctx.overlay.style.display = 'none';

  const bootEl = document.getElementById('boot');
  bootEl.classList.add('hidden');
  setTimeout(() => bootEl.remove(), 800);
  hook.ready = true;

  if (!MANUAL) {
    let last = performance.now();
    const loop = (now) => {
      const ms = now - last;
      last = now;
      fps = fps * 0.95 + (1000 / Math.max(1, ms)) * 0.05;
      frame(clamp(ms / 1000, 0, 0.05));
      adaptResolution(ms);
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  } else {
    frame(1 / 60);
  }
}

boot().catch((err) => {
  console.error(err);
  hook.errors.push(`boot: ${err && (err.stack || err)}`);
  bootMessage(`Failed to start: ${err && err.message}`);
});
