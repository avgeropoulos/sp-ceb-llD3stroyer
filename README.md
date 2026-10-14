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

## Installing on your phone

Every push builds an APK with GitHub Actions (see `.github/workflows/android.yml`):

1. On your phone, open
   **https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download/RoccosQuest.apk**
   in Chrome. It's also the latest release on the repository page.
2. Download `RoccosQuest.apk` and open it. If Android asks, allow your browser to install unknown apps.

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```sh
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test             # game-logic tests; also renders screenshots to core/build/screens
                                 # and the music to core/build/music/*.wav
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

## Μπουζούκι: the Greek bouzouki app

The repository also builds a second app, **Bouzouki.apk**, a playable Greek bouzouki:

**https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download/Bouzouki.apk**

To update, tap the **⬆ Update** button at the bottom of the first screen. The app downloads the new
version from GitHub itself and Android asks you to confirm, so you don't need the browser. The first time,
Android asks you to allow Bouzouki to install apps. Updates install over the old version and keep your settings.
Every build is signed with the same key (`bouzouki/signing/`) and gets a higher version number. The version is shown under the title.

* **Τρίχορδο / Τετράχορδο**: switch between the three-course trichordo (D A D, 26 frets) and the
  four-course tetrachordo (C F A D, 27 frets). The bass courses are octave pairs, like the real instruments.
* **Two screens**: on the first, pick the bouzouki, the dromos and the root note. It also spells out the
  scale and its arpeggio. **Παίξε ▶ Play the bouzouki** opens the neck on its own screen.
* **The whole neck**: the neck runs top to bottom with the nut at the top. Scroll down to reach the
  high frets by dragging the fret numbers on either side. Touch a course anywhere to pluck it,
  slide along it to glide between frets, and use several fingers for chords.
* **Δρόμοι (scales)**: the scale's notes are marked on the neck (root in red) for any root note. The Greek name
  comes first and the Western equivalent follows in parentheses:

  | Dromos | | Dromos | |
  |---|---|---|---|
  | Matzore (Major) | Ματζόρε | Sabah (Minor with flat 4th) | Σαμπάχ |
  | Minore (Natural Minor) | Μινόρε | Houzam (Major with sharp 2nd) | Χουζάμ |
  | Nihavent (Harmonic Minor) | Νιαβέντ | Segiah (Harmonic Major with sharp 2nd) | Σεγκιά |
  | Hitzaz (Phrygian Dominant) | Χιτζάζ | Pireotikos (Double Harmonic with sharp 4th) | Πειραιώτικος |
  | Hitzazkiar (Double Harmonic) | Χιτζαζκιάρ | Nikriz (Ukrainian Dorian) | Νικρίζ |
  | Ousak (Phrygian) | Ουσάκ | Tsinganikos (Hungarian Minor) | Τσιγγάνικος |
  | Kiourdi (Phrygian) | Κιουρντί | Kartsigar (Dorian with flat 5th) | Καρσιγάρ |
  | Rast (Mixolydian) | Ραστ | | |

* **The scale player** on the neck screen plays the dromos up and back down:
  * **⇣ Down the neck**: all the way up one string, from its first root to its highest root (two
    octaves). Choose the string with the buttons under it. The string is shaded on the neck.
  * **⇄ Across the neck**: across all the strings in one hand position, root to root. Move the
    position with ◀ ▶. The frets are shaded on the neck.
  * **Scale / Arpeggio**: play the whole dromos, or just its 1st, 3rd, 5th and 7th. The neck marks
    only the arpeggio notes when it's selected.
  * **Tremolo** re-picks held notes fast, bouzouki style. **✋ Mute** stops everything ringing.
* **🎵 Play along with a YouTube song**: paste a YouTube link, or tap **Share → Μπουζούκι** in the
  YouTube app. The video plays in the app and the melody's notes light up on the neck as they sound,
  with a gold ring on the note playing now and the last few notes listed above the neck.
  * **🎧 Listen to the video** hears the video's own sound (Android 10+). Android asks once per
    session to "start recording or casting". Only the sound is analysed: nothing is recorded or saved.
  * **🎤 Listen with mic** works for music playing anywhere: the YouTube app, a speaker, or a live bouzouki.
  * **Sounds like** guesses the song's dromos and root from the notes it has heard. **Use** puts it on the neck.
  * It follows one melody line at a time. It works best when the bouzouki or the voice is clearly
    in front; with a full band it will sometimes jump to other instruments.
* **Ντο Ρε Μι / C D E** switches note names, and **All notes** shows the notes outside the scale too.
* **The sound** is synthesized (no recordings). Each course is a pair of plucked steel strings,
  slightly detuned or an octave apart, picked near the bridge with a plectrum click, and shaped
  by a bowl-body EQ and a small room. Listen to `docs/bouzouki-demo.wav`.

Code: `bouzouki-core/` holds the scales, tunings and synthesizer in plain Kotlin (tested with
`./gradlew :bouzouki-core:test`). `bouzouki/` is the Android app (`./gradlew :bouzouki:assembleDebug`).
