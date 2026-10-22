# Thermopylae: design and module contracts

This is the build spec for **Thermopylae**, a first-person 3D Spartan melee game set at the
Battle of Thermopylae (480 BC). It's the contract every module is written against. If you
change an interface, update this file in the same change.

## 1. The game in one paragraph

You are a Spartan hoplite in Leonidas' line at the Hot Gates, the narrow pass between the
cliffs of Mount Kallidromos (on your **left**) and the Malian Gulf (on your **right**). The
Phocian Wall is behind you. Wave after wave of Xerxes' army comes up the pass at you: Median
and Kissian levies, sparabara shield walls, archers whose volleys blot out the sun, the
Immortals, and axe-wielding captains. You fight in first person with a **xiphos** (short
sword) and a **dory** (spear), behind a bronze **aspis** (round shield with the Λ of
Lacedaemon). You can play with **touch controls**, or with **motion controls**: you swing the
phone like a sword, push it forward to stab, hold your thumb on the screen to raise the
shield, push forward with the shield up to **shield bash**, and make a real overhand
**throwing** motion to throw the spear. Then you look back at the phone and follow the spear
in slow motion until it hits. The campaign runs over three days and ends with the last stand
on Day 3.

The game should look and feel **realistic**. That means physically lit 3D (sun, sky,
environment reflections on bronze, fog/haze), procedural but detailed textures, historically
grounded gear, and weighty combat: hit-stop, camera shake, haptics, layered sound, and
anticipation and follow-through in animation. It has no cartoon look and no film-specific
characters, quotes or likenesses (it is *not* the movie "300"). It uses history instead:
Herodotus, Plutarch and Simonides.

## 2. Tech stack and layout

* An Android app module `:spartan`: a full-screen `WebView` that serves the game from APK
  assets on a secure origin (`https://appassets.androidplatform.net/`) and exposes a native
  bridge for motion sensors, haptics and text-to-speech.
* The game is plain browser JavaScript (ES modules, **no build step**) on **three.js r186**,
  vendored in `assets/game/lib/three/` and resolved through an import map
  (`"three"` and `"three/addons/"`). Don't edit the vendored files.
* The same `assets/game/` folder runs in any modern browser. On desktop it uses keyboard and
  mouse. On a phone browser it uses `DeviceMotionEvent` for motion control.

```
spartan/
  DESIGN.md                      this file
  build.gradle.kts               Android module (WebView host)
  src/main/AndroidManifest.xml
  src/main/java/com/thermopylae/ MainActivity.kt, AssetServer.kt, NativeBridge.kt
  src/main/res/                  strings, vector launcher icon
  src/main/assets/game/
    index.html                   import map, <canvas>, #overlay root, loads js/main.js
    style.css                    all HUD/menu styling
    lib/three/                   vendored three.js (module + core + addons). DO NOT EDIT.
    js/
      main.js                    bootstrap, ctx, game loop, mode state machine (lead)
      core/events.js             EventBus (lead)
      core/math.js               pure helpers + seeded RNG (lead)
      core/noise.js              pure value/simplex noise + fbm
      platform.js                native bridge or web fallbacks: motion, haptics, voice, storage, lifecycle
      motion/frames.js           PURE: device→screen remap, gravity/linear-acc estimation
      motion/gestures.js         PURE: GestureRecognizer (swing, thrust, throw, gyro look)
      motion/synth.js            PURE: kinematic phone-motion synthesizer (tests + demos)
      game/rules.js              PURE: timings, weapon stats, damage, hit geometry, ballistics
      game/combos.js             PURE: ComboTracker + COMBOS table
      game/player.js             PlayerController
      game/combat.js             attack resolution, blocking, projectiles (spears, arrows, volleys)
      game/ai.js                 enemy and ally brains, spawning, corpses
      game/waves.js              campaign: days, waves, respites, voice lines
      gfx/textures.js            procedural canvas textures
      gfx/world.js               terrain, cliffs, sea, sky, lighting, wall, distant army, props
      gfx/characters.js          actor models + procedural animator
      gfx/viewmodel.js           first-person arms, sword, spear, shield
      gfx/fx.js                  particles, decals, shake, hit-stop, slow-mo, spear cam
      audio/audio.js             WebAudio synthesized SFX, ambience, music
      ui/hud.js                  in-battle HUD
      ui/menus.js                title, settings, pause, game over, how-to, safety notice
      ui/input.js                touch + keyboard/mouse → actions and continuous input
      ui/training.js             the Agoge: guided gesture training and calibration
  tests/
    unit/*.test.mjs              `node --test` on the PURE modules (no DOM, no three)
    e2e/*.mjs                    Playwright scripts (local; headless Chromium + SwiftShader)
```

"PURE" modules import nothing but other pure modules. They must run under Node 20+ with no
DOM, so the unit tests can load them directly.

## 3. Conventions

### 3.1 World
* Units: metres, seconds, radians, kilograms. Y is up. Gravity is 9.81.
* The Spartan line stands along **z = 0**. The player starts at `(0, 0, 0)` (feet) and faces
  **-Z** (toward the Persians). The Persians come from **-Z**, spawning around z = -35…-60,
  and the distant horde reaches z = -400.
* The cliff (Mount Kallidromos) rises on the **left (−X)** from about x = -9. The sea (Malian
  Gulf) is on the **right (+X)**: ground drops at about x = +10 to rocks and water at y ≈ -3.
  The playable pass floor is about x ∈ [-8, 9]. The Phocian Wall runs across the pass at
  z ≈ +12.
* Yaw: `yaw = 0` faces -Z. **Positive yaw turns left** (counter-clockwise seen from above),
  the same as `camera.rotation.y`. A direction from yaw is `(-sin yaw, 0, -cos yaw)`.
  Pitch is positive when looking up.
