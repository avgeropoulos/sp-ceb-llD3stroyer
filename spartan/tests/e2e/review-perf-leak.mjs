// Review (performance lens): heap stability and leaks across waves, days and restarts.
// Part A: 5 minutes of game time in one battle (Day 2, god-mode autopilot): retained heap after
//         forced GC every 30 s of game time, plus per-interval allocation volume.
// Part B: 8 restart cycles (battle → pause → restart, battle → title, day 1/2/3, training), each
//         playing 40 s; after each, back on the title screen, forced GC and a census of
//         renderer.info.memory, programs, scene objects, DOM nodes, JS listeners, bus handlers,
//         audio buffers and heap.
// Usage: node spartan/tests/e2e/review-perf-leak.mjs [minutes=5] [cycles=8]
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const MIN = Number(process.argv[2] || 5);
const CYCLES = Number(process.argv[3] || 8);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  await cdp.send('HeapProfiler.enable');
  const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));
  const gcHeap = async () => {
    await cdp.send('HeapProfiler.collectGarbage');
    await cdp.send('HeapProfiler.collectGarbage');
    return (await cdp.send('Runtime.getHeapUsage')).usedSize;
  };
  const census = () => page.evaluate(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const r = ctx.renderer;
    // Draw one real frame so lazily freed resources settle.
    g.render();
    let objects = 0;
    let skinned = 0;
    ctx.scene.traverse((o) => { objects++; if (o.isSkinnedMesh) skinned++; });
    let vmObjects = 0;
    ctx.vm.scene.traverse(() => vmObjects++);
    let handlers = 0;
    const perEvent = {};
    for (const [k, list] of ctx.events.handlers) { handlers += list.length; perEvent[k] = list.length; }
    const au = ctx.audio.stats ? ctx.audio.stats() : {};
    return {
      mode: ctx.mode,
      geometries: r.info.memory.geometries,
      textures: r.info.memory.textures,
      programs: r.info.programs.length,
      sceneObjects: objects,
      sceneChildren: ctx.scene.children.length,
      skinned,
      vmObjects,
      actors: ctx.actors.length,
      corpses: ctx.ai.corpses.length,
      stuckSpears: ctx.combat.stuckSpears.length,
      projectiles: ctx.combat.projectiles.length,
      particles: ctx.fx.particleCount,
      handlers,
      perEvent,
      audioMB: au.mb,
      audioReady: au.ready,
      audioVoices: au.voices,
      dom: document.getElementsByTagName('*').length,
    };
  });

  // ---- Part A: one long battle ----
  console.log(`== Part A: ${MIN} min of game time in battle (Day 2) ==`);
  await page.evaluate(() => {
    const rv = window.__rv;
    rv.noDraw = true;
    rv.pilot(true);
    rv.hpScale = 0.6;
    window.__game.startBattle(2);
  });
  await cdp.send('HeapProfiler.startSampling', { samplingInterval: 32768, includeObjectsCollectedByMajorGC: true, includeObjectsCollectedByMinorGC: true });
  const rowsA = [];
  const steps = Math.round((MIN * 60) / 30);
  for (let i = 0; i <= steps; i++) {
    if (i > 0) {
      await page.evaluate(() => {
        const rv = window.__rv;
        // 30 s of game time; draw a frame every 2 s so render-side caches are exercised.
        for (let k = 0; k < 15; k++) { rv.run(110, 10); rv.noDraw = false; window.__game.step(10); rv.noDraw = true; }
      });
    }
    const heap = await gcHeap();
    const m = await metrics();
    const c = await census();
    const s = await page.evaluate(() => ({ t: window.__game.ctx.time.gnow, day: window.__game.ctx.waves.day, wave: window.__game.ctx.waves.wave, state: window.__game.ctx.waves.state, mode: window.__game.ctx.mode }));
    rowsA.push({ heap, ...c, listeners: m.JSEventListeners, nodes: m.Nodes });
    console.log(`t=${s.t.toFixed(0).padStart(4)}s ${s.mode} d${s.day} w${s.wave} ${s.state.padEnd(9)} heap=${(heap / 1e6).toFixed(2)}MB geo=${c.geometries} tex=${c.textures} prog=${c.programs} objs=${c.sceneObjects} actors=${c.actors} corpses=${c.corpses} spears=${c.stuckSpears} parts=${c.particles} handlers=${c.handlers} jsListeners=${m.JSEventListeners} domNodes=${m.Nodes} audioMB=${(c.audioMB || 0).toFixed(1)}`);
  }
  const { profile } = await cdp.send('HeapProfiler.stopSampling');
  let total = 0;
  const byFn = new Map();
  const walk = (n) => {
    if (n.selfSize > 0) {
      total += n.selfSize;
      const cf = n.callFrame;
      const k = `${cf.functionName || '(anon)'} ${(cf.url || '').replace(/^.*\/game\//, '')}:${cf.lineNumber + 1}`;
      byFn.set(k, (byFn.get(k) || 0) + n.selfSize);
    }
    (n.children || []).forEach(walk);
  };
  walk(profile.head);
  const frames = MIN * 60 * 60;
  console.log(`allocation volume (sampled, incl. collected): ${(total / 1e6).toFixed(1)} MB over ${frames} frames = ${(total / frames / 1024).toFixed(2)} KB/frame`);
  for (const [k, v] of [...byFn.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25)) console.log(`  ${(v / 1e6).toFixed(2).padStart(7)} MB ${(v / frames).toFixed(0).padStart(6)} B/frame  ${k}`);

  // ---- Part B: restart cycles ----
  console.log(`\n== Part B: ${CYCLES} restart cycles, census on the title screen after each ==`);
  await page.evaluate(() => { window.__game.ctx.setMode('title'); window.__game.step(30); });
  const base = { heap: await gcHeap(), ...(await census()), m: await metrics() };
  console.log(`title baseline: heap=${(base.heap / 1e6).toFixed(2)}MB geo=${base.geometries} tex=${base.textures} prog=${base.programs} objs=${base.sceneObjects} children=${base.sceneChildren} handlers=${base.handlers} jsListeners=${base.m.JSEventListeners} domNodes=${base.m.Nodes} audioMB=${(base.audioMB || 0).toFixed(1)}`);
  const plans = [
    ['battle d1, pause→restart, quit', `g.startBattle(1); rv.run(1200,10); ctx.setMode('paused'); g.step(5); ctx.setMode('battle', { day: ctx.waves.day }); rv.run(1200,10); ctx.setMode('title');`],
    ['battle d3 to phase C, quit', `g.startBattle(3); rv.run(2400,10); ctx.setMode('title');`],
    ['training, quit', `g.startTraining(); rv.run(1800,10); ctx.setMode('title');`],
    ['battle d2, die → gameover → title', `g.startBattle(2); rv.run(1200,10); rv.god=false; ctx.player.damage(1000,{kind:'melee'}); rv.run(400,10); rv.god=true; ctx.setMode('title');`],
  ];
  for (let c = 0; c < CYCLES; c++) {
    const [name, code] = plans[c % plans.length];
    await page.evaluate((code) => {
      const g = window.__game, ctx = g.ctx, rv = window.__rv;
      rv.noDraw = true;
      rv.pilot(true);
      new Function('g', 'ctx', 'rv', code)(g, ctx, rv);
      rv.noDraw = false;
      g.step(30);
    }, code);
    const heap = await gcHeap();
    const cc = await census();
    const m = await metrics();
    console.log(`cycle ${c + 1} (${name}): heap=${(heap / 1e6).toFixed(2)}MB (${((heap - base.heap) / 1e6 >= 0 ? '+' : '')}${((heap - base.heap) / 1e6).toFixed(2)}) geo=${cc.geometries} tex=${cc.textures} prog=${cc.programs} objs=${cc.sceneObjects} children=${cc.sceneChildren} vmObjs=${cc.vmObjects} actors=${cc.actors} corpses=${cc.corpses} handlers=${cc.handlers} jsListeners=${m.JSEventListeners} domNodes=${m.Nodes} audioMB=${(cc.audioMB || 0).toFixed(1)} mode=${cc.mode}`);
    if (c === CYCLES - 1) {
      const diff = {};
      for (const k in cc.perEvent) if (cc.perEvent[k] !== (base.perEvent[k] || 0)) diff[k] = `${base.perEvent[k] || 0}→${cc.perEvent[k]}`;
      console.log('bus handler changes vs baseline:', JSON.stringify(diff));
    }
  }
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
