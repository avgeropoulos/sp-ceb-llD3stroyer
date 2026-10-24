// platform.lockOrientation(lock, orientation) and the host's orientation mapping
// (docs-hilt-grip.md §A.4, §A.5, §H.1 #9), in Node with a fake window and SpartanNative bridge.
// There is no JVM test source set in :spartan, so the Kotlin side is checked as source text, the
// way motion-platform.test.mjs reads NativeBridge.kt for the bridge arities.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createPlatform } from '../../src/main/assets/game/js/platform.js';

const kotlin = (name) => readFileSync(new URL(`../../src/main/java/com/thermopylae/${name}`, import.meta.url), 'utf8');

/** A minimal window with a screen.orientation whose lock() rejects outside fullscreen, as Chrome's does. */
function webWindow({ orientation = true } = {}) {
  const win = new EventTarget();
  win.document = new EventTarget();
  win.navigator = {};
  const locks = [];
  win.screen = {};
  if (orientation) {
    win.screen.orientation = {
      angle: 0,
      lock: (o) => (locks.push(o), Promise.reject(new Error('NotSupportedError: needs fullscreen'))),
      unlock: () => locks.push('unlock'),
    };
  }
  return { win, locks };
}

/** A fake bridge that records lockOrientation's arguments exactly as received. */
function nativeWindow() {
  const calls = [];
  const N = { pollMotion: () => '', lockOrientation: (...args) => calls.push(args) };
  const win = new EventTarget();
  win.document = new EventTarget();
  win.navigator = {};
  win.SpartanNative = N;
  return { win, calls, N };
}

/** Lets a rejected lock() promise surface as an unhandled rejection if nobody caught it. */
async function settle() {
  await new Promise((r) => setTimeout(r, 0));
}

test('web: portrait locks portrait-primary whether locked or not; landscape locks or unlocks', async () => {
  const unhandled = [];
  const onUnhandled = (e) => unhandled.push(e);
  process.on('unhandledRejection', onUnhandled);
  try {
    const { win, locks } = webWindow();
    const p = createPlatform({ window: win });
    assert.equal(p.kind, 'web');
    p.lockOrientation(false, 'portrait');
    p.lockOrientation(true, 'portrait');
    p.lockOrientation(true, 'landscape');
    p.lockOrientation(false, 'landscape');
    // The 1-argument calls keep their old meaning.
    p.lockOrientation(true);
    p.lockOrientation(false);
    // Anything but 'portrait' is landscape.
    p.lockOrientation(true, 'sideways');
    p.lockOrientation(false, null);
    await settle();
    assert.deepEqual(locks, ['portrait-primary', 'portrait-primary', 'landscape', 'unlock', 'landscape', 'unlock', 'landscape', 'unlock']);
    assert.deepEqual(unhandled, [], 'every refused lock() is caught');
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
});

test('web: no screen.orientation, or one that throws, is ignored', () => {
  const { win } = webWindow({ orientation: false });
  const p = createPlatform({ window: win });
  p.lockOrientation(true, 'portrait');
  p.lockOrientation(false, 'landscape');
  const boom = webWindow().win;
  boom.screen.orientation.lock = () => {
    throw new Error('SecurityError');
  };
  boom.screen.orientation.unlock = () => {
    throw new Error('SecurityError');
  };
  const q = createPlatform({ window: boom });
  q.lockOrientation(false, 'portrait');
  q.lockOrientation(false, 'landscape');
  createPlatform({ window: null }).lockOrientation(true, 'portrait');
});

test('android: always exactly (lock, "portrait"|"landscape"); an unknown orientation is landscape', () => {
  const { win, calls } = nativeWindow();
  const p = createPlatform({ window: win });
  assert.equal(p.kind, 'android');
  p.lockOrientation(false, 'portrait');
  p.lockOrientation(true, 'portrait');
  p.lockOrientation(true, 'landscape');
  p.lockOrientation(false, 'landscape');
  p.lockOrientation(true);
  p.lockOrientation(false);
  p.lockOrientation(true, 'portrait-primary');
  p.lockOrientation(false, 'PORTRAIT');
  p.lockOrientation(true, null);
  p.lockOrientation(1, 'portrait');
  p.lockOrientation(undefined, undefined);
  assert.deepEqual(calls, [
    [false, 'portrait'],
    [true, 'portrait'],
    [true, 'landscape'],
    [false, 'landscape'],
    [true, 'landscape'],
    [false, 'landscape'],
    [true, 'landscape'],
    [false, 'landscape'],
    [true, 'landscape'],
    [true, 'portrait'],
    [false, 'landscape'],
  ]);
  for (const c of calls) {
    assert.equal(c.length, 2);
    assert.equal(typeof c[0], 'boolean');
  }
});

test('android: a throwing bridge is caught', () => {
  const { win, N } = nativeWindow();
  N.lockOrientation = () => {
    throw new Error('Method not found');
  };
  const p = createPlatform({ window: win });
  p.lockOrientation(true, 'portrait');
  p.lockOrientation(false);
});

test('NativeBridge.kt declares lockOrientation(lock: Boolean, orientation: String?) and nothing with one argument', () => {
  const kt = kotlin('NativeBridge.kt');
  const decls = [...kt.matchAll(/@JavascriptInterface\s+fun\s+lockOrientation\s*\(([^)]*)\)/g)];
  assert.equal(decls.length, 1, 'one JS-visible lockOrientation');
  const params = decls[0][1].split(',').map((x) => x.trim().replace(/\s+/g, ' '));
  assert.deepEqual(params, ['lock: Boolean', 'orientation: String?']);
  // Every arity the page can reach goes through platform.js, which always passes 2 (above).
  const { win, calls, N } = nativeWindow();
  const seen = [];
  const rec = N.lockOrientation;
  N.lockOrientation = function (...args) {
    seen.push(args.length);
    return rec.apply(this, args);
  };
  const p = createPlatform({ window: win });
  p.lockOrientation(true);
  p.lockOrientation(false, 'portrait');
  assert.deepEqual(seen, [params.length, params.length]);
  assert.equal(calls.length, 2);
});

