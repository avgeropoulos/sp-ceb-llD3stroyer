// End-to-end checks for js/audio in headless Chromium.
//   node spartan/tests/e2e/audio-check.mjs [outDir]
// 1. Renders every SFX and loop through the real engine (module worker), reports the startup
//    synthesis time and buffer memory, measures each sound (peak, loudness, centroid, NaN,
//    loop seams), checks it matches the Node render, and writes WAVs to outDir.
// 2. The same with the Worker unavailable (main-thread time-sliced fallback).
// 3. Event wiring with a fake EventBus, voice dispatch to a platform.voice.say stub,
//    heartbeat, voice cap, volumes and music/ambience output through an AnalyserNode.
// 4. AudioContext unlock/suspend/resume state transitions (default autoplay policy).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { launch } from './lib.mjs';
import { stats, spectrum, seam, writeWav } from '../unit/audio-analysis.mjs';
import { SFX, renderSfx } from '../../src/main/assets/game/js/audio/sfx.js';
import { LOOPS } from '../../src/main/assets/game/js/audio/loops.js';
import { BANDS } from '../unit/audio-expect.mjs';

const OUT = process.argv[2] || path.join(os.tmpdir(), 'thermopylae-audio');
fs.mkdirSync(OUT, { recursive: true });
const failures = [];
const check = (ok, msg) => {
  if (!ok) failures.push(msg);
  return ok;
};

async function pullBuffer(page, name, variant = 0) {
  const r = await page.evaluate(([n, v]) => {
    const b = window.__audio.getBuffer(n, v);
    if (!b) return null;
    const d = b.getChannelData(0);
    const u8 = new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return { sr: b.sampleRate, b64: btoa(s) };
  }, [name, variant]);
  if (!r) return null;
  const bytes = Buffer.from(r.b64, 'base64');
  return { sr: r.sr, data: new Float32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4) };
}

async function waitRendered(page, timeout = 180000) {
  await page.waitForFunction(() => window.__audio && window.__audio.stats().pending === 0, null, { timeout, polling: 100 });
  return page.evaluate(() => window.__audio.stats());
}

