# Thermopylae: design and module contracts

This is the build spec for **Thermopylae**, a first-person 3D Spartan melee game set at the
Battle of Thermopylae (480 BC). It's the contract every module is written against. If you
change an interface, update this file in the same change. "MUST" items are not optional.

## 1. The game

You are a Spartan hoplite in Leonidas' line at the Hot Gates, the narrow pass between the
cliffs of Mount Kallidromos (on your **left**) and the Malian Gulf (on your **right**). The
Phocian Wall is behind you. Wave after wave of Xerxes' army comes up the pass at you: Medes and
Kissians, sparabara shield walls, archers whose volleys hide the sun, the Immortals under
Hydarnes, and Saka axemen. You fight in first person with a **xiphos** (short sword) and a
**dory** (spear), behind a bronze-faced **aspis** that carries a personal emblem.

You can play with **touch controls**, or with **motion controls**:
* Swing the phone like a sword to slash in that direction.
* Push it forward to stab.
* Press your thumb on the screen to raise the shield. Push forward with the shield up to
  **shield bash**.
* With the spear, make a real overhand **throwing** motion to throw it. Then look back at the
  phone: the camera follows the spear in slow motion until it hits.

The campaign follows Herodotus over three days. It ends with the last stand on Day 3, where
you fall.

The game must look and feel **realistic**:
* Physically lit 3D: sun, sky, environment reflections on bronze, haze.
* Detailed procedural textures and historically grounded kit.
* Weighty combat: hit-stop, camera shake, haptics, layered sound, and animation with
  anticipation and follow-through.

There is no cartoon look and nothing from the film "300": none of its characters, costumes,
lines or the uniform Λ shields. Sources are Herodotus (Book 7), Plutarch, Diodorus and
Simonides.

## 2. Tech stack and layout

* An Android app module `:spartan`: a full-screen `WebView` that serves the game from APK
  assets on a secure origin (`https://appassets.androidplatform.net/game/…`). It exposes a
  native bridge for motion sensors, haptics and text-to-speech (§2.1).
* The game is plain browser JavaScript (ES modules, **no build step**) on **three.js r186**,
  vendored in `assets/game/lib/three/` and resolved by an import map. **Don't edit vendored
  files.** It uses WebGL2 and the `WebGLRenderer` only (no WebGPU/TSL nodes).
* The same `assets/game/` folder runs in any modern browser from any http(s) server. On a
  desktop it uses keyboard and mouse. On a phone browser it uses `DeviceMotionEvent`.

```
spartan/
  DESIGN.md                      this file
  package.json                   { "private": true, "type": "module" } (tests only; not packaged)
  build.gradle.kts               Android module
  src/main/AndroidManifest.xml
  src/main/java/com/thermopylae/ MainActivity.kt, AssetServer.kt, NativeBridge.kt
  src/main/res/                  strings, vector launcher icon
  src/main/assets/game/
    index.html                   engine check, import map, <canvas id="scene">, #overlay, js/main.js
    style.css                    all HUD/menu/controls styling
    lib/three/                   three.module.js, three.core.js, addons/Sky.js, addons/BufferGeometryUtils.js. DO NOT EDIT.
    js/
      main.js                    bootstrap, ctx, loop, mode state machine, motion glue (lead)
      core/events.js             EventBus (lead)
      core/math.js               PURE helpers, yaw helpers, seeded RNG (lead)
      core/noise.js              PURE value/simplex noise + fbm
      platform.js                native bridge or web fallbacks
      motion/frames.js           PURE: device→screen remap, gravity/up estimation
      motion/gestures.js         PURE: GestureRecognizer
      motion/synth.js            PURE: kinematic phone simulator (tests + training demo)
      game/rules.js              PURE: timings, weapon/enemy stats, damage, hit geometry, targeting, ballistics
      game/combos.js             PURE: ComboTracker + COMBOS table
      game/player.js             PlayerController
      game/combat.js             attack resolution, blocking, projectiles, volleys, bleeds
      game/ai.js                 actors (enemies, allies, dummies, helot), brains, director, corpses
      game/waves.js              campaign: days, phases, waves, respites, voice lines
      gfx/textures.js            procedural canvas textures
      gfx/world.js               terrain, cliffs, sea, sky, lighting, wall, horde, props
      gfx/characters.js          skinned actor models + procedural animator + weapon props
      gfx/viewmodel.js           first-person arms, sword, spear, shield (+ full-screen overlay quad)
      gfx/fx.js                  particles, decals, shake, hit-stop, slow-mo, spear cam, gameplay haptics
      audio/audio.js             WebAudio synthesized SFX, ambience, music, voice dispatch
      ui/hud.js                  in-battle HUD (#hud)
      ui/menus.js                title, settings, pause, game over, how-to, safety notice (#menus)
      ui/input.js                touch + keyboard/mouse → actions and continuous input (#controls)
      ui/training.js             the Agoge: guided training and calibration
  tests/
    serve.mjs                    static server (used by e2e)
    sandbox/                     per-module sandbox pages (sandbox-ctx.js builds a stand-in ctx)
    unit/*.test.mjs              `node --test spartan/tests/unit/*.test.mjs` on PURE modules
    e2e/*.mjs                    Playwright scripts (local; headless Chromium + SwiftShader)
```

**Module rules (MUST):**
* PURE modules import only other PURE modules, by relative path with the `.js` extension. They
  run under Node 20+ with no DOM.
* No module touches `window`, `document`, `navigator`, `localStorage`, `AudioContext`,
  `speechSynthesis` or `requestAnimationFrame` **at import time**, only inside `createX()` or
  functions. `tests/unit/modulegraph.test.mjs` imports every module in Node to check this.
* Import addons exactly as `import { Sky } from 'three/addons/Sky.js'` and
  `import { mergeGeometries } from 'three/addons/BufferGeometryUtils.js'`. Never use upstream
  paths like `objects/` or `utils/`, and never import other addons. If you need a helper (for
  example a skeleton clone), write it yourself.
* Game logic never uses `setTimeout` or `setInterval`. Timers count down with `gdt`/`pdt` or
  compare against `ctx.time.gnow`. UI-only fades may use CSS transitions.
* Allocate nothing per frame in hot paths. Reuse vectors and objects.

### 2.1 Android host (MUST)

`gradle.properties` has `android.useAndroidX=false`, so the module uses only plain
`android.app.Activity` and framework classes: no AppCompat, no androidx.webkit. AGP 8 doesn't
generate `BuildConfig`, so don't reference it.

**MainActivity**
* `WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)`.
* `settings`:
  * `javaScriptEnabled = true`, `domStorageEnabled = true`, `mediaPlaybackRequiresUserGesture = false`
  * `allowFileAccess = false`, `allowContentAccess = false`
  * `setSupportZoom(false)`, `builtInZoomControls = false`, `displayZoomControls = false`
  * `textZoom = 100`
* The view:
  * `setBackgroundColor(Color.BLACK)`
  * `isLongClickable = false`, `setOnLongClickListener { true }`, `isHapticFeedbackEnabled = false`
  * `overScrollMode = OVER_SCROLL_NEVER`, with no scrollbars
* `addJavascriptInterface(bridge, "SpartanNative")` before `loadUrl("https://appassets.androidplatform.net/game/index.html")`.
* Window and audio:
  * `FLAG_KEEP_SCREEN_ON`
  * `volumeControlStream = AudioManager.STREAM_MUSIC`
  * `window.attributes.preferredRefreshRate = 60f`
  * Display cutout `SHORT_EDGES` (API 28+)
* Immersive mode, re-applied in onResume and in `onWindowFocusChanged(true)`:
  * API 30+: `WindowInsetsController` hides the system bars with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.
  * API 24–29: the deprecated `SYSTEM_UI_FLAG_*` immersive-sticky flags.
* Cutout insets: an `OnApplyWindowInsetsListener` (API 28+) sets the CSS variables
  `--sai-left/top/right/bottom` (in px = inset / density) with `evaluateJavascript`. It also
  runs in onPageFinished.
* Engine check: parse `Chrome/(\d+)` from `WebSettings.getDefaultUserAgent(this)`. If it is
  below 94, show an AlertDialog that offers to open
  `market://details?id=<WebView.getCurrentWebViewPackage()?.packageName ?: "com.google.android.webview">`.
  Catch `ActivityNotFoundException`.
* `onRenderProcessGone` (API 26+): remove and destroy the WebView, create a fresh one, reload,
  and return true.
* Back:
  * API 33+: `onBackInvokedDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT)`, with
    `android:enableOnBackInvokedCallback="true"` in the manifest.
  * Older: `onBackPressed()`.
  * Both call `evaluateJavascript("(function(){try{return !!(window.__onBack&&window.__onBack())}catch(e){return false}})()") { r -> if (r != "true") finish() }`.
* `onPause`, in this order:
  1. `evaluateJavascript("window.dispatchEvent(new Event('nativepause'))", null)`
  2. `bridge.onPause()` (unregister sensors, `tts.stop()`, `vibrator.cancel()`)
  3. `webView.onPause()`, then `pauseTimers()`
* `onResume`, in this order:
  1. `resumeTimers()`, then `webView.onResume()`
  2. `bridge.onResume()` (re-register sensors if they're wanted)
  3. immersive mode
  4. dispatch `nativeresume`
* `onDestroy`: `bridge.release()` (tts.shutdown, unregister the DisplayListener,
  `sensorThread.quitSafely()`), detach the WebView, `destroy()`.

**AssetServer** (`WebViewClient.shouldInterceptRequest(view, request: WebResourceRequest)`,
which runs on a WebView IO thread):
* Host `appassets.androidplatform.net`, path `/game/<p>` → asset `game/<p>`. `/game/` →
  `game/index.html`. Use `request.url.path`, so query strings are ignored.
* A path containing `..` → 403. A non-GET request → 405.
* Any other host or scheme → an empty 403. Never return null.
* MIME types come from a hard-coded table, never MimeTypeMap:
  * html → text/html; js and mjs → text/javascript; css → text/css; json → application/json
  * svg → image/svg+xml; png → image/png; jpg → image/jpeg; webp → image/webp
  * woff2 → font/woff2; wasm → application/wasm; txt → text/plain
  * anything else → application/octet-stream
* Encoding is "utf-8" for text types, json and svg, otherwise null.
* Found: `WebResourceResponse(mime, enc, assets.open(path))`.
* Missing (an IOException): `WebResourceResponse("text/plain","utf-8",404,"Not Found",emptyMap(),ByteArrayInputStream(ByteArray(0)))`.
* `shouldOverrideUrlLoading` returns true (blocks navigation) for any URL that isn't appassets.

**NativeBridge** (`window.SpartanNative`). Every `@JavascriptInterface` body is wrapped in
`try { … } catch (e: Throwable) { Log.w("Spartan", e) }`.
* **Sensors.**
  * TYPE_GYROSCOPE, TYPE_LINEAR_ACCELERATION, TYPE_GRAVITY and TYPE_ACCELEROMETER are each
    registered with `registerListener(l, s, 5000 /*µs = 200 Hz*/, 0, sensorHandler)` on a
    dedicated `HandlerThread("sensors")`. Never use SENSOR_DELAY_FASTEST. The manifest
    declares `HIGH_SAMPLING_RATE_SENSORS` as a guard.
  * Emit **one line per gyroscope event**. The other sensors are sample-and-hold, and are NaN
    until their first event or if the sensor is missing.
  * `t = (event.timestamp − baseNs) / 1e9` as a **Double**. baseNs is the first gyro event
    after process start and stays the same across stop/start.
  * The line is `t,gx,gy,gz,ax,ay,az,Ax,Ay,Az,gvx,gvy,gvz,rot`, where a = linear
    acceleration, A = raw accelerometer and gv = gravity.
  * Build it **only** with `StringBuilder.append(Double/Float/Int)`, ',' and '\n'. Never use
    `String.format`: decimal commas on Greek/German locales would corrupt the data.
  * The buffer is a ring of 512 lines under `synchronized(lock)`, dropping the oldest.
* **Rotation.** `@Volatile var rotationDeg` is updated by a `DisplayManager.DisplayListener`
  on the main looper (for `Display.DEFAULT_DISPLAY`) and in onResume:
  `(if (SDK_INT >= 30) display!!.rotation else windowManager.defaultDisplay.rotation) * 90`.
* **Methods.**
  * `motionAvailable(): Boolean`. True if a gyroscope exists.
  * `startMotion()`, `stopMotion()`. The wanted state survives pause/resume, and resume
    clears the ring.
  * `pollMotion(): String`. Drains the ring, or returns "".
  * `motionInfo(): String`. JSON `{"gyroRange":rad/s,"accRange":m/s²,"rateHz":measured}`.
  * `vibrate(ms: Int, amplitude: Int)`. Skips ms ≤ 0 or amplitude ≤ 0, clamps ms to 1..400
    and amplitude to 1..255, and falls back to `DEFAULT_AMPLITUDE` without amplitude control.
    It uses VibratorManager on API 31+ and VibrationEffect on 26+, with the deprecated calls
    below that. It does nothing without a vibrator.
  * `vibratePattern(csv: String)`. At most 16 values, each ≤ 400 ms, with repeat −1.
  * `say(text: String, pitch: Float, rate: Float)`, `stopSpeech()`. TTS uses `Locale.UK`,
    falling back to `Locale.US`. If neither is available, it is silent and only subtitles
    show. Calls before init are dropped. It uses `QUEUE_FLUSH` and a unique utterance id.
  * `rotation(): Int`. Degrees.
  * `lockOrientation(lock: Boolean)`. On the UI thread, sets `SCREEN_ORIENTATION_LOCKED` or
    `SENSOR_LANDSCAPE`.
  * `exit()`. Calls finish() via runOnUiThread.

**Manifest:**
* Permissions: `VIBRATE`, `HIGH_SAMPLING_RATE_SENSORS`.
* `<queries>` for `android.intent.action.TTS_SERVICE`.
* Activity:
  * `screenOrientation="sensorLandscape"`
  * `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|keyboard|navigation|uiMode|density"`
  * `hardwareAccelerated="true"`
  * `enableOnBackInvokedCallback="true"`
