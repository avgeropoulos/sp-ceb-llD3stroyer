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

You can play with **touch controls** (always landscape), or with **motion controls** in one of
two **grips**:
* **Hilt grip** (portrait): the phone is held upright in the fist like a sword's handle, screen
  toward you, and the blade comes out of its top edge.
  * Swing the fist to slash in that direction, punch forward to stab.
  * Press the glass with the thumb to raise the shield; punch with it up to **shield bash**.
  * The player can't turn his body to look, so the view **locks onto a foe** and turns by itself;
    a thumb swipe moves the lock to the next foe that way.
  * With the spear, **cock the phone back behind the shoulder** and snap it forward to throw.
* **Window grip** (landscape): the phone is held sideways and looked through, and the blade
  sticks out of its back.
  * Swing the phone like a sword to slash in that direction, push it forward to stab.
  * Press your thumb on the screen to raise the shield. Push forward with the shield up to
    **shield bash**.
  * Turn your body to look around.
  * With the spear, make a real overhand **throwing** motion to throw it.

The hilt grip is the default for new motion players once it is released (§9 `HILT_RELEASED`); the
window grip stays in Settings. In both grips the camera follows a thrown spear in slow motion
until it hits, and time stays almost still until the phone is back at guard, where the player
can watch.

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
  src/main/java/com/thermopylae/ MainActivity.kt, AssetServer.kt, NativeBridge.kt, HostLogic.kt (pure rules), AppUpdater.kt, UpdateFileProvider.kt
  src/main/res/                  strings, vector launcher icon
  src/main/assets/game/
    index.html                   engine check, import map, <canvas id="scene">, #overlay, js/main.js
    style.css                    all HUD/menu/controls styling
    lib/three/                   three.module.js, three.core.js, addons/Sky.js, addons/BufferGeometryUtils.js. DO NOT EDIT.
    audio/voice/*.ogg            the Greeks' recorded voice lines (mono Ogg Opus, listed in js/audio/voicebank.js)
    audio/music/*.ogg            the music cues title, battle, lastStand (seamless 48 kHz stereo Ogg Opus loops, listed in js/audio/musicbank.js)
    js/
      main.js                    bootstrap, ctx, loop, mode state machine, motion glue (lead)
      core/events.js             EventBus (lead)
      core/math.js               PURE helpers, yaw helpers, seeded RNG (lead)
      core/grip.js               PURE: the grips (hilt | window), their settings keys, HILT_RELEASED
      core/noise.js              PURE value/simplex noise + fbm
      platform.js                native bridge or web fallbacks
      motion/frames.js           PURE: device→screen remap, gravity/up estimation
      motion/gestures.js         PURE: GestureRecognizer
      motion/synth.js            PURE: kinematic phone simulator (tests + training demo)
      game/rules.js              PURE: timings, weapon/enemy stats, damage, hit geometry, targeting, ballistics
      game/combos.js             PURE: ComboTracker + COMBOS table
      game/lockon.js             PURE: the hilt grip's lock-on (scoring, switching, yaw spring)
      game/player.js             PlayerController
      game/combat.js             attack resolution, blocking, projectiles, volleys, bleeds
      game/ai.js                 actors (enemies, allies, dummies, helot), brains, director, corpses
      game/waves.js              campaign: days, phases, waves, respites, voice lines
      gfx/framing.js             PURE: the world camera's FOV per aspect (landscape and portrait)
      gfx/textures.js            procedural canvas textures
      gfx/world.js               terrain, cliffs, sea, sky, lighting, wall, horde, props
      gfx/characters.js          skinned actor models + procedural animator + weapon props
      gfx/viewmodel.js           first-person arms, sword, spear, shield (+ full-screen overlay quad)
      gfx/fx.js                  particles, decals, shake, hit-stop, slow-mo, spear cam, gameplay haptics
      audio/audio.js             WebAudio synthesized SFX, ambience, music, voice dispatch
      audio/voicebank.js         GENERATED by tools/voice/render.py: the recorded lines (variants, Greek, English, clips)
      audio/musicbank.js         GENERATED by tools/music/render.mjs: the music cues (file, dur, samples, bpm, lufs)
      audio/voicelines.js        PURE: picks a line's variant and clip for a speaker
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
* **Starting orientation**, in `onCreate` before `setContentView` (so before the WebView exists):
  `requestedOrientation = HostLogic.requestedOrientation(false, storedOrientation(), false)`, where
  `storedOrientation()` reads the family the game last asked for from the host's
  SharedPreferences (`HostLogic.HOST_PREFS = "thermopylae.host"`, key
  `HostLogic.PREF_ORIENTATION = "orientation"`, `"landscape"` if none or unreadable). A hilt
  player's next launch starts in portrait, boot screen included. The family is passed on as
  `NativeBridge(activity, initialOrientation)`.
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
  * ogg and opus → audio/ogg
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
  on the main looper (for `Display.DEFAULT_DISPLAY`), in onResume and in MainActivity's
  `onConfigurationChanged` (a rotation):
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
  * `lockOrientation(lock: Boolean, orientation: String?)`, **exactly 2 parameters** (Android
    finds a bridge method by name and argument count; `motion-platform.test.mjs` and the fake
    bridges check the arity). The JavaBridge thread normalizes the orientation with
    `HostLogic.orientationFamily` (`"portrait"`, or `"landscape"` for anything else). The UI thread
    then sets `requestedOrientation = HostLogic.requestedOrientation(lock, family, displayLandscape)`,
    with `displayLandscape = resources.configuration.orientation == ORIENTATION_LANDSCAPE`, and,
    when the family changed, persists it with `apply()` for the next onCreate:
    ```kotlin
    fun requestedOrientation(lock: Boolean, orientation: String?, displayLandscape: Boolean): Int = when {
      orientation == "portrait" -> 1   // SCREEN_ORIENTATION_PORTRAIT: portrait-primary, locked or not, never reverse
      !lock -> 6                       // SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      displayLandscape -> 14           // SCREEN_ORIENTATION_LOCKED: keep the landscape side the display shows
      else -> 0                        // SCREEN_ORIENTATION_LANDSCAPE: still portrait (the grip was just changed)
    }
    ```
    The side is never chosen from the rotation number (rotation 0 is landscape on
    natural-landscape tablets). There is no JVM test source set in `:spartan`, so
    `hilt-platform.test.mjs` checks the branch order in the Kotlin source.
  * `onNewPage()` (first load, reload, renderer recovery) re-requests `(false, lastOrientation)`:
    the page keeps the family the game last asked for, unlocked, until it asks again.
  * `exit()`. Calls finish() via runOnUiThread.

**Manifest:**
* Permissions: `VIBRATE`, `HIGH_SAMPLING_RATE_SENSORS`, `INTERNET` (WebView needs it; the only
  traffic is the updater's) and `REQUEST_INSTALL_PACKAGES`.
* `<provider .UpdateFileProvider authorities="com.thermopylae.updates" exported="false" grantUriPermissions="true">`.
* `<queries>` for `android.intent.action.TTS_SERVICE`.
* Activity:
  * `screenOrientation="sensorLandscape"`: only the cold-start default of a first install (touch,
    landscape), with a comment saying so. Android draws the starting window in it, so a hilt
    player sees one rotation of the black starting window at launch (preferable to flashing every
    touch player). A rotation never recreates the Activity or reloads the WebView
    (`configChanges` below); the cutout insets are re-sent after it, and `onConfigurationChanged`
    refreshes `rotationDeg`.
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

### 2.2 App updates

Installs update themselves in one tap, through Android's own package installer. Game code is
only ever loaded from the APK: the updater downloads a whole APK and Android installs it after
the player confirms, checking that it is signed with the same key.

* **Signing.** Every build (debug and release, CI and local) is signed with
  `spartan/signing.keystore` (alias and passwords `thermopylae`). It is a convenience key for
  this hobby project, committed on purpose; a store release would need a private key.
* **Version.** `versionCode` = `GITHUB_RUN_NUMBER` (1 locally), `versionName` = `1.<code>`.
* **Manifest URL.** A string resource `update_manifest_url` =
  `https://github.com/<repo>/releases/download/latest-<branch>/thermopylae-update.json`, empty for
  local and pull-request builds (the updater is then off).
* **CI** writes `thermopylae-update.json` = `{versionCode, versionName, apkUrl, sha256, size,
  notes}` for the APK it just built and publishes it to the same release **after** the APKs.
* **AppUpdater** (Kotlin):
  * Trusts only `https://github.com/…` and `https://*.githubusercontent.com/…` URLs
    (`HostLogic.isTrustedDownload`), for the manifest and the APK.
  * `check()`, 4 s after the first page load: fetches the manifest (64 KB cap). A newer
    `versionCode` → `available` (or `ready` if a matching APK is already downloaded); otherwise
    `current`, and any old download is deleted. Network errors are silent (`idle`).
  * `install()`: downloads to `cacheDir/updates/Thermopylae.apk` (48 MB cap), checks size and
    SHA-256, then opens the installer with `ACTION_VIEW` on
    `content://com.thermopylae.updates/Thermopylae.apk` (`UpdateFileProvider`, read-only, not
    exported, one-off read grant). On API 26+ without `canRequestPackageInstalls()` it opens
    `ACTION_MANAGE_UNKNOWN_APP_SOURCES` first (state `permission`) and carries on in onResume.
  * States: `idle`, `checking`, `current`, `available`, `downloading`, `ready`, `permission`,
    `error`. Each change is pushed to the page as
    `window.dispatchEvent(new CustomEvent('appupdate', {detail: <stateJson>}))`.
* **Bridge.** `updateState(): String` (the state JSON
  `{state, installed, available, name, notes, progress, error, enabled}`), `checkForUpdate()`,
  `installUpdate()`. JS: `platform.updates.{state, check, install, onChange}`, disabled on the web.
* **UI** (`ui/updates.js`): a banner in the title screen, between the logo and the buttons, with
  Update/Install/Try again/Retry and Later. It never shows during a fight. Text goes in with
  `textContent` only.

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

**The grips.** What is above is the **window grip** (landscape, the screen a window, the blade out
of the back). The **hilt grip** holds the phone in portrait, upright in one fist like a sword's
handle, screen toward the player:
* **Hand model** (right hand; mirrored for the left): the palm heel and the thumb's base sit on the
  phone's right edge, the fingers wrap the back, and the **fingertips curl onto the left edge** of
  the glass over the lower half. The thumb crosses the glass from the right edge; its pad rests over
  roughly x 40–90%, y 40–75% of the screen. **Guard**: the phone upright in front of the right
  chest, the top leaning forward 20–45°, the screen visible.
* **Axes**: the screen frame of the portrait display, which is the device frame on every phone
  (portrait-primary is rotation 0; a natural-landscape tablet reports 90/270 and is remapped as
  above). **The blade is `b = +y`**, out of the top edge. At guard the back of the phone (−z) faces
  the foe, so a thrust is still a push along −z (a punch).
* **Elevation**: with `u = up`, R the body-right axis (§5.3) and `F = u × R` (body forward,
  horizontal, unit), the blade elevation is the angle of +y in the vertical plane through F,
  unwrapped past vertical: `βabs = atan2(u.y, F.y)`, `F.y = u.z·R.x − u.x·R.z`. Upright = π/2,
  pointing at the foe (phone flat, screen up) = 0, pointing down = −π/2, tipped back over the
  shoulder (screen facing down and back) > π/2, π pointing straight back. Roll about the blade
  doesn't change it.
* **Horizontal sweep axis**: the tip of an upright blade moves sideways when the fist *rolls* about
  F; a blade pointing at the foe moves sideways when it *yaws* about u. With βc the hilt βabs of the
  sample R was last captured on (kept as a scalar, so a strike keeps the axis of its guard):
  ```
  A = max(cos βc, yawFloor)·u − sin(βc)·F       (cos βc ≥ 0; yawFloor = 0.7, A not normalized)
  A = (cos βc + yawFloor·(1 + cos βc))·u − sin(βc)·F   (past vertical, cos βc < 0: continuous at π/2, the true axis at π)
  wh = w·A    wv = w·R    (vx, vy) = (−wh, wv)
  ```
  For βc = 0 this is the window grip's `wh = w·u`. **The yaw floor**: the most natural horizontal
  cut with an upright blade is a forearm sweep, which the gyro sees only as a yaw; `cos βc` alone
  would count it at 0.20–0.43 of its rate, below the slash threshold for most players. The real tip
  of a blade on a swept forearm moves sideways at about `ω·(cos βc + r/L)` (forearm 0.3 m, blade
  0.7 m), ≥ 0.7·ω. A cut's yaw and roll have the same sign, so they add; A is not normalized, so the
  roll gain stays `sin βc` and a mixed swing reads at most 1.22× its rate. The **sweep** (cut
  thresholds, direction, recoil) integrates the unit axis `A/|A|` instead, or a 0.3 rad cock-back
  would read 1.2× long and fire as the opposite slash. Gyro look is off in this grip, and body turns
  below 2 rad/s never slash (the `lookMax` floor), so the floor costs nothing there.

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
  renderer, scene, camera,       // camera: PerspectiveCamera(fov 70, near 0.1, far 2000), rotation.order 'YXZ'; fov per aspect (§15)
  vm: { scene, camera },         // viewmodel scene + camera (fov 60, 80 in portrait; near 0.01, far 10); camera stays at origin, identity rotation
  events,                        // EventBus: on(name, fn) → off, once, off, emit (handlers isolated with try/catch)
  settings, saveSettings(),      // §9
  applyControls(),               // brings the grip profile, ctx.input.grip and the orientation in line with settings (§10)
  platform,                      // §5.1
  time,                          // §3.2
  rng,                           // createRng(seed): next, range, int, pick, chance, gauss
  quality,                       // 'low'|'medium'|'high' (resolved from settings.quality)
  input,                         // §6 continuous input (input.grip: the effective grip, §9)
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
  lockOrientation(lock, orientation = 'landscape'),  // the app orientation, below
  onPause(fn), onResume(fn),            // 'nativepause'/'nativeresume' and visibilitychange
  onBack(fn),                           // installs window.__onBack = () => fn() (must return boolean synchronously)
  exit(),
}
```
**lockOrientation(lock, orientation)** (main.js `applyControls` is the only caller, §10):
* Android: always `nat('lockOrientation', !!lock, orientation === 'portrait' ? 'portrait' : 'landscape')`,
  2 arguments (§2.1).
* Web: portrait → `screen.orientation.lock('portrait-primary')` whether locked or not (the game
  wants portrait in its menus too); landscape with `lock` → `lock('landscape')`; landscape without
  → `unlock()`. Every promise is caught: a web lock only succeeds once the document is fullscreen,
  which resolves later, so on a hilt Fight or Agoge click menus.js requests fullscreen and calls
  `ctx.applyControls()` again when it lands (§11 menus). The rotate prompt covers a refusal.
* `lockOrientation(true)` and `lockOrientation(false)` keep their old meaning (landscape).

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
  constructor({ tiltTau = 0.5, platformKind = 'web', grip = 'window' } = {})
  process(raw: RawSample) → Sample      // reused object: { t, dt, w:[3], a:[3], up:[3], calm, gap }
  reset()
  setGrip(grip)                         // 'window'|'hilt' (anything else → 'window'); if it changed: reset().
                                        // The device calibration (axisMap, gravitySign, rotFix) is kept.
  get grip()
  get gravitySign()  get rotFix()  get axisMap()   // diagnostics
}
export const FRAME_GRIPS = {   // frozen
  window: { latch: [0, 0, 1],      restUp: [0, 0.707, 0.707], rotFix: true,  latchRuns: false },
  hilt:   { latch: [0, -0.6, 0.8], restUp: [0, 0.89, 0.45],   rotFix: false, latchRuns: true },
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

**In the hilt grip** the remap, up estimation, `a`, `calm`, `gap` and the axis check are
unchanged, and the display rotation is still applied. Only the three web heuristics above use the
grip's `FRAME_GRIPS` row:
* (a) the latch averages `acc·latch` (or `accG·latch`) instead of `acc.z`: in a hilt swing the
  centripetal acceleration points toward the elbow and the body (−y, +z). The thresholds are the
  same (≥ 12 samples, a mean beyond ±4), but with `latchRuns` it decides only at the end of a fast
  run: in the fist a chop or cock-back turns about x, so its tangential acceleration also lies along
  the latch. Over a whole run (speeding up, then slowing down) it cancels, while over the first 12
  samples at 200 Hz it can outweigh the centripetal part and latch the wrong sign.
* (b) the provisional −1 tests `up·restUp < −0.5`, the grip's own guard up.
* (c) `rotFix` is never applied in the hilt grip: a calm blade-down pose is a rest, not a screen
  angle reported 180° off. The stored rotFix is kept for the window grip.

(The same fact as (a) is the hilt grip's safety problem: the swing's inertial load pulls the phone
out of the top of the fist, which only grip friction resists. See §5.3's firm-flick thresholds.)

**axisMap**: web only, optional but recommended. During slow rotations (0.5 < |w| < 3,
||accG| − 9.81| < 1), correlate `d(normalize accG)/dt` with `normalize(accG) × w` for the
current mapping and for the permutation (alpha→z, beta→x, gamma→y). If the permutation wins
by 2× over at least 1 s of data, switch to it.

### 5.3 motion/gestures.js (PURE)
```js
export const GESTURE_DEFAULTS = {   // frozen; rates are the body-frame planar rate m (rad/s)
  swingStart: 2.0, swingPeak: 2.8, swingSweep: 0.24, swingSweepMin: 0.2, swingSweepFast: 0.16, swingSweepLate: 0.55,
  riseRatio: 0.04, riseWindow: 0.025, exRate: 0.8, exSweep: 0.08, fastWindow: 0.35, fastRatio: 1.5,
  preRate: 0.6, swingEnd: 1.5, endHold: 0.04, maxSwing: 0.7, maxRaise: 1.6,
  reverseWindow: 0.6, reverseRatio: 0.55, reverseSweep: 0.4, reverseSector: 60°, cardinalSnap: 22°,
  dirMargin: 8°, dipSector: 35°, dipRaise: 0.07, dipLook: 0.3, dipSweep: 0.55, dipEarlySweep: 0.3, dipRise: 0.08,
  quietRate: 1.0, rightStale: 0.5,
  thrustAcc: 12, thrustMaxRate: 3, thrustVel: 0.15, thrustWindow: 0.06, thrustRefractory: 0.35, vfLeak: 0.5,
  postThrustSwingLock: 0.15, vfLeakBack: 4, calmHold: 0.1,
  guardBeta: -0.39, guardTau: 1.5, upHoldStartBeta: -0.45, windupBeta: 1.6, windupTime: 0.1, windupMemory: 1.0,
  throwPeak: 7, throwFull: 13, throwMin: 4.5, throwMinSweep: 0.8, throwSector: 65°, throwReleaseRatio: 0.6,
  throwMaxSweep: 1.4, postThrowLock: 1.0,
  settleRate: 1.0, settleTime: 0.15,
  lookDead0: 0.1, lookDead1: 0.3, lookMax: 2.0, lookStrike: 2.5, lookDelay: 0.28, lookAfter: 0.6,
  lookRestRate: 0.15, lookRestTime: 0.35, lookAfterMax: 3.0, lookCockGap: 0.3, lookHoldRun: 0.45, lookRunMin: 0.03,
  lookTau: 0.07, lookRate: 1.4, lookStep: 0.026,
  candStart: 1.8, candEnd: 1.2, candSweep: 0.3, candMax: 0.9,
  slashPowerSpan: 10, powerRise: 1.3, thrustPowerSpan: 18 }
export const SENSITIVITY_MIN = 0.5, SENSITIVITY_MAX = 2.2
export function snapCardinal(dir, lim)   // the nearest of 0, ±π/2, π if dir is within lim of it, else dir (never −0 or −π)
export class GestureRecognizer {
  constructor({ sensitivity = 1, weapon = 'sword', gyroRange = 34.9, params = null, grip = 'window' } = {})
                            // params overrides GESTURE_DEFAULTS and the grip's params (tests, tuning)
  setGrip(grip)             // 'window'|'hilt' (anything else → 'window'); see "The hilt grip" below
  get grip()
  get guardLearned()        // true once the held guard has been learned (hilt: the Agoge's grip check waits for it);
                            // kept across reset(), cleared by a grip change
  setSensitivity(s)         // clamped to SENSITIVITY_MIN–MAX
  setWeapon('sword'|'spear')   // anything else → 'sword'
  setFlip(flipX, flipY)     // settings.motionFlipX/Y: corrects a mirrored sensor setup
  reset()                   // clears swing/thrust/wind-up/candidate state, recoil memory, locks and look state;
                            // keeps the released heading and the learned guard
  push(sample) → GestureEvent[]           // returns a reused array; copy what you keep
  consumeCandidates() → SwingCandidate[]  // completed since the last call, oldest first (≤ 32 queued); reused array
  consumeLook(frameDt = 0) → { yaw }  // released heading change since the last call (rad, + = left), capped per
                                      // call at max(lookStep, 1.1·lookRate·min(frameDt, 0.1)); reused object
  holdLook(), resumeLook(), recenter()
  get settled()             // |w| < settleRate for settleTime with no swing active (a post-throw lock doesn't matter),
                            // AND the screen upright (up.y ≥ 0.5) or tilted back toward the eyes (up.y ≥ 0, up.z ≥ 0.5)
                            // (hilt: the screen upright and not facing down, below)
  get debug()               // { m, yawRate, pitchRate, beta, phase, peak, fwdAcc, fwdVel, lookState, lastEvent,
                            //   guardBeta, lastCandidatePeak, lastCandidate, grip }
}
// GestureEvent: { type:'slash', dir, rawDir, power, peak, sweep, t, fast? } | { type:'thrust', power, peak, t }
//             | { type:'throw', power, peak, t, fast? } | { type:'windup', t }
//             | { type:'overSpeed', fast: true, peak, t }          (hilt only, below)
// SwingCandidate: { type:'swingCandidate', peak, sweep, dir, t0, t, heldUp? }   (heldUp: hilt only, present only when true)
// debug.phase: 'idle'|'swing'|'hold-up'|'throw'|'done'|'thrust'|'locked'|'cock-back'
// debug.lookState: 'hold'|'turning'|'blocked'|'settled'|'moving'
```
**Sensitivity** `s` (clamped to 0.5–2.2) divides `swingStart`, `swingPeak`, `thrustAcc` and
`thrustMaxRate`. It divides `swingSweep` (never below `swingSweepMin`), `swingSweepFast` and
`swingSweepLate` by √s, and `throwPeak`, `throwMin` and `throwFull` by max(1, s), so a hard
slasher calibrated below 1 never has to throw harder. Nothing else scales. Below, a primed name is
the scaled value (`swingStart′ = swingStart/s`). Two of them are bounded:
* `swingPeak′ = max(swingPeak/s, lookMax)`, exported as `slashThreshold(s)` (the meters mark it). A body turn that gyro look accepts (up to `lookMax`)
  must never slash, or a high sensitivity would turn every turn into a strike and stop the view
  from following it.
* `swingEnd′ = min(swingEnd, 0.7·swingStart′)`.

A cautious swing (peak 3.5–4 rad/s, arc 0.8–1.0) registers at s = 1.

**Body frame.** Directions don't depend on how the phone is gripped (tilted back, rolled, screen
facing partly sideways).
* `u = sample.up`. Blade elevation, unwrapped past vertical: `βabs = atan2(−u.z, s·hypot(u.x, u.y))`
  with `s = u.y ≥ 0 ? 1 : −1`, so β > π/2 means the blade points back over the shoulder.
* **Learned guard** G (the grip; reset() keeps it): the first settle with βabs ∈ (−1.45, 0.9) sets
  it, and later settles move it toward βabs with time constant `guardTau` while within 0.6 of it.
  Learning pauses while locked or while an overhead swing is pending. Every elevation rule uses
  `β = βabs − (G − guardBeta)`, as if the guard were at `guardBeta`, so a phone held tilted back
  raises the same.
* **Body-right R** (screen frame) is captured from the attitude whenever no swing is active and
  the phone is quiet (|w| < `quietRate`) or hasn't been for `rightStale` (a long body turn), and
  after a reset or gap. In between, the gyro carries it as a world-fixed vector, kept horizontal and
  unit. It is never re-derived mid-swing, so a strike keeps the axis of the guard it started from
  (a cock-back isn't quiet).
  * Capture: F = the horizontal part of the back axis −z (negated past vertical, u.y < 0). As the
    phone nears flat (|F| < 0.35), F also takes in the horizontal part of the top edge +y, with
    weight `clamp((0.35 − |F|)/0.25, 0, 1)·sign(u.z)`.
  * `R = normalize(F × u)`, or the horizontal part of +x if that degenerates.
* `wh = w·u` (positive = turning left) and `wv = w·R` (positive = tip up). Roll about the body's
  forward axis (wrist roll) is ignored.
* `setFlip` negates `wh` (flipX: dir → π − dir, and look turns the other way) and/or `wv` (flipY:
  dir → −dir) before every rule below, so uppercut, chop, wind-up, throw and recoil logic agree.
* The blade velocity across the view is `(vx, vy) = (−wh, wv)`, and the planar rate is
  `m = hypot(vx, vy)`. The **sweep** `(dx, dy)` is its integral since the swing began.
  `rawDir = atan2(dy, dx)` (§3.4).
* **Rising**: m ≥ the previous sample's m, and m rose by at least `riseRatio·m` over `riseWindow`
  (true with too little history).

**Swing.**
* **Ramp**: outside a swing, a rising run of m ≥ `preRate` in one direction is integrated (within
  0.03 s of its running max, and no more than 0.03 + 0.02·m below it). A swing it leads into starts
  with that sweep, so the direction is known sooner.
* **Starts** when `m > swingStart′`, unless locked, or within `postThrustSwingLock` of a thrust
  with m < 6.
  * β0 = β where the motion began (the ramp's start, if lower).
  * βtop = the highest β over the `dipLook` before the start.
* **Cut**: the direction is reliable. It is decided once per swing, and needs a running peak
  ≥ `swingPeak′` and one of the rules below. `clear` means rising, m ≥ `swingStart′`, and rawDir
  more than `dirMargin` from a token boundary (π/8 + kπ/4).
  * sweep ≥ `swingSweepLate′`, at any phase
  * sweep ≥ `swingSweep′` while clear. A cock-back stops accelerating at half its short arc, so
    cock-backs of up to about 0.5 rad never fire, while a stroke of 0.6 rad or more fires early.
  * sweep ≥ `swingSweepFast′` while clear, right after its own cock-back. That is the last
    completed run (m ≥ `exRate` one way, sweep ≥ `exSweep`): it ended ≤ `fastWindow` ago, went
    within 60° of the opposite direction, and this swing's peak is ≥ `fastRatio` × the run's peak.
  * **Dip rule** (uppercut or chop?) for a swing within `dipSector` of straight down from the guard
    (`βtop ≤ guardBeta + dipRaise`; a chop is raised first, if only a little). It may be the dip
    to a low guard before an uppercut, which peaks at half its short arc. It is a cut only once:
    * sweep ≥ `dipSweep`, at any phase, or
    * sweep ≥ `dipEarlySweep` while clear and m rose by ≥ `dipRise·m` over `riseWindow`.

    Neither is scaled: how far one dips is style, not strength. A chop from a raised blade follows
    the rules above.

  A cut cancels a thrust candidate and zeroes vf.
* A cut fires its slash at once, except in the upward and spear cases below. The recoil rule may
  hold it, and is re-checked every sample. At most one slash per swing.
* **Ends** at the first of:
  * (a) `m < swingEnd′` for `endHold`
  * (b) a reversal: once sweep ≥ 0.2, the velocity points against the sweep on 2 consecutive
    samples
  * (c) `maxSwing` (`maxRaise` for a held-up swing, which may be a slow raise)

  A new swing may start on the very next sample.
* **Slash event**:
  * `dir = snapCardinal(rawDir, cardinalSnap)`, and `rawDir` keeps the measured angle. Real
    horizontal slashes dip a little, and a tilted animation reads as the wrong direction.
  * Power is measured at fire time. While the stroke still accelerates, it counts on
    `powerRise·m`: `power = clamp((max(peak, rising ? powerRise·m : 0) − swingPeak′)/span, 0, 1)`.
    A pending uppercut fired later (during the next swing) uses only its own peak.
  * The gyro range sets the ceiling: `span = max(0.5, min(slashPowerSpan, 0.9·gyroRange − swingPeak′))`.

**Recoil and return-to-guard.** Let P be the last slash. A swing is P's return if:
* its rawDir is within `reverseSector` of P's rawDir + π, and
* it starts within `reverseWindow` after P's swing ended, or before the arm has come to rest
  since P (as for look, below).

A return slashes only when its peak is ≥ `max(swingPeak′, reverseRatio·P.peak)` and its sweep is
≥ `reverseSweep`. Returns run at 20–50% of the strike's speed; deliberate reverse slashes run at
70% or more. A throw clears P.

**Upward swings and wind-ups.** A swing is **held up** if its rawDir is within 35° of straight up
and β0 ≥ `upHoldStartBeta`. Uppercuts from a low guard (the usual small dip first) and
up-diagonals beyond 35° fire like any cut.
* A raise: a held-up swing that reaches `windupBeta` (beside or behind the head) with sweep ≥ 0.15
  before it is a cut. It emits `windup`, never a slash.
* A held-up cut fires **while braking**, once it will clearly stop short of `windupBeta`. That
  needs β < windupBeta − 0.3, sweep ≥ `swingSweep′`, m ≤ 0.97·peak, no longer rising, and
  `β + r(m/peak)·sweep < windupBeta − 0.35`. Here r is the arc still to come of a minimum-jerk
  stroke past its peak, as a fraction of the arc so far.
* Otherwise it fires at swing end if below `windupBeta`. If it ended overhead, it is **pending**:
  * Held up for `windupTime` without moving back down: it was a raise, and emits `windup`.
  * Moving back down (vy < −0.5 on 2 samples, β 0.1 below its top, or β < windupBeta): it was a
    cut that rebounds, and emits the U slash.
  * With the spear, the next downward motion may be the throw itself. A swing then decides: a
    throw (the overhead swing was its wind-up), else the U fires first. A slow rebound is the U:
    its m has peaked above 1 rad/s and fallen below 70% of that, β < windupBeta − 0.3, or 0.5 s
    have passed.
  * With the sword, a new swing that starts while pending fires the U first. So an uppercut that
    flows over the top into a chop gives U, then D (Leonidas' Cleave).
* **Wind-up memory**: a raise counts only once the phone has been below windupBeta − 0.3. Any
  period of ≥ `windupTime` with β ≥ `windupBeta` emits `windup` once per raise, and is remembered
  for `windupMemory`. It doesn't emit for an uppercut's raise, or while a held-up swing is still
  deciding.

**Throw** (weapon = spear only).
* A swing within ±`throwSector` of straight down, after a remembered wind-up (within
  `windupMemory`, or β ≥ windupBeta at swing start), is never a chop. Once its sweep reaches 0.12,
  it resolves at **release**, the first of:
  * the running peak is ≥ `throwPeak′` and `m < throwReleaseRatio·peak`
  * sweep ≥ `throwMaxSweep`
  * swing end
* At release, if peak ≥ `swingPeak′` and (peak ≥ `throwMin′` or sweep ≥ `throwMinSweep`), it is a
  `throw` with `power = clamp((peak − throwPeak′)/(throwFull′ − throwPeak′), 0, 1)`.
  * Below throwPeak′ it is a weak throw (power 0), never a chop. A firm flick is enough, so never
    throw hard.
  * A shorter, slower one is no throw. After an uppercut that ended overhead, it was that cut's
    rebound.
* A downward swing with no wind-up is an ordinary slash, so an overhead spear chop never throws.
* After a throw:
  * every swing, thrust and wind-up is ignored for `postThrowLock`
  * recoil memory is cleared
  * look is held until `resumeLook()`, or for 2.5 s if no spear cam follows

**Thrust.**
* `f = min(−a.z, 2·(a·(u × R)))`: a push along the phone's back axis that is also forward for the
  body. A phone held tilted back moves along its back axis when it chops down.
* The forward velocity is `vf = vf·exp(−dt/(vf > 0 ? vfLeak : vfLeakBack)) + f·dt`. It is zeroed
  while a cut, raise or throw swing is active, at swing end, and once samples have been calm for
  `calmHold`.
* A candidate opens on a sample with all of:
  * `f > thrustAcc′`, `vf > thrustVel` and `m < thrustMaxRate′`
  * no cut, raise or throw swing, and no lock
  * at least `thrustRefractory` since the last thrust
* Emit `thrustWindow` after it opens, or earlier once `f < 0.8·runningPeak`, but no sooner than
  30 ms after opening. `power = clamp((runningPeak − thrustAcc′)/thrustPowerSpan, 0, 1)`. An
  uncut swing under way was the thrust's dip, and emits nothing.
* Cancel the candidate if, within that window, a cut swing is active, `m ≥ thrustMaxRate′`, or a
  lock starts.
* After a thrust, a swing may not start for `postThrustSwingLock` unless m ≥ 6.
* Retraction is stopped by +f with negative vf (backward vf leaks slowly), so it never fires.

A **gap** resets the swing, thrust, wind-up, pending-uppercut and candidate state, and R is
captured again. A slash whose swing the gap cut off ends there, for the recoil rule. For look, a
gap counts as a strike: there is no turn from it, and look waits for the arm to rest. If time went
backwards (injected → real samples), the timers are shifted.

**Calibration candidates.** Every swing-like excursion, slash or not, is queued for
`consumeCandidates()`, with the whole excursion's peak.
* It starts at `m > candStart`.
* It ends when `m < candEnd` for `endHold`, on a reversal (sweep ≥ 0.15, 2 samples), or after
  `candMax`.
* It counts if its sweep is ≥ `candSweep`. None of these is scaled.

The Agoge calibrates from these (§11), so a player whose swings are all too gentle to slash still
calibrates.

**Gyro look.** Only deliberate body rotation turns the view, 1:1.
* Look integrates `wh` through a soft dead zone: gain `smoothstep(lookDead0, lookDead1, |wh|)`.
* **Strikes** are:
  * a swing whose running peak reaches `min(lookStrike, swingPeak′)`
  * a thrust, a wind-up, or a throw and its lock
  * any run of rotation one way faster than `lookMax` (a stroke too gentle to slash still swings
    the arm)
* Rotation counts only when all of these hold:
  * no strike is under way
  * it is more than `lookAfter` since the last strike
  * the arm has come to rest since then: the low-passed |w| (τ 0.06 s) < `lookRestRate` and
    |w| < 2·lookRestRate + 0.2 for `lookRestTime`, or `lookAfterMax` has passed. A slow, sloppy
    return to guard is part of the strike.

  Rotation during strikes and their aftermath is discarded, never released later.
* **Runs**: a run is rotation one way above `lookDead0`. A 0.05 s pause or a sign change ends it.
  A run that gets faster than `lookMax` is no turn: none of it counts, and whatever of it was
  shown is taken back.
* **Delay**: rotation is shown `lookDelay` later, so a strike's run-up never shows. A run is held
  back, because it may be a cock-back, until either:
  * it has lasted `lookHoldRun` (a deliberate turn), or
  * it has been over for `lookCockGap` with no strike after it.

  The same holds for a run that began within lookCockGap of the previous one.
* **Cock-back undo**: a strike drops its own run-up and shows anything older. The run just before
  it is decided by the slash, if it ended ≤ `lookCockGap` before the strike's run-up began and
  moved ≥ `lookRunMin`.
  * It turned against the slash: |dx| ≥ 0.3·sweep, and the run moved the blade the opposite way.
    It was the cock-back. It is dropped, and whatever of it was already shown is taken back.
  * Otherwise it was a turn, and it is shown.

  A swing that ends with no slash drops what of it is still delayed. A thrust or wind-up shows it.
  A throw drops everything still delayed.
* **Smoothing**: the view eases toward the released heading (`lookTau`), at most `lookRate` fast.
  `consumeLook(frameDt)` hands out at most `max(lookStep, 1.1·lookRate·min(frameDt, 0.1))` per
  call, and the rest follows. So no 60 fps frame turns the view by more than lookStep, and at a
  lower frame rate the view keeps up with the body instead of trailing behind it.
* `holdLook()` freezes the view, and a turn being eased in stops where it is. `resumeLook()`
  follows body turns again from the current view, so nothing jumps. A throw holds look by itself.
  If no holdLook/resumeLook follows (no spear cam), it resumes after 2.5 s.
* `recenter()`: look is relative, so nothing is re-anchored, and a turn under way carries on.
  player.js turns the view back to the line.
* Pitch is never looked by gyro. player.js holds a steady pitch (§7).

**The hilt grip** (`setGrip('hilt')`, §3.3). `GESTURE_DEFAULTS` stays exactly as above; the
structural switches and each grip's thresholds live in `GRIPS`, and the window grip runs every
code path above unchanged (`yawFloor: 0`, no over-speed check, today's guard learning).
```js
export const GRIPS = {   // frozen
  window: { blade: 'back', sweep: 'gravity', look: true, heldUpCuts: true, upHoldSector: 35°, yawFloor: 0,
            guardSnap: [-1.45, 0.9], guardShift: [-∞, ∞], settle: 'window',
            throwSettle: false, thrustFromGuard: false, cutThrustLock: 0, params: {} },
  hilt:   { blade: 'top', sweep: 'guardBlade', look: false, heldUpCuts: false, upHoldSector: 60°, yawFloor: 0.7,
            overSpeed: 14, throwSettle: true, thrustFromGuard: true, cutThrustLock: 0.15,
            guardSnap: [0.30, 1.85], guardShift: [-0.35, 0.25], settle: 'hilt', guardGrace: 1.0, guardHold: 0.5, guardResnap: 2.0,
            params: { guardBeta: 1.10, upHoldStartBeta: 0.60, windupBeta: 2.35, dipSector: 0,
                      throwPeak: 4.5, throwFull: 7.5, throwMin: 3.5, slashPowerSpan: 5.5, fastRatio: 1.6 } },
}
```
`setGrip(grip)`, if the grip changed: `p = { ...GESTURE_DEFAULTS, ...GRIPS[grip].params, ...ctorParams }`,
the sensitivity re-applied, `reset()`, the learned guard forgotten, and no look residue. It is a
no-op when unchanged (main.js calls it on every mode change). In `push()` the hilt grip differs as
follows:
1. **Order**: R is stepped before the elevation, which needs it (the window β doesn't read R).
   R's capture rule is unchanged; evaluated in the hilt frame it gives body-forward across the
   whole hilt range (guard, flat, cocked back, blade down). βc = βabs of the capture sample.
2. **βabs** and the rates: §3.3 (`blade: 'top'`, `sweep: 'guardBlade'`).
3. **Learned guard**. The pose the player held while tapping Fight is not their fist guard, so the
   hilt grip learns G only from a held guard and keeps correcting it:
   * **Grace**: no snap during the first `guardGrace` (1.0 s) of samples after `reset()` or
     `setGrip()`. Until G is learned, G = guardBeta (shift 0).
   * **Snap**: a settle in the hilt attitude held ≥ `guardHold` (0.5 s) with βabs inside
     `guardSnap` sets G. **Follow**: later settles within 0.6 of G move it (`guardTau`).
   * **Re-snap**: settles inside `guardSnap` but > 0.6 from G adding up to `guardResnap` (2.0 s,
     counted across settles, reset by any settle near G) snap G to the latest one, so a wrong first
     guard heals within about 2.5 s at the real guard.
   * The shift is `clamp(G − guardBeta, guardShift)` and `β = βabs − shift`. A forward-leaning guard
     (G 0.77) cocks back at βabs ≥ 2.02, an upright one (1.37) at ≥ 2.60, the reference (1.10) at
     2.35: all of them "the top points back over the shoulder". The raise is armed below
     `windupBeta − 0.3` (β′ 2.05), so every guard in the snap range arms.
4. **Upward swings**. A held-up swing (`β0 ≥ upHoldStartBeta`, rawDir within `upHoldSector` of
   up) **never slashes**: tipping an upright blade back is a cock-back. If it reaches `windupBeta`
   with sweep ≥ 0.15 and the raise armed, it emits `windup`; otherwise it emits nothing, and its
   calibration candidate carries `heldUp: true` (the Agoge hints "Lower the blade first, then swing
   up"). The wind-up memory rule still applies. A **U, UR or UL** slash starts from a **low guard**
   (β0 < 0.60, the blade lowered toward the foe or the ground) and fires like any cut; an uppercut
   that carries on past vertical is still U.
5. **Dip rule off** (`dipSector: 0`): every downward swing from the guard is judged by the ordinary
   cut rules, so the hilt overhead chop comes straight from the guard, with no cock-back.
6. **Throw**: the same logic with the hilt thresholds. `windupBeta 2.35` is the javelin cock-back,
   the top of the phone pointing back over the shoulder, about 1.25 rad past the guard; a smaller
   raise is no wind-up, so a lift of up to ~1 rad and a chop is a chop with either weapon (the
   sword's `windup` has no gameplay effect, so one threshold serves both). A firm flick gets full
   power: `throwPeak 4.5`, `throwFull 7.5`, `throwMin 3.5` (nothing is gained above 7.5 rad/s). The
   release resolves at `throwMaxSweep` (1.4 rad of a 3.0 rad release) at the latest.
   **throwSettle**: after a throw nothing fires until the phone is back at guard (settled), as well
   as `postThrowLock`, for at most 2.5 s: the release ends with the blade low, and its way back up
   would otherwise be an uppercut from a low guard during the spear cam.
7. **Thrust**: `f` is unchanged (at the hilt guard −z is the punch's direction).
   **thrustFromGuard**: a thrust needs β′ ≥ upHoldStartBeta, a punch from the guard (the dip
   before an uppercut moves the fist down and forward about the elbow). **cutThrustLock**: no thrust
   within 0.15 s of a cut's swing ending (the fist pivots at the elbow, so a hard cut's rebound
   pushes it forward like a punch).
8. `fastRatio` 1.6 (window 1.5): in the fist the wrist damps a slash's rebound more than the next
   stroke's wind-up, which at 1.5 could pass for a stroke after its own cock-back.
9. **Look** is computed internally (the recoil rule's rest detection uses it), but `consumeLook()`
   always returns `{ yaw: 0 }`; `holdLook`, `resumeLook` and `recenter` change nothing visible.
10. **settled** (`settle: 'hilt'`): still, no swing, `up.y ≥ 0.45 && up.z ≥ −0.25` (the screen
    upright and not facing down). A held cock-back and a follow-through with the blade below
    horizontal are not settled, so the spear cam's slow hold lasts until the phone is back at
    guard where the player can see it.
11. **Slash power** is full at 8.3 rad/s at 1× (`slashPowerSpan 5.5`).
12. **Over-speed**: a slash or throw whose measured peak is ≥ `overSpeed` (14 rad/s) or clips the
    gyro (≥ 0.95·gyroRange) carries `fast: true`, and gets no extra power. A slash fires early,
    before its peak: when its swing only reaches 14 rad/s after the slash went out, the swing's
    end emits `{ type: 'overSpeed', fast: true, peak, t }` instead (a throw that does the same gets
    one too). The HUD and the Agoge react to **any** gesture with `fast === true` (§11).

The hilt grip rewards a **firm flick, never a hard throw**: the phone is held by friction, and the
swing's load pulls it out of the top of the fist.

**Slash directions in the hilt grip** (as the player sees the imaginary blade's tip move):

| token | motion from the guard (blade upright, screen toward you) |
|---|---|
| R / L | the tip sweeps right / left: a roll of the fist about the forward axis, a forearm sweep (yaw), or any mix (they add) |
| D | chop: the tip falls forward and down from the guard; no cock-back needed |
| DR / DL | diagonal chops from upper-left to lower-right, and from upper-right to lower-left |
| U, UR, UL | from a **low guard** (blade lowered toward the foe or the ground): the tip rises. From the upright guard an upward tip motion is the cock-back, never a slash |
| thrust | punch the fist straight at the foe (along −z) |
| bash | thumb on the glass (shield) + thrust |
| wind-up | cock the phone back behind the shoulder, the top pointing back (β′ ≥ 2.35) |
| throw | spear in hand: from the wind-up, snap forward and down, fist closed |

main.js passes `settings[sensitivityKey(settings)]` to `setSensitivity` (§9), and
`slashThreshold(s)` keeps its `lookMax` floor of 2.0 rad/s in both grips, so a repositioning slower
than 2 rad/s (such as lowering to a low guard) never slashes at any sensitivity.

**Required unit tests** (synth §5.4 → frames → gestures).

`tests/unit/motion-gestures.test.mjs` runs over the matrix rot {90, 270} × tilt {0, 0.5} ×
roll {0, 0.3} × rate {50, 100, 200} × {Android with TYPE_GRAVITY, web +1, web −1 without gravity},
plus runs without linear acceleration and/or gravity:
* Each 8-way swing with wind-up, rebound and return gives exactly one slash with the right token.
* R,L,R,L at about 2 Hz (arc 1.0, speed 9, ret 0) gives 4 alternating slashes.
* `swing R speed 10 ret 0, idle 0.05, swing L speed 4` gives exactly 1 slash.
* `… swing L speed 9` gives 2 slashes.
* Gentle R then L (speed 4, arc 0.9), each returning at 1–2 rad/s, gives R, L.
* Throws at speed {9, 13, 18} give exactly one windup and one throw, no slash. Power is > 0 and
  rises with speed.
* `swing dir −π/2 speed 9` without a wind-up, in spear mode, gives a D slash.
* A thrust followed by retraction {0.25, 0.3, 0.35} s gives exactly one thrust, power > 0.5.
* 10 s of walking, and 10 s of idling, give nothing (idle look ≤ 0.02).
* A 1.0 rad turn over 1.2 s gives a consumed look of 1.0 ± 0.1 and no gesture.
* A swing plus its return gives |net| and |peak| consumed look < 0.05.
* Detection latency from motion onset is < 130 ms for a 9 rad/s swing (U, UL and UR at tilt 0.5
  excluded).

The same file, on a lighter matrix:
* Proportionally scaled motions at s {0.6, 1, 1.6} classify the same. So do the left hand, and
  10% jitter with about 4% dropped samples.
* Throw wind-ups at 3.5–12 rad/s or over 2–3 rad never slash.
* At s 0.6, throws at {8, 10, 13} still throw. With the spear, a raise + chop at 6 rad/s is a
  power-0 throw.
* Raise + chop gives windup, D with the sword, and windup, throw with the spear. Ordinary 8-way
  spear swings stay slashes.
* An uppercut ending overhead (arc 2.0–2.4, speed 7–12) is U with either weapon. Held overhead, it
  is a windup. U flowing over the top into D gives U, D.
* Straight up from the guard (tilt 0, no dip), an uppercut fires before 0.8 of its stroke.
* None of these move the view by 0.05 or more:
  * arm motions that come back to the guard (raise and chop, high swings)
  * a slow cock-back (1.5–2.5 rad/s, held up to 0.5 s) before a strike
  * a return landing 0.12 rad off
  * repeated gentle slashes (speed 5–6)
* Body turns and strikes:
  * A body turn 0.35 s before a strike is kept.
  * A turn ending 0.1 s before a same-way or upward slash is kept.
  * A turn ending 0.05 s before an opposite slash is kept or dropped whole. It is dropped if it
    lasted ≤ 0.5 s.
  * A 0.4 rad turn over 0.45 s is followed (± 0.05). Over 0.25 s it is not (≤ 0.03).
* A strike that stays out never turns the view. A turn after the arm has rested is followed 1:1.
* Thrusts of other depths, speeds and dips count once.
* A gap > 0.1 s gives no event from the torn swing, and no look jump (< 0.1).
* reset() clears recoil memory, the post-throw lock and look hold, without moving the view.
* After a throw, `settled` follows the phone (within settleTime + 0.05 s), not the lock.
* holdLook, resumeLook and recenter never jump.
* API: the sensitivity clamp, the reused event array, the debug fields, and a frozen
  GESTURE_DEFAULTS. Slash power is > 0.9 for a 14 rad/s swing on an 8.7 rad/s gyro.

`tests/unit/motion-feel.test.mjs` runs over 9 grips × Android / web −1 × 50/100/200 Hz. The grips
are default, upright, tilted back 30° and 60°, rolled ±45°, back 40° and rolled 30°, and screen
sideways ±30°.
* Cautious swings register at s = 1, with never a wrong token or an extra event, and ≥ 98%
  detected. They peak at 3.5–4 rad/s with an arc of 0.8–1.0, a small cock-back, some wrist roll
  and a slow, off-target return.
* None of these give a false slash: walking, idling, slow body turns (±90°), looking around, and
  returns to guard (at 2.5 and 4 rad/s, and at 1–2 rad/s up to 0.44 rad off). R, L with their
  returns gives exactly R, L.
* All 8 directions at 5 and 9 rad/s come out right in every grip. Wrist roll of ±0.2 rad doesn't
  bend them.
* Latency from onset (cardinals, tilt {0, 0.5}):
  * 5 rad/s, arc 1.0: median ≤ 90 ms, worst ≤ 100 ms
  * 9 rad/s, arc 1.6: median ≤ 70 ms, worst ≤ 80 ms
  * 9 rad/s, arc 1.0: worst ≤ 60 ms
  * uppercuts with the usual dip: worst ≤ 80 ms
  * straight up with no dip (tilt 0.5): before 0.7 of the stroke
* A 0.3–0.5 rad dip before an uppercut (speed 5–13, tilt −0.3…0.4) gives only U.
* A chop straight down from the guard gives D within 180, 140 and 100 ms at 3.5, 5 and 9 rad/s.
* Slashes within cardinalSnap snap to the cardinal (never −0), rawDir keeps the angle, and
  diagonals stay diagonal.
* setFlip(x, y) maps dir → π − dir and/or −dir. It corrects a sensor with one axis reversed, and
  flipX mirrors look.
* Look:
  * Slashes with sloppy returns (≤ 25° off, 1–2 rad/s) move the view < 3°, net and at peak.
  * A 90° body turn over 2 s turns the view 80–92°, smoothly (never back, no jumps).
  * No frame moves the view more than 1.5°, whatever the phone does.
  * Idle < 0.5°, walking < 3°, and a strike that stays out or thrusts < 1°.
  * Swings too gentle to slash (2.2–2.7 rad/s) slash nothing and leave the view < 1° net.
* Calibration:
  * 5 swings at 2.4 rad/s slash nothing at 1×, yet `pickStrokes` finds 5 strokes among the
    candidates.
  * The suggestion, in (1.5, 2.2], makes them R L R L R, still with nothing from walking or a slow
    look.
  * A hard slasher's (9 rad/s) strokes are picked, not the cock-backs or returns, and suggest 0.6.
* At s 2.2, proportionally scaled motions still classify.
* At s 1.6–2.2, a hard swing (9–18 rad/s) is one slash: its short, fast wind-up is no stroke.

**Hilt required tests** (synth `hold: 'hilt'` → `MotionFrames({ grip: 'hilt' })` →
`GestureRecognizer({ grip: 'hilt' })`). Every window test above stays unmodified and green.

`tests/unit/motion-hilt.test.mjs`, over lean {0.2, 0.45, 0.8} × roll {0, 0.3} × rate {50, 100, 200}
× {Android with TYPE_GRAVITY, web +1, web −1 without gravity}, plus runs without linear
acceleration and/or gravity. Every run starts with an idle at guard (1.2 s; 1.7 s for web −1, whose
provisional sign takes 1 s before the guard's 0.5 s hold):
* R, DR, D, DL and L from the guard, with wind-up, rebound and return, give exactly one slash with
  the right token. U, UR and UL each give one slash after `lower β 0.1, idle 0.3`.
* An up swing from the guard (speed 4–12, arc 0.6–1.6) is never a slash, and gives one `windup`
  iff it ends at β′ ≥ 2.35 (arcs ≤ 1.2 never do).
* R, L, R, L at about 2 Hz gives 4 alternating slashes; `swing R speed 10 ret 0, idle 0.05,
  swing L speed 4` gives 1, with L at speed 9, 2. Gentle R then L with slow returns gives R, L.
* Forearm sweeps (`yawShare 1.0`) R and L at 5 and 9 rad/s, lean 0.2 and 0.45, give R and L.
* Throws at speed {5, 6, 7} give exactly one windup and one throw, no slash, power > 0 and rising
  with speed; a nominal 7.5 rad/s throw scores ≥ 0.85 (the recognizer measures about 95% of the
  synth's nominal speed), and full power by 8.
* **No false throws**: with the spear, `raise { cockArc }` of 0.5, 0.75 and 1.0 and a DR, D or DL
  swing at 6, 9 and 12 rad/s give that slash and no `windup` or `throw`, at every lean and at the
  shift clamps. A D swing without a cock-back is a D slash; raise + D gives windup, D with the
  sword and windup, throw with the spear.
* Over-speed: a slash and a throw at 15 rad/s carry `fast: true`; at 12, nothing does.
* Guard learning: a tapping pose (βabs 0.7) for 1.5 s, then a fist guard at 1.35: from 3 s after
  the change the wind-up and chop thresholds equal a clean start at 1.35; no snap in the first
  1.0 s after `reset()`.
* A thrust with a retraction of {0.25, 0.3, 0.35} s gives one thrust, power > 0.5. 10 s of walking,
  10 s of idling and a slow lower and back give nothing. `consumeLook().yaw === 0` throughout.
* Latency from the phone's own motion onset < 130 ms at 9 rad/s for R, L, D, DR and DL. Display
  rotation {0, 90, 270} (a natural-landscape tablet) classifies the same.
* Light matrix: scaled motions at s {0.6, 1, 1.6}, the left hand, 10% jitter with 4% dropped
  samples; cock-backs at 3.5–12 rad/s over 0.9–1.5 rad never slash.
* settled is true at guard within settleTime + 0.05 s, false while cocked back with the screen
  facing down and at a follow-through with the blade below horizontal.
* `setGrip('window')` then `('hilt')` resets cleanly: no event, no look jump.

`tests/unit/motion-hilt-feel.test.mjs`: lean {0.2, 0.45, 0.8}, roll ±0.5, aim ±0.52, grip pitch
±0.35, × Android / web −1 × 50/100/200 Hz.
* Cautious swings (3.5–4 rad/s, arc 0.8–1.0, small cock-back, wrist roll 0.2, slow off-target
  return) register at s = 1, ≥ 98% detected, never a wrong token or an extra event.
* Forearm-sweep horizontals (`yawShare` 0.6 and 1.0) at 5 and 9 rad/s give R and L.
* Latency: 5 rad/s arc 1.0, median ≤ 90 ms and worst ≤ 110 ms; 9 rad/s arc 1.4, median ≤ 70 ms and
  worst ≤ 85 ms; a chop from the guard gives D within 180, 140 and 100 ms at 3.5, 5 and 9 rad/s.
* No false slashes from walking, idling, a held cock-back, a slow lower, returns to guard (2.5 and
  4 rad/s, and 1–2 rad/s up to 0.44 rad off), or body turns up to 2 rad/s.
* setFlip maps dir → π − dir and dir → −dir. Calibration: 5 swings at 2.4 rad/s slash nothing at
  1×, `pickStrokes` finds 5, and the suggestion makes them slash.

`tests/unit/motion-frames-hilt.test.mjs`: hilt web −1 latches within 3 fast swings; the provisional
rule uses `restUp` (a hilt guard held 1 s flips a wrong sign, a calm blade-down rest of 2 s after
the latch changes nothing); rotFix is never applied in the hilt grip; a rotation change is still a
gap; `setGrip` keeps axisMap, sign and rotFix.

`tests/unit/motion-synth-hilt.test.mjs`: at the guard gravity reads device +y·sin βg and +z·cos βg;
a hilt R swing with lean 0 is a pure roll about the body's forward axis (`speed` is the true peak);
the cock-back takes βabs past π/2 + 1.0; a thrust's peak acceleration is along the body forward;
`hold: 'window'` output is byte-identical to before for a fixed script and seed.

### 5.4 motion/synth.js (PURE)
```js
export function synthesize(script, opts) → RawSample[]
// opts: { rate = 100, rot = 90, tilt = 0, roll = 0, aim = 0, grip = null /* { pitch, roll, yaw } */, hand = 'right',
//         noise = 1, seed = 1, gravitySign = 1, includeLinear = true, includeGravity = true, gyroRange = 34.9, jitter = 0, t0 = 0,
//         hold = 'window' /* | 'hilt' */, lean = 0.45 /* hilt */ }
// hold 'hilt' (below): rot defaults to 0 (portrait-primary) and tilt is ignored. hold 'window' output is unchanged.
// script segments, back-to-back:
//   { kind:'idle', duration }  { kind:'walk', duration }  { kind:'turn', yaw, duration }
//   { kind:'swing', dir, speed = 9, arc = 1.6, radius = 0.55, windup = 0.3, recoil = 0.15, ret = 2.5, wrist = 0.3, pronate = 0.3,
//     wristRoll = 0, rollEnd = 0, retYaw = 0, retPitch = 0 }
//   { kind:'thrust', distance = 0.35, duration = 0.18, hold = 0.08, retract = 0.3, dip = 0.12 }
//   { kind:'throw', speed = 13, windupArc = 2.5, windupSpeed = 6, hold = 0.12, arc = 3.4, twist = 0.45, lookBack = 0.7 }
//   { kind:'raise' }   (wind-up only: lift the phone beside the head and hold; used for overhead chops)
//   { kind:'lower', beta = 0.1, duration = 1.4 }   (both holds: lower the blade to elevation beta)
// hold 'hilt' re-defines swing, thrust, throw and raise (below); idle, walk, turn and lower are shared.
export function scriptDuration(script) → seconds
```
The model:
* The world frame is §3.1. Mirror x when `hand = 'left'`.
* Guard pose: eye `E = (0, 1.66, 0)`, phone centre `P0 = (0.12, 1.48, −0.42)`. The blade
  `b0 = normalize(P0 − E)` is pitched back by `tilt` and rolled by `roll` about b0, with
  screen-x horizontal.
* `aim` turns the guard orientation about the vertical by that yaw: the forearm, and the phone
  with it, points off the body's forward, so the screen faces partly sideways. Swings follow the
  forearm.
* `grip = { pitch, roll, yaw }` is the phone's attitude in the hand. The arm moves the same; only
  what the sensors see changes (`q·G`).
  * pitch > 0 tilts the screen back: top edge away, screen up toward the eyes.
  * roll turns the phone about its own back axis.
  * yaw turns the screen to face partly sideways (+ = right).
* `aim`, grip roll and grip yaw are mirrored for the left hand.
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
  * **Wrist roll**: the hand rolls about the forearm by `wristRoll·sin(π·k) + rollEnd·k`, where
    k is the stroke's progress (s5) and the forearm is the blade's horizontal forward at the
    swing's start, carried by the arm. rollEnd stays until the return. It is mirrored for the left
    hand, and it doesn't tilt the arm's sweep.
  * **Sloppy return**: the return lands off the guard by `retYaw` about the trunk and `retPitch`
    about the body's right axis. The body hasn't turned.
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
* **lower** (both holds): the blade turns about R̂ through the wrist until its elevation (the
  hold's βabs, as the recognizer measures it) is `beta`, min-jerk over `duration`; with the
  defaults it peaks below 1.5 rad/s (a slow lower to a low guard, or back up).

**The hilt hold** (`hold: 'hilt'`; world frame §3.1, right hand, x mirrored for the left):
* Rig: eye `(0, 1.66, 0)`, shoulder `S = (0.19, 1.45, 0.03)`, guard hand (phone centre)
  `H0 = (0.15, 1.24, −0.36)`, elbow `EL = (0.21, 1.02, −0.10)`; the wrist pivot sits 0.05 m below
  H0 along −y. Guard attitude, with F = (0, 0, −1), U = (0, 1, 0) and βg = π/2 − lean:
  `y = cos βg·F + sin βg·U`, `z = −sin βg·F + cos βg·U`, `x = y × z`. Then `roll` about y (mirrored),
  `aim` (a yaw about the vertical: the forearm and the fist with it), and `grip` (q·G).
* The **elbow** is tracked: it starts behind and below the fist along the aimed forearm, stays put
  for wrist motions and during a strike, turns about the shoulder in a cock-back and with the
  trunk, and comes back to the guard with the hand. The fist rolls (pronation, wrist roll) about the
  phone's long axis (its +y, with `grip`).
* `swing { dir, speed = 9, arc = 1.4, windup = 0.3, recoil = 0.15, ret = 2.5, wrist = 0.45, pronate = 0.2,
  wristRoll, rollEnd, retYaw, retPitch, yawShare = 0 }`: with R and b0 the body-right capture and the
  blade (+y) at the start, the axis is `n = normalize(sin dir·R − cos dir·(R × b0))`, so the recognizer
  sees (cos dir, sin dir). `yawShare` blends its horizontal part toward a yaw about world up (a
  forearm sweep). The arm turns `(1 − wrist)·θ` about n through the elbow, and the wrist adds
  `wrist·θ` 30 ms later (orientation only). Wind-up, pause, profile, recoil, pronation, wrist roll
  and sloppy returns as for the window hold; the return covers the blade's turn and the hand's way
  back over the forearm at `ret` rad/s, whichever takes longer.
* `thrust { distance = 0.30, duration = 0.18, hold = 0.08, retract = 0.3, dip = 0.15 }`: the fist
  punches along the body forward (U × R̂), and the tip dips forward by `dip` at full extension.
* `throw { speed = 7, cockArc = 1.5, shoulderArc = 1.4·cockArc/1.5, windupSpeed = 5, hold = 0.12, arc = 3.0, twist = 0.45, lookBack = 0.7 }`:
  * **Cock-back**: the top of the phone tips back by cockArc about R̂ (peak `windupSpeed`) while
    the hand swings up and back by shoulderArc about R̂ through the shoulder (position only). With
    cockArc 1.5 the top reaches β′ ≈ 2.60 at every lean of the test matrix, 0.25 past windupBeta.
    Then the hold.
  * **Release**: the phone turns −arc about R̂ with the late-peaking profile `s(τ^1.4)` (peak =
    `speed`, the firm flick the hilt grip asks for; full power is 7.5), while the hand swings down to
    0.25 rad below its guard angle about the shoulder, the shoulder advances 0.25 m and the trunk
    yaws +twist. Then a 0.3 s follow-through and the return to guard over `lookBack`.
  * These are the hilt defaults only; the window `throw` and `raise` keep theirs.
* `raise { cockArc = 1.5, shoulderArc, windupSpeed = 5, hold = 0.2 }`: the cock-back alone, held.
  A small cockArc (0.5–1.0) is the ordinary lift before a chop of the false-throw tests.

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
{ type:'switchTarget', dir: -1|1, src } // hilt grip: move the lock-on to the next foe left (−1) or right (+1) (§11 player)
```
`switchTarget` comes from a thumb stroke in the hilt layout (below) or the keys Z / X. Like
`recenter` it is never buffered, and the player ignores it unless the lock-on runs (`ctx.input.lockOn`: the hilt grip or buttons), the
player is alive and the camera isn't overridden. The buttons layout's ‹ FOE / FOE › push it
too (`ctx.input.lockOn`, below).
`ctx.input` is continuous state, owned by ui/input.js. It is zeroed on every `modeChanged`
(stick, look, shieldHeld = false, tracked pointers forgotten).
```js
{ moveX, moveZ,             // −1..1: strafe right+, forward+
  lookYaw, lookPitch,       // radians accumulated this frame (touch drag, mouse, gyro via main); player consumes + zeroes
  shieldHeld,               // touch-hold (motion layout), shield button, RMB/Shift/K
  shieldPressedAt,          // ctx.time.gnow when the current hold began (for perfect-block timing)
  phoneSettled,             // main: gestures.settled in motion mode; always true otherwise
  motion,                   // main: true when motion control is active
  grip }                    // main (applyControls): effectiveGrip(settings), 'hilt'|'window'|null (touch), in every
                            // mode (paused too: the viewmodel keeps its hilt pose under the pause menu); never zeroed
```
**Motion layout, window grip** (`#controls` covers the screen):
* A touch raises the shield only if it starts outside the HUD buttons and outside a 12 px edge
  strip, and its contact radius is ≤ 40 px when reported.
