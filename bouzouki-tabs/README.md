# Bouzouki Tabs

Paste a YouTube link to a Greek song, and Bouzouki Tabs listens for the bouzouki
line and writes it out as tablature. It also tells you the tempo and makes a guess
at the **dromos** (Hijaz, Ousak, Rast, and so on).

```
A Hijaz phrase [0:04.0 - 0:30.0]
================================
Source:  https://www.youtube.com/watch?v=...
Tuning:  Tetrachordo: C3 F3 A3 D4 (lowest course at the bottom)
Tempo:   ~100 BPM, 4/4, one column per 16th note
Dromos:  Hijaz on D, Hijazkiar on D, Minore / Nihavent on G (best guess first)

[0:04.6]  bar 1
D|0-----1-----4-----5-----7-----------5--4--1--0--|4-----1-----0-----------------0-----12-10-8--7--|
A|------------------------------------------------|------------------------0----------------------|
F|------------------------------------------------|-----------------------------------------------|
C|------------------------------------------------|-----------------------------------------------|
```

The time stamp on each line is the time in the video, so you can jump straight there.

## Install

You need Python 3.10 or newer and [ffmpeg](https://ffmpeg.org).

```sh
cd bouzouki-tabs
pip install -e .              # numpy and yt-dlp
pip install -e ".[separate]"  # optional: Demucs, to isolate the bouzouki from the band
```

## Use it

### In the browser

```sh
bouzouki-tabs serve
```

Then open http://127.0.0.1:8000, paste a link and press **Make tab**. The video plays
next to the tab. Click a time stamp in the tab to play the video from that point.

### From the command line

```sh
# The intro of a song
bouzouki-tabs tab "https://www.youtube.com/watch?v=..." --start 0:04 --end 0:30

# A zeibekiko on a three-course bouzouki, saved to a file with a MIDI copy to check by ear
bouzouki-tabs tab "https://youtu.be/..." --meter 9/8 --tuning trichordo -o tab.txt --midi check.mid

# A local recording works too
bouzouki-tabs tab my-recording.mp3
```

| Option | What it does |
|---|---|
| `--start`, `--end` | The part of the video to transcribe, like `1:05`. |
| `--tuning` | `tetrachordo` (CFAD, the default), `trichordo` (DAD), or your own notes, lowest first, like `C3,F3,A3,D4`. |
| `--meter` | `4/4` (default), `2/4`, `3/4`, `5/8`, `7/8`, `9/8` or `12/8`. Used for bar lines. |
| `--bpm` | Set the tempo yourself if the detected one is off (beats per minute; eighth notes for x/8). |
| `--separate` | Run Demucs first to remove the vocals, bass and drums. It's slower, but much better on full-band recordings. |
| `-o`, `--json`, `--midi` | Save the tab, every note as JSON, or a MIDI file. |

Downloads and separated tracks are cached in `~/.cache/bouzouki-tabs`.

## How it works

1. **Download**: [yt-dlp](https://github.com/yt-dlp/yt-dlp) fetches the audio track,
   and ffmpeg decodes it to mono.
2. **Optional separation**: [Demucs](https://github.com/facebookresearch/demucs) keeps
   the "other" stem, which is where the bouzouki ends up.
3. **Tuning check**: spectral peaks show how far the recording is from A440. Older
   rebetiko records are often well off, and everything after this is corrected for it.
4. **Pitch salience**: for every semitone the bouzouki can play, the app adds up the
   energy at that note's first eight harmonics. The bright, harmonic-rich sound of a
   bouzouki stands out well this way.
5. **Melody tracking**: a hidden Markov model (one state per semitone, plus silence)
   finds the most likely melody line. Changing note costs a little, so the line doesn't
   flicker.
6. **Notes**: the line is cut at the pick attacks (from spectral flux), so tremolo and
   repeated notes come out as separate strokes.
7. **Fingering**: dynamic programming picks a course and fret for every note with the
   least hand movement. It favours runs along one course and low positions, the way
   bouzouki players tend to play.
8. **Rhythm and layout**: the tempo comes from the autocorrelation of the attacks. Notes
   are placed on a 16th-note grid with bar lines, and long stretches without bouzouki
   are folded away.
9. **Dromos**: the time spent on each pitch class is compared with the scales of the
   common dromoi on all twelve tonics.

## Limits

* It follows **one melody line**. Chords and double stops are reduced to their strongest
  note.
* It can't tell a bouzouki from a voice or a violin playing at the same time. Pick a
  stretch where the bouzouki leads (the intro, a solo or a taximi), or use `--separate`.
* Octave mistakes can happen, especially on the C and F courses, which have octave pairs.
* Bar lines start at the first note, so the downbeat may be off. Check the rhythm by ear.
* The dromos is a guess from the notes used. Dromoi that share notes, like Hijaz and
  Hijazkiar on a short phrase, can be mixed up.

Use the tab as a strong first draft, and check it against the recording. The MIDI
export is handy for that.

## Tests

```sh
pip install -e ".[test]"
pytest
```

The tests synthesise plucked-string melodies (with noise, detuning, octave-paired low
courses and tremolo) and check that the notes, timing, tempo, fingering and dromos
come back correctly.
