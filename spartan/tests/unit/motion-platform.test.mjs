// platform.js (DESIGN.md §5.1, §2.1) in Node with a fake window and SpartanNative bridge.
// (Browser behaviour is covered again in tests/e2e/motion-platform.mjs.)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPlatform } from '../../src/main/assets/game/js/platform.js';

/** A minimal window: EventTarget + the bits platform.js reads. */
function fakeWindow(extra = {}) {
  const win = new EventTarget();
  const timers = [];
  win.setTimeout = (fn, ms) => timers.push({ fn, ms });
  win.runTimers = () => timers.splice(0).forEach((t) => t.fn());
  win.document = new EventTarget();
  win.document.visibilityState = 'visible';
  win.navigator = {};
  win.screen = { orientation: { angle: 90 } };
  return Object.assign(win, extra);
}

function fakeNative(lines = []) {
  const calls = [];
  const queue = lines.slice();
  const N = {
    calls,
    push: (s) => queue.push(s),
    motionAvailable: () => (calls.push(['motionAvailable']), true),
    startMotion: () => calls.push(['startMotion']),
    stopMotion: () => calls.push(['stopMotion']),
    pollMotion: () => {
      calls.push(['pollMotion']);
      return queue.splice(0).join('');
    },
    motionInfo: () => '{"gyroRange":17.45,"accRange":156.9,"rateHz":198.5}',
    vibrate: (ms, amp) => calls.push(['vibrate', ms, amp]),
    vibratePattern: (csv) => calls.push(['vibratePattern', csv]),
    say: (t, p, r) => calls.push(['say', t, p, r]),
    stopSpeech: () => calls.push(['stopSpeech']),
    rotation: () => 270,
    lockOrientation: (b) => calls.push(['lockOrientation', b]),
    exit: () => calls.push(['exit']),
    updateState: () => (calls.push(['updateState']), N.update),
    checkForUpdate: () => calls.push(['checkForUpdate']),
    installUpdate: () => calls.push(['installUpdate']),
    update: '{"state":"current","installed":41,"available":0,"name":"","notes":"","progress":0,"error":"","enabled":true}',
  };
  return N;
}

const line = (t, rot = 90, rest = '0.1,-0.2,0.3,1.0E-4,0.0,-0.0,9.8,0.1,0.2,9.81,0.0,0.0') => `${t},${rest},${rot}\n`;

