// Scripted checks for game/player.js + game/combat.js, run in the gameplay sandbox (Playwright:
// tests/e2e/gameplay-checks.mjs) against the stub actors of gameplay-harness.js. Each check
// starts from h.reset() and steps the game deterministically at 60 Hz.
import { TIMING } from '../../src/main/assets/game/js/game/rules.js';

const DT = 1 / 60;

function ok(cond, msg) {
  if (!cond) throw new Error(msg);
}
function near(a, b, eps, msg) {
  if (!(Math.abs(a - b) <= eps)) throw new Error(`${msg}: got ${a}, want ${b} ±${eps}`);
}
// Position at view angle `a` (positive = left) and distance d in front of the origin (yaw 0).
const front = (a, d) => [-Math.sin(a) * d, -Math.cos(a) * d];

function spawnAt(h, kind, a, d, o = {}) {
  const [x, z] = front(a, d);
  return h.spawn(kind, x, z, { blockChance: 0, hp: 1000, ...o });
}

const MELEE = { kind: 'melee', damage: 10, reach: 1.9, halfAngle: 0.45, heavy: false, target: 'player' };
const AXE = { kind: 'axe', damage: 28, reach: 1.8, halfAngle: 0.45, heavy: true, target: 'player' };

function getSpear(h) {
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.7);
  ok(h.player.weapon === 'spear' && h.player.hasSpearInHand, 'spear drawn');
}

// Refill and make sure the spear is the weapon in hand.
function armSpear(h) {
  h.player.refillSpears();
  if (h.player.weapon !== 'spear') {
    h.action({ type: 'switchWeapon', weapon: 'spear' });
    h.stepTime(0.35);
  }
  ok(h.player.weapon === 'spear' && h.player.hasSpearInHand, 'armed with a spear');
}

function throwAndWait(h, power, src = 'touch', maxSeconds = 5) {
  const before = h.events('spearImpact').length;
  h.action({ type: 'throw', power, src });
  const n = h.stepUntil(() => h.events('spearImpact').length > before, Math.round(maxSeconds / DT));
  ok(n > 0, 'the spear landed');
  return h.events('spearImpact')[before].payload;
}