* Small corner buttons: **pause** (top-left), **weapon swap** (top-right, showing the spear
  count), and **recenter** (top-centre).
* `contextmenu` and non-passive `touchstart` are preventDefault-ed.
* If a touch has been held for more than 5 s while at least 3 slashes fired, show this tip
  once per battle: "Your shield is up while you swing. Rest your thumb on the frame, and press
  the glass to raise the shield."

**Motion layout, hilt grip**: `#controls` gets `ctl-motion ctl-hilt` when the effective grip is
the hilt (and keeps `ctl-left` for a left hand). The layout is re-applied whenever `control`, the
effective grip or `leftHanded` changes (checked each update while battle or training is active).
Nothing but the shield and the thumb strokes lives on the thumb's arc (the hand model of §3.3):
* **Pause** (48 px) at the top-left and the **weapon button** (`.ctl-swap`, 48 px, with the spear
  count) at the top-right, `safe + 10 px` from the edges, swapped with `ctl-left`: the off hand's
  reach, out of the thumb's. The weapon button fires on the **tap's release** in this layout, and
  sliding off it cancels (touch and window fire it on press). There is **no thumb-flick weapon
  swap**: the swap is the top button only. **Recenter** is hidden.
* **Shield**: a pointer that starts on `.ctl-surface` raises the shield at once, in the same frame
  (`motionShieldCount++`, `shieldHeld = true`, `shieldPressedAt = gnow`), unless it starts on a
  shown HUD button (forwarded; in this layout only READY, which sits at the top), its contact
  radius is > 40 px (a palm), or it starts in a reject strip (`inHiltStrip(x, y, vw, vh, left, fingerStrip)`,
  for a right hand, mirrored with `ctl-left`):
  * top 12 px (`hiltTopStrip`) and bottom 24 px (`hiltBottomStrip`, the fist's lower fingers);
  * the thumb side (right) 20 px (`hiltThumbStrip`: the thumb's base and the palm heel);
  * the finger side (left), where the fingertips curl onto the glass: `settings.hiltFingerStrip`
    (default `INPUT.hiltFingerStrip` 44, clamped to `hiltFingerStripMax` 120, read on every
    touch-down) below `hiltFingerFrom` (40%) of the height, and 16 px (`hiltFingerTop`) above. The
    Agoge's grip check widens it when it finds a resting finger there (§11 training).
* Lifting (pointerup or pointercancel) decrements the count; the shield goes down when no shield
  pointer is left. Every shield pointer counts (a second finger, the off hand); pause and the
  weapon button capture their own pointers and never raise it.
* **Thumb strokes**, on a shield pointer only. Every move sample (coalesced events included) goes
  into the pointer's `Trail` (`pushSpaced`, samples ≥ `strokeGapMs` 10 ms apart). `findThumbStroke`
  classifies a **switch**: |dx| ≥ `lockSwipePx` (44 px) within `lockSwipeMs` (220 ms), at ≥
  `lockSwipeSpeed` (0.35 px/ms) horizontally, and |dx| ≥ `lockSwipeRatio`·|dy| (0.8: up to ≈ 51° off
  horizontal, because a thumb pivoting in a fist strokes in arcs). It yields
  `{ type: 'switchTarget', dir: dx > 0 ? 1 : −1, src: 'touch' }`. Steeper strokes, slow creeps (a
  held block) and anything else do nothing. The shield stays held throughout; one action per
  stroke, re-armed after the pointer reverses ≥ 20 px (`strokeRearmPx`), pauses ≥ 150 ms
  (`strokeRearmMs`) or lifts.
* **Swing suppression**: the thumb slides on the glass when the phone is swung, and that must not
  change the target. Pointer events arrive before the frame's `pumpMotion`, so a classified stroke
  is **queued**, not pushed. input.js keeps a fixed 8-entry ring of per-frame `gestures.debug.m`
  values with their real times. `inputCtl.update()` (after `pumpMotion`) commits a queued stroke
  once `strokeCommitMs` (100 ms, real time) have passed since it was classified, if the phone's m
  stayed ≤ `strokeCalm` (2.5 rad/s) from `strokeLookbackMs` (50 ms) before the stroke's first sample
  and no `gesture` event fired meanwhile (the end-of-swing `overSpeed` notice counts). Otherwise it
  is dropped silently: a stroke during a swing is a slip. A swipe made once the phone is back near
  guard therefore counts, also right after a kill.
* **Tip**: the resting-thumb tip keeps its trigger, and names the cause from the longest-held
  shield pointer: in the outer 35% of the width opposite the thumb (`fingerSide`) it is
  `HILT_TIP_FINGER` = "A finger is touching the glass at the edge. Keep your fingers on the back of
  the phone.", elsewhere `HILT_TIP` = "Your shield is up while you swing. Rest your thumb on the edge
  of the phone, and press the glass to block."
* For the Agoge's grip check: `ctx.inputCtl.shieldPointers` (shield pointers down now) and
  `ctx.inputCtl.lastShieldDown` (`{ x, y, t }`, CSS px and `ctx.time.now` at the touch-down, −1
  before the first; one object mutated in place).
* input.js plays no haptics: fx owns the shield-up pulse, the lock tick and the "nothing there"
  double tick (§11 fx). Hygiene as above, plus `touch-action: none` on the surface.

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

**Buttons layout** (`control: 'buttons'`, `#controls.ctl-buttons`, landscape, mirrored when
leftHanded). Every move is a button, so nothing is swiped, dragged or swung, and the bare glass does
nothing (HUD buttons are still forwarded; a mouse click never takes the pointer lock).
* The view is the hilt grip's lock-on camera: main.js sets `ctx.input.lockOn` for the hilt grip and
  for buttons, and the player, the volley rule (combat.js), the rear-attack grace (ai.js), the HUD's
  lock marker and the Agoge's lock step all follow `lockOn`. What is specific to holding a phone
  (the hilt viewmodel, the swing haptics and sounds, portrait) stays with the hilt grip.
* Bottom-left: a move pad (four 46 px buttons, each walks while held). Above it, **SHIELD** (hold)
  and **BASH**.
* Bottom-right: the sword pad, 3×3 buttons of 56 px (`PAD` in ui/input.js). Each arrow cuts the
  way it points and pushes `slash` with that direction (0 right, π/2 up, the corners at the
  diagonals); the middle is **STAB** (`thrust`). Power `buttonPower` (0.8); one strike per press.
* Above the sword pad: **‹ FOE** and **FOE ›** push `switchTarget` −1 and +1.
* With a spear in hand, **THROW** appears left of the sword pad and works as in the touch layout.
* The Agoge panel sits between the two clusters (`#training.is-buttons`), and the Agoge uses the
  lock step and each step's `buttons` text (falling back to `touch`).

**Keyboard and mouse:**
* WASD moves. The mouse looks with pointer lock, at 0.0025 rad/px.
* Left-drag slashes in the drag direction, and a quick left click thrusts.
* RMB, Shift or K holds the shield. F bashes. Q swaps weapon. R or the middle button
  throws (power 0.7).
* Arrow keys slash ←→↑↓. Space thrusts. Esc or P pauses. C recenters.
* Z and X push `switchTarget` −1 and +1 in every layout (the desktop and e2e stand-in for the hilt
  thumb swipe; the player ignores them outside the hilt grip).

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
* **Motion strikes** (`src: 'motion'`: the phone has already swung):
  * A motion slash strikes at `0.5 × TIMING.slash.strike` (`MOTION_STRIKE_SCALE`); its total and
    cancelAfter are unchanged. Every other attack keeps TIMING.
  * A motion slash or thrust (or the bash a thrust becomes) arriving during a cancellable attack
    starts at once, in that frame. If the current attack's strike hasn't resolved, the rest of
    its lunge lands and its strike resolves first; then the rest of that attack is cancelled.
  * A motion strike is buffered only while staggered, drawing or throwing. A buffered one may
    start as soon as the current attack has struck.
  * Touch, key and test actions wait for cancelAfter, as above.

**Targets.** A normal slash, thrust, spearThrust or bash damages **one** target: the focus
target if it's inside the attack's sector, otherwise the nearest hostile in the sector. The
sector's halfAngle is acquisition forgiveness, not an area. Only `sweep` and `stagger_all` hit
every hostile in their arc. Sectors are centred on the **attack yaw**.

**Aim assist.** `focusTarget = pickTarget(hostiles, pos, yaw, 0.70, reach + 1.5)`, re-evaluated
every frame.
* **Motion mode, window grip**: the camera only ever turns with the player's body (gyro look,
  §5.3) or a recenter. Nothing turns it toward a foe: there is no auto-face and no pitch easing.
  * The attack turns instead: `attack.yaw = yaw + clamp(angleToFocus, ±0.52)` (±30°,
    `MOTION_YAW_CLAMP`), with the focus target taken at the attack's start.
  * Pitch is steady and slightly lowered: −0.07 rad (`MOTION_PITCH`, the chest of a foe in
    reach), eased back at 4 rad/s after a recenter or a mode change.
* **Motion mode, hilt grip**: the view faces the lock (the lock-on camera, §11 player). The
  focus is the lock while it is alive, within reach + 1.5 m and within 0.70 rad of the view;
  otherwise it is `pickTarget` as above, so a closer foe in front still gets hit while the lock is
  on a far one. The attack yaw, lunge, single-target choice and combos work as in the window grip
  on that focus, and the pitch is the same steady `MOTION_PITCH`.
* **Touch and keyboard** (if `settings.aimAssist`): during the first 0.1 s of an attack, the
  view yaw is pulled up to 0.21 rad toward the focus target. Never with the lock-on, whose spring
  is the hilt grip's only yaw writer.

**Lunge.** If the focus target is beyond the weapon's reach but within reach + 1.0 m, the
player steps up to 0.8 m toward it over the attack kind's TIMING `strike` time (≥ 0.05 s). A
motion slash's step is no faster: it strikes halfway through it, so its blow reaches from the
lunge's end point (`pos + lunge·lungeLeft`). Positions clamp to
`world.bounds`. In motion mode, the player drifts back to the line slot at 1 m/s after 1.5 s
without attacking.

**Shield.**
* `shieldRaise` moves toward 1 while held, at a rate of 1/shieldRaise, and toward 0 otherwise,
  at 1/shieldLower.
* **Striking from behind the shield**: if the shield is held when a slash, thrust or
  spearThrust starts, it dips to `shieldDip` for that attack's `total`, then returns. Melee
  can't be blocked during the dip. Frontal arrows are still blocked at ≥ 0.4.
* **Block**: `shieldRaise ≥ 0.7`, with the attacker within ±1.31 rad (75°) of the view yaw. In the
  hilt grip the view faces the lock, so its blows are blockable, and so is anyone within 75° of it.
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

**Anti-turtle.** Once the player has held the shield up (raise ≥ 0.7) without a break for 3 s,
player-targeted blows become heavy (guard breaks). This is waived for 7 s after a volley
warning. Allies take on one of the player's attackers that the player hasn't hit for 15 s.

**Bash.**
* A thrust with the shield held, or an explicit bash.
* Reach 1.6 m, halfAngle 0.44 rad, one target (`wall` hits everything within 2.5 m).
* The target staggers 1.1 s (heavy enemies such as the captain 0.5 s, never knocked down), is
  knocked back 0.9 m along the hit direction, loses its guard, and is knocked down if
  power > 0.75. A wind-up is interrupted.
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
* `refillSpears(total = 4)`: tops up to `total` spears (one in hand). It never takes spears
  away. In training a spear is always redrawn.
* `dropSpears({ keepInHand })`: spares = 0. Without keepInHand the hand is emptied too, and the
  weapon becomes the sword. Day 3 uses it ("Our spears are broken. Swords!").
* Throw power > 0.7 can skewer two actors.

**Throw.** At release (attack start + `TIMING.throw.windup`, or +0.03 s for motion throws),
player computes:
* `origin = eye + right3(yaw)·0.22 + up·0.10 + forward3(yaw,0)·0.35`
* `speed = lerp(20, 34, power)`
* The target is the living hostile with the smallest view angle within 0.262 rad and ≤ 45 m,
  archers included, never one flagged `noThrowLock`. In the hilt grip it is the **lock** instead,
  while the lock is lockable, still in `ctx.actors`, not `noThrowLock` and within 45 m (§11
  player); the throw then flies at it even before the view has finished turning to it.
  * A moving target is led with its velocity measured since the throw started (capped at 5 m/s).
  * With a target: `yawL = yawTo(chest − origin)`, `pitchL = ballisticPitch(speed, dxz, dy)`.
  * Otherwise: `yawL = yaw`, `pitchL = pitch + 0.06`.
* `velocity = speed·forward3(yawL, pitchL)`.

The view is not rotated. player calls `combat.throwSpear({ origin, velocity, power, byPlayer: true, src })`.

**Score** (owned by player):
* A kill scores `round(100·chain)`, with +250 for a spear kill and +150 for into the Gulf.
* The chain starts at 1.0 and rises 0.1 per player hit, capped at 3.0. It resets on
  `playerHurt`, on `waveCleared`, and after 4 s (game time) without a player hit.
* Perfect block: +50. A combo adds its `bonus`.
* `scoreChanged` is emitted on every change.

## 8. Combos (game/combos.js)

```js
export const COMBOS = [   // rows; 'either order' combos are two rows with the same id
  { id:'twin',   name:'Twin Fangs',          seq:[Rish, Lish],             mult:1.5, flow:true },
  { id:'twin',   name:'Twin Fangs',          seq:[Lish, Rish],             mult:1.5, flow:true },
  { id:'fury',   name:'Fury of Lakedaimon',  seq:[Rish, Lish, Rish],       mult:1.8, flow:true, effect:'sweep', arc:1.75 },
  { id:'fury',   name:'Fury of Lakedaimon',  seq:[Lish, Rish, Lish],       mult:1.8, flow:true, effect:'sweep', arc:1.75 },
  { id:'storm',  name:'Storm of Bronze',     seq:[Rish, Lish, Rish, Lish], mult:2.4, flow:true, effect:'sweep', arc:1.75, slowMo:[0.5, 0.4] },
  { id:'storm',  name:'Storm of Bronze',     seq:[Lish, Rish, Lish, Rish], mult:2.4, flow:true, effect:'sweep', arc:1.75, slowMo:[0.5, 0.4] },
  { id:'cleave', name:"Leonidas' Cleave",    seq:[Uish, Dish],             mult:2.2, slow:true, breakGuard:true, slowMo:[0.35, 0.5] },
  { id:'xcut',   name:'Cross of Ares',       seq:[{DR}, {DL}],             mult:1.7, bleed:{ dps:6, dur:3 } },
  { id:'xcut',   name:'Cross of Ares',       seq:[{DL}, {DR}],             mult:1.7, bleed:{ dps:6, dur:3 } },
  { id:'answer', name:"The Hoplite's Answer", seq:[{B}, {T}],              mult:1.3, effect:'execute' },
  { id:'drive',  name:'Phalanx Drive',       seq:[{T}, {T}, {T}],          mult:1.6, knockback:2.5 },
  { id:'wall',   name:'Shield Wall',         seq:[{B}, {B}],               mult:1.0, effect:'stagger_all', radius:2.5 },
]   // each row also has desc and bonus = round(100·mult)
export const COMBO_WINDOWS = {   // frozen; seconds between consecutive attack starts
  touch:  { chain: 1.25, flow: 0.6, step: 1.25 },
  motion: { chain: 2.6,  flow: 1.7, step: 1.7 },
}
export class ComboTracker {
  constructor(table = COMBOS, window = 1.25, flow = Infinity, step = Infinity)
  setWindows({ chain, flow, step })   // e.g. COMBO_WINDOWS.touch or .motion; omitted fields are kept
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
* Every gap between a row's tokens must also be within one of the tracker's windows:
  * `flow` rows (twin, fury, storm): within `flow`, so slow right-left swiping is no combo
  * `slow` rows (the Cleave: uppercut, raise the blade overhead, chop): within the whole `chain`
    window
  * every other row: within `step`
* Touch: flow 0.6 s, and everything else 1.25 s.
* Motion: a strike plus its return to guard takes about 1.5 s, so flow and step are 1.7 s. The
  Cleave as the Agoge teaches it chops about 2.5 s after its uppercut, so the chain window is
  2.6 s, which only the Cleave can use: two other swings 2.5 s apart are no combo.
* With no setWindows the tracker has window 1.25 s and no flow or step limit.
* `execute` (the Hoplite's Answer) finishes a staggered target at ≤ 35% HP; otherwise it deals
  ×2 damage.

**Who does what.**
* player pushes a token when an attack **starts** (as it emits `swing`), with `t = gnow`, after
  `setWindows(COMBO_WINDOWS.motion)` in motion mode (`ctx.input.motion`), else `.touch`. The
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
* With the motion windows:
  * U, D up to 2.6 s apart is the Cleave (UL, DR at 2.5 s too), and 2.7 s apart is not.
  * No other row spans a gap over 1.7 s, and each still completes with 1.6 s gaps.
  * R, L 2.5 s apart is no Twin, and DR, DL 2.5 s apart is no Cross.

## 9. Settings (persisted under `thermopylae.settings`)

```js
{ control: 'touch',          // 'motion' | 'touch' | 'buttons'; motion is offered only if supported
  sensitivity: 1.0,          // SENSITIVITY_MIN–MAX = 0.5–2.2 (§5.3); the Agoge suggests 0.6–2.2
  gyroLook: true,            // motion mode: deliberate body turns turn the view 1:1; strikes never do (§5.3)
  aimAssist: true,           // touch/keyboard camera pull (motion always rotates attacks)
  leftHanded: false,         // mirror the touch layout and synth demos
  haptics: true, voice: true, blood: true, helmetView: true,
  quality: 'auto',           // 'auto'|'low'|'medium'|'high'
  master: 0.9, music: 0.55, sfx: 1.0,
  sensorDebug: false,
  safetyAccepted: false,
  motionFlipX: false,        // motion: a mirrored sensor setup, slashes come out left↔right (dir → π − dir; look too)
  motionFlipY: false,        // motion: ... up↔down (dir → −dir)
  // the grips (§3.3); every reader goes through core/grip.js
  grip: DEFAULT_GRIP,        // 'hilt' | 'window'. Motion only; touch ignores it
  gripChosen: false,         // the grip chooser has been answered
  sensitivityHilt: 1.0,      // the hilt grip's own sensitivity (0.5–2.2); `sensitivity` stays the window grip's
  motionFlipXHilt: false,    // the hilt grip's own mirrored-setup flips; motionFlipX/Y stay the window grip's
  motionFlipYHilt: false,    //   (a sensor defect maps to different body axes in portrait and landscape)
  lockAuto: true,            // hilt: the lock turns to an attacker the shield can't cover (§11 player)
  hiltFingerStrip: 44,       // px (44–120): the hilt finger-side reject strip (§6), widened by the Agoge grip check
  hiltAgogeDone: false,      // the hilt Agoge has been finished once
  hiltSafetyAccepted: false } // the hilt safety notice has been accepted
```
* Settings UI (Controls tab): sensitivity, gyroLook, and the two toggles "Swap swing left/right"
  and "Swap swing up/down" are motion-only rows, greyed out in touch mode. The sensitivity slider
  spans SENSITIVITY_MIN–MAX in steps of 0.05.
  * A first motion row, **Grip**, is a segmented control *Hilt (upright)* / *Window (sideways)*
    (`motionOnly`), shown in every build so the hilt grip can be tried before its release.
  * **Sensitivity** edits `settings[sensitivityKey(settings)]`, and the two **Swap** rows edit
    `settings[flipKeys(settings).x / .y]` (FIELDS `keyOf`): each grip keeps its own.
  * **Gyro look** is greyed in the hilt grip, hint "Hilt grip: the view locks onto your foe".
  * **Auto-turn** (`lockAuto`, hint "Hilt grip: turn to a foe attacking from behind") is
    `hiltOnly`, a FIELDS flag: greyed unless the effective grip is the hilt.
  * Only `grip` and `lockAuto` have rows of their own among the new keys; the game sets the rest.
* The Agoge also toggles the effective grip's flips when it sees mirrored swings (§11).

**The grips** (`js/core/grip.js`, PURE). Everything that branches on the grip (main, menus, hud,
input, the viewmodel, fx, audio, training) uses these; no module compares `settings.grip` by hand.
```js
export const GRIP_IDS = ['hilt', 'window'];                       // frozen, in the Settings segment's order
export const HILT_RELEASED = false;                              // the release gate (below)
export const DEFAULT_GRIP = HILT_RELEASED ? 'hilt' : 'window';    // DEFAULT_SETTINGS.grip
export function effectiveGrip(settings) → 'hilt'|'window'|null   // null unless control === 'motion'; a missing or unknown grip → 'window'
export function appOrientation(settings) → 'portrait'|'landscape' // portrait iff effectiveGrip(settings) === 'hilt'
export function sensitivityKey(settings) → 'sensitivityHilt'|'sensitivity'
export function flipKeys(settings) → { x, y }                    // one of two frozen objects: hilt → motionFlipXHilt/YHilt, else motionFlipX/Y
export function migrateSettings(stored) → stored                 // the boot migration (below); mutates and returns its argument
```
* An unknown or missing grip reads as `'window'`, so every context that never heard of grips (the
  UI sandbox, the gameplay harness, old stored settings) keeps the window grip. New players get the
  hilt grip through `DEFAULT_SETTINGS.grip`, never through this fallback.
* **Release gate**: until `HILT_RELEASED` is true, `DEFAULT_GRIP` is `'window'` and the grip chooser
  is never offered, so every intermediate build CI publishes (§2.2) keeps the window grip for new
  and existing players; the Grip row and `?grip=hilt` let the hilt grip be tried. Flipping it makes
  the hilt grip the default for new motion players and offers the chooser (§11 menus). The chooser
  trigger is tested with `settings.gripChosen === false`, never a falsy test.
* **Migration** (main.js boot, before the stored settings are merged over the defaults): stored
  settings with `control === 'motion'` and no `grip` key get `grip = 'window'`, so an existing
  motion player keeps what they know (the chooser offers the hilt grip once, after the release). A
  fresh install has no grip key and gets `DEFAULT_GRIP`.
* Orientation per control and grip (§10 applies it):

  | control | grip | title and menus | battle and training | request |
  |---|---|---|---|---|
  | touch | any | landscape | landscape (not locked) | `lockOrientation(false, 'landscape')` |
  | motion | window | landscape | landscape, locked to the current side | `(false\|true, 'landscape')` |
  | motion | hilt | **portrait** | **portrait** (portrait-primary, always locked) | `(false\|true, 'portrait')` |

  The fight never rotates on its own: only a settings change (in the title or the pause menu)
  rotates the app, and settings are only reachable through pause, so the grip never changes in play.

`thermopylae.best` is `{ score, kills, day }`, written by menus on `gameOver`.

## 10. Frame order and lifecycle (main.js)

**Each frame:**
0. Time:
   * `dt = min(real, 0.05)`.
   * `hitStop` is decremented unless paused or in gameover.
   * gdt, pdt and gnow are computed per §3.2.
1. Motion glue (`pumpMotion(dt)`), when `mode ∈ {battle, training}` and (`settings.control === 'motion'` or `ctx.motionDemo`):
   * Every frame, first: `gestures.setSensitivity(settings[sensitivityKey(settings)])`,
     `setWeapon` ('spear' with a spear in hand, else 'sword') and
     `setFlip(settings[flipKeys(settings).x], settings[flipKeys(settings).y])` (each grip keeps its
     own, §9; constant keys, no allocation). During an Agoge demo (`ctx.motionDemo`) it is
     `setFlip(false, false)`: the demos are synthesized for an unmirrored phone.
   * `poll` → `frames.process` → `gestures.push`. Each event emits `gesture`.
   * slash (with its snapped `dir`), thrust and throw events become actions with
     `src: 'motion'`, except while `fx.cameraOverridden`, when they are dropped.
   * A `windup` with a spear in hand (camera not overridden) calls `viewmodel.play('throwWindup')`.
   * Then each of `gestures.consumeCandidates()` emits `swingCandidate`.
   * `consumeLook(dt)` (the frame's dt) is called every frame. `ctx.input.lookYaw += yaw` only if
     `settings.gyroLook`, the control is motion (not a demo) and the camera isn't overridden. (The
     hilt recognizer's look is always 0, and the player ignores lookYaw under the lock-on.)
   * `gestures.holdLook()` when the spear cam starts, `resumeLook()` when it ends.
   * `input.phoneSettled = gestures.settled`.

   The poll is skipped entirely outside motion mode (`phoneSettled = true`).
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
* Every setMode calls `actions.clear()` and `gestures.reset()`, then `ctx.applyControls()`, and
  emits `modeChanged {mode, prev}` **after** the work.
* Pause menu: Resume → `ctx.resume()`. Restart → `setMode(pausedFrom, { day: waves.day })`.
  Quit → `setMode('title')`.
* Death: player emits `playerDeath`. waves emits `gameOver` 3.2 s later (gdt). main switches
  to `gameover` on `gameOver`.
* **`ctx.applyControls()`** brings everything that depends on the control and the grip in line
  with the settings, so the recognizer, `ctx.input.grip` and the orientation never disagree, even
  after a direct `settings.control` change (tests, the menus' probe fallback). With
  `g = effectiveGrip(settings) || 'window'`:
  * `motionFrames.setGrip(g)` and `gestures.setGrip(g)` (each a no-op if unchanged; a change resets
    that stage and forgets the learned guard; the device calibration is kept);
  * `ctx.input.grip = effectiveGrip(settings)`, in every mode;
  * `gestures.setFlip` from the effective grip's flip keys (not during a demo);
  * `<html data-grip="hilt|window|none">` (written only on change, from boot on: CSS keys on it);
  * `platform.lockOrientation(active && control === 'motion', appOrientation(settings))`, active
    meaning battle or training: portrait for the hilt grip in every mode, locked while fighting.

  setMode calls it on every mode change (in place of a bare lockOrientation call), boot calls it
  once right after the motion pipeline is built (before the boot screen), and menus.js calls it
  after every write of `control` or `grip` (the support-probe fallback included), so the app
  rotates while the menu is still open and the menus re-lay out through the CSS media queries.
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
* Load settings (`migrateSettings` first, §9; the URL's `control`, `quality` and `grip` override
  them, §14), then `platform.bindSettings`, then build the motion pipeline and `applyControls()`.
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
  * `swing` → slash / thrust / spearThrust / bash / throw. A touch throw plays `throwWindup`,
    then `throwRelease` at TIMING.throw.windup. A motion throw plays `throwRelease` at once
    (motion sync, below).
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
  to `+0.35·(cos dir, sin dir)`, with a motion-trail ribbon. It crosses the centre at
  `TIMING.slash.strike` for a touch, key or test swing, after the anticipation (into the cocked
  pose).
* **Motion sync** (a `swing` with `src: 'motion'`; `gfx/viewmodel/motion.js` exports
  `MOTION_SWING = { s0: −1.3, a0: 0.5, blendIn: 0.012, minStrike: 0.02, maxStrike: 0.25 }`,
  `motionStrike`, `motionSlashTimes`, `thrustCurveMotion`, `bashCurveMotion`). The phone has
  already wound up, so there is no anticipation.
  * The one-shot animates on the swing's own frame, in the payload's `dir` (the gesture's snapped
    dir).
  * Its contact pose lands at `lead + motionStrike(strikeIn)`.
    * `motionStrike` clamps the payload's `strikeIn` to [minStrike, maxStrike]. Without one it
      falls back to TIMING's strike (0.03 s for a throw).
    * `lead` is this frame's pdt (clamped to 0–0.05). The swing fires inside player.update, and
      this frame's viewmodel update still adds its pdt. So the contact pose renders on the frame
      the strike resolves, and is the pose a hit-stop freezes.
  * Slash: `ta = 0`. The blade starts at the cocked end of the path (s = s0), already moving at
    a0 of its peak speed, and reaches the peak at contact (s = 0 exactly at the strike).
    * After contact come the touch slash's follow-through and recovery:
      `total = strike + max(0.12, TIMING.slash.total − TIMING.slash.strike)`. The blade always
      follows through past the path end.
    * The hand joins the moving path from its current pose within `blendIn` (under one 60 Hz
      frame), and the trail shows from the first frame.
  * Thrust, spearThrust and bash: no draw-back or chamber.
    * The extension moves from the first frame (`u·(0.6 + 0.4u)`) and is full at the strike.
    * Then comes the touch retract: a thrust holds 0.06 s first; a bash goes straight back.
      `total = strike + (TIMING total − TIMING strike)`.
    * The shield's bash punch lands on the same strike.
  * Throw: `throwRelease` with the release pose at `lead + motionStrike(strikeIn)` (0.03 s, the
    spear's spawn, §7). The viewmodel spear hides then.
  * A new swing always restarts the arm from wherever the hand is, even mid-swing.
  * Touch, key and test swings keep the authored timing.
* A bash punches the shield forward. A throw draws the spear back over the shoulder and whips
  it forward. The viewmodel hides the spear at release.
* The overlay quad (vignette, hurt flash, Corinthian-helmet eye-slit when `helmet`) is drawn
  last in the vm pass with depthTest off. hud drives it via setOverlay.
* **Portrait** (any portrait screen; the hilt grip runs in portrait):
  * `viewmodel.update` sets `vm.camera.fov` from the aspect, written only on change: 60 in
    landscape (unchanged) and 80 in portrait (about 41° across at 0.45). main.js keeps setting only
    the aspect. The framing (fov, slash path, poses, x spreads) is refreshed when the aspect
    changes, never per frame.
  * Portrait variants of the spear (fist at (0.125, 0.03, −0.33) aimed at (0, −0.7, −2.4)), thrust
    and aspis poses, authored for a `RIG.portraitAspect` (0.45) phone and spread a little wider on a
    wider portrait screen; the wind-up, release and draw poses are reused. The aspis sits low at
    (−0.29, −0.58, −0.40) and raised at (−0.06, −0.49, −0.37), so its rim sits just under the foe's
    head; eulers, one-shots, the perfect snap and the guard-break knock are unchanged.
  * **Slash path**: an ellipse, the tip travelling along `(hx·cos dir, hy·sin dir)·s` in the plane
    z = −0.55. `motion.js` exports `slashHalf(aspect, out) → [hx, hy]`: `RIG.slashHalfPortrait`
    [0.22, 0.34] at 0.45 and below, easing back to the landscape circle (`RIG.slashHalf` 0.35) at a
    square screen. The half-axes travel in the tuning object: `slashGeometry(dir, s, out, reach, P)`
    reads `P.half` (absent: today's circle), so the signature and every existing call are
    unchanged; the viewmodel passes a per-aspect copy of SLASH with `half`. Motion sync, the trail
    and timings are unchanged; the edge choice is made against the hilt guard's wrist.
  * **Helmet overlay**: a `uPortrait` uniform (no new program) switches to a portrait mask that
    keeps to the edges: ≤ 6% of the screen darkened by more than 40%, none of it inside the central
    70% × 70%. The vignette and hurt flash use `uAspect` as before.
* **Hilt guard** (`ctx.input.grip === 'hilt'`, kept under the pause menu): the sword is held as the
  player holds the phone, blade upright: `POSES_HILT.swordLow` hand (0.12, −0.27, −0.40), blade toward
  (−0.05, 0.93, −0.36), wrist hint (0.3, −0.3, 0.9); `swordHigh` (shield up) puts the hand at
  (0.14, −0.30, −0.38). The fist sits low in the right of the view, the tip above the locked foe's
  shoulder.
* **Portrait acceptance** (`tests/e2e/hilt-vm.mjs`, projected mesh bounds and a flat-colour mask
  pass at 412×915 and 360×800): at guard with the shield down the sword is visible (blade ≥ 25% of
  the height) inside x ∈ [45%, 100%] and the aspis covers ≤ 15% at the lower left; shield up, the
  aspis covers 35–55% with its rim 46–60% from the top; a locked foe at 1.6–2.2 m shows his head
  above the rim; nothing of the rig covers the centre 20% × 20%; ≤ 7 draw calls in the vm pass.

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
  `shield`, `spearThrown`, `volley` and `swing` (blood follows the player's blade; a motion swing
  ends a hit-stop, below).
* **Hit-stop and shake fire only for events involving the player**: hit/kill with byPlayer,
  block with by === 'player', and playerHurt. AI-vs-AI hits get only particles (and audio).
  * `hitStop(s)` sets remaining = max(remaining, s). A new one can't start within 0.08 s of
    the last one ending.
  * A motion swing (`swing` with `src: 'motion'`) ends a running hit-stop at once
    (`TimeControl.endHitStop()`). The phone is already swinging again, and the new attack and its
    animation run on pdt. The cut-short freeze doesn't count for the 0.08 s rule, so the new blow
    (striking about 50 ms later) gets its own hit-stop. It does nothing while the spear cam runs,
    so the cam's impact freeze stays.
  * Durations: player hit 50 ms, kill or crit 85 ms, block 35 ms, perfect 110 ms,
    playerHurt 60 ms.
  * Shake is in radians: hit 0.006, kill 0.010, bash 0.012, block 0.008, perfect 0.012,
    hurt 0.025, guardBreak 0.035, lasting 0.12–0.3 s.
  * In motion mode each is scaled by `MOTION_SHAKE = { hit: 0.4, kill: 0.4, bash: 0.4,
    block: 0.6, perfect: 0.6, hurt: 1, guardBreak: 1, other: 0.5 }` (`other`: the public
    `shake()`). The camera is the phone in the swinging hand. The player's own blows are already
    confirmed by the swing, the haptic, the sound and the blade's contact pose. A block lands
    while the phone is held still. Blows taken hard stay full.
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
  | arrow on the shield | 12 ms / 0.5 |
  | spear release | [0,25,60,25] |
  | spear impact | [0,30,40,60] |
  | volley warning | [0,20,90,20] |

  In motion mode the player can't see the screen mid-swing, so haptics and sound are the main
  confirmation channel.
* **Hilt grip feedback** (`ctx.input.grip === 'hilt'`; window and touch keep `HAPTICS` and get no
  slow motion). With the phone in the swinging fist, haptics are the main channel, sound is second,
  and the screen is read when the phone is back at guard (the last-strike chip, §11 hud).
  * `HAPTICS_HILT` (exported, frozen) has a hilt row for every `HAPTICS` key plus five hilt-only
    cues. Rows are a pulse `[ms, strength]` (every 2-element row is dispatched as a pulse, so its ms
    is > 0) or an Android pattern `[wait, on, off, on, …]` (≤ 16 values, each ≤ 400 ms). A swing is
    felt mid-motion and many motors barely render 10 ms, so every "on" segment of a strike result
    is ≥ 30 ms, and hit, guarded, kill and hurt have four different shapes:

    | key | event | hilt row | reads as |
    |---|---|---|---|
    | fleshHit | flesh hit by player | `[45, 1.0]` | one short knock |
    | guarded | absorbed by an enemy guard | `[0, 30, 35, 30, 35, 30]` | three short (rattle) |
    | kill | kill | `[0, 45, 45, 110]` | knock + long |
    | hurt | hurt | `[160, 1.0]` | one long buzz |
    | block | player block | `[40, 0.8]` | solid |
    | perfect | perfect block | `[0, 30, 40, 30, 40, 90]` | tick-tick-long |
    | shieldUp | shield up | `[15, 0.4]` | light tick |
    | arrowShield | arrow on the shield | `[20, 0.6]` | tap |
    | spearArmed | a `windup` gesture with the spear in hand (camera not overridden) | `[0, 35, 45, 35]` | double: the cock-back counted |
    | spearRelease | spear release | `[0, 30, 60, 30]` | double |
    | spearImpact | spear impact on an actor | `[0, 40, 40, 90]` | heavy |
    | spearGround | spear in the ground or the sea | `[20, 0.5]` | light |
    | volleyWarning | volley warning | `[0, 40, 90, 40]` | double |
    | lockTick | `lockOn` with a target (switch, acquire) | `[30, 0.6]` | tick |
    | lockNone | `lockOn` reason `'none'` (a swipe found nobody) | `[0, 30, 70, 30]` | soft double |
    | rearThreat | `enemyWindup` at the player from beyond `LOCK.threatAngle` of the view | `[0, 80, 60, 80]` | two long: urgent |

    The attack-from-behind haptic plays whether or not the lock turns (so `lockOn` reason
    `'threat'` adds none), and again for wind-ups re-emitted after the spear cam.
  * **Coalescing**: a haptic within `HILT_HAPTIC_GAP` (0.04 real s) of the last is dropped, except
    the urgent kill, hurt, perfect and rearThreat, which replace it (unless the last one was that
    same row within the gap, so a burst of rear wind-ups in one frame is one bridge call). A whiff
    gives no haptic.
  * **Slow motion** `HILT_SLOWMO = { kill: [0.4, 0.22], heavy: [0.3, 0.30], perfect: [0.5, 0.18], minGap: 2.5 }`
    (frozen), each `slowMo(scale, realSeconds, { playerRealtime: true })`, a flourish on a big blow:
    * a kill by the player that is not a spear or bleed kill: `heavy` for a crit (read from the
      killing `hit`), a captain or Into the Gulf; otherwise `kill` when the last player swing's power
      was ≥ `HILT_SLOWMO_POWER` (0.55). `onSwing` records it (`fx.lastSwingPower`, a getter);
    * a perfect block: `perfect`;
    * at most one per `minGap` real seconds, none from a volley's `impact` to its `end` (overlapping
      volleys counted, with a safety net of the impact's game-time duration + 0.5 s). A slow-mo
      TimeControl refuses (the spear cam) doesn't use up the gap. Combo slow-mo merges as before.
* **Spear cam** (fx is the only caller; it starts on `spearThrown` with `projectile.byPlayer`).
  Times are in **real** seconds, and frozen while paused.
  * **Motion throw**: scale 0.1 while trailing the spear, until `ctx.input.phoneSettled` has
    been true for 0.1 s, or for at most 1.4 s. Then ramp over 0.15 s to
    `scale = clamp(remainingFlightGameTime/1.6, 0.3, 1.0)`. In the hilt grip `phoneSettled` is the
    hilt settle (§5.3: the screen upright, not facing down), so the hold lasts until the phone is
    back at guard where the player can see it (a cock-back throw's synthetic return settles about
    1.2–1.4 s after the release, just inside the cap).
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
  preloadVoice(files),                      // decode voice clips ahead of need (waves, at each dayStart)
  canPlayClips() → boolean,                 // false without WebAudio or once clips won't decode
}
```
* Every SFX and ambience bed is synthesized with WebAudio (sfx.js, loops.js), rendered once to
  AudioBuffers at startup or lazily. The audio files are the recorded voice lines and the three
  music cues below.
* `at` gives stereo pan and distance falloff relative to the camera. AI-vs-AI events play at
  volume 0.5.
* SFX names: `whoosh`, `whooshHeavy`, `clang`, `parry`, `guardBreak`, `flesh`, `fleshHeavy`,
  `wicker`, `bash`, `shieldUp`, `spearThrow`, `spearWhistle`, `spearHit`, `spearGround`,
  `volley`, `arrowThud`, `arrowShield`, `hurt`, `death`, `grunt`, `scream`, `splash`, `warcry`
  ("Alalai!"), `horn` (Persian), `drum` (Persian), `comboSting`, `heartbeat`, `step`, `uiTap`,
  `uiConfirm`.
* Bronze uses inharmonic modal partials. Flesh is a low thump plus noise.
* Music (`setMusic`) is three recorded cues: `title` (title screen and menus), `battle` (Days 1
  and 2, fights and respites) and `lastStand` (Day 3). They are epic and ancient Greek: aulos,
  kithara, frame drum and a men's chorus over an orchestral bed, in the ancient Dorian and
  Mixolydian harmoniai, quoting line 1 of the Seikilos epitaph. They are scored and rendered
  offline by `spartan/tools/music/` (its README; `cues/NOTES.md` has the sources). Persian drums
  and horns belong only to the battle ambience, with the sea, wind and the army's din.
  * Files: `audio/music/<cue>.ogg`, seamless 48 kHz stereo Ogg Opus loops of 90 s, 120 s and
    108.8 s. They are listed in `audio/musicbank.js` (GENERATED by `tools/music/render.mjs
    --bank`; don't edit), `MUSICBANK = { <cue>: { file, dur, samples, bpm, lufs } }`, frozen.
  * Decode: `fetch` of the game-root URL (as for the voice clips), then `decodeAudioData` on a
    48 kHz `OfflineAudioContext(2, 1, 48000)`, or on the game's context only if it runs at 48 kHz.
    Never on a 44.1 kHz context: that resamples the loop as a one-shot, a sample short and with a
    click at the wrap. The buffer must be 48 kHz and exactly `samples` long (a decoder that leaves
    up to 959 samples of end padding gets `loopEnd`). It loops whole on an
    `AudioBufferSourceNode` (`loop = true`) whatever the context's rate.
  * Graph: source → hand-off gain → fade gain (crossfade, cue level) → music bus → duck →
    master. `setMusic` crossfades over 2.5 s; asking for the cue already playing changes nothing,
    so a new day of battle keeps the battle cue running.
  * **Memory rule: one decoded cue at a time.** A cue decodes to samples × channels × 4 bytes
    (stereo: title 34.6 MB, battle 46.1 MB, lastStand 41.8 MB).
    * When a cue gives way, its source is swapped for a copy of its next 10 s (3.8 MB). The swap
      is sample-aligned, through a 20 ms crossfade of the same samples. Its full buffer is then
      released (source stopped, `buffer = null`, every reference dropped), and only then is the
      next cue fetched and decoded. The copy plays on under the decode and fades out under the
      new cue, or by itself before it runs out.
    * So decoded music peaks at the largest cue plus one copy: 49.9 MB.
      `tests/unit/music-cues.test.mjs` and `tests/e2e/music-cues.mjs` assert ≤ 80 MB.
    * When each cue is decoded: the title at boot, ahead of the title screen; battle when the
      battle starts (waves' `beginEntry` → `setMusic('battle')`); lastStand at the start of
      Day 3. Decoding the next cue any earlier would hold two at once (up to 88 MB).
    * In a context that isn't running (nobody hears it), a cue that gives way stops at once.
    * On the low quality setting or with `navigator.deviceMemory ≤ 3`, each cue is down-mixed to
      one channel right after decoding. That halves the steady memory (battle 23 MB); the peak is
      71 MB, for the moment of the down-mix.
  * **Fallback**: the procedural music loops of loops.js (the game's music before the
    recordings) are no longer rendered at boot; only the ambience beds are.
    * A procedural loop is rendered and played, at its own level, when its cue can't be fetched
      or decoded. It also stands in while a wanted cue is still loading after 3 s of running game
      (ticks, each counted up to 0.1 s, so a stalled main thread doesn't count). A recording that
      arrives late takes over with the usual crossfade.
    * Without `fetch` or a 48 kHz decoder, or once two voice clips have failed to decode (no Ogg
      Opus), no cue is fetched, and the title and battle loops render at boot as before. Without
      the lastStand recording, `dayStart` of Day 3 queues its loop.
  * **Levels**: each cue is trimmed from its `lufs` (all mastered to −16 LUFS) to the integrated
    loudness of the procedural loop it replaces, as the game played it, so voices and SFX keep
    their balance against the music.
    * `MUSIC_REF_LUFS`: title −16.4, battle −16.0, lastStand −20.8. They were measured by
      `tools/music` and are measured again by `tests/unit/music-cues.test.mjs`.
    * The trims are −0.4, 0 and −4.8 dB; `cueLevel(name)` gives the gain.
  * **Duck**: under speech the music bus ducks to 0.42 under a recording (`MUSIC_DUCK.file`),
    against 0.55 under a procedural loop. The cues have louder climaxes (LRA about 10 LU). At 0.42,
    Leonidas' clips stand about as far above the battle cue in the 200 Hz–3.5 kHz band as they did
    over the old loop at 0.55: speech-band SNR median 15.6 dB, 5th percentile 12.0, worst 10.9,
    against 12.8, 12.0 and 11.6.
  * `stats().cues` = `{ want, playing, source: 'file'|'synth'|null, level, loading, ready, failed,
    outgoing, mono, bytes, peakBytes, mb, decodes, decodeMs, failures, standIns, duck, duckGain,
    history }`. `bytes` is the decoded music held now (cues and copies); `history` lists the last
    40 events (load, decoded, start, tail, released, end, failed).
* audio subscribes to bus events itself, including `voice`. **No other module calls
  platform.voice.say.**
  * A line with a `clip` (the Greeks: Leonidas, Dienekes, the Trachinian, the herald, the
    lookout) plays the recorded clip through WebAudio and never calls TTS:
    * `fetch` + `decodeAudioData` of `new URL('../../' + file, import.meta.url)` (the game
      root), decoded on first need and cached (≤ 16 MB of decoded audio, so the whole bank fits;
      over that, spoken lines are dropped first, least recently used first, then preloaded ones;
      the line being said stays, and a preload marks the clips it asks for as not yet spoken).
    * Clip → gain → stereo pan → a voice bus into master, plus a send into the pass's reverb.
      The pan comes from where the speaker stands relative to the player's position and view:
      Leonidas at (LEONIDAS_X, 0) on the left (pan up to 0.35), Dienekes at (2.8, 0) slightly
      right (0.2), the Trachinian further right; the herald (z −15) and the lookout (z −9) come
      from ahead, a touch quieter (0.8) and wetter (send 0.3 vs 0.1).
    * Music and ambience duck for the clip's length.
    * One line at a time, like the TTS it replaces: a new line (clip or TTS) cuts the clip being
      said with an 80 ms fade, and a clip stops a TTS line that may still be talking.
    * A clip still loading plays when it arrives only if that is ≤ 0.3 s after the event (no more
      than waves' 0.35 s gap, so the next line never cuts its end); otherwise it is dropped. A clip
      that can't be fetched or decoded, or can't start because the audio context isn't running,
      falls back to TTS with the English `text`; after two decode failures (an engine without Ogg
      Opus) every line goes straight to TTS, and `canPlayClips()` turns false.
    * `settings.voice === false`: no sound at all (the HUD still shows the subtitles); turning
      voices off mid-line stops the clip.
    * Pause: entering `paused` stops the clip and holds it (a line still loading is held from its
      start); resuming to battle/training plays it again from 0.15 s before the cut. A dayStart (a new day or a restart), the title screen
      or game over drop it. (SFX tails simply ring out under the pause menu; TTS lines too.)
    * `stats().voice` = `{ started, tts, late, failed, speaking, held, loading, cached, mb, broken }`;
      `log` gets `{ name: 'voice', key, speaker, file, volume, pan, offset, t }` per clip.
  * Any other line (the Narrator, the Agoge, a clip that can't be had): `platform.voice.say(text)`
    with speaker-specific pitch and rate, ducking for an estimate of its length.
* `enemyWindup` with targetIsPlayer plays `grunt` at the actor (louder and lower for the
  captain).
* **Hilt grip only** (`ctx.input.grip === 'hilt'`):
  * `lockOn` with a target: `uiTap` at 0.6 for `'switch'` and `'acquire'`, at 0.8 for `'threat'`,
    always centred (`pan: 0`): in portrait a phone's speakers sit at the top and the bottom, and the
    fist covers the bottom one, so direction comes from the turn itself and the HUD arrows. Nothing
    for `'none'`, `'death'`, `'lost'` or `'clear'`.
  * A `windup` gesture with the spear in hand (camera not overridden): `uiConfirm` at 0.5 (armed).
  * No new SFX names (`SFX_NAMES` stays at 30); the hilt how-to suggests earbuds.

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
  * Up to min(3, ceil(count/100)) arrows resolve against the player (`arrowOnPlayer`): blocked
    (arrowHit 'shield') if `shieldRaise ≥ 0.4` and the player faces the source within 1.31 rad (the
    Persian front at yaw 0, or yaw π `fromBehind`; an aimed shot's archer), otherwise 8 damage each.
    Turning toward the threat is the skill. **In the hilt grip** a raised shield takes **volley**
    arrows (no shooter) from either side: the player can't turn his body to face a rear volley, and
    a 180° auto-turn out and back for every other volley would make the last stand a spin. Aimed
    archer shots still need the shield to face the archer. So volleys never turn the view, and the
    volley payload needs no `fromBehind`.
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
  * `timeToStrike = windup × (1.15 in motion mode) × (0.9 on Day 3) + hiltRearGrace`. The clip
    plays with `duration = timeToStrike / CLIPS[attackClip].strikeAt`, so onStrike lands exactly on
    time.
  * **Hilt rear grace** (`HILT_REAR_GRACE` 0.35 s): a Persian's player-targeted wind-up gets it when
    `ctx.input.grip === 'hilt'` and its bearing is beyond `LOCK.threatAngle` (1.31 rad) of
    `player.yaw` at the wind-up's start (judged once, there). The hilt player turns only through the
    lock-on, and its threat turn from straight behind into the block arc takes about 0.70 s at the
    fast rate, while the shortest motion wind-up (a Day 3 Immortal, 0.70 × 1.15 × 0.9) is 0.72 s.
    With the grace it is 1.07 s, leaving 0.37 s to raise the shield (the urgent haptic fires at the
    switch). Never for the trainer, touch, the window grip, or runs without `ctx.input.grip`
    (the headless balance runs).
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
  heal(amount), refillSpears(), dropSpears({ keepInHand }), recenter(),
  lockTarget, lockMode, lockSides, lockSpring, switchTarget(dir), setLock(actor),   // the hilt lock-on, below
}
```
player emits `swing`, `combo`, `playerHurt`, `playerDeath`, `scoreChanged`, `weaponSwitch`,
`shield` and `lockOn`. `shield {up}` fires when shieldRaise crosses 0.7 going up or 0.5 going
down. It calls `viewmodel.setWeapon / setHasSpear / setShield` every frame.

**Lock-on camera** (the hilt grip only). The hilt player can't turn his body to look, so the view
faces one foe, the **lock**. player.js owns one lock state and runs it only while
`ctx.input.grip === 'hilt'`, writing `player.yaw`; there it ignores `ctx.input.lookYaw` and zeroes
it. Touch and the window grip keep `lockMode` `'off'`, `lockTarget` null and their yaw code paths.
```js
// the player's lock API (read-only fields)
player.lockTarget        // Actor|null: the foe the view faces and the strikes aim at
player.lockMode          // 'off' (touch, window, dead) | 'target' | 'front' (easing to yaw 0) | 'hold' (a dead lock's last bearing)
player.lockSides         // { left, right }: what a swipe would pick now (HUD arrows); one object mutated in place, recomputed ≤ every 0.1 s
player.lockSpring        // the view's YawSpring (tests and the e2e read .moving)
player.switchTarget(dir) → boolean   // dir −1 left, +1 right: the switchTarget action (§6)
player.setLock(actor|null) → boolean // the Agoge and tests: force a lock (kept like a swiped one); null → 'front'
```
`js/game/lockon.js` (PURE, no three.js, nothing allocated per call; unit-tested in Node):
```js
export const LOCK = Object.freeze({
  acquireRange: 14,  switchRange: 30,  spearRange: 45,   // m: auto-acquire; a swipe with the sword; with the spear (= THROW_RANGE)
  keepRange: 22,     keepSlack: 3,     loseTime: 1.0,    // an auto-acquired lock beyond keepRange for loseTime (s) is dropped; a
                                                         //   swiped or threat lock is kept to min(spearRange, max(keepRange, d at lock + keepSlack))
  acquireCone: 1.15, distWeight: 0.12, swipeNear: 3,     // rad around the view; score per m (acquire) and rank per m beyond swipeNear (swipe)
  sideGap: 0.4,      sideMin: 0.05,                      // a swipe skips a foe within sideGap m of the current one's line; needs ≥ sideMin rad that way
  windupBonus: 0.6,  engagedBonus: 0.25,                 // rad of score: winding up at the player; engaged with him (targetIsPlayer)
  deathHold: 0.35,   recenterHold: 0.8,                  // s facing a dead lock's bearing / the front before acquiring again
  threatAngle: 1.31, threatGap: 3.0,   threatReturn: 5.0,// the threat switch (below)
  deadZone: 0.035,   settleErr: 0.004, settleVel: 0.02,  // the spring's rest hysteresis
  omega: 9, maxRate: 2.2, maxAccel: 12, fastRate: 3.0, fastAccel: 18,   // rad/s, rad/s², critically damped; fast: threat switches
  bearingTau: 0.12,                                      // s: low-pass of the target's bearing (animation sway)
});
export function isLockable(actor) → bool        // alive, team 'persian' (the Agoge's dummies and trainer too), not the helot,
                                                 //   not retreating or dead; archers are lockable but only switching reaches them
export function inActors(actor, actors) → bool  // still in ctx.actors (index scan): ai.clear() disposes actors without killing them
export function lockScore(actor, pos, viewYaw, P) → number   // |angle off the view| + distWeight·dist − the bonuses
export function acquire(actors, pos, viewYaw, P) → Actor|null
export function nextInDirection(actors, pos, fromYaw, current, dir, range, P) → Actor|null
export class YawSpring { constructor(P), reset(yaw), step(yaw, desired, dt, fast = false) → yaw, get vel(), get moving() }
```
* **Acquire** when there is no lock and no hold is running: `acquire` picks the lowest score among
  lockable non-archers within `acquireRange` and within `acquireCone` of the view, so a kill on the
  left never swings the view to a foe far on the right. None: mode `'front'`, the view eases to the
  line's front (yaw 0). Respite: no hostiles → front; the next wave's first men walk into the cone.
* **Stable lock**: while the target stays valid there is no automatic re-targeting between nearby
  foes. The view moves only for a swipe, the threat rule, or loss of the target.
* **Swipe** (`switchTarget(dir)`): `nextInDirection(actors, pos, bearingOfCurrent ?? yaw, current, dir, range)`
  with `range = spearRange` while the spear is in hand, else `switchRange`. Lockable (archers
  included), not the current one, within range, `rel = angleDiff(fromYaw, bearing)` at least
  `sideMin + atan2(sideGap, dist(current))` that way (a foe straight behind or in front of the
  current one is not "beside" him) and |rel| ≤ π; rank = |rel| + distWeight·max(0, dist − swipeNear),
  the lowest wins, ties to the nearer. No wrap. A hit locks it (`lockOn` `'switch'`); a miss emits
  `'none'` (target unchanged, the view doesn't move). Repeated swipes walk round through the rear,
  so a rear attacker can be reached without auto-turn. Examples: from the Agoge's centre dummy a
  right swipe picks the right dummy (1.6, −2.2), not the far one (1.5, −18); from an engaged foe
  ahead at 1.6 m a ±35° slot foe at 1.6 m (rank 0.61) beats a foe at 8° and 20 m (2.18).
* **Keep range**: `keepRange` for an auto-acquired lock; for a swiped, threat or `setLock` one,
  `min(spearRange, max(keepRange, distance at lock + keepSlack))`, so an archer swiped to at 30 m
  stays locked through the cock-back and the throw.
* **Loss**, checked every frame before the focus: the target dead → `'death'`, mode `'hold'`
  facing its last bearing for `deathHold`, then acquire; no longer lockable, no longer in
  `ctx.actors`, or beyond its keep range for `loseTime` → `'lost'`, front, acquire; `phase` and
  `dayStart` → `'clear'`, front (the lock clears behind the fade); player death → `'clear'`, off.
* **Recenter** (key C; the button is hidden in the hilt layout): `'clear'`, front for
  `recenterHold`, then acquire, plus `gestures.recenter()`. It never starts the 0.25 s
  `recenterLeft` ease in this grip, so the spring stays the only yaw writer.
* **Threat switch** (`settings.lockAuto`), on `enemyWindup { actor: X, targetIsPlayer: true }`: X ≠
  the lock, X lockable, |angleDiff(yaw, bearing(X))| > `threatAngle` (the shield can't cover it),
  the current lock not itself winding up at the player, ≥ `threatGap` since the last threat switch,
  and X not a foe that lost the lock to a threat within `threatReturn` (so after a switch to a rear
  attacker, the old target, now behind, can't take the lock straight back). Then it locks X
  (`'threat'`, the fast limits until the spring rests). Otherwise the wind-up still plays the
  "behind you" haptic (§11 fx) and the HUD's wind-up chevron clamps to the screen edge. The rear
  attacker's wind-up also has the hilt grace (§11 ai).
* **The camera**, each player update (pdt: frozen in hit-stop and pause, full speed under combo
  slow-mo). `desired` is the lock's low-passed bearing (to `pos`, the feet, not the animated chest;
  τ = bearingTau) in `'target'`, 0 in `'front'`, the last desired in `'hold'`. Then
  `player.yaw = spring.step(player.yaw, desired, pdt, fast)`:
  ```
  e = angleDiff(yaw, desired)
  if (!moving) { if (|e| ≤ deadZone) return yaw; moving = true }   // hysteresis: no creep or chatter
  a = clamp(ω²·e − 2ω·vel, ±accel); vel = clamp(vel + a·dt, ±rate); yaw = wrap(yaw + vel·dt)
  if (|angleDiff(yaw, desired)| < settleErr && |vel| < settleVel) { moving = false; vel = 0 }
  ```
  with `dt ≤ 0.05`. So the view never jumps (a step is ≤ rate·dt: 0.037 rad at 60 fps, 0.05 fast),
  its velocity is continuous (≤ accel·dt per frame), a transition never reverses (critically
  damped), and nothing moves for a target swaying inside ±2°. At 60 Hz a 35° switch comes within 2°
  in ≈ 0.6 s and a 90° one in ≈ 1.0 s, at rest about 0.4 s later. While `fx.cameraOverridden` (the
  spear cam) the spring is frozen (reset at the current yaw) and yaw isn't written, so the eye pose
  the spear cam eases back to holds still. Pitch is the motion `MOTION_PITCH`. The guard-break
  `yawImpulse` (0.1 rad) still applies and the spring brings the view back; `idleCamera` and the
  death camera are unchanged.
* **Aim** (§7): the focus is the lock while alive, in reach + 1.5 and within 0.70 rad of the view;
  `attackYaw = yaw + clamp(angleTo(focus), ±0.52)`. The throw targets the lock while lockable,
  listed, not `noThrowLock` and within 45 m. Blocks use the view yaw (±1.31 rad).
* **Grip changes**: when `ctx.input.grip` becomes `'hilt'` the spring resets at the current yaw (no
  jump) and the player acquires; when it leaves, the lock clears (`lockOn` `'clear'`) and the yaw
  stays. `reset()` drops the lock and resets the spring at 0.
* **Day 3 phase C**: phase changes clear the lock behind the fade; rear Immortals are reached by a
  swipe or the threat rule (with its grace); volleys never turn the view (§11 combat).

### game/waves.js
```js
createWaves(ctx) → { start(day = 1), stop(), update(gdt), get day(), get phase(), get wave(), get state() }
// state: 'intro'|'fighting'|'respite'|'dayEnd'|'over'
```
It's data-driven (§13). It emits `dayStart`, `waveStart`, `waveCleared`, `respite`, `voice`
and `gameOver`. It calls `combat.volley`, `ai.*`, `player.heal/refillSpears`,
`world.setTimeOfDay`, `audio.setMusic` and `audio.preloadVoice` (at each dayStart: the entry's
intro lines, Leonidas' battle lines, and Dienekes' too on Day 3, the first volley's exchange,
`swords` and the Kolonos line).
* **Voice queue**: `say(key)` resolves the speaker (Dienekes for Leonidas' battle lines once
  he has fallen), takes the next variant of that key the speaker has a clip for (round-robin per
  key, counters reset at start(); `voicelines.pickVoiceLine`) and queues it; `say(key, true)`
  speaks at once, cutting in (shieldsUp). A line holds the queue for `lineSeconds(line, clips)`:
  its clip's `dur` + 0.35 s, or `voiceSeconds(text)` = 0.7 + 0.062 × length without a clip; when
  `audio.canPlayClips()` is false the English is spoken instead, so it gets the longer of the two. An
  intro's timer and a phase's are long enough for their lines.

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
    (bottom-centre, ends the respite after ≥ 6 s), voice subtitles with the speaker's name
    (the Greek line in epigraphic capitals above the English; a recorded line stays up at least
    its clip's length + 0.8 s), and "SHIELDS UP!" on volley warning.
  * A letterbox during the spear cam. Low-HP vignette, hurt flash and helmet slit go through
    `viewmodel.setOverlay`.
  * An optional sensor debug meter (`gestures.debug`).
  * DOM text is updated only on change, and only transform/opacity are animated. No
    filter/backdrop-filter.
  * **Hilt grip** (`ctx.input.grip === 'hilt'`), in battle and training:
    * **Lock-on marker** `.hud-lock`, shown while not cinematic and `player.lockTarget` is set; it
      replaces `.hud-focus` there (touch and window keep the focus marker). Four bronze corner
      brackets (14 px arms, a 3 px stroke over a 5 px dark backing) around the target, centred at
      0.62 of his height; with `h` his projected head-to-feet height the box is
      `lockBoxSize(h)` = clamp(0.45·h, 40, 160) × clamp(0.62·h, 56, 220) px. It is **four
      fixed-size corner elements**, each moved by its own `translate3d`, so the box resizes without
      writing width or height and the strokes keep their 3 px; transforms are written only when the
      rounded pixel value changes. His HP bar is a fifth element **above** the box (the bottom of
      the screen is behind the fist), hidden for dummies.
    * States: `is-windup` (red #d8331f, a 0.3 s pulse) while he winds up at the player; `is-new`
      (a 150 ms scale-in from 1.25) on every `lockOn` with a new target; `is-off` when he projects
      off screen during a turn (clamped 40 px inside the edge, with an arrow toward him).
    * **Side arrows** `.hud-lock-more-l/-r` (bronze ‹ ›) while `player.lockSides.left/right`
      exists: a swipe has somewhere to go.
    * **Last-strike chip** `.hud-lock-res` under the top-left bracket: KILL / HIT / GUARDED / MISS
      for the player's last strike, from the player's `swing` and the byPlayer `hit`s that follow
      (the best wins; none within 0.35 s of game time, or the strike's own `strikeIn` + 0.1, is a
      MISS; a throw waits up to 2.5 s for its spear to land). It stays until
      `ctx.input.phoneSettled` has been true for 0.8 s of real time, so the result is still there
      when the phone comes back to guard.
    * The **closing (perfect-block) ring** is centred on the target's head (`project(actor, −0.12)`),
      about 40–50% of the height in portrait, below the banners and above the thumb.
    * **Coach toasts** (`.hud-toast.is-coach`) in a hilt battle while `hiltAgogeDone` is false, each
      once per session: "Thumb on the glass: shield" at the first wind-up at the player, "Swipe:
      change foe" at the first lock with a side to swipe to, "Cock back behind your shoulder, then
      snap forward" when the spear is first in hand.
    * **Over-speed**: any `gesture` with `fast === true` (the end-of-swing `overSpeed` notice
      included) shows "Easy: a firm flick is enough", in every hilt battle, at most once per 60 s.
  * **Portrait** (`@media (orientation: portrait)`; all tops include `var(--safe-t)` and bottoms
    `var(--safe-b)`): the pause and weapon buttons at the top corners (#controls, §6); row 1 the
    health bar (`left: safe-l + 66 px`, `min(42vw, 190px, 100vw − 254px)`) and the score with the
    chain under it (right, clear of the weapon button); row 2 (`safe-t + 48`) the spears and the
    kills with the wave label under them; toasts in a right column under row 2; the sensor meter at
    20%, left; chevrons as before (40 px edge margin); hurt arcs a centred ring of radius
    0.42·min(vw, vh); the respite row at 26%: the label left of centre and **READY** (132×48) right
    of centre, out of the thumb's arc (a shield pointer is never forwarded to it, only a pointer
    that starts on it).
    * **The portrait column** (`layoutColumn`): under the toasts, the subtitles (home 13%, max-width
      94vw, Greek 14 px over English 15 px, newest on top: column-reverse), the volley warning
      (19%, *Shields up!* 26 px), the title card (24%, max-width 92vw, title clamp(22px, 7.5vw, 34px))
      and the combo popup (28%) keep their homes, but one that shows pushes the next down (`--push`,
      a transform), none covers the respite row, and the column ends above a floor: 40% of the
      height while a foe is locked or winding up on screen (his head ring), else 72% (above the
      fist). What doesn't fit yields (fades out until there is room), first to last: the older
      subtitle row, the title card, the combo popup, the newest subtitle row. The volley warning
      never yields. (Fixed percentages overlap as soon as two of these show.)
* **Menus** (`createMenus(ctx) → { update(dt), back() → bool, show(name) }`):
  * Title: THERMOPYLAE, "480 BC", and the epigraph *"With it, or on it."* (a Spartan mother
    handing her son his shield, Plutarch, Moralia 241F). Buttons: Fight (Day 1, or continue
    from the highest day reached), The Agoge (training), Controls (Motion/Touch), Grip
    (Hilt/Window, with Motion on), Settings, How to play.
  * Settings (§9). "Quality: applies after restart" for antialias.
  * Pause: Resume / Restart / Settings / Quit.
  * Game over:
    * the day reached, kills, score and best
    * Simonides' epitaph: *"Go tell the Spartans, stranger passing by, that here, obedient to
      their laws, we lie."*, attributed to Simonides (Herodotus 7.228)
  * The first time Motion is chosen, a safety notice: *hold the phone firmly, use a wrist
    strap, clear the space around you, **never let go when throwing**, a firm flick is enough*.
    Accepting sets `safetyAccepted` (the hilt grip has its own lines, below).
  * The first click calls `audio.unlock()`. Choosing Motion calls
    `platform.motion.requestPermission()` and `isSupported()`, and falls back to touch with a
    message if motion is unavailable.
  * menus writes `thermopylae.best` on `gameOver`.
  * **The grips** (§9). Every write of `control` or `grip` (`setSetting`, the support-probe
    fallback and `enableMotion()` included) calls `ctx.applyControls()`, so the app rotates at once
    and the menus re-lay out through the CSS media queries.
    * **Grip chooser** (screen `'grip'`, `.mn-grip`): "How will you hold your sword?", two large
      cards with a diagram each, stacked in portrait and side by side in landscape. **Hilt grip**,
      marked *Recommended*: "Hold the phone upright in your fist like a sword handle, screen toward
      you. Press the glass with your thumb to raise the shield. The view turns to face your foe;
      swipe to change foe." **Window grip**: "Hold the phone sideways and look through it. Turn your
      body to look around." A choice sets `grip` and `gripChosen = true`. It is offered the first
      time Motion is chosen (`gripChosen === false` and `HILT_RELEASED`; before the release it is
      skipped and the grip stays as set), and once, with the kicker "New", on the title for a motion
      player from before the grips. Back cancels a first Motion choice (control stays touch); on the
      "New" variant Back counts as an answer (`gripChosen = true`, the window grip kept). The test
      hook `ctx.menus.forceRelease = true` offers it as if released (`hilt-shots.mjs`); grip.js's
      gate stays a const.
    * **Safety notice**, with lines per grip. It shows if `!safetyAccepted`, and for the hilt grip
      also if `!hiltSafetyAccepted`, wherever the hilt grip first becomes effective (the chooser, the
      Grip segment, a Fight or Agoge click), so no one reaches a hilt fight without having read it.
      Hilt: "Hold it firmly in your fist. A wrist strap, or a ring grip on the back of the phone, is
      strongly recommended." / "Clear the space around you, behind you too: the spear's cock-back
      reaches behind your head." / "A firm flick is enough. Swinging harder does nothing more."
      There is no strap gate. Its second button reads "Use the window grip" (switches the grip and
      carries on with that grip's own notice); the window notice keeps "Use touch instead".
    * After the notice: `enableMotion()` (permission, the support probe, the touch fallback), then
      `applyControls()`. A hilt choice rotates the app to portrait and toasts "Hilt grip on. The
      Agoge teaches it in a few minutes."; a window choice gets the usual motion toast.
    * **First hilt Fight before the hilt Agoge** (`!hiltAgogeDone`): a two-button notice, "The
      Agoge first" (3 min) / "Fight now"; "Fight now" is remembered for the session only.
    * **Fullscreen**: on a hilt Fight, Continue or Agoge click on the web (`platform.kind ===
      'web'`), `document.documentElement.requestFullscreen?.({ navigationUI: 'hide' })?.then(() =>
      ctx.applyControls(), () => {})`, then the mode starts at once (a web orientation lock needs
      fullscreen, §5.1). Android rotates natively.
    * **Rotate prompt** `#rotate` (its own layer over every screen): when `appOrientation` is
      portrait but `innerWidth > innerHeight` for more than 600 ms (Android 16 ignores orientation
      requests on large screens; a web lock needs fullscreen), in title, battle, training or paused,
      a full-screen panel with an animated phone-rotation glyph, "Hold the phone upright for the
      hilt grip" and one button, **Use the window grip** (sets `grip = 'window'`, `gripChosen`, and
      calls `applyControls()`). In battle or training it also pauses, and a fight that starts or
      resumes under it pauses too. Recognition keeps working; the prompt is only about the layout.
    * **Title** (portrait): one centred column: the logo (clamp(30px, 11vw, 52px)), 480 BC, the
      epigraph, the update banner, then full-width buttons (min(92vw, 360px)): Fight, Continue, The
      Agoge; then the Controls segment (Motion/Touch), the **Grip segment** (Hilt/Window, only with
      Motion on), and How to play | Settings side by side. In landscape the Grip segment joins the
      second row when Motion is on.
    * **Settings** (§9 rows); in portrait a full-screen panel, the tabs under the header, one field
      per row, the control under its label below 380 px wide. **Pause**: the narrow panel, centred,
      buttons full width. **Game over**: a full-width panel, stats in a 2×2 grid.
    * **How to play, Motion tab**: the current grip's cards first, with a small *Window grip* /
      *Hilt grip* switch for the other set. Hilt diagrams in `DIAGRAMS` (exported): `hiltGrip` (the
      thumb resting on the edge, the fingertips off the glass), `hiltCut`, `hiltBlock`, `hiltSwipe`,
      `hiltThrust`, `hiltThrow` (cock back behind the shoulder, then snap forward, fist closed). The
      hilt lead: "Hold the phone upright in your fist like a sword's hilt, screen toward you. The
      blade comes out of the top. Rest your thumb on the edge; press the glass to block. A firm flick
      is enough, never throw hard. Earbuds help: your fist covers the speaker."
    * **Update banner** (`#app-update`): in portrait full column width, `.upd-btns` wrapping under
      the text with `flex: 1` buttons of ≥ 44 px. The boot screen and the engine-fail panel read in
      portrait too. Every screen pads `var(--safe-*)`; in portrait the cutout is at the top and the
      gesture area at the bottom.
* **Input** (`createInput(ctx) → { update(dt), reset() }`): §6. It handles `pause` by calling
  `ctx.setMode('paused')`.
* **Training, the Agoge** (`createTraining(ctx) → { start(), stop(), update(dt), demo(stepId), get active(), get stepIndex() }`):
  * The place is a practice yard: the same pass, in the morning, with no army.
  * start() spawns a dummy at (0, −2.6) and a far target dummy at (1.5, −18).
  * Steps complete on events, each after N repetitions, with a Skip button:
    0. Grip and safety (confirm).
    1. Calibrate: "Slash 5 times, the way you will in battle. Gentle is fine." Touch mode skips
       this step.
       * It counts `swingCandidate`s, slash or not, so a gentle swinger below the thresholds
         still calibrates.
       * `pickStrokes(cands)`: a stroke is a candidate at least 0.8× as fast as every candidate
         touching it in time (within `STROKE_GAP` = 0.45 s before its start or after its end).
         The cock-back before a stroke and the return after it are slower, so they drop out.
       * A candidate counts once its neighbours are known: a later one started more than
         STROKE_GAP after it ended, or STROKE_GAP + 0.05 s (real time) have passed since the
         last one.
       * After 5 strokes: `sensitivity = suggestSensitivity(peaks, CALIBRATION)`, which is
         clamp(target/median(peak), min, max) rounded to 0.05.
         * `CALIBRATION = { target: swingPeak/0.6 (≈ 4.67 rad/s), min: 0.6, max: 2.2 }`: the slash
           threshold swingPeak/s lands at 60% of the median swing (but never below lookMax, §5.3).
         * suggestSensitivity's defaults stay `{ target: 9, min: 0.6, max: 1.8 }`.
       * It shows the median peak and the suggestion, with Apply/Keep buttons.
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
  * **Mirrored setup** (motion slashes only, steps 2–4): each slash is compared with what the
    instruction asks at that moment. Step 2 asks right until two rights have counted, then left;
    step 3 asks D; step 4 asks U. Only R/L-ish slashes (step 2) or U/D-ish ones (steps 3–4) tell.
    * If a step's first three telling slashes all went the opposite way, motionFlipX (step 2) or
      motionFlipY (steps 3–4) is toggled, saved and applied (`gestures.setFlip`), with the hint
      "Swing direction corrected".
    * The slashes counted so far are recounted the right way round: step 2 swaps its right and
      left counts, and steps 3–4 restart their count. The check then starts over.
    * A telling slash that went the asked way ends the check for the step, so a player who only
      took the sides in another order is not flipped.
  * Each step shows the last detected gesture, an arrow (from `gesture` in motion or `swing` in
    touch), live sensor meters (marks at `slashThreshold(s)` from §5.3 and `thrustAcc/s`) and a
    sensitivity slider (SENSITIVITY_MIN–MAX).
  * **Demo** button: `ctx.motionDemo = true`, then `platform.motion.inject(synthesize(script, { rot: current rotation, hand }))`,
    then clear the flag after `scriptDuration + 0.3 s`. This works in touch mode too. Demos are
    never flipped by motionFlipX/Y (§10).
  * Training emits `voice` (Narrator) for instructions.
  * **The hilt Agoge** (`effectiveGrip(settings) === 'hilt'`):
    * **Steps.** The exported `STEPS` keeps its 12 ids, order and `n` (`ui-helpers.test.mjs` pins
      them, and two e2e scripts address the spear step as index 10). Its entries gain a `hilt`
      instruction and a `demoHilt()` script. The new step is a separate export, `HILT_LOCK_STEP`
      (`id: 'lock'`, `n: 2`, `hiltOnly: true`, frozen), and `agogeSteps(grip)` returns the runtime
      list: `[...STEPS.slice(0, 6), HILT_LOCK_STEP, ...STEPS.slice(6)]` (13 steps, frozen) for the
      hilt grip, else `STEPS` itself. `start()` builds it from `effectiveGrip(settings)`, and
      `stepIndex`, `goTo`, the step label and `demo(i)` index it, so touch and the window grip keep
      indices 0–11 and their demo seeds (`seed: 7 + index`). `ROMAN` gains XIII; the label is
      `${ROMAN[index]} / ${ROMAN[steps.length − 1]}`. A grip change mid-Agoge (pause → Settings, or
      the rotate prompt's "Use the window grip") is followed on the next update: the same step
      carries on in the new list, and from the lock step it goes on to Block.

      | step | hilt instruction | hilt demo (`hold: 'hilt'`) |
      |---|---|---|
      | grip | Hold the phone upright in your sword hand like the hilt of a xiphos, screen toward you. Rest your thumb on the edge; your fingers stay on the back, off the glass. Grip firmly; a wrist strap is strongly recommended. Clear the space around you, behind you too. Then hold your guard still. | (the grip check) |
      | calibrate | Cut 5 times the way you will in battle: let the top of the phone lead, like a blade. A firm flick is enough. | swing R ret 0, swing L ret 0, swing R |
      | slashLR | Cut right twice, then left twice: sweep the top of the phone across, like a windscreen wiper. | R, R, L, L |
      | overhead | Chop straight down from your guard: tip the top of the phone forward and down. Twice. It goes over a wicker shield. | swing D, idle 0.5, swing D |
      | uppercut | Lower the blade slowly toward the ground ahead, then swing it up. | lower β 0.1, idle 0.3, swing U |
      | thrust | Punch the phone straight forward, then draw it back. Three times. | thrust ×3 |
      | **lock** | Swipe your thumb right to turn to the next foe. Then swipe left. | (an animated thumb glyph) |
      | block | Press the glass with your thumb to raise the shield before each blow lands. Lift it to lower the shield. | |
      | perfect | Press at the last moment, as the red ring around his head closes. Twice. | |
      | bash | Thumb on the glass, then punch forward. Twice. | thrust ×2 (demoShield) |
      | volley | When the archers loose, keep your thumb on the glass until the arrows have fallen. | |
      | spear | Tap the weapon button at the top to take the spear. Thrust twice. Then cock the phone back behind your shoulder like a javelin, and snap it forward, fist closed. | thrust ×2 at the near dummy, then (timed to the throw segment) the near dummy steps aside and `setLock(farDummy)`, then the cock-back throw |
      | twin | Cut right, then left, fast, into the dummy. | R, L (arc 1.0, speed 9) |

      (The weapon swap is the top button only, §6.)
    * **Grip step: guard and grip check**. "I'm ready" starts a 3 s check, "Hold your guard still,
      thumb on the edge." (the button hides). It passes once `gestures.guardLearned` is true and no
      shield pointer has started during it; without a learned guard, a hint to hold the phone
      upright in front of the chest comes every 6 s. Any shield pointer restarts the check; one held
      ≥ 0.5 s is a resting finger: a dot at `inputCtl.lastShieldDown` with "Something touches the
      glass here. Keep your fingers on the back of the phone." If the dot is on the finger side
      (left, or right with `leftHanded`) within 160 px of the edge, `hiltFingerStrip` grows to
      `min(120, distance from the edge + 16)` if that is larger, and is saved (input.js reads it on
      every touch-down).
    * **lock step**: `enterStep` spawns two extra dummies at (−1.6, −2.2) and (1.6, −2.2) with
      `ai.spawnDummy` and calls `player.setLock(nearDummy)`. Progress: one `lockOn` `'switch'` to the
      right and one to the left, in either order ("Now left." / "Now right."); a `'none'` hints
      "Nobody stands that way. Swipe the other way." The big slot shows an animated (SMIL) thumb
      glyph labelled "Swipe". `leaveStep` removes the extra dummies (ai.clear, then
      `spawnDummies()`); the old lock drops in the next frame (`inActors`).
    * **Overhead** (hilt): a `windup` before the chop doesn't count against the player.
      **Uppercut**: a `swingCandidate` with `heldUp: true` and no slash hints "Lower the blade first,
      then swing up."
    * **Spear step**: after the second thrust the near dummy steps aside (as before) and the Agoge
      calls `player.setLock(farDummy)` with "The far target is locked. Now throw." (3 s), so the
      view turns to it at once. A throw with the lock elsewhere hints "Throw at the far target.", a
      wide spear re-locks the far target, and a spear `windup` shows "Armed". Leaving the step
      re-locks the near dummy. After a hilt throw nothing fires until the phone is back at guard
      (`throwSettle`).
    * **Over-speed**: any gesture with `fast: true` (the `overSpeed` notice included, never during
      a demo) hints "Easy: a firm flick is enough.", set after the frame's events so a step hint
      doesn't hide it; `overSpeed` events show no glyph.
    * **Calibration**: the same `pickStrokes` / `suggestSensitivity(peaks, CALIBRATION)`, applied to
      `settings[sensitivityKey(settings)]`; the panel slider, the calibration text and the meter
      marks read and write the same key. In calibrate and slashLR, a candidate below
      `slashThreshold(s)` with no slash within 1 s hints "Sweep the top across, like a windscreen
      wiper." and shows the `DIAGRAMS.hiltCut` card for 3 s (at most once per 4 s). The
      mirrored-setup check toggles `settings[flipKeys(settings).x/.y]`.
    * **Cards**: the grip diagram (`DIAGRAMS.hiltGrip`, step 0) and the hiltCut card hang in a
      `.tr-fig` element under the panel on the finger side (above it if the panel sits low),
      re-placed by a ResizeObserver on the panel (at 360×800 the grip step's panel is ~355 px tall
      and would cover a fixed slot). `.tr-fig` and the resting-finger mark `.tr-dot` are styled
      inline in training.js (`CARD_STYLE`, `DOT_STYLE`); the JS toggles `display` and writes
      `top/left/right` (card) and `transform` (dot).
    * **Demos**: `synthesize(script, { hold: grip === 'hilt' ? 'hilt' : 'window', rot: screenRotation(), hand, gyroRange, seed })`;
      the `ctx.motionDemo` flag, `inject` and the timing are unchanged. Every hilt demo gives its
      taught gestures through the hilt pipeline for both hands (`ui-agoge-demos-hilt.test.mjs`:
      calibrate R,L,R; slashLR R,R,L,L; overhead D,D, no windup; uppercut U; thrust ×3; bash ×2;
      spear thrust, thrust, windup, throw; twin R,L).
    * **Panel** (portrait, style.css): at the top (`top: safe-t + 76px`, under the HUD's row 2;
      left/right 10 px), the head row (number, title, pips, check; tighter letter-spacing in
      portrait, so the longest titles fit beside their pips at 360 px), then hint and text, then a
      wrapping tools row. `#training.is-hilt` marks the hilt Agoge; `#training.is-compact` (set in
      any grip during the block, perfect, bash and volley steps; the stylesheet applies it in
      portrait only) collapses it to the head row and one line (about 56 px; 57 at 412×915), so the
      target's head ring is never under it; a tap on the head row (hit-testable only while compact)
      removes it until the next step. The big gesture glyph sits on the finger side (x 20%
      for a right hand, 80% for a left), at 42% height, 96 px.
    * Finishing the Agoge in the hilt grip sets `settings.hiltAgogeDone = true`.

## 12. Events (EventBus)

| event | emitter | payload |
|---|---|---|
| `gesture` | main | the GestureEvent (§5.3; in the hilt grip a slash or throw may carry `fast: true`, and `{ type:'overSpeed', fast: true, peak, t }` may follow a fast swing) |
| `swingCandidate` | main | `{ type:'swingCandidate', peak, sweep, dir, t0, t, heldUp? }`: any swing-like excursion, slash or not (§5.3), for the Agoge's calibration; `heldUp: true` (hilt) for an up swing from the upright guard |
| `swing` | player | `{ kind:'slash'|'thrust'|'spearThrust'|'bash'|'throw', dir, power, weapon, src, strikeIn }` (attack start). `strikeIn` is the seconds until the strike resolves (0.05 for a motion slash), or for a throw until the spear leaves the hand; the viewmodel lands its contact pose on it. |
| `combo` | player | combo row + `{ bonus }` (only when the completing attack hit) |
| `hit` | combat | `{ target: Actor, attacker: Actor|'player', damage, kind, dir:{x,z}, point, guarded, critical, killed, byPlayer, combo: id|null }`. Every damage to an Actor; never for the player. |
| `kill` | combat | `{ target, attacker, byPlayer, kind, spear: bool, cause: 'weapon'|'sea'|'bleed' }` |
| `block` | combat | `{ by: 'player'|Actor, attacker, point, perfect, kind: 'melee'|'axe'|'guardBreak' }`. A fully stopped attack only; a guarded actor hit is just `hit{guarded:true}`. |
| `playerHurt` | player | `{ damage, hp, from: Actor|null, kind:'melee'|'axe'|'arrow', relYaw }` (relYaw > 0 = source on the player's left) |
| `playerDeath` | player | `{}` |
| `scoreChanged` | player | `{ score, chain, kills }` |
| `weaponSwitch` | player | `{ weapon }` |
| `shield` | player | `{ up }` |
| `lockOn` | player | `{ target: Actor|null, prev: Actor|null, reason: 'acquire'|'switch'|'threat'|'death'|'lost'|'clear'|'none' }` (the hilt grip, §11 player). Fires on every change of `lockTarget`, and with reason `'none'` (target unchanged) when a swipe finds nobody that way. |
| `enemyWindup` | ai | `{ actor, timeToStrike, targetIsPlayer }` |
| `spearThrown` | combat | `{ projectile }` |
| `spearImpact` | combat | `{ projectile, target: Actor|null, point, normal, water: bool }` |
| `spearCam` | fx | `{ phase: 'start'|'impact'|'end' }` |
| `arrowHit` | combat | `{ target: 'player'|'shield'|Actor|null, point }` |
| `volley` | combat | `{ phase: 'warning'|'loosed'|'impact'|'end', duration }` |
| `dayStart` / `waveStart` / `waveCleared` / `respite` / `phase` | waves | `{ day, phase, wave, title, subtitle, seconds }` |
| `voice` | waves, training | `{ key?, text, speaker: 'Leonidas'|'Dienekes'|'Trachinian'|'Herald'|'Lookout'|'Narrator', greek?, clip?: { file, dur } }`. `key` is the §13 line (waves only); `text` is the English (subtitle, and TTS when there is no clip); `greek` the Greek subtitle; `clip` the recorded line (`file` relative to the game root, `dur` in s). |
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
* Spawning: one enemy every 1.2–2.0 s until `alive` is reached, at z = −26…−20 (archers at
  −45…−35) and x ∈ [−6, 7]. A wave clears when all its melee enemies are dead. Archers then
  retreat. The next wave's first men set out 6 s before a respite ends. Day 1 opens with four
  levies standing in a rank at z ≈ −16 during the herald's lines.
* Engage delay by day (`DIRECTOR[d].engageDelay`): Days 1–2 0.3–0.7 s, Day 3 0.8–1.4 s. Rates:
  Day 1 0.30, Day 2 0.40.
* The first volley comes ≥ 12 s after waveStart. After that, one comes every `volleyEvery`
  seconds, but only while the wave's archers live. Its size scales with the surviving archers
  (phase C keeps its own clock).
* A volley is blocked only if the player faces where it comes from (within 1.31 rad) with the
  shield raised ≥ 0.4. In the hilt grip a raised shield takes volleys from either side (§11
  combat).
* **Respite**:
  * 18 s in motion mode, 10 s otherwise. READY ends it after 3 s.
  * Heals 35%, tops spears up to 2, and sends the helot. dayStart refills fully.
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
    * Volleys come every 10 s, shrinking by 0.5 s down to 7 s. Rear volleys (100 arrows) are
      blocked only by turning to face them (in the hilt grip, by the raised shield alone). "Shields
      up!" plays at most every 12 s.
    * Sword only (`swordOnly`, spears dropped), no heal. It ends at death.
    * The card text: "They defended themselves with swords, those who still had them, and with
      hands and teeth." (7.225)
* **Voice lines** (key, speaker: text, trigger). `campaign.VOICE` holds each key's speaker and
  English line; the Greeks speak period Greek from recorded clips:
  * `audio/voicebank.js` (GENERATED by `spartan/tools/voice/render.py` from
    `spartan/tools/voice/lines.json`; don't edit by hand) is
    `VOICEBANK = { <key>: [ { greek, en, clips: { <Speaker>: { file, dur } } }, … ] }`, deeply
    frozen. `file` is relative to the game root (`audio/voice/<key>-<n>-<speaker>.ogg`, mono
    Ogg Opus); `dur` is its length in s (within 0.1 s). `greek` is epigraphic capitals with no
    diacritics (like ΜΟΛΩΝ ΛΑΒΕ); `en` is the English subtitle (the VOICE `text` is only the
    fallback for a key or speaker without a clip). The voices are rendered offline (Kokoro-82M).
  * Variants say the same thing in other words so frequent barks don't repeat: holdTheLine 3,
    shieldsUp 3, warcry 2, respite 2, the rest 1. Every speaker who can say a key has a clip
    of each of its variants: holdTheLine, shieldsUp, warcry and respite (`LEONIDAS_BATTLE_LINES`)
    by Leonidas and Dienekes.
  * The Narrator (tegeans, and the Agoge) stays English text-to-speech, with no clip.
  * Day 1 intro:
    * `herald` Herald: "Thus says King Xerxes to Leonidas: hand over your arms." (ΩΔΕ ΛΕΓΕΙ…; cf. Plutarch, Moralia 225C: πέμψον τὰ ὅπλα)
    * `molonLabe` Leonidas: "Come and take them." (ΜΟΛΩΝ ΛΑΒΕ; Plutarch, Moralia 225C–D)
  * First volley warning:
    * `arrowsHideSun` Trachinian: "Their arrows are so many they hide the sun!" (Herodotus 7.226)
    * `fightInShade` Dienekes: "Good news. Then we shall fight in the shade." (Herodotus 7.226)
  * In battle (Leonidas; Dienekes once Leonidas has fallen): `holdTheLine` "Hold the line!",
    `shieldsUp` "Shields up!" (every volley warning, at most every 12 s), `warcry` "Alalai!" (at
    waveStart), and `respite` "You proved yourselves brave men." / "Many men, but few warriors."
    (Herodotus 7.210) 1.0 s into every other respite or lull between
    surges, counted from start() (the 1st, 3rd, 5th…: so Dienekes has it over Leonidas' body).
  * Day 1 dusk: `immortals` Lookout: "The Immortals! Hydarnes leads them!"
  * Day 2, first respite: `tegeans` Narrator: "The Tegeans take the line." (Herodotus 7.212)
  * Day 3 dawn:
    * `anopaia` Lookout: "The Phocians have abandoned the Anopaia path! Ephialtes is leading the
      Immortals over the mountain!"
    * `breakfast` Leonidas: "Eat your breakfast as men who will dine in Hades." (Plutarch;
      Diodorus 11.9.4)
  * Day 3 phase B, 2.4 s in: `swords` Dienekes: "Our spears are broken. Swords!" (7.224)
  * Day 3 phase C (`intro: ['kolonos']`), 0.6 s after the fade: `kolonos` Dienekes: "Fall back
    to the hillock! Close ranks, all of you!" (7.225: back behind the wall, onto the hillock)

## 14. Test hooks (main.js)

URL parameters:
* `manual=1`: no RAF loop; tests call `__game.step()`. preserveDrawingBuffer is on for screenshots.
* `seed=N`
* `quality=low|medium|high`
* `mode=battle|training`
* `day=N`
* `control=touch|motion`
* `grip=hilt|window`: overrides the stored grip and sets `gripChosen = true` (no chooser)
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
* **Field of view** (`js/gfx/framing.js`, PURE; main.js `applySize` sets `camera.fov =
  cameraFov(w / h)`): a fixed horizontal FOV, so the framing doesn't depend on the phone's shape.
  ```js
  export const FOV = { landscapeH: 100, landscapeV: [55, 70], portraitH: 46, portraitV: [72, 84] };   // degrees, frozen
  export function verticalFov(hDeg, aspect), horizontalFov(vDeg, aspect)
  export function cameraFov(aspect) → vertical degrees
  //   aspect ≥ 1: clamp(verticalFov(100), 55, 70)   (a fixed 70° vertical would be ~114° across a wide phone)
  //   aspect < 1: clamp(verticalFov(46), 72, 84)    (portrait, the hilt grip); 0 or NaN → 70
  ```
  A 412×915 or 360×800 phone in portrait gets 84° × 44°, 9:18 gets 80.7° × 46°, a portrait tablet
  (0.75) 72° × 57°. A foe at 1.8 m then fills about 55% of the height and 45% of the width, so the
  lock-on keeps him centred and the chevrons and hurt arcs carry the flanks. The vm camera's fov is
  the viewmodel's (§11).
* **Frame pacing**: the RAF callback returns without stepping when `now − lastRendered < 9.5 ms`,
  so 120 Hz screens run at 60.
* **Dynamic resolution**:
  * Track the mean interval M over each 1 s window.
  * M > 20 ms: scale −0.1 (min 0.6).
  * M < 17.5 ms for 5 consecutive windows, with the last decrease > 10 s ago: scale +0.05.
  * At most one change per second. Ignore the 2 s after a mode change, time-of-day change,
    resume, pre-warm or `resize` (a rotation re-lays out the page over a few slow frames).
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
  `com.thermopylae` and no dependencies, plus the fixed signing key, version and update URL
  of §2.2.
* `spartan/package.json` is `{ "private": true, "type": "module" }`.
* `.github/workflows/android.yml`:
  * After setup-gradle, `actions/setup-node@v4` (node 22) and
    `node --test spartan/tests/unit/*.test.mjs`.
  * Build both APKs: `./gradlew :app:assembleDebug :spartan:assembleDebug`.
  * Copy `spartan/build/outputs/apk/debug/spartan-debug.apk` → `Thermopylae.apk`.
  * Upload it as an artifact and add it to the release `files:` next to RoccosQuest.apk, with
    a line in the release body.
  * Write `thermopylae-update.json` (§2.2) with `jq`, and publish it to the same release in a
    second step, after the APKs.
* `tests/unit/modulegraph.test.mjs` registers a resolve hook (`node:module` `register`) that
  maps `three` and `three/addons/*` to the vendored files. It then imports every `.js` under
  `assets/game/js` except main.js, and fails on any error.
* CI runs the unit tests before it builds the APK, so a red unit test means no APK and no in-app
  update. `tests/unit/ui-helpers.test.mjs` pins the Settings FIELDS (the sorted keys, 18 with
  `grip` and `lockAuto`; the `motionOnly` keys `grip, sensitivity, gyroLook, motionFlipX,
  motionFlipY`; `lockAuto` the only `hiltOnly` key) and the Agoge's `STEPS` (ids, `n`, the `touch`
  text of every non-motion-only step): a change to either updates that test in the same commit.
* **The hilt grip's tests.** Unit (Node): `hilt-grip` (the grip helpers, `DEFAULT_GRIP` following
  `HILT_RELEASED`, the migration), `hilt-framing` (`cameraFov`), `hilt-platform` (the web and
  Android orientation requests, `HostLogic`'s branch order read from the Kotlin source),
  `motion-hilt`, `motion-hilt-feel`, `motion-frames-hilt`, `motion-synth-hilt` (§5.3),
  `hilt-lockon` (lockon.js, PURE), `hilt-player` (the gameplay harness with `ctx.input.grip = 'hilt'`:
  locks, switches, loss, the volley rule, the threat rule, recenter, the spear cam, aim and throw),
  `hilt-ai` (the rear grace), `viewmodel-hilt` (`slashHalf`, the ellipse path), `hilt-feedback`
  (`HAPTICS_HILT`, coalescing, `HILT_SLOWMO`), `ui-agoge-demos-hilt` (every hilt demo through the
  hilt pipeline, the runtime step list). `modulegraph.test.mjs` imports the new PURE modules in Node.
* **End-to-end** (Playwright, headless Chromium with SwiftShader, local; run them one at a time,
  from a working directory outside the repo, since some write screenshot folders into it):
  `smoke`, `ui-flows`, `ui-input`, `ui-real`, `ui-shots`, `voice-clips`, `music-cues`,
  `audio-check`, `viewmodel-checks`, `fx-e2e`, `presentation-checks`, `motion-platform`,
  `combat-agoge-dory`, `review-motion-*`, `review-device-*`, `review-integ-*`, and for the hilt
  grip `hilt-orientation --strict` (the bridge's orientation requests through a strict fake),
  `hilt-thumb` (strips, shield, strokes and their commit, via CDP touches), `hilt-vm` (the portrait
  viewmodel's coverage criteria), `hilt-shots` (the portrait UI at 412×915 and 360×800 with layout
  assertions), `hilt-agoge [outDir] [WxH]` (the hilt Agoge step by step; run at 412x915 and
  360x800), `hilt-lockon` (§11 player, including a 60 s Day 3 phase C acceptance
  run: at most 4 turns of more than 90° per minute, the camera moving < 15% of the time, no threat
  switch straight back within 5 s) and `hilt-throw` (the cock-back, the armed haptic, the release at
  the lock, the spear cam's hold, no false throw from a lift and chop, the Agoge's far target).
  Every way the scripts build a page keeps the window grip whatever `DEFAULT_GRIP` is:
  `lib.mjs` `newPage({ grip = 'window' })` seeds `{ grip, gripChosen: true }` into the stored
  settings unless they hold a grip (`grip: null` skips it); `review-device-native.mjs`'s
  `phonePage({ grip })` does the same; the UI sandbox (`sandbox-ctx.js`, `ui-sandbox.html`) has
  `grip: 'window', gripChosen: true`, a `?grip=` parameter and a `ctx.applyControls()` stub; and an
  unknown grip reads as the window grip. `review-motion-lib.mjs` takes `openGame({ grip })` and
  `R.arena({ grip })` (`settings.grip` + `ctx.applyControls()`, rotation 0 for the hilt), and its
  fake bridge records haptics in `window.__haptics`. Hilt scripts pass `grip: 'hilt'` or `?grip=hilt`.
