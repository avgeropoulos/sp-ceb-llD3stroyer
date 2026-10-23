// Scene fixer check: spear-cam framing on the real game. Throws the dory at an Immortal 16 m out
// and screenshots the slow-motion flight and the impact. In flight it reports where the camera
// sits relative to the spear's flight line (up, and to the side away from the cliff) and how the
// shaft lies on screen: its angle from vertical (0° = the 'vertical pole' look) and its length.
// Usage: [SCENE_FX=old-fx.js] node spartan/tests/e2e/scene-spearcam.mjs <outDir> [WxH=915x412] [spawnX=0.4]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', spawnX = '0.4'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  // SCENE_FX=<file>: serve that file as gfx/fx.js (e.g. `git show HEAD:…/fx.js` for a before shot).
  if (process.env.SCENE_FX) {
    const body = fs.readFileSync(process.env.SCENE_FX, 'utf8');
    await page.route('**/js/gfx/fx.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body }));
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  const ev = (fn, a) => page.evaluate(fn, a);
  await ev((sx) => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay('morning');
    g.ctx.hud.reset();
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    g.step(60);
    g.spawn('immortal', sx, -16);
    g.step(20);
    g.ctx.__camLog = [];
    g.ctx.events.on('spearCam', (e) => g.ctx.__camLog.push(e.phase));
    g.ctx.__spear = null;
    g.ctx.events.on('spearThrown', (e) => (g.ctx.__spear = e.projectile));
    g.action({ type: 'throw', power: 0.85 });
    g.step(26);
  }, Number(spawnX));
  const flight = await ev(() => {
    const c = window.__game.ctx;
    const T = c.THREE;
    const pr = c.__spear;
    if (!pr) return null;
    const cam = c.camera;
    const dir = pr.vel.clone().normalize();
    const rel = cam.position.clone().sub(pr.pos);
    const side = new T.Vector3().crossVectors(dir, new T.Vector3(0, 1, 0)).setY(0).normalize();
    const q = pr.mesh.quaternion;
    const tip = new T.Vector3(0, 0, 1.2).applyQuaternion(q).add(pr.mesh.position);
    const butt = new T.Vector3(0, 0, -1.2).applyQuaternion(q).add(pr.mesh.position);
    const px = (v) => {
      const w = v.clone().project(cam);
      return [((w.x + 1) / 2) * innerWidth, ((1 - w.y) / 2) * innerHeight];
    };
    const a = px(tip);
    const b = px(butt);
    const dx = a[0] - b[0];
    const dy = a[1] - b[1];
    return {
      phase: c.fx.spearCamPhase,
      camUp: +(rel.y - 0).toFixed(2),
      camSide: +rel.dot(side).toFixed(2),
      camBack: +(-rel.dot(dir)).toFixed(2),
      camX: +cam.position.x.toFixed(2),
      shaftAngleFromVerticalDeg: +((Math.atan2(Math.abs(dx), Math.abs(dy)) * 180) / Math.PI).toFixed(1),
      shaftLenPx: +Math.hypot(dx, dy).toFixed(0),
      tipPx: a.map((v) => Math.round(v)),
      buttPx: b.map((v) => Math.round(v)),
    };
  });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(outDir, 'spearcam-flight.png'), timeout: 300000 });
  for (let i = 0; i < 40; i++) {
    const ph = await ev(() => window.__game.ctx.__camLog.slice(-1)[0]);
    if (ph === 'impact' || ph === 'end') break;
    await ev(() => window.__game.step(3));
  }
  await ev(() => window.__game.step(4));
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(outDir, 'spearcam-impact.png'), timeout: 300000 });
  const res = await ev(() => ({ log: window.__game.ctx.__camLog, errors: window.__game.errors.slice(0, 3) }));
  console.log(JSON.stringify({ flight, ...res }));
  if (res.errors.length || page.errors.length) process.exitCode = 1;
} finally {
  await l.close();
}
