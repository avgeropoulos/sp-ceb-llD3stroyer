// The recorded music cues in js/audio/audio.js, against a fake WebAudio (tests/unit/fake-webaudio.mjs):
// which cue plays when, the 48 kHz decode, the fallback to the procedural loops, the crossfades, the
// release of decoded buffers (one cue held at a time; peak decoded bytes measured), the ducking under a
// voice line, and the loudness match to the procedural loops the cues replace (DESIGN.md §11).
// node --test spartan/tests/unit/music-cues.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  installFakes, settle, fakeFile, FakeAudioContext, FakeOfflineAudioContext, FakeWorker,
} from './fake-webaudio.mjs';
import { createAudio, cueLevel, MUSIC_REF_LUFS, MUSIC_DUCK, MUSICBANK } from '../../src/main/assets/game/js/audio/audio.js';
import { LOOPS, renderLoop } from '../../src/main/assets/game/js/audio/loops.js';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';

const fakes = installFakes();
const CUES = Object.keys(MUSICBANK);
const SR = 48000;
const FADE = 2.5;
const TAIL_FRAMES = 10 * SR + 1;
const stereoBytes = (cue) => MUSICBANK[cue].samples * 2 * 4;
const TAIL_BYTES = TAIL_FRAMES * 2 * 4;
const LEO = 'audio/voice/test-leonidas.ogg';
const near = (a, b, eps, msg) => assert.ok(Math.abs(a - b) <= eps, `${msg}: ${a} vs ${b}`);

/** A fresh engine on a fresh fake context. */
function harness({ rate = SR, quality = 'medium', offline = true, state = 'running', files = {}, deviceMemory = 8 } = {}) {
  FakeAudioContext.rate = rate;
  FakeAudioContext.initialState = state;
  window.OfflineAudioContext = offline ? FakeOfflineAudioContext : undefined;
  window.navigator.deviceMemory = deviceMemory;
  FakeWorker.requests.length = 0;
  FakeWorker.held.length = 0;
  FakeWorker.holdLoops = false;
  FakeOfflineAudioContext.decodes.length = 0;
  FakeOfflineAudioContext.made.length = 0;
  fakes.state.fetched.length = 0;
  fakes.state.files.clear();
  fakes.state.gates.clear();
  for (const cue of CUES) fakes.state.files.set(MUSICBANK[cue].file, fakeFile({ cue, channels: 2, length: MUSICBANK[cue].samples }));
  fakes.state.files.set(LEO, fakeFile({ clip: 'leo', channels: 1, length: 2 * SR }));
  for (const [k, v] of Object.entries(files)) fakes.state.files.set(k, v);
  const events = new EventBus();
  const ctx = {
    settings: { master: 0.9, music: 0.55, sfx: 1, voice: true }, events, mode: 'title', quality, time: { scale: 1 },
    camera: null, player: null, platform: { voice: { say() {}, stop() {} } },
  };
  const audio = createAudio(ctx);
  const ac = FakeAudioContext.last;
  let peakHeld = 0; // the most full cues ever decoded at once
  const h = {
    audio, ac, ctx, events,
    cues: () => audio.stats().cues,
    /** Sources that played the decoded cue `name` (oldest first). */
    cueSources: (name) => ac.sources.filter((s) => s.firstBuffer && s.firstBuffer.tag === name),
    /** The copies a cue hands off to. */
    tails: () => ac.sources.filter((s) => s.firstBuffer && !s.firstBuffer.tag && s.firstBuffer.sampleRate === SR && s.firstBuffer.length <= TAIL_FRAMES && s.firstBuffer.length > SR),
    /** Sources of procedural music loops (rendered at 24 kHz). */
    synthSources: () => ac.sources.filter((s) => s.firstBuffer && s.firstBuffer.sampleRate === 24000),
    /** Moves wall clock and audio clock on together, a frame of game at a time. */
    async step(seconds, dt = 0.05) {
      for (let t = 0; t < seconds - 1e-9; t += dt) {
        fakes.state.clock += dt * 1000;
        ac.advance(dt);
        audio.update(dt);
        await settle(3);
        peakHeld = Math.max(peakHeld, h.cues().ready.length);
      }
    },
    get peakHeld() {
      return Math.max(peakHeld, h.cues().ready.length);
    },
    /** Lets the (fake) render worker work through its queue. */
    async drain() {
      for (let i = 0; i < 400 && audio.stats().pending > 0; i++) await settle(1);
      await settle();
    },
  };
  return h;
}

