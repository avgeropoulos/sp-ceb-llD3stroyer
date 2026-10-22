// Harness for ai.js + waves.js: stub combat/player/fx/audio/hud, an event recorder, a frame
// stepper that follows DESIGN.md §10 (ai → combat → waves), and scripted scenarios the e2e
// scripts (tests/e2e/ai-*.mjs) call through window.__sim.
import * as THREE from 'three';
import { createSandboxCtx } from './sandbox-ctx.js';
import { createAI } from '../../src/main/assets/game/js/game/ai.js';
import { createWaves } from '../../src/main/assets/game/js/game/waves.js';
import { createCharacters, CLIPS } from '../../src/main/assets/game/js/gfx/characters.js';
import { ENEMIES, DIRECTOR } from '../../src/main/assets/game/js/game/rules.js';
import { VOICE } from '../../src/main/assets/game/js/game/campaign.js';

const params = new URLSearchParams(location.search);
const ctx = await createSandboxCtx({ withWorld: params.get('world') === '1' });
ctx.mode = 'battle';
if (params.get('quality')) ctx.quality = params.get('quality');

// --- stubs ----------------------------------------------------------------------------------

const rec = { events: [], sfx: [], music: [], banners: [], times: [], heals: [], refills: 0, splashes: 0 };

const realWorld = params.get('world') === '1' && !!ctx.world.rebuildEnvironment && ctx.world.colliders && ctx.world.colliders.length > 0;
if (!realWorld) {
  const flatGround = ctx.world.groundHeight;
  ctx.world.groundHeight = (x, z) => (x > 9.5 ? -3 : x > 8.8 ? -3 * (x - 8.8) / 0.7 : flatGround(x, z));
}
const baseSetTime = ctx.world.setTimeOfDay.bind(ctx.world);
ctx.world.setTimeOfDay = (t) => {
  rec.times.push({ t: ctx.time.gnow, time: t });
  if (realWorld) baseSetTime(t);
};

const player = {
  pos: new THREE.Vector3(0, 0, 0), yaw: 0, pitch: 0, hp: 100, maxHp: 100, alive: true, kills: 0, score: 0,
  invulnerable: true, shieldRaise: 0,
  heal(n) { rec.heals.push({ t: ctx.time.gnow, n }); this.hp = Math.min(this.maxHp, this.hp + n); },
  refillSpears() { rec.refills++; },
  damage(n) {
    if (!this.alive) return;
    if (!this.invulnerable) this.hp -= n;
    if (this.hp <= 0) { this.hp = 0; this.alive = false; ctx.events.emit('playerDeath', {}); }
  },
  tryBlock() { return 'none'; },
  guardBreak() {},
};
ctx.player = player;

ctx.fx = { cameraOverridden: false, splash() { rec.splashes++; }, blood() {}, sparks() {}, dust() {} };
ctx.audio = { play(name) { rec.sfx.push(name); }, setMusic(m) { rec.music.push(m); } };
ctx.hud = { banner(text, sub, s) { rec.banners.push({ t: ctx.time.gnow, text, sub, s }); } };

function sector(attacker, target, reach, half) {
  const dx = target.pos.x - attacker.pos.x, dz = target.pos.z - attacker.pos.z;
  const dist = Math.hypot(dx, dz);
  // attacker yaw from facing: facing f looks along (sin f, cos f)
  const ang = Math.atan2(dx, dz) - attacker.facing;
  const a = Math.abs(Math.atan2(Math.sin(ang), Math.cos(ang)));
  const r = 0.32;
  return { hit: dist - r <= reach && a - Math.atan2(r, Math.max(dist, 0.01)) <= half, dist, angle: a };
}

const combat = {
  strikes: [], arrows: [], volleys: [], misses: 0, playerResult: 'hit',
  actorStrike(attacker, atk) {
    const toPlayer = atk.target === 'player';
    const target = toPlayer ? player : atk.target;
    const s = sector(attacker, target, atk.reach + 0.3, atk.halfAngle);
    const entry = { t: ctx.time.gnow, id: attacker.id, kind: attacker.kind, role: attacker.role, toPlayer, target: toPlayer ? 'player' : target.id, damage: atk.damage, atkKind: atk.kind, heavy: atk.heavy, dist: s.dist, angle: s.angle, result: 'miss' };
    combat.strikes.push(entry);
    if (!s.hit) { combat.misses++; return 'miss'; }
    if (toPlayer) {
      const r = combat.playerResult;
      if (r === 'perfect') attacker.takeHit({ damage: 0, kind: 'parry', dir: { x: -(target.pos.x - attacker.pos.x) / s.dist, z: -(target.pos.z - attacker.pos.z) / s.dist }, stagger: atk.heavy ? 1.4 : 1.2, ignoreGuard: true, byPlayer: true, attacker: 'player' });
      else if (r === 'hit') player.damage(atk.damage, { from: attacker, kind: atk.kind });
      entry.result = r;
      return r;
    }
    const dir = { x: (target.pos.x - attacker.pos.x) / s.dist, z: (target.pos.z - attacker.pos.z) / s.dist };
    const res = target.takeHit({ damage: atk.damage, kind: atk.kind, dir, power: 0.6, byPlayer: false, attacker, point: null });
    entry.result = res.guarded ? 'block' : 'hit';
    ctx.events.emit('hit', { target, attacker, damage: res.damage, kind: atk.kind, dir, guarded: res.guarded, killed: res.killed, byPlayer: false });
    if (res.killed) ctx.events.emit('kill', { target, attacker, byPlayer: false, kind: atk.kind, spear: false, cause: res.cause || 'weapon' });
    return entry.result;
  },
  shootArrow(from, target) {
    combat.arrows.push({ t: ctx.time.gnow, id: from.id, z: from.pos.z, target: target ? target.id : null, targetRole: target ? target.role : null });
  },
  volley(opts) {
    combat.volleys.push({ t: ctx.time.gnow, ...opts });
    ctx.events.emit('volley', { phase: 'warning', duration: opts.duration });
  },
  hostilesInSector() { return []; },
  throwSpear() { return null; },
  reset() {},
  update() {},
};
ctx.combat = combat;

