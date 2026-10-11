# Rocco's Quest

A retro side-scrolling platformer for Android.

Rocco, a plumber in a red cap, has to rescue **Princess Rosalie** (she wears pink) from
**King Krag**, a giant spiky-shelled turtle dragon. Grab the **Blaze Blossom** to
throw bouncing fireballs and take Krag down.

![Title screen](docs/title.png)

## The game

There are three worlds with four levels each:

| Level | Name | What's there |
|---|---|---|
| 1-1 | Meadow Hills | ? blocks, pipes, Grumblers and Shellbacks. Ends at a flagpole and castle. |
| 1-2 | Crystal Caverns | Underground, with blue bricks, coin runs and an exit door. |
| 1-3 | Cloudtop Heights | Clouds and floating islands over bottomless pits, plus winged Grumblers. |
| 1-4 | Krag's Fortress | Lava pits, then the first fight with King Krag. Pip has bad news... |
| 2-1 | Sunset Dunes | A desert at sunset, with pyramids and cacti in the background. |
| 2-2 | Deep Caverns | A longer, harder underground level. |
| 2-3 | Starlight Skyway | A night-time sky level with lots of flyers. |
| 2-3 | | ...and Krag Jr. attacks in his flying clown car! |
| 2-4 | Krag's Volcano | A long lava castle and a tougher fight with King Krag. |
| 3-1 | Frosty Peaks | Slippery snow and ice. Bring the Ice Flower! |
| 3-2 | Glacier Grotto | More snow, a Skip-a-Doo Toilet and Boomadaboom. |
| 3-3 | Blizzard Pass | Icy ledges and two Boomadabooms. |
| 3-4 | Pipe Gorge | Pipes, flyers and a Krag Jr. ambush. |
| 3-5 | Sky Armada | A sky level that ends in a Krag Jr. battle. |
| 3-6 | Krag's Last Stand | Krag Jr. in the great hall, then the final battle. Zoom the Hedgehog smashes the wall and you rescue Princess Rosalie! |
| ★-1 | Wonder Meadow | **Bonus**, unlocked by rescuing the princess. Harder, with a Wonder Flower. |
| ★-2 | Wonder Skies | **Bonus** sky level with Wonder mode, and a last showdown with Krag Jr. |

Power-ups come out of `?` blocks:

* **Super Mushroom**: Rocco grows big and can smash bricks.
* **Blaze Blossom**: Rocco turns white and red and throws bouncing fireballs.
* **Ice Flower**: Rocco turns light blue and throws iceballs that freeze enemies into ice
  blocks. You can stand on the blocks.
* **Boomerang Flower**: Rocco gets an orange and green outfit and throws a boomerang that comes
  back to him and grabs coins on the way.
* **Super Star**: 10 seconds of invincibility. Touching enemies bowls them over, and there's
  special music.
* **Mini Mushroom**: Rocco shrinks to tiny size and can float high into the sky. One hit and
  he's out, though!
* **Blue Shell**: Rocco wears a blue shell. FIRE launches a winged blue shell that hunts down
  the nearest enemy and explodes. Run, and he tucks into his shell and spins along, smashing
  enemies and bricks.
* **Bullet Blaster**: a cannon outfit. FIRE shoots big bullets that fly straight through walls
  and bowl over every enemy in their path (2 damage to bosses).

* **Builder Hammer**: a yellow hard hat. SMASH breaks bricks *and* silver blocks, and bonks bad guys.
* **1-Up Mushroom** (green): an extra life.

If you get hit with a flower power, you drop back to big Rocco. Big Rocco shrinks to small Rocco.

Moves:

* **Run**: double-tap a direction and keep holding it.
* **Crouch**: swipe down on the ground.
* **Butt slam**: swipe down in mid-air. It smashes straight through bricks, opens `?` blocks
  from above, knocks out enemies and sends a shockwave along the ground.
* **Grumblers**: stomp them or burn them. **Winged Grumblers** hop around. Stomp one once to clip its wings.
* **Shellbacks**: stomp one to hide it in its shell, then kick the shell into other enemies.
* **Clouds**: you can jump up through them from below and land on top.
* **King Krag**: breathes fire and jumps. Hit him with fireballs, iceballs or the boomerang
  (6 hits in the fortress, 10 in the volcano, 14 at the last stand), or get past him and touch
  the **axe** to drop the bridge into the lava.
