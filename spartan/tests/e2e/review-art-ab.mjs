// Art-direction review (continuation): before/after screenshots of proposed visual fixes, applied at
// RUNTIME only. Module sources are rewritten in flight with page.route (no game file is edited), or
// exported objects are mutated from the page.
//   fov     world camera: shipped fixed 70 deg vertical (114 deg horizontal at 915x412) vs a
//           horizontal-FOV target of 100 deg (vertical 56.4 deg at 915x412); measures the enemy's
//           on-screen height and how many ally spear shafts enter the frame.
//   sword   xiphos idle: shipped blade-up pose vs a lower guard with the point toward the enemy;
//           measures how much of the upper half (above the horizon) the viewmodel covers.
//   helmet  Corinthian eye-slit overlay: shipped mask vs a lighter mask (darkness only in the outer
//           ~8% of the frame); measures % of pixels darkened by > 40%.
//   skin    viewmodel hand/forearm skin colour sampled vs a realistic tanned skin range.
// Usage: node spartan/tests/e2e/review-art-ab.mjs <outDir> [WxH=915x412] [fov|sword|helmet ...]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', ...wanted] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();

// Source rewrites for the "after" variants (string -> string), keyed by a URL suffix.
const REWRITES = {
  sword: {
    'gfx/viewmodel.js': [
      [
        'swordLow: pose([0.27, -0.235, -0.42], [-0.11, 0.7, -0.7], [0.35, -0.6, 0.7]),',
        'swordLow: pose([0.29, -0.29, -0.42], [-0.26, 0.42, -0.87], [0.35, -0.6, 0.7]),',
      ],
      [
        'swordHigh: pose([0.285, -0.165, -0.41], [-0.12, 0.68, -0.72], [0.35, -0.6, 0.7]),',
        'swordHigh: pose([0.30, -0.22, -0.41], [-0.27, 0.45, -0.85], [0.35, -0.6, 0.7]),',
      ],
    ],
  },
  helmet: {
    'gfx/viewmodel/overlay.js': [
      // Brow lower edge pushed up from 0.99 to 1.08 (half-heights), sides from 0.9..1.05 to 0.95..1.08,
      // cheek-pieces 0.1 lower, overall opacity 0.94 -> 0.85.
      ['float brow = 0.99 - 0.07', 'float brow = 1.08 - 0.07'],
      ['float sides = smoothstep(0.9, 1.05, side', 'float sides = smoothstep(0.95, 1.08, side'],
      ['float cheekEdge = -1.15 + 0.52', 'float cheekEdge = -1.25 + 0.45'],
      ['mask = max(mask, nasal) * 0.94;', 'mask = max(mask, nasal) * 0.85;'],
    ],
  },
};

async function newGame(rewrites) {
  const page = await l.newPage({ width, height });
  if (rewrites) {
    for (const [suffix, pairs] of Object.entries(rewrites)) {
      await page.route(`**/js/${suffix}`, async (route) => {
        const resp = await route.fetch();
        let body = await resp.text();
        for (const [a, b] of pairs) {
          if (!body.includes(a)) throw new Error(`rewrite target not found in ${suffix}: ${a}`);
          body = body.replace(a, b);
        }
        await route.fulfill({ response: resp, body });
      });
    }
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  return page;
}

const grabStats = (page) =>
  page.evaluate(() => {
    const c = document.getElementById('scene');
    const t = document.createElement('canvas');
    t.width = c.width;
    t.height = c.height;
    const g2 = t.getContext('2d');
    g2.drawImage(c, 0, 0);
    return { w: t.width, h: t.height, data: Array.from(g2.getImageData(0, 0, t.width, t.height).data) };
  });

async function shot(page, name, extra = {}) {
  await page.waitForTimeout(700);
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file, timeout: 300000 });
  console.log(`${file} ${JSON.stringify(extra)}`);
}

