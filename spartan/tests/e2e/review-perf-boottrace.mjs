// Review (performance lens): what blocks the main thread during boot and the first frames.
// Wraps (in an init script, before any game code) the native calls that can stall on the GPU
// process: 2D getImageData/putImageData/drawImage, createImageBitmap, WebGL getProgramParameter
// (waits for a shader link), texImage2D/texSubImage2D/generateMipmap (uploads), readPixels,
// and records bootProgress steps, hook.ready and the first frames (non-manual RAF loop).
// Usage: node spartan/tests/e2e/review-perf-boottrace.mjs [quality=medium] [manual=0|1]
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const manual = process.argv[3] === '1';
const l = await launch();
try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.addInitScript(() => {
    const T = (window.__trace = { calls: {}, big: [], steps: [], marks: {} });
    const rec = (name, ms, info) => {
      const c = T.calls[name] || (T.calls[name] = { n: 0, ms: 0, max: 0 });
      c.n++;
      c.ms += ms;
      if (ms > c.max) c.max = ms;
      if (ms > 50) T.big.push({ name, ms: Math.round(ms), at: Math.round(performance.now()), info, step: T.steps.length ? T.steps[T.steps.length - 1].msg : null, stack: (new Error().stack || '').split('\n').slice(2, 7).map((x) => x.trim().replace(/^at /, '').replace(/https?:\/\/[^/]+\/src\/main\/assets\/game\//, '')).join(' < ') });
    };
    const wrap = (proto, name, label, infoFn) => {
      const f = proto[name];
      if (!f) return;
      proto[name] = function () {
        const t0 = performance.now();
        const r = f.apply(this, arguments);
        rec(label, performance.now() - t0, infoFn ? infoFn.call(this, arguments) : undefined);
        return r;
      };
    };
    const C2 = CanvasRenderingContext2D.prototype;
    wrap(C2, 'getImageData', '2d.getImageData', function (a) { return `${a[2]}x${a[3]}`; });
    wrap(C2, 'putImageData', '2d.putImageData', function (a) { return `${a[0].width}x${a[0].height}`; });
    wrap(C2, 'drawImage', '2d.drawImage', function () { return `${this.canvas.width}x${this.canvas.height}`; });
    for (const P of [WebGL2RenderingContext.prototype]) {
      wrap(P, 'getProgramParameter', 'gl.getProgramParameter');
      wrap(P, 'getShaderParameter', 'gl.getShaderParameter');
      wrap(P, 'texImage2D', 'gl.texImage2D', function (a) { return a.length >= 9 ? `${a[3]}x${a[4]}` : (a[5] && a[5].width ? `${a[5].width}x${a[5].height}` : ''); });
      wrap(P, 'texSubImage2D', 'gl.texSubImage2D');
      wrap(P, 'texStorage2D', 'gl.texStorage2D', function (a) { return `${a[3]}x${a[4]}`; });
      wrap(P, 'generateMipmap', 'gl.generateMipmap');
      wrap(P, 'readPixels', 'gl.readPixels');
      wrap(P, 'linkProgram', 'gl.linkProgram');
      wrap(P, 'compileShader', 'gl.compileShader');
      wrap(P, 'bufferData', 'gl.bufferData');
    }
    let v;
    Object.defineProperty(window, '__bootProgressAt', {
      configurable: true,
      get: () => v,
      set: (x) => {
        v = x;
        const t = performance.now();
        queueMicrotask(() => { const m = document.getElementById('boot-msg'); T.steps.push({ t: Math.round(t), msg: m && m.textContent }); });
      },
    });
    let hook;
    Object.defineProperty(window, '__game', {
      configurable: true,
      get: () => hook,
      set: (h) => {
        hook = h;
        let ready = h.ready;
        Object.defineProperty(h, 'ready', { configurable: true, enumerable: true, get: () => ready, set: (x) => { ready = x; if (x) T.marks.ready = Math.round(performance.now()); } });
      },
    });
    let booted;
    Object.defineProperty(window, '__booted', { configurable: true, get: () => booted, set: (x) => { booted = x; T.marks.booted = Math.round(performance.now()); } });
    // Long main-thread tasks after boot (first frames).
    T.rafGaps = [];
    let last = 0;
    const tick = (now) => { if (last && now - last > 100) T.rafGaps.push({ at: Math.round(now), gap: Math.round(now - last) }); last = now; if (T.rafGaps.length < 200) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
    T.long = [];
    try { new PerformanceObserver((list) => { for (const e of list.getEntries()) T.long.push({ at: Math.round(e.startTime), d: Math.round(e.duration) }); }).observe({ type: 'longtask', buffered: true }); } catch {}
  });
  const t0 = Date.now();
  await page.goto(`${l.game}?${manual ? 'manual=1&' : ''}seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__trace && window.__trace.marks.booted, null, { timeout: 400000, polling: 500 });
  // Let the first second of frames run (non-manual) so first-use work shows up.
  await page.waitForTimeout(manual ? 500 : 8000);
  const T = await page.evaluate(() => window.__trace);
  console.log(`quality=${quality} manual=${manual}: wall ${Date.now() - t0} ms; ready at ${T.marks.ready} ms, __booted at ${T.marks.booted} ms (performance.now since navigation)`);
  console.log('boot steps:', T.steps.filter((s, i, a) => i === 0 || s.msg !== a[i - 1].msg).map((s) => `${s.t}ms ${s.msg}`).join(' | '));
  console.log('native call totals (ms):');
  for (const [k, c] of Object.entries(T.calls).sort((a, b) => b[1].ms - a[1].ms)) console.log(`  ${k.padEnd(24)} n=${String(c.n).padStart(6)} total=${c.ms.toFixed(0).padStart(7)} max=${c.max.toFixed(0).padStart(6)}`);
  console.log('individual calls > 50 ms:');
  for (const b of T.big.slice(0, 40)) console.log(`  at ${b.at}ms ${b.name} ${b.ms}ms ${b.info || ''} [during "${b.step}"]\n      ${b.stack}`);
  // Long tasks (PerformanceObserver) after boot.
  console.log('long tasks > 200 ms:', JSON.stringify((T.long || []).filter((x) => x.d > 200).slice(0, 30)));
  console.log('RAF gaps > 100 ms after load:', JSON.stringify(T.rafGaps.slice(0, 30)));
  const errs = page.errors.concat(await page.evaluate(() => (window.__game && window.__game.errors) || []));
  console.log('errors:', errs.length, errs.slice(0, 3).join('\n'));
} finally {
  await l.close();
}