test('android: pollMotion lines parse with Number(), NaN triples become null, objects are pooled', () => {
  const N = fakeNative();
  const win = fakeWindow({ SpartanNative: N });
  const p = createPlatform({ window: win, now: () => 0 });
  assert.equal(p.kind, 'android');
  assert.deepEqual(p.motion.poll(), [], 'nothing before start()');
  p.motion.start();
  N.push(line(0.005));
  N.push('0.010,1.5E-3,2.0,-3.25,NaN,NaN,NaN,0.5,9.7,1.0E1,NaN,1.0,2.0,90\r\n');
  N.push('0.015,1,2,3,4,5,6,7,8,9,10,11,12,180\n');
  N.push('garbage line\n');
  N.push('0.020,1,2,3,4,5,6,7,8,9,10,11,12\n'); // 13 columns (dropped)
  N.push('0.021,1,2,3,4,5,6,7,8,9,10,11,12,13,90\n'); // 15 columns, e.g. "1,0" decimal commas (dropped)
  N.push('0.022,NaN,0,0,0,0,0,0,0,0,0,0,0,90\n'); // no gyro (dropped)
  N.push('0.025,0.1,0.2,0.3,,,,9,9,9,Infinity,0,0,-90');
  const out = p.motion.poll();
  assert.equal(out.length, 4);
  assert.deepEqual(out.map((s) => s.t), [0.005, 0.01, 0.015, 0.025]);
  assert.deepEqual(out[0].gyro, [0.1, -0.2, 0.3]);
  assert.deepEqual(out[0].acc, [1e-4, 0, -0]);
  assert.deepEqual(out[0].accG, [9.8, 0.1, 0.2]);
  assert.deepEqual(out[0].grav, [9.81, 0, 0]);
  assert.equal(out[0].rot, 90);
  assert.deepEqual(out[1].gyro, [0.0015, 2, -3.25]);
  assert.equal(out[1].acc, null);
  assert.deepEqual(out[1].accG, [0.5, 9.7, 10]);
  assert.equal(out[1].grav, null);
  assert.equal(out[2].rot, 180);
  assert.equal(out[3].acc, null, 'empty fields are NaN');
  assert.equal(out[3].grav, null, 'Infinity is not a reading');
  assert.equal(out[3].rot, 270, '−90 normalised');
  assert.equal(p.motion.lastT(), 0.025);

  const firstObj = out[0];
  N.push(line(0.03));
  const again = p.motion.poll();
  assert.equal(again, out, 'the same array is reused');
  assert.equal(again[0], firstObj, 'sample objects are pooled');
  assert.equal(again.length, 1);
  assert.equal(again[0].t, 0.03);
  assert.notEqual(again[0].acc, null);

  for (let i = 0; i < 300; i++) N.push(line(0.035 + i * 0.005));
  p.motion.poll();
  assert.ok(Math.abs(p.motion.rate() - 200) < 1, `rate ${p.motion.rate()}`);
  assert.deepEqual(p.motion.info(), { gyroRange: 17.45, accRange: 156.9, rateHz: 198.5 });
  p.motion.stop();
  N.push(line(9));
  assert.equal(p.motion.poll().length, 0, 'stopped');
  assert.deepEqual(N.calls.filter((c) => c[0] !== 'pollMotion').map((c) => c[0]), ['startMotion', 'stopMotion']);
});

test('android: every bridge call is wrapped; info() falls back to defaults', async () => {
  const boom = () => {
    throw new Error('bridge down');
  };
  const N = { pollMotion: boom, motionAvailable: boom, startMotion: boom, stopMotion: boom, motionInfo: () => 'not json', vibrate: boom, vibratePattern: boom, say: boom, stopSpeech: boom, rotation: boom, lockOrientation: boom, exit: boom };
  const p = createPlatform({ window: fakeWindow({ SpartanNative: N }) });
  assert.equal(await p.motion.isSupported(), false);
  p.motion.start();
  assert.deepEqual(p.motion.poll(), []);
  p.motion.stop();
  p.haptics.pulse(20);
  p.haptics.pattern([0, 10, 20, 10]);
  p.voice.say('Hold the line!');
  p.voice.stop();
  p.lockOrientation(true);
  p.exit();
  assert.equal(p.rotation(), 0);
  assert.deepEqual(p.motion.info(), { gyroRange: 34.9, accRange: 78.5, rateHz: 0 });
});

test('android: haptics and voice follow the bound settings and clamp their arguments', async () => {
  const N = fakeNative();
  const p = createPlatform({ window: fakeWindow({ SpartanNative: N }) });
  const settings = { haptics: true, voice: true };
  p.bindSettings(settings);
  p.haptics.pulse(22, 0.7);
  p.haptics.pulse(900, 5);
  p.haptics.pulse(0);
  p.haptics.pulse(30, 0);
  p.haptics.pattern([0, 12, 30, 12]);
  p.haptics.pattern(new Array(20).fill(500));
  p.voice.say('Come and take them.', { pitch: 0.8 });
  settings.haptics = false;
  settings.voice = false;
  p.haptics.pulse(40);
  p.haptics.pattern([0, 20]);
  p.voice.say('silent');
  p.voice.stop();
  assert.deepEqual(N.calls, [
    ['vibrate', 22, 179],
    ['vibrate', 400, 255],
    ['vibratePattern', '0,12,30,12'],
    ['vibratePattern', new Array(16).fill(400).join(',')],
    ['say', 'Come and take them.', 0.8, 0.95],
    ['stopSpeech'],
  ]);
  assert.equal(await p.motion.isSupported(), true);
  assert.equal(await p.motion.requestPermission(), true);
  assert.equal(p.rotation(), 270);
  p.lockOrientation(true);
  p.exit();
  assert.deepEqual(N.calls.slice(-3).map((c) => c[0]), ['motionAvailable', 'lockOrientation', 'exit']);
});

