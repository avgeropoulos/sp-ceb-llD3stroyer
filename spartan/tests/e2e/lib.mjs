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
 * Starts the static server and a browser. Returns { browser, base, newPage(opts), close() }.
 * newPage() collects console errors and page errors into page.errors.
 */
export async function launch() {
  const { chromium } = await loadPlaywright();
  const server = await startServer(0);
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'],
  });
  async function newPage({ width = 960, height = 540, mobile = false } = {}) {
    const context = await browser.newContext({
      viewport: { width, height },
      deviceScaleFactor: 1,
      hasTouch: mobile,
      isMobile: mobile,
    });
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
