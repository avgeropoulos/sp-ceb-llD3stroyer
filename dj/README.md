# MixDeck: a DJ app for Android, Mac, Windows and Linux

Two decks, a mixer and a sampler on your phone. Load songs from **YouTube**, from a
**Spotify** playlist link, or from **audio files** on the phone, then mix, scratch, loop and
record your set. The app also **updates itself**.

![MixDeck](docs/mixdeck.png)

## Install

1. On your phone, open
   **https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/mixdeck-latest/MixDeck.apk**
2. Open the downloaded file. If Android asks, allow your browser to install unknown apps.

That's the only time you install it by hand. (Until this branch is merged into the main branch,
GitHub doesn't show a "Run workflow" button for MixDeck; any push that changes `dj/` publishes
a new build.) Every push to this repo that changes `dj/`
builds a new version, and the app asks **"Update available"** the next time you open it
(or use ☰ → *Check for updates*). It downloads in the background while you keep mixing;
then choose **Install** (or ☰ → ⬆ Install) when the music stops. The first time, Android asks
you to allow MixDeck to install updates. MixDeck closes while Android installs it; open it again
when it's done (with notifications on, a notification brings you back).

## On your computer (Mac, Windows, Linux)

MixDeck also runs on a computer, with the same decks, mixer, effects, key and Nelly tutorial.
Download yours from the
[MixDeck release page](https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/tag/mixdeck-latest):

| Computer | Download | How to open it |
|---|---|---|
| Mac with Apple Silicon (M1, M2, M3, M4…) | `MixDeck-mac-apple-silicon.dmg` | Open it, drag MixDeck to Applications. The first time, right-click MixDeck → **Open** (or System Settings → Privacy & Security → **Open Anyway**). |
| Mac with Intel | `MixDeck-mac-intel.dmg` | Same as above. |
| Windows | `MixDeck-windows.zip` | Unzip it, then run `MixDeck\MixDeck.exe`. If SmartScreen warns you: **More info → Run anyway**. |
| Linux | `MixDeck-linux.tar.gz` | Extract it, then run `MixDeck/bin/MixDeck`. |

(Not sure which Mac you have? Apple menu → About This Mac: "Chip: Apple M…" means Apple Silicon.)

On a computer you use the mouse instead of touch: drag knobs and faders up/down or use the
scroll wheel over them, drag the jog wheel to scratch, and double-click to reset. There are
keyboard shortcuts too:

| Keys | Deck A | Keys | Deck B |
|---|---|---|---|
| A | Pick a song | B | Pick a song |
| Q / W / E | CUE / Play / SYNC | O / P / I | CUE / Play / SYNC |
| 1 2 3 4 | Hot cues | 7 8 9 0 | Hot cues |
| R / T | 4-beat loop / effect | U / Y | 4-beat loop / effect |

| Keys | |
|---|---|
| ← → ↓ | Crossfader left / right, ↓ centres it |
| Z X C V | Sampler: horn, siren, laser, drop (press again to stop) |
| N | Suggest the next song |
| M | MIX: blend into the waiting song (press again to take over) |
| Ctrl+R (⌘R on a Mac) | Start / stop recording |
| F1 | Help & guide |
| H | Show / hide the button labels, each with its key |
| Esc | Close the labels, or else the tutorial |

The computer version updates itself too: when ☰ shows a green dot, choose ⬆ Update, keep mixing while it downloads, then Restart now or When I quit.
Songs are kept in MixDeck's own folder and mixes in `Music/MixDeck`.

## Getting songs

Tap **LOAD** on a deck:

* **YouTube**: search YouTube Music for songs, or switch to *Videos* for remixes, live
  sets and mashups. You can also paste a YouTube link. The audio is downloaded to the phone
  once and then loads instantly from *My songs*.
