// Review (performance lens): boot time breakdown and memory at boot.
// Records every bootProgress() step (via a setter on window.__bootProgressAt), CPU-profiles the
// boot with CDP and attributes self time to modules, and reports heap + estimated GPU memory.
// Usage: node spartan/tests/e2e/review-perf-boot.mjs [quality=medium] [runs=1]
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const runs = Number(process.argv[3] || 1);
const l = await launch();
try {
  for (let run = 0; run < runs; run++) {
    const page = await l.newPage({ width: 960, height: 540 });
    await page.addInitScript(() => {
      const steps = [];
      window.__bootSteps = steps;
      let v;
      Object.defineProperty(window, '__bootProgressAt', {
        configurable: true,
        get: () => v,
        set: (x) => {
          v = x;
          const t = performance.now();
          queueMicrotask(() => {
            const msg = document.getElementById('boot-msg');
            const fill = document.getElementById('boot-fill');
            steps.push({ t, msg: msg && msg.textContent, fill: fill && fill.style.width });
          });
        },
      });
      // Long gaps without progress (the 15 s engine-fail watchdog in index.html).
      window.__rafGaps = [];
    });
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Profiler.enable');
    await cdp.send('Profiler.setSamplingInterval', { interval: 500 });
    await cdp.send('Profiler.start');
    const t0 = Date.now();
    await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
    const wall = Date.now() - t0;
    const { profile } = await cdp.send('Profiler.stop');
    const info = await page.evaluate(() => {
      const g = window.__game;
      const ctx = g.ctx;
      const r = ctx.renderer;
      // Unique geometries in scene + vm scene (GPU-resident ones) and their byte sizes.
      const geos = new Set();
      const add = (o) => { if (o.geometry) geos.add(o.geometry); };
      ctx.scene.traverse(add);
      ctx.vm.scene.traverse(add);
      let geoBytes = 0;
      for (const geo of geos) {
        for (const k in geo.attributes) {
          const a = geo.attributes[k];
          geoBytes += a.isInterleavedBufferAttribute ? 0 : a.array.byteLength;
        }
        if (geo.index) geoBytes += geo.index.array.byteLength;
      }
      // Textures: walk materials for maps (and the textures module estimate).
      const texs = new Set();
      const scan = (m) => {
        if (!m) return;
        for (const k of ['map', 'normalMap', 'roughnessMap', 'metalnessMap', 'alphaMap', 'envMap', 'aoMap', 'emissiveMap']) if (m[k]) texs.add(m[k]);
        if (m.uniforms) for (const k in m.uniforms) { const v = m.uniforms[k].value; if (v && v.isTexture) texs.add(v); }
      };
      const scanObj = (o) => { if (o.material) (Array.isArray(o.material) ? o.material : [o.material]).forEach(scan); };
      ctx.scene.traverse(scanObj);
      ctx.vm.scene.traverse(scanObj);
      const texRows = [];
      let texBytes = 0;
      for (const t of texs) {
        const img = t.image || {};
        let w = img.width || 0, h = img.height || 0;
        if (t.isRenderTargetTexture || (!w && t.source && t.source.data)) { w = t.source.data.width; h = t.source.data.height; }
        const bpp = t.type === 1015 /* Float */ ? 16 : t.type === 1016 /* HalfFloat */ ? 8 : t.format === 1028 /* Red */ ? 1 : 4;
        const mip = t.generateMipmaps || (t.mipmaps && t.mipmaps.length) ? 4 / 3 : 1;
        const bytes = w * h * bpp * mip;
        texBytes += bytes;
        texRows.push([t.name || '(unnamed)', w, h, bpp, Math.round(bytes / 1024)]);
      }
      texRows.sort((a, b) => b[4] - a[4]);
      const sun = ctx.world.sun;
      const smap = sun.shadow.mapSize.x;
      const dbs = r.getDrawingBufferSize(new ctx.THREE.Vector2());
      const aa = r.getContext().getContextAttributes().antialias;
      return {
        ready: g.ready,
        errors: g.errors.slice(0, 5),
        programs: r.info.programs.length,
        memInfo: r.info.memory,
        charStats: ctx.characters.stats,
        texModuleBytes: ctx.textures.memoryBytes ? ctx.textures.memoryBytes() : null,
        geoCount: geos.size,
        geoBytes,
        texCount: texs.size,
        texBytes,
        texTop: texRows.slice(0, 25),
        shadowMap: smap,
        drawingBuffer: [dbs.x, dbs.y],
        antialias: aa,
        steps: window.__bootSteps,
        jsHeap: performance.memory ? { used: performance.memory.usedJSHeapSize, total: performance.memory.totalJSHeapSize } : null,
        domNodes: document.getElementsByTagName('*').length,
      };
    });
    const heap = await cdp.send('Runtime.getHeapUsage');

    // Steps → durations.
    console.log(`\n=== boot run ${run + 1} quality=${quality}: ${wall} ms to ready (wall) ===`);
    const steps = info.steps;
    let prevMsg = null;
    let segStart = steps.length ? steps[0].t : 0;
    const segs = [];
    for (let i = 0; i < steps.length; i++) {
      if (steps[i].msg !== prevMsg) {
        if (prevMsg !== null) segs.push([prevMsg, steps[i].t - segStart]);
        prevMsg = steps[i].msg;
        segStart = steps[i].t;
      }
    }
    console.log(`first bootProgress at ${steps.length ? steps[0].t.toFixed(0) : '?'} ms after navigation start (HTML + module graph load/eval)`);
    for (const [m, d] of segs) console.log(`  ${m.padEnd(32)} ${d.toFixed(0).padStart(7)} ms`);
    const last = steps[steps.length - 1];
    console.log(`  last step "${last && last.msg}" at ${last && last.t.toFixed(0)} ms`);
    let maxGap = 0;
    for (let i = 1; i < steps.length; i++) maxGap = Math.max(maxGap, steps[i].t - steps[i - 1].t);
    console.log(`  longest gap between progress updates: ${maxGap.toFixed(0)} ms (index.html engine-fail watchdog fires at 15000 ms)`);

    // CPU profile: self time per script and per top function.
    const nodes = new Map(profile.nodes.map((n) => [n.id, n]));
    const self = new Map();
    const dtById = new Map();
    for (let i = 0; i < profile.samples.length; i++) {
      const id = profile.samples[i];
      dtById.set(id, (dtById.get(id) || 0) + (profile.timeDeltas[i] || 0));
    }
    const byUrl = new Map();
    const byFn = new Map();
    let total = 0;
    for (const [id, us] of dtById) {
      const n = nodes.get(id);
      const cf = n.callFrame;
      const url = (cf.url || '').replace(/^.*\/game\//, '') || `(${cf.functionName || 'native'})`;
      byUrl.set(url, (byUrl.get(url) || 0) + us);
      const k = `${cf.functionName || '(anon)'} ${url}:${cf.lineNumber + 1}`;
      byFn.set(k, (byFn.get(k) || 0) + us);
      total += us;
    }
    console.log(`CPU profile: ${(total / 1000).toFixed(0)} ms sampled. Self time by script:`);
    for (const [k, v] of [...byUrl.entries()].sort((a, b) => b[1] - a[1]).slice(0, 18)) console.log(`  ${(v / 1000).toFixed(0).padStart(6)} ms  ${k}`);
    console.log('Top functions (self):');
    for (const [k, v] of [...byFn.entries()].sort((a, b) => b[1] - a[1]).slice(0, 22)) console.log(`  ${(v / 1000).toFixed(0).padStart(6)} ms  ${k}`);

    // Inclusive time for interesting subtrees (by function name + url).
    const children = new Map();
    for (const n of profile.nodes) children.set(n.id, n.children || []);
    const incl = (id) => (dtById.get(id) || 0) + children.get(id).reduce((s, c) => s + incl(c), 0);
    const want = [
      ['generateAll', 'textures.js'], ['createWorld', 'world.js'], ['buildMaterials', 'atlas.js'], ['prebuild', 'characters.js'],
      ['createViewmodel', 'viewmodel.js'], ['createAudio', 'audio.js'], ['compileAsync', 'three.module.js'], ['createFx', 'fx.js'],
      ['createHud', 'hud.js'], ['createMenus', 'menus.js'], ['captureEnvironment', 'sky.js'], ['buildHorde', 'horde.js'], ['buildTerrain', 'terrain.js'],
      ['buildCliff', 'cliff.js'], ['buildVegetation', 'props.js'], ['buildRocks', 'props.js'], ['createTextures', 'textures.js'], ['build', 'textures.js'],
    ];
    console.log('Inclusive time of key builders (sum over all calls):');
    for (const [fn, file] of want) {
      let sum = 0;
      const seen = new Set();
      // Only count outermost occurrences.
      const visit = (id, inside) => {
        const n = nodes.get(id);
        const hit = n.callFrame.functionName === fn && (n.callFrame.url || '').endsWith(file);
        if (hit && !inside) sum += incl(id);
        for (const c of children.get(id)) visit(c, inside || hit);
      };
      visit(profile.nodes[0].id, false);
      if (sum > 0) console.log(`  ${(sum / 1000).toFixed(0).padStart(6)} ms  ${fn} (${file})`);
    }

    console.log(`programs=${info.programs} geometries(info)=${info.memInfo.geometries} textures(info)=${info.memInfo.textures}`);
    console.log(`characters: ${JSON.stringify(info.charStats)}`);
    console.log(`JS heap after boot: used ${(heap.usedSize / 1e6).toFixed(1)} MB, total ${(heap.totalSize / 1e6).toFixed(1)} MB; DOM nodes ${info.domNodes}`);
    console.log(`GPU estimate: textures referenced by scene materials ${info.texCount} = ${(info.texBytes / 1e6).toFixed(1)} MB (textures module estimate ${(info.texModuleBytes / 1e6).toFixed(1)} MB); geometry in scene ${info.geoCount} = ${(info.geoBytes / 1e6).toFixed(1)} MB; shadow map ${info.shadowMap}² depth`);
    console.log(`drawing buffer ${info.drawingBuffer.join('x')} antialias=${info.antialias}`);
    console.log('largest textures [name, w, h, bytes/px, KB]:', JSON.stringify(info.texTop));
    if (info.errors.length) console.log('errors', info.errors);
    await page.close();
  }
} finally {
  await l.close();
}
