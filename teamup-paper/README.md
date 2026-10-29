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
