// Scene fixer check: can the player SEE a volley, and does the sky go dark ("then we will fight in
// the shade")? Fires a 300-arrow volley in a clean sandbox with the player looking up 14°, and at
// several moments after 'loosed' reports:
//   * pixelsChangedByVolley: pixels that change by > 20 when the volley visuals (the volleyArrows
//     InstancedMesh and everything under it, e.g. the motion-blur streaks) are hidden;
//   * darkPx: pixels the volley visuals make darker by > 20 (the dark cloud against the sky);
//   * shade: world.shade, and the mean luma of the sky band and the ground band relative to the
//     frame just before 'loosed' (volley visuals hidden, so only the light changes).
// Screenshots each moment. Usage: node spartan/tests/e2e/scene-volley.mjs <outDir> [WxH=915x412] [tod=morning]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', tod = 'morning'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 240000 });
  await ev((tod) => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay(tod);
    g.ctx.ai.ensureAllies();
    g.ctx.hud.reset();
    g.ctx.__vol = [];
    g.ctx.events.on('volley', (e) => g.ctx.__vol.push([e.phase, g.ctx.time.gnow]));
    g.ctx.combat.volley({ count: 300, duration: 1.0, warn: 2.5 });
    g.ctx.player.pitch = 0.25;
  }, tod);
  const grab = () =>
    ev(() => {
      const cv = document.getElementById('scene');
      const t = document.createElement('canvas');
      t.width = cv.width;
      t.height = cv.height;
      const g2 = t.getContext('2d');
      g2.drawImage(cv, 0, 0);
      return Array.from(g2.getImageData(0, 0, t.width, t.height).data);
    });
  const setVis = (on) =>
    ev((on) => {
      const g = window.__game;
      g.ctx.scene.getObjectByName('volleyArrows').visible = on;
      g.render();
    }, on);
  // Mean luma of a band [y0, y1) x [x0, x1) in screen fractions.
  const band = (px, x0, y0, x1, y1) => {
    let s = 0;
    let n = 0;
    for (let y = Math.round(y0 * height); y < Math.round(y1 * height); y++) {
      for (let x = Math.round(x0 * width); x < Math.round(x1 * width); x++) {
        const i = (y * width + x) * 4;
        s += 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
        n++;
      }
    }
    return s / n;
  };
  // Sky band: upper right (over the sea); ground band: lower middle (left of the sword).
  const SKY = [0.5, 0.05, 0.95, 0.3];
  const GROUND = [0.2, 0.72, 0.6, 0.86];
  // Step to just before 'loosed' and take the unshaded reference.
  for (let i = 0; i < 80; i++) {
    const t = await ev(() => window.__game.ctx.time.gnow);
    if (t >= 2.4) break;
    await ev(() => window.__game.step(3));
  }
  await setVis(false);
  const ref = await grab();
  await setVis(true);
  const sky0 = band(ref, ...SKY);
  const ground0 = band(ref, ...GROUND);
  const programs0 = await ev(() => window.__game.ctx.renderer.info.programs.length);
  console.log('reference', JSON.stringify({ t: await ev(() => +window.__game.ctx.time.gnow.toFixed(2)), skyLuma: +sky0.toFixed(1), groundLuma: +ground0.toFixed(1), programs: programs0 }));
  for (let i = 0; i < 80; i++) {
    const loosed = await ev(() => window.__game.ctx.__vol.some((p) => p[0] === 'loosed'));
    if (loosed) break;
    await ev(() => window.__game.step(1));
  }
  for (const [label, frames] of [['loosed+0.5s', 30], ['loosed+1.0s', 30], ['loosed+1.5s', 30], ['loosed+1.9s', 24]]) {
    await ev((n) => window.__game.step(n), frames);
    const full = await grab();
    await setVis(false);
    const bare = await grab();
    await setVis(true);
    let changed = 0;
    let dark = 0;
    for (let i = 0; i < full.length; i += 4) {
      const d = Math.max(Math.abs(full[i] - bare[i]), Math.abs(full[i + 1] - bare[i + 1]), Math.abs(full[i + 2] - bare[i + 2]));
      if (d > 20) changed++;
      const lf = 0.2126 * full[i] + 0.7152 * full[i + 1] + 0.0722 * full[i + 2];
      const lb = 0.2126 * bare[i] + 0.7152 * bare[i + 1] + 0.0722 * bare[i + 2];
      if (lb - lf > 20) dark++;
    }
    const N = full.length / 4;
    const facts = await ev(() => {
      const c = window.__game.ctx;
      return { t: +c.time.gnow.toFixed(2), shade: +c.world.shade.toFixed(3), exposure: +c.renderer.toneMappingExposure.toFixed(3), sun: +c.world.sun.intensity.toFixed(3) };
    });
    facts.pixelsChangedByVolley = changed;
    facts.pctOfScreen = +((100 * changed) / N).toFixed(3);
    facts.darkPct = +((100 * dark) / N).toFixed(3);
    facts.skyLumaRel = +(band(bare, ...SKY) / sky0).toFixed(3);
    facts.groundLumaRel = +(band(bare, ...GROUND) / ground0).toFixed(3);
    facts.skyLumaWithVolleyRel = +(band(full, ...SKY) / sky0).toFixed(3);
    await page.waitForTimeout(300);
    const file = path.join(outDir, `volley-${label}.png`);
    await page.screenshot({ path: file, timeout: 300000 });
    console.log(file, JSON.stringify(facts));
  }
  // The streaks' program was compiled behind the boot screen: a volley compiles nothing new.
  const programs1 = await ev(() => window.__game.ctx.renderer.info.programs.length);
  console.log('programs', programs0, '->', programs1, programs1 === programs0 ? 'ok' : 'NEW PROGRAM COMPILED DURING THE VOLLEY');
  if (programs1 !== programs0) process.exitCode = 1;
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) {
    console.error(errs.slice(0, 5).join('\n'));
    process.exitCode = 1;
  }
} finally {
  await l.close();
}
