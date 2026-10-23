// (1c) Viewmodel sync with motion swings in the real game (DESIGN.md §11 motion sync): every motion
// swing event animates the blade on its own frame, in the payload's (snapped) direction, already
// moving (no anticipation), with the contact pose on the frame the strike resolves; a new swing
// restarts the arm mid-swing; a swing during a hit-stop ends it; thrusts reach full extension and
// throws release on their strike frame.
// Usage: node spartan/tests/e2e/review-motion-vmsync.mjs [source: inject|android|web]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const G = await openGame({ source });
const PI = Math.PI;
const names = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
let fails = 0;
const report = (ok, msg) => {
  if (!ok) fails++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${msg}`);
};
const deg = (a) => `${Math.round((a * 180) / PI)}°`;

try {
  // Page-side recorders (once): the sword tip in screen tan units, the arm, and the hit-stop that
  // was running when the frame started.
  await G.ev((R, g, ctx) => {
    R.tip = () => {
      const h = ctx.viewmodel.debug.hand;
      const { x, y, z, w } = h.q;
      const reach = 0.594; // meshes.js SWORD.tipY: the blade runs along the hand's +Y
      const t = [h.p.x + reach * 2 * (x * y - w * z), h.p.y + reach * (1 - 2 * (x * x + z * z)), h.p.z + reach * 2 * (y * z + w * x)];
      return [t[0] / -t[2], t[1] / -t[2]];
    };
    R.step1 = () => {
      const hs0 = ctx.time.hitStop;
      R.frames(1);
      const a = ctx.viewmodel.debug.arm;
      const tip = R.tip();
      return { f: R.frameNo, sx: tip[0], sy: tip[1], hz: ctx.viewmodel.debug.hand.p.z, arm: a ? a.kind : null, at: a ? a.t : null, hs0, shown: ctx.viewmodel.debug.shown };
    };
    R.spearLog = [];
    ctx.events.on('spearThrown', () => R.spearLog.push(R.frameNo));
    R.dummy = () => {
      ctx.ai.clear();
      const d = g.spawn('levy', 0, -1.7, {});
      d.blockChance = d.baseBlock = 0;
      d.hp = d.maxHp = 1e6;
      d.nextAttackAt = 1e9;
      return d;
    };
    return true;
  });

  // ---- A. eight directions --------------------------------------------------------------------
  for (let i = 0; i < 8; i++) {
    const want = (i * PI) / 4 > PI ? (i * PI) / 4 - 2 * PI : (i * PI) / 4;
    const r = await G.ev((R, g, ctx, a) => {
      if (a.source === 'android') window.__nativeRot = 90;
      R.arena();
      R.dummy();
      R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: a.source });
      R.frames(20);
      R.clearLog();
      const s = g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: a.dir, speed: 9 }, { kind: 'idle', duration: 0.5 }], { rot: 90 });
      R.feed(s, a.source);
      const recs = [R.step1()];
      const n = Math.ceil((s[s.length - 1].t - s[0].t + 0.3) * 60);
      for (let k = 0; k < n; k++) recs.push(R.step1());
      return { recs, swing: R.evs('swing').map((e) => ({ f: e.frame, dir: e.dir, kind: e.kind, src: e.src })), hit: R.evs('hit').map((e) => e.frame) };
    }, { dir: want, source });
    const sw = r.swing[0];
    if (!sw || sw.kind !== 'slash') {
      report(false, `${names[i]}: no motion slash (${JSON.stringify(r.swing)})`);
      continue;
    }
    const k0 = r.recs.findIndex((x) => x.f === sw.f);
    const prev = r.recs[k0 - 1];
    const dx = Math.cos(sw.dir);
    const dy = Math.sin(sw.dir);
    const along = (x) => x.sx * dx + x.sy * dy;
    const perp = (x) => -x.sx * dy + x.sy * dx;
    const ev = r.recs[k0];
    const moved = Math.hypot(ev.sx - prev.sx, ev.sy - prev.sy);
    const kc = r.recs.findIndex((x, k) => k >= k0 && along(x) >= -1e-3);
    const hitF = r.hit.length ? r.hit[0] : null;
    const steps = [];
    for (let k = k0 + 1; k <= kc; k++) steps.push(along(r.recs[k]) - along(r.recs[k - 1]));
    const ang = Math.atan2(r.recs[kc].sy - ev.sy, r.recs[kc].sx - ev.sx);
    const err = Math.abs(Math.atan2(Math.sin(ang - sw.dir), Math.cos(ang - sw.dir)));
    const drift = Math.max(...r.recs.slice(k0, kc + 1).map((x) => Math.abs(perp(x))));
    const ok =
      ev.arm === 'slash' && ev.at <= 1 / 60 + 1e-6 && moved > 0.03 && along(ev) < -0.3 &&
      steps.length > 0 && steps.every((d) => d > 0.05) && err < 0.35 && drift < 0.12 && hitF != null && r.recs[kc].f === hitF;
    report(ok, `${names[i].padEnd(2)} swing ${deg(sw.dir)}: drawn on its own frame (moved ${moved.toFixed(2)}, starts ${along(ev).toFixed(2)} behind centre), per-frame progress [${steps.map((d) => d.toFixed(2)).join(' ')}], sweep ${deg(ang)} (err ${deg(err)}), drift ${drift.toFixed(2)}, contact at +${r.recs[kc].f - sw.f} frames, hit at +${hitF == null ? '-' : hitF - sw.f}`);
  }

  // ---- B. flowing R,L,R,L: every swing restarts the arm in its own direction on its frame -------
  const rr = await G.ev((R, g, ctx, a) => {
    R.arena();
    R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: a.source });
    R.clearLog();
    const sw = (dir, last) => Object.assign({ kind: 'swing', dir, ret: 0, arc: 0.8, speed: 14 }, last ? {} : { recoil: 0 });
    const s = g.synth([{ kind: 'idle', duration: 0.3 }, sw(0), Object.assign(sw(Math.PI), { windup: 0 }), Object.assign(sw(0), { windup: 0 }), Object.assign(sw(Math.PI, true), { windup: 0 }), { kind: 'idle', duration: 0.6 }], { rot: 90 });
    R.feed(s, a.source);
    const recs = [];
    const n = Math.ceil((s[s.length - 1].t - s[0].t + 0.3) * 60);
    for (let k = 0; k < n; k++) recs.push(R.step1());
    return { recs, swing: R.evs('swing').map((e) => ({ f: e.frame, dir: e.dir })) };
  }, { source });
  report(rr.swing.length === 4, `flowing R,L,R,L gives 4 swings (${rr.swing.map((s) => deg(s.dir)).join(', ')}), frames apart: ${rr.swing.map((s, i) => (i ? s.f - rr.swing[i - 1].f : 0)).slice(1).join(', ')}`);
  for (const sw of rr.swing) {
    const k = rr.recs.findIndex((x) => x.f === sw.f);
    const cur = rr.recs[k];
    const nxt = rr.recs[k + 2];
    const prevArm = rr.recs[k - 1];
    const dx = Math.cos(sw.dir);
    const dy = Math.sin(sw.dir);
    const adv = nxt ? (nxt.sx - cur.sx) * dx + (nxt.sy - cur.sy) * dy : 0;
    const ok = cur && cur.arm === 'slash' && cur.at <= 1 / 60 + 1e-6 && adv > 0.15;
    report(ok, `restart: swing ${deg(sw.dir)} at frame ${sw.f}: arm ${cur && cur.arm} t=${cur && cur.at.toFixed(4)} (was ${prevArm && prevArm.arm} t=${prevArm && prevArm.at != null ? prevArm.at.toFixed(3) : '-'}), tip advances ${adv.toFixed(2)} along it over the next 2 frames`);
  }

  // ---- C. a swing during a hit-stop ends the freeze; the blade moves by the next frame ---------
  const hs = await G.ev((R, g, ctx, a) => {
    R.arena();
    R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: a.source });
    R.clearLog();
    const s = g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: Math.PI, speed: 9 }, { kind: 'idle', duration: 0.5 }], { rot: 90 });
    R.feed(s, a.source);
    const recs = [];
    let armed = false;
    for (let k = 0; k < 90; k++) {
      // a long hit-stop (a perfect block's is 0.11 s) just before the swing is recognized
      if (!armed && ctx.gestures.debug && ctx.gestures.debug.m > 4) {
        ctx.fx.hitStop(0.3);
        armed = true;
      }
      recs.push(R.step1());
    }
    return { recs, swing: R.evs('swing').map((e) => ({ f: e.frame, dir: e.dir })) };
  }, { source });
  {
    const sw = hs.swing[0];
    const k = sw ? hs.recs.findIndex((x) => x.f === sw.f) : -1;
    const seq = k >= 0 ? hs.recs.slice(k, k + 3) : [];
    const movingAt = seq.findIndex((x) => x.arm === 'slash' && x.at > 0);
    const ok = sw && seq[0].hs0 > 0 && seq.length > 1 && seq[1].hs0 === 0 && movingAt >= 0 && movingAt <= 1;
    report(ok, `hit-stop: swing at frame ${sw && sw.f} (hit-stop running as the frame started: ${seq[0] && seq[0].hs0.toFixed(3)} s; next frame ${seq[1] && seq[1].hs0.toFixed(3)} s); the blade moves ${movingAt === 0 ? 'on the same frame' : movingAt === 1 ? 'on the next frame' : 'late'}`);
  }

  // ---- D. motion thrust: starts on its frame, fully extended on the strike frame ---------------
  const th = await G.ev((R, g, ctx, a) => {
    R.arena();
    R.dummy();
    R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: a.source });
    R.frames(20);
    R.clearLog();
    const s = g.synth([{ kind: 'idle', duration: 0.4 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.6 }], { rot: 90 });
    R.feed(s, a.source);
    const recs = [];
    const n = Math.ceil((s[s.length - 1].t - s[0].t + 0.3) * 60);
    for (let k = 0; k < n; k++) recs.push(R.step1());
    return { recs, swing: R.evs('swing').map((e) => ({ f: e.frame, kind: e.kind })), hit: R.evs('hit').map((e) => e.frame) };
  }, { source });
  {
    const sw = th.swing[0];
    const k0 = sw ? th.recs.findIndex((x) => x.f === sw.f) : -1;
    const kh = th.hit.length ? th.recs.findIndex((x) => x.f === th.hit[0]) : -1;
    const span = k0 >= 0 ? th.recs.slice(k0, k0 + 30).filter((x) => x.arm === 'thrust') : [];
    const most = span.length ? Math.min(...span.map((x) => x.hz)) : 0;
    const ok = sw && sw.kind === 'thrust' && th.recs[k0].arm === 'thrust' && th.recs[k0].hz < th.recs[k0 - 1].hz && kh >= 0 && th.recs[kh].hz <= most + 0.003;
    report(ok, `thrust: swing ${sw && sw.kind} at frame ${sw && sw.f}, hand forward on its own frame (${k0 >= 0 ? (th.recs[k0].hz - th.recs[k0 - 1].hz).toFixed(3) : '-'}), hit at +${kh >= 0 ? th.recs[kh].f - sw.f : '-'} frames with the hand at ${kh >= 0 ? th.recs[kh].hz.toFixed(3) : '-'} (fullest ${most.toFixed(3)})`);
  }

  // ---- E. motion throw: the viewmodel lets go of the spear on the frame the world spear spawns ---
  const tw = await G.ev((R, g, ctx, a) => {
    R.arena();
    ctx.actions.push({ type: 'switchWeapon', weapon: 'spear' });
    R.play([{ kind: 'idle', duration: 1.4 }], { rot: 90 }, { source: a.source });
    R.clearLog();
    R.spearLog.length = 0;
    const s = g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'throw' }, { kind: 'idle', duration: 0.8 }], { rot: 90 });
    R.feed(s, a.source);
    const recs = [];
    const n = Math.ceil((s[s.length - 1].t - s[0].t + 0.3) * 60);
    for (let k = 0; k < n; k++) recs.push(R.step1());
    return { weapon: ctx.player.weapon, recs, swing: R.evs('swing').map((e) => ({ f: e.frame, kind: e.kind })), thrown: R.spearLog.slice() };
  }, { source });
  {
    const sw = tw.swing.find((s) => s.kind === 'throw');
    const kGone = tw.recs.findIndex((x) => sw && x.f >= sw.f && x.shown !== 'spear');
    const gone = kGone >= 0 ? tw.recs[kGone].f : null;
    const ok = sw && tw.thrown.length === 1 && gone === tw.thrown[0];
    report(ok, `throw: swing ${sw ? 'throw' : '-'} at frame ${sw && sw.f}, world spear at frame ${tw.thrown[0]}, viewmodel lets go at frame ${gone} (arm ${kGone >= 0 ? tw.recs[kGone].arm : '-'})`);
  }

  const errs = await G.errors();
  report(errs.length === 0, `no page errors${errs.length ? ': ' + errs.slice(0, 3).join(' | ') : ''}`);
  console.log(fails ? `${fails} FAILED` : 'all ok');
  process.exitCode = fails ? 1 : 0;
} finally {
  await G.close();
}