test('the title is decoded at boot at 48 kHz, ahead of the title screen, and plays as a whole-buffer loop', async () => {
  const h = harness();
  await h.drain();
  let c = h.cues();
  assert.deepEqual(c.ready, ['title'], 'the title cue is decoded ahead');
  assert.equal(c.playing, 'none');
  assert.ok(fakes.state.fetched.some((u) => u.endsWith('/game/audio/music/title.ogg')), `fetched relative to the game root: ${fakes.state.fetched}`);
  assert.equal(FakeOfflineAudioContext.decodes.length, 1, 'decoded by an OfflineAudioContext');
  assert.equal(FakeOfflineAudioContext.made[0].sampleRate, SR, '…at 48 kHz');
  assert.equal(h.ac.decodes.length, 0, 'never by the game context');
  // The procedural music loops are not rendered at boot (only the ambience beds).
  const loops = FakeWorker.requests.filter((r) => r.kind === 'loop').map((r) => r.name).sort();
  assert.deepEqual(loops, ['cicadas', 'din', 'sea', 'wind']);

  h.audio.setMusic('title');
  const [src] = h.cueSources('title');
  assert.ok(src, 'a source plays the decoded title');
  assert.equal(src.loop, true);
  assert.equal(src.loopEnd, 0, 'the whole buffer loops (the decode is exactly the loop)');
  assert.equal(src.firstBuffer.length, MUSICBANK.title.samples);
  assert.equal(src.firstBuffer.numberOfChannels, 2);
  const w = src.when;
  near(w, h.ac.currentTime + 0.05, 1e-9, 'starts at once');
  near(src.gainAt(w), 0, 1e-9, 'fades in from silence');
  near(src.gainAt(w + FADE / 2), cueLevel('title') / 2, 1e-6, 'halfway up');
  near(src.gainAt(w + FADE), cueLevel('title'), 1e-6, 'to the cue level');
  c = h.cues();
  assert.equal(c.playing, 'title');
  assert.equal(c.source, 'file');
  assert.equal(c.bytes, stereoBytes('title'));
  h.audio.dispose();
});

test('a cue change crossfades from a copy of the old cue, whose buffer goes before the next is decoded', async () => {
  const h = harness();
  await settle();
  h.audio.setMusic('title');
  await h.step(20);
  const title = h.cueSources('title')[0];
  const T0 = h.ac.currentTime;
  h.audio.setMusic('battle');
  // The title hands off to a 10 s copy of what comes next, sample-aligned, within 0.1 s.
  const [tail] = h.tails();
  assert.ok(tail, 'a tail copy starts');
  near(tail.when, T0 + 0.05, 1e-9, 'the copy starts at once');
  assert.ok(title.stopAt > tail.when && title.stopAt <= tail.when + 0.05, `the title's own source stops right after (${title.stopAt})`);
  near(tail.firstBuffer.length, TAIL_FRAMES, 0, 'tail length');
  const len = MUSICBANK.title.samples;
  const pos = ((tail.when - title.when) * SR) % len;
  const F = Math.floor(pos);
  near(tail.offset, (pos - F) / SR, 1e-12, 'the copy starts at the same sub-sample position');
  const fill = title.firstBuffer.fill;
  for (const k of [0, 1, 777, 123456, TAIL_FRAMES - 1]) {
    for (const ch of [0, 1]) near(tail.firstBuffer.getChannelData(ch)[k], Math.fround(fill(ch, (F + k) % len)), 0, `tail frame ${k} ch ${ch}`);
  }
  // A 20 ms crossfade between the two (the same samples); the cue's fade gain carries on unchanged.
  near(title.gainAt(tail.when + 0.01), cueLevel('title') * 0.5, 1e-6, 'old source half out');
  near(tail.gainAt(tail.when + 0.01), cueLevel('title') * 0.5, 1e-6, 'copy half in');
  near(tail.gainAt(tail.when + 0.03), cueLevel('title'), 1e-6, 'copy at the cue level');
  assert.ok(!fakes.state.fetched.some((u) => u.endsWith('battle.ogg')), 'battle waits until the title buffer is gone');

  await h.step(0.2);
  let c = h.cues();
  assert.deepEqual(c.ready, ['battle'], 'the title is released, battle decoded');
  const ev = c.history.map((e) => `${e.ev}:${e.cue}`);
  const at = (s) => ev.indexOf(s);
  assert.ok(at('tail:title') < at('released:title') && at('released:title') < at('load:battle') && at('load:battle') < at('decoded:battle') && at('decoded:battle') < at('start:battle'), ev.join(' '));
  assert.equal(c.bytes, stereoBytes('battle') + TAIL_BYTES);

  // The crossfade: battle fades in while the copy fades out, over the same 2.5 s.
  const battle = h.cueSources('battle')[0];
  const w = battle.when;
  near(battle.gainAt(w + FADE / 2), 0.5 * cueLevel('battle'), 1e-6, 'battle halfway in');
  near(tail.gainAt(w + FADE / 2), 0.5 * cueLevel('title'), 1e-6, 'title halfway out');
  near(tail.gainAt(w + FADE), 0, 1e-9, 'title out');
  near(battle.gainAt(w + FADE), cueLevel('battle'), 1e-9, 'battle in');
  assert.ok(tail.stopAt <= w + FADE + 0.06, 'the copy stops after its fade');
  await h.step(3);
  c = h.cues();
  assert.equal(c.playing, 'battle');
  assert.equal(c.outgoing, 0);
  assert.equal(c.bytes, stereoBytes('battle'), 'only battle is held');
  assert.equal(title.buffer, null, "the title's source let go of its buffer");
  assert.equal(tail.buffer, null);
  assert.ok(c.peakBytes <= stereoBytes('battle') + TAIL_BYTES, `peak ${c.peakBytes}`);
  assert.equal(h.peakHeld, 1, 'never two cues decoded at once');
  h.audio.dispose();
});

