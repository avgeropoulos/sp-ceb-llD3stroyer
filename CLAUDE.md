# Notes for Claude

This repo has two Android games built from the same engine:

* **Rocco's Quest** (`core/` + `app/`): the original Mario-style platformer.
* **Rocco & Blocky: Team Up!** (`team/` + `teamapp/`): the newer game, still being added to.
  All recent work is on the branch **`ccr-d6916f07-ygbof4`**. Start from that branch.

The player is a kid who asks for features in plain words. Keep replies short, friendly and concrete.
Characters from real games get made-up names that look like them: Rocco (Mario), Blocky (Steve),
King Krag / Krag Jr. (Bowser / Bowser Jr.), Ring-Ding Frog (Crazy Frog), and the Pokemon partners
Sparky, Blaze, Hydro, Bloom, Ember, Splashy, Mystic, Puncho and Spooky.

Flow: title → save file → world map (`State.MAP`, levels "1-1"...) → level → sticker → back to the map.
The house lives on the map (`State.HOME`: walk around, SHOP / SLEEP / plushie book / claw machine).
Dash the dino (`Dino` in `Monsters.kt`, level char `y`) can be ridden by Rocco (`TeamGame.dino`).
`GameView` catches any error and calls `game.recover()` (back to the title) instead of crashing.
Controllers: `GameView` maps gamepad buttons (default A jump, B throw, X switch, Y super; changeable on
`State.CONTROLS`, saved as `pad0..pad3` prefs), D-pad/hat/stick to `input.left/right/up/down`. Menus use
`TeamGame.nav` (edges that only count when `!input.touching`). Gamepad presses also set `input.tap` with
`tapX = -1`, so menu code must ignore taps with `tapX < 0` when it means a real touch.
Input: `TeamInput` setters latch presses (`wasPressed`), and `TeamGame.edge` reads them, so taps shorter
than a frame still count. Rocco has a jump buffer and coyote time (`jumpBufT`, `coyoteT`) and THROW is
buffered (`actionBufT`). `GameView.run` is a Choreographer loop (one draw per vsync, at most 3 updates
per draw); text-to-speech and SoundPool playing run on their own threads in `SoundFx`.
`MainActivity.dispatchKeyEvent`/`dispatchGenericMotionEvent` hand controller input straight to
`GameView` (no focus needed). The CONTROLS screen has a live tester (`padSaw`, `stickX/Y`, `fps`).
Every jump has a minimum height (`jumpHoldT`).
Each level has its own tune (`TeamGame.Tunes`); boss levels alternate scary songs.

Gear wears out (swords, pickaxes, armor, shields) and crafting never lets a weaker item replace a
better one. Earthquakes happen on every 4th level (`index % 4 == 1`, `isQuakeLevel`). Foe toughness is capped at 1.7x;
after a hit you're safe for `HURT_SAFE_TIME`.

Only one of Rocco or Blocky is out at a time, with the Pokemon (`TeamGame.team` is the live pair,
`allHeroes` is everybody; `switchTo` swaps them).

## Where things are in Team Up! (`team/src/main/kotlin/com/roccoquest/team/`)

