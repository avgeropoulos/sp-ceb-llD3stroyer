// Cross-module integration audit: drive the whole campaign (Day 1 → Day 3 last stand → death → game over
// → title → battle) by killing Persians through takeHit and pressing READY, logging every bus event and
// checking errors / NaN / leaks at each step.
import { launch } from './lib.mjs';
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=11&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const r = await page.evaluate(async () => {
    const g = window.__game, c = g.ctx;
    const evlog = [];
    const counts = {};
    const orig = c.events.emit.bind(c.events);
    c.events.emit = (n, p) => {
      counts[n] = (counts[n] || 0) + 1;
      if (['dayStart', 'phase', 'waveStart', 'waveCleared', 'respite', 'gameOver', 'modeChanged', 'playerDeath', 'spearCam'].includes(n))
        evlog.push(`${c.time.gnow.toFixed(1)} ${n} ${JSON.stringify(p, (k, v) => (k === 'actor' || k === 'projectile' ? undefined : v))}`);
      if (n === 'voice') evlog.push(`${c.time.gnow.toFixed(1)} voice ${p.speaker}: ${p.text.slice(0, 40)}`);
      return orig(n, p);
    };
    const nan = [];
    const fin = Number.isFinite;
    g.startBattle(1);
    let leonidas = null;
    let lastKey = '';
    for (let it = 0; it < 1400; it++) {
      g.step(20);
      const w = c.waves;
      const key = `${w.day}/${w.phase}/${w.wave}/${w.state}`;
      if (key !== lastKey) { evlog.push(`   [waves ${key}] actors=${c.actors.length} corpses=${c.ai.corpses.length} allies=${c.ai.alliesAlive} tod=${c.world.timeOfDay}`); lastKey = key; }
      if (w.day === 3 && w.phase === 'C' && w.state === 'fighting' && w.wave >= 1 && !leonidas) {
        leonidas = { t: c.time.gnow, fallen: c.ai.leonidasFallen };
      }
      for (const a of c.actors) if (!fin(a.pos.x) || !fin(a.pos.z)) nan.push(`${a.kind} ${a.pos.toArray()}`);
      if (!fin(c.camera.position.x)) nan.push('camera');
      if (c.mode === 'battle' && !(w.day === 3 && w.phase === 'C')) {
        c.player.hp = c.player.maxHp;
        for (const a of [...c.actors]) if (a.team === 'persian' && a.alive && a.role !== 'dummy' && a.pos.z > -40) a.takeHit({ damage: 9999, kind: 'slash', dir: { x: 0, z: -1 }, power: 1, byPlayer: true, attacker: 'player' });
        if (w.inRespite && w.canReady) w.ready();
      }
      if (w.day === 3 && w.phase === 'C' && c.mode === 'battle' && c.time.gnow - (leonidas ? leonidas.t : 1e9) > 20) c.player.damage(999, { from: null, kind: 'melee' });
      if (c.mode === 'gameover') { g.step(200); break; }
      if (it % 40 === 0) await new Promise((res) => setTimeout(res, 0));
    }
    const atEnd = { mode: c.mode, day: c.waves.day, state: c.waves.state, actors: c.actors.length, corpses: c.ai.corpses.length, scale: c.time.scale, music: c.audio.stats().music, menus: [...document.querySelectorAll('#menus .is-shown, #menus [data-screen].is-on')].map((e) => e.className).slice(0, 4) };
    c.setMode('title'); g.step(30);
    const title = { actors: c.actors.length, corpses: c.ai.corpses.length, tod: c.world.timeOfDay, horde: c.world.horde.density, leonidasFallen: c.ai.leonidasFallen };
    g.startBattle(1); g.step(60);
    const again = { actors: c.actors.length, allies: c.ai.alliesAlive, leonidasFallen: c.ai.leonidasFallen, names: c.actors.filter((a) => a.team === 'greek').map((a) => a.name || a.kind) };
    return { evlog, counts, nan: nan.slice(0, 5), atEnd, title, again, errors: g.errors.slice(0, 10), gnow: c.time.gnow };
  });
  console.log(r.evlog.join('\n'));
  delete r.evlog;
  console.log(JSON.stringify(r, null, 1));
  console.log('page errors', page.errors.slice(0, 5));
} finally { await l.close(); }
