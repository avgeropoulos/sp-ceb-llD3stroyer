// The recorded music cues (audio/music/*.ogg, js/audio/musicbank.js) in the real game, headless Chromium:
// the title cue is decoded at 48 kHz at boot and plays as a whole-buffer loop; battle starts with the
// battle (the title hands off to a 10 s copy, is released, then battle is decoded); Day 3 plays
// lastStand; the Greeks' voice clips still play and duck the music (to MUSIC_DUCK.file under a cue);
// pause and the app lifecycle keep the cue; a cue that 404s or won't decode falls back to its
// procedural loop; the low quality setting down-mixes to mono. Peak decoded music stays under 80 MB.
// Usage: node spartan/tests/e2e/music-cues.mjs
import fs from 'node:fs';
import { launch } from './lib.mjs';
import { MUSICBANK } from '../../src/main/assets/game/js/audio/musicbank.js';
import { cueLevel, MUSIC_DUCK } from '../../src/main/assets/game/js/audio/audio.js';

const SR = 48000;
const TAIL_BYTES = (10 * SR + 1) * 2 * 4; // the 10 s copy a cue hands off to (stereo)
const BUDGET = 80e6;
const stereo = (cue) => MUSICBANK[cue].samples * 2 * 4;
const mb = (b) => (b / 1e6).toFixed(1);

const l = await launch();
const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
  return cond;
};

/** Resident memory of the renderer processes (Linux), MB, after a full GC of the page (CDP). Informational. */
async function rendererRss(page) {
  try {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('HeapProfiler.collectGarbage');
    await cdp.detach();
    await new Promise((r) => setTimeout(r, 300));
  } catch { /* no CDP: RSS without a GC */ }
  let kb = 0;
  try {
    for (const pid of fs.readdirSync('/proc')) {
      if (!/^\d+$/.test(pid)) continue;
      try {
        const cmd = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8');
        if (!cmd.includes('--type=renderer')) continue;
        const m = /VmRSS:\s+(\d+)/.exec(fs.readFileSync(`/proc/${pid}/status`, 'utf8'));
        if (m) kb += Number(m[1]);
      } catch { /* gone */ }
    }
  } catch {
    return NaN;
  }
  return kb / 1024;
}

