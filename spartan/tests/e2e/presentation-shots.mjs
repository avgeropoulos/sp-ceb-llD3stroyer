// Presentation fixes (art review C): screenshots and measurements of the first-person viewmodel and
// HUD in the real game, for before/after comparison.
//   title   title screen; prints the epigraph cite / Greek kicker rects (for contrast measurement)
//   death   kills the player: mid-fall, after the fall, and a restart (viewmodel and HUD visibility)
//   arc     hurt-direction arc over the sky at several ages (stroke width, opacity)
//   helmet  Corinthian overlay: % of pixels darkened by > 40% and > 80%
//   spear   dory idle, thrust, throw wind-up, release and draw; projected fist NDC
//   skin    forearm close-up at the spear thrust and a sword slash; forearm high-pass std
// Usage: node spartan/tests/e2e/presentation-shots.mjs <outDir> [WxH=915x412] [scene ...]
//        MOBILE=1 emulates touch (the touch controls show).
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', ...wanted] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const tag = `${width}x${height}`;

async function newGame(tries = 2) {
  const page = await l.newPage({ width, height, mobile: process.env.MOBILE === '1' });
  try {
    await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  } catch (err) {
    // A loaded shared machine can stall a boot; retry once on a fresh page.
    await page.context().close();
    if (tries > 1) return newGame(tries - 1);
    throw err;
  }
  return page;
}
const sandbox = (page, tod = 'morning') =>
  page.evaluate((tod) => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay(tod);
    g.ctx.hud.reset();
  }, tod);
async function shot(page, name, extra = {}, wait = 700) {
  await page.waitForTimeout(wait);
  const file = path.join(outDir, `${name}-${tag}.png`);
  await page.screenshot({ path: file, timeout: 300000 });
  console.log(`${file} ${JSON.stringify(extra)}`);
}
const grab = (page) =>
  page.evaluate(() => {
    const c = document.getElementById('scene');
    const t = document.createElement('canvas');
    t.width = c.width;
    t.height = c.height;
    const g2 = t.getContext('2d');
    g2.drawImage(c, 0, 0);
    return { w: t.width, h: t.height, data: Array.from(g2.getImageData(0, 0, t.width, t.height).data) };
  });
// The right hand (the weapon mesh rides the grip pose) projected with the vm camera.
const handNdc = (page) =>
  page.evaluate(() => {
    const c = window.__game.ctx;
    const o = c.vm.scene.getObjectByName('vmSpear').visible ? c.vm.scene.getObjectByName('vmSpear') : c.vm.scene.getObjectByName('vmSword');
    const v = new c.THREE.Vector3();
    o.getWorldPosition(v);
    v.project(c.vm.camera);
    return { ndcX: +v.x.toFixed(2), ndcY: +v.y.toFixed(2), onScreen: Math.abs(v.x) <= 1 && Math.abs(v.y) <= 1 };
  });

// Pixels the first-person rig (and the spear alone) covers: whole frame, the HUD's top-right block
// (score, kills) and the shaft's run at column W-40 (the "plank from the bezel" measure).
async function cover(page) {
  const full = await grab(page);
  const hide = (name) =>
    page.evaluate((name) => {
      const g = window.__game;
      const o = g.ctx.vm.scene.getObjectByName(name);
      const was = o.visible;
      o.visible = false;
      g.render();
      o.visible = was;
      return was;
    }, name);
  await hide('vmSpear');
  const noSpear = await grab(page);
  await hide('vmRig');
  const noRig = await grab(page);
  await page.evaluate(() => window.__game.render());
  const hud = await page.evaluate(() => {
    const b = document.querySelector('.hud-top-r').getBoundingClientRect();
    const c = document.getElementById('scene');
    const k = c.width / c.clientWidth;
    return { x0: Math.floor(b.left * k), y0: Math.floor(b.top * k), x1: Math.ceil(b.right * k), y1: Math.ceil(b.bottom * k) };
  });
  const W = full.w;
  const X = W - 40;
  let rig = 0;
  let spear = 0;
  let rigHud = 0;
  let edge = 0;
  let hudN = 0;
  for (let i = 0, p = 0; i < full.data.length; i += 4, p++) {
    const d = (a) => Math.max(Math.abs(full.data[i] - a.data[i]), Math.abs(full.data[i + 1] - a.data[i + 1]), Math.abs(full.data[i + 2] - a.data[i + 2])) > 24;
    const x = p % W;
    const y = (p - x) / W;
    const inHud = x >= hud.x0 && x < hud.x1 && y >= hud.y0 && y < hud.y1;
    if (inHud) hudN++;
    if (d(noRig)) {
      rig++;
      if (inHud) rigHud++;
    }
    if (d(noSpear)) {
      spear++;
      if (x === X) edge++;
    }
  }
  const n = full.data.length / 4;
  return {
    rigPct: +((100 * rig) / n).toFixed(2), spearPct: +((100 * spear) / n).toFixed(2),
    rigInHudTopRightPct: hudN ? +((100 * rigHud) / hudN).toFixed(1) : 0, shaftPxAtRightEdge: edge,
  };
}