test('Day 3 plays lastStand from its recording (no procedural render) at the old lament level', async () => {
  const h = harness();
  await settle();
  h.audio.setMusic('battle');
  await h.step(4);
  assert.equal(h.cues().playing, 'battle');
  h.audio.setMusic('lastStand'); // waves: beginEntry of Day 3, then the dayStart card
  h.events.emit('dayStart', { day: 3 });
  await h.step(4);
  const c = h.cues();
  assert.equal(c.playing, 'lastStand');
  assert.equal(c.source, 'file');
  near(c.level, Math.pow(10, (MUSIC_REF_LUFS.lastStand - MUSICBANK.lastStand.lufs) / 20), 1e-12, 'level');
  assert.deepEqual(c.ready, ['lastStand']);
  assert.ok(!FakeWorker.requests.some((r) => r.kind === 'loop' && MUSICBANK[r.name]), 'no procedural music rendered');
  assert.ok(c.peakBytes <= stereoBytes('battle') + TAIL_BYTES, `peak ${c.peakBytes}`);
  h.audio.dispose();
});

test('a cue that cannot be fetched or decoded falls back to its procedural loop; the others still play', async () => {
  for (const [why, file] of [['404', 404], ['undecodable', fakeFile({ fail: true })], ['wrong length', fakeFile({ cue: 'battle', channels: 2, length: MUSICBANK.battle.samples - 1 })]]) {
    const h = harness({ files: { [MUSICBANK.battle.file]: file } });
    await settle();
    h.audio.setMusic('title');
    await h.step(4);
    assert.equal(h.cues().source, 'file', `${why}: the title still plays its recording`);
    h.audio.setMusic('battle');
    await h.step(1);
    const c = h.cues();
    assert.deepEqual(c.failed, ['battle'], `${why}: battle failed`);
    assert.ok(FakeWorker.requests.some((r) => r.kind === 'loop' && r.name === 'battle'), `${why}: the procedural battle loop is rendered`);
    assert.equal(c.playing, 'battle');
    assert.equal(c.source, 'synth', `${why}: and plays`);
    assert.equal(c.level, 1, `${why}: at its own level`);
    const [synth] = h.synthSources();
    assert.equal(synth.loop, true);
    await h.step(3);
    assert.equal(h.cues().bytes, 0, `${why}: the title's copy is gone after the crossfade`);
    // Back to the title: its recording again.
    h.audio.setMusic('title');
    await h.step(4);
    assert.equal(h.cues().source, 'file', `${why}: title from its recording again`);
    h.audio.dispose();
  }
});

