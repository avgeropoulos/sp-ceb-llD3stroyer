// Art/readability review: what the camera frames on a landscape phone.
// 1) Projects every ally (chest and head) and the spear tips of the two nearest allies into the view:
//    the bodies are off-screen while their spear shafts cross into the frame edges.
// 2) Measures the on-screen height of an enemy at several distances with the shipped vertical FOV
//    (70 deg) and a phone-friendly horizontal FOV (95 deg), and screenshots both.
// Usage: node spartan/tests/e2e/review-art-fov.mjs <outDir> [WxH=915x412]
// Runtime-only: it sets camera.fov from the page; no game source is changed.
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });
  const setup = () =>
    page.evaluate(() => {
      const g = window.__game;
      const c = g.ctx;
      g.startBattle(1);
      c.waves.stop();
      c.ai.clear();
      c.combat.reset();
      c.world.setTimeOfDay('morning');
      c.ai.ensureAllies();
      c.hud.reset();
      g.spawn('immortal', 0.3, -6);
      g.spawn('levy', -1.6, -14, { variant: 'mede' });
      g.spawn('sparabara', 2.0, -25);
      g.step(45);
    });
  await setup();
  const facts = await page.evaluate(() => {
    const g = window.__game;
    const c = g.ctx;
    const T = c.THREE;
    const cam = c.camera;
    const v = new T.Vector3();
    const proj = (x, y, z) => {
      v.set(x, y, z).project(cam);
      return { ndcX: +v.x.toFixed(2), ndcY: +v.y.toFixed(2), onScreen: Math.abs(v.x) <= 1 && Math.abs(v.y) <= 1 && v.z < 1 };
    };
    const allies = c.actors
      .filter((a) => a.role === 'ally')
      .map((a) => {
        const head = new T.Vector3();
        a.visual.sockets.head.getWorldPosition(head);
        const hand = new T.Vector3();
        a.visual.sockets.rightHand.getWorldPosition(hand);
        // Find the dory attached to the hand (longest child mesh) and its tip.
        let tip = null;
        a.visual.root.updateMatrixWorld(true);
        a.visual.root.traverse((o) => {
          if (o.isMesh && !o.isSkinnedMesh && /dory|spear/i.test(o.name || '')) {
            const b = new T.Box3().setFromObject(o);
            tip = b;
          }
        });
        return {
          x: +a.pos.x.toFixed(2),
          z: +a.pos.z.toFixed(2),
          chest: proj(a.pos.x, 1.3, a.pos.z),
          head: proj(head.x, head.y, head.z),
          hand: proj(hand.x, hand.y, hand.z),
        };
      });
    // Height on screen of a standing man (1.75 m) at distance d, for a vertical FOV.
    const px = (fovDeg, d) => Math.round((1.75 / d) * (innerHeight / 2) / Math.tan((fovDeg * Math.PI) / 360));
    const aspect = innerWidth / innerHeight;
    const hfov = (vf) => +((2 * Math.atan(Math.tan((vf * Math.PI) / 360) * aspect) * 180) / Math.PI).toFixed(1);
    const vfFor95 = +((2 * Math.atan(Math.tan((95 * Math.PI) / 360) / aspect) * 180) / Math.PI).toFixed(1);
    return {
      viewport: `${innerWidth}x${innerHeight}`,
      fov: cam.fov,
      horizontalFov: hfov(cam.fov),
      vfovFor95hfov: vfFor95,
      enemyPx: {
        shipped: { d1_5: px(cam.fov, 1.5), d6: px(cam.fov, 6), d14: px(cam.fov, 14), d25: px(cam.fov, 25), d40: px(cam.fov, 40) },
        hfov95: { d1_5: px(vfFor95, 1.5), d6: px(vfFor95, 6), d14: px(vfFor95, 14), d25: px(vfFor95, 25), d40: px(vfFor95, 40) },
      },
      allies,
    };
  });
  console.log(JSON.stringify(facts, null, 1));
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(outDir, 'fov-70-shipped.png') });
  await page.evaluate((vf) => {
    const g = window.__game;
    g.ctx.camera.fov = vf;
    g.ctx.camera.updateProjectionMatrix();
    g.step(1);
  }, facts.vfovFor95hfov);
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(outDir, `fov-${facts.vfovFor95hfov}-hfov95.png`) });
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  if (errs.length) console.error(errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
