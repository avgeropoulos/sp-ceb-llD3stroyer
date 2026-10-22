// Cross-module integration audit: dumps the runtime API surface of every ctx module (keys + getters).
import { launch } from './lib.mjs';
const l = await launch();
try {
  const page = await l.newPage();
  await page.goto(`${l.game}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const api = await page.evaluate(() => {
    const ctx = window.__game.ctx;
    const out = {};
    for (const m of ['textures','world','characters','fx','audio','viewmodel','combat','ai','player','waves','hud','menus','inputCtl','training','gestures','motionFrames','platform']) {
      const o = ctx[m];
      if (!o) { out[m] = null; continue; }
      const names = new Set();
      let p = o;
      while (p && p !== Object.prototype) { for (const k of Object.getOwnPropertyNames(p)) names.add(k); p = Object.getPrototypeOf(p); }
      names.delete('constructor');
      out[m] = [...names].sort();
    }
    out.platform_sub = {};
    for (const s of ['motion','voice','haptics','storage']) out.platform_sub[s] = ctx.platform[s] ? Object.keys(ctx.platform[s]).sort() : null;
    out.settings = Object.keys(ctx.settings).sort();
    return out;
  });
  console.log(JSON.stringify(api, null, 1));
} finally { await l.close(); }
