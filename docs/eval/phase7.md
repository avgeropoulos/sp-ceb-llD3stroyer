# Phase 7: Listen mode, solo vs full mix

## Separation on the synthetic set (CI runner, real Demucs weights)

The synthetic set's 6 mixes (two bouzoukia × 3 phrases) and their solo twins (the very same bouzouki part alone),
240 notes each. Mixes: bouzouki slightly right of centre, a bass, a strummed accompaniment, drums and a voice-like
lead. Scored with the harness (`.github/workflows/models.yml`, step "Score solo vs mix with each separator").

| Mix separator | Mix note F (P / R) | Mix octave-F | Solo F | RTF, mix (runner, 4 cores) | Peak RSS, model alone |
|---|---|---|---|---|---|
| none | 0.811 (0.801 / 0.821) | 0.864 | 0.933 | 0.025 | – |
| light DSP (HPSS + band, no centre cancel) | 0.829 (0.886 / 0.779) | 0.882 | 0.933 | 0.082 | small |
| **htdemucs_6s, guitar + other (7.8 s segments)** | **0.832** (0.810 / 0.854) | 0.840 | 0.933 | 0.569 | 2.9 GB |
| htdemucs_6s fp16 | 0.832 | 0.840 | 0.933 | 0.630 | 3.5 GB |
| htdemucs_6s, 3.9 s segments | 0.819 | 0.824 | 0.933 | 0.450 | 2.0 GB |
| htdemucs_6s, 2.0 s segments | 0.783 | 0.795 | 0.933 | 0.389 | 1.1 GB |
| htdemucs_6s, guitar stem only | 0.401 | 0.414 | 0.933 | 0.555 | 2.9 GB |

Model exports checked against PyTorch with the real weights: 81 dB (7.8 s), 92 dB (3.9 s), 100 dB (2.0 s) SNR, and the
app's Kotlin path 54–119 dB per stem. Sizes: 115 MB (fp32), 60 MB (fp16).

Reading it: on this synthetic band (no real voice, a simple accompaniment), separation gains little over none
(0.811 → 0.832) and the light DSP separation almost as much (0.829) at a seventh of Demucs's time. The bouzouki lands
mostly in Demucs's "other" stem (the guitar stem alone loses most of it). Short segments cost accuracy; the 2 s export
is worse than no separation, so the app never picks it by itself. Real records (a singer, a real band, reverb) are
where Demucs should matter more: measure on the phone (docs/listen-testing.md).

## Solo vs captured full mix

- Synthetic (above): the same bouzouki part transcribes at F 0.933 alone and 0.832 in its mix with Demucs (0.829
  light): about a tenth of the notes lost to the band.
- On a phone, with a real song: record it alone, capture the record with 🎧 Listen, and ⚖ Compare two songs. This can't
  be run from here (no phone, no YouTube app): see docs/listen-testing.md.

## Playback capture with the YouTube app

Not testable from the development environment: it needs a phone with the YouTube app. 🔧 Lab tools → 🎧 Capture test
reports, on the phone, whether capture works (sound captured) or is blocked (only silence: the app opted out), and
Listen falls back to the microphone in that case.