* **Krag Jr.**: flies a clown car, drops spiked balls and swoops at Rocco. He shakes and shows a
  red **!** before each dive. The screen locks until you beat him (4 damage). Stomp the car
  (2 damage), butt slam it (3), or use a power-up. Each of his arenas has a Super Mushroom block.
* **Grab Krag's tail**: Krag is slow to turn around. Jump over him, and while his back is turned
  a **GRAB** button appears. Rocco spins him around and throws him into the sky!
* **Boomadaboom**: Krag's big cousin. He leaps at Rocco and lands with a smash that breaks bricks
  and hurts Rocco if he's standing nearby. Takes 3 stomps.
* **Friends**: Zoom the Hedgehog smashes the wall in front of the princess, Captain Zap (a space
  ranger) jets in now and then to zap a bad guy, and Ring-Ding Frog scoots by every couple of levels
  singing his crazy song. A bouncing **Puzzle Cube** is friendly: bounce on it for a super jump and a coin.
* **Skip-a-Doo Toilet**: crouch on it to flush yourself ahead to the next one.
* **Snack break**: between worlds, Rocco eats a donut and says "That's-a great!" out loud, using
  the phone's own text-to-speech with an Italian voice.
* **Save slots**: three save slots on the title screen. Progress is saved at the start of each level.
* **Wonder mode** (bonus levels): touch the Wonder Flower and the level goes wild. The sky turns
  rainbow, the ground wobbles, Rocco floats, and spike balls, coins and flyers rain down. Grab
  the **Wonder Seed** to end it and get 5000 points.
* You start with 3 lives, and 100 coins gives you an extra one. After a Game Over you continue
  from the start of the current world.

### Music

Every area has its own looping 8-bit tune. They are all original songs written for this game:
a bouncy meadow theme, a spooky cavern bass line, an airy sky waltz-pop, a tense castle march
and a fast boss battle theme. The music is synthesized on the phone, NES-style, with two square
waves, a triangle bass and noise drums, so there are no audio files. Tap the **♪** button
(top right) to turn the music on or off.

### Controls

* **Left half of the screen**: move left and right (you can slide your thumb between the arrows).
  Double-tap and hold to run.
* **Swipe down** anywhere: crouch, or butt slam while in the air.
* **JUMP** (hold it to jump higher) and **FIRE** on the right.
* **II** in the top-right corner pauses the game, and **♪** turns the music on or off.
* Keyboards and game controllers also work: arrow keys or WASD, Shift to run,
  Down or S to crouch/slam, Space to jump, X to fire, M for music.

## New game: Rocco & Blocky: Team Up!

