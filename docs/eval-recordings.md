# Evaluating the transcription

`bouzouki-core` has a test harness that scores how well the app turns a recording into notes and tab, and how
long that takes:

```sh
./gradlew :bouzouki-core:evalSet -PevalSet=<folder> -PevalOut=<folder> -PevalLabel=<name>
./gradlew :bouzouki-core:evalSet -PevalSet=synth -PevalLabel=<name>   # the synthetic set, made on first use
```

It writes `<name>.md` (the report) and `<name>.csv` (one line per recording, to compare runs). The scores:

| score | what it counts |
|---|---|
| **F** (P, R) | notes found at the right pitch, onset within 50 ms (mir_eval's note F-measure) |
| **octave-F** | the same, an octave off allowed: tells octave errors (the octave-paired courses) from wrong notes |
| **offset-F** | right pitch, onset, and end (within 20% of the note's length, at least 50 ms) |
| **course / fret** | of the notes found, how many are placed on the course (and fret) they were played on |
| **tremolo** | tremolo notes found and marked, of those played |
| **RTF** | processing seconds per second of audio, by stage too (separation, transcription, placing) |

Course and fret only mean something where a recording's reference says where the notes were played *and* that is
where a player would play them: in "every fret of every course" the same pitch is played on several courses on
purpose, so its course score is low by design.

## Recording your own set

Real recordings are what the numbers should come from; the synthetic set (the app's own synth) only stands in until
they exist. Record the bouzouki alone (DI or a close microphone, little room), at 44.1 or 48 kHz, WAV, **to a click**
heard in headphones (the click must not be in the recording). Put each take in one folder with a plan beside it.

### Files

```
myset/
  set.properties                  instrument=TETRACHORDO   (or TRICHORDO; kind=solo)
  open-1.wav       open-1.plan.csv
  frets-c1-1.wav   frets-c1-1.plan.csv     every fret of course 1 (the bass course), 0 to 26
  frets-c2-1.wav   ...
  chromatic-1.wav  chromatic-1.plan.csv
  random-1.wav     random-1.plan.csv
  tremolo-1.wav    tremolo-1.plan.csv
```

The name's first word (before `-` or `_`) is the group the report sums by. Courses are numbered from 0 (the lowest,
bass course) in the plan.

### The plan (`<name>.plan.csv`)

What you will play, in beats of the click: `beat,beats,course,fret[,articulation]`.

```
# bpm=80
# offset=2.0        (optional: seconds from the start of the recording to beat 0)
# start beat, length in beats, course (0 = bass), fret, articulation
0,1,0,0
1,1,0,1
2,1,0,2
4,2,3,5,TREMOLO
```

Articulation is `PICK` (the default), `HAMMER`, `PULL`, `SLIDE` or `TREMOLO`. Without `offset`, the harness finds
where beat 0 is by lining the transcription up with the plan (it searches 1 s before to 8 s after the start), so a
count-in is fine. A take you didn't play as planned (a wrong note) is better re-recorded or its plan fixed: every
difference counts as an error.

If you know the notes in seconds instead (from a MIDI capture, or after correcting a transcription by hand), write a
`<name>.ref.csv` instead: `start,end,midi[,course,fret[,articulation]]`, one note per line.

### What to record

1. **open**: each open course, four plucks each, a beat apart (what the calibration screen asks for).
2. **frets**: every fret of every course, 0 to 26, one per beat, at 80 bpm. One take per course.
3. **chromatic**: up and down each course (frets 0 to 12 and back), in eighths; then a chromatic scale across the
   neck, from the lowest open course up two octaves.
4. **random**: phrases you make up, in dromoi you play, with mixed lengths (eighths, sixteenths, triplets), a few
   minutes in all. Write the plan first, then play it.
5. **tremolo**: held tremolo notes of one to two beats on the top courses, with picked notes between.
6. Optionally **songs**: a whole tune with its reference, solo, and the same tune captured from a full mix
   (`kind=mix`) with 🎧 Listen, to compare the two.

Recordings stay out of the repository (it is public); keep the folder on your computer and point `-PevalSet` at it.
