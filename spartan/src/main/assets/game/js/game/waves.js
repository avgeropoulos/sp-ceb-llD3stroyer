/**
 * The campaign state machine (DESIGN.md §11 game/waves.js, data in campaign.js per §13):
 * intro → fighting → respite → … → dayEnd → next day … → Day 3 phases A, B, C → over.
 *
 * Timers run on gdt only. stop() cancels everything without emitting anything.
 */
import {
  DAYS, VOICE, LEONIDAS_BATTLE_LINES, MELEE_KINDS, RESPITE, SPAWN, CARD_SECONDS, GAME_OVER_DELAY,
  ALLY_SLOTS, firstEntryOfDay, meleeCount,
} from './campaign.js';
import { VOICEBANK } from '../audio/voicebank.js';
import { pickVoiceLine, clipFiles } from '../audio/voicelines.js';

const WAVE_CARD_SECONDS = 3;
const CLEARED_SECONDS = 3;
const VOICE_LEAD = 1.2;           // first intro line after the card appears
const BEHIND_CARD = 1.0;          // world changes wait until the card covers the view
const PHASE_GAP = 3;              // pause between Day 3 phases
const ENDLESS_WAVE_SECONDS = 25;  // phase C: the director's wave count rises this often
const SPAWN_CLEARANCE = 1.3;
const SHIELDS_UP_EVERY = 12;      // 'Shields up!' is spoken at most this often (s)
const VANGUARD_STAGGER = 0.3;     // the pre-placed front rank sets out one man at a time (s)
const VOICE_GAP = 0.35;           // a breath after a recorded line before the next one (s)
const PHASE_VOICE_LEAD = 0.6;     // a phase's first line follows its fade (s)
const RESPITE_VOICE_AT = 1.0;     // the praise after a wave, into the respite (s)

/** Rough spoken length of a line, so lines don't cut each other off (TTS uses QUEUE_FLUSH). */
export function voiceSeconds(text) {
  return 0.7 + 0.062 * text.length;
}

/**
 * Seconds a line holds the voice queue: its recorded clip and a breath, else the TTS estimate.
 * `clips` false: recorded lines can't play here (audio.canPlayClips()), so the English is spoken
 * instead and gets at least its own length.
 */
export function lineSeconds(line, clips = true) {
  const d = line.clip ? Number(line.clip.dur) : NaN;
  if (!(d > 0)) return voiceSeconds(line.text);
  return clips ? d + VOICE_GAP : Math.max(d + VOICE_GAP, voiceSeconds(line.text));
}

/**
 * @param {object} ctx shared context (§4)
 */
