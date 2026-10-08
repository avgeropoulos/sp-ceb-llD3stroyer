# Backyard Boop

A browser game starring Hercules (black) and Apollo (brown). Chase squirrels and bunnies around the yard and give them a friendly boop.

Open `game/index.html` in a browser to play (no build step).

- Squirrels are worth 10 points and bunnies 15. Every 3 boops calls in the Giant Golden Goose, worth 100 points plus 25 per level.
- Sprinklers and Mr. Bubbles, the rolling bathtub, splash you. After 3 splashes it's bath time and the game ends. If the clock runs out first, it's nap time.
- Each level raises the goal and adds faster critters and more hazards. Critters get tired after a long chase, so if you keep after them you'll catch them.
- Play solo as either dog, or as a team on one keyboard: WASD moves Hercules and the arrow keys move Apollo. Touch and drag also works.

## Music

Each dog has a theme song, made live in the browser with no audio files. Hercules gets ridiculous heavy metal: chugging guitars, double kick drums, shredding solos and pinch squeals. Apollo gets fluffy, bouncy pop with marimba, ukulele strums and boings. Each level plays a different variation, the key goes up every 4 levels, and the music speeds up when time is running low. Extra layers kick in while the Giant Goose is out. In team mode the dogs take turns: Hercules plays on odd levels and Apollo on even ones. The **Music** and **Sounds** buttons switch each one off separately.

## Android

`android/` holds a small app that runs the game full screen. Every push that changes the game makes GitHub Actions build a new APK and publish it on the repo's [Releases page](../../releases/latest). To install it, open that page on an Android phone, tap `BackyardBoop.apk`, and allow your browser to install apps when Android asks. Each new build is signed with the same key, so it installs as an update over the last one.
