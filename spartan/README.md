# Thermopylae

A first-person 3D Spartan melee game for Android, set at the Battle of Thermopylae in 480 BC.

You stand in Leonidas' line at the Hot Gates. The cliffs of Mount Kallidromos are on your
left, the Malian Gulf is on your right, and the Phocian Wall is behind you. Xerxes' army comes
up the pass wave after wave: Medes and Kissians, sparabara shield walls, archers whose arrows
hide the sun, the Immortals under Hydarnes, and Saka axemen. You fight with a xiphos (short
sword) and a dory (spear), behind a bronze-faced aspis. The campaign follows Herodotus over
three days, and ends with the last stand on the Kolonos.

You can swing the phone itself like a sword: held upright in your fist like the hilt of a sword,
or sideways, looking through it like a window.

![Title screen](../docs/thermopylae-title.jpg)

## Installing on your phone

Every push builds the APK with GitHub Actions, next to Rocco's Quest:

1. On your phone, open
   **https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/latest-ccr-9def4096-ln0kio/Thermopylae.apk**
   in Chrome.
2. Open the downloaded `Thermopylae.apk`. If Android asks, allow your browser to install unknown apps.

### Updates

You only install it this way once. When a newer build is out, the title screen shows **Update
available**: tap **Update**, then **Install** in Android's installer. The new version installs
over the old one and keeps your progress and settings. The first time, Android asks you to allow
Thermopylae to install apps ("Allow from this source"); turn it on and go back.

Builds made before the updater came in were each signed with a different key, so if you have one
of those, uninstall it once and install the new APK from the link above. After that, updates are
one tap.

The game needs Android 7 or newer with an up-to-date Android System WebView, and a gyroscope for
the motion controls. Touch controls work on any phone.

## Controls

Pick **Motion**, **Touch** or **Buttons** under *Controls* on the title screen. Motion has two ways to hold the
phone, the **hilt grip** (upright) and the **window grip** (sideways): pick one under *Grip* on the
title screen or in Settings → Controls. The app turns to portrait for the hilt grip and stays in
landscape for the window grip, touch and buttons. **The Agoge** (training) walks you through every move
with a practice dummy, and calibrates the motion controls to how hard you swing.

### Motion, hilt grip: the phone is the hilt of your sword

Hold the phone **upright in your fist**, like the handle of a sword, screen toward you. The blade
comes out of the top of the phone. Rest your thumb on the edge of the phone and keep your fingers on
the back, off the glass. Your guard: the phone upright in front of your chest, the top leaning a
little forward, so you can see the screen.

