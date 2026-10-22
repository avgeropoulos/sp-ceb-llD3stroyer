// Cross-module integration audit (f): fuzz random actions, input, motion samples, settings and mode
// changes; after every batch check __game.errors and NaN in camera / player / actors / time.
// usage: node review-integ-fuzz.mjs [seed=1] [batches=700] [control=motion] [modeChangeP=0.04]
import { launch } from './lib.mjs';
const seed = Number(process.argv[2] || 1), batches = Number(process.argv[3] || 700), control = process.argv[4] || 'motion';
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low&control=${control}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const r = await page.evaluate(async ([seed, batches, modeP]) => {
    window.__fuzzModeP = modeP; window.__game.startBattle(1 + (seed % 3));
    const g = window.__game, c = g.ctx;
    let s = seed >>> 0 || 1;
    const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
    const pick = (a) => a[Math.floor(rnd() * a.length)];
    const P = Math.PI;
    const scripts = [
      () => [{ kind: 'idle', duration: 0.2 }, { kind: 'swing', dir: (rnd() * 2 - 1) * P }, { kind: 'idle', duration: 0.2 }],
      () => [{ kind: 'idle', duration: 0.2 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.3 }],
      () => [{ kind: 'idle', duration: 0.3 }, { kind: 'throw' }, { kind: 'idle', duration: 0.3 }],
      () => [{ kind: 'idle', duration: 0.2 }, { kind: 'raise' }, { kind: 'swing', dir: -P / 2 }, { kind: 'idle', duration: 0.2 }],
    ];
    const finite = (v) => typeof v === 'number' && Number.isFinite(v);
    const bad = [];
    const check = (label) => {
      const probs = [];
      const cp = c.camera.position, cq = c.camera.quaternion;
      if (![cp.x, cp.y, cp.z, cq.x, cq.y, cq.z, cq.w].every(finite)) probs.push(`camera ${cp.toArray()} ${cq.toArray()}`);
      const p = c.player;
      if (![p.pos.x, p.pos.y, p.pos.z, p.yaw, p.pitch, p.hp, p.shieldRaise].every(finite)) probs.push(`player pos=${p.pos.toArray()} yaw=${p.yaw} pitch=${p.pitch} hp=${p.hp} raise=${p.shieldRaise}`);
      for (const a of c.actors.concat(c.ai.corpses)) {
        if (![a.pos.x, a.pos.y, a.pos.z, a.facing, a.role === 'dummy' ? 0 : a.hp].every(finite)) probs.push(`actor ${a.kind}#${a.id} pos=${a.pos.toArray()} facing=${a.facing} hp=${a.hp} state=${a.state}`);
        const rp = a.visual.root.position;
        if (![rp.x, rp.y, rp.z].every(finite)) probs.push(`actor root ${a.kind}#${a.id} ${rp.toArray()}`);
      }
      const t = c.time;
      if (![t.scale, t.gnow, t.now, t.hitStop, t.gdt, t.pdt].every(finite)) probs.push(`time ${JSON.stringify(t)}`);
      for (const pr of c.combat.projectiles) if (![pr.pos.x, pr.pos.y, pr.pos.z].every(finite)) probs.push(`projectile ${pr.kind} ${pr.pos.toArray()}`);
      if (probs.length) bad.push({ label, probs: probs.slice(0, 4) });
    };
    const log = [];
    let errSeen = 0;
    for (let b = 0; b < batches; b++) {
      const r = rnd();
      let what = '';
      if (r < (window.__fuzzModeP ?? 0.04)) {
        const m = pick(['battle1', 'battle2', 'battle3', 'training', 'pause', 'resume', 'restart', 'quit', 'title', 'die']);
        what = 'mode:' + m;
        if (m.startsWith('battle')) g.startBattle(Number(m.slice(6)));
        else if (m === 'training') g.startTraining();
        else if (m === 'pause') c.setMode('paused');
        else if (m === 'resume') c.resume();
        else if (m === 'restart') { if (c.mode === 'paused') c.setMode('battle', { day: c.waves.day || 1 }); }
        else if (m === 'quit' || m === 'title') c.setMode('title');
        else if (m === 'die' && c.mode === 'battle') c.player.damage(500, { from: null, kind: 'melee' });
      } else if (r < 0.07) {
        const k = pick(['blood', 'haptics', 'voice', 'helmetView', 'leftHanded', 'gyroLook', 'aimAssist', 'sensitivity', 'control']);
        what = 'setting:' + k;
        if (k === 'sensitivity') c.settings.sensitivity = 0.5 + rnd() * 1.5;
        else if (k === 'control') c.settings.control = c.settings.control === 'motion' ? 'touch' : 'motion';
        else c.settings[k] = !c.settings[k];
      } else if (r < 0.12) {
        what = 'spawn';
        g.spawn(pick(['levy', 'sparabara', 'immortal', 'archer', 'captain']), (rnd() * 2 - 1) * 7, -1.5 - rnd() * 20, { facing: 0 });
      } else if (r < 0.14) {
        what = 'volley';
        c.combat.volley({ count: 50 + Math.floor(rnd() * 300), duration: 0.5 + rnd(), warn: rnd() * 2.5, fromBehind: rnd() < 0.2 });
      } else if (r < 0.2) {
        what = 'motion';
        try { g.injectMotion(g.synth(pick(scripts)(), { rot: pick([90, 270]), hand: rnd() < 0.5 ? 'left' : 'right', seed: b })); } catch (e) { log.push('synth ' + e.message); }
      } else if (r < 0.22) {
        what = 'motion-garbage';
        const t0 = 0;
        const samples = [];
        for (let i = 0; i < 20; i++) samples.push({ t: t0 + (rnd() < 0.2 ? -i : i) * 0.005 * (rnd() < 0.1 ? 1000 : 1), gyro: [(rnd() * 2 - 1) * 40, (rnd() * 2 - 1) * 40, (rnd() * 2 - 1) * 40], acc: rnd() < 0.3 ? null : [(rnd() * 2 - 1) * 80, (rnd() * 2 - 1) * 80, (rnd() * 2 - 1) * 80], accG: rnd() < 0.3 ? null : [0, rnd() < 0.5 ? 0 : 9.81, 0], grav: rnd() < 0.5 ? null : [0, 0, 0], rot: pick([0, 90, 180, 270]) });
        g.injectMotion(samples);
      } else {
        what = 'action';
        const a = pick(['slash', 'slash', 'slash', 'thrust', 'thrust', 'bash', 'throw', 'switchWeapon', 'recenter']);
        if (a === 'slash') g.action({ type: 'slash', dir: (rnd() * 2 - 1) * P, power: rnd() });
        else if (a === 'switchWeapon') g.action({ type: 'switchWeapon' });
        else g.action({ type: a, power: rnd() });
        c.input.shieldHeld = rnd() < 0.3;
        if (c.input.shieldHeld) c.input.shieldPressedAt = c.time.gnow;
        c.input.moveX = rnd() < 0.5 ? 0 : rnd() * 2 - 1;
        c.input.moveZ = rnd() < 0.5 ? 0 : rnd() * 2 - 1;
        c.input.lookYaw += (rnd() * 2 - 1) * 0.3;
        c.input.lookPitch += (rnd() * 2 - 1) * 0.2;
      }
      log.push(`${b}:${c.mode}:${what}`);
      try { g.step(1 + Math.floor(rnd() * 8), rnd() < 0.05 ? 0.2 : 1 / 60); } catch (e) { bad.push({ label: `step threw at ${b} after ${what}`, probs: [String(e && e.stack || e).slice(0, 600)] }); }
      check(`${b} after ${what} (mode ${c.mode})`);
      if (g.errors.length > errSeen) { bad.push({ label: `errors at ${b} after ${what}`, probs: g.errors.slice(errSeen, errSeen + 3) }); errSeen = g.errors.length; }
      if (bad.length > 12) break;
      if (b % 25 === 0) await new Promise((res) => setTimeout(res, 0));
    }
    return { bad, errors: g.errors.slice(0, 10), frames: c.time.now, tail: log.slice(-8), modes: [...new Set(log.map((x) => x.split(':')[1]))] };
  }, [seed, batches, Number(process.argv[5] ?? 0.04)]);
  console.log(JSON.stringify(r, null, 1));
  console.log('page errors', page.errors.slice(0, 10));
} finally { await l.close(); }
