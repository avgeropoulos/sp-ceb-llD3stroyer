// Review (performance lens): boot cost per boot step as renderer main-thread CPU time (CDP
// Performance ThreadTime, immune to machine load) next to wall time, plus the audio worker's
// render time and memory at boot. Real RAF loop (not manual), phone-like 915x412 viewport.
// Usage: node spartan/tests/e2e/review-perf-bootcpu.mjs [quality=medium] [runs=1]
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const runs = Number(process.argv[3] || 1);
const l = await launch();
try {
  for (let run = 0; run < runs; run++) {
    const page = await l.newPage({ width: 915, height: 412 });
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Performance.enable', { timeDomain: 'threadTicks' });
    await cdp.send('HeapProfiler.enable');
    const metric = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((x) => [x.name, x.value]));
    const t0 = Date.now();
    await page.goto(`${l.game}?seed=7&quality=${quality}`, { waitUntil: 'commit' });
    const marks = [];
    let last = null;
    const m0 = await metric();
    for (;;) {
      let s;
      try {
        s = await page.evaluate(() => ({ msg: (document.getElementById('boot-msg') || {}).textContent || '(loading modules)', booted: !!window.__booted }));
      } catch { await new Promise((r) => setTimeout(r, 50)); continue; }
      if (s.msg !== last || s.booted) {
        const m = await metric();
        marks.push({ wall: Date.now() - t0, msg: s.booted ? 'BOOTED (first frame drawn)' : s.msg, cpu: (m.ThreadTime - (m0.ThreadTime || 0)) * 1000, script: (m.ScriptDuration - (m0.ScriptDuration || 0)) * 1000 });
        last = s.msg;
      }
      if (s.booted) break;
      await new Promise((r) => setTimeout(r, 30));
    }
    console.log(`\n=== boot run ${run + 1}, quality=${quality} ===`);
    console.log('step (entered at)                         wall ms   main-thread CPU ms (cumulative)   Δcpu in previous step');
    let prevCpu = 0;
    for (const k of marks) {
      console.log(`  ${k.msg.padEnd(36)} ${String(k.wall).padStart(7)}   ${k.cpu.toFixed(0).padStart(8)}   ${(k.cpu - prevCpu).toFixed(0).padStart(8)}`);
      prevCpu = k.cpu;
    }
    // Audio worker progress and memory right after boot.
    const after = await page.evaluate(() => {
      const ctx = window.__game.ctx;
      const a = ctx.audio && ctx.audio.stats ? ctx.audio.stats() : null;
      return {
        audio: a && { workers: a.workers, ready: a.ready, pending: a.pending, mb: +(a.mb || 0).toFixed(1), renderMs: Math.round(a.renderMs) },
        jsHeapMB: performance.memory ? +(performance.memory.usedJSHeapSize / 1e6).toFixed(1) : null,
        info: ctx.renderer.info.memory,
        programs: ctx.renderer.info.programs.length,
        dom: document.getElementsByTagName('*').length,
        charStats: ctx.characters.stats,
      };
    });
    await cdp.send('HeapProfiler.collectGarbage');
    const heap = await cdp.send('Runtime.getHeapUsage');
    console.log(`after boot: JS heap used ${(heap.usedSize / 1e6).toFixed(1)} MB (total ${(heap.totalSize / 1e6).toFixed(1)} MB); renderer.info.memory ${JSON.stringify(after.info)}; programs ${after.programs}; DOM ${after.dom}`);
    console.log(`characters ${JSON.stringify(after.charStats)}; audio at boot ${JSON.stringify(after.audio)}`);
    // Wait for the audio worker to finish its boot queue (real event loop).
    const tA = Date.now();
    const done = await page.evaluate(async () => { const a = window.__game.ctx.audio; if (a.prerender) await a.prerender(); const s = a.stats(); return { ready: s.ready, mb: +(s.mb || 0).toFixed(1), renderMs: Math.round(s.renderMs), doneAfterStartMs: Math.round(s.doneAt - s.startedAt), workers: s.workers }; });
    console.log(`audio prerender complete ${Date.now() - tA} ms after boot: ${JSON.stringify(done)}`);
    await page.close();
  }
} finally {
  await l.close();
}
