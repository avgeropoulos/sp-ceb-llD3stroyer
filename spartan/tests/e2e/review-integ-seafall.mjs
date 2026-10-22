// Cross-module integration audit (b): a Persian bashed into the Gulf screams twice and splashes twice,
// because audio's kill{cause:'sea'} handler (scream now + splash 1.05 s later) AND ai.updateSeaFall
// (scream at the lip + splash on reaching the water) both play them.
// Frames are stepped one at a time with a real-time pause so audio's 12 ms same-name dedupe can't merge them.
import { launch } from './lib.mjs';
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  await page.evaluate(() => window.__game.ctx.audio.unlock());
  await page.waitForFunction(() => window.__game.ctx.audio.stats().pending === 0 && window.__game.ctx.audio.state === 'running', null, { timeout: 240000, polling: 250 });
  const r = await page.evaluate(async () => {
    const g = window.__game, c = g.ctx, A = c.audio;
    c.setMode('battle', { day: 1 }); c.waves.stop(); g.step(2);
    for (const a of [...c.actors]) if (a.team === 'persian') a.alive = false;
    g.step(2);
    c.player.pos.x = 7.4; c.player.pos.z = -1; c.player.yaw = -Math.PI / 2;
    const lv = g.spawn('levy', 8.35, -1, { facing: -Math.PI / 2 });
    g.step(3); lv.pos.x = 8.35; lv.pos.z = -1;
    const t0 = c.time.gnow, a0 = A.context.currentTime;
    let seen = A.log.length;
    const out = [];
    const viaApi = [];
    const oa = A.play; A.play = (n, o) => { if (n === 'scream' || n === 'splash') viaApi.push(n); return oa(n, o); };
    const grab = (who) => {
      for (; seen < A.log.length; seen++) {
        const e = A.log[seen];
        if (e.name !== 'scream' && e.name !== 'splash') continue;
        out.push(`${e.name}: game +${(c.time.gnow - t0).toFixed(2)}s, sounds at audio +${(e.t - a0).toFixed(2)}s, vol ${e.volume.toFixed(2)}, from ${who()}`);
      }
    };
    c.combat.playerStrike({ kind: 'bash', power: 1, yaw: -Math.PI / 2, origin: c.player.pos, focus: lv });
    grab(() => "audio kill{cause:'sea'} handler");
    for (let i = 0; i < 100; i++) {
      const n = viaApi.length;
      g.step(1);
      grab(() => (viaApi.length > n ? 'ai.updateSeaFall via ctx.audio.play' : '?'));
      await new Promise((res) => setTimeout(res, 15));
    }
    A.play = oa;
    return out;
  });
  console.log(r.join('\n'));
  console.log('errors', await page.evaluate(() => window.__game.errors), page.errors);
} finally { await l.close(); }
