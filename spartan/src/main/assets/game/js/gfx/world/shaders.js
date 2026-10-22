/**
 * onBeforeCompile patches for world materials. Each patch is fixed at creation (nothing here
 * changes program-affecting state at runtime); animated values are plain uniforms.
 *
 * - skyFog: exp² haze whose colour is the actual Preetham sky radiance in the view direction,
 *   tone-mapped like the sky, so distant terrain dissolves into the horizon behind it.
 * - ao: a per-vertex `ao` attribute that darkens indirect light (and a little direct light).
 * - ground: anti-tiling (a rotated, larger-scale second sample of the map modulates brightness)
 *   and splatting of a second texture pair by a per-vertex `splat` weight.
 * - wind: per-vertex `sway` (0 at the root, 1 at the tip) bends foliage.
 * - foliage: double-sided cards keep their authored normals on both faces (three flips them
 *   on back faces, which turns every card seen from behind black).
 */
import * as THREE from 'three';
import { SKY_GLSL } from './sky.js';

const LUMA = 'vec3( 0.2126, 0.7152, 0.0722 )';

/**
 * @param {THREE.Material} mat
 * @param {{ key: string, fog?: { uniforms: object, scale?: number, perFragment?: boolean },
 *   ao?: boolean, aoDirect?: number, ground?: { mean: number, splatMap?: THREE.Texture, splatNormal?: THREE.Texture, splatScale?: number, antiTile?: number },
 *   wind?: { time: { value: number }, amp?: number }, foliage?: boolean, extra?: (shader: object) => void }} opts
 */
