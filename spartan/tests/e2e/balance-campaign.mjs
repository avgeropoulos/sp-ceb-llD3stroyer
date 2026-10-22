// Balance fixes (review B, campaign lens) in the real game:
//  * Day 1: the vanguard stands at z ≈ −16 through the intro and reaches the player soon after Fight;
//  * the next wave's first men set out 6 s before a respite ends (touch respite 10 s);
//  * pause → Restart during Day 1 dusk restarts at dusk; the pause kicker names Dusk / Surge / Kolonos;
//  * Day 3: phase B leaves one spear in hand, phase C is sword only (needs player.dropSpears);
//  * a shield held for more than 3 s takes guard breaks.
// The battle is driven by killing Persians through takeHit and pressing READY (god mode).
// Usage: node spartan/tests/e2e/balance-campaign.mjs
import { launch } from './lib.mjs';

const l = await launch();
let failed = 0;
const check = (ok, msg, data) => {
  if (!ok) failed++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${msg}${data !== undefined ? ' ' + JSON.stringify(data) : ''}`);
};
try {
  const page = await l.newPage({ width: 640, height: 360 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=low&control=touch`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  await page.evaluate(() => {
    const g = window.__game, c = g.ctx;
    c.renderer.render = () => {};
    window.__titles = [];
    c.events.on('dayStart', (e) => window.__titles.push(e.title));
    // Advances the battle in god mode: kills Persians that have set out (not the idle vanguard)
    // once they come within `reach` m, presses READY, until cond() or maxS game seconds.
    window.__play = (cond, maxS, reach = 40) => {
      const t0 = c.time.gnow;
      while (c.time.gnow - t0 < maxS) {
        g.step(6, 1 / 30);
        if (c.mode !== 'battle') return false;
        c.player.hp = c.player.maxHp;
        for (const a of [...c.actors]) {
          if (a.team !== 'persian' || !a.alive || a.role === 'dummy' || a.state === 'idle') continue;
          if (Math.hypot(a.pos.x - c.player.pos.x, a.pos.z - c.player.pos.z) > reach) continue;
          a.takeHit({ damage: 9999, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, byPlayer: true, attacker: 'player' });
        }
        if (c.waves.inRespite && c.waves.canReady) c.waves.ready();
        if (cond(c)) return true;
      }
      return false;
    };
  });
  const st = () => page.evaluate(() => {
    const c = window.__game.ctx, w = c.waves;
    return { mode: c.mode, day: w.day, entry: w.entryIndex, tod: w.timeOfDay, phase: w.phase, wave: w.wave, state: w.state, title: window.__titles[window.__titles.length - 1], spears: c.player.spearsLeft, weapon: c.player.weapon, hasSpear: c.player.hasSpearInHand };
  });
  const click = async (act) => {
    await page.locator(`#menus [data-act="${act}"]:visible`).first().click({ timeout: 10000 });
    await page.evaluate(() => window.__game.step(2, 1 / 30));
  };
  const pauseAndRead = async () => {
    await page.evaluate(() => { window.__game.action({ type: 'pause', src: 'touch' }); window.__game.step(2, 1 / 30); });
    return page.evaluate(() => document.querySelector('#menus .mn-pause-k').textContent);
  };

  // ---- Day 1: vanguard and first contact ----
  await click('fight');
  const van = await page.evaluate(() => {
    const c = window.__game.ctx;
    const p = c.actors.filter((a) => a.alive && a.team === 'persian');
    return { n: p.length, idle: p.filter((a) => a.state === 'idle').length, z: p.map((a) => +a.pos.z.toFixed(1)), state: c.waves.state };
  });
  check(van.n === 4 && van.idle === 4 && van.z.every((z) => z > -17 && z < -14.9) && van.state === 'intro', 'Day 1 intro: 4 idle levies stand at z ≈ −16', van);
  const contact = await page.evaluate(() => {
    const c = window.__game.ctx, g = window.__game;
    const t0 = c.time.gnow;
    let ws = null;
    for (let i = 0; i < 40 * 30; i++) {
      g.step(1, 1 / 30);
      c.player.hp = c.player.maxHp;
      if (ws === null && c.waves.state === 'fighting') ws = c.time.gnow - t0;
      if (c.actors.some((a) => a.alive && a.team === 'persian' && a.role === 'melee' && Math.hypot(a.pos.x, a.pos.z) < 2.6)) return { waveStart: +ws.toFixed(1), firstContact: +(c.time.gnow - t0).toFixed(1) };
    }
    return { waveStart: ws, firstContact: null };
  });
  check(contact.firstContact !== null && contact.firstContact < 18, 'first enemy within 2.6 m < 18 s after Fight (was 23–26 s)', contact);

  // ---- Respite: 10 s, the next wave sets out 6 s before it ends ----
  const respite = await page.evaluate(() => {
    const c = window.__game.ctx, g = window.__game;
    let rAt = null, sec = null;
    const spawns = [];
    const offR = c.events.on('respite', (e) => { if (rAt === null) { rAt = c.time.gnow; sec = e.seconds; } });
    const orig = c.ai.spawnEnemy;
    c.ai.spawnEnemy = function (kind, x, z, o) { spawns.push({ t: c.time.gnow, kind, z: +z.toFixed(1) }); return orig.call(this, kind, x, z, o); };
    let ws2 = null;
    const offW = c.events.on('waveStart', () => { if (rAt !== null && ws2 === null) ws2 = c.time.gnow; });
    for (let i = 0; i < 200 * 5 && ws2 === null; i++) {
      g.step(6, 1 / 30);
      c.player.hp = c.player.maxHp;
      if (rAt === null) for (const a of [...c.actors]) if (a.team === 'persian' && a.alive && a.role === 'melee' && a.state !== 'idle') a.takeHit({ damage: 9999, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, byPlayer: true, attacker: 'player' });
    }
    c.ai.spawnEnemy = orig;
    if (offR) offR();
    if (offW) offW();
    const early = spawns.filter((s) => rAt !== null && s.t > rAt && s.t < ws2);
    return { seconds: sec, length: ws2 && +(ws2 - rAt).toFixed(1), early: early.length, firstLead: early.length ? +(ws2 - early[0].t).toFixed(1) : null, zs: early.map((s) => s.z) };
  });
  check(respite.seconds === 10 && Math.abs(respite.length - 10) < 0.3, 'touch respite lasts 10 s', respite);
  check(respite.early >= 2 && Math.abs(respite.firstLead - 6) < 0.4 && respite.zs.every((z) => z >= -26 && z <= -20), 'the next wave sets out 6 s before the respite ends, at z −26…−20', respite);

  // ---- Restart during Day 1 dusk ----
  const toDusk = await page.evaluate(() => window.__play((c) => c.waves.timeOfDay === 'dusk' && c.waves.state === 'fighting', 900));
  check(toDusk, 'reached Day 1 dusk');
  const kDusk = await pauseAndRead();
  check(/^Day I · Dusk · Wave I/.test(kDusk), 'pause kicker in dusk', kDusk);
  await click('restart');
  await click('restart');
  await page.evaluate(() => window.__game.step(30, 1 / 30));
  const afterDusk = await st();
  check(afterDusk.mode === 'battle' && afterDusk.day === 1 && afterDusk.entry === 1 && afterDusk.tod === 'dusk' && afterDusk.title === 'The Immortals', 'Restart in dusk restarts at dusk (was: Day 1 morning)', afterDusk);

  // ---- Day 3: surges, spears, the Kolonos ----
  await page.evaluate(() => window.__game.startBattle(3));
  check(await page.evaluate(() => window.__play((c) => c.waves.phase === 'B' && c.waves.state === 'fighting', 900)), 'reached phase B');
  const b = await st();
  const kB = await pauseAndRead();
  check(/^Day III · Surge I of IV$/.test(kB), 'pause kicker in phase B', kB);
  await click('resume');
  const hasDrop = await page.evaluate(() => typeof window.__game.ctx.player.dropSpears === 'function');
  if (hasDrop) check(b.spears === 1 && b.hasSpear, 'phase B: the bundle is gone, one spear stays in hand (was 4)', b);
  else console.log('skip phase B spears: player.dropSpears is not there yet', JSON.stringify(b));
  check(await page.evaluate(() => window.__play((c) => c.waves.phase === 'C' && c.waves.state === 'fighting', 900)), 'reached phase C');
  const cst = await st();
  const kC = await pauseAndRead();
  check(kC === 'Day III · The Kolonos', 'pause kicker in phase C', kC);
  await click('resume');
  if (hasDrop) {
    const sw = await page.evaluate(() => {
      const g = window.__game, c = g.ctx;
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(30, 1 / 30);
      return { weapon: c.player.weapon, spears: c.player.spearsLeft };
    });
    check(cst.spears === 0 && cst.weapon === 'sword' && sw.weapon === 'sword', 'phase C is sword only: no spear, and switching to the spear fails', { cst, sw });
  } else console.log('skip phase C spears: player.dropSpears is not there yet', JSON.stringify(cst));

  // ---- Anti-turtle: a shield held for > 3 s takes a guard break ----
  const turtle = await page.evaluate(() => {
    const g = window.__game, c = g.ctx;
    g.startBattle(1);
    g.step(10, 1 / 30);
    c.waves.stop();
    for (const a of c.actors) if (a.team === 'persian') a.alive = false;
    g.step(2, 1 / 30);
    const blocks = [];
    const off = c.events.on('block', (e) => { if (e.by === 'player') blocks.push({ t: +(c.time.gnow).toFixed(2), kind: e.kind }); });
    window.dispatchEvent(new KeyboardEvent('keydown', { code: 'KeyK', bubbles: true }));
    const held = c.time.gnow;
    g.spawn('levy', 0, -1.7, { facing: 0 });
    for (let i = 0; i < 20 * 30; i++) { g.step(1, 1 / 30); c.player.hp = c.player.maxHp; }
    window.dispatchEvent(new KeyboardEvent('keyup', { code: 'KeyK', bubbles: true }));
    if (off) off();
    return { held, blocks: blocks.map((b) => ({ at: +(b.t - held).toFixed(1), kind: b.kind })) };
  });
  const early = turtle.blocks.filter((b) => b.at < 3);
  const late = turtle.blocks.filter((b) => b.at >= 3.05);
  check(early.every((b) => b.kind !== 'guardBreak') && late.length >= 2 && late.every((b) => b.kind === 'guardBreak'), 'blocks after 3 s of holding the shield are guard breaks', turtle.blocks);

  const errors = await page.evaluate(() => window.__game.errors);
  check(errors.length === 0 && page.errors.length === 0, 'no errors', { errors, page: page.errors.slice(0, 3) });
} finally {
  await l.close();
}
console.log(failed ? `${failed} FAILED` : 'all balance checks passed');
process.exitCode = failed ? 1 : 0;
