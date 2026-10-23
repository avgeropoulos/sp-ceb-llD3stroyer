// Boot cost per boot step (fixer "boot"), as review-perf-bootcpu.mjs measures it (CDP
// Performance ThreadTime main-thread CPU, immune to machine load, next to wall time), but every
// launch reuses ONE browser context, so launch 2+ find the texture pixels that launch 1 cached in
// IndexedDB, as on a phone. Also prints what the textures module did (textures.stats).
// Usage: node spartan/tests/e2e/boot-bootcpu.mjs [quality=medium] [launches=2] [reference textures.js to serve instead]
import fs from 'node:fs';
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const runs = Number(process.argv[3] || 2);
const refSrc = process.argv[4] ? fs.readFileSync(process.argv[4], 'utf8') : null;
const l = await launch();
try {
  const page = await l.newPage({ width: 915, height: 412 });
  if (refSrc) {
    console.log(`(serving ${process.argv[4]} as gfx/textures.js)`);
    await page.route('**/js/gfx/textures.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: refSrc }));
  }
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable', { timeDomain: 'threadTicks' });
  await cdp.send('HeapProfiler.enable');
  for (let run = 0; run < runs; run++) {
    const metric = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((x) => [x.name, x.value]));
    const t0 = Date.now();
    // Leave the previous launch completely first (the machine may be loaded; be patient).
    if (run > 0) await page.goto('about:blank', { timeout: 300000 });
    await page.goto(`${l.game}?seed=7&quality=${quality}`, { waitUntil: 'commit', timeout: 300000 });
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
        textures: ctx.textures.stats || null,
      };
    });
    await cdp.send('HeapProfiler.collectGarbage');
    const heap = await cdp.send('Runtime.getHeapUsage');
    console.log(`after boot: JS heap used ${(heap.usedSize / 1e6).toFixed(1)} MB (total ${(heap.totalSize / 1e6).toFixed(1)} MB); renderer.info.memory ${JSON.stringify(after.info)}; programs ${after.programs}; DOM ${after.dom}`);
    console.log(`characters ${JSON.stringify(after.charStats)}; audio at boot ${JSON.stringify(after.audio)}`);
    console.log(`textures ${JSON.stringify(after.textures)}`);
    // Wait for the audio worker to finish its boot queue (real event loop).
    const tA = Date.now();
    const done = await page.evaluate(async () => { const a = window.__game.ctx.audio; if (a.prerender) await a.prerender(); const s = a.stats(); return { ready: s.ready, mb: +(s.mb || 0).toFixed(1), renderMs: Math.round(s.renderMs), doneAfterStartMs: Math.round(s.doneAt - s.startedAt), workers: s.workers }; });
    console.log(`audio prerender complete ${Date.now() - tA} ms after boot: ${JSON.stringify(done)}`);
  }
  await page.close();
} finally {
  await l.close();
}
