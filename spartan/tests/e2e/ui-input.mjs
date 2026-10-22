// Input tests for ui/input.js (and the HUD READY forwarding) on the UI sandbox.
// Touch via CDP Input.dispatchTouchEvent (hasTouch page), mouse and keyboard via Playwright.
// Usage: node spartan/tests/e2e/ui-input.mjs
import { launch } from './lib.mjs';

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const near = (a, b, tol) => Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))) <= tol;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const l = await launch();
try {
  // ------------------------------------------------------------------ touch layout
  async function touchPage(query) {
    const page = await l.newPage({ width: 844, height: 390, mobile: true });
    await page.goto(`${l.base}/tests/sandbox/ui-sandbox.html?${query}&manual=1`);
    await page.waitForFunction(() => window.__ui, null, { timeout: 30000 });
    await page.evaluate(() => window.__sandbox.step(2));
    const cdp = await page.context().newCDPSession(page);
    // Warm-up stroke: the first CDP touch sequence on a fresh page can arrive slowly (JIT, first
    // paint) and read as a look drag. Its effects are cleared below.
    const warm = async (type, x, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ id: 9, x, y, radiusX: 4, radiusY: 4, force: 1 }] });
    await warm('touchStart', 560, 120);
    for (let i = 1; i <= 4; i++) await warm('touchMove', 560 + i * 35, 120);
    await warm('touchEnd');
    await page.evaluate(() => { window.__sandbox.step(2); window.__ui.log.actions.length = 0; });
    const api = {
      page,
      async touch(type, pts) {
        await cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map((p, i) => Object.assign({ id: p.id ?? i + 1, radiusX: p.r ?? 4, radiusY: p.r ?? 4, force: 1 }, p)) });
      },
      // Headless Chromium delivers CDP touch moves ~33 ms apart, so steps are ~30 px (≈1 px/ms).
      async swipe(x0, y0, x1, y1, moves = 4, gapMs = 0) {
        await api.touch('touchStart', [{ x: x0, y: y0 }]);
        for (let i = 1; i <= moves; i++) {
          const t = i / moves;
          await api.touch('touchMove', [{ x: x0 + (x1 - x0) * t, y: y0 + (y1 - y0) * t }]);
          if (gapMs) await sleep(gapMs);
        }
        await api.touch('touchEnd', []);
      },
      // Synthetic PointerEvents for partial releases (CDP touchEnd lifts every touch at once).
      ptr: (type, id, x, y, sel = '.ctl-surface') => page.evaluate(([type, id, x, y, sel]) => {
        document.querySelector(sel).dispatchEvent(new PointerEvent(type, { pointerId: id, pointerType: 'touch', clientX: x, clientY: y, width: 8, height: 8, bubbles: true, cancelable: true }));
      }, [type, id, x, y, sel]),
      clear: () => page.evaluate(() => {
        const u = window.__ui;
        u.log.actions.length = 0;
        u.log.lookYaw = u.log.lookPitch = 0;
        u.ctx.input.lookYaw = u.ctx.input.lookPitch = 0;
      }),
      actions: () => page.evaluate(() => window.__ui.log.actions.map(({ type, dir, power, src, weapon }) => ({ type, dir, power, src, weapon }))),
      step: (n = 1) => page.evaluate((k) => window.__sandbox.step(k), n),
      input: () => page.evaluate(() => Object.assign({}, window.__ui.ctx.input)),
      center: (sel) => page.evaluate((s) => {
        const r = document.querySelector(s).getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
      }, sel),
    };
    return api;
  }

  {
    const t = await touchPage('view=touch&control=touch');
    // 8-way swipes on the right half (screen-y inverted: swiping up = +π/2).
    const names = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
    for (let k = 0; k < 8; k++) {
      const a = (k * Math.PI) / 4;
      await t.clear();
      const x0 = 610, y0 = 205, len = 120;
      await t.swipe(x0, y0, x0 + Math.cos(a) * len, y0 - Math.sin(a) * len);
      await sleep(30);
      const acts = await t.actions();
      const sl = acts.filter((x) => x.type === 'slash');
      check(`swipe ${names[k]} → one slash, dir ${a.toFixed(2)}`, sl.length === 1 && near(sl[0].dir, a, 0.25) && sl[0].src === 'touch' && acts.length === 1,
        JSON.stringify(acts.map((x) => [x.type, x.dir && +x.dir.toFixed(2), x.power && +x.power.toFixed(2)])));
    }
    // A long fast swipe still gives one slash; R then L in one stroke gives two.
    await t.clear();
    await t.swipe(480, 200, 800, 200, 9);
    check('long fast swipe → exactly one slash', (await t.actions()).filter((x) => x.type === 'slash').length === 1);
    await t.clear();
    await t.touch('touchStart', [{ x: 560, y: 200 }]);
    for (let i = 1; i <= 4; i++) await t.touch('touchMove', [{ x: 560 + i * 32, y: 200 }]);
    for (let i = 1; i <= 7; i++) await t.touch('touchMove', [{ x: 688 - i * 32, y: 200 }]);
    await t.touch('touchEnd', []);
    const rl = (await t.actions()).filter((x) => x.type === 'slash');
    check('R then L in one stroke → two slashes', rl.length === 2 && near(rl[0].dir, 0, 0.3) && near(rl[1].dir, Math.PI, 0.3), JSON.stringify(rl.map((x) => +x.dir.toFixed(2))));

    // A thumb that rests, then swipes, still slashes.
    await t.clear();
    await t.touch('touchStart', [{ x: 600, y: 230 }]);
    await sleep(300);
    for (let i = 1; i <= 4; i++) await t.touch('touchMove', [{ x: 600, y: 230 - i * 30 }]);
    await t.touch('touchEnd', []);
    const rs = await t.actions();
    check('rest 300 ms, then swipe up → slash U', rs.length === 1 && rs[0].type === 'slash' && near(rs[0].dir, Math.PI / 2, 0.25), JSON.stringify(rs));

    // Tap → thrust.
    await t.clear();
    await t.touch('touchStart', [{ x: 640, y: 220 }]);
    await t.touch('touchEnd', []);
    let acts = await t.actions();
    check('tap → thrust', acts.length === 1 && acts[0].type === 'thrust' && acts[0].src === 'touch', JSON.stringify(acts));

    // Slow drag → look (drag right = turn right = negative yaw; drag up = look up), no attack.
    await t.clear();
    await t.touch('touchStart', [{ x: 560, y: 260 }]);
    for (let i = 1; i <= 12; i++) {
      await t.touch('touchMove', [{ x: 560 + i * 7, y: 260 - i * 3 }]);
      await sleep(40);
      await t.step(1);
    }
    await t.touch('touchEnd', []);
    await t.step(1);
    acts = await t.actions();
    const look = await t.page.evaluate(() => ({ yaw: window.__ui.log.lookYaw, pitch: window.__ui.log.lookPitch }));
    check('slow drag → look only', acts.length === 0 && Math.abs(look.yaw + 84 * 0.006) < 0.02 && Math.abs(look.pitch - 36 * 0.006) < 0.02,
      `yaw ${look.yaw.toFixed(3)} (want ${(-84 * 0.006).toFixed(3)}), pitch ${look.pitch.toFixed(3)}, actions ${acts.length}`);

    // Stick → moveX/moveZ (floating: the ring centres on the thumb).
    await t.touch('touchStart', [{ x: 110, y: 300 }]);
    await t.touch('touchMove', [{ x: 150, y: 260 }]);
    await t.step(1);
    let inp = await t.input();
    check('stick up-right → moveX>0, moveZ>0', inp.moveX > 0.5 && inp.moveZ > 0.5 && Math.hypot(inp.moveX, inp.moveZ) <= 1.0001, `moveX ${inp.moveX.toFixed(2)} moveZ ${inp.moveZ.toFixed(2)}`);
    await t.touch('touchMove', [{ x: 110, y: 360 }]);
    await t.step(1);
    inp = await t.input();
    check('stick back → moveZ<0', inp.moveZ < -0.8 && Math.abs(inp.moveX) < 0.1, `moveX ${inp.moveX.toFixed(2)} moveZ ${inp.moveZ.toFixed(2)}`);
    await t.touch('touchEnd', []);
    await t.step(1);
    inp = await t.input();
    check('stick release → 0', inp.moveX === 0 && inp.moveZ === 0);

    // Stick and swipe at once (two thumbs).
    await t.clear();
    await t.touch('touchStart', [{ id: 1, x: 110, y: 300 }]);
    await t.touch('touchMove', [{ id: 1, x: 110, y: 250 }]);
    await t.touch('touchStart', [{ id: 1, x: 110, y: 250 }, { id: 2, x: 600, y: 200 }]);
    for (let i = 1; i <= 4; i++) await t.touch('touchMove', [{ id: 1, x: 110, y: 250 }, { id: 2, x: 600 + i * 30, y: 200 }]);
    await t.step(1);
    inp = await t.input();
    await t.touch('touchEnd', []);
    acts = await t.actions();
    check('two thumbs: stick + swipe', inp.moveZ > 0.7 && acts.length === 1 && acts[0].type === 'slash', `moveZ ${inp.moveZ.toFixed(2)} ${JSON.stringify(acts.map((x) => x.type))}`);

    // SHIELD hold → shieldHeld + shieldPressedAt (gnow at the press).
    const sh = await t.center('.ctl-shield');
    await t.step(30);
    const gnow = await t.page.evaluate(() => window.__ui.ctx.time.gnow);
    await t.touch('touchStart', [{ x: sh.x, y: sh.y }]);
    await t.step(1);
    inp = await t.input();
    check('SHIELD hold → shieldHeld, shieldPressedAt = gnow', inp.shieldHeld === true && Math.abs(inp.shieldPressedAt - gnow) < 1e-6, `held ${inp.shieldHeld} at ${inp.shieldPressedAt} gnow ${gnow}`);
    // Swipe up from the shield → bash (shield stays up while the thumb is down).
    await t.clear();
    for (let i = 1; i <= 4; i++) await t.touch('touchMove', [{ x: sh.x, y: sh.y - i * 24 }]);
    acts = await t.actions();
    inp = await t.input();
    check('swipe up from SHIELD → bash', acts.length === 1 && acts[0].type === 'bash' && inp.shieldHeld, JSON.stringify(acts));
    await t.touch('touchEnd', []);
    await t.step(1);
    inp = await t.input();
    check('SHIELD release → shieldHeld false', inp.shieldHeld === false);

    // BASH button.
    await t.clear();
    const bash = await t.center('.ctl-bash');
    await t.touch('touchStart', [{ x: bash.x, y: bash.y }]);
    await t.touch('touchEnd', []);
    acts = await t.actions();
    check('BASH tap → bash', acts.length === 1 && acts[0].type === 'bash');

    // Weapon swap.
    await t.clear();
    const swap = await t.center('.ctl-swap');
    await t.touch('touchStart', [{ x: swap.x, y: swap.y }]);
    await t.touch('touchEnd', []);
    acts = await t.actions();
    check('swap button → switchWeapon', acts.length === 1 && acts[0].type === 'switchWeapon' && acts[0].weapon === undefined);

    // THROW: hidden with a sword; with a spear, hold 350 ms → power ≈ 0.5; drag off cancels.
    const throwHidden = await t.page.evaluate(() => getComputedStyle(document.querySelector('.ctl-throw')).display === 'none');
    await t.page.evaluate(() => Object.assign(window.__ui.ctx.player, { weapon: 'spear', hasSpearInHand: true, spearsLeft: 3 }));
    await t.step(1);
    const th = await t.center('.ctl-throw');
    check('THROW appears only with a spear in hand', throwHidden && th.w > 60, `w ${th.w}`);
    check('touch layout: swap in the bottom-right corner, THROW above it', swap.x > 844 - 70 && swap.y > 390 - 70 && Math.abs(th.x - swap.x) < 8 && th.y < swap.y - 50 && th.y > 390 / 2,
      `swap ${swap.x.toFixed(0)},${swap.y.toFixed(0)} throw ${th.x.toFixed(0)},${th.y.toFixed(0)}`);
    await t.clear();
    const held = await t.page.evaluate(() => {
      window.__thr = [];
      const b = document.querySelector('.ctl-throw');
      const rec = (e) => window.__thr.push(e.timeStamp);
      b.addEventListener('pointerdown', rec);
      b.addEventListener('pointerup', rec);
      return true;
    });
    await t.touch('touchStart', [{ x: th.x, y: th.y }]);
    await sleep(350);
    await t.touch('touchEnd', []);
    acts = await t.actions();
    const [td, tu] = await t.page.evaluate(() => window.__thr);
    const wantP = Math.min(1, (tu - td) / 700);
    check('THROW hold → throw, power = held/0.7 s', held && acts.length === 1 && acts[0].type === 'throw' && Math.abs(acts[0].power - wantP) < 1e-6 && wantP > 0.3 && wantP < 1,
      `${JSON.stringify(acts)} held ${(tu - td).toFixed(0)} ms`);
    await t.touch('touchStart', [{ x: th.x, y: th.y }]);
    await sleep(380);
    await t.step(1);
    const ring = await t.page.evaluate(() => [document.querySelector('.ctl-ring-r i').style.transform, document.querySelector('.ctl-ring-l i').style.transform]);
    await t.touch('touchEnd', []);
    check('THROW ring fills while held', /rotate\(45deg\)/.test(ring[0]) && !/-135deg/.test(ring[1]), JSON.stringify(ring));
    await t.clear();
    await t.touch('touchStart', [{ x: th.x, y: th.y }]);
    await sleep(200);
    await t.touch('touchMove', [{ x: th.x - 120, y: th.y + 60 }]);
    await t.touch('touchEnd', []);
    acts = await t.actions();
    check('THROW dragged off → cancelled', acts.length === 0, JSON.stringify(acts));
    await t.clear();
    await t.touch('touchStart', [{ x: th.x, y: th.y }]);
    await sleep(900);
    await t.touch('touchEnd', []);
    acts = await t.actions();
    check('THROW hold 0.9 s → power 1', acts.length === 1 && acts[0].power === 1);

    // Pause button → setMode('paused').
    const pz = await t.center('.ctl-pause');
    await t.touch('touchStart', [{ x: pz.x, y: pz.y }]);
    await t.touch('touchEnd', []);
    let mode = await t.page.evaluate(() => window.__ui.ctx.mode);
    check('pause button → paused', mode === 'paused', mode);
    await t.page.evaluate(() => window.__ui.ctx.resume());
    await t.step(1);

    // modeChanged zeroes the input and forgets tracked pointers.
    await t.touch('touchStart', [{ id: 1, x: 110, y: 300 }]);
    await t.touch('touchMove', [{ id: 1, x: 110, y: 240 }]);
    await t.touch('touchStart', [{ id: 1, x: 110, y: 240 }, { id: 2, x: sh.x, y: sh.y }]);
    await t.step(1);
    const before = await t.input();
    await t.page.evaluate(() => { window.__ui.ctx.input.lookYaw = 0.3; window.__ui.ctx.input.lookPitch = -0.1; window.__ui.ctx.setMode('paused'); });
    const z = await t.input();
    check('modeChanged zeroes input', before.moveZ > 0.5 && before.shieldHeld && z.moveX === 0 && z.moveZ === 0 && z.lookYaw === 0 && z.lookPitch === 0 && z.shieldHeld === false,
      `before moveZ ${before.moveZ.toFixed(2)} held ${before.shieldHeld}; after ${JSON.stringify(z)}`);
    await t.page.evaluate(() => window.__ui.ctx.resume());
    await t.touch('touchMove', [{ id: 1, x: 110, y: 200 }, { id: 2, x: sh.x, y: sh.y }]);
    await t.step(1);
    const after = await t.input();
    await t.touch('touchEnd', []);
    mode = await t.page.evaluate(() => window.__ui.ctx.mode);
    check('resumed: forgotten pointers stay forgotten', after.moveZ === 0 && after.shieldHeld === false && mode === 'battle', JSON.stringify(after));

    // READY in #hud is reachable through #controls (forwarded tap).
    await t.page.evaluate(() => { window.__ui.ctx.waves.state = 'respite'; window.__ui.emit('respite', { day: 1, seconds: 15 }); });
    await t.step(2);
    await sleep(450);
    const rd = await t.center('#hud .hud-ready');
    await t.touch('touchStart', [{ x: rd.x, y: rd.y }]);
    await t.touch('touchEnd', []);
    let calls = await t.page.evaluate(() => window.__ui.ctx.waves.readyCalls);
    check('READY before 6 s → armed, not called yet', calls === 0 && (await t.page.evaluate(() => document.querySelector('.hud-ready').classList.contains('is-armed'))));
    await t.step(370);
    calls = await t.page.evaluate(() => window.__ui.ctx.waves.readyCalls);
    check('READY armed → waves.ready() once 6 s have passed', calls === 1, `calls ${calls}`);
    await t.page.context().close();
  }

  // ------------------------------------------------------------------ left-handed
  {
    const t = await touchPage('view=touch&control=touch&left=1');
    await t.touch('touchStart', [{ x: 734, y: 300 }]);
    await t.touch('touchMove', [{ x: 694, y: 300 }]);
    await t.step(1);
    const inp = await t.input();
    await t.touch('touchEnd', []);
    check('left-handed: stick on the right (drag left → moveX<0)', inp.moveX < -0.5, `moveX ${inp.moveX.toFixed(2)}`);
    await t.clear();
    await t.swipe(230, 200, 350, 200);
    const acts = await t.actions();
    check('left-handed: swipe on the left half → slash R', acts.length === 1 && acts[0].type === 'slash' && near(acts[0].dir, 0, 0.25), JSON.stringify(acts));
    const sh = await t.center('.ctl-shield');
    const pz = await t.center('.ctl-pause');
    const sw = await t.center('.ctl-swap');
    check('left-handed: SHIELD bottom-right, pause top-right, swap bottom-left', sh.x > 600 && pz.x > 700 && pz.y < 80 && sw.x < 80 && sw.y > 300,
      `shield ${sh.x.toFixed(0)} pause ${pz.x.toFixed(0)},${pz.y.toFixed(0)} swap ${sw.x.toFixed(0)},${sw.y.toFixed(0)}`);
    await t.page.context().close();
  }

  // ------------------------------------------------------------------ motion layout
  {
    const t = await touchPage('view=motion&control=motion');
    const held = async () => (await t.input()).shieldHeld;
    const [mp, mr, ms] = [await t.center('.ctl-pause'), await t.center('.ctl-recenter'), await t.center('.ctl-swap')];
    const noTouchCtl = await t.page.evaluate(() => ['.ctl-shield', '.ctl-bash', '.ctl-stick', '.ctl-throw'].every((s) => getComputedStyle(document.querySelector(s)).display === 'none'));
    check('motion layout: pause top-left, recenter top-centre, swap top-right, no touch buttons', mp.x < 80 && mp.y < 80 && Math.abs(mr.x - 422) < 2 && mr.y < 80 && ms.x > 764 && ms.y < 80 && noTouchCtl && mp.w >= 48 && mr.w >= 48 && ms.w >= 48,
      JSON.stringify([mp, mr, ms]));
    await t.touch('touchStart', [{ x: 600, y: 220 }]);
    await t.step(1);
    check('motion: hold anywhere → shield', await held());
    await t.touch('touchEnd', []);
    await t.step(1);
    check('motion: release → no shield', !(await held()));
    await t.touch('touchStart', [{ x: 5, y: 220 }]);
    await t.step(1);
    check('motion: 12 px edge strip → no shield', !(await held()));
    await t.touch('touchEnd', []);
    await t.touch('touchStart', [{ x: 420, y: 384 }]);
    await t.step(1);
    check('motion: bottom edge strip → no shield', !(await held()));
    await t.touch('touchEnd', []);
    await t.touch('touchStart', [{ x: 600, y: 220, r: 45 }]);
    await t.step(1);
    check('motion: contact radius > 40 px → no shield', !(await held()));
    await t.touch('touchEnd', []);
    const rc = await t.center('.ctl-recenter');
    await t.clear();
    await t.touch('touchStart', [{ x: rc.x, y: rc.y }]);
    await t.step(1);
    const onBtn = await held();
    await t.touch('touchEnd', []);
    const acts = await t.actions();
    check('motion: recenter button → recenter, no shield', !onBtn && acts.length === 1 && acts[0].type === 'recenter');
    // Two touches: the shield stays up until both lift.
    await t.ptr('pointerdown', 21, 600, 220);
    await t.ptr('pointerdown', 22, 300, 200);
    await t.ptr('pointerup', 22, 300, 200);
    await t.step(1);
    const still = await held();
    await t.ptr('pointerup', 21, 600, 220);
    await t.step(1);
    check('motion: shield stays up while any touch holds', still && !(await held()));
    // Resting-thumb tip: a hold > 5 s with ≥ 3 slashes.
    await t.touch('touchStart', [{ x: 600, y: 220 }]);
    for (let i = 0; i < 3; i++) await t.page.evaluate(() => window.__ui.emit('gesture', { type: 'slash', dir: 0, power: 0.7, peak: 9, sweep: 0.5, t: 0 }));
    await t.step(240);
    const early = await t.page.evaluate(() => document.querySelector('.ctl-tip').classList.contains('is-shown'));
    await t.step(80);
    const tip = await t.page.evaluate(() => document.querySelector('.ctl-tip').classList.contains('is-shown'));
    await t.touch('touchEnd', []);
    check('motion: resting-thumb tip after 5 s + 3 slashes', !early && tip);
    await t.page.context().close();
  }

  // ------------------------------------------------------------------ keyboard and mouse
  {
    const page = await l.newPage({ width: 1280, height: 720, mobile: false });
    // CDP mouse moves under pointer lock report bogus deltas, so drags are tested unlocked.
    await page.addInitScript(() => { window.__noLock = true; Element.prototype.requestPointerLock = undefined; });
    await page.goto(`${l.base}/tests/sandbox/ui-sandbox.html?view=touch&control=touch&manual=1`);
    await page.waitForFunction(() => window.__ui, null, { timeout: 30000 });
    await page.evaluate(() => window.__sandbox.step(2));
    const clear = () => page.evaluate(() => { window.__ui.log.actions.length = 0; });
    const actions = () => page.evaluate(() => window.__ui.log.actions.map(({ type, dir, power, src }) => ({ type, dir, power, src })));
    const step = (n = 1) => page.evaluate((k) => window.__sandbox.step(k), n);
    const input = () => page.evaluate(() => Object.assign({}, window.__ui.ctx.input));

    const touchHidden = await page.evaluate(() => getComputedStyle(document.querySelector('.ctl-shield')).display === 'none');
    check('desktop without touch: touch controls hidden', touchHidden);

    await page.keyboard.down('KeyW');
    await page.keyboard.down('KeyD');
    await step(1);
    let inp = await input();
    check('W+D → forward-right, normalised', Math.abs(inp.moveX - Math.SQRT1_2) < 1e-6 && Math.abs(inp.moveZ - Math.SQRT1_2) < 1e-6, `${inp.moveX} ${inp.moveZ}`);
    await page.keyboard.up('KeyW');
    await page.keyboard.up('KeyD');
    await page.keyboard.down('KeyS');
    await page.keyboard.down('KeyA');
    await step(1);
    inp = await input();
    check('S+A → back-left', inp.moveX < -0.7 && inp.moveZ < -0.7);
    await page.keyboard.up('KeyS');
    await page.keyboard.up('KeyA');

    await clear();
    for (const k of ['ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowDown', 'Space', 'KeyF', 'KeyQ', 'KeyR', 'KeyC']) await page.keyboard.press(k);
    let acts = await actions();
    const want = [['slash', 0], ['slash', Math.PI / 2], ['slash', Math.PI], ['slash', -Math.PI / 2], ['thrust'], ['bash'], ['switchWeapon'], ['throw'], ['recenter']];
    const okKeys = acts.length === want.length && want.every(([type, dir], i) => acts[i].type === type && (dir === undefined || near(acts[i].dir, dir, 1e-6)) && acts[i].src === 'key');
    check('keys → slash →↑←↓, thrust, bash, swap, throw, recenter', okKeys && acts[7].power === 0.7, JSON.stringify(acts.map((a) => a.type)));

    for (const k of ['ShiftLeft', 'KeyK']) {
      await page.keyboard.down(k);
      await step(1);
      const h = (await input()).shieldHeld;
      await page.keyboard.up(k);
      await step(1);
      check(`${k} holds the shield`, h && !(await input()).shieldHeld);
    }

    // Mouse: quick left click → thrust; left-drag → slash in the drag direction; RMB → shield; middle → throw.
    await clear();
    await page.mouse.move(640, 360);
    await page.mouse.down({ button: 'left' });
    await page.mouse.up({ button: 'left' });
    acts = await actions();
    check('quick left click → thrust', acts.length === 1 && acts[0].type === 'thrust' && acts[0].src === 'key', JSON.stringify(acts));
    await sleep(100);
    await clear();
    await page.mouse.move(640, 360);
    await page.mouse.down({ button: 'left' });
    for (let i = 1; i <= 4; i++) await page.mouse.move(640 - i * 24, 360 - i * 24);
    await page.mouse.up({ button: 'left' });
    acts = await actions();
    check('left-drag up-left → slash UL', acts.length === 1 && acts[0].type === 'slash' && near(acts[0].dir, (3 * Math.PI) / 4, 0.25), JSON.stringify(acts));
    await page.mouse.down({ button: 'right' });
    await step(1);
    const rmb = (await input()).shieldHeld;
    await page.mouse.up({ button: 'right' });
    await step(1);
    check('right button holds the shield', rmb && !(await input()).shieldHeld);
    await clear();
    await page.mouse.down({ button: 'middle' });
    await page.mouse.up({ button: 'middle' });
    acts = await actions();
    check('middle button → throw 0.7', acts.length === 1 && acts[0].type === 'throw' && acts[0].power === 0.7, JSON.stringify(acts));

    // Pointer-locked mouse look: fake the lock and feed movement (0.0025 rad/px, right = −yaw, down = −pitch).
    const look = await page.evaluate(() => {
      const surf = document.querySelector('.ctl-surface');
      Object.defineProperty(document, 'pointerLockElement', { configurable: true, get: () => surf });
      window.__ui.ctx.input.lookYaw = window.__ui.ctx.input.lookPitch = 0;
      document.dispatchEvent(new MouseEvent('mousemove', { movementX: 100, movementY: 40 }));
      document.dispatchEvent(new MouseEvent('mousemove', { movementX: 5000, movementY: 0 }));
      const r = { yaw: window.__ui.ctx.input.lookYaw, pitch: window.__ui.ctx.input.lookPitch };
      delete document.pointerLockElement;
      window.__ui.ctx.input.lookYaw = window.__ui.ctx.input.lookPitch = 0;
      return r;
    });
    check('pointer-locked mouse look 0.0025 rad/px (spikes ignored)', Math.abs(look.yaw + 0.25) < 1e-9 && Math.abs(look.pitch + 0.1) < 1e-9, JSON.stringify(look));

    // Keyboard shield + modeChanged zeroing, then Esc pauses; P in pause resumes.
    await page.keyboard.down('KeyW');
    await page.keyboard.down('ShiftLeft');
    await step(1);
    const pre = await input();
    await page.keyboard.press('Escape');
    let mode = await page.evaluate(() => window.__ui.ctx.mode);
    const post = await input();
    check('Esc → paused', mode === 'paused', mode);
    check('modeChanged (keys) zeroes input', pre.moveZ === 1 && pre.shieldHeld && post.moveZ === 0 && !post.shieldHeld, `${JSON.stringify(pre)} → ${JSON.stringify(post)}`);
    await page.keyboard.up('KeyW');
    await page.keyboard.up('ShiftLeft');
    await page.keyboard.press('KeyP');
    mode = await page.evaluate(() => window.__ui.ctx.mode);
    check('P in the pause menu resumes (not re-paused)', mode === 'battle', mode);
    await page.keyboard.press('KeyP');
    mode = await page.evaluate(() => window.__ui.ctx.mode);
    check('P in battle pauses', mode === 'paused', mode);
    const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
    check('no page errors', errs.length === 0, errs.join(' | '));
    await page.context().close();
  }
} finally {
  await l.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
if (failed.length) process.exitCode = 1;
