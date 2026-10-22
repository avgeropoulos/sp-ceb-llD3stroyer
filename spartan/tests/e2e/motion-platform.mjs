// Browser checks for platform.js + the motion pipeline (DESIGN.md §5):
//  (a) a fake window.SpartanNative (NaN columns, Java-formatted numbers like 1.0E-4) parses correctly
//  (b) synthetic DeviceMotionEvents dispatched on window flow through poll()
//  (c) inject() re-bases timestamps, keeps spacing, paces in real time and discards real samples
//  (d) synthesize(swing R) → inject → poll → frames → gestures gives exactly one R slash
// Usage: node spartan/tests/e2e/motion-platform.mjs
import assert from 'node:assert/strict';
import { launch } from './lib.mjs';

const PAGE = '/tests/sandbox/motion-platform.html';
const l = await launch();
let failed = 0;
async function check(name, fn) {
  const page = await l.newPage({ mobile: true, width: 800, height: 400 });
  try {
    await fn(page);
    assert.deepEqual(page.errors, []);
    console.log(`ok - ${name}`);
  } catch (err) {
    failed++;
    console.log(`not ok - ${name}\n${err.stack || err}`);
  } finally {
    await page.context().close();
  }
}
const open = async (page) => {
  await page.goto(l.base + PAGE);
  await page.waitForFunction(() => window.__motion);
};

await check('(a) android bridge: pollMotion lines with NaN columns and 1.0E-4 numbers', async (page) => {
  await page.addInitScript(() => {
    const lines = [
      '0.005,0.1,-0.2,0.3,1.0E-4,0.0,-0.0,9.8,0.1,0.2,9.81,0.0,0.0,90\n',
      '0.010,1.5E-3,2.0,-3.25,NaN,NaN,NaN,0.5,9.7,1.0E1,NaN,NaN,NaN,270\n',
      '0.015,7.0E-5,-1.2345678E2,0.0,1,2,3,4,5,6,7,8,9,0\n',
    ];
    const calls = [];
    window.__nativeCalls = calls;
    window.SpartanNative = {
      motionAvailable: () => true,
      startMotion: () => calls.push('start'),
      stopMotion: () => calls.push('stop'),
      pollMotion: () => lines.splice(0).join(''),
      motionInfo: () => '{"gyroRange":34.906586,"accRange":78.4532,"rateHz":200.3}',
      vibrate: (ms, a) => calls.push(`vibrate ${ms} ${a}`),
      vibratePattern: (csv) => calls.push(`pattern ${csv}`),
      say: (t, p, r) => calls.push(`say ${t} ${p} ${r}`),
      stopSpeech: () => calls.push('stopSpeech'),
      rotation: () => 90,
      lockOrientation: (b) => calls.push(`lock ${b}`),
      exit: () => calls.push('exit'),
    };
  });
  await open(page);
  const r = await page.evaluate(async () => {
    const p = window.__motion.createPlatform();
    p.bindSettings({ haptics: true, voice: true });
    const supported = await p.motion.isSupported();
    p.motion.start();
    const out = p.motion.poll().map((s) => ({ t: s.t, gyro: [...s.gyro], acc: s.acc && [...s.acc], accG: s.accG && [...s.accG], grav: s.grav && [...s.grav], rot: s.rot }));
    p.haptics.pulse(40, 1);
    p.haptics.pattern([0, 25, 60, 25]);
    p.voice.say('Hold the line!', { pitch: 0.8, rate: 1 });
    p.lockOrientation(true);
    let handled = null;
    p.onBack(() => true);
    handled = window.__onBack();
    return { kind: p.kind, supported, out, info: p.motion.info(), calls: window.__nativeCalls, handled, lastT: p.motion.lastT() };
  });
  assert.equal(r.kind, 'android');
  assert.equal(r.supported, true);
  assert.equal(r.out.length, 3);
  assert.deepEqual(r.out[0], { t: 0.005, gyro: [0.1, -0.2, 0.3], acc: [0.0001, 0, -0], accG: [9.8, 0.1, 0.2], grav: [9.81, 0, 0], rot: 90 });
  assert.deepEqual(r.out[1], { t: 0.01, gyro: [0.0015, 2, -3.25], acc: null, accG: [0.5, 9.7, 10], grav: null, rot: 270 });
  assert.deepEqual(r.out[2].gyro, [7e-5, -123.45678, 0]);
  assert.equal(r.lastT, 0.015);
  assert.deepEqual(r.info, { gyroRange: 34.906586, accRange: 78.4532, rateHz: 200.3 });
  assert.deepEqual(r.calls, ['start', 'vibrate 40 255', 'pattern 0,25,60,25', 'say Hold the line! 0.8 1', 'lock true']);
  assert.equal(r.handled, true);
});

