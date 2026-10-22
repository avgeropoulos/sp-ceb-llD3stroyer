// Review (performance lens): fill cost. Re-renders the current frame into an RGBA8 target with
// every material swapped for a "count one" material (additive, 1/255 per fragment), keeping each
// object's geometry, vertex shader (ShaderMaterials are cloned with only the fragment replaced),
// draw order and depth state, then sums the counts. Reports fragments per screen pixel for:
//   opaque raw (no depth test), opaque after early-z (draw order as rendered), transparent by
//   owner (after the opaque depth), sky pixels, vm pass and the vm overlay quad.
// Also takes a screenshot of each scenario.
// (overdraw2: worst-case scenarios: next to a hot spring, dust and blood in the face.)
// Usage: node spartan/tests/e2e/review-perf-overdraw2.mjs [outDir]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const outDir = process.argv[2] || '/tmp/claude-0/-home-user-sp-ceb-llD3stroyer/f2f80b3b-a8d1-5741-b07f-51172e4acc56/scratchpad/review2/overdraw2';
fs.mkdirSync(outDir, { recursive: true });

const MEASURE = String.raw`(() => {
  const g = window.__game, ctx = g.ctx, r = ctx.renderer, THREE = ctx.THREE, rv = window.__rv;
  rv.refreshActors();
  const size = r.getDrawingBufferSize(new THREE.Vector2());
  const W = size.x, H = size.y, N = W * H;
  const rt = new THREE.WebGLRenderTarget(W, H, { depthBuffer: true, samples: 0, type: THREE.HalfFloatType });
  const ONE = 1;
  const FRAG = 'void main() { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); }';
  const counters = new Map();
  function counter(m) {
    let c = counters.get(m);
    if (c) return c;
    if (m.isShaderMaterial) {
      c = m.clone();
      c.fragmentShader = FRAG;
      c.onBeforeCompile = m.onBeforeCompile;
      c.customProgramCacheKey = () => 'count-' + m.uuid;
      c.extensions = m.extensions;
    } else {
      c = new THREE.MeshBasicMaterial({ color: new THREE.Color(ONE, 0, 0), side: m.side });
      c.toneMapped = false;
    }
    c.blending = THREE.CustomBlending;
    c.blendEquation = THREE.AddEquation;
    c.blendSrc = THREE.OneFactor;
    c.blendDst = THREE.OneFactor;
    c.transparent = true;
    c.alphaTest = 0;
    c.alphaToCoverage = false;
    counters.set(m, c);
    return c;
  }
  const isTransparent = (m) => !!m.transparent;
  const meshes = [];
  ctx.scene.traverse((o) => { if ((o.isMesh || o.isPoints) && o.material) meshes.push(o); });
  const vmMeshes = [];
  ctx.vm.scene.traverse((o) => { if ((o.isMesh || o.isPoints) && o.material) vmMeshes.push(o); });
  const saved = new Map();
  for (const o of meshes.concat(vmMeshes)) saved.set(o, { material: o.material, visible: o.visible });
  const mats = (o) => (Array.isArray(saved.get(o).material) ? saved.get(o).material : [saved.get(o).material]);
  const setCount = (o, depthTest, depthWrite) => {
    const src = saved.get(o).material;
    const one = (m) => { const c = counter(m); c.depthTest = depthTest; c.depthWrite = depthWrite; return c; };
    o.material = Array.isArray(src) ? src.map(one) : one(src);
  };
  const pixels = new Uint16Array(N * 4);
  const half = THREE.DataUtils.fromHalfFloat;
  function sum() {
    r.readRenderTargetPixels(rt, 0, 0, W, H, pixels);
    let s = 0, covered = 0, max = 0;
    for (let i = 0; i < N; i++) { const h = pixels[i * 4]; if (!h) continue; const v = half(h); s += v; covered++; if (v > max) max = v; }
    return { perPixel: +(s / N).toFixed(3), coverage: +(covered / N).toFixed(3), max };
  }
  const prevAuto = r.autoClear;
  const prevTone = r.toneMapping;
  r.autoClear = false;
  r.toneMapping = THREE.NoToneMapping;
  const scene = ctx.scene;
  const bg = scene.background; scene.background = null;
  function pass(list, opts, filter) {
    // opts: { depthTest, depthWrite, clearDepth, clearColor }
    for (const o of meshes) o.visible = false;
    for (const o of vmMeshes) o.visible = false;
    for (const o of list) if (saved.get(o).visible && (!filter || filter(o))) { o.visible = true; setCount(o, opts.depthTest, opts.depthWrite); }
  }
  const out = {};
  const opaque = meshes.filter((o) => o.name !== 'sky' && !mats(o).some(isTransparent));
  const transparent = meshes.filter((o) => o.name !== 'sky' && mats(o).some(isTransparent));
  const sky = meshes.filter((o) => o.name === 'sky');
  const visibleAncestors = (o) => { let p = o.parent; while (p) { if (!p.visible) return false; p = p.parent; } return true; };
  r.setRenderTarget(rt);
  // 1. Opaque raw (no depth test).
  r.setClearColor(0x000000, 0); r.clear(true, true, true);
  pass(opaque, { depthTest: false, depthWrite: false });
  r.render(scene, ctx.camera);
  out.opaqueRaw = sum();
  // 2. Opaque with depth test, as drawn (no hardware hidden-surface removal).
  r.clear(true, true, true);
  pass(opaque, { depthTest: true, depthWrite: true });
  r.render(scene, ctx.camera);
  out.opaqueEarlyZ = sum();
  // 3. Sky pixels (sky is drawn last against the opaque depth).
  r.clear(true, false, false);
  pass(sky, { depthTest: true, depthWrite: false });
  r.render(scene, ctx.camera);
  out.sky = sum();
  // 4. Transparent, by owner, against the opaque depth.
  out.transparent = {};
  const owners = new Map();
  for (const o of transparent) { if (!saved.get(o).visible || !visibleAncestors(o)) continue; const k = rv.ownerOf(o); if (!owners.has(k)) owners.set(k, []); owners.get(k).push(o); }
  let tTotal = 0;
  for (const [k, list] of owners) {
    r.clear(true, false, false);
    pass(list, { depthTest: true, depthWrite: false });
    r.render(scene, ctx.camera);
    const s = sum();
    out.transparent[k] = s;
    tTotal += s.perPixel;
  }
  out.transparentTotal = +tTotal.toFixed(3);
  // 2b. Per owner: everything opaque writes depth in draw order, only one owner writes colour.
  out.opaqueByOwner = {};
  const oOwners = new Map();
  for (const o of opaque) { if (!saved.get(o).visible || !visibleAncestors(o)) continue; let k = rv.ownerOf(o); k = k.replace(/^(actor|corpse|dead):.*$/, '$1s').replace(/^combat:prop:.*/, 'combat:props'); if (!oOwners.has(k)) oOwners.set(k, []); oOwners.get(k).push(o); }
  for (const [k, list] of oOwners) {
    r.clear(true, true, true);
    pass(opaque, { depthTest: true, depthWrite: true });
    const set = new Set(list);
    for (const o of opaque) {
      if (!o.visible) continue;
      const ms = Array.isArray(o.material) ? o.material : [o.material];
      if (!set.has(o)) {
        // Depth only: a per-object clone so other owners' counters stay untouched.
        const src = saved.get(o).material;
        const d = (m) => { const c = counter(m).clone(); c.colorWrite = false; c.depthTest = true; c.depthWrite = true; return c; };
        o.material = Array.isArray(src) ? src.map(d) : d(src);
      }
    }
    r.render(scene, ctx.camera);
    const sres = sum();
    out.opaqueByOwner[k] = { earlyZ: sres.perPixel };
    // Raw (no depth test) for this owner alone.
    r.clear(true, true, true);
    pass(list, { depthTest: false, depthWrite: false });
    r.render(scene, ctx.camera);
    out.opaqueByOwner[k].raw = sum().perPixel;
    for (const o of opaque) { const m = o.material; if (m !== saved.get(o).material) { (Array.isArray(m) ? m : [m]).forEach((x) => { if (!x.isMeshBasicMaterial || x.colorWrite === false) { if (x.colorWrite === false) x.dispose(); } }); } }
  }
  // 5. vm pass (fresh depth) and its overlay.
  r.clear(true, true, true);
  const vmOpaque = vmMeshes.filter((o) => o.name !== 'vmOverlay');
  for (const o of meshes) o.visible = false;
  for (const o of vmMeshes) o.visible = false;
  for (const o of vmOpaque) if (saved.get(o).visible && visibleAncestors(o)) { o.visible = true; setCount(o, true, true); }
  r.render(ctx.vm.scene, ctx.vm.camera);
  out.vmMeshes = sum();
  r.clear(true, true, true);
  for (const o of vmMeshes) o.visible = false;
  for (const o of vmMeshes) if (o.name === 'vmOverlay' && saved.get(o).visible) { o.visible = true; setCount(o, false, false); }
  r.render(ctx.vm.scene, ctx.vm.camera);
  out.vmOverlay = sum();
  // Restore.
  for (const [o, s] of saved) { o.material = s.material; o.visible = s.visible; }
  scene.background = bg;
  r.setRenderTarget(null);
  r.autoClear = prevAuto;
  r.toneMapping = prevTone;
  rt.dispose();
  for (const c of counters.values()) c.dispose();
  out.size = [W, H];
  out.particles = ctx.fx.particleCount;
  out.helmet = !!ctx.settings.helmetView;
  return out;
})()`;

