// (9a) Sensitivity × swing strength in the real game: 8-way slashes, thrusts, throws (spear) and walking.
// Usage: node spartan/tests/e2e/review-motion-sens.mjs [source] [extra synth opts JSON]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const extra = JSON.parse(process.argv[3] || '{}');
const G = await openGame({ source });
try {
  const rows = await G.ev((R, g, ctx, a) => {
    const PI = Math.PI;
    const opts = Object.assign({ rot: 90 }, a.extra);
    const out = [];
    const names = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
    for (const sens of [0.6, 1.0, 1.6]) {
      R.arena();
      ctx.settings.sensitivity = sens;
      R.play([{ kind: 'idle', duration: 1.0 }], opts, { source: a.source });
      for (const speed of [5, 6.5, 9, 13, 18]) {
        const res = [];
        for (let i = 0; i < 8; i++) {
          R.clearLog();
          const dir = i <= 4 ? (i * PI) / 4 : (i * PI) / 4 - 2 * PI;
          R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir, speed }, { kind: 'idle', duration: 0.5 }], opts, { source: a.source });
          const gs = R.evs('gesture').map((e) => e.type === 'slash' ? R.tok(e.dir) : e.type);
          res.push(gs.length === 1 && gs[0] === names[i] ? '✓' : `${names[i]}→[${gs.join(',')}]`);
        }
        out.push(`sens ${sens} slash speed ${String(speed).padEnd(4)}: ${res.filter((x) => x === '✓').length}/8 ${res.filter((x) => x !== '✓').join(' ')}`);
      }
      // thrusts (distance 0.25/0.35/0.45 m in 0.18 s)
      for (const distance of [0.2, 0.3, 0.45]) {
        R.clearLog();
        R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust', distance }, { kind: 'idle', duration: 0.5 }], opts, { source: a.source });
        out.push(`sens ${sens} thrust ${distance} m: [${R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '')).join(',')}]`);
      }
      // throws with the spear
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      R.frames(50);
      for (const speed of [8, 10, 13, 18]) {
        R.clearLog();
        if (!ctx.player.hasSpearInHand) { ctx.player.refillSpears(); R.frames(5); }
        R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'throw', speed }, { kind: 'idle', duration: 2.5 }], opts, { source: a.source });
        R.until(() => !ctx.fx.cameraOverridden, 600);
        R.frames(60);
        out.push(`sens ${sens} throw speed ${speed}: gestures [${R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '') + (e.power != null ? '(' + e.power.toFixed(2) + ')' : '')).join(',')}] swings [${R.evs('swing').map((e) => e.kind).join(',')}]`);
      }
      g.action({ type: 'switchWeapon', weapon: 'sword' });
      R.frames(50);
      // walking and idling
      R.clearLog();
      R.play([{ kind: 'walk', duration: 10 }, { kind: 'idle', duration: 5 }], opts, { source: a.source });
      out.push(`sens ${sens} walk 10 s + idle 5 s: [${R.evs('gesture').map((e) => e.type).join(',')}]`);
    }
    return out;
  }, { source, extra });
  console.log(`${source} ${JSON.stringify(extra)}\n  ` + rows.join('\n  '));
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
