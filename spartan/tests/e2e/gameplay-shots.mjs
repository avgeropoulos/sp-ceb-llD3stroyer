// Screenshots of the gameplay sandbox scenes (spear in flight, stuck, fallen, skewer, ground, volley).
// Usage: node spartan/tests/e2e/gameplay-shots.mjs <outDir> [scene ...]
import { launch } from './lib.mjs';
import fs from 'node:fs';
import path from 'node:path';

const [, , outDir = '.', ...wanted] = process.argv;
const scenes = wanted.length ? wanted : ['flight', 'stuck', 'fallen', 'skewer', 'ground', 'volley', 'volleyLanded'];
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
let bad = 0;
try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.base}/tests/sandbox/gameplay-sandbox.html?manual=1`);
  await page.waitForFunction(() => window.__gpReady === true, null, { timeout: 60000 });
  for (const name of scenes) {
    const msg = await page.evaluate((n) => window.__gp.scene(n), name);
    const info = await page.evaluate(() => {
      const r = window.__sandbox.ctx.renderer.info.render;
      return `${r.calls} calls, ${r.triangles} tris`;
    });
    const file = path.join(outDir, `gameplay-${name}.png`);
    await page.screenshot({ path: file });
    console.log(`${file}: ${msg} (${info})`);
  }
  const propSource = await page.evaluate(() => window.__gp.propSource);
  console.log(`props: ${propSource}`);
  const errs = page.errors.filter((e) => !(propSource === 'fallback' && /status of 404/.test(e)))
    .concat(await page.evaluate(() => window.__sandbox.errors || []));
  if (errs.length) {
    bad++;
    console.error(errs.join('\n'));
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
