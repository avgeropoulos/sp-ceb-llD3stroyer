// Sandbox views for gfx/characters.js. Each characters-*.html page calls run(view).
// URL params (all optional): kind, variant, clip, phases (comma list 0..1), speed, yaw, dist,
// guard=1, quality, seed, cam=front|side|top|three, sun=side|front.
import * as THREE from 'three';
import { createSandboxCtx } from './sandbox-ctx.js';
import { createCharacters, CLIPS, KINDS } from '../../src/main/assets/game/js/gfx/characters.js';

const params = new URLSearchParams(location.search);
const num = (k, d) => (params.has(k) ? Number(params.get(k)) : d);

function skyEnv(renderer, sunDir) {
  // a gradient sky with a sun blob, standing in for world.js's PMREM sky
  const scene = new THREE.Scene();
  const geo = new THREE.SphereGeometry(100, 48, 24);
  const col = [];
  const pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) {
    const y = pos.getY(i) / 100;
    const c = new THREE.Color();
    if (y > 0) c.setRGB(0.55 - 0.25 * y, 0.68 - 0.18 * y, 0.9 - 0.05 * y);
    else c.setRGB(0.42 + 0.1 * y, 0.36 + 0.08 * y, 0.28 + 0.05 * y);
    col.push(c.r, c.g, c.b);
  }
  geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  scene.add(new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.BackSide })));
  const sun = new THREE.Mesh(new THREE.SphereGeometry(6, 16, 8), new THREE.MeshBasicMaterial({ color: new THREE.Color(14, 12, 9) }));
  sun.position.copy(sunDir).multiplyScalar(90);
  scene.add(sun);
  const pm = new THREE.PMREMGenerator(renderer);
  const env = pm.fromScene(scene, 0, 0.1, 1000).texture;
  pm.dispose();
  return env;
}

/** One viewport per actor: each actor gets its own camera (profile, front or 3/4). */
function tileViews(ctx, visuals, camMode) {
  const { renderer, scene } = ctx;
  const n = visuals.length;
  const sp = 7;
  const cams = visuals.map((v, i) => {
    const x = (i - (n - 1) / 2) * sp;
    const dx = x - v.root.position.x;
    v.root.position.x = x;
    if (v.root.position.z !== 0 && camMode !== 'top') v.root.position.z = 0;
    void dx;
    const cam = new THREE.PerspectiveCamera(Number(params.get('fov') || 40), 1, 0.1, 100);
    const d = num('dist', 3.6), cy = num('cy', 0.95);
    if (camMode === 'side') cam.position.set(x, cy + 0.25, d);
    else if (camMode === 'three') cam.position.set(x + d * 0.6, cy + 0.4, d * 0.8);
    else if (camMode === 'top') cam.position.set(x, d * 1.1, 1.2);
    else cam.position.set(x, cy + 0.4, d);
    cam.lookAt(x, camMode === 'top' ? 0 : cy, 0);
    if (camMode === 'side') v.root.rotation.y = Math.PI / 2 + (num('yaw', 0) * Math.PI) / 180;
    return cam;
  });
  const sun = ctx.sandboxLights.sun;
  const half = (n * sp) / 2 + 3;
  Object.assign(sun.shadow.camera, { left: -half, right: half, top: half, bottom: -half, far: 200 });
  sun.shadow.camera.updateProjectionMatrix();
  ctx.render = () => {
    const w = renderer.domElement.width, h = renderer.domElement.height;
    const pr = renderer.getPixelRatio();
    renderer.info.reset();
    renderer.setScissorTest(false);
    renderer.clear();
    renderer.setScissorTest(true);
    const tw = w / n / pr, th = h / pr;
    cams.forEach((cam, i) => {
      cam.aspect = tw / th;
      cam.updateProjectionMatrix();
      renderer.setViewport(i * tw, 0, tw, th);
      renderer.setScissor(i * tw, 0, tw, th);
      renderer.render(scene, cam);
    });
    renderer.setScissorTest(false);
    renderer.setViewport(0, 0, w / pr, h / pr);
  };
}