* Theme `@android:style/Theme.Black.NoTitleBar.Fullscreen`. The label is "Thermopylae".
  `applicationId`/`namespace` is `com.thermopylae`.

**index.html.** An inline ES5 classic `<script>` runs before the import map. It sets
`window.__engineFail(msg)`, which fills `#overlay` with a readable panel. It checks
`new Function('class A{static{}}')` and `HTMLScriptElement.supports('importmap')`, and on
failure shows "Please update Android System WebView (or Chrome) to play". It starts a 12 s
watchdog: unless `window.__booted` is true by then, it shows the same panel plus the first
recorded `window.onerror` message. Then come the import map, modulepreload links for
three.core.js and three.module.js, and `js/main.js`. main.js shows "This device has no
WebGL 2" if the renderer throws.

## 3. Conventions

### 3.1 World
* Units are metres, seconds and radians. Y is up. Gravity is 9.81 m/s².
* The Spartan line stands along **z = 0**. The player's line slot is `(0, 0, 0)` (feet), facing
  **−Z** toward the Persians, who come from −Z, spawning at about z = −35…−60. The distant
  horde reaches z = −400.
* The cliff (Mount Kallidromos) rises on the **left (−X)** from about x = −9, with a cliff
  face plane at x = −9 facing +X. The sea (Malian Gulf) is on the **right (+X)**: the ground
  drops at about x = +9.5 to rocks and water at y = −3. The pass floor is about x ∈ [−8.5, 9].
* The Phocian Wall runs across the pass at z ≈ +12, about 2.5 m high, with a **4 m gap at
  x ∈ [−2, 2]**. The ground beyond it continues to z = +60.
* **Yaw** is the camera convention: yaw 0 faces −Z, and **positive yaw turns left** (CCW seen
  from above), the same as `camera.rotation.y` with order 'YXZ'. Pitch is positive when looking up.
* **Facing** is the actor convention: actor models face **+Z in local space**, and
  `root.rotation.y = facing`.
* core/math.js provides:
  * `yawToDir(yaw) → [−sin yaw, −cos yaw]` (x, z)
  * `yawTo(dx, dz) = atan2(−dx, −dz)`
  * `facingTo(dx, dz) = atan2(dx, dz)`
  * `facingToYaw(f) = wrapAngle(f + π)`, `yawToFacing(y) = wrapAngle(y + π)`
  * `forward3(yaw, pitch) → [−sin yaw·cos pitch, sin pitch, −cos yaw·cos pitch]`
  * `right3(yaw) → [cos yaw, 0, −sin yaw]`

  Every `yaw` parameter in rules.js and combat.js uses the camera convention. Actors pass
  `facingToYaw(actor.facing)`.
* Eye height is 1.66 m. Actors are 1.70–1.85 m tall (the Saka captain 1.95 m), with a
  capsule radius of 0.32 from feet to head.
* `world.groundHeight(x, z)` is defined everywhere. It returns −3 (the water surface) over the sea.

### 3.2 Time
`ctx.time = { now, gnow, dt, gdt, pdt, scale, hitStop, playerRealtime }`
* `dt` is the real frame delta, clamped to ≤ 0.05 s. `now` is the sum of dt.
* `gdt` is 0 if paused, in gameover or `hitStop > 0`; otherwise `dt·scale`. `gnow` is the sum of gdt.
* `pdt` is the player's own timeline (attacks, shield, spear draw). It is 0 when gdt would
  be 0 for hit-stop or pause. Otherwise it is `dt` if `playerRealtime` (combo slow-mo lets the
  hero outpace slowed enemies), else `gdt`.
* `scale`, `hitStop` and `playerRealtime` are written **only by fx** (`slowMo`, `hitStop`,
  spear cam, `reset`). main.js decrements `hitStop` by `dt` except while paused or in gameover.
* Gameplay, AI, animation and particles use `gdt`. The player uses `pdt`. UI, input and
  sensors use real time. Sensor samples carry their own timestamps.

### 3.3 Screen-frame motion axes
Sensors are remapped from the device frame into the **screen frame** of the current display rotation:
* `+x` is the right edge of the screen as the user sees it.
* `+y` is the top edge.
* `+z` points out of the screen toward the user's face.

The **blade axis** is `b = (0, 0, −1)`: an imaginary sword sticks out of the back of the phone
into the scene. The player holds the phone in landscape with the screen facing them, "looking
through it", so the blade points where they look.

Remap: for display rotation `r` (degrees, 0|90|180|270, from Android's `rotation()` or
`screen.orientation.angle`), a device vector `(dx, dy, dz)` becomes `sx = cos r·dx − sin r·dy`,
`sy = sin r·dx + cos r·dy`, `sz = dz`. Check: with the phone rotated CCW so its top edge is
on the left, r = 90, and device +x points to screen-up. The inverse, used by synth, is
`dx = cos r·sx + sin r·sy`, `dy = −sin r·sx + cos r·sy`.

`up` is the unit vector **opposite to gravity**, the direction an accelerometer at rest reads
as positive (Android `TYPE_GRAVITY` normalized).

### 3.4 Slash direction
`dir` is the **direction the blade moves across the view**, as a screen-space angle: `0` =
rightward, `π/2` = upward (uppercut), `±π` = leftward, `−π/2` = downward (overhead chop).
`rules.dirToken(dir)` gives eight tokens, `R, UR, U, UL, L, DL, D, DR`, each a 45° sector centred
on its angle. Token sets: Rish `{R, UR, DR}`, Lish `{L, UL, DL}`, Uish `{U, UL, UR}`,
Dish `{D, DL, DR}`.

## 4. Shared context `ctx` (built in main.js)

```js
ctx = {
  THREE,
  renderer, scene, camera,       // camera: PerspectiveCamera(fov 70, near 0.1, far 2000), rotation.order 'YXZ'
  vm: { scene, camera },         // viewmodel scene + camera (fov 60, near 0.01, far 10); camera stays at origin, identity rotation
  events,                        // EventBus: on(name, fn) → off, once, off, emit (handlers isolated with try/catch)
  settings, saveSettings(),      // §9
  platform,                      // §5.1
  time,                          // §3.2
  rng,                           // createRng(seed): next, range, int, pick, chance, gauss
  quality,                       // 'low'|'medium'|'high' (resolved from settings.quality)
  input,                         // §6 continuous input
  actions,                       // { push(a), drain() → array, clear() }
  mode, setMode(mode, opts), resume(),   // 'title'|'training'|'battle'|'paused'|'gameover'
  debug,                         // ?debug=1
  motionFrames, gestures,        // MotionFrames, GestureRecognizer (main.js owns the glue)
  motionDemo: false,             // training sets this while a synthetic demo is injected
  overlay,                       // the #overlay element; UI modules each add one root
  actors: [],                    // ONLY living actors (alive === true); owned by ai.js
  // modules, created in this order:
  textures, world, characters, fx, audio, viewmodel, combat, ai, player, waves, hud, menus, inputCtl, training,
}
```
* Each non-pure module exports `createX(ctx)` and returns an object.
* A constructor may keep references to earlier modules and subscribe to events, but **must
  not call any other module**. update() and event handlers may call any ctx module.
* Modules subscribe to events in their constructor, and **must not reset themselves on
  `modeChanged`**. main.js resets everything explicitly (§10).

## 5. Platform, motion and gestures

### 5.1 platform.js
```js
createPlatform() → {
  kind: 'android' | 'web',
  bindSettings(settings),               // main calls this right after loading settings
  motion: {
    isSupported(): Promise<boolean>,    // android: motionAvailable(); web: a devicemotion event with non-null rotationRate within 1.5 s
    requestPermission(): Promise<boolean>, // iOS 13+: DeviceMotionEvent.requestPermission() (call from a user gesture); else true
    start(), stop(),
    poll(): RawSample[],                // samples since last poll, oldest first; returns a reused array of pooled objects
    inject(samples),                    // queue synthetic samples (re-based so the first is at lastT()+0.01, spacing kept);
                                        // while injected samples are queued, real samples are discarded; poll() returns them even if not started
    lastT(): number,                    // t of the last delivered sample (0 if none)
    rate(): number,                     // measured Hz
    info(): { gyroRange, accRange, rateHz },  // gyroRange defaults to 34.9 rad/s if unknown
  },
  haptics: { pulse(ms, strength = 1), pattern(msArray) },   // no-op unless settings.haptics; native, else navigator.vibrate
  voice: { say(text, { pitch = 0.9, rate = 0.95 } = {}), stop() }, // no-op unless settings.voice; native TTS, else speechSynthesis (en-GB)
  storage: { load(key, fallback), save(key, value) },       // JSON in localStorage, try/catch, falls back to an in-memory Map
  lockOrientation(bool),                // android bridge; web: screen.orientation.lock?.('landscape').catch(()=>{}) / unlock()
  onPause(fn), onResume(fn),            // 'nativepause'/'nativeresume' and visibilitychange
  onBack(fn),                           // installs window.__onBack = () => fn() (must return boolean synchronously)
  exit(),
}
```
`RawSample` is in the device frame: `{ t, gyro:[x,y,z] rad/s, acc:[x,y,z]|null (linear),
accG:[x,y,z]|null (including gravity), grav:[x,y,z]|null, rot }`.
* **Android**: parse each pollMotion line with `Number()`. Any NaN triple becomes null.
* **Web**:
  * `rotationRate` deg/s → rad/s with **alpha → x, beta → y, gamma → z** (what Chrome and
    Firefox on Android and Safari on iOS ship).
  * `rot = ((screen.orientation?.angle ?? window.orientation ?? 0) % 360 + 360) % 360`.
  * `t = event.timeStamp / 1000`.
  * `acc` comes from `acceleration`, `accG` from `accelerationIncludingGravity`, and grav is
    null.
* Every `SpartanNative` call is wrapped in try/catch.

### 5.2 motion/frames.js (PURE)
```js
export function remapToScreen(v, rotDeg, out?) → [x,y,z]
export class MotionFrames {
  constructor({ tiltTau = 0.5, platformKind = 'web' } = {})
  process(raw: RawSample) → Sample      // reused object: { t, dt, w:[3], a:[3], up:[3], calm, gap }
  reset()
  get gravitySign()  get rotFix()  get axisMap()   // diagnostics
}
```
`process` runs these steps on each sample:
1. `dt = clamp(t − tPrev, 0, 0.05)`. `gap = (t − tPrev) > 0.1`.
2. Remap gyro, acc, accG and grav to the screen frame using `rot + rotFix`.
3. Multiply acc, accG and grav (**never the gyro**) by `gravitySign`.
4. **up.** If `grav` is present: `up = normalize(grav)`. Otherwise propagate with the gyro
   and correct slowly:
   * `up = normalize(up + cross(up, w)·dt)`
   * `g = normalize(accG)`, `wgt = clamp(1 − ||accG| − 9.81| / 2.5, 0, 1) · (|w| < 2 ? 1 : 0)`
   * `up = normalize(up + (dt/tiltTau)·wgt·(g − up))`

   Initialize from the first accG. Re-initialize when `rot` changes or on a gap.