const l = await launch();
try {
  const page = await l.newPage({ width: 640, height: 360 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  // Worst cases a player can reach: standing at the left edge of the line next to the spring
  // behind it (x=-7.9, z=6.5) and turning toward it; and a dust/blood-heavy melee right in front.
  const scen = [
    ['battle-front', `g.startBattle(1); rv.noDraw = true; rv.pilot(true); rv.run(1800, 10); rv.pilot(false); rv.noDraw = false; g.step(2);`],
    ['near-spring-behind', `const p = ctx.player; p.pos.x = -7.0; p.pos.z = 3.0; const s = ctx.world.springs[1]; const want = Math.atan2(-(s.x - p.pos.x), -(s.z - p.pos.z)); ctx.input.lookYaw = ((want - p.yaw + Math.PI * 3) % (Math.PI * 2)) - Math.PI; rv.noDraw = true; g.step(30); ctx.input.lookYaw = 0; rv.noDraw = false; g.step(2); return { pos: [p.pos.x, p.pos.z], yaw: p.yaw, spring: s };`],
    ['melee-dust-blood', `const p = ctx.player; p.pos.x = 0; p.pos.z = 0; ctx.input.lookYaw = ((0 - p.yaw + Math.PI * 3) % (Math.PI * 2)) - Math.PI; rv.noDraw = true; g.step(20); ctx.input.lookYaw = 0; const f = [-Math.sin(p.yaw), -Math.cos(p.yaw)]; for (let i = 0; i < 6; i++) { const d = 1.0 + 0.3 * i; ctx.fx.dust({ x: p.pos.x + f[0] * d, y: 0.05, z: p.pos.z + f[1] * d }, 1); ctx.fx.blood({ x: p.pos.x + f[0] * d, y: 1.3, z: p.pos.z + f[1] * d }, { x: -f[0], y: 0.3, z: -f[1] }, 1); } rv.noDraw = false; g.step(6); return { particles: ctx.fx.particleCount };`],
  ];
  for (const [name, code] of scen) {
    const r = await page.evaluate(([code, M]) => {
      const g = window.__game, ctx = g.ctx, rv = window.__rv;
      const extra = new Function('g', 'ctx', 'rv', code)(g, ctx, rv);
      const res = eval(M);
      g.render();
      return { extra, res, calls: g.ctx.renderer.info.render.calls };
    }, [code, MEASURE]);
    await page.screenshot({ path: path.join(outDir, `${name}.png`), timeout: 300000 });
    const o = r.res;
    const tr = Object.entries(o.transparent).sort((a, b) => b[1].perPixel - a[1].perPixel).map(([k, v]) => `${k}=${v.perPixel}(cov ${v.coverage}, max ${v.max})`).join(' ');
    const ob = Object.entries(o.opaqueByOwner).sort((a, b) => b[1].earlyZ - a[1].earlyZ).slice(0, 12).map(([k, v]) => `${k}=${v.earlyZ}/${v.raw}`).join(' ');
    console.log(`[${name}] opaque by owner (early-z/raw frags per px): ${ob}`);
    console.log(`[${name}] ${o.size.join('x')} particles=${o.particles} | opaque raw ${o.opaqueRaw.perPixel}/px, after early-z ${o.opaqueEarlyZ.perPixel}/px | sky ${o.sky.perPixel}/px | transparent total ${o.transparentTotal}/px: ${tr} | vm meshes ${o.vmMeshes.perPixel}/px | vm overlay ${o.vmOverlay.perPixel}/px (helmet=${o.helmet})${r.extra ? ' ' + JSON.stringify(r.extra) : ''}`);
  }
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 3).join('\n'));
} finally {
  await l.close();
}
