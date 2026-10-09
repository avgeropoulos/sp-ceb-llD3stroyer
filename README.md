# Rocco's Quest

A retro side-scrolling platformer for Android.

Rocco, a plumber in a red cap, has to rescue **Princess Rosalie** (she wears pink) from
**King Krag**, a giant spiky-shelled turtle dragon. Grab the **Blaze Blossom** to
throw bouncing fireballs and take Krag down.

![Title screen](docs/title.png)

## The game

| World | Level | What's there |
|---|---|---|
| 1-1 | Meadow Hills | ? blocks, pipes, Grumblers and Shellbacks. Ends at a flagpole and castle. |
| 1-2 | Crystal Caverns | Underground, with blue bricks, coin runs and an exit door. |
| 1-3 | Krag's Castle | Lava pits, leaping lava bubbles and the boss fight on the bridge. |

* **Blaze Blossom**: hit a flashing `?` block that has one and touch the flower. Rocco grows and
  turns white and red, and the FIRE button appears. If you get hit, you shrink back down.
* **Grumblers**: stomp them or burn them.
* **Shellbacks**: stomp one to hide it in its shell, then kick the shell into other enemies.
* **King Krag**: breathes fire and jumps. You can hit him with 10 fireballs, or get past him and
  touch the **axe** to drop the bridge into the lava. Then go to Princess Rosalie.
* Collect 100 coins for an extra life. You start with 3 lives.

### Controls

* **Left half of the screen**: move left and right (you can slide your thumb between the arrows).
* **JUMP** (hold it to jump higher) and **FIRE** on the right.
* **II** in the top-right corner pauses the game.
* Keyboards and game controllers also work: arrow keys or WASD, Space to jump, X to fire.

## Installing on your phone

Every push builds an APK with GitHub Actions (see `.github/workflows/android.yml`):

1. Open the repository's **Releases** page on your phone and find the
   *"Rocco's Quest (latest … build)"* pre-release. You can also download the
   `RoccosQuest-apk` artifact from the workflow run.
2. Download `RoccosQuest.apk` and open it. If Android asks, allow your browser to install unknown apps.

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```sh
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test             # game-logic tests (also renders screenshots to core/build/screens)
```

You can also open the folder in Android Studio and press Run.

## Code layout

* `core/`: the whole game in plain Kotlin with no Android dependencies. It covers physics, levels,
  enemies, the boss and rendering through a small `Gfx` interface. All art is pixel art defined
  in code (`Sprites.kt`), so the game has no image files.
* `app/`: the Android layer. A `SurfaceView` game loop, multitouch controls, a `Canvas`
  renderer, and sound effects that are synthesized at startup.

Levels are built in `core/.../Level.kt` with a small builder, so adding a new one is a few lines.
