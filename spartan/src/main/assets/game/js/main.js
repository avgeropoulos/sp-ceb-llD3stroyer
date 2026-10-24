// Thermopylae: bootstrap, shared ctx, game loop and mode state machine (DESIGN.md §4, §10, §14, §15).
import * as THREE from 'three';
import { EventBus } from './core/events.js';
import { createRng, clamp } from './core/math.js';
import { GRIP_IDS, DEFAULT_GRIP, effectiveGrip, appOrientation, sensitivityKey, flipKeys, migrateSettings } from './core/grip.js';
import { createPlatform } from './platform.js';
import { MotionFrames } from './motion/frames.js';
import { GestureRecognizer } from './motion/gestures.js';
import { synthesize } from './motion/synth.js';
import { createTextures } from './gfx/textures.js';
import { createWorld } from './gfx/world.js';
import { createCharacters } from './gfx/characters.js';
import { createFx } from './gfx/fx.js';
import { cameraFov } from './gfx/framing.js';
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
import { createUpdates } from './ui/updates.js';

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
  motionFlipX: false, // a mirrored sensor setup: swing directions left↔right (dir → π − dir)
  motionFlipY: false, // ... and up↔down (dir → −dir)
  // The grips (docs-hilt-grip.md §A.1). Every reader goes through core/grip.js.
  grip: DEFAULT_GRIP, // 'hilt' | 'window'. Motion only; touch ignores it
  gripChosen: false, // the grip chooser has been answered
  sensitivityHilt: 1.0, // the hilt grip's own sensitivity; `sensitivity` stays the window grip's
  motionFlipXHilt: false, // the hilt grip's own mirrored-setup flips (flipKeys); motionFlipX/Y stay the window grip's
  motionFlipYHilt: false,
  lockAuto: true, // hilt: the lock turns to an attacker the shield can't cover
  hiltFingerStrip: 44, // px (44–120): the finger-side reject strip, widened by the Agoge grip check
  hiltAgogeDone: false, // the hilt Agoge has been finished once
  hiltSafetyAccepted: false, // the hilt safety screen has been accepted
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
  window.__bootProgressAt = Date.now();
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
  // A motion player from before the grips keeps the window grip; a new player gets DEFAULT_GRIP.
  const stored = migrateSettings(platform.storage.load('thermopylae.settings', {}));
  const settings = Object.assign({}, DEFAULT_SETTINGS, stored);
  if (params.get('control')) settings.control = params.get('control');
  if (params.get('quality')) settings.quality = params.get('quality');
  // ?grip=hilt|window (tests) skips the grip chooser too.
  if (GRIP_IDS.includes(params.get('grip'))) {
    settings.grip = params.get('grip');
    settings.gripChosen = true;
  }
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
      grip: null, // effectiveGrip(settings): 'hilt' | 'window' | null (touch), kept by ctx.applyControls()
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
  // A browser's sensor quirks (axis order, gravity sign, screen angle) are learned once and remembered.
  const CALIB_KEY = 'thermopylae.calib';
  ctx.motionFrames.importCalibration?.(platform.storage.load(CALIB_KEY, null));
  const saveCalibration = () => {
    const calib = ctx.motionFrames.exportCalibration?.();
    if (calib && calib.confirmed) platform.storage.save(CALIB_KEY, calib);
  };
  ctx.gestures = new GestureRecognizer({
    sensitivity: settings[sensitivityKey(settings)],
    weapon: 'sword',
    gyroRange: platform.motion.info().gyroRange,
  });

  /**
   * Brings everything that depends on the control and the grip in line with the settings
   * (docs-hilt-grip.md §A.6). Call it after any change to `control` or `grip`; setMode calls it
   * on every mode change, so the recognizer, ctx.input.grip and the orientation never disagree.
   *  - the recognizer's grip profile (each setGrip is a no-op if unchanged; a change resets it),
   *  - ctx.input.grip, in every mode (the viewmodel keeps its hilt pose under the pause menu),
   *  - the effective grip's flips, <html data-grip="hilt|window|none">,
   *  - the app orientation: portrait for the hilt grip in every mode, locked while fighting.
   */
  let gripAttr = '';
  ctx.applyControls = () => {
    const grip = effectiveGrip(settings);
    const g = grip || 'window';
    ctx.motionFrames.setGrip?.(g);
    ctx.gestures.setGrip?.(g);
    ctx.input.grip = grip;
    const flip = !ctx.motionDemo;
    const keys = flipKeys(settings);
    ctx.gestures.setFlip?.(flip && !!settings[keys.x], flip && !!settings[keys.y]);
    const attr = grip || 'none';
    if (attr !== gripAttr) {
      gripAttr = attr;
      document.documentElement.dataset.grip = attr;
    }
    const active = ctx.mode === 'battle' || ctx.mode === 'training';
    platform.lockOrientation(active && settings.control === 'motion', appOrientation(settings));
  };
  ctx.applyControls();

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
  ctx.updates = createUpdates(ctx);

  // ---- Mode state machine (DESIGN.md §10) ----
  let pausedFrom = 'battle';
  const motionWanted = () => settings.control === 'motion';

  function resetAll(mode) {
    ctx.training.stop();
    ctx.waves.stop();
    ctx.ai.clear();
    ctx.combat.reset();
    ctx.world.reset();
    ctx.fx.reset();
    ctx.viewmodel.reset();
    ctx.hud.reset();
    ctx.actions.clear();
    platform.motion.clearInjected();
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
    if (prev === 'battle' || prev === 'training') saveCalibration();
    ctx.mode = mode;
    ctx.actions.clear();
    ctx.gestures.reset();

    if ((mode === 'battle' || mode === 'training') && !opts.resume) {
      resetAll(mode);
      if (mode === 'battle') {
        ctx.waves.start(opts.day ?? 1, opts.entry ?? 0);
        ctx.audio.setAmbience('battle');
      } else {
        ctx.training.start();
      }
    } else if (mode === 'title') {
      resetAll('title');
      ctx.world.setTimeOfDay('afternoon');
      ctx.world.setArmyDensity(1);
      ctx.audio.setAmbience('calm');
      ctx.audio.setMusic('title');
    }

    // Sensors run only while fighting. Stopping them in the pause menu also means nothing the
    // phone did meanwhile can replay as attacks on Resume (starting again clears the native ring).
    const active = mode === 'battle' || mode === 'training';
    ctx.input.motion = active && motionWanted();
    if (active && motionWanted()) {
      platform.motion.start();
      if (opts.resume) {
        platform.motion.poll();
        ctx.motionFrames.reset();
      }
    } else {
      platform.motion.stop();
    }
    // The grip profile, ctx.input.grip and the orientation lock follow the settings.
    ctx.applyControls();
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
    saveCalibration();
  });
  // The notification shade or another system window took focus: stop the fight until the player is back.
  platform.onBlur(() => {
    if (ctx.mode === 'battle' || ctx.mode === 'training') ctx.setMode('paused');
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
  let windowCpu = 0;
  let goodWindows = 0;
  let lastDecrease = -100;
  let lastChange = -100;
  function applySize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, PIXEL_RATIO_CAP[quality]) * renderScale);
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    // A fixed horizontal field of view: ~100° across in landscape (55–70° vertical), ~46° across
    // in portrait (72–84° vertical), gfx/framing.js.
    camera.fov = cameraFov(camera.aspect);
    camera.updateProjectionMatrix();
    vm.camera.aspect = w / h;
    vm.camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', () => {
    applySize();
    // A rotation re-lays out the page over a few slow frames: they must not lower the resolution.
    quietUntil = ctx.time.now + 2;
  });
  applySize();

  // Only the GPU's share of a slow frame is helped by fewer pixels. When the main thread itself
  // takes most of the frame (CPU-bound fights on mid-range phones), keep the image sharp.
  function adaptResolution(intervalMs, cpuMs) {
    const now = ctx.time.now;
    if (now < quietUntil) {
      windowTime = 0;
      windowCpu = 0;
      windowFrames = 0;
      return;
    }
    windowTime += intervalMs;
    windowCpu += cpuMs;
    windowFrames++;
    if (windowTime < 1000) return;
    const mean = windowTime / windowFrames;
    const cpuMean = windowCpu / windowFrames;
    windowTime = 0;
    windowCpu = 0;
    windowFrames = 0;
    if (now - lastChange < 1) return;
    const gpuBound = cpuMean < 0.75 * mean;
    if (!gpuBound && renderScale < 0.85) {
      renderScale = 0.85;
      lastChange = now;
      goodWindows = 0;
      applySize();
    } else if (mean > 20 && gpuBound && renderScale > 0.6) {
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
  function pumpMotion(dt) {
    const active = ctx.mode === 'battle' || ctx.mode === 'training';
    const fed = active && (motionWanted() || ctx.motionDemo);
    if (!fed) {
      ctx.input.phoneSettled = true;
      return;
    }
    const player = ctx.player;
    const gestures = ctx.gestures;
    const overridden = ctx.fx.cameraOverridden;
    // Each grip keeps its own sensitivity and flips (core/grip.js; constant keys, no allocation).
    gestures.setSensitivity(settings[sensitivityKey(settings)]);
    gestures.setWeapon(player.weapon === 'spear' && player.hasSpearInHand ? 'spear' : 'sword');
    // A mirrored setup is corrected on the rates themselves, so every directional decision
    // (uppercut, chop, throw, recoil) and gyro look agree: flipX gives dir → π − dir, flipY dir → −dir.
    // The Agoge's demos are synthesized for an unmirrored phone: they are never flipped.
    const flip = !ctx.motionDemo;
    const keys = flipKeys(settings);
    gestures.setFlip?.(flip && !!settings[keys.x], flip && !!settings[keys.y]);
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
    // any swing-like excursion, slash or not: the Agoge calibrates from these
    const cands = gestures.consumeCandidates ? gestures.consumeCandidates() : null;
    if (cands) for (let i = 0; i < cands.length; i++) ctx.events.emit('swingCandidate', cands[i]);
    if (wasOverridden && !overridden) gestures.resumeLook();
    wasOverridden = overridden;
    const look = gestures.consumeLook(dt);
    if (settings.gyroLook && motionWanted() && !overridden) ctx.input.lookYaw += look.yaw;
    ctx.input.phoneSettled = gestures.settled;
  }

  // ---- Frame (DESIGN.md §10) ----
  let fps = 60;
  function frame(realDt, draw = true) {
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

    pumpMotion(dt);
    ctx.inputCtl.update(dt);

    if (ctx.mode === 'battle' || ctx.mode === 'training') {
      ctx.player.update(t.pdt);
      ctx.ai.update(t.gdt);
      ctx.combat.update(t.gdt);
      if (ctx.mode === 'battle') ctx.waves.update(t.gdt);
      else ctx.training.update(dt);
    } else if (ctx.mode === 'title' || ctx.mode === 'gameover') {
      ctx.ai.update(t.gdt);
      // The title pans slowly behind the line; game over keeps the fallen hoplite's view of the sky.
      if (ctx.mode === 'title') ctx.player.idleCamera(dt);
    }

    ctx.viewmodel.update(dt, t.gdt);
    ctx.fx.update(ctx.mode === 'paused' ? 0 : dt, t.gdt);

    ctx.player.applyCamera(camera);
    ctx.fx.applyCamera(camera);
    camera.updateMatrixWorld();

    ctx.world.update(dt, t.gdt);
    ctx.audio.update(dt);
    ctx.hud.update(dt);
    ctx.menus.update(dt);
    if (draw) render();
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
  const probeMeshes = [];
  for (let i = 0; i < PROBE_KINDS.length; i++) {
    try {
      const v = ctx.characters.create(PROBE_KINDS[i], {});
      v.root.position.set((i - 4) * 1.2, 0, -6);
      scene.add(v.root);
      if (v.mesh) probeMeshes.push(v.mesh);
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
      // Parallel shader compiles can take a while on a phone GPU; keep the boot watchdog fed.
      const alive = setInterval(() => bootProgress(null, null), 1000);
      try {
        await renderer.compileAsync(scene, camera);
        await renderer.compileAsync(vm.scene, vm.camera);
      } finally {
        clearInterval(alive);
      }
    } else {
      renderer.compile(scene, camera);
    }
    // compileAsync skips the shadow pass's depth materials (the skinned one most of all), so draw
    // one frame with the probes casting shadows while the boot screen still covers the canvas.
    for (const obj of probeMeshes) obj.castShadow = true;
    renderer.render(scene, camera);
  } catch (err) {
    hook.errors.push(`prewarm: ${err && (err.stack || err)}`);
  }
  for (const dispose of probes) dispose();
  bootProgress('To the Hot Gates!', 1);

  // ---- Test hooks (DESIGN.md §14) ----
  Object.assign(hook, {
    ctx,
    /** Advances n frames; only the last one is drawn (software GL in tests is slow). */
    step(n = 1, dt = 1 / 60) {
      for (let i = 0; i < n; i++) frame(dt, i === n - 1);
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

  const markBooted = () => {
    window.__booted = true;
    document.getElementById('engine-fail')?.remove();
  };
  if (MANUAL) {
    frame(1 / 60);
    markBooted();
    return;
  }
  // 60 fps on any panel: an accumulator over vsync intervals renders 2 of 3 frames at 90 Hz,
  // 1 of 2 at 120 Hz, 5 of 12 at 144 Hz, and every frame at 60 Hz (with jitter headroom).
  let lastVsync = performance.now();
  let lastRendered = lastVsync;
  let pace = 0;
  const loop = (now) => {
    requestAnimationFrame(loop);
    pace += now - lastVsync;
    lastVsync = now;
    if (pace < 14) return;
    pace = Math.min(pace - 1000 / 60, 1000 / 60);
    const interval = now - lastRendered;
    lastRendered = now;
    if (contextLost) return;
    fps = fps * 0.95 + (1000 / Math.max(1, interval)) * 0.05;
    const t0 = performance.now();
    frame(interval / 1000);
    adaptResolution(interval, performance.now() - t0);
    if (!window.__booted) markBooted();
  };
  requestAnimationFrame(loop);
}

boot().catch((err) => {
  console.error(err);
  hook.errors.push(`boot: ${err && (err.stack || err)}`);
  window.__engineFail?.(`The game failed to start: ${err && err.message}`);
});
