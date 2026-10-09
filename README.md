# Backyard Boop

A browser game starring Hercules (black) and Apollo (brown). Chase squirrels and bunnies around the yard and give them a friendly boop.

Open `game/index.html` in a browser to play (no build step).

- Squirrels are worth 10 points and bunnies 15. Every 3 boops calls in the Giant Golden Goose, worth 100 points plus 25 per level.
- Sprinklers and Mr. Bubbles, the rolling bathtub, splash you. After 3 splashes it's bath time and the game ends. If the clock runs out first, it's nap time.
- Each level raises the goal and adds faster critters and more hazards. Critters get tired after a long chase, so if you keep after them you'll catch them.
- Hercules has the fastest paws and the quickest turns. Apollo is slower on the ground but leaps (Space, Enter, or the Leap button on touchscreens). While he's in the air he sails over mud, sprinklers and bathtubs, and he can pounce on critters from a little further away. Both dogs score the same points.
- Treats fall from the sky every 10 to 15 seconds. Grabbing one gives that dog a 7-second boost: Hercules gets zoomies (even faster, even snappier turns) and Apollo gets super leaps (longer, higher, quicker to recharge).
- Play solo as either dog, or as a team on one keyboard: WASD moves Hercules and the arrow keys move Apollo. On phones and tablets, put a thumb down anywhere and slide it like a joystick. In team mode the left half of the screen steers Hercules and the right half steers Apollo.

## Music

Each dog has a theme song, made live in the browser with no audio files. Hercules gets ridiculous heavy metal: chugging guitars, double kick drums, shredding solos and pinch squeals. Apollo gets fluffy, bouncy pop with marimba, ukulele strums and boings. Each level plays a different variation, the key goes up every 4 levels, and the music speeds up when time is running low. Extra layers kick in while the Giant Goose is out. In team mode the dogs take turns: Hercules plays on odd levels and Apollo on even ones. The **Music** and **Sounds** buttons switch each one off separately.

## Android

`android/` holds a small app that runs the game full screen. Every push that changes the game makes GitHub Actions build a new APK. It is attached to each run on the Actions tab as `BackyardBoop-apk`. If the repo's workflow permissions are set to "Read and write" (Settings > Actions > General), it is also published on the [Releases page](../../releases/latest). To install it, open the Releases page on an Android phone, tap `BackyardBoop.apk`, and allow your browser to install apps when Android asks. Each new build is signed with the same key, so it installs as an update over the last one.