test('HostLogic.requestedOrientation: portrait → 1, !lock → 6, displayLandscape → 14, else → 0, in that order', () => {
  const kt = kotlin('HostLogic.kt');
  const m = kt.match(/fun requestedOrientation\(\s*lock: Boolean,\s*orientation: String\?,\s*displayLandscape: Boolean\s*\): Int =\s*when \{([\s\S]*?)\n\s*\}/);
  assert.ok(m, 'requestedOrientation(lock: Boolean, orientation: String?, displayLandscape: Boolean): Int = when { … }');
  const branches = m[1]
    .split('\n')
    .map((l) => l.replace(/\/\/.*$/, '').trim())
    .filter(Boolean)
    .map((l) => l.replace(/\s+/g, ' '));
  assert.deepEqual(branches, ['orientation == "portrait" -> 1', '!lock -> 6', 'displayLandscape -> 14', 'else -> 0']);
  // The int literals are ActivityInfo's: PORTRAIT 1, SENSOR_LANDSCAPE 6, LOCKED 14, LANDSCAPE 0
  // (pinned against android.jar by HostLogicTest on a JVM).
  assert.match(kt, /fun orientationFamily\(orientation: String\?\): String =\s*if \(orientation == "portrait"\) "portrait" else "landscape"/);
});

test('the host persists the family and starts the next launch in it', () => {
  const bridge = kotlin('NativeBridge.kt');
  const activity = kotlin('MainActivity.kt');
  // The bridge maps every request through HostLogic, reading the display's own orientation.
  assert.match(bridge, /HostLogic\.requestedOrientation\(lock, family, displayLandscape\)/);
  assert.match(bridge, /resources\.configuration\.orientation == Configuration\.ORIENTATION_LANDSCAPE/);
  assert.match(bridge, /putString\(HostLogic\.PREF_ORIENTATION, family\)/);
  assert.doesNotMatch(bridge, /SCREEN_ORIENTATION_/, 'no orientation constant outside HostLogic');
  // A new page re-requests the last family, unlocked, and never forces landscape.
  const onNewPage = bridge.match(/fun onNewPage\(\) \{([\s\S]*?)\n {4}\}/);
  assert.ok(onNewPage, 'onNewPage()');
  assert.match(onNewPage[1], /applyOrientation\(false, lastOrientation\)/);
  // MainActivity applies the stored family before setContentView, from the same preferences.
  const onCreate = activity.match(/override fun onCreate\(savedInstanceState: Bundle\?\) \{([\s\S]*?)\n {4}\}/);
  assert.ok(onCreate, 'onCreate()');
  const req = onCreate[1].indexOf('HostLogic.requestedOrientation(false, orientation, false)');
  const content = onCreate[1].indexOf('setContentView(');
  assert.ok(req > 0 && content > req, 'the stored orientation is requested before setContentView');
  assert.match(activity, /getSharedPreferences\(HostLogic\.HOST_PREFS, MODE_PRIVATE\)/);
  assert.match(activity, /getString\(HostLogic\.PREF_ORIENTATION, null\)/);
  assert.match(activity, /NativeBridge\(this, orientation\)/);
});

test('AndroidManifest.xml keeps sensorLandscape as the cold-start default and rotations in configChanges', () => {
  const xml = readFileSync(new URL('../../src/main/AndroidManifest.xml', import.meta.url), 'utf8');
  assert.match(xml, /android:screenOrientation="sensorLandscape"/);
  const changes = xml.match(/android:configChanges="([^"]*)"/)[1].split('|');
  for (const c of ['orientation', 'screenSize', 'smallestScreenSize', 'screenLayout']) assert.ok(changes.includes(c), c);
});