/** Contact sheet: rows of clips × columns of phases, one viewport and camera per cell. */
function sheetViews(ctx, cells, cols, rows, camMode) {
  const { renderer, scene } = ctx;
  const sp = 6;
  const cams = cells.map(({ v, r, c }) => {
    const x = (c - (cols - 1) / 2) * sp, z = (r - (rows - 1) / 2) * sp;
    v.root.position.set(x, 0, z);
    const cam = new THREE.PerspectiveCamera(Number(params.get('fov') || 42), 1, 0.1, 100);
    const d = num('dist', 3.4), cy = num('cy', 0.9);
    if (camMode === 'side') {
      v.root.rotation.y = Math.PI / 2 + (num('yaw', 0) * Math.PI) / 180;
      cam.position.set(x, cy + 0.3, z + d);
    } else if (camMode === 'three') cam.position.set(x + d * 0.62, cy + 0.45, z + d * 0.78);
    else if (camMode === 'threeR') cam.position.set(x - d * 0.62, cy + 0.45, z + d * 0.78);
    else if (camMode === 'low') cam.position.set(x + d * 0.9, 0.35, z + d * 0.45);
    else if (camMode === 'top') cam.position.set(x, d * 1.1, z + 1.0);
    else cam.position.set(x, cy + 0.45, z + d);
    cam.lookAt(x, camMode === 'top' ? 0.2 : cy, z);
    return cam;
  });
  const sun = ctx.sandboxLights.sun;
  const half = (Math.max(cols, rows) * sp) / 2 + 4;
  Object.assign(sun.shadow.camera, { left: -half, right: half, top: half, bottom: -half, far: 200 });
  sun.shadow.mapSize.set(4096, 4096);
  if (sun.shadow.map) {
    sun.shadow.map.dispose();
    sun.shadow.map = null;
  }
  sun.shadow.camera.updateProjectionMatrix();
  ctx.render = () => {
    const pr = renderer.getPixelRatio();
    const w = renderer.domElement.width / pr, h = renderer.domElement.height / pr;
    renderer.info.reset();
    renderer.setScissorTest(false);
    renderer.clear();
    renderer.setScissorTest(true);
    const tw = w / cols, th = h / rows;
    cells.forEach(({ r, c }, i) => {
      const cam = cams[i];
      cam.aspect = tw / th;
      cam.updateProjectionMatrix();
      const y = h - (r + 1) * th;
      renderer.setViewport(c * tw, y, tw, th);
      renderer.setScissor(c * tw, y, tw, th);
      renderer.render(scene, cam);
    });
    renderer.setScissorTest(false);
    renderer.setViewport(0, 0, w, h);
  };
}

