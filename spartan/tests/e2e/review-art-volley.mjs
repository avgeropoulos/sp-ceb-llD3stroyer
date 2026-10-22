// Art/readability review: can the player SEE a volley? DESIGN §1: "archers whose volleys hide the sun".
// Fires a 300-arrow volley in a clean sandbox, then at several moments after 'loosed' projects every
// flying arrow instance (combat.js volleyMesh) into the 915x412 view and reports how many are on
// screen and how long/wide they are in pixels; also diffs the frame with the volleyMesh hidden to
// count the pixels the volley actually changes. Screenshots each moment.
// Usage: node spartan/tests/e2e/review-art-volley.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 240000 });
  await ev(() => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay('morning');
    g.ctx.ai.ensureAllies();
    g.ctx.hud.reset();
    g.ctx.__vol = [];
    g.ctx.events.on('volley', (e) => g.ctx.__vol.push([e.phase, g.ctx.time.gnow]));
    g.ctx.combat.volley({ count: 300, duration: 1.0, warn: 2.5 });
    // Look up a little, as a player watching the sky would (pitch +0.25 rad = 14 deg).
    g.ctx.player.pitch = 0.25;
  });
  // Step to 'loosed'.
  for (let i = 0; i < 80; i++) {
    const loosed = await ev(() => window.__game.ctx.__vol.some((p) => p[0] === 'loosed'));
    if (loosed) break;
    await ev(() => window.__game.step(5));
  }
  const probe = () =>
    ev(() => {
      const g = window.__game;
      const c = g.ctx;
      const T = c.THREE;
      const mesh = c.scene.getObjectByName('volleyArrows');
      const m = new T.Matrix4();
      const p = new T.Vector3();
      const a = new T.Vector3();
      const b = new T.Vector3();
      const q = new T.Quaternion();
      const s = new T.Vector3();
      const toPx = (v) => {
        const w = v.clone().project(c.camera);
        return { x: ((w.x + 1) / 2) * innerWidth, y: ((1 - w.y) / 2) * innerHeight, in: Math.abs(w.x) <= 1 && Math.abs(w.y) <= 1 && w.z < 1 };
      };
      let flying = 0;
      let onScreen = 0;
      const lens = [];
      const widths = [];
      const dists = [];
      for (let i = 200; i < mesh.count; i++) {
        mesh.getMatrixAt(i, m);
        m.decompose(p, q, s);
        if (s.x === 0) continue;
        flying++;
        a.set(0, 0, 0.37).applyQuaternion(q).add(p);
        b.set(0, 0, -0.36).applyQuaternion(q).add(p);
        const pa = toPx(a);
        const pb = toPx(b);
        if (!pa.in && !pb.in) continue;
        onScreen++;
        const d = p.distanceTo(c.camera.position);
        dists.push(d);
        lens.push(Math.hypot(pa.x - pb.x, pa.y - pb.y));
        // Shaft diameter 9 mm in px at that distance.
        widths.push((0.009 / d) * (innerHeight / 2) / Math.tan((c.camera.fov * Math.PI) / 360));
      }
      const med = (arr) => (arr.length ? +arr.slice().sort((x, y) => x - y)[arr.length >> 1].toFixed(2) : null);
      return { t: +c.time.gnow.toFixed(2), meshCount: mesh.count, flying, onScreen, medianLenPx: med(lens), maxLenPx: lens.length ? +Math.max(...lens).toFixed(1) : null, medianWidthPx: med(widths), medianDistM: med(dists) };
    });
  const grab = () =>
    ev(() => {
      const cv = document.getElementById('scene');
      const t = document.createElement('canvas');
      t.width = cv.width;
      t.height = cv.height;
      const g2 = t.getContext('2d');
      g2.drawImage(cv, 0, 0);
      return Array.from(g2.getImageData(0, 0, t.width, t.height).data);
    });
  for (const [label, frames] of [['loosed+0.5s', 30], ['loosed+1.0s', 30], ['loosed+1.5s', 30], ['loosed+1.9s', 24]]) {
    await ev((n) => window.__game.step(n), frames);
    const facts = await probe();
    const full = await grab();
    await ev(() => {
      const g = window.__game;
      g.ctx.scene.getObjectByName('volleyArrows').visible = false;
      g.render();
    });
    const bare = await grab();
    await ev(() => {
      const g = window.__game;
      g.ctx.scene.getObjectByName('volleyArrows').visible = true;
      g.render();
    });
    let changed = 0;
    for (let i = 0; i < full.length; i += 4) {
      const d = Math.max(Math.abs(full[i] - bare[i]), Math.abs(full[i + 1] - bare[i + 1]), Math.abs(full[i + 2] - bare[i + 2]));
      if (d > 20) changed++;
    }
    facts.pixelsChangedByVolley = changed;
    facts.pctOfScreen = +((100 * changed) / (full.length / 4)).toFixed(3);
    await page.waitForTimeout(500);
    const file = path.join(outDir, `volley-${label}.png`);
    await page.screenshot({ path: file, timeout: 300000 });
    console.log(file, JSON.stringify(facts));
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) console.error(errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
