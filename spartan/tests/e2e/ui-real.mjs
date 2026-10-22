// Real-game integration for the UI slice: boots index.html (touch layout, low quality, manual
// stepping) and drives title → battle → pause → game over → the Agoge → motion choice with real
// CDP touches, checking how the other modules react (player swings, shield, weapon switch, throw
// and spear cam, waves cards and voice, training demo gestures through the real motion pipeline).
// Usage: node spartan/tests/e2e/ui-real.mjs [shotDir]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const outDir = process.argv[2] || '';
if (outDir) fs.mkdirSync(outDir, { recursive: true });
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const near = (a, b, tol) => Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))) <= tol;
const t0 = Date.now();

const l = await launch();
try {
  const page = await l.newPage({ width: 844, height: 390, mobile: true });
  // A loaded headless SwiftShader can stall boot steps for a long time: keep index.html's boot
  // watchdog from replacing #overlay (this test is about the UI, not boot speed).
  await page.addInitScript(() => { window.__booted = true; });
  await page.goto(`${l.game}?manual=1&seed=3&quality=low&control=touch`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });
  console.log(`booted in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  const cdp = await page.context().newCDPSession(page);
  // Let the last stepped frame reach the screen before a gesture starts: under SwiftShader the
  // renderer acks CDP input only after presenting, which can stretch a swipe's moves over seconds.
  const settle = () => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  const touch = async (type, pts) => {
    if (type === 'touchStart') await settle();
    return cdp.send('Input.dispatchTouchEvent', {
      type, touchPoints: pts.map((p, i) => Object.assign({ id: p.id ?? i + 1, radiusX: 4, radiusY: 4, force: 1 }, p)),
    });
  };
  const step = async (n = 1) => {
    for (let i = 0; i < n; i += 60) await page.evaluate((k) => window.__game.step(k), Math.min(60, n - i));
  };
  const ev = (fn, arg) => page.evaluate(fn, arg);
  const center = (sel) => ev((s) => {
    const el = [...document.querySelectorAll(s)].find((e) => e.getClientRects().length > 0);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
  }, sel);
  const tap = async (sel) => {
    const c = await center(sel);
    if (!c) throw new Error(`nothing to tap: ${sel}`);
    await touch('touchStart', [{ x: c.x, y: c.y }]);
    await touch('touchEnd', []);
    await sleep(120); // the synthesized click follows the touchend asynchronously
    return c;
  };
  // Timing-sensitive gestures (swipe speed, tap length, THROW hold) are played from inside the
  // page as touch PointerEvents 16 ms apart: CDP input acks wait for SwiftShader to present, which
  // on a loaded machine can stretch a 60 ms swipe over seconds. Each script item is
  // ['down'|'move'|'up', x, y] or ['wait', ms] or ['step', frames]. A script without 'down'
  // continues the previous one's pointer.
  const gesture = (script) => page.evaluate(async (items) => {
    const g = (window.__g = window.__g || { id: 100, target: null });
    const fire = (type, x, y) => g.target.dispatchEvent(new PointerEvent(type, {
      pointerId: g.id, pointerType: 'touch', isPrimary: true, clientX: x, clientY: y, width: 8, height: 8, bubbles: true, cancelable: true,
    }));
    for (const [k, a, b] of items) {
      if (k === 'wait') await new Promise((r) => setTimeout(r, a));
      else if (k === 'step') window.__game.step(a);
      else {
        if (k === 'down') {
          g.id++;
          g.target = document.elementFromPoint(a, b);
        }
        fire(k === 'down' ? 'pointerdown' : k === 'up' ? 'pointerup' : 'pointermove', a, b);
      }
    }
  }, script);
  const swipe = (x0, y0, x1, y1, moves = 4) => {
    const s = [['down', x0, y0]];
    for (let i = 1; i <= moves; i++) s.push(['wait', 16], ['move', x0 + ((x1 - x0) * i) / moves, y0 + ((y1 - y0) * i) / moves]);
    s.push(['wait', 16], ['up', x1, y1]);
    return gesture(s);
  };
  const has = (sel, cls) => ev(([s, c]) => !!document.querySelector(s) && document.querySelector(s).classList.contains(c), [sel, cls]);
  const text = (sel) => ev((s) => (document.querySelector(s) || {}).textContent || '', sel);
  const mode = () => ev(() => window.__game.ctx.mode);
  const events = (name) => ev((n) => window.__log.filter((e) => e[0] === n).map((e) => e[1]), name);
  const clearLog = () => ev(() => { window.__log.length = 0; });
  const shot = async (name) => {
    if (!outDir) return;
    await sleep(700); // CSS fades run in real time
    await step(1);
    await page.screenshot({ path: path.join(outDir, `real-${name}.png`), timeout: 180000 });
    console.log(`saved ${path.join(outDir, `real-${name}.png`)}`);
  };

  // Record the bus traffic the UI cares about.
  await ev(() => {
    const log = (window.__log = []);
    const pick = (e) => {
      if (!e || typeof e !== 'object') return e;
      const o = {};
      for (const [k, v] of Object.entries(e)) if (v === null || typeof v !== 'object') o[k] = v;
      return o;
    };
    for (const n of ['swing', 'shield', 'weaponSwitch', 'spearThrown', 'spearCam', 'playerDeath', 'gameOver', 'modeChanged',
      'dayStart', 'waveStart', 'voice', 'gesture', 'respite']) {
      window.__game.ctx.events.on(n, (e) => log.push([n, pick(e)]));
    }
    document.addEventListener('click', (e) => log.push(['click', { cls: String(e.target.className) }]), true);
  });

  // ------------------------------------------------------------------ title
  await step(3);
  check('boot → title screen; HUD hidden; controls inactive',
    (await mode()) === 'title' && (await has('.mn-title', 'is-on')) && !(await has('#hud', 'is-visible')) && !(await has('#controls', 'is-active')));
  check('title: epigraph, 480 BC, Fight/Agoge/Controls/Settings/How to play',
    /With it, or on it\./.test(await text('.mn-epigraph')) && /480 BC/.test(await text('.mn-year'))
      && (await center('.mn-title [data-act="fight"]')) && (await center('.mn-title [data-act="agoge"]')) && (await center('.mn-title .mn-seg'))
      && (await center('.mn-title [data-act="settings"]')) && (await center('.mn-title [data-act="howto"]')));
  await shot('title');

  // ------------------------------------------------------------------ battle
  await tap('.mn-title [data-act="fight"]');
  await step(1);
  check('Fight (touch) → battle Day 1; menus closed; HUD and touch controls up',
    (await mode()) === 'battle' && (await ev(() => window.__game.ctx.waves.day)) === 1 && !(await has('#menus', 'is-open'))
      && (await has('#hud', 'is-visible')) && (await has('#controls', 'is-active')) && (await has('#controls', 'ctl-hastouch')));
  await step(130);
  const day = (await events('dayStart'))[0];
  check('waves dayStart → day card on the HUD', day && (await text('.hud-banner-t')) === day.title && (await has('.hud-banner', 'is-shown')), `${day && day.title}`);
  const voice = (await events('voice'))[0];
  check('waves voice → subtitle with the speaker', voice && (await text('.hud-sub.is-shown .hud-sub-l b')) === voice.speaker, JSON.stringify(voice));
  await shot('battle-card');

  // Touch attacks reach the real player.
  await clearLog();
  await swipe(560, 200, 700, 200);
  await step(30);
  await swipe(720, 180, 580, 180);
  await step(30);
  await gesture([['down', 640, 160], ['wait', 40], ['up', 640, 160]]);
  await step(30);
  let sw = await events('swing');
  check('swipe R, swipe L, tap → player swings slash R, slash L, thrust (src touch)',
    sw.length >= 3 && sw[0].kind === 'slash' && near(sw[0].dir, 0, 0.3) && sw[1].kind === 'slash' && near(sw[1].dir, Math.PI, 0.3)
      && sw.slice(2).some((s) => s.kind === 'thrust') && sw.every((s) => s.src === 'touch'), JSON.stringify(sw));

  // SHIELD: hold raises the real shield; swipe up from it bashes.
  await clearLog();
  const sh = await center('.ctl-shield');
  await gesture([['down', sh.x, sh.y], ['step', 20]]);
  const raise = await ev(() => window.__game.ctx.player.shieldRaise);
  const up = [];
  for (let i = 1; i <= 4; i++) up.push(['wait', 16], ['move', sh.x, sh.y - i * 24]);
  await gesture([...up, ['step', 30], ['up', sh.x, sh.y - 96], ['step', 25]]);
  const lowered = await ev(() => window.__game.ctx.player.shieldRaise);
  sw = await events('swing');
  const shieldEv = await events('shield');
  check('SHIELD hold → shieldRaise ↑ and shield{up}; swipe up → bash; release lowers it',
    raise > 0.9 && shieldEv.some((s) => s.up === true) && sw.some((s) => s.kind === 'bash') && lowered < 0.3,
    `raise ${raise.toFixed(2)} lowered ${lowered.toFixed(2)} ${JSON.stringify(sw)} ${JSON.stringify(shieldEv)}`);

  // Weapon swap → spear; THROW appears; hold + release throws; the spear cam letterboxes the HUD.
  await clearLog();
  await step(60);
  await tap('.ctl-swap');
  await step(50);
  const ws = await events('weaponSwitch');
  const throwShown = !!(await center('.ctl-throw'));
  check('swap → weaponSwitch spear; THROW button appears',
    ws.some((w) => w.weapon === 'spear') && (await ev(() => window.__game.ctx.player.weapon)) === 'spear' && throwShown, JSON.stringify(ws));
  await shot('battle-spear');
  const th = await center('.ctl-throw');
  await gesture([['down', th.x, th.y], ['wait', 420], ['step', 1], ['up', th.x, th.y], ['step', 12]]);
  sw = await events('swing');
  const thrown = await events('spearThrown');
  const cine = await has('#hud', 'is-cinematic');
  check('THROW hold/release → swing throw (power ≈ hold/0.7 s), spearThrown, spear-cam letterbox',
    sw.some((s) => s.kind === 'throw' && s.power > 0.4 && s.power < 1) && thrown.length === 1 && cine, `${JSON.stringify(sw)} cine ${cine}`);
  if (cine) await shot('spearcam');
  await step(60 * 4);
  check('spear cam ends → letterbox gone', !(await has('#hud', 'is-cinematic')) && (await events('spearCam')).some((e) => e.phase === 'end'));

  // The stick walks the player forward (−Z).
  const z0 = await ev(() => window.__game.ctx.player.pos.z);
  await touch('touchStart', [{ x: 110, y: 300 }]);
  await touch('touchMove', [{ x: 110, y: 240 }]);
  await step(40);
  const mid = await ev(() => ({ z: window.__game.ctx.player.pos.z, mz: window.__game.ctx.input.moveZ }));
  await touch('touchEnd', []);
  await step(2);
  check('stick forward → input.moveZ > 0 and the player advances', mid.mz > 0.8 && mid.z < z0 - 0.3, `z ${z0.toFixed(2)} → ${mid.z.toFixed(2)}, moveZ ${mid.mz.toFixed(2)}`);

  // Pause button → pause menu → Resume.
  await tap('.ctl-pause');
  await step(2);
  check('pause button → paused, pause menu with Day I', (await mode()) === 'paused' && (await has('.mn-pause', 'is-on')) && /Day I/.test(await text('.mn-pause-k')));
  await shot('pause');
  await tap('.mn-pause [data-act="resume"]');
  await step(2);
  check('Resume → battle', (await mode()) === 'battle' && !(await has('#menus', 'is-open')));

  // Low HP → the viewmodel overlay gets the vignette; death → game over with the epitaph and best.
  await ev(() => window.__game.ctx.player.damage(85, { kind: 'melee', relYaw: 0.8 }));
  await step(10);
  const vmOverlay = await ev(() => {
    const vm = window.__game.ctx.viewmodel;
    return vm.debug && vm.debug.overlay ? vm.debug.overlay : null;
  });
  const arcOn = await ev(() => [...document.querySelectorAll('.hud-arc')].some((a) => +a.style.opacity > 0));
  check('playerHurt → damage arc; low HP bar', arcOn && (await has('.hud-health', 'is-low')), `overlay ${JSON.stringify(vmOverlay)}`);
  await shot('lowhp');
  await ev(() => window.__game.ctx.player.damage(50, { kind: 'melee', relYaw: -2 }));
  await step(60 * 3.6);
  const go = (await events('gameOver'))[0];
  const best = await ev(() => JSON.parse(localStorage.getItem('thermopylae.best') || 'null'));
  check('death → gameOver → game over screen, epitaph, thermopylae.best written',
    go && (await mode()) === 'gameover' && (await has('.mn-gameover', 'is-on')) && /Go tell the Spartans/.test(await text('.mn-epitaph'))
      && /Simonides \(Herodotus 7\.228\)/.test(await text('.mn-epitaph cite')) && best && best.day === 1 && best.score === go.score,
    `${JSON.stringify(go)} best ${JSON.stringify(best)}`);
  await shot('gameover');
  await tap('.mn-gameover [data-act="title"]');
  await step(2);
  check('game over → Title', (await mode()) === 'title' && (await has('.mn-title', 'is-on')));

  // ------------------------------------------------------------------ the Agoge
  await clearLog();
  await sleep(400); // not a double tap on the spot of the previous one
  await tap('.mn-title [data-act="agoge"]');
  await step(5);
  check('The Agoge → training; panel at step I; Narrator speaks', (await mode()) === 'training' && (await has('#training', 'is-on'))
    && (await ev(() => window.__game.ctx.training.stepIndex)) === 0 && (await events('voice')).some((v) => v.speaker === 'Narrator'));
  const dummies = await ev(() => window.__game.ctx.actors.filter((a) => a.role === 'dummy').map((a) => [a.pos.x, a.pos.z]));
  check('training spawns the dummy and the far target', dummies.length === 2, JSON.stringify(dummies));
  await tap('#training .tr-ok');
  await step(90);
  check('confirm → touch skips calibration → step III (right and left)', (await ev(() => window.__game.ctx.training.stepIndex)) === 2);
  for (const [x0, x1] of [[560, 700], [560, 700], [720, 580], [720, 580]]) {
    await swipe(x0, 170, x1, 170);
    await step(40);
  }
  check('swipes R, R, L, L complete the step', await has('#training', 'is-done'));
  await step(90);
  check('→ step IV (overhead)', (await ev(() => window.__game.ctx.training.stepIndex)) === 3);
  await clearLog();
  await tap('#training .tr-demo');
  await step(2);
  await ev(() => new Promise((r) => setTimeout(r, 300))); // the synth module loads asynchronously
  const demoOn = await ev(() => window.__game.ctx.motionDemo);
  // Injected samples are released in real time, so step the game at the pace of the clock (as the
  // RAF loop would) until the demo's ~7 s script has played and the flag clears.
  for (let last = Date.now(), end = last + 20000; Date.now() < end && (await ev(() => window.__game.ctx.motionDemo));) {
    await sleep(100);
    const now = Date.now();
    await step(Math.max(1, Math.min(30, Math.round((now - last) / (1000 / 60)))));
    last = now;
  }
  const gestures = await events('gesture');
  const demoSwings = (await events('swing')).filter((s) => s.src === 'motion');
  check('Demo → motionDemo; injected synth runs through frames + gestures: 2 overhead chops',
    demoOn === true && gestures.filter((g) => g.type === 'slash' && near(g.dir, -Math.PI / 2, 0.4)).length === 2 && demoSwings.length === 2,
    `demo ${demoOn} ${JSON.stringify(gestures.map((g) => [g.type, g.dir && +g.dir.toFixed(2)]))} clicks ${JSON.stringify(await events('click'))}`);
  check('demo ends: flag cleared; demo moves did not count', (await ev(() => window.__game.ctx.motionDemo)) === false
    && (await ev(() => window.__game.ctx.training.stepIndex)) === 3 && !(await has('#training', 'is-done')));
  await swipe(640, 110, 640, 260);
  await step(40);
  await shot('training');

  // ------------------------------------------------------------------ choosing motion on a device without it
  await ev(() => window.__game.ctx.setMode('title'));
  await step(2);
  await tap('.mn-title .mn-seg [data-v="motion"]');
  await step(2);
  check('first Motion → safety notice', await has('.mn-safety', 'is-on'));
  await shot('safety');
  await tap('.mn-safety [data-act="safety-accept"]');
  await step(1);
  const busy = await has('.mn-notice', 'is-busy');
  await sleep(2000); // platform.motion.isSupported(): no devicemotion within 1.5 s
  await step(2);
  const s = await ev(() => Object.assign({}, window.__game.ctx.settings));
  check('no gyroscope → falls back to touch with a message; safetyAccepted saved',
    busy && s.control === 'touch' && s.safetyAccepted === true && (await has('.mn-notice', 'is-on')) && /touch controls/i.test(await text('.mn-notice-p')),
    `control ${s.control}`);
  await shot('nomotion');

  const errs = page.errors.concat(await ev(() => window.__game.errors));
  check('no page errors', errs.length === 0, errs.slice(0, 6).join(' | '));
} finally {
  await l.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
if (failed.length) process.exitCode = 1;
