// Art review: is the left forearm visible inside the raised aspis? Raises the shield (real input
// path, key K), then reads the vm skeleton bones 3..5 (left upper arm, forearm, hand) and the aspis
// pose in vm-camera space: projects each into NDC and reports whether it lies on the camera side of
// the shield (the concave interior the player looks into) or behind it. Renders the frame with the
// shield hidden too, so the arm can be seen.
// Usage: node spartan/tests/e2e/review-art-leftarm.mjs <outDir> [WxH=915x412]
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
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 240000 });
  await page.evaluate(() => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay('morning');
    g.ctx.hud.reset();
    g.step(10);
  });
  await page.keyboard.down('KeyK');
  await page.evaluate(() => window.__game.step(30));
  const probe = () =>
    page.evaluate(() => {
      const c = window.__game.ctx;
      const T = c.THREE;
      const arms = c.vm.scene.getObjectByName('vmArms');
      const shield = c.vm.scene.getObjectByName('vmAspis');
      arms.updateMatrixWorld(true);
      const sp = new T.Vector3();
      shield.getWorldPosition(sp);
      const sq = new T.Quaternion();
      shield.getWorldQuaternion(sq);
      const sn = new T.Vector3(0, 0, 1).applyQuaternion(sq); // aspis local +Z (bowl axis)
      const bones = arms.skeleton.bones;
      const names = ['R upper', 'R fore', 'R hand', 'L upper', 'L fore', 'L hand'];
      const out = {};
      for (let i = 3; i < 6; i++) {
        const p = new T.Vector3();
        bones[i].getWorldPosition(p);
        const ndc = p.clone().project(c.vm.camera);
        // Signed distance from the shield centre plane along its axis, and whether the camera
        // (origin) is on the same side.
        const d = p.clone().sub(sp).dot(sn);
        const camSide = new T.Vector3().sub(sp).dot(sn);
        out[names[i]] = { vm: p.toArray().map((x) => +x.toFixed(3)), ndc: [+ndc.x.toFixed(2), +ndc.y.toFixed(2)], sideOfShield: Math.sign(d) === Math.sign(camSide) ? 'camera side (visible inside bowl)' : 'far side (hidden behind bronze)', dist: +d.toFixed(3) };
      }
      out.shield = { pos: sp.toArray().map((x) => +x.toFixed(3)), axis: sn.toArray().map((x) => +x.toFixed(2)), cameraSideDist: +new T.Vector3().sub(sp).dot(sn).toFixed(3) };
      return out;
    });
  console.log(JSON.stringify(await probe(), null, 1));
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(outDir, 'leftarm-shield-raised.png') });
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.vm.scene.getObjectByName('vmAspis').visible = false;
    g.render();
  });
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(outDir, 'leftarm-shield-hidden.png') });
  await page.keyboard.up('KeyK');
} finally {
  await l.close();
}