export const CHECKS = {
  slashHitsExactlyOne(h) {
    h.reset();
    const a = spawnAt(h, 'levy', 0.35, 1.5);
    const b = spawnAt(h, 'levy', 0, 1.6);
    const c = spawnAt(h, 'levy', -0.35, 1.5);
    h.step(1);
    ok(h.player.focusTarget === b, 'focus is the centre levy');
    h.action({ type: 'slash', dir: 0, power: 0.5 });
    h.stepTime(0.4);
    const hits = h.events('hit');
    ok(hits.length === 1, `one hit, got ${hits.length}`);
    ok(hits[0].payload.target === b, 'the focus target was hit');
    near(hits[0].payload.damage, 34, 1e-9, 'slash damage at power 0.5');
    ok(a.hitsTaken.length === 0 && c.hitsTaken.length === 0, 'neighbours untouched');

    // Focus outside the attack's sector → the nearest hostile in the sector instead.
    h.reset();
    h.ctx.settings.aimAssist = false;
    const far = spawnAt(h, 'levy', 0, 3.4);
    const side = spawnAt(h, 'levy', 0.45, 1.2);
    h.step(1);
    ok(h.player.focusTarget === far, 'the far levy is the focus');
    h.action({ type: 'thrust', power: 0.5 });
    h.stepTime(0.4);
    const hits2 = h.events('hit');
    ok(hits2.length === 1 && hits2[0].payload.target === side, 'the nearest in-sector hostile was hit');
    // hostilesInSector sorts by distance.
    const list = h.combat.hostilesInSector(h.player.pos, 0, 5, 1.2);
    ok(list.length === 2 && list[0] === side && list[1] === far, 'hostilesInSector nearest first');
  },

  sweepCombosHitSeveral(h) {
    h.reset();
    const foes = [spawnAt(h, 'levy', 0.5, 1.6), spawnAt(h, 'levy', 0, 1.6), spawnAt(h, 'levy', -0.5, 1.6)];
    const slash = (dir) => {
      const n = h.events('hit').length;
      h.action({ type: 'slash', dir, power: 0.5 });
      h.stepTime(0.36);
      return h.events('hit').slice(n);
    };
    ok(slash(0).length === 1, 'R: one target');
    const twin = slash(Math.PI);
    ok(twin.length === 1, 'L (twin): one target');
    ok(h.events('combo').map((e) => e.payload.id).join() === 'twin', 'twin emitted');
    near(twin[0].payload.damage, 34 * 1.5, 1e-9, 'twin multiplier');
    ok(twin[0].payload.combo === 'twin', 'hit.combo is the id');
    const fury = slash(0.1);
    ok(fury.length === 3, `fury sweeps all three (${fury.length})`);
    ok(new Set(fury.map((e) => e.payload.target)).size === 3, 'three distinct targets');
    const storm = slash(Math.PI - 0.1);
    ok(storm.length === 3, `storm sweeps all three (${storm.length})`);
    const combos = h.events('combo').map((e) => e.payload);
    ok(combos.map((c) => c.id).join() === 'twin,fury,storm', `combo order ${combos.map((c) => c.id)}`);
    ok(combos[2].bonus === 240 && combos[1].bonus === 180, 'bonuses');
    ok(Array.isArray(combos[2].slowMo), 'storm carries slowMo for fx');
    // A whiff resets the chain: R hits, L whiffs (turned away), R hits → no twin.
    h.reset();
    spawnAt(h, 'levy', 0, 1.6);
    slash(0);
    h.player.yaw = Math.PI;
    h.step(1);
    ok(slash(Math.PI).length === 0, 'whiff');
    h.player.yaw = 0;
    h.step(1);
    slash(0);
    ok(h.events('combo').length === 0, 'no combo after a whiff');
    ok(foes.every((f) => !f.alive || f.hitsTaken.length >= 0), 'sanity');
  },

  bufferingAndCancelAfter(h) {
    h.reset();
    spawnAt(h, 'levy', 0, 1.6);
    h.action({ type: 'slash', dir: 0 });
    h.step(1);
    const t1 = h.events('swing')[0].t;
    h.step(3);
    h.action({ type: 'thrust' });
    h.stepUntil(() => h.events('swing').length >= 2, 60);
    const sw = h.events('swing');
    ok(sw.length === 2 && sw[1].payload.kind === 'thrust', 'buffered thrust started');
    const gap = sw[1].t - t1;
    ok(gap >= TIMING.slash.cancelAfter - 1e-6 && gap <= TIMING.slash.cancelAfter + DT + 1e-6, `starts at start + cancelAfter (${gap})`);
    // The first attack still struck before it was cancelled.
    ok(h.events('hit').length >= 1, 'first slash struck');

    // Newest wins.
    h.reset();
    spawnAt(h, 'levy', 0, 1.6);
    h.action({ type: 'slash', dir: 0 });
    h.step(2);
    h.action({ type: 'slash', dir: Math.PI });
    h.step(1);
    h.action({ type: 'bash' });
    h.stepTime(0.6);
    const kinds = h.events('swing').map((e) => e.payload.kind);
    ok(kinds.join() === 'slash,bash', `newest buffered action wins (${kinds})`);

    // An action arriving after cancelAfter starts at once.
    h.reset();
    h.action({ type: 'slash', dir: 0 });
    h.step(1);
    const s0 = h.events('swing')[0].t;
    h.stepTime(0.25);
    h.action({ type: 'slash', dir: Math.PI });
    h.step(1);
    const sw3 = h.events('swing');
    ok(sw3.length === 2, 'started immediately');
    near(sw3[1].t - s0, 0.25 + DT, DT + 1e-6, 'arrival time');

    // The buffer lasts 0.4 s: a slash pushed 0.1 s into a 0.6 s spear draw is dropped,
    // one pushed 0.3 s in survives.
    h.reset();
    h.action({ type: 'switchWeapon', weapon: 'spear' });
    h.step(1);
    h.stepTime(0.1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(1.0);
    ok(h.events('swing').length === 0, 'expired buffered action dropped');
    h.action({ type: 'switchWeapon', weapon: 'sword' });
    h.step(1);
    h.stepTime(0.1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.5);
    ok(h.events('swing').length === 1, 'buffered during the sword draw and started after it');

    // Throws can't be cancelled: a slash pushed mid-throw waits for the redraw.
    h.reset();
    getSpear(h);
    h.action({ type: 'throw', power: 0.5, src: 'touch' });
    h.step(1);
    const tt = h.events('swing')[0].t;
    h.stepTime(0.45);
    h.action({ type: 'slash', dir: 0 });
    h.stepUntil(() => h.events('swing').length >= 2, 120);
    const sw4 = h.events('swing');
    ok(sw4.length === 2, 'slash after the throw');
    const wait = sw4[1].t - tt;
    ok(wait >= TIMING.throw.windup + TIMING.throw.redraw - DT, `waited for the redraw (${wait})`);
    ok(sw4[1].payload.weapon === 'spear', 'spear slash after the redraw');
  },

  bashCooldownAndShieldThrust(h) {
    h.reset();
    spawnAt(h, 'levy', 0, 1.2);
    h.action({ type: 'bash', power: 0.5 });
    h.step(1);
    h.stepTime(0.5);
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.3);
    ok(h.events('swing').length === 1, 'second bash inside the 0.9 s cooldown dropped');
    h.stepTime(0.15);
    h.action({ type: 'bash', power: 0.5 });
    h.step(2);
    ok(h.events('swing').length === 2, 'bash allowed after the cooldown');
    // Thrust with the shield held is a bash.
    h.reset();
    spawnAt(h, 'levy', 0, 1.2);
    h.setShield(true);
    h.stepTime(0.3);
    h.action({ type: 'thrust', power: 0.5 });
    h.step(1);
    const sw = h.events('swing');
    ok(sw.length === 1 && sw[0].payload.kind === 'bash' && sw[0].payload.weapon === 'shield', 'shield + thrust = bash');
    h.stepTime(0.3);
    const hit = h.events('hit')[0];
    ok(hit && hit.payload.kind === 'bash', 'bash hit');
  },

  shieldDipBlocksNothing(h) {
    h.reset();
    const lv = spawnAt(h, 'levy', 0, 1.6);
    h.setShield(true);
    h.stepTime(0.5);
    ok(h.player.shieldRaise === 1, 'raised');
    ok(h.player.tryBlock(lv, 'melee') === 'block', 'blocks while raised');
    h.action({ type: 'slash', dir: 0 });
    h.step(2);
    ok(h.player.tryBlock(lv, 'melee') === 'none', 'no melee block during the dip');
    h.stepTime(0.15);
    near(h.player.shieldRaise, TIMING.shieldDip, 1e-9, 'dips to shieldDip');
    ok(h.player.shieldRaise >= 0.4, 'still high enough for frontal arrows');
    h.stepTime(0.2);
    ok(h.player.tryBlock(lv, 'melee') === 'none', 'still dipping before total');
    h.stepTime(0.15);
    ok(h.player.shieldRaise > 0.7 && h.player.tryBlock(lv, 'melee') === 'block', 'raised again after the attack');
    const shields = h.events('shield').map((e) => e.payload.up);
    ok(shields.join() === 'true', `no shield down/up events for the dip (${shields})`);
    // Volley arrows during repeated dips are still blocked.
    h.reset();
    h.setShield(true);
    h.stepTime(0.3);
    h.combat.volley({ count: 300, duration: 1.0, warn: 0.2 });
    for (let i = 0; i < 14; i++) {
      h.action({ type: 'slash', dir: i % 2 ? Math.PI : 0 });
      h.stepTime(0.25);
    }
    const arrows = h.events('arrowHit').filter((e) => e.payload.target === 'shield' || e.payload.target === 'player');
    ok(arrows.length === 3 && arrows.every((e) => e.payload.target === 'shield'), `arrows blocked during dips (${arrows.map((e) => e.payload.target)})`);
    ok(h.events('playerHurt').length === 0, 'no damage');
  },

  blockAndPerfectBlock(h) {
    h.reset();
    const lv = spawnAt(h, 'levy', 0, 1.6);
    h.setShield(true);
    h.stepTime(0.5);
    ok(h.combat.actorStrike(lv, MELEE) === 'block', 'normal block');
    let b = h.events('block').pop().payload;
    ok(b.by === 'player' && b.perfect === false && b.kind === 'melee' && b.attacker === lv, 'block payload');
    ok(h.player.hp === 100, 'no damage on block');
    // Perfect: down ≥ 0.25 s, then press ≤ 0.22 s before the strike.
    h.setShield(false);
    h.stepTime(0.5);
    h.setShield(true);
    h.stepTime(0.1);
    const score0 = h.player.score;
    ok(h.combat.actorStrike(lv, MELEE) === 'perfect', 'perfect block');
    b = h.events('block').pop().payload;
    ok(b.perfect === true, 'perfect payload');
    const parry = lv.hitsTaken[lv.hitsTaken.length - 1];
    ok(parry.damage === 0 && parry.stagger === 1.2 && parry.ignoreGuard === true, 'attacker staggered 1.2 s');
    ok(lv.state === 'stagger', 'attacker in stagger');
    ok(h.player.score === score0 + 50, 'perfect block +50');
    // The next player hit within critWindow is a critical (once).
    h.action({ type: 'slash', dir: 0, power: 0.5 });
    h.stepTime(0.36);
    let hit = h.events('hit').pop().payload;
    ok(hit.critical === true, 'critical after a perfect block');
    near(hit.damage, 34 * 1.6, 1e-9, 'critical damage');
    h.action({ type: 'slash', dir: Math.PI, power: 0.5 });
    h.stepTime(0.36);
    hit = h.events('hit').pop().payload;
    ok(hit.critical === false, 'crit consumed');
    // Perfect works even before the shield reaches 0.7.
    h.setShield(false);
    h.stepTime(0.6);
    h.setShield(true);
    h.step(2);
    ok(h.player.shieldRaise < 0.7, 'not yet raised');
    ok(h.combat.actorStrike(lv, MELEE) === 'perfect', 'early perfect');
    ok(h.player.hp === 100, 'perfect takes no damage');
    // Anti-farming: a 0.1 s release never lets the shield drop below 0.2.
    h.stepTime(0.5);
    h.setShield(false);
    h.stepTime(0.1);
    h.setShield(true);
    h.stepTime(0.1);
    ok(h.player.tryBlock(lv, 'melee') === 'block', 'mashed re-press is only a block');
    // 0.2 s release: down for ~0.06 s < 0.25 s.
    h.stepTime(0.4);
    h.setShield(false);
    h.stepTime(0.2);
    h.setShield(true);
    h.stepTime(0.1);
    ok(h.player.tryBlock(lv, 'melee') === 'block', 'short release is only a block');
    // Too late: held 0.3 s.
    h.setShield(false);
    h.stepTime(0.6);
    h.setShield(true);
    h.stepTime(0.3);
    ok(h.player.tryBlock(lv, 'melee') === 'block', 'late hold is only a block');
    // The crit window expires.
    h.setShield(false);
    h.stepTime(0.6);
    h.setShield(true);
    h.stepTime(0.05);
    ok(h.combat.actorStrike(lv, MELEE) === 'perfect', 'perfect again');
    h.stepTime(1.1);
    h.setShield(false);
    h.stepTime(0.2);
    h.action({ type: 'slash', dir: 0, power: 0.5 });
    h.stepTime(0.36);
    ok(h.events('hit').pop().payload.critical === false, 'crit window expired');
    // Unblocked and from behind.
    h.stepTime(0.5);
    ok(h.combat.actorStrike(lv, MELEE) === 'hit', 'unblocked hit');
    ok(h.player.hp === 90, 'took 10');
    const back = h.spawn('levy', 0, 1.5, { blockChance: 0, hp: 1000 });
    h.setShield(true);
    h.stepTime(0.5);
    ok(h.player.tryBlock(back, 'melee') === 'none', 'cannot block from behind');
    ok(h.combat.actorStrike(back, MELEE) === 'hit', 'hit from behind');
    const hurt = h.events('playerHurt').pop().payload;
    ok(Math.abs(hurt.relYaw) > 3, `relYaw from behind (${hurt.relYaw})`);
    // Out of reach / not facing → miss.
    const away = h.spawn('levy', 0, -5, { blockChance: 0, hp: 1000 });
    ok(h.combat.actorStrike(away, MELEE) === 'miss', 'out of reach');
    const turned = h.spawn('levy', 1.5, 0, { blockChance: 0, hp: 1000, facing: Math.PI / 2 });
    ok(h.combat.actorStrike(turned, MELEE) === 'miss', 'facing away');
  },

  captainGuardBreak(h) {
    h.reset();
    const cap = spawnAt(h, 'captain', 0, 1.6);
    h.setShield(true);
    h.stepTime(0.5);
    const yaw0 = h.player.yaw;
    ok(h.combat.actorStrike(cap, AXE) === 'guardBreak', 'guard break');
    const b = h.events('block').pop().payload;
    ok(b.kind === 'guardBreak' && b.perfect === false && b.by === 'player', 'block kind guardBreak');
    const hurt = h.events('playerHurt').pop().payload;
    ok(hurt.damage === 8 && hurt.kind === 'axe' && hurt.from === cap, 'took 8');
    ok(h.player.hp === 92, 'hp 92');
    ok(h.player.shieldRaise === 0, 'shield forced down');
    h.stepTime(0.3);
    near(Math.abs(h.player.yaw - yaw0), 0.1, 0.01, 'view pushed 0.1 rad');
    h.stepTime(0.4);
    ok(h.player.shieldRaise === 0, 'cannot raise for 0.8 s');
    ok(h.player.tryBlock(cap, 'axe') === 'none', 'no block while locked');
    h.stepTime(0.3);
    ok(h.player.shieldRaise > 0.7, 'raises again after the lock');
    // Perfect vs the captain: 0 damage, 1.4 s stagger.
    h.setShield(false);
    h.stepTime(0.5);
    h.setShield(true);
    h.stepTime(0.1);
    ok(h.combat.actorStrike(cap, AXE) === 'perfect', 'perfect vs axe');
    ok(cap.hitsTaken[cap.hitsTaken.length - 1].stagger === 1.4, 'captain staggers 1.4 s');
    ok(h.player.hp === 92, 'no damage');
    // Unblocked: 28.
    h.setShield(false);
    h.stepTime(0.5);
    ok(h.combat.actorStrike(cap, AXE) === 'hit', 'unblocked axe');
    ok(h.player.hp === 64, `hp ${h.player.hp}`);
  },

  spearThrowsWithAssist(h) {
    for (const [dist, power, src] of [[12, 0.5, 'touch'], [12, 0, 'motion'], [30, 0.5, 'touch'], [30, 0, 'key'], [30, 1, 'motion']]) {
      h.reset();
      armSpear(h);
      const lv = h.spawn('levy', 0.8, -dist, { blockChance: 0, hp: 1000 });
      h.step(1);
      const imp = throwAndWait(h, power, src);
      ok(imp.target === lv, `hit at ${dist} m, power ${power}, ${src}`);
      ok(imp.water === false, 'not water');
      ok(lv.hitsTaken[0].kind === 'spear', 'spear hit');
      const th = h.events('spearThrown')[0].payload.projectile;
      ok(th.byPlayer === true && th.src === src && th.kind === 'spear', 'projectile fields');
      const v = th.vel.length();
      ok(v > 19 && v < 35, 'speed range');
    }
    // A walking target is led.
    h.reset();
    armSpear(h);
    const walker = h.spawn('levy', 0, -26, { blockChance: 0, hp: 1000, vel: [0, 1.6] });
    h.step(1);
    ok(throwAndWait(h, 0.4, 'touch').target === walker, 'moving target hit');
    // Out of range at minimum power: falls short.
    h.reset();
    armSpear(h);
    const farLv = h.spawn('levy', 0, -44, { blockChance: 0, hp: 1000 });
    h.step(1);
    let imp = throwAndWait(h, 0, 'touch');
    ok(imp.target === null && imp.point.z > -43, `falls short at 44 m without power (z ${imp.point.z.toFixed(1)})`);
    ok(farLv.hitsTaken.length === 0, 'untouched');
    // No target in the 0.262 rad cone: thrown along the view (+0.06 rad) and lands well short.
    h.reset();
    armSpear(h);
    spawnAt(h, 'levy', 0.4, 30);
    h.step(1);
    imp = throwAndWait(h, 0.5, 'touch');
    ok(imp.target === null && Math.hypot(imp.point.x, imp.point.z) < 25, 'unassisted throw lands short');
  },

  skewerTwo(h) {
    h.reset();
    armSpear(h);
    const a = h.spawn('levy', 0, -8, { blockChance: 0, hp: 100 });
    const b = h.spawn('levy', 0, -9.2, { blockChance: 0, hp: 100 });
    h.step(1);
    const imp = throwAndWait(h, 0.9, 'touch');
    ok(imp.target === b, 'stuck in the second');
    ok(imp.projectile.hits.length === 2 && imp.projectile.hits[0] === a && imp.projectile.hits[1] === b, 'hits [A, B]');
    const spearHits = h.events('hit').filter((e) => e.payload.kind === 'spear');
    ok(spearHits.length === 2, 'two spear hits');
    ok(h.events('kill').filter((e) => e.payload.spear).length === 2, 'two spear kills');
    ok(imp.projectile.mesh.parent && (imp.projectile.mesh.parent === b.visual.sockets.chest || imp.projectile.mesh.parent === b.visual.sockets.head), 'attached to B');
    // Weaker throws stop in the first.
    h.reset();
    armSpear(h);
    const c = h.spawn('levy', 0, -8, { blockChance: 0, hp: 100 });
    const d = h.spawn('levy', 0, -9.2, { blockChance: 0, hp: 100 });
    h.step(1);
    const imp2 = throwAndWait(h, 0.5, 'touch');
    ok(imp2.target === c && imp2.projectile.hits.length === 1 && d.hitsTaken.length === 0, 'no skewer at 0.5');
  },

  stuckSpearFollowsFall(h) {
    h.reset();
    armSpear(h);
    const lv = h.spawn('levy', 0.3, -10, { blockChance: 0, hp: 50 });
    h.step(1);
    const imp = throwAndWait(h, 0.5, 'touch');
    ok(imp.target === lv && !lv.alive, 'killed by the spear');
    const mesh = imp.projectile.mesh;
    const sock = mesh.parent;
    ok(sock === lv.visual.sockets.chest || sock === lv.visual.sockets.head, 'attached to a socket');
    const T = h.ctx.THREE;
    const p0 = mesh.getWorldPosition(new T.Vector3());
    const axis0 = new T.Vector3(0, 0, 1).applyQuaternion(mesh.getWorldQuaternion(new T.Quaternion()));
    const vdir = imp.projectile.vel.clone().normalize();
    ok(axis0.dot(vdir) > 0.999, 'the prop tip (+Z) points along the flight');
    const tipWorld = p0.clone().addScaledVector(axis0, 0.6);
    ok(tipWorld.distanceTo(imp.point) < 1.2, 'tip near the impact point');
    const local0 = mesh.position.clone();
    h.stepTime(1.0);
    h.ctx.scene.updateMatrixWorld(true);
    const p1 = mesh.getWorldPosition(new T.Vector3());
    ok(p1.distanceTo(p0) > 0.4, `the spear moved with the falling body (${p1.distanceTo(p0).toFixed(2)} m)`);
    ok(mesh.position.distanceTo(local0) < 1e-9, 'local transform unchanged');
    const axis1 = new T.Vector3(0, 0, 1).applyQuaternion(mesh.getWorldQuaternion(new T.Quaternion()));
    ok(axis1.dot(axis0) < 0.95, 'the spear rotated with the body');
    ok(h.combat.stuckSpears.indexOf(imp.projectile) >= 0, 'kept in the stuck list');
  },

  volleyTimelineAndLimits(h) {
    h.reset();
    const t0 = h.ctx.time.gnow;
    h.combat.volley({ count: 300, duration: 1.0, warn: 2.5 });
    h.stepUntil(() => h.events('volley').some((e) => e.payload.phase === 'end'), 480);
    const ph = h.events('volley');
    ok(ph.map((e) => e.payload.phase).join() === 'warning,loosed,impact,end', 'phase order');
    ok(ph.every((e) => e.payload.duration === 1.0), 'duration in payload');
    near(ph[0].t - t0, 0, 1e-9, 'warning at once');
    near(ph[1].t - t0, 2.5, DT + 1e-6, 'loosed at warn');
    near(ph[2].t - t0, 4.5, DT + 1e-6, 'impact at warn + 2');
    near(ph[3].t - t0, 5.5, DT + 1e-6, 'end at warn + 2 + duration');
    const hurts = h.events('playerHurt');
    ok(hurts.length === 3 && hurts.every((e) => e.payload.damage === 8 && e.payload.kind === 'arrow'), `3 arrows of 8 (${hurts.length})`);
    ok(hurts.every((e) => e.t >= ph[2].t - 1e-9 && e.t <= ph[3].t + 1e-9), 'arrows land in the impact window');
    ok(h.events('arrowHit').filter((e) => e.payload.target === 'player').length === 3, 'arrowHit player ×3');
    ok(h.player.hp === 76, `hp ${h.player.hp}`);
    for (const [count, n] of [[100, 1], [101, 2], [250, 3], [1000, 3], [50, 1], [0, 0]]) {
      h.reset();
      h.combat.volley({ count, duration: 1.0, warn: 0.5 });
      h.stepTime(4.5);
      ok(h.events('playerHurt').length === n, `count ${count} → ${n} arrows (${h.events('playerHurt').length})`);
    }
    // Shield up blocks every frontal arrow.
    h.reset();
    h.setShield(true);
    h.combat.volley({ count: 300, duration: 1.0, warn: 0.5 });
    h.stepTime(4.5);
    ok(h.events('arrowHit').filter((e) => e.payload.target === 'shield').length === 3, 'three on the shield');
    ok(h.events('playerHurt').length === 0, 'none hurt');
    // From behind: not blockable.
    h.reset();
    h.setShield(true);
    h.combat.volley({ count: 300, duration: 1.0, warn: 0.5, fromBehind: true });
    h.stepTime(4.5);
    const hb = h.events('playerHurt');
    ok(hb.length === 3 && hb.every((e) => Math.abs(e.payload.relYaw) > 3), 'arrows from behind hurt through the shield');
    // Allies take at most one arrow each.
    h.reset();
    const allies = [-1.4, 1.4, 2.8, -2.8].map((x) => h.spawn('spartan', x, 0, { team: 'greek', role: 'ally', hp: 150, facing: Math.PI }));
    h.combat.volley({ count: 300, duration: 1.0, warn: 0.5 });
    h.stepTime(4.5);
    for (const a of allies) {
      const n = h.events('arrowHit').filter((e) => e.payload.target === a || e.payload.by === a).length;
      ok(n <= 1, `ally took ${n} arrows`);
    }
    // Visual arrows are instanced; grounded ones are capped at 200.
    ok(h.combat.volleyMesh.count <= 200 + 600, 'instance count bounded');
  },

  spearsLeftAccounting(h) {
    h.reset();
    const P = h.player;
    ok(P.spearsLeft === 2 && P.spares === 2 && !P.hasSpearInHand && P.weapon === 'sword', 'start: 2 spares');
    h.action({ type: 'switchWeapon' });
    h.step(1);
    ok(P.weapon === 'spear' && P.hasSpearInHand && P.spares === 1 && P.spearsLeft === 2, 'switch draws a spare');
    ok(h.events('weaponSwitch').pop().payload.weapon === 'spear', 'weaponSwitch spear');
    h.stepTime(0.7);
    h.action({ type: 'switchWeapon', weapon: 'sword' });
    h.step(1);
    ok(P.weapon === 'sword' && P.hasSpearInHand && P.spearsLeft === 2, 'sword keeps the spear');
    h.stepTime(0.4);
    h.action({ type: 'switchWeapon', weapon: 'spear' });
    h.step(1);
    ok(P.spares === 1 && P.spearsLeft === 2, 'no spare used for a spear already in hand');
    h.stepTime(0.4);
    h.action({ type: 'throw', power: 0.5, src: 'touch' });
    h.stepTime(0.2);
    ok(!P.hasSpearInHand && P.spearsLeft === 1, 'thrown');
    h.stepTime(0.6);
    ok(P.hasSpearInHand && P.spares === 0 && P.spearsLeft === 1, 'redrawn from the last spare');
    h.action({ type: 'throw', power: 0.5, src: 'touch' });
    h.stepTime(0.2);
    ok(P.spearsLeft === 0, 'none left');
    const ws0 = h.events('weaponSwitch').length;
    h.stepTime(0.8);
    ok(P.weapon === 'sword', 'back to the sword');
    ok(h.events('weaponSwitch').length === ws0 + 1 && h.events('weaponSwitch').pop().payload.weapon === 'sword', 'weaponSwitch sword');
    h.action({ type: 'switchWeapon', weapon: 'spear' });
    h.step(2);
    ok(P.weapon === 'sword', 'cannot switch with no spears');
    h.action({ type: 'throw', power: 0.5 });
    h.step(2);
    ok(h.events('swing').filter((e) => e.payload.kind === 'throw').length === 2, 'no throw without a spear');
    P.refillSpears();
    ok(P.spares === 3 && P.hasSpearInHand && P.spearsLeft === 4, 'refill');
    // Training always redraws.
    h.reset('training');
    ok(P.spares === 3, 'training spares');
    h.action({ type: 'switchWeapon', weapon: 'spear' });
    h.stepTime(0.7);
    for (let i = 0; i < 5; i++) {
      h.action({ type: 'throw', power: 0.5, src: 'touch' });
      h.stepTime(0.9);
    }
    ok(P.weapon === 'spear' && P.hasSpearInHand && P.spearsLeft === 4, `training redraws forever (${P.spearsLeft})`);
  },

  scoreAndChain(h) {
    h.reset();
    const P = h.player;
    const lv = spawnAt(h, 'levy', 0, 1.6, { hp: 30 });
    h.action({ type: 'slash', dir: 0, power: 0.5 });
    h.stepTime(0.4);
    ok(!lv.alive, 'killed');
    near(P.chain, 1.1, 1e-9, 'chain +0.1 per hit');
    ok(P.kills === 1 && P.score === 110, `kill scores round(100·chain) (${P.score})`);
    const sc = h.events('scoreChanged').pop().payload;
    ok(sc.score === 110 && sc.chain === 1.1 && sc.kills === 1, 'scoreChanged payload');
    for (let i = 0; i < 60; i++) h.ctx.events.emit('hit', { byPlayer: true, kind: 'slash' });
    ok(P.chain === 5, 'chain capped at 5');
    h.ctx.events.emit('hit', { byPlayer: true, kind: 'bleed' });
    h.ctx.events.emit('hit', { byPlayer: false, kind: 'melee' });
    ok(P.chain === 5, 'bleed ticks and AI hits do not count');
    h.ctx.events.emit('kill', { byPlayer: true, spear: true, cause: 'weapon' });
    ok(P.score === 110 + 500 + 250 && P.kills === 2, 'spear kill +250');
    h.ctx.events.emit('kill', { byPlayer: false, spear: false, cause: 'weapon' });
    ok(P.kills === 2, 'AI kills ignored');
    const n = h.events('scoreChanged').length;
    P.damage(5, { kind: 'melee' });
    ok(P.chain === 1 && h.events('scoreChanged').length === n + 1, 'playerHurt resets the chain');
    ok(h.events('scoreChanged').pop().payload.chain === 1, 'reset chain reported');
    // Training: damage is a no-op.
    h.reset('training');
    const lv2 = spawnAt(h, 'levy', 0, 1.6);
    P.damage(50, { kind: 'melee' });
    ok(h.combat.actorStrike(lv2, MELEE) === 'hit', 'strike lands in training');
    ok(P.hp === 100 && h.events('playerHurt').length === 0, 'no damage in training');
  },

  intoTheGulf(h) {
    h.reset();
    const P = h.player;
    P.pos.set(7.6, 0, 0);
    P.yaw = -Math.PI / 2;
    const lv = h.spawn('levy', 8.3, 0, { blockChance: 0, hp: 1000 });
    h.step(1);
    ok(P.focusTarget === lv, 'focus');
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.3);
    const k = h.events('kill').pop();
    ok(k && k.payload.cause === 'sea' && k.payload.byPlayer && k.payload.target === lv, 'Into the Gulf');
    ok(lv.pos.x > 8.6, 'carried past 8.6');
    ok(P.score === 110 + 150, `kill + 150 (${P.score})`);
    // Short of the edge: no fall.
    h.reset();
    P.pos.set(5, 0, 0);
    P.yaw = -Math.PI / 2;
    const lv2 = h.spawn('levy', 6.0, 0, { blockChance: 0, hp: 1000 });
    h.step(1);
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.3);
    ok(lv2.alive && h.events('kill').length === 0, 'no fall from x = 6.9');
    near(lv2.pos.x, 6.9, 1e-6, 'knocked back 0.9 m');
    ok(lv2.state === 'stagger', 'staggered');
  },

  motionVsTouchAim(h) {
    // Touch: the camera is pulled up to 0.21 rad toward the focus.
    h.reset();
    const lv = spawnAt(h, 'levy', 0.3, 1.8);
    h.step(1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.12);
    near(h.player.yaw, 0.21, 1e-6, 'touch pull');
    ok(h.events('hit').pop().payload.target === lv, 'hit');
    h.reset();
    h.ctx.settings.aimAssist = false;
    spawnAt(h, 'levy', 0.3, 1.8);
    h.step(1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.12);
    ok(h.player.yaw === 0, 'no pull without aimAssist');
    // Motion: the camera never moves; the attack yaw turns up to 0.44 rad.
    for (const [ang, want] of [[0.3, 0.3], [0.6, 0.44], [-0.5, -0.44]]) {
      h.reset();
      h.ctx.input.motion = true;
      const t = spawnAt(h, 'levy', ang, 1.8);
      h.step(1);
      h.action({ type: 'slash', dir: 0, src: 'motion' });
      h.stepTime(0.12);
      ok(h.player.yaw === 0, 'motion: camera not pulled');
      near(h.player.attackYaw, want, 1e-6, `motion attack yaw for ${ang}`);
      const hit = h.events('hit').pop();
      ok(hit && hit.payload.target === t, 'motion hit');
    }
    // Motion pitch eases toward the chest within [-0.26, 0.17].
    h.reset();
    h.ctx.input.motion = true;
    spawnAt(h, 'levy', 0, 1.5);
    h.stepTime(0.5);
    ok(h.player.pitch < 0 && h.player.pitch >= -0.26, `pitch toward the chest (${h.player.pitch})`);
  },

  lungeAndDrift(h) {
    h.reset();
    const lv = spawnAt(h, 'levy', 0, 2.6);
    h.step(1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.11);
    near(h.player.pos.z, -0.8, 1e-6, 'lunged 0.8 m');
    ok(h.events('hit').pop().payload.target === lv, 'lunge hit');
    h.reset();
    spawnAt(h, 'levy', 0, 1.8);
    h.step(1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.2);
    ok(h.player.pos.z === 0, 'no lunge in reach');
    h.reset();
    spawnAt(h, 'levy', 0, 3.5);
    h.step(1);
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.2);
    ok(h.player.pos.z === 0, 'no lunge beyond reach + 1');
    // Motion drift back to the slot after 1.5 s.
    h.reset();
    h.ctx.input.motion = true;
    h.player.pos.set(1, 0, -1);
    h.stepTime(1.4);
    ok(h.player.pos.x === 1 && h.player.pos.z === -1, 'no drift before 1.5 s');
    h.stepTime(0.6);
    const d = Math.hypot(h.player.pos.x, h.player.pos.z);
    ok(d < Math.SQRT2 - 0.3 && d > Math.SQRT2 - 0.6, `drifting at 1 m/s (${d})`);
    h.stepTime(2);
    ok(Math.hypot(h.player.pos.x, h.player.pos.z) < 1e-6, 'back in the slot');
    // Touch: no drift.
    h.reset();
    h.player.pos.set(1, 0, -1);
    h.stepTime(3);
    ok(h.player.pos.x === 1, 'touch: no drift');
    // Bounds clamp.
    h.reset();
    h.ctx.input.moveZ = -1;
    h.stepTime(5);
    ok(h.player.pos.z <= 3 + 1e-9 && h.player.pos.z > 2.9, 'clamped to bounds.maxZ');
  },

  autoFaceMotion(h) {
    h.reset();
    h.ctx.input.motion = true;
    const lv = h.spawn('levy', 0, 1.6, { blockChance: 0, hp: 1000, state: 'windup' });
    h.ctx.events.emit('enemyWindup', { actor: lv, timeToStrike: 2.5, targetIsPlayer: true });
    h.stepTime(0.5);
    near(Math.abs(h.player.yaw), 0.75, 0.03, 'turns at 1.5 rad/s');
    h.stepTime(1.5);
    near(Math.abs(h.player.yaw), Math.PI - 1.0, 0.03, 'stops once within 1.0 rad');
    // Touch mode: no auto-face.
    h.reset();
    const lv2 = h.spawn('levy', 0, 1.6, { blockChance: 0, hp: 1000, state: 'windup' });
    h.ctx.events.emit('enemyWindup', { actor: lv2, timeToStrike: 2.5, targetIsPlayer: true });
    h.stepTime(0.5);
    ok(h.player.yaw === 0, 'touch: no auto-face');
    // A hostile in view blocks auto-face.
    h.reset();
    h.ctx.input.motion = true;
    spawnAt(h, 'levy', 0.2, 2.0);
    const lv3 = h.spawn('levy', 0, 1.6, { blockChance: 0, hp: 1000, state: 'windup' });
    h.ctx.events.emit('enemyWindup', { actor: lv3, timeToStrike: 2.5, targetIsPlayer: true });
    h.stepTime(0.5);
    ok(h.player.yaw === 0, 'a hostile in view: no auto-face');
  },

  comboEffects(h) {
    // xcut bleeds 6 dps for 3 s.
    h.reset();
    const lv = spawnAt(h, 'levy', 0, 1.6);
    h.action({ type: 'slash', dir: -Math.PI / 4 });
    h.stepTime(0.36);
    h.action({ type: 'slash', dir: -3 * Math.PI / 4 });
    h.stepTime(0.36);
    ok(h.events('combo').pop().payload.id === 'xcut', 'xcut');
    h.stepTime(3.5);
    const bleed = h.events('hit').filter((e) => e.payload.kind === 'bleed');
    const total = bleed.reduce((s, e) => s + e.payload.damage, 0);
    near(total, 18, 0.2, 'bleed total');
    ok(bleed.length >= 5 && bleed.length <= 7, `ticks (${bleed.length})`);
    ok(lv.hitsTaken.length === 2 + bleed.length, 'bleed went through takeHit');
    // answer: bash then thrust executes a staggered target.
    h.reset();
    const ex = spawnAt(h, 'levy', 0, 1.3, { hp: 400 });
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.47);
    ok(ex.state === 'stagger', 'bash staggered');
    h.action({ type: 'thrust', power: 0.5 });
    h.stepTime(0.4);
    ok(h.events('combo').pop().payload.id === 'answer' && !ex.alive, 'executed');
    // drive: T,T,T knocks back 2.5 m.
    h.reset();
    const dv = spawnAt(h, 'levy', 0, 1.6);
    for (let i = 0; i < 3; i++) {
      h.action({ type: 'thrust', power: 0.5 });
      h.stepTime(0.4);
    }
    ok(h.events('combo').pop().payload.id === 'drive', 'drive');
    near(dv.pos.z, -1.6 - 2.5, 1e-6, 'driven back 2.5 m');
    // wall: B,B staggers everything within 2.5 m, even a recently bashed foe and one behind.
    h.reset();
    const ring = [spawnAt(h, 'levy', 0, 1.3), spawnAt(h, 'levy', 1.2, 1.5), spawnAt(h, 'levy', -1.2, 1.5), h.spawn('levy', 0.3, 1.4, { blockChance: 0, hp: 1000 })];
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.95);
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.3);
    ok(h.events('combo').pop().payload.id === 'wall', 'wall');
    const wallHits = h.events('hit').filter((e) => e.payload.combo === 'wall');
    ok(wallHits.length === 4, `stagger_all hits all four (${wallHits.length})`);
    ok(ring.every((a) => a.hitsTaken[a.hitsTaken.length - 1].stagger > 0), 'all staggered, rebash immunity bypassed');
    // cleave breaks guard.
    h.reset();
    const gd = spawnAt(h, 'sparabara', 0, 1.6, { blockChance: 1 });
    h.action({ type: 'slash', dir: Math.PI / 2 });
    h.stepTime(0.36);
    ok(h.events('hit').pop().payload.guarded === true, 'uppercut guarded');
    h.action({ type: 'slash', dir: -Math.PI / 2 + 0.3 });
    h.stepTime(0.36);
    const cl = h.events('hit').pop().payload;
    ok(cl.combo === 'cleave' && cl.guarded === false, 'cleave ignores guard');
    ok(gd.hitsTaken[1].ignoreGuard === true, 'breakGuard → ignoreGuard');
  },

  enemyGuardRules(h) {
    h.reset();
    spawnAt(h, 'sparabara', 0, 1.6, { blockChance: 1 });
    const hitOf = (a) => {
      h.action(a);
      h.stepTime(0.5);
      return h.events('hit').pop().payload;
    };
    let p = hitOf({ type: 'slash', dir: 0, power: 0.5 });
    ok(p.guarded && Math.abs(p.damage - 34 * 0.15) < 1e-9, 'guarded slash deals 15%');
    p = hitOf({ type: 'slash', dir: -Math.PI / 2, power: 0.5 });
    ok(!p.guarded, 'overhead D goes over the shield');
    p = hitOf({ type: 'thrust', power: 0.9 });
    ok(!p.guarded, 'thrust with power > 0.8 ignores guard');
    p = hitOf({ type: 'thrust', power: 0.5 });
    ok(p.guarded, 'weak thrust guarded');
    // Rebash immunity outside the combo window.
    h.reset();
    const rb = spawnAt(h, 'levy', 0, 1.2);
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(1.3);
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(1.4);
    h.action({ type: 'bash', power: 0.5 });
    h.stepTime(0.3);
    const st = rb.hitsTaken.map((x) => x.stagger);
    ok(st.length === 3 && st[0] > 0 && st[1] === 0 && st[2] > 0, `rebash immunity (${st})`);
    ok(rb.hitsTaken[1].knockback === 0, 'no knockback while immune');
    ok(h.events('combo').length === 0, 'no wall outside the window');
  },

  aimedArrows(h) {
    h.reset();
    const ar = h.spawn('archer', 0, -20, { blockChance: 0, hp: 40 });
    h.setShield(true);
    h.stepTime(0.4);
    h.combat.shootArrow(ar, 'player');
    h.stepTime(1.2);
    ok(h.events('arrowHit').pop().payload.target === 'shield', 'aimed arrow on the shield');
    h.setShield(false);
    h.stepTime(0.4);
    h.combat.shootArrow(ar, 'player');
    h.stepTime(1.2);
    ok(h.events('arrowHit').pop().payload.target === 'player' && h.player.hp === 92, 'aimed arrow hurts');
    const behind = h.spawn('archer', 0, 20, { blockChance: 0, hp: 40 });
    h.setShield(true);
    h.stepTime(0.4);
    h.combat.shootArrow(behind, 'player');
    h.stepTime(1.2);
    ok(h.events('arrowHit').pop().payload.target === 'player' && h.player.hp === 84, 'from behind not blockable');
    // At an ally.
    const ally = h.spawn('spartan', 3, 0, { team: 'greek', role: 'ally', hp: 150, facing: Math.PI });
    const ar2 = h.spawn('archer', 3, -20, { blockChance: 0, hp: 40 });
    const n0 = h.events('arrowHit').length;
    h.combat.shootArrow(ar2, ally);
    h.stepTime(1.2);
    const ah = h.events('arrowHit').slice(n0);
    ok(ah.length === 1 && (ah[0].payload.target === ally || ah[0].payload.by === ally), 'arrow resolved on the ally');
  },

  spearWaterAndGroundCap(h) {
    h.reset();
    const P = h.player;
    armSpear(h);
    P.pos.set(8, 0, 0);
    P.yaw = -Math.PI / 2;
    h.step(1);
    const imp = throwAndWait(h, 0.6, 'touch');
    ok(imp.water === true && imp.target === null, 'spear in the sea');
    ok(h.fx.splashes.length === 1, 'fx.splash');
    ok(!imp.projectile.alive, 'not alive');
    // Ground spears are capped at 12.
    h.reset();
    for (let i = 0; i < 14; i++) {
      armSpear(h);
      h.stepTime(0.05);
      throwAndWait(h, 0.2, 'touch');
      h.stepTime(0.7);
    }
    ok(h.combat.stuckSpears.length === 12, `12 kept (${h.combat.stuckSpears.length})`);
    let inScene = 0;
    h.ctx.scene.traverse((o) => {
      if (h.combat.stuckSpears.some((p) => p.mesh === o)) inScene++;
    });
    ok(inScene === 12, 'oldest removed from the scene');
    // Into the cliff plane: sticks with its normal.
    h.reset();
    armSpear(h);
    P.pos.set(-6.5, 0, 0);
    P.yaw = Math.PI / 2;
    P.pitch = 0.1;
    h.step(1);
    const cliff = throwAndWait(h, 1, 'touch');
    ok(cliff.target === null && Math.abs(cliff.point.x + 9) < 0.05 && cliff.normal.x > 0.99, 'stuck in the cliff face');
    h.combat.reset();
    ok(h.combat.stuckSpears.length === 0 && h.combat.projectiles.length === 0, 'combat.reset clears');
  },

  cameraOverrideAndTimeline(h) {
    h.reset();
    h.fx.cameraOverridden = true;
    h.action({ type: 'slash', dir: 0 });
    h.action({ type: 'thrust' });
    h.stepTime(0.5);
    ok(h.events('swing').length === 0, 'attacks ignored during the spear cam');
    h.fx.cameraOverridden = false;
    h.action({ type: 'slash', dir: 0 });
    h.step(1);
    ok(h.events('swing').length === 1, 'attacks again');
    // pdt: hit-stop freezes the player's timeline; playerRealtime outpaces slow-mo.
    h.reset();
    spawnAt(h, 'levy', 0, 1.6);
    h.action({ type: 'slash', dir: 0 });
    h.step(1);
    h.ctx.time.hitStop = 0.1;
    const f0 = h.frame;
    h.stepUntil(() => h.events('hit').length > 0, 60);
    const frames = h.frame - f0;
    ok(frames >= 11 && frames <= 13, `strike delayed by the hit-stop (${frames} frames)`);
    h.reset();
    spawnAt(h, 'levy', 0, 1.6);
    h.ctx.time.scale = 0.25;
    h.ctx.time.playerRealtime = true;
    h.action({ type: 'slash', dir: 0 });
    h.step(1);
    const f1 = h.frame;
    h.stepUntil(() => h.events('hit').length > 0, 60);
    ok(h.frame - f1 >= 5 && h.frame - f1 <= 7, `strike on real time during combo slow-mo (${h.frame - f1})`);
    h.ctx.time.scale = 1;
    h.ctx.time.playerRealtime = false;
    // pause action
    h.reset();
    h.action({ type: 'pause' });
    h.step(1);
    ok(h.ctx.mode === 'paused', 'pause drained');
    h.ctx.mode = 'battle';
    h.action({ type: 'recenter' });
    h.player.yaw = 1;
    h.stepTime(0.5);
    ok(Math.abs(h.player.yaw) < 1e-9 && h.gestures.recenters > 0, 'recenter');
  },

  deathAndHealth(h) {
    h.reset();
    const P = h.player;
    P.damage(60, { kind: 'melee' });
    P.heal(10);
    ok(P.hp === 50, 'heal');
    P.heal(500);
    ok(P.hp === 100, 'heal caps at maxHp');
    P.damage(60, { kind: 'melee' });
    h.step(1);
    const y0 = h.ctx.camera.position.y;
    P.damage(60, { kind: 'melee' });
    ok(P.hp === 0 && !P.alive && P.state === 'dead', 'dead');
    ok(h.events('playerDeath').length === 1, 'playerDeath once');
    P.damage(10, { kind: 'melee' });
    ok(h.events('playerHurt').length === 3, 'no damage once dead');
    h.action({ type: 'slash', dir: 0 });
    h.stepTime(0.5);
    ok(h.events('swing').length === 0, 'no attacks when dead');
    h.stepTime(1.5);
    ok(h.ctx.camera.position.y < y0 - 1.0, `the death camera sinks (${y0.toFixed(2)} → ${h.ctx.camera.position.y.toFixed(2)})`);
  },

  cameraAndViewmodel(h) {
    h.reset();
    const P = h.player;
    const cam = h.ctx.camera;
    const calls0 = h.vm.calls.setShield;
    h.stepTime(1);
    ok(h.vm.calls.setWeapon - calls0 >= 59 && h.vm.calls.setHasSpear - calls0 >= 59 && h.vm.calls.setShield - calls0 >= 59, 'viewmodel synced every frame');
    near(cam.position.y, 1.66, 0.02, 'eye height with breathing');
    ok(cam.rotation.order === 'YXZ', 'YXZ');
    // Walking bobs the eye.
    h.ctx.input.moveZ = 1;
    let lo = Infinity;
    let hi = -Infinity;
    for (let i = 0; i < 60; i++) {
      h.step(1);
      lo = Math.min(lo, cam.position.y);
      hi = Math.max(hi, cam.position.y);
    }
    ok(hi - lo > 0.02 && hi - lo < 0.08, `walk bob ${(hi - lo).toFixed(3)}`);
    ok(P.pos.z < -1.5, 'walked forward (−Z)');
    h.ctx.input.moveZ = 0;
    // Hurt flinch kicks the view and settles.
    h.stepTime(1);
    const pitch0 = cam.rotation.x;
    P.damage(10, { kind: 'melee' });
    h.step(4);
    ok(cam.rotation.x - pitch0 > 0.02, 'hurt flinch');
    h.stepTime(1);
    near(cam.rotation.x, pitch0, 0.01, 'flinch settles');
    // Idle camera for title/gameover.
    P.idleCamera(10);
    P.applyCamera(cam);
    near(cam.position.z, 1.5, 1e-9, 'idle eye z');
    near(cam.position.x, 0, 1e-9, 'idle eye x');
    ok(Math.abs(cam.rotation.y) <= 0.25 + 1e-9, 'idle pan within ±0.25');
    ok(h.vm.playCalls === 0, 'player never calls viewmodel.play');
  },
};

