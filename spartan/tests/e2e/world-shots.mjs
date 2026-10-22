// World slice screenshots from the player's eye, one page load per viewport size.
// Usage: node spartan/tests/e2e/world-shots.mjs <outDir> [quality=high] [set=all|views|tod|phone|extra] [prefix=]
// Views: fwd, left (cliff), right (sea), back (wall), down (ground), plus every time of day
// looking forward, the volley shade, and a phone-sized frame.
import { mkdirSync } from 'node:fs';
import { launch } from './lib.mjs';

const [, , outDir, quality = 'high', set = 'all', prefix = ''] = process.argv;
if (!outDir) {
  console.error('usage: world-shots.mjs <outDir> [quality] [set] [prefix]');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

const VIEWS = {
  fwd: [0, 0], left: [90, 6], right: [-90, -4], back: [180, 2], down: [0, -62],
  horde: [0, 1, 30], cliffUp: [70, 35], seaFar: [-55, -2], springs: [25, -18],
};

async function shoot(l, size, jobs) {
  const [width, height] = size;
  const page = await l.newPage({ width, height });
  await page.goto(`${l.base}/tests/sandbox/world-view.html?manual=1&quality=${quality}`);
  await page.waitForFunction(() => window.__sandbox && window.__worldStats, null, { timeout: 180000 });
  for (const job of jobs) {
    await page.evaluate((j) => {
      const { ctx } = window.__sandbox;
      const { world, camera, THREE } = ctx;
      world.setTimeOfDay(j.tod || 'morning');
      ctx.events.emit('volley', { phase: 'end', duration: 1 });
      if (j.shade) ctx.events.emit('volley', { phase: 'loosed', duration: 1.5 });
      const x = j.x || 0;
      const z = j.z || 0;
      camera.position.set(x, world.groundHeight(x, z) + 1.66, z);
      camera.rotation.set(THREE.MathUtils.degToRad(j.pitch), THREE.MathUtils.degToRad(j.yaw), 0, 'YXZ');
      camera.fov = j.fov || 70;
      camera.updateProjectionMatrix();
      camera.updateMatrixWorld();
      // Shade fades in over ~1 s of game time; step a little anyway so water and steam move.
      window.__sandbox.step(j.shade ? 90 : 4);
    }, job);
    const file = `${outDir}/${prefix}${job.name}.png`;
    await page.screenshot({ path: file });
    console.log('saved', file);
  }
  const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors));
  if (errs.length) console.error(errs.join('\n'));
  await page.context().close();
}

const l = await launch();
try {
  const big = [];
  const phone = [];
  if (set === 'all' || set === 'views') {
    for (const v of ['fwd', 'left', 'right', 'back', 'down']) {
      const [yaw, pitch, fov] = VIEWS[v];
      big.push({ name: `view-${v}`, yaw, pitch, fov });
    }
  }
  if (set === 'all' || set === 'tod') {
    for (const tod of ['dawn', 'morning', 'noon', 'afternoon', 'dusk']) big.push({ name: `tod-${tod}`, yaw: 0, pitch: 2, tod });
    big.push({ name: 'shade-morning', yaw: 0, pitch: 2, tod: 'morning', shade: true });
  }
  if (set === 'extra') {
    for (const v of ['horde', 'cliffUp', 'seaFar', 'springs']) {
      const [yaw, pitch, fov] = VIEWS[v];
      big.push({ name: `extra-${v}`, yaw, pitch, fov });
    }
    big.push({ name: 'extra-wallGap', yaw: 180, pitch: 0, z: -2 });
    big.push({ name: 'extra-dusk-right', yaw: -60, pitch: 0, tod: 'dusk' });
  }
  if (set === 'all' || set === 'phone') {
    phone.push({ name: 'phone-fwd', yaw: 0, pitch: 0 });
    phone.push({ name: 'phone-left', yaw: 60, pitch: 4 });
  }
  if (big.length) await shoot(l, [960, 540], big);
  if (phone.length) await shoot(l, [844, 390], phone);
} finally {
  await l.close();
}
