# Thermopylae: the hilt grip (portrait), lock-on and thumb controls

Implementation contract for the motion-control redesign the player chose after the real-phone
feedback ("throwing the spear isn't intuitive, neither is turning", "I want to cock back my hand
to throw", "press my thumb while I hold the phone to block", "when you swing you can't look at the
screen"). It is written against DESIGN.md and uses its conventions (§3 axes and yaw, §3.2 time,
§4 ctx). "MUST" items are not optional. When the work lands, package P10 folds this file into
DESIGN.md (the list is in §I.4) and deletes it.

Paths below are relative to `spartan/src/main/assets/game/` unless they start with `spartan/`.

## 0. Decisions at a glance

| Question | Decision |
|---|---|
| Default motion grip | **Hilt** (portrait) once released. **Window** (landscape, today's behaviour) stays in Settings. Touch is always landscape. The default is staged: `HILT_RELEASED` in `core/grip.js` is `false` until P10 flips it, so every intermediate build that CI publishes keeps today's default (A.2, I.2). |
| Who owns the orientation | `appOrientation(settings)`: portrait iff `control === 'motion' && grip === 'hilt'`. Title, menus and the fight all follow it. |
| Native API | `lockOrientation(lock: Boolean, orientation: String?)`, **arity 2**. The old 1-argument method is removed. |
| Manifest | `screenOrientation="sensorLandscape"` stays as the cold-start default. MainActivity applies the persisted orientation in `onCreate`, before the WebView exists. |
| Hilt recognizer | The same `GestureRecognizer` with a **grip profile** (`GRIPS.hilt`): blade = device +Y, elevation and the horizontal axis change, look is off, an upward swing from the guard is never a slash. It is not a second recognizer. |
| Window behaviour | Bit-for-bit unchanged. Every pure module defaults to `'window'`, and a missing or unknown `grip` means `'window'`. The existing tests run unmodified, except `ui-helpers.test.mjs`'s Settings FIELDS assertions, which gain the two new rows (P8, H.1). |
| Safety | The hilt grip holds the phone by friction, and swing loads pull it out of the top of the fist. So the hilt rewards a **firm flick, never a hard throw**: full throw power at 7.5 rad/s and full slash power at 8.3 rad/s, an over-speed warning above 14 rad/s, and a safety screen that recommends a wrist strap or a ring grip and says to clear the space behind you (A.6, B.3). |
| Turning | No gyro look in the hilt grip. A **lock-on camera** in player.js faces the locked foe through a rate-limited, critically damped yaw spring. |
| Target switching | A thumb swipe left or right moves the lock to the foe that way, ranked by angle plus distance (the adjacent attacker beats a far one a few degrees off). The shield stays up while the thumb is down. |
| Thumb layout | The whole glass is the shield, except reject strips shaped for a real fist (the fingertips curl onto the edge opposite the thumb). The weapon button and READY sit at the top for the off hand, out of the thumb's arc; an upward thumb flick also swaps the weapon. |
| Blind swings | Swings aim at the locked foe. A small vocabulary of strong haptics (≥ 30 ms pulses) tells hit, guarded, kill and hurt apart. A "last strike" chip on the lock marker stays until the phone is back at guard. Big hits get a brief slow-motion. |
| Spear | Cock the phone back **behind the shoulder** (β′ ≥ 2.35, about 1.25 rad past the guard, i.e. the top pointing back over the shoulder), then snap it forward. An "armed" haptic confirms the cock-back. A smaller raise is never a wind-up, so a raised chop with the spear in hand is a chop, not a throw. The spear flies at the locked foe, and the spear cam plays as today. |
| Rear threats (Day 3 phase C) | With `lockAuto` on, the lock turns to an attacker the shield can't cover (beyond 75°), at most once per 3 s and never straight back. Such a wind-up gets +0.35 s for a hilt player, so the turn leaves time to block. Volleys never turn the view: in the hilt grip a raised shield takes volley arrows from either side. |
| Portrait FOV | Vertical = clamp(vfov for a 46° horizontal FOV, 72°, 84°). That is 84° × 44° on a 412×915 phone. Landscape is unchanged. |

## 1. Terms

* **Grip**: how the phone is held in motion mode. `'window'`: landscape, the screen is a window and
  the blade sticks out of the back (DESIGN §3.3). `'hilt'`: portrait, the phone is a sword handle
  held upright in one fist, screen toward the player, the thumb resting on the side edge and
  pressing the glass to block, and the blade sticks out of the top edge.
* **Effective grip** `effectiveGrip(settings)`: `settings.grip` when `settings.control === 'motion'`
  (a missing or unknown grip reads as `'window'`), else `null` (touch and keyboard).
* **Hand model (hilt, right hand; mirrored for the left)**: the palm heel and the thumb's base sit
  on the phone's right edge, the fingers wrap the back, and the **fingertips curl onto the left
  edge** of the glass over the lower half. The thumb crosses the glass from the right edge; its pad
  rests over roughly x 40–90%, y 40–75% of the screen.
* **Lock** (hilt only): the foe the camera faces and the strikes aim at, `player.lockTarget`.
* **Guard (hilt)**: the phone upright in front of the right chest, the top leaning forward
  20–45°, the screen visible.

---

## A. Orientation and the grip setting

### A.1 Settings (DESIGN §9 additions)

```js
grip: DEFAULT_GRIP,      // 'hilt' | 'window'. Motion only; touch ignores it. DEFAULT_GRIP from core/grip.js (A.2)
gripChosen: false,       // the grip chooser has been answered (A.6)
sensitivityHilt: 1.0,    // the hilt grip's own sensitivity (0.5–2.2); `sensitivity` stays the window grip's
motionFlipXHilt: false,  // the hilt grip's own mirrored-setup flips; motionFlipX/Y stay the window grip's.
motionFlipYHilt: false,  //   A sensor defect maps to different body axes in portrait and landscape (A.2 flipKeys)
lockAuto: true,          // hilt: turn to an attacker the shield can't cover (C.6)
hiltFingerStrip: 44,     // px (44–120): the finger-side reject strip (D), widened by the Agoge grip check (G)
hiltAgogeDone: false,    // the hilt Agoge has been finished once (A.6 first Fight)
hiltSafetyAccepted: false, // the hilt safety screen has been accepted (A.6)
```
Only `grip` and `lockAuto` get Settings rows. The other keys are edited through existing rows
(Sensitivity, the two Swap rows) or set by the game.

**Migration** (main.js boot, before `bindSettings`): if the stored settings have
`control === 'motion'` and no `grip` key, set `grip = 'window'`. An existing motion player keeps
what they know, and the chooser (A.6) offers the hilt grip once.

**Settings UI, Controls tab:**
* A new first motion row, **Grip**, a segmented control: *Hilt (upright)* / *Window (sideways)*.
  It is `motionOnly`. It is shown in every build (so the hilt grip can be tried before release).
* **Sensitivity** edits `settings[sensitivityKey(settings)]`.
* **Swap swing left/right** and **Swap swing up/down** edit `settings[flipKeys(settings).x]` and
  `settings[flipKeys(settings).y]`.
* **Gyro look** is greyed in the hilt grip, with the hint "Hilt grip: the view locks onto your foe".
* A new **Auto-turn** row (`lockAuto`, hint "Hilt grip: turn to a foe attacking from behind"). It is
  `hiltOnly` (a new FIELDS flag: greyed unless the effective grip is hilt), not `motionOnly`.

### A.2 PURE helpers: `js/core/grip.js` (new)

```js
export const GRIP_IDS = Object.freeze(['hilt', 'window']);
export const HILT_RELEASED = false;                                // P10 flips it to true (I.2)
export const DEFAULT_GRIP = HILT_RELEASED ? 'hilt' : 'window';     // DEFAULT_SETTINGS.grip
export function effectiveGrip(settings) → 'hilt'|'window'|null   // null unless control === 'motion'; a missing or unknown grip → 'window'
export function appOrientation(settings) → 'portrait'|'landscape' // 'portrait' iff effectiveGrip(settings) === 'hilt'
export function sensitivityKey(settings) → 'sensitivityHilt'|'sensitivity'
export function flipKeys(settings) → { x, y }                      // frozen constants: hilt → motionFlipXHilt/YHilt, else motionFlipX/Y
export function migrateSettings(stored) → stored                   // A.1 migration; returns its argument (mutated)
```
Everything that branches on the grip (main, menus, hud, input, training) uses these. No module
compares `settings.grip` by hand.
* An unknown grip reads as `'window'` so that every context that never heard of grips (the UI
  sandbox, the gameplay harness, old stored settings) keeps today's behaviour. New players get the
  hilt grip through `DEFAULT_SETTINGS.grip`, not through this fallback.
* Tests and menus check the chooser trigger with `settings.gripChosen === false`, never a falsy test.
* `flipKeys` returns one of two frozen objects (no allocation per frame).

### A.3 What runs in which orientation

| control | grip | title and menus | battle and training | Android request |
|---|---|---|---|---|
| touch | any | landscape | landscape (not locked, as today) | `lockOrientation(false, 'landscape')` |
| motion | window | landscape | landscape, locked to the current side | `(false\|true, 'landscape')` |
| motion | hilt | **portrait** | **portrait** (portrait-primary, always locked) | `(false\|true, 'portrait')` |

The fight never rotates on its own. Only a settings change (in the title or the pause menu)
rotates the app.

### A.4 Native host (P1)

**NativeBridge.kt** replaces `lockOrientation(lock: Boolean)` with:
```kotlin
/** Requests the app orientation. orientation: "portrait" | "landscape" (anything else = landscape). */
@JavascriptInterface
fun lockOrientation(lock: Boolean, orientation: String?)   // exactly 2 parameters
```
* The body (UI thread) sets `activity.requestedOrientation = HostLogic.requestedOrientation(lock, orientation, rotationDeg)`.
* It then persists the family with
  `activity.getSharedPreferences("thermopylae.host", MODE_PRIVATE).edit().putString("orientation", o).apply()`,
  where `o ∈ {"portrait", "landscape"}`.
* As with every bridge method, the body is wrapped in try/catch.
* `onNewPage()` re-requests `(false, lastOrientation)` and no longer forces landscape.

**HostLogic.kt** (pure, no Android types except the int constants):
```kotlin
fun requestedOrientation(lock: Boolean, orientation: String?, displayLandscape: Boolean): Int =
  when {
    orientation == "portrait" -> 1                    // SCREEN_ORIENTATION_PORTRAIT (portrait-primary, never reverse)
    !lock -> 6                                        // SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    displayLandscape -> 14                            // SCREEN_ORIENTATION_LOCKED: keep the current landscape side
    else -> 0                                         // SCREEN_ORIENTATION_LANDSCAPE: still portrait (the grip was just changed)
  }
```
* `displayLandscape` is `activity.resources.configuration.orientation == ORIENTATION_LANDSCAPE`,
  read on the UI thread in the bridge body.
* Locked landscape keeps today's `SCREEN_ORIENTATION_LOCKED` whenever the display already is
  landscape. That is correct on natural-landscape tablets too, where rotation 0 is landscape and
  180 is reverse landscape, so the side is never chosen from the rotation number.
* Only when the display is still portrait as a window-grip fight starts (LOCKED would lock
  portrait) does it fall back to `SCREEN_ORIENTATION_LANDSCAPE`.

**MainActivity.kt**, in `onCreate`, before `setContentView`: `requestedOrientation =
HostLogic.requestedOrientation(false, prefs.getString("orientation", "landscape"), false)`. A hilt player's
next launch then starts in portrait, boot screen included.

**AndroidManifest.xml**: the attribute stays `android:screenOrientation="sensorLandscape"`, which
is the cold-start default for a first install (touch, landscape). Android draws the starting
window in the manifest orientation, so a hilt player sees one black-window rotation at launch.
That is acceptable, and preferable to flashing every touch player. `configChanges` already holds
`orientation|screenSize|smallestScreenSize|screenLayout`, so a rotation never recreates the
Activity or reloads the WebView. Update the manifest comment to say this. Android re-sends the
cutout insets after each rotation through the existing `OnApplyWindowInsetsListener`, and
`onConfigurationChanged` already refreshes `rotationDeg`.

### A.5 Web side (P1, `platform.js`)

```js
lockOrientation(lock, orientation = 'landscape')
```
* **Android**: `nat('lockOrientation', !!lock, orientation === 'portrait' ? 'portrait' : 'landscape')`,
  always 2 arguments.
* **Web**:
  * portrait: `screen.orientation.lock('portrait-primary')` whether locked or not. The game wants portrait in its menus too.
  * landscape with `lock`: `lock('landscape')`.
  * landscape without `lock`: `unlock()`.
  * Every promise is caught. A web lock only succeeds once the document is fullscreen, and
    `requestFullscreen` resolves later. So on a hilt Fight or Agoge click, menus.js (in the click
    handler, hilt grip only) calls
    `document.documentElement.requestFullscreen?.({ navigationUI: 'hide' })?.then(() => ctx.applyControls(), () => {})`
    and then starts the mode at once. The lock lands when fullscreen does; the rotate prompt
    covers a refusal.
* `lockOrientation(true)` and `lockOrientation(false)` keep today's meaning, so existing callers
  and tests are unaffected.

**Rotate prompt** (menus.js, both platforms; Android 16 ignores orientation requests on large
screens):
* When `appOrientation === 'portrait'` and `innerWidth > innerHeight` for more than 600 ms, in
  title, battle, training or paused, show a full-screen `#rotate` panel.
* It says "Hold the phone upright for the hilt grip" and has one button, **Use the window grip**,
  which sets `grip = 'window'` and calls `ctx.applyControls()`.
* In battle or training the prompt also pauses (`setMode('paused')`).
* Recognition keeps working while it is up (B.2 still remaps by the display rotation); the
  prompt is only about the layout.

### A.6 First run, and switching (P2 main.js, P8 menus.js)

**main.js** gains:
```js
ctx.applyControls()  // after any change to control or grip, and on every mode change:
                     //  - motionFrames.setGrip(g), gestures.setGrip(g), with g = effectiveGrip || 'window' (each a no-op if unchanged)
                     //  - ctx.input.grip = effectiveGrip(settings)   (not gated on active: the viewmodel keeps
                     //    its hilt pose under the pause menu; player.update doesn't run while paused anyway)
                     //  - gestures.setFlip from settings[flipKeys(settings).x/y] (pumpMotion does the same each frame)
                     //  - <html data-grip="hilt|window|none"> (the effective grip, in every mode)
                     //  - platform.lockOrientation(active && control === 'motion', appOrientation(settings))
```
* `setMode` calls `ctx.applyControls()` itself in place of today's
  `platform.lockOrientation(active && motionWanted())`, so the recognizer, `ctx.input.grip` and the
  orientation can never disagree, even after a direct `settings.control` change (tests, menus'
  probe fallback).
* pumpMotion passes `settings[sensitivityKey(settings)]` to `setSensitivity` and
  `settings[flipKeys(settings).x/y]` to `setFlip` (in place of `sensitivity` and `motionFlipX/Y`).
* Boot calls `applyControls()` once after loading settings. A `resize` sets
  `quietUntil = now + 2` (dynamic resolution, §15), so the rotation's re-layout frames don't lower
  the resolution.
* URL parameter `grip=hilt|window` overrides the setting and sets `gripChosen = true` (tests).

**Choosing Motion for the first time** (`settings.gripChosen === false` and `HILT_RELEASED`; before
the release the chooser is skipped and the grip stays as set), in menus.js:
1. A new **grip** screen: "How will you hold your sword?" with two large cards and a diagram each:
   * **Hilt grip**, marked *Recommended*: "Hold the phone upright in your fist like a sword
     handle, screen toward you. Press the glass with your thumb to raise the shield. The view
     turns to face your foe; swipe to change foe."
   * **Window grip**: "Hold the phone sideways and look through it. Turn your body to look around."

   A choice sets `grip` and `gripChosen = true`. Back cancels the Motion choice: control stays touch.
2. The safety notice, with lines per grip. It shows if `!safetyAccepted`, and for the hilt grip also
   if `!hiltSafetyAccepted`. The same rule applies wherever the hilt grip first becomes effective
   (the chooser, the Grip segment in Settings, a Fight or Agoge click), so no one reaches a hilt
   fight without having read it. Hilt:
   * "Hold it firmly in your fist. A wrist strap, or a ring grip on the back of the phone, is
     strongly recommended."
   * "Clear the space around you, behind you too: the spear's cock-back reaches behind your head."
   * "A firm flick is enough. Swinging harder does nothing more."

   Window: "Hold it firmly in your sword hand." and the existing lines.
3. `enableMotion()` as today: permission, then the support probe, then the touch fallback.
4. `applyControls()`. A hilt choice rotates the app to portrait now, and the toast reads "Hilt
   grip on. The Agoge teaches it in a few minutes." A window choice gets `TEXT.motionOn`.

**Existing motion players** (migrated to window, `gripChosen === false`, once released): the first
title render pushes the grip screen once, with the kicker "New". Either answer sets `gripChosen`.

**First hilt Fight without the Agoge** (`!hiltAgogeDone`, the Agoge finishing in the hilt grip sets
it): Fight first shows a two-button notice, "The Agoge first (3 min)" / "Fight now". "Fight now" is
remembered for the session only. In that player's first hilt battle, hud shows one-time coach
toasts (P8): "Thumb on the glass: shield" at the first wind-up at the player; "Swipe: change foe"
at the first lock with `lockSides` set; "Cock back behind your shoulder, then snap forward" when
the spear is first in hand. (The over-speed toast of B.3 is not a coach mark: it shows in every
hilt battle, at most once per 60 s.)

**Switching later**: the Grip segment, or Controls Motion/Touch, in Settings from the title or the
pause menu. Each change saves and calls `ctx.applyControls()` at once, so the app rotates while
the menu is open and the menus re-lay out through the CSS media queries.
* The recognizer state resets (`setGrip`).
* The lock-on starts or stops on the next player update (C.9).
* Resume works as today: `setMode(..., {resume:true})` re-polls and resets the motion pipeline.

**Mid-fight**: settings are only reachable through pause, so the grip never changes during play.

---

## B. Motion recognition for the hilt grip (P3)

### B.1 Axes

The hilt grip's frame is the **screen frame of the portrait display** (DESIGN §3.3), which is the
device frame on every phone (rot 0):
* `+x`: the screen's right edge as the player sees it.
* `+y`: the top edge. **The blade is `b = +y`.**
* `+z`: the screen normal, toward the face.

At guard the back of the phone (−z) faces the foe, so a **thrust is a push along −z** (a punch),
exactly as in the window grip.

**Elevation.** Let `u = sample.up`, R the body-right axis (B.3), and `F = u × R` (body forward,
horizontal, unit). The blade elevation is the angle of +y in the vertical plane through F,
unwrapped past vertical:
```
βabs = atan2(u.y, F.y)        F.y = u.z·R.x − u.x·R.z
```
* Upright = π/2.
* Pointing at the foe (phone flat, screen up) = 0.
* Pointing down = −π/2.
* Tipped back over the shoulder (screen facing down and back) > π/2, and π pointing straight back.

Roll about the blade (the screen facing partly sideways) does not change it.

**Horizontal sweep axis.** The tip of an upright blade moves sideways when the blade *rolls*
about F. A blade pointing at the foe moves sideways when it *yaws* about u. In general a rotation
about `U_g = R × b_g` moves the tip across the view, where `b_g` is the guard blade:
```
A = max(cos βc, yawFloor)·u − sin(βc)·F      wh = w·A      wv = w·R      (vx, vy) = (−wh, wv)
yawFloor = GRIPS.hilt.yawFloor = 0.7          (A is deliberately not normalized)
```
βc is the hilt βabs of the sample on which R was last captured (B.3). It is kept as a scalar,
since u and F are tracked continuously. So a strike keeps the axis of the guard it started from,
as R does. For βc = 0 this is exactly the window grip's `wh = w·u`.

**Why the yaw floor.** The most natural horizontal cut with an upright blade is a forearm sweep:
the phone yaws about the vertical while the hand travels sideways. The gyro sees only the yaw, and
`cos βc` would count it at 0.20–0.43 of its rate (lean 0.2–0.45), below the slash threshold for
most players. The real tip of a blade on a swept forearm moves sideways at about
`ω·(cos βc + r/L)` (forearm r ≈ 0.3 m, blade L ≈ 0.7 m), i.e. ≥ 0.7·ω, so the floor matches what the
player sees. A cut's yaw and roll have the same sign (both move the tip the same way), so they
add. A is not normalized: the roll gain stays `sin βc`, so cautious roll cuts keep today's
detection, and a mixed swing reads at most 1.22× its rate. In the hilt grip gyro look is off, and
body turns below 2 rad/s never slash (the `lookMax` floor), so the floor costs nothing there.

### B.2 `motion/frames.js`

```js
new MotionFrames({ tiltTau = 0.5, platformKind = 'web', grip = 'window' })
setGrip(grip)          // 'window'|'hilt' (anything else → 'window'); if it changed: reset(). Device calibration is kept.
get grip()
export const FRAME_GRIPS = Object.freeze({
  window: Object.freeze({ latch: [0, 0, 1],      restUp: [0, 0.707, 0.707], rotFix: true }),
  hilt:   Object.freeze({ latch: [0, -0.6, 0.8], restUp: [0, 0.89, 0.45],   rotFix: false }),
});
```
In the hilt grip the remap, up estimation, `a`, `calm`, `gap` and the axis self-check are
unchanged. The display rotation is still applied: portrait-primary is rot 0 on phones, and
natural-landscape tablets report 90/270 and are remapped correctly. Only the three web
heuristics of DESIGN §5.2 change:
* **(a) sign latch**: average `acc·latch` (or `accG·latch`) over samples with |w| > 7, using the
  grip's `latch` instead of +z. In a hilt swing the centripetal acceleration points toward the
  elbow and the body (−y, +z). The thresholds are unchanged: ≥ 12 samples, a mean beyond ±4.
  (The same fact is the safety problem of the grip: the inertial load pulls the phone out of the
  top of the fist, which only grip friction resists. See A.6 and the B.3 throw and power values.)
* **(b) provisional −1**: calm for 1 s with `up·restUp < −0.5`, where `restUp` is the grip's guard up.
* **(c) rotFix**: disabled in the hilt grip. The stored `rotFix` is kept for the window grip.
  In the hilt grip a calm, blade-down pose is a rest, not a reversed screen angle.

### B.3 `motion/gestures.js`

```js
export const GRIPS = Object.freeze({
  window: Object.freeze({
    blade: 'back', sweep: 'gravity', look: true, heldUpCuts: true, upHoldSector: 35 * DEG,
    guardSnap: [-1.45, 0.9], guardShift: [-Infinity, Infinity], settle: 'window', params: {},
  }),
  hilt: Object.freeze({
    blade: 'top', sweep: 'guardBlade', look: false, heldUpCuts: false, upHoldSector: 60 * DEG,
    yawFloor: 0.7, overSpeed: 14,
    guardSnap: [0.30, 1.85], guardShift: [-0.35, 0.25], settle: 'hilt',
    guardGrace: 1.0, guardHold: 0.5, guardResnap: 2.0,
    params: Object.freeze({
      guardBeta: 1.10, upHoldStartBeta: 0.60, windupBeta: 2.35, dipSector: 0,
      throwPeak: 4.5, throwFull: 7.5, throwMin: 3.5, slashPowerSpan: 5.5,
    }),
  }),
});
new GestureRecognizer({ sensitivity = 1, weapon = 'sword', gyroRange = 34.9, params = null, grip = 'window' })
setGrip(grip)   // anything else → 'window'. If it changed:
                //   p = { ...GESTURE_DEFAULTS, ...GRIPS[grip].params, ...ctorParams }, sensitivity re-applied,
                //   reset(), the learned guard forgotten (_guardInit = false), _L = _Lc (no look residue)
get grip()
// debug gains: grip
```
`GESTURE_DEFAULTS` stays exactly as it is (frozen, same keys and values). The structural switches
live in `GRIPS` (the window profile has `yawFloor: 0` and no over-speed check, and its guard
learning is today's).

**Hilt thresholds, and why** (all relative to the learned guard, B.3.3):
* `windupBeta 2.35`: the wind-up is the javelin cock-back, the top of the phone pointing back over
  the shoulder, about 1.25 rad past the guard. A smaller raise is not a wind-up, so with the spear
  in hand a chop that starts with an ordinary lift stays a chop and never throws. The sword's
  `windup` event has no gameplay effect (pumpMotion only plays the viewmodel's spear wind-up), so one
  threshold serves both weapons.
* `throwPeak 4.5`, `throwFull 7.5`, `throwMin 3.5`: a firm flick already gets full power (spear
  speed, two-man skewer). Nothing is gained above 7.5 rad/s.
* `slashPowerSpan 5.5`: slash power is full at 8.3 rad/s at 1×.
* **Over-speed** (hilt): a slash or throw whose peak is ≥ `overSpeed` (14 rad/s), or reaches
  0.95·gyroRange (clipping), carries `fast: true` on its gesture event. It gets no extra power (it is
  already capped). hud shows the toast "Easy: a firm flick is enough" at most once per 60 s, and the
  Agoge shows it as a hint.

**In `push()`, the hilt grip differs as follows. The window grip runs today's code paths unchanged.**
1. **Order.** `_stepRight` runs before the elevation is computed, because the hilt βabs needs R.
   This doesn't change the window grip, whose β doesn't read R. R's capture rule
   (`_captureRight`) is unchanged: the horizontal part of −z, negated past vertical, blended with
   +y when flat. Evaluated in the hilt frame it gives body-forward across the whole hilt range,
   from the guard through flat, cocked back and blade down. When R is captured on a sample, store
   `_axisBeta = βabs` from that sample.
2. **βabs**: B.1 for `blade: 'top'`, today's formula for `'back'`.
3. **Learned guard** (hilt). The pose the player held while tapping Fight is not their fist guard,
   so the hilt grip learns G only from a held guard and keeps correcting it:
   * **Grace**: no snap during the first `guardGrace` (1.0 s) of samples after `reset()` or
     `setGrip()`. Until G is learned, G = guardBeta (shift 0).
   * **Snap**: G is set by a settle (`settle: 'hilt'` attitude, still) held ≥ `guardHold` (0.5 s)
     with βabs inside `guardSnap`.
   * **Follow**: later settles within 0.6 of G move it as today (`guardTau`).
   * **Re-snap**: if hilt settles with βabs inside `guardSnap` but > 0.6 from G add up to more than
     `guardResnap` (2.0 s, counted across settles, reset by any settle near G), G snaps to the
     latest such βabs. A wrong first guard therefore heals within about 2.5 s at the real guard.
   * The shift is `clamp(G − guardBeta, guardShift[0], guardShift[1])` and `β = βabs − shift`.
   * A forward-leaning guard (G = 0.77) cocks back at βabs ≥ 2.02, an upright one (G = 1.37) at
     ≥ 2.60, and the reference guard (1.10) at 2.35. All of them mean "the top points back over the
     shoulder".
   * With an upright guard at the clamp (G ≥ 1.35) the cock-back is still ≥ 0.98 rad past it
     (G = 1.62: 2.60, 0.98 above).
   * The raise is armed (`raiseArmed`) below `windupBeta − 0.3` = β′ 2.05, i.e. βabs ≤ 1.70 even
     with the most negative shift, so every guard in the snap range arms.
   * Window: unchanged (first settle in its range, then follow).
4. **Rates**: `wh = w·A` (B.1) for `sweep: 'guardBlade'`, `w·u` for `'gravity'`. `setFlip`,
   `m`, sweep, rawDir, ramp, cut rules, recoil, candidates and power are unchanged. They all read
   vx and vy.
5. **Upward swings.** "Held up" means `β0 ≥ upHoldStartBeta` and rawDir within `upHoldSector` of
   straight up. With `heldUpCuts: false` a held-up swing **never slashes**: tipping an upright
   blade back is a cock-back, never a cut.
   * If it reaches `windupBeta` with sweep ≥ 0.15 and the raise is armed, it emits `windup` and
     goes DONE (today's branch).
   * Otherwise it runs until it ends and emits nothing. It never enters the pending-uppercut state.
     Its calibration candidate (if any) carries `heldUp: true`, so the Agoge can hint "Lower the
     blade first, then swing up" (G).

   The wind-up memory rule (≥ windupTime above windupBeta emits once per raise) still applies.
   A **U, UR or UL** slash in the hilt grip starts from a low guard (β0 < 0.60, the blade pointing
   ahead or down) and fires like any cut. An uppercut that carries on past vertical is still U
   (`_cutRaise`).
6. **Dip rule off** (`dipSector: 0`). Every downward swing from the guard is judged by the
   ordinary cut rules. A cock-back of up to ~0.5 rad never fires (it stops accelerating at half
   its arc), and a chop of 0.6 rad or more fires early, so the hilt overhead chop is taught
   **straight from the guard, with no cock-back** (G). A deliberate uppercut is taught as "lower
   the blade, then swing up".
7. **Throw**: unchanged logic with the hilt thresholds above. A swing within ±throwSector of
   straight down after a remembered wind-up is a throw: the forward snap from the cock-back.
   `postThrowLock` is unchanged. Since a wind-up needs the deep cock-back, a lift of up to ~1 rad
   followed by a chop is a chop with either weapon.
8. **Thrust**: unchanged (`f = min(−a.z, 2·a·(u × R))`). At the hilt guard −z is the punch's direction.
9. **Look**: computed internally as today, fed with the grip's wh. The recoil rule's rest
   detection (`_armQuiet`) depends on it. With `look: false`, `consumeLook()` always returns
   `{ yaw: 0 }`; `holdLook`, `resumeLook` and `recenter` are callable and change nothing visible.
10. **settled**: `settle: 'hilt'` is `_settled && up.y ≥ 0.45 && up.z ≥ −0.25`, meaning still, no
    swing, the screen upright and not facing down. Window: unchanged. A held cock-back (screen
    facing down) and a follow-through (blade below horizontal) are not settled, so the spear cam's
    slow hold lasts until the phone is back at guard where the player can see it.

**Slash directions in the hilt grip** (body frame, as the player sees the imaginary blade's tip
move; the 8 tokens of DESIGN §3.4):

| token | motion from the guard (blade upright, screen toward you) |
|---|---|
| R / L | the tip sweeps right / left across the view: a roll of the fist about the forward axis, a forearm sweep (yaw), or any mix (they add, B.1) |
| D | chop: the tip falls forward and down from the guard (rotation about body-right, tip forward); no cock-back needed |
| DR / DL | diagonal chops from upper-left to lower-right, and from upper-right to lower-left |
| U, UR, UL | from a **low guard** (blade lowered toward the foe or the ground): the tip rises. From the upright guard an upward tip motion is the cock-back (wind-up), never a slash |
| thrust | punch the fist straight at the foe (along −z) |
| bash | thumb on the glass (shield) + thrust |
| wind-up | cock the phone back behind the shoulder, the top pointing back (β′ ≥ 2.35, ≈ 1.25 rad past the guard) |
| throw | spear in hand: from the wind-up, snap forward and down, fist closed (a downward swing ≥ the throw thresholds) |

**Sensitivity**: the same scaling (DESIGN §5.3). main.js passes
`settings[sensitivityKey(settings)]` to `setSensitivity` every frame. `slashThreshold(s)` keeps
its `lookMax` floor of 2.0 rad/s in both grips, so any repositioning slower than 2 rad/s (such as
lowering to a low guard) never slashes at any sensitivity. Every reader of the sensitivity uses
`sensitivityKey`: main.js pumpMotion, the menus slider, hud.js's sensor meter (hud.js:605), and
training.js's panel slider, calibration text and meter marks (training.js:232, 414, 578, 606, 627).

### B.4 `motion/synth.js`: the hilt grip model

```js
synthesize(script, opts)
// new opts: hold = 'window' | 'hilt' (default 'window'); lean = 0.45 (hilt: the blade's forward lean
// from vertical at guard, βg = π/2 − lean); rot defaults to 0 for hold 'hilt' (90 for 'window').
// tilt is ignored in the hilt grip. roll, aim, grip{pitch,roll,yaw}, hand, noise, gravitySign,
// includeLinear, includeGravity, gyroRange, jitter, seed, t0 mean what they mean today.
// hold:'window' output is identical to today's for every existing script and option.
```
**Hilt rig** (world frame §3.1, right hand; x is mirrored for the left hand):
* `EYE (0, 1.66, 0)`, `SHOULDER (0.19, 1.45, 0.03)`, guard hand (phone centre) `H0 (0.15, 1.24, −0.36)`,
  elbow `EL (0.21, 1.02, −0.10)`. The wrist pivot sits 0.05 m below H0 along −y.
* Guard attitude with F = (0, 0, −1) and U = (0, 1, 0): `y = cos βg·F + sin βg·U`,
  `z = −sin βg·F + cos βg·U`, `x = y × z`.
* Then `roll` about y (mirrored), then `aim` (a yaw about the vertical), then `grip` (the
  phone's attitude in the hand, q·G).

**Segments in the hilt grip.** `idle`, `walk` and `turn` are unchanged. `raise` changes meaning,
and `lower` is new and works in both grips.
* `swing { dir, speed = 9, arc = 1.4, windup = 0.3, recoil = 0.15, ret = 2.5, wrist = 0.45, pronate = 0.2, wristRoll, rollEnd, retYaw, retPitch, yawShare = 0 }`
  * Let R and b0 be the recognizer's body-right capture and the blade (+y) at the swing's
    starting pose. The axis is `n = normalize(sin(dir)·R − cos(dir)·(R × b0))`, so the recognizer
    sees (cos dir, sin dir).
  * `yawShare` blends the horizontal part toward a yaw about world up (a forearm sweep):
    `n' = normalize((1 − yawShare)·n + yawShare·(−cos dir)·U)`.
  * The arm turns `(1 − wrist)·θ` about n through EL. The wrist adds `wrist·θ` 30 ms later,
    orientation only.
  * Wind-up, pause, the min-jerk profile (`T = 1.875·arc/speed`, so `speed` is the true peak),
    recoil, return, pronation, wrist roll and sloppy returns work as in DESIGN §5.4.
* `thrust { distance = 0.30, duration = 0.18, hold = 0.08, retract = 0.3, dip = 0.15 }`: the
  phone translates along the body forward F (peak `5.77·D/T²`), and the tip tips forward by `dip`
  at full extension.
* `throw { speed = 7, cockArc = 1.5, shoulderArc = 1.4, windupSpeed = 5, hold = 0.12, arc = 3.0, twist = 0.45, lookBack = 0.7 }`
  * **Cock-back**: orientation +cockArc about R̂ (tip back), while the hand turns +shoulderArc
    about R̂ (position only) through SHOULDER, up and back beside the head. Then hold. With
    cockArc 1.5 the top reaches β′ ≈ 2.60 at every lean in the test matrix, 0.25 past windupBeta.
  * The default `speed` is the firm flick the hilt grip asks for (full power is 7.5). These are
    `hold: 'hilt'` defaults only; the window grip's `throw` and `raise` defaults (speed 13, …) are
    unchanged.
  * **Release**: the orientation turns −arc with the late-peaking profile `s(τ^1.4)`, scaled so
    the peak = speed. The hand turns back to −0.25 rad about the shoulder, the shoulder advances
    0.25 m, and the trunk yaws +twist.
  * Then follow 0.3 s, and return to guard over `lookBack`.
* `raise { cockArc = 1.5, shoulderArc = 1.4, windupSpeed = 5, hold = 0.2 }`: the cock-back alone, held.
  `raise { cockArc: 0.5–1.0 }` is the "ordinary lift before a chop" of the H.1 false-throw tests.
* `lower { beta = 0.1, duration = 1.4 }` (both grips): rotate about R̂ through the wrist so βabs
  (the grip's definition) reaches `beta`, min-jerk over `duration`. With the defaults it peaks
  below 1.5 rad/s.

The sensor output (finite differences, gravity, noise, clipping, inverse remap) is unchanged.

---

## C. Lock-on camera (hilt grip only; P4)

### C.1 Where it lives

* **`js/game/lockon.js` (new, PURE, no three.js)**: candidate scoring, acquisition, directional
  switching, and the yaw spring. It is unit-tested in Node.
* **`js/game/player.js`**: owns one lock state per player. It runs it when
  `ctx.input.grip === 'hilt'` and writes `player.yaw`. In the hilt grip it ignores
  `ctx.input.lookYaw` and zeroes it.

```js
// lockon.js
export const LOCK = Object.freeze({
  acquireRange: 14,     // m: auto-acquire
  switchRange: 30,      // m: a swipe may pick this far with the sword
  spearRange: 45,       // m: … and this far with the spear in hand (= THROW_RANGE)
  keepRange: 22,        // m: an auto-acquired lock beyond this for loseTime is dropped
  keepSlack: 3,         // m: a swiped or threat lock is kept to min(spearRange, max(keepRange, its distance at lock + keepSlack))
  loseTime: 1.0,        // s (pdt)
  acquireCone: 1.15,    // rad: auto-acquire only within this of the view yaw
  distWeight: 0.12,     // score per metre (acquire), and rank per metre beyond swipeNear (swipe)
  swipeNear: 3,         // m: swipe ranking ignores distance up to this
  sideGap: 0.4,         // m: a swipe skips a foe within this lateral offset of the current one (behind or in front of him)
  windupBonus: 0.6,     // rad of score: winding up at the player
  engagedBonus: 0.25,   // rad of score: engaged with the player (actor.targetIsPlayer)
  deathHold: 0.35,      // s: keep facing a foe that just died before acquiring the next
  recenterHold: 0.8,    // s: after a recenter, face the line before acquiring again
  threatAngle: 1.31,    // rad: a player-targeted wind-up from beyond this may take the lock (lockAuto)
  threatGap: 3.0,       // s: at most one threat switch per this
  threatReturn: 5.0,    // s: a threat switch never goes back to a foe that lost the lock to a threat this recently
  sideMin: 0.05,        // rad: a swipe needs a foe at least this far to that side
  deadZone: 0.035,      // rad: a resting camera starts moving only beyond this error
  settleErr: 0.004, settleVel: 0.02,   // it comes to rest inside these
  omega: 9,             // rad/s: spring natural frequency (critically damped)
  maxRate: 2.2,         // rad/s (≈ 2.1° per 60 Hz frame)
  maxAccel: 12,         // rad/s²
  fastRate: 3.0, fastAccel: 18,        // threat switches
  bearingTau: 0.12,     // s: low-pass of the target's bearing (animation sway)
});
export function isLockable(actor) → bool
  // alive, team 'persian', role ∉ {'helot'}, state ∉ {'retreat','dead'}; archers only through switching
export function inActors(actor, actors) → bool
  // the actor is still in ctx.actors (an index scan, no allocation). ai.clear() disposes actors
  // without marking them dead, so a stored lock must be checked against the live list every frame.
export function lockScore(actor, pos, viewYaw, P = LOCK) → number
  // |angleDiff(viewYaw, bearing)| + distWeight·dist − windupBonus·[state==='windup' && targetIsPlayer] − engagedBonus·[targetIsPlayer]
export function acquire(actors, pos, viewYaw, P = LOCK) → Actor|null
  // lockable, role !== 'archer', dist ≤ acquireRange, |rel| ≤ acquireCone: the lowest score
export function nextInDirection(actors, pos, fromYaw, current, dir /* +1 right, −1 left */, range, P = LOCK) → Actor|null
  // lockable (archers included), ≠ current, dist ≤ range (switchRange, or spearRange with the spear);
  // rel = angleDiff(fromYaw, bearing); right means rel < −min, left means rel > min, |rel| ≤ π, with
  //   min = sideMin + (current ? atan2(sideGap, dist(current)) : 0)   (a foe straight behind the current one is not "beside" him);
  // rank = |rel| + distWeight·max(0, dist − swipeNear); the lowest rank wins, ties go to the nearer.
  // No wrap: if nothing lies that way → null.
export class YawSpring {
  constructor(P = LOCK)
  reset(yaw)                          // vel = 0, at rest
  step(yaw, desired, dt, fast = false) → yaw   // see C.4
  get vel()  get moving()
}
```
Nothing in lockon.js allocates per call.

Ranking examples (player at the origin):
* Agoge lock step, from the centre dummy (0, −2.6): the right dummy (1.6, −2.2) ranks 0.63, the far
  dummy (1.5, −18) is skipped (|rel| 0.08 < min 0.20) → a right swipe picks the right dummy.
* Battle, from the engaged foe ahead at 1.6 m: a ±35° slot foe at 1.6 m ranks 0.61, a queued foe at
  8° and 20 m ranks 2.18 → the slot foe.
* An archer at 30 m is reached once no nearer foe lies that way, or straight away if he is the
  only one.

### C.2 Player API additions (DESIGN §11 game/player.js)

```js
player.lockTarget        // Actor|null (read-only)
player.lockMode          // 'off' | 'target' | 'front' | 'hold'
player.lockSides         // { left: Actor|null, right: Actor|null }: what a swipe would pick now (HUD arrows).
                         // One preallocated object, mutated in place; recomputed at most every 0.1 s.
player.switchTarget(dir) → boolean   // dir −1 left, +1 right; same as the action below
player.setLock(actor|null)           // Agoge and tests: force a lock (null → 'front')
```
**Action** (DESIGN §6): `{ type: 'switchTarget', dir: -1|1, src }`.
* It is never buffered, like `recenter`.
* It is ignored unless `ctx.input.grip === 'hilt'`, the player is alive and the camera isn't
  overridden.

**Event** (DESIGN §12): `lockOn { target: Actor|null, prev: Actor|null, reason }`.
* `reason ∈ 'acquire' | 'switch' | 'threat' | 'death' | 'lost' | 'clear' | 'none'`.
* It fires on every change of `lockTarget`. It also fires with reason `'none'` (target unchanged)
  when a swipe finds nothing that way.

### C.3 Selection and switching

* **Acquire** when there is no lock, or after `deathHold` / `recenterHold` has run out:
  `acquire(actors, pos, yaw)`.
  * It searches only around the current view, so a kill on the left never swings the view to a
    foe far on the right.
  * If it finds none, the mode is `'front'`: the view eases to the line's front, yaw 0.
  * In the Agoge the near dummy and the trainer are lockable (team persian). The far dummy at
    18 m is reached by a swipe.
* **Stable lock**: while the target stays valid there is **no automatic re-targeting** between
  nearby foes, so the view never wanders on its own. It moves only for a swipe, the threat rule
  (C.6), or loss of the target.
* **Swipe**: `nextInDirection(actors, pos, bearingOfCurrent ?? yaw, current, dir, range)`, with
  `range = spearRange` while the spear is in hand, else `switchRange`. On a hit it locks (reason
  `'switch'`). On a miss it emits `'none'`, which fx turns into a "nothing there" haptic, and the
  view does not move. Repeated swipes walk around the player through the rear, covering a full
  half-circle per side, so a phase C attacker behind the wall can be reached without auto-turn.
* **Keep range**: an auto-acquired lock is kept to `keepRange`. A lock chosen by a swipe or a
  threat is kept to `min(spearRange, max(keepRange, distance at lock + keepSlack))`, so an archer
  swiped to at 30 m stays locked through the cock-back and the throw.
* **Loss**:
  * Target dead → reason `'death'`. The view keeps facing its last bearing for `deathHold`, then acquires.
  * No longer lockable, **no longer in `ctx.actors`** (`inActors`, checked every frame: an
    `ai.clear()` in the Agoge disposes actors without killing them), or beyond its keep range for
    `loseTime` → `'lost'`, then acquire.
  * `phase`, `dayStart` and player death → `'clear'`. The mode goes to `'front'` (`'off'` when dead).
  * A `recenter` action (key C; the button is hidden in the hilt layout) → `'clear'`, front for
    `recenterHold`, then acquire. In the hilt grip `recenter()` only does this and calls
    `gestures.recenter()`; it does not start the 0.25 s `recenterLeft` yaw/pitch ease, so the spring
    stays the only yaw writer.

### C.4 The camera: smoothing and rate limits (the old "jumpy view" complaint)

Each player update (pdt: frozen in hit-stop and pause, full speed under combo slow-mo):
1. `desired` by mode:
   * `'target'`: the low-passed bearing to `lockTarget.pos` (the feet, not the animated chest;
     τ = bearingTau).
   * `'front'`: 0.
   * `'hold'`: the last desired.
2. `player.yaw = spring.step(player.yaw, desired, pdt, fast)`:
   ```
   e = angleDiff(yaw, desired)
   if (!moving) { if (|e| ≤ deadZone) return yaw; moving = true }       // hysteresis: no creep or chatter
   a = clamp(ω²·e − 2ω·vel, ±accel); vel = clamp(vel + a·dt, ±rate); yaw = wrap(yaw + vel·dt)
   if (|angleDiff(yaw, desired)| < settleErr && |vel| < settleVel) { moving = false; vel = 0 }
   ```
   `rate/accel = maxRate/maxAccel`, or `fastRate/fastAccel` for threat switches.
   `dt` is clamped to 0.05.
3. While `fx.cameraOverridden` (spear cam) the spring is frozen (vel = 0) and yaw isn't written,
   so the eye pose the spear cam eases back to holds still.

Guarantees:
* The view never jumps: no step exceeds `rate·dt`, so at 60 fps a step is ≤ 0.037 rad (or 0.05
  rad fast).
* Velocity is continuous: it changes by at most `accel·dt` per frame.
* A transition never reverses direction (critically damped).
* Nothing moves for a target swaying inside ±2°.
* With the numbers above at 60 Hz, a 35° switch comes within 2° of the new bearing in ≈ 0.60 s,
  and a 90° one in ≈ 1.0 s. Either comes to rest about 0.4 s later, with no overshoot.

**Pitch**: as motion mode today, a steady `MOTION_PITCH = −0.07`, eased at 4 rad/s. Behind a
raised aspis the player looks over its rim at the foe's head (E.3).

**Other yaw writers in the hilt grip**: the guard-break `yawImpulse` (0.1 rad) still applies, and
the spring brings the view back. The touch aim pull doesn't apply (motion). `recenterLeft` is
never started (C.3). `idleCamera` and the death camera are unchanged.

### C.5 Aiming at the lock

* **Focus**: `focusTarget = lockTarget` when it is alive, within `weaponReach() + FOCUS_EXTRA`
  and within `FOCUS_ANGLE` of the view. Otherwise it is today's `pickTarget` (a closer foe in
  front still gets hit while the lock is on a far one).
* **Attack yaw, lunge, single-target choice and combos**: unchanged mechanics on that focus.
  `attackYaw = yaw + clamp(angleTo(target), ±MOTION_YAW_CLAMP)`, and the view already faces the lock.
* **Throw**: the target is `lockTarget` when it is lockable, still in `ctx.actors`, not
  `noThrowLock` and within `THROW_RANGE` (45 m). Otherwise it is `findThrowTarget()` as today. Leading the target, the
  ballistic pitch, the origin and `throwSpear` are unchanged.
* **Blocks**: `tryBlock` still uses the view yaw (±1.31 rad). The view faces the lock, so its
  blows are blockable, and so is anyone within 75° of it.

### C.6 Threat switch (`settings.lockAuto`)

On `enemyWindup { actor: X, targetIsPlayer: true }`:
* It needs X ≠ lockTarget, X lockable, |angleDiff(yaw, bearing(X))| > `threatAngle` (the shield
  can't cover it), the current target not itself winding up at the player, ≥ `threatGap` (3 s)
  since the last threat switch, and X not a foe that lost the lock to a threat within
  `threatReturn` (5 s). The last rule stops the phase C ping-pong: after a switch to a rear
  attacker, the old target (now behind) can't take the lock straight back.
* Then it locks X (reason `'threat'`, fast rate).
* Without `lockAuto`, or when a rule above blocks the switch, the wind-up still plays the
  "behind you" haptic (fx, F.1) and the wind-up chevron clamps to the screen edge (hud, as today).

**Timing, and the hilt wind-up grace (ai.js, P4).** The shortest motion wind-up is Day 3's
Immortal: 0.70 × 1.15 (motion) × 0.9 (Day 3) = 0.72 s. Turning from directly behind into the block
arc (within 1.31 rad of the attacker) takes 0.70 s at the fast rate (simulated: fastRate 3,
fastAccel 18, ω 9), which leaves no time to raise the shield. So in `timeToStrike`, a Persian's
player-targeted wind-up gets **+0.35 s** when `ctx.input.grip === 'hilt'` and the attacker's bearing
is beyond `threatAngle` from `player.yaw` at wind-up start. That makes the shortest such wind-up
1.07 s, with 0.37 s left after the turn (the urgent haptic fires at the switch, so the player can
raise the shield while the view is still turning). The grace is evaluated once, at wind-up start,
and doesn't apply to touch, the window grip, the trainer, or the headless balance runs (no
`ctx.input.grip`).

### C.7 Volleys, the spear cam, respites, Day 3

* **Volleys never turn the view.** In the hilt grip a raised shield (≥ 0.4) takes **volley**
  arrows from either side: in combat.js `arrowOnPlayer`, when `shooter` is null (a volley) and
  `ctx.input.grip === 'hilt'`, the shield counts as facing the source. Aimed archer shots (with a
  shooter) still need the shield to face the archer. Touch and the window grip are unchanged (facing
  the rear is their skill). So the volley event payload needs no `fromBehind`, and there is no
  `'volley'` lock mode.
  * Rationale: phase C alternates rear volleys every 7–10 s. A 180° auto-turn out and back for every
    other volley would make the last stand a spin; a hilt player cannot turn his body to face them.
* **Spear cam**: frozen (C.4). Afterwards a dead target follows the death rule.
* **Respite**: no hostiles → front. The next wave's first men walk into the 14 m cone and are acquired.
* **Day 3**: phase changes clear the lock behind the fade. In phase B the front is unchanged. In
  phase C enemies behind are reached by swipe or by the threat rule (with its grace).
* **Training**: as above. The Agoge's `lock` step and spear step use it (G).

### C.8 Touch mode and the window grip

Unaffected. `lockMode` is `'off'`, `lockTarget` is null and the yaw code paths are today's. The
HUD keeps its focus marker there.

### C.9 Switching grip with a lock

* When `ctx.input.grip` becomes `'hilt'`, the spring resets at the current yaw (no jump) and the
  player acquires.
* When it leaves `'hilt'`, the lock clears (`lockOn {target: null, reason: 'clear'}`) and the yaw
  stays where it is.

---

## D. Thumb controls in the hilt grip (P5, `ui/input.js`)

**Layout**: `#controls` gets `ctl-motion ctl-hilt` when the effective grip is hilt (and keeps
`ctl-left` for left-handed). The layout key that triggers `applyLayout()` becomes
`control + grip + leftHanded`. Nothing but the shield and the thumb strokes lives on the thumb's
arc (the hand model of §1).
* **Pause**: 48 px, top-left (top-right with `ctl-left`), for the off hand.
* **Weapon swap**: `.ctl-swap`, 48 px, top-right (`top: safe-t + 10px; right: safe-r + 10px`;
  top-left with `ctl-left`), for the off hand. It shows the spear count as today and fires on
  pointerdown as today. It is out of the thumb's reach, so it never takes a block press.
* **Thumb swap**: an upward flick of a shield pointer also swaps the weapon (below). The player
  never has to let go of the shield or bring the off hand up.
* **Recenter**: hidden in the hilt layout.

**Shield**: a pointer that starts on `.ctl-surface` raises the shield at once, in the same frame
(`motionShieldCount++`, `shieldHeld = true`, `shieldPressedAt = gnow`), unless one of these holds:
* it starts on a shown HUD button (forwarded, as today; in the hilt layout the only such button,
  READY, sits at the top, E.3);
* it starts within an **edge strip**. Hilt strips, for a right hand (mirrored with `ctl-left`):
  * top 12 px;
  * bottom 24 px (the fist's lower fingers);
  * the **thumb side (right)**: 20 px (the thumb's base and the palm heel);
  * the **finger side (left)**: `settings.hiltFingerStrip` (default 44 px, up to 120) over the lower
    60% of the height, where the fingertips curl onto the glass, and 16 px above that;
* its contact radius is > 40 px (palm).

The Agoge's grip check (G) widens the finger strip when it finds a resting finger there.

Lifting (pointerup or pointercancel) decrements the count, and the shield goes down when no shield
pointer is left.

**Multi-touch**: every shield pointer counts. A second finger (the off hand) also holds the
shield. The pause and swap buttons capture their own pointers and never raise it.

**Thumb strokes**, on a shield pointer only (hilt). Every move sample goes into the pointer's
`Trail` (coalesced events included). A stroke is classified when it has travelled the distance
within the time and speed limits:
* A **switch**: horizontal travel |dx| ≥ 44 px within ≤ 220 ms, speed ≥ 0.35 px/ms and
  |dx| ≥ 0.8·|dy|, i.e. up to ≈ 51° off horizontal, because a thumb pivoting in a fist strokes in
  arcs tilted 30–45° (`INPUT.lockSwipePx/lockSwipeMs/lockSwipeSpeed/lockSwipeRatio = 44/220/0.35/0.8`).
  It yields `{ type: 'switchTarget', dir: dx > 0 ? 1 : -1, src: 'touch' }`.
* A **thumb swap**: upward travel −dy ≥ 70 px within ≤ 250 ms, speed ≥ 0.45 px/ms and
  |dy| ≥ 2·|dx| (≈ 27° off vertical) (`INPUT.swapFlickPx/swapFlickMs/swapFlickSpeed/swapFlickRatio`).
  It yields `{ type: 'switchWeapon', src: 'touch' }` (the existing action).
* Strokes between the two sectors, downward strokes, and slow creeps (< 0.35 px/ms) do nothing:
  a creep is a held block.
* The shield stays held throughout. One action per stroke. It re-arms after the pointer reverses
  ≥ 20 px, pauses ≥ 150 ms, or lifts.

**Swing suppression and commit.** The thumb slides on the glass when the phone is swung, and that
must not change the target. Pointer events arrive before the frame's `pumpMotion`, so
`gestures.debug.m` at stroke time is a frame old. So a classified stroke is **queued**, not pushed:
* input.js keeps a fixed 8-entry ring of per-frame `debug.m` values with their real times, and a
  flag set by any `gesture` event (no allocation). For a queued stroke it tracks the max m from
  50 ms before the stroke's first sample onward.
* `inputCtl.update()` (after `pumpMotion` in the frame) commits the queued stroke once 100 ms of
  real time have passed since it was classified, if that max m stayed ≤ 2.5 rad/s and no `gesture`
  arrived. Otherwise it is discarded silently: a stroke made during a swing is a slip.
* A swipe made once the phone is back near guard (m ≤ 2.5) therefore counts, also right after a
  kill. One made while the phone still swings does not, and gives no feedback (feedback for an
  unintended slip would be noise).

**Keys** (all layouts; the player ignores them outside the hilt grip): Z = switch left,
X = switch right. They are for desktop development and e2e.

**Tip**: the resting-thumb tip keeps its trigger (shield held > 5 s while ≥ 3 slashes fired). In
the hilt grip it names the cause from the position of the longest-held shield pointer:
* on the finger side (the outer 35% of the width on the side opposite the thumb):
  `HILT_TIP_FINGER = 'A finger is touching the glass at the edge. Keep your fingers on the back of the phone.'`;
* elsewhere: `HILT_TIP = 'Your shield is up while you swing. Rest your thumb on the edge of the phone, and press the glass to block.'`.

input.js exposes, for the Agoge grip check, `inputCtl.shieldPointers` (count) and
`inputCtl.lastShieldDown` (`{ x, y, t }`, one object mutated in place).

**Haptics**: input.js plays none. fx owns them (F.1): the shield-up pulse, the lock tick, and the
"nothing there" double tick.

**Hygiene**: unchanged (`contextmenu` and non-passive `touchstart` are preventDefault-ed), plus
`touch-action: none` on the surface (already set).

---

## E. Rendering in portrait

### E.1 Field of view (P2, `js/gfx/framing.js`, new PURE)

```js
export const FOV = Object.freeze({ landscapeH: 100, landscapeV: [55, 70], portraitH: 46, portraitV: [72, 84] });
export function cameraFov(aspect) → vertical degrees
//   aspect ≥ 1: clamp(vfov(landscapeH), 55, 70)   (today's formula, the same numbers)
//   aspect < 1: clamp(vfov(portraitH), 72, 84)    with vfov(h) = 2·atan(tan(h/2)/aspect)
```
* 412×915 and 360×800 (aspect 0.45) give 84° vertical × 44° horizontal.
* 9:18 gives 80.7° × 46°.
* A tablet in portrait (0.75) gives 72° × 57°.

main.js `applySize` uses `cameraFov(w/h)`. A foe at 1.8 m fills ≈ 55% of the height and 45% of
the width, so the lock keeps him centred and the chevrons and hurt arcs carry the flanks.

### E.2 Viewmodel (P6: `gfx/viewmodel.js`, `gfx/viewmodel/motion.js`, `gfx/viewmodel/overlay.js`)

* **vm camera**: `viewmodel.update` sets `vm.camera.fov` from the aspect, writing it only on
  change: 60 when aspect ≥ 1 (unchanged) and 80 when portrait, about 41° across at 0.45. main.js
  keeps setting only the aspect.
* **Hilt guard pose** (`ctx.input.grip === 'hilt'`): the sword is held as the player holds the
  phone, blade upright.
  * `swordHilt`: hand (0.11, −0.27, −0.40), blade (−0.08, 0.93, −0.36), wrist hint (0.3, −0.3, 0.9).
  * `swordHiltHigh` (shield up): hand (0.13, −0.30, −0.38).
  * The rest are portrait variants of today's poses, with x pulled toward the centre by
    `clamp((aspect − 1.78)·0.05, …)` extended to portrait: `thrustBack/Out`, `spearLow/High`
    (the fist at (0.14, 0.02, −0.33) aimed at (0, −0.55, −2.4)), and `spearOut`. The wind-up and
    release poses are reused.
* **Slash path in portrait**: an ellipse `tip = (hx·cos dir, hy·sin dir)·s` in the plane
  z = −0.55, with `[hx, hy] = [0.22, 0.34]` (landscape `[0.35, 0.35]`).
  * `motion.js` gains `slashHalf(aspect) → [hx, hy]`. The half-axes travel in the existing tuning
    object: `slashGeometry(dir, s, out, reach = RIG.swordReach, P = SLASH)` reads `P.half`
    (default `[RIG.slashHalf, RIG.slashHalf]` when absent), so the signature and every existing
    call (`slashEdgeSign` passes `P` 5th) are unchanged. The viewmodel passes a per-aspect copy of
    SLASH with `half` set, built on aspect change only.
  * Motion sync, the trail, edge sign and timings are unchanged.
* **Shield**: low (−0.26, −0.55, −0.40) and high (−0.06, −0.47, −0.37) in portrait. Eulers,
  one-shots, the perfect snap and the guard-break knock are unchanged.
* **Acceptance**, measured by the e2e script from projected mesh bounds or a flat-colour mask
  pass, at 412×915 and 360×800:
  * Guard, shield down: the sword is visible (blade bounds ≥ 25% of the screen height), inside
    x ∈ [45%, 100%]. The aspis covers ≤ 15% of the screen, at the lower left.
  * Shield up: the aspis covers 35–55% of the screen, and the top of its rim sits between 46%
    and 60% from the top.
  * A locked foe at 1.6–2.2 m shows his head above the rim.
  * Nothing of the rig covers the centre 20% × 20%.
  * Draw calls in the vm pass stay ≤ 7.
* **Helmet overlay**: a new `uPortrait` uniform (a uniform, so no new program). In portrait the
  mask keeps to the edges.
  * Acceptance: ≤ 6% of the screen darkened by more than 40%, and none of it inside the central
    70% × 70%.
  * The vignette and hurt flash use `uAspect` as today.

### E.3 HUD in portrait (P8: `ui/hud.js` + `style.css`, `@media (orientation: portrait)`)

All tops include `var(--safe-t)` and all bottoms `var(--safe-b)`. Only transform and opacity animate.

| element | portrait placement |
|---|---|
| pause button | top-left 10 px, 48 px (owned by #controls) |
| weapon button | top-right 10 px, 48 px (owned by #controls, D) |
| health bar | row 1: left: safe-l + 66 px, width min(42vw, 190px), top safe-t + 12 |
| score / chain | row 1, right-aligned at safe-r + 66 px (clear of the weapon button) |
| spears | row 2 (top safe-t + 48), under the health bar |
| kills · wave | row 2, right, 12 px type |
| toasts | right column under row 2 |
| voice subtitles | top 13%, max-width 94vw, Greek 14 px over English 15 px, 2 rows max (above the fist, clear of the thumb) |
| volley warning | centred, top 19%, *Shields up!* 26 px |
| title cards / wave banners | top 24%, max-width 92vw, title clamp(22px, 7.5vw, 34px) |
| combo popup | centred, top ≤ 28%, above the target's head ring |
| lock-on marker | on the target (E.4) |
| lock side arrows | left and right edges at 46% height (E.4) |
| closing (perfect-block) ring | **hilt portrait**: centred on the target's head (`project(actor, −0.12)`), so it sits around 40–50% of the height, below the collapsed Agoge panel and the banners and above the thumb. Elsewhere as today (0.5 m above the head) |
| wind-up chevrons | as today (projected); edge margin 40 px |
| hurt arcs | centred ring of radius 0.42·min(vw, vh) |
| respite | one row at top 30%: the label left of centre and **READY** right of centre (132×48). It is out of the thumb's arc, so a resting thumb never ends the rest; a shield pointer is never forwarded to it (only a pointer that starts on it) |
| sensor meter | top 20%, left |
| spear reticle | centre, as today |

### E.4 Lock-on marker (P8, hud.js)

Shown when the effective grip is hilt, in battle or training, while not cinematic and
`player.lockTarget` is set. It replaces `.hud-focus` there; touch and window keep `.hud-focus`.
* `.hud-lock`: four bronze corner brackets (14 px arms, 3 px stroke over a 5 px dark backing)
  around the target.
  * The box is centred at 0.62 of his height. With `h` the projected head-to-feet height, its
    width is clamp(0.45·h, 40, 160) px and its height clamp(0.62·h, 56, 220) px.
  * It is built from **four fixed-size corner elements**, each moved to its corner by its own
    `translate3d`, so the box resizes without writing width or height and the strokes keep their
    3 px. Transform strings are written only when the rounded pixel value changes.
  * His HP bar sits **above** the box, between his head and the bracket, since the bottom of the
    screen is behind the fist and the thumb (the focus bar moved here). It is a fifth element,
    moved the same way.
* **Last strike chip** `.hud-lock-res` under the top-left bracket: KILL / HIT / GUARDED / MISS for
  the player's last strike. It comes from the player's `swing` and the following byPlayer `hit`
  events: no hit within the strike window (0.35 s game time) is MISS. It stays until
  `ctx.input.phoneSettled` has been true for 0.8 s (real time), so the result is still there when
  the phone comes back to guard. Text changes only on change.
* **States**:
  * `is-windup`: red #d8331f, a 0.3 s pulse, while he winds up at the player.
  * `is-new`: a 150 ms scale-in from 1.25 on every `lockOn` with a new target.
  * `is-off`: when he projects off screen during a turn, the marker clamps to the edge with an
    arrow (the chevron edge maths).
* **Side arrows** `.hud-lock-more-l/-r`: small bronze ‹ › shown while `player.lockSides.left/right`
  exists, telling the player a swipe has somewhere to go.
* DOM text and attributes change only on change.

### E.5 Menus, title, pause, game over, settings, how-to, Agoge panel, update banner (P8; the Agoge panel's markup is P9's)

* **Title** (portrait): one centred column.
  * ΘΕΡΜΟΠΥΛΑΙ, the logo (clamp(30px, 11vw, 52px)), 480 BC, the epigraph (≤ 2 lines, 13 px),
    the update banner, then full-width buttons (min(92vw, 360px)): Fight, Continue, The Agoge.
  * Then the Controls segment (Motion/Touch), the Grip segment (Hilt/Window, only when Motion),
    and How to play | Settings side by side.
  * In landscape the Grip segment joins row 2 when Motion is on.
* **Grip chooser** (A.6): the two cards stacked in portrait, side by side in landscape.
* **Pause**: the existing narrow panel, centred, buttons full width.
* **Game over**: a full-width panel, stats in a 2×2 grid, the epitaph at 14 px.
* **Settings**: a full-screen panel, the tabs row under the header, one field per row with the
  control under its label below 380 px wide.
* **How to play, Motion tab**: the current grip's cards first.
  * Hilt diagrams (new in `DIAGRAMS`): `hiltGrip`, `hiltCut`, `hiltBlock` (thumb on the glass),
    `hiltSwipe`, `hiltThrust`, `hiltThrow` (cock back behind the shoulder, then snap forward, fist
    closed). `hiltGrip` shows the thumb resting on the edge and the fingertips off the glass.
  * A small *Window grip* / *Hilt grip* switch shows the other set.
  * Lead text, hilt: "Hold the phone upright in your fist like a sword's hilt, screen toward you.
    The blade comes out of the top. Rest your thumb on the edge; press the glass to block. A firm
    flick is enough, never throw hard. Earbuds help: your fist covers the speaker."
* **Safety** and **notice**: stacked lists, buttons full width.
* **Rotate prompt** `#rotate` (A.5): full screen, an animated phone-rotation glyph, text and one button.
* **Agoge panel** (portrait): at the **top**, `top: safe-t + 62px; left/right: 10px`.
  * Head row (number, title, pips, check), then hint and text, then a tools row that wraps
    (last move, meters, sensitivity, buttons).
  * **Collapsed** (`#training.is-compact`, set by P9) during the trainer steps (block, perfect,
    bash) and the volley step: only the head row and a one-line hint (≤ 56 px tall). Tapping the
    head row expands it until the next step. So the target's head ring (E.3) is never under it.
  * The big gesture glyph sits on the **finger side** (x 20% for a right hand, 80% for a left),
    at 42% height, 96 px, off the target and out of the thumb's arc. The end screen is centred.
  * The bottom of the screen is behind the fist.
* **Update banner** (`#app-update`, title only, never in a fight): full column width
  (min(540px, 100%)). In portrait `.upd-btns` wraps under the text at 100% width, with buttons
  `flex: 1`, min-height 44.
* **Boot screen and engine-fail panel**: centred flex; they must also read in portrait.
* **Safe areas**: every screen pads `var(--safe-*)`. In portrait the cutout is at the top
  (`--sai-top`) and the gesture area at the bottom.

---

## F. Feedback while swinging blind (P7: `gfx/fx.js`, `audio/audio.js`)

With the phone in the fist, haptics are the main channel, sound is second, and the screen is
read when the phone is back at guard (the last-strike chip, E.4, waits there for it).

### F.1 Haptics

fx keeps owning gameplay haptics. In the hilt grip (`ctx.input.grip === 'hilt'`) it uses
`HAPTICS_HILT`, exported and frozen, which overrides `HAPTICS` per key. Window and touch use
`HAPTICS` unchanged. Rows are either a pulse `[ms, strength]` (fx's dispatcher treats every
2-element row as a pulse, so its ms must be > 0) or an Android pattern `[wait, on, off, on, …]` ms
(≤ 16 values, each ≤ 400).

A swing is felt mid-motion, when touch sensitivity is low, and many phone motors barely render
10 ms. So the hilt vocabulary is small and strong: every "on" segment of a strike-result row is
≥ 30 ms, and the four strike results have four different shapes.

| event | hilt row | reads as |
|---|---|---|
| flesh hit by player | `[45, 1.0]` | **one** short knock |
| absorbed by an enemy guard | `[0, 30, 35, 30, 35, 30]` | **three** short (rattle) |
| kill | `[0, 45, 45, 110]` | knock **+ long** |
| hurt | `[160, 1.0]` | **one long** buzz |
| player block | `[40, 0.8]` | solid |
| perfect block | `[0, 30, 40, 30, 40, 90]` | tick-tick-long |
| shield up | `[15, 0.4]` | light tick |
| arrow on the shield | `[20, 0.6]` | tap |
| spear armed (a `windup` gesture with the spear in hand) | `[0, 35, 45, 35]` | double: the cock-back counted |
| spear release | `[0, 30, 60, 30]` | double |
| spear impact on an actor | `[0, 40, 40, 90]` | heavy |
| spear in the ground or sea | `[20, 0.5]` | light |
| volley warning | `[0, 40, 90, 40]` | double |
| lock switch / acquire (`lockOn` with a target) | `[30, 0.6]` | tick |
| swipe, nothing that way (`lockOn` none) | `[0, 30, 70, 30]` | soft double |
| attack from behind (`enemyWindup`, targetIsPlayer, from beyond `threatAngle` of the view) | `[0, 80, 60, 80]` | two long: urgent. It plays whether or not the lock turns (C.6), so `lockOn` reason `'threat'` adds nothing |

**Coalescing**: in the hilt grip a new haptic within 40 ms of the last is dropped, except kill,
hurt, perfect and attack-from-behind, which replace it. A whiff (a strike that hits nothing)
gives no haptic; the chip says MISS at guard.

### F.2 Audio

* Existing cues already carry the swing: `whoosh` on a swing; `flesh`, `clang` and `wicker` on
  impact; `death`; `comboSting`; `grunt` at a player-targeted wind-up (the perfect-block cue);
  `spearHit`; the horn and "Shields up!" for volleys.
* New handlers in audio.js (hilt grip only):
  * on `lockOn` with a target and reason `'switch'`, `'acquire'` or `'threat'`: `uiTap` at volume
    0.6 (0.8 for threat), centred. Stereo panning is not used for direction: in portrait a phone's
    speakers sit at the top and bottom, and the fist covers the bottom one. Direction comes from
    the turn itself and the HUD arrows.
  * on a `windup` gesture with the spear in hand: `uiConfirm` at 0.5 (spear armed).
* No new SFX names (`SFX_NAMES` stays at 30). The hilt how-to suggests earbuds (E.5).

### F.3 Slow motion (hilt only; fx)

`HILT_SLOWMO = Object.freeze({ kill: [0.4, 0.22], heavy: [0.3, 0.30], perfect: [0.5, 0.18], minGap: 2.5 })`.
* All are `slowMo(scale, realSeconds, { playerRealtime: true })`, so the hero keeps full speed.
* **Kill** by the player, not by the spear (the spear cam has its own): `heavy` for a crit, a
  captain or an "Into the Gulf" kill. Otherwise `kill`, when the last player swing's power was
  ≥ 0.55. fx's `onSwing` records `lastSwingPower = e.power` for this (today it records only kind,
  direction and time).
* The slow-motion is a flourish, not the result readout. The death animation (> 1 s) and the
  last-strike chip (E.4) are what the player sees when the phone is back at guard.
* **Perfect block**: `perfect`.
* At most one per `minGap` real seconds. None during a volley's impact phase.
* Overlaps with combo slow-mo merge as `TimeControl.slowMo` already does (min scale, max duration).
* The spear cam rules are unchanged.

### F.4 The lock-on indicator

Together with the camera's own turn, the indicator is three channels:
* the HUD marker and side arrows (E.4);
* the centred tick sound (F.2);
* the tick haptic (F.1).

A player who never looks still knows that the lock moved and whether a swipe found nobody; the
turn itself, and the marker once the phone is at guard, say where.

---

## G. The Agoge in the hilt grip (P9, `ui/training.js`)

**Steps.** The exported `STEPS` keeps its 12 ids, order and `n` (ui-helpers.test.mjs pins them, and
two e2e scripts address the spear step as index 10). Its entries gain a `hilt` instruction string
and a `demoHilt()` script, used when `effectiveGrip(settings) === 'hilt'`. The new step is a
separate export, `HILT_LOCK_STEP` (`id: 'lock'`, `n: 2`, `hiltOnly: true`).
* `start()` builds the runtime list once:
  `steps = grip === 'hilt' ? [...STEPS.slice(0, 6), HILT_LOCK_STEP, ...STEPS.slice(6)] : STEPS`.
  `stepIndex`, `goTo`, `leaveStep`'s look-ahead, the step label and `demo(i)` all index `steps`.
* Touch and window therefore keep indices 0–11 and their demo seeds (`seed: 7 + index`) unchanged.
* `ROMAN` gains XIII, and the label is `${ROMAN[index]} / ${ROMAN[steps.length − 1]}`.

| step | hilt instruction | hilt demo (`hold: 'hilt'`) |
|---|---|---|
| grip | Hold the phone upright in your sword hand like the hilt of a xiphos, screen toward you. Rest your thumb on the edge; your fingers stay on the back, off the glass. Grip firmly; a wrist strap helps. Clear the space around you, behind you too. Then hold your guard still. | — (the grip check below) |
| calibrate | Cut 5 times the way you will in battle: let the top of the phone lead, like a blade. A firm flick is enough. | swing R ret 0, swing L ret 0, swing R |
| slashLR | Cut right twice, then left twice: sweep the top of the phone across, like a windscreen wiper. | R, R, L, L |
| overhead | Chop straight down from your guard: tip the top of the phone forward and down. Twice. It goes over a wicker shield. | swing D, idle 0.5, swing D |
| uppercut | Lower the blade slowly toward the ground ahead, then swing it up. | lower β 0.1, idle 0.3, swing U |
| thrust | Punch the phone straight forward, then draw it back. Three times. | thrust ×3 |
| **lock** | Swipe your thumb right to turn to the next foe. Then swipe left. | — (an animated thumb glyph in the big slot) |
| block | Press the glass with your thumb to raise the shield before each blow lands. Lift it to lower the shield. | — |
| perfect | Press at the last moment, as the red ring around his head closes. Twice. | — |
| bash | Thumb on the glass, then punch forward. Twice. | thrust ×2 (demoShield) |
| volley | When the archers loose, keep your thumb on the glass until the arrows have fallen. | — |
| spear | Flick your thumb up (or tap the weapon button) to take the spear. Thrust twice. Then cock the phone back behind your shoulder like a javelin, and snap it forward, fist closed. | thrust ×2, then the far dummy is locked by `setLock`, then throw |
| twin | Cut right, then left, fast, into the dummy. | R, L (arc 1.0, speed 9) |

* **Grip step: guard and grip check** (hilt). After the confirm tap, the step runs a 3 s check:
  "Hold your guard still, thumb on the edge."
  * It passes once the recognizer has learned the guard (B.3.3) and no shield pointer has started
    during the 3 s.
  * A shield pointer that starts and is held ≥ 0.5 s during the check is a resting finger. The
    step shows a dot where it is (`inputCtl.lastShieldDown`), with the hint "Something touches the
    glass here. Keep your fingers on the back of the phone.", and restarts the check.
  * If the dot is on the finger side within 160 px of the edge, `hiltFingerStrip` grows to cover
    it (`min(120, distance from the edge + 16)`) and is saved.
* **`lock` step**: `enterStep` spawns two extra dummies at (−1.6, −2.2) and (1.6, −2.2) with
  `ai.spawnDummy`, and calls `player.setLock(nearDummy)`.
  * Progress: one `lockOn` with reason `'switch'` and the new target to the right (hint "Now
    left."), then one to the left. n = 2. The C.1 ranking makes a right swipe from the centre pick
    the right dummy, not the far one.
  * `leaveStep` removes the extra dummies the way `removeTrainer` does (ai.clear, then
    `spawnDummies()`). The old lock is dropped in the next frame (`inActors`, C.3).
* **Overhead step** (hilt): a `windup` before the chop doesn't count against the player (the chop
  still counts).
* **Uppercut step** (hilt): a calibration candidate with `heldUp: true` and no slash gives the hint
  "Lower the blade first, then swing up."
* **Spear step**: after the second thrust the near dummy steps aside (as today) and the Agoge calls
  `player.setLock(farDummy)` with the hint "The far target is locked." The view turns to it at
  once, so it never ends up beside the screen. A throw at anything else gets "Throw at the far
  target." A spear `windup` shows "Armed" in the panel.
* **Over-speed**: a gesture with `fast: true` gets the hint "Easy: a firm flick is enough."
* **Calibration**: the same `pickStrokes` / `suggestSensitivity(peaks, CALIBRATION)`. Apply
  writes `settings[sensitivityKey(settings)]`. The panel slider, the calibration text and the
  meter marks read and write the same key (training.js:232, 414, 578, 606, 627). If the stroke
  candidates are slow, the hint is "Sweep the top across, like a windscreen wiper", and the big
  slot shows `DIAGRAMS.hiltCut` during calibrate and slashLR.
* **Mirrored-setup check**: unchanged logic, on the effective grip's keys: it toggles
  `settings[flipKeys(settings).x]` or `.y`, and `setFlip` gets the same keys.
* **Demos**: `synthesize(script, { hold: grip === 'hilt' ? 'hilt' : 'window', rot: screenRotation(), hand, gyroRange, seed })`.
  The `ctx.motionDemo` flag, `inject` and the timing are unchanged.
* **Panel**: in portrait it sits at the top (E.5). `#training.is-hilt` is added for CSS, and
  `#training.is-compact` during the block, perfect, bash and volley steps.
* **Grip diagram**: step 0 shows `DIAGRAMS.hiltGrip` (from menus.js, exported).
* **Done**: finishing the Agoge in the hilt grip sets `settings.hiltAgogeDone = true` (A.6).

---

## H. Test plan

### H.1 Unit tests (`node --test spartan/tests/unit/*.test.mjs`)

CI runs these before it builds the APK (`.github/workflows/android.yml`), so a red unit test means
no APK and no in-app update.

**Unchanged and green**: every existing test file passes unmodified, including
`motion-gestures`, `motion-feel`, `motion-frames`, `motion-synth`, `motion-player`,
`motion-platform`, `ui-agoge-demos`, `viewmodel-motion`, `balance-ai` and `combat-fixes`, with
**one exception**: `ui-helpers.test.mjs` (P8) pins the Settings FIELDS. In the same change that
adds the rows, P8 updates:
* the sorted FIELDS keys: + `grip`, + `lockAuto` (18 keys);
* the `motionOnly` keys: `['grip', 'sensitivity', 'gyroLook', 'motionFlipX', 'motionFlipY']`;
* a new assertion: the only `hiltOnly` key is `lockAuto`.

Its STEPS assertions (ids, n, `touch` text for every non-motion-only step) stay as they are,
because `HILT_LOCK_STEP` is not in `STEPS` (G).

`modulegraph.test.mjs` picks up the new modules (`core/grip.js`, `gfx/framing.js`,
`game/lockon.js`), which MUST be import-safe in Node. `motion-platform`'s arity test reads
`NativeBridge.kt`, so it checks the new 2-argument call by itself.

New files (each owned by the package that owns the code under test):

1. **`motion-hilt.test.mjs`** (P3). synth `hold:'hilt'`, then `MotionFrames({grip:'hilt'})`, then
   `GestureRecognizer({grip:'hilt'})`.
   * Matrix: lean {0.2, 0.45, 0.8} × roll {0, 0.3} × rate {50, 100, 200} × {android with TYPE_GRAVITY,
     web +1, web −1 without gravity}, plus no-linear / no-gravity / both / web −1 no-linear at
     lean {0.2, 0.8}. Every run is preceded by idle 1.2.
   * Each of R, DR, D, DL, L from the guard, with wind-up, rebound and return, gives exactly one
     slash with the right token.
   * U, UR and UL each give exactly one slash after `lower β 0.1, idle 0.3`.
   * An up swing from the guard (speed 4–12, arc 0.6–1.6) is never a slash. It gives exactly one
     `windup` iff it ends at β′ ≥ 2.35 (arcs ≤ 1.2 never do).
   * R, L, R, L at ≈ 2 Hz (arc 1.0, speed 9, ret 0) gives 4 alternating slashes.
     `swing R speed 10 ret 0, idle 0.05, swing L speed 4` gives exactly 1; with L at speed 9, 2.
   * Gentle R then L (speed 4, arc 0.9), each returning at 1–2 rad/s, gives R, L.
   * **Forearm sweeps**: `yawShare 1.0` R and L at 5 and 9 rad/s, at lean 0.2 and 0.45, give R and L.
   * Throws at speed {5, 6, 7} give exactly one windup and one throw, no slash, power > 0 and
     rising with speed. Throws at 7.5 and 10 both give power 1.
   * **No false throws**: with the spear, `raise { cockArc }` of 0.5, 0.75 and 1.0 followed by a
     DR, D or DL swing at 6, 9 and 12 rad/s gives that slash and no `windup` or `throw`, at lean
     {0.2, 0.45, 0.8}, including guards at the shift clamps (G 0.6 and 1.6).
   * With the spear, a D swing without a cock-back is a D slash.
   * raise + swing D gives windup, D with the sword, and windup, throw with the spear.
   * **Over-speed**: a slash at 15 rad/s and a throw at 15 rad/s carry `fast: true`; at 12, no `fast`.
   * **Guard learning**: hold a tapping pose (βabs 0.7) for 1.5 s, then a fist guard at 1.35: from
     3 s after the change, the wind-up and chop thresholds equal those of a clean start at 1.35.
     No snap happens during the first 1.0 s after `reset()`.
   * A thrust, then a retraction of {0.25, 0.3, 0.35} s, gives exactly one thrust, power > 0.5.
   * 10 s of walking, 10 s of idling, and a slow lower to β 0.1 and back give nothing.
   * `consumeLook().yaw === 0` on every sample of every script.
   * Latency from motion onset < 130 ms at 9 rad/s for R, L, D, DR and DL.
   * Display rotation {0, 90, 270} (a natural-landscape tablet) classifies the same.
   * Light matrix: proportionally scaled motions at s {0.6, 1, 1.6}; the left hand; 10% jitter
     with 4% dropped samples; cock-backs at 3.5–12 rad/s over 0.9–1.5 rad never slash.
   * settled: true at the guard within settleTime + 0.05 s; false while cocked back with the
     screen facing down; false at the follow-through with the blade below horizontal.
   * `setGrip('window')` then `('hilt')` resets cleanly: no event, and no look jump > 0.
2. **`motion-hilt-feel.test.mjs`** (P3).
   * Grips: lean {0.2, 0.45, 0.8}, roll ±0.5, aim ±0.52, grip pitch ±0.35, crossed with android /
     web −1 and 50/100/200 Hz.
   * Cautious swings (3.5–4 rad/s, arc 0.8–1.0, small cock-back, wrist roll, slow off-target
     return) register at s = 1, ≥ 98% detected, never a wrong token or an extra event.
   * Forearm-sweep horizontals (`yawShare` 0.6 and 1.0) at 5 and 9 rad/s give R and L.
   * Latency: 5 rad/s arc 1.0, median ≤ 90 ms and worst ≤ 110 ms. 9 rad/s arc 1.4, median
     ≤ 70 ms and worst ≤ 85 ms. A chop from the guard gives D within 180, 140 and 100 ms at 3.5,
     5 and 9 rad/s.
   * No false slashes from walking, idling, a held cock-back, a slow lower, returns to guard
     (2.5 and 4 rad/s, and 1–2 rad/s up to 0.44 rad off), or body turns up to 2 rad/s (with the
     yaw floor).
   * setFlip maps dir → π − dir and dir → −dir.
   * Calibration: 5 swings at 2.4 rad/s slash nothing at 1×. `pickStrokes` finds 5, and the
     suggestion makes them slash.
3. **`motion-frames-hilt.test.mjs`** (P3).
   * Hilt web −1 latches within 3 fast swings.
   * The provisional rule uses `restUp` (a hilt guard held 1 s flips a wrong sign; a calm
     blade-down rest of 2 s after the latch changes nothing).
   * rotFix is never applied in the hilt grip.
   * A rotation change is still a gap.
   * `setGrip` keeps axisMap, sign and rotFix.
4. **`motion-synth-hilt.test.mjs`** (P3).
   * At the guard, gravity reads device +y·sin βg and +z·cos βg.
   * A hilt R swing with lean 0 is a pure roll about the body's forward axis (gyro on screen −z,
     `speed` is the true peak).
   * The cock-back takes βabs past π/2 + 1.0.
   * A thrust's peak acceleration is along the body forward.
   * `hold:'window'` output is byte-identical to before for a fixed script and seed.
5. **`hilt-lockon.test.mjs`** (P4, PURE lockon.js).
   * `acquire` picks the front-centre foe over a nearer one at 1.0 rad. The wind-up and engaged
     bonuses reorder.
   * Archers are never acquired but can be switched to.
   * `nextInDirection` finds right and left, honours `sideMin` and the `sideGap` rule, reaches the
     rear (|rel| up to π), honours `range`, and returns null when nothing lies that way.
   * Ranking: from an engaged foe ahead at 1.6 m, the ±35° slot foe at 1.6 m beats a foe at 8° and
     20 m; from the Agoge centre dummy, a right swipe gives the right dummy (1.6, −2.2), not the
     far dummy (1.5, −18); an archer at 30 m is picked when he is the only foe that way.
   * `inActors` is true for a listed actor and false once the list was cleared.
   * `YawSpring`:
     * 0 → 1.0 rad at 60 Hz: monotone, overshoot < 0.005, every step ≤ maxRate/60 + 1e-9, the
       change in velocity per step ≤ maxAccel/60 + 1e-9, within 0.035 rad by 0.8 s, at rest by 1.2 s;
     * a ±0.02 rad oscillating target leaves it at rest;
     * fast mode honours fastRate;
     * dt 0.05 is stable.
6. **`hilt-player.test.mjs`** (P4, headless gameplay harness, `ctx.input.grip = 'hilt'`).
   * Locks the front foe on spawn. A `switchTarget` action moves it, with `lockOn` reason
     `'switch'`. A miss emits `'none'` without moving the view.
   * Killing the lock acquires the next within deathHold + 2 frames.
   * An auto-acquired lock beyond keepRange for loseTime: `'lost'`. A lock swiped to an archer
     at 30 m is kept for 3 s, and a throw 1.5 s later flies at him.
   * Clearing the actor list (as `ai.clear()` does) with a lock gives `'lost'` within 1 frame.
   * No foes: yaw → 0.
   * A rear volley (`combat.volley({ fromBehind: true })`) with the shield raised and
     `ctx.input.grip = 'hilt'`: the arrows hit the shield and the yaw doesn't change. With grip
     `'window'`, the same volley hits the player (unchanged rule).
   * A threat wind-up from behind takes the lock, at most once per threatGap, and the foe that
     lost the lock can't take it back by a threat within threatReturn.
   * In the hilt grip a `recenter` action never writes yaw through `recenterLeft`.
   * `fx.cameraOverridden` freezes the yaw.
   * attackYaw is within 0.02 rad of the lock's bearing.
   * A throw's velocity yaw is within 0.03 rad of the bearing to a locked foe at 12 m.
   * With `grip` null or `'window'`: lockTarget stays null and the yaw follows `input.lookYaw` as
     today (`motion-player.test.mjs` stays green).
6b. **`hilt-ai.test.mjs`** (P4, built like `balance-ai.test.mjs`: `createAI` with a stub ctx).
   * With `ctx.input.grip = 'hilt'`, player yaw 0: a Day 3 Immortal's player-targeted wind-up from
     bearing π gets `timeToStrike` = 0.70 × 1.15 × 0.9 + 0.35; from bearing 0.5, no grace.
   * With grip `'window'` or absent, and for the trainer, no grace.
7. **`hilt-framing.test.mjs`** (P2).
   * `cameraFov` equals the old formula at aspect 16/9, 19.5/9 and 2.22.
   * At 0.45 it is 84 (horizontal 44° ± 1).
   * It is continuous and monotone across each branch and clamped.
8. **`hilt-grip.test.mjs`** (P2): the `effectiveGrip` (a missing or unknown grip → window),
   `appOrientation`, `sensitivityKey` and `flipKeys` tables; `DEFAULT_GRIP` follows `HILT_RELEASED`;
   `migrateSettings` (motion without a grip → window; touch → untouched; a fresh install → no grip
   key, so `DEFAULT_GRIP` applies).
9. **`hilt-platform.test.mjs`** (P1, platform.js in Node).
   * Web portrait → `lock('portrait-primary')`, both locked and not.
   * Landscape → `'landscape'` or `unlock()`.
   * Android passes exactly `(lock, 'portrait'|'landscape')`, and an unknown orientation → `'landscape'`.
   * `HostLogic.kt`'s `requestedOrientation` (read as text, as motion-platform reads the bridge)
     has the four branches in order: portrait → 1, `!lock` → 6, `displayLandscape` → 14, else → 0.
     There is no JVM test source set in `:spartan`, so this source check stands in for one.
10. **`ui-agoge-demos-hilt.test.mjs`** (P9). For hand {right, left}, every hilt demo gives its
    taught gestures through the hilt pipeline:
    `calibrate R,L,R; slashLR R,R,L,L; overhead D,D; uppercut U; thrust thrust×3; bash thrust×2; spear thrust,thrust,windup,throw; twin R,L`.
    The runtime step list has 13 steps in the hilt grip (`lock` at index 6) and is `STEPS` itself
    otherwise.
11. **`viewmodel-hilt.test.mjs`** (P6, PURE motion.js): `slashHalf(0.45) = [0.22, 0.34]`,
    `slashHalf(2.17) = [0.35, 0.35]`, `slashGeometry` with `P.half` absent equals today's output,
    and the portrait ellipse path (`P.half = [0.22, 0.34]`) crosses the centre at s = 0 with the
    same timing.
12. **`hilt-feedback.test.mjs`** (P7).
    * `HAPTICS_HILT` is frozen, every value is valid (≤ 16 values, each ≤ 400 ms), and every key
      of `HAPTICS` resolves.
    * Every 2-element row has ms > 0 (fx dispatches it as a pulse), and every "on" segment of the
      hit, guarded, kill and hurt rows is ≥ 30 ms; those four rows have different shapes.
    * The slow-mo gap rule, driven against `TimeControl` with a fake time; a kill after a swing of
      power 0.5 gives no slow-mo, after 0.6 it does (`lastSwingPower`).

### H.2 End-to-end (Playwright, `spartan/tests/e2e/`)

**Infrastructure.** Every way the existing scripts build a page must keep today's window-grip
behaviour once `HILT_RELEASED` flips the default:
* **`lib.mjs`** (P2): `newPage({ width, height, mobile, grip = 'window' })` adds an init script.
  Before each load it merges `{ grip, gripChosen: true }` into `localStorage['thermopylae.settings']`,
  unless the stored settings already hold a `grip`.
* **`review-device-native.mjs`'s `phonePage`** (P1): the same seed behind a `grip = 'window'`
  option (`grip: null` skips it). This covers every phonePage user: `review-device-pausemotion`
  (runtime `control = 'motion'`), `review-device-phone` (taps Motion, then expects the safety
  screen, so it needs `gripChosen`), `review-device-fonts` (shows the Motion how-to tab), and the
  other `review-device-*` scripts.
* **The UI sandbox** (P2, `tests/sandbox/sandbox-ctx.js`, `ui-sandbox.html`): its settings gain
  `grip: 'window', gripChosen: true`, a `?grip=` parameter, and a `ctx.applyControls() {}` stub
  (P5 and P8 call it as `ctx.applyControls?.()`). `ui-input`, `ui-flows` and `ui-shots` stay on the
  window layout; the unknown-grip → window rule also protects them.
* Hilt scripts pass `grip: 'hilt'` or the URL parameter `grip=hilt`.

New scripts:
1. **`hilt-shots.mjs [outDir]`** (P8), mobile contexts at **412×915** and **360×800**, `?manual=1&control=motion&grip=hilt`.
   * Screenshots: title, grip chooser (with `HILT_RELEASED` forced through a test hook), safety,
     settings (Controls), how-to (Motion), pause, game over, Agoge (the grip, lock, perfect and
     spear steps), battle (guard, shield up, lock marker with 3 foes and the last-strike chip,
     a closing ring, volley warning, subtitles + combo popup, respite READY), the update banner
     (fake `appupdate` state), and the rotate prompt (a landscape viewport with hilt).
   * Assertions in each state:
     * `scrollWidth ≤ innerWidth`;
     * every visible `button` inside the viewport inset by the safe area, and ≥ 44×44;
     * no overlap among the HUD rows, subtitles, Agoge panel, volley warning and banners;
     * the closing-ring and lock-bracket rectangles never intersect the Agoge panel, subtitles,
       title cards or the combo popup;
     * the weapon button and READY lie above 35% of the height (out of the thumb's arc), and no
       button lies in the band y 40–80%, x 35–100% (right hand; mirrored for `ctl-left`);
     * computed font size ≥ 11 px;
     * no page errors.
2. **`hilt-vm.mjs [outDir]`** (P6): the E.2 coverage criteria at both sizes, with guard / shield up
   / slash mid-path screenshots, the helmet mask coverage, and vm draw calls ≤ 7.
3. **`hilt-thumb.mjs`** (P5), touch events through CDP `Input.dispatchTouchEvent` on the real game:
   * a touch-down raises `shieldHeld` in the same step and a lift lowers it;
   * the thumb-side, bottom and top strips and a contact radius of 41 px don't raise it;
   * **touches 20–40 px from the finger-side edge in the lower half don't raise it**, and with
     `hiltFingerStrip = 80` neither do touches at 60 px;
   * two pointers hold it until both lift;
   * a 60 px / 120 ms horizontal swipe gives exactly one `switchTarget` (committed ≈ 100 ms later)
     and the shield stays up;
   * 60 px strokes at 35° and 45° off horizontal also switch; one at 60° does not;
   * a 80 px / 150 ms upward flick gives exactly one `switchWeapon`, and the shield stays up;
   * a 0.2 px/ms drag gives none;
   * a swipe that starts 30 ms before an injected hilt swing gives none (the commit waits for the
     swing's samples); a swipe 0.4 s after the swing's slash, with the phone at guard, gives one;
   * pause and swap never raise the shield.
4. **`hilt-agoge.mjs`** (P9): the hilt Agoge runs through every step with demos (and actions for
   the `lock` step). The grip check passes with no touch and flags a held touch at the finger
   edge (and widens `hiltFingerStrip`). The lock step counts the right swipe (to the right dummy),
   then the left. In the spear step the far dummy is locked when the near one steps aside. Each
   step advances, the label reads "XIII" at the end, and `hiltAgogeDone` is set.
5. **`hilt-orientation.mjs`** (P1). It uses `review-device-native.mjs`'s fake bridge with
   `lockOrientation: ['boolean', 'String?']`, and the calls are recorded:
   * boot with stored motion+hilt gives `(false,'portrait')` on the title;
   * a battle gives `(true,'portrait')`;
   * pause, Settings, Touch gives `(false,'landscape')` while paused;
   * resume gives `(false,'landscape')`;
   * window grip gives `(true,'landscape')`;
   * zero ARITY warnings.
6. **`hilt-lockon.mjs`** (P10, cross-package, real game).
   * 3 foes at 4 m: 40° left, ahead, and 35° right. The initial lock is the one ahead.
   * Swipe right: the lock moves to the right foe. The yaw comes within 2° of his bearing in
     ≤ 0.8 s, every frame's |Δyaw| ≤ 0.040 rad, and Δyaw never changes sign during the transition.
   * Killing the lock moves it within 0.5 s.
   * No foes: yaw → 0 ± 0.01 within 1.5 s.
   * A rear volley (`combat.volley({fromBehind:true})`) leaves the yaw unchanged, and with the
     shield raised its arrows hit the shield.
   * The spear cam leaves the yaw unchanged.
   * The HUD marker sits within 12 px of the target's projected point.
   * **Day 3 phase C, 60 s** (`?grip=hilt`, an autopilot that holds the shield up and slashes the
     lock every 1.5 s, `lockAuto` on): at most 4 turns of more than 90° per minute, the camera
     moving (`spring.moving`) less than 15% of the time, and no threat switch straight back to
     the previous target within 5 s.
7. **`hilt-throw.mjs`** (P10, android fake source through `review-motion-lib.mjs`, `hold:'hilt'`, rot 0).
   * Cock-back + throw gives the `windup` and `throw` gestures, `viewmodel.play('throwWindup')`
     and the "armed" haptic on the wind-up.
   * With the spear in hand, a 0.8 rad lift and a D chop gives a slash and no spear cam.
   * `spearThrown` velocity yaw is within 0.03 rad of the bearing to the locked foe at 12 m.
   * `spearCam` starts, time scale is 0.1 until the synthetic return to guard settles the phone,
     then it ramps.
   * In the Agoge, with the far dummy locked, the spear lands within 2 m of it.

**Existing e2e that MUST stay green**: `smoke.mjs` (960×540), `ui-flows`, `ui-input`, `ui-shots`,
`ui-real`, `viewmodel-checks`, `presentation-checks`, `fx-e2e`, `motion-platform.mjs`,
`combat-agoge-dory.mjs` and `review-motion-agoge-spear.mjs` (they address the spear step as index
10, which stays true outside the hilt grip), every `review-motion-*.mjs` (window grip through the
lib seed), `review-device-native.mjs` (SIG updated by P1), `review-device-pausemotion.mjs`,
`review-device-phone.mjs`, `review-device-fonts.mjs` (phonePage seed), and the `review-integ-*.mjs`
scripts that switch to motion (lib seed).

The sweep (P10) runs all of them with `HILT_RELEASED = true` before flipping it. Every script that
reaches motion goes through one of the three seeds above (`ai-alloc` and `review-device-memory`
never switch to motion and need nothing).

---

## I. Work breakdown

### I.1 Packages and file ownership (each file is owned by exactly one package)

| pkg | scope | files (new = ★) |
|---|---|---|
| **P1** Host and platform orientation | A.4, A.5 bridge/web lock, persisted orientation, the phonePage seed | `spartan/src/main/AndroidManifest.xml` (comment), `spartan/src/main/java/com/thermopylae/NativeBridge.kt`, `…/HostLogic.kt`, `…/MainActivity.kt`, `js/platform.js`, `spartan/tests/e2e/review-device-native.mjs` (SIG + fake + `phonePage({ grip })` seed), ★`spartan/tests/unit/hilt-platform.test.mjs`, ★`spartan/tests/e2e/hilt-orientation.mjs` |
| **P2** Grip core and main glue | A.1–A.3, A.6 main side, E.1 FOV, ctx plumbing, e2e and sandbox seeds | ★`js/core/grip.js`, ★`js/gfx/framing.js`, `js/main.js`, `spartan/tests/e2e/lib.mjs`, `spartan/tests/sandbox/sandbox-ctx.js`, `spartan/tests/sandbox/ui-sandbox.html`, ★`spartan/tests/unit/hilt-grip.test.mjs`, ★`spartan/tests/unit/hilt-framing.test.mjs` |
| **P3** Recognition | B (frames, gestures, synth) | `js/motion/frames.js`, `js/motion/gestures.js`, `js/motion/synth.js`, ★`…/unit/motion-hilt.test.mjs`, ★`…/unit/motion-hilt-feel.test.mjs`, ★`…/unit/motion-frames-hilt.test.mjs`, ★`…/unit/motion-synth-hilt.test.mjs` |
| **P4** Lock-on, player and the hilt combat rules | C, including the two narrow edits outside player.js: combat.js's hilt volley rule (C.7) and ai.js's hilt wind-up grace (C.6). No other change to those files | ★`js/game/lockon.js`, `js/game/player.js`, `js/game/combat.js` (`arrowOnPlayer` only), `js/game/ai.js` (`timeToStrike` only), `spartan/tests/sandbox/gameplay-harness.js` (only if the hilt tests need ai/fx stubs; `motion-player.test.mjs` stays green), ★`…/unit/hilt-lockon.test.mjs`, ★`…/unit/hilt-player.test.mjs`, ★`…/unit/hilt-ai.test.mjs` |
| **P5** Thumb input | D | `js/ui/input.js`, ★`spartan/tests/e2e/hilt-thumb.mjs` |
| **P6** Portrait viewmodel | E.2 | `js/gfx/viewmodel.js`, `js/gfx/viewmodel/motion.js`, `js/gfx/viewmodel/overlay.js`, ★`…/unit/viewmodel-hilt.test.mjs`, ★`spartan/tests/e2e/hilt-vm.mjs` |
| **P7** Blind-swing feedback | F | `js/gfx/fx.js`, `js/audio/audio.js`, ★`…/unit/hilt-feedback.test.mjs` |
| **P8** Portrait UI | A.5 rotate prompt, A.6 menus side (chooser, safety, first-Fight notice), E.3–E.5 (incl. the last-strike chip and coach toasts), **all CSS** (including `.ctl-hilt` from D and the Agoge panel from G), the Settings FIELDS test | `js/ui/hud.js`, `js/ui/menus.js`, `js/ui/updates.js`, `style.css`, `index.html` (only if the boot markup needs it), `spartan/tests/unit/ui-helpers.test.mjs` (FIELDS assertions only, H.1), ★`spartan/tests/e2e/hilt-shots.mjs` |
| **P9** The Agoge | G | `js/ui/training.js`, ★`…/unit/ui-agoge-demos-hilt.test.mjs`, ★`spartan/tests/e2e/hilt-agoge.mjs` |
| **P10** Integration, release and docs | the cross-package e2e, the existing-test sweep, the release flip, DESIGN.md, README | `spartan/DESIGN.md`, `spartan/README.md`, `spartan/tests/e2e/review-motion-lib.mjs` (`arena({ grip })` → `settings.grip` + `ctx.applyControls()`), ★`spartan/tests/e2e/hilt-lockon.mjs`, ★`spartan/tests/e2e/hilt-throw.mjs`; the one-line `HILT_RELEASED = true` in `js/core/grip.js` after P2–P9 have merged (a sequential follow-up to P2's file, never concurrent); deletes `spartan/docs-hilt-grip.md` |

**Cross-package interfaces** (this document is their contract; consumers use optional chaining
until the producer lands):

| interface | producer | consumers |
|---|---|---|
| `core/grip.js` helpers (`effectiveGrip`, `sensitivityKey`, `flipKeys`, `HILT_RELEASED`, …) | P2 | P5, P6, P7, P8, P9 |
| `ctx.input.grip`, `ctx.applyControls()`, `<html data-grip>` | P2 | P4, P5, P6, P7, P8, P9 |
| new settings keys (A.1) | P2 (defaults, migration) | P5 (`hiltFingerStrip`), P8 (rows), P9 (`hiltFingerStrip`, `hiltAgogeDone`, flip keys) |
| `platform.lockOrientation(lock, orientation)` | P1 | P2 |
| `motionFrames.setGrip`, `gestures.setGrip`, `GRIPS`, synth `hold`; gesture `fast` flag; candidate `heldUp` flag | P3 | P2, P7, P8, P9 (P10 e2e) |
| `switchTarget` action (touch swipe, keys Z/X); `switchWeapon` from the thumb flick; `inputCtl.shieldPointers` / `lastShieldDown` | P5 | P4 (actions), P9 (grip check) |
| `player.lockTarget / lockMode / lockSides / setLock / switchTarget`, the `lockOn` event | P4 | P6 (guard pose needs only `grip`), P7, P8, P9 |
| CSS class contract (`.ctl-hilt`, `.ctl-swap` placement, `#training.is-hilt`, `#training.is-compact`, `.hud-lock*`, `.hud-lock-res`, `#rotate`, grip chooser `.mn-grip`) | P5, P8 (hud/menus), P9 (markup) | P8 (stylesheet) |
| `DIAGRAMS.hiltGrip`, `DIAGRAMS.hiltCut` exports | P8 | P9 |

### I.2 Order and dependencies

```
            ┌──────────── P1 host/platform ───────────┐
P2 grip ────┼──────────── P3 recognition ─────────────┼──► P9 Agoge ──┐
core/main   ├──────────── P4 lock-on/player ──────────┤               │
(small,     ├──────────── P5 thumb input ─────────────┤               ├──► P10 integration,
 first)     ├──────────── P6 portrait viewmodel ──────┤               │    e2e, DESIGN.md
            ├──────────── P7 feedback ────────────────┤               │
            └──────────── P8 portrait UI + CSS ───────┴───────────────┘
```
* **P2 first** (≈ a day): it publishes `core/grip.js` (with `HILT_RELEASED = false`, so
  `DEFAULT_GRIP` is `'window'`), `ctx.input.grip`, `applyControls`, the new settings keys and the
  lib and sandbox seeds. Nothing changes for any player, because every consumer is optional and the
  default grip is still window. P1 and P3–P8 then run in parallel.
* **Every intermediate build is safe to ship.** CI publishes each branch push as an in-app update
  (DESIGN §2.2), so until P10 flips `HILT_RELEASED`:
  * new and existing players keep the window grip, and the chooser is never pushed;
  * the Grip row in Settings (once P8 lands) and `?grip=hilt` let the lead try the hilt grip early,
    knowing the Agoge is window-only until P9.
* **P9** needs P3's `hold:'hilt'` for its demo unit test, P4's `setLock` / `lockOn` for the lock
  and spear steps, and P5's `inputCtl.lastShieldDown` for the grip check. It can be written against
  the contract and merged after P3, P4 and P5.
* **P8's** marker and arrows need P4's `lockTarget` / `lockSides` at runtime (stubbed in its sandbox
  until then). P8 lands the FIELDS rows and the `ui-helpers.test.mjs` change in the same commit.
* Suggested merge order: P2 → P1 → P3 → P4 → P5 → P6 → P7 → P8 → P9 → P10. P10 ends with the
  sweep and the `HILT_RELEASED` flip.
* **Done** for every package means `node --test spartan/tests/unit/*.test.mjs` is green, plus its
  own e2e and `smoke.mjs`.

### I.3 Risks and mitigations

1. **Safety: the phone leaving the hand.** The hilt grip has no pommel; swing loads pull the phone
   out of the top of the fist. Mitigation: nothing is gained above a firm flick (B.3 thresholds),
   the over-speed toast, the safety screen (strap or ring grip, clear the space behind you), and
   wording that never says "throw hard" (G). Real-phone check: the lead plays a Day 1 battle and
   the Agoge spear step with the full-power flick and reports whether the grip ever felt insecure.
2. **Thumb slip during swings** (spurious switches and shield flicker). Mitigation: thumb strokes
   are queued and committed only after 100 ms with the phone calm and no gesture (D); the shield
   dips during strikes anyway; the weapon button is off the thumb's arc. Real-phone check: 30 R/L
   slashes with the thumb down should give 0 switches and 0 swaps.
3. **Yaw-heavy horizontal cuts with an upright blade.** Mitigated in the design by the yaw floor
   (B.1, `yawFloor 0.7`), with `yawShare 1.0` tests. Real-phone check: forearm-sweep R/L at the
   player's natural speed register in the Agoge's slashLR step without changing the sensitivity.
4. **Uppercuts are harder in the hilt grip** (they need a low guard). This is taught explicitly in
   the Agoge, and the Cleave (U then D) stays reachable. Accepted.
5. **Lock-on motion sickness / "the view turned on its own"**. The view never re-targets on its
   own except for an attacker the shield can't cover, behind `lockAuto`, at most once per 3 s,
   never straight back, rate- and acceleration-limited, with no reversals and a dead zone. Volleys
   never turn it. Needs real-phone tuning of `maxRate`/`omega`.
6. **Phase C balance for hilt players**: the hilt shield takes rear volleys without facing them
   (C.7), and rear wind-ups get +0.35 s (C.6). That makes the last stand somewhat easier in the
   hilt grip than in the window grip. Accepted for parity (a hilt player cannot turn his body);
   flagged as an open question for the lead.
7. **Resting fingers on the glass** keep the shield up. Mitigation: the corrected hand model and
   finger strip (D), the Agoge grip check that finds and fixes it per device, and the tip that names
   the cause.
8. **The orientation request is ignored on large screens** (Android 16 large-screen policy,
   foldables) and **web locks fail outside fullscreen**. Mitigation: the rotate prompt, and the
   fact that recognition uses the display's own rotation.
9. **Rotation transitions**: one WebGL resize and a re-layout. Dynamic resolution is quieted for
   2 s, and the Activity is never recreated.
10. **Default grip hilt vs existing tests and players**: the migration keeps existing motion players
   on window; the lib, phonePage and sandbox seeds and the unknown-grip → window rule keep every
   existing script on window; and the default only flips after the P10 sweep. A missed script fails
   loudly.
11. **Narrow FOV hides flankers** (44° across). Mitigation: chevrons and arcs, threat switching,
   side arrows, and the attack-from-behind haptic.
12. **Portrait text overflow on 360-wide phones**: covered by `hilt-shots.mjs` layout assertions at 360×800.
13. **Arity change** of `lockOrientation`: the page always ships inside the same APK as the bridge,
    and the arity is checked by `motion-platform.test.mjs` (parsed from Kotlin) and the fake
    bridges. No mixed-version risk.

### I.4 DESIGN.md sections P10 updates

* §1: motion bullets for both grips.
* §2.1: bridge `lockOrientation(lock, orientation)` and its mapping (`displayLandscape`, LOCKED);
  MainActivity's persisted orientation; the manifest note.
* §3.3: the hilt axes, βabs, A and the yaw floor; the hilt hand model.
* §4: `ctx.applyControls`, `ctx.input.grip`.
* §5.1: `lockOrientation`.
* §5.2: `FRAME_GRIPS`, `setGrip`.
* §5.3: `GRIPS`, the hilt rules (guard learning, deep cock-back, firm-flick thresholds, over-speed),
  the hilt required tests.
* §5.4: synth `hold`, `lean`, the hilt segments, `lower`.
* §6: the `switchTarget` action, the thumb flick swap, `ctx.input.grip`, the hilt layout, strips,
  stroke queue and commit.
* §7: lock-on aim and throw target; the hilt volley rule (a raised shield takes volleys from
  either side) and the hilt rear wind-up grace.
* §9: the new settings, `HILT_RELEASED` / `DEFAULT_GRIP`, and the migration.
* §10: orientation and applyControls in setMode / boot; the sensitivity key in pumpMotion.
* §11: player lock API, ai `timeToStrike` grace, combat `arrowOnPlayer` hilt rule, viewmodel
  portrait, fx `HAPTICS_HILT` / `HILT_SLOWMO` / `lastSwingPower`, hud lock marker and last-strike
  chip, menus grip chooser, hilt safety screen, first-Fight notice and rotate prompt, training
  runtime step list, `HILT_LOCK_STEP` and grip check.
* §12: the `lockOn` event.
* §14: the `grip=` URL parameter.
* §15: the portrait FOV.
* §16: CI note that `ui-helpers.test.mjs` pins the Settings FIELDS.

### I.5 Open questions for the lead

1. **Phase C balance.** In the hilt grip a raised shield takes rear volleys without turning (C.7),
   and rear wind-ups get +0.35 s (C.6). Without them the auto-turn either spins the view every
   other volley or turns too late to block. Do you accept that the last stand is somewhat easier
   in the hilt grip than in the window grip?
2. **Thumb-flick weapon swap.** An upward thumb flick swaps the weapon (D), so the player never has
   to bring the off hand up to the top button. Keep it, or button only?
3. **Release gate.** Should P10 flip `HILT_RELEASED` (hilt becomes the default for new motion
   players, and the chooser appears) only after you have played a hilt build on your phone? Until
   then you can pick it in Settings → Controls → Grip. The risks to check on a real phone are
   I.3 items 1–3 and 7: a secure grip at a full-power flick, no thumb-slip switches, forearm-sweep
   cuts, and no resting finger on the glass.
4. **Strap wording.** The hilt safety screen says a wrist strap or ring grip is "strongly
   recommended". Is that the right strength, or should the hilt grip ask the player to confirm
   they have one?

---

## Review log

Two reviews were checked against the code at the time of writing (feasibility and regression: F1–F18;
player experience: X1–X14). Every finding was verified. Below, **Applied** means the design now
does what the finding asks, **Applied differently** means the problem is fixed another way, and
**Rejected (part)** gives the reason for each part not taken.

**Verified facts used below:**
* CI runs the unit tests before assembling the APK (`.github/workflows/android.yml`).
* `ui-helpers.test.mjs:125-168` pins the FIELDS keys, the motionOnly keys, and the STEPS ids and n.
* `combat-agoge-dory.mjs:27,56` and `review-motion-agoge-spear.mjs:12` use stepIndex 10.
* `training.js:9` ROMAN has 12 entries, `:224` builds the label from it, and `:753` seeds demos
  with `seed: 7 + index`.
* `sandbox-ctx.js:42` has no `grip`, and its storage returns the fallback.
* `phonePage` (`review-device-native.mjs:128`) skips lib.mjs.
* `combat.js:1009/1190/1203/1220` emit volley events without `fromBehind`.
* `ai.js:1862` `clear()` disposes actors without killing them.
* `ai.js:1089/1096`: timeToStrike = windup × 1.15 × 0.9 (Day 3).
* `gestures.js:368-371` applies flips to wh and wv, and `main.js:406` applies them in every grip.
* `input.js:534` `bindButton` calls `stopPropagation` and captures the pointer.
* `main.js:248`: `input.motion` is false while paused, and `main.js:481` renders the vm in pause.
* `player.js:666`: the recenter ease is a yaw writer.
* `NativeBridge.kt:420` uses LOCKED.
* `motion.js:199` is `slashGeometry(dir, s, out, reach, P)`, and `:241` passes P 5th.
* `fx.js:115` dispatches 2-element rows as pulses, and `platform.js:351` drops ms ≤ 0.
* `fx.js:556` `onSwing` stores no power.
* `gestures.js:697` `_learnGuard` snaps on the first settle and ignores everything more than 0.6 away.
* `ai.js:402` archers stand at z −26…−31.
* `hud.js:502/534` puts the closing ring 0.5 m above the head.
* `hud.js:484` READY: a click before 6 s arms it.

The spring was re-simulated: a 35° switch takes 0.60 s, 90° takes 1.02 s, and π to the block arc
takes 0.70 s at the fast rate.

| # | sev | finding | verdict | change, or reason |
|---|---|---|---|---|
| F1 | blocker | ui-helpers.test pins FIELDS/STEPS; CI breaks | Applied | §0, H.1: the "unmodified" claim is dropped for this one file. P8 owns its FIELDS assertions (grip, lockAuto, motionOnly list, a new `hiltOnly` check) and changes them in the same commit as the rows. STEPS stays untouched (see F2). |
| F2 | major | inserting `lock` shifts step indices | Applied | G: `STEPS` keeps its 12 ids; `HILT_LOCK_STEP` is separate; `start()` builds a per-grip runtime list; ROMAN gains XIII. Touch and window keep indices 0–11 and their seeds, so seeding by id is unnecessary. |
| F3 | major | unknown grip → hilt flips the UI sandbox | Applied | A.2: a missing or unknown grip reads as window; the chooser tests `gripChosen === false`; the sandbox files (P2) get `grip: 'window', gripChosen: true`, `?grip=` and an `applyControls` stub. |
| F4 | major | phonePage scripts bypass the lib seed | Applied | H.2: `phonePage({ grip = 'window' })` seed (P1) covers pausemotion, phone and fonts. The design's own list was also wrong: `review-device-memory` and `ai-alloc` never switch to motion. |
| F5 | major | volley event lacks `fromBehind`; combat.js unowned | Applied differently | Volley facing is removed (X9). A raised hilt shield takes volleys from either side, so no payload change is needed. combat.js (`arrowOnPlayer` only) is now owned by P4. |
| F6 | major | lock survives `ai.clear()` as a ghost | Applied | C.1/C.3: `inActors` is checked every frame and on the throw, with a test. Not chosen: making `ai.clear()` kill actors, which would touch every holder of an actor reference. |
| F7 | major | swipe ignores distance | Applied | C.1: rank = \|rel\| + 0.12·max(0, d − 3), and the `sideGap` rule; the worked examples and tests include the Agoge right dummy. |
| F8 | major | threat turn takes as long as the Day 3 wind-up | Applied | C.6: the figure is corrected (0.72 s, not 0.80) and hilt players get a +0.35 s grace on rear wind-ups (ai.js, P4), with a test. **Rejected (part)**: turning on 'engage' before the wind-up. In phase C (40% spawned behind, 4 engaged) it would swing the view constantly, which is what X9 warns about. |
| F9 | major | flips shared across grips | Applied | A.1/A.2: `motionFlipXHilt/YHilt` + `flipKeys`; main, the menus Swap rows and the Agoge use the effective grip's keys. |
| F10 | major | swap button on the thumb arc swallows blocks | Applied differently | D: the button moved to the top corner for the off hand, plus an upward thumb flick. Nothing on the thumb's arc captures pointers, so no shield-on-swap rule is needed. |
| F11 | minor | grip gated on motion; setMode/applyControls desync; recenter yaw writer | Applied | A.6: `ctx.input.grip = effectiveGrip(settings)`, not gated on active; setMode calls `applyControls()`. C.3/C.4: the hilt recenter never starts `recenterLeft`. |
| F12 | minor | HostLogic side choice wrong on natural-landscape tablets | Applied | A.4: `displayLandscape` → LOCKED (today's behaviour), LANDSCAPE only while still portrait. There is no JVM test set in `:spartan`, so H.1 #9 checks the branch order in the Kotlin source. |
| F13 | minor | `slashGeometry`'s 5th parameter is already taken | Applied | E.2: `P.half` in the tuning object; the signature is unchanged. |
| F14 | minor | `[0,15]` never fires; fx stores no swing power | Applied | F.1: `[20, 0.5]`, plus a test that every 2-element row has ms > 0. F.3: `lastSwingPower`. |
| F15 | minor | swipe suppression reads last frame's m | Applied | D: strokes are queued and committed in `inputCtl.update()` after 100 ms with max m ≤ 2.5 and no gesture; the e2e starts the swing 30 ms after the swipe. |
| F16 | minor | orientation lock before fullscreen resolves | Applied | A.5: `requestFullscreen()?.then(applyControls)`. |
| F17 | minor | default hilt lands before the features (CI publishes every push) | Applied | A.2/I.2: `HILT_RELEASED = false` → `DEFAULT_GRIP 'window'` and no chooser until P10 flips it after the sweep. |
| F18 | minor | unlisted sensitivity readers; bracket resized per frame; lockSides literal | Applied | B.3/G: hud.js:605 and training.js readers listed. E.4: four translated corner elements, written on change. C.2: one mutated `lockSides`. |
| X1 | blocker | friction grip, javelin throw, rewards the hardest throw | Applied | B.3: throwPeak 4.5 / throwFull 7.5 / throwMin 3.5, slashPowerSpan 5.5, over-speed `fast` flag and toast. A.6: hilt safety screen (strap or ring, behind you, firm flick, `hiltSafetyAccepted`). G: "snap it forward, fist closed". D: the tip says to rest the thumb on the edge. **Rejected (part)**: the 4 g axial-acceleration trigger. Gyro peak and clipping already catch hard swings, and a linear-acceleration threshold would need its own tuning on web phones without a gravity sensor. |
| X2 | major | small cock-back turns spear chops into throws | Applied | B.3: windupBeta 2.35 (the top pointing back over the shoulder, ≈ 1.25 rad past the guard); the overhead is taught from the guard; armed haptic and sound; false-throw tests at lean 0.2–0.8 and the clamps. **Rejected (part)**: requiring a 0.1 s pause at the top. Depth alone separates the gestures, and a pause would fail fluid throws. |
| X3 | major | guard learned from the tapping pose, never corrected | Applied | B.3.3: 1 s grace, a 0.5 s held guard to snap, re-snap after 2 s away; the Agoge grip step learns the guard explicitly. **Rejected (part)**: a "Raise your guard" beat at every battle start, because re-snap heals within ≈ 2.5 s during the opening card. The snap range stays [0.30, 1.85]: narrowing it breaks the lean 0.8 / grip pitch −0.35 feel case. |
| X4 | major | shield sticks; strips model the hand wrongly | Applied | §1 hand model (fingertips on the edge opposite the thumb). D: finger strip 44 px over the lower 60% (`hiltFingerStrip`, widened by the Agoge grip check), rewritten texts, and a tip that names the cause. H.2: the finger-edge e2e. **Rejected (part)**: a positive "thumb pad only" zone. It would break off-hand blocks and varied thumb reach, while the corrected strips plus per-device widening fix the observed cause. |
| X5 | major | swap button and READY under the thumb | Applied | D: weapon button at the top plus the thumb flick. E.3: READY in a top row at 30%, never fed by a shield pointer. The hold-to-confirm alternative is unnecessary once it is out of reach. |
| X6 | major | swipe ratio rejects natural thumb arcs; silent drops | Applied | D: ratio 0.8 (≈ 51°); strokes are judged on the phone's motion during the stroke, so a swipe after a kill counts once the phone is near guard; e2e at 35°/45°/60°. **Rejected (part)**: a learned thumb-angle frame (0.8 already covers the described arcs), and feedback for strokes dropped mid-swing (they are slips by construction, so a tick would be noise). |
| X7 | major | forearm-sweep cuts read at 20–45% | Applied | B.1: `yawFloor 0.7` in A now (not normalized, so cautious roll cuts keep today's gain); `yawShare 1.0` tests; Agoge hiltCut diagram and wiper hint. I.3 risk 3 no longer needs a fallback. |
| X8 | major | keepRange < switchRange; archers can't be speared | Applied | C.1/C.3: swiped and threat locks are kept to max(22, d + 3) up to 45 m; spear swipes reach 45 m; a test with an archer at 30 m. |
| X9 | major | phase C back-and-forth turning | Applied | C.6: threatGap 3 s, threatReturn 5 s, attack-from-behind haptic. C.7: volley facing removed and a hilt raised shield takes volleys (the old risk-5 fallback, now in scope). H.2: phase C acceptance. **Rejected (part)**: partial blocks for rear melee blows. The +0.35 s grace fixes the timing without a new combat rule. |
| X10 | major | blind feedback weaker than claimed | Applied | F.1: four strike shapes, pulses ≥ 30 ms. E.4: last-strike chip until settled + 0.8 s. F.2: no stereo-pan claim, louder tick, armed cue, earbuds hint. **Rejected (part)**: holding kill slow-motion until guard. Up to 0.8 s per kill would drag Day 3 fights and hand hilt players extra time; the chip and the > 1 s death animation carry the result. |
| X11 | major | Agoge panel and banners cover the closing ring | Applied | E.3: the ring on the head in hilt portrait, the combo popup ≤ 28%. E.5: compact panel in trainer and volley steps, glyph on the finger side. H.2: intersection assertions. |
| X12 | minor | target HP bar under the fist | Applied | E.4: above the bracket. |
| X13 | minor | spear step lock follows the near dummy away | Applied | G: `setLock(farDummy)` when the near dummy steps aside. |
| X14 | minor | first hilt Fight without the Agoge; silent held-up swings | Applied | A.6: `hiltAgogeDone`, the "Agoge first / Fight now" notice and coach toasts. B.3/G: `heldUp` candidates drive the "lower the blade first" hint. **Rejected (part)**: a blade-raise animation and creak for a non-action. It needs a new gesture type through pumpMotion, the viewmodel and audio, for a cue the player can't see mid-swing. |

**Corrections found while verifying** (not raised by either review):
* The hilt `throw` and `raise` default `cockArc` (1.15) would not have satisfied the design's own synth
  test ("past π/2 + 1.0"). With the deeper wind-up it is now 1.5, as `hold: 'hilt'` defaults only.
* The existing `MOTION_TIP` already tells window players to rest the thumb on the frame. The hilt
  texts now agree with it instead of telling players to rest the thumb on the glass.