test('inject(): re-based to lastT()+0.01 with spacing kept, released in real time, real samples discarded', () => {
  let now = 100;
  const N = fakeNative();
  const p = createPlatform({ window: fakeWindow({ SpartanNative: N }), now: () => now });
  const synth = (t0, n, dt = 0.01) => Array.from({ length: n }, (_, i) => ({ t: t0 + i * dt, gyro: [i, 0, 0], acc: null, accG: [0, 9.81, 0], grav: null, rot: 90 }));

  // not started: injected samples are still returned
  p.motion.inject(synth(500, 5, 0.02));
  assert.deepEqual(p.motion.poll().map((s) => +s.t.toFixed(6)), [0.01], 'first at lastT()+0.01 (lastT = 0), released at once');
  now += 0.05;
  assert.deepEqual(p.motion.poll().map((s) => +s.t.toFixed(6)), [0.03, 0.05]);
  now += 1;
  assert.deepEqual(p.motion.poll().map((s) => +s.t.toFixed(6)), [0.07, 0.09]);
  assert.ok(Math.abs(p.motion.lastT() - 0.09) < 1e-9);

  // started, with real data flowing: real samples are discarded while the queue drains
  p.motion.start();
  N.push(line(7.0));
  const src = synth(3, 4);
  p.motion.inject(src);
  assert.equal(src[0].t, 3, 'the caller\'s samples are not modified');
  N.push(line(7.01));
  const a = p.motion.poll();
  assert.deepEqual(a.map((s) => +s.t.toFixed(6)), [0.1]);
  assert.equal(a[0].gyro[0], 0);
  // appending while queued continues right after the queued ones
  p.motion.inject(synth(9, 2));
  now += 0.5;
  N.push(line(7.5));
  assert.deepEqual(p.motion.poll().map((s) => +s.t.toFixed(6)), [0.11, 0.12, 0.13, 0.14, 0.15]);
  // the queue is empty again: real samples flow
  N.push(line(8.0));
  assert.deepEqual(p.motion.poll().map((s) => s.t), [8.0]);
  assert.equal(p.motion.lastT(), 8.0);
  p.motion.inject([]);
  p.motion.inject(null);
  assert.equal(p.motion.poll().length, 0);
});

/** Dispatches a DeviceMotionEvent-like event on a fake window. */
function motionEvent(win, { t, alpha = 0, beta = 0, gamma = 0, acc = { x: 0, y: 0, z: 0 }, accG = { x: 0, y: 9.81, z: 0 }, interval = 16 }) {
  const e = new Event('devicemotion');
  Object.defineProperty(e, 'timeStamp', { value: t * 1000 });
  e.rotationRate = alpha === null ? null : { alpha, beta, gamma };
  e.acceleration = acc;
  e.accelerationIncludingGravity = accG;
  e.interval = interval;
  win.dispatchEvent(e);
}

