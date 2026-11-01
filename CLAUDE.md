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
per draw); text-to-speech (`Speaker`) and SoundPool playing (`SoundFx`) run on their own threads.
`FrameClock` (both apps) learns the screen's refresh period from the vsync times and locks onto it as a
fraction of a 60 Hz update (`p/q`: 1/1 at 60 Hz, 2/3 at 90, 1/2 at 120, 5/12 at 144), counting refreshes, not
nanoseconds, so vsync wobble can't turn into skip-then-double hitches; odd rates fall back to real time.
The level camera is `LevelCamera.kt` (`TeamGame.camera`, `camX` = `camera.x`): a critically damped `Spring`
following the leader with a look-ahead (`lead`, shifts only while he moves), sudden target jumps (SWAP, pit
respawns, `camLock` on/off) go into `jumpOffset` and glide away, far teleports cut, the leader is always fully
on screen (a push of more than `JUMP` in one frame is a cut too, never spring speed). Use `snapCamera()` (no
glide) when a level starts or a bonus room is entered/left. On the ground,
stopping/turning uses `GROUND_BRAKE` (not while crouching: the crouch slide makes the crouch long jump).
`MainActivity.dispatchKeyEvent`/`dispatchGenericMotionEvent` hand controller input straight to
`GameView` (no focus needed). The CONTROLS screen has a live tester (`padSaw`, `stickX/Y`, `fps`).
Every jump has a minimum height (`jumpHoldT`). Holding DOWN after a butt slam keeps pounding (`poundAgain`: hop, slam,
hop...); holding ACTION repeats it whenever `cooldown` runs out (Blocky keeps mining).
Each level has its own tune (`TeamGame.Tunes`); boss levels alternate scary songs.

Gear wears out (swords, pickaxes, armor, shields) and crafting never lets a weaker item replace a
better one. Earthquakes happen on every 4th level (`index % 4 == 1`, `isQuakeLevel`). Foe toughness is capped at 1.7x;
after a hit you're safe for `HURT_SAFE_TIME`.

Only one of Rocco or Blocky is out at a time, with the Pokemon (`TeamGame.team` is the live pair,
`allHeroes` is everybody; `switchTo` swaps them).

## UPDATE button

Both Android apps set `TeamGame.onUpdate` (title screen UPDATE button, top left; status in `updateStatus`).
`Updater.kt` (a copy in `teamapp/` and `teamup3d/android/`) reads `version.txt` from the `latest-<BuildConfig.BRANCH>`
release (CI writes the run number), compares it with `VERSION_CODE - 100`, downloads the APK and installs it with
`PackageInstaller` (needs `REQUEST_INSTALL_PACKAGES`; first time it opens the "install unknown apps" setting).

## Team Up! 3D (`teamup3d/`)

