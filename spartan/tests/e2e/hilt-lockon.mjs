// The hilt grip's lock-on camera in the real game (DESIGN.md §11 player and hud, §16), at 412×915
// with ?grip=hilt: the lock is player.js's, the swipe comes through ui/input.js (key X, the desktop
// and e2e stand-in for the thumb swipe), the shield is held by a real touch on the glass (CDP), and
// the marker is hud.js's DOM.
//  - 3 foes at 4 m (40° left, ahead, 35° right): the lock starts on the one ahead; a swipe right
//    moves it, and the view turns within 2° in ≤ 0.8 s, ≤ 0.040 rad per frame, never reversing;
//  - the HUD marker sits within 12 px of the target's projected point, side arrows where a swipe can go;
//  - killing the lock moves it within 0.5 s; with no foes the view eases to the front (± 0.01 in 1.5 s);
//  - a rear volley leaves the yaw unchanged and its arrows hit the raised shield; so does the spear cam;
//  - Day 3 phase C, 60 s of an autopilot that holds the shield up and slashes the lock every 1.5 s
//    (lockAuto on): ≤ 4 turns of more than 90°, the camera moving < 15% of the time, and no threat
//    switch straight back to the previous target within 5 s.
// Usage: node spartan/tests/e2e/hilt-lockon.mjs [outDir]   (outDir: a screenshot of the lock marker)
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const outDir = process.argv[2] || null;
if (outDir) fs.mkdirSync(outDir, { recursive: true });
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const W = 412;
const H = 915;
const DEG = Math.PI / 180;
const THUMB = { x: Math.round(W * 0.65), y: Math.round(H * 0.55) }; // the thumb pad's home (§1 hand model)