const characters = createCharacters(ctx);
// Record every clip played, for stub and real visuals alike.
const baseCreate = characters.create;
characters.create = (kind, opts) => {
  const v = baseCreate(kind, opts);
  if (!v.anim.log) {
    const log = [];
    const play = v.anim.play;
    v.anim.log = log;
    v.anim.play = (clip, o) => {
      log.push({ clip, at: ctx.time.gnow, duration: o && o.duration });
      return play(clip, o);
    };
  }
  return v;
};
ctx.characters = characters;
const ai = createAI(ctx);
ctx.ai = ai;
const waves = createWaves(ctx);
ctx.waves = waves;

// Bus recorder.
const RECORD = ['dayStart', 'waveStart', 'waveCleared', 'respite', 'phase', 'voice', 'gameOver', 'enemyWindup', 'volley', 'playerDeath', 'kill'];
for (const name of RECORD) {
  ctx.events.on(name, (e) => {
    const r = { name, t: +ctx.time.gnow.toFixed(3) };
    if (name === 'enemyWindup') Object.assign(r, { id: e.actor.id, kind: e.actor.kind, T: e.timeToStrike, toPlayer: e.targetIsPlayer, trainer: e.actor.trainer });
    else if (name === 'kill') Object.assign(r, { id: e.target.id, kind: e.target.kind, role: e.target.role, cause: e.cause, by: e.attacker === 'player' ? 'player' : e.attacker && e.attacker.role });
    else if (e) for (const k in e) if (typeof e[k] !== 'object' || e[k] === null) r[k] = e[k];
    if (name !== 'enemyWindup' && name !== 'volley' && name !== 'kill') r.wavesState = waves.state;
    rec.events.push(r);
  });
}

// --- stepping -------------------------------------------------------------------------------

const hooks = [];
function frame(gdt) {
  ai.update(gdt);
  combat.update(gdt);
  if (waves.running) waves.update(gdt);
  for (let i = 0; i < hooks.length; i++) hooks[i](gdt);
}
/** Advances one frame of game time without rendering (sandbox-ctx time rules). */
function advance(dt) {
  const t = ctx.time;
  t.dt = dt;
  t.now += dt;
  if (t.hitStop > 0) { t.hitStop = Math.max(0, t.hitStop - dt); t.gdt = 0; t.pdt = 0; } else { t.gdt = dt * t.scale; t.pdt = t.gdt; }
  t.gnow += t.gdt;
  frame(t.gdt);
}
ctx.loop((dt, gdt) => frame(gdt)); // screenshots via __sandbox.step

function realWaves() {
  ctx.waves = waves;
}
function fakeWaves(day, dayWave = 0) {
  ctx.waves = { day, dayWave, state: 'fighting' };
}

function resetAll({ day = 1, motion = false, quality = 'high', invulnerable = true } = {}) {
  waves.stop();
  ai.clear();
  hooks.length = 0;
  combat.strikes.length = 0;
  combat.arrows.length = 0;
  combat.volleys.length = 0;
  combat.misses = 0;
  combat.playerResult = 'hit';
  rec.events.length = 0;
  rec.sfx.length = 0;
  rec.music.length = 0;
  rec.banners.length = 0;
  rec.times.length = 0;
  rec.heals.length = 0;
  rec.refills = 0;
  rec.splashes = 0;
  ctx.fx.cameraOverridden = false;
  ctx.settings.control = motion ? 'motion' : 'touch';
  ctx.quality = quality;
  player.pos.set(0, 0, 0);
  player.hp = 100;
  player.alive = true;
  player.invulnerable = invulnerable;
  player.kills = 0;
  ctx.time.scale = 1;
  ctx.time.hitStop = 0;
  fakeWaves(day);
}

function living(role) {
  return ctx.actors.filter((a) => a.alive && (!role || a.role === role));
}

function minPairDistance() {
  const list = ctx.actors.filter((a) => a.alive);
  let min = Infinity, pair = null;
  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const d = Math.hypot(list[i].pos.x - list[j].pos.x, list[i].pos.z - list[j].pos.z);
      if (d < min) { min = d; pair = [list[i].kind + list[i].id + ':' + list[i].state, list[j].kind + list[j].id + ':' + list[j].state]; }
    }
    const dp = Math.hypot(list[i].pos.x - player.pos.x, list[i].pos.z - player.pos.z);
    if (dp < min) { min = dp; pair = [list[i].kind + list[i].id, 'player']; }
  }
  return { min, pair };
}

function playerKill(a) {
  const dx = a.pos.x - player.pos.x, dz = a.pos.z - player.pos.z;
  const d = Math.hypot(dx, dz) || 1;
  const res = a.takeHit({ damage: 999, kind: 'slash', dir: { x: dx / d, z: dz / d }, power: 1, ignoreGuard: true, byPlayer: true, attacker: 'player' });
  if (res.killed) {
    player.kills++;
    player.score += 100;
    ctx.events.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: res.cause || 'weapon' });
  }
  return res;
}