| Move | What you do |
|---|---|
| **Slash** | Swing the fist. The tip of the blade cuts the way it moves: sweep the top of the phone right or left (like a windscreen wiper), chop it forward and down for an overhead, or the diagonals. |
| **Uppercut** | Lower the blade toward the ground ahead first, then swing it up. (From the upright guard, tipping the top back is the spear's cock-back, never a cut.) |
| **Stab** | Punch the phone straight forward, then draw it back. |
| **Shield** | Press the glass with your thumb to raise the aspis; lift it to lower it. |
| **Shield bash** | Thumb on the glass, then punch forward. |
| **Change foe** | The view locks onto a foe and turns to face him by itself. Swipe your thumb across the glass, left or right, to turn to the next foe that way. Swipes made while the phone is still swinging don't count. |
| **Throw the spear** | Tap the weapon button (top right) to take the spear. Cock the phone back behind your shoulder like a javelin (a buzz tells you it's armed), then snap it forward, fist closed. The spear flies at the foe you're locked onto, and the camera follows it in slow motion until the phone is back at your guard. |
| **Combos** | As in the window grip: right-left (Twin Fangs), right-left-right, up then down (Leonidas' Cleave), bash then stab, and more. |

You can't see the screen while you swing, so the phone tells you with vibrations: one knock for a
hit, a rattle when his shield took it, a knock and a long buzz for a kill, one long buzz when you're
hurt, and two long buzzes when someone attacks you from behind (with **Auto-turn** on, in Settings
→ Controls, the view also turns to him). Back at guard, a tag on the locked foe shows how your last
strike went. Earbuds help: your fist covers the speaker.

The corner buttons, for your other hand, are **pause** (top left) and **sword/spear** with your
spear count (top right; it switches when you lift your finger). With *Left-handed* on in Settings,
everything is mirrored.

**Safety:** the phone is only held by your grip. **A wrist strap, or a ring grip on the back of the
phone, is strongly recommended.** Hold it firmly in your fist, and clear the space around you,
behind you too: the spear's cock-back reaches behind your head. **A firm flick is enough;**
swinging harder does nothing more, and the game tells you to ease off if you swing too hard.

### Motion, window grip: the phone is your sword

Hold the phone in landscape with the screen toward you, as if you were looking through it. The
blade points where you look.

| Move | What you do |
|---|---|
| **Slash** | Swing the phone. The blade cuts the way you swing: right, left, overhead, uppercut or the diagonals. |
| **Stab** | Push the phone straight forward, then draw it back. |
| **Shield** | Press your thumb on the glass to raise the aspis. Rest your thumb on the frame to lower it. |
| **Shield bash** | With the shield up, push the phone forward. |
| **Throw the spear** | With the spear in hand, raise the phone beside your head, then throw overhand. The camera follows the spear in slow motion: look back at the phone to watch it fly. |
| **Look around** | Turn your body. Swings don't move the view. |
| **Combos** | Chain strikes without returning to guard: right-left (Twin Fangs), right-left-right (Fury of Lakedaimon), up then down (Leonidas' Cleave), bash then stab (the Hoplite's Answer), and more. |

The corner buttons are **pause** (top left), **sword/spear** with your spear count (top right)
and **recenter** (top middle).

**Safety:** hold the phone firmly and use a wrist strap if you have one. Clear the space around
you. **Never let go when you throw:** a firm flick is enough.

### Both grips

Gentle swings count too: the Agoge measures how hard you swing and suggests a sensitivity (each
grip keeps its own). If your slashes come out mirrored, turn on **Swap swing left/right** (or
**up/down**) under Settings → Controls; the Agoge also notices and fixes a mirrored phone by itself.

### Touch

* **Left thumb:** the stick moves you. **SHIELD** (hold) blocks. Swipe up from it, or tap
  **BASH**, to bash.
* **Right half of the screen:**
  * A fast swipe slashes in that direction.
  * A tap stabs.
  * A slow drag looks around.
* With a spear in hand, hold **THROW** to wind up and release it to throw.

### Buttons

Every move is a button: nothing to swipe, drag or swing. As in the hilt grip, the view locks onto
a foe and turns with him, and your strikes and spears go at him.

* **Left thumb:** the arrows step you forward, back and to the sides. **SHIELD** (hold) blocks, and
  **BASH** bashes.
* **Right thumb:** the sword pad. Each arrow cuts the way it points: ← and → slash, ↓ chops
  overhead (over a wicker shield), ↑ cuts upward, and the corners cut diagonally. **STAB** is in
  the middle.
* **Change foe:** tap a foe on screen to face him, swipe sideways on the open ground, or tap
  **‹ FOE** / **FOE ›** above the sword pad.
* With a spear in hand, hold **THROW** to wind up and release it: the spear flies at the foe you face.
* Combos: → then ← (Twin Fangs), ↑ then ↓ (Leonidas' Cleave), the two lower corners (the Cross of
  Ares), STAB three times (Phalanx Drive), BASH then STAB (the Hoplite's Answer).

### Keyboard and mouse (in a desktop browser)

* WASD moves, and the mouse looks.
* Left-drag slashes, and a left click stabs.
* Right mouse button, Shift or K holds the shield. F bashes.
* Q swaps weapon, and R or the middle button throws.
* Arrow keys slash, and Space stabs.
* C recenters. Esc or P pauses.
* Z and X turn the lock to the next foe on the left or right (the hilt grip's thumb swipe).

## Fighting

* **Spartan rage.** Fighting fills the red ring around the **RAGE** button: hits, kills, blocks
  (a perfect block most), combos, and a little from the blows you take. When it glows, tap it (V
  on a keyboard) and Leonidas raises the war cry. For 8 seconds you strike twice as hard, every
  cut catches each foe in front of you and staggers him, blows hurt you half as much, and every
  kill heals you. The button is at the bottom right in Buttons and Touch, and at the top in Motion.
  The rage grows as the end nears:

  | When | Rage | What it adds |
  |---|---|---|
  | Day 1 | Spartan rage | 8 s, double damage, half the harm, +6 health a kill |
  | Day 2 | Fury of Lakedaimon | 10 s, and every kill adds a second |
  | Day 3 | Wrath of the Three Hundred | fills 1.5× faster, +10 health a kill |
  | The Kolonos | The last stand | 12 s, 2.5× damage, a third of the harm, +12 a kill, arrows cannot touch you, and the war cry hurls back every foe within 4 m |
* **Spears.** You start each day with seven (one in hand) and every respite tops you up to five.
  On the third day the spears break, as Herodotus tells: the fight over Leonidas' body and the last
  stand are fought with the sword.

* **Watch for the wind-up.** Every enemy telegraphs his blow, with a red chevron when it's
  aimed at you. Raise the shield in time to block.
* **Perfect block.** Raise the shield just before the blow lands. The attacker staggers, and
  your next hit is a critical.
* **Wicker shields** stop most blows. Get around them with an overhead chop, a hard stab or a
  bash.
* **The Gulf.** Bash an enemy over the edge into the sea; Herodotus says many Persians drowned
  there.
* **Arrows.** When an archer volley comes ("Shields up!"), hold the shield up and face the
  arrows. In the hilt grip, just keep your thumb on the glass: the raised shield takes a volley
  from either side. Killing the archers with thrown spears ends the volleys.
* **The Saka captain's axe** breaks a normal block. Only a perfect block stops it cleanly.

## The campaign

| Day | What happens |
|---|---|
| The First Day | The Medes and Kissians attack. At dusk the Immortals come, led by Hydarnes. |
| The Second Day | Mixed assaults, sparabara shield walls and heavier volleys. |
| The Third Day | Ephialtes has shown the Persians the Anopaia path. Three phases: the advance into the wider pass, the fight over Leonidas' body, and the last stand on the Kolonos, where the spears are broken and the Immortals come from behind. |

There is no victory at Thermopylae. When you fall, Simonides' epitaph is shown, along with your
score and the day you reached.

## How it's built

The game itself is plain JavaScript on [three.js](https://threejs.org) (r186, vendored), with no
build step. The Android app is a small Kotlin host around a full-screen WebView:

* `src/main/assets/game/`: the game. It runs in any modern browser too.
  * `js/main.js`: boot, game loop and mode state machine.
  * `js/core/grip.js`: the two motion grips (hilt and window) and the settings each one uses.
  * `js/motion/`: the sensor pipeline. `frames.js` turns raw sensor data into the screen's frame
    of reference, `gestures.js` recognizes swings, stabs, wind-ups and throws plus gyro look (with
    a profile per grip), and `synth.js` is a rigid-body phone simulator, holding the phone either
    way, used by the tests and the training demos.
  * `js/game/`: rules, combos, the player, the hilt grip's lock-on camera (`lockon.js`), combat,
    enemy and ally AI, and the campaign.
  * `js/gfx/`: procedural textures, the world, skinned characters, the first-person arms and
    weapons, and particles and camera effects.
  * `js/audio/`: the sound effects and ambience, synthesized with WebAudio, plus two kinds of
    audio file:
    * The music: three pre-rendered, seamless Ogg Opus loops in `audio/music/` (the title, the
      battle, and the last stand on Day 3). They are decoded one at a time and trimmed to the
      level of the synthesized music they replaced, which stays as the fallback (`loops.js`).
    * The voice lines in `audio/voice/`: Leonidas, Dienekes and the other Greeks speak the Greek
      of 480 BC, read in Modern Greek pronunciation the way Greek schools read ancient texts
      (subtitled in Greek capitals over the English). The narrator and the Agoge use the
      phone's text-to-speech in English.
  * `js/ui/`: HUD, menus, touch and keyboard input, and the Agoge.
* `src/main/java/com/thermopylae/`: the Android host. It serves the assets from a secure origin
  and bridges the gyroscope, accelerometer and gravity sensors at 200 Hz, plus vibration,
  text-to-speech and the screen orientation the game asks for (portrait for the hilt grip), to the
  page.
* `tools/music/`: the offline soundtrack pipeline. The scores (`cues/`, with the sources in
  `cues/NOTES.md`) are rendered, mastered and checked into `audio/music/`. `tools/voice/` makes
  the voice lines.
* `DESIGN.md`: the full design and module contracts.

Credits:
* The Greek voices are rendered offline with [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)
  (Apache-2.0).
* The music is rendered offline with the **GeneralUser GS** SoundFont v1.471 by S. Christian Collins
  ([schristiancollins.com](https://schristiancollins.com)). Its licence (v2.0) allows music made with
  it to be used without restriction, privately or commercially; only the rendered audio ships, never
  the SoundFont. The licence's note on sample provenance is in `tools/music/README.md`.
* The music is synthesized from that SoundFont with [spessasynth_core](https://github.com/spessasus/spessasynth_core)
  (Apache-2.0), a build tool that is not shipped.
* The music quotes line 1 of the Seikilos epitaph (1st c. AD), an ancient melody in the public domain.
  Everything else is composed for the game in the ancient Greek idiom.

### Building and testing

```sh
./gradlew :spartan:assembleDebug              # APK at spartan/build/outputs/apk/debug/spartan-debug.apk
node --test spartan/tests/unit/*.test.mjs     # unit tests: gestures, rules, combos, campaign, module graph…
node spartan/tests/serve.mjs                  # play in a desktop browser at the printed URL
node spartan/tests/e2e/smoke.mjs              # end-to-end run in headless Chromium (needs Playwright)
node spartan/tests/e2e/voice-clips.mjs        # the Greeks' recorded voice lines in the running game
node spartan/tests/e2e/music-cues.mjs         # the recorded music cues in the running game
node spartan/tests/e2e/hilt-lockon.mjs        # the hilt grip's lock-on camera, up to a minute of Day 3's last stand
node spartan/tests/e2e/hilt-throw.mjs         # the hilt grip's cock-back spear throw
node spartan/tests/e2e/buttons.mjs            # the buttons control: layout, every button, the Agoge
```

Run the end-to-end scripts one at a time, from a folder outside the repository: some of them save
screenshots into the current folder.

You can also add `?debug=1`, `?control=touch|motion`, `?grip=hilt|window`, `?mode=battle&day=3`
or `?quality=low|medium|high` to the page URL.
