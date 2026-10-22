// End-to-end smoke test of the whole game in headless Chromium.
// Boots index.html, checks the title screen, starts a battle and training, and drives a short fight with
// keyboard-style actions and synthesized phone motion. Fails on any page error.
// Usage: node spartan/tests/e2e/smoke.mjs [outDir] [WxH]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const outDir = process.argv[2] || null;
const [width, height] = (process.argv[3] || '960x540').split('x').map(Number);
if (outDir) fs.mkdirSync(outDir, { recursive: true });

const l = await launch();
const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};

try {
  const page = await l.newPage({ width, height });
  const shot = async (name) => {
    if (!outDir) return;
    await page.evaluate(() => window.__game.render());
    await page.screenshot({ path: path.join(outDir, `${name}.png`), timeout: 180000 });
  };
  const errors = async () => page.errors.concat(await page.evaluate(() => window.__game.errors));
  const step = (n, dt = 1 / 60) => page.evaluate(([n, dt]) => window.__game.step(n, dt), [n, dt]);

  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 120000 });
  check((await errors()).length === 0, `boots without errors ${JSON.stringify(await errors()).slice(0, 2000)}`);
  check(await page.evaluate(() => window.__game.ready), 'reaches ready');
  await step(30);
  check(await page.evaluate(() => window.__game.ctx.mode === 'title'), 'starts on the title screen');
  await shot('01-title');

  // Battle, day 1.
  await page.evaluate(() => window.__game.startBattle(1));
  await step(60);
  check(await page.evaluate(() => window.__game.ctx.mode === 'battle'), 'battle mode');
  await shot('02-battle-start');
  for (let i = 0; i < 40; i++) await step(15, 1 / 30);
  const s1 = await page.evaluate(() => window.__game.stats());
  console.log('stats after 20 s', JSON.stringify(s1));
  check(s1.actors > 0, `actors spawned (${s1.actors})`);
  await shot('03-battle-20s');

  // Fight: slashes, thrusts, shield, bash and a spear throw from actions.
  const fight = async () => {
    await page.evaluate(() => {
      const g = window.__game;
      g.action({ type: 'slash', dir: 0, power: 0.8 });
    });
    await step(12);
    await page.evaluate(() => window.__game.action({ type: 'slash', dir: Math.PI, power: 0.8 }));
    await step(12);
    await page.evaluate(() => window.__game.action({ type: 'thrust', power: 0.7 }));
    await step(20);
  };
  for (let i = 0; i < 6; i++) await fight();
  await shot('04-fight');
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.input.shieldHeld = true;
    g.ctx.input.shieldPressedAt = g.ctx.time.gnow;
  });
  await step(20);
  await shot('05-shield');
  await page.evaluate(() => window.__game.action({ type: 'thrust', power: 0.9 }));
  await step(10);
  await page.evaluate(() => {
    window.__game.ctx.input.shieldHeld = false;
  });
  await page.evaluate(() => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }));
  await step(60);
  await page.evaluate(() => window.__game.action({ type: 'throw', power: 0.8 }));
  await step(12);
  const thrown = await page.evaluate(() => window.__game.ctx.fx.cameraOverridden);
  check(thrown, 'spear throw starts the spear cam');
  await step(20);
  await shot('06-spearcam');
  for (let i = 0; i < 12; i++) await step(10);
  check(!(await page.evaluate(() => window.__game.ctx.fx.cameraOverridden)), 'spear cam ends');

  // Motion pipeline end to end: a synthesized R swing becomes a slash action.
  const swings = await page.evaluate(() => {
    const g = window.__game;
    g.ctx.settings.control = 'motion';
    g.ctx.input.motion = true;
    const seen = [];
    const off = g.ctx.events.on('gesture', (e) => seen.push(e.type + ':' + (e.dir != null ? e.dir.toFixed(2) : '')));
    g.injectMotion(g.synth([{ kind: 'idle', duration: 0.5 }, { kind: 'swing', dir: 0, speed: 10 }, { kind: 'idle', duration: 0.6 }], { rot: 90 }));
    return new Promise((resolve) => {
      let n = 0;
      const tick = () => {
        g.step(6);
        if (++n < 40) setTimeout(tick, 16);
        else {
          off();
          resolve(seen);
        }
      };
      tick();
    });
  });
  console.log('motion gestures', swings);
  check(swings.some((s) => s.startsWith('slash')), 'synthesized swing is recognized in-game');
  await page.evaluate(() => {
    window.__game.ctx.settings.control = 'touch';
    window.__game.ctx.input.motion = false;
  });

  for (let i = 0; i < 20; i++) await step(15, 1 / 30);
  const s2 = await page.evaluate(() => window.__game.stats());
  console.log('stats after fight', JSON.stringify(s2));
  check(s2.calls <= 260, `draw calls within budget-ish (${s2.calls})`);

  // Pause and resume.
  await page.evaluate(() => window.__game.ctx.setMode('paused'));
  await step(5);
  await shot('07-paused');
  await page.evaluate(() => window.__game.ctx.resume());
  await step(5);
  check(await page.evaluate(() => window.__game.ctx.mode === 'battle'), 'resumes to battle');

  // Training.
  await page.evaluate(() => window.__game.startTraining());
  await step(60);
  check(await page.evaluate(() => window.__game.ctx.mode === 'training'), 'training mode');
  await shot('08-training');

  // Day 3.
  await page.evaluate(() => window.__game.startBattle(3));
  for (let i = 0; i < 20; i++) await step(15, 1 / 30);
  await shot('09-day3');

  // Death → game over.
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.player.damage(1000, { from: null, kind: 'melee' });
  });
  for (let i = 0; i < 20; i++) await step(15, 1 / 30);
  check(await page.evaluate(() => window.__game.ctx.mode === 'gameover'), 'player death leads to game over');
  await shot('10-gameover');

  const errs = await errors();
  check(errs.length === 0, `no runtime errors (${errs.length}) ${errs.slice(0, 5).join('\n')}`);
} catch (err) {
  failures.push(String(err && err.stack || err));
  console.error(err);
} finally {
  await l.close();
}
console.log(failures.length ? `\n${failures.length} FAILED` : '\nALL SMOKE CHECKS PASSED');
process.exit(failures.length ? 1 : 0);
