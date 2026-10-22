// Scripted viewmodel checks: node spartan/tests/e2e/viewmodel-checks.mjs [query]
// Loads the viewmodel sandbox and verifies, against DESIGN.md §11:
//   * the vm pass stays within its draw-call budget (≤ 15) in the worst case;
//   * one-shots, shield jolts and arrow wobbles freeze while pdt is 0 (hit-stop) while sway and
//     breathing keep running on dt;
//   * no triangle crosses the near plane inside the view at 19.5:9, 16:9 and 4:3 through every one-shot;
//   * the vm sun follows world.sunDirection in camera space, the env map and rotation are wired,
//     lights cast no shadows, there is no fog, and the overlay draws last without depth test;
//   * the event wiring (swing, block, playerHurt, weaponSwitch, spear redraw, arrowHit, respite) and the API.
import { launch } from './lib.mjs';

const query = process.argv[2] || '';
const l = await launch();
let failures = 0;
const fail = (msg) => {
  failures++;
  console.error('FAIL', msg);
};
const ok = (cond, msg) => (cond ? console.log('ok  ', msg) : fail(msg));

try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.base}/tests/sandbox/viewmodel-sandbox.html?manual=1${query}`);
  await page.waitForFunction(() => window.__vm, null, { timeout: 120000 });

  // ---- Draw calls -------------------------------------------------------------------------------
  const calls = await page.evaluate(() => {
    const V = window.__vm;
    const out = {};
    V.shot({ setup: { weapon: 'sword' } });
    out.idle = V.stats();
    V.shot({
      setup: { weapon: 'sword', raise: 1, arrows: 8, overlay: { helmet: true, vignette: 0.8, hurt: 0.5 } },
      events: [['swing', { kind: 'slash', dir: 0.4, power: 1, weapon: 'sword', src: 'test' }]],
      at: 0.1,
    });
    out.worst = V.stats();
    out.trailVisible = V.ctx.vm.scene.getObjectByName('vmTrail').visible;
    return out;
  });
  console.log('vm pass: idle', JSON.stringify(calls.idle), '\n        worst', JSON.stringify(calls.worst));
  ok(calls.worst.vmCalls <= 15, `vm draw calls ≤ 15 (worst case ${calls.worst.vmCalls}, idle ${calls.idle.vmCalls})`);
  ok(calls.trailVisible, 'slash trail visible at the strike');

  // ---- Hit-stop freeze ----------------------------------------------------------------------------
  const freeze = await page.evaluate(() => {
    const V = window.__vm;
    const t = V.ctx.time;
    const sway = V.ctx.vm.scene.getObjectByName('vmRig').children[0];
    const snap = () => {
      const d = V.vm.debug;
      return {
        armT: d.arm ? d.arm.t : null,
        hand: d.hand.p.toArray().concat(d.hand.q.toArray()),
        shield: d.shieldPose.p.toArray().concat(d.shieldPose.q.toArray()),
        sway: sway.quaternion.toArray().concat(sway.position.toArray()),
        arrow: Array.from(V.ctx.vm.scene.getObjectByName('vmAspis').children.find((c) => c.isInstancedMesh).instanceMatrix.array.slice(0, 16)),
      };
    };
    V.shot({ setup: { weapon: 'sword', raise: 1 } });
    V.emit('swing', { kind: 'slash', dir: 1.2, power: 0.7, weapon: 'sword', src: 'test' });
    V.emit('block', { by: 'player', perfect: false, kind: 'melee' });
    V.vm.addStuckArrow();
    V.tick(3);
    const a = snap();
    t.hitStop = 0.2;
    V.tick(8); // 8 frames of pdt = 0
    const b = snap();
    t.hitStop = 0;
    V.tick(2);
    const c = snap();
    const same = (x, y) => x.length === y.length && x.every((v, i) => Math.abs(v - y[i]) < 1e-9);
    return {
      armFrozen: a.armT === b.armT, handFrozen: same(a.hand, b.hand), shieldFrozen: same(a.shield, b.shield),
      arrowFrozen: same(a.arrow, b.arrow), swayMoved: !same(a.sway, b.sway), resumed: c.armT > b.armT && !same(b.hand, c.hand),
      armT: [a.armT, b.armT, c.armT],
    };
  });
  ok(freeze.armFrozen && freeze.handFrozen, `slash frozen while pdt = 0 (t ${freeze.armT.join(' → ')})`);
  ok(freeze.shieldFrozen, 'shield block jolt frozen while pdt = 0');
  ok(freeze.arrowFrozen, 'stuck-arrow wobble frozen while pdt = 0');
  ok(freeze.swayMoved, 'breathing and idle sway keep running on dt during hit-stop');
  ok(freeze.resumed, 'one-shots resume when pdt returns');

  // ---- Slash paths as rendered (after blending, sway and the rig scale) ---------------------------
  const paths = await page.evaluate(() => {
    const V = window.__vm;
    const { THREE } = V.ctx;
    const cam = V.ctx.vm.camera;
    const sword = V.ctx.vm.scene.getObjectByName('vmSword');
    const spear = V.ctx.vm.scene.getObjectByName('vmSpear');
    // Blade tip / spear head in the weapon's grip frame (meshes.js SWORD.tipY, SPEAR.headTip).
    const swordTip = new THREE.Vector3(0, 0.594, 0);
    const spearTip = new THREE.Vector3(0, -1.36, 0);
    const p = new THREE.Vector3();
    const tipAt = (obj, local, t) => {
      V.tick(Math.round(t * 60));
      obj.updateWorldMatrix(true, false);
      p.copy(local).applyMatrix4(obj.matrixWorld);
      const world = p.clone();
      const ndc = p.project(cam);
      return { world, ndc };
    };
    const out = [];
    const dirs = [0, 45, 90, 135, 180, -135, -90, -45, 20, 160, -70];
    for (const deg of dirs) {
      const dir = (deg * Math.PI) / 180;
      const d = [Math.cos(dir), Math.sin(dir)];
      const sample = (weapon, obj, local, t) => {
        V.shot({ setup: { weapon } });
        V.ctx.player.yaw = 0;
        V.emit('swing', { kind: 'slash', dir, power: 0.8, weapon, src: 'test' });
        return tipAt(obj, local, t);
      };
      // Sword: at the strike the tip is at the view centre in the plane z = -0.55 (sway aside).
      const s = sample('sword', sword, swordTip, 0.1);
      const a = sample('sword', sword, swordTip, 0.0833);
      const b = sample('sword', sword, swordTip, 0.1167);
      const ax = (b.ndc.x - a.ndc.x) * cam.aspect;
      const ay = b.ndc.y - a.ndc.y;
      const cosS = (ax * d[0] + ay * d[1]) / Math.hypot(ax, ay);
      // Spear: the head sweeps along dir too.
      const h0 = sample('spear', spear, spearTip, 0.0833);
      const h1 = sample('spear', spear, spearTip, 0.1167);
      const hx = (h1.ndc.x - h0.ndc.x) * cam.aspect;
      const hy = h1.ndc.y - h0.ndc.y;
      const cosH = (hx * d[0] + hy * d[1]) / Math.hypot(hx, hy);
      out.push({ deg, tip: [s.world.x, s.world.y, s.world.z], cosS, cosH });
    }
    return out;
  });
  const worstTip = Math.max(...paths.map((r) => Math.hypot(r.tip[0], r.tip[1], r.tip[2] + 0.55)));
  ok(worstTip < 0.03, `sword tip at the strike is at (0, 0, -0.55) vm (worst offset ${worstTip.toFixed(3)}, sway and breathing included)`);
  const worstS = Math.min(...paths.map((r) => r.cosS));
  ok(worstS > 0.95, `sword tip moves along dir through the strike, 11 directions (min cos ${worstS.toFixed(3)})`);
  const worstH = Math.min(...paths.map((r) => r.cosH));
  ok(worstH > 0.8, `spear head sweeps along dir, 11 directions (min cos ${worstH.toFixed(3)})`);

  // ---- Lights, environment, overlay ---------------------------------------------------------------
  const env = await page.evaluate(() => {
    const V = window.__vm;
    const { ctx } = V;
    V.shot({});
    V.player.yaw = 1.1;
    V.player.pitch = 0.25;
    V.tick(1); // the sandbox applies yaw/pitch to ctx.camera after vm.update…
    V.tick(1); // …so the second update sees it
    const sc = ctx.vm.scene;
    const lights = [];
    sc.traverse((o) => o.isLight && lights.push(o));
    const sun = lights.find((o) => o.isDirectionalLight);
    const expect = ctx.world.sunDirection.clone().applyQuaternion(ctx.camera.quaternion.clone().invert()).normalize();
    const got = sun.position.clone().sub(sun.target.position).normalize();
    // Env rotation: the shader samples R(E)^-1 · r, which must equal camera.quaternion · r.
    const E = new ctx.THREE.Matrix4().makeRotationFromEuler(sc.environmentRotation).invert();
    const Q = new ctx.THREE.Matrix4().makeRotationFromQuaternion(ctx.camera.quaternion);
    let envErr = 0;
    for (let i = 0; i < 16; i++) envErr = Math.max(envErr, Math.abs(E.elements[i] - Q.elements[i]));
    const ov = sc.getObjectByName('vmOverlay');
    let maxOrder = -Infinity;
    sc.traverse((o) => { if (o !== ov && o.isMesh) maxOrder = Math.max(maxOrder, o.renderOrder); });
    const cam = ctx.vm.camera;
    return {
      sunErr: got.distanceTo(expect), envErr,
      envSet: sc.environment === ctx.world.envMap, fog: sc.fog,
      shadows: lights.some((o) => o.castShadow), lightKinds: lights.map((o) => o.type),
      overlayLast: ov.renderOrder > maxOrder, overlayDepth: ov.material.depthTest === false && ov.material.depthWrite === false,
      cam: { fov: cam.fov, pos: cam.position.toArray(), quat: cam.quaternion.toArray() },
    };
  });
  ok(env.sunErr < 1e-4, `vm sun = world.sunDirection in camera space (err ${env.sunErr.toExponential(1)})`);
  ok(env.envErr < 1e-5, `env map rotation follows the camera (err ${env.envErr.toExponential(1)})`);
  ok(env.envSet && env.fog === null, 'vm.scene.environment = world.envMap, no fog');
  ok(!env.shadows, `vm lights cast no shadows (${env.lightKinds.join(', ')})`);
  ok(env.overlayLast && env.overlayDepth, 'overlay draws last with depthTest/depthWrite off');
  ok(env.cam.fov === 60 && env.cam.pos.every((v) => v === 0) && env.cam.quat.join() === '0,0,0,1', 'vm camera: fov 60 at the origin, identity rotation');

  // ---- Events and API ----------------------------------------------------------------------------
  const api = await page.evaluate(() => {
    const V = window.__vm;
    const r = {};
    const sword = V.ctx.vm.scene.getObjectByName('vmSword');
    const spear = V.ctx.vm.scene.getObjectByName('vmSpear');
    V.shot({});
    for (let i = 0; i < 11; i++) V.emit('arrowHit', { target: 'shield', point: null });
    V.emit('arrowHit', { target: 'player', point: null });
    r.arrows = V.vm.debug.arrows;
    V.emit('respite', { day: 1 });
    r.arrowsAfterRespite = V.vm.debug.arrows;
    V.emit('swing', { kind: 'thrust', power: 0.5, weapon: 'sword', src: 'test' });
    r.thrust = V.vm.debug.arm && V.vm.debug.arm.kind;
    V.emit('swing', { kind: 'bash', power: 0.5, weapon: 'sword', src: 'test' });
    r.bash = V.vm.debug.arm && V.vm.debug.arm.kind;
    V.emit('playerHurt', { damage: 10, hp: 50, relYaw: -0.5, kind: 'melee' });
    V.emit('block', { by: {}, perfect: false, kind: 'melee' }); // an ally's block: ignored
    V.tick(30);
    // Weapon switch to the spear plays the draw and shows the spear afterwards.
    V.setup({ weapon: 'spear' });
    V.emit('weaponSwitch', { weapon: 'spear' });
    r.draw = V.vm.debug.arm && V.vm.debug.arm.kind;
    V.tick(40);
    r.afterDraw = { shown: V.vm.debug.shown, sword: sword.visible, spear: spear.visible };
    // Motion wind-up held, then the motion throw releases and hides the spear.
    V.vm.play('throwWindup');
    V.tick(10);
    r.windup = V.vm.debug.arm && V.vm.debug.arm.kind;
    V.emit('swing', { kind: 'throw', power: 0.8, weapon: 'spear', src: 'motion' });
    V.tick(4);
    r.released = { kind: V.vm.debug.arm && V.vm.debug.arm.kind, shown: V.vm.debug.shown, spear: spear.visible };
    // player: hasSpearInHand false after the throw, then the silent redraw (true again).
    V.setup({ hasSpear: false });
    V.tick(40);
    r.empty = { shown: V.vm.debug.shown, sword: sword.visible, spear: spear.visible };
    V.setup({ hasSpear: true });
    V.tick(1);
    r.redraw = V.vm.debug.arm && V.vm.debug.arm.kind;
    V.tick(40);
    r.redrawn = V.vm.debug.shown;
    // Touch throw: wind-up, then release at TIMING.throw.windup.
    V.emit('swing', { kind: 'throw', power: 0.5, weapon: 'spear', src: 'touch' });
    V.tick(4);
    r.touchWindup = V.vm.debug.arm && V.vm.debug.arm.kind;
    V.tick(6);
    r.touchRelease = V.vm.debug.arm && V.vm.debug.arm.kind;
    // Out of spears: player switches to the sword and emits weaponSwitch.
    V.setup({ weapon: 'sword', hasSpear: false });
    V.emit('weaponSwitch', { weapon: 'sword' });
    V.tick(40);
    r.backToSword = V.vm.debug.shown;
    // Visibility and reset.
    V.vm.setVisible(false);
    V.tick(1);
    r.hidden = !V.ctx.vm.scene.getObjectByName('vmRig').visible;
    V.vm.setVisible(true);
    V.vm.setOverlay({ vignette: 0.5, hurt: 0.5, helmet: true });
    V.vm.addStuckArrow();
    V.emit('swing', { kind: 'slash', dir: 0, power: 0.5, weapon: 'sword', src: 'test' });
    V.vm.reset();
    V.tick(1);
    r.reset = { arm: V.vm.debug.arm, arrows: V.vm.debug.arrows, overlay: V.ctx.vm.scene.getObjectByName('vmOverlay').visible };
    r.errors = window.__sandbox.errors.slice();
    return r;
  });
  ok(api.arrows === 8 && api.arrowsAfterRespite === 0, `arrowHit shield caps at 8 (${api.arrows}), respite clears (${api.arrowsAfterRespite})`);
  ok(api.thrust === 'thrust' && api.bash === 'bash', `swing → thrust (${api.thrust}) / bash (${api.bash})`);
  ok(api.draw === 'draw' && api.afterDraw.shown === 'spear' && api.afterDraw.spear && !api.afterDraw.sword, `weaponSwitch → draw → spear (${JSON.stringify(api.afterDraw)})`);
  ok(api.windup === 'throwWindup', `play('throwWindup') (${api.windup})`);
  ok(api.released.kind === 'throwRelease' && api.released.shown === null && !api.released.spear, `motion throw releases and hides the spear (${JSON.stringify(api.released)})`);
  ok(api.empty.shown === null && !api.empty.spear && !api.empty.sword, `empty hand while hasSpear is false (${JSON.stringify(api.empty)})`);
  ok(api.redraw === 'draw' && api.redrawn === 'spear', `silent spear redraw plays 'draw' (${api.redraw} → ${api.redrawn})`);
  ok(api.touchWindup === 'throwWindup' && api.touchRelease === 'throwRelease', `touch throw: wind-up then release (${api.touchWindup} → ${api.touchRelease})`);
  ok(api.backToSword === 'sword', `out of spears → sword (${api.backToSword})`);
  ok(api.hidden, 'setVisible(false) hides the rig');
  ok(!api.reset.arm && api.reset.arrows === 0 && api.reset.overlay === true, `reset clears one-shots and arrows, keeps the helmet setting (${JSON.stringify(api.reset)})`);
  ok(api.errors.length === 0, `no handler errors (${api.errors.join('; ')})`);

  // ---- Near plane --------------------------------------------------------------------------------
  const near = await page.evaluate(() => {
    const V = window.__vm;
    const { THREE } = V.ctx;
    const cam = V.ctx.vm.camera;
    const scene = V.ctx.vm.scene;
    const v = new THREE.Vector3();
    const m = new THREE.Matrix4();
    const im = new THREE.Matrix4();
    let buf = new Float32Array(3 * 65536);
    const tan = Math.tan((cam.fov * Math.PI) / 360);

    // Liang–Barsky: does segment p→q intersect the rectangle |x| ≤ hw, |y| ≤ hh?
    function segHitsRect(px, py, qx, qy, hw, hh) {
      let t0 = 0;
      let t1 = 1;
      const dx = qx - px;
      const dy = qy - py;
      const p = [-dx, dx, -dy, dy];
      const q = [px + hw, hw - px, py + hh, hh - py];
      for (let i = 0; i < 4; i++) {
        if (p[i] === 0) {
          if (q[i] < 0) return false;
        } else {
          const r = q[i] / p[i];
          if (p[i] < 0) t0 = Math.max(t0, r);
          else t1 = Math.min(t1, r);
          if (t0 > t1) return false;
        }
      }
      return true;
    }

    function checkFrame(aspect, res) {
      const zn = -cam.near;
      const hh = cam.near * tan;
      const hw = hh * aspect;
      scene.updateMatrixWorld(true);
      scene.traverseVisible((o) => {
        if (!o.isMesh || o.name === 'vmOverlay') return;
        const g = o.geometry;
        const pos = g.attributes.position;
        const n = pos.count;
        const instances = o.isInstancedMesh ? o.count : 1;
        for (let k = 0; k < instances; k++) {
          if (o.isInstancedMesh) {
            o.getMatrixAt(k, im);
            m.multiplyMatrices(o.matrixWorld, im);
          } else m.copy(o.matrixWorld);
          if (buf.length < n * 3) buf = new Float32Array(n * 3);
          let anyNear = false;
          for (let i = 0; i < n; i++) {
            o.getVertexPosition(i, v);
            v.applyMatrix4(m);
            buf[i * 3] = v.x;
            buf[i * 3 + 1] = v.y;
            buf[i * 3 + 2] = v.z;
            if (v.z > zn * 6) anyNear = true;
            const d = -v.z;
            if (d > 0 && Math.abs(v.x) <= d * tan * aspect && Math.abs(v.y) <= d * tan && d < res.minDepth) {
              res.minDepth = d;
              res.minWhat = o.name;
            }
          }
          if (!anyNear) continue;
          const idx = g.index ? g.index.array : null;
          const start = g.drawRange.start;
          const count = Math.min(g.drawRange.count, idx ? idx.length : n) - (Math.min(g.drawRange.count, idx ? idx.length : n) % 3);
          for (let t = start; t < start + count; t += 3) {
            const ia = idx ? idx[t] : t;
            const ib = idx ? idx[t + 1] : t + 1;
            const ic = idx ? idx[t + 2] : t + 2;
            const P = [ia, ib, ic];
            const za = buf[ia * 3 + 2] - zn;
            const zb = buf[ib * 3 + 2] - zn;
            const zc = buf[ic * 3 + 2] - zn;
            if ((za < 0 && zb < 0 && zc < 0) || (za >= 0 && zb >= 0 && zc >= 0)) continue;
            const pts = [];
            const D = [za, zb, zc];
            for (let e = 0; e < 3; e++) {
              const i0 = P[e];
              const i1 = P[(e + 1) % 3];
              const d0 = D[e];
              const d1 = D[(e + 1) % 3];
              if ((d0 < 0) !== (d1 < 0)) {
                const s = d0 / (d0 - d1);
                pts.push(buf[i0 * 3] + (buf[i1 * 3] - buf[i0 * 3]) * s, buf[i0 * 3 + 1] + (buf[i1 * 3 + 1] - buf[i0 * 3 + 1]) * s);
              }
            }
            if (pts.length >= 4 && segHitsRect(pts[0], pts[1], pts[2], pts[3], hw, hh)) {
              res.violations++;
              res.where = `${o.name}`;
            }
          }
        }
      });
    }

    const swing = (kind, extra = {}) => ['swing', Object.assign({ kind, dir: 0, power: 0.9, weapon: 'sword', src: 'test' }, extra)];
    const scen = [];
    for (let i = 0; i < 16; i++) {
      const dir = -Math.PI + (i * Math.PI) / 8;
      scen.push({ name: `slash ${(dir * 180 / Math.PI).toFixed(0)}`, events: [swing('slash', { dir })], frames: 24 });
      scen.push({ name: `slash raised ${(dir * 180 / Math.PI).toFixed(0)}`, setup: { raise: 1 }, events: [swing('slash', { dir })], frames: 24 });
    }
    for (let i = 0; i < 8; i++) {
      const dir = -Math.PI + (i * Math.PI) / 4;
      scen.push({ name: `spear slash ${(dir * 180 / Math.PI).toFixed(0)}`, setup: { weapon: 'spear' }, events: [swing('slash', { dir, weapon: 'spear' })], frames: 24 });
    }
    scen.push({ name: 'thrust', events: [swing('thrust')], frames: 26 });
    scen.push({ name: 'spearThrust', setup: { weapon: 'spear', raise: 1 }, events: [swing('spearThrust', { weapon: 'spear' })], frames: 30 });
    scen.push({ name: 'bash', setup: { raise: 1 }, events: [swing('bash')], frames: 30 });
    scen.push({ name: 'touch throw', setup: { weapon: 'spear' }, events: [swing('throw', { weapon: 'spear', src: 'touch' })], frames: 50 });
    scen.push({ name: 'motion windup', setup: { weapon: 'spear' }, play: ['throwWindup'], frames: 90 });
    scen.push({ name: 'motion throw', setup: { weapon: 'spear' }, play: ['throwWindup'], settle2: 0.3, events: [swing('throw', { weapon: 'spear', src: 'motion' })], frames: 50 });
    scen.push({ name: 'block', setup: { raise: 1 }, events: [['block', { by: 'player', perfect: false, kind: 'melee' }]], frames: 30 });
    scen.push({ name: 'perfect', events: [['block', { by: 'player', perfect: true, kind: 'melee' }]], frames: 30 });
    scen.push({ name: 'guardBreak', setup: { raise: 1 }, events: [['block', { by: 'player', perfect: false, kind: 'guardBreak' }]], frames: 50 });
    scen.push({ name: 'hurt', setup: { raise: 1 }, events: [['playerHurt', { damage: 30, hp: 20, relYaw: 1, kind: 'axe' }]], frames: 36 });
    scen.push({ name: 'draw spear', after: { weapon: 'spear' }, events: [['weaponSwitch', { weapon: 'spear' }]], frames: 36 });
    scen.push({ name: 'draw sword', setup: { weapon: 'spear' }, after: { weapon: 'sword' }, events: [['weaponSwitch', { weapon: 'sword' }]], frames: 36 });
    scen.push({ name: 'arrows walking', setup: { arrows: 8, walk: true, raise: 1 }, frames: 60, turn: true });

    const aspects = [19.5 / 9, 16 / 9, 4 / 3];
    const report = { frames: 0, violations: [], minDepth: Infinity, minWhat: '', minScenario: '' };
    for (const aspect of aspects) {
      cam.aspect = aspect;
      cam.updateProjectionMatrix();
      for (const s of scen) {
        V.shot(Object.assign({}, s, { at: 0 }));
        const res = { violations: 0, minDepth: Infinity, minWhat: '', where: '' };
        for (let f = 0; f < s.frames; f++) {
          if (s.turn) V.player.yaw += 0.08 * Math.sin(f * 0.4);
          V.tick(1);
          checkFrame(aspect, res);
          report.frames++;
        }
        if (res.violations) report.violations.push(`${s.name} @${aspect.toFixed(2)}: ${res.violations} (${res.where})`);
        if (res.minDepth < report.minDepth) {
          report.minDepth = res.minDepth;
          report.minWhat = res.minWhat;
          report.minScenario = `${s.name} @${aspect.toFixed(2)}`;
        }
      }
    }
    cam.aspect = innerWidth / innerHeight;
    cam.updateProjectionMatrix();
    return report;
  });
  ok(near.violations.length === 0, `near plane: ${near.frames} frames × 3 aspects, no triangle crosses it in view${near.violations.length ? ': ' + near.violations.join('; ') : ''}`);
  ok(near.minDepth > 0.05, `closest on-screen vertex ${near.minDepth.toFixed(3)} vm units (${near.minWhat}, ${near.minScenario})`);

  const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors));
  ok(errs.length === 0, `no page errors${errs.length ? ': ' + errs.join('\n') : ''}`);
} finally {
  await l.close();
}
console.log(failures ? `${failures} check(s) FAILED` : 'all viewmodel checks passed');
if (failures) process.exitCode = 1;
