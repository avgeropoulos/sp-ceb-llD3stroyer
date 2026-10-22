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
        let maxSlip = 0, sum = 0, n = 0, maxSink = 0;
        for (let f = 0; f < 240; f++) {
          v.root.position.z += speed * dt;
          v.anim.update(dt);
          v.root.updateMatrixWorld(true);
          feet.forEach((b, i) => {
            for (const off of [[0, -0.085, 0.13], [0, -0.085, -0.05]]) {
              p.set(off[0], off[1], off[2]).applyMatrix4(b.matrixWorld);
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
    check(r.planted > 20 && r.meanSlip < 0.12 * Math.max(1, Math.abs(r.speed)) && r.maxSink > -0.02,
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
    check(d.ended && d.minY > -0.06 && d.minY < 0.05 && d.maxY < 0.75,
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
