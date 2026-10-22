// Review (device lens): sensor lines that pile up in the native ring while the game sits in its own
// pause menu (sensors stay registered for mode 'paused') are replayed into the gesture recognizer
// on Resume. Feeds a continuous 200 Hz timeline through the strict fake SpartanNative:
// guard (1.5 s, battle) → [paused] what the phone does in the menu → guard (2.5 s, battle again).
// Prints view yaw change and gestures after resume, for a still phone vs a turned / swung one.
// Usage: node spartan/tests/e2e/review-device-pausemotion.mjs
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage } from './review-device-native.mjs';

const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });

const CASES = {
  still: [{ kind: 'idle', duration: 2.5 }],
  'turn 90° left then hold still': [{ kind: 'turn', yaw: Math.PI / 2, duration: 1.0 }, { kind: 'idle', duration: 1.5 }],
  'swing (e.g. waving the phone) then still': [{ kind: 'swing', dir: 0, speed: 10 }, { kind: 'idle', duration: 1.2 }],
};

for (const [name, during] of Object.entries(CASES)) {
  const page = await phonePage(browser, { dpr: 1 });
  await page.goto(`${base}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const res = await page.evaluate((during) => {
    const g = window.__game;
    const ctx = g.ctx;
    ctx.renderer.render = () => {};
    ctx.settings.control = 'motion';
    ctx.settings.safetyAccepted = true;
    ctx.setMode('battle', { day: 1 });
    const pre = [{ kind: 'idle', duration: 1.5 }];
    const post = [{ kind: 'idle', duration: 2.5 }];
    const all = g.synth(pre.concat(during, post), { rot: 90, rate: 200, t0: 5, noise: 1 });
    const preEnd = 5 + 1.5;
    const durEnd = preEnd + g.synth(during, { rate: 200 }).slice(-1)[0].t;
    const seg = (a, b) => all.filter((s) => s.t >= a && s.t < b);
    const feedRun = (samples, frames) => {
      // ~3.33 samples per 60 Hz frame
      let i = 0, k = 0;
      while (i < samples.length) {
        const n = (k++ % 3 === 0) ? 4 : 3;
        window.__nat.feed(samples.slice(i, i + n));
        i += n;
        g.step(1);
      }
      for (let j = 0; j < frames; j++) g.step(1);
    };
    const gestures = [];
    ctx.events.on('gesture', (e) => gestures.push({ phase: ctx.mode, type: e.type, t: +ctx.time.now.toFixed(2) }));
    const acts = [];
    const push0 = ctx.actions.push;
    ctx.actions.push = (a) => { acts.push({ mode: ctx.mode, type: a.type, src: a.src }); return push0(a); };
    feedRun(seg(0, preEnd), 30);
    const yaw0 = ctx.player.yaw;
    // In-game pause (Android back): mode 'paused'; the phone keeps streaming into the native ring.
    const backPause = window.__nat.back();
    const pausedMode = ctx.mode;
    const startedDuringPause = window.__nat.st.wanted;
    feedRun(seg(preEnd, durEnd), 0); // the game steps (menu), pumpMotion does not poll
    const ringLines = window.__nat.st.ring.length;
    const yawPaused = ctx.player.yaw;
    // Resume (Android back again)
    const backResume = window.__nat.back();
    const g0 = gestures.length;
    const a0 = acts.length;
    feedRun(seg(durEnd, 1e9), 60);
    return {
      backPause, pausedMode, sensorsStillWanted: startedDuringPause, ringLinesAtResume: ringLines,
      backResume, modeAfter: ctx.mode,
      yawChangeDuringPause: +(yawPaused - yaw0).toFixed(3),
      yawChangeAfterResumeDeg: +(((ctx.player.yaw - yawPaused) * 180) / Math.PI).toFixed(1),
      gesturesAfterResume: gestures.slice(g0),
      actionsAfterResume: acts.slice(a0).filter((a) => a.src === 'motion'),
      actionsBeforePause: acts.slice(0, a0).filter((a) => a.src === 'motion').length,
    };
  }, during);
  console.log(`\n=== phone in the pause menu: ${name}`);
  console.log(JSON.stringify(res, null, 1));
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  if (errs.length) console.log('errors', errs.slice(0, 5));
  await page.context().close();
}
await browser.close();
server.close();