* Actor models face **+Z in local space**, and `root.rotation.y = facing`. Use
  `facingTo(dx, dz) = Math.atan2(dx, dz)`. A Persian at z = -10 facing the player has
  `facing ≈ 0`, and the player at yaw 0 faces it.
* Heights: eye height is 1.66 m. Actors are 1.70–1.85 m tall, and their capsule radius is 0.32.

### 3.2 Time
* `ctx.time.dt` is the real frame delta, clamped to ≤ 0.05 s.
* `ctx.time.gdt` is the game delta, `dt * scale`, or 0 during hit-stop.
* Gameplay, AI, animation and particles use `gdt`. UI, input and sensors use real time.
  Sensor samples carry their own timestamps.
* `ctx.time.scale` is set only through `fx.slowMo()`, and `hitStop` only through
  `fx.hitStop()`.

### 3.3 Screen-frame motion axes (important)
Sensors are remapped from the device frame to the **screen frame** of the current display
rotation:
* `+x` = toward the right edge of the screen as the user sees it
* `+y` = toward the top edge of the screen as the user sees it
* `+z` = out of the screen, toward the user's face

The **blade axis** is `b = (0, 0, -1)` in the screen frame: an imaginary sword sticks out of the
back of the phone into the scene. The player holds the phone in landscape with the screen
facing them, "looking through it", and the blade points where they look.