// --- scenarios ------------------------------------------------------------------------------

const scenarios = {};

/** Director limits against the player with a crowd of enemies. */
scenarios.director = ({ day = 1, motion = false, seconds = 240, dt = 1 / 30, enemies = 12, hp = 100, dayWave = 0 } = {}) => {
  resetAll({ day, motion });
  fakeWaves(day, dayWave);
  player.hp = hp;
  ai.ensureAllies();
  const spawnOne = () => ai.spawnEnemy(['levy', 'immortal', 'sparabara', 'captain'][Math.floor(ctx.rng.next() * (day === 1 ? 1 : 4))], -5 + ctx.rng.next() * 11, -8 - ctx.rng.next() * 10);
  for (let i = 0; i < enemies; i++) spawnOne();
  let maxConcurrent = 0, maxEngaged = 0;
  const t0 = ctx.time.gnow;
  const frames = Math.round(seconds / dt);
  let minPair = Infinity;
  for (let f = 0; f < frames; f++) {
    advance(dt);
    let c = 0;
    for (const a of ctx.actors) if (a.alive && a.state === 'windup' && a.targetIsPlayer) c++;
    maxConcurrent = Math.max(maxConcurrent, c);
    maxEngaged = Math.max(maxEngaged, ai.playerAttackers.length);
    minPair = Math.min(minPair, minPairDistance().min);
    if (ai.enemiesAlive < enemies) spawnOne();
  }
  const wind = rec.events.filter((e) => e.name === 'enemyWindup' && e.toPlayer && e.t >= t0);
  const strikes = combat.strikes.filter((s) => s.toPlayer).map((s) => s.t);
  let minGap = Infinity;
  for (let i = 1; i < strikes.length; i++) minGap = Math.min(minGap, strikes[i] - strikes[i - 1]);
  // Every strike lands T after its wind-up began (± one frame).
  let maxTimingErr = 0;
  const pending = new Map();
  for (const w of wind) pending.set(w.id, (pending.get(w.id) || []).concat([w.t + w.T]));
  for (const s of combat.strikes.filter((x) => x.toPlayer)) {
    const list = pending.get(s.id) || [];
    let best = Infinity;
    for (const at of list) best = Math.min(best, Math.abs(at - s.t));
    maxTimingErr = Math.max(maxTimingErr, best);
  }
  const D = DIRECTOR[day];
  const rate = Math.min(D.rateMax || 9, D.rate + (D.ratePerWave || 0) * dayWave) * (day < 3 && hp <= 25 ? 0.7 : 1);
  const Ts = {};
  for (const w of wind) Ts[w.kind] = +w.T.toFixed(4);
  const playerStrikes = combat.strikes.filter((s) => s.toPlayer);
  return {
    windups: wind.length, strikes: strikes.length, maxConcurrent, maxEngaged, minGap, maxTimingErr,
    bound: Math.max(1, D.windups) + rate * seconds, rate, cap: D.windups, engagedCap: D.engaged, gap: D.gap, Ts,
    misses: playerStrikes.filter((s) => s.result === 'miss').length,
    allyStrikes: combat.strikes.filter((s) => !s.toPlayer && s.role === 'ally').length,
    enemyOnAlly: combat.strikes.filter((s) => !s.toPlayer && s.role === 'melee').length,
    minPair,
    windupMul: { motion: ctx.settings.control === 'motion', day },
    expectedT: Object.fromEntries(['levy', 'immortal', 'sparabara', 'captain'].map((k) => [k, +(ENEMIES[k].windup * (motion ? 1.15 : 1) * (day === 3 ? 0.9 : 1)).toFixed(4)])),
  };
};

/** Spacing, slot arrival and overlap with a full line and 14 enemies. */
scenarios.spacing = ({ day = 2, seconds = 40, dt = 1 / 30 } = {}) => {
  resetAll({ day });
  ai.ensureAllies();
  for (let i = 0; i < 14; i++) ai.spawnEnemy(i % 3 === 0 ? 'sparabara' : 'levy', -6 + (i % 7) * 2, -35 - Math.floor(i / 7) * 5);
  let minPair = Infinity, worst = null;
  const frames = Math.round(seconds / dt);
  for (let f = 0; f < frames; f++) {
    advance(dt);
    const m = minPairDistance();
    if (m.min < minPair) { minPair = m.min; worst = m.pair; }
  }
  const slotted = ctx.actors.filter((a) => a.alive && a.role === 'melee' && a.targetIsPlayer);
  const slotErr = slotted.map((a) => {
    const s = a.slot;
    return s ? +Math.hypot(a.pos.x - s.x, a.pos.z - s.z).toFixed(3) : null;
  });
  const bearings = slotted.map((a) => +(Math.atan2(-(a.pos.x - player.pos.x), -(a.pos.z - player.pos.z)) * 180 / Math.PI).toFixed(1));
  const dists = slotted.map((a) => +Math.hypot(a.pos.x - player.pos.x, a.pos.z - player.pos.z).toFixed(2));
  const vsAlly = ctx.actors.filter((a) => a.alive && a.role === 'melee' && a.foe && a.foe.role === 'ally');
  const allyErr = vsAlly.map((a) => (a.allySpot ? +Math.hypot(a.pos.x - a.allySpot.x, a.pos.z - a.allySpot.z).toFixed(3) : null));
  const waiting = ctx.actors.filter((a) => a.alive && a.role === 'melee' && a.state === 'hold').length;
  const allyDrift = Math.max(...living('ally').map((a) => Math.hypot(a.pos.x - a.homeX, a.pos.z - a.homeZ)));
  return { minPair, worst, slotErr, bearings, dists, allyErr, waiting, engaged: slotted.length, vsAlly: vsAlly.length, allyDrift, states: ctx.actors.filter((a) => a.alive && a.role === 'melee').map((a) => a.state) };
};

