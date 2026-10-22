// (1b) Viewmodel animation direction for motion slashes in the real game: tracks the first-person hand
// (viewmodel.debug.hand, view space: +x right, +y up) through each motion slash and screenshots mid-swing.
// Usage: node spartan/tests/e2e/review-motion-vm.mjs [outDir]
import fs from 'node:fs';
import { openGame } from './review-motion-lib.mjs';
const outDir = process.argv[2] || '/tmp/claude-0/-home-user-sp-ceb-llD3stroyer/f2f80b3b-a8d1-5741-b07f-51172e4acc56/scratchpad/review/vm';
fs.mkdirSync(outDir, { recursive: true });
const G = await openGame({ source: 'inject' });
const names = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
try {
  await G.ev((R) => { R.arena(); R.play([{ kind: 'idle', duration: 1.2 }], { rot: 90 }); });
  for (let i = 0; i < 8; i++) {
    const dir = (i * Math.PI) / 4 > Math.PI ? (i * Math.PI) / 4 - 2 * Math.PI : (i * Math.PI) / 4;
    const r = await G.ev((R, g, ctx, dir) => {
      R.frames(30);
      R.clearLog();
      const s = g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir, speed: 9 }, { kind: 'idle', duration: 0.5 }], { rot: 90 });
      R.feed(s, 'inject');
      const n = R.until(() => R.evs('swing').length > 0, 120);
      const path = [];
      const h = ctx.viewmodel.debug.hand.p;
      path.push([h.x, h.y]);
      // advance to ~mid-swing (TIMING.slash.strike = 0.10 s), then draw
      for (let k = 0; k < 5; k++) { R.frames(1); path.push([h.x, h.y]); }
      R.draw();
      return { n, path, swing: R.evs('swing')[0] };
    }, dir);
    await G.page.screenshot({ path: `${outDir}/slash-${names[i]}.png` });
    const more = await G.ev((R, g, ctx) => {
      const h = ctx.viewmodel.debug.hand.p;
      const path = [];
      for (let k = 0; k < 14; k++) { R.frames(1); path.push([h.x, h.y]); }
      R.frames(40);
      return path;
    });
    const p = r.path.concat(more);
    // dominant motion: from the first sample to the sample farthest from it
    let best = 0, bi = 0;
    for (let k = 1; k < p.length; k++) { const d = Math.hypot(p[k][0] - p[0][0], p[k][1] - p[0][1]); if (d > best) { best = d; bi = k; } }
    const ang = Math.atan2(p[bi][1] - p[0][1], p[bi][0] - p[0][0]);
    const err = Math.abs(Math.atan2(Math.sin(ang - dir), Math.cos(ang - dir)));
    console.log(`${err < 0.6 ? 'ok  ' : 'FAIL'} ${names[i].padEnd(2)} swing=${r.swing && r.swing.kind}:${r.swing && r.swing.dir.toFixed(2)} hand moved ${best.toFixed(3)} toward ${(ang * 180 / Math.PI).toFixed(0)}° (want ${(dir * 180 / Math.PI).toFixed(0)}°, err ${(err * 180 / Math.PI).toFixed(0)}°)`);
  }
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
