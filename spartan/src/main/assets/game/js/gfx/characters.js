/**
 * Characters: skinned actor models, a procedural animator and weapon props (DESIGN.md §11).
 *
 * Each Visual is ONE THREE.SkinnedMesh with rigid skinning (every vertex on one bone) and at
 * most two material groups (0 non-metal, 1 metal), sharing geometry per kind variant and
 * materials across all kinds, with its own bones and Skeleton. See characters/*.js for the
 * geometry toolkit, atlases, rig, kit, poses and animator.
 */
import * as THREE from 'three';
import { buildMaterials, atlasRects } from './characters/atlas.js';
import { B, createBones } from './characters/rig.js';
import { buildModel, spearParts, sagarisParts } from './characters/models.js';
import { KINDS, variantFor } from './characters/kinds.js';
import { Animator } from './characters/anim.js';
import { CLIPS } from './characters/poses.js';
import { PartBuilder, sweep, slab, mat } from './characters/geo.js';
import { METAL_TILES } from './characters/atlas.js';

export { CLIPS, KINDS };

const METAL = new Set(METAL_TILES);
const SOCKETS = { head: B.head, chest: B.chest, rightHand: B.handR, leftHand: B.handL, back: B.back };
const RIGHT = { dory: 'spear', persianSpear: 'spear', sagaris: 'axe', bow: 'arrow', bundle: 'bundle', xiphos: 'sword' };
const LEFT = { aspis: 'aspis', gerron: 'grip', spara: 'grip', wicker: null };

/** Ground-contact sample points of the left item (item-bone space, unit body metres). */
function itemPointsFor(shield, spec) {
  if (shield === 'aspis') {
    const c = [0.30, 0, -0.05], R = 0.44;
    return [[c[0] + R, 0, c[2]], [c[0] - R, 0, c[2]], [c[0], R, c[2]], [c[0], -R, c[2]], [c[0], 0, c[2] + 0.16]];
  }
  if (shield === 'spara') return [[0.3, 0.42, 0.06], [-0.3, 0.42, 0.06], [0.3, -1.15, 0.06], [-0.3, -1.15, 0.06]];
  if (shield === 'gerron') {
    const h = spec && spec.kind === 'immortal' ? 0.43 : 0.46;
    return [[0.25, h, 0.05], [-0.25, h, 0.05], [0.25, -h, 0.05], [-0.25, -h, 0.05]];
  }
  if (shield === 'bow') return [[0, -0.04, 0.52], [0, -0.04, -0.52]];
  return [];
}

/**
 * createCharacters(ctx) → { create(kind, opts), createProp(name), KINDS }.
 * Nothing is built until the first create/createProp (the constructor calls no module).
 */
