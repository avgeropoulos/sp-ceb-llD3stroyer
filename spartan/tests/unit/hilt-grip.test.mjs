// Unit tests for the PURE grip helpers (js/core/grip.js, docs-hilt-grip.md §A.1–A.3) and the
// main.js glue that must use them (main.js touches the DOM at import, so it is read as text).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  GRIP_IDS, HILT_RELEASED, DEFAULT_GRIP, effectiveGrip, appOrientation, sensitivityKey, flipKeys, migrateSettings,
} from '../../src/main/assets/game/js/core/grip.js';

const MAIN = readFileSync(new URL('../../src/main/assets/game/js/main.js', import.meta.url), 'utf8');

/** main.js's DEFAULT_SETTINGS, evaluated from its source with the real DEFAULT_GRIP. */
function mainDefaults() {
  const m = MAIN.match(/const DEFAULT_SETTINGS = (\{[\s\S]*?\n\});/);
  assert.ok(m, 'main.js declares DEFAULT_SETTINGS');
  return new Function('DEFAULT_GRIP', `return (${m[1]});`)(DEFAULT_GRIP);
}

/** The body of a top-level-in-boot function or arrow, by its opening text (to the next line at its indent + '}'). */
function body(open) {
  const i = MAIN.indexOf(open);
  assert.ok(i >= 0, `main.js has ${open}`);
  const lineStart = MAIN.lastIndexOf('\n', i) + 1;
  const indent = MAIN.slice(lineStart, i).match(/^ */)[0];
  const end = MAIN.indexOf(`\n${indent}}`, i);
  return MAIN.slice(i, end);
}

