// Cross-module integration audit (e): settings.blood = false must mean "no blood" (DESIGN §9/§11 fx),
// but ai.js/characters.js still paint wounded and dead Persians with the blood-splatter material.
// Writes a screenshot pair (blood off) to the given directory.
import { launch } from './lib.mjs';
const outDir = process.argv[2] || '/tmp';
const l = await launch();
try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const r = await page.evaluate(() => {
    const g = window.__game, c = g.ctx;
    c.settings.blood = false;
    g.startBattle(1); c.waves.stop(); g.step(5);
    for (const a of [...c.actors]) if (a.team === 'persian') a.alive = false;
    g.step(3);
    const lv = g.spawn('immortal', 0.3, -2.0, { facing: 0 });
    const lv2 = g.spawn('levy', -0.9, -2.4, { facing: 0 });
    g.step(30);
    const lvl = (a) => a.visual.mesh.material[0].userData.uBlood.value;
    const before = [lvl(lv), lvl(lv2)];
    // Wound one (no kill) and kill the other, through the real hit path.
    lv.takeHit({ damage: lv.hp * 0.7, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, ignoreGuard: true, byPlayer: true, attacker: 'player' });
    lv2.takeHit({ damage: 999, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, ignoreGuard: true, byPlayer: true, attacker: 'player' });
    g.step(90);
    return { blood: c.settings.blood, uBloodBefore: before, uBloodAfter: [lvl(lv), lvl(lv2)], hp: lv.hp, corpse: !lv2.alive, decals: c.fx.decalCount };
  });
  console.log(JSON.stringify(r));
  await page.screenshot({ path: `${outDir}/integ-blood-off.png`, timeout: 300000 });
  console.log('errors', await page.evaluate(() => window.__game.errors), page.errors);
} finally { await l.close(); }