test('web: devicemotion → RawSample (deg/s → rad/s, alpha→x, beta→y, gamma→z), screen angle, ring buffer', () => {
  const win = fakeWindow({ DeviceMotionEvent: class {} });
  const p = createPlatform({ window: win });
  assert.equal(p.kind, 'web');
  motionEvent(win, { t: 1 });
  assert.equal(p.motion.poll().length, 0, 'not listening before start()');
  p.motion.start();
  motionEvent(win, { t: 1.0, alpha: 180, beta: -90, gamma: 57.29577951308232 });
  win.screen.orientation.angle = -90;
  motionEvent(win, { t: 1.016, acc: null, accG: { x: 1, y: null, z: 2 } });
  delete win.screen.orientation;
  win.orientation = 90;
  motionEvent(win, { t: 1.016 }); // repeated timestamp → kept monotonic
  motionEvent(win, { t: 1.05, alpha: null }); // no gyro → ignored
  const out = p.motion.poll();
  assert.equal(out.length, 3);
  assert.ok(Math.abs(out[0].gyro[0] - Math.PI) < 1e-12 && Math.abs(out[0].gyro[1] + Math.PI / 2) < 1e-12 && Math.abs(out[0].gyro[2] - 1) < 1e-12);
  assert.deepEqual(out[0].acc, [0, 0, 0]);
  assert.deepEqual(out[0].accG, [0, 9.81, 0]);
  assert.equal(out[0].grav, null);
  assert.equal(out[0].rot, 90);
  assert.equal(out[1].acc, null);
  assert.equal(out[1].accG, null, 'partial vectors are not readings');
  assert.equal(out[1].rot, 270);
  assert.ok(out[2].t > out[1].t, 'monotonic');
  assert.equal(out[2].rot, 90, 'window.orientation fallback');
  for (let i = 0; i < 400; i++) motionEvent(win, { t: 2 + i * 0.016 });
  const many = p.motion.poll();
  assert.equal(many.length, 256, 'the ring keeps the newest 256');
  assert.ok(Math.abs(many[255].t - (2 + 399 * 0.016)) < 1e-9);
  assert.ok(Math.abs(p.motion.rate() - 62.5) < 0.5, `rate ${p.motion.rate()}`);
  p.motion.stop();
  motionEvent(win, { t: 20 });
  assert.equal(p.motion.poll().length, 0);
  assert.deepEqual(p.motion.info(), { gyroRange: 34.9, accRange: 78.5, rateHz: p.motion.rate() });
});

test('web: isSupported() probes for 1.5 s; requestPermission() asks iOS', async () => {
  const win = fakeWindow({ DeviceMotionEvent: class {} });
  const p = createPlatform({ window: win });
  const pending = p.motion.isSupported();
  motionEvent(win, { t: 1, alpha: null }); // desktop Chrome: an event without a gyro
  motionEvent(win, { t: 1.1, alpha: 1 });
  assert.equal(await pending, true);
  assert.equal(await p.motion.isSupported(), true, 'remembered');

  const win2 = fakeWindow({ DeviceMotionEvent: class {} });
  const p2 = createPlatform({ window: win2 });
  const pending2 = p2.motion.isSupported();
  win2.runTimers();
  assert.equal(await pending2, false, 'no event within 1.5 s');
  assert.equal(await createPlatform({ window: fakeWindow() }).motion.isSupported(), false, 'no DeviceMotionEvent');
  assert.equal(await p2.motion.requestPermission(), true, 'nothing to ask');

  for (const [answer, expected] of [['granted', true], ['denied', false], [null, false]]) {
    const DME = class {};
    DME.requestPermission = async () => {
      if (answer === null) throw new Error('needs a user gesture');
      return answer;
    };
    assert.equal(await createPlatform({ window: fakeWindow({ DeviceMotionEvent: DME }) }).motion.requestPermission(), expected);
  }
});

