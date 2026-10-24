// Batch Whisper ASR for qa.py: raw float32 16 kHz mono files -> transcripts. Local files only.
// node asr.mjs <@huggingface/transformers dist/transformers.node.mjs> <models root> <list.json> <out.json> [<lid.json>]
// list.json: [{ "id": "...", "f32": "/abs/file.f32", "lang": "greek" }]
// With <lid.json>, also Whisper's own language identification per clip (the softmax over its language tokens
// at the first decoding step, as Whisper does when no language is forced): { id: { el: 0.71, es: 0.12, ... } }.
import fs from 'node:fs';

const [entry, modelsRoot, listPath, outPath, lidPath] = process.argv.slice(2);
const T = await import(entry);
T.env.allowRemoteModels = false;
T.env.allowLocalModels = true;
T.env.localModelPath = modelsRoot.endsWith('/') ? modelsRoot : `${modelsRoot}/`;
T.env.useBrowserCache = false;
T.env.useFSCache = false;
const asr = await T.pipeline('automatic-speech-recognition', 'Xenova/whisper-small', { dtype: 'q8', device: 'cpu' });
const added = JSON.parse(fs.readFileSync(`${T.env.localModelPath}Xenova/whisper-small/added_tokens.json`, 'utf8'));
const LANGS = Object.entries(added).filter(([k, v]) => /^<\|[a-z]{2,3}\|>$/.test(k) && v >= 50259 && v <= 50357);
const SOT = BigInt(added['<|startoftranscript|>']);

async function languageId(audio) {
  const feats = await asr.processor(audio);
  const { logits } = await asr.model({ ...feats, decoder_input_ids: new T.Tensor('int64', BigInt64Array.from([SOT]), [1, 1]) });
  const last = logits.dims[1] - 1;
  const V = logits.dims[2];
  const at = (id) => logits.data[last * V + id];
  const mx = Math.max(...LANGS.map(([, id]) => at(id)));
  const p = LANGS.map(([k, id]) => [k.slice(2, -2), Math.exp(at(id) - mx)]);
  const z = p.reduce((s, [, v]) => s + v, 0);
  return Object.fromEntries(p.map(([k, v]) => [k, v / z]).sort((a, b) => b[1] - a[1]).slice(0, 8)
    .map(([k, v]) => [k, Math.round(v * 1e4) / 1e4]));
}

const out = {};
const lid = {};
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
  if (lidPath) {
    try {
      lid[it.id] = await languageId(audio);
    } catch (e) {
      console.error(`lid: ${it.id}: ${e.message}`);
      lid[it.id] = {};
    }
  }
}
fs.writeFileSync(outPath, JSON.stringify(out, null, 1));
if (lidPath) fs.writeFileSync(lidPath, JSON.stringify(lid, null, 1));
