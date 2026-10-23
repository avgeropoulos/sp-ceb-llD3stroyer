/**
 * Picks recorded voice lines from the bank (DESIGN.md §11 audio/audio.js, §13 voice lines). PURE.
 *
 * A bank maps each line key to its variants: [{ greek, en, clips: { <Speaker>: { file, dur } } }].
 * Variants say the same thing in different words, so frequent barks don't repeat; each speaker
 * cycles through the variants he has a clip for.
 */

/** Number of variants of `key` that `speaker` has a clip for. */
export function variantCount(bank, key, speaker) {
  const list = bank && bank[key];
  if (!Array.isArray(list)) return 0;
  let n = 0;
  for (const v of list) if (v && v.clips && v.clips[speaker]) n++;
  return n;
}

/**
 * What `speaker` says the `n`-th time (0, 1, …) the line `key` is used: round-robin over the
 * variants with a clip for him. → { greek, en, file, dur }, or null if he has none.
 */
export function pickVoiceLine(bank, key, speaker, n = 0) {
  const count = variantCount(bank, key, speaker);
  if (!count) return null;
  const i = Number.isFinite(n) ? Math.floor(n) : 0;
  let k = ((i % count) + count) % count;
  for (const v of bank[key]) {
    const c = v && v.clips && v.clips[speaker];
    if (!c || k-- > 0) continue;
    return { greek: v.greek || '', en: v.en || '', file: c.file, dur: c.dur };
  }
  return null;
}

/** Clip files of every variant of `keys` spoken by any of `speakers`, without repeats (for preloading). */
export function clipFiles(bank, keys, speakers) {
  const out = [];
  for (const key of keys) {
    const list = bank && bank[key];
    if (!Array.isArray(list)) continue;
    for (const v of list) {
      for (const sp of speakers) {
        const c = v && v.clips && v.clips[sp];
        if (c && c.file && !out.includes(c.file)) out.push(c.file);
      }
    }
  }
  return out;
}