test('web: navigator.vibrate patterns, speechSynthesis en-GB, orientation lock', async () => {
  const vib = [];
  const spoken = [];
  let cancels = 0;
  class Utterance {
    constructor(text) {
      this.text = text;
    }
  }
  const voices = [{ lang: 'en-US', name: 'us' }, { lang: 'en_GB', name: 'gb' }, { lang: 'el-GR', name: 'el' }];
  const ss = Object.assign(new EventTarget(), { getVoices: () => voices, cancel: () => cancels++, speak: (u) => spoken.push(u) });
  const locks = [];
  const win = fakeWindow({
    navigator: { vibrate: (x) => vib.push(x) },
    speechSynthesis: ss,
    SpeechSynthesisUtterance: Utterance,
  });
  win.screen.orientation = { angle: 90, lock: (o) => (locks.push(o), Promise.reject(new Error('needs fullscreen'))), unlock: () => locks.push('unlock') };
  const p = createPlatform({ window: win });
  p.haptics.pulse(30, 0.8);
  p.haptics.pattern([0, 12, 30, 12]);
  p.haptics.pattern([50, 20]);
  p.voice.say('Good. Then we shall fight in the shade.', { pitch: 1.1, rate: 0.9 });
  p.voice.say('');
  p.bindSettings({ haptics: false, voice: false });
  p.haptics.pulse(30);
  p.voice.say('muted');
  p.voice.stop();
  assert.deepEqual(vib, [30, [12, 30, 12], [0, 50, 20]]);
  assert.equal(spoken.length, 1);
  assert.equal(spoken[0].voice.name, 'gb');
  assert.equal(spoken[0].lang, 'en-GB');
  assert.equal(spoken[0].pitch, 1.1);
  assert.equal(spoken[0].rate, 0.9);
  assert.equal(cancels, 2, 'cancel before speaking, and stop()');
  p.lockOrientation(true);
  p.lockOrientation(false);
  await new Promise((r) => setTimeout(r, 0)); // the rejected lock() must be caught
  assert.deepEqual(locks, ['landscape', 'unlock']);
  assert.equal(p.rotation(), 90);
});

test('storage: JSON in localStorage, falls back to memory, survives corrupt data', () => {
  const data = new Map();
  const ls = { getItem: (k) => (data.has(k) ? data.get(k) : null), setItem: (k, v) => data.set(k, String(v)), removeItem: (k) => data.delete(k) };
  const p = createPlatform({ window: fakeWindow({ localStorage: ls }) });
  assert.equal(p.storage.load('thermopylae.settings', 'fallback'), 'fallback');
  p.storage.save('thermopylae.settings', { control: 'motion', sensitivity: 1.2 });
  assert.equal(data.get('thermopylae.settings'), '{"control":"motion","sensitivity":1.2}');
  const a = p.storage.load('thermopylae.settings', {});
  a.control = 'touch';
  assert.equal(p.storage.load('thermopylae.settings', {}).control, 'motion', 'fresh objects');
  data.set('bad', '{nope');
  assert.deepEqual(p.storage.load('bad', { ok: 1 }), { ok: 1 });

  const blocked = fakeWindow();
  Object.defineProperty(blocked, 'localStorage', { get: () => { throw new Error('SecurityError'); } });
  const q = createPlatform({ window: blocked });
  q.storage.save('thermopylae.best', { score: 10 });
  assert.deepEqual(q.storage.load('thermopylae.best', null), { score: 10 });

  const full = { getItem: () => null, setItem: (k) => { if (k !== '__thermopylae_probe') throw new Error('QuotaExceeded'); }, removeItem() {} };
  const r = createPlatform({ window: fakeWindow({ localStorage: full }) });
  r.storage.save('k', [1, 2]);
  assert.deepEqual(r.storage.load('k', null), [1, 2]);
  r.storage.save('cyclic', (() => { const o = {}; o.o = o; return o; })());
  assert.equal(r.storage.load('cyclic', 'none'), 'none');
  assert.equal(createPlatform({ window: null }).storage.load('x', 7), 7, 'no window at all (Node)');
});

test('lifecycle: nativepause/nativeresume and visibilitychange, deduplicated; onBack; exit', () => {
  const win = fakeWindow({ close: () => (win.closed = true) });
  const p = createPlatform({ window: win });
  const log = [];
  const off = p.onPause(() => log.push('pause'));
  p.onResume(() => log.push('resume'));
  p.onPause(() => {
    throw new Error('a bad handler does not stop the others');
  });
  const errors = console.error;
  console.error = () => {};
  win.dispatchEvent(new Event('nativepause'));
  win.document.visibilityState = 'hidden';
  win.document.dispatchEvent(new Event('visibilitychange'));
  win.dispatchEvent(new Event('nativeresume'));
  win.document.visibilityState = 'visible';
  win.document.dispatchEvent(new Event('visibilitychange'));
  off();
  win.dispatchEvent(new Event('nativepause'));
  console.error = errors;
  assert.deepEqual(log, ['pause', 'resume']);

  p.onBack(() => 1);
  assert.equal(win.__onBack(), true);
  p.onBack(() => {
    throw new Error('x');
  });
  assert.equal(win.__onBack(), false);
  p.exit();
  assert.equal(win.closed, true);
});

