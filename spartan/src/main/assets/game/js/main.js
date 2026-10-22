// Thermopylae: bootstrap, shared ctx, game loop and mode state machine (DESIGN.md §4, §10, §14, §15).
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
  safetyAccepted: false,
};

const PIXEL_RATIO_CAP = { low: 1.0, medium: 1.25, high: 1.5 };
const PROBE_KINDS = ['spartan', 'levy', 'sparabara', 'immortal', 'archer', 'captain', 'dummy', 'helot'];
const PROBE_PROPS = ['dory', 'arrow', 'persianSpear', 'sagaris'];

const hook = { ready: false, errors: [] };
window.__game = hook;
window.addEventListener('error', (e) => hook.errors.push(`error: ${e.message} @ ${e.filename}:${e.lineno}`));
window.addEventListener('unhandledrejection', (e) =>
  hook.errors.push(`rejection: ${e.reason && (e.reason.stack || e.reason)}`),
);

function bootProgress(text, fraction) {
  const msg = document.getElementById('boot-msg');
  if (msg && text) msg.textContent = text;
  const fill = document.getElementById('boot-fill');
  if (fill && fraction != null) fill.style.width = `${Math.round(clamp(fraction, 0, 1) * 100)}%`;
}

const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));

/** Resolves settings.quality 'auto' (DESIGN.md §15). */
function resolveQuality(setting) {
  if (setting === 'low' || setting === 'medium' || setting === 'high') return setting;
  const cores = navigator.hardwareConcurrency || 8;
  const memory = navigator.deviceMemory || 4;
  return cores <= 4 || memory <= 3 ? 'low' : 'medium';
}