const l = await launch();
try {
  // ---------------------------------------------------------------- 1. worker render
  const page = await l.newPage({ width: 1100, height: 700 });
  await page.goto(`${l.base}/tests/sandbox/audio-lab.html`);
  await page.waitForFunction(() => window.__audio, null, { timeout: 60000 });
  await page.evaluate(() => window.__audio.preload('lastStand'));
  const st = await waitRendered(page);
  const ra = st.readyAt;
  const combat = ['whoosh', 'flesh', 'clang', 'parry', 'hurt', 'grunt', 'death', 'shieldUp', 'bash'].map((n) => ra['sfx:' + n]);
  console.log(`worker render (${st.workers} workers): ${st.jobsDone} jobs, ${st.renderMs.toFixed(0)} ms CPU in workers, boot→all ready ${(st.doneAt - st.startedAt).toFixed(0)} ms wall, ` +
    `${st.mb.toFixed(2)} MB of buffers at ${st.sampleRate} Hz`);
  console.log(`  ready at (ms after createAudio): UI ${ra['sfx:uiTap']}, title music ${ra['loop:title']}, calm beds ${Math.max(ra['loop:wind'], ra['loop:sea'], ra['loop:cicadas'])}, ` +
    `core combat SFX ${Math.max(...combat)}, battle music ${ra['loop:battle']}, din ${ra['loop:din']}, warcry ${ra['sfx:warcry']}, lastStand ${ra['loop:lastStand']}`);
  check(st.workers >= 1, 'render worker did not start');
  check(st.mb <= 25, `buffer memory ${st.mb.toFixed(1)} MB > 25 MB`);

  console.log('\nname          var  dur    peak   rms   mom   centroid  [band]          seam(jump/curv vs p99.9)  node-diff');
  const rows = [];
  for (const name of Object.keys(SFX)) {
    for (let v = 0; v < SFX[name].variants; v++) {
      const b = await pullBuffer(page, name, v);
      if (!check(!!b, `${name}:${v} not rendered`)) continue;
      const s = stats(b.data, b.sr);
      if (v === 0) {
        const sp = spectrum(b.data, b.sr);
        const ref = renderSfx(name, b.sr, 0);
        let diff = 0;
        for (let i = 0; i < ref.length; i++) diff = Math.max(diff, Math.abs(ref[i] - b.data[i]));
        const band = BANDS[name];
        rows.push(`${name.padEnd(13)} ${v}  ${s.dur.toFixed(2).padStart(5)}  ${s.peak.toFixed(3)} ${s.rms.toFixed(3)} ${s.momentary.toFixed(3)} ${sp.centroid.toFixed(0).padStart(6)} Hz [${band ? band.join('-') : '?'}]  ${''.padEnd(24)} ${diff.toExponential(1)}`);
        check(!band || (sp.centroid >= band[0] && sp.centroid <= band[1]), `${name} centroid ${sp.centroid.toFixed(0)} outside ${band}`);
        check(diff < 1e-4, `${name} browser render differs from Node by ${diff}`);
        writeWav(path.join(OUT, `${name}.wav`), b.data, b.sr);
      }
      check(s.nan === 0, `${name}:${v} has ${s.nan} non-finite samples`);
      check(s.peak <= 0.98, `${name}:${v} peak ${s.peak.toFixed(3)} clips`);
      check(s.momentary >= SFX[name].loud * 0.5 && s.momentary <= SFX[name].loud * 1.05, `${name}:${v} loudness ${s.momentary.toFixed(3)} far from target ${SFX[name].loud}`);
      check(Math.abs(s.dur - SFX[name].dur) < 0.01, `${name}:${v} duration ${s.dur}`);
    }
  }
  for (const name of Object.keys(LOOPS)) {
    const b = await pullBuffer(page, name);
    if (!check(!!b, `loop ${name} not rendered`)) continue;
    const s = stats(b.data, b.sr), sp = spectrum(b.data, b.sr), se = seam(b.data);
    const band = BANDS[name];
    rows.push(`${('loop:' + name).padEnd(13)} -  ${s.dur.toFixed(2).padStart(5)}  ${s.peak.toFixed(3)} ${s.rms.toFixed(3)} ${s.momentary.toFixed(3)} ${sp.centroid.toFixed(0).padStart(6)} Hz [${band ? band.join('-') : '?'}]  ` +
      `${se.jump.toExponential(1)}/${se.curv.toExponential(1)} vs ${se.p999jump.toExponential(1)}/${se.p999curv.toExponential(1)}`);
    check(s.nan === 0, `loop ${name} has NaN`);
    check(s.peak <= 0.98, `loop ${name} clips`);
    check(se.jump <= se.p999jump && se.curv <= se.p999curv, `loop ${name} seam click: jump ${se.jump} curv ${se.curv}`);
    check(Math.abs(Math.log(se.rmsHead / se.rmsTail)) < Math.log(3), `loop ${name} level step across the seam`);
    check(!band || (sp.centroid >= band[0] && sp.centroid <= band[1]), `loop ${name} centroid ${sp.centroid.toFixed(0)} outside ${band}`);
    const two = new Float32Array(b.data.length * 2);
    two.set(b.data);
    two.set(b.data, b.data.length);
    writeWav(path.join(OUT, `loop-${name}-x2.wav`), two, b.sr);
  }
  console.log(rows.join('\n'));
  console.log(`\nWAVs written to ${OUT}`);

  // ---------------------------------------------------------------- 3. events, voices, mix
  const ev = await page.evaluate(async () => {
    const { createAudio, ctx: labCtx } = window.__lab;
    const THREE = labCtx.THREE;
    labCtx.camera.position.set(0, 1.66, 0);
    labCtx.camera.rotation.set(0, 0, 0);
    labCtx.camera.updateMatrixWorld();
    const subs = {};
    const bus = {
      on(n, fn) { (subs[n] = subs[n] || []).push(fn); return () => {}; },
      emit(n, p) { for (const f of subs[n] || []) f(p); },
    };
    const said = [];
    const ctx = {
      settings: { master: 0.9, music: 0.55, sfx: 1, voice: true }, events: bus, camera: labCtx.camera, mode: 'battle',
      time: { scale: 1 }, quality: 'medium', player: null,
      platform: { voice: { say: (text, o) => said.push({ text, ...o }), stop() {} } },
    };
    const a = createAudio(ctx);
    a.unlock();
    await a.prerender();
    const t0 = performance.now();
    while (a.context.state !== 'running' && performance.now() - t0 < 3000) await new Promise((r) => setTimeout(r, 20));
    const out = {};
    const run = async (key, name, payload) => {
      a.log.length = 0;
      bus.emit(name, payload);
      out[key] = a.log.map((e) => ({ name: e.name, volume: +e.volume.toFixed(3), rate: +e.rate.toFixed(3), pan: +e.pan.toFixed(2), delay: +e.delay.toFixed(2), at: e.at, bus: e.bus }));
      await new Promise((r) => setTimeout(r, 30)); // let the duplicate guard (12 ms) pass
    };
    const levy = { kind: 'levy', team: 'persian', pos: new THREE.Vector3(0.4, 0, -1.8), alive: true };
    const captain = { kind: 'captain', team: 'persian', pos: new THREE.Vector3(-0.5, 0, -1.7), alive: true };
    const ally = { kind: 'spartan', team: 'greek', pos: new THREE.Vector3(4.2, 0, 0), alive: true };
    const farLeft = { kind: 'immortal', team: 'persian', pos: new THREE.Vector3(-6, 0, -1), alive: true };
    const pt = (x, y, z) => new THREE.Vector3(x, y, z);
    await run('swingWeak', 'swing', { kind: 'slash', dir: 0, power: 0.3, weapon: 'sword', src: 'test' });
    await run('swingStrong', 'swing', { kind: 'slash', dir: 0, power: 0.95, weapon: 'sword', src: 'test' });
    await run('swingSpear', 'swing', { kind: 'slash', dir: 0, power: 0.4, weapon: 'spear', src: 'test' });
    await run('swingThrust', 'swing', { kind: 'thrust', power: 0.5, weapon: 'sword', src: 'test' });
    await run('swingBash', 'swing', { kind: 'bash', power: 0.5, weapon: 'sword', src: 'test' });
    await run('hitFlesh', 'hit', { target: levy, attacker: 'player', damage: 30, kind: 'slash', point: pt(0.3, 1.3, -1.6), guarded: false, byPlayer: true });
    await run('hitKill', 'hit', { target: levy, attacker: 'player', damage: 60, kind: 'slash', point: pt(0.3, 1.3, -1.6), guarded: false, killed: true, byPlayer: true });
    await run('hitWicker', 'hit', { target: levy, attacker: 'player', damage: 5, kind: 'slash', point: pt(0.3, 1.3, -1.6), guarded: true, byPlayer: true });
    await run('hitWood', 'hit', { target: captain, attacker: 'player', damage: 5, kind: 'slash', point: pt(-0.4, 1.4, -1.5), guarded: true, byPlayer: true });
    await run('hitAlly', 'hit', { target: ally, attacker: levy, damage: 12, kind: 'melee', point: pt(4.2, 1.3, -0.3), guarded: true, byPlayer: false });
    await run('hitAiFlesh', 'hit', { target: farLeft, attacker: ally, damage: 30, kind: 'melee', point: pt(-6, 1.3, -1), guarded: false, byPlayer: false });
    await run('hitBash', 'hit', { target: levy, attacker: 'player', damage: 8, kind: 'bash', point: pt(0.3, 1.2, -1.4), byPlayer: true });
    await run('hitBleed', 'hit', { target: levy, attacker: 'player', damage: 1, kind: 'bleed', byPlayer: true });
    await run('kill', 'kill', { target: levy, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon' });
    await run('killSea', 'kill', { target: { ...levy, pos: pt(8.8, 0, -2) }, attacker: 'player', byPlayer: true, kind: 'bash', spear: false, cause: 'sea' });
    await run('blockPlayer', 'block', { by: 'player', attacker: levy, point: pt(0, 1.3, -0.6), perfect: false, kind: 'melee' });
    await run('blockPerfect', 'block', { by: 'player', attacker: levy, point: pt(0, 1.3, -0.6), perfect: true, kind: 'melee' });
    await run('blockBreak', 'block', { by: 'player', attacker: captain, point: pt(0, 1.3, -0.6), perfect: false, kind: 'guardBreak' });
    await run('blockAlly', 'block', { by: ally, attacker: levy, point: pt(4.2, 1.3, -0.4), perfect: false, kind: 'melee' });
    await run('shieldUp', 'shield', { up: true });
    await run('shieldDown', 'shield', { up: false });
    await run('spearThrown', 'spearThrown', { projectile: { byPlayer: true, pos: pt(0.2, 1.7, -0.3) } });
    await run('spearImpactFlesh', 'spearImpact', { projectile: {}, target: levy, point: pt(0, 1.3, -14), normal: pt(0, 0, 1), water: false });
    await run('spearImpactGround', 'spearImpact', { projectile: {}, target: null, point: pt(0, 0, -20), normal: pt(0, 1, 0), water: false });
    await run('spearImpactWater', 'spearImpact', { projectile: {}, target: null, point: pt(14, -3, -10), normal: pt(0, 1, 0), water: true });
    await run('volleyWarning', 'volley', { phase: 'warning', duration: 1 });
    await run('volleyLoosed', 'volley', { phase: 'loosed', duration: 1 });
    await run('volleyImpact', 'volley', { phase: 'impact', duration: 1 });
    await run('volleyEnd', 'volley', { phase: 'end', duration: 1 });
    await run('arrowShield', 'arrowHit', { target: 'shield', point: pt(0, 1.4, -0.5) });
    await run('arrowPlayer', 'arrowHit', { target: 'player', point: pt(0, 1.4, 0) });
    await run('arrowGround', 'arrowHit', { target: null, point: pt(3, 0, -2) });
    await run('playerHurt', 'playerHurt', { damage: 10, hp: 60, from: levy, kind: 'melee', relYaw: 1.2 });
    await run('playerHurtArrow', 'playerHurt', { damage: 8, hp: 52, from: null, kind: 'arrow', relYaw: 0 });
    await run('windupPlayer', 'enemyWindup', { actor: levy, timeToStrike: 0.9, targetIsPlayer: true });
    await run('windupCaptain', 'enemyWindup', { actor: captain, timeToStrike: 1.1, targetIsPlayer: true });
    await run('windupAlly', 'enemyWindup', { actor: levy, timeToStrike: 0.9, targetIsPlayer: false });
    await run('combo', 'combo', { id: 'storm', name: 'Storm of Bronze', bonus: 240 });
    await run('waveStart', 'waveStart', { day: 1, phase: 0, wave: 1, title: 'Wave 1' });
    await run('weaponSwitch', 'weaponSwitch', { weapon: 'spear' });
    await run('voiceLeonidas', 'voice', { text: 'Come and take them.', speaker: 'Leonidas', greek: 'ΜΟΛΩΝ ΛΑΒΕ' });
    await run('voiceLookout', 'voice', { text: 'The Immortals! Hydarnes leads them.', speaker: 'Lookout' });
    await run('voiceUnknown', 'voice', { text: 'Hello.', speaker: 'Somebody' });

    // Heartbeat: low HP in battle beats, healthy does not.
    ctx.player = { hp: 18, maxHp: 100, alive: true, pos: pt(0, 0, 0) };
    a.log.length = 0;
    for (let i = 0; i < 90; i++) {
      a.update(1 / 60);
      await new Promise((r) => setTimeout(r, 16));
    }
    const beatsLow = a.log.filter((e) => e.name === 'heartbeat').length;
    ctx.player.hp = 80;
    a.log.length = 0;
    for (let i = 0; i < 60; i++) {
      a.update(1 / 60);
      await new Promise((r) => setTimeout(r, 16));
    }
    const beatsHigh = a.log.filter((e) => e.name === 'heartbeat').length;

    // Voice cap: 80 sounds at once.
    const names = ['flesh', 'clang', 'whoosh', 'arrowThud', 'death', 'wicker', 'drum', 'step'];
    for (let i = 0; i < 80; i++) a.play(names[i % names.length], { volume: 0.2, delay: (i % 7) * 0.013, at: pt(Math.sin(i) * 5, 1, -3 - (i % 5)) });
    const cap = a.stats();

    // Positional: a hit on the far left pans left and is quieter than the same hit in front.
    await new Promise((r) => setTimeout(r, 200));
    a.log.length = 0;
    for (const p of [pt(-8, 1.6, 0), pt(0, 1.6, -2), pt(0, 1.6, -40)]) {
      a.play('flesh', { at: p, exact: true });
      await new Promise((r) => setTimeout(r, 40));
    }
    const posLog = a.log.map((e) => ({ volume: e.volume, pan: e.pan }));
    a.dispose();
    return { subs: Object.keys(subs).sort(), out, said, beatsLow, beatsHigh, cap, posLog };
  });

  const names = (k) => (ev.out[k] || []).map((e) => e.name);
  const has = (k, ...ns) => check(ns.every((n) => names(k).includes(n)), `${k}: expected ${ns.join('+')}, got [${names(k)}]`);
  const none = (k) => check(names(k).length === 0, `${k}: expected silence, got [${names(k)}]`);
  console.log('\nsubscribed events:', ev.subs.join(', '));
  for (const e of ['swing', 'hit', 'kill', 'block', 'shield', 'playerHurt', 'playerDeath', 'enemyWindup', 'spearThrown', 'spearImpact', 'arrowHit', 'volley', 'combo', 'waveStart', 'voice']) {
    check(ev.subs.includes(e), `not subscribed to ${e}`);
  }
  has('swingWeak', 'whoosh'); has('swingStrong', 'whooshHeavy'); has('swingSpear', 'whooshHeavy'); has('swingThrust', 'whoosh'); has('swingBash', 'whooshHeavy');
  has('hitFlesh', 'flesh'); has('hitKill', 'fleshHeavy'); has('hitWicker', 'wicker'); has('hitWood', 'wood'); has('hitAlly', 'clang'); has('hitBash', 'bash'); none('hitBleed');
  has('hitAiFlesh', 'flesh');
  const ai = ev.out.hitAiFlesh[0], pl = ev.out.hitFlesh[0];
  check(ai && ai.at && ai.volume <= 0.5 * 1.0 + 1e-6 && ai.pan < -0.3, `AI-vs-AI hit should be positional, ≤ 0.5 and panned left: ${JSON.stringify(ai)}`);
  check(pl && pl.volume > 0.8, `player hit should be near full volume: ${JSON.stringify(pl)}`);
  has('kill', 'death'); has('killSea', 'scream', 'splash');
  check((ev.out.killSea.find((e) => e.name === 'splash') || {}).delay > 0.8, 'sea kill: splash should follow the scream');
  has('blockPlayer', 'clang'); has('blockPerfect', 'parry'); has('blockBreak', 'guardBreak'); has('blockAlly', 'clang');
  has('shieldUp', 'shieldUp'); none('shieldDown');
  has('spearThrown', 'spearThrow', 'spearWhistle'); has('spearImpactFlesh', 'spearHit'); has('spearImpactGround', 'spearGround'); has('spearImpactWater', 'splash');
  has('volleyWarning', 'horn'); check(ev.out.volleyWarning[0] && ev.out.volleyWarning[0].bus === 'distant', 'volley horn should be distant');
  has('volleyLoosed', 'volley'); has('volleyImpact', 'arrowThud', 'arrowShield'); none('volleyEnd');
  has('arrowShield', 'arrowShield'); has('arrowPlayer', 'arrowThud'); has('arrowGround', 'arrowThud');
  has('playerHurt', 'hurt', 'flesh'); has('playerHurtArrow', 'hurt', 'arrowThud');
  check((ev.out.playerHurt.find((e) => e.name === 'flesh') || {}).pan < 0, 'hurt from the left should pan left');
  has('windupPlayer', 'grunt'); has('windupCaptain', 'grunt'); none('windupAlly');
  const gL = ev.out.windupPlayer[0], gC = ev.out.windupCaptain[0];
  check(gC && gL && gC.rate < gL.rate * 0.9 && gC.volume > gL.volume, `captain grunt should be lower and louder: ${JSON.stringify([gL, gC])}`);
  has('combo', 'comboSting'); has('waveStart', 'warcry', 'horn', 'drum'); has('weaponSwitch', 'draw');
  check(ev.said.length === 3, `voice: expected 3 say() calls, got ${ev.said.length}`);
  const leo = ev.said[0] || {}, look = ev.said[1] || {}, unk = ev.said[2] || {};
  check(leo.text === 'Come and take them.' && leo.pitch < 0.85 && leo.rate < 0.95, `Leonidas voice ${JSON.stringify(leo)}`);
  check(look.pitch > 1 && look.rate > 1, `Lookout voice ${JSON.stringify(look)}`);
  check(unk.pitch === 0.9 && unk.rate === 0.95, `default voice ${JSON.stringify(unk)}`);
  check(ev.beatsLow >= 2 && ev.beatsHigh === 0, `heartbeat: ${ev.beatsLow} beats at 18 HP, ${ev.beatsHigh} at 80 HP`);
  check(ev.cap.voices <= 24, `voice cap: ${ev.cap.voices} live voices`);
  const [left, front, far] = ev.posLog;
  check(left && front && far && left.pan < -0.5 && Math.abs(front.pan) < 0.1 && far.volume < front.volume * 0.2 && left.volume < front.volume, `positional ${JSON.stringify(ev.posLog)}`);
  console.log(`events ok: ${Object.keys(ev.out).length} cases; voice says ${JSON.stringify(ev.said)}`);
  console.log(`heartbeat beats in 1.5 s at 18 HP: ${ev.beatsLow}; at 80 HP: ${ev.beatsHigh}; voice cap after 80 plays: ${ev.cap.voices} live, ${ev.cap.stolen} stolen, ${ev.cap.dropped} dropped`);
  console.log(`positional: left ${JSON.stringify(left)} front ${JSON.stringify(front)} 40 m ${JSON.stringify(far)}`);

  // Output level: music and ambience are audible, follow volumes, and music 'none' fades out.
  const lv = await page.evaluate(async () => {
    const a = window.__audio, ctx = window.__lab.ctx;
    const an = a.analyser();
    const buf = new Float32Array(an.fftSize);
    const rms = async (ms = 600) => {
      let s = 0, n = 0;
      const end = performance.now() + ms;
      while (performance.now() < end) {
        an.getFloatTimeDomainData(buf);
        for (const v of buf) { s += v * v; n++; }
        await new Promise((r) => setTimeout(r, 40));
      }
      return Math.sqrt(s / n);
    };
    const wait = (ms) => new Promise((r) => setTimeout(r, ms));
    a.setAmbience('none');
    a.setMusic('none');
    await wait(3500);
    const silent = await rms();
    a.setMusic('battle');
    await wait(3000);
    const music = await rms();
    ctx.settings.music = 0;
    a.applyVolumes();
    await wait(400);
    const muted = await rms();
    ctx.settings.music = 0.55;
    a.applyVolumes();
    a.setMusic('none');
    a.setAmbience('battle');
    await wait(3500);
    const amb = await rms();
    a.setAmbience('calm');
    await wait(3500);
    const calm = await rms();
    a.setAmbience('none');
    await wait(3600);
    const after = await rms();
    return { silent, music, muted, amb, calm, after, st: a.stats() };
  });
  console.log(`output RMS: silent ${lv.silent.toFixed(4)}, battle music ${lv.music.toFixed(4)}, music volume 0 ${lv.muted.toFixed(4)}, battle ambience ${lv.amb.toFixed(4)}, calm ambience ${lv.calm.toFixed(4)}, after none ${lv.after.toFixed(4)}`);
  check(lv.silent < 1e-4 && lv.after < 2e-3, 'silence when music and ambience are none');
  check(lv.music > 0.02 && lv.muted < 0.002, 'music audible and follows its volume');
  check(lv.amb > 0.02 && lv.calm > 0.02, 'ambience audible');
  // A screenshot of the lab with the battle mix running and a burst of combat sounds.
  await page.evaluate(async () => {
    const a = window.__audio;
    a.setMusic('battle');
    a.setAmbience('battle');
    await new Promise((r) => setTimeout(r, 3000));
    for (const [n, d] of [['whoosh', 0], ['flesh', 0.12], ['clang', 0.5], ['grunt', 0.7], ['fleshHeavy', 0.95], ['death', 1.05]]) a.play(n, { delay: d });
    await new Promise((r) => setTimeout(r, 1150));
  });
  await page.screenshot({ path: path.join(OUT, 'audio-lab.png') });
  await page.evaluate(() => { window.__audio.setMusic('none'); window.__audio.setAmbience('none'); });
  check(page.errors.length === 0, 'page errors: ' + page.errors.join('\n'));
  await page.close();

  // ---------------------------------------------------------------- 2. main-thread fallback
  const p2 = await l.newPage();
  await p2.addInitScript(() => { window.Worker = undefined; });
  await p2.goto(`${l.base}/tests/sandbox/audio-lab.html`);
  await p2.waitForFunction(() => window.__audio, null, { timeout: 60000 });
  const st2 = await waitRendered(p2);
  console.log(`\nmain-thread fallback: ${st2.jobsDone} jobs, ${st2.renderMs.toFixed(0)} ms of main-thread slices, boot→all ready ${(st2.doneAt - st2.startedAt).toFixed(0)} ms wall, workers=${st2.workers}`);
  check(st2.workers === 0 && st2.ready >= Object.keys(SFX).length + 6, 'fallback did not render everything');
  const fb = await pullBuffer(p2, 'clang');
  const ref = renderSfx('clang', fb.sr, 0);
  let d = 0;
  for (let i = 0; i < ref.length; i++) d = Math.max(d, Math.abs(ref[i] - fb.data[i]));
  check(d < 1e-4, `fallback clang differs by ${d}`);
  check(p2.errors.length === 0, 'fallback page errors: ' + p2.errors.join('\n'));
  await p2.close();

  // A worker that loads but never answers (a WebView that can't fetch the worker script).
  const p3 = await l.newPage();
  await p3.addInitScript(() => {
    window.Worker = class { postMessage() {} terminate() {} };
  });
  await p3.goto(`${l.base}/tests/sandbox/audio-lab.html`);
  await p3.waitForFunction(() => window.__audio, null, { timeout: 60000 });
  const st3 = await waitRendered(p3);
  console.log(`silent worker: fell back after the watchdog; workers=${st3.workers}, ${st3.ready} buffers ready, boot→all ready ${(st3.doneAt - st3.startedAt).toFixed(0)} ms`);
  check(st3.workers === 0 && st3.ready >= Object.keys(SFX).length + 6, 'silent-worker fallback did not render everything');
  check(p3.errors.length === 0, 'silent-worker page errors: ' + p3.errors.join('\n'));
  await p3.close();
} finally {
  await l.close();
}

// ---------------------------------------------------------------- 4. autoplay policy and states
{
  let pw;
  try { pw = await import('playwright'); } catch { pw = await import('/opt/node22/lib/node_modules/playwright/index.mjs'); }
  const { startServer } = await import('../serve.mjs');
  const server = await startServer(0);
  const browser = await pw.chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=user-gesture-required'] });
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 500 } });
    await page.goto(`http://127.0.0.1:${server.address().port}/tests/sandbox/audio-lab.html?manual=1`);
    await page.waitForFunction(() => window.__audio, null, { timeout: 60000 });
    const states = [];
    const state = () => page.evaluate(() => window.__audio.state);
    const waitState = (st) => page.waitForFunction((x) => window.__audio.state === x, st, { timeout: 5000 }).catch(() => {});
    const playNull = async () => ((await page.evaluate(() => window.__audio.play('uiTap'))) === null ? 'null' : 'handle');
    states.push(['created', await state()]);
    if (states[0][1] !== 'running') {
      states.push(['play before unlock', await playNull()]);
      await page.mouse.click(400, 480);
      await waitState('running');
      states.push(['first tap', await state()]);
    }
    // The browser suspends the context behind our back (autoplay policy, interruption): a tap restarts it.
    await page.evaluate(() => window.__audio.context.suspend());
    await waitState('suspended');
    states.push(['browser-suspended', await state()]);
    states.push(['play while suspended', await playNull()]);
    await page.mouse.click(400, 480);
    await waitState('running');
    states.push(['tap', await state()]);
    // Our own suspend (app paused) is not undone by taps, only by resume().
    await page.evaluate(() => window.__audio.suspend());
    await waitState('suspended');
    states.push(['suspend()', await state()]);
    await page.mouse.click(400, 480);
    await new Promise((r) => setTimeout(r, 300));
    states.push(['tap while app-suspended', await state()]);
    states.push(['play while app-suspended', await playNull()]);
    await page.evaluate(() => window.__audio.resume());
    await waitState('running');
    states.push(['resume()', await state()]);
    states.push(['play after resume', await playNull()]);
    console.log('\nAudioContext states:', states.map(([k, v]) => `${k}: ${v}`).join(' → '));
    const m = Object.fromEntries(states);
    if (m.created !== 'running') check(m['play before unlock'] === 'null' && m['first tap'] === 'running', 'first gesture unlocks');
    else console.log('(headless Chromium starts contexts running regardless of --autoplay-policy; the gesture path is exercised via a browser-side suspend)');
    check(m['browser-suspended'] === 'suspended' && m['play while suspended'] === 'null' && m.tap === 'running', 'a tap restarts a browser-suspended context');
    check(m['suspend()'] === 'suspended' && m['tap while app-suspended'] === 'suspended' && m['play while app-suspended'] === 'null', 'suspend() holds until resume()');
    check(m['resume()'] === 'running' && m['play after resume'] === 'handle', 'resume() restarts playback');
  } finally {
    await browser.close();
    server.close();
  }
}

if (failures.length) {
  console.error(`\n${failures.length} FAILURE(S):\n - ` + failures.join('\n - '));
  process.exitCode = 1;
} else {
  console.log('\nALL AUDIO CHECKS PASSED');
}