test('android: every bridge call passes exactly the arguments NativeBridge.kt declares', async () => {
  // Android's JS bridge finds a method by name AND argument count; a call with extra (even undefined)
  // arguments throws "Method not found" on the device. Arities come from the Kotlin source itself.
  const { readFileSync } = await import('node:fs');
  const kt = readFileSync(new URL('../../src/main/java/com/thermopylae/NativeBridge.kt', import.meta.url), 'utf8');
  const arity = {};
  for (const m of kt.matchAll(/@JavascriptInterface\s+fun\s+(\w+)\s*\(([^)]*)\)/g)) {
    arity[m[1]] = m[2].trim() ? m[2].split(',').length : 0;
  }
  assert.ok(Object.keys(arity).length >= 10, `parsed bridge methods: ${Object.keys(arity)}`);
  const N = fakeNative([line(1), line(1.005)]);
  const misuse = [];
  for (const name of Object.keys(arity)) {
    const fn = N[name];
    assert.equal(typeof fn, 'function', `fake bridge implements ${name}`);
    N[name] = function (...args) {
      if (args.length !== arity[name]) misuse.push(`${name} called with ${args.length} args, declared ${arity[name]}`);
      return fn.apply(this, args);
    };
  }
  const p = createPlatform({ window: fakeWindow({ SpartanNative: N }), now: () => 0 });
  p.bindSettings({ haptics: true, voice: true });
  await p.motion.isSupported();
  p.motion.start();
  p.motion.poll();
  p.motion.info();
  p.motion.stop();
  p.haptics.pulse(30, 0.8);
  p.haptics.pattern([0, 20, 40, 20]);
  p.voice.say('Hold the line!', { pitch: 0.8, rate: 0.9 });
  p.voice.stop();
  p.lockOrientation(true);
  p.lockOrientation(false);
  if (p.rotation) p.rotation();
  p.updates.state();
  p.updates.check();
  p.updates.install();
  p.exit();
  assert.deepEqual(misuse, []);
  const used = new Set(N.calls.map((c) => c[0]));
  for (const name of ['startMotion', 'stopMotion', 'vibrate', 'vibratePattern', 'say', 'stopSpeech', 'lockOrientation', 'exit', 'updateState', 'checkForUpdate', 'installUpdate']) {
    assert.ok(used.has(name), `${name} reached the bridge`);
  }
});

test('updates: state comes from the bridge, changes arrive as appupdate events, the web reports disabled', () => {
  const N = fakeNative();
  const win = fakeWindow({ SpartanNative: N });
  const p = createPlatform({ window: win, now: () => 0 });
  assert.equal(p.updates.state().state, 'current');
  assert.equal(p.updates.state().installed, 41);
  N.update = 'not json';
  assert.equal(p.updates.state().enabled, false, 'a bad answer reads as "no updater"');
  const seen = [];
  const off = p.updates.onChange((st) => seen.push(st.state));
  win.dispatchEvent(Object.assign(new Event('appupdate'), { detail: { state: 'available', enabled: true, available: 42 } }));
  win.dispatchEvent(Object.assign(new Event('appupdate'), { detail: '{"state":"downloading","enabled":true}' }));
  win.dispatchEvent(Object.assign(new Event('appupdate'), { detail: null }));
  off();
  win.dispatchEvent(Object.assign(new Event('appupdate'), { detail: { state: 'ready', enabled: true } }));
  assert.deepEqual(seen, ['available', 'downloading']);

  const web = createPlatform({ window: fakeWindow(), now: () => 0 });
  assert.deepEqual([web.updates.state().state, web.updates.state().enabled], ['idle', false]);
  web.updates.check();
  web.updates.install();
});
