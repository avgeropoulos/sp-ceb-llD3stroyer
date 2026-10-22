// (7a) Latency in the real game: forward-stroke onset → gesture (sensor time and frame) → swing event → strike (hit).
// Usage: node spartan/tests/e2e/review-motion-latency.mjs [source] [rate] [sensitivity]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const rate = Number(process.argv[3] || 100);
const sens = Number(process.argv[4] || 1);
const G = await openGame({ source });
try {
  const rows = await G.ev((R, g, ctx, a) => {
    R.arena();
    ctx.settings.sensitivity = a.sens;
    const opts = { rot: 90, rate: a.rate };
    R.play([{ kind: 'idle', duration: 1.2 }], opts, { source: a.source });
    const out = [];
    const cases = [
      { label: 'R speed 6', seg: { kind: 'swing', dir: 0, speed: 6 } },
      { label: 'R speed 9', seg: { kind: 'swing', dir: 0, speed: 9 } },
      { label: 'R speed 13', seg: { kind: 'swing', dir: 0, speed: 13 } },
      { label: 'D speed 9', seg: { kind: 'swing', dir: -Math.PI / 2, speed: 9 } },
      { label: 'U speed 9 (from guard)', seg: { kind: 'swing', dir: Math.PI / 2, speed: 9 } },
      { label: 'U speed 9 (low guard, windup .5)', seg: { kind: 'swing', dir: Math.PI / 2, speed: 9, windup: 0.5 } },
      { label: 'thrust', seg: { kind: 'thrust' } },
    ];
    for (const c of cases) {
      ctx.ai.clear();
      const d = g.spawn('levy', 0, -1.7, {});
      d.blockChance = d.baseBlock = 0;
      d.hp = d.maxHp = 1e6;
      d.nextAttackAt = 1e9;
      R.frames(30);
      R.clearLog();
      const idle0 = 0.4;
      const seg = c.seg;
      let onset = idle0;
      if (seg.kind === 'swing') {
        const speed = seg.speed ?? 9, windup = seg.windup ?? 0.3;
        onset += (1.875 * windup) / (0.4 * speed) + 0.06; // the forward stroke starts after the wind-up and pause
      }
      const p = R.play([{ kind: 'idle', duration: idle0 }, seg, { kind: 'idle', duration: 0.6 }], opts, { source: a.source });
      const gst = R.evs('gesture')[0];
      const sw = R.evs('swing')[0];
      const hit = R.evs('hit')[0];
      const on = p.start + onset;
      out.push({
        label: c.label,
        gestureSensor: gst ? Math.round((gst.t0 ?? 0) * 1000) : null,
        gesture: gst ? Math.round((gst.t - on) * 1000) : null,
        swing: sw ? Math.round((sw.t - on) * 1000) : null,
        strike: hit ? Math.round((hit.t - on) * 1000) : null,
        kind: (gst ? gst.type + (gst.dir != null ? ':' + R.tok(gst.dir) : '') : '-') + '/' + (sw ? sw.kind : '-'),
      });
    }
    return out;
  }, { source, rate, sens });
  console.log(`${source} ${rate} Hz sensitivity ${sens}: ms after the forward stroke begins (frame-quantized at 60 fps)`);
  for (const r of rows) console.log(`  ${r.label.padEnd(34)} ${r.kind.padEnd(16)} gesture +${r.gesture}  swing +${r.swing}  strike/hit +${r.strike}`);
} finally {
  await G.close();
}
