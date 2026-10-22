// Cross-module integration audit (d): a training demo's injected motion samples survive Quit.
// platform.motion keeps its injected queue (resetAll never clears it) and discards REAL samples while
// it is non-empty, so (a) in motion mode the next battle receives the stale demo as gestures/attacks, and
// (b) in touch mode the next demo first replays the tail of the old one.
import { launch } from './lib.mjs';
const control = process.argv[2] || 'motion';
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low&control=${control}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const r = await page.evaluate(async (control) => {
    const g = window.__game, c = g.ctx;
    const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
    const gest = [], swings = [];
    c.events.on('gesture', (e) => gest.push(`${c.mode}:${e.type}${e.dir != null ? '(' + (e.dir * 180 / Math.PI).toFixed(0) + ')' : ''}`));
    c.events.on('swing', (e) => swings.push(`${c.mode}:${e.kind}/${e.src}`));
    g.startTraining(); g.step(5);
    const ok = await c.training.demo('slashLR');           // 4 slashes over ~3.5 s
    g.step(2);
    await sleep(300); g.step(2);                           // a fraction delivered
    const consumedBefore = gest.length;
    c.setMode('paused'); c.setMode('title'); g.step(2);    // pause → Quit
    await sleep(5000);                                     // read the title screen for a while
    const marks = { demoStarted: ok, gesturesBeforeQuit: consumedBefore };
    if (control === 'motion') {
      c.settings.control = 'motion';                      // headless has no sensors: menus' probe reverted it to touch
      g.startBattle(1); c.waves.stop(); g.step(1);
      for (let i = 0; i < 10; i++) { g.step(3); await sleep(50); }
      marks.battle = { control: c.settings.control, gestures: gest.filter((x) => x.startsWith('battle')), swings: swings.filter((x) => x.startsWith('battle')) };
    } else {
      g.startTraining(); g.step(5);
      await c.training.demo('thrust');                     // 3 thrusts, no slash
      for (let i = 0; i < 40; i++) { g.step(3); await sleep(60); }
      marks.secondDemo = gest.filter((x) => x.startsWith('training')).slice(consumedBefore);
    }
    return marks;
  }, control);
  console.log(JSON.stringify(r, null, 1));
  console.log('errors', await page.evaluate(() => window.__game.errors), page.errors);
} finally { await l.close(); }
