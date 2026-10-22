// Flow tests for ui/menus.js, ui/hud.js and ui/training.js on the UI sandbox (stub ctx).
// Usage: node spartan/tests/e2e/ui-flows.mjs
import { launch } from './lib.mjs';

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const l = await launch();
async function open(query, size = { width: 844, height: 390, mobile: true }) {
  const page = await l.newPage(size);
  await page.goto(`${l.base}/tests/sandbox/ui-sandbox.html?${query}&manual=1`);
  await page.waitForFunction(() => window.__ui, null, { timeout: 30000 });
  await page.evaluate(() => window.__sandbox.step(2));
  const h = {
    page,
    ev: (fn, arg) => page.evaluate(fn, arg),
    step: (n = 1) => page.evaluate((k) => window.__sandbox.step(k), n),
    log: () => page.evaluate(() => JSON.parse(JSON.stringify(window.__ui.log, (k, v) => (v === Infinity ? 'Inf' : v)))),
    settings: () => page.evaluate(() => Object.assign({}, window.__ui.ctx.settings)),
    mode: () => page.evaluate(() => window.__ui.ctx.mode),
    click: async (sel) => {
      const ok = await page.evaluate((s) => {
        const el = [...document.querySelectorAll(s)].find((e) => e.offsetParent !== null || getComputedStyle(e).position === 'fixed');
        if (!el) return false;
        el.click();
        return true;
      }, sel);
      if (!ok) throw new Error(`no visible ${sel}`);
    },
    // Menu screens fade out with a delayed visibility transition, so a screen counts as shown
    // when it (or its .mn-screen ancestor) carries is-on.
    visible: (sel) => page.evaluate((s) => {
      const el = document.querySelector(s);
      if (!el) return false;
      const scr = el.closest('.mn-screen');
      if (scr && !scr.classList.contains('is-on')) return false;
      const cs = getComputedStyle(el);
      return cs.display !== 'none' && el.getClientRects().length > 0;
    }, sel),
    has: (sel, cls) => page.evaluate(([s, c]) => !!document.querySelector(s) && document.querySelector(s).classList.contains(c), [sel, cls]),
    text: (sel) => page.evaluate((s) => document.querySelector(s).textContent, sel),
    errors: async () => page.errors.concat(await page.evaluate(() => window.__sandbox.errors || [])),
  };
  return h;
}