export function createWaves(ctx) {
  const events = ctx.events;
  const rng = ctx.rng;

  let running = false;
  let state = 'over';
  let entryIdx = 0;
  let phaseIdx = -1;
  let waveIdx = -1;
  let day = 1;
  let dayWave = 0;          // waves started today (0-based index of the current one)
  let wavesToday = 0;
  // Every timer lives in this object: doubles in closure variables would be boxed per frame.
  const T = {
    clock: 0.5,             // sum of gdt while running
    stateT: 0.5,            // time in the current state
    timer: 0.5,             // countdown for intro / respite / dayEnd
    fightT: 0.5,
    spawnT: 0.5,
    archerT: 0.5,
    nextVolley: 0.5,
    volleyEvery: 0.5,
    voiceBusy: 0.5,
    shieldsUpAt: -1.5,
    deathT: -1.5,
  };
  for (const k in T) T[k] = k === 'deathT' ? -1 : k === 'shieldsUpAt' ? -1e9 : 0;
  let lull = false;
  let readyRequested = false;
  let afterTimer = null;    // what the timer leads to: 'wave' | 'phase' | 'entry' | 'endless'
  let refillAllowed = true;
  let leonidasFallen = false;

  // Fighting.
  let wave = null;
  let plan = [];
  let planIdx = 0;
  let preparedWave = -1;    // index of the next wave whose plan is already built (and partly spawned)
  const vanguard = [];      // Day 1: the front rank standing during the intro, released at waveStart
  let endless = false;
  let archersLeft = 0;
  let volleyN = 0;
  let campaignVolleys = 0;
  let holdLineSaid = false;
  let tegeansSaid = false;
  let respitesSeen = 0;     // respites and lulls between surges; every other one gets a word of praise
  const voiceUses = new Map(); // key → times said this run (picks the next variant)

  const timeline = [];      // { at, fn }
  const voiceQ = [];        // line objects

  // ---------------------------------------------------------------------------------------

  function entry() {
    return DAYS[entryIdx];
  }
  function phaseDef() {
    const e = entry();
    return e && e.phases && phaseIdx >= 0 ? e.phases[phaseIdx] : null;
  }
  function waveList() {
    const p = phaseDef();
    return p ? p.waves || null : entry().waves;
  }
  function phaseId() {
    const p = phaseDef();
    return p ? p.id : null;
  }

  function later(delay, fn) {
    timeline.push({ at: T.clock + delay, fn });
  }

  function card(name, title, subtitle, seconds, waveNum) {
    events.emit(name, { day, phase: phaseId(), wave: waveNum, title, subtitle, seconds });
  }

  /** Who says `key` now: Dienekes takes Leonidas' battle lines once the king has fallen. */
  function speakerFor(key) {
    const sp = VOICE[key].speaker;
    return leonidasFallen && sp === 'Leonidas' && LEONIDAS_BATTLE_LINES.includes(key) ? 'Dienekes' : sp;
  }

  /**
   * The `n`-th saying of `key`: the speaker's next recorded variant (round-robin), with its Greek
   * and English; without a clip, the VOICE text for text-to-speech.
   */
  function lineFor(key, n) {
    const src = VOICE[key];
    const speaker = speakerFor(key);
    const v = pickVoiceLine(VOICEBANK, key, speaker, n);
    return {
      key,
      n,
      speaker,
      text: (v && v.en) || src.text,
      greek: (v && v.greek) || src.greek,
      clip: v && v.file ? { file: v.file, dur: v.dur } : null,
    };
  }

  /** Queues a line. `now` speaks it at once, cutting off whatever is being said. */
  function say(key, now = false) {
    if (!VOICE[key]) return;
    if (!now && key === 'holdTheLine' && voiceQ.length > 0) return; // not worth waiting for
    const n = voiceUses.get(key) || 0;
    voiceUses.set(key, n + 1);
    const line = lineFor(key, n);
    if (now) speak(line);
    else voiceQ.push(line);
  }

  function speak(line) {
    // A battle line still queued when the king fell is Dienekes' now.
    if (line.speaker !== speakerFor(line.key)) line = lineFor(line.key, line.n);
    const payload = { key: line.key, text: line.text, speaker: line.speaker };
    if (line.greek) payload.greek = line.greek;
    if (line.clip) payload.clip = line.clip;
    events.emit('voice', payload);
    T.voiceBusy = lineSeconds(line, clipsPlay());
  }

  /** Whether audio can play recorded lines (else they fall back to English TTS, timed by the text). */
  function clipsPlay() {
    const a = ctx.audio;
    return !a || typeof a.canPlayClips !== 'function' || a.canPlayClips();
  }

  /** Schedules `keys` to be said from `lead` s on; returns when the last will have ended (s). */
  function scheduleLines(keys, lead) {
    let end = lead;
    for (const key of keys || []) {
      if (!VOICE[key]) continue;
      later(lead, () => say(key));
      end += lineSeconds(lineFor(key, voiceUses.get(key) || 0), clipsPlay());
    }
    return end;
  }

  /** Asks audio to decode, ahead of need, the clips this part of the campaign will use. */
  function prefetch(e) {
    const a = ctx.audio;
    if (!a || typeof a.preloadVoice !== 'function') return;
    const files = [];
    const add = (key, speaker) => {
      for (const f of clipFiles(VOICEBANK, [key], [speaker])) if (!files.includes(f)) files.push(f);
    };
    const kingFalls = !!(e.phases && e.phases.some((p) => p.killAlly === 'Leonidas'));
    for (const key of e.intro || []) add(key, VOICE[key] ? VOICE[key].speaker : '');
    for (const key of LEONIDAS_BATTLE_LINES) {
      add(key, 'Leonidas');
      if (kingFalls) add(key, 'Dienekes');
    }
    if (campaignVolleys === 0 && (e.phases || (e.waves || []).some((w) => w.volleyEvery > 0))) {
      add('arrowsHideSun', VOICE.arrowsHideSun.speaker);
      add('fightInShade', VOICE.fightInShade.speaker);
    }
    for (const p of e.phases || []) {
      if (p.noRefill) add('swords', VOICE.swords.speaker);
      for (const key of p.intro || []) add(key, VOICE[key] ? VOICE[key].speaker : '');
    }
    a.preloadVoice(files);
  }

  function pumpVoice(gdt) {
    T.voiceBusy -= gdt;
    if (T.voiceBusy > 0 || voiceQ.length === 0) return;
    speak(voiceQ.shift());
  }

  function setTime(t) {
    if (t && ctx.world && ctx.world.setTimeOfDay) ctx.world.setTimeOfDay(t);
  }
  function music(name) {
    if (ctx.audio && ctx.audio.setMusic) ctx.audio.setMusic(name);
  }
  function heal(fraction) {
    const p = ctx.player;
    if (p && p.heal && fraction > 0) p.heal((p.maxHp || 100) * fraction);
  }
  /** Full bundle with no argument; else tops up to `total` spears (never takes any away). */
  function refill(total) {
    const p = ctx.player;
    if (!refillAllowed || !p || !p.refillSpears) return;
    if (!(total > 0)) p.refillSpears();
    else if (!(p.spearsLeft >= total)) p.refillSpears(total);
  }
  function motion() {
    return !!(ctx.settings && ctx.settings.control === 'motion');
  }

  // ---------------------------------------------------------------------------------------
  // Days and phases

  function beginEntry(i, first) {
    const e = DAYS[i];
    const newDay = first || e.day !== day;
    entryIdx = i;
    phaseIdx = -1;
    waveIdx = -1;
    preparedWave = -1;
    vanguard.length = 0;
    day = e.day;
    if (newDay) {
      wavesToday = 0;
      dayWave = 0;
    }
    state = 'intro';
    T.stateT = 0;
    lull = false;
    music(day === 3 ? 'lastStand' : 'battle');
    card('dayStart', e.title, e.subtitle, CARD_SECONDS, 0);
    prefetch(e);
    // dayStart heals, refills and restores the line; world changes happen behind the card.
    T.timer = Math.max(CARD_SECONDS + 1, scheduleLines(e.intro, VOICE_LEAD) + 1.2);
    afterTimer = e.phases ? 'phase' : 'wave';
    const restore = () => {
      setTime(e.time);
      if (!first && newDay && ctx.ai && ctx.ai.clearCorpses) ctx.ai.clearCorpses();
      if (ctx.ai) ctx.ai.ensureAllies(ALLY_SLOTS);
      if (e.vanguard && e.waves) placeVanguard(e.vanguard, e.waves[0]);
    };
    if (first) restore();
    else later(BEHIND_CARD, restore);
    heal(1);
    refill();
  }

  /**
   * The herald's audience: the first `count` men of the entry's first wave stand in a rank at
   * z ≈ `z` through the intro and set out at waveStart (they're the start of that wave's plan).
   */
  function placeVanguard(v, w) {
    const ai = ctx.ai;
    if (!ai || !w) return;
    buildPlan(w);
    preparedWave = 0;
    const n = Math.min(v.count | 0, plan.length, w.alive || 0);
    for (let k = 0; k < n; k++) {
      const x = (k - (n - 1) / 2) * 1.5 + 0.3 + (rng.next() - 0.5) * 0.4;
      const z = v.z + (k % 2) * 0.6 + (rng.next() - 0.5) * 0.4;
      // Idle until the wave starts (the timer still covers the rest of the intro).
      const a = ai.spawnEnemy(plan[planIdx++], x, z, { facing: 0, delay: Math.max(0.05, T.timer) + k * VANGUARD_STAGGER });
      if (a) vanguard.push(a);
    }
  }

  /** waveStart: the vanguard sets out now, whatever is left of its intro delay. */
  function releaseVanguard() {
    for (let k = 0; k < vanguard.length; k++) {
      const a = vanguard[k];
      const d = 0.05 + k * VANGUARD_STAGGER;
      if (a && a.alive && a.state === 'idle' && a.delay > d) a.delay = d;
    }
    vanguard.length = 0;
  }

  function beginPhase(k) {
    const e = entry();
    const p = e.phases[k];
    phaseIdx = k;
    waveIdx = -1;
    preparedWave = -1;
    state = 'intro';
    T.stateT = 0;
    lull = false;
    card('phase', p.title, p.subtitle, CARD_SECONDS + (p.fade || 0), 0);
    if (k === 0 && e.battleTime) later(0.8, () => setTime(e.battleTime));
    if (p.killAlly) {
      later(1.0, () => {
        if (ctx.ai && ctx.ai.killAlly) ctx.ai.killAlly(p.killAlly);
        if (p.killAlly === 'Leonidas') leonidasFallen = true;
      });
    }
    if (p.noRefill) {
      // 7.224: the spears are broken. One stays in hand; the bundle is gone.
      refillAllowed = false;
      later(2.4, () => {
        say('swords');
        ctx.player?.dropSpears?.({ keepInHand: true });
      });
    }
    if (p.swordOnly) {
      // 7.225: the Kolonos is fought with swords.
      refillAllowed = false;
      ctx.player?.dropSpears?.({ keepInHand: false });
    }
    T.timer = (p.fade || 0) + CARD_SECONDS - 1;
    // Phase C: Dienekes calls them back to the hillock (7.225) as the fade ends.
    if (p.intro) T.timer = Math.max(T.timer, scheduleLines(p.intro, (p.fade || 0) + PHASE_VOICE_LEAD) + 0.3);
    afterTimer = p.endless ? 'endless' : 'wave';
  }

  function beginDayEnd() {
    const e = entry();
    state = 'dayEnd';
    T.stateT = 0;
    lull = false;
    if (e.endCard) {
      later(CLEARED_SECONDS, () => {
        if (ctx.hud && ctx.hud.banner) ctx.hud.banner(e.endCard, e.endSource || '', 7);
      });
      T.timer = CLEARED_SECONDS + 8;
    } else {
      T.timer = CLEARED_SECONDS + 2;
    }
    afterTimer = 'entry';
  }

  // ---------------------------------------------------------------------------------------
  // Waves

  function buildPlan(w) {
    plan.length = 0;
    for (const k of MELEE_KINDS) {
      if (k === 'captain') continue;
      for (let i = 0; i < (w[k] || 0); i++) plan.push(k);
    }
    // Shuffle, then put any captain in the second half so he arrives with the press.
    for (let i = plan.length - 1; i > 0; i--) {
      const j = Math.floor(rng.next() * (i + 1));
      const t = plan[i];
      plan[i] = plan[j];
      plan[j] = t;
    }
    for (let c = 0; c < (w.captain || 0); c++) {
      const at = Math.floor(plan.length * (0.5 + rng.next() * 0.35));
      plan.splice(at, 0, 'captain');
    }
    planIdx = 0;
  }

  function startWave(k) {
    const list = waveList();
    const w = list[k];
    waveIdx = k;
    wave = w;
    endless = false;
    const prepared = preparedWave === k;
    if (!prepared) buildPlan(w);
    preparedWave = -1;
    beginFighting(w, prepared);
    releaseVanguard();
    const p = phaseDef();
    const title = p && p.id === 'B' ? `Surge ${k + 1} of ${list.length}` : `Wave ${k + 1} of ${list.length}`;
    card('waveStart', title, w.subtitle || '', WAVE_CARD_SECONDS, k + 1);
    say('warcry');
  }

  function startEndless() {
    const p = phaseDef();
    waveIdx = 0;
    wave = p.endlessWave;
    endless = true;
    plan.length = 0;
    planIdx = 0;
    beginFighting(wave);
    T.nextVolley = p.volley.first;
    T.volleyEvery = p.volley.start;
    card('waveStart', 'The last stand', 'Sell your lives dearly', WAVE_CARD_SECONDS, 1);
    say('warcry');
  }

  /** `keepSpawnClock`: the wave's first men already set out during the respite; keep their cadence. */
  function beginFighting(w, keepSpawnClock = false) {
    state = 'fighting';
    T.stateT = 0;
    T.fightT = 0;
    lull = false;
    archersLeft = w.archer || 0;
    if (!keepSpawnClock) T.spawnT = 0.6;
    T.archerT = 2.5;
    T.volleyEvery = w.volleyEvery || 0;
    T.nextVolley = T.volleyEvery > 0 ? SPAWN.firstVolley + rng.next() * 3 : Infinity;
    volleyN = 0;
    holdLineSaid = false;
    dayWave = wavesToday++;
  }

  function spawnPoint(behind, archer, out) {
    const area = behind ? SPAWN.behind : archer && SPAWN.archer ? SPAWN.archer : SPAWN.front;
    const actors = ctx.actors || [];
    let bestX = 0, bestZ = 0, bestD = -1;
    for (let tries = 0; tries < 6; tries++) {
      const x = area.x[0] + rng.next() * (area.x[1] - area.x[0]);
      const z = area.z[0] + rng.next() * (area.z[1] - area.z[0]);
      let dmin = Infinity;
      for (let i = 0; i < actors.length; i++) {
        const a = actors[i];
        if (!a.alive) continue;
        const d = Math.hypot(a.pos.x - x, a.pos.z - z);
        if (d < dmin) dmin = d;
      }
      if (dmin > bestD) { bestD = dmin; bestX = x; bestZ = z; }
      if (dmin >= SPAWN_CLEARANCE) break;
    }
    out.x = bestX;
    out.z = bestZ;
    return out;
  }

  const spot = { x: 0, z: 0 };
  function spawn(kind, behind) {
    const ai = ctx.ai;
    if (!ai) return;
    spawnPoint(behind, kind === 'archer', spot);
    ai.spawnEnemy(kind, spot.x, spot.z, behind ? { fromBehind: true } : undefined);
  }

  function nextEndlessKind() {
    return rng.next() < wave.levy ? 'levy' : 'immortal';
  }

  function updateFighting(gdt) {
    const ai = ctx.ai;
    T.fightT += gdt;
    const alive = ai ? ai.enemiesAlive : 0;

    T.spawnT -= gdt;
    if (T.spawnT <= 0 && alive < wave.alive && (endless || planIdx < plan.length)) {
      if (endless) {
        // Phase C: the front, and the Immortals over the mountain through the wall gap.
        const behind = rng.next() < wave.behindShare;
        spawn(behind ? 'immortal' : nextEndlessKind(), behind);
      } else {
        spawn(plan[planIdx++], false);
      }
      T.spawnT = SPAWN.interval[0] + rng.next() * (SPAWN.interval[1] - SPAWN.interval[0]);
    }
    if (archersLeft > 0) {
      T.archerT -= gdt;
      if (T.archerT <= 0) {
        spawn('archer', false);
        archersLeft--;
        T.archerT = SPAWN.interval[0] + rng.next() * (SPAWN.interval[1] - SPAWN.interval[0]);
      }
    }
    if (endless) dayWave = wavesToday - 1 + Math.floor(T.fightT / ENDLESS_WAVE_SECONDS);

    T.nextVolley -= gdt;
    if (T.nextVolley <= 1e-6) fireVolley();

    if (!holdLineSaid && T.fightT > 5 && ai && ai.playerAttackers && ai.playerAttackers.length > 0) {
      holdLineSaid = true;
      say('holdTheLine');
    }

    if (!endless && planIdx >= plan.length && ai && ai.enemiesAlive === 0) waveCleared();
  }

  /**
   * Living archers of the current wave (on the firing line or still to come), or -1 if the wave
   * has none or ai can't tell: then volleys run on the clock alone (Day 3 phase A, phase C).
   */
  function archersStanding() {
    const ai = ctx.ai;
    if (endless || !wave || !(wave.archer > 0) || !ai) return -1;
    const n = ai.archersShooting ?? ai.archersAlive;
    return Number.isFinite(n) ? n + archersLeft : -1;
  }

  function fireVolley() {
    const p = phaseDef();
    const kolonos = endless && p && p.volley;
    // Kill the wave's archers and its volleys stop; each archer down thins them.
    const standing = archersStanding();
    if (standing === 0) {
      T.nextVolley = Infinity;
      return;
    }
    volleyN++;
    const first = campaignVolleys === 0;
    campaignVolleys++;
    // Phase C: every other volley comes from behind, over the wall, and can't be blocked.
    const fromBehind = !!kolonos && volleyN % 2 === 0;
    let count = fromBehind ? 100 : day === 3 ? 360 : 300;
    if (standing > 0 && standing < wave.archer) count = Math.max(100, Math.round((count * standing) / wave.archer));
    if (ctx.combat && ctx.combat.volley) ctx.combat.volley({ count, duration: 1.0, warn: 2.5, fromBehind });
    if (T.clock - T.shieldsUpAt >= SHIELDS_UP_EVERY - 1e-6) {
      T.shieldsUpAt = T.clock;
      say('shieldsUp', true);
    }
    if (first) {
      say('arrowsHideSun');
      say('fightInShade');
    }
    // Carry the frame's overshoot so the cadence stays exact.
    if (kolonos) {
      T.nextVolley += T.volleyEvery;
      T.volleyEvery = Math.max(p.volley.min, T.volleyEvery - p.volley.step);
    } else {
      T.nextVolley = T.volleyEvery > 0 ? T.nextVolley + T.volleyEvery : Infinity;
    }
  }

  function waveCleared() {
    const list = waveList();
    const p = phaseDef();
    const n = waveIdx + 1;
    T.nextVolley = Infinity;
    if (ctx.ai) ctx.ai.retreatArchers();
    if (p && p.id === 'B') card('waveCleared', `Driven back (${n}/${list.length})`, 'Four times they drove the Persians back', CLEARED_SECONDS, n);
    else card('waveCleared', 'They fall back', `Wave ${n} of ${list.length}`, CLEARED_SECONDS, n);

    if (waveIdx + 1 < list.length) {
      if (p && p.lull) {
        beginLull(p.lull, 'wave');
        praise();
      } else {
        beginRespite();
      }
    } else if (entry().phases && phaseIdx + 1 < entry().phases.length) {
      beginLull(PHASE_GAP, 'phase');
    } else {
      beginDayEnd();
    }
  }

  function beginLull(seconds, next) {
    state = 'respite';
    T.stateT = 0;
    lull = true;
    readyRequested = false;
    T.timer = seconds;
    afterTimer = next;
  }

  /** Every other respite (or lull between surges), the king, or Dienekes after him, has a word for the line. */
  function praise() {
    respitesSeen++;
    if (respitesSeen % 2 === 1) later(RESPITE_VOICE_AT, () => say('respite'));
  }

  function beginRespite() {
    const p = phaseDef();
    const cfg = p && p.respite
      ? p.respite
      : { seconds: motion() ? RESPITE.motion : RESPITE.touch, heal: RESPITE.heal, refill: true, helot: true, allies: day < 3 };
    state = 'respite';
    T.stateT = 0;
    lull = false;
    readyRequested = false;
    T.timer = cfg.seconds;
    afterTimer = 'wave';
    heal(cfg.heal);
    if (cfg.refill) refill(RESPITE.spears);
    if (cfg.helot && ctx.ai) ctx.ai.spawnHelot();
    if (cfg.allies && ctx.ai) ctx.ai.ensureAllies(ALLY_SLOTS, { walkIn: true });
    const sub = cfg.refill && refillAllowed ? 'Bind your wounds. Take up fresh spears.' : 'Bind your wounds.';
    card('respite', 'Respite', sub, T.timer, waveIdx + 1);
    praise();
    if (day === 2 && !tegeansSaid) {
      tegeansSaid = true;
      later(1.5, () => say('tegeans'));
    }
  }

  function timerDone() {
    const next = afterTimer;
    afterTimer = null;
    if (next === 'wave') {
      const p = phaseDef();
      if (entry().phases && !p) beginPhase(0);
      else startWave(waveIdx + 1);
    } else if (next === 'phase') {
      beginPhase(phaseIdx + 1);
    } else if (next === 'endless') {
      startEndless();
    } else if (next === 'entry') {
      if (entryIdx + 1 < DAYS.length) beginEntry(entryIdx + 1, false);
      else { state = 'over'; running = false; }
    }
  }

  // ---------------------------------------------------------------------------------------

  function onPlayerDeath() {
    if (!running || T.deathT >= 0) return;
    T.deathT = GAME_OVER_DELAY;
    voiceQ.length = 0;
    timeline.length = 0;
    T.nextVolley = Infinity;
  }
  events.on('playerDeath', onPlayerDeath);
  events.on('ready', () => ready());

  function update(gdt) {
    if (!running || !(gdt > 0)) return;
    T.clock += gdt;
    T.stateT += gdt;

    if (T.deathT >= 0) {
      T.deathT -= gdt;
      if (T.deathT <= 0) {
        T.deathT = -1;
        running = false;
        state = 'over';
        const p = ctx.player;
        events.emit('gameOver', { day, wave: Math.max(0, waveIdx + 1), kills: p ? p.kills || 0 : 0, score: p ? p.score || 0 : 0 });
      }
      return;
    }

    // Scheduled actions (in order; one may schedule another).
    for (let i = 0; i < timeline.length; i++) {
      const ev = timeline[i];
      if (ev.at > T.clock) continue;
      timeline.splice(i, 1);
      i--;
      ev.fn();
      if (!running) return;
    }
    pumpVoice(gdt);

    if (state === 'fighting') {
      updateFighting(gdt);
    } else if (state === 'intro' || state === 'respite' || state === 'dayEnd') {
      T.timer -= gdt;
      if (state === 'respite' && readyRequested && T.stateT >= RESPITE.readyAfter) T.timer = 0;
      if (T.timer <= 0) timerDone();
      else if (state === 'respite' && !lull && afterTimer === 'wave' && T.timer <= SPAWN.respiteLead) prespawn(gdt);
    }
  }

  /** The last seconds of a respite: the next wave's first men set out from the Persian line. */
  function prespawn(gdt) {
    const ai = ctx.ai;
    const list = waveList();
    const k = waveIdx + 1;
    if (!ai || !list || k >= list.length) return;
    const w = list[k];
    if (preparedWave !== k) {
      buildPlan(w);
      preparedWave = k;
      T.spawnT = 0;
    }
    T.spawnT -= gdt;
    if (T.spawnT <= 0 && planIdx < plan.length && ai.enemiesAlive < w.alive) {
      spawn(plan[planIdx++], false);
      T.spawnT = SPAWN.interval[0] + rng.next() * (SPAWN.interval[1] - SPAWN.interval[0]);
    }
  }

  /**
   * Starts the campaign at `day` (1–3), at its `entry`-th part (0 = the day's first entry, 1 =
   * Day 1 dusk; out-of-range values fall back to the day's first entry).
   */
  function start(d = 1, entry = 0) {
    stop();
    running = true;
    T.clock = 0;
    T.shieldsUpAt = -1e9;
    day = d >= 3 ? 3 : d <= 1 ? 1 : 2;
    refillAllowed = true;
    leonidasFallen = false;
    tegeansSaid = false;
    respitesSeen = 0;
    voiceUses.clear();
    campaignVolleys = 0;
    wavesToday = 0;
    const first = firstEntryOfDay(day);
    const k = first + (entry > 0 ? Math.floor(entry) : 0);
    beginEntry(k < DAYS.length && DAYS[k].day === day ? k : first, true);
  }

  /** Cancels everything silently (no events, no voice). */
  function stop() {
    running = false;
    state = 'over';
    timeline.length = 0;
    voiceQ.length = 0;
    T.voiceBusy = 0;
    T.deathT = -1;
    plan.length = 0;
    planIdx = 0;
    preparedWave = -1;
    vanguard.length = 0;
    wave = null;
    endless = false;
    T.nextVolley = Infinity;
    afterTimer = null;
    lull = false;
    readyRequested = false;
  }

  /**
   * READY from the HUD: ends the respite now if it has run ≥ RESPITE.readyAfter s, else as soon as it has.
   * Returns true if the request was taken. (Also bound to the `ready` event.)
   */
  function ready() {
    if (!running || state !== 'respite' || lull || T.deathT >= 0) return false;
    if (T.stateT >= RESPITE.readyAfter) T.timer = 0;
    else readyRequested = true;
    return true;
  }

  return {
    start,
    stop,
    update,
    ready,
    get day() {
      return day;
    },
    /** 0-based part of the current day (Day 1: 0 morning, 1 dusk); pass to start(day, entry). */
    get entryIndex() {
      return Math.max(0, entryIdx - firstEntryOfDay(day));
    },
    /** The current entry's time of day ('morning'|'dusk'|'noon'|'dawn'), or null when stopped. */
    get timeOfDay() {
      return running && DAYS[entryIdx] ? DAYS[entryIdx].time : null;
    },
    /** Waves (or surges) in the current day entry or phase; 0 in the endless phase or before one. */
    get waveCount() {
      if (endless) return 0;
      const e = DAYS[entryIdx];
      if (!e || (e.phases && phaseIdx < 0)) return 0;
      const list = waveList();
      return list ? list.length : 0;
    },
    /** 'A'|'B'|'C' on Day 3, else null. */
    get phase() {
      return phaseId();
    },
    /** 1-based wave (or surge) within the current day entry or phase; 0 before the first. */
    get wave() {
      return Math.max(0, waveIdx + 1);
    },
    get state() {
      return state;
    },
    /** 0-based count of waves started today; drives DIRECTOR[3].ratePerWave. */
    get dayWave() {
      return dayWave;
    },
    /** Seconds left in a respite (or lull), else 0. */
    get respiteLeft() {
      return state === 'respite' ? Math.max(0, T.timer) : 0;
    },
    /** True while READY would end the respite right away. */
    get canReady() {
      return running && state === 'respite' && !lull && T.stateT >= RESPITE.readyAfter;
    },
    /** True during a respite (not a lull) whether or not READY is live yet. */
    get inRespite() {
      return running && state === 'respite' && !lull;
    },
    get running() {
      return running;
    },
    /** Melee enemies in the current wave (for the HUD), or Infinity in the endless phase. */
    get waveSize() {
      return endless ? Infinity : wave ? meleeCount(wave) : 0;
    },
  };
}