export function createCharacters(ctx) {
  const quality = ctx.quality || 'medium';
  let mats = null;
  const entries = new Map();
  const props = new Map();
  let counter = 1;
  const stats = { variants: 0, buildMs: 0 };

  function materials() {
    if (!mats) {
      const t0 = performance.now();
      mats = buildMaterials(ctx);
      stats.atlasMs = performance.now() - t0;
    }
    return mats;
  }

  function entryFor(kind, opts, seed) {
    const { key, spec } = variantFor(kind, opts, seed);
    let e = entries.get(key);
    if (!e) {
      const t0 = performance.now();
      const rects = atlasRects(quality);
      const rectMap = { ...rects.cloth, ...rects.metal };
      const model = buildModel(spec, rectMap, quality);
      const kd = KINDS[kind];
      e = {
        key,
        spec,
        geometry: model.geometry,
        rest: model.rest,
        info: model.info,
        right: kind === 'spartan' && spec.weapon === 'xiphos' ? 'sword' : RIGHT[kd.weapon] || null,
        left: kd.weapon === 'bow' ? 'bow' : LEFT[kd.shield] || null,
        itemPoints: itemPointsFor(kd.weapon === 'bow' ? 'bow' : kd.shield, spec),
        bowTipY: kd.weapon === 'bow' ? -0.04 : 0,
      };
      entries.set(key, e);
      stats.variants++;
      stats.buildMs += performance.now() - t0;
    }
    return e;
  }

  function create(kind, opts = {}) {
    if (!KINDS[kind]) kind = 'levy';
    const m = materials();
    const seed = Number.isFinite(opts.seed) ? opts.seed | 0 : (counter++ * 7919 + 101) | 0;
    const e = entryFor(kind, opts, seed);
    const mesh = new THREE.SkinnedMesh(e.geometry, [m.cloth[0], m.metal[0]]);
    mesh.name = `actor-${kind}`;
    let overrides = null;
    if (kind === 'dummy') {
      const s = e.rest.s;
      overrides = { pelvis: [0, 0.02 * s, 0], spine: [0, 0.04 * s, 0], chest: [0, 1.3 * s, 0], neck: [0, 1.55 * s, 0], head: [0, 1.62 * s, 0] };
    }
    const { bones } = createBones(e.rest, overrides);
    mesh.add(bones[0]);
    mesh.bind(new THREE.Skeleton(bones));
    mesh.frustumCulled = true;
    mesh.boundingSphere = new THREE.Sphere(new THREE.Vector3(0, 0.9, 0), 1.7);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    // ±4% height, deterministic per seed
    const h = (((Math.imul(seed ^ 0x5bd1e995, 0x27d4eb2d) >>> 0) % 10007) / 10007 - 0.5) * 0.08;
    if (kind !== 'dummy') mesh.scale.setScalar(1 + h);
    const root = new THREE.Group();
    root.name = `visual-${kind}`;
    root.add(mesh);
    const anim = new Animator({
      bones, rest: e.rest, style: KINDS[kind].style, mesh, root, seed,
      right: e.right, left: e.left, itemPoints: e.itemPoints, bowTipY: e.bowTipY,
    });
    anim.update(0);
    const sockets = {};
    for (const k in SOCKETS) sockets[k] = bones[SOCKETS[k]];
    let blood = 0;
    let disposed = false;
    const visual = {
      root,
      mesh,
      kind,
      variant: e.key,
      height: KINDS[kind].height * (kind === 'dummy' ? 1 : 1 + h),
      sockets,
      anim: {
        setLocomotion: (speed) => anim.setLocomotion(speed),
        setGuard: (on) => anim.setGuard(on),
        play: (clip, o) => anim.play(clip, o),
        flinch: (dx, dz, strength) => anim.flinch(dx, dz, strength),
        get clip() {
          return anim.clip;
        },
        get playing() {
          return anim.playing;
        },
        get guard() {
          return anim.guard;
        },
        update: (gdt) => anim.update(gdt),
      },
      attach(object3d, socketName) {
        const bone = sockets[socketName] || sockets.chest;
        bone.attach(object3d);
        return object3d;
      },
      setBloodied(amount) {
        const a = Number.isFinite(amount) ? amount : 0;
        const lvl = a <= 0.05 ? 0 : a < 0.4 ? 1 : a < 0.75 ? 2 : 3;
        if (lvl === blood) return;
        blood = lvl;
        mesh.material[0] = m.cloth[lvl];
        mesh.material[1] = m.metal[lvl];
      },
      setVisible(on) {
        root.visible = !!on;
      },
      dispose() {
        if (disposed) return;
        disposed = true;
        root.removeFromParent();
        anim.onStrike = null;
        anim.onEnd = null;
        mesh.skeleton.dispose();
      },
      /** Triangles of this visual's geometry (both groups). */
      get triangles() {
        return e.info.triangles;
      },
      _animator: anim,
    };
    return visual;
  }

  // -------------------------------------------------------------------------------------
  // Props: plain meshes, shared geometry, tip on +Z, origin at the centre of mass.

  function propGeometry(name) {
    let g = props.get(name);
    if (g) return g;
    const rects = atlasRects(quality);
    const rectMap = { ...rects.cloth, ...rects.metal };
    const pb = new PartBuilder(rectMap, { skinned: false });
    const lo = quality === 'low';
    const add = (pt, tile, bone, m, extra) => pb.add(pt, Object.assign({ tile, bone: 0, m, group: METAL.has(tile) ? 1 : 0 }, extra));
    if (name === 'dory') {
      // ash shaft ~0.95 kg, iron head ~0.15 kg, bronze butt-spike ~0.3 kg → COM 1.13 m from the butt
      spearParts(add, 0, { length: 2.5, grip: 1.13, butt: 'spike', headTile: 'iron', buttTile: 'bronze', headLen: 0.27 }, new THREE.Matrix4(), lo);
    } else if (name === 'persianSpear') {
      spearParts(add, 0, { length: 2.0, grip: 1.03, butt: 'knob', headTile: 'iron', buttTile: 'bronzeDark', headLen: 0.22, shaftR: 0.0135 }, new THREE.Matrix4(), lo);
    } else if (name === 'sagaris') {
      sagarisParts(add, 0, { length: 0.8, grip: 0.61 }, new THREE.Matrix4(), lo);
    } else {
      // arrow: reed shaft, dark head, three fletchings; single material for instancing
      const toZ = new THREE.Matrix4().makeRotationX(Math.PI / 2);
      const c = -0.47;
      add(sweep([{ y: c - 0.02, rx: 0.004, rz: 0.004 }, { y: c + 0.7, rx: 0.004, rz: 0.004 }], { segs: 3, uvFn: (u, v) => [u * 0.2, v] }), 'wood', 0, mat([0, 0, 0]).multiply(toZ));
      add(sweep([{ y: c + 0.69, rx: 0.0055, rz: 0.0055 }, { y: c + 0.715, rx: 0.0075, rz: 0.0075 }, { y: c + 0.765, rx: 0, rz: 0 }], { segs: 3, uvFn: (u, v) => [u * 0.2, v * 0.2] }), 'woodDark', 0, toZ.clone());
      const nf = lo ? 2 : 3;
      for (let i = 0; i < nf; i++) {
        const a = (i / nf) * Math.PI * 2;
        const f = slab((u, v, out) => {
          out[0] = 0;
          out[1] = 0.003 + v * 0.011;
          out[2] = c + 0.01 + u * 0.1 + v * 0.012;
        }, 1, 1, 0.0012, { noEdges: true });
        add(f, 'wool', 0, mat([0, 0, 0], [0, 0, a]));
      }
    }
    g = pb.build();
    g.computeBoundingSphere();
    props.set(name, g);
    return g;
  }

  function createProp(name) {
    const m = materials();
    const geo = propGeometry(name);
    const twoGroups = geo.groups.length > 1 || (geo.groups[0] && geo.groups[0].materialIndex === 1);
    const mesh = new THREE.Mesh(geo, twoGroups ? [m.cloth[0], m.metal[0]] : m.cloth[0]);
    mesh.name = `prop-${name}`;
    mesh.castShadow = true;
    return mesh;
  }

  return {
    create,
    createProp,
    KINDS,
    CLIPS,
    /** Diagnostics: variants built, build time, atlas time. */
    get stats() {
      return stats;
    },
  };
}
