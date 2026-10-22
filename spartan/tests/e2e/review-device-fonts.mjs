// Review (device lens): UI layout with Android's only serif (Noto Serif) vs the local fallback
// (Liberation Serif, Times metrics), at Pixel-like 915×412 and Samsung-like 800×360 landscape.
// Reports text that is clipped by an overflow:hidden ancestor, spills off-screen, or wraps where
// nowrap; screenshots each screen.
// Usage: node spartan/tests/e2e/review-device-fonts.mjs <noto-inline.css> [outDir] [WxH,...]
import fs from 'node:fs';
import path from 'node:path';
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS } from './review-device-native.mjs';

const notoCss = fs.readFileSync(process.argv[2], 'utf8');
const outDir = process.argv[3] || '/tmp/fonts';
const sizes = (process.argv[4] || '915x412,800x360').split(',').map((s) => s.split('x').map(Number));
fs.mkdirSync(outDir, { recursive: true });
const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });

const PROBE = () => {
  const W = innerWidth, H = innerHeight;
  const issues = [];
  const visible = (e) => {
    for (let n = e; n && n !== document.documentElement; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return false;
    }
    return true;
  };
  const label = (e) => `${e.tagName.toLowerCase()}${e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\s+/).join('.') : ''}`;
  const texts = [...document.querySelectorAll('#overlay *')].filter((e) => [...e.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
  for (const e of texts) {
    if (!visible(e)) continue;
    const r = e.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    const txt = e.textContent.trim().slice(0, 40);
    // clipped by an ancestor
    let scroller = false;
    for (let a = e.parentElement; a && a.id !== 'overlay'; a = a.parentElement) {
      const cs = getComputedStyle(a);
      if (/(auto|scroll)/.test(cs.overflowX + cs.overflowY)) { scroller = true; break; }
      if (/(hidden|clip)/.test(cs.overflowX + cs.overflowY)) {
        const ar = a.getBoundingClientRect();
        if (r.right > ar.right + 1 || r.left < ar.left - 1 || r.bottom > ar.bottom + 1 || r.top < ar.top - 1) {
          issues.push(`clipped by ${label(a)}: ${label(e)} "${txt}" [${r.left|0},${r.top|0},${r.right|0},${r.bottom|0}] in [${ar.left|0},${ar.top|0},${ar.right|0},${ar.bottom|0}]`);
        }
        break;
      }
    }
    if (!scroller && (r.right > W + 1 || r.left < -1 || r.bottom > H + 1 || r.top < -1)) {
      issues.push(`off-screen: ${label(e)} "${txt}" [${r.left|0},${r.top|0},${r.right|0},${r.bottom|0}] viewport ${W}x${H}`);
    }
    const cs = getComputedStyle(e);
    if (e.scrollWidth > e.clientWidth + 1 && /(hidden|clip)/.test(cs.overflowX)) {
      issues.push(`text overflow: ${label(e)} "${txt}" scrollWidth ${e.scrollWidth} > ${e.clientWidth}`);
    }
  }
  // scroll containers whose content overflows: can they scroll?
  for (const a of document.querySelectorAll('#overlay *')) {
    if (!visible(a)) continue;
    const cs = getComputedStyle(a);
    if (/(auto|scroll)/.test(cs.overflowY) && a.scrollHeight > a.clientHeight + 1) issues.push(`scrolls: ${label(a)} ${a.scrollHeight} > ${a.clientHeight} (touch-action ${cs.touchAction})`);
  }
  return issues;
};

for (const font of ['local', 'noto']) {
  for (const [W, H] of sizes) {
    const context = await browser.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, isMobile: true, hasTouch: true });
    const page = await context.newPage();
    page.errors = [];
    page.on('pageerror', (e) => page.errors.push(e.message));
    await page.goto(`${base}?manual=1&seed=7&quality=low`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
    if (font === 'noto') {
      await page.addStyleTag({ content: notoCss + '\n:root{--serif:"NotoSerifTest", serif !important}' });
      await page.evaluate(() => document.fonts.ready);
      await page.evaluate(() => Promise.all(['400', '700'].map((w) => document.fonts.load(`${w} 16px NotoSerifTest`, 'AΘ'))));
    }
    const used = await page.evaluate(() => getComputedStyle(document.querySelector('#menus')).fontFamily);
    const tag = `${font}-${W}x${H}`;
    console.log(`\n=== ${tag} (font-family: ${used.slice(0, 60)}; Noto loaded: ${await page.evaluate(() => document.fonts.check('16px NotoSerifTest'))})`);
    const step = (n) => page.evaluate((n) => window.__game.step(n), n);
    const probe = async (name) => {
      await step(3);
      const issues = await page.evaluate(PROBE);
      console.log(`[${name}] ${issues.length ? '\n  ' + issues.join('\n  ') : 'ok'}`);
      await page.screenshot({ path: path.join(outDir, `${tag}-${name}.png`) });
    };
    await step(30);
    await probe('title');
    for (const tab of await page.evaluate(() => { window.__game.ctx.menus.show('settings'); return [...document.querySelectorAll('[data-screen="settings"] [data-tab]')].map((b) => b.dataset.tab); })) {
      await page.evaluate((t) => document.querySelector(`[data-screen="settings"] [data-tab="${t}"]`).click(), tab);
      await probe(`settings-${tab}`);
    }
    await page.evaluate(() => { window.__game.ctx.menus.show('title'); window.__game.ctx.menus.show('howto'); });
    for (const tab of ['motion', 'touch', 'keys']) {
      await page.evaluate((t) => document.querySelector(`[data-screen="howto"] [data-tab="${t}"]`).click(), tab);
      await probe(`howto-${tab}`);
    }
    await page.evaluate(() => { window.__game.ctx.menus.show('title'); window.__game.ctx.menus.show('safety'); });
    await probe('safety');
    await page.evaluate(() => window.__game.startBattle(1));
    await step(240);
    await probe('battle-hud');
    await page.evaluate(() => window.__game.ctx.setMode('paused'));
    await probe('pause');
    await page.evaluate(() => { window.__game.ctx.resume(); window.__game.ctx.player.damage(1000, { from: null, kind: 'melee' }); });
    for (let i = 0; i < 8; i++) await step(30);
    await probe('gameover');
    await page.evaluate(() => window.__game.startTraining());
    await step(120);
    await probe('training');
    if (page.errors.length) console.log('errors', page.errors.slice(0, 5));
    await context.close();
  }
}
await browser.close();
server.close();
