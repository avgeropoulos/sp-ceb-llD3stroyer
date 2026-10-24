// The portrait viewmodel in the hilt grip (docs-hilt-grip.md §E.2, §H.2 #2):
//   node spartan/tests/e2e/hilt-vm.mjs [outDir] [sizes=412x915,360x800]
// On the real game at each portrait size (?grip=hilt, motion forced after boot: headless Chromium
// has no sensor), with an Immortal standing still and locked, it measures the rig from flat-colour
// mask passes of the vm scene (one object group at a time, into a render target):
//   * the vm camera: fov 80 in portrait;
//   * guard, shield down: the sword is visible (blade ≥ 25% of the height, inside x ∈ [45%, 100%])
//     and the aspis covers ≤ 15% of the screen, at the lower left;
//   * shield up: the aspis covers 35–55% of the screen and the top of its rim sits 46–60% from the top;
//   * a locked foe at 1.6, 1.8 and 2.2 m shows his head above the rim (the rim at his column is
//     below the centre of his head);
//   * nothing of the rig covers the centre 20% × 20% at guard (sword or spear, shield down). With the
//     shield up the rim (46–60%) crosses the box's lower part by design, so there only the arms
//     and the weapon must stay out of it;
//   * a portrait slash crosses the centre at the strike, and its trail shows;
//   * draw calls in the vm pass ≤ 7 (shield up, stuck arrows, a slash trail and the helmet);
//   * the helmet mask: ≤ 6% of the screen darkened by more than 40%, none of it inside the
//     central 70% × 70%;
//   * no on-screen vertex of the rig comes nearer than 0.05 vm units (guard, slashes, thrusts, spear);
//   * the window grip in landscape keeps fov 60 and its guard.
// Screenshots (outDir, default ./hilt-vm-shots): guard, shield up, slash mid-path (R and D), thrust,
// spear guard, spear cock-back, helmet, per size.
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outArg = 'hilt-vm-shots', sizesArg = '412x915,360x800'] = process.argv;
const outDir = path.resolve(outArg);
fs.mkdirSync(outDir, { recursive: true });
const sizes = sizesArg.split(',').map((s) => s.split('x').map(Number));

