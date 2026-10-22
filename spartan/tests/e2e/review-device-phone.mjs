// Review (device lens): phone-like play-through with a strict fake SpartanNative.
// boot → title → choose Motion (touch taps) → Fight → motion swing → activity pause/resume →
// back ×N → quit → back on title. Prints every bridge call count, coercion warning and error.
// Usage: node spartan/tests/e2e/review-device-phone.mjs [outDir]
import fs from 'node:fs';
import path from 'node:path';
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage, tap } from './review-device-native.mjs';

const outDir = process.argv[2] || null;
if (outDir) fs.mkdirSync(outDir, { recursive: true });
const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });
const page = await phonePage(browser);
const log = (...a) => console.log(...a);
const step = (n, dt = 1 / 60) => page.evaluate(([n, dt]) => window.__game.step(n, dt), [n, dt]);
const shot = async (name) => {
  if (!outDir) return;
  await page.evaluate(() => window.__game.render());
  await page.screenshot({ path: path.join(outDir, `${name}.png`), timeout: 180000 });
};
const st = () => page.evaluate(() => {
  const s = window.__nat.st;
  return { calls: s.calls, warn: s.warn, orient: s.orient, locked: s.locked, wanted: s.wanted, vib: s.vib.length, said: s.said.length, exits: s.exits, mode: window.__game.ctx && window.__game.ctx.mode, control: window.__game.ctx && window.__game.ctx.settings.control, audio: window.__game.ctx && window.__game.ctx.audio && window.__game.ctx.audio.ac ? window.__game.ctx.audio.ac.state : undefined };
});

try {
  const t0 = Date.now();
  await page.goto(`${base}?manual=1&seed=7`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  log('boot ms', Date.now() - t0, 'quality', await page.evaluate(() => window.__game.ctx.quality), 'platform', await page.evaluate(() => window.__game.ctx.platform.kind));
  await step(30);
  log('title', JSON.stringify(await st()));
  await shot('01-title');

  // Choose Motion on the title with a touch tap.
  await tap(page, '.mn-seg[data-field="control"] [data-v="motion"]');
  await step(10);
  log('after tap Motion: screen', await page.evaluate(() => [...document.querySelectorAll('.mn-screen')].filter((s) => getComputedStyle(s).display !== 'none' && s.classList.contains('is-on')).map((s) => s.dataset.screen).join(',')));
  await shot('02-safety');
  await tap(page, '[data-act="safety-accept"]');
  for (let i = 0; i < 10; i++) await step(6);
  await page.waitForTimeout(200);
  await step(10);
  log('after accept', JSON.stringify(await st()));

  // Fight.
  await tap(page, '[data-act="fight"]');
  await step(60);
  log('battle', JSON.stringify(await st()));

  // Feed 1 s idle + a right-to-left swing at 200 Hz through the native ring, polled each frame.
  const gestures = await page.evaluate(async () => {
    const g = window.__game;
    const rr = g.ctx.renderer.render;
    g.ctx.renderer.render = () => {}; // SwiftShader is slow: skip drawing while feeding
    const seen = [];
    const off = g.ctx.events.on('gesture', (e) => seen.push(e.type));
    const samples = g.synth([{ kind: 'idle', duration: 1.0 }, { kind: 'swing', dir: 0, speed: 10 }, { kind: 'idle', duration: 1.0 }], { rot: 90, rate: 200, t0: 1 });
    // deliver ~3.3 samples per 1/60 s frame
    let i = 0;
    while (i < samples.length) {
      window.__nat.feed(samples.slice(i, i + 3));
      i += 3;
      g.step(1);
    }
    off();
    g.ctx.renderer.render = rr;
    return seen;
  });
  log('gestures from native lines', JSON.stringify(gestures));

  // Activity pause → resume (MainActivity order).
  await page.evaluate(() => window.__nat.activityPause());
  await page.waitForTimeout(300);
  log('after activityPause', JSON.stringify(await st()));
  await page.evaluate(() => window.__nat.activityResume());
  await page.waitForTimeout(300);
  await step(10);
  log('after activityResume', JSON.stringify(await st()));
  await shot('03-paused-after-resume');

  // Back from the pause menu → battle; back in battle → pause; back → battle.
  for (let k = 0; k < 3; k++) {
    const r = await page.evaluate(() => window.__nat.back());
    await step(5);
    log(`back #${k + 1} ->`, r, JSON.stringify(await st()));
  }
  // Quit to title via pause → Quit (two taps) and press back on the title.
  await page.evaluate(() => window.__nat.back());
  await step(5);
  await tap(page, '.mn-screen[data-screen="pause"] [data-act="quit"]');
  await step(2);
  await tap(page, '.mn-screen[data-screen="pause"] [data-act="quit"]');
  await step(10);
  const r = await page.evaluate(() => window.__nat.back());
  log('back on title ->', r, '(Kotlin: anything but "true" finishes the activity)', JSON.stringify(await st()));

  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  log('page errors', errs.length, errs.slice(0, 10).join('\n'));
  log('warnings', page.logs.slice(0, 20).join('\n'));
  log('bridge warnings', JSON.stringify(await page.evaluate(() => window.__nat.st.warn)));
} catch (e) {
  console.error('FAILED', e);
} finally {
  await browser.close();
  server.close();
}