/** A page of the game with spies: TTS calls and every AudioBufferSourceNode started. */
async function openGame(query, setup) {
  const page = await l.newPage({ width: 960, height: 540 });
  if (setup) await setup(page);
  await page.addInitScript(() => {
    window.__sources = [];
    const start = AudioBufferSourceNode.prototype.start;
    AudioBufferSourceNode.prototype.start = function (...args) {
      const b = this.buffer;
      window.__sources.push({ len: b ? b.length : 0, rate: b ? b.sampleRate : 0, ch: b ? b.numberOfChannels : 0, loop: this.loop, loopStart: this.loopStart, loopEnd: this.loopEnd });
      return start.apply(this, args);
    };
  });
  await page.goto(`${l.game}?manual=1&seed=7&${query}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  check(await page.evaluate(() => window.__game.ready), `game boots (${query})`);
  await page.evaluate(() => {
    window.__said = [];
    window.__game.ctx.platform.voice.say = (text, o) => window.__said.push({ text, ...o });
    window.__game.ctx.audio.unlock();
  });
  const g = {
    page,
    /** One real game frame (drawn). Under SwiftShader the first frames of a battle can block the main
     *  thread for many seconds; the music must cope with that, and the waits below don't depend on it. */
    frame: () => page.evaluate(() => window.__game.step(1, 1 / 60)),
    cues: () => page.evaluate(() => window.__game.ctx.audio.stats().cues),
    stats: () => page.evaluate(() => window.__game.ctx.audio.stats()),
    sleep: (ms) => page.evaluate((ms) => new Promise((r) => setTimeout(r, ms)), ms),
    /** Runs the audio engine's per-frame update (all the music logic needs: hand-offs, stand-ins, the
     *  duck) at ~20 Hz until fn(cues) holds, or times out. */
    async until(fn, what, timeout = 30000) {
      const end = Date.now() + timeout;
      for (;;) {
        const c = await g.tick();
        if (fn(c)) return c;
        if (Date.now() > end) {
          check(false, `timed out waiting for ${what}: ${JSON.stringify({ ...c, history: c.history.slice(-8) })}`);
          return c;
        }
      }
    },
    tick: () => page.evaluate(async () => {
      const a = window.__game.ctx.audio;
      a.update(1 / 60);
      await new Promise((r) => setTimeout(r, 50));
      a.update(1 / 60);
      return a.stats().cues;
    }),
    /** Ticks on for `seconds` of wall clock. */
    async play(seconds) {
      const end = Date.now() + seconds * 1000;
      while (Date.now() < end) await g.tick();
    },
    /** RMS of the final output over `ms`, with the music at `music` volume. */
    rms: (music, ms = 700) => page.evaluate(async ([music, ms]) => {
      const a = window.__game.ctx.audio, s = window.__game.ctx.settings;
      s.music = music;
      a.applyVolumes();
      await new Promise((r) => setTimeout(r, 400));
      const an = a.analyser(), buf = new Float32Array(an.fftSize);
      let e = 0, n = 0;
      const end = performance.now() + ms;
      while (performance.now() < end) {
        an.getFloatTimeDomainData(buf);
        for (const v of buf) { e += v * v; n++; }
        await new Promise((r) => setTimeout(r, 30));
      }
      s.music = 0.55;
      a.applyVolumes();
      return Math.sqrt(e / n);
    }, [music, ms]),
    /** Sources started since the last call that played a whole decoded cue. */
    async cueSources(cue) {
      const all = await page.evaluate(() => window.__sources.splice(0));
      return all.filter((s) => s.rate === SR && s.len === MUSICBANK[cue].samples);
    },
    readyLoops: async () => Object.keys((await g.stats()).readyAt).filter((k) => /^loop:(title|battle|lastStand)$/.test(k)),
    errors: async (expected = () => false) => page.errors.concat(await page.evaluate(() => window.__game.errors)).filter((e) => !expected(e)),
  };
  return g;
}

const seq = (c) => c.history.map((e) => `${e.ev}:${e.cue}`);
const before = (c, ...evs) => {
  const s = seq(c);
  const at = evs.map((e) => s.lastIndexOf(e));
  return at.every((i, k) => i >= 0 && (k === 0 || i > at[k - 1]));
};

try {
  // ------------------------------------------------------------------ the campaign, stereo
  const g = await openGame('quality=medium');
  const rss = { boot: await rendererRss(g.page) };
  const sr = (await g.stats()).sampleRate;
  console.log(`AudioContext at ${sr} Hz`);

  // Title: decoded at boot, ahead of the title screen, then played.
  let c = await g.until((c) => c.playing === 'title' && c.source === 'file', 'the title cue to play');
  const boot = c.history.filter((e) => e.cue === 'title').map((e) => `${e.ev}@${e.t}s`).join(' ');
  console.log(`title: ${boot}; decode ${c.decodeMs} ms`);
  check(c.decodes === 1 && c.standIns === 0 && c.failures === 0, `the title cue decoded once, no stand-in (${c.decodes} decodes, ${c.standIns} stand-ins)`);
  check(c.bytes === stereo('title') && !c.mono, `one stereo 48 kHz buffer of exactly ${MUSICBANK.title.samples} samples held (${c.bytes} B)`);
  check(Math.abs(c.level - cueLevel('title')) < 1e-9, `at the procedural title loop's loudness (gain ${c.level.toFixed(3)})`);
  check((await g.readyLoops()).length === 0, 'no procedural music loop rendered at boot');
  await g.play(3);
  const on = await g.rms(0.55), off = await g.rms(0);
  console.log(`output RMS on the title screen: music on ${on.toFixed(4)}, music volume 0 ${off.toFixed(4)}`);
  check(on * on - off * off > 0.008 ** 2, 'the title cue is audible and follows the music volume');
  rss.title = await rendererRss(g.page);

  // Battle starts: the title hands off to its copy, is released, battle is decoded and fades in.
  await g.page.evaluate(() => window.__sources.splice(0));
  await g.page.evaluate(() => window.__game.startBattle(1));
  c = await g.cues();
  check(c.want === 'battle' && c.outgoing === 1 && seq(c).includes('tail:title'), `battle start: the title plays on from a copy (${c.outgoing} outgoing)`);
  let t = Date.now();
  await g.frame();
  console.log(`(the first battle frame took ${Date.now() - t} ms)`);
  c = await g.until((c) => c.playing === 'battle' && c.source === 'file', 'the battle cue to play');
  check(before(c, 'tail:title', 'released:title', 'load:battle', 'decoded:battle', 'start:battle'), `title released before battle is decoded: ${seq(c).slice(-6).join(' ')}`);
  let src = await g.cueSources('battle');
  check(src.length === 1 && src[0].ch === 2 && src[0].loop && src[0].loopEnd === 0, `battle loops its whole 48 kHz buffer ${JSON.stringify(src)}`);
  console.log(`battle: decode ${c.decodeMs} ms`);
  c = await g.until((c) => c.outgoing === 0, 'the title copy to fade out', 8000);
  check(c.bytes === stereo('battle') && c.ready.join() === 'battle', `then only battle is held (${mb(c.bytes)} MB)`);
  check(c.peakBytes <= stereo('battle') + TAIL_BYTES, `peak so far ${mb(c.peakBytes)} MB (battle + one 10 s copy)`);
  rss.battle = await rendererRss(g.page);

  // The voice clips still play, from their files, and duck the music deeper under a cue.
  await g.page.waitForFunction(() => window.__game.ctx.audio.stats().voice.cached >= 12, null, { timeout: 20000, polling: 100 }).catch(() => {});
  const v0 = (await g.stats()).voice;
  check(v0.failed === 0 && !v0.broken && v0.cached >= 12, `Day 1 clips decode (${v0.cached} cached)`);
  // (the campaign's own intro lines would duck it too: they need game frames, so none come here)
  await g.until((c) => c.duck === 1 && c.duckGain > 0.95, 'no line being said', 40000);
  const line = await g.page.evaluate(async () => {
    const bank = (await import('./js/audio/voicebank.js')).VOICEBANK;
    const v = bank.molonLabe[0];
    window.__said.length = 0;
    const p = { key: 'molonLabe', speaker: 'Leonidas', text: v.en, greek: v.greek, clip: v.clips.Leonidas };
    window.__game.ctx.events.emit('voice', p);
    return p;
  });
  await g.play(0.4);
  let st = await g.stats();
  check(st.voice.speaking === line.clip.file, `Leonidas' clip plays (${st.voice.speaking})`);
  check((await g.page.evaluate(() => window.__said.length)) === 0, 'and not through TTS');
  check(st.cues.playing === 'battle' && st.cues.duck === MUSIC_DUCK.file && Math.abs(st.cues.duckGain - MUSIC_DUCK.file) < 0.04, `the battle cue ducks under it (target ${st.cues.duck}, gain now ${st.cues.duckGain.toFixed(3)})`);
  c = await g.until((c) => c.duck === 1 && c.duckGain > 0.9, 'the music to come back after the line', 30000);
  check(c.playing === 'battle' && c.source === 'file', 'and comes back after the line');

  // Pause menu and the app going to the background keep the cue (no new decode).
  const decodes = c.decodes;
  await g.page.evaluate(() => window.__game.ctx.setMode('paused'));
  await g.play(0.5);
  await g.page.evaluate(() => window.__game.ctx.resume());
  await g.page.evaluate(() => window.__game.ctx.audio.suspend());
  await g.sleep(300);
  await g.page.evaluate(() => window.__game.ctx.audio.resume());
  await g.frame();
  c = await g.tick();
  check((await g.stats()).state === 'running' && c.playing === 'battle' && c.source === 'file' && c.decodes === decodes, 'pause, suspend and resume keep the battle cue');

  // A new day of the same cue changes nothing; Day 3 is lastStand.
  await g.page.evaluate(() => window.__game.startBattle(2));
  await g.frame();
  await g.play(0.5);
  c = await g.cues();
  check(c.playing === 'battle' && c.decodes === decodes && c.outgoing === 0, 'Day 2 keeps the battle cue playing');
  await g.page.evaluate(() => window.__sources.splice(0));
  await g.page.evaluate(() => window.__game.startBattle(3));
  t = Date.now();
  await g.frame();
  console.log(`(the first Day 3 frame took ${Date.now() - t} ms)`);
  c = await g.until((c) => c.playing === 'lastStand' && c.source === 'file', 'the lastStand cue on Day 3');
  check(before(c, 'tail:battle', 'released:battle', 'load:lastStand', 'decoded:lastStand', 'start:lastStand'), `battle released before lastStand is decoded: ${seq(c).slice(-6).join(' ')}`);
  check(Math.abs(c.level - cueLevel('lastStand')) < 1e-9, `lastStand at the old lament's loudness (gain ${c.level.toFixed(3)}, ${(20 * Math.log10(c.level)).toFixed(1)} dB)`);
  src = await g.cueSources('lastStand');
  check(src.length === 1 && src[0].ch === 2 && src[0].loop, 'lastStand loops its 48 kHz buffer');
  c = await g.until((c) => c.outgoing === 0, 'the battle copy to fade out', 8000);
  check(c.bytes === stereo('lastStand') && c.ready.join() === 'lastStand', `then only lastStand is held (${mb(c.bytes)} MB)`);
  check((await g.readyLoops()).length === 0, 'no procedural music loop rendered on Day 3 either');
  rss.lastStand = await rendererRss(g.page);

  // Quit to the title: the title cue again.
  await g.page.evaluate(() => window.__game.ctx.setMode('title'));
  await g.frame();
  c = await g.until((c) => c.playing === 'title' && c.source === 'file' && c.outgoing === 0, 'the title cue after quitting', 20000);
  check(c.bytes === stereo('title'), 'quit to the title: the title cue again, alone');
  check(c.peakBytes <= BUDGET, `peak decoded music over the campaign ${mb(c.peakBytes)} MB <= ${mb(BUDGET)} MB`);
  check(c.standIns === 0 && c.failures === 0, `no stand-ins or failures (${c.decodes} decodes)`);
  rss.end = await rendererRss(g.page);
  console.log(`peak decoded music ${mb(c.peakBytes)} MB; renderer RSS (MB, informational) ${JSON.stringify(Object.fromEntries(Object.entries(rss).map(([k, v]) => [k, Math.round(v)])))}`);
  console.log('history', seq(c).join(' '));
  let errs = await g.errors();
  check(errs.length === 0, `no page errors ${errs.slice(0, 5).join('\n')}`);
  await g.page.context().close();

  // ------------------------------------------------------------------ fallback to the procedural loops
  const notFound = (e) => /404/.test(e) && /Failed to load resource/.test(e);
  const f = await openGame('quality=medium', async (page) => {
    await page.route('**/audio/music/battle.ogg', (r) => r.fulfill({ status: 404, body: 'missing' }));
    await page.route('**/audio/music/lastStand.ogg', (r) => r.fulfill({ status: 200, contentType: 'audio/ogg', body: Buffer.from('OggS this is not an Opus stream') }));
  });
  c = await f.until((c) => c.playing === 'title' && c.source === 'file', 'the title cue (fallback page)');
  await f.page.evaluate(() => window.__game.startBattle(1));
  c = await f.until((c) => c.playing === 'battle' && c.source === 'synth', 'the procedural battle loop when battle.ogg 404s', 30000);
  check(c.failed.includes('battle') && c.level === 1, `a cue that can't be fetched plays its procedural loop (failed ${c.failed})`);
  check((await f.readyLoops()).includes('loop:battle'), '…rendered only then');
  await f.page.evaluate(() => window.__game.startBattle(3));
  c = await f.until((c) => c.playing === 'lastStand' && c.source === 'synth', 'the procedural lastStand loop when lastStand.ogg will not decode', 30000);
  check(c.failed.includes('lastStand'), `a cue that can't be decoded plays its procedural loop (failed ${c.failed})`);
  await f.page.evaluate(() => window.__game.ctx.setMode('title'));
  c = await f.until((c) => c.playing === 'title' && c.source === 'file', 'the title cue again after the fallbacks');
  check(true, 'the title still plays its recording after the others failed');
  errs = await f.errors(notFound);
  check(errs.length === 0, `no page errors on the fallback page ${errs.slice(0, 5).join('\n')}`);
  await f.page.context().close();

  // ------------------------------------------------------------------ low quality: mono cues
  const m = await openGame('quality=low');
  c = await m.until((c) => c.playing === 'title' && c.source === 'file', 'the title cue (low quality)');
  check(c.mono && c.bytes === MUSICBANK.title.samples * 4, `low quality holds the title in mono (${mb(c.bytes)} MB)`);
  await m.page.evaluate(() => window.__game.startBattle(1));
  c = await m.until((c) => c.playing === 'battle' && c.source === 'file' && c.outgoing === 0, 'the mono battle cue');
  check(c.bytes === MUSICBANK.battle.samples * 4 && c.peakBytes <= MUSICBANK.battle.samples * 12 + TAIL_BYTES / 2, `mono battle ${mb(c.bytes)} MB, peak ${mb(c.peakBytes)} MB`);
  errs = await m.errors();
  check(errs.length === 0, `no page errors at low quality ${errs.slice(0, 5).join('\n')}`);
} catch (err) {
  failures.push(String((err && err.stack) || err));
  console.error(err);
} finally {
  await l.close();
}
console.log(failures.length ? `\n${failures.length} FAILED` : '\nALL MUSIC CHECKS PASSED');
process.exit(failures.length ? 1 : 0);