async function boot() {
  bootProgress('Forging bronze…', 0.02);
  const platform = createPlatform();
  const settings = Object.assign({}, DEFAULT_SETTINGS, platform.storage.load('thermopylae.settings', {}));
  if (params.get('control')) settings.control = params.get('control');
  if (params.get('quality')) settings.quality = params.get('quality');
  platform.bindSettings(settings);
  const quality = resolveQuality(settings.quality);

  const canvas = document.getElementById('scene');
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: quality !== 'low',
      powerPreference: 'high-performance',
      precision: 'highp',
      alpha: false,
      stencil: false,
      depth: true,
      preserveDrawingBuffer: MANUAL,
    });
  } catch (err) {
    window.__engineFail?.('This device has no WebGL 2, which the game needs.');
    throw err;
  }
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.autoClear = false;
  renderer.info.autoReset = false;

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(70, 16 / 9, 0.1, 2000);
  camera.rotation.order = 'YXZ';
  camera.position.set(0, 1.66, 0);
  const vm = { scene: new THREE.Scene(), camera: new THREE.PerspectiveCamera(60, 16 / 9, 0.01, 10) };

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
    time: { now: 0, gnow: 0, dt: 0, gdt: 0, pdt: 0, scale: 1, hitStop: 0, playerRealtime: false },
    rng: createRng(Number(params.get('seed')) || (Date.now() % 100000) + 1),
    quality,
    input: {
      moveX: 0,
      moveZ: 0,
      lookYaw: 0,
      lookPitch: 0,
      shieldHeld: false,
      shieldPressedAt: -10,
      phoneSettled: true,
      motion: false,
    },
    actions: {
      push: (a) => actionQueue.push(a),
      drain: () => actionQueue.splice(0, actionQueue.length),
      clear: () => {
        actionQueue.length = 0;
      },
    },
    mode: 'boot',
    debug: params.get('debug') === '1',
    motionDemo: false,
    actors: [],
    overlay: document.getElementById('overlay'),
  };

  // Motion pipeline (DESIGN.md §5); main.js owns the glue.
  ctx.motionFrames = new MotionFrames({ platformKind: platform.kind });
  ctx.gestures = new GestureRecognizer({
    sensitivity: settings.sensitivity,
    weapon: 'sword',
    gyroRange: platform.motion.info().gyroRange,
  });

  bootProgress('Weaving cloaks…', 0.06);
  ctx.textures = createTextures(ctx);
  if (ctx.textures.generateAll) {
    await ctx.textures.generateAll((f) => bootProgress('Weaving cloaks…', 0.06 + 0.44 * f));
  }
  bootProgress('Raising the Phocian Wall…', 0.52);
  await nextFrame();
  ctx.world = createWorld(ctx);
  bootProgress('Mustering the Three Hundred…', 0.62);
  await nextFrame();
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

  // ---- Mode state machine (DESIGN.md §10) ----
  let pausedFrom = 'battle';
  const motionWanted = () => settings.control === 'motion';

  function resetAll(mode) {
    ctx.training.stop();
    ctx.waves.stop();
    ctx.ai.clear();
    ctx.combat.reset();
    ctx.fx.reset();
    ctx.viewmodel.reset();
    ctx.hud.reset();
    ctx.actions.clear();
    ctx.motionFrames.reset();
    ctx.gestures.reset();
    platform.voice.stop();
    ctx.player.reset({ mode });
  }

  ctx.setMode = (mode, opts = {}) => {
    const prev = ctx.mode;
    if (mode === 'paused') {
      if (prev !== 'battle' && prev !== 'training') return;
      pausedFrom = prev;
    }
    ctx.mode = mode;
    ctx.actions.clear();
    ctx.gestures.reset();

    if ((mode === 'battle' || mode === 'training') && !opts.resume) {
      resetAll(mode);
      if (mode === 'battle') ctx.waves.start(opts.day ?? 1);
      else ctx.training.start();
    } else if (mode === 'title') {
      resetAll('title');
      ctx.world.setTimeOfDay('afternoon');
      ctx.audio.setAmbience('calm');
      ctx.audio.setMusic('title');
    }

    const active = mode === 'battle' || mode === 'training';
    ctx.input.motion = active && motionWanted();
    if (active && motionWanted()) platform.motion.start();
    else if (mode !== 'paused') platform.motion.stop();
    platform.lockOrientation(active && motionWanted());
    quietUntil = ctx.time.now + 2;
    ctx.events.emit('modeChanged', { mode, prev });
  };
  ctx.resume = () => {
    if (ctx.mode === 'paused') ctx.setMode(pausedFrom, { resume: true });
  };

  ctx.events.on('gameOver', () => {
    if (ctx.mode === 'battle') ctx.setMode('gameover');
  });

  let wasOverridden = false;
  ctx.events.on('spearCam', (e) => {
    if (e.phase === 'start') ctx.gestures.holdLook();
  });

  platform.onPause(() => {
    if (ctx.mode === 'battle' || ctx.mode === 'training') ctx.setMode('paused');
    ctx.audio.suspend();
    ctx.saveSettings();
  });
  platform.onResume(() => {
    ctx.audio.resume();
    ctx.motionFrames.reset();
    ctx.gestures.reset();
    quietUntil = ctx.time.now + 2;
  });
  platform.onBack(() => {
    if (ctx.menus.back()) return true;
    if (ctx.mode === 'battle' || ctx.mode === 'training') {
      ctx.setMode('paused');
      return true;
    }
    if (ctx.mode === 'paused') {
      ctx.resume();
      return true;
    }
    if (ctx.mode === 'gameover') {
      ctx.setMode('title');
      return true;
    }
    return false;
  });

  // ---- WebGL context loss ----
  let contextLost = false;
  canvas.addEventListener('webglcontextlost', (e) => {
    e.preventDefault();
    contextLost = true;
  });
  canvas.addEventListener('webglcontextrestored', () => {
    contextLost = false;
    ctx.world.rebuildEnvironment();
    quietUntil = ctx.time.now + 2;
  });

  // ---- Resize and dynamic resolution (DESIGN.md §15) ----
  let renderScale = 1;
  let quietUntil = 0;
  let windowTime = 0;
  let windowFrames = 0;
  let goodWindows = 0;
  let lastDecrease = -100;
  let lastChange = -100;
  function applySize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, PIXEL_RATIO_CAP[quality]) * renderScale);
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    vm.camera.aspect = w / h;
    vm.camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', applySize);
  applySize();

  function adaptResolution(intervalMs) {
    const now = ctx.time.now;
    if (now < quietUntil) {
      windowTime = 0;
      windowFrames = 0;
      return;
    }
    windowTime += intervalMs;
    windowFrames++;
    if (windowTime < 1000) return;
    const mean = windowTime / windowFrames;
    windowTime = 0;
    windowFrames = 0;
    if (now - lastChange < 1) return;
    if (mean > 20 && renderScale > 0.6) {
      renderScale = Math.max(0.6, renderScale - 0.1);
      lastDecrease = lastChange = now;
      goodWindows = 0;
      applySize();
    } else if (mean < 17.5) {
      goodWindows++;
      if (goodWindows >= 5 && renderScale < 1 && now - lastDecrease > 10) {
        renderScale = Math.min(1, renderScale + 0.05);
        lastChange = now;
        goodWindows = 0;
        applySize();
      }
    } else {
      goodWindows = 0;
    }
  }

  // ---- Motion glue (DESIGN.md §10 step 1) ----
  function pumpMotion() {
    const active = ctx.mode === 'battle' || ctx.mode === 'training';
    const fed = active && (motionWanted() || ctx.motionDemo);
    if (!fed) {
      ctx.input.phoneSettled = true;
      return;
    }
    const player = ctx.player;
    const gestures = ctx.gestures;
    const overridden = ctx.fx.cameraOverridden;
    gestures.setSensitivity(settings.sensitivity);
    gestures.setWeapon(player.weapon === 'spear' && player.hasSpearInHand ? 'spear' : 'sword');
    const raw = platform.motion.poll();
    for (let i = 0; i < raw.length; i++) {
      const sample = ctx.motionFrames.process(raw[i]);
      const events = gestures.push(sample);
      for (let j = 0; j < events.length; j++) {
        const g = events[j];
        ctx.events.emit('gesture', g);
        if (g.type === 'windup') {
          if (player.weapon === 'spear' && player.hasSpearInHand && !overridden) ctx.viewmodel.play('throwWindup');
          continue;
        }
        if (overridden) continue;
        if (g.type === 'slash') ctx.actions.push({ type: 'slash', dir: g.dir, power: g.power, src: 'motion' });
        else if (g.type === 'thrust') ctx.actions.push({ type: 'thrust', power: g.power, src: 'motion' });
        else if (g.type === 'throw') ctx.actions.push({ type: 'throw', power: g.power, src: 'motion' });
      }
    }
    if (wasOverridden && !overridden) gestures.resumeLook();
    wasOverridden = overridden;
    const look = gestures.consumeLook();
    if (settings.gyroLook && motionWanted() && !overridden) ctx.input.lookYaw += look.yaw;
    ctx.input.phoneSettled = gestures.settled;
  }

  // ---- Frame (DESIGN.md §10) ----
  let fps = 60;
  function frame(realDt) {
    const t = ctx.time;
    const dt = Math.min(realDt, 0.05);
    const frozen = ctx.mode === 'paused' || ctx.mode === 'gameover';
    t.dt = dt;
    t.now += dt;
    if (!frozen && t.hitStop > 0) t.hitStop = Math.max(0, t.hitStop - dt);
    const stopped = frozen || t.hitStop > 0;
    t.gdt = stopped ? 0 : dt * t.scale;
    t.pdt = stopped ? 0 : t.playerRealtime ? dt : t.gdt;
    t.gnow += t.gdt;

    pumpMotion();
    ctx.inputCtl.update(dt);

    if (ctx.mode === 'battle' || ctx.mode === 'training') {
      ctx.player.update(t.pdt);
      ctx.ai.update(t.gdt);
      ctx.combat.update(t.gdt);
      if (ctx.mode === 'battle') ctx.waves.update(t.gdt);
      else ctx.training.update(dt);
    } else if (ctx.mode === 'title' || ctx.mode === 'gameover') {
      ctx.ai.update(t.gdt);
      ctx.player.idleCamera(dt);
    }

    ctx.viewmodel.update(dt, t.gdt);
    ctx.fx.update(ctx.mode === 'paused' ? 0 : dt, t.gdt);

    if (ctx.mode !== 'title' && ctx.mode !== 'gameover') ctx.player.applyCamera(camera);
    ctx.fx.applyCamera(camera);
    camera.updateMatrixWorld();

    ctx.world.update(dt, t.gdt);
    ctx.audio.update(dt);
    ctx.hud.update(dt);
    ctx.menus.update(dt);
    render();
  }

  function render() {
    renderer.info.reset();
    renderer.clear();
    renderer.render(scene, camera);
    const fpMode = ctx.mode === 'battle' || ctx.mode === 'training' || ctx.mode === 'paused';
    if (fpMode && !ctx.fx.cameraOverridden) {
      renderer.clearDepth();
      renderer.render(vm.scene, vm.camera);
    }
  }

  // ---- Pre-warm shaders behind the boot screen (DESIGN.md §10 boot) ----
  bootProgress('Lighting the watch fires…', 0.75);
  await nextFrame();
  const probes = [];
  for (let i = 0; i < PROBE_KINDS.length; i++) {
    try {
      const v = ctx.characters.create(PROBE_KINDS[i], {});
      v.root.position.set((i - 4) * 1.2, 0, -6);
      scene.add(v.root);
      probes.push(() => v.dispose());
    } catch (err) {
      hook.errors.push(`probe ${PROBE_KINDS[i]}: ${err && (err.stack || err)}`);
    }
  }
  for (const name of PROBE_PROPS) {
    try {
      const prop = ctx.characters.createProp(name);
      prop.position.set(0, 1, -5);
      scene.add(prop);
      probes.push(() => scene.remove(prop));
    } catch (err) {
      hook.errors.push(`probe prop ${name}: ${err && (err.stack || err)}`);
    }
  }
  try {
    ctx.player.applyCamera(camera);
    camera.updateMatrixWorld();
    if (renderer.compileAsync) {
      await renderer.compileAsync(scene, camera);
      await renderer.compileAsync(vm.scene, vm.camera);
    } else {
      renderer.compile(scene, camera);
    }
  } catch (err) {
    hook.errors.push(`prewarm: ${err && (err.stack || err)}`);
  }
  for (const dispose of probes) dispose();
  bootProgress('To the Hot Gates!', 1);

  // ---- Test hooks (DESIGN.md §14) ----
  Object.assign(hook, {
    ctx,
    step(n = 1, dt = 1 / 60) {
      for (let i = 0; i < n; i++) frame(dt);
    },
    render,
    action: (a) => ctx.actions.push(Object.assign({ src: 'test' }, a)),
    injectMotion: (samples) => platform.motion.inject(samples),
    synth: (script, opts) => synthesize(script, opts),
    startBattle: (day = 1) => ctx.setMode('battle', { day }),
    startTraining: () => ctx.setMode('training'),
    spawn: (kind, x, z, opts = {}) => ctx.ai.spawnEnemy(kind, x, z, opts),
    stats: () => ({
      fps,
      calls: renderer.info.render.calls,
      triangles: renderer.info.render.triangles,
      programs: renderer.info.programs ? renderer.info.programs.length : 0,
      actors: ctx.actors.length,
      particles: ctx.fx.particleCount,
      renderScale,
    }),
  });

  // ---- Start ----
  ctx.setMode('title');
  const startMode = params.get('mode');
  if (startMode === 'battle') ctx.setMode('battle', { day: Number(params.get('day') || 1) });
  else if (startMode === 'training') ctx.setMode('training');
  if (params.get('nohud') === '1') ctx.overlay.style.display = 'none';

  const bootEl = document.getElementById('boot');
  if (bootEl) {
    bootEl.classList.add('hidden');
    setTimeout(() => bootEl.remove(), 800);
  }
  hook.ready = true;

  if (MANUAL) {
    frame(1 / 60);
    window.__booted = true;
    return;
  }
  let lastRendered = performance.now();
  const loop = (now) => {
    requestAnimationFrame(loop);
    const interval = now - lastRendered;
    if (interval < 9.5) return; // 120 Hz panels run the game at 60 fps
    lastRendered = now;
    if (contextLost) return;
    fps = fps * 0.95 + (1000 / Math.max(1, interval)) * 0.05;
    frame(interval / 1000);
    adaptResolution(interval);
    window.__booted = true;
  };
  requestAnimationFrame(loop);
}

boot().catch((err) => {
  console.error(err);
  hook.errors.push(`boot: ${err && (err.stack || err)}`);
  window.__engineFail?.(`The game failed to start: ${err && err.message}`);
});
