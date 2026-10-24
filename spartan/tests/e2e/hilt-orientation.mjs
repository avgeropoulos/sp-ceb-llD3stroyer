// Orientation requests through the strict fake SpartanNative (docs-hilt-grip.md §A.3–A.5, §H.2 #5).
// The fake bridge declares lockOrientation(boolean, String?) as NativeBridge.kt does and records
// every call. A stored motion + hilt player boots to the title in portrait, fights locked in
// portrait, switches to Touch in the pause menu's Settings, resumes in landscape, and fights in the
// window grip locked in landscape. The host's rotation is emulated by resizing the viewport once
// the game has asked for the other family, as Android would rotate the display.
// Usage: node spartan/tests/e2e/hilt-orientation.mjs [--strict]
//   A step that waits on another package (menus.js calling ctx.applyControls() after a Controls
//   change, P8) prints PENDING until it lands; --strict makes PENDING a failure.
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage, tap } from './review-device-native.mjs';

const STRICT = process.argv.includes('--strict');
const PORTRAIT = { width: 412, height: 915 };
const LANDSCAPE = { width: 915, height: 412 };

const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });
const failures = [];
const pendings = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};
const pending = (msg) => {
  pendings.push(msg);
  if (STRICT) failures.push(msg);
  console.log(`${STRICT ? 'FAIL' : 'PENDING'} ${msg}`);
};
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** Init script: a returning motion player who chose the hilt grip (phonePage seeds the grip). */
function seedHiltPlayer() {
  try {
    const key = 'thermopylae.settings';
    const s = JSON.parse(localStorage.getItem(key) || '{}');
    if (s.control) return;
    Object.assign(s, { control: 'motion', safetyAccepted: true, hiltSafetyAccepted: true, gripChosen: true });
    localStorage.setItem(key, JSON.stringify(s));
  } catch {
    /* about:blank */
  }
  // MainActivity.onCreate already started a hilt player's launch in portrait (rotation 0).
  if (window.__nat) window.__nat.st.rot = 0;
}

