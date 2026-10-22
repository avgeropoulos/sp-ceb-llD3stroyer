// Boot-time textures (fixer "boot"): the cross-launch pixel cache, the generator workers and the
// main-thread fallback must all give the same textures as plain main-thread generation, and only
// the textures materials sample may be uploaded.
//   1. first launch in a fresh browser context (workers, empty cache)
//   2. second launch in the same context (everything from IndexedDB, nothing generated)
//   3. a launch without Worker support (main-thread fallback)
//   4. optionally (A/B) a launch with an older textures.js served in place of the current one
// Every boot texture's pixels (as the GPU gets them, row 0 = v 0) are hashed and compared.
// Usage: node spartan/tests/e2e/boot-textures.mjs [quality=medium] [reference textures.js to compare against]
import fs from 'node:fs';
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const refFile = process.argv[3] || null;
const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};

/** In the page: per-texture SHA-1 of the uploaded pixels, plus the module's stats and GPU state. */
const FINGERPRINT = async () => {
  const ctx = window.__game.ctx;
  const t = ctx.textures;
  const r = ctx.renderer;
  const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
  const out = {};
  for (const name of t.names) {
    if (t.has ? !t.has(name) : ['water', 'blood', 'spark', 'bronzeNormal', 'wickerNormal'].includes(name)) continue;
    const tex = t.get(name);
    const img = tex.image;
    let px;
    if (img.data) px = new Uint8Array(img.data.buffer, img.data.byteOffset, img.data.length);
    else {
      const S = img.width;
      const top = img.getContext('2d').getImageData(0, 0, S, img.height).data;
      // Canvases are top-down and uploaded with flipY.
      px = new Uint8Array(top.length);
      const row = S * 4;
      for (let y = 0; y < img.height; y++) px.set(top.subarray(y * row, (y + 1) * row), (img.height - 1 - y) * row);
    }
    const mips = tex.mipmaps && tex.mipmaps.length ? tex.mipmaps.length : 0;
    out[name] = {
      hash: hex(await crypto.subtle.digest('SHA-1', px)),
      size: img.width,
      kind: tex.isDataTexture ? 'data' : tex.isCanvasTexture ? 'canvas' : 'other',
      colorSpace: tex.colorSpace,
      mips,
      mipHash: mips ? hex(await crypto.subtle.digest('SHA-1', new Uint8Array(tex.mipmaps.map((m) => [...m.data.subarray(0, 64)]).flat()))) : null,
      meanLum: tex.userData.meanLum == null ? null : +tex.userData.meanLum.toFixed(6),
      uploaded: !!r.properties.get(tex).__webglTexture,
    };
  }
  const est = navigator.storage && navigator.storage.estimate ? await navigator.storage.estimate() : {};
  const mod = await import('/src/main/assets/game/js/gfx/textures.js');
  return { tex: out, boot: mod.BOOT_TEXTURES ? mod.BOOT_TEXTURES.slice() : null, stats: t.stats ? { ...t.stats } : null, gpuMB: t.memoryBytes ? +(t.memoryBytes() / 1048576).toFixed(1) : null, storageMB: est.usage ? +(est.usage / 1048576).toFixed(1) : null };
};

async function boot(page, label) {
  const t0 = Date.now();
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const fp = await page.evaluate(FINGERPRINT);
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log(`${label}: ready after ${Date.now() - t0} ms; textures ${JSON.stringify(fp.stats)}; GPU estimate ${fp.gpuMB} MB; storage ${fp.storageMB} MB`);
  check(errs.length === 0, `${label}: no page errors ${errs.slice(0, 3).join(' | ')}`);
  return fp;
}

function same(a, b, label, names = Object.keys(a.tex).filter((n) => b.tex[n])) {
  const diff = names.filter((n) => !b.tex[n] || a.tex[n].hash !== b.tex[n].hash || a.tex[n].mipHash !== b.tex[n].mipHash || a.tex[n].meanLum !== b.tex[n].meanLum || a.tex[n].colorSpace !== b.tex[n].colorSpace);
  check(diff.length === 0, `${label}: ${names.length} textures identical${diff.length ? `; differ: ${diff.join(', ')}` : ''}`);
}

const l = await launch();
try {
  // 1 + 2: first and second launch share one browser context (and so one IndexedDB).
  const page = await l.newPage({ width: 480, height: 270 });
  const first = await boot(page, 'first launch');
  const names = Object.keys(first.tex);
  const bootN = first.boot.length;
  const painted = first.boot.filter((n) => n.startsWith('blazon')).length;
  check(first.stats && first.stats.generated === bootN && first.stats.fromCache === 0, `first launch generates all ${bootN} boot textures`);
  check(first.stats && first.stats.workers > 0 && first.stats.workerJobs > 0, `first launch uses generator workers (${first.stats && first.stats.workers} workers, ${first.stats && first.stats.workerJobs} jobs)`);
  for (const n of ['water', 'blood', 'spark']) check(!first.tex[n], `${n} is not built at boot`);
  const notUploaded = names.filter((n) => !first.tex[n].uploaded);
  console.log('not uploaded:', notUploaded.join(' '));
  for (const n of ['dirt', 'dirtNormal', 'rock', 'cliffStrata', 'sand', 'stone', 'waterNormal', 'cloud', 'smoke', 'grass', 'scrub', 'sulphur', 'foam']) {
    check(first.tex[n] && first.tex[n].uploaded, `${n} (sampled by a material) is uploaded`);
  }
  for (const n of ['bronze', 'linen', 'skinGreek', 'persianRobeA', 'blazonFly']) check(!first.tex[n] || !first.tex[n].uploaded, `${n} (atlas source) is not uploaded by the textures module`);
  // Give the background IndexedDB writes a moment, then launch again.
  await page.waitForTimeout(1500);
  const second = await boot(page, 'second launch');
  check(second.stats && second.stats.generated === painted && second.stats.fromCache === bootN - painted, `second launch takes every generated texture from the cache (${second.stats && second.stats.fromCache} cached, ${second.stats && second.stats.generated} generated; the ${painted} blazons are painted)`);
  same(first, second, 'second launch vs first');
  await page.context().close();

  // 3: no Worker support (main-thread fallback), fresh context so nothing is cached.
  const p3 = await l.newPage({ width: 480, height: 270 });
  await p3.addInitScript(() => {
    window.Worker = undefined;
  });
  const main = await boot(p3, 'main thread only');
  check(main.stats && main.stats.workers === 0 && main.stats.generated === bootN, 'main-thread fallback generates everything');
  same(first, main, 'workers vs main thread');
  await p3.context().close();

  // 4: A/B against a reference textures.js (e.g. the version before this change).
  if (refFile) {
    const src = fs.readFileSync(refFile, 'utf8');
    const p4 = await l.newPage({ width: 480, height: 270 });
    await p4.route('**/js/gfx/textures.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: src }));
    const ref = await boot(p4, 'reference textures.js');
    same(ref, first, 'reference vs current', names);
    await p4.context().close();
  }
} finally {
  await l.close();
}
if (failures.length) {
  console.log(`\n${failures.length} failure(s)`);
  process.exitCode = 1;
} else console.log('\nall boot texture checks passed');