test('a recording still loading 3 s after it is wanted gets its procedural loop as a stand-in, then takes over', async () => {
  const h = harness();
  const open = fakes.gate(MUSICBANK.battle.file);
  await settle();
  h.audio.setMusic('title');
  await h.step(5);
  h.audio.setMusic('battle');
  await h.step(2.5);
  assert.equal(h.cues().playing, 'none', 'the title copy plays on while battle loads');
  assert.equal(h.cues().loading, 'battle');
  assert.ok(!FakeWorker.requests.some((r) => r.kind === 'loop' && r.name === 'battle'), 'no stand-in yet');
  await h.step(1);
  let c = h.cues();
  assert.equal(c.standIns, 1);
  assert.equal(c.playing, 'battle');
  assert.equal(c.source, 'synth');
  open();
  await h.step(0.5);
  c = h.cues();
  assert.equal(c.source, 'file', 'the recording takes over');
  const [synth] = h.synthSources();
  const b = h.cueSources('battle')[0];
  near(synth.gainAt(b.when + FADE / 2), synth.gainAt(b.when) / 2, 1e-6, 'the stand-in fades out under it');
  near(synth.gainAt(b.when + FADE), 0, 1e-9, '…to silence');
  near(b.gainAt(b.when + FADE), cueLevel('battle'), 1e-9, 'while the recording fades in');
  await h.step(3);
  assert.ok(synth.ended && h.cues().outgoing === 0);
  h.audio.dispose();
});

test('a stalled main thread does not count toward the stand-in wait', async () => {
  // A heavy first battle frame can block the page for seconds (12 s under SwiftShader in
  // tests/e2e/music-cues.mjs): the audio thread plays on, the title copy runs out, and only then can the
  // title be released and battle decoded. That stall is no reason to render the procedural battle loop.
  const h = harness();
  const open = fakes.gate(MUSICBANK.battle.file);
  await settle();
  h.audio.setMusic('title');
  await h.step(5);
  h.audio.setMusic('battle');
  fakes.state.clock += 12000; // 12 s with no frame
  h.ac.advance(12);
  await settle(3);
  await h.step(1);
  let c = h.cues();
  assert.equal(c.loading, 'battle');
  assert.equal(c.standIns, 0);
  assert.ok(!FakeWorker.requests.some((r) => r.kind === 'loop' && r.name === 'battle'), 'no stand-in render queued after the stall');
  open();
  await h.step(0.5);
  c = h.cues();
  assert.equal(c.playing, 'battle');
  assert.equal(c.source, 'file');
  assert.ok(!FakeWorker.requests.some((r) => r.kind === 'loop' && r.name === 'battle'));
  h.audio.dispose();
});

test('music never goes through the 44.1 kHz context decoder', async () => {
  // A 44.1 kHz context: decoded by a 48 kHz OfflineAudioContext, played resampled.
  let h = harness({ rate: 44100 });
  await settle();
  h.audio.setMusic('title');
  assert.equal(h.cues().source, 'file');
  assert.equal(h.cueSources('title')[0].firstBuffer.sampleRate, SR);
  assert.equal(h.ac.decodes.length, 0, 'not ac.decodeAudioData');
  h.audio.dispose();
  // No OfflineAudioContext and a 44.1 kHz context: no recording is even fetched; the procedural title and
  // battle loops render behind the title screen, as before the recordings.
  h = harness({ rate: 44100, offline: false });
  await h.drain();
  assert.ok(!fakes.state.fetched.some((u) => u.includes('audio/music/')), 'nothing fetched');
  assert.deepEqual(FakeWorker.requests.filter((r) => r.kind === 'loop').map((r) => r.name).sort(), ['battle', 'cicadas', 'din', 'sea', 'title', 'wind']);
  h.audio.setMusic('title');
  await h.step(0.2);
  assert.equal(h.cues().source, 'synth');
  assert.equal(h.ac.decodes.length, 0);
  h.audio.dispose();
  // No OfflineAudioContext but a 48 kHz context: that context decodes exactly.
  h = harness({ rate: SR, offline: false });
  await settle();
  h.audio.setMusic('title');
  assert.equal(h.cues().source, 'file');
  assert.equal(h.ac.decodes.length, 1);
  h.audio.dispose();
});

test('low memory down-mixes each cue to one channel right after decoding', async () => {
  for (const opts of [{ quality: 'low' }, { deviceMemory: 2 }]) {
    const h = harness(opts);
    await settle();
    h.audio.setMusic('title');
    const src = h.cueSources('title')[0] || h.ac.sources.find((s) => s.firstBuffer && s.firstBuffer.length === MUSICBANK.title.samples);
    assert.equal(src.firstBuffer.numberOfChannels, 1, JSON.stringify(opts));
    const c = h.cues();
    assert.equal(c.mono, true);
    assert.equal(c.bytes, MUSICBANK.title.samples * 4);
    assert.equal(c.peakBytes, MUSICBANK.title.samples * 12, 'stereo and mono for the moment of the down-mix');
    h.audio.setMusic('battle');
    await h.step(4);
    assert.equal(h.cues().bytes, MUSICBANK.battle.samples * 4);
    assert.ok(h.cues().peakBytes <= MUSICBANK.battle.samples * 12 + TAIL_FRAMES * 4);
    h.audio.dispose();
  }
});

