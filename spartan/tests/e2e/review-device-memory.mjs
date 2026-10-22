// Review (device lens): memory footprint and boot phases on a phone-like context.
// Wraps WebGL2 allocation calls to total GPU bytes (textures incl. mip chains, renderbuffers,
// buffers), counts 2D canvases still alive and their pixel area, reads the JS heap, and times
// every boot phase (bootProgress messages) plus main-thread CPU time from CDP metrics.
// Usage: node spartan/tests/e2e/review-device-memory.mjs [quality] [WxH] [dpr]
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, NATIVE_INIT } from './review-device-native.mjs';

const quality = process.argv[2] || 'auto';
const [W, H] = (process.argv[3] || '915x412').split('x').map(Number);
const dpr = Number(process.argv[4] || 2.75);
const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS.concat(['--enable-precise-memory-info', '--js-flags=--expose-gc']) });
const context = await browser.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: dpr, isMobile: true, hasTouch: true });
await context.addInitScript(NATIVE_INIT);
await context.addInitScript(() => {
  // ---- GPU allocation accounting ----
  const BPP = new Map();
  const mem = { tex: new Map(), rb: new Map(), buf: new Map(), canvases: new Set(), big: [] };
  window.__mem = mem;
  const bpp = (gl, internal, format, type) => {
    const k = internal;
    const t = {
      [gl.RGBA8]: 4, [gl.SRGB8_ALPHA8]: 4, [gl.RGB8]: 4, [gl.R8]: 1, [gl.RG8]: 2, [gl.RGBA16F]: 8, [gl.RGBA32F]: 16,
      [gl.R16F]: 2, [gl.R32F]: 4, [gl.RG16F]: 4, [gl.DEPTH_COMPONENT24]: 4, [gl.DEPTH_COMPONENT16]: 2, [gl.DEPTH24_STENCIL8]: 4,
      [gl.DEPTH_COMPONENT32F]: 4, [gl.RGBA]: 4, [gl.RGB]: 4, [gl.LUMINANCE]: 1, [gl.ALPHA]: 1, [gl.RED]: 1, [gl.R11F_G11F_B10F]: 4,
    }[k];
    return t || 4;
  };
  const wrap = (proto) => {
    const bound = new WeakMap(); // gl → { target → texture }
    const cur = (gl) => { let b = bound.get(gl); if (!b) bound.set(gl, (b = {})); return b; };
    const ob = proto.bindTexture;
    proto.bindTexture = function (target, tex) { cur(this)[target] = tex; return ob.call(this, target, tex); };
    const key = (gl, target) => {
      const t = target >= gl.TEXTURE_CUBE_MAP_POSITIVE_X && target <= gl.TEXTURE_CUBE_MAP_NEGATIVE_Z ? gl.TEXTURE_CUBE_MAP : target;
      return cur(gl)[t];
    };
    const note = (gl, target, level, face, bytes, w, h) => {
      const tex = key(gl, target);
      if (!tex) return;
      let e = mem.tex.get(tex);
      if (!e) mem.tex.set(tex, (e = { levels: new Map(), w: 0, h: 0 }));
      e.levels.set(face + ':' + level, bytes);
      if (level === 0) { e.w = w; e.h = h; }
    };
    const ti = proto.texImage2D;
    proto.texImage2D = function (target, level, internal, a, b, c, d, e2, f) {
      let w, h, format, type;
      if (arguments.length >= 8) { w = a; h = b; format = d; type = e2; }
      else { const src = c && c.width !== undefined ? c : arguments[5]; w = (src && (src.videoWidth || src.width)) || 0; h = (src && (src.videoHeight || src.height)) || 0; format = a; type = b; }
      note(this, target, level, target, w * h * bpp(this, internal, format, type), w, h);
      return ti.apply(this, arguments);
    };
    const ts = proto.texStorage2D;
    proto.texStorage2D = function (target, levels, internal, w, h) {
      const faces = target === this.TEXTURE_CUBE_MAP ? 6 : 1;
      let bytes = 0;
      for (let l = 0, ww = w, hh = h; l < levels; l++, ww = Math.max(1, ww >> 1), hh = Math.max(1, hh >> 1)) bytes += ww * hh * bpp(this, internal);
      note(this, target === this.TEXTURE_CUBE_MAP ? this.TEXTURE_CUBE_MAP : target, 0, 'storage', bytes * faces, w, h);
      return ts.apply(this, arguments);
    };
    const ts3 = proto.texStorage3D;
    proto.texStorage3D = function (target, levels, internal, w, h, d) {
      let bytes = 0;
      for (let l = 0, ww = w, hh = h; l < levels; l++, ww = Math.max(1, ww >> 1), hh = Math.max(1, hh >> 1)) bytes += ww * hh * d * bpp(this, internal);
      note(this, target, 0, 'storage3d', bytes, w, h);
      return ts3.apply(this, arguments);
    };
    const gm = proto.generateMipmap;
    proto.generateMipmap = function (target) {
      const tex = key(this, target);
      const e = tex && mem.tex.get(tex);
      if (e && !e.mip) { e.mip = true; let base = 0; for (const [k, v] of e.levels) if (k.endsWith(':0')) base += v; e.levels.set('mips', Math.round(base / 3)); }
      return gm.apply(this, arguments);
    };
    const dt = proto.deleteTexture;
    proto.deleteTexture = function (t) { mem.tex.delete(t); return dt.apply(this, arguments); };
    let rbBound = null;
    const brb = proto.bindRenderbuffer;
    proto.bindRenderbuffer = function (t, rb) { rbBound = rb; return brb.apply(this, arguments); };
    const rbs = proto.renderbufferStorage;
    proto.renderbufferStorage = function (t, internal, w, h) { if (rbBound) mem.rb.set(rbBound, w * h * bpp(this, internal)); return rbs.apply(this, arguments); };
    const rbm = proto.renderbufferStorageMultisample;
    proto.renderbufferStorageMultisample = function (t, samples, internal, w, h) { if (rbBound) mem.rb.set(rbBound, w * h * bpp(this, internal) * Math.max(1, samples)); return rbm.apply(this, arguments); };
    const drb = proto.deleteRenderbuffer;
    proto.deleteRenderbuffer = function (rb) { mem.rb.delete(rb); return drb.apply(this, arguments); };
    let bufBound = {};
    const bb = proto.bindBuffer;
    proto.bindBuffer = function (t, b) { bufBound[t] = b; return bb.apply(this, arguments); };
    const bd = proto.bufferData;
    proto.bufferData = function (t, data) { const b = bufBound[t]; if (b) mem.buf.set(b, typeof data === 'number' ? data : (data && data.byteLength) || 0); return bd.apply(this, arguments); };
    const db = proto.deleteBuffer;
    proto.deleteBuffer = function (b) { mem.buf.delete(b); return db.apply(this, arguments); };
  };
  wrap(WebGL2RenderingContext.prototype);
  // 2D canvases created by the game (CPU/GPU backing stores that stay alive while referenced).
  const ce = Document.prototype.createElement;
  const reg = new FinalizationRegistry(() => {});
  const refs = [];
  Document.prototype.createElement = function (tag) {
    const el = ce.apply(this, arguments);
    if (String(tag).toLowerCase() === 'canvas') refs.push(new WeakRef(el));
    return el;
  };
  window.__canvasRefs = refs;
  // boot phases
  window.__phases = [];
  const t0 = performance.now();
  new MutationObserver(() => {
    const m = document.getElementById('boot-msg');
    const f = document.getElementById('boot-fill');
    window.__phases.push({ t: Math.round(performance.now() - t0), msg: m && m.textContent, fill: f && f.style.width });
  }).observe(document, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['style'] });
});
const page = await context.newPage();
const cdp = await context.newCDPSession(page);
await cdp.send('Performance.enable');
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));

