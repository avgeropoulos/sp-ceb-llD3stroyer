// Imports every game module under Node, with no DOM, to prove that none of them touches browser
// globals at import time (DESIGN.md §2 module rules, §16). 'three' and 'three/addons/*' resolve
// to the vendored files through a resolve hook, the way the import map does in the page.
// It also checks the static import rules: PURE modules import only PURE modules by relative
// '.js' paths, only the two vendored addons are imported, and game logic has no timers.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { register } from 'node:module';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const gameDir = resolve(here, '../../src/main/assets/game');
const jsDir = join(gameDir, 'js');
const threeBase = pathToFileURL(join(gameDir, 'lib/three') + sep).href;

const hooks = `
const base = ${JSON.stringify(threeBase)};
export async function resolve(specifier, context, next) {
  if (specifier === 'three') return next(base + 'three.module.js', context);
  if (specifier.startsWith('three/addons/')) return next(base + 'addons/' + specifier.slice('three/addons/'.length), context);
  return next(specifier, context);
}
`;
register('data:text/javascript,' + encodeURIComponent(hooks));

// Node 21+ defines a global `navigator`; browsers' globals must be absent here, as on Node 20.
if ('navigator' in globalThis) {
  if (!delete globalThis.navigator) {
    Object.defineProperty(globalThis, 'navigator', { value: undefined, configurable: true, writable: true });
  }
}

const PURE = new Set([
  'core/math.js', 'core/noise.js',
  'motion/frames.js', 'motion/gestures.js', 'motion/synth.js',
  'game/rules.js', 'game/combos.js',
  'audio/voicelines.js', 'audio/voicebank.js',
]);

const ADDONS = new Set(['three/addons/Sky.js', 'three/addons/BufferGeometryUtils.js']);

/** Exports each module must provide (DESIGN.md §4, §5, §7, §8, §11). */
const CONTRACT = {
  'core/events.js': ['EventBus'],
  'core/math.js': ['TAU', 'clamp', 'lerp', 'invLerp', 'smoothstep', 'damp', 'wrapAngle', 'angleDiff', 'createRng',
    'v3', 'v3add', 'v3sub', 'v3scale', 'v3dot', 'v3cross', 'v3len', 'v3norm',
    'yawToDir', 'yawTo', 'facingTo', 'facingToYaw', 'yawToFacing', 'forward3', 'right3'],
  'core/noise.js': ['createNoise'],
  'platform.js': ['createPlatform'],
  'motion/frames.js': ['remapToScreen', 'MotionFrames'],
  'motion/gestures.js': ['GESTURE_DEFAULTS', 'GestureRecognizer'],
  'motion/synth.js': ['synthesize', 'scriptDuration'],
  'game/rules.js': ['TIMING', 'WEAPONS', 'ENEMIES', 'DIRECTOR', 'dirToken', 'TOKEN_SETS', 'slashProfile', 'sectorHit',
    'pickTarget', 'segmentCapsule', 'ballisticPitch', 'computeDamage'],
  'game/combos.js': ['COMBOS', 'ComboTracker'],
  'game/player.js': ['createPlayer'],
  'game/combat.js': ['createCombat'],
  'game/ai.js': ['createAI'],
  'game/waves.js': ['createWaves'],
  'gfx/textures.js': ['createTextures'],
  'gfx/world.js': ['createWorld'],
  'gfx/characters.js': ['createCharacters', 'CLIPS'],
  'gfx/viewmodel.js': ['createViewmodel'],
  'gfx/fx.js': ['createFx'],
  'audio/audio.js': ['createAudio'],
  'ui/hud.js': ['createHud'],
  'ui/menus.js': ['createMenus'],
  'ui/input.js': ['createInput'],
  'ui/training.js': ['createTraining'],
};

/** Every .js under js/ except main.js, as paths relative to js/ with '/' separators, sorted. */
function listModules(dir = jsDir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listModules(full));
    else if (entry.isFile() && entry.name.endsWith('.js')) out.push(relative(jsDir, full).split(sep).join('/'));
  }
  return out.filter((m) => m !== 'main.js');
}

function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:\\'"`])\/\/.*$/gm, '$1');
}

/** Static and dynamic import specifiers of a module's source. */
function importSpecifiers(src) {
  const code = stripComments(src);
  const specs = [];
  const patterns = [
    /\b(?:import|export)\s[^'"`;]*?\bfrom\s*(['"])([^'"]+)\1/g,
    /\bimport\s*(['"])([^'"]+)\1/g,
    /\bimport\s*\(\s*(['"])([^'"]+)\1\s*\)/g,
  ];
  for (const re of patterns) for (const m of code.matchAll(re)) specs.push(m[2]);
  return specs;
}

const modules = listModules();

test('three and the two addons resolve to the vendored files', async () => {
  const THREE = await import('three');
  assert.equal(typeof THREE.REVISION, 'string');
  assert.equal(typeof (await import('three/addons/Sky.js')).Sky, 'function');
  assert.equal(typeof (await import('three/addons/BufferGeometryUtils.js')).mergeGeometries, 'function');
});

test('there are game modules to check', () => {
  assert.ok(modules.length > 0, `no modules under ${jsDir}`);
});

for (const mod of modules) {
  const file = join(jsDir, mod);
  test(`imports cleanly in Node: ${mod}`, { timeout: 20000 }, async () => {
    let ns;
    try {
      ns = await import(pathToFileURL(file).href);
    } catch (err) {
      // ESM syntax errors arrive without a location; `node --check` prints file:line and a caret.
      if (err instanceof SyntaxError) {
        const check = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
        const where = (check.stderr || '').split('\n').slice(0, 4).join('\n').trim();
        if (where) err.message += `\n${where}`;
      }
      throw err;
    }
    for (const name of CONTRACT[mod] || []) {
      assert.ok(name in ns, `${mod} must export ${name}`);
      assert.notEqual(ns[name], undefined, `${mod} export ${name} is undefined`);
    }
  });

  test(`import rules: ${mod}`, () => {
    const specs = importSpecifiers(readFileSync(file, 'utf8'));
    for (const spec of specs) {
      if (spec.startsWith('three/')) {
        assert.ok(ADDONS.has(spec), `${mod} imports '${spec}'; only ${[...ADDONS].join(' and ')} are vendored`);
      }
      if (spec.startsWith('.')) assert.ok(spec.endsWith('.js'), `${mod} imports '${spec}' without the .js extension`);
      if (!PURE.has(mod)) continue;
      assert.ok(spec.startsWith('./') || spec.startsWith('../'), `PURE ${mod} imports '${spec}'; PURE modules import only relative PURE modules`);
      const target = relative(jsDir, resolve(dirname(file), spec)).split(sep).join('/');
      assert.ok(PURE.has(target), `PURE ${mod} imports ${target}, which is not PURE`);
    }
    if (PURE.has(mod) || mod.startsWith('game/')) {
      const code = stripComments(readFileSync(file, 'utf8'));
      assert.ok(!/\bset(Timeout|Interval)\s*\(/.test(code), `${mod} uses setTimeout/setInterval; game logic counts down with gdt/pdt`);
    }
  });
}

test('contract modules not written yet', (t) => {
  const missing = Object.keys(CONTRACT).filter((m) => !existsSync(join(jsDir, m)));
  if (missing.length) t.diagnostic(`missing: ${missing.join(', ')}`);
  if (missing.length) t.skip(`${missing.length} contract module(s) not present yet`);
});
