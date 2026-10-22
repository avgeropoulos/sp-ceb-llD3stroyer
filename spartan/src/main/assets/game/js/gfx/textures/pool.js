/**
 * Runs texture generators on a few module workers (textures/worker.js). A worker that fails to
 * start, crashes or stops answering hands its job back to the queue; whatever no worker could
 * do is returned so the caller generates it on the main thread. Nothing here runs at import.
 */

const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

/** How many generator workers this device gets (none without module worker support). */
export function textureWorkerCount() {
  if (typeof Worker === 'undefined') return 0;
  const nav = typeof navigator !== 'undefined' ? navigator : null;
  const cores = (nav && nav.hardwareConcurrency) || 4;
  return Math.max(1, Math.min(3, cores - 1));
}

/**
 * @param {string[]} names owners to generate, slowest first
 * @param {{ count: number, quality: string, onResult(res: object): void, onTick?(): void,
 *   readyMs?: number, jobMs?: number }} opts  `onResult` gets generateOwner()'s result; `onTick`
 *   runs about twice a second while workers are busy. The time limits count only time the page
 *   is running (a frozen or backgrounded WebView does not use them up).
 * @returns {Promise<{ left: string[], workers: number, jobs: number }>} `left`: names for the main thread
 */
export function runOnWorkers(names, { count, quality, onResult, onTick, readyMs = 8000, jobMs = 45000 }) {
  return new Promise((resolve) => {
    const queue = names.slice();
    const failed = [];
    const slots = [];
    let seq = 0;
    let jobs = 0;
    let finished = false;
    let timer = 0;
    let lastCheck = now();
    const url = new URL('./worker.js', import.meta.url);
    for (let i = 0, n = Math.min(count, queue.length); i < n; i++) {
      try {
        const slot = { w: new Worker(url, { type: 'module' }), job: null, id: 0, ready: false, alive: true, waited: 0 };
        slot.w.onmessage = (e) => onMessage(slot, e.data);
        slot.w.onerror = (e) => {
          if (e && e.preventDefault) e.preventDefault();
          down(slot, 'error');
        };
        slot.w.onmessageerror = () => down(slot, 'messageerror');
        slots.push(slot);
      } catch {
        break;
      }
    }
    const started = slots.length;
    if (!started) {
      resolve({ left: queue, workers: 0, jobs: 0 });
      return;
    }
    timer = setInterval(check, 500);

    function onMessage(slot, msg) {
      if (!slot.alive || !msg) return;
      if (msg.ready) {
        slot.ready = true;
        slot.waited = 0;
        dispatch(slot);
        return;
      }
      if (!slot.job || msg.id !== slot.id) return;
      const job = slot.job;
      slot.job = null;
      if (msg.error) {
        console.warn(`textures: worker failed on ${job}; generating it on the main thread`, msg.error);
        failed.push(job);
      } else {
        jobs++;
        try {
          onResult(msg);
        } catch (err) {
          console.error(`textures: installing ${job} failed`, err);
        }
      }
      dispatch(slot);
      settle();
    }

    function dispatch(slot) {
      if (finished || !slot.alive || !slot.ready || slot.job || !queue.length) return;
      slot.job = queue.shift();
      slot.id = ++seq;
      slot.waited = 0;
      try {
        slot.w.postMessage({ id: slot.id, name: slot.job, quality });
      } catch {
        down(slot, 'postMessage');
      }
    }

    function down(slot, why) {
      if (!slot.alive) return;
      slot.alive = false;
      try {
        slot.w.terminate();
      } catch {
        /* already gone */
      }
      if (slot.job) {
        queue.unshift(slot.job);
        slot.job = null;
      }
      if (!slots.some((s) => s.alive)) console.warn(`textures: generator workers unavailable (${why}); generating on the main thread`);
      for (const s of slots) dispatch(s);
      settle();
    }

    function check() {
      const t = now();
      // Long gaps (a frozen page, a busy main thread) count as a single short step.
      const dt = Math.min(t - lastCheck, 1000);
      lastCheck = t;
      for (const s of slots) {
        if (!s.alive) continue;
        s.waited += dt;
        if (!s.ready && s.waited > readyMs) down(s, 'no start');
        else if (s.job && s.waited > jobMs) down(s, 'no answer');
      }
      if (!finished && onTick) {
        try {
          onTick();
        } catch {
          /* progress display only */
        }
      }
      settle();
    }

    function settle() {
      if (finished) return;
      const live = slots.filter((s) => s.alive);
      if (live.some((s) => s.job)) return;
      // Live workers still starting up will take the remaining jobs.
      if (queue.length && live.length) return;
      finished = true;
      clearInterval(timer);
      for (const s of slots) {
        try {
          s.w.terminate();
        } catch {
          /* already gone */
        }
      }
      resolve({ left: queue.concat(failed), workers: started, jobs });
    }
  });
}
