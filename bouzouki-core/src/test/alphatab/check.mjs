// Parses an alphaTex file with alphaTab and prints, as JSON, each bar's length (in whole notes) and every struck note.
import * as alphaTab from '@coderline/alphatab';
import fs from 'node:fs';
const imp = new alphaTab.importer.AlphaTexImporter();
imp.initFromString(fs.readFileSync(process.argv[2], 'utf8'), new alphaTab.Settings());
const score = imp.readScore();
const st = score.tracks[0].staves[0];
const bars = st.bars.map(b => b.voices[0].beats.reduce((s, be) => s + (1 / be.duration) * (be.dots === 1 ? 1.5 : 1), 0));
const notes = [];
// A tied note's hammer-on or slide (its chain's last piece leads on) counts for the note struck at its start.
for (const b of st.bars) for (const be of b.voices[0].beats) for (const n of be.notes) {
  if (!n.isTieDestination) notes.push([n.string, n.fret, n.isHammerPullOrigin ? 1 : 0, n.slideOutType, be.tremoloSpeed ? 1 : 0]);
  else if (notes.length) { const last = notes[notes.length - 1]; if (n.isHammerPullOrigin) last[2] = 1; if (n.slideOutType) last[3] = n.slideOutType; }
}
console.log(JSON.stringify({ tuning: st.tuning, tempo: score.tempo, bars, notes }));
