/**
 * Xerxes' army filling the far pass: thousands of tiny soldiers in national contingents, a forest
 * of spears, standards and a pall of dust. Four instanced draws, all animated on the GPU. Instance
 * order is shuffled so lowering the density thins the whole army evenly.
 */
import * as THREE from 'three';
import { clamp, lerp, createRng } from '../../core/math.js';

// National contingents (sRGB). Herodotus 7.61–80: Persians and Medes in coloured tunics and
// trousers, Kissians in turbans, Saka in pointed caps.
const PALETTES = [
  ['#8a2f22', '#a07a3a', '#3a4a78', '#8d8270', '#5b6236', '#6e3a5e'],
  ['#b88a2e', '#6a2a50', '#c8bca0', '#8e2a1c', '#4a3c6c'],
  ['#7d6a52', '#5a3424', '#8a7a62', '#6a5a3a'],
  ['#6a4a2c', '#8a3222', '#5e4e36', '#7a6a4a'],
];

/** Soldier: tapered body, peaked head (cap or tiara), wicker shield. 14 triangles, faces +Z. */
function soldierGeometry() {
  const pos = [];
  const part = [];
  const quad = (a, b, c, d, p) => {
    pos.push(...a, ...b, ...c, ...a, ...c, ...d);
    for (let i = 0; i < 6; i++) part.push(p);
  };
  const tri = (a, b, c, p) => {
    pos.push(...a, ...b, ...c);
    part.push(p, p, p);
  };
  const bw = 0.16;
  const bd = 0.11;
  const tw = 0.22;
  const td = 0.13;
  const ht = 1.44;
  const B = [[-bw, 0, bd], [bw, 0, bd], [bw, 0, -bd], [-bw, 0, -bd]];
  const T = [[-tw, ht, td], [tw, ht, td], [tw, ht, -td], [-tw, ht, -td]];
  for (let i = 0; i < 4; i++) {
    const j = (i + 1) % 4;
    quad(B[i], B[j], T[j], T[i], 0);
  }
  const hb = 0.11;
  const apex = [0, 1.86, 0];
  const H = [[-hb, 1.4, hb], [hb, 1.4, hb], [hb, 1.4, -hb], [-hb, 1.4, -hb]];
  for (let i = 0; i < 4; i++) tri(H[i], H[(i + 1) % 4], apex, 1);
  quad([-0.24, 0.55, 0.2], [0.12, 0.55, 0.2], [0.12, 1.22, 0.22], [-0.24, 1.22, 0.22], 2);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('part', new THREE.Float32BufferAttribute(part, 1));
  g.computeVertexNormals();
  return g;
}

/** Unit spear/pole quad: x ∈ [−0.5, 0.5], y ∈ [0, 1]; widened toward the camera in the shader. */
function lineGeometry() {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute([-0.5, 0, 0, 0.5, 0, 0, 0.5, 1, 0, -0.5, 1, 0], 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute([0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1], 3));
  g.setIndex([0, 1, 2, 0, 2, 3]);
  return g;
}

