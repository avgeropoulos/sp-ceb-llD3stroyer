# Rocco's Quest and Bouzouki Hero

This repository has two Android games:

* **[Bouzouki Hero](#bouzouki-hero)**: a rhythm game like Guitar Hero, but for bouzouki. Play it
  with built-in songs or with any song from YouTube.
* **[Rocco's Quest](#roccos-quest)**: a retro side-scrolling platformer.

## Bouzouki Hero

<img src="docs/hero.png" width="300" alt="Bouzouki Hero gameplay">

Notes shaped like plectrums come down a bouzouki neck. When a note reaches the bottom, tap
the button in its lane.

The buttons follow the tab, worked out the way [Bouzouki Tabs](https://github.com/avgeropoulos/bouzouki-tabs)
does it (the same course-and-fret search, ported to the phone). Each note shows its tab, like
`D7` for the D course, fret 7. The buttons are your fingers: the left one is the open string,
then fingers 1 to 4 at the hand's current position, and each button shows which fret it is
right now (e.g. `0 5 6 7 8`). When the tab moves up or down the neck, the fret numbers on the
buttons move with it. Easy and Medium have fewer buttons, so some buttons cover two frets. In
**Settings** you can switch to note buttons instead: low notes on the left, high notes on the
right. If you hit notes in a row, your multiplier goes up to x4. Long notes and
tremolo become **hold notes**: keep your finger down until the tail ends. Hitting notes
fills the **OPA!** meter. Once it's half full, tap it to score double points and send plates
flying.

### Difficulties

| | Buttons | Notes |
|---|---|---|
| Easy | 3 | Only the main notes, falling slowly, with a wide timing window |
| Medium | 4 | More of the melody |
| Hard | 5 | Fast runs included |
| Expert | 5 | Every note, falling fast, with tight timing |

Every chart is made from the song's real melody, so the same song works on all four
difficulties. Each one gets a 1 to 5 star rating and a best score.

### Songs

Four original songs come with the game. The phone synthesizes them, so there are no audio
files. Each is in a Greek rhythm and dromos:
*Kafeneio Tsifteteli* (Ousak), *Harbour Hasapiko* (Hitzaz), *Kalamatiano by the Sea* (7/8) and
*Fast Hands Serviko* (a fast one for Expert). When you miss a note, the bouzouki drops out of
the mix until you hit one again.

### Real bouzouki sound

Open **Bouzouki sound** in the menu and record notes from your own bouzouki. Pluck one open
course at a time and let it ring. The app hears each note's pitch, and the built-in songs are
then played with your real recordings instead of the synthesizer. Each note uses the recording
closest in pitch, retuned to the right note. One recording per course (C, F, A, D) covers the
whole neck, and more recordings sound more natural. You can also import single-note
recordings (WAV, MP3, M4A or OGG). Recordings with licenses that allow it can be bundled with
the app in `hero-app/src/main/assets/bouzouki/`. The bass, chords and drums are still
synthesized. YouTube songs play the video's own audio, so you hear the real record.

### YouTube songs

Tap **Search YouTube**, type a song or artist (or paste a link), and tap a result. You can also
share a video from the YouTube app to Bouzouki Hero. If the right video isn't in the list, tap
**Search on YouTube** to browse YouTube's own site inside the app; opening a video picks it. A
free YouTube Data API key in **Settings** makes the built-in search use YouTube's official API.

Once you pick a song, the app plays the video once and listens to make the chart (see
**Automatic** below), then starts the game. If you only want the bouzouki solo, set a start and end time. The video plays
at the top of the screen and the notes stay in time with it. There are three ways to make the
chart:

* **Automatic** (what happens when you add a song). The app listens to the video as it plays and
  finds the bouzouki notes itself,
  like Bouzouki Tabs does, but on the phone. On Android 10 and newer, it captures its own sound
  directly. Android asks once per song whether the app may capture what it plays. On older
  phones, or if you say no, it listens through the microphone, so turn the volume up. It finds
  plucks by the plectrum's bright attack, works out each note's pitch from its harmonics, and
  turns fast repeated picking into tremolo. The notes appear on the neck as they're found, and
  when the video ends (or you tap DONE) the chart is saved. The phone can't run Bouzouki Tabs'
  neural network that separates the instruments, so a loud singer or another string instrument
  can add extra notes. On busy records it loosens its filter until it has enough notes to play,
  so the notes stay in time with the music even when some pitches come from the singer. A meter
  at the top shows that the app can hear the video. For the cleanest result, set a start and end
  around a bouzouki solo, or use Bouzouki Tabs (long-press the song).

* **From Bouzouki Tabs.** Your [Bouzouki Tabs](https://github.com/avgeropoulos/bouzouki-tabs)
  app finds the bouzouki notes for you. Run its web app on a computer on the same Wi-Fi:

  ```sh
  bouzouki-tabs-web --host 0.0.0.0
  ```

  In Bouzouki Hero, open **Settings** and enter the computer's address, e.g.
  `http://192.168.1.20:8000`. Then pick **Bouzouki Tabs** for a song. The phone sends the video
  to Bouzouki Tabs, shows its progress, and builds the chart from the notes it finds, including
  tremolo.
* **Tap along.** Play the video and tap a button for each note you hear: higher buttons for
  higher notes, and hold the button for long notes. Tap **DONE** to save the chart. You can then
  play it on any difficulty.

Some uploaders don't allow their videos to play inside other apps. If a video won't play, try
another upload of the same song.

### Settings

* **Bouzouki Tabs address**: where your Bouzouki Tabs web app is running.
* **YouTube API key** (optional): makes search use the official YouTube API.
* **Sound delay**: if your taps always come out late, for example with Bluetooth headphones,
  raise this.

Keyboards also work: keys 1-5 (or A S D F G) are the five buttons.

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

1. On your phone, open one of these links in Chrome. Both APKs are also in the latest release
   on the repository page.
   * **https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download/BouzoukiHero.apk**
   * **https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download/RoccosQuest.apk**
2. Download the APK and open it. If Android asks, allow your browser to install unknown apps.
3. To update, download the newest APK and open it. It installs over the old version and keeps
   your songs, recordings and scores. Every build is signed with the same key
   (`keystore/debug.keystore`) and has a higher version number. Builds made before this change
   used a random key, so if you have one of those installed, uninstall it once first.

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```sh
./gradlew :app:assembleDebug       # Rocco's Quest: app/build/outputs/apk/debug/app-debug.apk
./gradlew :hero-app:assembleDebug  # Bouzouki Hero: hero-app/build/outputs/apk/debug/hero-app-debug.apk
./gradlew :core:test               # game-logic tests; also renders screenshots to core/build/screens
                                   # and the music to core/build/music/*.wav
./gradlew :hero-core:test          # the same for Bouzouki Hero (hero-core/build/screens and /music)
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

The Bouzouki Hero game lives in `hero-core/`, in plain Kotlin: `Chart.kt` turns a bouzouki line into notes
and buttons for each difficulty, `Session.kt` is the gameplay and drawing, `Tunes.kt` holds the built-in
songs, and `Synth.kt` plays them. The Μπουζούκι app (below) hosts it.

## Μπουζούκι: the Greek bouzouki app

The repository also builds a second app, **Bouzouki.apk**, a playable Greek bouzouki:

**https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download/Bouzouki.apk**

To update, tap the **⬆ Update** button at the bottom of the first screen. The app downloads the new
version from GitHub itself and Android asks you to confirm, so you don't need the browser. The first time,
Android asks you to allow Bouzouki to install apps. Updates install over the old version and keep your settings.
Every build is signed with the same key (`bouzouki/signing/`) and gets a higher version number. The version is shown under the title.

* **Τρίχορδο / Τετράχορδο**: switch between the three-course trichordo (D A D, 26 frets) and the
  four-course tetrachordo (C F A D, 27 frets). The bass courses are octave pairs, like the real instruments.
* **Zoom**: pinch the neck with two fingers to fit more or fewer frets on screen. **⤢ Whole neck** fits
  every fret at once (the song screen starts that way). **⤡ Bigger** goes back to normal size.
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
* **🎵 Play along with a YouTube song** (like Chordify): paste a YouTube link, or tap **Share → Μπουζούκι**
  in the YouTube app. The first time, the app prepares the song in the background (a few minutes, with a
  progress notification):
  1. it reads the song's audio from YouTube,
  2. removes the singer's voice with an AI vocal remover (UVR MDX-Net, downloaded once, 67 MB),
  3. transcribes what's left into notes with Spotify's Basic Pitch (an old record tuned off A = 440 Hz is
     put back in tune for it first, or it would miss many of the notes),
  4. keeps the bouzouki line (the top melodic line in the bouzouki's range) and chooses frets that keep
     the hand moving as little as possible.

  Then the video plays with that line on the neck: a gold ring on the note playing now, a hollow ring
  on the next one, and a moving tab strip of the upcoming notes (tap it to jump). **0.5× / 0.75×** slow
  the song down to learn it, and **🔊 Bouzouki** plays the line on the app's bouzouki too. Prepared songs
  are kept on the phone (**🕘 My songs**) and open instantly; the song's audio itself is deleted once the
  notes are worked out. **📁 Audio file** does the same with a song file on the phone.
* **Ντο Ρε Μι / C D E** switches note names, and **All notes** shows the notes outside the scale too.
* **The sound** is synthesized (no recordings). Each course is a pair of plucked steel strings,
  slightly detuned or an octave apart, picked near the bridge with a plectrum click, and shaped
  by a bowl-body EQ and a small room. Listen to `docs/bouzouki-demo.wav`.

Code: `bouzouki-core/` holds the scales, tunings and synthesizer in plain Kotlin (tested with
`./gradlew :bouzouki-core:test`). `bouzouki/` is the Android app (`./gradlew :bouzouki:assembleDebug`).
