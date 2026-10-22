// Cross-module integration audit (d): world state that outlives a reset.
//  1) The volley shade (world keeps its own volley expiry list; nothing resets world on Restart/Quit).
//  2) The Persian horde stays hidden on the title screen after visiting the Agoge (only dayStart restores it).
// Saves title screenshots before/after the Agoge to the given directory.
import { launch } from './lib.mjs';
const outDir = process.argv[2] || '/tmp';
const l = await launch();
try {
  const page = await l.newPage({ width: 640, height: 360 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const shot = async (name) => page.screenshot({ path: `${outDir}/${name}`, timeout: 300000 });
  // 1) Restart right after a volley is loosed.
  const shade = await page.evaluate(() => {
    const g = window.__game, c = g.ctx;
    g.startBattle(1); g.step(10);
    c.combat.volley({ count: 300, duration: 1.0, warn: 0.2 });
    g.step(20); // past 'loosed'
    const atRestart = c.world.shade;
    const sunBefore = c.world.sun.intensity;
    c.setMode('paused'); c.setMode('battle', { day: 1 }); // pause → Restart
    const trace = [];
    for (let i = 0; i < 12; i++) { g.step(30); trace.push([+(i * 0.5 + 0.5).toFixed(1), +c.world.shade.toFixed(2), +c.world.sun.intensity.toFixed(2)]); }
    return { atRestart: +atRestart.toFixed(2), sunBefore: +sunBefore.toFixed(2), afterRestart_sec_shade_sun: trace, activeVolleysInCombat: c.combat.activeVolleys };
  });
  console.log('shade', JSON.stringify(shade));
  // 2) Title before and after the Agoge.
  const a = await page.evaluate(() => { const g = window.__game, c = g.ctx; c.setMode('title'); g.step(400); return { density: c.world.horde.density, bodies: c.world.horde.meshes[0].count }; });
  await shot('integ-title-before-agoge.png');
  const b = await page.evaluate(() => { const g = window.__game, c = g.ctx; g.startTraining(); g.step(30); c.setMode('paused'); c.setMode('title'); g.step(400); return { density: c.world.horde.density, bodies: c.world.horde.meshes[0].count }; });
  await shot('integ-title-after-agoge.png');
  console.log('title horde before Agoge', JSON.stringify(a), 'after Agoge', JSON.stringify(b));
  console.log('errors', await page.evaluate(() => window.__game.errors), page.errors);
} finally { await l.close(); }
