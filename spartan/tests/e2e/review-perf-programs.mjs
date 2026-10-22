// Review (performance lens): shader programs compiled after boot. For each quality, boots the
// game and then walks through every program-relevant situation, drawing a frame after each and
// recording renderer.info.programs growth (and the render time of the frame that grew it):
// time of day x5, volley shade, blood levels, spear cam, each enemy kind's first appearance,
// every fx emitter and decal kind, splash, fall into the sea, training, settings toggles.
// Usage: node spartan/tests/e2e/review-perf-programs.mjs [qualities=low,medium,high]
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const qualities = (process.argv[2] || 'low,medium,high').split(',');
const l = await launch();
try {
  for (const q of qualities) {
    const page = await l.newPage({ width: 320, height: 180 });
    await page.goto(`${l.game}?manual=1&seed=7&quality=${q}`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
    await installProbe(page);
    const res = await page.evaluate(() => {
      const g = window.__game, ctx = g.ctx, r = ctx.renderer, rv = window.__rv;
      const log = [];
      const progNames = () => r.info.programs.map((p) => p.name + ':' + p.cacheKey.slice(0, 40));
      let prev = r.info.programs.length;
      let prevNames = new Set(progNames());
      const boot = prev;
      const step = (label, fn, frames = 2) => {
        try { fn(); } catch (e) { log.push(`${label}: ERROR ${e && e.message}`); }
        rv.noDraw = true;
        if (frames > 1) g.step(frames - 1);
        rv.noDraw = false;
        const t0 = performance.now();
        g.step(1);
        const ms = performance.now() - t0;
        const n = r.info.programs.length;
        if (n !== prev) {
          const names = progNames();
          const added = names.filter((x) => !prevNames.has(x));
          log.push(`${label}: programs ${prev} -> ${n} (+${n - prev}) frame ${ms.toFixed(0)} ms; new: ${added.join(', ')}`);
          prevNames = new Set(names);
        }
        prev = n;
      };
      step('title frames', () => {}, 30);
      for (const t of ['dawn', 'morning', 'noon', 'afternoon', 'dusk']) step(`title setTimeOfDay(${t})`, () => ctx.world.setTimeOfDay(t));
      step('training start', () => g.startTraining(), 120);
      step('training 10 s', () => {}, 600);
      step('battle day 1 start', () => g.startBattle(1), 60);
      for (const k of ['levy', 'sparabara', 'immortal', 'archer', 'captain', 'helot']) step(`spawn ${k}`, () => g.spawn(k, 1, -4), 20);
      step('spawn levy kissian', () => g.spawn('levy', -1, -4, { variant: 'kissian' }), 20);
      step('player slashes', () => { for (let i = 0; i < 4; i++) g.action({ type: 'slash', dir: i, power: 1 }); }, 60);
      step('player thrust', () => g.action({ type: 'thrust', power: 1 }), 30);
      step('player bash', () => g.action({ type: 'bash', power: 1 }), 30);
      step('shield up', () => { ctx.input.shieldHeld = true; ctx.input.shieldPressedAt = ctx.time.gnow; }, 30);
      step('shield down', () => { ctx.input.shieldHeld = false; }, 10);
      const P = { x: 0.5, y: 1.3, z: -2 };
      step('fx.blood', () => ctx.fx.blood(P, { x: 0, y: 0.3, z: 1 }, 1), 10);
      step('fx.sparks', () => ctx.fx.sparks(P, { x: 0, y: 0, z: 1 }, 1), 10);
      step('fx.dust', () => ctx.fx.dust({ x: 0.5, y: 0, z: -2 }, 1), 10);
      step('fx.splinters', () => ctx.fx.splinters(P, { x: 0, y: 0, z: 1 }, 1), 10);
      step('fx.splash', () => ctx.fx.splash({ x: 13, y: -1.6, z: -6 }), 10);
      for (const k of ['blood', 'pool', 'spot', 'scuff']) step(`fx.decal ${k}`, () => ctx.fx.decal({ x: 0.3, y: 0, z: -2.5 }, k, 1), 5);
      step('fx.shake/hitStop/slowMo', () => { ctx.fx.shake(0.03, 0.3); ctx.fx.hitStop(0.1); ctx.fx.slowMo(0.3, 0.5); }, 60);
      step('arrow volley (front)', () => ctx.combat.volley({ count: 300, duration: 1, warn: 0.5 }), 240);
      step('arrow volley (behind)', () => ctx.combat.volley({ count: 200, duration: 1, warn: 0.5, fromBehind: true }), 240);
      step('spear throw (spear cam)', () => { g.action({ type: 'switchWeapon', weapon: 'spear' }); }, 60);
      step('spear throw release', () => g.action({ type: 'throw', power: 1 }), 20);
      step('spear cam flight', () => {}, 120);
      step('spear into the sea', () => { ctx.combat.throwSpear({ origin: { x: 6, y: 1.6, z: -2 }, velocity: { x: 9, y: 3, z: -2 }, power: 1, byPlayer: false }); }, 150);
      step('arrows on allies (shield stuck arrows)', () => { const a = ctx.actors.find((x) => x.team === 'greek' && x.alive); if (a) ctx.combat.shootArrow(ctx.actors.find((x) => x.role === 'archer') || { pos: { x: 0, y: 0, z: -15 }, facing: 0, alive: true }, a); }, 120);
      // Kill everything nearby: deaths, blood levels, corpses (via a fake attacker).
      step('kill all enemies', () => { for (const v of ctx.actors.filter((a) => a.alive && a.team === 'persian')) ctx.combat.actorStrike({ alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI }, { target: v, damage: 9999, reach: 2 }); }, 120);
      step('enemy falls into the sea', () => { const v = g.spawn('levy', 8.2, -3); ctx.combat.actorStrike({ alive: true, pos: { x: 7.2, y: 0, z: -3 }, facing: Math.PI / 2 }, { target: v, damage: 9999, reach: 2, kind: 'bash' }); }, 240);
      step('ally bloodied + killAlly', () => { const a = ctx.actors.find((x) => x.role === 'ally' && x.alive); if (a) a.visual.setBloodied(0.5); ctx.ai.killAlly && ctx.ai.killAlly('Leonidas'); }, 120);
      step('player hurt (overlay hurt, vignette)', () => { rv.god = true; ctx.player.damage(70, { kind: 'melee' }); }, 60);
      step('helmet view off', () => { ctx.settings.helmetView = false; }, 10);
      step('helmet view on', () => { ctx.settings.helmetView = true; }, 10);
      step('blood off', () => { ctx.settings.blood = false; ctx.fx.blood(P, { x: 0, y: 0, z: 1 }, 1); }, 10);
      step('blood on', () => { ctx.settings.blood = true; }, 10);
      for (const t of ['dawn', 'noon', 'afternoon', 'dusk', 'morning']) step(`battle setTimeOfDay(${t})`, () => ctx.world.setTimeOfDay(t), 5);
      step('day 2 start (noon)', () => g.startBattle(2), 300);
      step('day 3 start (dawn → morning)', () => g.startBattle(3), 600);
      step('pause', () => ctx.setMode('paused'), 5);
      step('resume', () => ctx.resume(), 5);
      step('player death → gameover', () => { rv.god = false; ctx.player.damage(1000, { kind: 'melee' }); }, 400);
      step('title', () => ctx.setMode('title'), 30);
      return { boot, end: r.info.programs.length, log, errors: g.errors.slice(0, 5) };
    });
    console.log(`\n[quality=${q}] programs after boot=${res.boot}, at end=${res.end}`);
    for (const line of res.log) console.log('  ' + line);
    if (res.errors.length) console.log('  errors', res.errors);
    await page.close();
  }
} finally {
  await l.close();
}
