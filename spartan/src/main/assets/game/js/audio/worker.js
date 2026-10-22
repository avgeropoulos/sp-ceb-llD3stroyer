/**
 * Module worker that renders SFX variants and loops off the main thread (audio.js falls back
 * to time-sliced rendering on the main thread if the worker can't start). Importing this file
 * anywhere other than a worker does nothing.
 *
 * Request: { id, kind: 'sfx'|'loop', name, sr, variants }
 * Reply:   { id, kind, name, sr, chans: Float32Array[], ms } (chans transferred) or { id, error }
 */
import { SFX, sfxLength, sfxRate, renderSfxGen } from './sfx.js';
import { LOOPS, loopLength, renderLoopGen } from './loops.js';

function run(it) {
  while (!it.next().done);
}

function render(req) {
  const t0 = performance.now();
  const chans = [];
  if (req.kind === 'sfx') {
    const sr = sfxRate(req.name, req.sr);
    for (let v = 0; v < SFX[req.name].variants; v++) {
      const out = new Float32Array(sfxLength(req.name, sr));
      run(renderSfxGen(req.name, out, sr, v));
      chans.push(out);
    }
    return { id: req.id, kind: req.kind, name: req.name, sr, chans, ms: performance.now() - t0 };
  }
  const out = new Float32Array(loopLength(req.name));
  run(renderLoopGen(req.name, out));
  chans.push(out);
  return { id: req.id, kind: req.kind, name: req.name, sr: LOOPS[req.name].sr, chans, ms: performance.now() - t0 };
}

const inWorker = typeof WorkerGlobalScope !== 'undefined' && typeof self !== 'undefined' && self instanceof WorkerGlobalScope;
if (inWorker) {
  self.onmessage = (e) => {
    const req = e.data;
    try {
      const res = render(req);
      self.postMessage(res, res.chans.map((c) => c.buffer));
    } catch (err) {
      self.postMessage({ id: req.id, error: String(err && err.stack || err) });
    }
  };
}
