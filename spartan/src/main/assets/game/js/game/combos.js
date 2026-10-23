/**
 * Combos (DESIGN.md §8). PURE: the COMBOS table and a ComboTracker that matches the suffix of
 * the player's recent attack tokens (dirToken for slashes, 'T' thrust, 'B' bash, 'J' throw).
 */
import { TOKEN_SETS } from './rules.js';

const { Rish, Lish, Uish, Dish } = TOKEN_SETS;
const one = (token) => new Set([token]);
const DR = one('DR');
const DL = one('DL');
const B = one('B');
const T = one('T');

function row(r) {
  r.bonus = Math.round(100 * r.mult);
  return Object.freeze(r);
}

/**
 * Rows; 'either order' combos are two rows with the same id. Each row has desc and bonus = round(100·mult).
 * Every gap between the tokens of a row must be within one of the tracker's windows (COMBO_WINDOWS):
 * `flow` rows (the alternating Twin/Fury/Storm) within `flow`, so slow right-left swiping is not a
 * combo; `slow` rows (the Cleave: uppercut, raise the blade overhead, chop) within the whole
 * `chain` window; every other row within `step`.
 */
export const COMBOS = [
  row({ id: 'twin', name: 'Twin Fangs', seq: [Rish, Lish], mult: 1.5, flow: true, desc: 'Slash right, then left.' }),
  row({ id: 'twin', name: 'Twin Fangs', seq: [Lish, Rish], mult: 1.5, flow: true, desc: 'Slash left, then right.' }),
  row({ id: 'fury', name: 'Fury of Lakedaimon', seq: [Rish, Lish, Rish], mult: 1.8, flow: true, effect: 'sweep', arc: 1.75, desc: 'Three alternating slashes: the last sweeps every foe before you.' }),
  row({ id: 'fury', name: 'Fury of Lakedaimon', seq: [Lish, Rish, Lish], mult: 1.8, flow: true, effect: 'sweep', arc: 1.75, desc: 'Three alternating slashes: the last sweeps every foe before you.' }),
  row({ id: 'storm', name: 'Storm of Bronze', seq: [Rish, Lish, Rish, Lish], mult: 2.4, flow: true, effect: 'sweep', arc: 1.75, slowMo: [0.5, 0.4], desc: 'Four alternating slashes in a storm of bronze.' }),
  row({ id: 'storm', name: 'Storm of Bronze', seq: [Lish, Rish, Lish, Rish], mult: 2.4, flow: true, effect: 'sweep', arc: 1.75, slowMo: [0.5, 0.4], desc: 'Four alternating slashes in a storm of bronze.' }),
  row({ id: 'cleave', name: "Leonidas' Cleave", seq: [Uish, Dish], mult: 2.2, slow: true, breakGuard: true, slowMo: [0.35, 0.5], desc: 'An uppercut, then an overhead chop that splits any guard.' }),
  row({ id: 'xcut', name: 'Cross of Ares', seq: [DR, DL], mult: 1.7, bleed: { dps: 6, dur: 3 }, desc: 'Two crossing diagonal downward cuts. The wound bleeds.' }),
  row({ id: 'xcut', name: 'Cross of Ares', seq: [DL, DR], mult: 1.7, bleed: { dps: 6, dur: 3 }, desc: 'Two crossing diagonal downward cuts. The wound bleeds.' }),
  row({ id: 'answer', name: "The Hoplite's Answer", seq: [B, T], mult: 1.3, effect: 'execute', desc: 'Bash, then thrust: a staggered foe is finished.' }),
  row({ id: 'drive', name: 'Phalanx Drive', seq: [T, T, T], mult: 1.6, knockback: 2.5, desc: 'Three thrusts drive the foe back.' }),
  row({ id: 'wall', name: 'Shield Wall', seq: [B, B], mult: 1.0, effect: 'stagger_all', radius: 2.5, desc: 'Two bashes: everyone around you staggers.' }),
];