/** Bot that plays the campaign: kills engaged enemies after a while, presses READY sometimes. */
function campaignBot({ killAfter = 2.0, readyAt = null, maxWaitHold = 9 } = {}) {
  const held = new Map();
  return () => {
    const now = ctx.time.gnow;
    for (const a of ctx.actors) {
      if (!a.alive || a.team !== 'persian') continue;
      if (a.role === 'melee') {
        const engagedFor = a.engagedAt >= 0 ? now - a.engagedAt : -1;
        if ((a.state === 'engage' || a.state === 'windup' || a.state === 'recover') && engagedFor > killAfter) playerKill(a);
        else if (a.state === 'hold') {
          if (!held.has(a.id)) held.set(a.id, now);
          if (now - held.get(a.id) > maxWaitHold) playerKill(a);
        }
      }
    }
    if (readyAt !== null && waves.inRespite && waves.respiteLeft > 0) {
      const ev = rec.events.filter((e) => e.name === 'respite').pop();
      if (ev && now - ev.t >= readyAt) waves.ready();
    }
  };
}

/** The whole campaign from Day 1 to the end of phase C (player death). */
scenarios.campaign = ({ dt = 0.05, motion = false, readyAt = 7, cSeconds = 75, maxSeconds = 4000 } = {}) => {
  resetAll({ motion });
  realWaves();
  hooks.push(campaignBot({ readyAt }));
  waves.start(1);
  const track = { behindSpawned: 0, behindGapOk: true, behindReachedFront: 0, behindIds: new Set(), maxEnemies: 0, maxAllies: 0, minPair: Infinity, leonidasDeathAt: null, archersAtClear: [], phaseCStart: null, gapViolations: [] };
  let killedPlayerAt = null;
  const frames = Math.round(maxSeconds / dt);
  for (let f = 0; f < frames && waves.running; f++) {
    advance(dt);
    let enemies = 0;
    for (const a of ctx.actors) {
      if (!a.alive) continue;
      if (a.team === 'persian' && a.role !== 'dummy') enemies++;
      if (a.fromBehind) {
        if (!track.behindIds.has(a.id)) { track.behindIds.add(a.id); track.behindSpawned++; }
        if (Math.abs(a.pos.z - 12) < 1.1 && Math.abs(a.pos.x) > 1.6) { track.behindGapOk = false; track.gapViolations.push([+a.pos.x.toFixed(2), +a.pos.z.toFixed(2)]); }
        if (a.pos.z < 3 && !a.reachedFront) { a.reachedFront = true; track.behindReachedFront++; }
      }
    }
    track.maxEnemies = Math.max(track.maxEnemies, enemies);
    track.maxAllies = Math.max(track.maxAllies, ai.alliesAlive);
    if (f % 4 === 0) track.minPair = Math.min(track.minPair, minPairDistance().min);
    if (track.leonidasDeathAt === null && ai.leonidasFallen) track.leonidasDeathAt = ctx.time.gnow;
    if (waves.phase === 'C' && waves.state === 'fighting' && track.phaseCStart === null) track.phaseCStart = ctx.time.gnow;
    if (track.phaseCStart !== null && killedPlayerAt === null && ctx.time.gnow - track.phaseCStart > cSeconds) {
      killedPlayerAt = ctx.time.gnow;
      player.invulnerable = false;
      player.damage(999);
    }
    const last = rec.events[rec.events.length - 1];
    if (last && last.name === 'waveCleared' && last.t === +ctx.time.gnow.toFixed(3) && !track.archersAtClear.some((x) => x.t === last.t)) {
      track.archersAtClear.push({ t: last.t, day: last.day, wave: last.wave, archers: ai.archersAlive });
    }
  }
  const leonidasCorpse = ai.corpses.find((c) => c.name === 'Leonidas');
  return {
    events: rec.events.filter((e) => e.name !== 'enemyWindup' && e.name !== 'kill'),
    windups: rec.events.filter((e) => e.name === 'enemyWindup').length,
    kills: rec.events.filter((e) => e.name === 'kill').length,
    killsBy: rec.events.filter((e) => e.name === 'kill').reduce((m, e) => { const k = `${e.by}>${e.role}`; m[k] = (m[k] || 0) + 1; return m; }, {}),
    volleys: combat.volleys,
    heals: rec.heals,
    refills: rec.refills,
    times: rec.times,
    music: rec.music,
    banners: rec.banners,
    killedPlayerAt,
    endState: waves.state,
    track: { ...track, behindIds: undefined, leonidasCorpse: !!leonidasCorpse, leonidasKeep: leonidasCorpse ? leonidasCorpse.keepCorpse : null },
    arrowsAtAllies: combat.arrows.filter((a) => a.targetRole === 'ally').length,
    arrowsMaxZ: Math.max(-1e9, ...combat.arrows.map((a) => a.z)),
    strikesOnPlayer: combat.strikes.filter((s) => s.toPlayer).length,
    misses: combat.strikes.filter((s) => s.result === 'miss').length,
    totalStrikes: combat.strikes.length,
    voice: VOICE,
  };
};

