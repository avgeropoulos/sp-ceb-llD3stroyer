// (1) 8 slash directions in the real game: gesture → slash action → swing event → hit on a dummy in front.
// Usage: node spartan/tests/e2e/review-motion-dirs.mjs [source=inject|android|web] [rot=90] [extra synth opts JSON]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const rot = Number(process.argv[3] || 90);
const extra = JSON.parse(process.argv[4] || '{}');
const G = await openGame({ source });
try {
  if (source === 'android') await G.ev((R, g, ctx, rot) => { window.__nativeRot = rot; }, rot);
  if (source === 'web') await G.ev((R, g, ctx, rot) => { window.__webRot = rot; }, rot);
  const res = await G.ev((R, g, ctx, a) => {
    R.arena();
    const out = [];
    // settle the phone first
    R.play([{ kind: 'idle', duration: 1.5 }], a.opts, { source: a.source });
    const dirs = [0, Math.PI / 4, Math.PI / 2, (3 * Math.PI) / 4, Math.PI, (-3 * Math.PI) / 4, -Math.PI / 2, -Math.PI / 4];
    const names = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
    for (let i = 0; i < dirs.length; i++) {
      ctx.ai.clear();
      const d = g.spawn('levy', 0, -1.9, {});
      d.stats = Object.assign({}, d.stats, { windup: 99 });
      d.blockChance = 0; d.baseBlock = 0;
      d.hp = d.maxHp = 1e6;
      d.nextAttackAt = 1e9;
      R.frames(2);
      R.clearLog();
      R.play([{ kind: 'idle', duration: 0.4 }, { kind: 'swing', dir: dirs[i], speed: 9 }, { kind: 'idle', duration: 0.6 }], a.opts, { source: a.source });
      const gest = R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : ''));
      const sw = R.evs('swing').map((e) => e.kind + (e.kind === 'slash' ? ':' + R.tok(e.dir) : '') + '/' + e.src);
      const hits = R.evs('hit').map((e) => (e.target || '?') + ':' + (e.damage != null ? e.damage.toFixed(1) : ''));
      const yaw = ctx.player.yaw;
      out.push({ want: names[i], gest: gest.join(','), swing: sw.join(','), hits: hits.join(','), yaw: +yaw.toFixed(3) });
    }
    return out;
  }, { source, opts: Object.assign({ rot }, extra) });
  let bad = 0;
  for (const r of res) {
    const ok = r.gest === `slash:${r.want}` && r.swing === `slash:${r.want}/motion` && r.hits.length > 0;
    if (!ok) bad++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${r.want.padEnd(3)} gestures=[${r.gest}] swings=[${r.swing}] hits=[${r.hits}] yaw=${r.yaw}`);
  }
  console.log(`${source} rot=${rot} ${JSON.stringify(extra)}: ${8 - bad}/8 correct`);
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
