// Cross-module integration audit (d): state that leaks between modes.
// battle (spear cam + volley + kills) → pause → Restart → Quit → battle → training → title → battle.
import { launch } from './lib.mjs';
const l = await launch();
try {
  const page = await l.newPage();
  await page.goto(`${l.game}?manual=1&seed=7&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 180000 });
  const out = await page.evaluate(() => {
    const g = window.__game, c = g.ctx;
    const snap = (label) => {
      let objs = 0; c.scene.traverse(() => objs++);
      const hudShown = [...document.querySelectorAll('#hud .is-shown')].map((e) => e.className.replace(/\s+/g, '.')).slice(0, 8);
      const a = c.audio.stats();
      return {
        label, mode: c.mode,
        sceneChildren: c.scene.children.length, sceneObjs: objs, vmObjs: (() => { let n = 0; c.vm.scene.traverse(() => n++); return n; })(),
        actors: c.actors.length, corpses: c.ai.corpses.length,
        particles: c.fx.particleCount, decals: c.fx.decalCount,
        projectiles: c.combat.projectiles.length, stuckSpears: c.combat.stuckSpears.length, volleys: c.combat.activeVolleys, volleyMeshCount: c.combat.volleyMesh.count,
        scale: c.time.scale, hitStop: +c.time.hitStop.toFixed(3), playerRealtime: c.time.playerRealtime, camOverridden: c.fx.cameraOverridden, camPhase: c.fx.spearCamPhase,
        shade: +c.world.shade.toFixed(3), tod: c.world.timeOfDay, hordeDensity: c.world.horde.density ?? c.world.horde.debug ?? null,
        music: a.music, ambience: a.ambience, heartbeat: a.heartbeat,
        vmArrows: c.viewmodel.debug.arrows, vmArm: c.viewmodel.debug.arm, vmShown: c.viewmodel.debug.shown,
        hp: c.player.hp, weapon: c.player.weapon, spears: c.player.spearsLeft, chain: c.player.chain, kills: c.player.kills, score: c.player.score, pstate: c.player.state,
        motionDemo: c.motionDemo, input: { ...c.input },
        geos: c.renderer.info.memory.geometries, texs: c.renderer.info.memory.textures,
        hudShown, waves: { state: c.waves.state, day: c.waves.day, wave: c.waves.wave },
        training: c.training.active,
      };
    };
    const S = [];
    S.push(snap('title-boot'));
    g.startBattle(1); g.step(30);
    S.push(snap('battle-start'));
    // Fight: enemies close, kills, blood, spear throw → spear cam, volley.
    for (let i = 0; i < 4; i++) g.spawn('levy', -1 + i * 0.7, -2.2, { facing: 0 });
    g.step(20);
    for (let k = 0; k < 12; k++) { g.action({ type: 'slash', dir: k % 2 ? 0 : Math.PI, power: 1 }); g.step(25); }
    c.combat.volley({ count: 300, duration: 1.0, warn: 0.5 });
    g.step(150);
    g.action({ type: 'switchWeapon', weapon: 'spear' }); g.step(40);
    g.spawn('immortal', 0.5, -14, { facing: 0 });
    g.step(5);
    g.action({ type: 'throw', power: 1 }); g.step(40);
    S.push(snap('battle-mid-spearcam'));
    // pause mid spear cam → Restart (menus' path)
    c.setMode('paused'); g.step(5);
    S.push(snap('paused'));
    c.setMode('battle', { day: c.waves.day }); g.step(2);
    S.push(snap('restart+2f'));
    g.step(120);
    S.push(snap('restart+122f'));
    c.setMode('paused'); c.setMode('title'); g.step(30);
    S.push(snap('quit-title'));
    g.startBattle(1); g.step(30);
    S.push(snap('battle2'));
    c.setMode('paused'); c.setMode('title'); g.step(5);
    g.startTraining(); g.step(60);
    S.push(snap('training'));
    c.setMode('paused'); c.setMode('title'); g.step(30);
    S.push(snap('title-after-training'));
    g.startBattle(1); g.step(30);
    S.push(snap('battle3'));
    return { S, errors: g.errors };
  });
  const keys = Object.keys(out.S[0]).filter((k) => !['label', 'input', 'hudShown'].includes(k));
  console.log(['key', ...out.S.map((s) => s.label)].join('\t'));
  for (const k of keys) console.log([k, ...out.S.map((s) => JSON.stringify(s[k]))].join('\t'));
  console.log('hudShown', JSON.stringify(out.S.map((s) => [s.label, s.hudShown])));
  console.log('input', JSON.stringify(out.S.map((s) => [s.label, s.input])));
  console.log('errors', JSON.stringify(out.errors), page.errors);
} finally { await l.close(); }