const isVec = (p) => p && Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.z);
const isBool = (b) => b === true || b === false;
const isActor = (a) => a && typeof a === 'object' && typeof a.takeHit === 'function';

/** Validates one logged event payload against DESIGN.md §12. Returns an error string or null. */
export function validateEvent(name, p, synthetic) {
  if (!p || typeof p !== 'object') return 'payload is not an object';
  switch (name) {
    case 'swing':
      if (!['slash', 'thrust', 'spearThrust', 'bash', 'throw'].includes(p.kind)) return `kind ${p.kind}`;
      if (!Number.isFinite(p.dir) || !Number.isFinite(p.power) || p.power < 0 || p.power > 1) return 'dir/power';
      if (!['sword', 'spear', 'shield'].includes(p.weapon) || typeof p.src !== 'string') return 'weapon/src';
      return null;
    case 'combo':
      if (typeof p.id !== 'string' || typeof p.name !== 'string' || !Array.isArray(p.seq)) return 'row fields';
      if (!Number.isFinite(p.mult) || p.bonus !== Math.round(100 * p.mult)) return 'bonus';
      return null;
    case 'playerHurt':
      if (!(p.damage > 0) || !Number.isFinite(p.hp) || !Number.isFinite(p.relYaw)) return 'numbers';
      if (!['melee', 'axe', 'arrow'].includes(p.kind)) return `kind ${p.kind}`;
      if (!(p.from === null || isActor(p.from))) return 'from';
      return null;
    case 'playerDeath':
      return null;
    case 'scoreChanged':
      return Number.isFinite(p.score) && Number.isFinite(p.chain) && Number.isFinite(p.kills) ? null : 'fields';
    case 'weaponSwitch':
      return p.weapon === 'sword' || p.weapon === 'spear' ? null : 'weapon';
    case 'shield':
      return isBool(p.up) ? null : 'up';
    case 'hit':
      if (synthetic) return null;
      if (!isActor(p.target)) return 'target';
      if (!(p.attacker === 'player' || p.attacker === null || isActor(p.attacker))) return 'attacker';
      if (!(p.damage >= 0) || typeof p.kind !== 'string') return 'damage/kind';
      if (!p.dir || !Number.isFinite(p.dir.x) || !Number.isFinite(p.dir.z) || !isVec(p.point)) return 'dir/point';
      if (![p.guarded, p.critical, p.killed, p.byPlayer].every(isBool)) return 'flags';
      if (!(p.combo === null || typeof p.combo === 'string')) return 'combo';
      return null;
    case 'kill':
      if (synthetic) return null;
      if (!isActor(p.target) || !isBool(p.byPlayer) || !isBool(p.spear) || typeof p.kind !== 'string') return 'fields';
      if (!(p.attacker === 'player' || p.attacker === null || isActor(p.attacker))) return 'attacker';
      return ['weapon', 'sea', 'bleed'].includes(p.cause) ? null : `cause ${p.cause}`;
    case 'block':
      if (!(p.by === 'player' || isActor(p.by)) || !isActor(p.attacker) || !isVec(p.point) || !isBool(p.perfect)) return 'fields';
      return ['melee', 'axe', 'guardBreak'].includes(p.kind) ? null : `kind ${p.kind}`;
    case 'spearThrown': {
      const q = p.projectile;
      if (!q || q.kind !== 'spear' || !q.mesh || !isVec(q.pos) || !isVec(q.prevPos) || !isVec(q.vel)) return 'projectile';
      if (!isBool(q.alive) || !isBool(q.stuck) || !isBool(q.byPlayer) || !Number.isFinite(q.power) || !Array.isArray(q.hits) || !Number.isFinite(q.age)) return 'projectile fields';
      return null;
    }
    case 'spearImpact':
      if (!p.projectile || !(p.target === null || isActor(p.target)) || !isVec(p.point) || !isVec(p.normal) || !isBool(p.water)) return 'fields';
      return null;
    case 'arrowHit':
      if (!(p.target === 'player' || p.target === 'shield' || p.target === null || isActor(p.target))) return 'target';
      return isVec(p.point) ? null : 'point';
    case 'volley':
      if (!['warning', 'loosed', 'impact', 'end'].includes(p.phase)) return 'phase';
      return Number.isFinite(p.duration) ? null : 'duration';
    default:
      return null;
  }
}