test('a voice line ducks the music, deeper under a recorded cue', async () => {
  const h = harness();
  await settle();
  h.ctx.mode = 'battle';
  h.audio.setMusic('battle');
  await h.step(3);
  assert.equal(h.cues().duck, 1);
  h.events.emit('voice', { key: 'molonLabe', speaker: 'Leonidas', text: 'Come and take them.', clip: { file: LEO, dur: 2 } });
  await h.step(0.3);
  assert.ok(h.audio.stats().voice.speaking, 'the clip plays');
  assert.equal(h.cues().duck, MUSIC_DUCK.file);
  await h.step(2.5);
  assert.equal(h.cues().duck, 1, 'and the music comes back after the line');
  // Under the procedural loop the duck stays as it was.
  const g = harness({ files: { [MUSICBANK.battle.file]: 404 } });
  await settle();
  g.ctx.mode = 'battle';
  g.audio.setMusic('battle');
  await g.step(1);
  assert.equal(g.cues().source, 'synth');
  g.events.emit('voice', { key: 'molonLabe', speaker: 'Leonidas', text: 'Come and take them.', clip: { file: LEO, dur: 2 } });
  await g.step(0.3);
  assert.equal(g.cues().duck, MUSIC_DUCK.synth);
  assert.ok(MUSIC_DUCK.file < MUSIC_DUCK.synth);
  h.audio.dispose();
  g.audio.dispose();
});

test('in a suspended context a cue that gives way is stopped and released at once', async () => {
  const h = harness({ state: 'suspended' });
  await settle();
  h.audio.setMusic('title');
  h.audio.setMusic('battle');
  await settle();
  const c = h.cues();
  assert.equal(h.tails().length, 0, 'no copy: nobody hears it');
  assert.equal(h.cueSources('title')[0].buffer, null);
  assert.deepEqual(c.ready, ['battle']);
  assert.equal(c.bytes, stereoBytes('battle'));
  assert.equal(c.playing, 'battle');
  h.audio.dispose();
});

test("setMusic('none') fades the cue out and releases everything", async () => {
  const h = harness();
  await settle();
  h.audio.setMusic('title');
  await h.step(5);
  h.audio.setMusic('none');
  const [tail] = h.tails();
  const t = h.ac.currentTime;
  near(tail.gainAt(t + 0.05 + FADE / 2), cueLevel('title') / 2, 0.02, 'fading out at once');
  await h.step(3);
  const c = h.cues();
  assert.equal(c.playing, 'none');
  assert.equal(c.outgoing, 0);
  assert.equal(c.bytes, 0);
  assert.deepEqual(c.ready, []);
  h.audio.dispose();
});

test('the campaign holds one decoded cue at a time; peak decoded music stays far under 80 MB', async () => {
  // As the game changes music: boot to the title, Day 1 and 2 (battle; a new day re-asks for battle),
  // the Agoge (none) and back, Day 3 (lastStand), game over and quit to the title.
  const h = harness();
  await settle();
  const walk = [['title', 20], ['battle', 30], ['battle', 30], ['title', 5], ['none', 4], ['title', 3], ['battle', 20], ['lastStand', 30], ['title', 6]];
  for (const [cue, wait] of walk) {
    h.audio.setMusic(cue);
    await h.step(wait, 0.1);
    const c = h.cues();
    assert.equal(c.playing, cue === 'none' ? 'none' : cue, `${cue} plays`);
    if (cue !== 'none') assert.equal(c.source, 'file');
  }
  let c = h.cues();
  assert.equal(h.peakHeld, 1, 'never two cues decoded at once');
  assert.equal(c.bytes, stereoBytes('title'));
  // The largest cue (battle, 46.1 MB) plus one 10 s copy (3.8 MB) of the cue it replaced.
  assert.ok(c.peakBytes <= stereoBytes('battle') + TAIL_BYTES, `peak ${(c.peakBytes / 1e6).toFixed(1)} MB`);
  console.log(`# peak decoded music over the campaign: ${(c.peakBytes / 1e6).toFixed(2)} MB (${c.decodes} decodes)`);
  h.audio.dispose();
  assert.equal(h.cues().bytes, 0, 'dispose releases it all');

  // Cues switched faster than anyone taps menus: still one decoded cue, and older copies are cut short.
  const s = harness();
  await settle();
  const storm = [['title', 6], ['battle', 1], ['title', 0.3], ['battle', 0.15], ['lastStand', 6], ['lastStand', 1], ['title', 4], ['none', 0.5],
    ['battle', 6], ['battle', 2], ['lastStand', 0.05], ['title', 0.05], ['lastStand', 0.1], ['battle', 0.2], ['lastStand', 8]];
  for (const [cue, wait] of storm) {
    s.audio.setMusic(cue);
    await s.step(wait);
  }
  c = s.cues();
  assert.equal(c.playing, 'lastStand');
  assert.equal(c.source, 'file');
  assert.equal(c.bytes, stereoBytes('lastStand'));
  assert.equal(s.peakHeld, 1, 'never two cues decoded at once');
  assert.ok(c.peakBytes < 60e6, `peak ${(c.peakBytes / 1e6).toFixed(1)} MB`);
  console.log(`# peak decoded music with cues switched every 0.05-0.3 s: ${(c.peakBytes / 1e6).toFixed(2)} MB`);
  s.audio.dispose();
});

