// Review (performance lens): CPU time (process.cpuUsage, robust to machine load) of every audio
// render job the worker runs at boot, rendered in Node exactly as audio/worker.js does.
// Usage: node spartan/tests/e2e/review-perf-audio-cpu.mjs [sampleRate=48000]
import { SFX, SFX_NAMES, sfxLength, sfxRate, renderSfxGen } from '../../src/main/assets/game/js/audio/sfx.js';
import { LOOPS, loopLength, renderLoopGen } from '../../src/main/assets/game/js/audio/loops.js';

const SR = Number(process.argv[2] || 48000);
const cpu = () => { const u = process.cpuUsage(); return (u.user + u.system) / 1000; };
const run = (it) => { let n = 0; let longest = 0; let t = cpu(); for (;;) { const r = it.next(); const now = cpu(); if (now - t > longest) longest = now - t; t = now; n++; if (r.done) break; } return { yields: n, longestSliceMs: longest }; };
const rows = [];
for (const name of Object.keys(SFX)) {
  const sr = sfxRate(name, SR);
  const t0 = cpu();
  let longest = 0;
  for (let v = 0; v < SFX[name].variants; v++) {
    const out = new Float32Array(sfxLength(name, sr));
    const r = run(renderSfxGen(name, out, sr, v));
    longest = Math.max(longest, r.longestSliceMs);
  }
  rows.push(['sfx:' + name, cpu() - t0, longest, SFX[name].variants * sfxLength(name, sr) * 4]);
}
for (const name of Object.keys(LOOPS)) {
  const t0 = cpu();
  const out = new Float32Array(loopLength(name));
  const r = run(renderLoopGen(name, out));
  rows.push(['loop:' + name, cpu() - t0, r.longestSliceMs, loopLength(name) * 4]);
}
rows.sort((a, b) => b[1] - a[1]);
const total = rows.reduce((s, r) => s + r[1], 0);
const bytes = rows.reduce((s, r) => s + r[3], 0);
console.log(`audio render CPU at ${SR} Hz: total ${total.toFixed(0)} ms, ${(bytes / 1048576).toFixed(1)} MB of Float32 buffers`);
console.log('job                CPU ms   longest non-yielding slice ms   MB');
for (const [k, ms, sl, b] of rows.slice(0, 16)) console.log(`  ${k.padEnd(18)} ${ms.toFixed(0).padStart(7)} ${sl.toFixed(1).padStart(12)} ${(b / 1048576).toFixed(2).padStart(10)}`);
console.log(`phone estimate (×4–6): total ${(total * 4 / 1000).toFixed(1)}–${(total * 6 / 1000).toFixed(1)} s on one worker; slowest job ${(rows[0][1] * 4 / 1000).toFixed(1)}–${(rows[0][1] * 6 / 1000).toFixed(1)} s (watchdog: 3 s before the first reply, 30 s per job after)`);
console.log(`main-thread fallback: pumpMain budget 3 ms/frame in battle, but the longest non-yielding generator slice is ${Math.max(...rows.map((r) => r[2])).toFixed(1)} ms here (×4–6 on a phone)`);
