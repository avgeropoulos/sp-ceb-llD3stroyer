// (3) Combos via motion in the real game: R,L,R → fury; U,D → cleave; B,T → answer.
// Usage: node spartan/tests/e2e/review-motion-combos.mjs [source] [rot]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const rot = Number(process.argv[3] || 90);
const G = await openGame({ source });
const PI = Math.PI;
const cases = [
  { name: 'fury: R,L,R with return to guard (ret 2.5)', want: 'fury', script: [{ kind: 'swing', dir: 0 }, { kind: 'swing', dir: PI }, { kind: 'swing', dir: 0 }] },
  { name: 'fury: R,L,R flowing (no return, no wind-up)', want: 'fury', script: [{ kind: 'swing', dir: 0, ret: 0 }, { kind: 'swing', dir: PI, ret: 0, windup: 0 }, { kind: 'swing', dir: 0, windup: 0, ret: 0 }] },
  { name: 'fury: R,L,R flowing, 0.1 s pauses', want: 'fury', script: [{ kind: 'swing', dir: 0, ret: 0 }, { kind: 'idle', duration: 0.1 }, { kind: 'swing', dir: PI, ret: 0, windup: 0 }, { kind: 'idle', duration: 0.1 }, { kind: 'swing', dir: 0, windup: 0, ret: 0 }] },
  { name: 'fury: R,L,R fast (arc 1.0, speed 9, return 4)', want: 'fury', script: [{ kind: 'swing', dir: 0, arc: 1.0, ret: 4 }, { kind: 'swing', dir: PI, arc: 1.0, ret: 4 }, { kind: 'swing', dir: 0, arc: 1.0, ret: 4 }] },
  { name: 'cleave: U then D (default)', want: 'cleave', script: [{ kind: 'swing', dir: PI / 2 }, { kind: 'swing', dir: -PI / 2 }] },
  { name: 'cleave: U then D flowing (chop from the top of the uppercut)', want: 'cleave', script: [{ kind: 'swing', dir: PI / 2, ret: 0 }, { kind: 'swing', dir: -PI / 2, windup: 0 }] },
  { name: 'cleave: low-guard uppercut (windup 0.5) then D', want: 'cleave', script: [{ kind: 'swing', dir: PI / 2, windup: 0.5, ret: 0 }, { kind: 'idle', duration: 0.1 }, { kind: 'swing', dir: -PI / 2, windup: 0 }] },
  { name: 'cleave: U then raise + chop (as the Agoge teaches the chop)', want: 'cleave', script: [{ kind: 'swing', dir: PI / 2 }, { kind: 'raise' }, { kind: 'swing', dir: -PI / 2 }] },
];
try {
  for (const c of cases) {
    const r = await G.ev((R, g, ctx, a) => {
      if (a.source === 'android') window.__nativeRot = a.rot;
      if (a.source === 'web') window.__webRot = a.rot;
      R.arena();
      const d = g.spawn('levy', 0, -1.7, {});
      d.blockChance = d.baseBlock = 0;
      d.hp = d.maxHp = 1e6;
      d.nextAttackAt = 1e9;
      R.play([{ kind: 'idle', duration: 1.2 }], { rot: a.rot }, { source: a.source });
      R.clearLog();
      R.play([{ kind: 'idle', duration: 0.3 }, ...a.script, { kind: 'idle', duration: 0.8 }], { rot: a.rot }, { source: a.source });
      return {
        gest: R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '') + '@' + e.t.toFixed(2)).join(' '),
        swings: R.evs('swing').map((e) => (e.kind === 'slash' ? R.tok(e.dir) : e.kind) + '@' + e.gnow.toFixed(2)).join(' '),
        combos: R.evs('combo').map((e) => e.id).join(','),
      };
    }, { source, rot, script: c.script });
    const ok = r.combos.split(',').includes(c.want);
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${c.name}\n     gestures: ${r.gest}\n     swings:   ${r.swings}\n     combos:   [${r.combos}]`);
  }
  // B,T: touch the glass, push forward (bash), lift the thumb, push forward again (thrust)
  for (const lift of [0.15, 0.3, 0.5]) {
    const r = await G.ev((R, g, ctx, a) => {
      R.arena();
      const d = g.spawn('levy', 0, -1.5, {});
      d.blockChance = d.baseBlock = 0;
      d.hp = d.maxHp = 1e6;
      d.nextAttackAt = 1e9;
      R.play([{ kind: 'idle', duration: 1.2 }], { rot: a.rot }, { source: a.source });
      R.clearLog();
      R.touch('pointerdown', 480, 300, 21);
      R.frames(20);
      const s = g.synth([{ kind: 'idle', duration: 0.2 }, { kind: 'thrust' }, { kind: 'idle', duration: a.lift }, { kind: 'thrust' }, { kind: 'idle', duration: 0.6 }], { rot: a.rot });
      R.feed(s, a.source);
      // lift the thumb once the bash has started (~a reaction after the first push)
      let lifted = false;
      const n = Math.ceil((s[s.length - 1].t - s[0].t + 0.3) * 60);
      for (let i = 0; i < n; i++) {
        R.frames(1);
        if (!lifted && R.evs('swing').length > 0) {
          lifted = true;
          R.liftAt = R.vt;
        }
        if (lifted && R.vt - R.liftAt > 0.12 && R.liftAt > 0) {
          R.touch('pointerup', 480, 300, 21);
          R.liftAt = -1;
        }
      }
      return {
        gest: R.evs('gesture').map((e) => e.type + '@' + e.t.toFixed(2)).join(' '),
        swings: R.evs('swing').map((e) => e.kind + '@' + e.gnow.toFixed(2)).join(' '),
        combos: R.evs('combo').map((e) => e.id).join(','),
      };
    }, { source, rot, lift });
    const ok = r.combos.split(',').includes('answer');
    console.log(`${ok ? 'ok  ' : 'FAIL'} answer: B (shield+push), lift thumb, T (push) with ${lift}s between pushes\n     gestures: ${r.gest}\n     swings:   ${r.swings}\n     combos:   [${r.combos}]`);
  }
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
