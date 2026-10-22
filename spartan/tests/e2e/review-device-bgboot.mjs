// Review (device lens): the player switches away while the game is still booting (a phone boot
// takes many seconds). MainActivity.onPause() calls wv.onPause() + wv.pauseTimers(), so rAF and
// timers stop and boot stalls; onResume() resumes timers and the page. Emulated with CDP
// Page.setWebLifecycleState frozen → active. Watches for the engine-failure panel.
// Usage: node spartan/tests/e2e/review-device-bgboot.mjs [awaySeconds]
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage } from './review-device-native.mjs';

const away = Number(process.argv[2] || 20);
const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });
const page = await phonePage(browser, { dpr: 1 });
const cdp = await page.context().newCDPSession(page);
await page.addInitScript(() => {
  // WebView.onPause() hides the page, so rAF stops until onResume(); hold rAF callbacks while
  // __hiddenSim is set (CDP freezing alone resumes rAF and timers together).
  const raf = window.requestAnimationFrame.bind(window);
  const held = [];
  window.__hiddenSim = false;
  window.requestAnimationFrame = (cb) => (window.__hiddenSim ? (held.push(cb), -1) : raf(cb));
  window.__unhide = () => { window.__hiddenSim = false; for (const cb of held.splice(0)) raf(cb); };
  window.__failLog = [];
  const t0 = Date.now();
  new MutationObserver(() => {
    const el = document.getElementById('engine-fail');
    const s = el ? el.textContent : null;
    const last = window.__failLog[window.__failLog.length - 1];
    if (!last || last.s !== s) window.__failLog.push({ t: Date.now() - t0, s, booted: !!window.__booted, msg: (document.getElementById('boot-msg') || {}).textContent });
  }).observe(document, { subtree: true, childList: true });
});
await page.goto(`${base}?seed=7&quality=low`);
// Leave the app as soon as the world starts being built (after the texture pass).
await page.waitForFunction(() => /Phocian|Mustering|watch fires/.test((document.getElementById('boot-msg') || {}).textContent || ''), null, { timeout: 600000, polling: 20 });
const at = await page.evaluate(() => document.getElementById('boot-msg').textContent);
await page.evaluate(() => { window.__hiddenSim = true; }); // wv.onPause(): page hidden, no rAF
await cdp.send('Page.setWebLifecycleState', { state: 'frozen' }); // wv.pauseTimers()
console.log(`frozen (app in background) at boot step "${at}" for ${away}s`);
await new Promise((r) => setTimeout(r, away * 1000));
await cdp.send('Page.setWebLifecycleState', { state: 'active' }); // onResume(): wv.resumeTimers() ...
await new Promise((r) => setTimeout(r, 30));
await page.evaluate(() => window.__unhide()); // ... then wv.onResume(): visible, rAF again
console.log('active (app resumed: timers first, then page shown, as MainActivity.onResume orders them)');
await page.waitForFunction(() => window.__booted, null, { timeout: 600000, polling: 100 });
await page.waitForTimeout(500);
console.log('engine-fail panel history:', JSON.stringify(await page.evaluate(() => window.__failLog), null, 1));
await browser.close();
server.close();
