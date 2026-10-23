// Batch Whisper ASR for qa.py: raw float32 16 kHz mono files -> transcripts. Local files only.
// node asr.mjs <@huggingface/transformers dist/transformers.node.mjs> <models root> <list.json> <out.json>
// list.json: [{ "id": "...", "f32": "/abs/file.f32", "lang": "greek" }]
import fs from 'node:fs';

const [entry, modelsRoot, listPath, outPath] = process.argv.slice(2);
const T = await import(entry);
T.env.allowRemoteModels = false;
T.env.allowLocalModels = true;
T.env.localModelPath = modelsRoot.endsWith('/') ? modelsRoot : `${modelsRoot}/`;
T.env.useBrowserCache = false;
T.env.useFSCache = false;
const asr = await T.pipeline('automatic-speech-recognition', 'Xenova/whisper-small', { dtype: 'q8', device: 'cpu' });
const out = {};
for (const it of JSON.parse(fs.readFileSync(listPath, 'utf8'))) {
  const buf = fs.readFileSync(it.f32);
  const audio = new Float32Array(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));
  try {
    const r = await asr(audio, { language: it.lang || 'greek', task: 'transcribe', num_beams: 1, max_new_tokens: 96 });
    out[it.id] = (r.text || '').trim();
  } catch (e) {
    // transformers.js throws when Whisper emits no text tokens at all; score that clip as an empty transcript
    console.error(`asr: ${it.id}: ${e.message}`);
    out[it.id] = '';
  }
}
fs.writeFileSync(outPath, JSON.stringify(out, null, 1));
