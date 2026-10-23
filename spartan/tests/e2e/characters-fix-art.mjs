// Characters fixer: before/after shots and numbers for the character art findings (review C).
//   line     allies in the line + three Persians at duel range: where the allies' dory shafts cross
//            the frame (screen y of every in-frame sample) against the enemy heads, and the luma of
//            each wicker shield face against the ground.
//   guard    a Kissian levy and a sparabara at 1.6–2.2 m, side-on: the guard arm (elbow height vs the
//            shoulder) and the thrust 'back' key (the fist must stay behind the shoulder: chambered).
//   corpses  eight Persians cut down in a heap, looked down at: blood-stain variety (each corpse's
//            uBloodSeed) and the closest distance between lying bodies (capsule model of ai.js).
// --rev=<git rev> serves the characters fixer's files (ai.js, characters.js, characters/*.js) from
// that revision, so 'before' and 'after' run against the same tree otherwise.
// Usage: node spartan/tests/e2e/characters-fix-art.mjs <outDir> [WxH=915x412] [--rev=7f85c80] [line|guard|corpses ...]
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', ...rest] = process.argv;
const rev = (rest.find((a) => a.startsWith('--rev=')) || '').slice(6);
const wanted = rest.filter((a) => !a.startsWith('--rev='));
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const page = await l.newPage({ width, height });
if (rev) {
  const OWN = /\/js\/(game\/ai\.js|gfx\/characters\.js|gfx\/characters\/[a-z]+\.js)$/;
  await page.route(OWN, async (route) => {
    const rel = new URL(route.request().url()).pathname.replace(/^\//, '');
    const body = execFileSync('git', ['show', `${rev}:spartan/${rel}`], { encoding: 'utf8', maxBuffer: 1 << 26 });
    await route.fulfill({ status: 200, contentType: 'text/javascript', body });
  });
}
const ev = (fn, arg) => page.evaluate(fn, arg);

// Mean luma (Rec.709 on sRGB bytes) of axis-aligned boxes read back from the drawn canvas.
const lumaBoxes = (boxes) =>
  ev((boxes) => {
    const c = document.getElementById('scene');
    const tmp = document.createElement('canvas');
    tmp.width = c.width;
    tmp.height = c.height;
    const g2 = tmp.getContext('2d');
    g2.drawImage(c, 0, 0);
    const sx = c.width / innerWidth, sy = c.height / innerHeight;
    return boxes.map(([x0, y0, x1, y1]) => {
      const X0 = Math.max(0, Math.round(x0 * sx)), Y0 = Math.max(0, Math.round(y0 * sy));
      const W = Math.max(1, Math.min(c.width - X0, Math.round((x1 - x0) * sx))), H = Math.max(1, Math.min(c.height - Y0, Math.round((y1 - y0) * sy)));
      const d = g2.getImageData(X0, Y0, W, H).data;
      let s = 0;
      for (let i = 0; i < d.length; i += 4) s += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
      return +(s / (d.length / 4)).toFixed(1);
    });
  }, boxes);

async function shot(name, facts) {
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file, timeout: 300000 });
  console.log(file, JSON.stringify(facts));
}

const sandbox = () =>
  ev(() => {
    const g = window.__game;
    const c = g.ctx;
    g.startBattle(1);
    c.waves.stop();
    c.ai.clear();
    c.combat.reset();
    c.world.setTimeOfDay('morning');
    c.hud.reset();
  });

