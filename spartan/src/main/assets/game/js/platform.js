/**
 * Platform layer (DESIGN.md §5.1, §2.1): the Android `window.SpartanNative` bridge when it exists,
 * web fallbacks otherwise. Motion samples, haptics, speech, storage, orientation lock and the
 * app lifecycle. Nothing here touches window/navigator/localStorage at import time.
 */

const DEG2RAD = Math.PI / 180;
const GYRO_RANGE = 34.9; // rad/s (2000 °/s) when the device doesn't say
const ACC_RANGE = 78.5; // m/s² (8 g)
const WEB_RING = 256; // devicemotion events buffered between polls
const MAX_PARSE = 2048; // samples per poll (the native ring holds 512 lines)
const PROBE_MS = 1500;

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

/** A pooled RawSample (§5.1). acc/accG/grav point at the private arrays or are null. */
function makeSample() {
  return { t: 0, gyro: [0, 0, 0], acc: null, accG: null, grav: null, rot: 0, _acc: [0, 0, 0], _accG: [0, 0, 0], _grav: [0, 0, 0] };
}

function copySample(s, t) {
  const tri = (v) => (v && v.length >= 3 ? [+v[0], +v[1], +v[2]] : null);
  return { t, gyro: tri(s.gyro) || [0, 0, 0], acc: tri(s.acc), accG: tri(s.accG), grav: tri(s.grav), rot: s.rot | 0 };
}

const normRot = (r) => ((Math.round(r) % 360) + 360) % 360;

/**
 * Creates the platform object (§5.1).
 * @param {{window?: object, now?: () => number}} [opts] test hooks: the global object to use and a
 *   clock in seconds (defaults: globalThis.window, performance.now()/1000)
 */