| File | What's in it |
|---|---|
| `TeamGame.kt` | The game: states, save files, menus, crafting tabs, villager trades, stickers, HUD, rendering |
| `TeamLevel.kt` | Tiles, ores, places, the 21 World 1 levels (character grids built with `Builder`), the `remix` pass that sprinkles new monsters/materials/portals into every level, and the bonus room |
| `World2.kt` | The 21 World 2 levels (22-42) and `WORLD_SIZE`/`worldCount()`/`clearedKey()` |
| `Sea.kt` | World 5 (levels 85-105: water, pirate ships, Rainbow Road), ship/mast/rainbow tiles (`I` `:` `%`), `Cannon` (`N`), `MetalItem` (`$`), Metal Rocco sprites, sea and space skies |
| `MoreWorlds.kt` | Worlds 3 and 4 (levels 43-84), built by a seeded level generator plus boss-arena templates. Last level: THE ULTIMATE BATTLE (Y, Q, Z boss rush) |
| `Monsters.kt` | Newer monsters (Skelly Archer + arrows, Hopper, Gloop, Snapper + seeds, Crusher `z`, Peekaboo `q`, Puffcloud `l`, Spikestack `j`), bonus `Portal`, `Chest`, sunset sky, Dash |
| `Homes.kt` | `HouseType` (10), `Furniture` (36: `floor` 0-2, `unlock` = levels beaten; `furnitureBits` is a Long saved as `furniture`+`furniture2`), floors (`MAX_FLOORS`, stairs at `STAIRS_X`), `PAINTS` (house colors), the Pokemon mat (`MON_X`), `BedStyle`, house outside/inside drawing (the room is 900 wide and scrolls), `PlushieItem` |
| `Plushies.kt` | The 250 `Plushie`s (new ones always go at the END: saved by index) (characters use sticker art, animals are drawn from a `Species` + color) |
| `Actors.kt` | `Power` (flowers), `Mon` (Pokemon), `Sword` (with durability), `Armor`, `Shield`, and `Hero` (Rocco, Blocky, partner) |
| `Foes.kt` | Enemies with health bars, Krag Jr. and King Krag (normal, Wonder, Dry Bones) |
| `Extras.kt` | Villagers, Dennis the dog, minecarts, traps, TNT, bubbles, fish, Endermen, Warden, Ender Dragon, Bomb-bombs |
| `Items.kt` | Shots, pickups (stars, leaf, wing cap, mini mushroom...), house/bed, particles, SUPER effects |
| `TeamArt.kt` | Pixel art and hand-drawn pictures |
| `Stickers.kt` | 300 stickers = 60 base pictures x 5 `StickerStyle`s. Add new base pictures only at the end of `base` (index = style*60+base is saved; changing BASE reshuffles saves) |
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

## Pokemon levels and Lugia

* `Mon.LUGIA` is the 10th Pokemon, locked until level 3 is cleared (`monUnlocked`). Switch Pokemon at home
  (the POKEMON mat: `monMenu`, `monSwitch`). Each Pokemon has its own XP (`monXp`, keys `monXp<i>`), levels 1-10.
  `superTime()`, `superDmg()` and `monAttack` scale gently with the level.
* Save keys added: `stk0..stk9` (stickers, 30 bits each), `plush4`, `plush5`, `houseColor`, `monXp<i>`.
* Houses have 1-3 floors (`houseFloors`, key `floors`; `homeFloor` is where you are, `changeFloor`).
  `State.PARK` is the Pokemon Park mini game BERRY CATCH (`startPark`/`updatePark`/`renderPark`), opened from
  the Pokemon menu's MINI GAME button or the ARCADE upstairs. Prizes only while `monEnergy` > 0.
* `score` is a Long; `particles` are capped at `MAX_PARTICLES` (too many floating texts made phones lag).
* Performance: draw repeated tiles as cached `Sprite`s (see `SeaArt.plank`/`rainbowTile`), not many shapes per
  tile. Floating texts are capped at `MAX_FLOAT_TEXTS`. Poseidon/Black Flower buttons are top-right (`TeamControls.layout`).
* Music: 33 songs in `Songs.kt`; the title song rotates (`titleSong`, global key `titleSong`) (`Music` enum in `Gfx.kt`); `Tunes.tunes(place)` lists 3-4 per place.
* `Fun.kt`: `Spotty` (the Toad look-alike, `Voice.TOAD`; `planSpotty`/`updateSpotty`, 40% of non-boss levels) and
  `Scenes` (10 five-second funny scenes, `State.SCENE`, after the sticker on odd level indexes; `funnyScenes` flag).
* `thinCrowds()` spreads out bunched-up bad guys at level start (32px gap in World 1, 48px later).
* `Weapons.kt`: `Bow` (Blocky; key `bow`; fired with the long-jump button as SHOOT), `HeroArrow`, `CrazyMushroom`
  (spawned in `onBossEngaged`) and `CrazyBall`. `Foe.frozenT` freezes a foe (no update, no touch damage).