// [settings, effectiveGrip, appOrientation, sensitivityKey, flip x key, flip y key]
const TABLE = [
  [{ control: 'motion', grip: 'hilt' }, 'hilt', 'portrait', 'sensitivityHilt', 'motionFlipXHilt', 'motionFlipYHilt'],
  [{ control: 'motion', grip: 'window' }, 'window', 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'motion' }, 'window', 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'motion', grip: 'sideways' }, 'window', 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'motion', grip: 'HILT' }, 'window', 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'motion', grip: null }, 'window', 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'touch', grip: 'hilt' }, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'touch', grip: 'window' }, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'touch' }, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ control: 'keyboard', grip: 'hilt' }, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{ grip: 'hilt' }, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [{}, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [null, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
  [undefined, null, 'landscape', 'sensitivity', 'motionFlipX', 'motionFlipY'],
];

test('GRIP_IDS, HILT_RELEASED and DEFAULT_GRIP', () => {
  assert.deepEqual([...GRIP_IDS], ['hilt', 'window']);
  assert.ok(Object.isFrozen(GRIP_IDS));
  assert.equal(typeof HILT_RELEASED, 'boolean');
  assert.equal(DEFAULT_GRIP, HILT_RELEASED ? 'hilt' : 'window', 'DEFAULT_GRIP follows HILT_RELEASED');
  assert.ok(GRIP_IDS.includes(DEFAULT_GRIP));
});

test('effectiveGrip: the grip in motion (missing or unknown → window), null otherwise', () => {
  for (const [s, grip] of TABLE) assert.equal(effectiveGrip(s), grip, JSON.stringify(s));
});

test('appOrientation: portrait iff motion with the hilt grip', () => {
  for (const [s, , orientation] of TABLE) assert.equal(appOrientation(s), orientation, JSON.stringify(s));
});

test('sensitivityKey: the hilt grip keeps its own sensitivity', () => {
  for (const [s, , , key] of TABLE) assert.equal(sensitivityKey(s), key, JSON.stringify(s));
});

test('flipKeys: each grip has its own pair, as one of two frozen constants', () => {
  for (const [s, , , , x, y] of TABLE) {
    const k = flipKeys(s);
    assert.deepEqual({ ...k }, { x, y }, JSON.stringify(s));
    assert.ok(Object.isFrozen(k));
  }
  const hilt = { control: 'motion', grip: 'hilt' };
  const win = { control: 'motion', grip: 'window' };
  assert.equal(flipKeys(hilt), flipKeys({ ...hilt }), 'the same object every call (no allocation)');
  assert.equal(flipKeys(win), flipKeys({ control: 'touch' }));
  assert.notEqual(flipKeys(hilt), flipKeys(win));
});

test('migrateSettings: a motion player without a grip keeps the window grip; nothing else changes', () => {
  const old = { control: 'motion', sensitivity: 1.3, motionFlipX: true };
  assert.equal(migrateSettings(old), old, 'returns its argument');
  assert.deepEqual(old, { control: 'motion', sensitivity: 1.3, motionFlipX: true, grip: 'window' });

  const hilt = { control: 'motion', grip: 'hilt' };
  assert.deepEqual(migrateSettings(hilt), { control: 'motion', grip: 'hilt' }, 'a stored grip is kept');
  const win = { control: 'motion', grip: 'window', gripChosen: true };
  assert.deepEqual(migrateSettings(win), { control: 'motion', grip: 'window', gripChosen: true });

  const touch = { control: 'touch', sensitivity: 0.8 };
  migrateSettings(touch);
  assert.ok(!('grip' in touch), 'touch is untouched');
  const fresh = {};
  migrateSettings(fresh);
  assert.ok(!('grip' in fresh), 'a fresh install has no grip key, so DEFAULT_GRIP applies');

  assert.equal(migrateSettings(null), null);
  assert.equal(migrateSettings(undefined), undefined);
  assert.equal(migrateSettings(7), 7);
});

test('main.js DEFAULT_SETTINGS carries the grip keys with their defaults (§A.1)', () => {
  const d = mainDefaults();
  const want = {
    grip: DEFAULT_GRIP, gripChosen: false, sensitivityHilt: 1.0, motionFlipXHilt: false, motionFlipYHilt: false,
    lockAuto: true, hiltFingerStrip: 44, hiltAgogeDone: false, hiltSafetyAccepted: false,
  };
  for (const [k, v] of Object.entries(want)) assert.strictEqual(d[k], v, k);
  // The window grip's keys are unchanged.
  assert.equal(d.control, 'touch');
  assert.equal(d.sensitivity, 1.0);
  assert.equal(d.motionFlipX, false);
  assert.equal(d.motionFlipY, false);
  assert.equal(d.safetyAccepted, false);
});

test('boot as main.js does it: defaults merged over the migrated stored settings', () => {
  const d = mainDefaults();
  const load = (stored) => Object.assign({}, d, migrateSettings(stored));

  const fresh = load({});
  assert.equal(fresh.grip, DEFAULT_GRIP);
  assert.strictEqual(fresh.gripChosen, false, 'the chooser trigger is gripChosen === false');
  assert.equal(effectiveGrip(fresh), null, 'a new player starts on touch');
  assert.equal(effectiveGrip({ ...fresh, control: 'motion' }), DEFAULT_GRIP);

  const oldMotion = load({ control: 'motion', sensitivity: 1.4, motionFlipY: true });
  assert.equal(effectiveGrip(oldMotion), 'window', 'an existing motion player keeps the window grip');
  assert.equal(appOrientation(oldMotion), 'landscape');
  assert.equal(oldMotion[sensitivityKey(oldMotion)], 1.4);
  assert.equal(oldMotion[flipKeys(oldMotion).y], true);
  assert.strictEqual(oldMotion.gripChosen, false);

  const hilt = load({ control: 'motion', grip: 'hilt', gripChosen: true, sensitivityHilt: 0.7 });
  assert.equal(appOrientation(hilt), 'portrait');
  assert.equal(hilt[sensitivityKey(hilt)], 0.7);
  assert.equal(hilt[flipKeys(hilt).x], false);
});

test('main.js glue goes through the grip helpers', () => {
  // Migration runs before the settings are bound, and ?grip= overrides and answers the chooser.
  assert.ok(MAIN.indexOf('migrateSettings(') > 0 && MAIN.indexOf('migrateSettings(') < MAIN.indexOf('platform.bindSettings('));
  assert.match(MAIN, /GRIP_IDS\.includes\(params\.get\('grip'\)\)/);

  // The orientation is requested in exactly one place: applyControls, with 2 arguments.
  const apply = body('ctx.applyControls = () => {');
  assert.equal(MAIN.match(/platform\.lockOrientation\(/g).length, 1);
  assert.match(apply, /platform\.lockOrientation\(active && settings\.control === 'motion', appOrientation\(settings\)\)/);
  assert.match(apply, /ctx\.input\.grip = grip/);
  assert.match(apply, /ctx\.motionFrames\.setGrip\?\.\(g\)/);
  assert.match(apply, /ctx\.gestures\.setGrip\?\.\(g\)/);
  assert.match(apply, /dataset\.grip/);
  assert.match(body('ctx.setMode = (mode, opts = {}) => {'), /ctx\.applyControls\(\);/);

  // pumpMotion reads the effective grip's sensitivity and flips, never the window grip's directly.
  const pump = body('function pumpMotion(dt) {');
  assert.match(pump, /setSensitivity\(settings\[sensitivityKey\(settings\)\]\)/);
  assert.match(pump, /flipKeys\(settings\)/);
  assert.doesNotMatch(MAIN, /settings\.(sensitivity|motionFlipX|motionFlipY)\b/);

  // The world camera's FOV comes from gfx/framing.js.
  assert.match(body('function applySize() {'), /camera\.fov = cameraFov\(/);
});
