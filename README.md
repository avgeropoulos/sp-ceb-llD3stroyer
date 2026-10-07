# Spike Rush Jr.

An Android arcade game: a bratty prince in a flying propeller pod hurls spiked balls at a
plumber hero. The spikes stay planted where they land, so the floor fills up and you have to
keep finding safe ground. When the prince swoops down low, jump on his head. Three stomps
win the round, and every round gets faster and meaner.

![Gameplay](docs/screenshot-gameplay.png)

## How to play

| Control | Touch | Keyboard / gamepad |
| --- | --- | --- |
| Move | ◀ ▶ buttons (bottom-left) | ← → or A / D |
| Jump (hold for higher) | ▲ button (bottom-right half of the screen) | Space, ↑, W, or gamepad A |

- **Red `!` markers** show where a thrown spike is about to land.
- **Planted spikes** blink right before they vanish. You get +5 for each one you outlast.
- **Coins** (+100) pop up on the platforms.
- **Swoop:** after a few throws the prince dives down and sweats. Stomp him for +500,
  but touching the pod or the propeller from the side hurts.
- Clearing a round gives a bonus and an extra life (max 5). The music speeds up each round.

Attack patterns unlock as you progress:

1. **Lob**: an aimed throw that leads your movement. **Spread**: three at once.
2. **Carpet bomb**: he flies across the arena laying a row of spikes with a 2-slot gap.
   **Roller**: a spike ball that rolls at you (jump it!).
3. **Spike rain** from the sky.

![Stomp](docs/screenshot-stomp.png)

## Music and sound

Everything is synthesized in real time (`Synth.kt`), with no audio files. The soundtrack
(`Music.kt`) is an original chiptune boss march in C minor in the style of a cocky "villain
kid" theme:
square-wave lead with vibrato, fast arpeggiated offbeat "brass" stabs, an oom-pah triangle
bass and noise drums, plus a sneaky chromatic bridge (Fm → Cm → D♭ → G7). There are also
round-clear and game-over jingles and retro sound effects.

## Building

The project has no dependencies beyond the Kotlin standard library. All graphics are
drawn with `Canvas`.

- **Android Studio:** open the folder and press Run.
- **Command line** (needs the Android SDK, JDK 17):
  ```
  ./gradlew assembleDebug
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```
- **No setup:** every push runs the **Build APK** GitHub Actions workflow. Download the
  `spike-rush-apk` artifact from the run, copy `app-debug.apk` to your phone and open it
  (allow "install unknown apps").

Requires Android 7.0 (API 24) or newer. Landscape only.

## Code map

| File | What it does |
| --- | --- |
| `MainActivity.kt` | Full-screen immersive activity, lifecycle |
| `GameView.kt` | Game loop thread (fixed 120 Hz step), screen scaling, multi-touch and key input |
| `Game.kt` | Hero physics, boss AI and attacks, spikes, coins, collisions, all drawing |
| `Synth.kt` | Real-time chiptune synthesizer and sound effects (AudioTrack) |
| `Music.kt` | Song data and compositions |
