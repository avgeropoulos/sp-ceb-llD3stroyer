// Scripted checks for gfx/characters.js in headless Chromium:
//  - planted-foot slip while walking/running at several speeds (feet must not skate)
//  - clip callbacks (onStrike at strikeAt·duration, onEnd; replacing a clip cancels them)
//  - death clips end lying on the ground (lowest point ≈ 0, no floating or sinking)
//  - draw calls and triangles per kind, low-quality budget
//  - update cost of 30 animated actors (JS time per frame, allocation check)
// usage: node spartan/tests/e2e/characters-anim.mjs
import { launch } from './lib.mjs';

const l = await launch();
let failures = 0;
const check = (ok, msg) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${msg}`);
  if (!ok) failures++;
};
try {
  const page = await l.newPage({ width: 640, height: 360 });
  await page.goto(`${l.base}/tests/sandbox/characters-close.html?manual=1&kind=levy`);
  await page.waitForFunction(() => window.__chars, null, { timeout: 60000 });

  // ---- foot slip
  const slip = await page.evaluate(() => {
    const { chars, THREE, ctx } = window.__chars;
    const out = [];
    for (const kind of ['levy', 'spartan', 'captain']) {
      for (const speed of [0.7, 1.4, 2.4, 3.0, -1.0]) {
        const v = chars.create(kind, { seed: 5 });
        ctx.scene.add(v.root);
        v.anim.setLocomotion(speed);
        const dt = 1 / 60;
        const feet = [v.mesh.skeleton.bones.find((b) => b.name === 'footL'), v.mesh.skeleton.bones.find((b) => b.name === 'footR')];
        const prev = [new THREE.Vector3(), new THREE.Vector3()];
        const p = new THREE.Vector3();
        const s = v._animator.s;
        let maxSlip = 0, sum = 0, n = 0, maxSink = 0;
        for (let f = 0; f < 240; f++) {
          v.root.position.z += speed * dt;
          v.anim.update(dt);
          v.root.updateMatrixWorld(true);
          feet.forEach((b, i) => {
            for (const off of [[0, -0.085, 0.13], [0, -0.085, -0.05]]) {
              p.set(off[0] * s, off[1] * s, off[2] * s).applyMatrix4(b.matrixWorld);
              if (off[2] < 0) {
                maxSink = Math.min(maxSink, p.y);
                continue;
              }
              if (f > 60 && p.y < 0.012 && prev[i].y < 0.012) {
                const sp = Math.hypot(p.x - prev[i].x, p.z - prev[i].z) / dt;
                maxSlip = Math.max(maxSlip, sp);
                sum += sp;
                n++;
              }
              prev[i].copy(p);
            }
          });
        }
        v.dispose();
        out.push({ kind, speed, maxSlip, meanSlip: n ? sum / n : 0, planted: n, maxSink });
      }
    }
    return out;
  });
  for (const r of slip) {
    check(r.planted > 20 && r.meanSlip < 0.05 && r.maxSink > -0.02,
      `${r.kind} speed ${r.speed}: planted samples ${r.planted}, mean slip ${r.meanSlip.toFixed(3)} m/s, max ${r.maxSlip.toFixed(3)} m/s, lowest heel ${r.maxSink.toFixed(3)} m`);
  }

  // ---- callbacks
  const cb = await page.evaluate(() => {
    const { chars } = window.__chars;
    const v = chars.create('levy', { seed: 2 });
    const log = [];
    let t = 0;
    v.anim.play('thrust', { duration: 1.5, onStrike: () => log.push(['strike', t]), onEnd: () => log.push(['end', t]) });
    for (let i = 0; i < 120; i++) {
      t += 1 / 60;
      v.anim.update(1 / 60);
    }
    const a = log.slice();
    // replacing cancels
    const log2 = [];
    v.anim.play('overhead', { duration: 1.0, onStrike: () => log2.push('s1'), onEnd: () => log2.push('e1') });
    for (let i = 0; i < 10; i++) v.anim.update(1 / 60);
    v.anim.play('hit', { onEnd: () => log2.push('e2') });
    v.anim.flinch(0, -1, 1);
    for (let i = 0; i < 90; i++) v.anim.update(1 / 60);
    // play from inside onStrike
    const log3 = [];
    v.anim.play('slash', { onStrike: () => { log3.push('s'); v.anim.play('hit', { onEnd: () => log3.push('hitEnd') }); }, onEnd: () => log3.push('slashEnd') });
    for (let i = 0; i < 120; i++) v.anim.update(1 / 60);
    // big step past strike and end in one update
    const log4 = [];
    v.anim.play('bash', { onStrike: () => log4.push('s'), onEnd: () => log4.push('e') });
    v.anim.update(2.0);
    return { a, log2, log3, log4, clip: v.anim.clip, playing: v.anim.playing };
  });
  const strike = cb.a.find((e) => e[0] === 'strike'), end = cb.a.find((e) => e[0] === 'end');
  check(strike && Math.abs(strike[1] - 0.6 * 1.5) < 0.02, `thrust onStrike at ${strike && strike[1].toFixed(3)} s (expected 0.900)`);
  check(end && Math.abs(end[1] - 1.5) < 0.02, `thrust onEnd at ${end && end[1].toFixed(3)} s (expected 1.500)`);
  check(cb.log2.join() === 'e2', `replacing a clip cancels its callbacks: ${cb.log2.join()}`);
  check(cb.log3.join() === 's,hitEnd', `play() inside onStrike: ${cb.log3.join()}`);
  check(cb.log4.join() === 's,e', `large step fires strike then end: ${cb.log4.join()}`);
  check(cb.clip === null && cb.playing === false, `after a one-shot ends: clip ${cb.clip}, playing ${cb.playing}`);

  // ---- deaths end on the ground
  const deaths = await page.evaluate(() => {
    const { chars, CLIPS, ctx, THREE } = window.__chars;
    const res = [];
    const box = new THREE.Box3();
    for (const kind of ['spartan', 'levy', 'sparabara', 'immortal', 'archer', 'captain', 'helot']) {
      for (const clip of ['die_back', 'die_forward', 'die_crumple', 'die_spear', 'knockdown']) {
        const v = chars.create(kind, { seed: 9 });
        ctx.scene.add(v.root);
        v.anim.setGuard(true);
        for (let i = 0; i < 30; i++) v.anim.update(1 / 60);
        let ended = false;
        v.anim.play(clip, { onEnd: () => { ended = true; } });
        let minDuring = Infinity;
        for (let i = 0; i < 200 && !ended; i++) {
          v.anim.update(1 / 60);
          minDuring = Math.min(minDuring, v._animator.groundY ?? 0);
        }
        // the ai stops updating here: measure the frozen pose
        v.root.updateMatrixWorld(true);
        v.mesh.skeleton.update();
        // lowest skinned vertex
        const g = v.mesh.geometry, pos = g.attributes.position;
        const tmp = new THREE.Vector3();
        let minY = Infinity, maxY = -Infinity;
        for (let k = 0; k < pos.count; k += 3) {
          tmp.fromBufferAttribute(pos, k);
          v.mesh.applyBoneTransform(k, tmp);
          tmp.applyMatrix4(v.mesh.matrixWorld);
          minY = Math.min(minY, tmp.y);
          maxY = Math.max(maxY, tmp.y);
        }
        res.push({ kind, clip, ended, minY, maxY, clipName: v.anim.clip });
        v.dispose();
      }
    }
    void CLIPS;
    void box;
    return res;
  });
  for (const d of deaths) {
    check(d.ended && d.minY > -0.03 && d.minY < 0.05 && d.maxY < 0.75,
      `${d.kind} ${d.clip}: ended ${d.ended}, lowest vertex ${d.minY.toFixed(3)} m, highest ${d.maxY.toFixed(3)} m, holds '${d.clipName}'`);
  }

  // ---- draw calls / triangles per kind, both qualities
  for (const quality of ['high', 'low']) {
    const p2 = await l.newPage({ width: 640, height: 360 });
    await p2.goto(`${l.base}/tests/sandbox/characters-close.html?manual=1&kind=levy&quality=${quality}`);
    await p2.waitForFunction(() => window.__chars, null, { timeout: 60000 });
    const kinds = await p2.evaluate(() => {
      const { chars, ctx } = window.__chars;
      const out = [];
      for (const v of window.__chars.visuals) v.setVisible(false);
      const variants = [['spartan', {}], ['spartan', { weapon: 'xiphos' }], ['levy', { variant: 'mede' }], ['levy', { variant: 'kissian' }], ['sparabara', {}], ['immortal', {}], ['archer', {}], ['captain', {}], ['dummy', {}], ['helot', {}]];
      for (const [kind, o] of variants) {
        for (let seed = 1; seed <= 6; seed++) {
          const v = chars.create(kind, Object.assign({ seed }, o));
          ctx.scene.add(v.root);
          ctx.renderer.info.reset();
          ctx.renderer.shadowMap.enabled = false;
          ctx.camera.position.set(0, 1.66, 3);
          ctx.camera.lookAt(0, 1, 0);
          ctx.renderer.render(ctx.scene, ctx.camera);
          const r = ctx.renderer.info.render;
          out.push({ kind: kind + (o.variant ? ':' + o.variant : '') + (o.weapon ? ':' + o.weapon : ''), seed, variant: v.variant, calls: r.calls - 1, triangles: v.triangles, groups: v.mesh.geometry.groups.length });
          v.dispose();
        }
      }
      return out;
    });
    const byKind = {};
    for (const k of kinds) {
      const e = (byKind[k.kind] = byKind[k.kind] || { calls: 0, tmin: 1e9, tmax: 0, variants: new Set() });
      e.calls = Math.max(e.calls, k.calls);
      e.tmin = Math.min(e.tmin, k.triangles);
      e.tmax = Math.max(e.tmax, k.triangles);
      e.variants.add(k.variant);
    }
    const limit = quality === 'low' ? 1200 : 2500;
    for (const [k, e] of Object.entries(byKind)) {
      check(e.calls <= 2 && e.tmax <= limit, `${quality}: ${k}: ${e.calls} draw calls, ${e.tmin}–${e.tmax} triangles (${e.variants.size} variants)`);
    }
    await p2.close();
  }

  // ---- Visual API: attach, bloodied, visibility, dispose, flinch, props, shared programs
  const api = await page.evaluate(() => {
    const { chars, ctx, THREE } = window.__chars;
    const r = {};
    const render = () => {
      ctx.camera.position.set(0, 1.66, 3);
      ctx.camera.lookAt(0, 1, 0);
      ctx.camera.updateMatrixWorld();
      ctx.renderer.render(ctx.scene, ctx.camera);
    };
    const a = chars.create('levy', { seed: 21 }), b = chars.create('levy', { seed: 21 });
    ctx.scene.add(a.root, b.root);
    a.root.position.set(0.7, 0, -1);
    a.root.rotation.y = 0.6;
    a.anim.play('thrust', {});
    for (let i = 0; i < 40; i++) a.anim.update(1 / 60);
    a.root.updateMatrixWorld(true);
    const spear = chars.createProp('dory');
    ctx.scene.add(spear);
    spear.position.set(0.8, 1.2, -0.9);
    spear.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), new THREE.Vector3(0.3, -0.2, -1).normalize());
    spear.updateMatrixWorld(true);
    const before = spear.matrixWorld.clone();
    a.attach(spear, 'chest');
    spear.updateMatrixWorld(true);
    let diff = 0;
    for (let i = 0; i < 16; i++) diff = Math.max(diff, Math.abs(before.elements[i] - spear.matrixWorld.elements[i]));
    r.attachDiff = diff;
    r.attachParent = spear.parent && spear.parent.name;
    render();
    render(); // shadow depth programs settle on the second frame
    const progs = ctx.renderer.info.programs.length;
    a.setBloodied(0.5);
    b.setBloodied(1);
    render();
    r.bloodMats = [a.mesh.material[0].name, b.mesh.material[0].name];
    r.newPrograms = ctx.renderer.info.programs.length - progs;
    a.setVisible(false);
    r.hidden = a.root.visible === false;
    a.setVisible(true);
    // flinch never cancels a clip
    let struck = 0;
    a.anim.play('overhead', { duration: 1.0, onStrike: () => struck++ });
    for (let i = 0; i < 70; i++) {
      if (i % 9 === 0) a.anim.flinch(0.5, -1, 1);
      a.anim.update(1 / 60);
    }
    r.flinchStruck = struck;
    const geo = b.mesh.geometry;
    let disposedEvents = 0;
    geo.addEventListener('dispose', () => disposedEvents++);
    a.dispose();
    a.dispose();
    r.removed = a.root.parent === null;
    render();
    r.geoDisposed = disposedEvents;
    r.sharedGeometry = a.mesh.geometry === b.mesh.geometry;
    // props: tip on +Z (the point/head is the +Z end: thin there), origin between the ends
    r.props = ['dory', 'arrow', 'persianSpear', 'sagaris'].map((n) => {
      const p = chars.createProp(n);
      const pos = p.geometry.attributes.position;
      let minZ = Infinity, maxZ = -Infinity;
      for (let i = 0; i < pos.count; i++) {
        minZ = Math.min(minZ, pos.getZ(i));
        maxZ = Math.max(maxZ, pos.getZ(i));
      }
      // widest cross-section near each end (the head of an axe is at +Z; spear and arrow points end in a pole)
      let headR = 0, tailR = 0;
      for (let i = 0; i < pos.count; i++) {
        const rr = Math.hypot(pos.getX(i), pos.getY(i)), z = pos.getZ(i);
        if (z > maxZ - 0.12) headR = Math.max(headR, rr);
        if (z < minZ + 0.12) tailR = Math.max(tailR, rr);
      }
      return { n, minZ, maxZ, headR, tailR, calls: Array.isArray(p.material) ? p.material.length : 1 };
    });
    // all kinds share the two material programs (plus depth programs for shadows)
    const kinds = ['spartan', 'levy', 'sparabara', 'immortal', 'archer', 'captain', 'dummy', 'helot'];
    const vs = kinds.map((k, i) => {
      const v = chars.create(k, { seed: 40 + i });
      v.root.position.set(i - 3.5, 0, -2);
      ctx.scene.add(v.root);
      return v;
    });
    render();
    render();
    const p1 = ctx.renderer.info.programs.length;
    vs.forEach((v) => v.setBloodied(0.9));
    render();
    r.kindPrograms = ctx.renderer.info.programs.length - p1;
    vs.forEach((v) => v.dispose());
    b.dispose();
    return r;
  });
  check(api.attachDiff < 1e-4, `attach keeps the world transform (max matrix diff ${api.attachDiff.toExponential(1)}, parent '${api.attachParent}')`);
  check(api.newPrograms === 0, `setBloodied switches materials without new programs (${api.bloodMats.join(', ')}; +${api.newPrograms} programs)`);
  check(api.hidden, 'setVisible(false) hides the root');
  check(api.flinchStruck === 1, `flinch during a clip never cancels it (onStrike fired ${api.flinchStruck}×)`);
  check(api.removed && api.geoDisposed === 0 && api.sharedGeometry, `dispose removes the root and keeps shared geometry (dispose events ${api.geoDisposed})`);
  for (const p of api.props) {
    const headFirst = p.n === 'sagaris' ? p.headR > p.tailR * 2 : p.n === 'arrow' ? p.tailR > p.headR * 0.5 : true;
    check(p.maxZ > 0.1 && p.minZ < -0.1 && headFirst,
      `prop ${p.n}: +Z end at ${p.maxZ.toFixed(2)} m (r ${p.headR.toFixed(3)}), −Z end at ${p.minZ.toFixed(2)} m (r ${p.tailR.toFixed(3)}), ${p.calls} material(s)`);
  }
  check(api.kindPrograms === 0, `bloodied variants of every kind add no programs (+${api.kindPrograms})`);

  // ---- update cost, 30 actors
  const perf = await page.evaluate(() => {
    const { chars, ctx } = window.__chars;
    const vs = [];
    const kinds = ['levy', 'immortal', 'spartan', 'sparabara', 'captain', 'archer'];
    for (let i = 0; i < 30; i++) {
      const v = chars.create(kinds[i % kinds.length], { seed: 100 + i });
      v.root.position.set((i % 6) * 1.2, 0, -Math.floor(i / 6) * 1.5);
      ctx.scene.add(v.root);
      v.anim.setLocomotion(i % 3 === 0 ? 1.4 : i % 3 === 1 ? 0 : 2.6);
      v.anim.setGuard(i % 2 === 0);
      vs.push(v);
    }
    const clips = ['thrust', 'overhead', 'axe', 'hit', 'stagger', 'block_hit', 'cheer', 'shoot'];
    const frame = (f) => {
      for (let i = 0; i < vs.length; i++) {
        const v = vs[i];
        if ((f + i * 7) % 90 === 0) v.anim.play(clips[(f + i) % clips.length], {});
        if ((f + i * 11) % 50 === 0) v.anim.flinch(0.3, -1, 0.6);
        v.anim.update(1 / 60);
      }
    };
    for (let f = 0; f < 240; f++) frame(f); // warm-up (JIT)
    const N = 1200;
    const t0 = performance.now();
    for (let f = 0; f < N; f++) frame(f);
    const ms = (performance.now() - t0) / N;
    // matrix update cost that the renderer adds (scene graph + skeleton.update)
    const t1 = performance.now();
    for (let f = 0; f < 300; f++) {
      for (const v of vs) {
        v.root.updateMatrixWorld(true);
        v.mesh.skeleton.update();
      }
    }
    const ms2 = (performance.now() - t1) / 300;
    let heapNote = 'n/a';
    if (performance.memory) {
      const before = performance.memory.usedJSHeapSize;
      for (let f = 0; f < 600; f++) frame(f);
      heapNote = `${((performance.memory.usedJSHeapSize - before) / 1024).toFixed(0)} KB heap growth over 600 frames`;
    }
    for (const v of vs) v.dispose();
    return { ms, ms2, heapNote };
  });
  console.log(`perf: anim.update for 30 actors = ${perf.ms.toFixed(3)} ms/frame; scene-graph matrices + skeleton.update = ${perf.ms2.toFixed(3)} ms/frame; ${perf.heapNote}`);
  check(perf.ms < 2.0, `30-actor update under 2 ms/frame (${perf.ms.toFixed(3)} ms)`);
  const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors));
  check(errs.length === 0, `no page errors ${errs.join(' | ')}`);
} finally {
  await l.close();
}
console.log(failures ? `${failures} check(s) failed` : 'all checks passed');
process.exitCode = failures ? 1 : 0;
