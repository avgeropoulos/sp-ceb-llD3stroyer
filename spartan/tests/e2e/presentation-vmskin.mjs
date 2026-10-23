// Presentation fix (art review C): the first-person forearm's skin and elbow seam, in the viewmodel
// sandbox. Renders the right arm at the sword guard from outside the rig (orbit views at the elbow and
// the forearm, fixed framing, so the pose cannot change the measurement) plus the first-person spear
// thrust, and prints the forearm's high-pass luminance std (pore "orange peel": lower is smoother).
// Usage: node spartan/tests/e2e/presentation-vmskin.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
let bad = 0;
try {
  const page = await l.newPage({ width, height });
  await page.goto(`${l.base}/tests/sandbox/viewmodel-sandbox.html?manual=1&enemy=0`);
  await page.waitForFunction(() => window.__vm, null, { timeout: 180000 });
  const views = ['elbow', 'forearm', 'elbowBent', 'elbowBentBack'];
  for (const view of views) {
    const res = await page.evaluate((view) => {
      const V = window.__vm;
      const { THREE } = V.ctx;
      V.shot({ setup: { weapon: view.startsWith('elbowBent') ? 'spear' : 'sword' } });
      // Bones: 0 R upper (at the shoulder), 1 R fore (at the elbow), 2 R hand.
      const arms = V.ctx.vm.scene.getObjectByName('vmArms');
      const e = new THREE.Vector3();
      const w = new THREE.Vector3();
      arms.skeleton.bones[1].getWorldPosition(e);
      arms.skeleton.bones[2].getWorldPosition(w);
      const at = view === 'forearm' ? e.clone().lerp(w, 0.5) : e;
      const o = { elbow: { az: 80, el: -25, dist: 0.24 }, forearm: { az: 70, el: -10, dist: 0.26 }, elbowBent: { az: 100, el: -30, dist: 0.26 }, elbowBentBack: { az: 150, el: 5, dist: 0.26 } }[view];
      V.orbit(Object.assign({ tx: at.x, ty: at.y, tz: at.z, fov: 30, bg: 0x506070 }, o));
      // Read back in the same task (no preserveDrawingBuffer needed).
      const c = document.getElementById('scene');
      const t = document.createElement('canvas');
      t.width = c.width;
      t.height = c.height;
      const g = t.getContext('2d');
      g.drawImage(c, 0, 0);
      const d = g.getImageData(0, 0, t.width, t.height).data;
      const W = t.width;
      const H = t.height;
      const L = new Float32Array(W * H);
      const skin = new Uint8Array(W * H);
      for (let i = 0; i < W * H; i++) {
        const r = d[i * 4];
        const gg = d[i * 4 + 1];
        const b = d[i * 4 + 2];
        L[i] = 0.2126 * r + 0.7152 * gg + 0.0722 * b;
        // Skin: warm (r > g > b) and not the background.
        skin[i] = r > gg + 12 && gg > b + 4 && r > 70 ? 1 : 0;
      }
      // High-pass: L minus its 5x5 box blur, over skin pixels whose whole 7x7 window is skin.
      let n = 0;
      let s2 = 0;
      for (let y = 3; y < H - 3; y++) {
        for (let x = 3; x < W - 3; x++) {
          let all = true;
          for (let k = -3; k <= 3 && all; k++) for (let m = -3; m <= 3; m++) if (!skin[(y + k) * W + x + m]) { all = false; break; }
          if (!all) continue;
          let sum = 0;
          for (let k = -2; k <= 2; k++) for (let m = -2; m <= 2; m++) sum += L[(y + k) * W + x + m];
          const hp = L[y * W + x] - sum / 25;
          s2 += hp * hp;
          n++;
        }
      }
      return { view, skinPx: n, highPassStd: n ? +Math.sqrt(s2 / n).toFixed(2) : null };
    }, view);
    const file = path.join(outDir, `vmskin-${view}-${width}x${height}.png`);
    await page.screenshot({ path: file });
    console.log(file, JSON.stringify(res));
  }
  // The gameplay view: first-person spear thrust (forearm across the lower right).
  await page.evaluate(() => {
    const V = window.__vm;
    V.shot({ setup: { weapon: 'spear' }, events: [['swing', { kind: 'spearThrust', power: 0.9, weapon: 'spear', src: 'test' }]], at: 0.13 });
  });
  const file = path.join(outDir, `vmskin-spearThrust-${width}x${height}.png`);
  await page.screenshot({ path: file });
  console.log(file);
  const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors));
  if (errs.length) {
    bad++;
    console.error(errs.slice(0, 5).join('\n'));
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