* **Spotify**: paste a Spotify playlist, album or song link (Share → Copy link in Spotify).
  MixDeck reads the song list and finds each song's audio on YouTube Music when you tap it.
  You can also **share** a Spotify or YouTube link straight to MixDeck from those apps.
  (Spotify itself doesn't let any app mix its audio, which is why the audio comes from YouTube.)
* **My songs**: everything you've loaded before, with its BPM.
* **Phone files**: any MP3, M4A, FLAC, WAV, OGG, ... on your phone.

YouTube downloading is for personal use: it's against YouTube's terms of service, so this app
can't go on the Play Store or App Store.

## DJ tools

| | |
|---|---|
| **Jog wheel** | VINYL on: touch the middle to scratch. Turn the outer ring to nudge the tempo. |
| **Waveforms** | Scrolling, colour-coded (bass / mids / highs) with the beat grid. Drag to scratch or move. Overview above: tap to jump. |
| **BPM & beat grid** | Detected automatically. Tap a deck's title to double/halve the BPM or move the first beat. |
| **Pitch** | ±8 / ±16 / ±50 % (tap the range button). Double-tap the fader to reset. |
| **SYNC** | Matches tempo and beat phase to the other deck (handles half/double time). |
| **CUE** | CDJ-style: set when stopped, hold to preview, tap while playing to jump back. |
| **Hot cues 1-4** | Tap to set, tap to jump, hold to delete. Snap to the beat with Quantize on. |
| **Loops** | 1 / 2 / 4 / 8 beat auto loops, ½ and ×2, manual IN / OUT and reloop. |
| **Mixer** | Trim, 3-band kill EQ, filter (left = low-pass, right = high-pass), channel faders, meters, crossfader (smooth or sharp scratch curve). |
| **Effects** | Per deck: Echo, Flanger, Reverb, Bitcrusher, beat-synced, with an amount knob. Echo and reverb ring out after you switch them off. |
| **Sampler** | Air horn, siren, laser, bass drop. |
| **Headphones** | Split cue: left ear = mix, right ear = decks with 🎧 lit, so you can pre-listen. 🎧 offers to turn it on. |
| **REC** | Records the master mix. Hold REC to stop: it's saved straight away to My mixes. |
| **Safety** | A master limiter so stacked decks never distort. |

Knobs: drag up/down, double-tap to reset.

## Help inside the app

* When both decks are empty, a **Start card** offers the ways in: a guided mix, loading a song,
  or labels on every control. An empty deck's ▶, jog wheel or waveform opens the library too.
* **?** (top middle) lays labels over the DJ screen, one on every control (on a computer, with its
  keyboard key). Tap the yellow banner for the full guide.
* **☰ → Help & guide** is the full guide, starting with "Your first mix in 8 steps".
* **☰ → Learn to mix** is a hands-on lesson: it loads Nelly's "Air Force Ones" on deck A and
  "Grillz" on deck B, dims the screen except the button you need next, tells you what to do,
  and moves on by itself once you've done it. It covers play, the crossfader, the waveform,
  scratching, hot cues, loops, SYNC, cutting and swapping the bass, echo-out and the sampler.
  Exit, Back and Skip are always there.

## Saving mixes

Tap **REC** and play your set. To stop, **hold REC** (a quick tap just shows how long you've been
recording, so a stray tap can't end your set). The mix is saved straight away as "Mix 14 Nov 2026
21.30"; rename it in ☰ → My mixes. It's saved as an M4A
(AAC, about 1.4 MB a minute) in **Music/MixDeck**, where music players and file apps can see
it, and it stays there if you uninstall the app. **☰ → My mixes** plays your mixes back and
lets you share, rename or delete them.

## iPhone

There's no iPhone version yet (the Mac version above runs on Apple computers). An iPhone app would
need a Mac with Xcode and an Apple developer account ($99/year) to build and install, and Apple
doesn't allow apps to update themselves or download from YouTube.

## Building

You need JDK 17 and the Android SDK.

```sh
cd dj
./gradlew :core:test           # audio engine tests (BPM detection, sync, loops, EQ, effects…)
./gradlew :app:assembleDebug   # APK at app/build/outputs/apk/debug/app-debug.apk
```

* `core/`: the audio engine in plain Kotlin: decks with vinyl-style varispeed and scratching,
  cue/hot cues/loops, sync, EQ, filter, effects, crossfader, limiter, sampler, BPM and beat
  detection, waveform analysis, WAV recording.
* `core/…/ui/`: the DJ console itself (decks, mixer, key, tutorial), drawn through a small
  Canvas/Paint API so the phone and the computer share it.
* `media/`: YouTube search and download, Spotify link import and the song library, shared by both apps.
* `desktop/`: the computer app: a Swing window, Java Sound output, FFmpeg (via JavaCV) to decode
  any audio file and encode mixes, keyboard shortcuts. `./gradlew :desktop:run` starts it and
  `./gradlew :desktop:selftest` checks it works on this computer.
* `app/`: the Android app: low-latency `AudioTrack` output, `MediaCodec` decoding, the
  Canvas-drawn console (`DjView`), the song library (YouTube via
  [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), Spotify link import,
  files), mix recording and the self-updater.

Releases are signed with one fixed key so each update installs over the last one. The key is
kept out of git: CI reads it from the repository secrets `MIXDECK_KEYSTORE` (the keystore,
base64) and `MIXDECK_KEYSTORE_PASSWORD`. Without them CI still builds and tests, but doesn't
publish an update. Keep a copy of the key: if it's lost, the next version has to be installed
by hand after uninstalling the old one.

NewPipe Extractor is GPL-3.0, so the MixDeck APK as distributed is covered by the GPL too.