/** Respite length (touch 15 s / motion 25 s) and READY. */
scenarios.respite = ({ motion = false, readyAt = null, dt = 0.05 } = {}) => {
  resetAll({ motion });
  realWaves();
  hooks.push(campaignBot({ readyAt: null, killAfter: 0.5, maxWaitHold: 3 }));
  waves.start(1);
  let respiteAt = null, nextWaveAt = null, readyResult = null, readyEarly = null;
  for (let f = 0; f < 200 * 20 && nextWaveAt === null; f++) {
    advance(dt);
    const ev = rec.events.filter((e) => e.name === 'respite').pop();
    if (ev && respiteAt === null) respiteAt = ev.t;
    if (respiteAt !== null) {
      const age = ctx.time.gnow - respiteAt;
      if (readyEarly === null && age >= 2) readyEarly = { canReady: waves.canReady, inRespite: waves.inRespite };
      if (readyAt !== null && readyResult === null && age >= readyAt) readyResult = waves.ready();
      const ws = rec.events.filter((e) => e.name === 'waveStart' && e.t > respiteAt);
      if (ws.length) nextWaveAt = ws[0].t;
    }
  }
  const ev = rec.events.find((e) => e.name === 'respite');
  return { respiteAt, nextWaveAt, length: nextWaveAt - respiteAt, seconds: ev && ev.seconds, readyResult, readyEarly, heals: rec.heals.slice(), refills: rec.refills, helotSeen: rec.helotSeen };
};

/** stop() in the middle of a wave, then ai.clear(): nothing is left and nothing more happens. */
scenarios.stop = ({ dt = 0.05 } = {}) => {
  resetAll({ day: 2 });
  realWaves();
  const baseChildren = ctx.scene.children.length;
  const liveBefore = characters.stats ? characters.stats.live : null;
  waves.start(2);
  // Into wave 1 with enemies alive and a volley pending.
  for (let f = 0; f < 20 * 20; f++) advance(dt);
  const mid = { state: waves.state, enemies: ai.enemiesAlive, archers: ai.archersAlive, allies: ai.alliesAlive, children: ctx.scene.children.length };
  waves.stop();
  ai.clear();
  const nEvents = rec.events.length, nVolleys = combat.volleys.length, nStrikes = combat.strikes.length;
  for (let f = 0; f < 120 * 20; f++) advance(dt);
  return {
    mid,
    after: {
      state: waves.state, actors: ctx.actors.length, corpses: ai.corpses.length, children: ctx.scene.children.length, baseChildren,
      newEvents: rec.events.slice(nEvents).map((e) => e.name), newVolleys: combat.volleys.length - nVolleys, newStrikes: combat.strikes.length - nStrikes,
      live: characters.stats ? characters.stats.live : null, liveBefore,
    },
  };
};

/** Corpse cap: kill 45 enemies over time and watch the corpse list. */
scenarios.corpses = ({ quality = 'high', dt = 1 / 30 } = {}) => {
  resetAll({ day: 2, quality });
  const cap = quality === 'low' ? 10 : 20;
  let maxResting = 0, maxTotal = 0, shadowOnCorpse = 0, killed = 0;
  for (let f = 0; f < 45 * 18; f++) {
    if (f % 18 === 0 && killed < 45) {
      const a = ai.spawnEnemy('levy', -5 + (killed % 10), -3 - (killed % 3));
      advance(dt);
      playerKill(a);
      killed++;
    }
    advance(dt);
    const resting = ai.corpses.filter((c) => c.sinkT < 0).length;
    maxResting = Math.max(maxResting, resting);
    maxTotal = Math.max(maxTotal, ai.corpses.length);
    for (const c of ai.corpses) if (c.visual.mesh && c.visual.mesh.castShadow) shadowOnCorpse++;
  }
  for (let f = 0; f < 5 * 30; f++) advance(dt);
  return { cap, maxResting, maxTotal, shadowOnCorpse, finalCorpses: ai.corpses.length, sceneChildren: ctx.scene.children.length, live: characters.stats ? characters.stats.live : null };
};