/** Standard: a pole (part 0) and a four-panel banner (part 1) that waves. 10 triangles, faces +Z. */
function standardGeometry() {
  const pos = [];
  const part = [];
  const uvx = [];
  const add = (p, k, u) => {
    pos.push(...p);
    part.push(k);
    uvx.push(u);
  };
  const pw = 0.07;
  const h = 5.2;
  for (const p of [[-pw, 0, 0], [pw, 0, 0], [pw, h, 0], [-pw, 0, 0], [pw, h, 0], [-pw, h, 0]]) add(p, 0, 0);
  const top = h - 0.15;
  const bh = 0.8;
  const bwid = 1.05;
  for (let i = 0; i < 4; i++) {
    const x0 = pw + (i / 4) * bwid;
    const x1 = pw + ((i + 1) / 4) * bwid;
    const u0 = i / 4;
    const u1 = (i + 1) / 4;
    add([x0, top - bh, 0], 1, u0);
    add([x1, top - bh, 0], 1, u1);
    add([x1, top, 0], 1, u1);
    add([x0, top - bh, 0], 1, u0);
    add([x1, top, 0], 1, u1);
    add([x0, top, 0], 1, u0);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('part', new THREE.Float32BufferAttribute(part, 1));
  g.setAttribute('flag', new THREE.Float32BufferAttribute(uvx, 1));
  g.computeVertexNormals();
  return g;
}

/** Lays out the army: contingents in loose ranks between the cliff foot and the lip. */
function layoutArmy(layout, total) {
  const R = createRng(1480);
  const men = [];
  let z = -82;
  let guard = 0;
  while (men.length < total && z > -470 && guard++ < 400) {
    const depth = 7 + R.next() * 16;
    const cx = layout.cliffX(z - depth * 0.5) + 3;
    const sx = layout.shoreX(z - depth * 0.5) - 2.2;
    const width = sx - cx;
    // One to three contingents side by side.
    const nBlocks = width > 26 ? 3 : width > 15 ? 2 : 1;
    const gap = 2 + R.next() * 3;
    const bw = (width - gap * (nBlocks - 1)) / nBlocks;
    for (let b = 0; b < nBlocks; b++) {
      const pal = PALETTES[Math.floor(R.next() * PALETTES.length)];
      const x0 = cx + b * (bw + gap);
      const spacingX = 0.95 + R.next() * 0.35;
      const spacingZ = 1.05 + R.next() * 0.4;
      const loose = z > -105 ? 0.6 : 0.22;
      const nation = Math.floor(R.next() * 4);
      for (let rz = 0; rz < depth; rz += spacingZ) {
        for (let rx = 0; rx < bw; rx += spacingX) {
          if (z > -100 && R.next() < 0.55) continue;
          const x = x0 + rx + (R.next() - 0.5) * spacingX * loose * 2;
          const zz = z - rz + (R.next() - 0.5) * spacingZ * loose * 2;
          men.push({ x, z: zz, col: pal[Math.floor(R.next() * pal.length)], nation, spear: R.next() < 0.82 });
        }
      }
    }
    z -= depth + 2 + R.next() * 7;
  }
  // Shuffle so any prefix is spread over the whole army.
  for (let i = men.length - 1; i > 0; i--) {
    const j = Math.floor(R.next() * (i + 1));
    const t = men[i];
    men[i] = men[j];
    men[j] = t;
  }
  return men.slice(0, total);
}

/**
 * @param {object} layout
 * @param {{ patch: Function, fogUniforms: object, time: { value: number }, quality: string, dustMap: THREE.Texture, lit: object }} o
 */
export function buildHorde(layout, o) {
  const total = o.quality === 'high' ? 5200 : o.quality === 'low' ? 1800 : 3600;
  const men = layoutArmy(layout, total);
  const n = men.length;
  const lit = o.lit;

  // --- Soldiers ---
  const bodyMat = new THREE.MeshStandardMaterial({ roughness: 0.92, metalness: 0 });
  bodyMat.name = 'hordeBody';
  o.patch(bodyMat, {
    key: 'hordeBody',
    fog: {},
    extra: (sh) => {
      sh.uniforms.wTime = o.time;
      sh.vertexShader = `attribute float part;\nuniform float wTime;\n${sh.vertexShader}`
        .replace(
          '#include <color_vertex>',
          `#include <color_vertex>
          #ifdef USE_INSTANCING_COLOR
            vec3 skin = vec3( 0.28, 0.17, 0.11 );
            vec3 wicker = mix( vec3( 0.3, 0.22, 0.12 ), instanceColor.rgb, fract( instanceMatrix[ 3 ].x * 3.7 + instanceMatrix[ 3 ].z * 1.3 ) * 0.6 );
            vColor.rgb = part < 0.5 ? instanceColor.rgb : ( part < 1.5 ? mix( instanceColor.rgb, skin, 0.55 ) : wicker );
          #endif`,
        )
        .replace(
          '#include <begin_vertex>',
          `#include <begin_vertex>
          {
            float ph = instanceMatrix[ 3 ].x * 1.7 + instanceMatrix[ 3 ].z * 0.9;
            float k = transformed.y / 1.8;
            transformed.x += sin( wTime * 1.4 + ph ) * 0.05 * k;
            transformed.y += abs( sin( wTime * 2.1 + ph ) ) * 0.025;
          }`,
        );
    },
  });
  const bodies = new THREE.InstancedMesh(soldierGeometry(), bodyMat, n);
  bodies.name = 'hordeBodies';
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const e = new THREE.Euler();
  const s = new THREE.Vector3();
  const p = new THREE.Vector3();
  const c = new THREE.Color();
  const R = createRng(2480);
  for (let i = 0; i < n; i++) {
    const man = men[i];
    const y = layout.terrainHeight(man.x, man.z);
    man.y = y;
    const sc = 0.92 + R.next() * 0.16;
    e.set(0, (R.next() - 0.5) * 0.6, 0);
    q.setFromEuler(e);
    m.compose(p.set(man.x, y, man.z), q, s.set(sc, sc, sc));
    bodies.setMatrixAt(i, m);
    c.set(man.col);
    c.multiplyScalar(0.85 + R.next() * 0.3);
    bodies.setColorAt(i, c);
  }
  bodies.instanceMatrix.needsUpdate = true;
  bodies.instanceColor.needsUpdate = true;
  bodies.computeBoundingSphere();

  // --- Spears: one per spearman, in the same order, so density thins both together ---
  const spearMen = [];
  for (let i = 0; i < n; i++) if (men[i].spear) spearMen.push(i);
  const spearMat = new THREE.MeshBasicMaterial({ color: 0xffffff });
  spearMat.name = 'hordeSpears';
  o.patch(spearMat, {
    key: 'hordeSpears',
    fog: {},
    extra: (sh) => {
      sh.uniforms.wTime = o.time;
      sh.uniforms.wShaft = lit.shaft;
      sh.uniforms.wTip = lit.tip;
      sh.vertexShader = `uniform float wTime;\nvarying float vT;\n${sh.vertexShader}`.replace(
        '#include <project_vertex>',
        `vec4 baseW = modelMatrix * instanceMatrix * vec4( 0.0, 0.0, 0.0, 1.0 );
        vec4 topW = modelMatrix * instanceMatrix * vec4( 0.0, 1.0, 0.0, 1.0 );
        vec3 P = mix( baseW.xyz, topW.xyz, position.y );
        P.x += sin( wTime * 1.1 + baseW.x * 0.7 + baseW.z * 0.31 ) * 0.1 * position.y;
        vec3 axis = normalize( topW.xyz - baseW.xyz );
        vec3 toCam = cameraPosition - P;
        float dist = length( toCam );
        vec3 side = normalize( cross( axis, toCam / dist ) );
        P += side * position.x * max( 0.035, dist * 0.0021 );
        vT = position.y;
        vec4 mvPosition = viewMatrix * vec4( P, 1.0 );
        gl_Position = projectionMatrix * mvPosition;`,
      );
      sh.fragmentShader = `uniform vec3 wShaft;\nuniform vec3 wTip;\nvarying float vT;\n${sh.fragmentShader}`.replace(
        '#include <color_fragment>',
        '#include <color_fragment>\ndiffuseColor.rgb = vT > 0.9 ? wTip : wShaft;',
      );
    },
  });
  const spears = new THREE.InstancedMesh(lineGeometry(), spearMat, spearMen.length);
  spears.name = 'hordeSpears';
  for (let k = 0; k < spearMen.length; k++) {
    const man = men[spearMen[k]];
    const len = 2.2 + R.next() * 0.6;
    e.set((R.next() - 0.5) * 0.12, 0, (R.next() - 0.5) * 0.12);
    q.setFromEuler(e);
    m.compose(p.set(man.x + 0.26, man.y + 0.25, man.z + 0.05), q, s.set(1, len, 1));
    spears.setMatrixAt(k, m);
  }
  spears.instanceMatrix.needsUpdate = true;
  spears.computeBoundingSphere();
  // Spear k belongs to soldier spearMen[k]; count of spears visible for a given soldier count.
  const spearCountFor = (soldiers) => {
    let lo = 0;
    let hi = spearMen.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (spearMen[mid] < soldiers) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  };

  // --- Standards ---
  const nStd = o.quality === 'low' ? 14 : 26;
  const stdMat = new THREE.MeshStandardMaterial({ roughness: 0.85, side: THREE.DoubleSide });
  stdMat.name = 'hordeStandards';
  o.patch(stdMat, {
    key: 'hordeStandards',
    fog: {},
    extra: (sh) => {
      sh.uniforms.wTime = o.time;
      sh.vertexShader = `attribute float part;\nattribute float flag;\nuniform float wTime;\n${sh.vertexShader}`
        .replace(
          '#include <color_vertex>',
          `#include <color_vertex>
          #ifdef USE_INSTANCING_COLOR
            vColor.rgb = part < 0.5 ? vec3( 0.12, 0.08, 0.05 ) : instanceColor.rgb;
          #endif`,
        )
        .replace(
          '#include <begin_vertex>',
          `#include <begin_vertex>
          {
            float ph = instanceMatrix[ 3 ].x * 0.9 + instanceMatrix[ 3 ].z * 0.4;
            transformed.z += sin( wTime * 2.6 + flag * 4.0 + ph ) * 0.22 * flag;
            transformed.y += sin( wTime * 1.7 + flag * 3.0 + ph ) * 0.05 * flag;
          }`,
        );
    },
  });
  const standards = new THREE.InstancedMesh(standardGeometry(), stdMat, nStd);
  standards.name = 'hordeStandards';
  const stdCols = ['#7a2a22', '#4e2a48', '#9a7a3a', '#6a2018', '#a89a80', '#34385a'];
  for (let i = 0; i < nStd; i++) {
    const man = men[Math.floor(R.next() * n)];
    m.compose(p.set(man.x + 0.4, man.y, man.z), q.identity(), s.set(1, 0.9 + R.next() * 0.3, 1));
    standards.setMatrixAt(i, m);
    c.set(stdCols[i % stdCols.length]);
    standards.setColorAt(i, c);
  }
  standards.instanceMatrix.needsUpdate = true;
  standards.instanceColor.needsUpdate = true;
  standards.computeBoundingSphere();

  // --- Dust raised by the army ---
  const nDust = o.quality === 'low' ? 18 : 34;
  const dustMat = new THREE.MeshBasicMaterial({ map: o.dustMap, transparent: true, depthWrite: false, opacity: 1 });
  dustMat.name = 'hordeDust';
  o.patch(dustMat, {
    key: 'hordeDust',
    fog: {},
    extra: (sh) => {
      sh.uniforms.wTime = o.time;
      sh.uniforms.wDust = lit.dust;
      sh.uniforms.wDustAlpha = lit.dustAlpha;
      sh.vertexShader = `uniform float wTime;\n${sh.vertexShader}`.replace(
        '#include <project_vertex>',
        `vec4 cW = modelMatrix * instanceMatrix * vec4( 0.0, 0.0, 0.0, 1.0 );
        float sz = length( instanceMatrix[ 0 ].xyz );
        float ph = cW.x * 0.13 + cW.z * 0.07;
        cW.x += sin( wTime * 0.05 + ph ) * 6.0;
        cW.y += sin( wTime * 0.08 + ph * 2.0 ) * 1.5;
        vec4 mvPosition = viewMatrix * cW;
        float rot = ph * 3.0 + wTime * 0.01;
        vec2 cr = position.xy * sz * vec2( 1.6, 1.0 );
        mvPosition.xy += mat2( cos( rot ), sin( rot ), -sin( rot ), cos( rot ) ) * cr * 0.2 + cr * 0.8;
        gl_Position = projectionMatrix * mvPosition;`,
      );
      sh.fragmentShader = `uniform vec3 wDust;\nuniform float wDustAlpha;\n${sh.fragmentShader}`.replace(
        '#include <color_fragment>',
        '#include <color_fragment>\ndiffuseColor.rgb = wDust;\ndiffuseColor.a *= wDustAlpha;',
      );
    },
  });
  const dustGeo = new THREE.PlaneGeometry(1, 1);
  const dust = new THREE.InstancedMesh(dustGeo, dustMat, nDust);
  dust.name = 'hordeDust';
  for (let i = 0; i < nDust; i++) {
    const zz = -95 - Math.pow(R.next(), 0.8) * 340;
    const xx = lerp(layout.cliffX(zz) + 4, layout.shoreX(zz) - 3, R.next());
    const size = 16 + R.next() * 26 + (-zz - 95) * 0.05;
    m.compose(p.set(xx, layout.terrainHeight(xx, zz) + size * 0.22, zz), q.identity(), s.set(size, size, size));
    dust.setMatrixAt(i, m);
  }
  dust.instanceMatrix.needsUpdate = true;
  dust.computeBoundingSphere();
  dust.renderOrder = 2;

  const group = new THREE.Group();
  group.name = 'horde';
  for (const mesh of [bodies, spears, standards, dust]) {
    mesh.matrixAutoUpdate = false;
    mesh.castShadow = false;
    mesh.receiveShadow = false;
    group.add(mesh);
  }
  group.matrixAutoUpdate = false;

  let density = 1;
  function setDensity(d) {
    density = clamp(Number.isFinite(d) ? d : 1, 0, 1);
    const k = Math.round(n * density);
    bodies.count = k;
    spears.count = spearCountFor(k);
    standards.count = Math.round(nStd * clamp(density * 1.2, 0, 1));
    dust.count = Math.round(nDust * clamp(0.3 + density * 0.7, 0, 1));
    group.visible = k > 0;
  }
  return { group, setDensity, get density() { return density; }, count: n, meshes: [bodies, spears, standards, dust] };
}