try {
  // ------------------------------------------------------------------ title
  {
    const t = await open('view=title&best={"score":100,"kills":5,"day":2}');
    await t.page.mouse.click(5, 5);
    let log = await t.log();
    check('first click → audio.unlock()', log.audio.filter((a) => a === 'unlock').length === 1, JSON.stringify(log.audio));
    check('title shows epigraph + attribution', /With it, or on it\./.test(await t.text('.mn-epigraph p')) && /Plutarch, Moralia 241F/.test(await t.text('.mn-epigraph cite')));
    check('Continue offered for the highest day reached', (await t.visible('.mn-continue')) && /Day II/.test(await t.text('.mn-continue small')));
    await t.click('.mn-title [data-act="continue"]');
    log = await t.log();
    check('Continue → battle, day 2', JSON.stringify(log.modes.at(-1)) === JSON.stringify(['battle', { day: 2 }]), JSON.stringify(log.modes.at(-1)));
    await t.ev(() => window.__ui.ctx.setMode('title'));
    await t.click('.mn-title [data-act="fight"]');
    log = await t.log();
    check('Fight → battle, day 1', JSON.stringify(log.modes.at(-1)) === JSON.stringify(['battle', { day: 1 }]));
    await t.ev(() => window.__ui.ctx.setMode('title'));
    await t.click('.mn-title [data-act="agoge"]');
    check('The Agoge → training', (await t.mode()) === 'training');
    await t.ev(() => window.__ui.ctx.setMode('title'));
    check('back() on the title → false', (await t.ev(() => window.__ui.ctx.menus.back())) === false);

    // Settings: every control live-applies and saves.
    await t.click('.mn-title [data-act="settings"]');
    check('settings opens over the title', (await t.visible('.mn-settings')) && !(await t.visible('.mn-title .mn-logo')));
    const before = await t.settings();
    const saves0 = (await t.log()).saves;
    for (const key of ['gyroLook', 'aimAssist', 'leftHanded', 'haptics', 'voice', 'blood', 'helmetView', 'sensorDebug']) {
      await t.ev((k) => document.querySelector(`.mn-field[data-key="${k}"] .mn-toggle`).click(), key);
    }
    let after = await t.settings();
    const flipped = ['gyroLook', 'aimAssist', 'leftHanded', 'haptics', 'voice', 'blood', 'helmetView', 'sensorDebug'].every((k) => after[k] === !before[k]);
    check('toggles flip their settings', flipped);
    await t.ev(() => document.querySelector('.mn-field[data-key="quality"] [data-v="low"]').click());
    after = await t.settings();
    check('quality seg → low, with "applies after restart"', after.quality === 'low' && /after restart/i.test(await t.text('.mn-field[data-key="quality"] small')));
    await t.ev(() => {
      const set = (k, v) => {
        const i = document.querySelector(`.mn-field[data-key="${k}"] input`);
        i.value = String(v);
        i.dispatchEvent(new Event('input'));
        i.dispatchEvent(new Event('change'));
      };
      set('master', 0.4);
      set('music', 0.2);
      set('sfx', 0.75);
      set('sensitivity', 1.35);
    });
    after = await t.settings();
    log = await t.log();
    const sens = await t.ev(() => window.__ui.ctx.gestures.sens);
    check('sliders apply live (volumes → applyVolumes, sensitivity → gestures)', after.master === 0.4 && after.music === 0.2 && after.sfx === 0.75 && after.sensitivity === 1.35 && sens === 1.35 && log.audio.includes('volumes'),
      `${after.master} ${after.music} ${after.sfx} ${after.sensitivity} gestures ${sens}`);
    check('every change saved', log.saves - saves0 >= 13, `${log.saves - saves0} saves`);
    check('slider output shows the value', (await t.text('.mn-field[data-key="master"] output')) === '40%');
    check('back() from settings → title, true', (await t.ev(() => window.__ui.ctx.menus.back())) === true && (await t.visible('.mn-title .mn-logo')));

    // Motion: first time → safety notice → permission → unsupported → touch with a message.
    await t.ev(() => document.querySelector('.mn-title .mn-seg [data-v="motion"]').click());
    check('first Motion → safety notice', await t.visible('.mn-safety'));
    check('safety text: firm grip, strap, space, never let go', /never let go when throwing/i.test(await t.text('.mn-safety')) && /wrist strap/i.test(await t.text('.mn-safety')) && /firm flick is enough/i.test(await t.text('.mn-safety')));
    await t.click('.mn-safety [data-act="safety-accept"]');
    await sleep(50);
    await t.step(1);
    after = await t.settings();
    check('accepting sets safetyAccepted; unsupported → touch + message', after.safetyAccepted === true && after.control === 'touch' && (await t.visible('.mn-notice')) && /touch controls/i.test(await t.text('.mn-notice-p')));
    await t.click('.mn-notice [data-act="back"]');
    check('notice dismissed back to the title', await t.visible('.mn-title .mn-logo'));
    const errs = await t.errors();
    check('title/settings: no page errors', errs.length === 0, errs.join(' | '));
    await t.page.context().close();
  }
  {
    const t = await open('view=title&motion=1');
    await t.ev(() => document.querySelector('.mn-title .mn-seg [data-v="motion"]').click());
    await t.click('.mn-safety [data-act="safety-accept"]');
    await sleep(50);
    await t.step(1);
    const s = await t.settings();
    check('supported → control motion + toast', s.control === 'motion' && (await t.has('.mn-toast', 'is-shown')) && !(await t.visible('.mn-notice')));
    await t.ev(() => document.querySelector('.mn-title .mn-seg [data-v="touch"]').click());
    await t.ev(() => document.querySelector('.mn-title .mn-seg [data-v="motion"]').click());
    await sleep(50);
    check('second time: no safety notice', !(await t.visible('.mn-safety')) && (await t.settings()).control === 'motion');
    await t.ev(() => document.querySelector('.mn-title .mn-seg [data-v="touch"]').click());
    await t.ev(() => { window.__ui.ctx.settings.safetyAccepted = false; });
    await t.ev(() => document.querySelector('.mn-title .mn-seg [data-v="motion"]').click());
    await t.click('.mn-safety [data-act="safety-touch"]');
    check('"Use touch instead" keeps touch', (await t.settings()).control === 'touch' && (await t.visible('.mn-title .mn-logo')));
    await t.page.context().close();
  }

  // ------------------------------------------------------------------ pause and game over
  {
    const t = await open('view=pause');
    check('pause screen shows', (await t.visible('.mn-pause')) && /Day I · Wave II/.test(await t.text('.mn-pause-k')));
    check('back() in pause → false (main resumes)', (await t.ev(() => window.__ui.ctx.menus.back())) === false);
    await t.click('.mn-pause [data-act="resume"]');
    check('Resume → battle', (await t.mode()) === 'battle' && !(await t.visible('.mn-pause')));
    await t.ev(() => window.__ui.ctx.setMode('paused'));
    await t.click('.mn-pause [data-act="settings"]');
    check('pause → settings → back() returns to pause', (await t.ev(() => window.__ui.ctx.menus.back())) === true && (await t.visible('.mn-pause')));
    await t.click('.mn-pause [data-act="restart"]');
    check('Restart asks to confirm', (await t.mode()) === 'paused' && /Tap again/.test(await t.text('.mn-pause [data-act="restart"]')));
    await t.click('.mn-pause [data-act="restart"]');
    let log = await t.log();
    check('Restart (confirmed) → setMode(pausedFrom, { day: waves.day })', JSON.stringify(log.modes.at(-1)) === JSON.stringify(['battle', { day: 1 }]), JSON.stringify(log.modes.at(-1)));
    await t.ev(() => window.__ui.ctx.setMode('paused'));
    await t.click('.mn-pause [data-act="quit"]');
    await t.step(240);
    check('Quit confirmation expires after 3 s', (await t.text('.mn-pause [data-act="quit"]')) === 'Quit');
    await t.click('.mn-pause [data-act="quit"]');
    await t.click('.mn-pause [data-act="quit"]');
    check('Quit (confirmed) → title', (await t.mode()) === 'title');
    // Training pause → Restart restarts training.
    await t.ev(() => { window.__ui.ctx.setMode('training'); window.__ui.ctx.setMode('paused'); });
    check('pause from training shows "The Agoge"', /The Agoge/.test(await t.text('.mn-pause-k')));
    await t.click('.mn-pause [data-act="restart"]');
    await t.click('.mn-pause [data-act="restart"]');
    log = await t.log();
    check('Restart in training → training', log.modes.at(-1)[0] === 'training' && (await t.mode()) === 'training');
    await t.page.context().close();
  }
  {
    const t = await open('view=gameover&best={"score":9000,"kills":90,"day":1}');
    const best = await t.ev(() => window.__ui.store.get('thermopylae.best'));
    check('gameOver writes thermopylae.best (max of each)', best.score === 15420 && best.kills === 90 && best.day === 3, JSON.stringify(best));
    check('game over: stats, best, new record', (await t.text('.mn-st-kills')) === '87' && (await t.text('.mn-st-score')) === '15,420' && (await t.text('.mn-st-best')) === '15,420' && (await t.has('.mn-record', 'is-on')));
    check('epitaph attributed to Simonides (Herodotus 7.228)', /Go tell the Spartans, stranger passing by,/.test(await t.text('.mn-epitaph')) && /Simonides \(Herodotus 7\.228\)/.test(await t.text('.mn-epitaph cite')));
    check('back() in game over → title, true', (await t.ev(() => window.__ui.ctx.menus.back())) === true && (await t.mode()) === 'title');
    await t.page.context().close();
  }

  // ------------------------------------------------------------------ HUD
  {
    const t = await open('view=hud', { width: 1280, height: 720, mobile: false });
    await t.step(3);
    // On-screen chevron sits over the actor's head; the off-screen one is clamped to the edge.
    const ch = await t.ev(() => [...document.querySelectorAll('.hud-chev.is-shown')].map((e) => {
      const m = /translate\(([-\d.]+)px,\s*([-\d.]+)px\)/.exec(e.style.transform);
      return { x: +m[1], y: +m[2], off: e.classList.contains('is-off'), rot: e.querySelector('.hud-chev-arrow').style.transform };
    }));
    const on = ch.find((c) => !c.off);
    const off = ch.find((c) => c.off);
    // Actor at (0.6, −2.4), head + 0.5 m: x = 640 + (0.6/2.4)/(tan 35°·16/9)·640, y = 360 − (0.62/2.4)/tan 35°·360.
    const ex = 640 + (0.25 / (Math.tan((35 * Math.PI) / 180) * (16 / 9))) * 640;
    const ey = 360 - ((2.28 - 1.66) / 2.4 / Math.tan((35 * Math.PI) / 180)) * 360;
    check('wind-up chevron over the on-screen actor\'s head', on && Math.abs(on.x - ex) < 2 && Math.abs(on.y - ey) < 2 && on.rot === 'rotate(0rad)', `${JSON.stringify(on)} want ${ex.toFixed(1)},${ey.toFixed(1)}`);
    const offRot = off && parseFloat(/rotate\(([-\d.]+)rad\)/.exec(off.rot)[1]);
    check('off-screen (left, behind) chevron clamped to the left edge, pointing left-down', off && Math.abs(off.x - 46) < 1 && off.y > 360 && off.y < 674 && offRot > 1.0 && offRot < 1.6, JSON.stringify(off));
    const arc = await t.ev(() => [...document.querySelectorAll('.hud-arc')].map((e) => [e.style.transform, +e.style.opacity]).find((a) => a[1] > 0));
    check('damage arc rotated by −relYaw', arc && /^rotate\(-1\.2(00)?rad\)$/.test(arc[0]), JSON.stringify(arc));
    let log = await t.log();
    check('low HP → vignette, hurt flash, helmet via setOverlay', log.overlay.vignette > 0.6 && log.overlay.hurt > 0.3 && log.overlay.helmet === true, JSON.stringify(log.overlay));
    await t.step(60);
    log = await t.log();
    check('hurt flash decays', log.overlay.hurt === 0 && log.overlay.vignette > 0.6);
    await t.ev(() => { window.__ui.ctx.settings.helmetView = false; });
    await t.step(1);
    log = await t.log();
    check('helmetView off → helmet false', log.overlay.helmet === false);
    check('combo popup shows name and bonus', /Fury of Lakedaimon/.test(await t.text('.hud-combo.is-shown')) && /\+180/.test(await t.text('.hud-combo.is-shown')));
    check('voice subtitle with speaker and Greek', (await t.text('.hud-sub.is-shown .hud-sub-l b')) === 'Leonidas' && (await t.text('.hud-sub.is-shown .hud-sub-g')) === 'ΜΟΛΩΝ ΛΑΒΕ');
    check('score/chain/kills', (await t.text('.hud-score-n')) === '12,840' && (await t.text('.hud-chain')) === '×2.3' && (await t.text('.hud-kills b')) === '31');
    check('spear icons = spearsLeft', (await t.ev(() => document.querySelectorAll('.hud-spear.is-on').length)) === 3);
    // DOM only changes when state changes: settle, then count mutations over 60 frames.
    await t.ev(() => { const u = window.__ui; u.ctx.actors.forEach((a) => { a.state = 'engage'; }); u.ctx.player.focusTarget = null; u.ctx.player.hp = 80; });
    await t.step(360); // let every timer (subtitle, toasts, arcs) run out
    const muts = await t.ev(() => {
      let n = 0;
      const mo = new MutationObserver((list) => { n += list.length; });
      mo.observe(document.getElementById('overlay'), { subtree: true, attributes: true, childList: true, characterData: true });
      window.__sandbox.step(60);
      return new Promise((r) => setTimeout(() => { mo.disconnect(); r(n); }, 0));
    });
    check('idle HUD/controls/menus: 0 DOM mutations over 60 frames', muts === 0, `${muts} mutations`);
    await t.ev(() => { window.__ui.ctx.player.hp = 64; });
    const muts2 = await t.ev(() => {
      let n = 0;
      const mo = new MutationObserver((list) => { n += list.length; });
      mo.observe(document.getElementById('hud'), { subtree: true, attributes: true, childList: true, characterData: true });
      window.__sandbox.step(30);
      return new Promise((r) => setTimeout(() => { mo.disconnect(); r(n); }, 0));
    });
    check('an HP change touches the DOM once', muts2 > 0 && muts2 <= 6, `${muts2} mutations`);
    // Spear cam letterbox; sensor meter only in motion mode.
    await t.ev(() => { window.__ui.ctx.fx.cameraOverridden = true; });
    await t.step(1);
    check('spear cam → letterbox (cinematic HUD, faded controls)', (await t.has('#hud', 'is-cinematic')) && (await t.has('#controls', 'is-cinematic')));
    await t.ev(() => { window.__ui.ctx.fx.cameraOverridden = false; window.__ui.ctx.settings.sensorDebug = true; });
    await t.step(1);
    const dbgTouch = await t.has('.hud-debug', 'is-shown');
    await t.ev(() => { window.__ui.ctx.settings.control = 'motion'; });
    await t.step(1);
    check('sensor meter only with sensorDebug in motion mode', !dbgTouch && (await t.has('.hud-debug', 'is-shown')) && /settled/.test(await t.text('.hud-dbg-txt')));
    // Volley: SHIELDS UP!, steadies when the shield is up, hides at the end.
    await t.ev(() => window.__ui.ctx.combat.volley({ warn: 2.5, duration: 1 }));
    await t.step(2);
    const shown = await t.has('.hud-volley', 'is-shown');
    await t.ev(() => { window.__ui.ctx.input.shieldHeld = true; window.__ui.ctx.player.shieldRaise = 1; });
    await t.step(1);
    const raised = await t.has('.hud-volley', 'is-raised');
    await t.step(60 * 6);
    check('volley warning → SHIELDS UP!, raised state, hides after', shown && raised && !(await t.has('.hud-volley', 'is-shown')));
    // Banners queue behind a day card; respite READY state.
    await t.ev(() => {
      window.__ui.emit('dayStart', { day: 2, title: 'The Second Day', subtitle: 'sub', seconds: 4 });
      window.__ui.emit('waveStart', { day: 2, wave: 1 });
    });
    await t.step(2);
    const first = await t.text('.hud-banner-t');
    await t.step(60 * 4.2);
    const second = await t.text('.hud-banner-t');
    check('wave banner waits for the day card', first === 'The Second Day' && second === 'Wave I', `${first} → ${second}`);
    const errs = await t.errors();
    check('HUD: no page errors', errs.length === 0, errs.join(' | '));
    await t.page.context().close();
  }
  {
    // Narrator lines are not subtitled in the Agoge (its panel shows them); others are.
    const t = await open('view=training&control=touch');
    await t.step(2);
    const narr = await t.has('.hud-sub:last-child', 'is-shown');
    await t.ev(() => window.__ui.emit('voice', { text: 'Hold the line!', speaker: 'Leonidas' }));
    await t.step(2);
    check('Narrator not subtitled in training; Leonidas is', !narr && (await t.has('.hud-sub:last-child', 'is-shown')));
    await t.page.context().close();
  }

  // ------------------------------------------------------------------ the Agoge
  {
    const t = await open('view=training&control=motion');
    let log = await t.log();
    const dummies = log.spawnArgs.filter((a) => a[0] === 'dummy');
    check('start() spawns the dummy (0, −2.6) and far target (1.5, −18)', dummies.length === 2 && dummies[0][1] === 0 && dummies[0][2] === -2.6 && dummies[1][1] === 1.5 && dummies[1][2] === -18 && dummies[1][3].far === true, JSON.stringify(dummies));
    const narr = log.events.filter((e) => e[0] === 'voice' && e[1].speaker === 'Narrator');
    check('step 0 speaks as the Narrator', narr.length === 1 && /Agoge/.test(narr[0][1].text));
    const idx = () => t.ev(() => window.__ui.ctx.training.stepIndex);
    check('step 0 = grip (I\'m ready shown)', (await idx()) === 0 && (await t.visible('#training .tr-ok')));
    await t.click('#training [data-act="ok"]');
    await t.step(90);
    check('confirm → calibrate (motion mode)', (await idx()) === 1 && /Calibrate/.test(await t.text('.tr-title')));
    // Calibration: 5 slashes, median 7.5 → 1.20.
    for (const peak of [6, 7, 7.5, 8, 30]) await t.ev((p) => window.__ui.emit('gesture', { type: 'slash', dir: 0, power: 0.6, peak: p, sweep: 0.5, t: 0 }), peak);
    check('5 slashes → suggestion 1.20×', (await t.has('#training', 'is-calib')) && /1\.20×/.test(await t.text('.tr-calib-t')));
    await t.click('#training [data-act="apply"]');
    let s = await t.settings();
    check('Apply sets + saves sensitivity, tells gestures', s.sensitivity === 1.2 && (await t.ev(() => window.__ui.ctx.gestures.sens)) === 1.2);
    await t.step(90);
    check('→ step 2 (right and left)', (await idx()) === 2);
    // Event-based counting with swing events.
    const swing = (kind, dir) => t.ev(([k, d]) => window.__ui.emit('swing', { kind: k, dir: d, power: 0.7, weapon: 'sword', src: 'touch' }), [kind, dir]);
    await swing('slash', 0);
    await swing('slash', Math.PI / 2); // wrong: hint, no count
    await swing('slash', 0.5);
    await swing('slash', 0); // a third right doesn't count
    await swing('slash', Math.PI);
    await swing('slash', -2.6);
    check('R,R,L,L completes step 2 (extra R and U ignored)', await t.has('#training', 'is-done'));
    await t.step(90);
    // Demo (motion): injects synthesized samples and flags motionDemo until the script ends.
    check('step 3 (overhead) has Demo', (await idx()) === 3 && (await t.visible('#training .tr-demo')));
    await t.click('#training [data-act="demo"]');
    await sleep(300);
    log = await t.log();
    const demoOn = await t.ev(() => window.__ui.ctx.motionDemo);
    check('Demo → motionDemo + inject(synthesize(…))', demoOn === true && log.injected > 300, `injected ${log.injected}`);
    await t.ev(() => window.__ui.emit('swing', { kind: 'slash', dir: -Math.PI / 2, power: 0.7, src: 'motion' }));
    await t.step(60 * 8);
    check('demo moves do not count; flag clears after the script', (await t.ev(() => window.__ui.ctx.motionDemo)) === false && (await idx()) === 3 && !(await t.has('#training', 'is-done')));
    await swing('slash', -2.4); // DL: "straighter"
    const hint = await t.text('.tr-hint');
    await swing('slash', -Math.PI / 2);
    await swing('slash', -Math.PI / 2);
    check('overhead needs D (DL hints "straighter")', /Straighter/.test(hint) && (await t.has('#training', 'is-done')));
    await t.step(90);
    await swing('slash', Math.PI / 2);
    await t.step(90);
    for (let i = 0; i < 3; i++) await swing('thrust', 0);
    await t.step(90);
    check('uppercut ×1, thrust ×3 → step 6 (block)', (await idx()) === 6);
    log = await t.log();
    const tr = log.spawnArgs.find((a) => a[0] === 'levy');
    check('block step spawns the trainer levy (0, −1.6, { trainer: true })', tr && tr[1] === 0 && tr[2] === -1.6 && tr[3].trainer === true && Object.keys(tr[3]).length === 1, JSON.stringify(tr));
    for (let i = 0; i < 3; i++) await t.ev(() => window.__ui.emit('block', { by: 'player', perfect: false, kind: 'melee' }));
    await t.step(90);
    await t.ev(() => window.__ui.emit('block', { by: 'player', perfect: false, kind: 'melee' }));
    const notPerfect = await t.has('#training', 'is-done');
    for (let i = 0; i < 2; i++) await t.ev(() => window.__ui.emit('block', { by: 'player', perfect: true, kind: 'melee' }));
    check('block ×3, then perfect ×2 (a normal block does not count)', !notPerfect && (await t.has('#training', 'is-done')));
    await t.step(90);
    const cleared0 = (await t.log()).cleared;
    await swing('bash', 0);
    await swing('bash', 0);
    await t.step(90);
    log = await t.log();
    check('bash ×2 → volley step; trainer removed, dummies back', (await idx()) === 9 && log.cleared > cleared0 && log.spawnArgs.filter((a) => a[0] === 'dummy').length === 4);
    await t.step(60 * 2.2);
    log = await t.log();
    check('volley step calls combat.volley', log.volleys.length === 1);
    await t.step(60 * 5.8);
    check('volley survived (no arrowHit on the player) → done', await t.has('#training', 'is-done'));
    await t.step(90);
    check('→ spear step', (await idx()) === 10);
    await t.ev(() => window.__ui.emit('weaponSwitch', { weapon: 'spear' }));
    await swing('spearThrust', 0);
    await swing('spearThrust', 0);
    await t.ev(() => window.__ui.emit('spearImpact', { projectile: {}, target: null, point: { x: 6, y: 0, z: -18 }, normal: { x: 0, y: 1, z: 0 }, water: false }));
    const wide = /Wide/.test(await t.text('.tr-hint'));
    await t.ev(() => window.__ui.emit('spearImpact', { projectile: {}, target: null, point: { x: 2.4, y: 0, z: -17.2 }, normal: { x: 0, y: 1, z: 0 }, water: false }));
    check('spear: switch, 2 thrusts, throw within 2 m of the far target', wide && (await t.has('#training', 'is-done')));
    await t.step(90);
    await t.ev(() => window.__ui.emit('combo', { id: 'twin', name: 'Twin Fangs', bonus: 150 }));
    await t.step(90);
    check('Twin Fangs → the Agoge is done (end card)', await t.has('#training', 'is-finished'));
    await t.click('#training [data-act="battle"]');
    log = await t.log();
    check('end card → To the Hot Gates = battle day 1', JSON.stringify(log.modes.at(-1)) === JSON.stringify(['battle', { day: 1 }]) && !(await t.has('#training', 'is-on')));
    const errs = await t.errors();
    check('Agoge: no page errors', errs.length === 0, errs.join(' | '));
    await t.page.context().close();
  }
  {
    const t = await open('view=training&control=touch');
    await t.click('#training [data-act="ok"]');
    await t.step(90);
    check('touch mode skips calibration', (await t.ev(() => window.__ui.ctx.training.stepIndex)) === 2);
    await t.click('#training [data-act="skip"]');
    check('Skip advances at once', (await t.ev(() => window.__ui.ctx.training.stepIndex)) === 3);
    await t.click('#training [data-act="demo"]');
    await sleep(300);
    check('Demo works in touch mode too', (await t.ev(() => window.__ui.ctx.motionDemo)) === true);
    await t.page.context().close();
  }
} finally {
  await l.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
if (failed.length) process.exitCode = 1;