A second game in the same repository. **Rocco** the plumber, **Blocky** the miner and a
Pokemon partner team up to stop **Krag Jr.**, **King Krag**, their purple-and-green **Wonder**
versions, **the Warden**, **Dry Bones King Krag** and finally **the Ender Dragon**, across 21 levels. **Ring-Ding Frog** scoots by on his scooter in every level, singing his
crazy song out loud (with the phone's voice), bowling over bad guys and dropping a present.

![Team Up! title screen](docs/teamup-title.png)

### Save files and picking your Pokemon

There are **three save files**. Tap an empty one to start a new game, then **pick your Pokemon**:

| Pokemon | Plays like | SUPER move |
|---|---|---|
| **Sparky** (electric mouse) | Fast, jumps high, shoots sparks | **Thunderbolt**: lightning hits every bad guy on screen |
| **Blaze** (fire dragon) | Glides if you hold JUMP | **Flamethrower**: burns everything in front |
| **Hydro** (water turtle) | Slow and heavy, stomps hurt bosses more | **Hydro Cannon**: blasts left and right at once |
| **Bloom** (flower dino) | Slow, strong short vine whip | **Solar Beam**: a giant beam across the whole screen |
| **Ember** (little fire lizard) | Fast | **Fire Spin**: a ring of fire all around |
| **Splashy** (tiny turtle) | Air lasts twice as long underwater | **Bubble Beam**: bubbles blast forward and refill your air |
| **Mystic** (pink mystery cat) | Floats if you hold JUMP | **Psychic**: hits every bad guy on screen AND heals 2 hearts |
| **Puncho** (super strong fighter) | Strong punches up close | **Dynamic Punch**: the biggest single hit in the game |
| **Spooky** (ghost) | Floats if you hold JUMP | **Night Shade**: scares every bad guy on screen |

Tap a Pokemon once to see what it does, then tap it again (or **GO!**) to pick it.

![Choose your Pokemon](docs/teamup-pick.png)

The SUPER button fills up slowly (25 seconds), so it's a big boost now and then, not something to
use all the time. When the Pokemon isn't the one you're playing, it follows the team and zaps a
nearby bad guy every few seconds. Tap **ERASE** twice on a file to delete it.

### Playing

* You control one hero and the others follow you. **SWAP** goes Rocco, then Blocky, then your Pokemon.
* **Blocky** swings his pickaxe (**MINE**) to dig stone, **iron**, **gold**, **diamond**, **emerald**,
  **copper** and **amethyst** out of the rocks. Swipe down to dig straight down.
* **Rocco** jumps higher and **THROWS** whatever flower power the team has crafted.
* **Sprint**: double-tap left or right and keep holding to run 50% faster (Shift on a keyboard).
* **Double jump**: Rocco can press JUMP again in mid-air for a spinning second jump.
* **Rocco mines too**: his **butt slam** breaks the block under him, and **jumping up into a block**
  bonks it from below. Either way you get the ore (plain ground is too tough for him).
* **Rocco's Mario 64 moves**: swipe down on the ground to **squat**, swipe down in the air for a
  **butt slam** (it breaks blocks under him and sends out a shockwave), and press **LONG JUMP**
  (or squat + JUMP while running) for a big flying **long jump**.
* Every bad guy has a **health bar**, and they're **tough**: more health and faster than before, and
  bosses have about 30% more health. Stomping still works on most of them!
* New bad guys: **Boneheads** fall apart when stomped and then get back up, and **Flapper** bats
  wake up in caves and chase you.
* `?` blocks can hide a heart or a **Super Star**, and rainbow **mystery blocks** hold a surprise.
* **Bomb-bombs** are walking bombs with wind-up keys. Get close and they light their fuse and run
  at you! Stomp one to stop it (the fuse keeps burning), then kick it into other bad guys. Hitting one
  with a power sets it off right away, and bombs set each other off in a chain reaction.
* **Endermen** are tall, tough mini-bosses. They teleport around, and sometimes they dodge your shots!
* **Villagers** talk to you out loud and trade. Walk up and press the action button (**TALK**).
  They swap coins and stone for iron, gold, diamonds, TNT, golden apples and even stars.
* **Dennis the dog** runs up every now and then, barks "WOOF!" and points the way with a big arrow.
* **Minecarts**: stand next to one and press **RIDE**. It zooms along the rails and smashes bad guys.
  JUMP hops out.
* **Traps**: watch out for **spike traps** on the floor (they hurt bad guys too!), and craft your own
  **Spike Trap** to set down in front of you.
* **TNT**: craft it, then press the **TNT** button to throw it. A red **5x5 grid** shows what will blow up,
  and about one second later... BOOM! Blocks break (you keep the ore) and bad guys get hit hard.
* **Poseidon**: once you have **50 coins**, a trident button appears. Tap it and Poseidon rises from the
  sea, floods the screen and washes away all the simple bad guys (and hurts bosses).
* **Underwater levels**: tap JUMP to swim. Watch the **AIR** bubbles under the hearts and swim through
  **bubbles** from the bubble vents to fill up. If you run out you lose one heart and get more air,
  so it's not too hard. Swim all the way to the shore!
* **Boss music**: King Krag gets his own crazy fast music, and the Warden a creepy heartbeat one.

### Stickers

After every level you get to **pick one of three stickers**. There are **40** to collect: Rocco, Blocky,
all nine Pokemon, Ring-Ding Frog, Dennis, villagers, Poseidon, every kind of bad guy, items, and a
**shiny** sticker for every boss (beat a boss and its shiny sticker is always one of the choices).
Open your **Sticker Book** from the pause screen to see your collection. Stickers are saved in your
save file.

![Pick a sticker](docs/teamup-stickers.png)
![Sticker book](docs/teamup-sticker-book.png)

### The crafting table

Tap **CRAFT**. It glows green when there's something new you can make. It has five tabs:

**Flowers**: make one with 3 of its ore, then make it again to power it up, all the way to **Lv5**.
Every level adds damage. Lv3 throws two at once, Lv4 shots go through bad guys, and Lv5 throws three.

| Flower | Ore | What it does |
|---|---|---|
| Boomerang | iron | Weakest metal, but it comes back |
| Fire | gold | Stronger bouncing fireballs |
| Ice | diamond | The strongest one |
| Grass | emerald | **Green armor** that soaks up hits, plus sharp leaves |
| Thunder | copper | Lightning that jumps to the next bad guy |
| Crystal | amethyst | Fires three crystal shards in a fan |
| **Blue Shell** | diamond | Rocco wears a blue shell and throws **homing shells** that fly to the nearest bad guy and explode. **Sprint** and he tucks in and **spins**, smashing bad guys and bricks |

**Supplies**: better pickaxes (iron, gold, diamond, then amethyst), **Heart** (5 stone),
**Golden Apple** (2 gold: all hearts back plus 1 armor), **TNT x2** (6 stone + 2 iron), **Spike Trap**
(3 iron), **Super Star** (2 amethyst + 1 diamond: 10 seconds of invincibility) and
**Mini Mushroom** (1 emerald + 3 stone): Rocco shrinks to tiny size for 25 seconds and floats way up
high. If he gets hit while mini, he just pops back to normal size. Mystery blocks can hold one too!

**Armor** (its own tab): Iron, Gold, Diamond and Amethyst armor take 4, 5, 8 and 12 hits instead of
your hearts, then break. A bar under the hearts shows how much is left. It costs **8 of its ore plus
10 coins**, so it takes some work to get. Make the same armor again to fix it.

**Black Flower** (on the Weapons tab): a rare one-time item that costs 3 diamond, 3 amethyst,
3 emerald and 20 coins. Tap its black flower button and it pulls in **every bad guy near you** and
gets rid of them (bosses just take a big hit).

**Swords** (the Weapons tab): Blocky can craft five kinds of swords and switch between the ones he's made for free.
He still digs blocks with his pickaxe, but swings his sword whenever a bad guy is in front of him.

| Sword | Costs | What's special |
|---|---|---|
| Stone | 4 stone | A good start: 2 damage |
| Iron | 3 iron | Longer reach: 3 damage |
| Gold | 3 gold | Super fast swings: 4 damage |
| Diamond | 3 diamond | Sweeps every bad guy in reach: 6 damage |
| Amethyst | 3 amethyst | Sweeps AND shoots a purple magic slash: 8 damage |

**Home**: build a **House** (10 stone + 2 iron) where you're standing, then a **Bed** inside it
(4 stone + 10 coins). Stand on the bed and press the action button to **sleep**. That heals you and
**saves your game** right there, and when you load the file you wake up next to your bed.
You can also save any time: pause the game and tap **SAVE GAME**. When you load that file you start
right where you saved. (The game also saves at the start of every level.)

### Levels

| # | Name | What's there |
|---|---|---|
| 1 | Grassy Hills | Lots of iron to get started |
| 2 | Diamond Cave | Diamonds, emeralds and lava pits |
| 3 | Cloud Skies | Cloud jumping and **Krag Jr.** in his clown car |
| 4 | Krag's Castle | **King Krag**... but he finds a Wonder Flower! |
| 5 | Copper Canyon | Desert, copper and Boneheads |
| 6 | Frosty Peaks | Snowy hills and amethyst |
| 7 | Amethyst Mines | Dark caves full of Flapper bats |
| 8 | Wonder Fortress | **Wonder Krag Jr.**, purple and green, in a rainbow sky |
| 9 | Mushroom Jungle | Giant mushrooms and lots of bad guys |
| 10 | Lava Lake | Lava pits everywhere |
| 11 | Wonder Castle | **Wonder King Krag**: huge, purple and green, rainbow fire and spike ball rain |
| 12 | Villager Valley | Villagers to trade with, your first minecart and spike traps |
| 13 | Coral Reef | **Underwater!** Fish, bubble vents and treasure. Swim to the shore |
| 14 | Minecart Mine | Two long minecart rides through a cave full of bad guys |
| 15 | Ender Plains | Night time, and lots of **Endermen** |
| 16 | The Deep Dark | **THE WARDEN**: he's blind but hears you run and jump. Sonic booms! |
| 17 | Treasure Lagoon | More swimming and more treasure |
| 18 | Spike Temple | Spike traps everywhere, plus a minecart ride |
| 19 | Fire Fortress | Endermen, Boneheads and lava |
| 20 | Bone Castle | **DRY BONES KING KRAG**: knock him down and he gets back up once! |
| 21 | The End | **THE ENDER DRAGON**: break the healing crystals, then hit it when it lands |

![Wonder King Krag](docs/teamup-wonder-king-krag.png)
![Poseidon](docs/teamup-poseidon.png)
![The Ender Dragon](docs/teamup-ender-dragon.png)
![Villager trades](docs/teamup-trade.png)

**Lots of points!** Every point counts three times, and your score shows at the top (the star).
Every bad guy you knock out drops coins (tough ones drop more), bosses drop a shower of coins,
stomping bad guys one after another without landing is a **combo** worth more and more points
(5 in a row gives a heart!), and every heart you have left at the end of a level is worth a bonus.

It's easy but not too easy: 5 hearts, checkpoints, and if you run out of hearts you go back to the
last checkpoint (or your bed) and keep all your ore. Beaten bosses leave diamonds, gold and TNT behind.

Keyboard: arrows/WASD to move, Space to jump, X to mine/throw/talk/ride/sleep, Q to swap, E for SUPER,
T to throw TNT, G for Poseidon, B for the Black Flower, Shift to sprint, L for long jump, C to craft (1-6 and 0 pick a recipe, 7 and 8 switch tabs), P to pause, M for music.

![Crafting table](docs/teamup-craft.png)

## Installing on your phone

Every push builds an APK with GitHub Actions (see `.github/workflows/android.yml`):

1. On your phone, open
   **https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download/RoccosQuest.apk**
   in Chrome. It's also the latest release on the repository page.
2. Download `RoccosQuest.apk` and open it. If Android asks, allow your browser to install unknown apps.

For **Team Up!**, do the same with
**https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/latest-ccr-d6916f07-ygbof4/TeamUp.apk**.
It installs as a separate app, so you can have both games.

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```sh
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test             # game-logic tests; also renders screenshots to core/build/screens
                                 # and the music to core/build/music/*.wav
./gradlew :teamapp:assembleDebug # Team Up! APK at teamapp/build/outputs/apk/debug/teamapp-debug.apk
./gradlew :team:test             # Team Up! tests, including a bot that runs every level;
                                 # screenshots go to team/build/screens
```

You can also open the folder in Android Studio and press Run.

## Code layout

* `core/`: the whole game in plain Kotlin with no Android dependencies. It covers physics, levels,
  enemies, the boss and rendering through a small `Gfx` interface. All art is pixel art defined
  in code (`Sprites.kt`), so the game has no image files.
  The music lives in `Songs.kt`, written in a tiny note notation, and `Chiptune.kt` turns it into audio.
* `app/`: the Android layer. A `SurfaceView` game loop, multitouch controls, a `Canvas`
  renderer, sound effects that are synthesized at startup, and a streaming music player.

Levels are built in `core/.../Level.kt` with a small builder, so adding a new one is a few lines.

* `team/`: Team Up!, also plain Kotlin. It reuses the drawing interface, Rocco's pixel art, the
  sound effects and the music from `core/`. Its levels are in `TeamLevel.kt`, the heroes and
  Pokemon in `Actors.kt`, the bad guys and bosses in `Foes.kt`, villagers, Dennis, minecarts, TNT,
  Endermen, the Warden and the Ender Dragon in `Extras.kt`, shots, items and effects in `Items.kt`, and the crafting table, save files and menus in `TeamGame.kt`.
* `teamapp/`: the Android layer for Team Up!, a copy of `app/` wired to the new game.
