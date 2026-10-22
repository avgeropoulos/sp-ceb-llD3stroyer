// (7b) Duel in the real game, motion mode: an enemy winds up; a scripted player reacts `react` ms after
// enemyWindup with a real touch on the glass (shield), lifts the thumb after the blow, then counters
// with a synthesized slash (motion). Can a motion player block and win?
// Usage: node spartan/tests/e2e/review-motion-duel.mjs [source] [kind=levy] [react ms=300] [counter=slash|thrust] [day=1]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const kind = process.argv[3] || 'levy';
const react = Number(process.argv[4] || 300) / 1000;
const counter = process.argv[5] || 'slash';
const day = Number(process.argv[6] || 1);
const G = await openGame({ source });
try {
  const r = await G.ev((R, g, ctx, a) => {
    R.arena({ day: a.day });
    const opts = { rot: 90 };
    R.play([{ kind: 'idle', duration: 1.0 }], opts, { source: a.source });
    const foe = g.spawn(a.kind, 0.2, -5, {});
    const t0 = ctx.time.gnow;
    let pending = null; // { pressAt, strikeAt }
    let held = false;
    let releaseAt = Infinity;
    let counterAt = Infinity;
    let flip = 0;
    const timeline = [];
    let lastLog = 0;
    ctx.events.on('enemyWindup', (e) => {
      if (!e.targetIsPlayer) return;
      pending = { pressAt: ctx.time.gnow + a.react, strikeAt: ctx.time.gnow + e.timeToStrike, T: e.timeToStrike };
      timeline.push(`${(ctx.time.gnow - t0).toFixed(2)} windup T=${e.timeToStrike.toFixed(2)} (react at +${a.react})`);
    });
    ctx.events.on('block', (e) => { if (e.by === 'player') timeline.push(`${(ctx.time.gnow - t0).toFixed(2)} BLOCK ${e.perfect ? 'perfect' : e.kind || 'normal'} raise=${ctx.player.shieldRaise.toFixed(2)}`); });
    ctx.events.on('playerHurt', (e) => timeline.push(`${(ctx.time.gnow - t0).toFixed(2)} HURT ${e.damage} hp=${e.hp} shieldHeld=${ctx.input.shieldHeld} raise=${ctx.player.shieldRaise.toFixed(2)}`));
    ctx.events.on('hit', (e) => { if (e.byPlayer) timeline.push(`${(ctx.time.gnow - t0).toFixed(2)} player hit ${e.kind} dmg=${(e.damage || 0).toFixed(1)}${e.guarded ? ' (guarded)' : ''}`); });
    ctx.events.on('kill', (e) => timeline.push(`${(ctx.time.gnow - t0).toFixed(2)} KILL`));
    const maxT = 40;
    while (ctx.time.gnow - t0 < maxT && foe.alive && ctx.player.alive) {
      R.frames(1);
      const now = ctx.time.gnow;
      if (pending && !held && now >= pending.pressAt) {
        R.touch('pointerdown', 480, 300, 51);
        held = true;
        releaseAt = pending.strikeAt + 0.15;
        pending = null;
      }
      if (held && now >= releaseAt) {
        R.touch('pointerup', 480, 300, 51);
        held = false;
        counterAt = now + 0.15;
        releaseAt = Infinity;
      }
      if (now >= counterAt) {
        counterAt = Infinity;
        const seg = a.counter === 'thrust' ? { kind: 'thrust' } : { kind: 'swing', dir: flip++ % 2 ? Math.PI : 0, speed: 10 };
        R.feed(g.synth([{ kind: 'idle', duration: 0.05 }, seg, { kind: 'idle', duration: 0.1 }], opts), a.source);
      }
      if (now - lastLog > 2) {
        lastLog = now;
      }
    }
    return {
      result: !ctx.player.alive ? 'player died' : !foe.alive ? 'enemy killed' : 'timeout',
      t: +(ctx.time.gnow - t0).toFixed(2),
      hp: ctx.player.hp,
      foeHp: +foe.hp.toFixed(1),
      blocks: R.evs('block', (e) => e.by === 'player').length,
      hurts: R.evs('playerHurt').length,
      swings: R.evs('swing').map((e) => e.kind).join(','),
      timeline,
    };
  }, { source, kind, react, counter, day });
  console.log(`${kind} day ${day}, react ${react * 1000} ms, counter ${counter} (${source}): ${r.result} after ${r.t}s; player hp ${r.hp}, foe hp ${r.foeHp}; blocks ${r.blocks}, hurt ${r.hurts}`);
  console.log('  ' + r.timeline.join('\n  '));
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
