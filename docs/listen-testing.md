# Testing 🎧 Listen on your phone

Two things can only be measured on a real phone, with real apps: whether Android lets Μπουζούκι Lab capture what the
YouTube app plays, and how well a song captured in its full mix transcribes against the same song played alone.
Both stay on the phone; nothing is uploaded.

## 1. Does playback capture work with the YouTube app?

1. Install BouzoukiLab.apk (release `latest-bouzouki-lab`), open it and accept the Terms.
2. Open the YouTube app and start any video with music. Leave it playing.
3. Back in Μπουζούκι Lab: **🔧 Lab tools → 🎧 Capture test → Test**. Android asks to allow capturing; allow it.
4. The report says:
   - **Playback capture: WORKS**: the app heard YouTube's audio directly (best: no room noise, full quality).
   - **Playback capture: only silence came**: YouTube (or the app playing) doesn't allow other apps to capture it;
     🎧 Listen then falls back to the microphone (hold the phone near the speaker, or play it on another device).
   - The Android version, the phone, the levels heard and what Android reported playing (usages only).
5. **Copy** the report to keep it.

Apps decide for themselves whether they may be captured (`allowAudioPlaybackCapture`, and the audio usage they play
with); the YouTube app's behaviour can differ between versions, between YouTube and YouTube Music, and with Premium
background play. Try both, and the browser's YouTube, if you can.

## 2. A song alone, and the same song in its mix

1. Calibrate first: **🎯 Calibrate my bouzouki** (quiet room, each open course three times).
2. Record yourself playing the tune alone: **Songs → 🎧 Listen → The microphone: my bouzouki alone** (or open a
   recording of yours with 📁 Audio file and answer *the bouzouki alone*).
3. Play the record of the same song (YouTube or any app) and capture it: **🎧 Listen → What's playing on this phone**,
   the same length.
4. **🔧 Lab tools → ⚖ Compare two songs**: first your solo take (the reference), then the captured one. The report
   gives the note F-measure (same pitch, onsets within 50 ms, after lining the two up), precision, recall, and how
   many notes landed on the same course and fret.

Tips: play the solo take as the record plays it (same key, same phrasing): every note you play differently counts as an
error. A recording tuned off A = 440 Hz is put in tune automatically.

## 3. What separation costs on your phone

**🔧 Lab tools → ⏱ Separation**: times each separation (the light DSP one, Demucs on short segments, full Demucs) on 20 s
of a synthetic band, with the peak memory. **🧩 Separation** chooses which one songs with a band use, and downloads
its model once (Demucs htdemucs_6s, 115 MB; checked against the release's SHA-256 sums). With too little memory the
full Demucs fails and the light separation is used instead.