const runs = {
  async line() {
    await sandbox();
    const facts = await ev(() => {
      const g = window.__game;
      const c = g.ctx;
      const T = c.THREE;
      c.ai.ensureAllies();
      g.spawn('levy', -1.1, -2.2, { variant: 'kissian' });
      g.spawn('levy', 1.0, -2.5, { variant: 'mede' });
      g.spawn('immortal', 0.0, -4.8);
      g.step(60);
      const toPx = (v) => { const p = v.clone().project(c.camera); return [((p.x + 1) / 2) * innerWidth, ((1 - p.y) / 2) * innerHeight, p.z]; };
      const spears = [];
      for (const a of c.actors.filter((x) => x.role === 'ally')) {
        const hand = new T.Vector3();
        const q = new T.Quaternion();
        a.visual.sockets.rightHand.getWorldPosition(hand);
        a.visual.sockets.rightHand.getWorldQuaternion(q);
        const ys = [];
        const ax = new T.Vector3(0, 0, 1).applyQuaternion(a.visual._animator.bones[15].getWorldQuaternion(new T.Quaternion()));
        for (let s = -1.1; s <= 1.5; s += 0.1) {
          const [x, y, z] = toPx(hand.clone().addScaledVector(ax, s));
          if (x >= 0 && x <= innerWidth && y >= 0 && y <= innerHeight && z < 1) ys.push(Math.round(y));
        }
        const [bx, by, bz] = toPx(a.pos.clone().setY(a.groundY + 1.3));
        const bodyIn = bx >= 0 && bx <= innerWidth && by >= 0 && by <= innerHeight && bz < 1;
        if (ys.length) spears.push({ allyX: +a.pos.x.toFixed(2), allyZ: +a.pos.z.toFixed(2), bodyInFrame: bodyIn, samples: ys.length, yMin: Math.min(...ys), yMax: Math.max(...ys) });
      }
      const enemies = [];
      const boxes = [];
      for (const a of c.actors.filter((x) => x.team === 'persian')) {
        const h = new T.Vector3();
        a.visual.sockets.head.getWorldPosition(h);
        const [, hy] = toPx(h);
        // the shield face: project the gerron's corners (item bone space), shrink to the middle 50%
        const item = a.visual._animator.bones[9];
        const s = a.visual._animator.s;
        const pts = [[0.2, 0.32], [-0.2, 0.32], [0.2, -0.32], [-0.2, -0.32]].map(([x, y]) => toPx(item.localToWorld(new T.Vector3(x * s, y * s, 0.07 * s))));
        const xs = pts.map((p) => p[0]), yy = pts.map((p) => p[1]);
        const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...yy) + Math.max(...yy)) / 2;
        const hw = (Math.max(...xs) - Math.min(...xs)) * 0.25, hh = (Math.max(...yy) - Math.min(...yy)) * 0.25;
        boxes.push([cx - hw, cy - hh, cx + hw, cy + hh]);
        enemies.push({ kind: a.kind, headY: Math.round(hy), shieldBox: [cx - hw, cy - hh, cx + hw, cy + hh].map(Math.round) });
      }
      return { spears, enemies, boxes };
    });
    // ground: a strip at the bottom-middle in front of the feet, between the shields
    const ground = [width * 0.42, height * 0.86, width * 0.58, height * 0.94];
    const lum = await lumaBoxes([...facts.boxes, ground]);
    const shields = lum.slice(0, -1);
    delete facts.boxes;
    facts.enemies.forEach((e, i) => { e.shieldLuma = shields[i]; });
    facts.groundLuma = lum[lum.length - 1];
    const heads = facts.enemies.filter((e) => e.kind === 'levy').map((e) => e.headY);
    facts.shaftsAtFaceHeight = facts.spears.filter((s) => s.yMin <= Math.max(...heads) + 40).length;
    await shot('line', facts);
  },
  async guard() {
    await sandbox();
    // Brains off: only the animators run, so the two hold their guard (no wind-up in the shot).
    const facts = await ev(() => {
      const g = window.__game;
      const c = g.ctx;
      const T = c.THREE;
      const lv = g.spawn('levy', -0.75, -1.9, { variant: 'kissian' });
      const sp = g.spawn('sparabara', 0.95, -2.3);
      window.__aiUpdate = c.ai.update;
      c.ai.update = (dt) => { for (const a of c.actors) a.visual.anim.update(dt); };
      for (const a of [lv, sp]) a.visual.anim.setGuard(true);
      g.step(45);
      const w = (a, i) => a.visual._animator.bones[i].getWorldPosition(new T.Vector3());
      const arm = (a) => {
        const sh = w(a, 12), el = w(a, 13), hand = w(a, 14);
        return { elbowBelowShoulder: +(sh.y - el.y).toFixed(3), handAboveShoulder: +(hand.y - sh.y).toFixed(3) };
      };
      const toPx = (v) => { const p = v.clone().project(c.camera); return [((p.x + 1) / 2) * innerWidth, ((1 - p.y) / 2) * innerHeight]; };
      const box = (a) => {
        const item = a.visual._animator.bones[9];
        const s = a.visual._animator.s;
        const h = a.kind === 'sparabara' ? [0.25, 0.3, -0.9] : [0.2, 0.32, -0.32];
        const pts = [[h[0], h[1]], [-h[0], h[1]], [h[0], h[2]], [-h[0], h[2]]].map(([x, y]) => toPx(item.localToWorld(new T.Vector3(x * s, y * s, 0.07 * s))));
        const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
        const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2;
        const hw = (Math.max(...xs) - Math.min(...xs)) * 0.25, hh = (Math.max(...ys) - Math.min(...ys)) * 0.25;
        return [cx - hw, cy - hh, cx + hw, cy + hh];
      };
      return { levy: arm(lv), sparabara: arm(sp), boxes: [box(lv), box(sp)] };
    });
    // a clear ground patch between the two, above the player's shadow
    const lum = await lumaBoxes([...facts.boxes, [width * 0.41, height * 0.6, width * 0.49, height * 0.67]]);
    facts.levy.shieldLuma = lum[0];
    facts.sparabara.shieldLuma = lum[1];
    facts.groundLuma = lum[2];
    delete facts.boxes;
    await shot('guard', facts);
    // the thrust's 'back' key (t = 0.36–0.5 of 1.4 s): the fist must be chambered behind the shoulder
    const chamber = await ev(() => {
      const g = window.__game;
      const c = g.ctx;
      const T = c.THREE;
      const out = {};
      const as = c.actors.filter((x) => x.team === 'persian');
      for (const a of as) a.visual.anim.play('thrust', { duration: 1.4 });
      g.step(Math.round(0.47 * 1.4 * 60));
      for (const a of as) {
        const b = a.visual._animator.bones;
        const sh = b[12].getWorldPosition(new T.Vector3());
        const hand = b[14].getWorldPosition(new T.Vector3());
        const f = a.facing;
        out[a.kind] = { fistAheadOfShoulder: +((hand.x - sh.x) * Math.sin(f) + (hand.z - sh.z) * Math.cos(f)).toFixed(3), fistAboveShoulder: +(hand.y - sh.y).toFixed(3) };
      }
      return out;
    });
    await shot('guard-thrust-back', chamber);
    await ev(() => { window.__game.ctx.ai.update = window.__aiUpdate; });
  },
  async corpses() {
    await sandbox();
    const facts = await ev(() => {
      const g = window.__game;
      const c = g.ctx;
      const kinds = ['levy', 'sparabara', 'levy', 'immortal', 'levy', 'levy', 'sparabara', 'levy'];
      // one at a time, like a fight at the player's front: each walks up, is cut down and falls
      // where the last ones fell
      for (let i = 0; i < kinds.length; i++) {
        const a = g.spawn(kinds[i], -0.4 + ((i * 0.37) % 1), -2.0, kinds[i] === 'levy' ? { variant: 'mede' } : {});
        g.step(40);
        c.combat.actorStrike({ alive: true, pos: { x: c.player.pos.x, y: 0, z: c.player.pos.z }, facing: Math.PI }, { target: a, damage: 9999, reach: 4 });
        g.step(30);
      }
      g.step(150);
      c.player.pitch = -0.5;
      g.step(2);
      // lying bodies as capsules (head–feet segments of the settled skeleton, radius 0.2 m)
      const T = c.THREE;
      const segs = c.ai.corpses.map((k) => {
        const b = k.visual._animator.bones;
        const head = b[4].getWorldPosition(new T.Vector3());
        const fl = b[18].getWorldPosition(new T.Vector3()), fr = b[21].getWorldPosition(new T.Vector3());
        return [head.x, head.z, (fl.x + fr.x) / 2, (fl.z + fr.z) / 2];
      });
      const segDist = (a, b) => {
        const d = (px, pz, s) => {
          const vx = s[2] - s[0], vz = s[3] - s[1];
          const t = Math.max(0, Math.min(1, ((px - s[0]) * vx + (pz - s[1]) * vz) / (vx * vx + vz * vz || 1)));
          return Math.hypot(px - s[0] - t * vx, pz - s[1] - t * vz);
        };
        let m = Math.min(d(a[0], a[1], b), d(a[2], a[3], b), d(b[0], b[1], a), d(b[2], b[3], a));
        // crossing segments
        const cr = (ax, az, bx, bz, cx, cz) => (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
        const s1 = cr(a[0], a[1], a[2], a[3], b[0], b[1]), s2 = cr(a[0], a[1], a[2], a[3], b[2], b[3]);
        const s3 = cr(b[0], b[1], b[2], b[3], a[0], a[1]), s4 = cr(b[0], b[1], b[2], b[3], a[2], a[3]);
        if (s1 * s2 < 0 && s3 * s4 < 0) m = 0;
        return m;
      };
      let overl = 0, minD = Infinity, cost = 0;
      for (let i = 0; i < segs.length; i++) for (let j = i + 1; j < segs.length; j++) {
        const dd = segDist(segs[i], segs[j]);
        minD = Math.min(minD, dd);
        if (dd < 0.4) overl++;
        cost += Math.max(0, 0.5 - dd);
      }
      const seeds = c.ai.corpses.map((k) => {
        const sk = k.visual.mesh.skeleton;
        return +(sk.boneMatrices[28 * 16] || 0).toFixed(3);
      });
      const clips = c.ai.corpses.map((k) => k.deathClip || '?');
      return { corpses: c.ai.corpses.length, overlappingPairs: overl, overlapCost: +cost.toFixed(2), closestBodies: +minD.toFixed(2), clips, bloodSeeds: seeds };
    });
    await shot('corpses', facts);
  },
};

let bad = 0;
try {
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  for (const name of wanted.length ? wanted : Object.keys(runs)) await runs[name]();
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) {
    bad = 1;
    console.error(errs.slice(0, 8).join('\n'));
  }
} finally {
  await l.close();
}
process.exitCode = bad;
