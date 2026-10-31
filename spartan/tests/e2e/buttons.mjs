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
    for (const n of ['swing', 'hit', 'combo', 'lockOn', 'spearThrown', 'spearImpact', 'weaponSwitch', 'rage', 'rageReady']) {
      ctx.events.on(n, (e) => window.__ev.push({
        n, kind: e && e.kind, dir: e && e.dir, reason: e && e.reason, name: e && e.name, id: e && e.id, damage: e && e.damage,
        x: e && e.target && e.target.pos ? +e.target.pos.x.toFixed(2) : null,
      }));
    }
  });
  const step = (n = 1) => ev((k) => window.__game.step(k, 1 / 60), n);
  await step(60);
  const cdp = await page.context().newCDPSession(page);
  // `at`: an explicit event time (epoch ms), so a timed swipe keeps its pace however slowly the page acks.
  const touch = (type, pts, at) => cdp.send('Input.dispatchTouchEvent', Object.assign({
    type, touchPoints: pts.map((p) => ({ id: p.id ?? 1, x: p.x, y: p.y, radiusX: 4, radiusY: 4, force: 1 })),
  }, at === undefined ? {} : { timestamp: at / 1000 }));
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

  // ---- tap a foe, swipe on the open ground ----
  // A point on the foe at x = fx that is on screen and not under a button (the bare glass there).
  const screenOf = (fx) => ev((x) => {
    const c = window.__game.ctx;
    const a = c.actors.find((b) => Math.abs(b.pos.x - x) < 0.1);
    for (const k of [0.55, 0.75, 0.9, 0.35, 0.2]) {
      const v = new c.camera.position.constructor(a.pos.x, a.pos.y + k * (a.height || 1.75), a.pos.z).project(c.camera);
      const p = { x: (v.x + 1) / 2 * innerWidth, y: (1 - v.y) / 2 * innerHeight };
      if (p.x < 0 || p.y < 0 || p.x > innerWidth || p.y > innerHeight) continue;
      const el = document.elementFromPoint(p.x, p.y);
      if (el && el.classList.contains('ctl-surface')) return p;
    }
    return null;
  }, fx);
  // Locked on the left foe, the far right foe is on screen (35° either side of the line).
  await ev(() => window.__game.ctx.player.setLock(window.__game.ctx.actors.find((a) => Math.abs(a.pos.x) < 0.1)));
  await step(60);
  const at = await screenOf(-3.5);
  await touch('touchStart', [{ id: 6, x: at.x, y: at.y }]);
  await touch('touchEnd', []);
  await step(60);
  const tapped = await player();
  check('tapping a foe on screen locks onto him', !!at && tapped.lock === -3.5, `lock ${tapped.lock} (tapped ${at && at.x.toFixed(0)},${at && at.y.toFixed(0)})`);
  const t0 = Date.now();
  await touch('touchStart', [{ id: 7, x: 330, y: 80 }], t0);
  for (let i = 1; i <= 5; i++) await touch('touchMove', [{ id: 7, x: 330 + i * 24, y: 84 }], t0 + i * 30);
  await touch('touchEnd', [], t0 + 160);
  await step(60);
  const swiped = await player();
  check('swiping right on the open ground switches to the next foe on the right', swiped.lock === 0, `lock ${swiped.lock}`);
  await touch('touchStart', [{ id: 8, x: 380, y: 60 }]);
  await touch('touchEnd', []);
  await step(10);
  check('tapping the empty sky keeps the lock', (await player()).lock === 0);

  // ---- Spartan rage ----
  const rageBtn = await box('.ctl-rage');
  const rageOver = Object.keys(boxes).filter((s) => {
    const b = boxes[s];
    return rageBtn.l < b.r - 1 && b.l < rageBtn.r - 1 && rageBtn.t < b.b - 1 && b.t < rageBtn.b - 1;
  });
  check('the RAGE button shows, on screen, over no other button', rageBtn.shown && rageBtn.r <= W && rageBtn.b <= H && rageOver.length === 0, rageOver.join(' '));
  const rageCls = () => ev(() => ({ cls: document.querySelector('.ctl-rage').className, p: document.querySelector('.ctl-rage').style.getPropertyValue('--p'), root: document.getElementById('controls').className, rage: window.__game.ctx.player.rage, raging: window.__game.ctx.player.raging }));
  await clearLogs();
  await tap('.ctl-rage');
  await step(5);
  check('an empty meter: RAGE does nothing', !(await rageCls()).raging && (await evs('rage')).length === 0);
  // Hits fill the meter.
  await ev(() => window.__game.ctx.player.setLock(window.__game.ctx.actors.find((a) => Math.abs(a.pos.x) < 0.1)));
  await step(40);
  await untilIdle();
  const r0 = (await rageCls()).rage;
  await tap('.ctl-cut-right');
  await step(40);
  const r1 = await rageCls();
  check('a hit fills the rage meter and its ring', r1.rage > r0 && Number(r1.p) > 0, `${r0} → ${r1.rage}, ring ${r1.p}`);
  // A blow before rage, for its damage.
  await untilIdle();
  await step(90);
  await clearLogs();
  await tap('.ctl-cut-stab');
  await step(40);
  const calm = (await evs('hit'))[0];
  await ev(() => { window.__game.ctx.player.rage = 0.99; });
  await untilIdle();
  await step(90);
  await clearLogs();
  await tap('.ctl-cut-stab');
  await step(40);
  const full = await rageCls();
  check('filling the meter makes RAGE glow and announces it', /is-ready/.test(full.cls) && (await evs('rageReady')).length === 1, full.cls);
  await shoot('06-rage-ready');
  await untilIdle();
  await step(90);
  // Two foes side by side in reach: raging cuts catch both.
  await ev(() => {
    const c = window.__game.ctx;
    c.ai.spawnDummy(0.7, -1.6);
  });
  await step(10);
  const hp0 = await ev(() => window.__game.ctx.player.hp);
  await clearLogs();
  await tap('.ctl-rage');
  await step(5);
  const on = await rageCls();
  check('tapping a glowing RAGE unleashes it: the button burns, the screen edges run red', on.raging && /is-on/.test(on.cls) && /ctl-raging/.test(on.root) && (await evs('rage')).some((e) => e.kind === undefined), JSON.stringify({ cls: on.cls, raging: on.raging }));
  await shoot('07-raging');
  await clearLogs();
  await tap('.ctl-cut-stab');
  await step(40);
  const hot = (await evs('hit'))[0];
  check('raging blows strike twice as hard', calm && hot && hot.damage >= calm.damage * 1.9, `${calm && calm.damage} → ${hot && hot.damage}`);
  await untilIdle();
  await clearLogs();
  await tap('.ctl-cut-right');
  await step(40);
  const sweepHits = (await evs('hit')).length;
  check('a raging cut catches every foe in its arc', sweepHits >= 2, `${sweepHits} hits`);
  await ev(() => window.__game.ctx.player.damage(20, {}));
  const hp1 = await ev(() => window.__game.ctx.player.hp);
  check('raging, blows hurt half as much', Math.abs(hp0 - hp1 - 10) < 1e-6, `${hp0} → ${hp1}`);
  await step(60 * 8);
  const off = await rageCls();
  check('the rage burns out after 8 s and the meter is empty', !off.raging && off.rage === 0 && !/is-on/.test(off.cls) && !/ctl-raging/.test(off.root), JSON.stringify(off));
  await ev(() => {
    const c = window.__game.ctx;
    const extra = c.actors.find((a) => Math.abs(a.pos.x - 0.7) < 0.05);
    if (extra) extra.alive = false;
    c.ai.clear();
    c.ai.spawnDummy(0, -1.7);
    c.ai.spawnDummy(-3.5, -5);
    c.ai.spawnDummy(3.5, -5);
    c.player.hp = c.player.maxHp;
  });
  await step(30);

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
  check('the bare glass never slashes, stabs or drags the view (a swipe only switches foe)', await (async () => {
    await clearLogs();
    await touch('touchStart', [{ id: 5, x: W / 2, y: 120 }]);
    for (let i = 1; i <= 6; i++) await touch('touchMove', [{ id: 5, x: W / 2 + i * 6, y: 120 + i * 20 }]);
    await touch('touchEnd', []);
    await step(10);
    const a = await acts();
    return a.every((x) => x.type === 'switchTarget') && !a.some((x) => x.type === 'slash' || x.type === 'thrust');
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

  // ---- the rage grows with the campaign ----
  await ev(() => {
    const g = window.__game;
    g.startBattle(2);
  });
  await step(30);
  const day2 = await ev(() => window.__game.ctx.player.ragePerks.tier);
  check('a Day 2 battle starts with the second rage tier', day2 === 2, `tier ${day2}`);
  await ev(() => {
    const c = window.__game.ctx;
    c.waves.stop();
    c.ai.clear();
    c.player.pos.set(0, 0, 0);
    c.events.emit('phase', { day: 3, phase: 'C', title: 'The Kolonos' });
  });
  await step(5);
  const last = await ev(() => ({ tier: window.__game.ctx.player.ragePerks.tier, toast: [...document.querySelectorAll('#hud .hud-toast.is-shown')].map((t) => t.textContent).join(' | ') }));
  check('the Kolonos raises the rage to the last stand, and the HUD says what it gives', last.tier === 4 && /Rage grows/.test(last.toast) && /arrow-proof/.test(last.toast), JSON.stringify(last));
  await shoot('08-rage-tier');
  // Three Immortals around the player, one behind: the war cry hurls them all back.
  await ev(() => {
    const c = window.__game.ctx;
    window.__ring = [c.ai.spawnEnemy('immortal', 0, -2.2), c.ai.spawnEnemy('immortal', 2.2, 0.4), c.ai.spawnEnemy('immortal', -0.5, 2.5)];
    window.__ring0 = window.__ring.map((a) => Math.hypot(a.pos.x, a.pos.z));
    c.player.rage = 1;
  });
  await step(3);
  await tap('.ctl-rage');
  await step(30);
  const ring = await ev(() => ({ states: window.__ring.map((a) => a.state), d: window.__ring.map((a) => Math.hypot(a.pos.x, a.pos.z)), d0: window.__ring0, dur: window.__game.ctx.player.raging }));
  check('the last stand\'s war cry knocks down every foe around, the one behind too', ring.states.every((st) => st === 'knockdown' || st === 'stagger') && ring.d.every((d, i) => d > ring.d0[i] + 0.5),
    JSON.stringify(ring));
  const lhp0 = await ev(() => window.__game.ctx.player.hp);
  await ev(() => window.__game.ctx.player.damage(10, { kind: 'arrow' }));
  const lhp1 = await ev(() => window.__game.ctx.player.hp);
  await ev(() => window.__game.ctx.player.damage(20, { kind: 'melee' }));
  const lhp2 = await ev(() => window.__game.ctx.player.hp);
  check('in the last stand, arrows cannot hurt a raging Spartan, and blows hurt a third', lhp1 === lhp0 && Math.abs(lhp1 - lhp2 - 7) < 1e-6, `${lhp0} → ${lhp1} → ${lhp2}`);
  await shoot('09-last-stand-rage');

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
