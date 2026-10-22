// Stand-in for gfx/characters.js used by the ai sandbox (the real one is written concurrently).
// Each Visual is a capsule with a facing marker and a spear, and implements the §11 anim API:
// it logs every clip played and fires onStrike at CLIPS[clip].strikeAt·duration and onEnd at
// duration, exactly like the real animator must.
import * as THREE from 'three';

export const CLIPS = {
  thrust: { strikeAt: 0.55, duration: 0.9, layer: 'upper', loop: false },
  overhead: { strikeAt: 0.6, duration: 1.0, layer: 'upper', loop: false },
  slash: { strikeAt: 0.5, duration: 0.8, layer: 'upper', loop: false },
  axe: { strikeAt: 0.62, duration: 1.4, layer: 'full', loop: false },
  bash: { strikeAt: 0.4, duration: 0.7, layer: 'upper', loop: false },
  shoot: { strikeAt: 0.72, duration: 1.6, layer: 'upper', loop: false },
  throw: { strikeAt: 0.6, duration: 1.0, layer: 'upper', loop: false },
  hit: { strikeAt: 0, duration: 0.4, layer: 'upper', loop: false },
  stagger: { strikeAt: 0, duration: 1.1, layer: 'full', loop: false },
  knockdown: { strikeAt: 0, duration: 0.9, layer: 'full', loop: false },
  getup: { strikeAt: 0, duration: 1.0, layer: 'full', loop: false },
  block_hit: { strikeAt: 0, duration: 0.35, layer: 'upper', loop: false },
  cheer: { strikeAt: 0, duration: 1.6, layer: 'upper', loop: false },
  die_back: { strikeAt: 0, duration: 1.2, layer: 'full', loop: false },
  die_forward: { strikeAt: 0, duration: 1.2, layer: 'full', loop: false },
  die_crumple: { strikeAt: 0, duration: 1.3, layer: 'full', loop: false },
  die_spear: { strikeAt: 0, duration: 1.5, layer: 'full', loop: false },
  fall_sea: { strikeAt: 0, duration: 1.4, layer: 'full', loop: false },
};

const KINDS = {
  spartan: { height: 1.8, color: 0x9b2a20 },
  levy: { height: 1.74, color: 0xb59a6a },
  sparabara: { height: 1.76, color: 0x8a6b3e },
  immortal: { height: 1.82, color: 0x5a3a7a },
  archer: { height: 1.72, color: 0x5d7a3a },
  captain: { height: 1.95, color: 0x2a2a2a },
  dummy: { height: 1.75, color: 0x7a6040 },
  helot: { height: 1.68, color: 0x8a8a80 },
};

const DEATHS = new Set(['die_back', 'die_forward', 'die_crumple', 'die_spear', 'fall_sea']);

