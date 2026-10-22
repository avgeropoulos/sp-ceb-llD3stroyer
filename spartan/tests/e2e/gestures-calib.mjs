// Fixer "gestures": the web sensor calibration (DESIGN.md §5.2) survives the session.
// A browser that ships the W3C rotationRate order (alpha about z, beta about x, gamma about y)
// costs the first strikes of a fresh page; main.js stores the confirmed calibration
// (MotionFrames.exportCalibration) when the battle ends and seeds the next page with it.
// Usage: node spartan/tests/e2e/gestures-calib.mjs
import { openGame } from './review-motion-lib.mjs';

const G = await openGame({ source: 'web' });
let failed = false;
const check = (ok, msg) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${msg}`);
  if (!ok) failed = true;
};
try {
  const strikes = (R, g, ctx) => {
    const dirs = [0, Math.PI, -Math.PI / 2, Math.PI / 2, Math.PI / 4, (-3 * Math.PI) / 4];
    const opts = { rot: 90 };
    R.play([{ kind: 'idle', duration: 1.5 }], opts, { source: 'web' });
    const out = [];
    for (const dir of dirs) {
      R.clearLog();
      R.play([{ kind: 'idle', duration: 0.4 }, { kind: 'swing', dir, speed: 9 }, { kind: 'idle', duration: 0.6 }], opts, { source: 'web' });
      const gs = R.evs('gesture').map((e) => (e.type === 'slash' ? R.tok(e.dir) : e.type)).join(',');
      out.push(`${R.tok(dir)}:${gs || '-'}`);
    }
    return out.join(' ');
  };
  const want = 'R:R L:L D:D U:U UR:UR DL:DL';

  // session 1: a fresh page learns the order on its first strikes, then quits to the title
  const first = await G.ev(
    (R, g, ctx, src) => {
      window.__webRot = 90;
      R.w3cOrder = true;
      R.arena();
      // eslint-disable-next-line no-new-func
      const got = new Function('R', 'g', 'ctx', `return (${src})(R, g, ctx);`)(R, g, ctx);
      const live = ctx.motionFrames.exportCalibration();
      ctx.setMode('title'); // main.js saves a confirmed calibration when a battle ends
      return { got, live, stored: localStorage.getItem('thermopylae.calib') };
    },
    strikes.toString(),
  );
  console.log(`fresh page:  ${first.got}`);
  console.log(`calibration: ${JSON.stringify(first.live)}; stored ${first.stored}`);
  check(first.got !== want, 'a fresh page gets its first strikes wrong (the bug this guards against)');
  const stored = first.stored && JSON.parse(first.stored);
  check(!!stored && stored.axisMap === 'zxy' && stored.confirmed === true, 'the confirmed W3C order is stored');

  // the next page boots with it: reload in the same browser profile
  await G.page.reload();
  await G.page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  const boot = await G.page.evaluate(() => window.__game.ctx.motionFrames.exportCalibration());
  console.log(`after reload: ${JSON.stringify(boot)}`);
  check(boot.axisMap === 'zxy' && boot.confirmed === false, 'the next page starts from the stored order (as a seed, re-checked)');
  const bootErrs = await G.page.evaluate(() => window.__game.errors);
  check(bootErrs.length === 0, `no boot errors ${bootErrs.join(' ')}`);
} finally {
  await G.close();
}

// and a page seeded that way gets every strike right from the first (fresh profile, same storage value)
const H = await openGame({ source: 'web' });
try {
  const seeded = await H.ev((R, g, ctx) => {
    window.__webRot = 90;
    R.w3cOrder = true;
    R.arena();
    ctx.motionFrames.importCalibration({ axisMap: 'zxy', gravitySign: 1, rotFix: 0, confirmed: true });
    const dirs = [0, Math.PI, -Math.PI / 2, Math.PI / 2, Math.PI / 4, (-3 * Math.PI) / 4];
    const opts = { rot: 90 };
    R.play([{ kind: 'idle', duration: 1.5 }], opts, { source: 'web' });
    const out = [];
    for (const dir of dirs) {
      R.clearLog();
      R.play([{ kind: 'idle', duration: 0.4 }, { kind: 'swing', dir, speed: 9 }, { kind: 'idle', duration: 0.6 }], opts, { source: 'web' });
      const gs = R.evs('gesture').map((e) => (e.type === 'slash' ? R.tok(e.dir) : e.type)).join(',');
      out.push(`${R.tok(dir)}:${gs || '-'}`);
    }
    return out.join(' ');
  });
  console.log(`seeded page: ${seeded}`);
  check(seeded === 'R:R L:L D:D U:U UR:UR DL:DL', 'every strike right from the first');
} finally {
  await H.close();
}
process.exitCode = failed ? 1 : 0;
