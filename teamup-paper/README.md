# Team Up! 3D (Godot) — 3D TOYS and PAPER looks

A 3D version of **Rocco & Blocky: Team Up!**, built with Godot 4.5 (Mobile renderer), in two looks
you switch with the **LOOK** buttons on the title screen (remembered in `user://look.cfg`, `scripts/look.gd`):

* **3D TOYS** (the default): chunky 3D models built from shapes in `scripts/toys.gd` (cartoon shading,
  dark outlines, hand-written walk / jump / mine / slam animations, 3D trees, coins, flags and shots).
* **PAPER**: flat sticker cutouts standing in the world of real 3D blocks, like a pop-up book.

Both looks run exactly the same game code; only the pictures change (`Actor.model` vs `Actor.sprite`,
`Level.prop()` builds a toy or a paper prop).

## The level: 1-1 Meadow Mine

* **One hero at a time**, like the real game: Rocco **or** Blocky is out, with **Sparky**.
  **SWAP** goes Rocco → Blocky → Sparky → Rocco (the button shows who's next).
* **Rocco** jumps high and has a **double jump**. ACTION is a **LONG JUMP** on the ground and a
  **BUTT SLAM** in the air (it breaks the block under him and knocks over bad guys nearby).
  Jumping up into a **? block** bonks it open.
* **Blocky** swings his pickaxe (**MINE**): the block in front, or the one under him if nothing's in front.
  Stone takes 2 hits, iron and gold 3, diamond 4. Bedrock never breaks. A wall early on is there for Blocky.
* **Sparky** is fast and jumps highest. As the helper he zaps a nearby bad guy every few seconds;
  played himself, ACTION is **ZAP**. **SUPER** fills up over 25 seconds: **Thunderbolt** hits everything nearby.
* Bad guys: **Grumblers** (stomp them), **Skelly Archers** (they flash a red **!** and shoot), and
  **Krag Jr.** in his hover-pot at the end. He drops spike balls and swoops down: stomp him when he's low.
* 5 hearts. Run out and you go back to the checkpoint with full hearts. Falling in a pit costs one heart.
* Dennis the dog shows you the way. Rocco talks with the phone's Italian voice.
* The music is the real Team Up! songs (rendered from `core/` Songs.kt).

## Character animation (3D toys)

Everyone blinks (eyes are their own little pieces, `Toys.eyes` / `p.eyes`), breathes and looks around when
standing. Running: arms swing against the legs, hips twist, the body bounces, leans forward with speed and banks
into turns (`p.lean` / `p.roll` in `Toys.pose`). Jumps stretch on the way up and tuck at the top, falls spread
the arms, landings from high up squash and puff dust (`Actor.land_v`). Blocky's pickaxe winds up, strikes and
follows through. Partners gallop with a hop and a wagging tail, Sparky boings, ghosts sway, birds flap. The
clear screen has a little victory dance. The paper look bounces, breathes and stretches the cutouts too.

## Worlds and the rocket

At home, walk onto the **ROCKET** launch pad to take off. The **sky map** has floating islands: your **home**
and one island per world. Landing is easy:

* **Tap an island** (or its floating name), or one of the **island buttons** along the bottom (HOME, then every
  world; locked ones say LOCKED): the rocket flies there by itself and lands (autopilot, `SkyMap.fly_to`).
* Or steer with the stick (all the way = BOOST; the stick always takes over from the autopilot). Over an island,
  let go and the rocket drifts to its middle; **LAND** (or JUMP) works anywhere within 7 blocks of the middle.
* Worlds unlock in order; finishing one puts you back in the rocket over it. Test: `tests/map_check.gd`.

| World | Look | New bad guy | Krag Jr. |
|---|---|---|---|
| 1-1 Meadow Mine | grass, trees, ore hill | Grumblers, Skelly Archers | 18 HP |
| 2-1 Frosty Peaks | snow, pine trees, snowmen, slippery **ice** fields, ice platforms | **Penguin**: "!" then a belly slide | 24 HP |
| 3-1 Sunset Dunes | sand, cacti, palms, sunset sky, sandstone pillars to hop | **Scorpion**: raises its tail "!" then lunges | 30 HP |
| 4-1 Spooky Swamp | dark purple sky, thick mist, mossy ground and mud bogs, dead trees, pumpkins, gravestones | lots of Skelly Archers | 36 HP |
| 5-1 Moon Base | starry black sky, grey moon dust, round craters, moon rock with diamonds; **low gravity** (0.6: jump way higher) | Grumblers in space | 42 HP |
| 6-1 Lava Land | dark red sky, basalt rock with glowing cracks, **lava pools** (touch one: OUCH! you bounce up high and lose a heart, like Mario 64), basalt spikes | bombers, spikies, cactus-men | 48 HP |
| 7-1 Candy Kingdom | pink sky, pink frosting on cake hills (whipped cream on top), **lollipop trees**, gumdrops, cotton-candy trees in pink / lilac / lemon | hoppers, buzzers, boohoos | 54 HP |
| 8-1 Beach Bay | blue sky, sand sloping down to the **sea** (shallow water: you wade at 2/3 speed), palms, sandcastles, beach balls | scorpions (crabs), hoppers | 60 HP |
| 9-1 Jungle Jamboree | deep green jungle, tall trees, **giant mushrooms**, flower patches everywhere | hoppers, buzzers, skellies | 66 HP |
| 10-1 Cloud Castle | high in the sky: **floating cloud islands** with cloud bridges to every challenge (fall off and you're back where you were safe), rainbow arches, gold pillars, gravity 0.85 | buzzers, boohoos | 72 HP |

* Each world has its own song (the real Team Up! snow and desert tunes). Ice: slow to start, slow to stop.
* Adding a world: `Worlds.ORDER` + `INFO` (name, song, sky, fog, map spot, boss HP, optional `cloud` tint and
  `gravity`), then the theme dictionaries at the top of `level.gd` `generate_open` (seed, top / sub / theme
  blocks, foe2, mirror, terrain shape), `_world_extras` (its decorations), `FOES_BY_WORLD`, the sky map island
  props in `skymap.gd`, and the CI `open_check` world loop.

### Colour

Every block has its own gentle colour (`level.gd` `block_color`, per-instance MultiMesh colours): a tiny random
brightness change plus big soft patches from noise (yellow-green to deep-green grass, warm / cool sand, peach /
pink / lavender frosting...). Leaves use a light grey texture (`t_leaves_tint`) coloured per tree: green, or
**autumn** yellow / orange / red in Meadow, Frosty Peaks and at home, cotton-candy colours in Candy Kingdom.
Instance colours are capped at 1 (they can only darken / tint), so bright textures + tints. Clouds are tinted
per world (`INFO.cloud`), lava glows (its own emissive material) and water is shiny. More flower patches and
giant block mushrooms in Meadow and the Jungle.

### Open worlds and stars

Every world is a big **open 96 x 96 world** (like the home) with the turning camera: drag the right side of
the screen to look around, the stick walks where the camera looks. Hills, block trees, ore rocks to mine and bad
guys wandering about (they walk in any direction now). Find the **5 stars** (top of the screen); floating gold
names show where each challenge is from far away, and the closest one's hint shows under the stars:

| Star | How |
|---|---|
| BAD GUY CAMP | beat all 5 bad guys at the camp, then grab the star |
| TALL TOWER | climb the stairs round the tower (a ? block above the top) |
| SECRET CAVE | the tunnel into the big hill is blocked by rock: dig through with Blocky |
| SKY STEPS | jump up 7 floating steps to a little island (Rocco's jump) |
| COIN HUNT | collect 40 coins (coin trails lead from the start to every challenge); the star is at the start |

All 5 open the **gold gate** of **Krag Jr.'s castle** (its walls can't be mined). Inside, Krag Jr. circles,
throws spike balls and dives; beat him and the flag comes down: touch it to finish the world. Stars found are
saved with the file (`stars`). Frosty Peaks is a mirror image of the layout, taller and peaky, with ice ponds.

* Code: `level.gd` `generate_open()` (+ `_castle`, `_camp`, `_tower`, `_cave`, `_sky_steps`, `keep_out`),
  `scripts/openplay.gd` (stars, floating names, gate, flag), `foes.gd` `_tick_open` / `_kragjr_open`.
* Test: `tests/open_check.gd -- [toy] meadow|frost|dunes`: Rocco really jumps every sky step and tower stair
  (real physics), Blocky digs the cave, the camp, 40 coins, the gate, Krag Jr. and the flag.

### Quests, Mario 64 style

Each world now has **8 stars** (5 still open the gate) and you go for **one quest at a time**:

* **STAR SELECT** when you land: a card per star (name, GOT IT! if found), tap a card then tap again (or GO!).
  Stars are kept for good per world (`stars_w` in the save), so a second visit shows them gold and the gate is
  already open. Pause -> **STARS** switches quest any time. Continuing a mid-world save keeps your quest.
* The **current quest** shows everywhere: a big banner ("STAR 2: TALL TOWER"), a gold **light beam** on the spot
  (it follows the nearest red coin / the BIG GRUMBLER / Dennis), an **arrow at the screen edge** with the
  distance when it's off screen (a bouncing arrow over it when it's on screen), its floating name, sparkles when
  you're close, and a panel at the top with the goal and progress ("Left: 3", "Red coins 5 / 8").
* **YOU GOT A STAR!**: everything stops, the camera swings round, Rocco holds the star up, fanfare, star count;
  then the next quest not done yet is picked by itself. Any star still counts, in any order.
* New stars: **RED COINS** (8 red coins: on the tower, the sky steps and little pillars; the star appears on a red
  circle), **BIG GRUMBLER** (beat the mini-boss), **LOST DOG** (Dennis is lost far away: find him, he follows
  you, walk him back to his doghouse by the start).
* Code: `openplay.gd` (`open_select`, `pick`, `target_pos`, `progress`, beam, red coins, `_dog_step`,
  `celebrate`), `level.gd` `_more_quests`, `hud.gd` `_draw_stars` / `_draw_quest_arrow` / `_draw_select` /
  `_draw_stargot`, states `starselect` and `stargot` in `main.gd`. Tests that enter worlds without testing
  this set `OpenPlay.select_enabled = false` (the robot and `Cutscene.enabled = false` skip it too).
* Test: `tests/quest_check.gd` (star select, picking, beam, arrow, the star moment, next quest, pause STARS,
  save and continue); `open_check.gd` now also collects every red coin (and really jumps onto a pillar), beats
  the BIG GRUMBLER and walks Dennis home.

### More bad guys (open worlds)

| Bad guy | What it does | How to beat it | Where |
|---|---|---|---|
| BUZZER (bee) | buzzes in wavy circles, then "!" and dives at you | stomp / attacks | Meadow |
| HOPPER (frog) | big frog hops toward you | stomp / attacks | Meadow |
| BOOHOO (ghost) | hides its face when the camera looks at it; floats after you (through walls) when you look away | stomp / attacks | Frost |
| BOMBER (walking bomb) | lights up when you're close, chases you, then BOOM: breaks blocks around it (not the castle) | hitting or stomping it lights the fuse: run! | everywhere |
| CHOMPY (ball on a chain) | bounces round its post, lunges when you come close (the chain stops it) | attacks only (too hard to stomp) | one per world |
| SPIKY (spiky shell) | wanders about | attacks only: stomping it hurts YOU | everywhere |
| BAT | hangs under the sky steps, swoops at you when you're below | stomp / attacks | sky steps |
| MUMMY | shuffles after you | stomp / attacks | Dunes |
| SNOWBALL ROLLER | "!" then rolls at you, getting bigger, crumbles when it hits something | stomp / attacks | Frost |
| CACTUS-MAN | stands still and throws needles | attacks only (spiky) | Dunes |
| BIG GRUMBLER (mini-boss) | a huge crowned Grumbler with a health bar; "!" then charges | lots of stomps / attacks (+10 coins) | guards a ? block in every world |

Which bad guys wander each world: `level.gd` `FOES_BY_WORLD` (add a line for a new world; `"default"` otherwise).
Code: `foes.gd` `FOES2` / `_tick_foe2`, models `toys.gd` `_foe2` / `_pose_foe2`, pictures `gen_art.py` `foes2()`.
Test: `tests/foes_check.gd -- [toy]` (each one moves/attacks, is beaten the right way, the bomb breaks blocks,
the ghost hides when looked at).

## Power-ups

? blocks often give a power-up (it rises out of the block; mushrooms slide off along the ground like the
2D game), and about 8 lie around each open world (one near the start, one on the tower stairs, one in the
secret cave). Whoever you steer gets it, and it moves to the next hero when you SWAP. The HUD (top right)
shows the power and a ring for the time left.

| Power | What it does | How long |
|---|---|---|
| FIRE FLOWER | ACTION throws bouncing fireballs | until you're hit |
| ICE FLOWER | ACTION throws ice balls: bad guys become ice blocks you can stand on (4.5 s) | until you're hit |
| SUPER MUSHROOM | big, one free hit, head-bumps smash blocks | until you're hit |
| MINI MUSHROOM | tiny (fits small gaps), jumps 30% higher | 15 s |
| SUPER STAR | rainbow, nothing hurts, touching bad guys beats them, faster, faster music | 12 s |
| WING CAP | hold JUMP to fly up | 12 s |
| PROPELLER | JUMP in the air = a big spin up, hold JUMP to float down | 15 s |
| METAL CAP | shiny and heavy: nothing hurts, flattens bad guys, stomps shake, no double jump | 12 s |
| LIFE MUSHROOM | all hearts back | right away |

* Code: `scripts/powers.gd` (`Powers`: items, effects, frozen bad guys, HUD ring; toy models in `item_model`,
  paper pictures `art/pw_*.png` from `tools/gen_art.py` `power_items()`). `main.gd` only calls into it
  (`_setup_powers`, `_power_speed` / `_power_jump`, and one line each in `_open_mystery`, `_action`, `hurt_hero`,
  `_update_foes`, `_step`). Test: `tests/power_check.gd`.

## Cutscenes and MOVIES

Little movies with black bars, speech bubbles and the voices (tap to skip after 1 second), in `scripts/cutscene.gd`:

| Scene | When |
|---|---|
| KRAG JR. STEALS THE STARS | the first time a file arrives home: Krag Jr. flies in with every world's stars, Dennis leaps into his copter and gets carried off |
| BONK! | walking up to Krag Jr.'s castle without 5 stars: he bonks his head on his own gold gate |
| KRAG JR.'S CASTLE | all 5 stars, inside the castle, before the fight |
| HOORAY, DENNIS! | after beating Krag Jr.: his copter crashes, Dennis pops out, everyone cheers |
| BANANA PEEL, STUCK PICKAXE, ACHOO!, DENNIS ZOOMIES | funny ones: sometimes when you get home or grab a star (30%, never two within 90 s, never the same twice in a row) |

Seen scenes are saved (`scenes_seen`). At home, stand by the **MOVIES** TV (next to the welcome sign, or any TV you
place) and tap **MOVIES** to watch them again. A scene is a timeline of tracks (`walk`, `hop`, `say`, `cam`, `pose`...);
everyone is put back where they were when it ends. Tests switch scenes off with `Cutscene.enabled = false`;
`tests/scene_check.gd` plays every scene, SKIP, the intro on a new file, MOVIES and a funny scene.

## Home base: build and decorate

Picking a save file takes you **home**: a big **96 x 96** world (40 blocks tall) to build a whole city on, with
block trees to chop. A new file picks a partner first and gets 30 coins. Walk onto the **rocket** pad to fly
to the worlds.

* **Camera**: drag anywhere on the right side of the screen to turn it around (controller: right stick). The
  stick walks the way the camera looks.
* **BUILD** (like Minecraft): pick a block at the top. **Tap** a spot on the screen to put the block on the side
  of the block you tapped; **hold** a finger on a block to break it (keep holding and slide to break more).
  The cross in the middle + **PLACE / REMOVE** does the same for the block in the middle of the screen
  (the black outline shows which). You can dig the ground too (not the bottom bedrock layer). Reach: 9 blocks.
  23 blocks: planks, log, stone brick, brick, stone, window, road, grass, dirt, leaves, sand, sandstone, snow,
  ice and 6 wool colours are **free**; iron, gold and diamond blocks cost 1 of that ore (refunded on REMOVE).
* **FLY** (top right): fly like Minecraft creative. **UP** (the jump button) rises, **DOWN** sinks; hold DOWN on
  the ground to land. Keyboard F / Q, controller L1 / R1.
* **DECOR**: 33 decorations bought with coins (flower pot, bush, fence, tree, pumpkin, lamp, chair, table, bench,
  bed, campfire, mailbox, fountain, 4 partner plushies, a Krag Jr. trophy that unlocks after beating 1-1, and the
  furniture: bunk bed, canopy bed, race car bed, sleeping bag, hammock, sofa, armchair, beanbag, bookshelf, TV, rug,
  desk, wardrobe, plant pot, toy box).
  **TURN** spins the one in front of you; REMOVE picks it up (coins back). Nothing stacks on a decoration.
* **Colours**: the paint pots under the decorations (the rainbow one = the item's own colours) colour the next
  furniture you place; **PAINT** (free) repaints the furniture you point at. Only the "paint" parts change (blankets,
  cushions, seats, frames), not wood legs or metal. Saved as a 6th number in each decor entry (old saves: own colours).
  Code: `Decor.PAINTS`, `Decor.paint()` marks paintable parts, `Decor.recolour()` (toy look). The paper look uses
  `art/decor_<id>_mask.png` (rendered by `tools/render_decor.gd`, trimmed by `tools/sticker.py`) and a small shader in
  `HomeBase._painted_picture` that colours only the mask's pixels. New paintable item: add it with `"paint": true`
  (and `"h"` = paper height), then `xvfb-run godot --path . --script res://tools/render_decor.gd -- <id>` and
  `python3 tools/sticker.py <id>`.
* **PARTNER** (top right) switches partners at home. **DONE** saves the home. A controller's B places and
  Y removes while building.
* The home saves only what changed from the starting world (`home.edits`, `"v": 2`). Old saves from the small
  44 x 10 home are moved into the middle of the big one (`Level.HOME_OX/OZ`).
* Code: `scripts/home.gd` (HomeBase: `aim_ray` voxel walk, place/remove/rotate, costs, save data), `scripts/decor.gd` (items + 3D
  models), `scripts/boot.gd` (title / home / level switching via `main.go()`), `level.gd` `generate_home()`.
  Paper-look decor pictures are rendered from the 3D models: `tools/render_decor.gd` then `tools/sticker.py`.
* Test: `tests/home_check.gd` (new file -> home, aiming, stacking, side placing, digging, costs/refunds, decor,
  camera-relative walking, flying, Rocco's double jump, rocket, back home, old-save moving).

Rocco's **double jump** is a full-strength second jump with a flip around his middle; the stick can steer it,
and every jump (even a quick tap) goes up a minimum amount (`jump_hold_t`).

## Sprint

Push the thumb stick all the way to the edge and keep it there: after a moment the team sprints (55% faster,
dust puffs, the stick ring glows orange with SPRINT!). Keyboard: hold Shift. Controller: R1 or R2.

## Rocco's moves (like Mario 64)

Rocco has the Mario 64 moves. The orange ACTION button shows what it will do right now
(LONG JUMP / BACKFLIP / DIVE / POUND).

| Move | How (tablet) |
|---|---|
| LONG JUMP | run (or sprint) and tap ACTION: a low, fast leap, about 10 blocks from a sprint; steer a little in the air |
| TRIPLE JUMP | while running, tap JUMP again right as you land, twice: each jump is higher, the third one is a big front flip ("Yahoo!") |
| BACKFLIP | stand still and tap ACTION: straight up, very high (higher than any other jump) |
| SIDE FLIP | while running, pull the stick back the other way and tap JUMP: a high side somersault that turns you around |
| WALL KICK | jump into a wall, then tap JUMP as you touch it: kick off the other way and up; works again and again between two walls |
| DIVE | in the air while pushing the stick and going fast (e.g. after a long jump), tap ACTION: dive forward, land in a BELLY SLIDE (knocks bad guys over); JUMP during the slide rolls out |
| GROUND POUND | in the air with the stick let go (or barely pushed), tap ACTION: spin, then drop hard (breaks blocks, flattens bad guys) |
| DOUBLE JUMP | JUMP again in the air (still works after any jump except a dive) |

Code: `main.gd` `_rocco_action_kind` (what ACTION does), `_rocco_action`, `_rocco_ground_jump` (triple / side flip /
roll out), `_wall_kick`, `_rocco_steer`, `_rocco_after_move` (wall touches, bonks, landing), `Hero.trick`. Poses: `toys.gd`
`_pose_person` (long, dive, slide, wallkick, sideflip, flip/backflip tuck) and back/side flips via `Actor.spin(len, kind)`;
paper pictures `rocco_long/dive/slide/kick/tuck0-7` (`gen_art.py` `rocco_moves()`; the tuck turns through 8 pictures
during a flip). Test: `tests/moves_check.gd` (real physics: distances and heights for every move).

## Partners: pick one, level up, evolve

After picking a save file (and after every finished run) you **PICK YOUR PARTNER**: tap a card to look,
tap again (or **GO WITH ...!**) to play. 12 partners on 2 rows of cards (the picked one's moves are shown
under them). Each has its own level, saved in the file, and evolves (it takes a while now):

| Type | Lv 1 | Lv 7 | Lv 14 | ACTION | SUPER |
|---|---|---|---|---|---|
| Water | SPLASHY | TIDAL | HYDRO | WATER GUN | HYDRO WAVE (waves roll both ways) |
| Grass | SEEDY | SPROUT | BLOOM | VINE WHIP (hits and digs) | SOLAR BEAM (huge beam in front) |
| Fire | EMBER | SCORCH | BLAZE (glides) | FIREBALL (bounces) | FIRE SPIN (ring of fire) |
| Electric | SPARKY | ZAPPER (Lv 10) | | ZAP | THUNDERBOLT |
| Rock | PEBBLIT | BOULDRO | TERRADON | ROCK THROW (bounces, smashes blocks) | EARTHQUAKE (bad guys on the ground) |
| Ghost | BOOLET | SPOOKLE | PHANTOMA | SHADOW BALL (through walls); floats down slowly | BIG SCARE |
| Flying | PIPWING | SWOOPER | SKYHAWK | GUST (knocks back); all glide | TORNADO |
| Psychic | MYSTIKIT | FOXSEER | ASTRAFOX | PSY ORB (chases bad guys) | MIND BLAST (everyone near) |
| Ice | SNOWPUP | FROSTEAL | BLIZZARC | ICE BEAM (goes through a line of bad guys) | BLIZZARD |
| Bug | BUZZLET | BEETLEBUD | TITANBUG | STRING SHOT (long, pulls in) | BUG SWARM |
| Dragon | DRAKLET | WYVERNO | DRAGOROAR | DRAGON BREATH (fire in front) | DRAGON METEOR |
| Fairy | PIXIBUN | FLUFFAIRY | STARABBIT | SPARKLE (3 in a fan) | HEAL BLOOM (hearts full) |

* XP: 12 per bad guy, 60 for Krag Jr., 40 for finishing a world (`Mons.need(level)` = 30 + 18 x level, max Lv 20).
  Old saves keep their XP; a partner can go back a stage because evolving moved to Lv 7 / 14.
* Each stage is bigger, faster and hits harder; Hydro's stomps hurt more. Evolving plays a short scene
  ("WHAT? SPLASHY is evolving!") and saves. When it's the helper, the partner uses its own attack.
* Code: `scripts/mons.gd` (lines, XP, evolve levels), `Hero.set_form()`, `main.gd` `_mon_attack` (+ `_gust`,
  `_string_shot`, `_dragon_breath`) / `_thunderbolt` + `_new_super` / new shots in `spawn_shot` + `_update_shots`
  (rock, shadow, orb, ice, sparkle) / `gain_xp` / `start_evolve`, `hud.gd` `_draw_partner` (`PARTNER_COLS`).
  Art: `gen_art.py` `critter()` + `beast()` + `new_shots()` (paper) and `toys.gd` `_critter()` + `_beast()` (3D).
  Our own designs with made-up names.
* Test: `tests/partner_check.gd` (pick the dragon, evolve at Lv 7, every ACTION and SUPER of all 12 lines at every
  stage, saved levels).

## Save files and pause menu

* **Three save files** (like Team Up!): TAP TO START opens PICK A SAVE FILE. An empty file starts a new game;
  a used one shows who was out, hearts, coins, ores and how many times 1-1 was cleared, and continues the run.
  **ERASE** needs two taps and keeps a backup, so **UNDO ERASE** brings the file back.
* **Pause (II)**: RESUME, **SAVE** and **SAVE & QUIT** (back to the title). A save keeps where you are,
  hearts, coins, ores, the blocks you mined, coins picked up, bad guys beaten, the checkpoint and who's out.
* It also saves by itself at the checkpoint and after beating Krag Jr. Touching the flag saves a fresh
  1-1 for next time but keeps your coins and ores (`scripts/saves.gd`, `main.gd` `save_data`/`_apply_save`).
* Test: `godot --headless --path . --script res://tests/save_check.gd` (save, quit, load, erase, undo, clear).

## Smoothness

The game opens with a short LOADING screen that prepares every text size, picture and effect, so nothing
has to be prepared mid-game. If it still stutters, the pause menu lists the slow moments (bottom left:
how long the frame took and what was happening) - take a screenshot of it.

## Controls

Touch: thumb stick on the left (anywhere on the left half), JUMP / ACTION / SWAP / SUPER on the right, II pauses.
Keyboard: arrows/WASD, Space jump, X action, C swap, V super, Esc pause. Controller: stick, A jump, B action,
X swap, Y super, Start pause.

## Install

Every push that changes `teamup-paper/` builds a signed APK (`.github/workflows/teamup-paper.yml`):
https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/teamup-paper-latest/TeamUpPaper.apk
It's a separate app (`com.roccoquest.teamup.paper`), so it sits next to Team Up!.

**Updates inside the game:** on the title screen the game checks `version.txt` in that release (CI writes the
run number there, after uploading the APK). If it's newer than this build (`application/config/version`,
stamped by CI), a big **UPDATE!** button appears. Tapping it downloads the APK inside the game
(`scripts/updater.gd`) and opens Android's installer; the save data stays because it's the same app and key.
The first time, Android asks to allow installs from this app: turn it on, come back, tap UPDATE! again.
Builds made on a computer (version 0) never check. Test: `godot --headless --path . --script res://tests/update_check.gd -- 1`.

## Making changes

* `scripts/level.gd`: the block world (grid, mining, the level layout in `generate()`, scenery).
* `scripts/main.gd`: the team, SWAP, moves, SUPER, coins, the boss fight, camera, sound.
* `scripts/foes.gd`: Grumbler, Skelly Archer, Krag Jr.
* `scripts/hud.gd`: hearts/ores/boss bar, title and clear screens, multi-touch controls.
* `tools/gen_art.py` draws every sticker and block texture (`python3 tools/gen_art.py`);
  `tools/gen_sfx.py` makes the sound effects. Characters are our own designs.
* Test: `godot --headless --fixed-fps 60 --path teamup-paper --script res://tests/smoke.gd -- toy` (or `-- paper`):
  a robot plays the level. `tests/shot.gd -- <dir> toy` saves screenshots. For screenshots that match the tablet,
  use the real Mobile renderer with a software Vulkan driver (`apt-get install mesa-vulkan-drivers`, then
  `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json xvfb-run godot --rendering-driver vulkan --rendering-method mobile ...`);
  the OpenGL preview hides lighting mistakes (the sun once lit everything from behind and only Vulkan showed it).