// ---- Levels -----------------------------------------------------------------------------------

/** BS.1770-4 integrated loudness of a mono loop played on both channels (L = R), measured circularly. */
function loopLufs(x, fs) {
  // K-weighting for any sample rate (libebur128's derivation).
  let f0 = 1681.974450955533, Q = 0.7071752369554196, K = Math.tan(Math.PI * f0 / fs);
  const Vh = Math.pow(10, 3.999843853973347 / 20), Vb = Math.pow(Vh, 0.4996667741545416);
  let a0 = 1 + K / Q + K * K;
  const s1 = { b: [(Vh + Vb * K / Q + K * K) / a0, 2 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0], a: [2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0] };
  f0 = 38.13547087602444;
  Q = 0.5003270373238773;
  K = Math.tan(Math.PI * f0 / fs);
  a0 = 1 + K / Q + K * K;
  const s2 = { b: [1, -2, 1], a: [2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0] };
  const n = x.length, y = new Float64Array(3 * n);
  const st = [[0, 0, 0, 0], [0, 0, 0, 0]];
  for (let i = 0; i < 3 * n; i++) {
    let v = x[i % n];
    for (const [k, s] of [s1, s2].entries()) {
      const z = st[k];
      const o = s.b[0] * v + s.b[1] * z[0] + s.b[2] * z[1] - s.a[0] * z[2] - s.a[1] * z[3];
      z[1] = z[0];
      z[0] = v;
      z[3] = z[2];
      z[2] = o;
      v = o;
    }
    y[i] = v;
  }
  const B = Math.round(0.4 * fs), S = Math.round(0.1 * fs);
  const blocks = [];
  for (let s = n; s < 2 * n; s += S) {
    let e = 0;
    for (let i = s; i < s + B; i++) e += y[i] * y[i];
    blocks.push((2 * e) / B); // two channels
  }
  const L = (z) => -0.691 + 10 * Math.log10(z);
  const abs = blocks.filter((z) => L(z) > -70);
  const rel = L(abs.reduce((a, b) => a + b, 0) / abs.length) - 10;
  const gated = abs.filter((z) => L(z) > rel);
  return L(gated.reduce((a, b) => a + b, 0) / gated.length);
}

test('each recorded cue is trimmed to the measured loudness of the procedural loop it replaces', () => {
  for (const cue of CUES) {
    const measured = loopLufs(renderLoop(cue), LOOPS[cue].sr);
    console.log(`# ${cue}: procedural loop ${measured.toFixed(2)} LUFS (MUSIC_REF_LUFS ${MUSIC_REF_LUFS[cue]}), recording ${MUSICBANK[cue].lufs} LUFS, trim ${(20 * Math.log10(cueLevel(cue))).toFixed(2)} dB`);
    near(measured, MUSIC_REF_LUFS[cue], 0.3, `${cue} reference loudness`);
    near(20 * Math.log10(cueLevel(cue)), MUSIC_REF_LUFS[cue] - MUSICBANK[cue].lufs, 1e-9, `${cue} trim`);
  }
  // Day 3 stays the quieter cue, as the old lament was: Leonidas' and Dienekes' last lines sit over it.
  assert.ok(cueLevel('lastStand') < cueLevel('battle'));
});
