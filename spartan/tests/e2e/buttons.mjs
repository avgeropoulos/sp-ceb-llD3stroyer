// The buttons control on the real game (ui/input.js 'buttons' layout): every move is an on-screen
// button, with the lock-on camera. Checks the layout fits a landscape phone without overlaps, that
// each sword-pad button cuts the way its arrow points (and lands on the dummy), STAB, SHIELD (held),
// BASH, the move pad, the target buttons, a combo, the spear's THROW at the locked foe, a mouse
// click (no pointer lock), the left-handed mirror and the Agoge's button wording.
// Touches go through CDP Input.dispatchTouchEvent at 800×360 (mobile, landscape).
// Usage: node spartan/tests/e2e/buttons.mjs [outDir]
import { launch } from './lib.mjs';
import { PAD } from '../../src/main/assets/game/js/ui/input.js';

const OUT = process.argv[2] || null;
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const W = 800;
const H = 360;

const l = await launch();
try {
  const page = await l.newPage({ width: W, height: H, mobile: true, grip: 'window' });
  await page.addInitScript(() => { window.__booted = true; });
  await page.goto(`${l.game}?manual=1&seed=5&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  await sleep(2000); // let the menus' motion probe finish (it only touches a motion setting)
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const shoot = async (name) => {
    if (OUT) await page.screenshot({ path: `${OUT}/${name}.png` });
  };
  // The title's Controls segment and Settings → Controls both offer Buttons.
  await page.locator('#menus .mn-title .mn-seg[data-field="control"] button[data-v="buttons"]').click();
  const picked = await ev(() => ({
    control: window.__game.ctx.settings.control,
    lit: !!document.querySelector('#menus .mn-title .mn-seg[data-field="control"] button[data-v="buttons"].is-on'),
    lockOn: window.__game.ctx.input.lockOn,
    settings: [...document.querySelectorAll('#menus .mn-seg[data-field="control"] button')].map((b) => b.dataset.v).join(','),
  }));
  check('tapping Buttons on the title picks the buttons control (lit, lock-on armed)', picked.control === 'buttons' && picked.lit && picked.lockOn === true, JSON.stringify(picked));
  check('the Settings Controls row offers Buttons too', /motion,touch,buttons,motion,touch,buttons/.test(picked.settings), picked.settings);
  await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    ctx.settings.control = 'buttons';
    ctx.settings.leftHanded = false;
    ctx.applyControls();
    g.startBattle(1);
    ctx.waves.stop();
    ctx.ai.clear();
    ctx.player.pos.set(0, 0, 0);
    ctx.player.yaw = 0;
    // A dummy in reach ahead, and two more 6 m out either side (forward is −z, right is +x).
    ctx.ai.spawnDummy(0, -1.7);
    ctx.ai.spawnDummy(-3.5, -5);
    ctx.ai.spawnDummy(3.5, -5);
    window.__acts = [];
    const push = ctx.actions.push;
    ctx.actions.push = (a) => {
      window.__acts.push({ type: a.type, dir: a.dir, src: a.src, power: a.power });
      return push(a);
    };
    window.__ev = [];
    for (const n of ['swing', 'hit', 'combo', 'lockOn', 'spearThrown', 'spearImpact', 'weaponSwitch']) {
      ctx.events.on(n, (e) => window.__ev.push({
        n, kind: e && e.kind, dir: e && e.dir, reason: e && e.reason, name: e && e.name, id: e && e.id,
        x: e && e.target && e.target.pos ? +e.target.pos.x.toFixed(2) : null,
      }));
    }
  });
  const step = (n = 1) => ev((k) => window.__game.step(k, 1 / 60), n);
  await step(60);
  const cdp = await page.context().newCDPSession(page);
  const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', {
    type, touchPoints: pts.map((p) => ({ id: p.id ?? 1, x: p.x, y: p.y, radiusX: 4, radiusY: 4, force: 1 })),
  });
  const box = (sel) => ev((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, l: r.left, t: r.top, r: r.right, b: r.bottom, w: r.width, h: r.height, shown: cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 };
  }, sel);
  const tap = async (sel, id = 1) => {
    const b = await box(sel);
    await touch('touchStart', [{ id, x: b.x, y: b.y }]);
    await touch('touchEnd', []);
  };
  const clearLogs = () => ev(() => { window.__acts.length = 0; window.__ev.length = 0; });
  const acts = () => ev(() => window.__acts.slice());
  const evs = (n) => ev((k) => window.__ev.filter((e) => e.n === k), n);
  const untilIdle = async () => {
    for (let i = 0; i < 120; i++) {
      if (await ev(() => window.__game.ctx.player.state === 'idle')) return true;
      await step(5);
    }
    return false;
  };
  const player = () => ev(() => {
    const p = window.__game.ctx.player;
    return { x: p.pos.x, z: p.pos.z, yaw: p.yaw, weapon: p.weapon, lock: p.lockTarget ? +p.lockTarget.pos.x.toFixed(2) : null, mode: p.lockMode, shield: window.__game.ctx.input.shieldHeld };
  });

  // ---- the layout ----
  const root = await ev(() => document.getElementById('controls').className);
  check('the controls root takes the buttons layout', /ctl-buttons/.test(root) && !/ctl-touch|ctl-motion/.test(root), root);
  const SELS = ['.ctl-pause', '.ctl-swap', '.ctl-dpad', '.ctl-pad', '.ctl-tgt-l', '.ctl-tgt-r', '.ctl-shield', '.ctl-bash'];
  const boxes = {};
  for (const s of SELS) boxes[s] = await box(s);
  const shown = SELS.filter((s) => boxes[s] && boxes[s].shown);
  check('every button group is shown', shown.length === SELS.length, `shown: ${shown.join(' ')}`);
  const inView = SELS.filter((s) => boxes[s] && boxes[s].l >= 0 && boxes[s].t >= 0 && boxes[s].r <= W && boxes[s].b <= H);
  check('every button group fits an 800×360 screen', inView.length === SELS.length, SELS.filter((s) => !inView.includes(s)).join(' '));
  const overlaps = [];
  for (let i = 0; i < SELS.length; i++) {
    for (let j = i + 1; j < SELS.length; j++) {
      const a = boxes[SELS[i]], b = boxes[SELS[j]];
      if (a && b && a.l < b.r - 1 && b.l < a.r - 1 && a.t < b.b - 1 && b.t < a.b - 1) overlaps.push(`${SELS[i]}×${SELS[j]}`);
    }
  }
  check('no two button groups overlap', overlaps.length === 0, overlaps.join(', '));
  const pad = boxes['.ctl-pad'];
  const dpad = boxes['.ctl-dpad'];
  check('right hand: the sword pad sits bottom right, the move pad bottom left', pad.l > W / 2 && dpad.r < W / 2 && pad.b > H - 30 && dpad.b > H - 30);
  const cells = await ev(() => [...document.querySelectorAll('.ctl-cut')].map((e) => { const r = e.getBoundingClientRect(); return Math.min(r.width, r.height); }));
  check('the sword pad has 9 buttons, each at least 48 px', cells.length === 9 && Math.min(...cells) >= 48, cells.join(','));
  const lockShown = await ev(() => document.querySelector('#hud .hud-lock').classList.contains('is-shown'));
  check('the lock-on is on and its marker shows', (await player()).mode !== 'off' && lockShown, JSON.stringify(await player()));
  await shoot('01-battle');

  // ---- the sword pad ----
  for (const [id, dir] of PAD) {
    await untilIdle();
    await clearLogs();
    await tap(`.ctl-cut-${id}`);
    await step(40);
    const a = (await acts())[0];
    const sw = (await evs('swing'))[0];
    const hit = (await evs('hit')).length > 0;
    const ok = dir === null
      ? a && a.type === 'thrust' && sw && sw.kind === 'thrust'
      : a && a.type === 'slash' && Math.abs(a.dir - dir) < 1e-9 && sw && sw.kind === 'slash';
    check(`pad ${id}: ${dir === null ? 'a stab' : `a slash at ${(dir * 180 / Math.PI).toFixed(0)}°`} that lands on the dummy`, ok && hit, JSON.stringify({ a, sw, hit }));
  }

  // ---- combo: → then ← ----
  await untilIdle();
  await step(90); // the last chain's window closes
  await clearLogs();
  await tap('.ctl-cut-right');
  await untilIdle();
  await tap('.ctl-cut-left');
  await step(40);
  const combos = await evs('combo');
  check('→ then ← makes Twin Fangs', combos.some((c) => c.id === 'twin'), JSON.stringify(combos));

  // ---- shield and bash ----
  await untilIdle();
  const sh = await box('.ctl-shield');
  await touch('touchStart', [{ id: 2, x: sh.x, y: sh.y }]);
  await step(2);
  const held = (await player()).shield;
  await touch('touchEnd', []);
  await step(2);
  const dropped = !(await player()).shield;
  check('holding SHIELD raises the shield, letting go lowers it', held && dropped);
  await step(30);
  await clearLogs();
  await tap('.ctl-bash');
  await step(30);
  check('BASH bashes', (await acts()).some((a) => a.type === 'bash') && (await evs('swing')).some((s) => s.kind === 'bash'));

  // ---- the target buttons ----
  await untilIdle();
  await ev(() => window.__game.ctx.player.setLock(window.__game.ctx.actors.find((a) => Math.abs(a.pos.x) < 0.1)));
  await step(30);
  await clearLogs();
  await tap('.ctl-tgt-r');
  await step(60);
  const right = await player();
  await tap('.ctl-tgt-l');
  await step(60);
  const back = await player();
  await tap('.ctl-tgt-l');
  await step(60);
  const left = await player();
  const sw = await evs('lockOn');
  check('› turns to the foe on the right, ‹ back, ‹ again to the left', right.lock === 3.5 && back.lock === 0 && left.lock === -3.5
    && sw.filter((e) => e.reason === 'switch').length === 3, JSON.stringify({ right: right.lock, back: back.lock, left: left.lock }));
  check('the view turns to the locked foe (yaw toward −x is positive)', left.yaw > 0.3 && right.yaw < -0.3, `${right.yaw.toFixed(2)} → ${left.yaw.toFixed(2)}`);
  await shoot('02-locked-left');

  // ---- the move pad ----
  await tap('.ctl-tgt-r'); // face ahead again
  await step(60);
  const p0 = await player();
  const fwd = await box('.ctl-mv-f');
  await touch('touchStart', [{ id: 3, x: fwd.x, y: fwd.y }]);
  await step(40);
  const p1 = await player();
  await touch('touchEnd', []);
  await step(20);
  const p2 = await player();
  const moved = Math.hypot(p1.x - p0.x, p1.z - p0.z);
  check('holding ▲ walks forward, letting go stops', moved > 0.3 && Math.hypot(p2.x - p1.x, p2.z - p1.z) < 0.25, `moved ${moved.toFixed(2)} m`);
  const bk = await box('.ctl-mv-b');
  await touch('touchStart', [{ id: 3, x: bk.x, y: bk.y }]);
  await step(40);
  await touch('touchEnd', []);
  await step(20);
  const p3 = await player();
  check('holding ▼ walks back', Math.hypot(p3.x - p2.x, p3.z - p2.z) > 0.3);

  // ---- the spear: swap, THROW at the lock ----
  await untilIdle();
  await ev(() => {
    const c = window.__game.ctx;
    c.player.pos.set(0, 0, 0);
    c.player.setLock(c.actors.find((a) => a.pos.x > 3));
  });
  await step(60);
  await clearLogs();
  await tap('.ctl-swap');
  await step(60);
  const thr = await box('.ctl-throw');
  check('taking the spear shows THROW', (await player()).weapon === 'spear' && thr.shown, JSON.stringify(thr));
  const overlapThrow = ['.ctl-pad', '.ctl-tgt-l', '.ctl-tgt-r', '.ctl-shield', '.ctl-bash', '.ctl-dpad'].filter((s) => {
    const a = thr, b = boxes[s];
    return a.l < b.r - 1 && b.l < a.r - 1 && a.t < b.b - 1 && b.t < a.b - 1;
  });
  check('THROW overlaps no other button', overlapThrow.length === 0 && thr.r <= W && thr.b <= H, overlapThrow.join(' '));
  await shoot('03-spear');
  await touch('touchStart', [{ id: 4, x: thr.x, y: thr.y }]);
  await sleep(500);
  await touch('touchEnd', []);
  await step(240);
  const thrown = await evs('spearThrown');
  const impact = await evs('spearImpact');
  check('holding and releasing THROW throws the spear at the locked foe', thrown.length === 1 && impact.some((e) => e.x === 3.5), JSON.stringify({ thrown, impact }));

  // ---- a mouse click works and does not grab the pointer ----
  await untilIdle();
  await tap('.ctl-swap'); // back to the sword
  await step(40);
  await untilIdle();
  await clearLogs();
  const c = await box('.ctl-cut-up');
  await page.mouse.click(c.x, c.y);
  await step(20);
  const clicked = (await acts()).some((a) => a.type === 'slash' && Math.abs(a.dir - Math.PI / 2) < 1e-9);
  await page.mouse.click(W / 2, H / 2);
  await step(5);
  const locked = await ev(() => !!document.pointerLockElement);
  check('a mouse click on a pad button strikes, a click elsewhere does not lock the pointer', clicked && !locked);
  check('the bare glass does nothing (no swipe slash, no look)', await (async () => {
    await clearLogs();
    const y0 = (await player()).yaw;
    await touch('touchStart', [{ id: 5, x: W / 2, y: 120 }]);
    for (let i = 1; i <= 6; i++) await touch('touchMove', [{ id: 5, x: W / 2 + i * 30, y: 120 }]);
    await touch('touchEnd', []);
    await step(10);
    return (await acts()).length === 0 && Math.abs((await player()).yaw - y0) < 0.05;
  })());

  // ---- left-handed ----
  await ev(() => { window.__game.ctx.settings.leftHanded = true; });
  await step(2);
  const lp = await box('.ctl-pad');
  const ld = await box('.ctl-dpad');
  const lt = await box('.ctl-tgt-l');
  const lr = await box('.ctl-tgt-r');
  check('left-handed: the sword pad goes bottom left, the move pad bottom right, ‹ stays left of ›', lp.r < W / 2 && ld.l > W / 2 && lt.x < lr.x && lt.l >= 0);
  await shoot('04-left');
  await ev(() => { window.__game.ctx.settings.leftHanded = false; });

  // ---- the Agoge ----
  await ev(() => window.__game.startTraining());
  await step(30);
  const ag = await ev(() => ({
    text: document.querySelector('#training .tr-text')?.textContent || '',
    num: document.querySelector('#training .tr-num')?.textContent || '',
  }));
  check('the Agoge speaks of the buttons and has the change-foe step (13 steps)', /sword pad/.test(ag.text) && /XIII/.test(ag.num), JSON.stringify(ag));
  // Every step: the panel covers no button (SHIELD and BASH matter in the block steps, THROW in the spear step).
  const covered = [];
  const texts = [];
  for (let i = 0; i < 13; i++) {
    if (await ev(() => document.getElementById('training').classList.contains('is-finished'))) break;
    const st = await ev(() => ({ num: document.querySelector('#training .tr-num').textContent, title: document.querySelector('#training .tr-title').textContent, text: document.querySelector('#training .tr-text').textContent }));
    texts.push(st);
    if (st.title === 'The dory') {
      await tap('.ctl-swap');
      await step(60);
    }
    const panel = await box('#training .tr-panel');
    for (const s of ['.ctl-pad', '.ctl-dpad', '.ctl-tgt-l', '.ctl-tgt-r', '.ctl-shield', '.ctl-bash', '.ctl-throw']) {
      const b = await box(s);
      if (b && b.shown && panel.l < b.r - 1 && b.l < panel.r - 1 && panel.t < b.b - 1 && b.t < panel.b - 1) covered.push(`${st.num} ${st.title}: ${s}`);
    }
    if (panel.t < 40) covered.push(`${st.num} ${st.title}: the panel reaches the top bar (${panel.t.toFixed(0)} px)`);
    await shoot(`05-agoge-${String(i + 1).padStart(2, '0')}`);
    await ev(() => document.querySelector('#training [data-act="skip"]').click());
    await step(90);
  }
  check('the Agoge panel covers no button at any step', covered.length === 0, covered.join('; '));
  const lockStep = texts.find((t) => t.title === 'Change foe');
  check('the change-foe step tells you to tap ‹ and ›', !!lockStep && /›/.test(lockStep.text) && /‹/.test(lockStep.text), JSON.stringify(lockStep));
  const missing = texts.filter((t) => !t.text.trim());
  check('every Agoge step has words for the buttons', missing.length === 0, missing.map((t) => t.title).join(', '));
  check('the Agoge reaches its end card (12 steps shown: calibration is for motion only)', texts.length === 12, String(texts.length));

  const errors = await ev(() => window.__game.errors.slice());
  check('no page errors', errors.length === 0 && page.errors.length === 0, [...errors, ...page.errors].join(' | ').slice(0, 400));
} finally {
  await l.close();
}
const failed = results.filter((r) => !r.ok);
console.log(failed.length ? `${failed.length} FAILED of ${results.length}` : `${results.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