export async function run(view) {
  const ctx = await createSandboxCtx();
  const { scene, camera, renderer } = ctx;
  const sunMode = params.get('sun') || 'side';
  const sun = ctx.sandboxLights.sun;
  if (sunMode === 'side') sun.position.set(38, 26, 6);
  else if (sunMode === 'front') sun.position.set(-12, 30, 34);
  else if (sunMode === 'back') sun.position.set(10, 25, -35);
  sun.shadow.camera.left = -8;
  sun.shadow.camera.right = 8;
  sun.shadow.camera.top = 8;
  sun.shadow.camera.bottom = -8;
  sun.shadow.bias = -0.0004;
  sun.shadow.normalBias = 0.02;
  sun.shadow.camera.updateProjectionMatrix();
  const env = skyEnv(renderer, sun.position.clone().normalize());
  scene.environment = env;
  scene.background = new THREE.Color(0xa9bccb);
  ctx.world.envMap = env;
  renderer.toneMappingExposure = 0.95;
  const chars = createCharacters(ctx);
  const visuals = [];
  const add = (kind, opts, x, z, facing = 0) => {
    const v = chars.create(kind, opts);
    v.root.position.set(x, 0, z);
    v.root.rotation.y = facing;
    scene.add(v.root);
    visuals.push(v);
    return v;
  };
  const look = (pos, target) => {
    camera.position.set(...pos);
    camera.lookAt(new THREE.Vector3(...target));
  };
  const info = { view, visuals: [] };
  window.__chars = { ctx, chars, visuals, info, CLIPS, KINDS, THREE };
  const updaters = [];
  let frozen = false;

  const kindsAll = [
    ['spartan', {}], ['levy', { variant: 'mede' }], ['levy', { variant: 'kissian' }], ['sparabara', {}],
    ['immortal', {}], ['archer', {}], ['captain', {}], ['helot', {}], ['dummy', {}],
  ];
  const seed0 = num('seed', 3);
  const camMode = params.get('cam') || 'front';

  if (view === 'lineup') {
    const n = kindsAll.length, sp = num('spacing', 0.9);
    kindsAll.forEach(([k, o], i) => add(k, Object.assign({ seed: seed0 + i * 17 }, o), (i - (n - 1) / 2) * sp, 0));
    const d = num('dist', 4);
    if (params.get('guard') === '1') visuals.forEach((v) => v.anim.setGuard(true));
    look([0, 1.66, d], [0, 1.15, 0]);
  } else if (view === 'close') {
    const kind = params.get('kind') || 'spartan';
    const v = add(kind, { seed: seed0, variant: params.get('variant') || undefined, blazon: params.get('blazon') || undefined }, 0, 0, (num('yaw', 0) * Math.PI) / 180);
    if (params.get('guard') === '1') v.anim.setGuard(true);
    const d = num('dist', 1.6);
    const ty = num('ty', 1.45);
    if (camMode === 'side') look([d, 1.66, 0.0], [0, ty, 0]);
    else if (camMode === 'three') look([d * 0.7, 1.66, d * 0.7], [0, ty, 0]);
    else if (camMode === 'back') look([0, 1.66, -d], [0, ty, 0]);
    else look([0, 1.66, d], [0, ty, 0]);
  } else if (view === 'clips') {
    // one actor per phase, each frozen at that phase of the clip
    const kind = params.get('kind') || 'spartan';
    const clip = params.get('clip') || 'thrust';
    const phases = (params.get('phases') || '0.3,0.5,0.6,0.7,0.95').split(',').map(Number);
    const sp = num('spacing', 1.5);
    const dur = num('duration', CLIPS[clip].duration);
    phases.forEach((ph, i) => {
      const v = add(kind, { seed: seed0, variant: params.get('variant') || undefined, weapon: params.get('weapon') || undefined }, (i - (phases.length - 1) / 2) * sp, 0, (num('yaw', 0) * Math.PI) / 180);
      if (params.get('guard') !== '0') v.anim.setGuard(true);
      for (let k = 0; k < 30; k++) v.anim.update(1 / 60);
      v.anim.play(clip, { duration: dur });
      const steps = Math.max(1, Math.round((ph * dur) / (1 / 120)));
      for (let k = 0; k < steps; k++) v.anim.update((ph * dur) / steps);
    });
    frozen = true;
    const w = phases.length * sp;
    if (camMode === 'side') look([w * 0.95 + 2, 1.5, 0], [0, 1.0, 0]);
    else if (camMode === 'three') look([w * 0.5 + 1, 1.66, w * 0.55 + 1.5], [0, 1.0, 0]);
    else look([0, 1.66, num('dist', Math.max(3.2, w * 0.75))], [0, 1.05, 0]);
    // profile: actors along x facing +X, camera in front of the row
    if (camMode === 'side') {
      visuals.forEach((v, i) => {
        v.root.position.set((i - (phases.length - 1) / 2) * sp, 0, 0);
        v.root.rotation.y = Math.PI / 2 + (num('yaw', 0) * Math.PI) / 180;
      });
      look([0, 1.3, num('dist', 6)], [0, 1.0, 0]);
    }
  } else if (view === 'walk') {
    const kind = params.get('kind') || 'levy';
    const speeds = (params.get('speeds') || '0,0.7,1.4,2.2,3.0').split(',').map(Number);
    speeds.forEach((s, i) => {
      const v = add(kind, { seed: seed0 + i }, -3, (i - (speeds.length - 1) / 2) * 1.2, Math.PI / 2);
      v.anim.setLocomotion(s);
      v.userSpeed = s;
    });
    const t0 = num('t', 1.0);
    updaters.push((dt) => {
      for (const v of visuals) v.root.position.x += v.userSpeed * dt;
    });
    // pre-roll to time t
    const steps = Math.round(t0 * 60);
    for (let k = 0; k < steps; k++) for (const u of updaters) u(1 / 60);
    for (let k = 0; k < steps; k++) for (const v of visuals) v.anim.update(1 / 60);
    look([num('cx', 0), 1.3, num('dist', 7)], [num('cx', 0), 0.9, 0]);
    if (camMode === 'side') look([visuals[0].root.position.x, 1.2, 8], [visuals[0].root.position.x, 0.8, 0]);
    frozen = params.get('live') !== '1';
  } else if (view === 'deaths') {
    const kind = params.get('kind') || 'levy';
    const clips = (params.get('clips') || 'die_back,die_forward,die_crumple,die_spear,knockdown').split(',');
    const ph = num('phase', 1.0);
    clips.forEach((clip, i) => {
      const v = add(kind, { seed: seed0 + i }, (i - (clips.length - 1) / 2) * 2.0, 0, (num('yaw', 0) * Math.PI) / 180);
      for (let k = 0; k < 30; k++) v.anim.update(1 / 60);
      v.anim.play(clip, {});
      const D = CLIPS[clip].duration;
      const steps = Math.round((ph * D + 0.05) * 60);
      for (let k = 0; k < steps; k++) v.anim.update(1 / 60);
    });
    frozen = true;
    if (camMode === 'top') look([0, 6.5, 2.5], [0, 0, 0]);
    else if (camMode === 'side') look([0, 0.6, 5], [0, 0.2, 0]);
    else look([0, 1.66, 5.5], [0, 0.4, 0]);
  } else if (view === 'sheet') {
    const kind = params.get('kind') || 'spartan';
    const clips = (params.get('clips') || 'thrust,bash,throw,hit,block_hit,cheer').split(',');
    const phases = (params.get('phases') || '0.3,0.5,0.6,0.7,0.9').split(',').map(Number);
    const cells = [];
    clips.forEach((clip, r) => {
      phases.forEach((ph, c) => {
        const v = add(kind, { seed: seed0, variant: params.get('variant') || undefined, weapon: params.get('weapon') || undefined }, 0, 0, 0);
        if (params.get('guard') !== '0') v.anim.setGuard(true);
        for (let k = 0; k < 40; k++) v.anim.update(1 / 60);
        const def = CLIPS[clip];
        const dur = num('duration', def.duration);
        v.anim.play(clip, { duration: dur });
        const total = ph * dur;
        const steps = Math.max(1, Math.ceil(total * 120));
        for (let k = 0; k < steps; k++) v.anim.update(total / steps);
        cells.push({ v, r, c });
      });
    });
    frozen = true;
    sheetViews(ctx, cells, phases.length, clips.length, camMode);
  } else if (view === 'crowd') {
    // 22 living actors + 20 corpses, as in a busy battle frame
    const kinds = ['levy', 'levy', 'immortal', 'sparabara', 'levy', 'immortal', 'captain', 'archer', 'levy', 'immortal', 'levy', 'sparabara', 'immortal', 'levy'];
    let s = 1;
    for (let i = 0; i < 14; i++) {
      const v = add(kinds[i], { seed: s++ }, ((i % 7) - 3) * 1.3, -2.2 - Math.floor(i / 7) * 1.6, 0);
      v.anim.setGuard(i % 2 === 0);
      v.anim.setLocomotion(i % 3 === 0 ? 1.4 : 0);
    }
    for (let i = 0; i < 8; i++) {
      const v = add('spartan', { seed: s++ }, (i - 3.5) * 1.4, 0.4, Math.PI);
      v.anim.setGuard(true);
    }
    const deaths = ['die_back', 'die_forward', 'die_crumple', 'die_spear'];
    for (let i = 0; i < 20; i++) {
      const v = add(kinds[i % kinds.length], { seed: s++ }, ((i % 10) - 4.5) * 1.1, -5 - Math.floor(i / 10) * 1.8, (i * 1.7) % 6.28);
      v.anim.play(deaths[i % 4], {});
      for (let k = 0; k < 130; k++) v.anim.update(1 / 60);
      v.mesh.castShadow = false;
      v.corpse = true;
    }
    look([0, 1.66, 3.2], [0, 1.0, -4]);
  }
  if (params.get('tiles') === '1') tileViews(ctx, visuals, camMode);
  info.visuals = visuals.map((v) => ({ kind: v.kind, variant: v.variant, triangles: v.triangles }));
  ctx.loop((dt, gdt) => {
    if (frozen) return;
    for (const u of updaters) u(gdt);
    for (const v of visuals) if (!v.corpse) v.anim.update(gdt);
  });
  return window.__chars;
}