export function patchMaterial(mat, opts) {
  const uniforms = {};
  if (opts.fog) {
    Object.assign(uniforms, opts.fog.uniforms);
    uniforms.wFogScale = { value: opts.fog.scale ?? 1 };
  }
  if (opts.ground) {
    uniforms.wMeanLum = { value: opts.ground.mean };
    uniforms.wAntiTile = { value: opts.ground.antiTile ?? 0.6 };
    if (opts.ground.splatMap) {
      uniforms.wSplatMap = { value: opts.ground.splatMap };
      uniforms.wSplatNormal = { value: opts.ground.splatNormal };
      uniforms.wSplatScale = { value: opts.ground.splatScale ?? 1 };
    }
  }
  if (opts.wind) {
    uniforms.wTime = opts.wind.time;
    uniforms.wWindAmp = { value: opts.wind.amp ?? 1 };
  }
  mat.userData.worldUniforms = uniforms;
  mat.customProgramCacheKey = () => `world:${opts.key}`;
  mat.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    let vs = shader.vertexShader;
    let fs = shader.fragmentShader;
    let vDecl = '';
    let fDecl = '';
    if (opts.fog) {
      const perFrag = !!opts.fog.perFragment;
      vDecl += `uniform float wFogScale;\nvarying float vFogDist;\n`;
      fDecl += `varying float vFogDist;\n`;
      if (perFrag) {
        vDecl += `varying vec3 vFogDirW;\n`;
        fDecl += `varying vec3 vFogDirW;\n${SKY_GLSL}`;
      } else {
        vDecl += `varying vec3 vSkyFog;\n${SKY_GLSL}`;
        fDecl += `varying vec3 vSkyFog;\n`;
      }
      vs = vs.replace(
        '#include <fog_vertex>',
        `#include <fog_vertex>
        {
          vec3 fogDirW = transpose( mat3( viewMatrix ) ) * mvPosition.xyz;
          vFogDist = length( mvPosition.xyz ) * wFogScale;
          ${perFrag ? 'vFogDirW = fogDirW;' : 'vSkyFog = wSkyRadiance( normalize( fogDirW ) );'}
        }`,
      );
      fs = fs.replace(
        '#include <fog_fragment>',
        `#ifdef USE_FOG
          ${perFrag ? 'vec3 fogSky = wSkyRadiance( normalize( vFogDirW ) );' : 'vec3 fogSky = vSkyFog;'}
          #if defined( TONE_MAPPING )
            fogSky = toneMapping( fogSky );
          #endif
          fogSky = linearToOutputTexel( vec4( fogSky, 1.0 ) ).rgb;
          float fogD = fogDensity * vFogDist;
          float fogFactor = 1.0 - exp( - fogD * fogD );
          gl_FragColor.rgb = mix( gl_FragColor.rgb, fogSky, fogFactor );
        #endif`,
      );
    }
    if (opts.ao) {
      vDecl += 'attribute float ao;\nvarying float vAO;\n';
      fDecl += 'varying float vAO;\n';
      vs = vs.replace('#include <begin_vertex>', '#include <begin_vertex>\nvAO = ao;');
      fs = fs.replace(
        '#include <aomap_fragment>',
        `#include <aomap_fragment>
        reflectedLight.indirectDiffuse *= vAO;
        reflectedLight.indirectSpecular *= vAO;
        reflectedLight.directDiffuse *= mix( 1.0, vAO, ${(opts.aoDirect ?? 0.3).toFixed(3)} );`,
      );
    }
    if (opts.ground) {
      const splat = !!opts.ground.splatMap;
      fDecl += 'uniform float wMeanLum;\nuniform float wAntiTile;\n';
      if (splat) {
        vDecl += 'attribute float splat;\nvarying float vSplat;\n';
        fDecl += 'varying float vSplat;\nuniform sampler2D wSplatMap;\nuniform sampler2D wSplatNormal;\nuniform float wSplatScale;\n';
        vs = vs.replace('#include <begin_vertex>', '#include <begin_vertex>\nvSplat = splat;');
      }
      fs = fs.replace(
        '#include <map_fragment>',
        `#ifdef USE_MAP
          vec4 wTexA = texture2D( map, vMapUv );
          vec4 wTexB = texture2D( map, mat2( 0.8, -0.6, 0.6, 0.8 ) * vMapUv * 0.21 + vec2( 0.31, 0.67 ) );
          float wMacro = dot( wTexB.rgb, ${LUMA} ) / wMeanLum;
          vec3 wCol = wTexA.rgb * clamp( mix( 1.0, wMacro, wAntiTile ), 0.6, 1.5 );
          ${splat ? `vec4 wTexS = texture2D( wSplatMap, vMapUv * wSplatScale );
          wCol = mix( wCol, wTexS.rgb * clamp( mix( 1.0, wMacro, wAntiTile * 0.5 ), 0.7, 1.3 ), vSplat );` : ''}
          diffuseColor.rgb *= wCol;
        #endif`,
      );
      if (splat) {
        fs = fs.replace(
          '#include <normal_fragment_maps>',
          `#ifdef USE_NORMALMAP_TANGENTSPACE
            vec3 mapN = texture2D( normalMap, vNormalMapUv ).xyz * 2.0 - 1.0;
            vec3 mapS = texture2D( wSplatNormal, vNormalMapUv * wSplatScale ).xyz * 2.0 - 1.0;
            mapN = normalize( mix( mapN, mapS, vSplat ) );
            mapN.xy *= normalScale;
            normal = normalize( tbn * mapN );
          #else
            #include <normal_fragment_maps>
          #endif`,
        );
      }
    }
    if (opts.foliage) {
      const chunk = THREE.ShaderChunk.normal_fragment_begin;
      const kept = chunk.replace('normal *= faceDirection;', '');
      if (kept === chunk) console.warn('world: foliage normal patch did not apply');
      fs = fs.replace('#include <normal_fragment_begin>', kept);
    }
    if (opts.wind) {
      vDecl += 'attribute float sway;\nuniform float wTime;\nuniform float wWindAmp;\n';
      vs = vs.replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
        {
          float ph = transformed.x * 0.37 + transformed.z * 0.23;
          float s = sway * sway * wWindAmp;
          float gust = 0.6 + 0.4 * sin( wTime * 0.31 + transformed.x * 0.05 );
          transformed.x += ( sin( wTime * 1.9 + ph ) * 0.05 + sin( wTime * 4.3 + ph * 2.7 ) * 0.015 ) * s * gust;
          transformed.z += ( cos( wTime * 1.4 + ph * 1.3 ) * 0.035 ) * s * gust;
        }`,
      );
    }
    if (opts.extra) {
      const sh = { vertexShader: vs, fragmentShader: fs, uniforms: shader.uniforms };
      opts.extra(sh);
      vs = sh.vertexShader;
      fs = sh.fragmentShader;
    }
    shader.vertexShader = vDecl + vs;
    shader.fragmentShader = fDecl + fs;
  };
  return mat;
}
