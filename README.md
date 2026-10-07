# Princess Jump 👑

An Android jumping game: a princess in a pink dress runs through a fairytale
meadow, past faraway castles, and leaps over rocks, thorn bushes, pumpkins and
giant mushrooms.

## How to play

- **Tap** to jump.
- **Tap again in the air** to double jump.
- Catch the floating golden stars for **+10 bonus points**.
- The princess speeds up the longer she runs. Giant mushrooms appear later on.
- Your best score is saved on the phone.

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

The whole game is drawn in code with no image files:

- `app/src/main/java/com/princessjump/game/GameView.kt`: game loop, physics,
  obstacles, and drawing of the princess and the world
- `app/src/main/java/com/princessjump/game/MainActivity.kt`: full-screen
  landscape activity