5. **a.** `a = acc` if present, else `accG − 9.81·up`.
6. **calm** = |w| < 0.6 and |a| < 1.5.

**gravitySign** is fixed at +1 on Android. On the web:
* (a) Latched test: over samples with |w| > 7, average the remapped raw `acc.z` (or `accG.z`).
  After at least 12 samples, a mean < −4 latches −1 and > +4 latches +1. During swings the
  centripetal acceleration points toward the user (+z), whatever the rotation.
* (b) Until it's latched: if the phone has been calm for 1 s with `up·(0, 0.707, 0.707) < −0.5`,
  use −1 provisionally.
* (c) Once latched: if `up.y < −0.6` while calm for 1.5 s, the reported screen angle is off
  by 180°, so `rotFix += 180`.

**axisMap**: web only, optional but recommended. During slow rotations (0.5 < |w| < 3,
||accG| − 9.81| < 1), correlate `d(normalize accG)/dt` with `normalize(accG) × w` for the
current mapping and for the permutation (alpha→z, beta→x, gamma→y). If the permutation wins
by 2× over at least 1 s of data, switch to it.

### 5.3 motion/gestures.js (PURE)
```js
export const GESTURE_DEFAULTS = { swingStart: 3.5, swingPeak: 5.0, swingSweep: 0.45, swingEnd: 1.5, endHold: 0.04,
  maxSwing: 0.7, reverseWindow: 0.45, reverseRatio: 0.6, reverseSweep: 0.5, reverseSector: 60°,
  thrustAcc: 12, thrustVel: 0.15, thrustWindow: 0.06, thrustRefractory: 0.35, vfLeak: 0.5, postThrustSwingLock: 0.15,
  upHoldStartBeta: -0.2, windupBeta: 1.0, windupTime: 0.1, windupMemory: 1.0,
  throwPeak: 7, throwFull: 13, throwSector: 65°, throwReleaseRatio: 0.6, throwMaxSweep: 1.4, postThrowLock: 1.0,
  settleRate: 1.0, settleTime: 0.15, absorb: 0.35, lookEase: 0.25, deadZone: 0.02,
  slashPowerSpan: 10, thrustPowerSpan: 18 }
export class GestureRecognizer {
  constructor(opts = { sensitivity: 1, weapon: 'sword', gyroRange: 34.9 })
  setSensitivity(s)         // 0.5–2.0
  setWeapon('sword'|'spear')
  reset()                   // clears swing/thrust/windup state, recoil memory, look state
  push(sample) → GestureEvent[]   // returns a reused array; copy what you keep
  consumeLook() → { yaw }   // heading change released since the last call (radians), then zeroed
  holdLook(), resumeLook(), recenter()
  get settled()             // phone settled AND up.y ≥ 0.5 (screen upright, facing the player)
  get debug()               // { m, yawRate, pitchRate, beta, phase, peak, fwdAcc, fwdVel, lookState, lastEvent }
}
// GestureEvent: { type:'slash', dir, power, peak, sweep, t } | { type:'thrust', power, peak, t }
//             | { type:'throw', power, peak, t } | { type:'windup', t }
```
**Sensitivity** `s` divides only `swingStart`, `swingPeak`, `throwPeak`, `throwFull` and
`thrustAcc`, and divides `swingSweep` by √s. The power formulas use the scaled thresholds.
Power ceilings respect the gyro range:
`slash power = clamp((peak − swingPeak/s)/min(slashPowerSpan, 0.9·gyroRange − swingPeak/s), 0, 1)`.
A gap resets the swing, thrust and look state.

**Rates in a gravity-aligned frame.**
* `u = sample.up`, `b = (0,0,−1)`, `s = u.y ≥ 0 ? 1 : −1`.
* Blade elevation, unwrapped past vertical: `β = atan2(−u.z, s·hypot(u.x, u.y))`, so β > π/2
  means the blade points back over the shoulder. `c = cos β`.
* `yawRate = w·u` (positive = blade moving left).
* Pitch axis: if `|b×u| ≥ 0.35`, `r = s·normalize(b×u)`; otherwise `r = normalize(x̂ − u.x·u)`.
  `pitchRate = w·r` (positive = blade tip moving up).
* Integrate `dx += −yawRate·c·dt`, `dy += pitchRate·dt`. The planar rate is
  `m = hypot(yawRate, pitchRate)`.

**Swing.**
* Starts when `m > swingStart`, with `dx = dy = 0` and `peak = m`.
* **Fires early**, as soon as `hypot(dx,dy) ≥ swingSweep` and `peak ≥ swingPeak`, with
  `dir = atan2(dy, dx)`. The two exceptions below are deferred instead.
* At most one event per swing.
* **Ends** at the first of:
  * (a) `m < swingEnd` for `endHold`
  * (b) a reversal: once `|(dx,dy)| ≥ 0.2`, the velocity `(−yawRate·c, pitchRate)` points
    against `(dx,dy)` on 2 consecutive samples
  * (c) `maxSwing`

  A new swing may start on the very next sample.

**Recoil and return-to-guard.** Let P be the last fired swing. A new swing whose dir is
within `reverseSector` of `P.dir + π`, and which starts within `reverseWindow` after P ended,
fires only when its running peak is at least `max(swingPeak/s, reverseRatio·P.peak)` and its
sweep is at least `reverseSweep`. The test runs every sample. Returns run at 20–50% of the
strike's speed; deliberate reverse slashes run at 70–110%.

**Upward swings and wind-ups.** A swing whose running dir is within ±60° of straight up, and
whose β at swing start is ≥ `upHoldStartBeta`, is **not** early-fired:
* If β reaches `windupBeta` (phone raised beside or behind the head), emit `windup` at once.
  No slash comes from this swing.
* Otherwise, at swing end, emit the slash with the whole swing's dir.

Uppercuts started from a low guard (β < upHoldStartBeta) fire early as normal. The time of the
last wind-up is remembered for `windupMemory`, and so is any period of at least `windupTime`
with β ≥ windupBeta.

**Throw** (weapon = spear only).
* A swing whose running dir is within ±`throwSector` of straight down is not early-fired
  **if** a wind-up is remembered (emitted, or β ≥ windupBeta at swing start).
* It resolves at **release**, the first of:
  * the running peak is ≥ `throwPeak/s` and `m < throwReleaseRatio·peak`
  * sweep ≥ `throwMaxSweep`
  * swing end
* Peak ≥ `throwPeak/s` → `throw` with `power = clamp((peak − throwPeak/s)/(throwFull/s − throwPeak/s), 0, 1)`.
  A firm flick is enough, so never throw hard. Otherwise it is a slash with that dir.
* A downward swing with no wind-up is an ordinary slash, so an overhead spear chop never
  throws.
* After a throw, ignore every swing, thrust and wind-up for `postThrowLock`, and keep look in
  HOLD until `resumeLook()`.

**Thrust.**
* `f = a·b = −a.z`. The forward velocity is `vf = vf·exp(−dt/vfLeak) + f·dt`. It is zeroed
  while a swing is active, at swing end, and on calm samples.
* A candidate opens on any sample with `f > thrustAcc/s`, `vf > thrustVel`, no active swing
  and `m < 4`.
* Emit `thrustWindow` after it opens, or earlier once `f < 0.8·runningPeak`, but no sooner
  than 30 ms after opening. `power = clamp((runningPeak − thrustAcc/s)/thrustPowerSpan, 0, 1)`.
* Cancel the candidate if a swing starts or `m ≥ 4` within that window.
* Refractory period: `thrustRefractory` after emitting.
* After a thrust, a swing may not start for `postThrustSwingLock` unless `m ≥ 6`.
* Retraction is stopped by +f with negative vf, so it never fires.

**Gyro look follows the settled heading.**
* Every sample, swings included: `hYaw += dz(yawRate, deadZone)·dt`.
* **Settled**: `m < settleRate` for at least `settleTime`, no active swing, and no lock.
* While settled, the released look heading `L = hYaw − O`, where O is an offset.
* When the phone settles after an excursion (a swing, wind-up, throw or fast turn), compare
  `δ = (hYaw − O) − L`:
  * If `|δ| ≤ absorb`, it was an imprecise return to guard: `O += δ`, and L doesn't move.
  * Otherwise, release δ eased over `lookEase` (a body turn).
* `consumeLook()` returns the change in L.
* `holdLook()` freezes L until `resumeLook()`. That re-anchors with `O = hYaw − L`, so nothing
  jumps.
* `recenter()` sets `O = hYaw − L`.
* Pitch is never looked by gyro: player.js eases the pitch toward the focus target instead.

**Required unit tests** (synth, §5.4), over the matrix rot {90, 270} × tilt {0, 0.5} ×
roll {0, 0.3} × rate {50, 100, 200} × gravitySign {±1}, plus runs without linear acceleration
and without gravity:
* Each 8-way swing with wind-up, rebound and return gives exactly one slash with the right token.
* R,L,R,L at about 2 Hz (arc 1.0, speed 9) gives 4 alternating slashes.
* `swing R speed 10, idle 0.05, swing L speed 4` gives exactly 1 slash.
* `… swing L speed 9` gives 2 slashes.
* Throws at speed {9, 13, 18} give exactly one windup and one throw, no slash, with power
  rising with speed.
* `swing dir −π/2 speed 9` without a wind-up, in spear mode, gives a slash.
* A thrust followed by retraction {0.25, 0.3, 0.35} s gives exactly one thrust.
* 10 s of walking, and 10 s of idling, give nothing.
* A 1.0 rad turn over 1.2 s gives a consumed look of 1.0 ± 0.1 and no gesture.
* A swing plus its return gives a net consumed |look| < 0.05.
* Detection latency from motion onset is < 130 ms for a 9 rad/s swing.

### 5.4 motion/synth.js (PURE)
```js
export function synthesize(script, opts) → RawSample[]
// opts: { rate = 100, rot = 90, tilt = 0, roll = 0, hand = 'right', noise = 1, seed = 1, gravitySign = 1,
//         includeLinear = true, includeGravity = true, gyroRange = 34.9, jitter = 0, t0 = 0 }
// script segments, back-to-back:
//   { kind:'idle', duration }  { kind:'walk', duration }  { kind:'turn', yaw, duration }
//   { kind:'swing', dir, speed = 9, arc = 1.6, radius = 0.55, windup = 0.3, recoil = 0.15, ret = 2.5, wrist = 0.3, pronate = 0.3 }
//   { kind:'thrust', distance = 0.35, duration = 0.18, hold = 0.08, retract = 0.3, dip = 0.12 }
//   { kind:'throw', speed = 13, windupArc = 2.5, windupSpeed = 6, hold = 0.12, arc = 3.4, twist = 0.45, lookBack = 0.7 }
//   { kind:'raise' }   (wind-up only: lift the phone beside the head and hold; used for overhead chops)
export function scriptDuration(script) → seconds
```
The model:
* The world frame is §3.1. Mirror x when `hand = 'left'`.
* Guard pose: eye `E = (0, 1.66, 0)`, phone centre `P0 = (0.12, 1.48, −0.42)`. The blade
  `b0 = normalize(P0 − E)` is pitched back by `tilt` and rolled by `roll` about b0, with
  screen-x horizontal.
* The profile is `s(τ) = 10τ³ − 15τ⁴ + 6τ⁵`, with `T = 1.875·arc/speed`, so `speed` is the
  true peak.
* **swing**: view direction `d = cos(dir)·R̂ + sin(dir)·Û`, axis `n = normalize(b × d)`.
  * The blade is **radial** from the pivot `P − radius·b`.
  * The arm turns `(1−wrist)·θ` about n through the pivot, and the wrist adds `wrist·θ` 30 ms
    later (orientation only).
  * Diagonals roll `pronate·sin(π·θ/arc)` about b.
  * Before the swing: a wind-up of `windup` rad the opposite way at 0.4·speed, then a 0.06 s
    pause.
  * After it: a rebound of `recoil·arc` at 0.3·speed, then a return to guard at peak `ret`
    rad/s (0 = stay).
* **thrust**: translate along b (peak 5.77·D/T²), hold, retract, and dip the blade tip by
  `dip` at full extension.
* **throw**:
  * Rotate about R̂ through the shoulder `S = (0.19, 1.45, 0.03)` by +windupArc, so the blade
    points up and back, then hold.
  * Then rotate −arc with the late-peaking profile `s(τ^1.4)`, scaled so the peak rate =
    speed. Meanwhile the trunk yaws +twist about the vertical through (0,·,0.1), and the
    shoulder moves 0.25 m forward.
  * Then, 0.3 s later, return to guard over `lookBack` s.
