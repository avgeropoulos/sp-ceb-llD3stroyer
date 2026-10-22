// Art review: character guard poses, before/after, via in-flight rewrite of gfx/characters/poses.js
// (page.route; no game file is edited).
//   levy.guard: shipped right elbow pole [-1, 0.1, -0.5] puts the upper arm straight out sideways at
//   shoulder height (a horizontal "scarecrow" arm with the spear vertical beside the head). Proposed:
//   fist by the ear and a little forward, elbow pole down-and-out.
//   hoplite.guard: shipped aimR [0.03, -0.08, 1] holds the dory level at shoulder height, so the
//   allies' shafts cross the player's frame at enemy-chest height. Proposed aimR [0.03, -0.4, 1]
//   (head ~22 deg down): the shafts run under the enemies' faces.
// Prints, for each ally, how many of its spear sample points are inside the frame and at which
// screen heights (y px), plus the shot.
// Usage: node spartan/tests/e2e/review-art-poses.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const AFTER = [
  [
    "  handR: [-0.26, 0.33, 0.06], elbR: [-1, 0.1, -0.5], aimR: [0.03, -0.07, 1],\n});\n\nconst sparabara",
    "  handR: [-0.24, 0.24, 0.12], elbR: [-0.6, -0.65, -0.35], aimR: [0.03, -0.07, 1],\n});\n\nconst sparabara",
  ],
  ['  handR: [-0.27, 0.36, 0.08], elbR: [-1, 0.1, -0.55], aimR: [0.03, -0.08, 1],', '  handR: [-0.27, 0.36, 0.08], elbR: [-1, 0.1, -0.55], aimR: [0.03, -0.4, 1],'],
];
try {
  for (const variant of ['before', 'after']) {
    const page = await l.newPage({ width, height });
    if (variant === 'after') {
      await page.route('**/js/gfx/characters/poses.js', async (route) => {
        const resp = await route.fetch();
        let body = await resp.text();
        for (const [a, b] of AFTER) {
          if (!body.includes(a)) throw new Error(`rewrite target not found: ${a.slice(0, 60)}`);
          body = body.replace(a, b);
        }
        await route.fulfill({ response: resp, body });
      });
    }
    await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
    const facts = await page.evaluate(() => {
      const g = window.__game;
      const c = g.ctx;
      g.startBattle(1);
      c.waves.stop();
      c.ai.clear();
      c.combat.reset();
      c.world.setTimeOfDay('morning');
      c.ai.ensureAllies();
      c.hud.reset();
      g.spawn('levy', -1.1, -2.2, { variant: 'kissian' });
      g.spawn('levy', 1.0, -2.5, { variant: 'mede' });
      g.spawn('immortal', 0.0, -4.8);
      g.step(60);
      const T = c.THREE;
      const out = [];
      for (const a of c.actors.filter((x) => x.role === 'ally')) {
        const hand = new T.Vector3();
        const q = new T.Quaternion();
        a.visual.sockets.rightHand.getWorldPosition(hand);
        a.visual.sockets.rightHand.getWorldQuaternion(q);
        const ys = [];
        for (const ax of [new T.Vector3(0, 0, 1), new T.Vector3(0, 1, 0), new T.Vector3(1, 0, 0)]) {
          ax.applyQuaternion(q);
          for (let s = -1.5; s <= 1.5; s += 0.25) {
            const p = hand.clone().addScaledVector(ax, s).project(c.camera);
            if (Math.abs(p.x) <= 1 && Math.abs(p.y) <= 1 && p.z < 1) ys.push(Math.round(((1 - p.y) / 2) * innerHeight));
          }
        }
        if (ys.length) out.push({ allyX: +a.pos.x.toFixed(2), samplesInFrame: ys.length, yMin: Math.min(...ys), yMax: Math.max(...ys) });
      }
      const enemies = c.actors.filter((x) => x.role !== 'ally').map((a) => {
        const h = new T.Vector3();
        a.visual.sockets.head.getWorldPosition(h);
        const p = h.project(c.camera);
        return { kind: a.kind, headY: Math.round(((1 - p.y) / 2) * innerHeight) };
      });
      return { allySpearsInFrame: out, enemyHeadY: enemies };
    });
    await page.waitForTimeout(700);
    const file = path.join(outDir, `poses-${variant}.png`);
    await page.screenshot({ path: file, timeout: 300000 });
    console.log(file, JSON.stringify(facts));
    const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
    if (errs.length) console.error(errs.slice(0, 5).join('\n'));
    await page.context().close();
  }
} finally {
  await l.close();
}
