// Recorded Greek voice lines in the real game (headless Chromium): a Greek line plays its Ogg
// Opus clip through WebAudio and never reaches platform TTS; the Narrator still uses TTS; the
// pause menu holds a line and Resume picks it up; a clip that can't be had falls back to TTS;
// voices off is silent but keeps the subtitles; the campaign's own intro plays from clips.
// Usage: node spartan/tests/e2e/voice-clips.mjs
import { launch } from './lib.mjs';

const l = await launch();
const failures = [];
const check = (cond, msg) => {
  if (!cond) failures.push(msg);
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${msg}`);
};
// The one console error we cause on purpose: the 404 of a clip that doesn't exist.
const expected = (e) => /404/.test(e) && /Failed to load resource/.test(e);

try {
  const page = await l.newPage({ width: 960, height: 540 });
  const step = (n, dt = 1 / 60) => page.evaluate(([n, dt]) => window.__game.step(n, dt), [n, dt]);
  const voice = () => page.evaluate(() => window.__game.ctx.audio.stats().voice);
  const waitFor = async (fn, arg, what, timeout = 20000) => {
    try {
      await page.waitForFunction(fn, arg, { timeout, polling: 50 });
      return true;
    } catch {
      check(false, `timed out waiting for ${what}`);
      return false;
    }
  };

  await page.goto(`${l.game}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  check(await page.evaluate(() => window.__game.ready), 'game boots');

  // Spies: platform TTS (records instead of speaking) and every AudioBufferSourceNode started.
  await page.evaluate(async () => {
    const g = window.__game;
    window.__said = [];
    g.ctx.platform.voice.say = (text, o) => window.__said.push({ text, ...o });
    window.__sources = [];
    const start = AudioBufferSourceNode.prototype.start;
    AudioBufferSourceNode.prototype.start = function (...args) {
      const b = this.buffer;
      window.__sources.push({ dur: b ? b.duration : 0, ch: b ? b.numberOfChannels : 0 });
      return start.apply(this, args);
    };
    window.__bank = (await import('./js/audio/voicebank.js')).VOICEBANK;
    window.__line = (key, speaker, i = 0) => {
      const v = window.__bank[key][i];
      return { key, speaker, text: v.en, greek: v.greek, clip: v.clips[speaker] };
    };
    // The lower subtitle row holds the newest line.
    window.__newestSub = () => {
      const row = [...document.querySelectorAll('.hud-sub')].pop();
      return { g: row.querySelector('.hud-sub-g').textContent, name: row.querySelector('.hud-sub-l b').textContent, text: row.querySelector('.hud-sub-l span').textContent };
    };
    g.ctx.audio.unlock();
  });

  // A battle: dayStart preloads the herald, Leonidas and his barks.
  await page.evaluate(() => window.__game.startBattle(1));
  await step(10);
  check((await page.evaluate(() => window.__game.ctx.audio.state)) === 'running', 'the AudioContext runs');
  await waitFor(() => window.__game.ctx.audio.stats().voice.cached >= 12, null, 'the Day 1 clips to decode');
  const pre = await voice();
  console.log('after preload', JSON.stringify(pre));
  check(pre.failed === 0 && !pre.broken, 'Day 1 clips decode in Chromium');

  // ΜΟΛΩΝ ΛΑΒΕ: a clip, not TTS.
  const molon = await page.evaluate(() => {
    const g = window.__game;
    window.__said.length = 0;
    window.__sources.length = 0;
    const p = window.__line('molonLabe', 'Leonidas');
    g.ctx.events.emit('voice', p);
    const v = g.ctx.audio.stats().voice;
    const log = g.ctx.audio.log.filter((e) => e.name === 'voice').pop();
    return { p, v, log, sources: window.__sources.slice(), said: window.__said.slice() };
  });
  console.log('molonLabe', JSON.stringify(molon.log), JSON.stringify(molon.sources));
  check(molon.v.speaking === molon.p.clip.file, `molonLabe is speaking (${molon.v.speaking})`);
  check(molon.v.started === pre.started + 1, 'one clip started');
  check(molon.sources.some((s) => Math.abs(s.dur - molon.p.clip.dur) < 0.06 && s.ch === 1), `a mono buffer source of the clip's length started (${molon.p.clip.dur} s)`);
  check(molon.said.length === 0, `platform TTS not called (${JSON.stringify(molon.said)})`);
  check(molon.log && molon.log.pan < -0.1 && molon.log.pan > -0.5, `Leonidas speaks from the left (pan ${molon.log && molon.log.pan})`);
  await step(2);
  const sub = await page.evaluate(() => window.__newestSub());
  check(sub.g === 'ΜΟΛΩΝ ΛΑΒΕ' && sub.name === 'Leonidas' && sub.text === molon.p.text, `subtitle shows the Greek over the English ${JSON.stringify(sub)}`);

  // Dienekes stands on the right. (His barks load on Day 3: this one is fetched on demand.)
  await page.evaluate(() => window.__game.ctx.events.emit('voice', window.__line('holdTheLine', 'Dienekes', 1)));
  await waitFor(() => /holdTheLine-2-dienekes/.test(window.__game.ctx.audio.stats().voice.speaking || ''), null, "Dienekes' line to load and start", 5000);
  const dien = await page.evaluate(() => window.__game.ctx.audio.log.filter((e) => e.name === 'voice').pop());
  check(dien.speaker === 'Dienekes' && dien.pan > 0.05 && dien.pan < 0.3, `Dienekes speaks from the right (pan ${dien.pan})`);

  // The pause menu holds a long line; Resume picks it up again.
  await page.evaluate(() => window.__game.ctx.events.emit('voice', window.__line('anopaia', 'Lookout')));
  await waitFor(() => window.__game.ctx.audio.stats().voice.speaking, null, 'the Anopaia line to start', 5000);
  await page.evaluate(() => new Promise((r) => setTimeout(r, 400)));
  const paused = await page.evaluate(() => {
    window.__game.ctx.setMode('paused');
    return window.__game.ctx.audio.stats().voice;
  });
  check(paused.speaking === null && /anopaia/.test(paused.held || ''), `pausing holds the line (${JSON.stringify(paused)})`);
  const resumed = await page.evaluate(() => {
    window.__game.ctx.resume();
    const g = window.__game;
    return { v: g.ctx.audio.stats().voice, log: g.ctx.audio.log.filter((e) => e.name === 'voice').pop() };
  });
  check(/anopaia/.test(resumed.v.speaking || '') && resumed.v.held === null, 'Resume picks the line up again');
  check(resumed.log.offset > 0.05, `…from where it was cut (offset ${resumed.log.offset.toFixed(2)} s)`);

  // The Narrator stays English TTS and cuts the clip.
  const narr = await page.evaluate(() => {
    window.__said.length = 0;
    window.__game.ctx.events.emit('voice', { key: 'tegeans', speaker: 'Narrator', text: 'The Tegeans take the line.' });
    return { said: window.__said.slice(), v: window.__game.ctx.audio.stats().voice };
  });
  check(narr.said.length === 1 && narr.said[0].text === 'The Tegeans take the line.', `the Narrator uses TTS ${JSON.stringify(narr.said)}`);
  check(narr.v.speaking === null, 'and the clip stops');

  // Clips that can't be had: TTS with the English line.
  for (const [what, file] of [['missing', 'audio/voice/missing.ogg'], ['undecodable', 'style.css']]) {
    await page.evaluate((file) => {
      window.__said.length = 0;
      window.__game.ctx.events.emit('voice', { key: 'herald', speaker: 'Herald', text: 'The King commands: hand over your arms.', clip: { file, dur: 2 } });
    }, file);
    if (await waitFor(() => window.__said.length > 0, null, `the ${what} clip to fall back`, 10000)) {
      const said = await page.evaluate(() => window.__said.slice());
      check(said[0].text === 'The King commands: hand over your arms.', `a ${what} clip falls back to TTS in English`);
    }
  }
  check(!(await voice()).broken, 'one bad file does not turn the clips off');

  // Voices off: no clip, no TTS, but the subtitle.
  const off = await page.evaluate(() => {
    const g = window.__game;
    g.ctx.settings.voice = false;
    window.__said.length = 0;
    const before = g.ctx.audio.stats().voice.started;
    g.ctx.events.emit('voice', window.__line('swords', 'Dienekes'));
    const v = g.ctx.audio.stats().voice;
    return { started: v.started - before, speaking: v.speaking, said: window.__said.length };
  });
  await step(2);
  const offSub = (await page.evaluate(() => window.__newestSub())).g;
  check(off.started === 0 && off.speaking === null && off.said === 0, `voices off is silent ${JSON.stringify(off)}`);
  check(offSub && offSub.startsWith('ΤΑ ΔΟΡΑΤΑ'), `…and still subtitled (${offSub})`);
  await page.evaluate(() => {
    window.__game.ctx.settings.voice = true;
  });

  // The campaign itself: the herald's intro line plays from its clip.
  const startedBefore = (await voice()).started;
  await page.evaluate(() => {
    window.__said.length = 0;
    window.__heard = [];
    window.__game.ctx.events.on('voice', (v) => window.__heard.push(v.key));
    window.__game.startBattle(1);
  });
  for (let i = 0; i < 12; i++) await step(10, 1 / 60);
  const intro = await page.evaluate(() => ({
    heard: window.__heard.slice(),
    v: window.__game.ctx.audio.stats().voice,
    said: window.__said.slice(),
    log: window.__game.ctx.audio.log.filter((e) => e.name === 'voice').pop(),
  }));
  check(intro.heard[0] === 'herald', `the intro starts with the herald (${intro.heard})`);
  check(intro.v.started > startedBefore && intro.log.key === 'herald' && /herald-1-herald\.ogg$/.test(intro.log.file), `the herald's clip plays ${JSON.stringify(intro.log)}`);
  check(Math.abs(intro.log.pan) < 0.15 && intro.log.volume < 0.9, 'from ahead, a little farther off');
  check(intro.said.length === 0, 'and TTS stays silent');

  // Quit to the title: nothing keeps talking.
  const title = await page.evaluate(() => {
    window.__game.ctx.setMode('title');
    return window.__game.ctx.audio.stats().voice;
  });
  check(title.speaking === null && title.held === null, 'the title screen silences the line');
  console.log('voice stats', JSON.stringify(title));

  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors)).filter((e) => !expected(e));
  check(errs.length === 0, `no page errors ${errs.slice(0, 5).join('\n')}`);
} catch (err) {
  failures.push(String((err && err.stack) || err));
  console.error(err);
} finally {
  await l.close();
}
console.log(failures.length ? `\n${failures.length} FAILED` : '\nALL VOICE CHECKS PASSED');
process.exit(failures.length ? 1 : 0);
