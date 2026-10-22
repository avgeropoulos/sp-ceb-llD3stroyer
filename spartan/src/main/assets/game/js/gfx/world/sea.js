/**
 * The Malian Gulf: an animated water surface whose shallows show the seabed and whose far
 * reaches fade into the sky, a lapping foam line, and the ring of distant mountains and shores.
 */
import * as THREE from 'three';
import { clamp, lerp, smoothstep } from '../../core/math.js';
import { SEA_LEVEL } from './layout.js';

const SEA_RADIUS = 1880;

/** Water surface geometry: columns anchored to the shore lip, rows dense near the line. */
export function buildSeaGeometry(layout) {
  const offs = [-2.2, -0.9, 0.2, 1.0, 1.8, 2.6, 3.5, 4.6, 6, 8, 11, 15, 21, 30, 44, 65, 95, 140, 210, 320, 480, 720, 1050, 1500, 2000];
  const rows = [];
  for (let z = SEA_RADIUS; z > 90; z -= Math.max(2, (z - 80) * 0.14)) rows.push(z);
  for (let z = 90; z > -90; z -= 1.5) rows.push(z);
  for (let z = -90, dz = 1.5; z > -SEA_RADIUS; dz = Math.min(120, dz * 1.12), z -= dz) rows.push(z);
  rows.push(-SEA_RADIUS);
  const nc = offs.length;
  const nr = rows.length;
  const pos = new Float32Array(nc * nr * 3);
  const uv = new Float32Array(nc * nr * 2);
  const depth = new Float32Array(nc * nr);
  for (let j = 0; j < nr; j++) {
    const z = rows[j];
    const sx = layout.shoreX(clamp(z, -1600, 360));
    for (let i = 0; i < nc; i++) {
      let x = sx + offs[i];
      let zz = z;
      const r = Math.hypot(x, zz);
      if (r > SEA_RADIUS) {
        x *= SEA_RADIUS / r;
        zz *= SEA_RADIUS / r;
      }
      const k = j * nc + i;
      pos[k * 3] = x;
      pos[k * 3 + 1] = SEA_LEVEL;
      pos[k * 3 + 2] = zz;
      uv[k * 2] = x / 13;
      uv[k * 2 + 1] = -zz / 13;
      depth[k] = offs[i] < 30 ? clamp(SEA_LEVEL - layout.terrainHeight(x, zz), 0, 40) : 40;
    }
  }
  const index = [];
  for (let j = 0; j < nr - 1; j++) {
    for (let i = 0; i < nc - 1; i++) {
      const a = j * nc + i;
      const b = a + 1;
      const d = a + nc;
      const e = d + 1;
      index.push(a, b, d, b, e, d);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
  g.setAttribute('depth', new THREE.BufferAttribute(depth, 1));
  const nrm = new Float32Array(nc * nr * 3);
  for (let k = 0; k < nc * nr; k++) nrm[k * 3 + 1] = 1;
  g.setAttribute('normal', new THREE.BufferAttribute(nrm, 3));
  g.setIndex(index);
  g.computeBoundingSphere();
  return g;
}

// Wave normals can tilt a reflection below the horizon, where the environment holds the land
// proxies: water never mirrors what is beneath it, so fold such rays back up over the horizon.
const ENV_REFLECT = 'reflectVec = transformDirectionByInverseViewMatrix( reflectVec, viewMatrix );';
function seaEnvPars() {
  const src = THREE.ShaderChunk.envmap_physical_pars_fragment;
  const out = src.replace(
    ENV_REFLECT,
    `${ENV_REFLECT}
      reflectVec.y = abs( reflectVec.y ) * 0.6 + 0.012;
      reflectVec = normalize( reflectVec );`,
  );
  if (out === src) console.warn('world: sea reflection patch did not apply');
  return out;
}

/**
 * Water material: three scrolling layers of the wave normal map, colour by depth, alpha from
 * depth and Fresnel (shallows show the seabed, grazing views mirror the sky).
 */
export function makeWaterMaterial(normalTex, time, patch) {
  const mat = new THREE.MeshStandardMaterial({
    color: 0x0b2a33,
    roughness: 0.06,
    metalness: 0,
    normalMap: normalTex,
    normalScale: new THREE.Vector2(0.3, 0.3),
    transparent: true,
    depthWrite: true,
  });
  mat.name = 'sea';
  const uniforms = {
    wTime: time,
    wShallow: { value: new THREE.Color(0.05, 0.2, 0.19) },
    wDeep: { value: new THREE.Color(0.006, 0.03, 0.045) },
  };
  patch(mat, {
    key: 'sea',
    fog: { perFragment: true },
    extra: (sh) => {
      Object.assign(sh.uniforms, uniforms);
      sh.vertexShader = `attribute float depth;\nvarying float vDepth;\n${sh.vertexShader}`.replace(
        '#include <begin_vertex>',
        '#include <begin_vertex>\nvDepth = depth;',
      );
      sh.fragmentShader = `varying float vDepth;\nuniform float wTime;\nuniform vec3 wShallow;\nuniform vec3 wDeep;\n${sh.fragmentShader}`
        .replace('#include <envmap_physical_pars_fragment>', seaEnvPars())
        .replace(
          '#include <color_fragment>',
          `#include <color_fragment>
          diffuseColor.rgb = mix( wShallow, wDeep, 1.0 - exp( - vDepth * 0.45 ) );`,
        )
        .replace(
          '#include <normal_fragment_maps>',
          `{
            vec2 wuv = vNormalMapUv;
            // Three layers at unrelated scales and angles so no tiling or wave direction lines up.
            vec3 n1 = texture2D( normalMap, mat2( 0.87, -0.5, 0.5, 0.87 ) * wuv + wTime * vec2( 0.011, 0.017 ) ).xyz * 2.0 - 1.0;
            vec3 n2 = texture2D( normalMap, mat2( 0.34, 0.94, -0.94, 0.34 ) * wuv * 0.31 + vec2( 0.31, 0.73 ) - wTime * vec2( 0.006, 0.0035 ) ).xyz * 2.0 - 1.0;
            vec3 n3 = texture2D( normalMap, wuv * 2.7 + wTime * vec2( -0.021, 0.013 ) ).xyz * 2.0 - 1.0;
            vec3 mapN = normalize( vec3( n1.xy + n2.xy * 0.6 + n3.xy * 0.5, n1.z * n2.z * n3.z ) );
            float vd = length( vViewPosition );
            mapN.xy *= normalScale * mix( 1.0, 0.25, smoothstep( 15.0, 300.0, vd ) );
            normal = normalize( tbn * mapN );
          }`,
        )
        .replace(
          '#include <opaque_fragment>',
          `{
            float ndv = saturate( dot( normal, normalize( vViewPosition ) ) );
            float fres = 0.02 + 0.98 * pow( 1.0 - ndv, 5.0 );
            float body = 1.0 - exp( - vDepth * 1.6 );
            diffuseColor.a = clamp( max( body, fres ) + 0.08, 0.0, 1.0 );
          }
          #include <opaque_fragment>`,
        );
    },
  });
  mat.userData.seaUniforms = uniforms;
  return mat;
}

/** Foam ribbon along the waterline, from the shore out over the water. */
export function buildFoamGeometry(layout) {
  const pos = [];
  const uv = [];
  const index = [];
  let n = 0;
  for (let z = 260; z > -260; z -= 1.25) {
    const sx = layout.shoreX(z);
    let xw = sx;
    for (let x = sx; x < sx + 8; x += 0.08) {
      if (layout.terrainHeight(x, z) < SEA_LEVEL) {
        xw = x;
        break;
      }
    }
    const w = 1.4 + 0.6 * layout.noise.noise2(z * 0.07, 1.3);
    pos.push(xw - 0.35, SEA_LEVEL + 0.025, z, xw + w, SEA_LEVEL + 0.02, z);
    uv.push(-z / 7, 0, -z / 7, 1);
    if (n > 0) {
      const a = (n - 1) * 2;
      index.push(a, a + 1, a + 2, a + 1, a + 3, a + 2);
    }
    n++;
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  const nrm = new Float32Array(pos.length);
  for (let k = 1; k < nrm.length; k += 3) nrm[k] = 1;
  g.setAttribute('normal', new THREE.BufferAttribute(nrm, 3));
  g.setIndex(index);
  g.computeBoundingSphere();
  return g;
}

/** Direction-dependent ridge height of the distant ring (φ = 0 looks down the pass, +π/2 over the gulf). */
function sectorHeight(phi) {
  const keys = [
    [-Math.PI, 210], [-2.4, 380], [-1.4, 420], [-0.55, 330], [-0.1, 300], [0.3, 230], [0.8, 170],
    [1.3, 120], [1.9, 105], [2.3, 160], [2.8, 190], [Math.PI, 210],
  ];
  for (let i = 1; i < keys.length; i++) {
    if (phi <= keys[i][0]) {
      const t = (phi - keys[i - 1][0]) / (keys[i][0] - keys[i - 1][0]);
      return lerp(keys[i - 1][1], keys[i][1], t * t * (3 - 2 * t));
    }
  }
  return 210;
}

/** Two ranges of mountains and the far shores at 1.4–1.9 km: a nearer, lower range and a higher, hazier one. */
export function buildMountainGeometry(layout) {
  const N = layout.noise;
  const seg = 480;
  const ranges = [
    // [base radius, height scale, profile rows [radial offset, height fraction, rockiness]]
    { R0: 1420, k: 0.55, salt: 3, prof: [[0, -6, 0], [20, 1.5, 0], [60, 0.12, 0.05], [110, 0.45, 0.2], [150, 0.8, 0.45], [175, 1, 0.8], [215, 0.7, 0.6]] },
    { R0: 1640, k: 1.0, salt: 11, prof: [[0, -6, 0], [25, 1.5, 0], [70, 0.1, 0.05], [130, 0.4, 0.25], [190, 0.75, 0.55], [230, 1, 1], [260, 0.8, 0.8]] },
  ];
  const pos = [];
  const col = [];
  const index = [];
  for (const rg of ranges) {
    const nr = rg.prof.length;
    const first = pos.length / 3;
    for (let s = 0; s <= seg; s++) {
      const phi = -Math.PI + (s / seg) * Math.PI * 2;
      const dx = Math.sin(phi);
      const dz = -Math.cos(phi);
      const base = sectorHeight(phi) * rg.k;
      const ca = Math.cos(phi);
      const sa = Math.sin(phi);
      // Fractal skyline: ridged peaks plus finer spurs; sampled on a circle so it closes seamlessly.
      const ridge = 0.5 + 0.5 * N.ridge2(ca * 2.2 + rg.salt, sa * 2.2, 4);
      const broad = 0.5 + 0.5 * N.fbm2(ca * 1.6 - rg.salt, sa * 1.6 + 4, 3);
      const mid = N.fbm2(ca * 7 + rg.salt, sa * 7 - 2, 4);
      const fine = N.fbm2(ca * 23 - rg.salt, sa * 23 + 5, 3);
      const H = Math.max(8, base * (0.3 + 0.45 * broad + 0.35 * ridge) + base * (0.16 * mid + 0.05 * fine));
      const rOff = 70 * N.noise2(ca * 3 + rg.salt, sa * 3);
      for (let r = 0; r < nr; r++) {
        const [dr, hf, rockiness] = rg.prof[r];
        let y;
        if (r === 0) y = hf;
        else if (r === 1) y = hf + 2.5 * N.noise2(ca * 30, sa * 30 + r);
        else y = hf * H * (1 + 0.12 * N.noise2(ca * 40 + r, sa * 40)) + (r > 2 ? fine * 8 * hf : 0);
        const rad = rg.R0 + dr + rOff * Math.min(1, r / 3);
        pos.push(dx * rad, y, dz * rad);
        const rock = clamp(rockiness * (0.5 + 0.7 * ridge), 0, 1);
        const dim = rg.k < 1 ? 0.8 : 1;
        if (r <= 1) col.push(0.42, 0.38, 0.3);
        else col.push(lerp(0.17, 0.34, rock) * dim, lerp(0.19, 0.33, rock) * dim, lerp(0.13, 0.3, rock) * dim);
      }
    }
    for (let s = 0; s < seg; s++) {
      for (let r = 0; r < nr - 1; r++) {
        const a = first + s * nr + r;
        const b = a + nr;
        // Faces point inward (toward the origin).
        index.push(a, b, a + 1, b, b + 1, a + 1);
      }
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  g.setIndex(index);
  g.computeVertexNormals();
  g.computeBoundingSphere();
  return g;
}