/**
 * Seconds between consecutive attack starts.
 * * `chain`: a token more than this after the previous one starts a new chain. It is also the gap
 *   limit inside a `slow` row.
 * * `flow`: the gap limit inside a `flow` row.
 * * `step`: the gap limit inside any other row.
 *
 * Motion: a strike with its return to guard takes ~1.5 s, so flow and step are 1.7 s. The Cleave as
 * the Agoge teaches it (uppercut, raise the blade overhead, chop) starts its chop ~2.5 s after the
 * uppercut, so the chain window is 2.6 s, which only the Cleave uses: two other swings 2.5 s apart
 * are no combo. Touch: every non-flow row keeps the 1.25 s chain window.
 */
export const COMBO_WINDOWS = Object.freeze({
  touch: Object.freeze({ chain: 1.25, flow: 0.6, step: 1.25 }),
  motion: Object.freeze({ chain: 2.6, flow: 1.7, step: 1.7 }),
});

const MAX_CHAIN = 6;

function specificity(seq) {
  let n = 0;
  for (let i = 0; i < seq.length; i++) n += seq[i].size;
  return n;
}

// Do the first n steps of seq match tokens[start .. start+n)?
function matchesAt(seq, n, tokens, start) {
  for (let i = 0; i < n; i++) if (!seq[i].has(tokens[start + i])) return false;
  return true;
}

/**
 * Matches combo rows against the suffix of the token chain.
 * * A token more than `window` seconds after the previous one starts a new chain.
 * * A `flow` row matches only if each gap between its tokens is ≤ `flow` seconds; a `slow` row
 *   needs only the chain window; any other row needs each gap ≤ `step` seconds.
 * * The longest matching row wins; ties go to the more specific row (smaller summed step-set size).
 * * After a match of length n the chain is cleared unless a longer row starts with those n tokens.
 */
export class ComboTracker {
  constructor(table = COMBOS, window = 1.25, flow = Infinity, step = Infinity) {
    this.table = table;
    this.window = window;
    this.flow = flow;
    this.step = step;
    this.tokens = [];
    this.times = [];
    this.lastT = -Infinity;
    this.spec = table.map((r) => specificity(r.seq));
  }

  /** Sets the windows (seconds), e.g. COMBO_WINDOWS.touch or .motion; omitted fields are kept. */
  setWindows({ chain, flow, step } = {}) {
    if (chain > 0) this.window = chain;
    if (flow > 0) this.flow = flow;
    if (step > 0) this.step = step;
  }

  // Are the last n tokens each within `limit` seconds of the one before?
  gapsWithin(n, limit) {
    if (limit === Infinity) return true;
    const times = this.times;
    for (let i = times.length - n + 1; i < times.length; i++) if (times[i] - times[i - 1] > limit + 1e-9) return false;
    return true;
  }

  /** Appends a token at time t (seconds) and returns the completed row, or null. */
  push(token, t) {
    const tokens = this.tokens;
    const times = this.times;
    if (t - this.lastT > this.window || t < this.lastT - 1e-6) {
      tokens.length = 0;
      times.length = 0;
    }
    this.lastT = t;
    tokens.push(token);
    times.push(t);
    if (tokens.length > MAX_CHAIN) {
      tokens.shift();
      times.shift();
    }

    let best = -1;
    let bestLen = 0;
    for (let i = 0; i < this.table.length; i++) {
      const n = this.table[i].seq.length;
      if (n > tokens.length || n < bestLen) continue;
      if (!matchesAt(this.table[i].seq, n, tokens, tokens.length - n)) continue;
      const r = this.table[i];
      if (!this.gapsWithin(n, r.flow ? this.flow : r.slow ? Infinity : this.step)) continue;
      if (n > bestLen || this.spec[i] < this.spec[best]) {
        best = i;
        bestLen = n;
      }
    }
    if (best < 0) return null;

    const start = tokens.length - bestLen;
    let extendable = false;
    for (let i = 0; i < this.table.length; i++) {
      const seq = this.table[i].seq;
      if (seq.length > bestLen && matchesAt(seq, bestLen, tokens, start)) {
        extendable = true;
        break;
      }
    }
    if (!extendable) {
      tokens.length = 0;
      times.length = 0;
    }
    return this.table[best];
  }

  reset() {
    this.tokens.length = 0;
    this.times.length = 0;
    this.lastT = -Infinity;
  }

  /** The current token chain, oldest first (read-only view). */
  get chain() {
    return this.tokens;
  }
}