/** Focused checks: bash interrupts, spear cam, trainer, dummy, sea fall, helot, enemies from behind, Leonidas. */
scenarios.units = ({ dt = 1 / 60 } = {}) => {
  const out = {};
  const run = (s) => { for (let f = 0; f < Math.round(s / dt); f++) advance(dt); };

  // Bash during a wind-up: stagger, and the blow never lands.
  resetAll({ day: 1 });
  let a = ai.spawnEnemy('levy', 0, -3);
  let t0 = ctx.time.gnow;
  while (a.state !== 'windup' && ctx.time.gnow - t0 < 20) run(dt);
  const strikesBefore = combat.strikes.length;
  const wound = a.state === 'windup';
  const r = a.takeHit({ damage: 8, kind: 'bash', dir: { x: 0, z: -1 }, power: 0.5, stagger: 1.1, knockback: 0.9, byPlayer: true });
  out.bash = { wound, state: a.state, guarded: r.guarded, damage: r.damage, z: +a.pos.z.toFixed(2) };
  run(0.5);
  out.bash.zAfter = +a.pos.z.toFixed(2);
  run(1.0);
  out.bash.strikesDuring = combat.strikes.length - strikesBefore;
  out.bash.stateAfter = a.state;
  // Rebash within 2.5 s: flinch only.
  const r2 = a.takeHit({ damage: 8, kind: 'bash', dir: { x: 0, z: -1 }, power: 0.5, stagger: 1.1, knockback: 0.9, byPlayer: true });
  out.bash.rebashState = a.state;
  out.bash.rebashDamage = r2.damage;

  // Guard rule: frontal hits on a sparabara are often guarded (15%), from behind never.
  resetAll({ day: 1 });
  a = ai.spawnEnemy('sparabara', 0, -6);
  a.state = 'hold';
  let guardedFront = 0, guardedBack = 0;
  for (let i = 0; i < 200; i++) {
    a.hp = 1e6; a.state = 'hold'; a.guard = 1; a.blockChance = a.baseBlock; a.facing = 0;
    if (a.takeHit({ damage: 10, kind: 'slash', dir: { x: 0, z: -1 }, power: 0.5 }).guarded) guardedFront++;
    a.state = 'hold'; a.blockChance = a.baseBlock;
    if (a.takeHit({ damage: 10, kind: 'slash', dir: { x: 0, z: 1 }, power: 0.5 }).guarded) guardedBack++;
  }
  const g1 = a.takeHit({ damage: 10, kind: 'slash', dir: { x: 0, z: -1 }, ignoreGuard: true });
  out.guard = { guardedFront, guardedBack, ignore: g1.guarded, blockChance: a.baseBlock };

  // Perfect block from combat: stagger 1.2 s, guard dropped.
  resetAll({ day: 1 });
  combat.playerResult = 'perfect';
  a = ai.spawnEnemy('immortal', 0.3, -4);
  t0 = ctx.time.gnow;
  while (combat.strikes.filter((s) => s.toPlayer).length === 0 && ctx.time.gnow - t0 < 20) run(dt);
  out.perfect = { state: a.state, staggerT: +a.staggerT.toFixed(2), guard: +a.guard.toFixed(2) };
  combat.playerResult = 'hit';

  // Spear cam: no new player wind-ups while overridden; +0.5 s for the ones in progress.
  resetAll({ day: 2 });
  for (let i = 0; i < 4; i++) ai.spawnEnemy('levy', -1.5 + i, -3 - (i % 2));
  t0 = ctx.time.gnow;
  let w = null;
  while (!w && ctx.time.gnow - t0 < 30) {
    run(dt);
    w = ctx.actors.find((x) => x.alive && x.state === 'windup' && x.targetIsPlayer && x.strikeAt - ctx.time.gnow > 0.3);
  }
  const before = w ? w.strikeAt : null;
  ctx.fx.cameraOverridden = true;
  const nW = rec.events.filter((e) => e.name === 'enemyWindup' && e.toPlayer).length;
  run(0.2);
  ctx.events.emit('spearCam', { phase: 'end' });
  const after = w ? w.strikeAt : null;
  run(5);
  const startedDuring = rec.events.filter((e) => e.name === 'enemyWindup' && e.toPlayer).length - nW;
  const reemitted = rec.events.filter((e) => e.name === 'enemyWindup' && e.toPlayer && w && e.id === w.id).length;
  ctx.fx.cameraOverridden = false;
  const landed = w ? combat.strikes.filter((s) => s.id === w.id).map((s) => s.t) : [];
  out.spearCam = { found: !!w, shift: after - before, startedDuringOverride: startedDuring - (reemitted > 1 ? 1 : 0), landedAt: landed.length ? landed[landed.length - 1] - after : null, reemitted };

  // Trainer: 0 damage, 1.2 s wind-up every 3 s, even with no director tokens.
  resetAll({ day: 1 });
  ai.spawnDummy(0, -2.6);
  const tr = ai.spawnEnemy('levy', 0, -1.6, { trainer: true });
  run(16);
  const tw = rec.events.filter((e) => e.name === 'enemyWindup' && e.trainer);
  const ts = combat.strikes.filter((s) => s.id === tr.id);
  out.trainer = { windups: tw.length, T: tw.map((e) => +e.T.toFixed(3)), gaps: tw.slice(1).map((e, i) => +(e.t - tw[i].t).toFixed(2)), damage: ts.map((s) => s.damage), pos: [+tr.pos.x.toFixed(2), +tr.pos.z.toFixed(2)] };

  // Dummy never dies.
  const dm = ctx.actors.find((x) => x.role === 'dummy');
  const dr = dm.takeHit({ damage: 99999, kind: 'thrust', dir: { x: 0, z: -1 }, power: 1 });
  out.dummy = { killed: dr.killed, alive: dm.alive, hp: dm.hp };

  // Into the Gulf: bash an enemy near the edge.
  resetAll({ day: 1 });
  a = ai.spawnEnemy('levy', 8.2, -2);
  run(0.1);
  const sr = a.takeHit({ damage: 8, kind: 'bash', dir: { x: 1, z: 0 }, power: 0.6, stagger: 1.1, knockback: 0.9, byPlayer: true });
  const ys = [];
  for (let i = 0; i < 40; i++) { run(0.1); ys.push(+a.visual.root.position.y.toFixed(2)); }
  out.sea = { killed: sr.killed, cause: sr.cause, alive: a.alive, minY: Math.min(...ys), splashes: rec.splashes, scream: rec.sfx.includes('scream'), splashSfx: rec.sfx.includes('splash'), clip: a.visual.anim.log.map((l) => l.clip).pop(), corpses: ai.corpses.length };

  // Helot: runs in from behind the wall, reaches the player, leaves, no corpse.
  resetAll({ day: 1 });
  const h = ai.spawnHelot();
  let minD = Infinity, inActors = ctx.actors.includes(h);
  for (let i = 0; i < 300; i++) { run(0.05); if (h.alive) minD = Math.min(minD, Math.hypot(h.pos.x - player.pos.x, h.pos.z - player.pos.z)); }
  out.helot = { inActors, minD: +minD.toFixed(2), gone: !ctx.actors.includes(h), corpse: ai.corpses.includes(h), hit: h.takeHit({ damage: 50 }).damage };

  // Day 3: enemies from behind come through the wall gap and engage from the rear.
  resetAll({ day: 3 });
  ai.ensureAllies();
  const behind = [];
  for (let i = 0; i < 5; i++) behind.push(ai.spawnEnemy('immortal', -3 + i * 1.5, 32 + i * 2, { fromBehind: true }));
  let viol = 0, minPairB = Infinity;
  for (let i = 0; i < 40 * 30; i++) {
    advance(1 / 30);
    for (const b of behind) if (b.alive && Math.abs(b.pos.z - 12) < 1.1 && Math.abs(b.pos.x) > 1.6) viol++;
    minPairB = Math.min(minPairB, minPairDistance().min);
  }
  out.behind = {
    violations: viol, minPair: minPairB,
    final: behind.map((b) => ({ z: +b.pos.z.toFixed(1), x: +b.pos.x.toFixed(1), state: b.state, toPlayer: b.targetIsPlayer, foe: b.foe ? b.foe.role : null, slot: b.slot ? b.slot.kind : null })),
    windupsFromBehind: rec.events.filter((e) => e.name === 'enemyWindup' && behind.some((b) => b.id === e.id)).length,
  };

  // Leonidas: protected until killAlly, then a corpse that never sinks.
  resetAll({ day: 1 });
  ai.ensureAllies();
  const leo = ctx.actors.find((x) => x.name === 'Leonidas');
  const lr = leo.takeHit({ damage: 1000, kind: 'thrust', dir: { x: 0, z: 1 }, power: 1, ignoreGuard: true });
  out.leonidas = { x: leo.homeX, survived: leo.alive && !lr.killed, hp: leo.hp };
  ai.killAlly('Leonidas');
  run(0.1);
  for (let i = 0; i < 30; i++) { const e = ai.spawnEnemy('levy', 3, -3); run(0.02); playerKill(e); }
  run(4);
  const lc = ai.corpses.find((c) => c.name === 'Leonidas');
  out.leonidas.corpse = !!lc;
  out.leonidas.sinking = lc ? lc.sinkT : null;
  out.leonidas.fallen = ai.leonidasFallen;
  ai.ensureAllies();
  out.leonidas.respawnedName = ctx.actors.filter((x) => x.alive && x.role === 'ally' && Math.abs(x.homeX + 1.4) < 0.01).map((x) => x.name);

  // Archers stay back and shoot at allies; retreatArchers walks them off without corpses.
  resetAll({ day: 1 });
  ai.ensureAllies();
  const arch = [ai.spawnEnemy('archer', -2, -40), ai.spawnEnemy('archer', 3, -42)];
  run(40);
  const shots = combat.arrows.length;
  const maxZ = Math.max(...arch.map((x) => x.pos.z));
  ai.retreatArchers();
  run(25);
  out.archers = { shots, atAllies: combat.arrows.every((x) => x.targetRole === 'ally'), maxZ: +maxZ.toFixed(2), removed: arch.every((x) => !ctx.actors.includes(x)), corpses: ai.corpses.length };

  // Ally duels: enemies that fight allies get hit back and can kill them; ensureAllies refills.
  resetAll({ day: 2 });
  ai.ensureAllies();
  for (const al of living('ally')) al.hp = al.name === 'Leonidas' ? al.hp : 25;
  for (let i = 0; i < 8; i++) ai.spawnEnemy('immortal', -5 + i * 1.6, -12);
  run(60);
  const allyStrikes = combat.strikes.filter((s) => s.role === 'ally').length;
  const deadAllies = 7 - ai.alliesAlive;
  ai.ensureAllies(undefined, { walkIn: true });
  const walkers = ctx.actors.filter((x) => x.alive && x.role === 'ally' && x.state === 'advance').length;
  run(15);
  out.allies = { allyStrikes, deadAllies, walkers, allies: ai.alliesAlive, allyCorpses: ai.corpses.filter((c) => c.role === 'ally').length, allHome: ctx.actors.filter((x) => x.alive && x.role === 'ally').every((x) => Math.hypot(x.pos.x - x.homeX, x.pos.z - x.homeZ) < 0.6) };
  return out;
};

