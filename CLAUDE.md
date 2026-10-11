# Notes for Claude

This repo has two Android games built from the same engine:

* **Rocco's Quest** (`core/` + `app/`): the original Mario-style platformer.
* **Rocco & Blocky: Team Up!** (`team/` + `teamapp/`): the newer game, still being added to.
  All recent work is on the branch **`ccr-d6916f07-ygbof4`**. Start from that branch.

The player is a kid who asks for features in plain words. Keep replies short, friendly and concrete.
Characters from real games get made-up names that look like them: Rocco (Mario), Blocky (Steve),
King Krag / Krag Jr. (Bowser / Bowser Jr.), Ring-Ding Frog (Crazy Frog), and the Pokemon partners
Sparky, Blaze, Hydro, Bloom, Ember, Splashy, Mystic, Puncho and Spooky.

Only one of Rocco or Blocky is out at a time, with the Pokemon (`TeamGame.team` is the live pair,
`allHeroes` is everybody; `switchTo` swaps them).

## Where things are in Team Up! (`team/src/main/kotlin/com/roccoquest/team/`)

| File | What's in it |
|---|---|
| `TeamGame.kt` | The game: states, save files, menus, crafting tabs, villager trades, stickers, HUD, rendering |
| `TeamLevel.kt` | Tiles, ores, places, the 21 World 1 levels (character grids built with `Builder`), the `remix` pass that sprinkles new monsters/materials/portals into every level, and the bonus room |
| `World2.kt` | The 21 World 2 levels (22-42). The last one is a boss rush: Wonder King Krag, then the Ender Dragon |
| `Monsters.kt` | Newer monsters (Skelly Archer + arrows, Hopper, Gloop, Snapper + seeds), bonus `Portal`, `Chest`, sunset sky |
| `Actors.kt` | `Power` (flowers), `Mon` (Pokemon), `Sword`, `Armor`, and `Hero` (Rocco, Blocky, partner) |
| `Foes.kt` | Enemies with health bars, Krag Jr. and King Krag (normal, Wonder, Dry Bones) |
| `Extras.kt` | Villagers, Dennis the dog, minecarts, traps, TNT, bubbles, fish, Endermen, Warden, Ender Dragon, Bomb-bombs |
| `Items.kt` | Shots, pickups (stars, leaf, wing cap, mini mushroom...), house/bed, particles, SUPER effects |
| `TeamArt.kt` | Pixel art and hand-drawn pictures |
| `Stickers.kt` | The 40 collectable stickers |
| `TeamControls.kt` | Touch buttons and `TeamInput` |

`teamapp/` is the Android wrapper (`GameView.kt` maps keys, `SoundFx.kt` does sounds and the
text-to-speech voices). Shared sounds and songs live in `core/` (`Gfx.kt` `Sound`/`Music`/`Voice`,
`SoundSynth.kt`, `Songs.kt`).

## Building and testing

* There is **no Android SDK** in the cloud container (Google's Maven is blocked), so the APK can only
  be built by GitHub Actions. The JVM modules can be tested locally with a scratch Gradle build that
  only includes `core` and `team`:

  ```sh
  S=/tmp/teamup-build; mkdir -p $S
  cat > $S/settings.gradle.kts <<'EOF'
  pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
  dependencyResolutionManagement { repositories { mavenCentral() } }
  rootProject.name = "scratch"
  include(":core"); project(":core").projectDir = file("<repo>/core")
  include(":team"); project(":team").projectDir = file("<repo>/team")
  EOF
  echo 'plugins { id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false }' > $S/build.gradle.kts
  ./gradlew -p $S :core:test :team:test
  ```

* `team/src/test/.../TeamGameTest.kt` has about 50 tests, including a bot that runs every level and
  fights every boss. The `screenshots` test renders PNGs to `team/build/screens/`. Look at them after
  visual changes.
* Every push builds both APKs in CI (`.github/workflows/android.yml`) and publishes them to the
  release tagged `latest-<branch>`. The Team Up! download link for this branch is
  https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/latest-ccr-d6916f07-ygbof4/TeamUp.apk
  (don't use `releases/latest/...`: other branches publish there too, so it can 404).
* Both APKs are signed with the fixed key in `keystore/games.keystore` and get `versionCode` from the
  CI run number. Don't remove that: a different key (or lower version) makes the phone say
  "App not installed" instead of updating.
* Keep `README.md` (the "New game: Rocco & Blocky: Team Up!" section) up to date with each feature.