await check('(b) web: DeviceMotionEvents dispatched on window flow through poll()', async (page) => {
  await open(page);
  const r = await page.evaluate(async () => {
    Object.defineProperty(ScreenOrientation.prototype, 'angle', { configurable: true, get: () => 90 });
    const p = window.__motion.createPlatform();
    const fire = (alpha, beta, gamma, acc = { x: 0.5, y: -0.25, z: 1 }) =>
      window.dispatchEvent(new DeviceMotionEvent('devicemotion', {
        acceleration: acc,
        accelerationIncludingGravity: { x: 9.8, y: 0.1, z: 0.2 },
        rotationRate: { alpha, beta, gamma },
        interval: 16,
      }));
    const probe = p.motion.isSupported();
    fire(0, 0, 0);
    const supported = await probe;
    p.motion.start();
    fire(180, -90, 45);
    fire(1, 2, 3, null);
    const out = p.motion.poll().map((s) => ({ t: s.t, gyro: [...s.gyro], acc: s.acc && [...s.acc], accG: s.accG && [...s.accG], grav: s.grav, rot: s.rot }));
    p.motion.stop();
    fire(5, 5, 5);
    const after = p.motion.poll().length;
    // a real probe with no events at all
    const quiet = await window.__motion.createPlatform().motion.isSupported();
    return { kind: p.kind, supported, out, after, quiet, perm: await p.motion.requestPermission() };
  });
  assert.equal(r.kind, 'web');
  assert.equal(r.supported, true);
  assert.equal(r.out.length, 2);
  const d = Math.PI / 180;
  for (const [i, v] of [180 * d, -90 * d, 45 * d].entries()) assert.ok(Math.abs(r.out[0].gyro[i] - v) < 1e-12, `alpha/beta/gamma → x/y/z rad/s: ${r.out[0].gyro}`);
  assert.deepEqual(r.out[0].acc, [0.5, -0.25, 1]);
  assert.deepEqual(r.out[0].accG, [9.8, 0.1, 0.2]);
  assert.equal(r.out[0].grav, null);
  assert.equal(r.out[0].rot, 90);
  assert.equal(r.out[1].acc, null);
  assert.ok(r.out[1].t > r.out[0].t && r.out[0].t > 0, 'event.timeStamp in seconds, monotonic');
  assert.equal(r.after, 0, 'stopped');
  assert.equal(r.quiet, false, 'no devicemotion within 1.5 s');
  assert.equal(r.perm, true);
});

await check('(c) inject(): re-based to lastT()+0.01, spacing kept, real-time pacing, real samples discarded', async (page) => {
  await open(page);
  const r = await page.evaluate(async () => {
    const p = window.__motion.createPlatform();
    p.motion.start();
    const fire = () => window.dispatchEvent(new DeviceMotionEvent('devicemotion', { rotationRate: { alpha: 1, beta: 1, gamma: 1 }, accelerationIncludingGravity: { x: 0, y: 9.8, z: 0 }, interval: 16 }));
    fire();
    const real = p.motion.poll().map((s) => s.t);
    const lastReal = p.motion.lastT();
    const samples = [];
    for (let i = 0; i < 40; i++) samples.push({ t: 1000 + i * 0.005, gyro: [i, 0, 0], acc: null, accG: [0, 9.81, 0], grav: null, rot: 90 });
    p.motion.inject(samples);
    const got = [];
    const t0 = performance.now();
    const firstPoll = p.motion.poll().map((s) => s.t);
    got.push(...firstPoll.map((t) => ({ t, injected: true })));
    while (performance.now() - t0 < 600) {
      fire(); // real samples during the demo must be dropped
      await new Promise((res) => setTimeout(res, 20));
      for (const s of p.motion.poll()) got.push({ t: s.t, injected: s.gyro[1] === 0 });
    }
    fire();
    const resumed = p.motion.poll().map((s) => s.t);
    return { real, lastReal, firstPoll, got, resumed, srcT: samples[0].t };
  });
  assert.equal(r.real.length, 1);
  assert.ok(Math.abs(r.firstPoll[0] - (r.lastReal + 0.01)) < 1e-9, 'first injected sample at lastT()+0.01');
  assert.ok(r.firstPoll.length < 5, `paced, not dumped: ${r.firstPoll.length} on the first poll`);
  const inj = r.got.filter((g) => g.injected);
  assert.equal(inj.length, 40, `all injected samples: ${inj.length}`);
  const lastInj = r.got.findLastIndex((g) => g.injected);
  assert.ok(r.got.slice(0, lastInj).every((g) => g.injected), 'no real sample while injected ones are queued');
  assert.ok(r.got.length > 40, 'real samples flow again once the queue drains');
  for (let i = 1; i < inj.length; i++) assert.ok(Math.abs(inj[i].t - inj[i - 1].t - 0.005) < 1e-9, 'spacing kept');
  assert.equal(r.srcT, 1000, 'the caller\'s array is untouched');
  assert.equal(r.resumed.length, 1);
});

await check('(d) synthesize(swing R) → inject → poll → frames → gestures: exactly one R slash', async (page) => {
  await open(page);
  const r = await page.evaluate(async () => {
    const { createPlatform, MotionFrames, GestureRecognizer, synthesize, scriptDuration } = window.__motion;
    const p = createPlatform(); // not started: injected samples still flow (training demo in touch mode)
    const script = [{ kind: 'idle', duration: 0.6 }, { kind: 'swing', dir: 0 }, { kind: 'idle', duration: 0.3 }];
    const frames = new MotionFrames({ platformKind: p.kind });
    const gestures = new GestureRecognizer({ sensitivity: 1, weapon: 'sword' });
    p.motion.inject(synthesize(script, { rot: 90, hand: 'right' }));
    const events = [];
    const end = performance.now() + (scriptDuration(script) + 0.3) * 1000;
    let polls = 0;
    while (performance.now() < end) {
      await new Promise((res) => requestAnimationFrame(res));
      polls++;
      for (const raw of p.motion.poll()) for (const e of gestures.push(frames.process(raw))) events.push({ ...e });
    }
    return { events, polls };
  });
  assert.ok(r.polls > 10, `polled per frame (${r.polls})`);
  const slashes = r.events.filter((e) => e.type === 'slash');
  assert.equal(r.events.length, 1, JSON.stringify(r.events));
  assert.equal(slashes.length, 1);
  assert.ok(Math.abs(slashes[0].dir) < Math.PI / 8, `dir ${slashes[0].dir} is R`);
});

await l.close();
if (failed) {
  console.log(`${failed} failed`);
  process.exit(1);
}
console.log('all motion-platform e2e checks passed');
