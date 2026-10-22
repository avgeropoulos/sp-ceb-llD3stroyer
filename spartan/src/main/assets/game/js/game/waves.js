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

const WAVE_CARD_SECONDS = 3;
const CLEARED_SECONDS = 3;
const VOICE_LEAD = 1.2;           // first intro line after the card appears
const BEHIND_CARD = 1.0;          // world changes wait until the card covers the view
const PHASE_GAP = 3;              // pause between Day 3 phases
const ENDLESS_WAVE_SECONDS = 25;  // phase C: the director's wave count rises this often
const SPAWN_CLEARANCE = 1.3;

/** Rough spoken length of a line, so lines don't cut each other off (TTS uses QUEUE_FLUSH). */
export function voiceSeconds(text) {
  return 0.7 + 0.062 * text.length;
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
    deathT: -1.5,
  };
  for (const k in T) T[k] = k === 'deathT' ? -1 : 0;
  let lull = false;
  let readyRequested = false;
  let afterTimer = null;    // what the timer leads to: 'wave' | 'phase' | 'entry' | 'endless'
  let refillAllowed = true;
  let leonidasFallen = false;

  // Fighting.
  let wave = null;
  let plan = [];
  let planIdx = 0;
  let endless = false;
  let archersLeft = 0;
  let volleyN = 0;
  let campaignVolleys = 0;
  let holdLineSaid = false;
  let tegeansSaid = false;

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

  /** Queues a line. `now` speaks it at once, cutting off whatever is being said. */
  function say(key, now = false) {
    const src = VOICE[key];
    if (!src) return;
    let speaker = src.speaker;
    if (leonidasFallen && speaker === 'Leonidas' && LEONIDAS_BATTLE_LINES.includes(key)) speaker = 'Dienekes';
    const line = { key, text: src.text, speaker, greek: src.greek };
    if (now) speak(line);
    else {
      if (key === 'holdTheLine' && voiceQ.length > 0) return; // not worth waiting for
      voiceQ.push(line);
    }
  }

  function speak(line) {
    const payload = { text: line.text, speaker: line.speaker };
    if (line.greek) payload.greek = line.greek;
    events.emit('voice', payload);
    T.voiceBusy = voiceSeconds(line.text);
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
  function refill() {
    const p = ctx.player;
    if (refillAllowed && p && p.refillSpears) p.refillSpears();
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
    // dayStart heals, refills and restores the line; world changes happen behind the card.
    const restore = () => {
      setTime(e.time);
      if (!first && newDay && ctx.ai && ctx.ai.clearCorpses) ctx.ai.clearCorpses();
      if (ctx.ai) ctx.ai.ensureAllies(ALLY_SLOTS);
    };
    if (first) restore();
    else later(BEHIND_CARD, restore);
    heal(1);
    refill();
    let speech = 0;
    for (const key of e.intro || []) {
      later(VOICE_LEAD, () => say(key));
      speech += voiceSeconds(VOICE[key].text);
    }
    T.timer = Math.max(CARD_SECONDS + 1, VOICE_LEAD + speech + 1.2);
    afterTimer = e.phases ? 'phase' : 'wave';
  }

  function beginPhase(k) {
    const e = entry();
    const p = e.phases[k];
    phaseIdx = k;
    waveIdx = -1;
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
      refillAllowed = false;
      later(2.4, () => say('swords'));
    }
    T.timer = (p.fade || 0) + CARD_SECONDS - 1;
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
    buildPlan(w);
    beginFighting(w);
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

  function beginFighting(w) {
    state = 'fighting';
    T.stateT = 0;
    T.fightT = 0;
    lull = false;
    archersLeft = w.archer || 0;
    T.spawnT = 0.6;
    T.archerT = 2.5;
    T.volleyEvery = w.volleyEvery || 0;
    T.nextVolley = T.volleyEvery > 0 ? SPAWN.firstVolley + rng.next() * 3 : Infinity;
    volleyN = 0;
    holdLineSaid = false;
    dayWave = wavesToday++;
  }

  function spawnPoint(behind, out) {
    const area = behind ? SPAWN.behind : SPAWN.front;
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
    spawnPoint(behind, spot);
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

  function fireVolley() {
    const p = phaseDef();
    const kolonos = endless && p && p.volley;
    volleyN++;
    const first = campaignVolleys === 0;
    campaignVolleys++;
    // Phase C: every other volley comes from behind, over the wall, and can't be blocked.
    const fromBehind = !!kolonos && volleyN % 2 === 0;
    const count = fromBehind ? (volleyN < 7 ? 100 : 200) : day === 3 ? 360 : 300;
    if (ctx.combat && ctx.combat.volley) ctx.combat.volley({ count, duration: 1.0, warn: 2.5, fromBehind });
    say('shieldsUp', true);
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
      if (p && p.lull) beginLull(p.lull, 'wave');
      else beginRespite();
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
    if (cfg.refill) refill();
    if (cfg.helot && ctx.ai) ctx.ai.spawnHelot();
    if (cfg.allies && ctx.ai) ctx.ai.ensureAllies(ALLY_SLOTS, { walkIn: true });
    const sub = cfg.refill && refillAllowed ? 'Bind your wounds. Take up fresh spears.' : 'Bind your wounds.';
    card('respite', 'Respite', sub, T.timer, waveIdx + 1);
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
    }
  }

  /** Starts the campaign at the first entry of `day` (1–3). */
  function start(d = 1) {
    stop();
    running = true;
    T.clock = 0;
    day = d >= 3 ? 3 : d <= 1 ? 1 : 2;
    refillAllowed = true;
    leonidasFallen = false;
    tegeansSaid = false;
    campaignVolleys = 0;
    wavesToday = 0;
    beginEntry(firstEntryOfDay(day), true);
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
    wave = null;
    endless = false;
    T.nextVolley = Infinity;
    afterTimer = null;
    lull = false;
    readyRequested = false;
  }

  /**
   * READY from the HUD: ends the respite now if it has run ≥ 6 s, else as soon as it has.
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