* **turn**: the whole rig rotates about the vertical through (0,·,0.1).
* **walk**: 0.04 m bob at 1.8 Hz, ±2 m/s² fore-aft, ±0.05 rad yaw at 0.9 Hz.
* **idle**: an 8–12 Hz tremor of 0.002 rad.

Sensor output:
* p and q are evaluated analytically at the sample times (with ±jitter on the spacing).
* `p̈` and the body rate `ω = 2·vec(q⁻¹·q̇)` come from central differences (h = 1 ms).
* `accG = q⁻¹(p̈ + 9.81Ŷ)`, `acc = q⁻¹p̈`, `grav = q⁻¹(9.81Ŷ)`, each multiplied by gravitySign.
* Map screen → device with the inverse remap (§3.3).
* Add noise (gyro σ 0.02·noise rad/s, accel σ 0.15·noise m/s²).
* Clip the gyro to ±gyroRange and acceleration to ±78.
* Timestamps start at `t0`.

It uses its own tiny quaternion helpers and no three.js.

## 6. Actions and input

Actions are discrete commands, pushed by `ui/input.js` (touch, keys, mouse) and by main.js's
motion glue:
```js
{ type:'slash', dir, power, src }      // src: 'motion'|'touch'|'key'|'test'
{ type:'thrust', power, src }          // shield held → bash; spear in hand → spear thrust; else sword thrust
{ type:'bash', power, src }
{ type:'throw', power, src }           // needs weapon 'spear' and a spear in hand
{ type:'switchWeapon', weapon?, src }  // toggle, or set 'sword'|'spear'
{ type:'pause', src }                  // input.js calls ctx.setMode('paused') directly; player also handles it if drained
{ type:'recenter', src }
```
`ctx.input` is continuous state, owned by ui/input.js. It is zeroed on every `modeChanged`
(stick, look, shieldHeld = false, tracked pointers forgotten).
```js
{ moveX, moveZ,             // −1..1: strafe right+, forward+
  lookYaw, lookPitch,       // radians accumulated this frame (touch drag, mouse, gyro via main); player consumes + zeroes
  shieldHeld,               // touch-hold (motion layout), shield button, RMB/Shift/K
  shieldPressedAt,          // ctx.time.gnow when the current hold began (for perfect-block timing)
  phoneSettled,             // main: gestures.settled in motion mode; always true otherwise
  motion }                  // main: true when motion control is active
```
**Motion layout** (`#controls` covers the screen):
* A touch raises the shield only if it starts outside the HUD buttons and outside a 12 px edge
  strip, and its contact radius is ≤ 40 px when reported.
* Small corner buttons: **pause** (top-left), **weapon swap** (top-right, showing the spear
  count), and **recenter** (top-centre).
* `contextmenu` and non-passive `touchstart` are preventDefault-ed.
* If a touch has been held for more than 5 s while at least 3 slashes fired, show this tip
  once per battle: "Your shield is up while you swing. Rest your thumb on the frame, and press
  the glass to raise the shield."

**Touch layout** (mirrored when leftHanded):
* Bottom-left: a virtual stick to move.
* Above it: a large **SHIELD** button. Hold it to block. Swiping up from it, or tapping
  **BASH** next to it, bashes.
* Right half:
  * A fast swipe (more than 40 px within 180 ms, at least 0.5 px/ms) slashes in the swipe
    direction. Screen-y is inverted, so swiping up gives an upward slash.
  * A tap (less than 12 px, under 200 ms) stabs.
  * A slow drag looks around, at 0.006 rad/px.
* With a spear in hand, a **THROW** button appears above weapon swap. Hold it to wind up
  (power rises from 0 to 1 over 0.7 s, drawn as a ring), then release to throw. Dragging off
  the button cancels.
* Pause and weapon swap sit in the corners.

**Keyboard and mouse:**
* WASD moves. The mouse looks with pointer lock, at 0.0025 rad/px.
* Left-drag slashes in the drag direction, and a quick left click thrusts.
* RMB, Shift or K holds the shield. F bashes. Q swaps weapon. R or the middle button
  throws (power 0.7).
* Arrow keys slash ←→↑↓. Space thrusts. Esc or P pauses. C recenters.

## 7. Gameplay rules (game/rules.js)

```js
export const TIMING = {   // seconds of pdt; strike = when damage resolves; total = attack length; cancelAfter = earliest buffered follow-up
  slash:       { strike: 0.10, total: 0.34, cancelAfter: 0.16 },
  thrust:      { strike: 0.11, total: 0.38, cancelAfter: 0.20 },
  spearThrust: { strike: 0.13, total: 0.44, cancelAfter: 0.24 },
  bash:        { strike: 0.09, total: 0.45, cancelAfter: 0.30, cooldown: 0.9 },
  throw:       { windup: 0.14, total: 0.60, redraw: 0.6 },     // motion throws skip windup (spawn after 0.03)
  shieldRaise: 0.14, shieldLower: 0.18, shieldDip: 0.45,
  perfectBlockWindow: 0.22, perfectNeedsDown: 0.25, critWindow: 1.0,
}
export const WEAPONS = {
  sword:  { reach: 2.1, slashDamage: 34, thrustDamage: 40, thrustHalfAngle: 0.35 },
  spear:  { reach: 3.0, slashDamage: 22, thrustDamage: 55, thrustHalfAngle: 0.24, throwDamage: 200, throwSpeed: [20, 34] },
  shield: { reach: 1.6, halfAngle: 0.44, bashDamage: 8, bashKnockback: 0.9, bashStagger: 1.1, rebashImmunity: 2.5 },
}
export const ENEMIES = {  // seconds, metres, m/s
  levy:      { hp: 55,  guard: 0.25, damage: 10, windup: 0.85, interval: [3.0, 4.5], walk: 1.4, run: 2.4, reach: 1.9, attackClip: 'thrust' },
  sparabara: { hp: 70,  guard: 0.70, damage: 12, windup: 0.90, interval: [3.2, 4.8], walk: 1.2, run: 2.0, reach: 2.2, attackClip: 'thrust' },
  immortal:  { hp: 90,  guard: 0.45, damage: 16, windup: 0.70, interval: [2.4, 3.4], walk: 1.5, run: 2.8, reach: 2.2, attackClip: 'overhead' },
  captain:   { hp: 180, guard: 0.30, damage: 28, windup: 1.00, interval: [3.5, 5.0], walk: 1.3, run: 2.2, reach: 1.8, attackClip: 'axe', heavy: true },
  archer:    { hp: 40,  guard: 0,    damage: 8,  windup: 1.20, interval: [4.0, 6.0], walk: 1.4, run: 2.4, reach: 45, attackClip: 'shoot' },
  spartan:   { hp: 150, guard: 0.60, damage: 30, windup: 0.60, interval: [1.6, 2.6], walk: 1.4, run: 3.0, reach: 2.6, attackClip: 'thrust' },
  dummy:     { hp: Infinity, guard: 0, damage: 0 },
  helot:     { hp: 1, guard: 0, damage: 0, walk: 3.5 },
}
export const DIRECTOR = {  // limits on attacks whose target is the player
  1: { engaged: 2, windups: 1, rate: 0.18, gap: 0.6 },
  2: { engaged: 3, windups: 2, rate: 0.28, gap: 0.4 },
  3: { engaged: 4, windups: 2, rate: 0.40, ratePerWave: 0.04, rateMax: 0.9, gap: 0.3 },
}
export function dirToken(dir) → 'R'|'UR'|'U'|'UL'|'L'|'DL'|'D'|'DR'
export const TOKEN_SETS = { Rish, Lish, Uish, Dish }   // Sets (§3.4)
export function slashProfile(dir) → { token, category, halfAngle, damageMul, overShield }
//   horizontal (R, L): halfAngle 0.70 rad, ×1.0; diagonal: 0.55, ×1.15; vertical (U, D): 0.35, ×1.35; D: overShield true
export function sectorHit(origin, yaw, reach, halfAngle, target, radius, out?) → { hit, dist, angle }
//   XZ only; points are duck-typed {x,y,z}; dist = |t−o|xz; angle = signed angleDiff(yaw, yawTo(dx,dz));
//   hit iff dist − radius ≤ reach && |angle| − atan2(radius, max(dist, 0.01)) ≤ halfAngle
export function pickTarget(actors, origin, yaw, maxAngle, maxDist, filter?) → actor|null   // min |angle| + 0.15·dist, alive only
export function segmentCapsule(p0, p1, a, b, r) → t∈[0,1] | −1
export function ballisticPitch(speed, dxz, dy, g = 9.81) → pitch (low arc) | null
export function computeDamage({ base, power, comboMul = 1, crit = false }) → base·(0.65 + 0.7·power)·comboMul·(crit ? 1.6 : 1)
```
**Attacks and buffering.**
* An attack action that arrives during the current attack is buffered: one slot, the newest
  wins, discarded 0.4 s (pdt) after it arrives.
* The buffered action starts at `max(arrival, start + cancelAfter)`, which cancels the rest of
  the current attack.
* Throws, stagger and the post-throw state can't be cancelled.
* `pause`, `recenter` and `switchWeapon` are never buffered.
* A bash within `TIMING.bash.cooldown` of the previous bash's start is dropped.
* While `fx.cameraOverridden` is true, attack actions are ignored.

**Targets.** A normal slash, thrust, spearThrust or bash damages **one** target: the focus
target if it's inside the attack's sector, otherwise the nearest hostile in the sector. The
sector's halfAngle is acquisition forgiveness, not an area. Only `sweep` and `stagger_all` hit
every hostile in their arc. Sectors are centred on the **attack yaw**.

**Aim assist.** `focusTarget = pickTarget(hostiles, pos, yaw, 0.70, reach + 1.5)`, re-evaluated
every frame.
* **Motion mode**: the camera is never pulled. Instead `attack.yaw = yaw + clamp(angleToFocus, ±0.44)`.
  * Pitch eases toward the focus target's chest at 4 rad/s, clamped to [−0.26, +0.17].
  * Auto-face: if no hostile is within ±1.0 rad of the view, and one engaged with the player is
    winding up, the view turns toward it at 1.5 rad/s. This is needed for attackers from
    behind on Day 3.
* **Touch and keyboard** (if `settings.aimAssist`): during the first 0.1 s of an attack, the
  view yaw is pulled up to 0.21 rad toward the focus target.

**Lunge.** If the focus target is beyond the weapon's reach but within reach + 1.0 m, the
player steps up to 0.8 m toward it over the attack's `strike` time. Positions clamp to
`world.bounds`. In motion mode, the player drifts back to the line slot at 1 m/s after 1.5 s
without attacking.

**Shield.**
* `shieldRaise` moves toward 1 while held, at a rate of 1/shieldRaise, and toward 0 otherwise,
  at 1/shieldLower.
* **Striking from behind the shield**: if the shield is held when a slash, thrust or
  spearThrust starts, it dips to `shieldDip` for that attack's `total`, then returns. Melee
  can't be blocked during the dip. Frontal arrows are still blocked at ≥ 0.4.
* **Block**: `shieldRaise ≥ 0.7`, with the attacker within ±1.31 rad (75°) of the view yaw.
* **Perfect block**: the current hold began ≤ `perfectBlockWindow` before the strike resolves,
  **and** the shield had been down (raise < 0.2) for ≥ `perfectNeedsDown` before that hold.
  It works even if raise < 0.7: the viewmodel snaps the shield up and the player takes 0
  damage.
  * The attacker staggers 1.2 s and loses its guard.
  * The next player hit within `critWindow` is a critical.
  * Score +50.
  * Volleys can't be perfect-blocked.
* **Captain axe (heavy)**:
  * Unblocked: 28 damage.
  * Normal block: a **guard break**. The player takes 8, the shield is forced down and can't
    be raised for 0.8 s, the view is pushed 0.1 rad, and the `block` event has kind
    `guardBreak`.
  * Perfect block: 0 damage, and the captain staggers 1.4 s.

**Bash.**
* A thrust with the shield held, or an explicit bash.
* Reach 1.6 m, halfAngle 0.44 rad, one target (`wall` hits everything within 2.5 m).
* The target staggers 1.1 s, is knocked back 0.9 m along the hit direction, loses its guard,
  and is knocked down if power > 0.75. A wind-up is interrupted.
* An actor bashed within the last 2.5 s only takes damage and a flinch, unless the bash
  completes `wall`.