A separate app (`com.roccoquest.teamup3d`) that runs the same `TeamGame` and draws levels in 3D.
`TeamGame` exposes hooks for it (`showsLevel`, `worldCam`, `renderLevelBackdrop`, `renderLevelTiles`,
`renderLevelActors(gfx, mark)`, `render(gfx, drawWorld = false)`); keep them working when changing
`renderWorld`. `teamup3d/engine` (`TeamScene`, `TileGfx` = tiles to 3D blocks, `PaperGfx` = any 2D drawing to
3D shapes, `Voxels` = sprites to puffy models) is plain Kotlin; its tests render PNGs with `SoftRaster` to
`teamup3d/engine/build/screens3d`. Other screens (map, house, menus, sticker book) stay Team Up!'s 2D Canvas picture (a 3D-ified menu
attempt looked darker and lagged on the sticker book, so it was removed). The title uses `prepareTitle`/`renderTitleCast` (`TileGfx.titleMode` hides low blocks, pits are filled).
`teamup3d/android` draws the level sky and the HUD with Canvas into `SurfaceTexture`s (`CanvasLayer`) and the
level with GLES 2.0 (`GlScene`); a main-thread `Choreographer` callback runs the `FrameClock` with the vsync
time and only requests a render when there are updates (`pendingSteps`, run on the GL thread in `onDrawFrame`,
at most 3), so a 120 Hz screen doesn't draw every picture twice; on any 3D error it draws plain 2D for half a second. Lighting keeps camera-facing surfaces at their
exact 2D colour (`lit` in the shader / `SoftRaster`). File 1's head start is `TeamGame.HeadStart` (World 9-1,
key `start3d_world9`). Sung theme: `ThemeSinger` (3D app) has the most lifelike English voice
(`VoiceCast.mostNatural`) say each `Songs.themeSpoken` syllable into a WAV (TTS pitch `Autotune.nudge`: 0.85-1.2,
up to 0.67-1.5 only for outer notes), `Autotune.fit` (core) retunes it (`Autotune.shift`: 4 semitones, up to 6 only
when that'd leave it off-key) to `Chiptune.sungNotes` in the octave where every note is in reach
(`Autotune.octavesDown`: 2, 4 or 8 times lower), squeezes long words to fit (`squeeze`,
same pitch) instead of chopping them, and `Chiptune.render(song, words)` mixes them in; `TeamGame.titleMusic` = THEME makes the 3D title play it. Add `:teamup3d:engine` to the
scratch build to test it locally.

3D scenery (`teamup3d/engine/.../Scenery.kt`): real 3D hills/trees/mountains/clouds behind every level, a `Look` per
`Place` (STATION has none: its painted windows are the view). `Scenery.build(place, seed)` makes a `SceneryLevel` once
per level (`TeamScene.scenery`, seed = level index, bonus room 1000+). `TeamScene` keeps the last `KEEP_SCENERY` (2: a
level and its bonus room, keyed on the `LevelDef`, the oldest released when a third comes; `sceneryBuilt` counts builds)
and builds a level's during its INTRO card (`TeamGame.showsIntroCard`: after the circle closing on the map): the app
calls `TeamScene.prepare` on every frame it draws in 2D, then `GlScene.preload` sends 2 of its meshes to the GPU per
frame, so a level's first 3D frame makes nothing new.
4 `Band`s (NEAR -190,
MID -500, FAR -1300, SKY clouds) of `STRIPS` ready-made strip meshes (+ a `glow` mesh drawn at `GLOW`, and a `blink`
mesh of little lights that blink with `time`) repeated along
the level in a hash-picked, sometimes mirrored order, plus an optional flat `floor` (sea, lava lake, toy-room floor).
World 14: THE BIG CITY is `skyscrapers` in two depth rows (`tower`: only lit windows are built, as quads `lift`ed off the
wall so the phone's 16-bit depth buffer doesn't make them flicker; `seenY`/`ceilingY` = what the level and HUD leave
visible), neon signs, water tanks, antennas, spires, a `crane`; THE FOOD KINGDOM is toast/lettuce `hills` (`cheesy`
caps), broccoli, carrots, tomatoes, forks, cheese wedges, soup bowls, giant `tomatoHills` and `cheeseMountains`.
Nothing comes closer than `FRONT_Z` (-80; the level's ground ends at -56). Budget: `MAX_TRIANGLES` per level,
`MAX_DRAWN` per frame on a 600-wide screen (`SceneryTest` checks every level; report in `screens3d/scenery-budget.txt`).
Fog (`FOG_NEAR` = 520, just past the level's farthest corner) fades it into the sky colour (`haze`). With scenery,
`TeamScene.drawsScenery` is true and the app paints `renderLevelBackdrop(gfx, scenery = false)`: only the sky, sun,
stars... (`Sunset`, `SeaArt.seaSky`, `GalaxyArt`, `DinoArt`, `WonderArt`, `PlanetArt`, `CityArt`, `FoodArt.drawSky` take the same flag; the
2D game always passes true). Floating things stay below the HUD bar (`skyY`). Cloud Skies' `cloudSea` is sky blue on a
blue `floor` (never white: the level's cloud platforms are white puffs; `TeamSceneTest.cloudPlatformsStandOutFromTheCloudSea`
checks every SKY level against `MIN_CLOUD_CONTRAST`, report in `screens3d/sky-platforms.txt`). Camera-facing flat shapes (eyes, petals,
candy) use `Kit.lens` (cheap). Screenshots: `TeamSceneTest.sceneryScreenshots` → `screens3d/scenery-*.png`.
`Mesh.release()` only queues meshes the backend uploaded (nothing else drains the queue in tests).

## Where things are in Team Up! (`team/src/main/kotlin/com/roccoquest/team/`)

| File | What's in it |
|---|---|
| `TeamGame.kt` | The game: states, save files, menus, crafting tabs, villager trades, stickers, HUD, rendering |
| `TeamLevel.kt` | Tiles, ores, places, the 21 World 1 levels (character grids built with `Builder`), the `remix` pass that sprinkles new monsters/materials/portals into every level, and the bonus room |
| `World2.kt` | The 21 World 2 levels (22-42) and `WORLD_SIZE`/`worldCount()`/`clearedKey()` |
| `Sea.kt` | World 5 (levels 85-105: water, pirate ships, Rainbow Road), ship/mast/rainbow tiles (`I` `:` `%`), `Cannon` (`N`), `MetalItem` (`$`), Metal Rocco sprites, sea and space skies |
| `MoreWorlds.kt` | Worlds 3 and 4 (levels 43-84) World 6 (Spooky World, levels 106-126, `world6`) and World 7 (Winter World, 127-147, `world7`), appended after World 5, then `world8`..`world14`, built by a seeded level generator plus boss-arena templates. World 4 ends with THE ULTIMATE BATTLE (Y, Q, Z boss rush); World 13 ends with THE WONDER FINALE (Y, Q rush); the last level of the game is World 14's CHEF KRAG (`chefArena`) |
| `Kingdoms.kt` | World 14, the Kingdom Tour: the Big City and Food Kingdom art (`CityArt`, `FoodArt`, `KingdomMap`), Taxi, Meatball, Chili and the last boss CHEF KRAG |
| `Monsters.kt` | Newer monsters (Skelly Archer + arrows, Hopper, Gloop, Snapper + seeds, Crusher `z`, Peekaboo `q`, Puffcloud `l`, Spikestack `j`), bonus `Portal`, `Chest`, sunset sky, Dash |
| `Homes.kt` | `HouseType` (46; the newer 23 are drawn in `MoreHouses.kt`), `Furniture` (46: `floor` 0-3, `unlock` = levels beaten; `furnitureBits` is a Long saved as `furniture`+`furniture2`), floors (`MAX_FLOORS`, stairs at `STAIRS_X`), `PAINTS` (house colors), the Pokemon mat (`MON_X`), `BedStyle`, house outside/inside drawing (the room is 900 wide and scrolls), `PlushieItem` |
| `Plushies.kt` | The 350 `Plushie`s (new ones always go at the END: saved by index) (characters use sticker art, animals are drawn from a `Species` + color) |
| `Actors.kt` | `Power` (flowers), `Mon` (Pokemon), `Sword` (with durability), `Armor`, `Shield`, and `Hero` (Rocco, Blocky, partner) |
| `Foes.kt` | Enemies with health bars, Krag Jr. and King Krag (normal, Wonder, Dry Bones) |
| `Extras.kt` | Villagers, Dennis the dog, minecarts, traps, TNT, bubbles, fish, Endermen, Warden, Ender Dragon, Bomb-bombs |
| `Hercules.kt` | `HerculesArt`: Dennis is drawn as Hercules from the Hercules and Apollo game (same rows + palette as its `WalkSprites`) |
| `Items.kt` | Shots, pickups (stars, leaf, wing cap, mini mushroom...), house/bed, particles, SUPER effects |
| `TeamArt.kt` | Pixel art and hand-drawn pictures |
| `Stickers.kt` | 300 stickers = 60 base pictures x 5 `StickerStyle`s. Add new base pictures only at the end of `base` (index = style*60+base is saved; changing BASE reshuffles saves) |
| `TeamControls.kt` | Touch buttons and `TeamInput` |
| `Juice.kt` | Dust puffs (`Puffs`), squash and stretch (`StretchGfx`, `Squash`) and the circle wipe (`Iris`) |

`teamapp/` is the Android wrapper (`GameView.kt` maps keys, `SoundFx.kt` does sounds, `Speaker.kt` the
text-to-speech voices). Shared sounds and songs live in `core/` (`Gfx.kt` `Sound`/`Music`/`Voice`,
`SoundSynth.kt`, `Songs.kt`).

Voices (`core/.../VoiceCast.kt`, tested in `VoiceCastTest`): `VoiceCast.cast` gives every `Voice` its own real phone
voice (`Persona`: voice name + pitch/rate kept within `MIN_TWEAK`..`MAX_TWEAK` = 0.85-1.2; big pitch changes are what
sounded robotic). It ranks the phone's voices (`naturalness`: quality, internet voices when online, never
`notInstalled`), wants a gender/accent per character (`parts`; Google voice genders by code in `gender`), and avoids
giving two characters the same person (`speaker` strips `-local`/`-network`). Ties go A-Z so the cast never changes.
`SpeechText.tidy` makes lines read naturally (CAPS words said normally, end punctuation); `SpeechQueue` makes a new line
wait for the current one (`QUEUE_ADD`) and only flushes when lines pile up or would be >1 s late. Timed lines (scene cues
in `updateScene`, `sayWarning`) use `SoundSink.sayNow` (QUEUE_FLUSH, cuts in); skipping a scene calls `hush()`. `Speaker.kt` (a
copy in `teamapp/` and `teamup3d/android/`, keep them the same) is the Android side: lists voices, checks online
(`ACCESS_NETWORK_STATE`), Greek line for Poseidon, falls back to plain English if a voice won't load.

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
* Houses have 1-4 floors (`houseFloors`, key `floors`; `homeFloor` is where you are, `changeFloor`).
  `State.PARK` is the Pokemon Park mini game BERRY CATCH (`startPark`/`updatePark`/`renderPark`), opened from
  the Pokemon menu's MINI GAME button or the ARCADE upstairs. Prizes only while `monEnergy` > 0.
* `score` is a Long; `particles` are capped at `MAX_PARTICLES` (too many floating texts made phones lag).
* Performance: draw repeated tiles as cached `Sprite`s (see `SeaArt.plank`/`rainbowTile`), not many shapes per
  tile. Floating texts are capped at `MAX_FLOAT_TEXTS`. Poseidon/Black Flower buttons are top-right (`TeamControls.layout`).
* Music: 42 songs in `Songs.kt` (new ones go at the END of `Music`; World 14 added METRO and FEAST); the title song rotates (`titleSong`, global key `titleSong`) (`Music` enum in `Gfx.kt`); `Tunes.tunes(place)` lists 3-4 per place.
* `Fun.kt`: `Spotty` (the Toad look-alike, `Voice.TOAD`; `planSpotty`/`updateSpotty`, 40% of non-boss levels) and
  `Scenes` (10 five-second funny scenes, `State.SCENE`, after the sticker on odd level indexes; `funnyScenes` flag).
* `thinCrowds()` spreads out bunched-up bad guys at level start (32px gap in World 1, 48px later).
* `Weapons.kt`: `Bow` (Blocky; key `bow`; fired with the long-jump button as SHOOT), `HeroArrow`, `CrazyMushroom`
  (spawned in `onBossEngaged`) and `CrazyBall`. `Foe.frozenT` freezes a foe (no update, no touch damage).
* `Spooky.kt`: `Witch` (`2`), `Potion`, and the World 6 bosses `KingBooHoo` (`3`), `WitchBoss` (`4`); the Warden Twins are
  `Warden(hp = 55)` (`5`). All boss characters are in `BOSS_CHARS` (TeamLevel.kt).
* Decorating: furniture positions are `furnPos` (keys `fpos<i>`: 0 = usual spot, else x+1 + floor*10000); use
  `furnX`/`furnFloor` (never `Homes.spot` directly) when drawing or checking furniture. `fits()` uses `Homes.width`,
  `Homes.layer` and `Homes.blocked`.
* `House3D.kt`: the 3D house (`home3d`, `TeamControls.mode3d` switches the touch layout; blocks saved per floor as
  `b3d<floor>n` + `b3d<floor>_<i>`). It draws the 2D pictures as camera-facing "billboards" (1 block = 30 room px).
* Evolution (`Evolve.kt`, Actors.kt): `MON_LINES`, `Mon.base` (whose SUPER/physics a form uses; switch on `mon.base`,
  not `mon`), `root` (XP is kept on the root), `evolveLevel`, `nextForm`. Evolved forms are drawn with `TintGfx` +
  `ScaledGfx` over their first form. The pick screen only shows `STARTERS` (the first 10).
* `Pokedex.kt`: the 50 newest Pokemon (LEAFLING..DRATINY) built from parts (`Spec`: `Body`, `Ears`, `Tail`, extras `X`,
  `base`). `MonSize.kt` scales every Pokemon to a target height (`TeamArt.mon` with `outline = false` draws the natural
  size, used inside evolutions) and finds the head (`MonSize.head`) for costumes. MEGA_BLAZE/HYDRO/BLOOM (Evolve.kt,
  `isMega`, level 10). Rocco is drawn at `ROCCO_SCALE`.
* `Decor.kt`: `DecorItem` (76, saved by index; `yard` ones go in the backyard past `Garden.YARD_X`; Garden.WIDTH 2000)
  placed as `Placed` in `TeamGame.decor` (keys `decorN`/`decor_<i>`, `Decor.pack`). DECOR/YARD/STYLE shop tabs 6/7/8.
  `hasElevator` (key `elevator`, replaces the stairs, `elevatorMenu`). SHOP button on every floor.
* `Build.kt`: Construction Rocco (`building`, `blocks[floor]` cell -> `BuildBlock`, keys `bld<f>n`/`bld<f>_<i>`).
* `MonGames.kt`: Memory Match, Balloon Pop, Dance Party (`State.GAMES`, `startMonGame`; GAMES button in the Pokemon menu).
* `Style.kt`: `RoccoOutfit`, `BlockySkin`, `MonCostume` (current ones in the `Style` object; keys outfits/skins/costumes
  bits + outfit/skin/costume), `HatKind` drawing. `LongScenes.kt`: scenes 20-25 (10-12 s); MOVIES (TV / movie screen)
  replays seen scenes (`scenesSeen`, `playScene(kind, BACK_HOME)`, `theater`).
* `SillyScenes.kt`: scenes 26-34 (`SillyScenes.FIRST`, 12-15 s: Wonder Oops, Spooky S'mores, The Wrong Mushroom,
  Ring-Ding Concert, Say Cheese, Bath Time Dennis, Who Ate My Spaghetti, The Block Tower, Dash and the Butterfly).
  New scenes go at the END (seen ones are saved by number). `scenesSeen` is a Long saved as `scenesSeen` (bits 0-31) +
  `scenesSeen2` (bits 32-63), so up to 64 scenes. `startScene` uses `nextSceneKind`: never-seen scenes go first.
  A `Scenes.Cue` with empty text is sound-only (speaking "" would cut off the line before). `Scenes.music(kind)` picks
  the song. `TurnGfx` draws anything turned around a point (tipping towers, spinning Krag Jr., flopping fish);
  `KingKrag.mouth` is internal so scenes can open his mouth.
* Safety copies: ERASE and starting a new game over a file copy the whole file to `trash<s>_*` first (`eraseWithBackup`,
  needs `Storage.keys()`); RECOVER on the save screen swaps it back (`recoverSlot`, undoable). Never erase without it.
* On a controller B is also ACTION, so modes that use ACTION (build mode) must not close on `nav.b`.
* `Galaxy.kt`: Worlds 8-9 (`MoreWorlds.world8/world9`; places TOYS, FUNGI, MOON (hero gravity 0.6), STATION; `GalaxyArt`
  ground tiles + skies), foes `ToySoldier` (`;`), `JackBox` (`,`), `Ufo` (`/`) + `Beam`, `MoonHopper` (`<`), bosses
  `MechaKrag` (`{`, `Missile`) and `GalaxyKrag` (`}`, ends 9-21, 10-21 and 12-21; `StarShot`). Also `Outline.of(sprite)` (cached
  outlined sprites for Rocco/Blocky/foes) and `SilhouetteGfx` (draw shapes through it first for a dark outline:
  `TeamArt.mon`, KingKrag, KragJr). Skies use `Gfx.gradient`/`gradientStops` (real gradients on Android, never bands).
* `Spin.kt`: `BlastCannon` (`>`, added to every 4th level by `remix`; `inCannon`, `fireCannon`, `cannonFlight`/`cannonFlyT`),
  `SpikeBomb` (`+`) and `TailKrag` (`&`, level 28 "KRAG'S TAIL SPIN": only thrown-into-a-bomb hurts him;
  `grabbing`, `actionTap`, `heldDir()`). While `busy` (cannon or tail) the normal ACTION/JUMP moves are skipped.
* `Garden.kt`: `Crop` + `Garden` (10 plots; keys `plot<i>`, `grow<i>`, `food<i>`, `seed`), shown at home when `inGarden`
  (GARDEN button, `updateGarden`). Plants grow in `update` everywhere. `babyMode` (key `baby`, the `BABY_MACHINE`
  furniture) draws heroes through `Baby.shrink`/`Baby.dress` (Powers.kt). Houses have up to 4 floors.
* 20 newer Pokemon (EEVO..PENGI, drawn in `MonArt.kt`): `NEW_MONS` join one every `NEW_MON_EVERY` (3) levels beaten
  (`newMonNeeds`, notice in `offerStickers`). The switch grid pages 21 at a time (`PAGE_MONS`, `monPage()`).
* Plushie shelves: wall shelves on every floor (36 each, `drawShelvesWithPlushies`), then the `PLUSH_SHELF*`
  furniture (12 each, `drawFurnitureAt`).
* `Powers.kt`: `CaptureKind` + `CappyHat` (Power.CAPPY; `capture`/`releaseCapture`/`captureAction`, the captured foe is
  drawn at Rocco by `drawCapture`), `MegaItem` (`megaT`), `PropellerItem` (`propT`), `HeroPotion`. Power.GOLD shots make coins.
* `PowerUps.kt`: Wonder power-ups Power.ELEPHANT/DRILL/BUBBLE (appended to `Power`, saved like flowers; `PowerUps.isWonder`).
  Power blocks: level char `'¿'` (`PowerUps.CHAR`, every printable ASCII char is taken) = `Tile.QPOWER`, put on the first
  `?` block of every other level by `remix` (`hasBlock`); `PowerUps.at(tx, index)` picks the power (deterministic).
  `PowerItem` -> `givePowerUp`; crafting tab WONDER (`flowerRecipe`). Only Rocco uses them (`throwPower` ->
  `trunkWhack`/`drillSpin`/`blowBubble`; `HeroBubble` + `bubbleHit`, bounced on in `interact`); drill: `bump` ->
  `drillBlock` (`drillable`: never GROUND/DIRT/bedrock) and keeps rising in `moveHero`, drill-stomps non-stompable
  foes, `drillDown` while squatting (not with `camLock`/`boss`, and only when `floorBelow`: an undrillable floor before
  any lava/spikes, so ship hulls can't be drilled out of the level). `hurt` -> `losePowerUp` (back to `powerBefore`, no
  heart lost) marks it `poofed` (keys `poofed_<NAME>`): `flowerRecipe` then shows MAKE with the ore cost instead of a
  free EQUIP until it's paid for or found again (else every hit could be undone). `checkFalls` respawns through
  `standX` (also used by the quake): nearest ground if the safe spot was dug away, else the checkpoint. Art in `PowerArt`: the elephant is 20 wide and Drill Rocco 9 rows taller, so draw Rocco's power
  pictures with `PowerArt.box` (keeps his 16x24 body in place); the drill spin uses `PHASES` fixed sprites (3D models
  each once); bubble rims are fixed-shape polys (cached in 3D). The moves only work while `powerShows` (not Mini, not
  captured: those punch / do their capture move; no drill stomp riding Dash), and `moveSize` scales them with the picture
  (MEGA 2x, drawn through `Hero.megaGfx`; baby `Baby.SCALE`). `dropsCoins` is the one rule for which knocked-out foes
  drop coins (`onFoeDefeated` and `bubbleHit`). Tests: `PowerUpsTest`, `TeamSceneTest.powerUpsIn3D`.
* `Winter.kt`: World 7 foes `Snowman` (6), `Penguin` (7), `HammerHank` (8), `Chompy` (9), `ZoomerLauncher` (0) + `Zoomer`,
  `Gummy` (1), projectiles `Lob`/`NoteShot`/`ChordWave`, and the final boss `RockStarKrag` (S, drawn as a `KingKrag` plus
  sunglasses and guitar). New places BLIZZARD, AURORA, CANDY (backgrounds in `renderBackground`).
* Never scan level rows every frame: `LevelDef.isBossLevel` is lazy and `Tunes.bossesBefore` is precomputed (a per-frame
  scan made late boss levels lag). Level letters must be unique: 'S' is the shore marker (Rock Star Krag is '!').
* Menus use `confirmTap(key, ...)`: first tap picks (yellow frame via `picked`), second tap on the same thing buys.
* World 10 (`MoreWorlds.world10`, levels 190-210, `Hazards.WORLD10` = first index): `Plan.extra` makes levels longer
  (more checkpoints, `w / 80`). `Hazards.kt`: `Meteor` (red X target), `LavaWave` (eruptions in World 10 VOLCANO
  levels), `Hazards` (per-level director, `game.hazards`; `meteorLevel`/`eruptionLevel`). `Place.ORBIT` = space walk
  (gravity 0.48, `Style.spaceSuit` forces the astronaut outfit/helmet while in the level, `roccoNow`/`blockyNow`).
* `Homes.MAX_FLOORS` = 10 (`storyHeight` squeezes tall houses). `Decor.YARD` = 20 and `Decor.ROOF` = 21 (old saves
  used 9 for the yard: `decorV` < 2 migrates). `Roof.kt`: the roof (`onRoof`, `enterRoof`/`leaveRoof`, ladder at the
  top floor's stairs spot, BUILD A FLOOR sign `Roof.SIGN_X`, telescope). `House3D` areas: floors, `Decor.YARD`,
  `Decor.ROOF` (`area()`, key = x 7 bits | y<<7 | z<<10; floors keep the old save packing, outside saves are
  `b3dyard*`/`b3droof*`).
* `Pumpkin.kt`: `Carver` (carving screen, `input.fingerX/fingerY` = the finger while it's down), `Lantern`s
  (`game.lanterns`, keys `jolN`, `jol<i>x`, `jol<i>_<row>`), table at `Pumpkins.TABLE_X` in the backyard.
* Houses are painted through `PaintGfx` (recolors wall colors keeping their brightness).
* File 1 (slot 0) has no ERASE (`updateSlots`/`renderSlots`). `welcomeBackGift()` (global key `gift2`, once): File 1 jumps
  to `giftLevel` (start of World 7) with 1000 coins/ores/pickaxe/TNT (if the old `gift1` 5000-coin gift was given, it takes
  4000 back); tests pre-set `gift1`/`gift2` in their Storage.
* King Krag: Dry Bones (`dry`) breathes blue `Flame(blue = true)` (a `drift` fan of 3 when angry) with fire drawn in
  his mouth while `mouth > 0`; Wonder (`wonderStyle`) calls `TeamGame.wonderTransform` every 8-11 s, which reuses the
  capture system (`becomeFoe`) for 8 s: FLOPPY (fish out of water, auto-hops), Grumbler, Shellback, Hopper, Bonehead,
  Peekaboo; `wonderForm` is true meanwhile (rainbow twinkle in `drawCapture`).
* `MonSize.target` has per-Pokemon overrides for the little ones (Sparky 12.5).
* Coins: `TeamGame.COIN_VALUE` (2) per coin, plus a level bonus in `offerStickers`. `Voice.HERO` (Rocco) uses an Italian
  TTS voice (a man's, picked by `VoiceCast`); Rocco's lines `GO_LINES`/`OUCH_LINES`.
* Decor (`DecorItem`, 152; the newer 76 drawn in `MoreDecor.kt`) is saved as version 3 (`decorV` = 3: 256 kinds per slot).
  Beds: `BedStyle` (16) has `frame`/`pattern`/`head`. Style: 22 outfits, 18 skins, 21 costumes (`HatKind` has 31).
* `Planets.kt`: `HomePlanet` (9; `homePlanet`/`planetsOwned`, keys `planet`/`planets`) colors the garden, window, roof
  and 3D yard; `PlanetArt` gives World 12's planet Places (MARS..CRYSTAL_PLANET) their sky, ground tiles, gravity and
  the rocket trip on the title card. HOUSE PICKER: `openHousePicker`, `housePicker`/`pickerPlanets`
  (`updateHousePicker`/`renderHousePicker`); `housesOwned` is a Long (keys `houses`/`houses2`).
* `Dinos.kt` (World 11, `MoreWorlds.world11`, Place PREHISTORIC): `DinoFoe`s Raptor `(`, Triceratops `)`, Pterodactyl `[`,
  Stegosaurus `]`, Ankylosaurus `_`, BabyRex `` ` ``, `CappyPickup` `"` (`giveCappy`), boss `RexKrag` `'` (implements
  `NamedBoss`: newer bosses give their own name/banner/home through that interface). CaptureKind has RAPTOR..REX.
  World 12 is `MoreWorlds.world12`; its last level (THE GALAXY GRAND FINALE) still ends with Galaxy Krag `}` and now opens World 13.
* `Wonder.kt` (World 13 Wonder World, `MoreWorlds.world13`, levels 253-273, `Wonder.WORLD13` = first index, Place WONDER
  drawn by `WonderArt`, song `Music.BLOOM`): `WonderFlower` `-` starts WONDER TIME (`TeamGame.wonderT`/`wonderEffect`,
  `startWonderTime`: sets `wonder` for the rainbow sky, wobbly tiles and Wonder music; gravity `min(place, 0.75)`, FLOATY
  0.5) plus a `WonderEffect` picked from the flower's column (`WonderEffect.at`); DANCE uses `Foe.danceT` (no update, no
  touch damage), COIN_RAIN spawns `RainCoin`s, the end spawns a `WonderSeed`. `TalkingFlower` `\` talks through
  `flowerTalk` (`Voice.TOAD`, rate-limited). The generator puts both only in World 13 (older levels never use `-`/`\`).
  `loadSlot` opens the level after the last beaten one, so saves that beat the old last level (12-21) get 13-1.
* `Kingdoms.kt` (World 14 the Kingdom Tour, `MoreWorlds.world14`, levels 274-294, `Kingdoms.WORLD14` = first index): new
  Places CITY (`CityArt`: rooftop tiles with window variants `ground(under, tx, ty)`, night skyline from cached tower
  sprites, neon signs, cranes, taxis on a highway; song `Music.METRO`) and FOOD (`FoodArt`: cheese-on-bread ground,
  `toast` = HARD, `cheese` = BRICK, `soupTop`/`soupDeep` = LAVA tiles, i.e. the soup works like lava; song
  `Music.FEAST`). Every plain ASCII level char was taken, so World 14 uses Latin-1 ones: `TourFoe`s Taxi '¥'
  (honks, then zooms), Meatball 'ø' (rolls at you), Chili 'ç' (spits `ChiliFlame`s) and boss `ChefKrag` '§'
  (`NamedBoss`, KingKrag look + chef hat/apron; `Pie`s, `BigMeatball`s to jump over; when angry he walks to his `SoupPot`,
  no pies or meatballs on the way (`goingToPot`), then stirs it). Falling in the soup says soup, not lava (`checkFalls`).
  CaptureKind TAXI/MEATBALL/CHILI. The generator gives the city more gaps (alleys) and FOOD little soup pools;
  `KingdomMap` decorates the map, `Kingdoms.kingdomName` is on the title card (its own top row, so hazard lines never
  hide it). On the tour `Tunes` counts each place's levels: its own song (first in its list) plays on the 1st, 3rd, 5th...
  (levels before `WORLD14` keep taking turns by level number). 13-21 now says "WORLD 14 IS NEXT!".

* Juice (`Juice.kt`): `game.puffs` is a fixed pool (dust, underwater bubbles, coin twinkles; never more than its size,
  no allocation), fed by `dust(h, ...)` in `moveHero`/`updateLeader` (landing, sprint start, skid, wall bump via
  `Hero.againstWall`, sprint trail; none in `Place.ORBIT`, bubbles underwater). Squash and stretch is drawing only:
  `Hero.stretch` springs (`updateStretch`, `land`), `Hero.draw` scales everything around the feet through the hero's own
  `StretchGfx`; a `StretchingGfx` (Team Up! 3D's `PaperGfx`) scales its finished 3D shapes by matrix instead, because it
  caches a mesh per polygon outline (`polysBuilt`) and stretched outlines would build new ones every frame. The circle wipe (`Iris`: bands + 4 corner polygons) is drawn last in `render`
  (`renderIris`), so the 3D app shows it with the HUD; `levelToScreen` (set by `TeamScene`) moves the hero's spot to
  the 3D picture. It closes in the last `Iris.CLOSE` s of CLEAR/OUCH (`CLEAR_TIME`/`OUCH_TIME`), the bonus sunset,
  portals (`portalPending`, the team can still move) and the first moment of INTRO after PLAY on the map
  (`irisFromMap`: the map is drawn then); `irisOpen` opens it (INTRO->PLAY, portals, `goToMap`, stickers). Wipes never
  add waiting. ? blocks hop when hit (`hop`, `HOP_HEIGHT` < 8 so `TileGfx` keeps them in their cell). Tests: `JuiceTest`
  (`build/screens/juice-*.png`), `TeamSceneTest.circleWipeOpensOnTheHeroIn3D`/`squashAndDustIn3D`.
* Dennis = Hercules (`Hercules.kt`, `HerculesArt`): `stand`/`walk`/`leap`/`wag` sprites (25x18 art + outline pixels on top
  and sides, paws on the bottom row). `Dennis.draw(gfx, px, ground, t, running, facing, ...)` (companion) is used by the
  level, the scenes (`ScaledGfx(gfx, 2.4f, x, ground)`, feet at local y 0) and the sticker (shrunk to fit 26x30).
  He stops at `clearSpot` (out in the open, ground under him) so solid 3D blocks never hide him. `DennisLookTest`
  renders `build/screens/dennis-*.png`; `TeamSceneTest.dennisIn3D` renders `screens3d/dennis-3d.png`.