Remap: for display rotation `r` (degrees, `0|90|180|270`, the same value as
`screen.orientation.angle` and Android's `Display.getRotation()*90`), a device-frame vector
`(dx, dy, dz)` becomes `sx = cos r·dx − sin r·dy`, `sy = sin r·dx + cos r·dy`, `sz = dz`.
(Check: rotating the phone CCW so its top edge goes to the left gives r = 90, and the device's
+x then points to screen-up.)

Gravity convention: `up` is the unit vector **opposite to gravity** (the direction an
accelerometer at rest reads as positive, i.e. Android `TYPE_GRAVITY` normalised). Some
browsers (older iOS Safari) report `accelerationIncludingGravity` with the opposite sign.
`frames.js` auto-detects this: when the device is calm and `up.y` is consistently below −0.5
for over 1 s while in landscape play, it flips the sign, because players hold the screen
roughly upright.

### 3.4 Slash direction
A slash direction `dir` is the **direction the blade moves across the view**, in screen
space, as an angle: `0` = rightward, `π/2` = upward (uppercut), `±π` = leftward,
`−π/2` = downward (overhead chop). Eight-way tokens (`rules.dirToken(dir)`):
`R, UR, U, UL, L, DL, D, DR`, each covering a 45° sector centred on its angle.

## 4. Shared context `ctx` (built in main.js)

```js
ctx = {
  THREE,                       // three.js namespace
  renderer, scene, camera,     // main scene; PerspectiveCamera(fov 70, near 0.05, far 2000)
  vm: { scene, camera },       // viewmodel scene + camera (fov 60, near 0.01, far 10), rendered after a depth clear
  events,                      // EventBus: on(name, fn) → off(), once, emit(name, payload)
  settings,                    // live settings object (see §9); persisted by platform.storage
  saveSettings(),              // persist ctx.settings
  platform,                    // see §5.1
  time: { now, dt, gdt, scale, hitStop },   // now = real seconds since start
  rng,                         // core/math.js createRng(seed): next() [0,1), range(a,b), int(a,b), pick(arr), chance(p)
  quality: 'low'|'medium'|'high',
  input,                       // continuous InputState (see §6)
  actions,                     // ActionQueue: push(a), drain() → array (player drains once per frame)
  mode,                        // 'title'|'training'|'battle'|'paused'|'gameover'
  setMode(mode, opts),         // main.js state machine
  // module instances (created in this order; later modules may use earlier ones):
  textures, world, characters, fx, audio, viewmodel, combat, ai, player, waves, hud, menus, inputCtl, training,
  gestures,                    // GestureRecognizer instance (motion control)
  actors: [],                  // every live Actor (enemies, allies, dummies), owned by ai.js
  overlay,                     // the #overlay DOM element (UI root)
}
```

Every non-pure module exports `createX(ctx)` and returns an object. Modules that need
per-frame work expose `update(dt, gdt)`. main.js calls them in a fixed order each frame (§10).
Modules **subscribe to events in their constructor** so other modules don't need to call
them. For example, audio plays a clang on `block` and fx sprays blood on `hit`.

## 5. Platform, motion and gestures

### 5.1 platform.js
```js
createPlatform() → {
  kind: 'android' | 'web',
  motion: {
    isSupported(): Promise<boolean>,   // gyroscope present (android bridge, or a DeviceMotionEvent with rotationRate seen)
    requestPermission(): Promise<boolean>, // iOS 13+ DeviceMotionEvent.requestPermission on a user gesture; true elsewhere
    start(), stop(),
    poll(): RawSample[],               // all samples since the last poll, oldest first
    inject(samples: RawSample[]),      // tests: queue synthetic samples (works with or without real sensors)
    rate(): number,                    // measured sample rate (Hz)
  },
  haptics: { pulse(ms, strength = 1), pattern(arrayOfMs) },  // native Vibrator, else navigator.vibrate
  voice: { say(text, {pitch, rate}) },  // native TTS, else speechSynthesis; respects settings.voice
  storage: { load(key, fallback), save(key, value) },     // JSON in localStorage
  onPause(fn), onResume(fn), onBack(fn),  // lifecycle hooks; onBack returns true if handled
  exit(),                                // android: finish(); web: no-op
}
```
`RawSample` (device frame, before remap):
`{ t: seconds (monotonic), gyro: [x,y,z] rad/s, acc: [x,y,z]|null (linear, gravity removed),
accG: [x,y,z]|null (including gravity, +up at rest), grav: [x,y,z]|null (gravity reaction),
rot: 0|90|180|270 }`.
The web path converts `rotationRate` from deg/s to rad/s (`alpha` → z, `beta` → x,
`gamma` → y).

The Android bridge object is `window.SpartanNative`. All methods are synchronous
`@JavascriptInterface` calls:
`motionAvailable(): boolean`, `startMotion()`, `stopMotion()`,
`pollMotion(): string` (one sample per line:
`t,gx,gy,gz,ax,ay,az,gvx,gvy,gvz,rot`; `ax..az` and `gvx..gvz` are `NaN` when the sensor is
missing; rot is degrees), `vibrate(ms, amplitude0to255)`, `vibratePattern("0,30,40,30")`,
`say(text, pitch, rate)`, `stopSpeech()`, `rotation(): int` (degrees), `exit()`.
Lifecycle: native dispatches `window` events `nativepause` / `nativeresume`. On Back, native calls
`window.__onBack()`. If that returns `false`, the activity finishes.

### 5.2 motion/frames.js (pure)
```js
export function remapToScreen(v, rotDeg) → [x,y,z]
export class MotionFrames {
  constructor({ gravityTau = 0.25 })
  process(raw: RawSample) → Sample   // screen frame
  reset()
  get gravitySign()                  // +1 or -1 (auto-detected, §3.3)
}
// Sample: { t, w:[x,y,z] rad/s, a:[x,y,z] m/s² linear, up:[x,y,z] unit, calm: bool }
```
If `raw.grav` is missing, estimate it with a low-pass filter of `accG`. If `raw.acc` is missing,
use `accG − gravity`. `calm` means |w| < 0.6 rad/s and |a| < 1.5 m/s².

### 5.3 motion/gestures.js (pure)
```js
export const GESTURE_DEFAULTS = { /* every threshold below, tunable */ }
export class GestureRecognizer {
  constructor(opts = { sensitivity: 1, weapon: 'sword', lookDelay: 0.1 })
  setSensitivity(s)        // 0.5 (needs big moves) … 2.0 (twitchy); thresholds divide by s
  setWeapon('sword'|'spear')
  push(sample: Sample) → GestureEvent[]   // feed every sample in time order
  consumeLook() → { yaw, pitch }          // delayed gyro-look deltas (radians), then zeroed
  get debug()                              // { rate, peak, phase, lastEvent, yawRate, pitchRate, fwdAcc }
}
// GestureEvent:
//   { type:'slash',  dir, power 0..1, peak (rad/s), sweep (rad), t }
//   { type:'thrust', power 0..1, peak (m/s²), t }
//   { type:'throw',  power 0..1, peak (rad/s), t }
```
The algorithm. These numbers are defaults at sensitivity 1; tune them against `synth.js` and
keep them in `GESTURE_DEFAULTS`.
* **Rates in a gravity-aligned frame.** `u = sample.up`. `yawRate = w·u` (positive = blade
  moving left). Right axis `r = normalize(b × u)` with `b = (0,0,-1)`, and `pitchRate = w·r`
  (positive = blade tip moving up). If `|b × u| < 0.35` (phone held flat), use
  `r = (1,0,0)`. Ignore roll. Planar rate `m = hypot(yawRate, pitchRate)`.
* **Swing.** Start when `m > 3.5`. Integrate `dx += −yawRate·dt` and `dy += pitchRate·dt`.
  **Fire early**: emit the slash as soon as `hypot(dx,dy) ≥ 0.45 rad` and `peak ≥ 5.0`. Don't
  wait for the swing to end, so latency is ≈ 60–100 ms. Then `dir = atan2(dy, dx)` and
  `power = clamp((peak − 5) / 10, 0, 1)`. Update `power` from the eventual peak for display
  only. The swing ends when `m < 1.5` for 40 ms or after 0.7 s. One swing gives at most one
  event.
* **Recoil suppression.** After a swing ends, ignore a new swing whose direction is within 45°
  of the reverse direction if it starts within 0.13 s, since that is the arm bouncing back.
  Deliberate back-and-forth combos (≥ 0.15 s apart) still register.
* **Thrust.** Forward acceleration `f = a·b = −a.z`. Fire when `f > 12 m/s²`, no swing is in
  progress, and `m < 4`. Use the peak within 60 ms for power, `clamp((peak−12)/18, 0, 1)`.
  Refractory period is 0.35 s, which skips the deceleration spike.
* **Throw** (only when weapon = spear). A swing with `dir` within ±55° of straight down and
  `peak ≥ 7` becomes `throw` instead of `slash`, with `power = clamp((peak−7)/11, 0, 1)`. Its
  early-fire point is the same.
* **Gyro look.** While no swing is active and `m < 2.2`, accumulate `yaw += yawRate·dt` and
  `pitch += pitchRate·dt` after a 0.03 rad/s dead-zone, into a FIFO that releases deltas
  `lookDelay` seconds later. When a swing starts, **discard everything still in the FIFO**, so
  the run-up to a swing never jerks the camera.

### 5.4 motion/synth.js (pure)
A rigid-body phone simulator used by unit tests, e2e tests and the training demo.
```js
export function synthesize(script, opts) → RawSample[]
// script: array of segments, played back-to-back:
//   { kind:'idle', duration }                                 hand tremor only
//   { kind:'swing', dir, speed = 9 (peak rad/s), arc = 1.6 (rad), radius = 0.55 (m) }
//   { kind:'thrust', distance = 0.35, duration = 0.18 }
//   { kind:'throw', speed = 14 }                              wind-up behind the head, then overhand release
//   { kind:'turn', yaw, pitch = 0, duration }                 slow body turn (gyro look)
//   { kind:'walk', duration }                                 bobbing noise; must NOT trigger gestures
// opts: { rate = 100, rot = 90, tilt = 0 (rad, phone pitched back from vertical), roll = 0,
//         noise = 1 (gyro 0.02 rad/s, acc 0.15 m/s² σ, scaled), seed = 1, gravitySign = 1,
//         includeLinear = true, includeGravity = true, t0 = 0 }
```
It models a pose `p(t)`, `q(t)` (the hand moves on an arc around the shoulder or elbow, with the
phone's blade axis tangent to the motion). It differentiates `q` to get the body-frame angular
velocity, and `p` to get acceleration, rotates world acceleration and gravity into the device
frame, then applies the inverse screen rotation, so the samples look like what a phone in
display rotation `opts.rot` reports. It is pure math with its own tiny quaternion helpers and
no three.js.

## 6. Actions and input

Actions are discrete commands, pushed to `ctx.actions` by `ui/input.js` (touch, keys,
mouse) and by main.js's gesture glue (motion):
```js
{ type:'slash', dir, power, src }        // src: 'motion'|'touch'|'key'|'test'
{ type:'thrust', power, src }            // with shield held → becomes a bash; with spear → spear thrust
{ type:'bash', power, src }              // explicit bash (touch button / key)
{ type:'throw', power, src }             // spear throw (needs spear weapon + a spear)
{ type:'switchWeapon', weapon?, src }    // toggle or set 'sword'|'spear'
{ type:'pause', src }
{ type:'recenter', src }                 // snap view back to straight ahead
```
Continuous `ctx.input` (owned by ui/input.js; the gesture glue adds gyro look):
```js
{ moveX, moveZ,          // -1..1 strafe right+/forward+ (joystick, WASD)
  lookYaw, lookPitch,    // accumulated radians this frame; player.js consumes and zeroes them
  shieldHeld,            // true while the shield is held (touch-hold in motion mode, shield button, RMB/Shift/K)
  firing: false }        // reserved
```
**Motion-mode touch layout.** Touching and holding anywhere on the screen raises the shield
(one thumb on the phone you're swinging). Small buttons sit at the corners: **weapon swap**
(top-right, sword/spear icon with the spear count) and **pause** (top-left). A two-finger tap
recenters the view.

**Touch-mode layout.**
* Left thumb: a virtual stick (bottom-left) moves the player. A big **shield** button sits
  above it. Hold it to block, and swipe up from it (or tap the **BASH** button next to it) to
  bash.
* Right half: a **fast swipe** slashes in the swipe's direction (screen-y inverted so swiping
  up gives an upward slash), a **tap** stabs, and a **slow drag** looks around.
* With the spear selected, a fast **upward flick** that starts in the lower right throws it.
* Buttons: weapon swap, pause.

**Keyboard and mouse** (desktop or tests). WASD moves and the mouse looks (pointer lock).
Left-drag slashes in the drag direction, and a quick left click thrusts. RMB/Shift/K holds the
shield, F bashes, Q swaps weapon, R or MMB throws, Esc or P pauses. Arrow keys slash
(←→↑↓), and Space thrusts.

## 7. Gameplay rules (game/rules.js holds the numbers)

```js
export const TIMING = {           // seconds; strike = when damage resolves, total = until next attack allowed
  slash:       { strike: 0.10, total: 0.34, cancelAfter: 0.20 },
  thrust:      { strike: 0.11, total: 0.38, cancelAfter: 0.22 },
  spearThrust: { strike: 0.13, total: 0.44, cancelAfter: 0.26 },
  bash:        { strike: 0.09, total: 0.45, cancelAfter: 0.30 },
  throw:       { windup: 0.14, release: 0.14, total: 0.60 },
  shieldRaise: 0.14, shieldLower: 0.18, perfectBlockWindow: 0.22,
}
export const WEAPONS = {
  sword: { reach: 2.1, slashDamage: 34, thrustDamage: 40 },
  spear: { reach: 3.0, slashDamage: 22, thrustDamage: 55, throwDamage: 200, throwSpeed: [20, 34] },
  shield:{ reach: 1.6, bashDamage: 8, bashKnockback: 1.6, bashStagger: 1.1 },
}
export function dirToken(dir) → 'R'|'UR'|'U'|'UL'|'L'|'DL'|'D'|'DR'
export function slashProfile(dir) → { token, category:'horizontal'|'vertical'|'diagonal', halfAngle, damageMul, overShield }
//   horizontal: halfAngle 70°, damageMul 1.0; diagonal: 45°, 1.15; vertical: 22°, 1.35.
//   D (overhead) has overShield true; it goes over wicker shields.
export function sectorHit(origin, yaw, reach, halfAngle, target, radius) → { hit, dist, angle }
export function segmentCapsule(p0, p1, a, b, r) → t∈[0,1] | -1
export function ballisticPitch(speed, dxz, dy, g = 9.81) → pitch (low arc) | null
export function computeDamage({ base, power, comboMul = 1, crit = false, blocked = false }) → number
//   base·(0.65 + 0.7·power)·comboMul·(crit ? 1.6 : 1), and ·0.15 if blocked
```
* **Attacks.** The player attacks on an action unless locked (stagger, throw, death). An
  action that arrives after `cancelAfter` of the current attack is buffered and runs when the
  current one ends (buffer of 1, newest wins, discarded after 0.4 s).
* **Aim assist.** The focus target is the hostile actor that minimises
  `angleFromViewCentre + 0.15·distance` within 40° and `reach + 1.5`. During an attack's
  first 0.1 s the view yaw is pulled up to 12° toward it, if `settings.aimAssist` is on.
  Throws use 15° and solve the ballistic pitch to the target's chest.
* **Blocking.** `shieldRaise ≥ 0.7` blocks melee from within ±75° of the view direction and
  arrows from anywhere in front or above. A shield raised within `perfectBlockWindow` before
  the hit is a **perfect block**: the attacker staggers, a parry spark flashes, and the next
  player hit within 1 s is a critical. Blocks cost no health, but heavy (captain axe) blows
  push the view.
* **Bash.** A thrust with the shield held, or an explicit bash. It reaches 1.6 m in a 50°
  cone. Hit enemies stagger for 1.1 s, are knocked back 1.6 m, lose their guard, and get knocked
  down if `power > 0.75`. An attack being wound up is interrupted.
* **Enemy guard.** Levies 25%, sparabara 70% (big wicker shield, so you must bash, chop
  overhead or flank), Immortals 45%, captains 30%. A guarded hit from the front deals 15%
  damage and makes a wicker or wood sound. Overhead `D`, thrusts with power > 0.8, and any
  hit on a staggered enemy ignore guard.
* **Health.** Player has 100 HP. Hits deal: levy 10, sparabara 12, Immortal 16, captain axe
  28 (unblockable unless perfect, or it breaks the guard), arrow 8. There is no regen in a
  wave, and each respite heals 35%. Low HP shows a blood vignette and a heartbeat.
* **Spears.** The player has 1 dory in hand plus `spares` (starts at 2, max 3). Each respite
  refills them: a helot runs up from behind. Throwing uses the one in hand. If spares remain, a
  new one is drawn after 0.6 s, otherwise the weapon becomes the sword. A thrown spear that
  misses sticks in the ground. A powerful throw (power > 0.7) can skewer two enemies.
* **Score.** Kill = 100 × chain multiplier. The chain grows by 0.1 per hit and resets when you
  are hurt. Combos give bonus points, and so do perfect blocks (50) and spear kills (250).

## 8. Combos (game/combos.js)

```js
export const COMBOS = [ { id, name, seq: [tokenSet...], mult, effect, desc } ... ]
export class ComboTracker {
  constructor(table = COMBOS, window = 1.0)
  push(token, t) → combo | null      // token: dirToken() | 'T' (thrust) | 'B' (bash) | 'J' (throw)
  reset()
  get chain()                        // recent tokens
}
```
Each step of a `seq` is a set of acceptable tokens, so a sloppy diagonal still counts as
horizontal. The longest matching combo wins, and completing one resets the chain. Use this
table (tune numbers if needed):

| id | name | sequence | effect |
|---|---|---|---|
| `twin` | Twin Fangs | R-ish, L-ish (either order) | 2nd hit ×1.5 |
| `fury` | Fury of Lakedaimon | R, L, R or L, R, L | `sweep`: final blow hits every hostile in a 100° arc, ×1.8 |
| `cleave` | Leonidas' Cleave | U, D | `cleave`: ×2.2, slow-mo 0.35 for 0.5 s, breaks guard |
| `answer` | The Hoplite's Answer | B, T | `execute`: thrust on a staggered enemy kills it |
| `drive` | Phalanx Drive | T, T, T | `drive`: ×1.6 and knocks the target back 2.5 m |
| `xcut` | Cross of Ares | DR, DL or DL, DR | `bleed`: ×1.7 + target bleeds 6 dmg/s for 3 s |
| `wall` | Shield Wall | B, B | `stagger_all`: 2nd bash staggers everything within 2.5 m |
| `storm` | Storm of Bronze | L, R, L, R or R, L, R, L | `sweep` ×2.4 + brief slow-mo |

The `combo` event fires on completion. The player applies the effect to the attack that
completed the combo.

## 9. Settings (persisted under `thermopylae.settings`)

```js
{ control: 'touch',          // 'motion' | 'touch' (motion is offered only if supported)
  sensitivity: 1.0,          // 0.5–2.0
  gyroLook: true,            // motion mode: turning the phone slowly turns the view
  aimAssist: true,
  leftHanded: false,         // mirror touch layout
  haptics: true, voice: true, blood: true, helmetView: true,
  quality: 'auto',           // 'auto'|'low'|'medium'|'high'
  master: 0.9, music: 0.55, sfx: 1.0,
  sensorDebug: false }
```
Best score and highest day are stored under `thermopylae.best` as `{ score, kills, day }`.

## 10. Frame order (main.js)

```
1  platform.motion.poll() → frames.process → gestures.push → actions / ctx.input.look*   (motion mode)
2  inputCtl.update(dt)
3  if mode is battle or training:
     player.update(gdt)        (drains ctx.actions, moves, attacks → combat)
     ai.update(gdt)            (brains + actor animators)
     combat.update(gdt)        (projectiles, volleys, bleed)
     waves.update(gdt) or training.update(dt)
4  viewmodel.update(dt, gdt)   world.update(dt, gdt)   fx.update(dt, gdt)
5  camera: player.applyCamera(camera), then fx.applyCamera(camera) (shake / spear-cam override)
6  hud.update(dt)
7  render: renderer.render(scene, camera); if !fx.cameraOverridden: clearDepth + render(vm.scene, vm.camera)
```

## 11. Module APIs

### core/events.js
`export class EventBus { on(name, fn) → off; once(name, fn); emit(name, payload) }`

### core/math.js
`clamp, lerp, invLerp, smoothstep, damp(a, b, lambda, dt), wrapAngle, angleDiff(a,b),
createRng(seed)`, plus small `[x,y,z]` vector helpers (`v3add, v3sub, v3scale, v3dot,
v3cross, v3len, v3norm`).

### gfx/textures.js
`createTextures(ctx) → { get(name) → THREE.Texture }`. Lazy and cached; canvas-generated;
`SRGBColorSpace` for colour maps; repeat wrapping. The names are `dirt`, `dirtNormal`, `rock`,
`rockNormal`, `cliffStrata`, `sand`, `bronze`, `bronzeNormal`, `linen`, `crimson` (cloak wool), `leather`,
`wood`, `wicker`, `wickerNormal`, `persianRobeA/B/C` (Achaemenid patterns: rosettes, stepped
merlons, stripes), `scale` (scale armour), `skinGreek`, `skinPersian`, `stone` (Phocian wall
masonry), `water`, `waterNormal`, `cloud`, `blood` (decal alpha), `smoke` (sprite), `spark`
(sprite), `lambda` (shield emblem). Quality `low` halves the resolution.

### gfx/world.js
```js
createWorld(ctx) → {
  groundHeight(x, z) → y,            // walkable floor height (fast; used by projectiles, actors, decals)
  bounds: { minX, maxX, minZ, maxZ },// playable rectangle for the player (x ∈ [-7, 8], z ∈ [-6, 3])
  colliders: [...],                  // simple shapes for projectiles: {type:'plane'|'box', ...}
  sunDirection: THREE.Vector3,       // unit, towards the sun
  envMap,                            // PMREM texture for bronze/metal (also set as scene.environment)
  setTimeOfDay(phase),               // 'morning'|'noon'|'afternoon'|'dusk'|'dawn' — sky, sun colour/angle, fog, hemi, exposure
  setArmyDensity(0..1),              // distant horde visibility
  setVolleyShade(0..1),              // arrows blot out the sun: dims the sun and shadows during a volley
  update(dt, gdt),                   // water, steam from the hot springs, banners, horde sway, shadow camera follows ctx.camera
}
```
The world contains:
* A rugged cliff wall with strata and scree on the left.
* An uneven dusty floor with rocks and tufts of dry grass.
* A drop to a rocky shore and an animated sea on the right, with sun glints and a distant far
  shore.
* Sulphur hot-spring vents with steam and white-orange mineral stains (the "hot gates").
* The Phocian Wall behind the player.
* Distant mountains, and the Persian horde filling the far pass (InstancedMesh soldiers with
  spears, standards, dust).
* A Preetham `Sky` (three/addons/Sky.js) and a PMREM env map generated from it.
* One shadow-casting directional sun with a 1024/2048 map that follows the player, a hemisphere
  light, and exponential fog for haze.
Merge static geometry and keep draw calls low.

### gfx/characters.js
```js
createCharacters(ctx) → {
  create(kind, opts = {}) → Visual,   // kind: 'spartan'|'levy'|'sparabara'|'immortal'|'archer'|'captain'|'dummy'|'helot'
  KINDS                               // per kind: { height, weapon, shield, colours… }
}
Visual = {
  root,                               // THREE.Group; add to ctx.scene; feet at origin; faces +Z
  kind,
  sockets: { head, chest, rightHand, leftHand, back },  // Object3Ds to attach things to (stuck spears/arrows)
  anim: {
    setLocomotion(speed),             // m/s; 0 = combat idle stance (breathing, weight shift); blends walk/run cycles
    setGuard(on),                     // raise the shield / weapon guard (upper body layer)
    play(clip, { duration, onStrike, onEnd }),  // one-shot clip; onStrike fires at CLIPS[clip].strikeAt·duration
    flinch(dirX, dirZ, strength),     // additive hit reaction, decays ~0.3 s
    get clip(), get playing(),
    update(gdt),
  },
  attach(object3d, socketName, localPos, localQuat), // e.g. stuck spear/arrow
  setBloodied(0..1),                  // darken/redden clothing a bit as they take damage (optional)
  dispose(),
}
export const CLIPS = {                 // name → { strikeAt (0..1), loop:false, layer:'full'|'upper' }
  thrust, overhead, slash, axe, bash, shoot, hit, stagger, knockdown, getup,
  die_back, die_forward, die_crumple, die_spear (pinned, falls back), cheer, block_hit, throw }
```
Procedural, articulated humanoids: a hierarchy of pelvis, spine, chest, neck, head, shoulders,
upper and lower arms, hands, thighs, shins and feet. They are built from lathe, capsule and box
geometry with good proportions. Share geometries and materials between instances, and keep
each visual at ≤ 14 meshes. They need believable idle and walk cycles, and attacks with
anticipation, a strike and follow-through. Death clips end in a lying pose that stays as a
corpse. Historical kit:
* **Spartan**: Corinthian helm with a crest, crimson cloak, linothorax, bronze greaves, aspis
  with Λ, dory or xiphos.
* **Levy (Mede/Kissian)**: soft felt cap, sleeved tunic and trousers, quilted coat, wicker
  shield, short spear.
* **Sparabara**: a tall rectangular wicker spara shield, short spear.
* **Immortal**: patterned robe or scale coat, headband or tiara, spear with a gold/silver
  pomegranate butt, bow case on the back.
* **Archer**: bow and quiver.
* **Captain**: taller, scale armour, sagaris axe.
* **Dummy**: a training post with a crossbar and a wicker shield, which doesn't animate except
  for wobbling on hit.
* **Helot**: a plain tunic, carrying a bundle of spears.

### gfx/viewmodel.js
```js
createViewmodel(ctx) → {   // populates ctx.vm.scene (own lights mirroring the sun + env map)
  setWeapon('sword'|'spear'),  setHasSpear(bool),
  setShield(raise 0..1),                 // player passes its animated value every frame
  play(kind, opts),                      // 'slash' {dir, power} | 'thrust' {power} | 'spearThrust' {power}
                                         // | 'bash' {power} | 'throwWindup' | 'throwRelease' | 'blockImpact' {perfect}
                                         // | 'hurt' | 'draw' (new spear / weapon swap)
  addStuckArrow(), clearStuckArrows(),   // arrows stuck in the aspis (max 8)
  getHandWorldPosition(target: Vector3), // right hand in world space (spear spawn point)
  setVisible(bool),
  update(dt, gdt),                       // sway from ctx.input look, walk bob, breathing
}
```
* The right hand holds the xiphos or the dory (overhand grip). The left arm carries the aspis,
  which rests low-left and partly in view.
* Raised, the aspis covers the lower-left/centre of the view, and you look over its rim.
* A slash visibly sweeps the blade across the screen **along `dir`**, timed so the blade
  crosses the centre at `TIMING.slash.strike`, with a motion-trail ribbon.
* A bash punches the shield forward. A throw draws the spear back over the shoulder and
  whips it forward.

### gfx/fx.js
```js
createFx(ctx) → {
  blood(point, dir, amount), sparks(point, normal, amount), dust(point, amount),
  splinters(point, dir, amount), steam?…,
  decal(point, kind = 'blood', size),          // ground decals (blood pools), capped and recycled
  shake(intensity, duration), hitStop(seconds), slowMo(scale, duration),
  spearCam(projectile),                        // cinematic follow; ends ~0.9 s after impact
  get cameraOverridden(),
  applyCamera(camera),                          // adds shake; or drives the camera in spear-cam
  update(dt, gdt),
}
```
Particles are pooled (≤ 800) in a few `Points`/`InstancedMesh` draw calls. Blood obeys
`settings.blood`. fx subscribes to `hit`, `block`, `kill`, `spearImpact`, `playerHurt` and
`arrowHit` to spray the right effects, and does hit-stop: 45 ms on a hit, 80 ms on a kill or
critical, 30 ms on a block.

**Spear cam.** On `spearThrown`, slow motion goes to 0.3 for the first 0.45 s of flight, so
the player has time to bring the phone back in front of their eyes, then 0.55. The camera
trails 1.6 m behind and 0.35 m above the spear, looking along its velocity. On impact it
freezes for 0.12 s, then slowly orbits the impact for 0.8 s and eases back to the player's
eyes over 0.4 s. The viewmodel is hidden while the spear cam is active.

### audio/audio.js
```js
createAudio(ctx) → {
  unlock(),                                 // call from the first user gesture
  play(name, { volume = 1, rate = 1, at: Vector3|null }),   // `at` → stereo pan + distance falloff relative to the camera
  setAmbience(kind),                        // 'battle'|'calm'|'none' — wind, sea, distant army din
  setMusic(kind),                           // 'title'|'battle'|'lastStand'|'none'
  applyVolumes(),                           // from ctx.settings
  update(dt),
}
```
Everything is synthesized with WebAudio at startup: no audio files. SFX names are `whoosh`,
`whooshHeavy`, `clang`, `parry`, `flesh`, `fleshHeavy`, `wicker`, `bash`, `spearThrow`,
`spearHit`, `spearGround`, `volley` (rising hiss of thousands of arrows), `arrowThud`,
`arrowShield`, `hurt`, `death`, `grunt`, `warcry` (Greek "alala!"), `horn` (Persian
trumpet), `drum`, `comboSting`, `heartbeat`, `step`, `uiTap`, `uiConfirm`. Bronze sounds are
inharmonic modal partials, and flesh hits are low thumps plus noise. The music is a sparse
Dorian aulos-and-drum war theme and a low drone for the last stand. audio subscribes to bus
events itself.

### game/combat.js
```js
createCombat(ctx) → {
  playerStrike(attack) → hits[],   // attack: { kind:'slash'|'thrust'|'spearThrust'|'bash', dir?, power, combo?, origin, yaw }
  enemyStrike(actor, attack),      // resolves vs player (blocking) or an ally in front of the actor
  throwSpear({ origin, velocity, power }) → projectile,
  volley({ count, duration, origin }),   // archers' arrow storm toward the Greek line
  shootArrow(fromActor, target),
  hostilesInSector(origin, yaw, reach, halfAngle) → actors sorted by distance,
  update(gdt),
}
```
Projectiles are integrated with gravity and collide against actor capsules (segment vs
capsule), `world.groundHeight` and `world.colliders`. A spear that hits an actor attaches its
mesh to that actor (it falls with the body). Arrows are instanced, and the ones stuck in the
ground stay (capped). combat emits the events in §12.

### game/ai.js
```js
createAI(ctx) → {
  spawnEnemy(kind, x, z, opts) → Actor,
  spawnAlly(slotX) → Actor,        // Spartans in the line at z≈0, x = ±1.4, ±2.8, …
  spawnDummy(x, z) → Actor,
  clear(),                         // remove all actors + corpses
  update(gdt),
  get enemiesAlive(), get alliesAlive(),
}
Actor = {
  id, kind, team: 'greek'|'persian', visual, pos: THREE.Vector3, facing, hp, maxHp,
  radius, height, state, alive, guard (0..1), staggerT, blockChance,
  takeHit(hit) → { damage, blocked, killed },   // hit: { damage, kind, dir:{x,z}, power, ignoreGuard, knockback, stagger, knockdown, byPlayer, critical, point }
  chestPoint(target) → Vector3,
}
```
Enemies do the following:
* They advance in loose ranks with spacing, and pick an attack slot around the player or an
  ally. At most 3 engage the player at once (4 on Day 3); the rest wait or fight allies.
* They telegraph each attack with a wind-up. The `enemyWindup` event carries
  `timeToStrike` ≈ 0.55–0.9 s depending on kind, which gives the player time to block.
* They react to hits (flinch, stagger, knockdown), sometimes guard, and die with varied death
  clips.
* Archers stay back at z < −25 and loose arrows.

Allies hold the line, fight, block, and can die (they're replaced in respites until Day 3).
Corpses stay, at most 40, and the oldest sink away.

### game/player.js
```js
createPlayer(ctx) → {
  pos, yaw, pitch, hp, maxHp, weapon, spares, hasSpearInHand, shieldRaise, state, alive,
  kills, score, chain, combos (ComboTracker),
  reset(opts),                     // new battle/training
  update(gdt),                     // drain actions, move, attack timeline, aim assist
  applyCamera(camera),             // eye position + yaw/pitch + breathing/bob + hurt flinch
  tryBlock(attacker, kind) → 'perfect'|'block'|'none',
  damage(amount, info),
  heal(amount), refillSpears(), recenter(),
  get focusTarget(),
}
```

### game/waves.js
```js
createWaves(ctx) → {
  start(day = 1),                   // also used for "continue from Day N"
  update(gdt),
  get day(), get wave(), get state(),   // 'intro'|'fighting'|'respite'|'dayEnd'|'lastStand'
}
```
The campaign is data-driven. Each day sets the time of day and has a title card:
* **Day 1, morning: The Medes and Kissians.** 3 waves of levies, then 1 wave with sparabara.
* **Day 1, dusk: The Immortals.** 2 waves of Immortals with arrow volleys, and a captain.
* **Day 2, noon: The Empire's fury.** 3 mixed waves with volleys and sparabara walls, plus 2
  captains.
* **Day 3, dawn: The last stand.** Ephialtes has shown them the mountain path. Allies are not
  replaced. It is endless and escalating, with volleys more and more often. There is no
  victory: when you fall, the epitaph shows.

Respites last 12 s: they heal and refill spears. Voice lines are spoken through
`platform.voice` and subtitled:
* "Come back with your shield, or on it." (start)
* "Spartans! Hold the line!"
* "Shields up!" (volleys)
* "Then we will fight in the shade." (first volley, Dienekes)
* "The Immortals!"
* "Molon labe!" (Come and take them)
* "We are betrayed! They come over the mountain!" (Day 3)

### ui/hud.js, ui/menus.js, ui/input.js, ui/training.js
* **HUD** (DOM over the canvas, styled in `style.css`):
  * A health bar plus a blood vignette at low HP.
  * A damage-direction arc, and a red wind-up warning chevron pointing at whoever is about
    to strike.
  * Combo name popups in a carved-stone Greek style, score and chain, kills, spear count
    icons, and the weapon swap button.
  * Day/wave title cards, voice subtitles, and a "SHIELDS UP!" volley warning.
  * During the spear cam, a letterbox and a hint: "Look at your phone!"
  * An optional Corinthian-helmet eye-slit overlay (`settings.helmetView`), and an optional
    sensor debug meter.
* **Menus**:
  * Title: THERMOPYLAE, 480 BC, with Fight, Training (the Agoge), Controls (Motion/Touch),
    Settings and How to play.
  * Settings (§9). Pause (Resume / Settings / Quit to title).
  * Game over: day reached, kills, score and best, then Simonides' epitaph: *"Go tell the
    Spartans, stranger passing by, that here, obedient to their laws, we lie."*
  * The first time motion is chosen, a safety notice: *hold the phone firmly, use a wrist
    strap, clear the space around you, **never let go when throwing***.
* **Training (the Agoge)** is guided steps with a practice dummy and live feedback. The
  steps are slash right, slash left, overhead, uppercut, thrust, raise shield, shield bash,
  switch to spear, throw (aim at the far target), and one combo. Each step shows the last
  detected gesture with an arrow and a sensitivity slider. A demo button plays a synthetic
  motion through the real recognizer and viewmodel.

## 12. Events (EventBus)

| event | payload |
|---|---|
| `gesture` | the raw GestureEvent (training/debug) |
| `swing` | `{ kind:'slash'|'thrust'|'spearThrust'|'bash'|'throw', dir, power, weapon }` (player starts an attack) |
| `hit` | `{ target, damage, kind, dir:{x,z}, point, blocked, critical, killed, byPlayer, combo }` |
| `kill` | `{ target, byPlayer, kind, spear: bool }` |
| `block` | `{ by: 'player'|actor, attacker, point, perfect, kind }` |
| `playerHurt` | `{ damage, from, kind: 'melee'|'arrow', relYaw }` |
| `playerDeath` | `{}` |
| `combo` | `{ id, name, mult, effect }` |
| `enemyWindup` | `{ actor, timeToStrike, targetIsPlayer }` |
| `spearThrown` | `{ projectile }` (projectile: `{ mesh, pos, vel, alive, stuck, target }`) |
| `spearImpact` | `{ projectile, target|null, point, normal }` |
| `arrowHit` | `{ target: 'player'|'shield'|actor|null, point }` |
| `volley` | `{ phase: 'warning'|'loosed'|'impact'|'end', duration }` |
| `dayStart` / `waveStart` / `waveCleared` / `respite` | `{ day, wave, title, subtitle, seconds }` |
| `voice` | `{ text, speaker }` |
| `weaponSwitch` | `{ weapon }` |
| `shield` | `{ up }` |
| `scoreChanged` | `{ score, chain, kills }` |
| `gameOver` | `{ day, wave, kills, score }` |
| `modeChanged` | `{ mode, prev }` |

## 13. Test hooks (main.js)

URL parameters:
* `manual=1`: no RAF loop; tests drive `__game.step()`.
* `seed=N`
* `quality=low|medium|high`
* `mode=battle|training`
* `day=N`
* `control=touch|motion`
* `debug=1`
* `nohud=1`: hides the HUD, for clean screenshots.

`window.__game = { ctx, step(frames = 1, dt = 1/60), render(), action(a), injectMotion(raw[]),
synth(script, opts), startBattle(day), startTraining(), spawn(kind, x, z), stats() →
{ fps, calls, triangles, actors, particles }, errors: [] }`.

## 14. Performance budget

The target is 60 fps on a mid-range 2021+ Android phone.
* Pixel ratio is at most 1.5, plus dynamic resolution: drop the render scale toward 0.6 if
  frame time is > 22 ms over 2 s, and recover if it's < 14 ms.
* ≤ 220 draw calls in battle, ≤ 450 k triangles, and one shadow map (1024 on low/medium,
  2048 on high).
* ≤ 14 active enemies and 8 allies. The rest of the army is instanced.
* Allocate nothing per frame in hot paths. Reuse vectors.