**Into the Gulf.** If knockback (bash, `drive`, `stagger_all`) carries an enemy past x > 8.6,
it falls off the edge. It plays `die_back` with a fall to y = −3, then a scream and a splash.
It's an instant kill, +150 "Into the Gulf", and `kill.cause = 'sea'` (Herodotus 7.223).

**Enemy guard.** A hit is guarded iff all of these hold:
* `!hit.ignoreGuard`
* the actor's state is not stagger or knockdown
* the hit comes from in front: `dot((sin f, cos f), (−dir.x, −dir.z)) > 0.5`
* `rng.chance(blockChance)`

A guarded hit deals 15% damage and makes a wicker or wood sound. An overhead D slash, a thrust
with power > 0.8, a bash, `breakGuard`, and any hit on a staggered target all ignore guard.

**Health.**
* The player has 100 HP. Enemy damage is in ENEMIES. Arrows deal 8.
* There's no regen within a wave. Respites heal; see §13.
* At low HP the HUD shows a blood vignette and the heartbeat plays.
* In training, damage is a no-op.

**Spears.**
* `spearsLeft = (hasSpearInHand ? 1 : 0) + spares`. Spares start at 2, max 3.
* `switchWeapon('spear')` is allowed iff spearsLeft > 0. If the hand is empty, `spares--`
  and the 0.6 s draw plays.
* After a throw, `hasSpearInHand = false`. After `redraw` (pdt), a spear is drawn if
  spares > 0. Otherwise `weapon = 'sword'` and `weaponSwitch` is emitted.
* `refillSpears()`: spares = 3 and hasSpearInHand = true. In training a spear is always
  redrawn.
* Throw power > 0.7 can skewer two actors.

**Throw.** At release (attack start + `TIMING.throw.windup`, or +0.03 s for motion throws),
player computes:
* `origin = eye + right3(yaw)·0.22 + up·0.10 + forward3(yaw,0)·0.35`
* `speed = lerp(20, 34, power)`
* The target is the living hostile with the smallest view angle within 0.262 rad and ≤ 45 m,
  archers included.
  * With a target: `yawL = yawTo(chest − origin)`, `pitchL = ballisticPitch(speed, dxz, dy)`.
  * Otherwise: `yawL = yaw`, `pitchL = pitch + 0.06`.
* `velocity = speed·forward3(yawL, pitchL)`.

The view is not rotated. player calls `combat.throwSpear({ origin, velocity, power, byPlayer: true, src })`.

**Score** (owned by player):
* A kill scores `round(100·chain)`, with +250 for a spear kill and +150 for into the Gulf.
* The chain starts at 1.0, rises 0.1 per player hit (capped at 5.0), and resets on `playerHurt`.
* Perfect block: +50. A combo adds its `bonus`.
* `scoreChanged` is emitted on every change.

## 8. Combos (game/combos.js)

```js
export const COMBOS = [   // rows; 'either order' combos are two rows with the same id
  { id:'twin',   name:'Twin Fangs',          seq:[Rish, Lish],             mult:1.5 },
  { id:'twin',   name:'Twin Fangs',          seq:[Lish, Rish],             mult:1.5 },
  { id:'fury',   name:'Fury of Lakedaimon',  seq:[Rish, Lish, Rish],       mult:1.8, effect:'sweep', arc:1.75 },
  { id:'fury',   name:'Fury of Lakedaimon',  seq:[Lish, Rish, Lish],       mult:1.8, effect:'sweep', arc:1.75 },
  { id:'storm',  name:'Storm of Bronze',     seq:[Rish, Lish, Rish, Lish], mult:2.4, effect:'sweep', arc:1.75, slowMo:[0.5, 0.4] },
  { id:'storm',  name:'Storm of Bronze',     seq:[Lish, Rish, Lish, Rish], mult:2.4, effect:'sweep', arc:1.75, slowMo:[0.5, 0.4] },
  { id:'cleave', name:"Leonidas' Cleave",    seq:[Uish, Dish],             mult:2.2, breakGuard:true, slowMo:[0.35, 0.5] },
  { id:'xcut',   name:'Cross of Ares',       seq:[{DR}, {DL}],             mult:1.7, bleed:{ dps:6, dur:3 } },
  { id:'xcut',   name:'Cross of Ares',       seq:[{DL}, {DR}],             mult:1.7, bleed:{ dps:6, dur:3 } },
  { id:'answer', name:"The Hoplite's Answer", seq:[{B}, {T}],              mult:1.3, effect:'execute' },
  { id:'drive',  name:'Phalanx Drive',       seq:[{T}, {T}, {T}],          mult:1.6, knockback:2.5 },
  { id:'wall',   name:'Shield Wall',         seq:[{B}, {B}],               mult:1.0, effect:'stagger_all', radius:2.5 },
]   // each row also has desc and bonus = round(100·mult)
export class ComboTracker {
  constructor(table = COMBOS, window = 1.25)
  push(token, t) → row | null   // token: dirToken | 'T' | 'B' | 'J'
  reset()
  get chain()
}
```
**Matching is on the chain's suffix.**
* `push(token, t)`: if `t − lastT > window`, clear the chain first. Append the token (keep at
  most 6).
* Candidates are the rows whose seq matches the last `seq.length` tokens. The longest wins.
  On a tie, the more specific wins: the smaller sum of its step-set sizes, so xcut beats twin.
* After a match of length n, clear the chain **only if** no longer row has its first n steps
  matching the last n tokens. So R,L returns twin, R,L,R returns fury, and R,L,R,L returns
  storm, which then clears.
* `bash` cooldown (0.9 s) is less than the window, so B,B is reachable.

**Who does what.**
* player pushes a token when an attack **starts** (as it emits `swing`), with `t = gnow`. The
  tokens are dirToken for a slash, 'T' for a thrust or spearThrust, 'B' for a bash (including
  shield + thrust), and 'J' for a throw.
* A returned row is attached as `attack.combo`.
* `combat.playerStrike` applies every damage effect:
  * mult, a `sweep` arc centred on the attack yaw, `breakGuard` → ignoreGuard, knockback,
    bleed
  * `execute` on a staggered target
  * `stagger_all` within the radius
* If the strike hits at least one target, player emits `combo` (row + bonus) and adds the
  bonus. If it whiffs, the chain resets.
* fx subscribes to `combo` and calls `slowMo(scale, seconds, { playerRealtime: true })` when
  the row has slowMo.
* Unit tests: R,L,R,L pushed 0.3 s apart returns twin, fury, storm; DR,DL returns xcut; and a
  gap > 1.25 s resets.

## 9. Settings (persisted under `thermopylae.settings`)

```js
{ control: 'touch',          // 'motion' | 'touch'; motion is offered only if supported
  sensitivity: 1.0,          // 0.5–2.0
  gyroLook: true,            // motion mode: turning your body turns the view (settled heading)
  aimAssist: true,           // touch/keyboard camera pull (motion always rotates attacks)
  leftHanded: false,         // mirror the touch layout and synth demos
  haptics: true, voice: true, blood: true, helmetView: true,
  quality: 'auto',           // 'auto'|'low'|'medium'|'high'
  master: 0.9, music: 0.55, sfx: 1.0,
  sensorDebug: false,
  safetyAccepted: false }
```
`thermopylae.best` is `{ score, kills, day }`, written by menus on `gameOver`.

## 10. Frame order and lifecycle (main.js)

**Each frame:**
0. Time:
   * `dt = min(real, 0.05)`.
   * `hitStop` is decremented unless paused or in gameover.
   * gdt, pdt and gnow are computed per §3.2.
1. Motion glue, when `mode ∈ {battle, training}` and (`settings.control === 'motion'` or `ctx.motionDemo`):
   * `poll` → `frames.process` → `gestures.push`. Each event emits `gesture`.
   * slash, thrust and throw events become actions, except while `fx.cameraOverridden`, when
     they are dropped.
   * A `windup` with a spear in hand calls `viewmodel.play('throwWindup')`.
   * `ctx.input.lookYaw += consumeLook().yaw` if `settings.gyroLook` and the camera isn't
     overridden.
   * `gestures.holdLook()` when the spear cam starts, `resumeLook()` when it ends.
   * `input.phoneSettled = gestures.settled`.

   The poll is skipped entirely outside motion mode.
2. `inputCtl.update(dt)`
3. Battle and training run, in order:
   * `player.update(pdt)` (it reads gdt itself where needed)
   * `ai.update(gdt)`
   * `combat.update(gdt)`
   * `waves.update(gdt)` in battle, or `training.update(dt)` in training

   Title and gameover run `ai.update(gdt)` and `player.idleCamera(dt)`.
4. `viewmodel.update(dt, gdt)`, then `fx.update(paused ? 0 : dt, gdt)`.
5. `player.applyCamera(camera)` sets the absolute eye position and rotation. Then
   `fx.applyCamera(camera)` (shake or spear cam), then `camera.updateMatrixWorld()`.
6. `world.update(dt, gdt)`, `audio.update(dt)`, `hud.update(dt)`, `menus.update(dt)`.
7. Render. At init: `renderer.autoClear = false; renderer.info.autoReset = false`. Each frame:
   `info.reset(); clear(); render(scene, camera)`, then, if `mode ∈ {battle, training, paused}`
   and `!fx.cameraOverridden`: `clearDepth(); render(vm.scene, vm.camera)`.

**Modes.**
* `setMode('battle'|'training', opts)` always runs `resetAll(mode)` and then starts the mode,
  unless `opts.resume`.
* `resetAll(mode)` runs, in order:
  * `training.stop`, `waves.stop`, `ai.clear`, `combat.reset`, `fx.reset`, `viewmodel.reset`, `hud.reset`
  * `actions.clear`, `motionFrames.reset`, `gestures.reset`, `platform.voice.stop`
  * `player.reset({ mode })`
* Then `waves.start(opts.day ?? 1)` or `training.start()`.
* `ctx.resume()` = `setMode(pausedFrom, { resume: true })`.
* Every setMode calls `actions.clear()` and `gestures.reset()` and emits
  `modeChanged {mode, prev}` **after** the work.
* Pause menu: Resume → `ctx.resume()`. Restart → `setMode(pausedFrom, { day: waves.day })`.
  Quit → `setMode('title')`.
* Death: player emits `playerDeath`. waves emits `gameOver` 3.2 s later (gdt). main switches
  to `gameover` on `gameOver`.
* `platform.lockOrientation(true)` on entering battle or training with motion, and false
  otherwise.
* nativepause or hidden: in battle or training → `paused`. Then `audio.suspend()` and
  `saveSettings()`.
* Resume: `audio.resume()` only.
* `webglcontextlost`: skip step and render. `webglcontextrestored`: `world.rebuildEnvironment()`.
* `__onBack`:
  * `menus.back()` true → true
  * battle or training → pause → true
  * paused → resume → true
  * title → false

**Boot:**
* Load settings, then `platform.bindSettings`.
* Create the renderer and modules behind the boot screen, which shows progress.
* Pre-warm: one Visual of each kind, the fx emitters and the props, then
  `await renderer.compileAsync(scene, camera)` and the same for vm. Then remove the probes.
* Then `setMode('title')`, set `window.__booted = true`, and hide the boot screen.

## 11. Module APIs

### core/events.js
`export class EventBus { on(name, fn) → off; once(name, fn); off(name, fn); emit(name, payload) }`.
Handlers are isolated: errors go to `console.error` and `window.__game.errors`.

### core/math.js
`TAU, clamp, lerp, invLerp, smoothstep, damp(a, b, lambda, dt), wrapAngle, angleDiff(a, b),
createRng(seed)`, plus `[x,y,z]` helpers (`v3, v3add, v3sub, v3scale, v3dot, v3cross, v3len, v3norm`)
and the yaw helpers in §3.1.

### core/noise.js (PURE)
`createNoise(seed) → { noise2(x,y), noise3(x,y,z), fbm2(x,y,octaves=5,lac=2,gain=0.5), ridge2(x,y,oct) }`.
Values are in about [−1, 1].

### gfx/textures.js
```js
createTextures(ctx) → {
  get(name) → THREE.Texture,         // lazy and cached; canvas-generated; colour maps SRGBColorSpace; RepeatWrapping; anisotropy 4 (8 on high)
  async generateAll(onProgress),     // optional pre-generation behind the boot screen, yielding a frame between textures
}
```
* Names: `dirt`, `dirtNormal`, `rock`, `rockNormal`, `cliffStrata`, `sand`, `bronze`,
  `bronzeNormal`, `linen`, `crimson`, `leather`, `wood`, `wicker`, `wickerNormal`,
  `persianRobeA/B/C` (Achaemenid patterns: rosettes, stepped merlons, stripes), `scale`
  (scale armour), `felt`, `skinGreek`, `skinPersian`, `stone`, `water`, `waterNormal`,
  `cloud`, `blood` (decal alpha), `smoke` (sprite), `spark` (sprite).
