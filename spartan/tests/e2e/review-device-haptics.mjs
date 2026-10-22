// Review (device lens): how often the game crosses the JS→Kotlin bridge for haptics and speech in
// a real fight. Each SpartanNative call is a synchronous renderer→browser IPC that runs the Kotlin
// body on the JavaBridge thread (Vibrator.vibrate / TextToSpeech.speak are binder calls) while
// the page's main thread waits. Counts calls per frame and per 100 ms / 1 s window.
// Usage: node spartan/tests/e2e/review-device-haptics.mjs [day] [seconds]
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage } from './review-device-native.mjs';

const day = Number(process.argv[2] || 3);
const seconds = Number(process.argv[3] || 120);
const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });
const page = await phonePage(browser, { dpr: 1 });
await page.goto(`${base}?manual=1&seed=11&quality=low`);
await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
const res = await page.evaluate(([day, seconds]) => {
  const g = window.__game;
  const ctx = g.ctx;
  ctx.renderer.render = () => {};
  ctx.player.damage = ((orig) => function (amt, info) { return orig.call(this, Math.min(amt, 0.001), info); })(ctx.player.damage); // survive
  g.startBattle(day);
  const st = window.__nat.st;
  const frames = [];
  const dt = 1 / 60;
  let lastAct = 0;
  for (let f = 0; f < seconds * 60; f++) {
    const before = st.vib.length + st.said.length;
    // alternate: shield up for 2 s, then swing for 2 s
    const phase = Math.floor(ctx.time.now / 2) % 2;
    ctx.input.shieldHeld = phase === 0;
    if (phase === 0 && ctx.input.shieldPressedAt < ctx.time.gnow - 2) ctx.input.shieldPressedAt = ctx.time.gnow;
    if (phase === 1 && ctx.time.now - lastAct > 0.35) {
      lastAct = ctx.time.now;
      g.action({ type: 'slash', dir: (f % 4) * Math.PI / 2, power: 0.8 });
    }
    g.step(1, dt);
    const n = st.vib.length + st.said.length - before;
    if (n) frames.push({ t: ctx.time.now, n });
    if (ctx.mode !== 'battle') break;
  }
  const vib = st.vib.map((v) => v.g);
  const win = (w) => {
    let best = 0;
    for (let i = 0, j = 0; i < vib.length; i++) {
      while (vib[i] - vib[j] > w) j++;
      best = Math.max(best, i - j + 1);
    }
    return best;
  };
  const kinds = {};
  for (const v of st.vib) { const k = v.k === 'one' ? `pulse ${v.ms}ms@${v.amp}` : `pattern ${v.csv}`; kinds[k] = (kinds[k] || 0) + 1; }
  return {
    gameSeconds: +ctx.time.now.toFixed(1), mode: ctx.mode, vibrateCalls: st.vib.length, sayCalls: st.said.length,
    perSecondAvg: +(st.vib.length / ctx.time.now).toFixed(2), maxIn100ms: win(0.1), maxIn1s: win(1.0),
    maxCallsInOneFrame: frames.reduce((m, x) => Math.max(m, x.n), 0),
    framesWith2plus: frames.filter((x) => x.n >= 2).length, kinds,
    pollMotionCalls: st.calls.pollMotion || 0,
  };
}, [day, seconds]);
console.log(JSON.stringify(res, null, 1));
const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
if (errs.length) console.log('errors', errs.slice(0, 5));
await browser.close();
server.close();
