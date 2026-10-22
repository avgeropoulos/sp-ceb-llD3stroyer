// Review (performance lens): draw calls / triangles / programs over a long battle, attributed by
// pass and owner. Runs Day 1 fully (both entries) and Day 3 into phase C with a god-mode autopilot.
// Usage: node spartan/tests/e2e/review-perf-render.mjs [day1Frames=60000] [day3Frames=40000] [quality=medium] [out.json]
import fs from 'node:fs';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const D1 = Number(process.argv[2] || 60000);
const D3 = Number(process.argv[3] || 40000);
const quality = process.argv[4] || 'medium';
const outJson = process.argv[5] || null;
const SAMPLE_EVERY = 120;

const l = await launch();
const t0 = Date.now();
try {
  const page = await l.newPage({ width: 480, height: 270 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  await page.evaluate(() => { window.__rv.noDraw = true; });
  console.log(`booted in ${Date.now() - t0} ms`);
  const bootSample = await page.evaluate(() => { window.__game.step(2); return window.__rv.sampleRender(); });
  console.log('title', JSON.stringify({ total: bootSample.total, programs: bootSample.programs, geo: bootSample.geometries, tex: bootSample.textures }));

  const samples = [];
  async function runPhase(label, frames, setup, stopWhen) {
    await page.evaluate(setup);
    let done = 0;
    while (done < frames) {
      const s = await page.evaluate(([n]) => {
        const rv = window.__rv;
        rv.run(n, 10);
        return rv.sampleRender();
      }, [SAMPLE_EVERY]);
      done += SAMPLE_EVERY;
      s.label = label;
      samples.push(s);
      if (samples.length % 25 === 0) {
        console.log(`${label} t=${s.gnow.toFixed(0)}s day=${s.day}${s.phase || ''} wave=${s.wave} ${s.state} calls=${s.total.calls} (sh ${s.pass.shadow.calls} main ${s.pass.main.calls} vm ${s.pass.vm.calls}) tris=${s.total.tris} prog=${s.programs} enemies=${s.alive} allies=${s.allies} corpses=${s.corpses} parts=${s.particles} geo=${s.geometries} tex=${s.textures} wall=${((Date.now() - t0) / 1000).toFixed(0)}s`);
      }
      const stop = await page.evaluate(stopWhen);
      if (stop) { console.log(`${label}: stop condition at t=${s.gnow.toFixed(0)}`); break; }
    }
  }

  // Day 1 (morning waves, then the Immortals at dusk). hpScale speeds the waves up.
  await runPhase('day1', D1, () => {
    const rv = window.__rv;
    rv.hpScale = 0.6;
    rv.pilot(true);
    window.__game.startBattle(1);
  }, () => window.__game.ctx.waves.day >= 2 || window.__game.ctx.mode !== 'battle');

  // Day 3: phases A, B and C.
  await runPhase('day3', D3, () => {
    const rv = window.__rv;
    rv.hpScale = 0.5;
    rv.pilot(true);
    window.__game.startBattle(3);
  }, () => window.__game.ctx.mode !== 'battle');

  const peak = (f) => samples.reduce((m, s) => (f(s) > f(m) ? s : m), samples[0]);
  const report = (name, f) => {
    const s = peak(f);
    console.log(`PEAK ${name}=${f(s)} at ${s.label} t=${s.gnow.toFixed(0)} day=${s.day}${s.phase || ''} wave=${s.wave} enemies=${s.alive} allies=${s.allies} corpses=${s.corpses} particles=${s.particles}`);
    const gl = Object.entries(s.groups).sort((a, b) => b[1].calls - a[1].calls).map(([k, v]) => `${k}:${v.calls}/${Math.round(v.tris)}`).join('  ');
    console.log(`   groups ${gl}`);
    return s;
  };
  const pc = report('totalCalls', (s) => s.total.calls);
  report('mainCalls', (s) => s.pass.main.calls);
  report('shadowCalls', (s) => s.pass.shadow.calls);
  report('mainTris', (s) => s.pass.main.tris);
  report('shadowTris', (s) => s.pass.shadow.tris);
  report('programs', (s) => s.programs);
  report('particles', (s) => s.particles);
  report('corpses', (s) => s.corpses);
  console.log('peak-calls detail:', JSON.stringify(Object.entries(pc.detail).sort((a, b) => b[1].calls - a[1].calls).slice(0, 60)));
  const progs = samples.map((s) => s.programs);
  console.log(`programs: first=${progs[0]} last=${progs[progs.length - 1]} min=${Math.min(...progs)} max=${Math.max(...progs)}`);
  const changes = [];
  for (let i = 1; i < samples.length; i++) if (samples[i].programs !== samples[i - 1].programs) changes.push(`${samples[i].label}@${samples[i].gnow.toFixed(0)}s ${samples[i - 1].programs}->${samples[i].programs} (${samples[i].day}${samples[i].phase || ''} w${samples[i].wave} ${samples[i].state})`);
  console.log('program count changes:', changes.join('; ') || 'none');
  const geo = samples.map((s) => s.geometries);
  const tex = samples.map((s) => s.textures);
  console.log(`geometries: first=${geo[0]} max=${Math.max(...geo)} last=${geo[geo.length - 1]}; textures: first=${tex[0]} max=${Math.max(...tex)} last=${tex[tex.length - 1]}`);
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 5).join('\n'));
  if (outJson) fs.writeFileSync(outJson, JSON.stringify(samples.map(({ detail, ...s }) => s)));
  console.log(`wall ${(Date.now() - t0) / 1000}s`);
} finally {
  await l.close();
}
