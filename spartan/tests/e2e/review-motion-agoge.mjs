// (8) The Agoge in motion mode, driven with synthesized motion + real touches: does each step complete?
// Usage: node spartan/tests/e2e/review-motion-agoge.mjs [source] [calibration swing speed=9] [rot=90] [extra synth opts JSON]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const calSpeed = Number(process.argv[3] || 9);
const rot = Number(process.argv[4] || 90);
const extra = JSON.parse(process.argv[5] || '{}');
const G = await openGame({ source });
try {
  const res = await G.ev((R, g, ctx, a) => {
    const PI = Math.PI;
    const opts = Object.assign({ rot: a.rot }, a.extra);
    ctx.settings.control = 'motion';
    ctx.settings.sensitivity = 1;
    g.startTraining();
    R.frames(10);
    const tr = ctx.training;
    const out = [];
    const click = (sel) => {
      const b = document.querySelector('#training ' + sel);
      if (!b) return 'missing ' + sel;
      b.click();
      return 'ok';
    };
    const tap = (sel, id) => {
      const b = document.querySelector(sel);
      const rc = b.getBoundingClientRect();
      R.touch('pointerdown', rc.left + rc.width / 2, rc.top + rc.height / 2, id);
      R.touch('pointerup', rc.left + rc.width / 2, rc.top + rc.height / 2, id);
    };
    const waitNext = (idx, max = 300) => R.until(() => tr.stepIndex !== idx, max);
    const play = (script) => R.play(script, opts, { source: a.source });
    const hint = () => document.querySelector('#training .tr-hint').textContent;
    const pips = () => document.querySelectorAll('#training .tr-pips .is-on').length;
    play([{ kind: 'idle', duration: 1.0 }]);
    let idx0 = tr.stepIndex, title0 = '';
    const begin = () => { idx0 = tr.stepIndex; title0 = document.querySelector('#training .tr-title').textContent; };
    const record = (id, extraInfo = '') => {
      const idx = idx0;
      extraInfo = `[step ${idx} ${title0}] yaw=${ctx.player.yaw.toFixed(3)} sens=${ctx.settings.sensitivity} ` + extraInfo;
      const n = waitNext(idx, 200);
      out.push({ id, done: tr.stepIndex !== idx, pips: pips(), hint: hint(), info: extraInfo, gest: R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '')).join(','), swings: R.evs('swing').map((e) => e.kind + (e.kind === 'slash' ? ':' + R.tok(e.dir) : '')).join(',') });
      R.clearLog();
    };
    const shieldDuel = (mode, reps) => {
      // react to the trainer's wind-up with a real touch on the glass
      let presses = 0;
      let pending = null, held = false, releaseAt = Infinity;
      const off = ctx.events.on('enemyWindup', (e) => {
        if (!e.targetIsPlayer) return;
        const now = ctx.time.gnow;
        pending = mode === 'perfect' ? { at: now + e.timeToStrike - 0.15, strike: now + e.timeToStrike } : { at: now + 0.3, strike: now + e.timeToStrike };
      });
      const idx = tr.stepIndex;
      let f = 0;
      while (tr.stepIndex === idx && f++ < 60 * 25) {
        R.frames(1);
        const now = ctx.time.gnow;
        if (pending && !held && now >= pending.at) {
          R.touch('pointerdown', 480, 330, 61);
          held = true;
          presses++;
          releaseAt = pending.strike + 0.2;
          pending = null;
        }
        if (held && now >= releaseAt) {
          R.touch('pointerup', 480, 330, 61);
          held = false;
        }
      }
      if (held) R.touch('pointerup', 480, 330, 61);
      off();
      return presses;
    };

    // I grip
    begin();
    R.clearLog();
    click('.tr-ok');
    record('grip');
    // II calibrate: 5 slashes as hard as in battle
    begin();
    const cal = [];
    for (let i = 0; i < 5; i++) cal.push({ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: i % 2 ? PI : 0, speed: a.calSpeed });
    play(cal.concat([{ kind: 'idle', duration: 0.5 }]));
    const calText = document.querySelector('#training .tr-calib-t').textContent;
    const calShown = document.querySelector('#training .tr-calib').classList.contains('is-shown');
    click('[data-act="apply"]');
    record('calibrate', `panel=${calShown} "${calText}" → sensitivity ${ctx.settings.sensitivity}`);
    // III right and left
    begin();
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: 0 }, { kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: 0 }, { kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: PI }, { kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: PI }, { kind: 'idle', duration: 0.4 }]);
    record('slashLR');
    // IV overhead chop: raise, chop (twice)
    begin();
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'raise' }, { kind: 'swing', dir: -PI / 2 }, { kind: 'idle', duration: 0.5 }, { kind: 'raise' }, { kind: 'swing', dir: -PI / 2 }, { kind: 'idle', duration: 0.4 }]);
    record('overhead');
    // V uppercut from a low guard
    begin();
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: PI / 2, windup: 0.4 }, { kind: 'idle', duration: 0.4 }]);
    record('uppercut');
    // VI thrust x3
    begin();
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.4 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.4 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.4 }]);
    record('thrust');
    // VII block x3, VIII perfect x2
    begin();
    const p1 = shieldDuel('block');
    record('block', `${p1} presses`);
    begin();
    const p2 = shieldDuel('perfect');
    record('perfect', `${p2} presses`);
    // IX bash x2: thumb on the glass, push forward
    begin();
    R.touch('pointerdown', 480, 330, 62);
    R.frames(15);
    play([{ kind: 'idle', duration: 0.2 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.9 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.5 }]);
    R.touch('pointerup', 480, 330, 62);
    record('bash');
    // X volley: hold the glass from the warning until the end
    begin();
    {
      const idx = tr.stepIndex;
      let held = false;
      const off = ctx.events.on('volley', (v) => {
        if (v.phase === 'warning' && !held) { R.touch('pointerdown', 480, 330, 63); held = true; }
        if (v.phase === 'end' && held) { R.touch('pointerup', 480, 330, 63); held = false; }
      });
      R.until(() => tr.stepIndex !== idx, 60 * 15);
      off();
      if (held) R.touch('pointerup', 480, 330, 63);
      record('volley');
    }
    // XI spear: weapon button, thrust x2, throw at the far target
    begin();
    tap('#controls .ctl-swap', 64);
    R.frames(50);
    const w = ctx.player.weapon;
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.45 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.6 }]);
    const aim = ctx.actors.filter((x) => x.role === 'dummy').map((x) => `${x.pos.x.toFixed(1)},${x.pos.z.toFixed(1)} angle ${Math.atan2(-(x.pos.x - ctx.player.pos.x), -(x.pos.z - ctx.player.pos.z)).toFixed(3)}`).join(' | ');
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'throw' }, { kind: 'idle', duration: 3.0 }]);
    const impacts = R.evs('spearImpact').map((e) => e.target).join(',');
    record('spear', `weapon=${w} dummies: ${aim}; yaw ${ctx.player.yaw.toFixed(3)}; spear impacts [${impacts}]`);
    // XII twin fangs
    begin();
    play([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: 0, arc: 1.0, speed: 9 }, { kind: 'swing', dir: PI, arc: 1.0, speed: 9 }, { kind: 'idle', duration: 0.6 }]);
    record('twin');
    return { out, finished: document.querySelector('#training').classList.contains('is-finished') };
  }, { source, calSpeed, rot, extra });
  for (const r of res.out) console.log(`${r.done ? 'ok  ' : 'FAIL'} ${r.id.padEnd(9)} pips=${r.pips} ${r.info ? '| ' + r.info : ''}${!r.done ? ` | hint="${r.hint}" gestures=[${r.gest}] swings=[${r.swings}]` : ''}`);
  console.log('finished:', res.finished);
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
