// Screenshot the real game after a scripted setup.
// Usage: node spartan/tests/e2e/gameshot.mjs <out.png> "<setup JS using g = window.__game>" [frames=30] [WxH=960x540] [query]
// Example: node spartan/tests/e2e/gameshot.mjs /tmp/a.png "g.startBattle(1); g.spawn('levy', 0, -3)" 20
import { launch } from './lib.mjs';

const [, , out, setup = '', framesArg = '30', size = '960x540', query = ''] = process.argv;
if (!out) {
  console.error('usage: gameshot.mjs <out.png> "<setup JS>" [frames] [WxH] [query]');
  process.exit(2);
}
const [width, height] = size.split('x').map(Number);
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium${query ? `&${query}` : ''}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const result = await page.evaluate(
    ([code, frames]) => {
      const g = window.__game;
      const r = new Function('g', code)(g);
      g.step(frames);
      return { r: r === undefined ? null : r, stats: g.stats(), mode: g.ctx.mode };
    },
    [setup, Number(framesArg)],
  );
  await page.screenshot({ path: out, timeout: 300000 });
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log(JSON.stringify(result));
  if (errs.length) {
    console.error(errs.slice(0, 8).join('\n'));
    process.exitCode = 1;
  }
} finally {
  await l.close();
}
