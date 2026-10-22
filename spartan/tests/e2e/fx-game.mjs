// fx inside the real game (index.html + main.js): pre-warmed programs, a player slash (blood,
// hit-stop, haptics), and a player spear throw driving the spear cam through combat.js, with
// screenshots of each moment. Usage: node spartan/tests/e2e/fx-game.mjs [outDir]
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const outDir = process.argv[2] || '.';
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
let failed = 0;
/** Saves the WebGL canvas (preserveDrawingBuffer is on with manual=1); faster than page.screenshot here. */
async function snap(page, name) {
  const url = await page.evaluate(() => document.getElementById('scene').toDataURL('image/png'));
  fs.writeFileSync(path.join(outDir, name), Buffer.from(url.split(',')[1], 'base64'));
}
const step = (page, n = 1) => page.evaluate((n) => {
  const g = window.__game;
  const out = [];
  for (let i = 0; i < n; i++) {
    g.step(1);
    const t = g.ctx.time;
    out.push({ scale: t.scale, hitStop: t.hitStop, rt: t.playerRealtime, gdt: t.gdt, now: t.now, phase: g.ctx.fx.spearCamPhase, particles: g.ctx.fx.particleCount });
  }
  return out;
}, n);

try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.game}?manual=1&mode=battle&seed=7&quality=medium&nohud=1`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });

  // 1. Pre-warm: main.js compiled the fx programs before the first frame (they're hidden since).
  const boot = await page.evaluate(() => {
    const g = window.__game;
    const names = (g.ctx.renderer.info.programs || []).map((p) => p.name).filter((n) => /^fx-/.test(n)).sort();
    return { names, errors: g.errors.slice() };
  });
  console.log(`fx programs after boot: ${boot.names.join(', ')}`);
  assert.deepEqual(boot.names, ['fx-decals', 'fx-soft', 'fx-sparks', 'fx-streaks']);

  // A quiet pass: no waves, one Mede in front of the player; haptics and events recorded.
  await page.evaluate(() => {
    const g = window.__game;
    const ctx = g.ctx;
    ctx.waves.stop();
    ctx.ai.clear();
    window.__log = { haptics: [], events: [] };
    ctx.platform.haptics = {
      pulse: (ms, s = 1) => window.__log.haptics.push(['pulse', ms, s]),
      pattern: (a) => window.__log.haptics.push(['pattern', ...a]),
    };
    for (const name of ['hit', 'kill', 'spearThrown', 'spearImpact', 'spearCam']) {
      ctx.events.on(name, (e) => window.__log.events.push({ name, t: ctx.time.now, phase: e && e.phase, killed: e && e.killed }));
    }
    ctx.player.pos.set(0, 0, 0);
    ctx.player.yaw = 0;
    ctx.player.pitch = 0;
    window.__mede = g.spawn('levy', 0.1, -1.9, { facing: 0 });
  });
  await step(page, 40);

  // 2. A player slash on the Mede: blood, a 50 ms hit-stop, a 22 ms haptic pulse.
  await page.evaluate(() => {
    window.__log.haptics.length = 0;
    window.__log.events.length = 0;
    window.__game.action({ type: 'slash', dir: Math.PI - 0.3, power: 0.95 });
  });
  const slash = await step(page, 8);
  const slashLog = await page.evaluate(() => window.__log);
  await snap(page, 'game-slash-blood.png');
  const maxHs = Math.max(...slash.map((s) => s.hitStop));
  console.log(`slash: hits ${slashLog.events.filter((e) => e.name === 'hit').length}, max hitStop ${maxHs.toFixed(3)}, particles ${Math.max(...slash.map((s) => s.particles))}, haptics ${JSON.stringify(slashLog.haptics)}`);
  assert.ok(slashLog.events.some((e) => e.name === 'hit'), 'the slash hit');
  assert.ok(maxHs > 0.03, 'hit-stop on a player hit');
  assert.ok(slashLog.haptics.length >= 1, 'haptic at the strike');
  assert.ok(Math.max(...slash.map((s) => s.particles)) > 10, 'blood particles');
  await step(page, 20);
  await snap(page, 'game-slash-after.png');

  // 3. The spear cam through combat.throwSpear: a Mede at 14 m, a touch throw.
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.ai.clear();
    g.action({ type: 'switchWeapon', weapon: 'spear' });
  });
  await step(page, 60);
  await page.evaluate(() => {
    const g = window.__game;
    window.__target = g.spawn('levy', 0.6, -14, { facing: 0 });
    window.__log.events.length = 0;
    window.__log.haptics.length = 0;
  });
  await step(page, 2);
  await page.evaluate(() => window.__game.action({ type: 'throw', power: 0.85 }));
  const run = [];
  const shots = { trail: false, impact: false, ease: false };
  let trailFrames = 0;
  let impactFrames = 0;
  for (let i = 0; i < 400; i++) {
    const [s] = await step(page, 1);
    run.push(s);
    if (s.phase === 'trail' && ++trailFrames === 40 && !shots.trail) {
      shots.trail = true;
      await snap(page, 'game-spearcam-trail.png');
    }
    if (s.phase === 'impact' && ++impactFrames === 25 && !shots.impact) {
      shots.impact = true;
      await snap(page, 'game-spearcam-orbit.png');
    }
    if (s.phase === 'ease' && !shots.ease && run.filter((x) => x.phase === 'ease').length === 10) {
      shots.ease = true;
      await snap(page, 'game-spearcam-ease.png');
    }
    if (run.length > 30 && s.phase === 'off' && run[run.length - 2].phase === 'off') break;
  }
  await snap(page, 'game-spearcam-end.png');
  const spearLog = await page.evaluate(() => window.__log);
  const phases = spearLog.events.filter((e) => e.name === 'spearCam').map((e) => e.phase);
  const t0 = spearLog.events.find((e) => e.name === 'spearCam' && e.phase === 'start');
  const rel = (p) => {
    const e = spearLog.events.find((x) => x.name === 'spearCam' && x.phase === p);
    return e && t0 ? (e.t - t0.t).toFixed(3) : '-';
  };
  const stats = await page.evaluate(() => window.__game.stats());
  console.log(`spear cam phases ${phases.join(' → ')} at +${rel('impact')} s / +${rel('end')} s; trail scale ${run.find((s) => s.phase === 'trail')?.scale.toFixed(3)}; orbit scale ${run.find((s) => s.phase === 'impact')?.scale}; haptics ${JSON.stringify(spearLog.haptics)}`);
  console.log(`end: scale ${run[run.length - 1].scale}, playerRealtime ${run[run.length - 1].rt}; stats ${JSON.stringify(stats)}`);
  assert.deepEqual(phases, ['start', 'impact', 'end'], 'spear cam start → impact → end');
  assert.equal(run[run.length - 1].scale, 1);
  assert.equal(run[run.length - 1].rt, false);
  assert.deepEqual(spearLog.haptics.slice(0, 1), [['pattern', 0, 25, 60, 25]], 'spear release haptic');
  assert.ok(spearLog.haptics.some((h) => h.join() === 'pattern,0,30,40,60'), 'spear impact haptic');

  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors || []));
  if (errs.length) throw new Error(`page errors:\n${errs.join('\n')}`);
  console.log('PASS fx in the real game');
} catch (err) {
  failed++;
  console.log(`FAIL ${String(err && err.stack || err).split('\n').slice(0, 10).join('\n  ')}`);
} finally {
  await l.close();
}
process.exitCode = failed ? 1 : 0;
