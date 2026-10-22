// Review (device lens): the notification shade (or power menu, Assistant, a system alert) takes
// window focus WITHOUT pausing the activity (Activity.onWindowFocusChanged docs), so neither
// nativepause nor visibilitychange fires. WebView reports it as window 'blur'. Does the battle stop?
// Usage: node spartan/tests/e2e/review-device-focus.mjs
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage } from './review-device-native.mjs';

const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });
const page = await phonePage(browser, { dpr: 1 });
await page.goto(`${base}?manual=1&seed=7&quality=low`);
await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
const res = await page.evaluate(() => {
  const g = window.__game;
  const ctx = g.ctx;
  ctx.renderer.render = () => {};
  g.startBattle(1);
  for (let i = 0; i < 60 * 25; i++) g.step(1); // let the first wave reach the line
  const hp0 = ctx.player.hp;
  const t0 = ctx.time.gnow;
  // MainActivity.onWindowFocusChanged(false): nothing is sent to the page; WebView blurs the window.
  window.dispatchEvent(new Event('blur'));
  let hurt = 0;
  const off = ctx.events.on('playerHurt', () => hurt++);
  for (let i = 0; i < 60 * 10; i++) g.step(1); // 10 s reading a notification
  off();
  return { modeWhileShadeDown: ctx.mode, gameSecondsElapsed: +(ctx.time.gnow - t0).toFixed(1), hpBefore: hp0, hpAfter: ctx.player.hp, hitsTaken: hurt };
});
console.log(JSON.stringify(res));
await browser.close();
server.close();