export function createCharacters(ctx) {
  const geo = {};
  const mats = {};
  const spearGeo = new THREE.CylinderGeometry(0.02, 0.02, 2.2, 5).rotateX(Math.PI / 2);
  const noseGeo = new THREE.BoxGeometry(0.16, 0.12, 0.22);
  const spearMat = new THREE.MeshStandardMaterial({ color: 0x5a4630, roughness: 0.8 });
  const noseMat = new THREE.MeshStandardMaterial({ color: 0xd8c8a0, roughness: 0.6 });
  const stats = { created: 0, disposed: 0, live: 0 };

  function create(kind, opts = {}) {
    const K = KINDS[kind] || KINDS.levy;
    if (!geo[kind]) {
      geo[kind] = new THREE.CapsuleGeometry(0.3, K.height - 0.6, 4, 10).translate(0, K.height / 2, 0);
      mats[kind] = new THREE.MeshStandardMaterial({ color: K.color, roughness: 0.75, metalness: 0.05 });
    }
    const root = new THREE.Group();
    root.name = `${kind}${opts.name ? ':' + opts.name : ''}`;
    const body = new THREE.Group();
    root.add(body);
    const mesh = new THREE.Mesh(geo[kind], mats[kind].clone());
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    body.add(mesh);
    const nose = new THREE.Mesh(noseGeo, noseMat);
    nose.position.set(0, K.height * 0.86, 0.26);
    body.add(nose);
    const spear = new THREE.Mesh(spearGeo, spearMat);
    spear.position.set(0.28, K.height * 0.62, 0.3);
    if (kind !== 'dummy') body.add(spear);
    if (opts.name === 'Leonidas') {
      const crest = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.25, 0.5), new THREE.MeshStandardMaterial({ color: 0xe0c040 }));
      crest.position.set(0, K.height + 0.08, 0);
      body.add(crest);
    }
    const sockets = { head: new THREE.Object3D(), chest: new THREE.Object3D(), rightHand: new THREE.Object3D(), leftHand: new THREE.Object3D(), back: new THREE.Object3D() };
    sockets.head.position.y = K.height * 0.9;
    sockets.chest.position.y = K.height * 0.7;
    for (const k in sockets) body.add(sockets[k]);

    const log = [];
    let clip = null, t = 0, dur = 1, strikeAt = 0, struck = true, ended = true, onStrike = null, onEnd = null;
    let loco = 0, guard = false, flinchT = 0, phase = 0, deadPose = 0, deadDir = 1;

    const anim = {
      log,
      setLocomotion(speed) { loco = speed; },
      setGuard(on) { guard = on; },
      play(name, o = {}) {
        if (!CLIPS[name]) throw new Error(`unknown clip ${name}`);
        clip = name;
        t = 0;
        dur = o.duration > 0 ? o.duration : CLIPS[name].duration;
        strikeAt = CLIPS[name].strikeAt * dur;
        struck = !(CLIPS[name].strikeAt > 0);
        ended = false;
        onStrike = o.onStrike || null;
        onEnd = o.onEnd || null;
        log.push({ clip: name, at: ctx.time.gnow, duration: dur });
        if (DEATHS.has(name)) deadDir = name === 'die_forward' ? 1 : -1;
      },
      flinch(dx, dz, s) { flinchT = 0.3 * s; },
      get clip() { return ended ? null : clip; },
      get playing() { return !ended; },
      get guard() { return guard; },
      update(gdt) {
        phase += gdt * (1 + loco * 4);
        if (!ended) {
          t += gdt;
          if (!struck && t >= strikeAt) {
            struck = true;
            const fn = onStrike;
            if (fn) fn();
          }
          if (!ended && t >= dur) {
            ended = true;
            const fn = onEnd;
            if (fn) fn();
          }
        }
        // Pose: walk bob, wind-up lean back and a red glow, strike lunge, stagger tilt, death fall.
        const winding = !ended && !struck && CLIPS[clip].strikeAt > 0;
        const p = winding ? t / Math.max(strikeAt, 1e-3) : 0;
        mesh.material.emissive.setRGB(winding ? 0.6 * p : 0, 0, 0);
        spear.position.z = winding ? 0.3 - 0.35 * p : (!ended && struck && CLIPS[clip].strikeAt > 0 ? 0.75 : 0.3);
        body.position.y = loco > 0.1 ? Math.abs(Math.sin(phase * 3)) * 0.05 : 0;
        if (clip && DEATHS.has(clip)) deadPose = Math.min(1, deadPose + gdt / 0.7);
        const stag = !ended && (clip === 'stagger' || clip === 'knockdown') ? 0.35 : 0;
        body.rotation.x = deadDir * deadPose * Math.PI / 2 + (winding ? -0.15 * p : 0) - stag + (flinchT > 0 ? -0.2 : 0);
        if (flinchT > 0) flinchT -= gdt;
      },
    };
    stats.created++;
    stats.live++;
    let disposed = false;
    const v = {
      root, mesh, kind, sockets, anim, opts,
      attach(o, socket) { (sockets[socket] || root).attach(o); },
      bloodied: 0,
      setBloodied(b) { v.bloodied = b; },
      setVisible(on) { root.visible = on; },
      dispose() {
        if (disposed) return;
        disposed = true;
        stats.disposed++;
        stats.live--;
        mesh.material.dispose();
        if (root.parent) root.parent.remove(root);
      },
    };
    return v;
  }

  return {
    create,
    createProp() { return new THREE.Mesh(spearGeo, spearMat); },
    KINDS,
    stats,
  };
}