/** Balance probe: the "player" kills only its own attackers, after killAfter s engaged. */
scenarios.balance = ({ day = 1, seconds = 300, killAfter = 3.5, dt = 0.05 } = {}) => {
  resetAll({ day });
  realWaves();
  hooks.push(() => {
    const now = ctx.time.gnow;
    for (const a of ctx.actors) {
      if (a.alive && a.role === 'melee' && a.targetIsPlayer && a.engagedAt >= 0 && now - a.engagedAt > killAfter) playerKill(a);
    }
    if (waves.canReady) waves.ready();
  });
  waves.start(day);
  for (let f = 0; f < seconds / dt && waves.running; f++) advance(dt);
  const kills = rec.events.filter((e) => e.name === 'kill');
  return {
    player: kills.filter((e) => e.by === 'player').length,
    allies: kills.filter((e) => e.by === 'ally' && e.role === 'melee').length,
    alliesLost: kills.filter((e) => e.role === 'ally').length,
    waves: rec.events.filter((e) => e.name === 'waveCleared').length,
    wavesState: waves.state, day: waves.day, wave: waves.wave,
  };
};

/** Day 3 phase C tableau: the line, enemies in front and Immortals through the wall gap. */
scenarios.kolonos = ({ seconds = 14, view = 'behind' } = {}) => {
  resetAll({ day: 3 });
  ai.ensureAllies();
  ai.killAlly('Leonidas');
  for (const al of living('ally')) if (al.homeX > 3) al.takeHit({ damage: 1e4, kind: 'melee', dir: { x: 0, z: 1 }, ignoreGuard: true });
  for (let i = 0; i < 6; i++) ai.spawnEnemy(i % 2 ? 'immortal' : 'levy', -4 + i * 1.7, -12 - (i % 2) * 3);
  for (let i = 0; i < 6; i++) ai.spawnEnemy('immortal', -2.5 + i, 26 + (i % 3) * 3, { fromBehind: true });
  for (let f = 0; f < seconds * 30; f++) advance(1 / 30);
  setView(view);
  ctx.render();
  return { actors: ctx.actors.length, behind: ctx.actors.filter((a) => a.fromBehind && a.alive).map((a) => [+a.pos.x.toFixed(1), +a.pos.z.toFixed(1), a.state]) };
};

