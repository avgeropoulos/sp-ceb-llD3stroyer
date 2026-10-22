// Review (performance lens): GPU texture memory per quality, which generated textures are actually
// sampled by a material, and the pixel-ratio / render-scale arithmetic for common phones.
// Usage: node spartan/tests/e2e/review-perf-texmem.mjs [qualities=low,medium,high]
import { launch } from './lib.mjs';

const qualities = (process.argv[2] || 'low,medium,high').split(',');
const l = await launch();
try {
  for (const q of qualities) {
    const page = await l.newPage({ width: 320, height: 180 });
    await page.goto(`${l.game}?manual=1&seed=7&quality=${q}`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
    const r = await page.evaluate(() => {
      const g = window.__game, ctx = g.ctx, r = ctx.renderer;
      g.startBattle(1);
      g.step(60);
      for (const k of ['levy', 'sparabara', 'immortal', 'archer', 'captain', 'helot']) g.spawn(k, 0, -6);
      g.step(5);
      const used = new Set();
      const scan = (m) => {
        if (!m) return;
        for (const k in m) { const v = m[k]; if (v && v.isTexture) used.add(v); }
        if (m.uniforms) for (const k in m.uniforms) { const v = m.uniforms[k] && m.uniforms[k].value; if (v && v.isTexture) used.add(v); }
        if (m.userData && m.userData.worldUniforms) for (const k in m.userData.worldUniforms) { const v = m.userData.worldUniforms[k].value; if (v && v.isTexture) used.add(v); }
      };
      const scanObj = (o) => { if (o.material) (Array.isArray(o.material) ? o.material : [o.material]).forEach(scan); };
      ctx.scene.traverse(scanObj);
      ctx.vm.scene.traverse(scanObj);
      if (ctx.scene.environment) used.add(ctx.scene.environment);
      const bytesOf = (t) => {
        const img = t.image || {};
        let w = img.width || 0, h = img.height || 0;
        if (!w && t.source && t.source.data) { w = t.source.data.width; h = t.source.data.height; }
        const bpp = t.type === 1016 ? 8 : t.type === 1015 ? 16 : t.format === 1028 ? 1 : 4; // HalfFloat, Float, Red
        const mip = (t.generateMipmaps || (t.mipmaps && t.mipmaps.length)) && t.minFilter !== 1006 && t.minFilter !== 1003 ? 4 / 3 : 1;
        return { w, h, bytes: w * h * bpp * mip };
      };
      // Textures module: every generated texture (generateAll uploads all of them).
      const mod = [];
      let modBytes = 0, modUnused = 0;
      for (const name of ctx.textures.names) {
        const t = ctx.textures.get(name);
        const b = bytesOf(t);
        modBytes += b.bytes;
        const isUsed = used.has(t);
        if (!isUsed) modUnused += b.bytes;
        mod.push([name, b.w, isUsed ? 'used' : 'UNUSED', Math.round(b.bytes / 1024)]);
      }
      // Everything else referenced by materials (atlases, vm paint sets, particles, env).
      let otherBytes = 0;
      const other = [];
      const modSet = new Set(ctx.textures.names.map((n) => ctx.textures.get(n)));
      for (const t of used) {
        if (modSet.has(t)) continue;
        const b = bytesOf(t);
        otherBytes += b.bytes;
        other.push([t.name || '(unnamed)', `${b.w}x${b.h}`, Math.round(b.bytes / 1024)]);
      }
      other.sort((a, b) => b[2] - a[2]);
      const sm = ctx.world.sun.shadow.mapSize.x;
      // Bone textures: one per skinned mesh (Float RGBA, size = next pow2 of sqrt(4*bones)).
      let bone = 0, boneN = 0;
      ctx.scene.traverse((o) => { if (o.isSkinnedMesh && o.skeleton.boneTexture) { const t = o.skeleton.boneTexture; bone += t.image.width * t.image.height * 16; boneN++; } });
      return {
        quality: ctx.quality,
        infoTextures: r.info.memory.textures,
        modBytes, modUnused, mod,
        otherBytes, other: other.slice(0, 14),
        shadowBytes: sm * sm * 4,
        shadowMap: sm,
        boneBytes: bone, boneN,
        pixelRatio: r.getPixelRatio(),
        aa: r.getContext().getContextAttributes().antialias,
      };
    });
    const MB = (b) => (b / 1048576).toFixed(1);
    console.log(`\n[quality=${q}] textures module: ${MB(r.modBytes)} MB uploaded (generateAll uploads every texture), of which ${MB(r.modUnused)} MB is never sampled by any material`);
    console.log('  unused:', r.mod.filter((x) => x[2] === 'UNUSED').map((x) => `${x[0]}(${x[1]}² ${x[3]}KB)`).join(' '));
    console.log(`  other material textures (atlases, vm paint sets, particles, env): ${MB(r.otherBytes)} MB: ${r.other.map((x) => `${x[0]} ${x[1]} ${x[2]}KB`).join('; ')}`);
    console.log(`  shadow map ${r.shadowMap}² depth ≈ ${MB(r.shadowBytes)} MB; bone textures ${r.boneN} × = ${(r.boneBytes / 1024).toFixed(0)} KB; antialias=${r.aa}`);
    console.log(`  TOTAL texture GPU ≈ ${MB(r.modBytes + r.otherBytes + r.shadowBytes + r.boneBytes)} MB (DESIGN §11 budget: ≤ 64 MB)`);
    await page.close();
  }
  // Pixel ratio arithmetic (main.js applySize): min(DPR, cap[q]) * renderScale, renderScale ∈ [0.6, 1].
  const caps = { low: 1.0, medium: 1.25, high: 1.5 };
  const phones = [['1080x2400 @ DPR 2.625 (Galaxy A5x/Pixel 6a class)', 914, 411, 2.625], ['720x1600 @ DPR 2.0 (budget)', 800, 360, 2.0], ['1440x3200 @ DPR 3.5 (flagship)', 914, 411, 3.5]];
  console.log('\npixel ratio arithmetic (landscape CSS viewport → drawing buffer):');
  for (const [name, w, h, dpr] of phones) {
    const row = Object.entries(caps).map(([q, c]) => { const pr = Math.min(dpr, c); const W = Math.round(w * pr), H = Math.round(h * pr); return `${q}: ${W}x${H} = ${(W * H / 1e6).toFixed(2)} MP (${(100 * (W * H) / (w * dpr * h * dpr)).toFixed(0)}% of native)`; }).join(' | ');
    console.log(`  ${name}: ${row}; at renderScale 0.6 medium → ${Math.round(w * Math.min(dpr, 1.25) * 0.6)}x${Math.round(h * Math.min(dpr, 1.25) * 0.6)}`);
  }
} finally {
  await l.close();
}
