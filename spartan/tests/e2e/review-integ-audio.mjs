// Cross-module integration audit (b): audio seams.
// 1) Nobody calls audio.setAmbience('battle'): battle keeps the calm title bed (gulls, cicadas), never the din/drums/horns.
// 2) A Persian knocked into the Gulf screams and splashes twice (audio's kill{cause:'sea'} handler AND ai.updateSeaFall).
import { launch } from './lib.mjs';
const l = await launch();
try {
  const page = await l.newPage();
  await page.goto(`${l.game}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  await page.evaluate(() => window.__game.ctx.audio.unlock());
  await page.waitForFunction(() => window.__game.ctx.audio.stats().pending === 0 && window.__game.ctx.audio.state === 'running', null, { timeout: 240000, polling: 250 });
  const r = await page.evaluate(() => {
    const g = window.__game, c = g.ctx, A = c.audio;
    const out = {};
    g.step(5);
    out.title = { ambience: A.stats().ambience, music: A.stats().music };
    g.startBattle(1);
    const n0 = A.log.length;
    for (let i = 0; i < 60; i++) g.step(60); // 60 s of battle (intro + wave 1)
    const names = A.log.slice(n0).map((e) => e.name);
    const count = (n) => names.filter((x) => x === n).length;
    out.battle = { ambience: A.stats().ambience, music: A.stats().music, gull: count('gull'), horn: count('horn'), drum: count('drum'), warcry: count('warcry') };
    // --- 2) sea fall: player at the sea edge bashes a levy over it ---
    c.setMode('battle', { day: 1 });
    c.waves.stop();
    g.step(2);
    for (const a of [...c.actors]) if (a.team === 'persian') a.alive = false;
    g.step(2);
    c.player.pos.x = 7.4; c.player.pos.z = -1; c.player.yaw = -Math.PI / 2; // facing +X
    const lv = g.spawn('levy', 8.35, -1, { facing: -Math.PI / 2 });
    g.step(3);
    lv.pos.x = 8.35; lv.pos.z = -1;
    const n1 = A.log.length;
    const kills = [];
    const off = c.events.on('kill', (k) => kills.push({ cause: k.cause, byPlayer: k.byPlayer }));
    const res = c.combat.playerStrike({ kind: 'bash', power: 1, yaw: -Math.PI / 2, origin: c.player.pos, focus: lv });
    for (let i = 0; i < 6; i++) g.step(30);
    off();
    const seaLog = A.log.slice(n1).filter((e) => e.name === 'scream' || e.name === 'splash').map((e) => `${e.name}@+${(e.t - A.log[n1].t).toFixed(2)}s vol=${e.volume.toFixed(2)} at=${e.at}`);
    out.sea = { strike: res.map((h) => ({ killed: h.killed })), kills, seaLog, alive: lv.alive };
    return out;
  });
  console.log(JSON.stringify(r, null, 1));
  console.log('errors', await page.evaluate(() => window.__game.errors), page.errors);
} finally { await l.close(); }