const report = async (label) => {
  const m = await page.evaluate(() => {
    const mem = window.__mem;
    let tex = 0, n = 0;
    const big = [];
    for (const [, e] of mem.tex) { let s = 0; for (const v of e.levels.values()) s += v; tex += s; n++; big.push([s, `${e.w}x${e.h}${e.mip ? '+mips' : ''}`]); }
    big.sort((a, b) => b[0] - a[0]);
    let rb = 0; for (const v of mem.rb.values()) rb += v;
    let buf = 0; for (const v of mem.buf.values()) buf += v;
    let cv = 0, cvArea = 0; const cvs = [];
    for (const r of window.__canvasRefs) { const c = r.deref(); if (c && c.width * c.height > 1) { cv++; cvArea += c.width * c.height; cvs.push(`${c.width}x${c.height}`); } }
    const g = window.__game && window.__game.ctx;
    const info = g ? g.renderer.info.memory : {};
    const pm = performance.memory || {};
    const db = g ? g.renderer.getDrawingBufferSize(new g.THREE.Vector2()) : null;
    return { texMB: +(tex / 1048576).toFixed(1), textures: n, top: big.slice(0, 8).map(([s, d]) => `${d}=${(s / 1048576).toFixed(1)}MB`), rbMB: +(rb / 1048576).toFixed(1), bufMB: +(buf / 1048576).toFixed(1), canvases: cv, canvasMB: +((cvArea * 4) / 1048576).toFixed(1), canvasSizes: cvs.slice(0, 12), three: info, heapUsedMB: pm.usedJSHeapSize ? +(pm.usedJSHeapSize / 1048576).toFixed(1) : null, drawingBuffer: db && `${db.x}x${db.y}`, quality: g && g.quality };
  });
  const metrics = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((x) => [x.name, x.value]));
  console.log(`[${label}]`, JSON.stringify(m), `JSHeapUsed=${(metrics.JSHeapUsedSize / 1048576).toFixed(1)}MB TaskDuration=${metrics.TaskDuration.toFixed(1)}s ScriptDuration=${metrics.ScriptDuration.toFixed(1)}s`);
};

const t0 = Date.now();
await page.goto(`${base}?seed=7${quality === 'auto' ? '' : '&quality=' + quality}`);
await page.waitForFunction(() => window.__booted || (window.__game && window.__game.errors.length), null, { timeout: 600000, polling: 250 });
console.log('boot wall ms', Date.now() - t0);
const phases = await page.evaluate(() => window.__phases);
let last = null;
const gaps = [];
for (const p of phases) {
  const key = `${p.msg}|${p.fill}`;
  if (last && key === last.key) continue;
  if (last) gaps.push([p.t - last.t, last.key]);
  last = { t: p.t, key };
}
gaps.sort((a, b) => b[0] - a[0]);
console.log('longest gaps between boot progress updates (ms):', JSON.stringify(gaps.slice(0, 6)));
console.log('phase timeline:', JSON.stringify(phases.filter((p, i, a) => i === 0 || p.msg !== a[i - 1].msg).map((p) => [p.t, p.msg])));
await report('after boot');
await page.evaluate(() => window.__game.startBattle(1));
await page.waitForTimeout(15000);
await report('battle +15s');
await page.evaluate(() => window.__game.startBattle(3));
await page.waitForTimeout(15000);
await report('day3 +15s');
console.log('errors', errors);
await browser.close();
server.close();
