#!/usr/bin/env node
// Thermopylae music pipeline, browser check (run by `qa.py --browser`): decodes the encoded cues in
// headless Chromium, the engine behind the Android WebView, the way the game's audio.js will, and
// saves what WebAudio produced so that qa.py can check its length and its seam.
//
//   node webcheck.mjs --out DIR [--music-dir DIR] [cue ...]
//
// Per cue: decodeAudioData in a 48 kHz context and in a 44.1 kHz one (WebAudio resamples a decoded
// buffer to its context's rate), and a looped AudioBufferSourceNode that plays the 48 kHz buffer in
// a 44.1 kHz context from its start to 1 s past its end (the game's other option on a 44.1 kHz
// device). Writes <cue>.<decode48|decode44|loop44>.f32 (interleaved stereo float32) and webcheck.json.
// Needs Playwright with Chromium (the e2e tests' setup); $PLAYWRIGHT_MODULE overrides where it is.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const GAME = path.resolve(HERE, '../../src/main/assets/game');
const CUES = JSON.parse(fs.readFileSync(path.join(HERE, 'cues.json'), 'utf8'));

const args = { musicDir: path.join(GAME, 'audio/music'), out: null, cues: [] };
for (let i = 2; i < process.argv.length; i++) {
  const v = process.argv[i];
  if (v === '--music-dir') args.musicDir = process.argv[++i];
  else if (v === '--out') args.out = process.argv[++i];
  else if (v.startsWith('-')) { console.error(`webcheck.mjs: unknown option ${v}`); process.exit(2); }
  else args.cues.push(v);
}
if (!args.out) { console.error('webcheck.mjs: --out DIR is required'); process.exit(2); }
if (!args.cues.length) args.cues = Object.keys(CUES).filter((k) => !k.startsWith('_'));

async function loadPlaywright() {
  const tries = [process.env.PLAYWRIGHT_MODULE, 'playwright', '/opt/node22/lib/node_modules/playwright/index.mjs'].filter(Boolean);
  for (const t of tries) {
    try {
      return await import(t.startsWith('/') ? pathToFileURL(t).href : t);
    } catch { /* next */ }
  }
  console.error('webcheck.mjs: Playwright not found (set PLAYWRIGHT_MODULE to its index.mjs)');
  process.exit(3);
}

const { chromium } = await loadPlaywright();
const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage();
await page.goto('about:blank');
fs.mkdirSync(args.out, { recursive: true });
const report = { browser: `Chromium ${browser.version()}`, cues: {} };

for (const cue of args.cues) {
  const file = path.join(args.musicDir, `${cue}.ogg`);
  if (!fs.existsSync(file)) continue;
  const res = await page.evaluate(async (b64) => {
    const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const toB64 = (buf, frames) => {
      const ch = [buf.getChannelData(0), buf.numberOfChannels > 1 ? buf.getChannelData(1) : buf.getChannelData(0)];
      const n = frames ?? buf.length;
      const inter = new Float32Array(2 * n);
      for (let i = 0; i < n; i++) { inter[2 * i] = ch[0][i]; inter[2 * i + 1] = ch[1][i]; }
      const bytes = new Uint8Array(inter.buffer);
      let s = '';
      for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      return btoa(s);
    };
    const out = {};
    let buf48 = null;
    for (const sr of [48000, 44100]) {
      const ac = new OfflineAudioContext(2, 1, sr);
      const buf = await ac.decodeAudioData(bin.buffer.slice(0));
      if (sr === 48000) buf48 = buf;
      out[sr === 48000 ? 'decode48' : 'decode44'] = { sampleRate: buf.sampleRate, length: buf.length, channels: buf.numberOfChannels, data: toB64(buf) };
    }
    // The 48 kHz buffer looped by a source node in a 44.1 kHz context (resampled while playing).
    const frames = Math.ceil((buf48.duration + 1) * 44100);
    const oc = new OfflineAudioContext(2, frames, 44100);
    const src = oc.createBufferSource();
    src.buffer = buf48;
    src.loop = true;
    src.connect(oc.destination);
    src.start(0);
    const rendered = await oc.startRendering();
    out.loop44 = { sampleRate: 44100, length: rendered.length, wrapAt: buf48.duration * 44100, data: toB64(rendered) };
    return out;
  }, fs.readFileSync(file).toString('base64'));
  const entry = { file };
  for (const [k, v] of Object.entries(res)) {
    const f = path.join(args.out, `${cue}.${k}.f32`);
    fs.writeFileSync(f, Buffer.from(v.data, 'base64'));
    const { data, ...meta } = v;
    entry[k] = { ...meta, file: f };
  }
  report.cues[cue] = entry;
  console.error(`webcheck: ${cue} decoded (${entry.decode48.length} samples at 48 kHz, ${entry.decode44.length} at 44.1 kHz)`);
}
await browser.close();
fs.writeFileSync(path.join(args.out, 'webcheck.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
