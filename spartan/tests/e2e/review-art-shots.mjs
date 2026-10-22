// Art-direction review: screenshots of the real game in many situations at phone size.
// Usage: node spartan/tests/e2e/review-art-shots.mjs <outDir> [WxH=915x412] [scene ...]
// Each scene restarts from a known state on the same page (one boot), steps the game in manual
// mode and writes <outDir>/<scene>[-suffix].png. Prints a JSON line of facts per shot.
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', ...wanted] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });

const l = await launch();
// MOBILE=1 emulates a touch phone, so the touch layout (stick, SHIELD, BASH, THROW) is drawn.
const page = await l.newPage({ width, height, mobile: process.env.MOBILE === '1' });
const ev = (fn, arg) => page.evaluate(fn, arg);
const step = (n, dt = 1 / 60) => ev(([n, dt]) => window.__game.step(n, dt), [n, dt]);
const facts = () =>
  ev(() => {
    const g = window.__game;
    const c = g.ctx;
    return {
      mode: c.mode,
      hp: c.player.hp,
      weapon: c.player.weapon,
      shield: +c.player.shieldRaise.toFixed(2),
      held: c.input.shieldHeld,
      spear: c.player.hasSpearInHand,
      cam: c.fx.cameraOverridden,
      actors: c.actors.length,
      corpses: c.ai.corpses.length,
      wave: c.waves.state,
      stats: g.stats(),
    };
  });
// The shield is held through the real input path (input.js recomputes shieldHeld every frame).
const shieldOn = () => page.keyboard.down('KeyK');
const shieldOff = () => page.keyboard.up('KeyK');
async function shot(name) {
  const file = path.join(outDir, `${name}.png`);
  // Manual stepping blocks the page, so let CSS transitions in the HUD/menus settle (the canvas is preserved).
  await page.waitForTimeout(900);
  await page.screenshot({ path: file, timeout: 300000 });
  console.log(`${file} ${JSON.stringify(await facts())}`);
}

// A clean battle with no waves running: the player alone with what we spawn.
const sandbox = (day = 1, tod = 'morning', allies = false) =>
  ev(
    ([day, tod, allies]) => {
      const g = window.__game;
      g.startBattle(day);
      g.ctx.waves.stop();
      g.ctx.ai.clear();
      g.ctx.combat.reset();
      g.ctx.world.setTimeOfDay(tod);
      if (allies) g.ctx.ai.ensureAllies();
      g.ctx.hud.reset();
    },
    [day, tod, allies],
  );