/** Runs the named checks (default all) and then validates every event payload logged. */
export async function runChecks(h, names) {
  const list = names && names.length ? names : Object.keys(CHECKS);
  const results = [];
  h.allLog.length = 0;
  for (const name of list) {
    const t0 = (typeof performance !== 'undefined' ? performance : Date).now();
    try {
      await CHECKS[name](h);
      results.push({ name, ok: true, ms: Math.round((typeof performance !== 'undefined' ? performance : Date).now() - t0) });
    } catch (err) {
      results.push({ name, ok: false, error: String(err && err.stack || err) });
    }
  }
  // Payload shapes for every event emitted by the real modules during the checks.
  const counts = {};
  const errors = [];
  for (const e of h.allLog) {
    counts[e.name] = (counts[e.name] || 0) + 1;
    const synthetic = (e.name === 'hit' || e.name === 'kill') && !e.payload.target;
    const err = validateEvent(e.name, e.payload, synthetic);
    if (err && errors.length < 20) errors.push(`${e.name}: ${err}`);
  }
  const required = ['swing', 'combo', 'playerHurt', 'playerDeath', 'scoreChanged', 'weaponSwitch', 'shield', 'hit', 'kill', 'block', 'spearThrown', 'spearImpact', 'arrowHit', 'volley'];
  const missing = names && names.length ? [] : required.filter((n) => !counts[n]);
  results.push({ name: 'eventPayloads', ok: errors.length === 0 && missing.length === 0, error: errors.concat(missing.map((n) => `never emitted: ${n}`)).join('\n') || undefined, counts });
  return results;
}