let failures = 0;
const check = (cond, msg, detail = '') => {
  if (!cond) failures++;
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}${detail ? ` (${detail})` : ''}`);
};
const pct = (x) => `${(100 * x).toFixed(1)}%`;

/** In the page: the measuring kit (window.__hv). */
function installKit() {
  const g = window.__game;
  const c = g.ctx;
  const T = c.THREE;
  const r = c.renderer;
  const sc = c.vm.scene;
  const W = r.domElement.width;
  const H = r.domElement.height;
  const rt = new T.WebGLRenderTarget(W, H);
  const buf = new Uint8Array(W * H * 4);
  const flat = new T.MeshBasicMaterial({ color: 0xffffff });
  const clear = new T.Color();
  const parts = {
    arms: sc.getObjectByName('vmArms'),
    sword: sc.getObjectByName('vmSword'),
    spear: sc.getObjectByName('vmSpear'),
    aspis: sc.getObjectByName('vmAspis'),
    trail: sc.getObjectByName('vmTrail'),
  };
  const overlay = sc.getObjectByName('vmOverlay');

  /** Renders the vm scene into the render target (bottom-up rows) and reads it back. */
  function pass(material) {
    const prevClear = r.getClearColor(clear).getHex();
    const prevAlpha = r.getClearAlpha();
    sc.overrideMaterial = material;
    r.setRenderTarget(rt);
    r.setClearColor(material ? 0x000000 : 0xffffff, 1);
    r.clear();
    r.render(sc, c.vm.camera);
    r.readRenderTargetPixels(rt, 0, 0, W, H, buf);
    r.setRenderTarget(null);
    r.setClearColor(prevClear, prevAlpha);
    sc.overrideMaterial = null;
  }
  /** Shows only the named rig parts for one call (the overlay stays hidden unless 'overlay'). */
  function only(names, fn) {
    const saved = {};
    for (const k in parts) {
      saved[k] = parts[k].visible;
      if (!names.includes(k)) parts[k].visible = false;
    }
    const ov = overlay.visible;
    overlay.visible = names.includes('overlay');
    try {
      return fn();
    } finally {
      for (const k in parts) parts[k].visible = saved[k];
      overlay.visible = ov;
    }
  }
  /** Flat-colour mask of the named parts: area share, bounds (fractions, y from the top), centre box. */
  function mask(names) {
    return only(names, () => {
      pass(flat);
      let n = 0;
      let minX = W;
      let maxX = -1;
      let minY = H;
      let maxY = -1;
      let sx = 0;
      let sy = 0;
      let centre = 0;
      for (let row = 0; row < H; row++) {
        const y = H - 1 - row;
        for (let x = 0; x < W; x++) {
          if (buf[(row * W + x) * 4] < 128) continue;
          n++;
          sx += x;
          sy += y;
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
          if (Math.abs(x + 0.5 - W / 2) <= W * 0.1 && Math.abs(y + 0.5 - H / 2) <= H * 0.1) centre++;
        }
      }
      return {
        area: n / (W * H), centre,
        minX: minX / W, maxX: (maxX + 1) / W, minY: minY / H, maxY: (maxY + 1) / H,
        cx: n ? sx / n / W : 0.5, cy: n ? sy / n / H : 0.5,
      };
    });
  }
  /** Top-most masked row (fraction from the top) in pixel column x (a fraction), or 1 when none. */
  function columnTop(names, fx) {
    return only(names, () => {
      pass(flat);
      const x = Math.min(W - 1, Math.max(0, Math.round(fx * W)));
      for (let y = 0; y < H; y++) if (buf[((H - 1 - y) * W + x) * 4] >= 128) return y / H;
      return 1;
    });
  }
  /** The helmet over white: the share darkened by more than 40%, and how much of it is central. */
  function helmet() {
    c.viewmodel.setOverlay({ helmet: true, vignette: 0, hurt: 0 });
    c.viewmodel.update(0, 0);
    return only(['overlay'], () => {
      pass(null);
      let dark = 0;
      let central = 0;
      for (let row = 0; row < H; row++) {
        const y = H - 1 - row;
        for (let x = 0; x < W; x++) {
          const i = (row * W + x) * 4;
          const lum = 0.2126 * buf[i] + 0.7152 * buf[i + 1] + 0.0722 * buf[i + 2];
          if (lum >= 0.6 * 255) continue;
          dark++;
          if (Math.abs(x + 0.5 - W / 2) <= W * 0.35 && Math.abs(y + 0.5 - H / 2) <= H * 0.35) central++;
        }
      }
      return { dark: dark / (W * H), central };
    });
  }
  /** Draw calls of the vm pass as main.js renders it. */
  function vmCalls() {
    r.info.reset();
    r.render(sc, c.vm.camera);
    return r.info.render.calls;
  }
  /** The foe's head (socket) and its top, in screen fractions through the world camera. */
  function head(actor) {
    const p = new T.Vector3();
    actor.visual.sockets.head.getWorldPosition(p);
    const top = p.clone();
    top.y += 0.1;
    p.project(c.camera);
    top.project(c.camera);
    return { x: (p.x + 1) / 2, y: (1 - p.y) / 2, top: (1 - top.y) / 2 };
  }
  /** Nearest on-screen vertex depth (vm units) over the visible rig meshes. */
  function nearest() {
    const cam = c.vm.camera;
    const tan = Math.tan((cam.fov * Math.PI) / 360);
    const v = new T.Vector3();
    const m = new T.Matrix4();
    const im = new T.Matrix4();
    let min = Infinity;
    let what = '';
    sc.updateMatrixWorld(true);
    sc.traverseVisible((o) => {
      if (!o.isMesh || o === overlay || o === parts.trail) return;
      const pos = o.geometry.attributes.position;
      const k = o.isInstancedMesh ? o.count : 1;
      for (let j = 0; j < k; j++) {
        if (o.isInstancedMesh) {
          o.getMatrixAt(j, im);
          m.multiplyMatrices(o.matrixWorld, im);
        } else m.copy(o.matrixWorld);
        for (let i = 0; i < pos.count; i++) {
          o.getVertexPosition(i, v);
          v.applyMatrix4(m);
          const d = -v.z;
          if (d < min && Math.abs(v.x) <= Math.max(d, 0) * tan * cam.aspect && Math.abs(v.y) <= Math.max(d, 0) * tan) {
            min = d;
            what = o.name;
          }
        }
      }
    });
    return { min, what };
  }
  window.__hv = { mask, columnTop, helmet, vmCalls, head, nearest, W, H };
}

/** A clean battle with one Immortal standing still `dist` m ahead, locked, in the hilt grip. */
function arena(dist) {
  const g = window.__game;
  const c = g.ctx;
  c.settings.control = 'motion';
  c.settings.grip = 'hilt';
  c.settings.helmetView = false;
  c.applyControls();
  g.startBattle(1);
  c.waves.stop();
  c.ai.clear();
  c.combat.reset();
  c.world.setTimeOfDay('morning');
  const foe = g.spawn('immortal', 0, -dist, { delay: 9999 });
  window.__foe = foe;
  g.step(75);
  return { grip: c.input.grip, locked: c.player.lockTarget === foe, vmFov: c.vm.camera.fov, yaw: c.player.yaw };
}

const l = await launch();
try {
  for (const [width, height] of sizes) {
    const tag = `${width}x${height}`;
    console.log(`--- ${tag}`);
    const page = await l.newPage({ width, height, mobile: true, grip: 'hilt' });
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const shot = async (name) => {
      await ev(() => window.__game.render());
      await page.screenshot({ path: path.join(outDir, `${tag}-${name}.png`), timeout: 180000 });
    };
    const step = (n) => ev((k) => window.__game.step(k), n);
    const shield = async (up) => {
      if (up) await page.keyboard.down('KeyK');
      else await page.keyboard.up('KeyK');
      await step(30);
    };
    await page.goto(`${l.game}?manual=1&seed=7&quality=medium&control=motion&grip=hilt&nohud=1`);
    await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });
    await page.waitForTimeout(300); // let the boot-time motion probe settle before forcing motion
    await ev(installKit);

    // ---- guard, shield down ------------------------------------------------------------------------
    const a = await ev(arena, 1.8);
    check(a.grip === 'hilt' && a.locked, `${tag}: hilt grip, the Immortal at 1.8 m locked`, JSON.stringify(a));
    check(a.vmFov === 80, `${tag}: vm camera fov 80 in portrait`, `${a.vmFov}`);
    const guard = await ev(() => ({ sword: window.__hv.mask(['sword']), aspis: window.__hv.mask(['aspis']), rig: window.__hv.mask(['arms', 'sword', 'spear', 'aspis']) }));
    const sw = guard.sword;
    check(sw.maxY - sw.minY >= 0.25 && sw.minX >= 0.45, `${tag} guard: the sword is visible, ≥ 25% of the height, inside x ∈ [45%, 100%]`,
      `height ${pct(sw.maxY - sw.minY)}, x ${pct(sw.minX)}–${pct(sw.maxX)}, y ${pct(sw.minY)}–${pct(sw.maxY)}`);
    check(guard.aspis.area <= 0.15 && guard.aspis.cx < 0.5 && guard.aspis.cy > 0.5, `${tag} guard: the aspis covers ≤ 15%, at the lower left`,
      `${pct(guard.aspis.area)}, centroid (${pct(guard.aspis.cx)}, ${pct(guard.aspis.cy)})`);
    check(guard.rig.centre === 0, `${tag} guard: nothing of the rig in the centre 20% × 20%`, `${guard.rig.centre} px`);
    await shot('guard');

    // ---- shield up ------------------------------------------------------------------------------------
    await shield(true);
    const up = await ev(() => ({ aspis: window.__hv.mask(['aspis']), rig: window.__hv.mask(['arms', 'sword', 'spear']), sword: window.__hv.mask(['sword']) }));
    check(up.aspis.area >= 0.35 && up.aspis.area <= 0.55, `${tag} shield up: the aspis covers 35–55%`, pct(up.aspis.area));
    check(up.aspis.minY >= 0.46 && up.aspis.minY <= 0.6, `${tag} shield up: the top of the rim 46–60% from the top`, pct(up.aspis.minY));
    check(up.rig.centre === 0, `${tag} shield up: nothing of the arms or the sword in the centre 20% × 20%`, `${up.rig.centre} px`);
    console.log(`     shield up: the sword shows ${pct(up.sword.area)} of the screen, y ${pct(up.sword.minY)}–${pct(up.sword.maxY)}`);
    await shot('shield-up');
    for (const dist of [1.6, 1.8, 2.2]) {
      await shield(false);
      await ev(arena, dist);
      await shield(true);
      const h = await ev(() => {
        const hd = window.__hv.head(window.__foe);
        return { head: hd, rim: window.__hv.columnTop(['aspis'], hd.x), locked: window.__game.ctx.player.lockTarget === window.__foe };
      });
      check(h.locked && h.rim > h.head.y, `${tag} shield up, foe at ${dist} m: his head shows above the rim`,
        `head top ${pct(h.head.top)}, centre ${pct(h.head.y)}, rim at his column ${pct(h.rim)}`);
      if (dist === 2.2) await shot('shield-up-2.2m');
    }
    await shield(false);

    // ---- draw calls: shield up with stuck arrows, the helmet and a slash trail -------------------------
    await ev(arena, 1.8);
    await shield(true);
    const calls = await ev(() => {
      const c = window.__game.ctx;
      for (let i = 0; i < 5; i++) c.viewmodel.addStuckArrow();
      c.viewmodel.setOverlay({ helmet: true });
      window.__game.action({ type: 'slash', dir: 0.4, power: 1, src: 'motion' });
      window.__game.step(4);
      return { calls: window.__hv.vmCalls(), trail: c.vm.scene.getObjectByName('vmTrail').visible };
    });
    check(calls.calls <= 7, `${tag}: vm pass draw calls ≤ 7 (shield up, arrows, helmet, slash trail)`, `${calls.calls}, trail ${calls.trail}`);
    await shield(false);

    // ---- slash mid-path ------------------------------------------------------------------------------------
    for (const [name, dir] of [['R', 0], ['D', -Math.PI / 2], ['UL', Math.PI * 0.75]]) {
      await ev(arena, 1.8);
      const s = await ev((d) => {
        const g = window.__game;
        const c = g.ctx;
        g.action({ type: 'slash', dir: d, power: 0.8, src: 'motion' });
        // to the strike: the arm's clock reaches its strike time (the blade at the centre)
        let k = 0;
        for (; k < 12; k++) {
          g.step(1);
          const arm = c.viewmodel.debug.arm;
          if (arm && arm.kind === 'slash' && arm.t >= 0.0666) break;
        }
        const sword = c.vm.scene.getObjectByName('vmSword');
        sword.updateWorldMatrix(true, false);
        const tip = new c.THREE.Vector3(0, 0.594, 0).applyMatrix4(sword.matrixWorld).project(c.vm.camera);
        return { frames: k + 1, tip: [tip.x, tip.y], trail: c.vm.scene.getObjectByName('vmTrail').visible, kind: c.viewmodel.debug.arm && c.viewmodel.debug.arm.kind };
      }, dir);
      check(s.kind === 'slash' && Math.hypot(s.tip[0], s.tip[1]) < 0.12 && s.trail, `${tag} slash ${name}: the tip crosses the centre at the strike, trail shown`,
        `tip ndc (${s.tip[0].toFixed(3)}, ${s.tip[1].toFixed(3)}) after ${s.frames} frames`);
      await shot(`slash-${name}`);
    }

    // ---- thrust, spear guard, spear cock-back ---------------------------------------------------------
    await ev(arena, 1.8);
    await ev(() => {
      window.__game.action({ type: 'thrust', power: 0.8, src: 'motion' });
      window.__game.step(7);
    });
    await shot('thrust');
    await ev(arena, 1.8);
    await ev(() => {
      window.__game.action({ type: 'switchWeapon', weapon: 'spear' });
      window.__game.step(60);
    });
    const spear = await ev(() => ({ spear: window.__hv.mask(['spear']), rig: window.__hv.mask(['arms', 'sword', 'spear', 'aspis']), shown: window.__game.ctx.viewmodel.debug.shown }));
    check(spear.shown === 'spear' && spear.spear.area > 0.004 && spear.rig.centre === 0, `${tag} spear guard: the spear shows, nothing of the rig in the centre 20% × 20%`,
      `spear ${pct(spear.spear.area)} x ${pct(spear.spear.minX)}–${pct(spear.spear.maxX)} y ${pct(spear.spear.minY)}–${pct(spear.spear.maxY)}, centre ${spear.rig.centre} px`);
    await shot('spear');
    await shield(true);
    await shot('spear-shield-up');
    await shield(false);
    const cock = await ev(() => {
      const c = window.__game.ctx;
      c.viewmodel.play('throwWindup');
      window.__game.step(20);
      return { arm: c.viewmodel.debug.arm && c.viewmodel.debug.arm.kind, spear: window.__hv.mask(['spear']) };
    });
    check(cock.arm === 'throwWindup' && cock.spear.area > 0, `${tag} spear cock-back: the drawn-back spear shows`,
      `${cock.arm}, spear ${pct(cock.spear.area)} x ${pct(cock.spear.minX)}–${pct(cock.spear.maxX)} y ${pct(cock.spear.minY)}–${pct(cock.spear.maxY)}`);
    await shot('spear-cockback');

    // ---- nearest vertex through the one-shots ---------------------------------------------------------------
    await ev(arena, 1.8);
    const near = await ev(() => {
      const g = window.__game;
      const c = g.ctx;
      const hv = window.__hv;
      let worst = { min: Infinity, what: '', when: '' };
      // Geometry only: the frames are not drawn (software GL would take minutes for them).
      const render = c.renderer.render;
      c.renderer.render = () => {};
      const watch = (label, frames) => {
        for (let i = 0; i < frames; i++) {
          g.step(1);
          const n = hv.nearest();
          if (n.min < worst.min) worst = { min: n.min, what: n.what, when: `${label} f${i}` };
        }
      };
      try {
        for (let i = 0; i < 8; i++) {
          const dir = -Math.PI + (i * Math.PI) / 4;
          g.action({ type: 'slash', dir, power: 0.9, src: 'motion' });
          watch(`slash ${Math.round((dir * 180) / Math.PI)}`, 22);
          g.action({ type: 'slash', dir, power: 0.9, src: 'test' });
          watch(`touch slash ${Math.round((dir * 180) / Math.PI)}`, 22);
        }
        g.action({ type: 'thrust', power: 0.9, src: 'motion' });
        watch('thrust', 26);
        g.action({ type: 'thrust', power: 0.9, src: 'test' });
        watch('touch thrust', 26);
        g.action({ type: 'switchWeapon', weapon: 'spear' });
        watch('draw spear', 40);
        g.action({ type: 'thrust', power: 0.9, src: 'motion' });
        watch('spear thrust', 30);
        g.action({ type: 'slash', dir: 2.4, power: 0.9, src: 'motion' });
        watch('spear slash', 22);
        c.viewmodel.play('throwWindup');
        watch('cock-back', 20);
      } finally {
        c.renderer.render = render;
      }
      return worst;
    });
    check(near.min > 0.05, `${tag}: no on-screen vertex of the rig nearer than 0.05 vm units`, `${near.min.toFixed(3)} (${near.what}, ${near.when})`);

    // ---- helmet ----------------------------------------------------------------------------------------------
    await ev(arena, 1.8);
    const hm = await ev(() => window.__hv.helmet());
    check(hm.dark <= 0.06 && hm.central === 0, `${tag} helmet: ≤ 6% darkened by more than 40%, none inside the central 70% × 70%`,
      `${pct(hm.dark)}, ${hm.central} px central`);
    await shot('helmet');

    const errs = page.errors.concat(await ev(() => window.__game.errors));
    check(errs.length === 0, `${tag}: no page errors`, errs.slice(0, 3).join(' | '));
    await page.context().close();
  }

  // ---- the window grip in landscape is unchanged -------------------------------------------------------
  console.log('--- 915x412 window grip');
  const page = await l.newPage({ width: 915, height: 412, mobile: true });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low&nohud=1`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });
  const win = await page.evaluate(() => {
    const g = window.__game;
    const c = g.ctx;
    g.startBattle(1);
    g.step(30);
    const d = c.viewmodel.debug;
    return { grip: c.input.grip, fov: c.vm.camera.fov, portrait: d.portrait, hand: d.hand.p.toArray(), low: d.poses.swordLow.p.toArray() };
  });
  check(win.fov === 60 && win.portrait === false && win.grip !== 'hilt', 'landscape: vm fov 60, not portrait', JSON.stringify({ grip: win.grip, fov: win.fov }));
  check(Math.abs(win.hand[0] - win.low[0] - Math.min(0.025, Math.max(-0.03, (915 / 412 - 1.78) * 0.05))) < 1e-6 && Math.abs(win.hand[1] - win.low[1]) < 1e-6,
    'landscape: the sword guard is the landscape pose', win.hand.map((x) => x.toFixed(3)).join(', '));
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  check(errs.length === 0, 'landscape: no page errors', errs.slice(0, 3).join(' | '));
} finally {
  await l.close();
}
console.log(failures ? `${failures} hilt viewmodel check(s) FAILED` : 'all hilt viewmodel checks passed');
console.log(`screenshots: ${outDir}`);
if (failures) process.exitCode = 1;
