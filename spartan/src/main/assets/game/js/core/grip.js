/**
 * How the phone is held in motion mode (docs-hilt-grip.md §A.2). PURE: no DOM, no imports.
 *
 * 'window': landscape, the screen is a window and the blade sticks out of the back (DESIGN.md §3.3).
 * 'hilt':   portrait, the phone held upright in the fist like a sword's handle, the thumb on the
 *           glass to block, the blade out of the top edge.
 * Every module that branches on the grip (main, menus, hud, input, training) goes through these
 * helpers; none compares `settings.grip` by hand. A missing or unknown grip reads as 'window', so
 * every context that never heard of grips (the UI sandbox, the gameplay harness, old stored
 * settings) keeps the window grip. New players get the hilt grip through DEFAULT_GRIP instead.
 */

/** The two grips, in the order the Settings segment shows them. */
export const GRIP_IDS = Object.freeze(['hilt', 'window']);

/**
 * The release gate: false until the hilt grip is the default for new motion players and the grip
 * chooser is offered (menus test `HILT_RELEASED && settings.gripChosen === false`). Package P10
 * flips it after the full sweep; until then every build keeps today's default.
 */
export const HILT_RELEASED = false;

/** DEFAULT_SETTINGS.grip in main.js: 'hilt' once released, else 'window'. */
export const DEFAULT_GRIP = HILT_RELEASED ? 'hilt' : 'window';

// flipKeys' two answers, frozen and shared so pumpMotion's per-frame call never allocates.
const FLIP_WINDOW = Object.freeze({ x: 'motionFlipX', y: 'motionFlipY' });
const FLIP_HILT = Object.freeze({ x: 'motionFlipXHilt', y: 'motionFlipYHilt' });

/**
 * The grip in effect: `settings.grip` when the control is motion ('hilt', or 'window' for a
 * missing or unknown grip), else null (touch and keyboard).
 * @param {object} settings
 * @returns {'hilt'|'window'|null}
 */
export function effectiveGrip(settings) {
  if (!settings || settings.control !== 'motion') return null;
  return settings.grip === 'hilt' ? 'hilt' : 'window';
}

/**
 * The orientation the whole app runs in (title, menus and the fight alike): portrait iff the
 * effective grip is the hilt.
 * @param {object} settings
 * @returns {'portrait'|'landscape'}
 */
export function appOrientation(settings) {
  return effectiveGrip(settings) === 'hilt' ? 'portrait' : 'landscape';
}

/**
 * The settings key that holds the effective grip's sensitivity. Every reader and writer of the
 * sensitivity uses it (pumpMotion, the Settings slider, the sensor meter, the Agoge).
 * @param {object} settings
 * @returns {'sensitivityHilt'|'sensitivity'}
 */
export function sensitivityKey(settings) {
  return effectiveGrip(settings) === 'hilt' ? 'sensitivityHilt' : 'sensitivity';
}

/**
 * The settings keys of the effective grip's mirrored-setup flips: a sensor defect maps to
 * different body axes in portrait and landscape, so each grip keeps its own pair. Returns one of
 * two frozen constants (no allocation).
 * @param {object} settings
 * @returns {{x: string, y: string}} hilt: motionFlipXHilt/YHilt, else motionFlipX/Y
 */
export function flipKeys(settings) {
  return effectiveGrip(settings) === 'hilt' ? FLIP_HILT : FLIP_WINDOW;
}

/**
 * Boot migration of the stored settings, before they are merged over DEFAULT_SETTINGS: a motion
 * player from before the grips existed (control 'motion', no grip key) keeps the window grip they
 * know. Anything else is left alone, so a fresh install has no grip key and gets DEFAULT_GRIP.
 * @param {object} stored the parsed `thermopylae.settings` (mutated)
 * @returns {object} its argument
 */
export function migrateSettings(stored) {
  if (stored && typeof stored === 'object' && stored.control === 'motion' && !('grip' in stored)) {
    stored.grip = 'window';
  }
  return stored;
}
