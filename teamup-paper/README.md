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

## Worlds and the rocket

At home, walk onto the **ROCKET** launch pad to take off. On the **sky map** you steer the rocket yourself
with the stick (all the way = BOOST) over floating islands: your **home** and one island per world. Fly over
one and tap **LAND** (or JUMP). Worlds unlock in order; finishing one puts you back in the rocket over it.

| World | Look | New bad guy | Krag Jr. |
|---|---|---|---|
| 1-1 Meadow Mine | grass, trees, ore hill | Grumblers, Skelly Archers | 18 HP |
| 2-1 Frosty Peaks | snow, pine trees, snowmen, slippery **ice** fields, ice platforms | **Penguin**: "!" then a belly slide | 24 HP |
| 3-1 Sunset Dunes | sand, cacti, palms, sunset sky, sandstone pillars to hop | **Scorpion**: raises its tail "!" then lunges | 30 HP |

* Each world has its own song (the real Team Up! snow and desert tunes). Ice: slow to start, slow to stop.
* The new worlds are built by `level.gd` `_generate_world()` from pieces (hills, pits, a platform pit, ? blocks,
  an ore hill, a mine-through wall, ice fields / pillars) with the start, checkpoint and boss arena in the same
  places as 1-1. The robot test beats every world (`smoke.gd -- toy frost`).
* Saves remember worlds cleared (`cleared_w`) and which world a mid-level save is in (`level`); continuing that
  file goes straight back into that world (`tests/world_check.gd`).
* Code: `scripts/worlds.gd` (the list: names, songs, skies, map spots, unlock order, boss HP), `scripts/skymap.gd`
  (islands, rocket flying, LAND), `main.gd` `_ready_map` / `_step_map` / `land_on`, `Boot.level` / `Boot.from_level`.
  Adding a world: add it to `Worlds.ORDER` / `INFO`, give `_generate_world` its blocks / special piece and
  `_scenery` its props, then add it to the CI world loop.

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
* **DECOR**: 18 decorations bought with coins (flower pot, bush, fence, tree, pumpkin, lamp, chair, table, bench,
  bed, campfire, mailbox, fountain, 4 partner plushies, and a Krag Jr. trophy that unlocks after beating 1-1).
  **TURN** spins the one in front of you; REMOVE picks it up (coins back). Nothing stacks on a decoration.
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

## Partners: pick one, level up, evolve

After picking a save file (and after every finished run) you **PICK YOUR PARTNER**: tap a card to look,
tap again (or **GO WITH ...!**) to play. Each partner has its own level, saved in the file, and evolves:

| Type | Lv 1 | Lv 4 | Lv 8 | ACTION | SUPER |
|---|---|---|---|---|---|
| Water | SPLASHY | TIDAL | HYDRO | WATER GUN | HYDRO WAVE (waves roll both ways) |
| Grass | SEEDY | SPROUT | BLOOM | VINE WHIP (hits and digs) | SOLAR BEAM (huge beam in front) |
| Fire | EMBER | SCORCH | BLAZE (glides) | FIREBALL (bounces) | FIRE SPIN (ring of fire) |
| Electric | SPARKY | ZAPPER (Lv 6) | | ZAP | THUNDERBOLT |

* XP: 12 per bad guy, 60 for Krag Jr., 40 for finishing the level (`Mons.need(level)` = 20 + 10 x level, max Lv 15).
* Each stage is bigger, faster and hits harder; Hydro's stomps hurt more. Evolving plays a short scene
  ("WHAT? SPLASHY is evolving!") and saves. When it's the helper, the partner uses its own attack.
* Code: `scripts/mons.gd` (lines, XP, evolve levels), `Hero.set_form()`, `main.gd` `_mon_attack` / `_thunderbolt`
  (all SUPERs) / `gain_xp` / `start_evolve`, `hud.gd` `_draw_partner`. Art: `gen_art.py` `critter()` (paper) and
  `toys.gd` `_critter()` (3D). Our own designs that keep the real game's partner names.
* Test: `tests/partner_check.gd` (pick, evolve, every ACTION and SUPER at every stage, saved levels).

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
