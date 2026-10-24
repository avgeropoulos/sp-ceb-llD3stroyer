// Shared Playwright helpers for the local end-to-end scripts.
// Chromium runs headless with SwiftShader so WebGL2 works without a GPU.
import { startServer } from '../serve.mjs';

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    return await import('/opt/node22/lib/node_modules/playwright/index.mjs');
  }
}

/**
 * Init script of newPage({ grip }): before each load, merges { grip, gripChosen: true } into the
 * stored settings unless they already hold a grip, so scripts written for the window grip keep it
 * whatever DEFAULT_GRIP is (docs-hilt-grip.md §H.2). Unreadable stored settings are left alone.
 */
function seedGrip(grip) {
  try {
    const key = 'thermopylae.settings';
    const str = localStorage.getItem(key);
    const s = str == null ? {} : JSON.parse(str);
    if (!s || typeof s !== 'object' || Array.isArray(s) || 'grip' in s) return;
    s.grip = grip;
    s.gripChosen = true;
    localStorage.setItem(key, JSON.stringify(s));
  } catch {
    /* no storage on this page (about:blank), or not JSON */
  }
}

/**
 * Starts the static server and a browser. Returns { browser, base, newPage(opts), close() }.
 * newPage() collects console errors and page errors into page.errors.
 * newPage({ grip }) seeds the stored settings' grip: 'window' (default), 'hilt', or null for none.
 */
export async function launch() {
  const { chromium } = await loadPlaywright();
  const server = await startServer(0);
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'],
  });
  async function newPage({ width = 960, height = 540, mobile = false, grip = 'window' } = {}) {
    const context = await browser.newContext({
      viewport: { width, height },
      deviceScaleFactor: 1,
      hasTouch: mobile,
      isMobile: mobile,
    });
    if (grip) await context.addInitScript(seedGrip, grip);
    const page = await context.newPage();
    page.errors = [];
    page.on('pageerror', (e) => page.errors.push(`pageerror: ${e.stack || e.message}`));
    page.on('console', (m) => {
      if (m.type() === 'error') page.errors.push(`console: ${m.text()}`);
    });
    return page;
  }
  return {
    browser,
    base,
    game: `${base}/src/main/assets/game/index.html`,
    newPage,
    async close() {
      await browser.close();
      server.close();
    },
  };
}