const runs = {
  async title(page) {
    await page.evaluate(() => {
      window.__game.ctx.setMode('title');
      window.__game.step(60);
    });
    const rects = await page.evaluate(() => {
      const r = (s) => {
        const e = document.querySelector(s);
        if (!e) return null;
        const b = e.getBoundingClientRect();
        const cs = getComputedStyle(e);
        return { x0: Math.floor(b.left), y0: Math.floor(b.top), x1: Math.ceil(b.right), y1: Math.ceil(b.bottom), color: cs.color, size: cs.fontSize };
      };
      return { cite: r('.mn-epigraph cite'), greek: r('.mn-greek'), quote: r('.mn-epigraph p') };
    });
    await shot(page, 'title', rects, 1200);
  },
  async death(page) {
    await page.evaluate(() => {
      const g = window.__game;
      g.startBattle(1);
      g.step(60);
      g.ctx.player.damage(1000, { from: null, kind: 'melee' });
    });
    const facts = () =>
      page.evaluate(() => {
        const g = window.__game;
        const hud = document.getElementById('hud');
        const banner = document.querySelector('.hud-banner');
        const d = g.ctx.viewmodel.debug;
        return {
          mode: g.ctx.mode,
          rig: g.ctx.vm.scene.getObjectByName('vmRig').visible,
          arm: d.arm && d.arm.kind,
          hudCls: hud.className,
          hudOpacity: getComputedStyle(hud).opacity,
          banner: banner.classList.contains('is-shown'),
        };
      });
    let t = 0;
    for (const at of [0.15, 0.3, 0.6, 1.5]) {
      await page.evaluate((n) => window.__game.step(n, 1 / 60), Math.round((at - t) * 60));
      t = at;
      await shot(page, `death-${at}s`, await facts(), 900);
    }
    // A new battle brings the viewmodel and the HUD back.
    await page.evaluate(() => {
      const g = window.__game;
      g.step(Math.round(4 * 30), 1 / 30);
      g.startBattle(1);
      g.step(30);
    });
    await shot(page, 'death-restart', await facts(), 900);
  },
  async arc(page) {
    await sandbox(page);
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.player.pitch = 0.12;
      g.step(30);
      g.ctx.player.damage(14, { from: null, kind: 'melee', relYaw: 0.35 });
      g.step(1);
    });
    let t = 0;
    for (const at of [0.1, 0.6, 1.0, 1.25]) {
      await page.evaluate((n) => window.__game.step(n, 1 / 60), Math.max(1, Math.round((at - t) * 60)));
      t = at;
      const st = await page.evaluate(() => {
        const a = [...document.querySelectorAll('.hud-arc')].find((e) => +e.style.opacity > 0);
        if (!a) return null;
        const ps = a.querySelectorAll('path');
        return {
          opacity: a.style.opacity,
          widths: [...ps].map((p) => p.getAttribute('stroke-width')),
          pathOpacity: [...ps].map((p) => p.getAttribute('opacity')),
        };
      });
      await shot(page, `arc-${at}s`, st, 400);
    }
    // Reference without the arc (the hurt vignette has faded too), for colour measurements.
    await page.evaluate(() => window.__game.step(150, 1 / 60));
    await shot(page, 'arc-none', {}, 400);
  },
  async helmet(page) {
    await sandbox(page);
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.settings.helmetView = true;
      g.ctx.ai.ensureAllies();
      g.spawn('levy', -2.4, -3.0, { variant: 'mede' });
      g.spawn('immortal', 2.6, -3.2);
      g.spawn('levy', 0.3, -6, { variant: 'kissian' });
      g.step(40);
    });
    const full = await grab(page);
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.vm.scene.getObjectByName('vmOverlay').visible = false;
      g.render();
    });
    const bare = await grab(page);
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.vm.scene.getObjectByName('vmOverlay').visible = true;
      g.render();
    });
    const lum = (d, i) => 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
    let d40 = 0;
    let d80 = 0;
    let n = 0;
    for (let i = 0; i < full.data.length; i += 4) {
      const lb = lum(bare.data, i);
      if (lb < 8) continue;
      n++;
      const r = lum(full.data, i) / lb;
      if (r < 0.6) d40++;
      if (r < 0.2) d80++;
    }
    await shot(page, 'helmet', { darkenedOver40pct: +((100 * d40) / n).toFixed(1), darkenedOver80pct: +((100 * d80) / n).toFixed(1) });
  },
  async spear(page) {
    await sandbox(page);
    await page.evaluate(() => {
      const g = window.__game;
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(60);
      g.spawn('levy', 0.0, -2.4, { variant: 'kissian' });
      g.step(30);
    });
    await shot(page, 'spear-idle', Object.assign(await handNdc(page), await cover(page)));
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.input.shieldHeld = true;
      g.step(30);
    });
    await shot(page, 'spear-idle-raised', Object.assign(await handNdc(page), await cover(page)));
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.input.shieldHeld = false;
      g.step(30);
      g.action({ type: 'thrust', power: 0.9 });
      g.step(5);
    });
    await shot(page, 'spear-thrust-a', await handNdc(page));
    await page.evaluate(() => window.__game.step(3));
    await shot(page, 'spear-thrust-b', Object.assign(await handNdc(page), await cover(page)));
    await page.evaluate(() => window.__game.step(30));
    // Motion wind-up held (the hand draws back over the shoulder).
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.viewmodel.play('throwWindup');
      g.step(6);
    });
    await shot(page, 'spear-windup-a', await handNdc(page));
    await page.evaluate(() => window.__game.step(14));
    await shot(page, 'spear-windup-b', await handNdc(page));
    await page.evaluate(() => {
      const g = window.__game;
      g.ctx.viewmodel.play('throwRelease', { motion: true });
      g.step(2);
    });
    await shot(page, 'spear-release', await handNdc(page));
    await page.evaluate(() => window.__game.step(8));
    await shot(page, 'spear-follow', await handNdc(page));
    // Draw: back to the spear from the sword.
    await page.evaluate(() => {
      const g = window.__game;
      g.step(60);
      g.ctx.viewmodel.reset();
      g.action({ type: 'switchWeapon', weapon: 'sword' });
      g.step(60);
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(26);
    });
    await shot(page, 'spear-draw', await handNdc(page));
  },
  async skin(page) {
    await sandbox(page);
    await page.evaluate(() => {
      const g = window.__game;
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(60);
      g.action({ type: 'thrust', power: 0.9 });
      g.step(8);
    });
    await shot(page, 'skin-spear-thrust');
    await page.evaluate(() => {
      const g = window.__game;
      g.step(40);
      g.action({ type: 'switchWeapon', weapon: 'sword' });
      g.step(60);
      g.action({ type: 'slash', dir: 0, power: 0.9 });
      g.step(5);
    });
    await shot(page, 'skin-slash-right');
  },
};

let bad = 0;
try {
  for (const name of wanted.length ? wanted : Object.keys(runs)) {
    const page = await newGame();
    try {
      await runs[name](page);
      const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
      if (errs.length) {
        bad++;
        console.error(`${name}: ${errs.slice(0, 5).join('\n')}`);
      }
    } catch (err) {
      bad++;
      console.error(`${name}: ${err && (err.stack || err)}`);
    }
    await page.context().close();
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