/** A battle tableau for screenshots. */
scenarios.battle = ({ day = 2, seconds = 22, view = 'eye' } = {}) => {
  resetAll({ day });
  ai.ensureAllies();
  const kinds = day === 1 ? ['levy'] : ['levy', 'sparabara', 'immortal', 'levy', 'immortal', 'captain'];
  for (let i = 0; i < 12; i++) ai.spawnEnemy(kinds[i % kinds.length], -6 + (i % 7) * 2, -14 - Math.floor(i / 7) * 4);
  ai.spawnEnemy('archer', -3, -38);
  ai.spawnEnemy('archer', 4, -40);
  for (let f = 0; f < seconds * 30; f++) advance(1 / 30);
  setView(view);
  ctx.render();
  return { actors: ctx.actors.length, info: { calls: ctx.renderer.info.render.calls, triangles: ctx.renderer.info.render.triangles } };
};

function setView(view) {
  const cam = ctx.camera;
  if (view === 'top') {
    cam.position.set(0.6, 11, -2.2);
    cam.rotation.set(-Math.PI / 2, 0, 0);
  } else if (view === 'side') {
    cam.position.set(-7.5, 4.5, 6);
    cam.lookAt(1, 0.8, -4);
  } else if (view === 'behind') {
    cam.position.set(0, 3.2, 6.5);
    cam.lookAt(0, 1.2, -3);
  } else if (view === 'wall') {
    cam.position.set(6.5, 5.5, 20);
    cam.lookAt(0, 0.8, 6);
  } else {
    cam.position.set(player.pos.x, 1.66, player.pos.z);
    cam.rotation.set(-0.08, 0, 0);
  }
  cam.updateMatrixWorld();
}

/** Allocation probe: build 14 enemies + 8 allies + 20 corpses, then just keep updating. */
scenarios.populate = () => {
  resetAll({ day: 2 });
  ai.ensureAllies();
  ai.spawnHelot();
  for (let i = 0; i < 20; i++) { const e = ai.spawnEnemy('levy', -5 + (i % 10), -4 - (i % 2)); advance(1 / 60); playerKill(e); }
  for (let i = 0; i < 14; i++) ai.spawnEnemy(i % 2 ? 'immortal' : 'levy', -6 + (i % 7) * 2, -6 - Math.floor(i / 7) * 3);
  for (let f = 0; f < 240; f++) advance(1 / 60);
  return { actors: ctx.actors.length, enemies: ai.enemiesAlive, allies: ai.alliesAlive, corpses: ai.corpses.length };
};
const keepKinds = ['levy', 'immortal'];
scenarios.runFrames = ({ frames = 600, dt = 1 / 60, keep = false } = {}) => {
  // keep: hold the population at 14 enemies + 7 allies (allies are immortal here) so the
  // measured frames always run the full load; spawning happens only on a death, rarely.
  if (keep) for (const al of living('ally')) al.protected = true;
  for (let f = 0; f < frames; f++) {
    advance(dt);
    if (keep && ai.enemiesAlive < 14) ai.spawnEnemy(keepKinds[f & 1], -6 + (f % 13), -8);
  }
  return { actors: ctx.actors.length, corpses: ai.corpses.length, windups: rec.events.filter((e) => e.name === 'enemyWindup').length };
};

window.__sim = { ctx, ai, waves, combat, player, rec, characters, CLIPS, scenarios, advance, setView, resetAll, realStubs: params.get('real') !== '1' ? 'stub' : 'real' };

const scene = params.get('scene');
if (scene === 'battle') scenarios.battle({ day: Number(params.get('day') || 2), view: params.get('view') || 'eye' });
else if (scene === 'campaign') { realWaves(); waves.start(Number(params.get('day') || 1)); }
setView(params.get('view') || 'eye');
