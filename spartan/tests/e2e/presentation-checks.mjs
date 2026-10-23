// Regression checks for the presentation fixes (art review C) in the real game:
//   - death: the viewmodel plays a fall, then hides; the HUD drops its transients (the day banner)
//     and fades out; a new battle brings both back;
//   - dory: the idle overhand fist (and the thrust) stays on screen on phone aspects;
//   - helmet: the Corinthian overlay darkens at most ~4% of the screen by more than 40%;
//   - hurt arc: the red holds a floor over its backing and thins as it fades;
//   - title: the epigraph attribution and the Greek kicker use the brighter colours.
// Usage: node spartan/tests/e2e/presentation-checks.mjs [WxH=915x412]
import { launch } from './lib.mjs';

const [width, height] = (process.argv[2] || '915x412').split('x').map(Number);
const l = await launch();
const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};

try {
  const page = await l.newPage({ width, height });
  const errors = async () => page.errors.concat(await page.evaluate(() => window.__game.errors));
  const step = (n, dt = 1 / 60) => page.evaluate(([n, dt]) => window.__game.step(n, dt), [n, dt]);
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  check((await errors()).length === 0, 'boots without errors');

  // ---- Title ----
  await page.evaluate(() => window.__game.ctx.setMode('title'));
  await step(30);
  const title = await page.evaluate(() => {
    const cs = (s) => getComputedStyle(document.querySelector(s));
    const root = getComputedStyle(document.documentElement);
    const rgb = (v) => {
      const d = document.createElement('i');
      d.style.color = v;
      document.body.appendChild(d);
      const c = getComputedStyle(d).color;
      d.remove();
      return c;
    };
    return {
      cite: cs('.mn-epigraph cite').color,
      citeShadow: cs('.mn-epigraph cite').textShadow,
      citeSize: parseFloat(cs('.mn-epigraph cite').fontSize),
      greek: cs('.mn-greek').color,
      parchment: rgb(root.getPropertyValue('--parchment').trim()),
      bronzeHi: rgb(root.getPropertyValue('--bronze-hi').trim()),
    };
  });
  check(title.cite === title.parchment && title.citeShadow !== 'none' && title.citeSize >= 11, `title attribution: parchment, shadowed, ≥ 11 px ${JSON.stringify(title)}`);
  check(title.greek === title.bronzeHi, `title Greek kicker in bronze-hi (${title.greek})`);

  // ---- Sandbox battle ----
  const sandbox = () =>
    page.evaluate(() => {
      const g = window.__game;
      g.startBattle(1);
      g.ctx.waves.stop();
      g.ctx.ai.clear();
      g.ctx.combat.reset();
      g.ctx.world.setTimeOfDay('morning');
      g.ctx.hud.reset();
      g.step(30);
    });
  await sandbox();

  // ---- Dory: the fist on screen at idle (low and raised) and at the thrust's peak ----
  const fist = () =>
    page.evaluate(() => {
      const c = window.__game.ctx;
      const o = c.vm.scene.getObjectByName('vmSpear');
      const v = new c.THREE.Vector3();
      o.getWorldPosition(v);
      v.project(c.vm.camera);
      return { x: +v.x.toFixed(2), y: +v.y.toFixed(2), visible: o.visible };
    });
  const onScreen = (p) => p.visible && Math.abs(p.x) <= 0.9 && Math.abs(p.y) <= 0.9;
  // The resize event is dispatched asynchronously: fire it and wait until the cameras follow.
  const resize = async (w, h) => {
    await page.setViewportSize({ width: w, height: h });
    await page.evaluate(() => window.dispatchEvent(new Event('resize')));
    await page.waitForFunction((a) => Math.abs(window.__game.ctx.vm.camera.aspect - a) < 1e-3, w / h, { timeout: 30000 });
    await step(10);
  };
  await page.evaluate(() => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }));
  await step(60);
  for (const [w, h] of [[width, height], [844, 390], [960, 540]]) {
    await resize(w, h);
    const idle = await fist();
    await page.evaluate(() => (window.__game.ctx.input.shieldHeld = true));
    await step(30);
    const raised = await fist();
    await page.evaluate(() => (window.__game.ctx.input.shieldHeld = false));
    await step(30);
    check(onScreen(idle) && onScreen(raised) && idle.y > 0, `dory fist on screen, upper half, at ${w}x${h}: idle ${JSON.stringify(idle)} raised ${JSON.stringify(raised)}`);
  }
  await resize(width, height);
  await page.evaluate(() => window.__game.action({ type: 'thrust', power: 0.9 }));
  let thrustOk = true;
  const thrustPts = [];
  for (let i = 0; i < 6; i++) {
    await step(2);
    const p = await fist();
    thrustPts.push(p);
    thrustOk = thrustOk && onScreen(p);
  }
  check(thrustOk, `dory fist on screen through the thrust ${JSON.stringify(thrustPts)}`);
  await step(60);

  // ---- Helmet overlay ----
  const grab = () =>
    page.evaluate(() => {
      const g = window.__game;
      g.render();
      const c = document.getElementById('scene');
      const t = document.createElement('canvas');
      t.width = c.width;
      t.height = c.height;
      const g2 = t.getContext('2d');
      g2.drawImage(c, 0, 0);
      return Array.from(g2.getImageData(0, 0, t.width, t.height).data);
    });
  await page.evaluate(() => {
    window.__game.ctx.settings.helmetView = true;
    window.__game.step(10);
  });
  const withMask = await grab();
  await page.evaluate(() => (window.__game.ctx.vm.scene.getObjectByName('vmOverlay').visible = false));
  const bare = await grab();
  await page.evaluate(() => (window.__game.ctx.vm.scene.getObjectByName('vmOverlay').visible = true));
  {
    const lum = (d, i) => 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
    let n = 0;
    let d40 = 0;
    let d80 = 0;
    for (let i = 0; i < bare.length; i += 4) {
      const lb = lum(bare, i);
      if (lb < 8) continue;
      n++;
      const r = lum(withMask, i) / lb;
      if (r < 0.6) d40++;
      if (r < 0.2) d80++;
    }
    const p40 = (100 * d40) / n;
    const p80 = (100 * d80) / n;
    const u = await page.evaluate(() => {
      const m = window.__game.ctx.vm.scene.getObjectByName('vmOverlay').material.uniforms;
      return { vignette: m.uVignette.value, hurt: m.uHurt.value, helmet: m.uHelmet.value, aspect: +m.uAspect.value.toFixed(3) };
    });
    check(p40 <= 4.5 && p80 <= 0.5, `helmet overlay darkens ${p40.toFixed(1)}% by > 40% (≤ 4.5) and ${p80.toFixed(1)}% by > 80% (≤ 0.5) ${JSON.stringify(u)}`);
  }

  // ---- Hurt arc ----
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.player.damage(14, { from: null, kind: 'melee', relYaw: 0.35 });
  });
  const arc = () =>
    page.evaluate(() => {
      const a = [...document.querySelectorAll('.hud-arc')].find((e) => e.style.opacity === '1');
      if (!a) return null;
      const [back, red] = a.querySelectorAll('path');
      return { back: +back.getAttribute('opacity'), red: +red.getAttribute('opacity'), w: +red.getAttribute('stroke-width'), bw: +back.getAttribute('stroke-width') };
    });
  await step(6);
  const a0 = await arc();
  await step(54);
  const a1 = await arc();
  check(a0 && a1 && a0.red > 0.7 && a1.red >= 0.4 && a1.back >= a1.red && a1.w < a0.w && a1.bw > a1.w,
    `hurt arc: red ≥ 0.4 at 1 s over a denser backing, thinning ${JSON.stringify({ a0, a1 })}`);
  await step(40);
  check((await arc()) === null, 'hurt arc gone after its life');

  // ---- Death: fall, hide, and back on a new battle ----
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.hud.reset();
    g.startBattle(1);
    g.step(60);
  });
  const facts = () =>
    page.evaluate(() => {
      const g = window.__game;
      const d = g.ctx.viewmodel.debug;
      return {
        rig: g.ctx.vm.scene.getObjectByName('vmRig').visible,
        arm: d.arm && d.arm.kind,
        fallen: d.fallen,
        hudFallen: document.getElementById('hud').classList.contains('is-fallen'),
        banner: document.querySelector('.hud-banner').classList.contains('is-shown'),
      };
    });
  const before = await facts();
  check(before.banner && before.rig && !before.fallen, `battle start: banner up, rig shown ${JSON.stringify(before)}`);
  await page.evaluate(() => window.__game.ctx.player.damage(1000, { from: null, kind: 'melee' }));
  await step(6);
  const falling = await facts();
  check(falling.arm === 'fall' && falling.rig && falling.hudFallen && !falling.banner, `on death: fall one-shot, HUD fading, banner gone ${JSON.stringify(falling)}`);
  await page.evaluate(() => window.__game.ctx.viewmodel.play('slash', { dir: 0, power: 1 }));
  await step(1);
  check((await facts()).arm === 'fall', 'no attack one-shot while fallen');
  await step(40);
  const fallen = await facts();
  check(!fallen.rig && fallen.fallen, `after the fall the rig is hidden ${JSON.stringify(fallen)}`);
  await page.evaluate(() => {
    const g = window.__game;
    g.step(240, 1 / 60);
    g.startBattle(1);
    g.step(30);
  });
  const again = await facts();
  check(again.rig && !again.fallen && !again.hudFallen, `a new battle shows the rig and the HUD again ${JSON.stringify(again)}`);

  const errs = await errors();
  check(errs.length === 0, `no page errors ${JSON.stringify(errs).slice(0, 1000)}`);
  await page.context().close();
} catch (err) {
  failures.push(String((err && err.stack) || err));
  console.error(err);
} finally {
  await l.close();
}
if (failures.length) {
  console.log(`\n${failures.length} check(s) failed`);
  process.exitCode = 1;
} else console.log('\nall presentation checks passed');
