# Free bouzouki tabs, scores, videos and datasets

Gathered October 2026 to make the bouzouki transcription (Μπουζούκι, Bouzouki Hero) more
accurate: material for a hand-checked test set now, and for training a bouzouki model later.
Most entries come from search results; pages marked **[checked]** were opened.

**The main finding:** there is no free, openly licensed Greek bouzouki tab collection and no
dataset of bouzouki recordings with note annotations. Most free tabs online are user-made
transcriptions of songs still under copyright. The clean options are public-domain tunes we
transcribe and record ourselves, and Turkish makam data under Creative Commons licences.

**Copyright rule of thumb (EU, life + 70 years):** a composition is public domain in 2026 only
if its composer died before 1956.

* Public domain: Tountas (d. 1942), Skarvelis (1942), Papazoglou (1943), Delias (1944),
  Semsis (1950), and traditional dance tunes.
* Still protected: Vamvakaris (1972), Tsitsanis (1984), Mitsakis (1993), Chiotis, and
  Theodorakis (Zorba).
* A modern transcription or edition can have its own copyright even when the tune is
  public domain.

## Tabs and scores

| Source | What | Licence / notes |
|---|---|---|
| [MuseScore.com](https://musescore.com) (search "bouzouki") | Dozens of Greek scores, some with tab; export to MusicXML. E.g. Tountas' "Κρασοπίνω" (staff notation + trichordo tab) | Set by each uploader; downloads often need an account. Many are copyrighted songs. |
| [John Chambers' ABC archive](http://trillian.mit.edu/~jc/music/abc/), [FolkTuneFinder](https://folktunefinder.com) | Traditional Greek tunes in ABC notation (e.g. Samiotissa, Hasapikos) | Mostly traditional, public domain; machine-readable, so we can generate CFAD/DAD tab from them |
| [SymbTr](https://github.com/MTG/SymbTr) **[checked]** | About 2,200 Turkish makam scores (MusicXML, MIDI, text), with modes close to the dromoi (hijaz, ussak) | CC BY-NC-SA 4.0 (non-commercial) |
| [Kounadis Archive (rebetiko)](https://vmrebetiko.aegean.gr/en/explore-en/) | About 3,000 78 rpm recordings and 2,200 scanned scores | Some items marked CC; exact terms unverified; scores are probably voice/piano |
| [Lilian Voudouri Music Library](https://digital2.mmb.org.gr/en/collections) | Greek scores, about 1870–1960 | Only page 1 shown online for songs still under copyright |
| [TEI Epirus bouzouki methods thesis](https://olympias.lib.uoi.gr/jspui/bitstream/teiep/394/1/lpm_000031.pdf) | Bouzouki teaching material | CC BY-NC-ND 3.0 |

Paid but reliable references: Carmelo Siciliano's books, Viktor's Tabs & Notations (Bandcamp,
PDF + MIDI per tune), learnbouzouki.gr scores, the Hal Leonard Greek Bouzouki Method, and
Fagotto's rebetika editions.

## YouTube

YouTube's terms forbid downloading videos, so these are for listening and checking, not for
training data.

* **Learn Bouzouki** ([@LearnBouzouki](https://www.youtube.com/@LearnBouzouki)): whole songs
  played with synchronised notation and tab on screen, mostly trichordo. The closest thing to
  audio paired with tab.
* **Greek Bouzouki Lessons, Sheet Music & Tab**
  ([@greekbouzoukilessonssheetm5777](https://www.youtube.com/@greekbouzoukilessonssheetm5777)),
  **bouzoukigr.com** lessons, and **zerlentes**: slow, clean lesson playing, with tab on screen
  for some.
* **Traditional kalamatiana lessons**, e.g. Psaropoula and Samiotissa: public-domain tunes we
  can write the tab for ourselves.
* **Solo taximia** (Tsitsanis, Papaioannou, Keromytis): no tab, but good for testing how the
  analysis copes with free, unaccompanied playing.

## Datasets

* **Lyra** ([github](https://github.com/pxaris/lyra-dataset)) **[checked]**: 1,570 Greek
  traditional pieces (about 80 h) with instrument labels per piece, not per note. Audio is
  YouTube timestamps. CC BY-NC-SA 4.0. Useful for finding bouzouki-heavy clips.
* **MTG makam data**
  ([otmm_audio_score_alignment_dataset](https://github.com/MTG/otmm_audio_score_alignment_dataset))
  **[checked]**: note onsets aligned by hand to instrumental recordings. CC BY-NC-SA; the audio
  comes through Dunya, which needs registration.
* **Sousta corpus** (Holzapfel & Benetos, ISMIR 2016): 40 Cretan tunes with scores aligned to
  the audio, available from KTH on request. The closest Greek example of note-level ground truth.
* **Single bouzouki notes:** Freesound's JustMog "Bouzouki" pack, and xserra's
  "bouzouki.wav" (CC BY 4.0). Licences are per sound.
* **Methods to copy:** GuitarSet (guitar audio with string and fret labels) and SynthTab (tab
  rendered to audio).

## Plan

1. **Test set (about 20 songs):** public-domain tunes we transcribe and record ourselves on
   tetrachordo and trichordo, so we own both the audio and the tab. Start from the
   ABC-archive melodies and Tountas' MuseScore score. Add Learn Bouzouki videos as by-ear
   checks. Score every change with the Lab app's `evalSet`.
2. **Training data:** our own recordings, plus public-domain and makam melodies rendered
   through bouzouki samples we have the rights to. Mix those into band backings to train a
   bouzouki separator and transcriber. Note the non-commercial terms on the makam data.