export function createPlatform(opts = {}) {
  const win = opts.window !== undefined ? opts.window : typeof window !== 'undefined' ? window : null;
  const nowSec = opts.now || (() => (typeof performance !== 'undefined' ? performance.now() : Date.now()) / 1000);
  const N = win && win.SpartanNative && typeof win.SpartanNative.pollMotion === 'function' ? win.SpartanNative : null;
  const kind = N ? 'android' : 'web';
  const nav = win && win.navigator;
  let settings = null;
  const allowed = (key) => !settings || !!settings[key];

  /**
   * Calls a bridge method; every SpartanNative call is wrapped (§5.1). Android looks bridge methods up by
   * name AND argument count, so the call must pass exactly the arguments the Kotlin method declares.
   */
  function nat(name, ...args) {
    try {
      const fn = N && N[name];
      return typeof fn === 'function' ? fn.apply(N, args) : undefined;
    } catch (err) {
      return undefined;
    }
  }

  // ---------------------------------------------------------------- motion

  const out = []; // reused poll() result
  const pool = []; // android: parsed samples
  const vals = new Float64Array(14);
  let started = false;
  let lastT = 0;
  let lastRealT = NaN;
  let dtAvg = 0;
  let lastRot = 0;

  // injected (synthetic) samples, released in real time
  const queue = [];
  let qHead = 0;
  let qT0 = 0;
  let qWall0 = 0;

  function noteReal(t) {
    const d = t - lastRealT;
    if (d > 0 && d < 0.1) dtAvg = dtAvg > 0 ? dtAvg + (d - dtAvg) * 0.05 : d;
    lastRealT = t;
  }

  // Android: "t,gx,gy,gz,ax,ay,az,Ax,Ay,Az,gvx,gvy,gvz,rot" per line, numbers parsed with Number().
  function parseInto(str) {
    let n = 0;
    let pos = 0;
    const len = str.length;
    while (pos < len && n < MAX_PARSE) {
      let end = str.indexOf('\n', pos);
      if (end < 0) end = len;
      if (end > pos && parseLine(str, pos, end, n)) n++;
      pos = end + 1;
    }
    return n;
  }

  function parseLine(str, a, b, n) {
    let k = 0;
    let s = a;
    for (let i = a; i <= b; i++) {
      if (i === b || str.charCodeAt(i) === 44) {
        if (k < 14) vals[k] = s < i ? Number(str.slice(s, i)) : NaN;
        k++;
        s = i + 1;
      }
    }
    if (k !== 14) return false; // malformed (e.g. a locale-formatted decimal comma)
    const t = vals[0];
    if (!Number.isFinite(t) || !Number.isFinite(vals[1]) || !Number.isFinite(vals[2]) || !Number.isFinite(vals[3])) return false;
    const smp = pool[n] || (pool[n] = makeSample());
    smp.t = t;
    smp.gyro[0] = vals[1];
    smp.gyro[1] = vals[2];
    smp.gyro[2] = vals[3];
    smp.acc = triple(4, smp._acc);
    smp.accG = triple(7, smp._accG);
    smp.grav = triple(10, smp._grav);
    if (Number.isFinite(vals[13])) lastRot = normRot(vals[13]);
    smp.rot = lastRot;
    out.push(smp);
    return true;
  }

  function triple(i, arr) {
    const x = vals[i], y = vals[i + 1], z = vals[i + 2];
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return null;
    arr[0] = x;
    arr[1] = y;
    arr[2] = z;
    return arr;
  }

  // Web: devicemotion events go into a ring of pooled samples until the next poll.
  const ring = [];
  let rHead = 0; // next write
  let rCount = 0;
  let webSeen = false;
  let webLastT = -Infinity;

  function screenAngle() {
    try {
      const so = win.screen && win.screen.orientation;
      const a = so && typeof so.angle === 'number' ? so.angle : typeof win.orientation === 'number' ? win.orientation : 0;
      return normRot(a);
    } catch (err) {
      return 0;
    }
  }

  function webTriple(src, arr) {
    if (!src || src.x == null || src.y == null || src.z == null) return null;
    arr[0] = +src.x;
    arr[1] = +src.y;
    arr[2] = +src.z;
    return Number.isFinite(arr[0] + arr[1] + arr[2]) ? arr : null;
  }

  function onMotion(e) {
    const rr = e && e.rotationRate;
    if (!rr || (rr.alpha == null && rr.beta == null && rr.gamma == null)) return;
    webSeen = true;
    if (ring.length < WEB_RING) ring.push(makeSample());
    const smp = ring[rHead];
    rHead = (rHead + 1) % WEB_RING;
    if (rCount < WEB_RING) rCount++;
    let t = (e.timeStamp || 0) / 1000;
    if (t <= webLastT) t = webLastT + (e.interval > 0 ? e.interval / 1000 : 0.016); // keep time monotonic
    webLastT = t;
    smp.t = t;
    // alpha → x, beta → y, gamma → z, deg/s → rad/s (what Chrome, Firefox and Safari ship)
    smp.gyro[0] = (+rr.alpha || 0) * DEG2RAD;
    smp.gyro[1] = (+rr.beta || 0) * DEG2RAD;
    smp.gyro[2] = (+rr.gamma || 0) * DEG2RAD;
    smp.acc = webTriple(e.acceleration, smp._acc);
    smp.accG = webTriple(e.accelerationIncludingGravity, smp._accG);
    smp.grav = null;
    smp.rot = screenAngle();
  }

  function drainRing(keep) {
    const start = (rHead - rCount + WEB_RING) % WEB_RING;
    if (keep) for (let i = 0; i < rCount; i++) out.push(ring[(start + i) % WEB_RING]);
    rCount = 0;
  }

  function drainNative(keep) {
    if (!started) return 0;
    let str;
    try {
      str = N.pollMotion();
    } catch (err) {
      return 0;
    }
    if (!keep || typeof str !== 'string' || str.length === 0) return 0;
    return parseInto(str);
  }

  const motion = {
    /** android: motionAvailable(); web: a devicemotion event with a rotationRate within 1.5 s. */
    isSupported() {
      if (N) return Promise.resolve(!!nat('motionAvailable'));
      if (!win || typeof win.DeviceMotionEvent === 'undefined') return Promise.resolve(false);
      if (webSeen) return Promise.resolve(true);
      return new Promise((resolve) => {
        let done = false;
        const finish = (ok) => {
          if (done) return;
          done = true;
          try {
            win.removeEventListener('devicemotion', probe);
          } catch (err) {
            /* ignore */
          }
          resolve(ok);
        };
        const probe = (e) => {
          onMotionProbe(e);
          if (webSeen) finish(true);
        };
        try {
          win.addEventListener('devicemotion', probe);
          win.setTimeout(() => finish(webSeen), PROBE_MS);
        } catch (err) {
          finish(false);
        }
      });
    },

    /** iOS 13+: DeviceMotionEvent.requestPermission() (call from a user gesture); else true. */
    async requestPermission() {
      if (N) return true;
      const DME = win && win.DeviceMotionEvent;
      if (DME && typeof DME.requestPermission === 'function') {
        try {
          return (await DME.requestPermission()) === 'granted';
        } catch (err) {
          return false;
        }
      }
      return true;
    },

    start() {
      if (started) return;
      started = true;
      if (N) {
        nat('startMotion');
      } else if (win) {
        rCount = 0;
        try {
          win.addEventListener('devicemotion', onMotion, { passive: true });
        } catch (err) {
          /* ignore */
        }
      }
    },

    stop() {
      if (!started) return;
      if (N) {
        nat('stopMotion');
      } else if (win) {
        try {
          win.removeEventListener('devicemotion', onMotion, { passive: true });
        } catch (err) {
          /* ignore */
        }
        rCount = 0;
      }
      started = false;
    },

    /** Samples since the last poll, oldest first: a reused array of pooled objects. */
    poll() {
      out.length = 0;
      if (qHead < queue.length) {
        // while injected samples are queued, real samples are discarded
        if (N) drainNative(false);
        else drainRing(false);
        const release = qT0 + (nowSec() - qWall0);
        while (qHead < queue.length && queue[qHead].t <= release + 1e-9) out.push(queue[qHead++]);
        if (qHead >= queue.length) {
          queue.length = 0;
          qHead = 0;
        }
      } else if (N) {
        drainNative(true);
        for (let i = 0; i < out.length; i++) noteReal(out[i].t);
      } else if (started) {
        drainRing(true);
        for (let i = 0; i < out.length; i++) noteReal(out[i].t);
      }
      if (out.length > 0) lastT = out[out.length - 1].t;
      return out;
    },

    /**
     * Queues synthetic RawSamples. They are re-based so the first lands at lastT() + 0.01 (or
     * right after samples still queued), keep their spacing, and are released in real time.
     */
    inject(samples) {
      if (!samples || !samples.length) return;
      const pendingQ = qHead < queue.length;
      const base = (pendingQ ? queue[queue.length - 1].t : lastT) + 0.01;
      const t0 = +samples[0].t || 0;
      if (!pendingQ) {
        queue.length = 0;
        qHead = 0;
        qT0 = base;
        qWall0 = nowSec();
      }
      for (const s of samples) if (s && s.gyro) queue.push(copySample(s, base + ((+s.t || 0) - t0)));
    },

    /** Drops queued synthetic samples (a training demo cut short must not replay later). */
    clearInjected() {
      queue.length = 0;
      qHead = 0;
    },

    /** t of the last delivered sample (0 if none). */
    lastT: () => lastT,

    /** Measured sample rate of real sensor data (Hz, 0 until known). */
    rate: () => (dtAvg > 0 ? 1 / dtAvg : 0),

    /** { gyroRange (rad/s), accRange (m/s²), rateHz } */
    info() {
      const res = { gyroRange: GYRO_RANGE, accRange: ACC_RANGE, rateHz: motion.rate() };
      if (N) {
        try {
          const j = JSON.parse(nat('motionInfo') || '{}');
          if (j && Number(j.gyroRange) > 0) res.gyroRange = Number(j.gyroRange);
          if (j && Number(j.accRange) > 0) res.accRange = Number(j.accRange);
          if (j && Number(j.rateHz) > 0) res.rateHz = Number(j.rateHz);
        } catch (err) {
          /* defaults */
        }
      }
      return res;
    },
  };

  // The isSupported() probe shares the ring handler so early events aren't lost while started.
  function onMotionProbe(e) {
    const rr = e && e.rotationRate;
    if (rr && (rr.alpha != null || rr.beta != null || rr.gamma != null)) webSeen = true;
  }

  // ---------------------------------------------------------------- haptics

  const haptics = {
    /** One vibration of `ms` (≤ 400) at strength 0..1. */
    pulse(ms, strength = 1) {
      if (!allowed('haptics')) return;
      ms = Math.round(Math.min(400, Number(ms) || 0));
      if (ms <= 0) return;
      if (N) {
        const amp = Math.round(clamp(Number(strength) || 0, 0, 1) * 255);
        if (amp > 0) nat('vibrate', ms, amp);
        return;
      }
      try {
        if (nav && typeof nav.vibrate === 'function') nav.vibrate(ms);
      } catch (err) {
        /* ignore */
      }
    },

    /** Android-style pattern [wait, on, off, on, …] in ms (≤ 16 values, each ≤ 400). */
    pattern(msArray) {
      if (!allowed('haptics') || !msArray || !msArray.length) return;
      const p = [];
      for (let i = 0; i < msArray.length && i < 16; i++) p.push(Math.round(clamp(Number(msArray[i]) || 0, 0, 400)));
      if (N) {
        nat('vibratePattern', p.join(','));
        return;
      }
      // navigator.vibrate patterns start with a vibration: shift by the initial wait
      const webP = p[0] > 0 ? [0].concat(p) : p.slice(1);
      try {
        if (webP.length && nav && typeof nav.vibrate === 'function') nav.vibrate(webP);
      } catch (err) {
        /* ignore */
      }
    },
  };

  // ---------------------------------------------------------------- voice

  let voiceCache;
  let voiceListen = false;
  function pickVoice(ss) {
    if (!voiceListen) {
      voiceListen = true;
      try {
        ss.addEventListener('voiceschanged', () => {
          voiceCache = undefined;
        });
      } catch (err) {
        /* ignore */
      }
    }
    if (voiceCache !== undefined) return voiceCache;
    let list = [];
    try {
      list = ss.getVoices() || [];
    } catch (err) {
      list = [];
    }
    const lang = (v) => String(v.lang || '').replace('_', '-').toLowerCase();
    const found = list.find((v) => lang(v) === 'en-gb') || list.find((v) => lang(v).startsWith('en-gb')) || list.find((v) => lang(v).startsWith('en'));
    if (list.length) voiceCache = found || null;
    return found || null;
  }

  const voice = {
    /** Speaks a line (no-op unless settings.voice): native TTS, else speechSynthesis en-GB. */
    say(text, { pitch = 0.9, rate = 0.95 } = {}) {
      if (!allowed('voice') || text == null || text === '') return;
      if (N) {
        nat('say', String(text), Number(pitch) || 0.9, Number(rate) || 0.95);
        return;
      }
      try {
        const ss = win && win.speechSynthesis;
        const U = win && win.SpeechSynthesisUtterance;
        if (!ss || typeof U !== 'function') return;
        ss.cancel();
        const u = new U(String(text));
        u.lang = 'en-GB';
        const v = pickVoice(ss);
        if (v) u.voice = v;
        u.pitch = clamp(Number(pitch) || 0.9, 0, 2);
        u.rate = clamp(Number(rate) || 0.95, 0.1, 4);
        ss.speak(u);
      } catch (err) {
        /* ignore */
      }
    },

    stop() {
      if (N) {
        nat('stopSpeech');
        return;
      }
      try {
        if (win && win.speechSynthesis) win.speechSynthesis.cancel();
      } catch (err) {
        /* ignore */
      }
    },
  };

  // ---------------------------------------------------------------- storage

  const mem = new Map();
  let ls;
  function store() {
    if (ls === undefined) {
      try {
        ls = win && win.localStorage;
        const k = '__thermopylae_probe';
        ls.setItem(k, '1');
        ls.removeItem(k);
      } catch (err) {
        ls = null;
      }
    }
    return ls;
  }

  const storage = {
    /** The JSON value stored under key, or fallback. */
    load(key, fallback) {
      let str = null;
      if (mem.has(key)) {
        str = mem.get(key);
      } else {
        const s = store();
        if (s) {
          try {
            str = s.getItem(key);
          } catch (err) {
            str = null;
          }
        }
      }
      if (str == null) return fallback;
      try {
        return JSON.parse(str);
      } catch (err) {
        return fallback;
      }
    },

    /** Stores value as JSON (in memory if localStorage is unavailable or full). */
    save(key, value) {
      let str;
      try {
        str = JSON.stringify(value);
      } catch (err) {
        return;
      }
      if (str === undefined) return;
      const s = store();
      if (s) {
        try {
          s.setItem(key, str);
          mem.delete(key);
          return;
        } catch (err) {
          /* fall through */
        }
      }
      mem.set(key, str);
    },
  };

  // ---------------------------------------------------------------- app updates

  // Android only: AppUpdater looks for a newer APK in the GitHub release and, on the player's tap,
  // downloads it and opens Android's installer. On the web this reports {enabled:false} and does nothing.
  const updateFns = [];
  let updateBound = false;
  const readUpdate = (raw) => {
    if (raw && typeof raw === 'object') return raw;
    try {
      const o = JSON.parse(raw || '');
      return o && typeof o === 'object' ? o : null;
    } catch (err) {
      return null;
    }
  };
  const noUpdate = () => ({ state: 'idle', enabled: false, installed: 0, available: 0, name: '', notes: '', progress: 0, error: '' });
  const updates = {
    /** Current state: {state, enabled, installed, available, name, notes, progress (0..1), error}. */
    state() {
      return (N && readUpdate(nat('updateState'))) || noUpdate();
    },
    check() {
      nat('checkForUpdate');
    },
    /** Downloads the update and opens Android's installer (the player confirms there). */
    install() {
      nat('installUpdate');
    },
    /** Called with the new state whenever it changes. Returns an unsubscribe function. */
    onChange(fn) {
      if (!updateBound && win && typeof win.addEventListener === 'function') {
        updateBound = true;
        win.addEventListener('appupdate', (e) => {
          const st = readUpdate(e && e.detail);
          if (st) run(updateFns, st);
        });
      }
      updateFns.push(fn);
      return () => {
        const i = updateFns.indexOf(fn);
        if (i >= 0) updateFns.splice(i, 1);
      };
    },
  };

  // ---------------------------------------------------------------- lifecycle

  const pauseFns = [];
  const blurFns = [];
  const resumeFns = [];
  let paused = false;
  let lifeBound = false;
  const run = (list, arg) => {
    for (const fn of list.slice()) {
      try {
        fn(arg);
      } catch (err) {
        if (typeof console !== 'undefined') console.error('platform lifecycle handler failed', err);
      }
    }
  };
  const firePause = () => {
    if (paused) return;
    paused = true;
    run(pauseFns);
  };
  const fireResume = () => {
    if (!paused) return;
    paused = false;
    run(resumeFns);
  };
  function bindLifecycle() {
    if (lifeBound || !win || typeof win.addEventListener !== 'function') return;
    lifeBound = true;
    win.addEventListener('nativepause', firePause);
    win.addEventListener('nativeresume', fireResume);
    // A system window (notification shade, power menu, assistant) took focus without pausing us.
    win.addEventListener('nativeblur', () => run(blurFns));
    const doc = win.document;
    if (doc && typeof doc.addEventListener === 'function') {
      doc.addEventListener('visibilitychange', () => (doc.visibilityState === 'hidden' ? firePause() : fireResume()));
    }
  }
  const subscribe = (list, fn) => {
    bindLifecycle();
    list.push(fn);
    return () => {
      const i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    };
  };

  return {
    kind,
    /** Haptics and voice follow settings.haptics / settings.voice live from now on. */
    bindSettings(s) {
      settings = s || null;
    },
    motion,
    haptics,
    voice,
    storage,
    updates,
    /** Locks the screen to the current landscape orientation (android) or landscape (web). */
    lockOrientation(lock) {
      if (N) {
        nat('lockOrientation', !!lock);
        return;
      }
      try {
        const so = win && win.screen && win.screen.orientation;
        if (!so) return;
        if (lock) {
          const p = typeof so.lock === 'function' ? so.lock('landscape') : null;
          if (p && typeof p.catch === 'function') p.catch(() => {});
        } else if (typeof so.unlock === 'function') {
          so.unlock();
        }
      } catch (err) {
        /* ignore */
      }
    },
    /** Current display rotation in degrees (0|90|180|270). */
    rotation() {
      if (N) {
        const r = Number(nat('rotation'));
        return Number.isFinite(r) ? normRot(r) : lastRot;
      }
      return win ? screenAngle() : 0;
    },
    /** 'nativepause' and visibilitychange → hidden. Returns an unsubscribe function. */
    onPause: (fn) => subscribe(pauseFns, fn),
    /** 'nativeresume' and visibilitychange → visible. Returns an unsubscribe function. */
    onResume: (fn) => subscribe(resumeFns, fn),
    /** 'nativeblur': the app lost window focus to a system window but kept running. */
    onBlur: (fn) => subscribe(blurFns, fn),
    /** Installs window.__onBack (the Android back key asks it; true = handled). */
    onBack(fn) {
      if (!win) return;
      win.__onBack = () => {
        try {
          return !!fn();
        } catch (err) {
          return false;
        }
      };
    },
    exit() {
      if (N) {
        nat('exit');
        return;
      }
      try {
        if (win && typeof win.close === 'function') win.close();
      } catch (err) {
        /* ignore */
      }
    },
  };
}