try {
  const page = await phonePage(browser, { ...PORTRAIT, dpr: 1, grip: 'hilt' });
  await page.context().addInitScript(seedHiltPlayer);
  const step = (n) => page.evaluate((n) => window.__game.step(n), n);
  const errors = async () => page.errors.concat(await page.evaluate(() => window.__game.errors));
  /** The bridge calls recorded since index `from`, the mode, and the effective grip. */
  const state = (from = 0) =>
    page.evaluate((from) => {
      const g = window.__game;
      return {
        calls: window.__nat.st.orient.slice(from),
        n: window.__nat.st.orient.length,
        mode: g.ctx.mode,
        grip: g.ctx.input.grip,
        dataGrip: document.documentElement.dataset.grip,
        sensors: window.__nat.st.wanted,
      };
    }, from);
  /** The host rotates the display: the viewport and the rotation the bridge reports. */
  const rotate = async (size, rot) => {
    await page.setViewportSize(size);
    await page.evaluate((rot) => (window.__nat.st.rot = rot), rot);
    await step(3);
  };

  await page.goto(`${base}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await step(10);

  // 1. Boot and title, stored motion + hilt: unlocked portrait, never a landscape request.
  let s = await state();
  check(s.mode === 'title' && s.grip === 'hilt' && s.dataGrip === 'hilt', `boots to the title as a hilt player (${s.mode}, ${s.grip}, data-grip ${s.dataGrip})`);
  check(s.n > 0 && same(s.calls[0], [false, 'portrait']), `the first request (at boot) is (false,'portrait'): ${JSON.stringify(s.calls[0])}`);
  check(s.calls.every((c) => same(c, [false, 'portrait'])), `boot and title request only (false,'portrait'): ${JSON.stringify(s.calls)}`);

  // 2. A battle: locked portrait, sensors on.
  let n0 = s.n;
  await page.evaluate(() => window.__game.ctx.setMode('battle', { day: 1 }));
  await step(5);
  s = await state(n0);
  check(s.mode === 'battle' && s.sensors, `a hilt battle runs with the sensors on (${s.mode}, sensors ${s.sensors})`);
  check(s.calls.length > 0 && s.calls.every((c) => same(c, [true, 'portrait'])), `a battle requests (true,'portrait'): ${JSON.stringify(s.calls)}`);

  // 3. Pause: still portrait, unlocked. Then Settings → Controls → Touch while paused.
  n0 = s.n;
  await page.evaluate(() => window.__game.ctx.setMode('paused'));
  await step(5);
  s = await state(n0);
  check(s.mode === 'paused' && same(s.calls[s.calls.length - 1], [false, 'portrait']), `pause requests (false,'portrait'): ${JSON.stringify(s.calls)}`);
  await tap(page, '[data-screen="pause"] [data-act="settings"]');
  await step(3);
  await tap(page, '[data-screen="settings"] [data-tab="controls"]');
  await step(3);
  n0 = s.n;
  await page.evaluate(() => {
    const ctx = window.__game.ctx;
    const real = ctx.applyControls;
    window.__applies = 0;
    ctx.applyControls = function (...args) {
      window.__applies++;
      return real.apply(this, args);
    };
  });
  await tap(page, '[data-screen="settings"] .mn-seg[data-field="control"] button[data-v="touch"]');
  await step(3);
  s = await state(n0);
  const applies = await page.evaluate(() => window.__applies);
  const control = await page.evaluate(() => window.__game.ctx.settings.control);
  check(control === 'touch' && s.mode === 'paused', `Settings → Touch while paused (control ${control}, ${s.mode})`);
  if (applies > 0) {
    check(same(s.calls[s.calls.length - 1], [false, 'landscape']), `Touch while paused requests (false,'landscape') at once: ${JSON.stringify(s.calls)}`);
  } else {
    pending(`Touch while paused requests (false,'landscape') at once: menus.js does not call ctx.applyControls() after a Controls change yet (P8, §A.6)`);
  }
  await rotate(LANDSCAPE, 90);

  // 4. Back and Resume: touch never locks.
  n0 = (await state()).n;
  await tap(page, '[data-screen="settings"] [data-act="back"]');
  await step(3);
  await tap(page, '[data-screen="pause"] [data-act="resume"]');
  await step(5);
  s = await state(n0);
  check(s.mode === 'battle' && !s.sensors && s.grip === null, `resumes the battle on touch (${s.mode}, sensors ${s.sensors}, grip ${s.grip})`);
  check(s.calls.length > 0 && same(s.calls[s.calls.length - 1], [false, 'landscape']), `resume requests (false,'landscape'): ${JSON.stringify(s.calls)}`);
  check(!s.calls.some((c) => c[1] === 'portrait'), 'no portrait request after Touch');

  // 5. The window grip: a motion battle locks landscape; the title unlocks it.
  n0 = s.n;
  await page.evaluate(() => {
    const ctx = window.__game.ctx;
    ctx.settings.control = 'motion';
    ctx.settings.grip = 'window';
    ctx.setMode('battle', { day: 1 });
  });
  await step(5);
  s = await state(n0);
  check(s.mode === 'battle' && s.grip === 'window' && s.dataGrip === 'window' && s.sensors, `a window-grip battle (${s.mode}, ${s.grip}, data-grip ${s.dataGrip}, sensors ${s.sensors})`);
  check(s.calls.length > 0 && s.calls.every((c) => same(c, [true, 'landscape'])), `the window grip requests (true,'landscape'): ${JSON.stringify(s.calls)}`);
  n0 = s.n;
  await page.evaluate(() => window.__game.ctx.setMode('title'));
  await step(3);
  s = await state(n0);
  check(s.calls.length > 0 && s.calls.every((c) => same(c, [false, 'landscape'])), `the window grip's title requests (false,'landscape'): ${JSON.stringify(s.calls)}`);

  // 6. The bridge saw only well-typed 2-argument calls.
  const nat = await page.evaluate(() => ({ warn: window.__nat.st.warn, all: window.__nat.st.orient }));
  check(!nat.warn.some((w) => w.startsWith('ARITY')), `zero ARITY warnings (${nat.warn.filter((w) => w.startsWith('ARITY')).length})`);
  check(nat.warn.length === 0, `zero bridge warnings ${JSON.stringify(nat.warn.slice(0, 5))}`);
  check(nat.all.every((c) => c.length === 2 && typeof c[0] === 'boolean' && (c[1] === 'portrait' || c[1] === 'landscape')), `every call is (boolean, 'portrait'|'landscape') (${nat.all.length} calls)`);
  const errs = await errors();
  check(errs.length === 0, `no runtime errors (${errs.length}) ${errs.slice(0, 5).join('\n')}`);
} catch (err) {
  failures.push(String((err && err.stack) || err));
  console.error(err);
} finally {
  await browser.close();
  server.close();
}
if (pendings.length && !STRICT) console.log(`\n${pendings.length} PENDING (another package's part; --strict fails on them)`);
console.log(failures.length ? `\n${failures.length} FAILED` : '\nALL ORIENTATION CHECKS PASSED');
process.exit(failures.length ? 1 : 0);