const scenes = {
  async title() {
    await ev(() => window.__game.ctx.setMode('title'));
    await step(60);
    await shot('title');
  },
  async day1start() {
    await ev(() => window.__game.startBattle(1));
    await step(30);
    await shot('day1start-0.5s');
    await step(180);
    await shot('day1start-3.5s');
    for (let i = 0; i < 8; i++) await step(150, 1 / 30);
    await shot('day1start-43s');
  },
  async charge() {
    await sandbox(1, 'morning', true);
    await ev(() => {
      window.__game.spawn('levy', 0.3, -14);
      window.__game.spawn('immortal', -1.5, -17);
    });
    await step(90);
    await shot('charge-1.5s');
    await step(60);
    await shot('charge-2.5s');
  },
  async duel() {
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('levy', 0, -1.5));
    await step(40);
    await shot('duel-levy-1.5m');
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('immortal', 0, -1.5));
    await step(40);
    await shot('duel-immortal-1.5m');
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('captain', 0, -1.8));
    await step(40);
    await shot('duel-captain-1.8m');
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('sparabara', 0, -2.0));
    await step(40);
    await shot('duel-sparabara-2m');
  },
  async slash() {
    const dirs = { right: 0, up: Math.PI / 2, left: Math.PI, down: -Math.PI / 2, upleft: (3 * Math.PI) / 4 };
    for (const [name, dir] of Object.entries(dirs)) {
      await sandbox(1, 'morning');
      await ev(() => window.__game.spawn('levy', 0, -2.2));
      await step(30);
      await ev((dir) => window.__game.action({ type: 'slash', dir, power: 0.9 }), dir);
      await step(5);
      await shot(`slash-${name}-mid`);
    }
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('levy', 0, -2.0));
    await step(30);
    await ev(() => window.__game.action({ type: 'thrust', power: 0.9 }));
    await step(7);
    await shot('thrust-strike');
  },
  async slashseq() {
    for (const [name, dir] of [['up', Math.PI / 2], ['right', 0]]) {
      await sandbox(1, 'morning');
      await ev(() => window.__game.spawn('levy', 0, -2.2));
      await step(30);
      await ev((dir) => window.__game.action({ type: 'slash', dir, power: 0.9 }), dir);
      for (const f of [2, 4, 6, 8, 12]) {
        await step(f === 2 ? 2 : f === 12 ? 4 : 2);
        await shot(`slashseq-${name}-f${f}`);
      }
    }
  },
  async block() {
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('immortal', 0.2, -1.8));
    await step(20);
    await shieldOn();
    await step(30);
    await shot('block-raised');
    await ev(() => window.__game.action({ type: 'thrust', power: 0.9 }));
    await step(6);
    await shot('bash-strike');
    await shieldOff();
  },
  async spear() {
    await sandbox(1, 'morning');
    await ev(() => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }));
    await step(60);
    await shot('spear-idle');
    await ev(() => window.__game.spawn('levy', 0, -2.4));
    await step(30);
    await ev(() => window.__game.action({ type: 'thrust', power: 0.9 }));
    await step(8);
    await shot('spear-thrust');
    await step(40);
    await ev(() => {
      const g = window.__game;
      g.ctx.ai.clear();
      g.spawn('immortal', 0.4, -16);
    });
    await step(20);
    await ev(() => {
      window.__game.ctx.__camLog = [];
      window.__game.ctx.events.on('spearCam', (e) => window.__game.ctx.__camLog.push(e.phase));
      window.__game.action({ type: 'throw', power: 0.85 });
    });
    await step(6);
    await shot('spear-windup');
    await step(20);
    await shot('spearcam-flight');
    // Step until impact.
    for (let i = 0; i < 40; i++) {
      const ph = await ev(() => window.__game.ctx.__camLog.slice(-1)[0]);
      if (ph === 'impact' || ph === 'end') break;
      await step(3);
    }
    await step(4);
    await shot('spearcam-impact');
    await step(30);
    await shot('spearcam-orbit');
  },
  async volley() {
    await sandbox(1, 'morning', true);
    await ev(() => {
      const g = window.__game;
      g.ctx.__vol = [];
      g.ctx.events.on('volley', (e) => g.ctx.__vol.push(e.phase));
      g.ctx.combat.volley({ count: 300, duration: 1.0, warn: 2.5 });
    });
    await step(30);
    await shot('volley-warning');
    await shieldOn();
    for (let i = 0; i < 60; i++) {
      const ph = await ev(() => window.__game.ctx.__vol.slice(-1)[0]);
      if (ph === 'loosed') break;
      await step(5);
    }
    await step(50);
    await shot('volley-falling');
    for (let i = 0; i < 60; i++) {
      const ph = await ev(() => window.__game.ctx.__vol.slice(-1)[0]);
      if (ph === 'impact') break;
      await step(3);
    }
    await step(6);
    await shot('volley-impact-shieldup');
    await shieldOff();
    await step(60);
    await shot('volley-after-shieldlowered');
    await ev(() => {
      const g = window.__game;
      for (let i = 0; i < 6; i++) g.ctx.viewmodel.addStuckArrow();
    });
    await shieldOn();
    await step(30);
    await shot('arrows-in-shield-raised');
    await shieldOff();
  },
  async crowd() {
    await ev(() => window.__game.startBattle(1));
    for (let i = 0; i < 14; i++) await step(150, 1 / 30);
    await shot('crowd-70s');
    // Fight a bit to make corpses and blood near the player.
    for (let r = 0; r < 4; r++) {
      await ev(() => {
        const g = window.__game;
        g.spawn('levy', -0.4 + Math.random(), -2.0);
      });
      for (let k = 0; k < 6; k++) {
        await ev((k) => window.__game.action({ type: 'slash', dir: k % 2 ? Math.PI : 0, power: 1 }), k);
        await step(24);
      }
    }
    await step(90);
    await shot('crowd-corpses');
    await ev(() => {
      window.__game.ctx.player.pitch = -0.45;
    });
    await step(2);
    await shot('crowd-corpses-lookdown');
  },
  async lowhp() {
    await sandbox(1, 'morning');
    await ev(() => window.__game.spawn('immortal', 0, -1.8));
    await step(20);
    await ev(() => {
      const p = window.__game.ctx.player;
      p.hp = 18;
    });
    await step(60);
    await shot('lowhp-18');
    await ev(() => window.__game.ctx.player.damage(6, { from: null, kind: 'melee', relYaw: 0.8 }));
    await step(4);
    await shot('lowhp-hurtflash');
  },
  async times() {
    for (const [day, tod, label] of [
      [1, 'dusk', 'day1-dusk'],
      [2, 'noon', 'day2-noon'],
      [3, 'dawn', 'day3-dawn'],
      [1, 'afternoon', 'afternoon'],
    ]) {
      await sandbox(day, tod, true);
      await ev(() => {
        const g = window.__game;
        g.spawn('immortal', 0.5, -7);
        g.spawn('levy', -2, -10);
        g.spawn('sparabara', 2, -12);
      });
      await step(60);
      await shot(label);
    }
  },
  async agoge() {
    await ev(() => window.__game.startTraining());
    await step(90);
    await shot('agoge');
    await step(400);
    await shot('agoge-7s');
  },
  async gameover() {
    await ev(() => window.__game.startBattle(1));
    await step(60);
    await ev(() => window.__game.ctx.player.damage(1000, { from: null, kind: 'melee' }));
    for (let i = 0; i < 20; i++) await step(15, 1 / 30);
    await shot('gameover');
  },
  async allies() {
    await sandbox(1, 'morning', true);
    await step(60);
    await shot('allies-front');
    for (const [label, yaw] of [['left', 0.95], ['right', -0.95]]) {
      await ev((yaw) => {
        window.__game.ctx.player.yaw = yaw;
      }, yaw);
      await step(30);
      await shot(`allies-look-${label}`);
    }
    await ev(() => {
      window.__game.ctx.player.yaw = 0;
    });
  },
  async faces() {
    for (const [kind, opts, label] of [
      ['levy', { variant: 'mede' }, 'mede'],
      ['levy', { variant: 'kissian' }, 'kissian'],
      ['immortal', {}, 'immortal'],
      ['archer', {}, 'archer'],
    ]) {
      await sandbox(1, 'morning');
      await ev(([kind, opts]) => window.__game.spawn(kind, 0, -1.25, opts), [kind, opts]);
      await step(20);
      await ev(() => {
        window.__game.ctx.player.pitch = 0.05;
      });
      await step(2);
      await shot(`face-${label}`);
    }
    // Side view of a levy's guard pose.
    await sandbox(1, 'morning');
    await ev(() => {
      const a = window.__game.spawn('levy', 0, -4, { variant: 'mede' });
      return !!a;
    });
    await step(10);
    await ev(() => {
      const g = window.__game;
      // Stand to the side of it, looking at it, so its guard is seen in profile.
      g.ctx.player.pos.set(3.2, g.ctx.player.pos.y, -4.2);
      g.ctx.player.yaw = Math.PI / 2;
    });
    await step(30);
    await shot('levy-profile');
  },
  async phone() {
    // The touch layout over a real fight: an enemy at 1.6 m, the spear in hand (THROW shown), low HP.
    await sandbox(1, 'morning', true);
    await ev(() => {
      const g = window.__game;
      g.spawn('immortal', 0.1, -1.7);
      g.spawn('levy', -1.2, -3.2, { variant: 'mede' });
    });
    await step(70);
    await shot('phone-fight');
    await ev(() => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }));
    await step(40);
    await ev(() => {
      window.__game.ctx.player.hp = 22;
    });
    await step(30);
    await shot('phone-spear-lowhp');
  },
  async pause() {
    await sandbox(1, 'morning', true);
    await step(10);
    await ev(() => window.__game.ctx.setMode('paused'));
    await step(5);
    await shot('paused');
    await ev(() => window.__game.ctx.resume());
  },
};

let bad = 0;
try {
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const list = wanted.length ? wanted : Object.keys(scenes);
  for (const name of list) {
    const t0 = Date.now();
    try {
      await scenes[name]();
    } catch (err) {
      bad++;
      console.error(`${name}: ${err && (err.stack || err)}`);
    }
    console.error(`[${name}] ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) {
    bad++;
    console.error(errs.slice(0, 10).join('\n'));
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
