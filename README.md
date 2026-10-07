# Princess Jump 👑

An Android jumping game: a princess in a pink dress runs through a fairytale
meadow, past faraway castles, and leaps over rocks, thorn bushes, pumpkins and
giant mushrooms.

## How to play

- **Tap** to jump.
- **Tap again in the air** to double jump.
- Catch the floating golden stars for **+10 bonus points**.
- Travel through 5 lands: Flower Meadow, Enchanted Forest, Sunset Hills,
  Candy Clouds and Starry Night. Each level is faster, with giant mushrooms
  from level 2 and double obstacles from level 3. After Starry Night the lands
  repeat, still getting faster.
- A music-box waltz plays while you run, with chimes for jumps, stars and
  level-ups. Tap the music note in the top-left corner to mute.
- Your top 5 scores, and the level each one reached, are saved on the phone.

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow. Open the latest run
under the repository's *Actions* tab and download the `PrincessJump-apk`
artifact. Unzip it, copy `app-debug.apk` to your phone, and open it to install.
You may need to allow installing apps from unknown sources.

## Building it yourself

You need JDK 17+ and the Android SDK (or Android Studio):

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press **Run**.

## Code

The whole game is drawn and composed in code, with no image or audio files:

- `app/src/main/java/com/princessjump/game/GameView.kt`: game loop, physics,
  obstacles, and drawing of the princess and the world
- `app/src/main/java/com/princessjump/game/Sound.kt`: the music and sound
  effects, synthesized in code
- `app/src/main/java/com/princessjump/game/MainActivity.kt`: full-screen
  landscape activity
