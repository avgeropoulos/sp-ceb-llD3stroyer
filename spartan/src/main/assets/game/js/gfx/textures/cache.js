/**
 * Cross-launch cache of generated texture pixels in IndexedDB, so only the first launch (and
 * the first after a generator changes) pays for generation. Records are raw RGBA8, which reads
 * back and uploads about 3× faster than a PNG decoded with createImageBitmap.
 *
 * Key: `${TEXTURE_VERSION}:${quality}:${name}`. Value: { size, data: Uint8ClampedArray,
 * meanLum?, mips? }. The cache is only an optimisation: every failure (no IndexedDB, blocked,
 * quota, slow or corrupt storage) resolves to "not cached" and never fails a boot.
 */

const DB_NAME = 'thermopylae-textures';
const STORE = 'pixels';

export function textureCacheKey(version, quality, name) {
  return `${version}:${quality}:${name}`;
}

/**
 * Opens the cache.
 * @param {{ timeoutMs?: number }} [opts]
 * @returns {Promise<null|{ getMany(keys: string[], timeoutMs?: number): Promise<Array<object|undefined>>,
 *   put(key: string, value: object): void, prune(keepPrefix: string): void, close(): void }>}
 */
export function openPixelCache({ timeoutMs = 3000 } = {}) {
  return new Promise((resolve) => {
    let settled = false;
    let timer = 0;
    const done = (db) => {
      if (settled) {
        // Opened after the timeout: nobody wants it any more.
        if (db) safe(() => db.close());
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve(db ? wrap(db) : null);
    };
    try {
      const idb = typeof indexedDB !== 'undefined' ? indexedDB : null;
      if (!idb) {
        done(null);
        return;
      }
      timer = setTimeout(() => done(null), timeoutMs);
      const rq = idb.open(DB_NAME, 1);
      rq.onupgradeneeded = () => {
        const db = rq.result;
        if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
      };
      rq.onsuccess = () => {
        const db = rq.result;
        // Another tab upgrading the schema: let it, and work uncached.
        db.onversionchange = () => safe(() => db.close());
        done(db);
      };
      rq.onerror = (e) => {
        if (e && e.preventDefault) e.preventDefault();
        done(null);
      };
      rq.onblocked = () => done(null);
    } catch {
      done(null);
    }
  });
}

function safe(fn) {
  try {
    return fn();
  } catch {
    return undefined;
  }
}

function wrap(db) {
  let open = true;
  let writable = true;

  /** Values for `keys` (undefined where missing); whatever has not arrived by the timeout stays undefined. */
  function getMany(keys, timeoutMs = 10000) {
    return new Promise((resolve) => {
      const out = new Array(keys.length);
      let settled = false;
      let timer = 0;
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(out);
      };
      if (!open || !keys.length) {
        finish();
        return;
      }
      try {
        timer = setTimeout(finish, timeoutMs);
        const tx = db.transaction(STORE, 'readonly');
        const store = tx.objectStore(STORE);
        keys.forEach((key, i) => {
          const rq = store.get(key);
          rq.onsuccess = () => {
            if (!settled) out[i] = rq.result;
          };
          rq.onerror = (e) => {
            if (e && e.preventDefault) e.preventDefault();
          };
        });
        tx.oncomplete = finish;
        tx.onerror = (e) => {
          if (e && e.preventDefault) e.preventDefault();
          finish();
        };
        tx.onabort = finish;
      } catch {
        finish();
      }
    });
  }

  /** Stores one record in the background. A full disk or any other failure stops further writes. */
  function put(key, value) {
    if (!open || !writable) return;
    const stop = (err) => {
      if (!writable) return;
      writable = false;
      console.warn('textures: pixel cache write failed; later launches will generate again', err && (err.name || err));
    };
    try {
      const tx = db.transaction(STORE, 'readwrite');
      tx.objectStore(STORE).put(value, key);
      tx.onerror = (e) => {
        if (e && e.preventDefault) e.preventDefault();
        stop(tx.error);
      };
      tx.onabort = () => stop(tx.error);
    } catch (err) {
      stop(err);
    }
  }

  /** Deletes, in the background, every record whose key does not start with `keepPrefix` (older generator versions). */
  function prune(keepPrefix) {
    if (!open) return;
    try {
      const tx = db.transaction(STORE, 'readwrite');
      const rq = tx.objectStore(STORE).openKeyCursor();
      rq.onsuccess = () => {
        const cur = rq.result;
        if (!cur) return;
        if (!String(cur.key).startsWith(keepPrefix)) cur.source.delete(cur.primaryKey);
        cur.continue();
      };
      tx.onerror = (e) => {
        if (e && e.preventDefault) e.preventDefault();
      };
    } catch {
      /* best effort */
    }
  }

  /** Closes the connection once its pending transactions have finished. */
  function close() {
    if (!open) return;
    open = false;
    safe(() => db.close());
  }

  return { getMany, put, prune, close };
}

/** Deletes the whole cache (tests, or a user-facing "clear data"). */
export function clearPixelCache() {
  return new Promise((resolve) => {
    try {
      if (typeof indexedDB === 'undefined' || !indexedDB) {
        resolve(false);
        return;
      }
      const rq = indexedDB.deleteDatabase(DB_NAME);
      rq.onsuccess = () => resolve(true);
      rq.onerror = () => resolve(false);
      rq.onblocked = () => resolve(false);
    } catch {
      resolve(false);
    }
  });
}
