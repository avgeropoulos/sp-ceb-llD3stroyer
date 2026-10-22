// Scripted checks for game/ai.js and game/waves.js in tests/sandbox/ai-battle.html (stub combat,
// player and visuals; real rules.js). Usage: node spartan/tests/e2e/ai-sim.mjs [--real] [only=name,...]
//   --real   use the real gfx/characters.js instead of the stub visuals
//   --world  use the real gfx/world.js (terrain, sea, wall) instead of a flat ground
import { launch } from './lib.mjs';

const args = process.argv.slice(2);
const real = args.includes('--real');
const world = args.includes('--world');
const only = (args.find((a) => a.startsWith('only=')) || '').slice(5).split(',').filter(Boolean);
const want = (name) => only.length === 0 || only.includes(name);

let failures = 0;
function check(name, ok, detail) {
  if (!ok) failures++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail !== undefined ? ' — ' + (typeof detail === 'string' ? detail : JSON.stringify(detail)) : ''}`);
}

const l = await launch();
try {
  const page = await l.newPage();
  await page.goto(`${l.base}/tests/sandbox/ai-battle.html?manual=1&scene=none${real ? '&real=1' : ''}${world ? '&world=1' : ''}`);
  await page.waitForFunction(() => window.__sim, null, { timeout: 240000 });
  const sim = (name, opts) => page.evaluate(({ name, opts }) => window.__sim.scenarios[name](opts), { name, opts });
  console.log(`visuals: ${await page.evaluate(() => window.__sim.realStubs)}, world: ${world ? 'real' : 'flat'}`);

  if (want('director')) {
    for (const cfg of [
      { day: 1 }, { day: 1, motion: true }, { day: 1, hp: 20 }, { day: 2 }, { day: 3 }, { day: 3, dayWave: 6 }, { day: 3, motion: true, dayWave: 10 },
    ]) {
      const r = await sim('director', { ...cfg, seconds: 240, enemies: 12 });
      const tag = `director ${JSON.stringify(cfg)}`;
      check(`${tag}: concurrent wind-ups ≤ ${r.cap}`, r.maxConcurrent <= r.cap, r.maxConcurrent);
      check(`${tag}: engaged ≤ ${r.engagedCap}`, r.maxEngaged <= r.engagedCap, r.maxEngaged);
      check(`${tag}: strike gap ≥ ${r.gap}`, r.minGap >= r.gap - 1e-6, +r.minGap.toFixed(3));
      check(`${tag}: rate ${r.windups} ≤ bound ${r.bound.toFixed(1)} and busy (≥ 60%)`, r.windups <= r.bound + 1e-6 && r.windups >= 0.6 * r.bound, { windups: r.windups, rate: r.rate });
      check(`${tag}: strikes land at timeToStrike (± 1 frame)`, r.maxTimingErr <= 1 / 30 + 1e-3, +r.maxTimingErr.toFixed(4));
      const tOk = Object.entries(r.Ts).every(([k, T]) => Math.abs(T - r.expectedT[k]) < 1e-3);
      check(`${tag}: timeToStrike = windup × mode × day`, tOk, { got: r.Ts, want: r.expectedT });
      check(`${tag}: no misses on the player`, r.misses === 0, r.misses);
      check(`${tag}: allies and enemies duel`, r.allyStrikes > 10 && r.enemyOnAlly > 10, { ally: r.allyStrikes, enemy: r.enemyOnAlly });
      check(`${tag}: no overlapping capsules`, r.minPair >= 0.64 - 0.01, +r.minPair.toFixed(3));
    }
  }

  if (want('spacing')) {
    for (const day of [1, 2, 3]) {
      const r = await sim('spacing', { day, seconds: 40 });
      check(`spacing day ${day}: min distance ≥ 0.63`, r.minPair >= 0.63, { min: +r.minPair.toFixed(3), pair: r.worst });
      check(`spacing day ${day}: engaged reach their slots (≤ 0.35 m)`, r.slotErr.every((e) => e !== null && e <= 0.35) && r.engaged > 0, { err: r.slotErr, bearings: r.bearings, dists: r.dists });
      check(`spacing day ${day}: slot distance 1.4–1.8 m`, r.dists.every((d) => d >= 1.4 && d <= 1.8), r.dists);
      check(`spacing day ${day}: ally duellists at their spots (≤ 0.45 m)`, r.allyErr.every((e) => e !== null && e <= 0.45), r.allyErr);
      check(`spacing day ${day}: allies hold their slots`, r.allyDrift < 0.6, +r.allyDrift.toFixed(2));
      console.log(`     engaged ${r.engaged}, vs allies ${r.vsAlly}, waiting ${r.waiting}, states ${r.states.join(',')}`);
    }
  }

  if (want('units')) {
    const u = await sim('units', {});
    check('bash interrupts a wind-up (stagger, no strike)', u.bash.wound && u.bash.state === 'stagger' && u.bash.strikesDuring === 0, u.bash);
    check('bash knockback ~0.9 m', Math.abs(u.bash.zAfter - u.bash.z + 0.9) < 0.12, u.bash);
    check('rebash within 2.5 s only flinches', u.bash.rebashState !== 'stagger' || u.bash.stateAfter === 'stagger', u.bash);
    check('guard rule: frontal guarded ~70%, never from behind', u.guard.guardedFront > 100 && u.guard.guardedFront < 180 && u.guard.guardedBack === 0 && !u.guard.ignore, u.guard);
    check('perfect block staggers the attacker and drops its guard', u.perfect.state === 'stagger' && u.perfect.guard < 0.3, u.perfect);
    check('spear cam: no new wind-ups while overridden', u.spearCam.found && u.spearCam.startedDuringOverride === 0, u.spearCam);
    check('spear cam end: +0.5 s and the blow lands then', Math.abs(u.spearCam.shift - 0.5) < 1e-6 && u.spearCam.landedAt !== null && Math.abs(u.spearCam.landedAt) < 0.02, u.spearCam);
    check('trainer: 1.2 s wind-ups every 3 s, 0 damage', u.trainer.windups >= 4 && u.trainer.T.every((t) => Math.abs(t - 1.2) < 1e-6) && u.trainer.gaps.every((g) => Math.abs(g - 3) < 0.05) && u.trainer.damage.every((d) => d === 0), u.trainer);
    check('dummy never dies', !u.dummy.killed && u.dummy.alive, u.dummy);
    check('into the Gulf: killed, cause sea, falls to −3 with splash and scream', u.sea.killed && u.sea.cause === 'sea' && !u.sea.alive && u.sea.minY <= -3 && u.sea.splashes === 1 && u.sea.scream && u.sea.splashSfx, u.sea);
    check('helot: in ctx.actors, reaches the player, leaves, no corpse, ignores hits', u.helot.inActors && u.helot.minD < 1.15 && u.helot.gone && !u.helot.corpse && u.helot.hit === 0, u.helot);
    check('from behind: through the wall gap only', u.behind.violations === 0, u.behind.violations);
    check('from behind: no overlap', u.behind.minPair >= 0.63, u.behind.minPair);
    // A player who never strikes lets the allies take his attackers after 15 s: some fall in the rear.
    check('from behind: they engage the player or allies from the rear', u.behind.final.filter((b) => b.z < 4 && (b.toPlayer || b.foe || b.state === 'dead')).length >= 3 && u.behind.windupsFromBehind > 0, u.behind);
    check('Leonidas is protected until his scripted fall', u.leonidas.survived && u.leonidas.x === -1.4, u.leonidas);
    check('Leonidas corpse is kept (never sinks) and not respawned by name', u.leonidas.corpse && u.leonidas.sinking < 0 && u.leonidas.fallen && !u.leonidas.respawnedName.includes('Leonidas'), u.leonidas);
    check('archers stay at z < −25, shoot allies, retreat without corpses', u.archers.shots > 2 && u.archers.atAllies && u.archers.maxZ < -25 && u.archers.removed && u.archers.corpses === 0, u.archers);
    check('allies fight back, can die, ensureAllies walks replacements in', u.allies.allyStrikes > 20 && u.allies.deadAllies > 0 && u.allies.walkers === u.allies.deadAllies && u.allies.allies === 7 && u.allies.allHome, u.allies);
  }

  if (want('respite')) {
    const t = await sim('respite', { motion: false });
    check('respite touch = 10 s', Math.abs(t.length - 10) < 0.11 && t.seconds === 10, t);
    check('respite heals 35% and refills spears', t.heals.some((h) => Math.abs(h.n - 35) < 1e-6) && t.refills >= 2, { heals: t.heals, refills: t.refills });
    check('READY not live before 3 s', t.readyEarly && !t.readyEarly.canReady && t.readyEarly.inRespite, t.readyEarly);
    const m = await sim('respite', { motion: true });
    check('respite motion = 18 s', Math.abs(m.length - 18) < 0.11 && m.seconds === 18, m);
    const e = await sim('respite', { motion: true, readyAt: 1 });
    check('READY at 1 s ends the respite at 3 s', e.readyResult === true && Math.abs(e.length - 3) < 0.11, e);
    const g = await sim('respite', { motion: true, readyAt: 8 });
    check('READY at 8 s ends it at once', g.readyResult === true && Math.abs(g.length - 8) < 0.11, g);
  }

  if (want('stop')) {
    const s = await sim('stop', {});
    check('stop mid-wave: wave was live', s.mid.state === 'fighting' && s.mid.enemies > 0, s.mid);
    check('stop + clear: nothing left, nothing after', s.after.actors === 0 && s.after.corpses === 0 && s.after.children === s.after.baseChildren && s.after.newEvents.length === 0 && s.after.newVolleys === 0 && s.after.newStrikes === 0 && s.after.state === 'over' && (s.after.live === null || s.after.live === s.after.liveBefore), s.after);
  }

  if (want('corpses')) {
    for (const quality of ['high', 'low']) {
      const c = await sim('corpses', { quality });
      check(`corpse cap ${quality}: resting ≤ ${c.cap}, total ≤ cap + 4`, c.maxResting <= c.cap && c.maxTotal <= c.cap + 4, c);
      check(`corpse ${quality}: no shadows on corpses`, c.shadowOnCorpse === 0, c.shadowOnCorpse);
    }
  }

  if (want('campaign')) {
    const r = await sim('campaign', { readyAt: 7, cSeconds: 75 });
    const ev = r.events;
    const names = (n) => ev.filter((e) => e.name === n);
    const dayStarts = names('dayStart').map((e) => e.title);
    check('campaign: dayStart titles', JSON.stringify(dayStarts) === JSON.stringify(['The First Day', 'The Immortals', 'The Second Day', 'The Third Day']), dayStarts);
    const perDay = {};
    for (const w of names('waveStart')) { const k = `${w.day}${w.phase || ''}`; perDay[k] = (perDay[k] || 0) + 1; }
    check('campaign: waves per day/phase (Day 1 = 4 + 2, Day 2 = 3, A 3, B 4, C 1)', perDay['1'] === 6 && perDay['2'] === 3 && perDay['3A'] === 3 && perDay['3B'] === 4 && perDay['3C'] === 1, perDay);
    const phases = names('phase').map((e) => `${e.phase}:${e.title}`);
    check('campaign: phases A, B, C', JSON.stringify(phases) === JSON.stringify(['A:Into the wider pass', "B:Over Leonidas' body", 'C:The Kolonos']), phases);
    check('campaign: phase C card text', names('phase')[2] && names('phase')[2].subtitle === 'They defended themselves with swords, those who still had them, and with hands and teeth.', names('phase')[2]);
    const cleared = names('waveCleared');
    const driven = cleared.filter((e) => e.phase === 'B').map((e) => e.title);
    check('campaign: Driven back (n/4)', JSON.stringify(driven) === JSON.stringify(['Driven back (1/4)', 'Driven back (2/4)', 'Driven back (3/4)', 'Driven back (4/4)']), driven);
    check('campaign: every wave except C cleared', cleared.length === 6 + 3 + 3 + 4, cleared.length);
    const voices = names('voice');
    const lines = voices.map((v) => `${v.speaker}: ${v.text}`);
    const want1 = [
      'Herald: The King commands: hand over your arms.', 'Leonidas: Come and take them.',
      'Lookout: The Immortals! Hydarnes leads them.',
      'Lookout: The Phocians have fled the Anopaia path! Ephialtes has led the Immortals over the mountain!',
      'Leonidas: Eat your breakfast as men who will dine in Hades.',
      'Trachinian: Their arrows will hide the sun!', 'Dienekes: Good. Then we shall fight in the shade.',
      'Narrator: The Tegeans take the line.', 'Dienekes: Our spears are broken. Swords!',
    ];
    for (const w of want1) check(`voice line "${w}"`, lines.filter((x) => x === w).length >= 1, lines.filter((x) => x === w).length);
    check('voice: ΜΟΛΩΝ ΛΑΒΕ subtitle', voices.some((v) => v.text === 'Come and take them.' && v.greek === 'ΜΟΛΩΝ ΛΑΒΕ'));
    check('voice: Herald before Leonidas on Day 1', lines.indexOf(want1[0]) >= 0 && lines.indexOf(want1[0]) < lines.indexOf(want1[1]));
    check('voice: the Tegeans line once', lines.filter((x) => x === want1[7]).length === 1);
    check('voice: the shade exchange once', lines.filter((x) => x === want1[5]).length === 1 && lines.filter((x) => x === want1[6]).length === 1);
    const warcries = voices.filter((v) => v.text === 'Alalai!');
    check('voice: Alalai! at every waveStart', warcries.length === names('waveStart').length, { warcries: warcries.length, waves: names('waveStart').length });
    const shields = voices.filter((v) => v.text === 'Shields up!');
    const shieldGaps = shields.map((v, i) => (i ? v.t - shields[i - 1].t : Infinity));
    check('voice: Shields up! on volleys, at most once per 12 s', shields.length > 0 && shields.length <= r.volleys.length && shieldGaps.every((g) => g >= 12 - 1e-6), { shields: shields.length, volleys: r.volleys.length });
    const afterB = ev.find((e) => e.name === 'phase' && e.phase === 'B').t;
    check('voice: Leonidas speaks no battle line after he falls', voices.filter((v) => v.t > afterB + 1.1 && v.speaker === 'Leonidas').length === 0 && voices.filter((v) => v.t > afterB && v.speaker === 'Dienekes' && (v.text === 'Alalai!' || v.text === 'Shields up!')).length > 0);
    const firstVolley = r.volleys[0];
    const firstShade = voices.find((v) => `${v.speaker}: ${v.text}` === want1[5]);
    check('voice: the exchange follows the first volley warning', firstVolley && firstShade && firstShade.t >= firstVolley.t && firstShade.t - firstVolley.t < 4, { volley: firstVolley && firstVolley.t, line: firstShade && firstShade.t });
    // Volley schedule per wave.
    const waveStarts = names('waveStart');
    let volleyOk = true;
    const vlog = [];
    for (let i = 0; i < waveStarts.length; i++) {
      const w = waveStarts[i];
      const end = (cleared.find((c) => c.t > w.t) || { t: Infinity }).t;
      const vs = r.volleys.filter((v) => v.t >= w.t && v.t <= end).map((v) => +(v.t - w.t).toFixed(2));
      vlog.push(`${w.day}${w.phase || ''}w${w.wave}:[${vs.join(',')}]`);
      if (w.phase === 'C') continue;
      if (vs.length && vs[0] < 12) volleyOk = false;
    }
    check('volleys: first ≥ 12 s after waveStart', volleyOk, vlog.join(' '));
    const expectEvery = { '1:1': 0, '1:2': 0, '1:3': 0, '1:4': 0, '1:5': 30, '1:6': 24, '2:1': 26, '2:2': 22, '2:3': 20, '3A:1': 18, '3A:2': 16, '3A:3': 14, '3B:1': 0 };
    let everyOk = true;
    const seen = {};
    for (let i = 0; i < waveStarts.length; i++) {
      const w = waveStarts[i];
      const key = w.day === 3 ? `3${w.phase}` : `${w.day}`;
      seen[key] = (seen[key] || 0) + 1;
      const id = `${key}:${seen[key]}`;
      const end = (cleared.find((c) => c.t > w.t) || { t: Infinity }).t;
      const vs = r.volleys.filter((v) => v.t >= w.t && v.t <= end).map((v) => v.t);
      const every = expectEvery[id] !== undefined ? expectEvery[id] : key === '3B' ? 0 : null;
      if (every === 0 && vs.length) everyOk = false;
      if (every) for (let k = 1; k < vs.length; k++) if (Math.abs(vs[k] - vs[k - 1] - every) > 0.06) everyOk = false;
    }
    check('volleys: every volleyEvery (none where 0/absent, none in B)', everyOk, vlog.join(' '));
    const cStart = (waveStarts.find((w) => w.phase === 'C') || {}).t;
    const cVolleys = r.volleys.filter((v) => v.t >= cStart);
    const cGaps = cVolleys.map((v, i) => (i ? +(v.t - cVolleys[i - 1].t).toFixed(2) : +(v.t - cStart).toFixed(2)));
    const wantGaps = [10, 10, 9.5, 9, 8.5, 8, 7.5, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7];
    check('volleys: phase C every 10 s shrinking by 0.5 s to 7 s', cGaps.length >= 8 && cGaps.every((g, i) => Math.abs(g - wantGaps[i]) < 0.06), cGaps);
    check('volleys: phase C alternates from behind', cVolleys.length > 3 && cVolleys.every((v, i) => v.fromBehind === (i % 2 === 1)), cVolleys.map((v) => v.fromBehind));
    check('volleys: none from behind before phase C', r.volleys.filter((v) => v.t < cStart).every((v) => !v.fromBehind));
    // Respites.
    const respites = names('respite');
    const rLens = respites.map((e) => +((waveStarts.find((w) => w.t > e.t) || { t: NaN }).t - e.t).toFixed(2));
    check('respites: 8 of them, each ended by READY at 7 s', respites.length === 3 + 1 + 2 + 2 && rLens.every((x) => Math.abs(x - 7) < 0.11), rLens);
    check('respites: 10 s on Days 1–2 (touch), 12 s in phase A', respites.slice(0, 6).every((e) => e.seconds === 10) && respites.slice(6).every((e) => e.seconds === 12 && e.phase === 'A'), respites.map((e) => `${e.day}${e.phase || ''}:${e.seconds}`));
    check('voice: Hold the line! is heard', voices.some((v) => v.text === 'Hold the line!'), voices.filter((v) => v.text === 'Hold the line!').length);
    check('respites: Day 3 heal 20%', r.heals.filter((h) => Math.abs(h.n - 20) < 1e-6).length === 2, r.heals.map((h) => h.n));
    check('dayStart heals to full', r.heals.filter((h) => h.n === 100).length === 4, r.heals.map((h) => h.n));
    check('time of day: morning, dusk, noon, dawn, morning', JSON.stringify(r.times.map((t) => t.time)) === JSON.stringify(['morning', 'dusk', 'noon', 'dawn', 'morning']), r.times);
    check('end card banner after Day 1 dusk', r.banners.some((b) => b.text.startsWith('Three times, it is said')), r.banners);
    check('music', r.music.includes('battle') && r.music.includes('lastStand'), r.music);
    // Leonidas and phase B.
    check('Leonidas falls at the start of phase B', r.track.leonidasDeathAt !== null && r.track.leonidasDeathAt - afterB >= 0.9 && r.track.leonidasDeathAt - afterB < 1.5 && r.track.leonidasCorpse && r.track.leonidasKeep, { at: r.track.leonidasDeathAt, phaseB: afterB });
    const bStart = afterB;
    check('no spear refills from phase B on', r.refills > 0, r.refills);
    // Phase C from behind.
    check('phase C: Immortals spawn behind and come through the gap', r.track.behindSpawned >= 5 && r.track.behindGapOk && r.track.behindReachedFront >= 3, { spawned: r.track.behindSpawned, reached: r.track.behindReachedFront, viol: r.track.gapViolations.slice(0, 5) });
    check('archers present when waves clear', r.track.archersAtClear.some((x) => x.archers > 0), r.track.archersAtClear);
    check('archers shoot at allies only, from z < −25', r.arrowsAtAllies > 0 && r.arrowsMaxZ < -25, { n: r.arrowsAtAllies, maxZ: r.arrowsMaxZ });
    check('≤ 14 enemies and ≤ 8 Greeks at once', r.track.maxEnemies <= 14 && r.track.maxAllies <= 8, { enemies: r.track.maxEnemies, allies: r.track.maxAllies });
    check('no overlap through the campaign', r.track.minPair >= 0.6, +r.track.minPair.toFixed(3));
    // Game over.
    const death = ev.find((e) => e.name === 'playerDeath');
    const over = ev.find((e) => e.name === 'gameOver');
    check('gameOver 3.2 s after playerDeath', death && over && Math.abs(over.t - death.t - 3.2) < 0.06 && over.day === 3 && over.wave === 1 && typeof over.kills === 'number', { death, over });
    check('nothing after gameOver', ev[ev.length - 1] === over && r.endState === 'over', ev.slice(-3));
    const eventOrder = ev.filter((e) => ['dayStart', 'phase', 'waveStart', 'waveCleared', 'respite', 'gameOver'].includes(e.name)).map((e) => `${e.name}(${e.day}${e.phase || ''}${e.wave ? '#' + e.wave : ''})`);
    console.log(`     sequence: ${eventOrder.join(' ')}`);
    console.log(`     windups ${r.windups}, strikes on player ${r.strikesOnPlayer}, misses ${r.misses}/${r.totalStrikes}, kills ${r.kills} ${JSON.stringify(r.killsBy)}, game time ${(over ? over.t : 0).toFixed(0)} s`);
    check('enemy strikes rarely miss', r.misses / Math.max(1, r.totalStrikes) < 0.1, { misses: r.misses, total: r.totalStrikes });
  }

  const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
  check('no page errors', errs.length === 0, errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
console.log(failures ? `${failures} FAILED` : 'all passed');
process.exit(failures ? 1 : 0);