const sandbox = (page, opts = {}) =>
  page.evaluate((o) => {
    const g = window.__game;
    g.startBattle(o.day || 1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay(o.tod || 'morning');
    if (o.allies) g.ctx.ai.ensureAllies();
    g.ctx.hud.reset();
    if (o.fov) {
      g.ctx.camera.fov = o.fov;
      g.ctx.camera.updateProjectionMatrix();
    }
  }, opts);

// On-screen extent (CSS px) of an actor: project head socket and feet.
const measure = (page) =>
  page.evaluate(() => {
    const g = window.__game;
    const c = g.ctx;
    const T = c.THREE;
    const v = new T.Vector3();
    const toPx = (p) => {
      v.copy(p).project(c.camera);
      return { x: Math.round(((v.x + 1) / 2) * innerWidth), y: Math.round(((1 - v.y) / 2) * innerHeight), in: Math.abs(v.x) <= 1 && Math.abs(v.y) <= 1 && v.z < 1 };
    };
    const enemies = c.actors
      .filter((a) => a.role !== 'ally')
      .map((a) => {
        const head = new T.Vector3();
        a.visual.sockets.head.getWorldPosition(head);
        head.y += 0.12;
        const feet = new T.Vector3(a.pos.x, a.pos.y ?? 0, a.pos.z);
        const ph = toPx(head);
        const pf = toPx(feet);
        const d = Math.hypot(a.pos.x - c.camera.position.x, a.pos.z - c.camera.position.z);
        return { kind: a.kind, dist: +d.toFixed(2), heightPx: pf.y - ph.y, headY: ph.y, feetY: pf.y, x: ph.x };
      });
    // Ally spears: count ally dory heads/butts that project inside the frame while the ally body (chest) is outside.
    let spearsInFrameBodyOut = 0;
    let alliesInFrame = 0;
    for (const a of c.actors.filter((x) => x.role === 'ally')) {
      const chest = new T.Vector3(a.pos.x, 1.3, a.pos.z);
      const pc = toPx(chest);
      if (pc.in) alliesInFrame++;
      a.visual.root.updateMatrixWorld(true);
      const hand = new T.Vector3();
      a.visual.sockets.rightHand.getWorldPosition(hand);
      const q = new T.Quaternion();
      a.visual.sockets.rightHand.getWorldQuaternion(q);
      // sample along the hand's local axes +-1.4 m; any sample in frame counts
      let inFrame = false;
      for (const ax of [new T.Vector3(0, 0, 1), new T.Vector3(0, 1, 0), new T.Vector3(1, 0, 0)]) {
        ax.applyQuaternion(q);
        for (const s of [-1.4, -0.9, 0.9, 1.4]) {
          const p = hand.clone().addScaledVector(ax, s);
          if (toPx(p).in) inFrame = true;
        }
      }
      if (!pc.in && inFrame) spearsInFrameBodyOut++;
    }
    return { fov: c.camera.fov, aspect: +c.camera.aspect.toFixed(3), hfov: +((2 * Math.atan(Math.tan((c.camera.fov * Math.PI) / 360) * c.camera.aspect) * 180) / Math.PI).toFixed(1), enemies, alliesInFrame, spearsInFrameBodyOut };
  });

const runs = {
  async fov() {
    const page = await newGame();
    const aspect = width / height;
    const vf100 = +((2 * Math.atan(Math.tan((100 * Math.PI) / 360) / aspect) * 180) / Math.PI).toFixed(1);
    for (const [label, fov] of [['shipped70', 70], [`hfov100-v${vf100}`, vf100]]) {
      await sandbox(page, { allies: true, fov });
      await page.evaluate(() => {
        const g = window.__game;
        g.spawn('levy', 0.2, -1.7, { variant: 'kissian' });
        g.spawn('immortal', -1.4, -3.4);
        g.step(45);
      });
      await shot(page, `fov-duel-${label}`, await measure(page));
      await sandbox(page, { allies: true, fov });
      await page.evaluate(() => {
        const g = window.__game;
        g.spawn('levy', 1.5, -12, { variant: 'mede' });
        g.spawn('immortal', -1.5, -16);
        g.spawn('sparabara', 3.0, -20);
        g.step(30);
      });
      await shot(page, `fov-charge-${label}`, await measure(page));
    }
    await page.context().close();
  },
  async sword() {
    for (const variant of ['before', 'after']) {
      const page = await newGame(variant === 'after' ? REWRITES.sword : null);
      await sandbox(page, { allies: true });
      await page.evaluate(() => {
        const g = window.__game;
        g.spawn('levy', 0.9, -2.2, { variant: 'kissian' });
        g.spawn('immortal', 1.8, -7);
        g.step(60);
      });
      // Coverage of the sword above the horizon: render with/without the rig and diff.
      const full = await grabStats(page);
      await page.evaluate(() => {
        const g = window.__game;
        g.ctx.vm.scene.getObjectByName('vmRig').visible = false;
        g.render();
      });
      const bare = await grabStats(page);
      await page.evaluate(() => {
        const g = window.__game;
        g.ctx.vm.scene.getObjectByName('vmRig').visible = true;
        g.render();
      });
      let upper = 0;
      let all = 0;
      const W = full.w;
      const H = full.h;
      for (let i = 0, p = 0; i < full.data.length; i += 4, p++) {
        const d = Math.max(Math.abs(full.data[i] - bare.data[i]), Math.abs(full.data[i + 1] - bare.data[i + 1]), Math.abs(full.data[i + 2] - bare.data[i + 2]));
        if (d > 24) {
          all++;
          if (((p / W) | 0) < H * 0.5) upper++;
        }
      }
      await shot(page, `sword-${variant}`, { vmCoverPct: +((100 * all) / (W * H)).toFixed(1), vmAboveHorizonPct: +((100 * upper) / (W * H)).toFixed(2) });
      // Also a slash right and a thrust in the after variant, so the pose still reads in motion.
      await page.evaluate(() => {
        const g = window.__game;
        g.action({ type: 'slash', dir: 0, power: 0.9 });
        g.step(6);
      });
      await shot(page, `sword-${variant}-slash`);
      await page.context().close();
    }
  },
  async helmet() {
    for (const variant of ['before', 'after']) {
      const page = await newGame(variant === 'after' ? REWRITES.helmet : null);
      await sandbox(page, { allies: true });
      await page.evaluate(() => {
        const g = window.__game;
        g.spawn('levy', -2.4, -3.0, { variant: 'mede' });
        g.spawn('immortal', 2.6, -3.2);
        g.spawn('levy', 0.3, -6, { variant: 'kissian' });
        g.step(40);
      });
      const full = await grabStats(page);
      await page.evaluate(() => {
        const g = window.__game;
        g.ctx.vm.scene.getObjectByName('vmOverlay').visible = false;
        g.render();
      });
      const bare = await grabStats(page);
      await page.evaluate(() => {
        const g = window.__game;
        g.ctx.vm.scene.getObjectByName('vmOverlay').visible = true;
        g.render();
      });
      const lum = (d, i) => 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
      let dark40 = 0;
      let dark80 = 0;
      let n = 0;
      for (let i = 0; i < full.data.length; i += 4) {
        const lb = lum(bare.data, i);
        if (lb < 8) continue;
        n++;
        const r = lum(full.data, i) / lb;
        if (r < 0.6) dark40++;
        if (r < 0.2) dark80++;
      }
      await shot(page, `helmet-${variant}`, { darkenedOver40pct: +((100 * dark40) / n).toFixed(1), darkenedOver80pct: +((100 * dark80) / n).toFixed(1) });
      await page.context().close();
    }
  },
};

let bad = 0;
try {
  for (const name of wanted.length ? wanted : Object.keys(runs)) {
    const t0 = Date.now();
    try {
      await runs[name]();
    } catch (err) {
      bad++;
      console.error(`${name}: ${err && (err.stack || err)}`);
    }
    console.error(`[${name}] ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
