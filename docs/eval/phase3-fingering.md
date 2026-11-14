# Phase 3: fingering engines compared

Synthetic set, the notes as played (references give course and fret), solo recordings: fingered from the reference pitches, so this measures the fingering alone. "As played" is against the synthetic player's own rule (the melody kept on one course up to fret 12, else the highest course that has the note), not a real player's tab.

```
random     hmm             notes  320 · as played: course  75.9% fret  75.9% · top two  76.6% · course changes/note 0.15 · mean jump 2.11 · span violations 1 · 26 ms
random     tuttut          notes  320 · as played: course  75.6% fret  75.6% · top two  75.3% · course changes/note 0.11 · mean jump 2.16 · span violations 6 · 21 ms
random     tuttut+courses  notes  320 · as played: course  73.1% fret  73.1% · top two  77.8% · course changes/note 0.14 · mean jump 2.20 · span violations 9 · 7 ms
solo       hmm             notes  240 · as played: course  65.0% fret  65.0% · top two  85.8% · course changes/note 0.19 · mean jump 1.80 · span violations 1 · 5 ms
solo       tuttut          notes  240 · as played: course  70.4% fret  70.4% · top two  85.0% · course changes/note 0.17 · mean jump 1.95 · span violations 2 · 3 ms
solo       tuttut+courses  notes  240 · as played: course  68.3% fret  68.3% · top two  87.1% · course changes/note 0.21 · mean jump 1.98 · span violations 3 · 3 ms
chromatic  hmm             notes  227 · as played: course  67.8% fret  67.8% · top two  75.3% · course changes/note 0.11 · mean jump 1.00 · span violations 0 · 3 ms
chromatic  tuttut          notes  227 · as played: course  67.8% fret  67.8% · top two  75.3% · course changes/note 0.11 · mean jump 1.00 · span violations 0 · 3 ms
chromatic  tuttut+courses  notes  227 · as played: course  67.8% fret  67.8% · top two  75.3% · course changes/note 0.11 · mean jump 1.00 · span violations 0 · 4 ms
tremolo    hmm             notes   96 · as played: course  80.2% fret  80.2% · top two 100.0% · course changes/note 0.28 · mean jump 2.30 · span violations 0 · 2 ms
tremolo    tuttut          notes   96 · as played: course  91.7% fret  91.7% · top two 100.0% · course changes/note 0.17 · mean jump 2.72 · span violations 0 · 1 ms
tremolo    tuttut+courses  notes   96 · as played: course  91.7% fret  91.7% · top two 100.0% · course changes/note 0.17 · mean jump 2.72 · span violations 0 · 1 ms
all        hmm             notes 1284 · as played: course  67.4% fret  67.4% · top two  81.6% · course changes/note 0.16 · mean jump 1.65 · span violations 2 · 18 ms
all        tuttut          notes 1284 · as played: course  70.2% fret  70.2% · top two  81.0% · course changes/note 0.13 · mean jump 1.72 · span violations 10 · 13 ms
all        tuttut+courses  notes 1284 · as played: course  68.8% fret  68.8% · top two  82.2% · course changes/note 0.16 · mean jump 1.73 · span violations 14 · 22 ms
```

Whole pipeline (transcription then fingering): course as played 66.7% with the HMM engine, 67.3% with tuttut; note F 0.940 either way (docs/eval/phase3.md).
