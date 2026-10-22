// Shared in-page instrumentation for the performance/memory review scripts (review-perf-*.mjs).
// Nothing here edits game source: it wraps public functions on window.__game.ctx at runtime.
//
// installProbe(page) adds window.__rv with:
//   rv.sampleRender()   re-renders the current frame and attributes every draw call to a pass
//                       (shadow/main/vm) and an owner (world mesh name, actor kind, corpse, fx, ...)
//   rv.sceneCensus()    counts visible meshes/triangles by owner by traversing ctx.scene
//   rv.pilot(on)        a god-mode autopilot: faces the nearest enemy and attacks, so waves clear
//   rv.run(frames, batch, everyFrames, onSample)  steps the game with the pilot, sampling as it goes
//   rv.timers           per-subsystem JS timing (wrapping update functions with performance.now)
export const PROBE_SRC = String.raw`(() => {
  const g = window.__game;
  const ctx = g.ctx;
  const r = ctx.renderer;
  const rv = (window.__rv = window.__rv || {});
  if (rv.installed) return true;
  rv.installed = true;

  // ---- owners ----------------------------------------------------------------------------
  let actorMap = new Map();
  function refreshActors() {
    actorMap = new Map();
    for (const a of ctx.actors) if (a.visual) actorMap.set(a.visual.root, (a.alive ? 'actor:' : 'dead:') + a.team + ':' + a.kind);
    const cs = ctx.ai.corpses || [];
    for (const c of cs) if (c.visual) actorMap.set(c.visual.root, 'corpse:' + c.kind);
  }
  function ownerOf(obj) {
    let o = obj;
    let prev = null;
    while (o) {
      const n = o.name || '';
      if (n.startsWith('visual-')) return actorMap.get(o) || 'visual-orphan:' + n;
      if (n === 'horde') return 'world:horde';
      if (n === 'world') return 'world:' + (obj.name || (prev && prev.name) || obj.type);
      if (n.startsWith('fx-')) return n === 'fx-particles' ? 'fx:' + (obj.material && obj.material.name) : 'fx:' + n;
      if (n === 'volleyArrows') return 'combat:volley';
      if (n.startsWith('prop:')) return 'combat:' + n;
      if (n.startsWith('vm')) return 'vm:' + n;
      prev = o;
      o = o.parent;
    }
    return 'other:' + obj.type + ':' + (obj.name || (obj.material && obj.material.name) || '');
  }
  rv.ownerOf = ownerOf;
  rv.refreshActors = refreshActors;

  // ---- draw-call attribution -------------------------------------------------------------
  const orig = r.renderBufferDirect;
  let rec = null;
  r.renderBufferDirect = function (camera, scene, geometry, material, object, group) {
    if (!rec) return orig.apply(this, arguments);
    const c0 = r.info.render.calls;
    const t0 = r.info.render.triangles;
    const res = orig.apply(this, arguments);
    const pass = scene === null ? 'shadow' : scene === ctx.scene ? 'main' : scene === ctx.vm.scene ? 'vm' : 'other';
    const key = pass + ' ' + ownerOf(object);
    let e = rec[key];
    if (!e) e = rec[key] = { calls: 0, tris: 0 };
    e.calls += r.info.render.calls - c0;
    e.tris += r.info.render.triangles - t0;
    return res;
  };
  rv.sampleRender = function () {
    refreshActors();
    rec = {};
    g.render();
    const out = rec;
    rec = null;
    const pass = { shadow: { calls: 0, tris: 0 }, main: { calls: 0, tris: 0 }, vm: { calls: 0, tris: 0 }, other: { calls: 0, tris: 0 } };
    const groups = {};
    for (const k in out) {
      const [p, owner] = k.split(' ');
      pass[p].calls += out[k].calls;
      pass[p].tris += out[k].tris;
      const grp = owner.startsWith('actor:') ? 'actors' : owner.startsWith('corpse:') || owner.startsWith('dead:') ? 'corpses'
        : owner.startsWith('world:horde') ? 'horde' : owner.startsWith('world:') ? 'world' : owner.startsWith('fx:') ? 'fx'
        : owner.startsWith('combat:') ? 'projectiles' : owner.startsWith('vm:') ? 'viewmodel' : 'other';
      const gk = p + ' ' + grp;
      if (!groups[gk]) groups[gk] = { calls: 0, tris: 0 };
      groups[gk].calls += out[k].calls;
      groups[gk].tris += out[k].tris;
    }
    return {
      total: { calls: r.info.render.calls, tris: r.info.render.triangles },
      pass,
      groups,
      detail: out,
      programs: r.info.programs.length,
      geometries: r.info.memory.geometries,
      textures: r.info.memory.textures,
      actors: ctx.actors.length,
      alive: ctx.actors.filter((a) => a.alive && a.team === 'persian').length,
      allies: ctx.actors.filter((a) => a.alive && a.team !== 'persian').length,
      corpses: (ctx.ai.corpses || []).length,
      particles: ctx.fx.particleCount,
      projectiles: ctx.combat.projectiles.length,
      stuckSpears: ctx.combat.stuckSpears.length,
      sceneChildren: ctx.scene.children.length,
      mode: ctx.mode,
      day: ctx.waves.day,
      phase: ctx.waves.phase,
      wave: ctx.waves.wave,
      state: ctx.waves.state,
      gnow: ctx.time.gnow,
    };
  };

  rv.sceneCensus = function () {
    refreshActors();
    const by = {};
    let objects = 0;
    ctx.scene.traverse((o) => {
      objects++;
    });
    ctx.scene.traverseVisible((o) => {
      if (!o.isMesh && !o.isPoints && !o.isLine) return;
      const k = ownerOf(o);
      if (!by[k]) by[k] = { meshes: 0, tris: 0 };
      by[k].meshes++;
      const geo = o.geometry;
      let n = geo.index ? geo.index.count : geo.attributes.position ? geo.attributes.position.count : 0;
      n = Math.min(n, geo.drawRange.count);
      const inst = o.isInstancedMesh ? o.count : geo.isInstancedBufferGeometry ? geo.instanceCount : 1;
      by[k].tris += (n / 3) * inst;
    });
    return { objects, by };
  };

  // ---- god-mode autopilot ------------------------------------------------------------------
  const p = ctx.player;
  const pdmg = p.damage;
  rv.god = true;
  p.damage = function (amount, info) {
    if (rv.god && amount >= p.hp) amount = Math.max(0, p.hp - 1);
    const res = pdmg.call(this, amount, info);
    if (rv.god && p.hp < 40) p.hp = 100;
    return res;
  };
  rv.hpScale = 1;
  const spawn = ctx.ai.spawnEnemy;
  ctx.ai.spawnEnemy = function (kind, x, z, opts) {
    const a = spawn.call(this, kind, x, z, opts);
    if (a && rv.hpScale !== 1) a.hp = a.maxHp = Math.max(1, Math.round(a.hp * rv.hpScale));
    return a;
  };
  let pilotOn = false;
  let tick = 0;
  rv.pilot = (on) => { pilotOn = on; };
  function angleDiff(a, b) {
    let d = b - a;
    while (d > Math.PI) d -= 2 * Math.PI;
    while (d < -Math.PI) d += 2 * Math.PI;
    return d;
  }
  function pilotStep() {
    if (!pilotOn || ctx.mode !== 'battle') return;
    tick++;
    if (ctx.waves.canReady) ctx.waves.ready && ctx.waves.ready();
    let best = null;
    let bd = 1e9;
    for (const a of ctx.actors) {
      if (!a.alive || a.team !== 'persian') continue;
      const dx = a.pos.x - p.pos.x;
      const dz = a.pos.z - p.pos.z;
      const d = dx * dx + dz * dz;
      if (d < bd) { bd = d; best = a; }
    }
    if (!best) {
      ctx.input.lookYaw = angleDiff(p.yaw, 0) * 0.3;
      ctx.input.moveZ = 0;
      return;
    }
    const want = Math.atan2(-(best.pos.x - p.pos.x), -(best.pos.z - p.pos.z));
    ctx.input.lookYaw = angleDiff(p.yaw, want) * 0.6;
    const d = Math.sqrt(bd);
    ctx.input.moveZ = d > 2.2 && d < 8 ? -0.6 : 0;
    if (d < 2.6) {
      const k = tick % 7;
      if (k === 0 || k === 2) g.action({ type: 'slash', dir: (tick * 1.3) % (2 * Math.PI), power: 0.9 });
      else if (k === 4) g.action({ type: 'thrust', power: 0.9 });
      else if (k === 5 && (tick % 35) === 5) g.action({ type: 'bash' });
    } else if (d < 14 && (tick % 60) === 30 && p.spearsLeft > 0 && ctx.waves.phase !== 'C') {
      g.action({ type: 'switchWeapon', weapon: 'spear' });
    } else if (d < 14 && (tick % 60) === 45 && p.weapon === 'spear' && p.hasSpearInHand) {
      g.action({ type: 'throw', power: 0.85 });
    } else if ((tick % 60) === 55 && p.weapon === 'spear') {
      g.action({ type: 'switchWeapon', weapon: 'sword' });
    }
    // Shield up when arrows are coming.
    const vol = ctx.combat.activeVolleys > 0;
    ctx.input.shieldHeld = vol;
    if (vol && !rv._shield) ctx.input.shieldPressedAt = ctx.time.gnow;
    rv._shield = vol;
  }
  rv.pilotStep = pilotStep;

  // Steps the game: pilot decisions every 'batch' frames; returns per-sample callbacks' outputs.
  // rv.noDraw skips the GPU work while stepping (SwiftShader rasterizes ~1 s/frame); samples
  // re-render explicitly. Game logic does not depend on drawing.
  rv.noDraw = false;
  const noop = () => {};
  rv.run = function (frames, batch = 10) {
    const saved = rv.noDraw ? [r.render, r.clear, r.clearDepth] : null;
    if (saved) { r.render = noop; r.clear = noop; r.clearDepth = noop; }
    try {
      for (let i = 0; i < frames; i += batch) {
        pilotStep();
        g.step(Math.min(batch, frames - i));
      }
    } finally {
      if (saved) { r.render = saved[0]; r.clear = saved[1]; r.clearDepth = saved[2]; }
    }
  };

  // ---- per-subsystem timers ----------------------------------------------------------------
  const timers = (rv.timers = {});
  rv.wrapTimers = function () {
    const targets = [
      ['player', ctx.player, 'update'], ['player.applyCamera', ctx.player, 'applyCamera'],
      ['ai', ctx.ai, 'update'], ['combat', ctx.combat, 'update'], ['waves', ctx.waves, 'update'],
      ['fx', ctx.fx, 'update'], ['viewmodel', ctx.viewmodel, 'update'], ['world', ctx.world, 'update'],
      ['audio', ctx.audio, 'update'], ['hud', ctx.hud, 'update'], ['menus', ctx.menus, 'update'],
      ['inputCtl', ctx.inputCtl, 'update'],
    ];
    for (const [name, obj, fn] of targets) {
      const f = obj[fn];
      if (typeof f !== 'function' || f.__wrapped) continue;
      const t = (timers[name] = { ms: 0, n: 0, max: 0 });
      const w = function () {
        const t0 = performance.now();
        const res = f.apply(this, arguments);
        const d = performance.now() - t0;
        t.ms += d;
        t.n++;
        if (d > t.max) t.max = d;
        return res;
      };
      w.__wrapped = true;
      obj[fn] = w;
    }
    // Render CPU time (includes SwiftShader raster when synchronous, so measured separately below).
    const rr = r.render;
    const tr = (timers['renderer.render'] = { ms: 0, n: 0, max: 0 });
    r.render = function () {
      const t0 = performance.now();
      const res = rr.apply(this, arguments);
      const d = performance.now() - t0;
      tr.ms += d;
      tr.n++;
      if (d > tr.max) tr.max = d;
      return res;
    };
  };
  rv.resetTimers = () => { for (const k in timers) timers[k] = Object.assign(timers[k], { ms: 0, n: 0, max: 0 }); };
  return true;
})()`;

export async function installProbe(page) {
  return page.evaluate(PROBE_SRC);
}

export async function bootGame(l, { width = 960, height = 540, query = '' } = {}) {
  const page = await l.newPage({ width, height });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium${query ? `&${query}` : ''}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  return page;
}