* Shield emblems, painted on the bronze: `blazonFly` (the player's: Plutarch, Moralia 234C),
  `blazonGorgon`, `blazonLion`, `blazonBoar`, `blazonClub`, `blazonScorpion`.
* Sizes are 512² by default. dirt, rock and cliffStrata are 1024² on high. Everything is
  256² on low. Total GPU texture memory ≤ 64 MB.
* Never null `texture.image`, since it's needed to re-upload after a context restore.

### gfx/world.js
```js
createWorld(ctx) → {
  groundHeight(x, z) → y,            // fast; −3 over the sea
  bounds: { minX:-7, maxX:8.4, minZ:-6, maxZ:3 },   // player rectangle
  colliders: [ {type:'plane', point:[x,y,z], normal:[x,y,z]} | {type:'box', min:[x,y,z], max:[x,y,z]} ],
  sunDirection: THREE.Vector3,       // unit, toward the sun
  envMap,                            // PMREM texture (also scene.environment)
  setTimeOfDay(phase),               // 'dawn'|'morning'|'noon'|'afternoon'|'dusk': sky, sun colour/angle, fog, hemi, exposure, env map
  setArmyDensity(0..1),
  rebuildEnvironment(),              // after WebGL context restore: regenerate PMREM (dispose the old one)
  update(dt, gdt),                   // water, steam, banners, horde sway; shadow camera follows ctx.camera (snapped to texels)
}
```
* The world subscribes to `volley` and dims the sun and shadows from `loosed` until `end`
  ("fight in the shade"), changing only colours and intensities.
* Content:
  * A rugged cliff wall with strata and scree on the left.
  * An uneven, dusty, trampled floor with rocks and dry grass tufts.
  * A drop to a rocky shore and an animated sea on the right, with sun glints and a far shore.
  * Sulphur hot-spring vents with steam and white-orange mineral stains (the "hot gates").
  * The Phocian Wall behind, with its 4 m gap.
  * Distant mountains.
  * The Persian horde filling the far pass: ≤ 4 InstancedMesh draws of ≤ 16 triangles per
    soldier, with spears, standards and dust.
* Sky: a Preetham `Sky` with `cloudCoverage = 0` on low, and a PMREM env map from it:
  `fromScene(skyScene, 0, 0.1, 1000, { size: high ? 256 : 128 })` with the sun disc hidden.
  It's regenerated only in setTimeOfDay and rebuildEnvironment, and the previous one is
  disposed.
* Lighting: one shadow-casting directional sun (PCFShadowMap; the ortho camera covers ±18 m
  around the player; 1024 map on low/medium, 2048 on high), a hemisphere light, and
  exponential fog for haze.
* Static meshes are merged, with `matrixAutoUpdate = false`. Draw calls ≤ 35, and only props
  within 20 m cast shadows.
* setTimeOfDay and the volley shade must never change program-affecting state: light count,
  castShadow, fog type, or a material's map going between null and set.

### gfx/characters.js
```js
createCharacters(ctx) → {
  create(kind, opts = {}) → Visual,  // kind: 'spartan'|'levy'|'sparabara'|'immortal'|'archer'|'captain'|'dummy'|'helot'
                                     // opts: { variant: 'mede'|'kissian' (levy), blazon (spartan), name, seed }
  createProp(name) → THREE.Object3D, // 'dory'|'arrow'|'persianSpear'|'sagaris' — shared geometry; tip on local +Z, origin at centre of mass
  KINDS,                             // per kind: { height, weapon, shield, attackClip, … }
}
Visual = {
  root,                               // THREE.Group (caller adds it to the scene); feet at origin; faces +Z
  mesh,                               // the SkinnedMesh
  kind,
  sockets: { head, chest, rightHand, leftHand, back },  // Bones
  anim: {
    setLocomotion(speed),             // m/s; 0 = combat idle (breathing, weight shift); blends walk/run cycles; feet don't skate
    setGuard(on),                     // raise shield/weapon guard (upper body layer)
    play(clip, { duration, onStrike, onEnd }),  // one-shot; onStrike at CLIPS[clip].strikeAt·duration; replacing a clip cancels its callbacks
    flinch(dirX, dirZ, strength),     // additive, decays ~0.3 s, never cancels a clip
    get clip(), get playing(), get guard(),
    update(gdt),
  },
  attach(object3d, socketName),       // keeps the object's current world transform (socket.attach)
  setBloodied(0..1),
  setVisible(bool),
  dispose(),                          // removes root from its parent; never disposes shared geometry/materials/textures
}
export const CLIPS = {   // name → { strikeAt, duration (default s), layer:'full'|'upper', loop:false }
  thrust, overhead, slash, axe, bash, shoot, throw, hit, stagger, knockdown, getup, block_hit, cheer,
  die_back, die_forward, die_crumple, die_spear, fall_sea }
```
**Each Visual is ONE `THREE.SkinnedMesh` with rigid skinning** (MUST):
* Every body part, plus the kind's carried weapon and shield, is an indexed BufferGeometry with
  the same attributes (position, normal, uv, skinIndex, skinWeight).
* Each vertex of a part has `skinIndex = [bone,0,0,0]` and `skinWeight = [1,0,0,0]`.
* Parts are merged once per kind with `mergeGeometries(parts, true)` into at most 2 groups:
  * 0: non-metal (skin, cloth, leather, wicker, wood), using one per-kind canvas atlas or the
    shared textures, metalness 0
  * 1: metal (bronze or iron, metalness ~1, using scene.environment)
* Geometry and materials are shared by every instance of a kind (variants may have their own).
* Each instance gets its own bones (pelvis → spine → chest → neck → head; shoulders → upper
  arm → lower arm → hand; thighs → shins → feet) and `mesh.bind(new THREE.Skeleton(bones))`.
* `mesh.frustumCulled = true`, with a fixed `boundingSphere = Sphere((0,0.9,0), 1.7)` that
  covers lying poses.
* Budget per Visual: ≤ 2 draw calls and ≤ 2,500 triangles (≤ 1,200 on low).

Historical kit:
* **Spartan**:
  * Corinthian helmet with a crest
  * crimson cloak (phoinikis), linothorax, bronze greaves
  * a bronze-faced aspis with a `blazon*`
  * dory or xiphos
* **Levy**:
  * Mede: a felt tiara
  * Kissian: a mitra (turban)
  * Both: sleeved tunic, trousers, a quilted or scale coat, a wicker gerron, a short spear,
    and a bow case.
* **Sparabara**: a tall rectangular wicker spara and a short spear.
* **Immortal**:
  * a patterned robe (persianRobe) or scale coat with a headband or tiara
  * a spear with a gold or silver pomegranate butt
  * a bow case on the back
* **Archer**: a composite bow and quiver.
* **Captain**: a Saka axeman, 1.95 m tall, with a tall pointed felt cap, trousers, a scale
  coat, a sagaris and a bow case.
* **Dummy**: a post with a crossbar and a wicker shield. It only wobbles on flinch().
* **Helot**: a plain tunic and a bundle of spears.

Animation is procedural:
* Believable idle, walk and run cycles.
* Attacks with anticipation, strike and follow-through.
* Deaths that end in a lying pose and stay as a corpse.

### gfx/viewmodel.js
```js
createViewmodel(ctx) → {
  setWeapon('sword'|'spear'), setHasSpear(bool),
  setShield(raise 0..1),               // player passes its animated value every frame
  play(kind, opts),                    // also called internally from events; main calls play('throwWindup') on motion windup
  addStuckArrow(), clearStuckArrows(), // max 8 arrows in the aspis
  setVisible(bool), reset(),
  setOverlay({ vignette 0..1, hurt 0..1, helmet bool }),  // the full-screen quad drawn in the vm pass at render scale
  update(dt, gdt),
}
```
* **Event-driven one-shots.** The viewmodel subscribes itself, and player never calls play():
  * `swing` → slash / thrust / spearThrust / bash / throw. A throw plays `throwWindup`, then
    `throwRelease` at TIMING.throw.windup, or at once for motion.
  * `block` with by === 'player' → blockImpact {perfect, guardBreak}
  * `playerHurt` → hurt
  * `weaponSwitch` and the spear redraw → draw
  * `arrowHit` with target 'shield' → addStuckArrow
  * `respite` → clearStuckArrows
* One-shots advance with **pdt** (`ctx.time.pdt`). Sway and breathing use dt.
* Sway comes from the per-frame change in `ctx.player.yaw/pitch`. It never reads
  `ctx.input.look*`.
* vm.camera stays at the origin with identity rotation. Content lives in its space with −Z
  forward.
* Each update, the vm sun direction = `world.sunDirection` transformed by the inverse of
  `ctx.camera.quaternion`. `vm.scene.environment = world.envMap`.
* vm lights have `castShadow = false`. There is no fog and no sky.
* The right hand holds the xiphos or the dory (overhand grip). The left arm carries the aspis
  low-left, partly in view. Raised, it covers the lower-left and centre of the view, and you
  look over its rim.
* **Slash path**: the blade tip travels in the plane z = −0.55 from `−0.35·(cos dir, sin dir)`
  to `+0.35·(cos dir, sin dir)`, crossing the centre at `TIMING.slash.strike`, with a
  motion-trail ribbon.
* A bash punches the shield forward. A throw draws the spear back over the shoulder and whips
  it forward. The viewmodel hides the spear at release.
* The overlay quad (vignette, hurt flash, Corinthian-helmet eye-slit when `helmet`) is drawn
  last in the vm pass with depthTest off. hud drives it via setOverlay.

### gfx/fx.js
```js
createFx(ctx) → {
  blood(point, dir, amount), sparks(point, normal, amount), dust(point, amount), splinters(point, dir, amount),
  splash(point), decal(point, kind = 'blood', size),
  shake(intensity, duration), hitStop(seconds), slowMo(scale, realSeconds, { playerRealtime = false } = {}),
  get cameraOverridden(), get particleCount(),
  applyCamera(camera), update(dt, gdt), reset(),
}
```
* Particles are pooled (≤ 800) in a few `Points`/`InstancedMesh` draws. Decals are capped
  (≤ 60) and recycled. `settings.blood` off means no blood and no decals.
* fx subscribes to `hit`, `kill`, `block`, `playerHurt`, `spearImpact`, `arrowHit`, `combo`,
  `shield` and `spearThrown`.
* **Hit-stop and shake fire only for events involving the player**: hit/kill with byPlayer,
  block with by === 'player', and playerHurt. AI-vs-AI hits get only particles (and audio).
  * `hitStop(s)` sets remaining = max(remaining, s). A new one can't start within 0.08 s of
    the last one ending.
  * Durations: player hit 50 ms, kill or crit 85 ms, block 35 ms, perfect 110 ms,
    playerHurt 60 ms.
  * Shake is in radians: hit 0.006, kill 0.010, bash 0.012, hurt 0.025, guardBreak 0.035,
    lasting 0.12–0.3 s, and ×0.5 in motion mode.
* **Gameplay haptics are owned by fx**, through `platform.haptics`, at the strike moment:

  | event | haptic |
  |---|---|
  | flesh hit by player | 22 ms / 0.7 |
  | kill | 40 ms / 1.0 |
  | absorbed by enemy guard | [0,12,30,12] |
  | player block | 30 ms / 0.8 |
  | perfect block | [0,15,25,45] |
  | hurt | 70 ms / 1.0 |
  | shield up | 6 ms / 0.3 |
  | spear release | [0,25,60,25] |
  | spear impact | [0,30,40,60] |
  | volley warning | [0,20,90,20] |

  In motion mode the player can't see the screen mid-swing, so haptics and sound are the main
  confirmation channel.
* **Spear cam** (fx is the only caller; it starts on `spearThrown` with `projectile.byPlayer`).
  Times are in **real** seconds, and frozen while paused.
  * **Motion throw**: scale 0.1 while trailing the spear, until `ctx.input.phoneSettled` has
    been true for 0.1 s, or for at most 1.4 s. Then ramp over 0.15 s to
    `scale = clamp(remainingFlightGameTime/1.6, 0.3, 1.0)`.
  * **Touch or key throw**: no hold. Scale is set so the flight lasts about 1.2 s.
  * Trail position: `spearPos − velDir·min(1.6, distanceFlown) + up·0.35·smoothstep(0, 0.25, t)`,
    so the shot starts at the eye.
  * On `spearImpact`: freeze 0.12 s, orbit the impact for 0.7 s at scale 0.2, then ease back to
    the eyes over 0.35 s while the scale ramps to 1.
  * A miss, the spear going `alive = false`, game age > 3 s, or `pos.y < −2.5`: ease back at once.
  * While the cam is active, other slowMo calls are ignored. At the end it sets scale = 1 and
    playerRealtime = false.
  * fx emits `spearCam {phase:'start'|'impact'|'end'}`.

### audio/audio.js
```js
createAudio(ctx) → {
  unlock(),                                 // from the first user gesture (menus call it)
  play(name, { volume = 1, rate = 1, at: Vector3|null } = {}),
  setAmbience('battle'|'calm'|'none'), setMusic('title'|'battle'|'lastStand'|'none'),
  applyVolumes(), suspend(), resume(), update(dt),
}
```
* Everything is synthesized with WebAudio, rendered once to AudioBuffers at startup or lazily.
  There are no audio files.
* `at` gives stereo pan and distance falloff relative to the camera. AI-vs-AI events play at
  volume 0.5.
* SFX names: `whoosh`, `whooshHeavy`, `clang`, `parry`, `guardBreak`, `flesh`, `fleshHeavy`,
  `wicker`, `bash`, `shieldUp`, `spearThrow`, `spearWhistle`, `spearHit`, `spearGround`,
  `volley`, `arrowThud`, `arrowShield`, `hurt`, `death`, `grunt`, `scream`, `splash`, `warcry`
  ("Alalai!"), `horn` (Persian), `drum` (Persian), `comboSting`, `heartbeat`, `step`, `uiTap`,
  `uiConfirm`.
* Bronze uses inharmonic modal partials. Flesh is a low thump plus noise.
* Music: Greek themes are aulos with a sung paean drone (no drum). Persian drums and horns
  belong only to the battle ambience, with the sea, wind and the army's din.
* audio subscribes to bus events itself, including `voice`: it calls
  `platform.voice.say(text)` with speaker-specific pitch. **No other module calls
  platform.voice.say.**
* `enemyWindup` with targetIsPlayer plays `grunt` at the actor (louder and lower for the
  captain).

### game/combat.js
```js
createCombat(ctx) → {
  playerStrike(attack) → hits[],   // attack: { kind:'slash'|'thrust'|'spearThrust'|'bash', dir, power, yaw, origin, combo, crit }
  actorStrike(attacker, attack) → 'hit'|'block'|'perfect'|'guardBreak'|'miss',
        // attack: { kind:'melee'|'axe', damage, reach, halfAngle, heavy, target:'player'|Actor }
  throwSpear({ origin, velocity, power, byPlayer, src }) → projectile,
  shootArrow(fromActor, target),   // single aimed arrow (archers in training/kolonos); resolves like a volley arrow
  volley({ count = 300, duration = 1.0, warn = 2.5, fromBehind = false }),
  hostilesInSector(origin, yaw, reach, halfAngle, team = 'persian') → actors by distance,
  reset(), update(gdt),
}
```
* **actorStrike**: if the target isn't inside `sectorHit(attacker.pos, facingToYaw(attacker.facing), reach + 0.3, halfAngle)`,
  the result is 'miss'.
  * **Player target**: `player.tryBlock(attacker, kind)` is a query with no side effects. It
    returns 'perfect' | 'block' | 'none'.
    * Perfect → emit block{perfect:true}, and call `attacker.takeHit({ damage:0, stagger:1.2, ignoreGuard:true, … })`.
    * Block → emit block. A heavy attack is a guardBreak: `player.guardBreak()` plus 8 damage.
    * Otherwise → `player.damage(damage, { from: attacker, kind })`.
  * **Actor target**: `target.takeHit(…)`, then emit `hit` and possibly `kill`.
* **Projectile** (combat owns it and mutates it in place):
  `{ kind:'spear'|'arrow', mesh, pos, prevPos, vel, alive, stuck, byPlayer, power, src, target, hits:[], age, impactPoint }`.
  * Meshes come from `characters.createProp`, oriented with `quaternion.setFromUnitVectors(+Z, velDir)`.
  * It is integrated with gravity and collides against actor capsules (segment vs capsule),
    `world.groundHeight` and `world.colliders`.
  * A spear that hits an actor is reparented with `visual.attach(mesh, 'chest'|'head')`. A
    missed spear sticks in the ground (at most 12 kept). A spear over the sea sinks and
    emits `fx.splash`.
* **Volley**:
  * Emits `volley` 'warning' at once, 'loosed' at `warn`, 'impact' at warn + 2.0, and 'end' at
    warn + 2.0 + duration.
  * `count` is the number of **visual** arrows: instanced, falling on the Greek line, with
    ground arrows kept up to 200.
  * Up to min(3, ceil(count/100)) arrows resolve against the player: blocked if
    `shieldRaise ≥ 0.4` and `!fromBehind` (arrowHit 'shield'), otherwise 8 damage each.
  * Each ally takes at most 1 arrow and blocks it with probability 0.8.
* **Bleed**: damage over time on actors (combo xcut).
* combat emits `hit`, `kill`, `block`, `spearThrown`, `spearImpact`, `arrowHit` and `volley`.

### game/ai.js
```js
createAI(ctx) → {
  spawnEnemy(kind, x, z, opts) → Actor,   // opts: { facing, delay, role, variant, trainer (0 dmg, windup 1.2, every 3 s), fromBehind }
  spawnAlly(x, opts) → Actor,             // x = world x of the line slot; opts: { name:'Leonidas', blazon }
  ensureAllies(xs),                       // spawn only into empty slots
  spawnDummy(x, z, opts) → Actor,         // opts: { far } ; team 'persian', role 'dummy', never dies
  spawnHelot(),                           // cosmetic runner from z=+10 to the player and back with a spear bundle
  killAlly(name),                         // scripted death (Leonidas, Day 3)
  retreatArchers(),                       // archers walk to z=−70 and are removed (no corpse)
  clear(), update(gdt),
  get enemiesAlive(), get archersAlive(), get alliesAlive(), get playerAttackers(),
}
Actor = {
  id, kind, team: 'greek'|'persian', role: 'melee'|'archer'|'dummy'|'helot'|'ally', name,
  visual, pos: THREE.Vector3 (feet; y = ground), facing, hp, maxHp, radius: 0.32, height, alive,
  state: 'idle'|'advance'|'hold'|'engage'|'windup'|'recover'|'stagger'|'knockdown'|'retreat'|'dead',
  guard,                                  // animated 0..1
  blockChance, staggerT, lastBashedAt,
  takeHit(hit) → { damage, guarded, killed },
     // hit: { damage, kind, dir:{x,z} (unit, attacker→target), power, ignoreGuard, knockback, stagger, knockdown,
     //        byPlayer, critical, point, attacker }
  chestPoint(out) → Vector3,
}
```
* `spawn*` adds `visual.root` to the scene and pushes the actor onto `ctx.actors`.
* Each update sets `root.position = (pos.x, groundHeight, pos.z)` and
  `root.rotation.y = facing`, then calls `anim.update(gdt)`.
* `takeHit` applies guard, damage, flinch, stagger, knockdown and the sea fall, and sets
  `alive = false` at once on a kill. ai moves dead actors from `ctx.actors` to its corpse list
  at the start of its next update.
* Corpses: at most 20 (10 on low), with castShadow off and the animator no longer updated
  after the death clip. The oldest sink 1 m over 3 s and are disposed.
* `enemiesAlive` counts only `role === 'melee'` Persians.
* **Director** (DIRECTOR by day; on Days 1–2 the rate is ×0.7 while player HP ≤ 25):
  * A token bucket limits the rate of player-targeted wind-ups.
  * Concurrent player-targeted wind-ups are capped, and successive strike moments on the
    player are at least `gap` apart.
  * A newly engaged enemy waits 0.8–1.4 s before its first wind-up.
  * `timeToStrike = windup × (1.15 in motion mode) × (0.9 on Day 3)`. The clip plays with
    `duration = timeToStrike / CLIPS[attackClip].strikeAt`, so onStrike lands exactly on time.
  * No new player-targeted wind-ups start while `fx.cameraOverridden`. Wind-ups already in
    progress get +0.5 s after `spearCam` ends.
* **Engage slots** are bearings relative to the line (−Z), not the camera: 0° and ±35° at
  1.5–1.7 m, with ±65° added only on Day 3.
* The rest fight allies, or wait in loose ranks.
* Enemies advance in loose ranks, attack allies along the line, react to hits and die with
  varied clips.
* Archers stay at z < −25 and shoot at allies, or at the player only through volleys.
* Allies hold slots at x = ±1.4, ±2.8, ±4.2 and +5.6 on z ≈ 0, fight enemies in front of them,
  block, and can die.
* Emits `enemyWindup {actor, timeToStrike, targetIsPlayer}`.

### game/player.js
```js
createPlayer(ctx) → {
  pos, yaw, pitch, hp, maxHp, weapon, spares, hasSpearInHand, spearsLeft, shieldRaise, state, alive,
  kills, score, chain, combos (ComboTracker), focusTarget, attackYaw,
  reset({ mode }),          // 'battle'|'training'|'title'
  update(pdt),              // drain actions, move (input + lunge + drift), attack timeline, shield, aim assist, spear draw
  applyCamera(camera),      // absolute: (pos.x, groundY + 1.66 + bob, pos.z), rotation (pitch, yaw, roll) order YXZ, hurt flinch
  idleCamera(dt),           // title/gameover: eye at (0, 1.66, 1.5), slow ±0.25 rad pan
  tryBlock(attacker, kind) → 'perfect'|'block'|'none',
  damage(amount, info),     // applies hp, resets chain, emits playerHurt, then playerDeath at 0; no-op in training or when dead
  guardBreak(), push(dirX, dirZ, metres),
  heal(amount), refillSpears(), recenter(),
}
```
player emits `swing`, `combo`, `playerHurt`, `playerDeath`, `scoreChanged`, `weaponSwitch` and
`shield`. `shield {up}` fires when shieldRaise crosses 0.7 going up or 0.5 going down. It
calls `viewmodel.setWeapon / setHasSpear / setShield` every frame.

### game/waves.js
```js
createWaves(ctx) → { start(day = 1), stop(), update(gdt), get day(), get phase(), get wave(), get state() }
// state: 'intro'|'fighting'|'respite'|'dayEnd'|'over'
```
It's data-driven (§13). It emits `dayStart`, `waveStart`, `waveCleared`, `respite`, `voice`
and `gameOver`. It calls `combat.volley`, `ai.*`, `player.heal/refillSpears`,
`world.setTimeOfDay` and `audio.setMusic`.

### ui/hud.js, ui/menus.js, ui/input.js, ui/training.js
* Each UI module creates **one root** under `ctx.overlay`: `#hud` (z 10, pointer-events none),
  `#controls` (input.js, z 20) and `#menus` (z 30).
* **HUD** (`createHud(ctx) → { update(dt), reset(), banner(text, sub, seconds) }`):
  * It polls `ctx.player` (hp, weapon, spearsLeft, score, chain, kills, focusTarget) and
    `ctx.fx.cameraOverridden` every frame, and uses events only for transient effects.
  * It shows a health bar, damage-direction arcs (rotated by −relYaw from screen-top), and a
    red wind-up chevron toward each player-targeted wind-up.
  * Combo name popups are in a carved-stone Greek style. It also shows score/chain, kills and
    spear icons.
  * Banners: day, phase and wave title cards, a respite countdown with a **READY** button
    (bottom-centre, ends the respite after ≥ 6 s), voice subtitles with the speaker's name,
    and "SHIELDS UP!" on volley warning.
  * A letterbox during the spear cam. Low-HP vignette, hurt flash and helmet slit go through
    `viewmodel.setOverlay`.
  * An optional sensor debug meter (`gestures.debug`).
  * DOM text is updated only on change, and only transform/opacity are animated. No
    filter/backdrop-filter.
* **Menus** (`createMenus(ctx) → { update(dt), back() → bool, show(name) }`):
  * Title: THERMOPYLAE, "480 BC", and the epigraph *"With it, or on it."* (a Spartan mother
    handing her son his shield, Plutarch, Moralia 241F). Buttons: Fight (Day 1, or continue
    from the highest day reached), The Agoge (training), Controls (Motion/Touch), Settings,
    How to play.
  * Settings (§9). "Quality: applies after restart" for antialias.
  * Pause: Resume / Restart / Settings / Quit.
  * Game over:
    * the day reached, kills, score and best
    * Simonides' epitaph: *"Go tell the Spartans, stranger passing by, that here, obedient to
      their laws, we lie."*, attributed to Simonides (Herodotus 7.228)
  * The first time Motion is chosen, a safety notice: *hold the phone firmly, use a wrist
    strap, clear the space around you, **never let go when throwing**, a firm flick is enough*.
    Accepting sets `safetyAccepted`.
  * The first click calls `audio.unlock()`. Choosing Motion calls
    `platform.motion.requestPermission()` and `isSupported()`, and falls back to touch with a
    message if motion is unavailable.
  * menus writes `thermopylae.best` on `gameOver`.
* **Input** (`createInput(ctx) → { update(dt), reset() }`): §6. It handles `pause` by calling
  `ctx.setMode('paused')`.
* **Training, the Agoge** (`createTraining(ctx) → { start(), stop(), update(dt), demo(stepId), get active(), get stepIndex() }`):
  * The place is a practice yard: the same pass, in the morning, with no army.
  * start() spawns a dummy at (0, −2.6) and a far target dummy at (1.5, −18).
  * Steps complete on events, each after N repetitions, with a Skip button:
    0. Grip and safety (confirm).
    1. Calibrate: "Slash 5 times as in battle". `sensitivity = clamp(9/median(gesture.peak), 0.6, 1.8)`,
       with Apply/Keep buttons. Touch mode skips this step.
    2. Slash right ×2 and left ×2.
    3. Overhead ×2. The dummy has a wicker shield, showing the guard bypass.
    4. Uppercut ×1, starting low.
    5. Thrust ×3.
    6. Block 3 telegraphed strikes from `spawnEnemy('levy', 0, −1.6, { trainer: true })`.
    7. Perfect block ×2.
    8. Shield bash ×2.
    9. Survive a volley with the shield up.
    10. Switch to the spear and thrust ×2, then throw ×1 so it lands within 2 m of the far
        target.
    11. Twin Fangs ×1.
  * Each step shows the last detected gesture, an arrow (from `gesture` in motion or `swing` in
    touch), live sensor meters and a sensitivity slider.
  * **Demo** button: `ctx.motionDemo = true`, then `platform.motion.inject(synthesize(script, { rot: current rotation, hand }))`,
    then clear the flag after `scriptDuration + 0.3 s`. This works in touch mode too.
  * Training emits `voice` (Narrator) for instructions.

## 12. Events (EventBus)

| event | emitter | payload |
|---|---|---|
| `gesture` | main | the GestureEvent |
| `swing` | player | `{ kind:'slash'|'thrust'|'spearThrust'|'bash'|'throw', dir, power, weapon, src }` (attack start) |
| `combo` | player | combo row + `{ bonus }` (only when the completing attack hit) |
| `hit` | combat | `{ target: Actor, attacker: Actor|'player', damage, kind, dir:{x,z}, point, guarded, critical, killed, byPlayer, combo: id|null }`. Every damage to an Actor; never for the player. |
| `kill` | combat | `{ target, attacker, byPlayer, kind, spear: bool, cause: 'weapon'|'sea'|'bleed' }` |
| `block` | combat | `{ by: 'player'|Actor, attacker, point, perfect, kind: 'melee'|'axe'|'guardBreak' }`. A fully stopped attack only; a guarded actor hit is just `hit{guarded:true}`. |
| `playerHurt` | player | `{ damage, hp, from: Actor|null, kind:'melee'|'axe'|'arrow', relYaw }` (relYaw > 0 = source on the player's left) |
| `playerDeath` | player | `{}` |
| `scoreChanged` | player | `{ score, chain, kills }` |
| `weaponSwitch` | player | `{ weapon }` |
| `shield` | player | `{ up }` |
| `enemyWindup` | ai | `{ actor, timeToStrike, targetIsPlayer }` |
| `spearThrown` | combat | `{ projectile }` |
| `spearImpact` | combat | `{ projectile, target: Actor|null, point, normal, water: bool }` |
| `spearCam` | fx | `{ phase: 'start'|'impact'|'end' }` |
| `arrowHit` | combat | `{ target: 'player'|'shield'|Actor|null, point }` |
| `volley` | combat | `{ phase: 'warning'|'loosed'|'impact'|'end', duration }` |
| `dayStart` / `waveStart` / `waveCleared` / `respite` / `phase` | waves | `{ day, phase, wave, title, subtitle, seconds }` |
| `voice` | waves, training | `{ text, speaker: 'Leonidas'|'Dienekes'|'Trachinian'|'Herald'|'Lookout'|'Narrator', greek? }` |
| `gameOver` | waves | `{ day, wave, kills, score }` |
| `modeChanged` | main | `{ mode, prev }` |

## 13. Campaign (waves.js data)

```js
const DAYS = [
 { day:1, time:'morning', title:'The First Day', subtitle:'The Medes and Kissians', allies:true, waves:[
   { levy:8, alive:4 }, { levy:12, alive:5 }, { levy:12, archer:2, alive:6, volleyEvery:0 },
   { levy:6, sparabara:6, alive:6 } ] },
 { day:1, time:'dusk', title:'The Immortals', subtitle:"Hydarnes leads the King's Ten Thousand", waves:[
   { immortal:8, archer:3, volleyEvery:30, alive:6 },
   { immortal:10, captain:1, archer:3, volleyEvery:24, alive:7 } ],
   endCard:'Three times, it is said, the King leapt from his throne in fear for his army.' },   // Herodotus 7.212
 { day:2, time:'noon', title:'The Second Day', subtitle:'The Greeks hold the pass city by city', allies:true, waves:[
   { levy:8, sparabara:6, archer:3, volleyEvery:26, alive:7 },
   { immortal:8, sparabara:4, captain:1, archer:3, volleyEvery:22, alive:8 },
   { levy:6, immortal:8, captain:1, archer:4, volleyEvery:20, alive:9 } ] },
 { day:3, time:'dawn', title:'The Third Day', subtitle:'The Anopaia path', phases:[ /* below */ ] },
];
```
* Spawning: one enemy every 1.2–2.0 s until `alive` is reached, at z = −35…−45 and
  x ∈ [−6, 7]. A wave clears when all its melee enemies are dead. Archers then retreat.
* The first volley comes ≥ 12 s after waveStart. After that, one comes every `volleyEvery`
  seconds.
* **Respite**:
  * 25 s in motion mode, 15 s otherwise. READY ends it after 6 s.
  * Heals 35%, refills spears, and sends the helot.
  * Calls `ensureAllies` before Day 3.
  * The Day 2 respite line: "The Tegeans take the line." (Herodotus 7.212).
* `dayStart` heals to 100% and restores allies on Days 1–2.
* **Day 3** (Herodotus 7.219–225). The title card is at dawn, then the battle switches to morning.
  * **Phase A, "Into the wider pass"**:
    * 3 waves of levy+immortal: 14, 16 and 18 enemies, alive 10.
    * volleyEvery 18 → 16 → 14.
    * Respites of 12 s heal 20% and refill spears. Allies are not replaced.
  * **Phase B, "Over Leonidas' body"**:
    * Leonidas is the ally at x = −1.4. He falls at the start of the phase (`killAlly('Leonidas')`).
    * 4 surges of 8 immortals + 1 captain, with 6 s lulls and no heal. The HUD shows "Driven
      back (n/4)".
    * Spears are no longer refilled: "Our spears are broken. Swords!" (7.224).
  * **Phase C, "The Kolonos"**:
    * A 2 s fade. Enemies come from the front, and the Immortals come **from behind**: they
      spawn at z = +30…+40 and enter through the wall gap.
    * Volleys come every 10 s, shrinking by 1 s down to 4 s. Volleys from behind can't be
      blocked.
    * Sword only, no heal. It ends at death.
    * The card text: "They defended themselves with swords, those who still had them, and with
      hands and teeth." (7.225)
* **Voice lines** (speaker: text, trigger):
  * Day 1 intro:
    * Herald: "The King commands: hand over your arms."
    * Leonidas: "Come and take them." (subtitle shows ΜΟΛΩΝ ΛΑΒΕ; Plutarch, Moralia 225C–D)
  * First volley warning:
    * Trachinian: "Their arrows will hide the sun!"
    * Dienekes: "Good. Then we shall fight in the shade." (Herodotus 7.226)
  * In battle (Leonidas): "Hold the line!", "Shields up!" (every volley warning), and "Alalai!"
    (the warcry, at waveStart).
  * Day 1 dusk: Lookout: "The Immortals! Hydarnes leads them."
  * Day 3 dawn:
    * Lookout: "The Phocians have fled the Anopaia path! Ephialtes has led the Immortals over
      the mountain!"
    * Leonidas: "Eat your breakfast as men who will dine in Hades." (Plutarch; Diodorus 11.9.4)

## 14. Test hooks (main.js)

URL parameters:
* `manual=1`: no RAF loop; tests call `__game.step()`. preserveDrawingBuffer is on for screenshots.
* `seed=N`
* `quality=low|medium|high`
* `mode=battle|training`
* `day=N`
* `control=touch|motion`
* `debug=1`
* `nohud=1`

`window.__game = { ready, errors: [], ctx, step(frames = 1, dt = 1/60), render(), action(a), injectMotion(raw[]), synth(script, opts),
startBattle(day), startTraining(), spawn(kind, x, z, opts), stats() → { fps, calls, triangles, actors, particles, renderScale } }`

## 15. Performance and renderer configuration

The target is 60 fps on a mid-range 2021+ Android phone.
* **Renderer**:
  * `new THREE.WebGLRenderer({ canvas, antialias: quality !== 'low', powerPreference: 'high-performance', precision: 'highp', alpha: false, stencil: false, depth: true, preserveDrawingBuffer: manual })`
  * `outputColorSpace = SRGBColorSpace`, `toneMapping = ACESFilmicToneMapping`
  * `shadowMap.type = THREE.PCFShadowMap`. PCFSoftShadowMap is removed in r186. Don't use VSM.
  * Never mediump.
* **Pixel ratio** = `min(devicePixelRatio, {low:1.0, medium:1.25, high:1.5}[quality]) × scale`,
  with `scale ∈ [0.6, 1.0]`.
* **Frame pacing**: the RAF callback returns without stepping when `now − lastRendered < 9.5 ms`,
  so 120 Hz screens run at 60.
* **Dynamic resolution**:
  * Track the mean interval M over each 1 s window.
  * M > 20 ms: scale −0.1 (min 0.6).
  * M < 17.5 ms for 5 consecutive windows, with the last decrease > 10 s ago: scale +0.05.
  * At most one change per second. Ignore the 2 s after a mode change, time-of-day change,
    resume or pre-warm.
* **Quality 'auto'**: medium, or low if `hardwareConcurrency ≤ 4` or `deviceMemory ≤ 3`.
* **Budget per frame**: ≤ 200 draw calls summed over the shadow, main and vm passes (main
  ≤ 140, shadow ≤ 45, vm ≤ 15), main ≤ 350k triangles, shadow ≤ 100k.
  * 22 actors × 2 + 20 corpses × 2 + world ≤ 35 + horde 4 + fx 8 + projectiles 8 ≈ 139.
  * Only world props and actors within 20 m cast shadows (the nearest 6 on low).
* **Never change program-affecting state at runtime** (§11 world). Pre-warm with
  `compileAsync` behind the boot screen.
* The DOM HUD stays small. Full-screen effects go through the vm overlay quad.
* ≤ 14 active enemies and 8 allies. The rest of the army is instanced.

## 16. Build and CI

* `settings.gradle.kts`: `include(":spartan")`.
* `spartan/build.gradle.kts` mirrors `app/build.gradle.kts`, with namespace and applicationId
  `com.thermopylae` and no dependencies.
* `spartan/package.json` is `{ "private": true, "type": "module" }`.
* `.github/workflows/android.yml`:
  * After setup-gradle, `actions/setup-node@v4` (node 22) and
    `node --test spartan/tests/unit/*.test.mjs`.
  * Build both APKs: `./gradlew :app:assembleDebug :spartan:assembleDebug`.
  * Copy `spartan/build/outputs/apk/debug/spartan-debug.apk` → `Thermopylae.apk`.
  * Upload it as an artifact and add it to the release `files:` next to RoccosQuest.apk, with
    a line in the release body.
* `tests/unit/modulegraph.test.mjs` registers a resolve hook (`node:module` `register`) that
  maps `three` and `three/addons/*` to the vendored files. It then imports every `.js` under
  `assets/game/js` except main.js, and fails on any error.
