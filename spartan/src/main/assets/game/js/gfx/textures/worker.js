/**
 * Module worker that runs texture pixel generators off the main thread on a first launch
 * (textures.js falls back to the main thread if no worker starts). Importing this file
 * anywhere other than a worker does nothing.
 *
 * On start it posts { ready: true }.
 * Request: { id, name, quality }
 * Reply:   generateOwner()'s result plus { id, ms } (pixel buffers transferred), or { id, error }
 */
import { generateOwner } from './generators.js';

const inWorker = typeof WorkerGlobalScope !== 'undefined' && typeof self !== 'undefined' && self instanceof WorkerGlobalScope;
if (inWorker) {
  self.onmessage = (e) => {
    const req = e.data || {};
    try {
      const t0 = performance.now();
      const res = generateOwner(req.name, req.quality);
      if (!res) throw new Error(`no generator for "${req.name}"`);
      const transfer = [res.color.buffer];
      if (res.pair) transfer.push(res.pair.color.buffer);
      self.postMessage({ id: req.id, ...res, ms: performance.now() - t0 }, transfer);
    } catch (err) {
      self.postMessage({ id: req.id, error: String((err && err.stack) || err) });
    }
  };
  self.postMessage({ ready: true });
}