const l = await launch();
try {
  const page = await l.newPage({ width: W, height: H, mobile: true, grip: 'hilt' });
  // A loaded headless SwiftShader can stall boot steps: keep index.html's boot watchdog away.
  await page.addInitScript(() => { window.__booted = true; });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low&grip=hilt`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  // Headless Chromium has no gyroscope: menus' support probe (1.5 s after the title shows) would
  // reset a motion setting to touch. Let it finish first, then choose motion.
  await sleep(2000);
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const step = (n = 1) => ev((k) => window.__game.step(k, 1 / 60), n);
  const cdp = await page.context().newCDPSession(page);
  const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map((p) => ({ id: p.id ?? 1, x: p.x, y: p.y, radiusX: 4, radiusY: 4, force: 1 })) });

  await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const render = ctx.renderer.render.bind(ctx.renderer);
    window.__draw = () => {
      ctx.renderer.render = render;
      try {
        g.render();
      } finally {
        ctx.renderer.render = () => {};
      }
    };
    ctx.renderer.render = () => {}; // SwiftShader is slow: draw only for the screenshot
    ctx.settings.control = 'motion';
    ctx.settings.grip = 'hilt';
    ctx.settings.lockAuto = true;
    ctx.applyControls();
    g.startBattle(1);
    ctx.waves.stop();
    ctx.ai.clear();
    ctx.player.pos.set(0, 0, 0);
    ctx.player.yaw = 0;
    window.__locks = [];
    ctx.events.on('lockOn', (e) => window.__locks.push({ reason: e.reason, t: e.target ? e.target.id : null, p: e.prev ? e.prev.id : null, f: window.__frame | 0 }));
    // Foes 4 m away (forward is −z, right is +x; positive yaw turns left).
    const at = (bearing, d, kind = 'dummy') => (kind === 'dummy'
      ? ctx.ai.spawnDummy(-Math.sin(bearing) * d, -Math.cos(bearing) * d)
      : ctx.ai.spawnEnemy(kind, -Math.sin(bearing) * d, -Math.cos(bearing) * d));
    window.__at = at;
    const d = Math.PI / 180;
    window.__L = at(40 * d, 4);
    window.__F = at(0, 4);
    window.__R = at(-35 * d, 4);
    window.__bearing = (a) => Math.atan2(-(a.pos.x - ctx.player.pos.x), -(a.pos.z - ctx.player.pos.z));
  });
  await step(20);

  // ---------------------------------------------------------------- the initial lock and the side arrows
  const s0 = await ev(() => {
    const p = window.__game.ctx.player;
    return {
      grip: window.__game.ctx.input.grip,
      ahead: p.lockTarget === window.__F,
      mode: p.lockMode,
      left: p.lockSides.left === window.__L,
      right: p.lockSides.right === window.__R,
      arrows: [...document.querySelectorAll('.hud-lock-more')].map((e) => e.classList.contains('is-shown')),
      yaw: p.yaw,
    };
  });
  check('hilt grip: 3 foes at 4 m, the lock starts on the one ahead', s0.grip === 'hilt' && s0.ahead && s0.mode === 'target' && Math.abs(s0.yaw) < 0.01, JSON.stringify(s0));
  check('lockSides and the HUD\'s side arrows show a foe each way', s0.left && s0.right && s0.arrows.every(Boolean), JSON.stringify(s0.arrows));

  // ---------------------------------------------------------------- swipe right (key X through input.js)
  await page.keyboard.press('x');
  const sw = await ev(() => {
    const g = window.__game;
    const p = g.ctx.player;
    const R = window.__R;
    const br = window.__bearing(R);
    const ys = [p.yaw];
    let t2 = null;
    for (let i = 1; i <= 120; i++) {
      g.step(1);
      ys.push(p.yaw);
      if (t2 === null && Math.abs(p.yaw - br) < 2 * Math.PI / 180) t2 = i / 60;
    }
    const dy = ys.slice(1).map((y, i) => y - ys[i]);
    const moving = dy.filter((x) => Math.abs(x) > 1e-9);
    return {
      lockR: p.lockTarget === R,
      reason: window.__locks.length ? window.__locks[window.__locks.length - 1].reason : null,
      t2,
      br,
      maxStep: Math.max(...dy.map(Math.abs)),
      signs: [...new Set(moving.map(Math.sign))],
      end: p.yaw,
      rest: !p.lockSpring.moving,
    };
  });
  check('a swipe right moves the lock to the right foe (lockOn switch)', sw.lockR && sw.reason === 'switch', `reason ${sw.reason}`);
  check('the view comes within 2° of his bearing in ≤ 0.8 s', sw.t2 !== null && sw.t2 <= 0.8, `${sw.t2} s to within 2° of ${sw.br.toFixed(3)}`);
  check('every frame turns ≤ 0.040 rad, and the turn never reverses', sw.maxStep <= 0.040 && sw.signs.length === 1,
    `max |Δyaw| ${sw.maxStep.toFixed(4)}, signs ${JSON.stringify(sw.signs)}, at rest ${sw.rest} at ${sw.end.toFixed(4)}`);

  // ---------------------------------------------------------------- the HUD marker on the target
  await ev(() => new Promise((res) => {
    let n = 0;
    const f = () => (++n > 12 ? res() : requestAnimationFrame(f)); // let the marker's scale-in finish
    requestAnimationFrame(f);
  }));
  await step(2);
  const mk = await ev(() => {
    const ctx = window.__game.ctx;
    const t = ctx.player.lockTarget;
    const v = new ctx.THREE.Vector3(t.pos.x, t.pos.y + 0.62 * (t.height || 1.75), t.pos.z).project(ctx.camera);
    const px = ((v.x + 1) / 2) * innerWidth;
    const py = ((1 - v.y) / 2) * innerHeight;
    const rs = [...document.querySelectorAll('.hud-lock-c')].map((e) => e.getBoundingClientRect());
    const left = Math.min(...rs.map((r) => r.left));
    const right = Math.max(...rs.map((r) => r.right));
    const top = Math.min(...rs.map((r) => r.top));
    const bottom = Math.max(...rs.map((r) => r.bottom));
    const el = document.querySelector('.hud-lock');
    return { px, py, cx: (left + right) / 2, cy: (top + bottom) / 2, w: right - left, h: bottom - top, shown: el.classList.contains('is-shown'), opacity: getComputedStyle(el).opacity };
  });
  const off = Math.hypot(mk.cx - mk.px, mk.cy - mk.py);
  check('the lock marker is shown and centred within 12 px of the target\'s projected point', mk.shown && off <= 12,
    `marker (${mk.cx.toFixed(1)}, ${mk.cy.toFixed(1)}) ${mk.w.toFixed(0)}×${mk.h.toFixed(0)} vs (${mk.px.toFixed(1)}, ${mk.py.toFixed(1)}): ${off.toFixed(1)} px`);
  if (outDir) {
    await ev(() => window.__draw());
    await page.screenshot({ path: path.join(outDir, 'hilt-lockon-marker.png') });
  }

  // ---------------------------------------------------------------- the spear cam leaves the yaw alone
  const cam = await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const p = ctx.player;
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    g.step(50);
    const yaw0 = p.yaw;
    const lock0 = p.lockTarget;
    g.action({ type: 'throw', power: 0.8 });
    let over = 0;
    let span = 0;
    for (let i = 0; i < 400; i++) {
      g.step(1);
      if (ctx.fx.cameraOverridden) over++;
      span = Math.max(span, Math.abs(p.yaw - yaw0));
      if (over > 0 && !ctx.fx.cameraOverridden) break;
    }
    g.step(30);
    span = Math.max(span, Math.abs(p.yaw - yaw0));
    return { over, span, lock: p.lockTarget === lock0 };
  });
  check('the spear cam leaves the yaw unchanged (and the lock)', cam.over > 10 && cam.span < 1e-6 && cam.lock, `${cam.over} frames overridden, |Δyaw| ≤ ${cam.span}`);

  // ---------------------------------------------------------------- a rear volley, the shield up
  await ev(() => {
    const ctx = window.__game.ctx;
    ctx.player.refillSpears?.();
    window.__arrows = [];
    window.__hurt = 0;
    ctx.events.on('arrowHit', (e) => window.__arrows.push(typeof e.target === 'string' ? e.target : e.target ? 'actor' : 'none'));
    ctx.events.on('playerHurt', () => window.__hurt++);
  });
  await touch('touchStart', [THUMB]);
  await step(30);
  const vol = await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const p = ctx.player;
    const raised = p.shieldRaise;
    const held = ctx.input.shieldHeld;
    const yaw0 = p.yaw;
    window.__arrows.length = 0;
    window.__hurt = 0;
    ctx.combat.volley({ fromBehind: true, count: 100 });
    let span = 0;
    for (let i = 0; i < 420; i++) {
      g.step(1);
      span = Math.max(span, Math.abs(p.yaw - yaw0));
    }
    return { held, raised, span, arrows: window.__arrows.slice(), hurt: window.__hurt };
  });
  await touch('touchEnd', []);
  await step(2);
  const onShield = vol.arrows.filter((a) => a === 'shield').length;
  const onPlayer = vol.arrows.filter((a) => a === 'player').length;
  check('a rear volley leaves the yaw unchanged', vol.span < 1e-6, `|Δyaw| ≤ ${vol.span}`);
  check('with the thumb on the glass, its arrows hit the raised shield', vol.held && vol.raised >= 0.7 && onShield >= 1 && onPlayer === 0 && vol.hurt === 0,
    `shieldHeld ${vol.held}, raise ${vol.raised.toFixed(2)}, arrows ${JSON.stringify(vol.arrows)}, hurt ${vol.hurt}`);

  // ---------------------------------------------------------------- killing the lock
  const kill = await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const p = ctx.player;
    ctx.ai.clear();
    g.step(30);
    const a = window.__at(0.05, 3, 'levy');
    const b = window.__at(-0.4, 3.5, 'levy');
    g.step(3);
    const t = p.lockTarget;
    const first = t === a || t === b;
    window.__locks.length = 0;
    t.takeHit({ damage: 1e4, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, byPlayer: true, attacker: 'player', point: t.pos.clone() });
    let n = 0;
    while (n < 60 && (p.lockTarget === null || p.lockTarget === t)) {
      g.step(1);
      n++;
    }
    return { first, n, next: !!p.lockTarget && p.lockTarget !== t && p.lockTarget.alive, reasons: window.__locks.map((x) => x.reason) };
  });
  check('killing the lock moves it to the next foe within 0.5 s (death, then acquire)', kill.first && kill.next && kill.n <= 30,
    `${(kill.n / 60).toFixed(2)} s, lockOn [${kill.reasons}]`);

  // ---------------------------------------------------------------- no foes: the front
  const front = await ev(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const p = ctx.player;
    ctx.ai.clear();
    window.__at(40 * Math.PI / 180, 4);
    g.step(80); // the lock acquires him and the view turns 40° left
    const yaw0 = p.yaw;
    ctx.ai.clear();
    let n = 0;
    let inside = null;
    for (; n < 150; n++) {
      g.step(1);
      if (Math.abs(p.yaw) <= 0.01) {
        if (inside === null) inside = n + 1;
      } else inside = null;
    }
    return { yaw0, inside, end: p.yaw, mode: p.lockMode };
  });
  check('no foes: the view eases to the front, yaw 0 ± 0.01 within 1.5 s and stays', front.yaw0 > 0.6 && front.inside !== null && front.inside <= 90 && front.mode === 'front',
    `from ${front.yaw0.toFixed(3)}: inside ± 0.01 from ${front.inside === null ? '-' : (front.inside / 60).toFixed(2)} s, ends ${front.end.toFixed(4)} (${front.mode})`);

  // ---------------------------------------------------------------- Day 3 phase C, 60 s autopilot
  const ff = await ev(async () => {
    const g = window.__game;
    const c = g.ctx;
    c.settings.lockAuto = true;
    g.startBattle(3);
    // Fast-forward phases A and B: kill every Persian in the pass, press READY, keep the hoplite alive.
    let it = 0;
    for (; it < 4000; it++) {
      g.step(20);
      const w = c.waves;
      if (w.day === 3 && w.phase === 'C' && w.state === 'fighting' && w.wave >= 1) break;
      c.player.hp = c.player.maxHp;
      if (!(w.day === 3 && w.phase === 'C')) {
        for (const a of [...c.actors]) if (a.team === 'persian' && a.alive && a.role !== 'dummy' && a.pos.z > -40) a.takeHit({ damage: 9999, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, byPlayer: true, attacker: 'player' });
      }
      if (w.inRespite && w.canReady) w.ready();
      if (it % 40 === 0) await new Promise((res) => setTimeout(res, 0));
    }
    return { it, mode: c.mode, day: c.waves.day, phase: c.waves.phase, state: c.waves.state, gnow: c.time.gnow };
  });
  check('Day 3 phase C is reached (fast-forward)', ff.mode === 'battle' && ff.phase === 'C' && ff.state === 'fighting', JSON.stringify(ff));
  await touch('touchStart', [THUMB]); // the thumb stays on the glass all minute
  const pc = await ev(async () => {
    const g = window.__game;
    const c = g.ctx;
    const p = c.player;
    window.__locks.length = 0;
    const N = 3600; // 60 s at 60 fps
    let moving = 0;
    const turns = [];
    let run = null;
    let held = 0;
    let slashes = 0;
    let hurt = 0;
    const offHurt = c.events.on('playerHurt', () => hurt++);
    let blocks = 0;
    const offBlock = c.events.on('block', (e) => {
      if (e.by === 'player') blocks++;
    });
    const behind = [];
    for (let i = 0; i < N; i++) {
      window.__frame = i;
      if (i % 90 === 45 && p.lockTarget) {
        g.action({ type: 'slash', dir: slashes % 2 ? Math.PI : 0, power: 0.8, src: 'motion' });
        slashes++;
      }
      const y0 = p.yaw;
      g.step(1);
      p.hp = p.maxHp; // the autopilot measures the camera, not survival
      if (c.input.shieldHeld) held++;
      const dy = Math.atan2(Math.sin(p.yaw - y0), Math.cos(p.yaw - y0));
      if (p.lockSpring.moving) {
        moving++;
        if (!run) run = { f0: i, net: 0 };
        run.net += dy;
      } else if (run) {
        run.net += dy;
        turns.push({ f0: run.f0, f1: i, deg: Math.abs(run.net) / (Math.PI / 180) });
        run = null;
      }
      if (i % 30 === 0) {
        let n = 0;
        for (const a of c.actors) if (a.team === 'persian' && a.alive && a.pos.z > 1) n++;
        behind.push(n);
      }
      if (i % 600 === 0) await new Promise((res) => setTimeout(res, 0));
    }
    if (run) turns.push({ f0: run.f0, f1: N, deg: Math.abs(run.net) / (Math.PI / 180) });
    offHurt();
    offBlock();
    return {
      N,
      moving,
      turns,
      held,
      slashes,
      hurt,
      blocks,
      locks: window.__locks.slice(),
      phase: c.waves.phase,
      mode: c.mode,
      behindMax: Math.max(...behind),
      lockMode: p.lockMode,
    };
  });
  await touch('touchEnd', []);
  const big = pc.turns.filter((t) => t.deg > 90);
  const reasons = {};
  for (const e of pc.locks) reasons[e.reason] = (reasons[e.reason] || 0) + 1;
  console.log(`      phase C: ${pc.slashes} slashes, shield held ${(100 * pc.held / pc.N).toFixed(0)}% of frames, ${pc.blocks} blocks, ${pc.hurt} hurts, up to ${pc.behindMax} Persians behind the line; lockOn ${JSON.stringify(reasons)}`);
  console.log(`      turns > 10°: ${pc.turns.filter((t) => t.deg > 10).map((t) => `${(t.f0 / 60).toFixed(1)}s ${t.deg.toFixed(0)}°`).join(', ')}`);
  check('phase C ran 60 s in battle with the lock on, the shield up and the autopilot slashing',
    pc.mode === 'battle' && pc.phase === 'C' && pc.lockMode !== 'off' && pc.held >= 0.95 * pc.N && pc.slashes >= 30, `${pc.mode}/${pc.phase}, lockMode ${pc.lockMode}`);
  check('phase C: at most 4 turns of more than 90° in the minute', big.length <= 4, `${big.length}: ${big.map((t) => `${(t.f0 / 60).toFixed(1)}s ${t.deg.toFixed(0)}°`).join(', ')}`);
  check('phase C: the camera moves less than 15% of the time', pc.moving / pc.N < 0.15, `${(100 * pc.moving / pc.N).toFixed(1)}% of frames`);
  // A threat switch from P to X: no threat switch back to P within 5 s (frames are real time, ≥ the player's pdt clock).
  const threats = pc.locks.filter((e) => e.reason === 'threat');
  const back = [];
  for (let i = 0; i < threats.length; i++) {
    for (let j = i + 1; j < threats.length; j++) {
      if (threats[j].t === threats[i].p && threats[i].p !== null && threats[j].f - threats[i].f < 5 * 60) back.push(`${(threats[i].f / 60).toFixed(1)}s→${(threats[j].f / 60).toFixed(1)}s`);
    }
  }
  check('phase C: no threat switch straight back to the previous target within 5 s', back.length === 0,
    `${threats.length} threat switches${back.length ? `; back: ${back.join(', ')}` : ''}`);

  const errors = page.errors.concat(await ev(() => window.__game.errors));
  check('no page errors', errors.length === 0, errors.join(' | ').slice(0, 1500));
} catch (err) {
  check(`script error: ${err && (err.stack || err)}`, false);
} finally {
  await l.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
