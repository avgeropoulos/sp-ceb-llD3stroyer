// ui/menus.js: title, settings, pause, game over, how to play, motion safety notice (#menus, z 30).
// DESIGN.md §9, §10 (pause/back), §11 ui/*. Settings apply live and are saved with ctx.saveSettings().
// The grips (docs-hilt-grip.md §A.5, §A.6, §E.5): the grip chooser, the per-grip safety notice, the
// first hilt Fight notice and the rotate prompt. Every change of control or grip calls
// ctx.applyControls(), so the app rotates at once and the CSS media queries re-lay out the menus.
import { clamp } from '../core/math.js';
import { HILT_RELEASED, effectiveGrip, appOrientation, sensitivityKey, flipKeys } from '../core/grip.js';
import { SENSITIVITY_MIN, SENSITIVITY_MAX } from '../motion/gestures.js';

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V'];
const ORDINAL = ['', 'first', 'second', 'third', 'fourth', 'fifth'];
const roman = (n) => ROMAN[n] || String(n);
const groupDigits = (n) => String(Math.round(n || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

export const TEXT = Object.freeze({
  epigraph: 'With it, or on it.',
  epigraphCite: 'A Spartan mother, handing her son his shield · Plutarch, Moralia 241F',
  epitaph1: 'Go tell the Spartans, stranger passing by,',
  epitaph2: 'that here, obedient to their laws, we lie.',
  epitaphCite: 'Simonides (Herodotus 7.228)',
  noMotion: 'This device did not report a gyroscope, or motion access was refused. You will fight with touch controls.',
  motionOn: 'Motion controls are on. Visit the Agoge to calibrate your swing.',
  hiltOn: 'Hilt grip on. The Agoge teaches it in a few minutes.',
  rotate: 'Hold the phone upright for the hilt grip',
  hiltLead: 'Hold the phone upright in your fist like a sword’s hilt, screen toward you. The blade comes out of the top. Rest your thumb on the edge; press the glass to block. A firm flick is enough, never throw hard. Earbuds help: your fist covers the speaker.',
});

/**
 * The pause screen's kicker for the campaign state `w` (ctx.waves): the day, the part of it
 * and the wave or surge, e.g. 'Day I · Dusk · Wave II', 'Day III · Surge II of IV', 'Day III · The Kolonos'.
 */
export function pauseKicker(w) {
  if (!w || !w.day) return '';
  let k = `Day ${roman(w.day)}`;
  if (w.timeOfDay === 'dusk') k += ' · Dusk';
  if (w.phase === 'C') k += ' · The Kolonos';
  else if (w.phase === 'B' && w.wave) k += w.waveCount > 0 ? ` · Surge ${roman(w.wave)} of ${roman(w.waveCount)}` : ` · Surge ${roman(w.wave)}`;
  else if (w.wave) k += ` · Wave ${roman(w.wave)}`;
  return k;
}

/**
 * Settings fields (§9). `tab` groups them; every field is live-applied. `motionOnly` rows grey out
 * outside motion mode, `hiltOnly` rows unless the effective grip is the hilt. The sensitivity range
 * is the recognizer's (and the Agoge calibration's), and the two swap toggles fix a mirrored sensor
 * setup (the Agoge also sets them when it sees mirrored swings). Each grip keeps its own sensitivity
 * and swaps: `keyOf(settings)` names the settings key a row edits (core/grip.js), `key` names the row.
 * Gyro look is greyed in the hilt grip, where the view locks onto the foe (`hintHilt`).
 */
export const FIELDS = [
  { tab: 'controls', key: 'control', type: 'seg', label: 'Controls', options: [['motion', 'Motion'], ['touch', 'Touch']] },
  { tab: 'controls', key: 'grip', type: 'seg', label: 'Grip', hint: 'Motion: how you hold the phone', options: [['hilt', 'Hilt (upright)'], ['window', 'Window (sideways)']], motionOnly: true },
  { tab: 'controls', key: 'sensitivity', type: 'range', label: 'Sensitivity', hint: 'Motion: higher lets gentler swings count', min: SENSITIVITY_MIN, max: SENSITIVITY_MAX, step: 0.05, fmt: (v) => `${v.toFixed(2)}×`, motionOnly: true, keyOf: sensitivityKey },
  { tab: 'controls', key: 'gyroLook', type: 'toggle', label: 'Gyro look', hint: 'Motion: turn your body to turn the view', hintHilt: 'Hilt grip: the view locks onto your foe', motionOnly: true },
  { tab: 'controls', key: 'lockAuto', type: 'toggle', label: 'Auto-turn', hint: 'Hilt grip: turn to a foe attacking from behind', hiltOnly: true },
  { tab: 'controls', key: 'motionFlipX', type: 'toggle', label: 'Swap swing left/right', hint: 'Motion: if your slashes come out mirrored', motionOnly: true, keyOf: (s) => flipKeys(s).x },
  { tab: 'controls', key: 'motionFlipY', type: 'toggle', label: 'Swap swing up/down', hint: 'Motion: if chops come out as uppercuts', motionOnly: true, keyOf: (s) => flipKeys(s).y },
  { tab: 'controls', key: 'aimAssist', type: 'toggle', label: 'Aim assist', hint: 'Touch and keyboard: turn toward your foe' },
  { tab: 'controls', key: 'leftHanded', type: 'toggle', label: 'Left-handed', hint: 'Mirror the touch layout and demos' },
  { tab: 'controls', key: 'haptics', type: 'toggle', label: 'Haptics', hint: 'Feel every blow' },
  { tab: 'sound', key: 'master', type: 'range', label: 'Master volume', min: 0, max: 1, step: 0.05, fmt: (v) => `${Math.round(v * 100)}%` },
  { tab: 'sound', key: 'music', type: 'range', label: 'Music', min: 0, max: 1, step: 0.05, fmt: (v) => `${Math.round(v * 100)}%` },
  { tab: 'sound', key: 'sfx', type: 'range', label: 'Effects', min: 0, max: 1, step: 0.05, fmt: (v) => `${Math.round(v * 100)}%` },
  { tab: 'sound', key: 'voice', type: 'toggle', label: 'Voices', hint: 'Spoken lines (subtitles always show)' },
  { tab: 'display', key: 'quality', type: 'seg', label: 'Quality', hint: 'Applies after restart', options: [['auto', 'Auto'], ['low', 'Low'], ['medium', 'Med'], ['high', 'High']] },
  { tab: 'display', key: 'blood', type: 'toggle', label: 'Blood', hint: 'Blood and wounds' },
  { tab: 'display', key: 'helmetView', type: 'toggle', label: 'Helmet view', hint: 'See through the Corinthian eye-slits' },
  { tab: 'display', key: 'sensorDebug', type: 'toggle', label: 'Sensor meter', hint: 'Show live motion readings' },
];

const TABS = [['controls', 'Controls'], ['sound', 'Sound'], ['display', 'Display']];

// ---- inline SVG diagrams (stroke = currentColor, accents via classes) ----
const PHONE = (x, y, w = 64, h = 36) => `<rect x="${x - w / 2}" y="${y - h / 2}" width="${w}" height="${h}" rx="6" class="d-phone"/><rect x="${x - w / 2 + 6}" y="${y - h / 2 + 4}" width="${w - 12}" height="${h - 8}" rx="2" class="d-glass"/>`;
const ARROW = (x1, y1, x2, y2, cls = 'd-arrow') => {
  const a = Math.atan2(y2 - y1, x2 - x1);
  const h = 7;
  const p1 = `${(x2 - h * Math.cos(a - 0.5)).toFixed(1)},${(y2 - h * Math.sin(a - 0.5)).toFixed(1)}`;
  const p2 = `${(x2 - h * Math.cos(a + 0.5)).toFixed(1)},${(y2 - h * Math.sin(a + 0.5)).toFixed(1)}`;
  return `<path class="${cls}" d="M${x1.toFixed(1)} ${y1.toFixed(1)}L${x2.toFixed(1)} ${y2.toFixed(1)}"/><path class="${cls}-head" d="M${x2.toFixed(1)} ${y2.toFixed(1)}L${p1}L${p2}Z"/>`;
};
const THUMB = (x, y) => `<path class="d-thumb" d="M${x - 9} ${y + 34}L${x - 8} ${y + 3}Q${x} ${y - 9} ${x + 8} ${y + 3}L${x + 9} ${y + 34}Z"/><ellipse class="d-nail" cx="${x}" cy="${y + 1}" rx="4" ry="5"/><circle class="d-press" cx="${x}" cy="${y - 1}" r="12"/>`;
// The hilt grip: an upright phone in a fist, the blade (dashed) out of its top edge.
const PHONE_V = (x, y, w, h) => `<rect x="${x - w / 2}" y="${y - h / 2}" width="${w}" height="${h}" rx="5" class="d-phone"/><rect x="${x - w / 2 + 4}" y="${y - h / 2 + 6}" width="${w - 8}" height="${h - 12}" rx="2" class="d-glass"/>`;
const FIST = (x, y, w = 30) => `<rect x="${x - w / 2}" y="${y}" width="${w}" height="22" rx="8" class="d-phone"/><path class="d-cut" d="M${x - w / 2 + 3} ${y + 7}h7M${x - w / 2 + 3} ${y + 13}h7"/><rect x="${x - 8}" y="${y + 20}" width="16" height="24" class="d-phone"/>`;
const HILT = (x, y, blade = 22) => `<path class="d-blade" d="M${x} ${y - 22 - blade}V${y - 24}"/>${PHONE_V(x, y, 22, 46)}${FIST(x, y + 10)}`;

function swingArrows() {
  let s = '';
  for (let i = 0; i < 8; i++) {
    const a = (i * Math.PI) / 4;
    const c = Math.cos(a), d = -Math.sin(a);
    s += ARROW(80 + c * 26, 52 + d * 22, 80 + c * 46, 52 + d * 40, i === 0 || i === 4 ? 'd-arrow d-hot' : 'd-arrow');
  }
  return s;
}

export const DIAGRAMS = {
  swing: `<svg viewBox="0 0 160 104">${PHONE(80, 52, 40, 24)}${swingArrows()}</svg>`,
  stab: `<svg viewBox="0 0 160 104"><circle class="d-head" cx="24" cy="44" r="13"/><path class="d-body" d="M24 57v40"/><path class="d-arm" d="M24 66 52 62"/><rect x="52" y="44" width="6" height="34" rx="2" class="d-phone"/>${ARROW(66, 61, 146, 61, 'd-arrow d-hot')}<text x="104" y="50" class="d-label">push</text></svg>`,
  shield: `<svg viewBox="0 0 160 104">${PHONE(80, 46, 92, 52)}${THUMB(46, 52)}<g transform="translate(108 46)"><circle r="15" class="d-aspis"/><circle r="10" class="d-aspis-in"/><circle r="2.5" class="d-boss"/></g><text x="80" y="96" class="d-label">thumb on the glass</text></svg>`,
  bash: `<svg viewBox="0 0 160 104">${PHONE(62, 46, 76, 44)}${THUMB(38, 50)}<g transform="translate(76 46)"><circle r="12" class="d-aspis"/><circle r="2.5" class="d-boss"/></g>${ARROW(104, 46, 150, 46, 'd-arrow d-hot')}<text x="80" y="96" class="d-label">shield + push</text></svg>`,
  throw: `<svg viewBox="0 0 160 104"><circle class="d-head" cx="50" cy="56" r="12"/><path class="d-body" d="M50 68v34"/><path class="d-arm" d="M44 66 34 46 38 30"/><rect x="33" y="14" width="10" height="17" rx="2" class="d-phone" transform="rotate(-18 38 22)"/><path class="d-arc" d="M44 16C66-2 108 4 124 40"/>${ARROW(118, 30, 128, 50, 'd-arrow d-hot')}<text x="112" y="94" class="d-label">overhand</text></svg>`,
  layout: `<svg viewBox="0 0 320 150" class="d-layout"><rect x="3" y="3" width="314" height="144" rx="18" class="d-frame"/><rect x="16" y="9" width="288" height="132" rx="5" class="d-screen"/>
    <circle cx="32" cy="24" r="8" class="d-btn"/><text x="45" y="27" class="d-small">pause</text>
    <circle cx="54" cy="112" r="21" class="d-ring"/><circle cx="58" cy="108" r="9" class="d-btn"/><text x="80" y="134" class="d-small">move</text>
    <circle cx="50" cy="64" r="16" class="d-btn"/><text x="50" y="66.5" class="d-tiny" text-anchor="middle">SHIELD</text>
    <circle cx="90" cy="72" r="11" class="d-btn"/><text x="90" y="74.5" class="d-tiny" text-anchor="middle">BASH</text>
    ${ARROW(50, 46, 50, 32, 'd-arrow')}<text x="58" y="40" class="d-small">swipe up: bash</text>
    <path d="M160 16v118" class="d-divider"/>
    <path d="M176 44q22-12 44 0" class="d-arc"/><text x="174" y="29" class="d-small">drag slowly: look</text>
    ${ARROW(180, 104, 232, 80, 'd-arrow d-hot')}<text x="176" y="70" class="d-small">swipe fast: slash</text>
    <circle cx="178" cy="126" r="4.5" class="d-hotfill"/><text x="187" y="129.5" class="d-small">tap: stab</text>
    <text x="300" y="56" class="d-small" text-anchor="end">throw: hold, release</text><circle cx="284" cy="78" r="13" class="d-btn d-throwbtn"/>
    <text x="300" y="108" class="d-small" text-anchor="end">swap weapon</text><circle cx="287" cy="124" r="9" class="d-btn"/></svg>`,
  grip: '<svg viewBox="0 0 64 64"><rect x="25" y="6" width="15" height="30" rx="3" class="d-phone"/><rect x="28" y="9" width="9" height="20" rx="1" class="d-glass"/><rect x="19" y="27" width="28" height="21" rx="8" class="d-phone"/><path class="d-cut" d="M23 33h20M23 39h20M24 45h18"/><path class="d-body" d="M19 33q-3-9 6-12"/><rect x="24" y="47" width="18" height="13" class="d-phone"/></svg>',
  strap: '<svg viewBox="0 0 64 64"><rect x="10" y="8" width="16" height="28" rx="3" class="d-phone"/><rect x="13" y="11" width="10" height="18" rx="1" class="d-glass"/><path class="d-cord" d="M18 36c0 12 10 17 20 13"/><path class="d-body" d="M44 46 58 60"/><ellipse cx="42" cy="46" rx="6" ry="8" transform="rotate(-45 42 46)" class="d-band"/></svg>',
  space: '<svg viewBox="0 0 64 64"><circle cx="32" cy="32" r="25" class="d-ring d-dash"/><circle cx="32" cy="21" r="6" class="d-head"/><path d="M23 46l4-17h10l4 17z" class="d-phone"/><path class="d-arrow" d="M32 4v-1M60 32h1M4 32H3"/></svg>',
  hold: '<svg viewBox="0 0 64 64"><path class="d-motion" d="M8 44q4-22 22-30M14 52q6-18 18-24"/><g transform="rotate(25 40 34)"><rect x="33" y="8" width="14" height="26" rx="3" class="d-phone"/><rect x="28" y="26" width="24" height="18" rx="7" class="d-phone"/><path class="d-cut" d="M31 31h18M31 37h18"/><rect x="32" y="43" width="16" height="14" class="d-phone"/></g></svg>',
  // The hilt grip (docs-hilt-grip.md §E.5): the thumb rests on the edge, the fingertips stay off the glass.
  hiltGrip: `<svg viewBox="0 0 160 104">${HILT(70, 46)}<path class="d-thumb" d="M78 80L79 48Q83 40 87 48L88 80Z"/><ellipse class="d-nail" cx="83" cy="47" rx="2.6" ry="3.4"/>
    <text x="94" y="45" class="d-note">thumb rests</text><text x="94" y="58" class="d-note">on the edge</text><text x="51" y="70" class="d-note" text-anchor="end">fingers</text><text x="51" y="83" class="d-note" text-anchor="end">behind</text><text x="78" y="13" class="d-note">blade</text></svg>`,
  hiltCut: `<svg viewBox="0 0 160 104"><path class="d-arc" d="M30 34Q80 2 130 34"/>${ARROW(118, 25, 131, 35, 'd-arrow d-hot')}${ARROW(42, 25, 29, 35, 'd-arrow d-hot')}<g transform="rotate(-16 80 86)">${HILT(76, 60, 10)}</g><text x="128" y="74" class="d-label">sweep</text><text x="128" y="88" class="d-label">the top</text></svg>`,
  hiltBlock: `<svg viewBox="0 0 160 104">${PHONE_V(68, 46, 42, 74)}${THUMB(76, 48)}<g transform="translate(124 44)"><circle r="16" class="d-aspis"/><circle r="10.5" class="d-aspis-in"/><circle r="2.5" class="d-boss"/></g><text x="80" y="99" class="d-label">thumb on the glass</text></svg>`,
  hiltSwipe: `<svg viewBox="0 0 160 104">${PHONE_V(80, 48, 42, 74)}${THUMB(86, 52)}${ARROW(88, 24, 128, 24, 'd-arrow d-hot')}${ARROW(84, 24, 44, 24, 'd-arrow d-hot')}<text x="80" y="100" class="d-label">swipe: next foe</text></svg>`,
  hiltThrust: `<svg viewBox="0 0 160 104"><circle class="d-head" cx="24" cy="40" r="13"/><path class="d-body" d="M24 53v44"/><path class="d-arm" d="M24 64 50 60"/><path class="d-blade" d="M55 22V8"/><rect x="51" y="24" width="8" height="28" rx="2" class="d-phone"/><rect x="48" y="50" width="14" height="13" rx="5" class="d-phone"/>${ARROW(72, 56, 146, 56, 'd-arrow d-hot')}<text x="108" y="46" class="d-label">punch</text></svg>`,
  hiltThrow: `<svg viewBox="0 0 160 104"><circle class="d-head" cx="62" cy="42" r="12"/><path class="d-body" d="M62 54v46"/><path class="d-arm" d="M62 62 46 52 36 36"/><g transform="rotate(-62 36 36)"><rect x="32" y="12" width="8" height="24" rx="2" class="d-phone"/></g><rect x="31" y="31" width="11" height="11" rx="4" class="d-phone"/><path class="d-arc" d="M30 22C62-2 112 6 130 42"/>${ARROW(124, 32, 132, 50, 'd-arrow d-hot')}<text x="118" y="92" class="d-label">cock back, snap</text></svg>`,
  windowGrip: `<svg viewBox="0 0 160 104"><circle class="d-head" cx="22" cy="44" r="12"/><path class="d-body" d="M22 56v42"/><path class="d-arm" d="M22 66 64 54"/><rect x="64" y="26" width="7" height="44" rx="2" class="d-phone"/><path class="d-arc" d="M36 42H140"/>${ARROW(132, 42, 148, 42, 'd-arrow d-hot')}<text x="110" y="34" class="d-label">look through it</text></svg>`,
};

const KEYS = [
  ['W A S D', 'Move'], ['Mouse', 'Look (click to capture)'], ['Left-drag', 'Slash that way'], ['Left click', 'Stab'],
  ['Right button · Shift · K', 'Hold the shield'], ['F', 'Shield bash'], ['Q', 'Sword / spear'], ['R · Middle button', 'Throw the spear'],
  ['← → ↑ ↓', 'Slash'], ['Space', 'Stab'], ['C', 'Recenter'], ['Esc · P', 'Pause'],
];

/**
 * Creates the menus.
 * @param {object} ctx shared context (§4)
 * @returns {{ update(dt:number):void, back():boolean, show(name:string|null):void }}
 */
export function createMenus(ctx) {
  const settings = ctx.settings;
  const root = document.createElement('div');
  root.id = 'menus';
  ctx.overlay.appendChild(root);

  const screens = {};
  let stack = [];
  let pausedFrom = 'battle';
  let best = null;           // { score, kills, day } (loaded lazily)
  let lastRun = null;
  let newRecord = false;
  let motionSupported = null; // null = unknown
  let probing = false;
  // What the grip chooser or a safety notice holds up until it is answered: 'motion' (turn motion
  // on), 'grip' (switch to the hilt grip), 'start' (pendingStart: a Fight, Continue or Agoge click).
  let pending = '';
  let pendingStart = null;   // [mode, opts, act]
  let safetyGrip = 'window'; // the lines the safety notice shows
  let gripNew = false;       // the chooser shows 'New' (a motion player from before the grips)
  let gripOffered = false;   // the chooser was pushed this session
  let fightNow = false;      // "Fight now" on the first hilt Fight notice (this session only)
  let rotateWide = window.innerWidth > window.innerHeight;
  let rotateWait = 0;
  let rotateOn = false;
  let confirmKey = '';
  let confirmLeft = 0;
  let toastLeft = 0;
  let unlocked = false;

  const sfx = (name) => {
    if (ctx.audio && ctx.audio.play) {
      try { ctx.audio.play(name); } catch (e) { /* ignore */ }
    }
  };
  const storage = () => (ctx.platform && ctx.platform.storage) || null;

  function loadBest() {
    if (best) return best;
    const s = storage();
    const b = (s && s.load('thermopylae.best', null)) || {};
    best = { score: +b.score || 0, kills: +b.kills || 0, day: +b.day || 0 };
    return best;
  }
  function saveBest() {
    const s = storage();
    if (s) s.save('thermopylae.best', best);
  }

  // First user gesture unlocks WebAudio (touchend/click/keydown are activation events).
  function unlockOnce() {
    if (unlocked) return;
    unlocked = true;
    for (const t of ['click', 'touchend', 'keydown', 'pointerup']) window.removeEventListener(t, unlockOnce, true);
    if (ctx.audio && ctx.audio.unlock) ctx.audio.unlock();
  }
  for (const t of ['click', 'touchend', 'keydown', 'pointerup']) window.addEventListener(t, unlockOnce, true);

  // ---- building blocks ----
  function el(tag, cls, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html !== undefined) e.innerHTML = html;
    return e;
  }
  function button(label, act, cls = '') {
    const b = el('button', `mn-btn ${cls}`);
    b.type = 'button';
    b.dataset.act = act;
    b.innerHTML = label;
    return b;
  }
  function screen(name, cls, inner) {
    const s = el('section', `mn-screen ${cls}`, inner);
    s.dataset.screen = name;
    root.appendChild(s);
    screens[name] = s;
    s.addEventListener('click', (e) => {
      const b = e.target.closest('[data-act]');
      if (!b || b.disabled || !s.contains(b)) return;
      act(b.dataset.act, b);
    });
    return s;
  }

  // ---- screens ----
  function buildTitle() {
    const s = screen('title', 'mn-title', `
      <div class="mn-veil"></div>
      <div class="mn-title-main">
        <div class="mn-greek">ΘΕΡΜΟΠΥΛΑΙ</div>
        <h1 class="mn-logo gk-gold"><span class="s" aria-hidden="true">THERMOPYLAE</span><span class="t">THERMOPYLAE</span></h1>
        <div class="mn-year"><i></i><span>480 BC</span><i></i></div>
        <blockquote class="mn-epigraph"><p></p><cite></cite></blockquote>
      </div>
      <div class="mn-title-bar">
        <div class="mn-title-row">
          <button type="button" class="mn-btn mn-primary" data-act="fight">Fight<small>Day I</small></button>
          <button type="button" class="mn-btn mn-primary mn-continue" data-act="continue">Continue<small>Day II</small></button>
          <button type="button" class="mn-btn" data-act="agoge">The Agoge<small>Training</small></button>
        </div>
        <div class="mn-title-row mn-title-row2">
          <div class="mn-ctlpick"><span class="mn-ctlpick-l">Controls</span>
            <div class="mn-seg" data-field="control"><button type="button" data-v="motion">Motion</button><button type="button" data-v="touch">Touch</button></div></div>
          <div class="mn-ctlpick mn-grippick"><span class="mn-ctlpick-l">Grip</span>
            <div class="mn-seg" data-field="grip"><button type="button" data-v="hilt">Hilt</button><button type="button" data-v="window">Window</button></div></div>
          <div class="mn-title-pair">
            <button type="button" class="mn-btn mn-quiet" data-act="howto">How to play</button>
            <button type="button" class="mn-btn mn-quiet" data-act="settings">Settings</button>
          </div>
        </div>
      </div>
      <div class="mn-toast" role="status"></div>`);
    s.querySelector('.mn-epigraph p').textContent = `“${TEXT.epigraph}”`;
    s.querySelector('.mn-epigraph cite').textContent = TEXT.epigraphCite;
    bindSeg(s.querySelector('.mn-seg[data-field="control"]'), 'control');
    bindSeg(s.querySelector('.mn-seg[data-field="grip"]'), 'grip');
  }

  function buildPause() {
    screen('pause', 'mn-pause mn-dim', `
      <div class="mn-panel mn-panel-narrow">
        <div class="mn-meander"></div>
        <div class="mn-kicker mn-pause-k"></div>
        <h2 class="mn-h">Paused</h2>
        <div class="mn-stack">
          <button type="button" class="mn-btn mn-primary" data-act="resume">Resume</button>
          <button type="button" class="mn-btn" data-act="restart" data-confirm="Restart? Tap again">Restart</button>
          <button type="button" class="mn-btn" data-act="settings">Settings</button>
          <button type="button" class="mn-btn mn-quiet" data-act="quit" data-confirm="Leave the field? Tap again">Quit</button>
        </div>
        <div class="mn-meander mn-meander-b"></div>
      </div>`);
  }

  function buildGameOver() {
    const s = screen('gameover', 'mn-gameover mn-dim mn-dark', `
      <div class="mn-panel mn-panel-wide">
        <div class="mn-meander"></div>
        <div class="mn-kicker">Thermopylae · 480 BC</div>
        <h2 class="mn-h mn-h-big">You have fallen</h2>
        <div class="mn-go-sub"></div>
        <div class="mn-stats">
          <div><span>Day</span><b class="mn-st-day">I</b></div>
          <div><span>Slain</span><b class="mn-st-kills">0</b></div>
          <div><span>Score</span><b class="mn-st-score">0</b></div>
          <div><span>Best</span><b class="mn-st-best">0</b><em class="mn-record">New record</em></div>
        </div>
        <blockquote class="mn-epitaph"><p><span></span><span></span></p><cite></cite></blockquote>
        <div class="mn-row-btns">
          <button type="button" class="mn-btn mn-primary" data-act="again">Fight again</button>
          <button type="button" class="mn-btn" data-act="title">Title</button>
        </div>
        <div class="mn-meander mn-meander-b"></div>
      </div>`);
    const ps = s.querySelectorAll('.mn-epitaph p span');
    ps[0].textContent = `“${TEXT.epitaph1}`;
    ps[1].textContent = `${TEXT.epitaph2}”`;
    s.querySelector('.mn-epitaph cite').textContent = TEXT.epitaphCite;
  }

  function buildSettings() {
    const s = screen('settings', 'mn-settings mn-dim', `
      <div class="mn-panel mn-panel-full">
        <header class="mn-head">
          <h2 class="mn-h">Settings</h2>
          <nav class="mn-tabs">${TABS.map(([k, l]) => `<button type="button" data-tab="${k}">${l}</button>`).join('')}</nav>
          <button type="button" class="mn-btn mn-back" data-act="back">Back</button>
        </header>
        <div class="mn-meander mn-meander-thin"></div>
        <div class="mn-body"></div>
      </div>`);
    const body = s.querySelector('.mn-body');
    for (const [tab] of TABS) {
      const page = el('div', 'mn-page');
      page.dataset.page = tab;
      for (const f of FIELDS.filter((x) => x.tab === tab)) page.appendChild(fieldRow(f));
      if (tab === 'controls') {
        const r = el('div', 'mn-field mn-field-act', '<div class="mn-fl"><b>Motion safety</b><small>Read the notice again</small></div>');
        r.appendChild(button('Read', 'safety-read', 'mn-small'));
        page.appendChild(r);
      }
      body.appendChild(page);
    }
    bindTabs(s, 'controls');
  }

  function fieldRow(f) {
    const row = el('div', `mn-field mn-field-${f.type}`);
    row.dataset.key = f.key;
    row.innerHTML = `<div class="mn-fl"><b></b><small></small></div><div class="mn-fc"></div>`;
    row.querySelector('b').textContent = f.label;
    row.querySelector('small').textContent = f.hint || '';
    const fc = row.querySelector('.mn-fc');
    if (f.type === 'toggle') {
      const t = el('button', 'mn-toggle', '<i></i>');
      t.type = 'button';
      t.setAttribute('role', 'switch');
      t.setAttribute('aria-label', f.label);
      t.addEventListener('click', () => {
        sfx('uiTap');
        const key = fieldKey(f);
        setSetting(key, !settings[key]);
      });
      fc.appendChild(t);
    } else if (f.type === 'seg') {
      const seg = el('div', 'mn-seg');
      seg.dataset.field = f.key;
      seg.innerHTML = f.options.map(([v, l]) => `<button type="button" data-v="${v}">${l}</button>`).join('');
      bindSeg(seg, f.key);
      fc.appendChild(seg);
    } else if (f.type === 'range') {
      const wrap = el('div', 'mn-range');
      const input = el('input');
      input.type = 'range';
      input.min = String(f.min);
      input.max = String(f.max);
      input.step = String(f.step);
      input.setAttribute('aria-label', f.label);
      const out = el('output');
      input.addEventListener('input', () => {
        const val = clamp(parseFloat(input.value), f.min, f.max);
        applySetting(fieldKey(f), val);
        out.textContent = f.fmt(val);
        paintRange(input, f);
      });
      input.addEventListener('change', () => {
        ctx.saveSettings();
        if (f.tab === 'sound') sfx('uiTap');
      });
      wrap.appendChild(input);
      wrap.appendChild(out);
      fc.appendChild(wrap);
    }
    return row;
  }

  // The filled part of a range track is a background-size (set on change, not animated).
  function paintRange(input, f) {
    const p = ((parseFloat(input.value) - f.min) / (f.max - f.min)) * 100;
    input.style.backgroundSize = `${p.toFixed(1)}% 4px, 100% 4px`;
  }

  function bindSeg(seg, key) {
    seg.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-v]');
      if (!b) return;
      sfx('uiTap');
      if (key === 'control') chooseControl(b.dataset.v);
      else if (key === 'grip') chooseGrip(b.dataset.v);
      else setSetting(key, b.dataset.v);
    });
  }

  function bindTabs(s, first) {
    const tabs = s.querySelectorAll('.mn-tabs button');
    const pages = s.querySelectorAll('[data-page]');
    const pick = (k) => {
      for (const t of tabs) t.classList.toggle('is-on', t.dataset.tab === k);
      for (const p of pages) p.classList.toggle('is-on', p.dataset.page === k);
    };
    for (const t of tabs) {
      t.addEventListener('click', () => {
        sfx('uiTap');
        pick(t.dataset.tab);
      });
    }
    pick(first);
  }

  function buildHowto() {
    const card = (svg, title, text, warn) => `<figure class="mn-card">${svg}<figcaption><b>${title}</b><span>${text}</span>${warn ? `<em>${warn}</em>` : ''}</figcaption></figure>`;
    const s = screen('howto', 'mn-howto mn-dim', `
      <div class="mn-panel mn-panel-full">
        <header class="mn-head">
          <h2 class="mn-h">How to play</h2>
          <nav class="mn-tabs"><button type="button" data-tab="motion">Motion</button><button type="button" data-tab="touch">Touch</button><button type="button" data-tab="keys">Keyboard</button></nav>
          <button type="button" class="mn-btn mn-back" data-act="back">Back</button>
        </header>
        <div class="mn-meander mn-meander-thin"></div>
        <div class="mn-body">
          <div class="mn-page" data-page="motion">
            <div class="mn-howgrip"><span class="mn-ctlpick-l">Grip</span>
              <div class="mn-seg mn-seg-mini"><button type="button" data-hg="hilt">Hilt grip</button><button type="button" data-hg="window">Window grip</button></div></div>
            <div class="mn-howset" data-set="hilt">
              <p class="mn-lead">${TEXT.hiltLead}</p>
              <div class="mn-cards">
                ${card(DIAGRAMS.hiltGrip, 'Hold it like a hilt', 'Upright in your fist, screen toward you. Your fingers stay on the back.')}
                ${card(DIAGRAMS.hiltCut, 'Cut with the top', 'Let the top of the phone lead, like a blade. You cut the way the top moves.')}
                ${card(DIAGRAMS.hiltBlock, 'Thumb = shield', 'Press the glass to raise the aspis. Rest your thumb on the edge to lower it.')}
                ${card(DIAGRAMS.hiltSwipe, 'Swipe: change foe', 'The view faces your foe. Swipe your thumb left or right to turn to the next one.')}
                ${card(DIAGRAMS.hiltThrust, 'Punch to stab', 'Punch the phone straight forward, then draw it back.')}
                ${card(DIAGRAMS.hiltThrow, 'Cock back, then throw', 'Spear in hand (the top button): cock the phone back behind your shoulder, then snap it forward, fist closed.', 'Never let go. A firm flick is enough.')}
              </div>
              <p class="mn-note">Shield + punch = bash. Alternate right and left cuts for combos.</p>
            </div>
            <div class="mn-howset" data-set="window">
              <p class="mn-lead">Hold the phone in landscape, screen toward you, and look through it: the blade points where you look.</p>
              <div class="mn-cards">
                ${card(DIAGRAMS.swing, 'Swing to slash', 'Swing the phone like a blade. You cut the way you swing.')}
                ${card(DIAGRAMS.stab, 'Push to stab', 'Push it straight forward, then draw it back.')}
                ${card(DIAGRAMS.shield, 'Thumb = shield', 'Press the glass to raise the aspis. Rest your thumb on the frame to lower it.')}
                ${card(DIAGRAMS.bash, 'Shield + push = bash', 'With the shield up, push forward to bash.')}
                ${card(DIAGRAMS.throw, 'Overhand throw', 'Spear in hand: raise it beside your head, then throw overhand.', 'Never let go. A firm flick is enough.')}
              </div>
              <p class="mn-note">Turn your body to look around. Alternate right and left cuts for combos.</p>
            </div>
          </div>
          <div class="mn-page" data-page="touch">
            <div class="mn-touch">
              <div class="mn-touch-fig">${DIAGRAMS.layout}</div>
              <ul class="mn-list">
                <li><b>Left thumb</b> moves with the stick; hold <b>Shield</b> to block.</li>
                <li><b>Swipe</b> fast on the right to slash that way; <b>tap</b> to stab; <b>drag slowly</b> to look.</li>
                <li><b>Bash</b>: tap Bash, or swipe up from the Shield.</li>
                <li><b>Throw</b>: with a spear in hand, hold Throw to wind up, then release. Drag off it to cancel.</li>
                <li><b>Swap</b> sword and spear with the corner button; its number is the spears you have left.</li>
              </ul>
            </div>
          </div>
          <div class="mn-page" data-page="keys">
            <div class="mn-keys">${KEYS.map(([k, d]) => `<div><kbd>${k}</kbd><span>${d}</span></div>`).join('')}</div>
          </div>
        </div>
      </div>`);
    bindTabs(s, settings.control === 'motion' ? 'motion' : 'touch');
    // The Motion tab shows one grip's cards at a time: the current grip's on opening (howtoGrip).
    for (const b of s.querySelectorAll('[data-hg]')) {
      b.addEventListener('click', () => {
        sfx('uiTap');
        howtoGrip(b.dataset.hg);
      });
    }
    howtoGrip(settings.grip === 'hilt' ? 'hilt' : 'window');
  }

  function howtoGrip(g) {
    const s = screens.howto;
    for (const b of s.querySelectorAll('[data-hg]')) b.classList.toggle('is-on', b.dataset.hg === g);
    for (const p of s.querySelectorAll('[data-set]')) p.classList.toggle('is-on', p.dataset.set === g);
  }

  // Lines per grip (docs-hilt-grip.md §A.6); showSafety() shows one list.
  function buildSafety() {
    screen('safety', 'mn-safety mn-dim mn-dark', `
      <div class="mn-panel mn-panel-wide">
        <div class="mn-meander"></div>
        <div class="mn-kicker mn-safe-k">Motion controls</div>
        <h2 class="mn-h">Before you take up the phone</h2>
        <ul class="mn-safe mn-safe-window">
          <li>${DIAGRAMS.grip}<span><b>Hold it firmly</b> in your sword hand.</span></li>
          <li>${DIAGRAMS.strap}<span><b>Use a wrist strap</b> if you have one.</span></li>
          <li>${DIAGRAMS.space}<span><b>Clear the space</b> around you: people, lamps, walls.</span></li>
          <li class="is-warn">${DIAGRAMS.hold}<span><b>Never let go when throwing.</b> A firm flick is enough.</span></li>
        </ul>
        <ul class="mn-safe mn-safe-hilt">
          <li>${DIAGRAMS.strap}<span><b>Hold it firmly in your fist.</b> A wrist strap, or a ring grip on the back of the phone, is strongly recommended.</span></li>
          <li>${DIAGRAMS.space}<span><b>Clear the space around you, behind you too:</b> the spear’s cock-back reaches behind your head.</span></li>
          <li class="is-warn">${DIAGRAMS.hold}<span><b>A firm flick is enough.</b> Swinging harder does nothing more.</span></li>
        </ul>
        <div class="mn-row-btns">
          <button type="button" class="mn-btn mn-primary" data-act="safety-accept">I understand</button>
          <button type="button" class="mn-btn mn-quiet mn-safe-alt" data-act="safety-touch">Use touch instead</button>
        </div>
      </div>`);
  }

  // The grip chooser (docs-hilt-grip.md §A.6): the first Motion choice once the hilt grip is
  // released, and once ('New') for a motion player from before the grips.
  function buildGrip() {
    screen('grip', 'mn-grip mn-dim mn-dark', `
      <div class="mn-panel mn-panel-wide">
        <div class="mn-meander"></div>
        <div class="mn-kicker mn-grip-k"></div>
        <h2 class="mn-h">How will you hold your sword?</h2>
        <div class="mn-grip-cards">
          <button type="button" class="mn-grip-card is-rec" data-act="grip-hilt">${DIAGRAMS.hiltGrip}
            <span class="mn-grip-tx"><b>Hilt grip</b><em>Recommended</em><span>Hold the phone upright in your fist like a sword handle, screen toward you. Press the glass with your thumb to raise the shield. The view turns to face your foe; swipe to change foe.</span></span></button>
          <button type="button" class="mn-grip-card" data-act="grip-window">${DIAGRAMS.windowGrip}
            <span class="mn-grip-tx"><b>Window grip</b><span>Hold the phone sideways and look through it. Turn your body to look around.</span></span></button>
        </div>
        <div class="mn-row-btns"><button type="button" class="mn-btn mn-quiet mn-small" data-act="back">Back</button></div>
      </div>`);
  }

  // A first hilt Fight before the hilt Agoge (docs-hilt-grip.md §A.6).
  function buildAgogeFirst() {
    screen('agogefirst', 'mn-agogefirst mn-dim', `
      <div class="mn-panel mn-panel-narrow">
        <div class="mn-meander"></div>
        <div class="mn-kicker">The hilt grip</div>
        <h2 class="mn-h">New to the hilt?</h2>
        <p class="mn-notice-p">The Agoge teaches the cuts, the shield, the lock-on and the spear throw in a few minutes.</p>
        <div class="mn-stack">
          <button type="button" class="mn-btn mn-primary" data-act="agoge-first">The Agoge first<small>3 min</small></button>
          <button type="button" class="mn-btn mn-quiet" data-act="fight-now">Fight now</button>
        </div>
        <div class="mn-meander mn-meander-b"></div>
      </div>`);
  }

  // The rotate prompt (docs-hilt-grip.md §A.5): its own layer over every screen, outside the stack.
  function buildRotate() {
    const r = el('div', 'mn-rotate', `
      <div class="mn-rotate-in">
        <svg class="mn-rotate-g" viewBox="0 0 64 64" aria-hidden="true"><path class="mn-rotate-arc" d="M14 18A24 24 0 0 1 50 14"/><path class="mn-rotate-arc" d="M50 46A24 24 0 0 1 14 50"/><g class="mn-rotate-phone"><rect x="22" y="12" width="20" height="40" rx="4"/><rect x="25" y="17" width="14" height="30" rx="1.5"/></g></svg>
        <p class="mn-rotate-t"></p>
        <button type="button" class="mn-btn mn-primary" data-act="rotate-window">Use the window grip</button>
      </div>`);
    r.id = 'rotate';
    r.setAttribute('role', 'alertdialog');
    r.querySelector('.mn-rotate-t').textContent = TEXT.rotate;
    r.querySelector('button').addEventListener('click', () => act('rotate-window'));
    root.appendChild(r);
    return r;
  }

  function buildNotice() {
    screen('notice', 'mn-notice mn-dim', `
      <div class="mn-panel mn-panel-narrow">
        <div class="mn-meander"></div>
        <h2 class="mn-h mn-notice-h"></h2>
        <p class="mn-notice-p"></p>
        <div class="mn-spinner"><i></i><i></i><i></i></div>
        <div class="mn-row-btns"><button type="button" class="mn-btn mn-primary" data-act="back">Understood</button></div>
      </div>`);
  }

  buildTitle();
  buildPause();
  buildGameOver();
  buildSettings();
  buildHowto();
  buildSafety();
  buildNotice();
  buildGrip();
  buildAgogeFirst();
  const rotateEl = buildRotate();

  // ---- settings application ----
  // The settings key a row edits: the effective grip's sensitivity and swaps (FIELDS keyOf).
  const fieldKey = (f) => (f.keyOf ? f.keyOf(settings) : f.key);

  function applySetting(key, value) {
    settings[key] = value;
    switch (key) {
      case 'sensitivity':
      case 'sensitivityHilt':
        if (key === sensitivityKey(settings) && ctx.gestures && ctx.gestures.setSensitivity) ctx.gestures.setSensitivity(value);
        break;
      case 'master':
      case 'music':
      case 'sfx':
        if (ctx.audio && ctx.audio.applyVolumes) ctx.audio.applyVolumes();
        break;
      case 'voice':
        if (!value && ctx.platform && ctx.platform.voice) ctx.platform.voice.stop();
        break;
      case 'haptics':
        if (value && ctx.platform && ctx.platform.haptics) ctx.platform.haptics.pulse(25, 0.7);
        break;
      default:
        break;
    }
  }

  // A change of control or grip re-applies the recognizer, ctx.input.grip and the orientation at
  // once (the probe fallback included), so the app rotates while the menu is still open.
  function setSetting(key, value) {
    applySetting(key, value);
    ctx.saveSettings();
    if (key === 'control' || key === 'grip') ctx.applyControls?.();
    refreshFields();
  }

  function refreshFields() {
    const motion = settings.control === 'motion';
    const hilt = effectiveGrip(settings) === 'hilt';
    for (const seg of root.querySelectorAll('.mn-seg[data-field]')) {
      const key = seg.dataset.field;
      // A missing or unknown grip reads as the window grip (core/grip.js).
      const cur = key === 'grip' ? (settings.grip === 'hilt' ? 'hilt' : 'window') : String(settings[key]);
      for (const b of seg.querySelectorAll('button')) {
        b.classList.toggle('is-on', cur === b.dataset.v);
        if (key === 'control' && b.dataset.v === 'motion') b.classList.toggle('is-na', motionSupported === false);
      }
    }
    // The title's Grip segment shows only with Motion on.
    screens.title.classList.toggle('is-motion', motion);
    for (const row of screens.settings.querySelectorAll('.mn-field[data-key]')) {
      const f = FIELDS.find((x) => x.key === row.dataset.key);
      const key = fieldKey(f);
      if (f.type === 'toggle') {
        const on = !!settings[key];
        const t = row.querySelector('.mn-toggle');
        t.classList.toggle('is-on', on);
        t.setAttribute('aria-checked', String(on));
      } else if (f.type === 'range') {
        const input = row.querySelector('input');
        const val = Number.isFinite(+settings[key]) ? +settings[key] : f.min;
        input.value = String(val);
        row.querySelector('output').textContent = f.fmt(val);
        paintRange(input, f);
      }
      if (f.motionOnly) row.classList.toggle('is-off', !motion || (f.hintHilt !== undefined && hilt));
      if (f.hiltOnly) row.classList.toggle('is-off', !hilt);
      if (f.hintHilt) {
        const small = row.querySelector('small');
        const hint = hilt ? f.hintHilt : f.hint;
        if (small.textContent !== hint) small.textContent = hint;
      }
      if (f.key === 'aimAssist') row.classList.toggle('is-off', motion);
    }
  }

  // ---- motion controls: (grip chooser →) safety → permission → support → fallback ----
  // The release gate, or the menus' own test hook (hilt-shots.mjs shows the chooser before release).
  const released = () => HILT_RELEASED || api.forceRelease === true;
  // The safety notice shows for a grip that has not been accepted yet: the window lines once, and
  // the hilt lines once more (docs-hilt-grip.md §A.6), wherever the grip first becomes effective.
  const needSafety = (grip) => !settings.safetyAccepted || (grip === 'hilt' && settings.hiltSafetyAccepted !== true);

  function chooseControl(v) {
    if (v !== 'motion') {
      clearPending();
      setSetting('control', 'touch');
      return;
    }
    if (released() && settings.gripChosen === false) {
      pending = 'motion';
      showGripChooser(false);
      return;
    }
    proceedMotion();
  }

  function proceedMotion() {
    const grip = settings.grip === 'hilt' ? 'hilt' : 'window';
    if (needSafety(grip)) {
      pending = 'motion';
      showSafety(grip);
      return;
    }
    enableMotion();
  }

  /** The Grip segment (title, Settings) and the chooser for an existing motion player. */
  function chooseGrip(v) {
    const grip = v === 'hilt' ? 'hilt' : 'window';
    if (grip === 'hilt' && settings.control === 'motion' && needSafety('hilt')) {
      pending = 'grip';
      showSafety('hilt');
      return;
    }
    clearPending();
    const was = effectiveGrip(settings);
    settings.gripChosen = true;
    setSetting('grip', grip);
    const now = effectiveGrip(settings);
    if (now && now !== was && current() === 'title') toast(now === 'hilt' ? TEXT.hiltOn : TEXT.motionOn);
  }

  function clearPending() {
    pending = '';
    pendingStart = null;
  }

  function showGripChooser(isNew) {
    gripNew = isNew;
    gripOffered = true;
    screens.grip.querySelector('.mn-grip-k').textContent = isNew ? 'New' : 'Motion controls';
    screens.grip.classList.toggle('is-new', isNew);
    push('grip');
  }

  function showSafety(grip) {
    const s = screens.safety;
    const hilt = grip === 'hilt';
    safetyGrip = hilt ? 'hilt' : 'window';
    s.classList.toggle('is-hilt', hilt);
    s.querySelector('.mn-safe-k').textContent = hilt ? 'The hilt grip' : 'Motion controls';
    // The way out: touch while Motion is not on yet (and for the window notice), else the window grip.
    const alt = hilt ? 'Use the window grip' : 'Use touch instead';
    const b = s.querySelector('.mn-safe-alt');
    if (b.textContent !== alt) b.textContent = alt;
    if (current() !== 'safety') push('safety');
  }

  // Must run inside the click handler: iOS only grants motion from a user gesture.
  function enableMotion() {
    pending = '';
    const m = ctx.platform && ctx.platform.motion;
    let perm;
    try {
      perm = m ? m.requestPermission() : false;
    } catch (e) {
      perm = false;
    }
    notice('Readying the sensors…', 'Hold the phone still for a moment.', true);
    Promise.resolve(perm)
      .then((ok) => (ok && m ? m.isSupported() : false))
      .catch(() => false)
      .then((ok) => {
        motionSupported = !!ok;
        if (current() === 'notice') pop();
        if (ok) {
          setSetting('control', 'motion');
          toast(effectiveGrip(settings) === 'hilt' ? TEXT.hiltOn : TEXT.motionOn);
        } else {
          setSetting('control', 'touch');
          notice('Touch controls', TEXT.noMotion, false);
        }
      });
  }

  function probeMotion() {
    if (probing || motionSupported !== null) return;
    const m = ctx.platform && ctx.platform.motion;
    if (!m || !m.isSupported) return;
    probing = true;
    const needsGesture = typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function';
    Promise.resolve()
      .then(() => m.isSupported())
      .catch(() => false)
      .then((ok) => {
        probing = false;
        if (ok) motionSupported = true;
        else if (!needsGesture) motionSupported = false;
        if (!ok && !needsGesture && settings.control === 'motion') {
          setSetting('control', 'touch');
          toast(TEXT.noMotion);
        }
        refreshFields();
      });
  }

  function notice(title, text, busy) {
    const s = screens.notice;
    s.querySelector('.mn-notice-h').textContent = title;
    s.querySelector('.mn-notice-p').textContent = text;
    s.classList.toggle('is-busy', !!busy);
    if (current() !== 'notice') push('notice');
  }

  function toast(text) {
    const t = screens.title.querySelector('.mn-toast');
    t.textContent = text;
    t.classList.add('is-shown');
    toastLeft = 5;
  }

  // ---- screen stack ----
  const current = () => stack[stack.length - 1] || null;

  function render() {
    const top = current();
    for (const [name, s] of Object.entries(screens)) s.classList.toggle('is-on', name === top);
    root.classList.toggle('is-open', !!top);
    if (top === 'title') renderTitle();
    else if (top === 'pause') renderPause();
    else if (top === 'gameover') renderGameOver();
    if (top === 'settings' || top === 'title') refreshFields();
    clearConfirm();
  }

  function push(name) {
    if (current() === name) return;
    stack.push(name);
    render();
  }
  function pop() {
    stack.pop();
    render();
  }

  function show(name) {
    if (!name || name === 'none') stack = [];
    else if (name === 'title' || name === 'pause' || name === 'gameover') stack = [name];
    else if (screens[name]) {
      if (current() === name) return;
      stack.push(name);
    }
    render();
  }

  function baseFor(mode) {
    if (mode === 'title') return 'title';
    if (mode === 'paused') return 'pause';
    if (mode === 'gameover') return 'gameover';
    return null;
  }

  function renderTitle() {
    loadBest();
    const s = screens.title;
    const reached = clamp(best.day | 0, 1, 3);
    const cont = s.querySelector('.mn-continue');
    cont.classList.toggle('is-hidden', reached <= 1);
    cont.querySelector('small').textContent = `Day ${roman(reached)}`;
    probeMotion();
  }

  function renderPause() {
    const s = screens.pause;
    let k = '';
    if (pausedFrom === 'training') k = 'The Agoge';
    else k = pauseKicker(ctx.waves);
    s.querySelector('.mn-pause-k').textContent = k;
  }

  function renderGameOver() {
    loadBest();
    const s = screens.gameover;
    const run = lastRun || { day: (ctx.waves && ctx.waves.day) || 1, kills: (ctx.player && ctx.player.kills) || 0, score: (ctx.player && ctx.player.score) || 0 };
    s.querySelector('.mn-st-day').textContent = roman(run.day || 1);
    s.querySelector('.mn-st-kills').textContent = groupDigits(run.kills);
    s.querySelector('.mn-st-score').textContent = groupDigits(run.score);
    s.querySelector('.mn-st-best').textContent = groupDigits(Math.max(best.score, run.score || 0));
    s.querySelector('.mn-record').classList.toggle('is-on', newRecord);
    const ord = ORDINAL[run.day] || '';
    s.querySelector('.mn-go-sub').textContent = ord ? `at the Hot Gates, on the ${ord} day` : 'at the Hot Gates';
    s.querySelector('[data-act="again"]').innerHTML = `Fight again<small>Day ${roman(clamp(run.day || 1, 1, 3))}</small>`;
  }

  // Two-tap confirmation for destructive pause actions.
  function confirmFirst(b) {
    if (!b.dataset.confirm) return true;
    if (confirmKey === b.dataset.act) return true;
    clearConfirm();
    confirmKey = b.dataset.act;
    confirmLeft = 3;
    b.dataset.label = b.textContent;
    b.textContent = b.dataset.confirm;
    b.classList.add('is-confirm');
    return false;
  }
  function clearConfirm() {
    confirmKey = '';
    confirmLeft = 0;
    for (const b of root.querySelectorAll('.is-confirm')) {
      b.textContent = b.dataset.label || b.textContent;
      b.classList.remove('is-confirm');
    }
  }

  function startMode(mode, opts) {
    // iOS: re-request motion inside this gesture (a no-op elsewhere).
    if (settings.control === 'motion' && ctx.platform && ctx.platform.motion) {
      try {
        const r = ctx.platform.motion.requestPermission();
        if (r && r.catch) r.catch(() => {});
      } catch (e) { /* ignore */ }
    }
    ctx.setMode(mode, opts);
  }

  /**
   * A Fight, Continue, Fight again or Agoge click (docs-hilt-grip.md §A.5, §A.6). In the hilt grip:
   * the hilt safety notice if it has not been accepted, then (a battle before the hilt Agoge) the
   * "Agoge first / Fight now" notice, then fullscreen on the web, where the portrait lock needs it.
   */
  function startFlow(mode, opts, name) {
    const hilt = effectiveGrip(settings) === 'hilt';
    if (hilt && needSafety('hilt')) {
      pending = 'start';
      pendingStart = [mode, opts, name];
      showSafety('hilt');
      return;
    }
    if (hilt && mode === 'battle' && name !== 'again' && settings.hiltAgogeDone !== true && !fightNow) {
      pendingStart = [mode, opts, name];
      push('agogefirst');
      return;
    }
    clearPending();
    if (hilt) fullscreen();
    startMode(mode, opts);
  }

  // A web orientation lock only succeeds in fullscreen, which resolves later: lock again then.
  // The mode starts at once; the rotate prompt covers a refusal. Android rotates natively.
  function fullscreen() {
    if (!ctx.platform || ctx.platform.kind !== 'web') return;
    const d = document.documentElement;
    if (!d.requestFullscreen || document.fullscreenElement) return;
    try {
      const p = d.requestFullscreen({ navigationUI: 'hide' });
      if (p && p.then) p.then(() => ctx.applyControls?.(), () => {});
    } catch (e) { /* ignore */ }
  }

  /** Picks up what a safety notice held up, once it is accepted (or the grip changed). */
  function resumePending() {
    const p = pending;
    pending = '';
    if (p === 'motion') proceedMotion();
    else if (p === 'grip') chooseGrip('hilt');
    else if (p === 'start' && pendingStart) startFlow(...pendingStart);
  }

  function act(name, b) {
    if (ctx.audio && ctx.audio.unlock) ctx.audio.unlock();
    switch (name) {
      case 'fight':
        sfx('uiConfirm');
        startFlow('battle', { day: 1 }, name);
        break;
      case 'continue':
        sfx('uiConfirm');
        startFlow('battle', { day: clamp(loadBest().day | 0, 1, 3) }, name);
        break;
      case 'agoge':
        sfx('uiConfirm');
        startFlow('training', {}, name);
        break;
      case 'agoge-first':
        sfx('uiConfirm');
        pop();
        startFlow('training', {}, 'agoge');
        break;
      case 'fight-now':
        sfx('uiConfirm');
        fightNow = true;
        pop();
        if (pendingStart) startFlow(...pendingStart);
        break;
      case 'grip-hilt':
      case 'grip-window': {
        sfx('uiConfirm');
        const grip = name === 'grip-hilt' ? 'hilt' : 'window';
        settings.gripChosen = true;
        pop();
        if (gripNew) chooseGrip(grip);
        else {
          // The first Motion choice: Motion is not on yet, so the grip just gets set.
          applySetting('grip', grip);
          ctx.saveSettings();
          proceedMotion();
        }
        break;
      }
      case 'rotate-window':
        sfx('uiConfirm');
        settings.gripChosen = true;
        setSetting('grip', 'window');
        break;
      case 'settings':
        sfx('uiTap');
        push('settings');
        break;
      case 'howto':
        sfx('uiTap');
        howtoGrip(settings.grip === 'hilt' ? 'hilt' : 'window');
        push('howto');
        break;
      case 'back':
        sfx('uiTap');
        back();
        break;
      case 'resume':
        sfx('uiConfirm');
        ctx.resume();
        break;
      case 'restart':
        if (!confirmFirst(b)) return sfx('uiTap');
        sfx('uiConfirm');
        if (pausedFrom === 'training') ctx.setMode('training', {});
        else {
          // Back to the start of the current part of the day (Day 1 dusk restarts at dusk).
          const w = ctx.waves;
          ctx.setMode(pausedFrom, { day: (w && w.day) || 1, entry: (w && w.entryIndex) || 0 });
        }
        break;
      case 'quit':
        if (!confirmFirst(b)) return sfx('uiTap');
        sfx('uiTap');
        ctx.setMode('title');
        break;
      case 'again':
        sfx('uiConfirm');
        startFlow('battle', { day: clamp((lastRun && lastRun.day) || 1, 1, 3) }, name);
        break;
      case 'title':
        sfx('uiTap');
        ctx.setMode('title');
        break;
      case 'safety-read':
        sfx('uiTap');
        showSafety(effectiveGrip(settings) === 'hilt' ? 'hilt' : 'window');
        break;
      case 'safety-accept':
        sfx('uiConfirm');
        settings.safetyAccepted = true;
        if (safetyGrip === 'hilt') settings.hiltSafetyAccepted = true;
        ctx.saveSettings();
        pop();
        resumePending();
        break;
      case 'safety-touch':
        sfx('uiTap');
        pop();
        if (safetyGrip === 'hilt') {
          // "Use the window grip": what was held up goes on in the window grip (its own notice first).
          setSetting('grip', 'window');
          if (pending === 'grip') clearPending();
          else resumePending();
        } else {
          clearPending();
          setSetting('control', 'touch');
        }
        break;
      default:
        break;
    }
    return undefined;
  }

  /** Handles Android back / Esc. True when a menu consumed it. */
  function back() {
    if (stack.length > 1) {
      const top = current();
      // Backing out of a notice or the chooser cancels what it held up (Motion stays off). An
      // existing motion player's 'New' chooser counts as answered: they keep the window grip.
      if (top === 'safety' || top === 'grip' || top === 'agogefirst') clearPending();
      if (top === 'grip' && gripNew) {
        settings.gripChosen = true;
        ctx.saveSettings();
      }
      pop();
      return true;
    }
    if (current() === 'gameover') {
      ctx.setMode('title');
      return true;
    }
    return false;
  }

  // ---- events ----
  ctx.events.on('modeChanged', ({ mode, prev }) => {
    if (mode === 'paused') pausedFrom = prev;
    show(baseFor(mode));
  });
  ctx.events.on('gameOver', (e) => {
    loadBest();
    lastRun = { day: (e && e.day) || 1, wave: (e && e.wave) || 0, kills: (e && e.kills) || 0, score: (e && e.score) || 0 };
    newRecord = lastRun.score > best.score;
    best.score = Math.max(best.score, lastRun.score);
    best.kills = Math.max(best.kills, lastRun.kills);
    best.day = Math.max(best.day, lastRun.day);
    saveBest();
    if (current() === 'gameover') renderGameOver();
  });
  ctx.events.on('dayStart', (e) => {
    if (!e || ctx.mode !== 'battle') return;
    loadBest();
    if ((e.day | 0) > best.day) {
      best.day = e.day | 0;
      saveBest();
    }
  });

  window.addEventListener('resize', () => {
    rotateWide = window.innerWidth > window.innerHeight;
  });

  // The rotate prompt (§A.5): the hilt grip wants portrait, but the display has stayed landscape
  // for 600 ms (Android 16 ignores the request on large screens; a web lock needs fullscreen). In a
  // fight it also pauses. Recognition keeps working; the prompt is only about the layout.
  function updateRotate(dt) {
    const m = ctx.mode;
    const wrong = rotateWide && (m === 'title' || m === 'battle' || m === 'training' || m === 'paused') && appOrientation(settings) === 'portrait';
    rotateWait = wrong ? rotateWait + dt : 0;
    const on = wrong && rotateWait > 0.6;
    // A fight that starts (or resumes) under the prompt pauses too.
    if (on && (m === 'battle' || m === 'training')) ctx.setMode('paused');
    if (on === rotateOn) return;
    rotateOn = on;
    rotateEl.classList.toggle('is-on', on);
  }

  window.addEventListener('keydown', (e) => {
    if (e.defaultPrevented || !current()) return;
    if (e.code === 'Escape' || (e.code === 'KeyP' && current() === 'pause')) {
      if (!back() && current() === 'pause') ctx.resume();
      e.preventDefault();
    }
  });

  const api = {
    update(dt) {
      if (confirmLeft > 0) {
        confirmLeft -= dt;
        if (confirmLeft <= 0) clearConfirm();
      }
      if (toastLeft > 0) {
        toastLeft -= dt;
        if (toastLeft <= 0) screens.title.querySelector('.mn-toast').classList.remove('is-shown');
      }
      if (!stack.length && baseFor(ctx.mode)) show(baseFor(ctx.mode));
      // A motion player from before the grips is offered the hilt grip once, on the title.
      if (!gripOffered && current() === 'title' && settings.control === 'motion' && settings.gripChosen === false && released()) showGripChooser(true);
      updateRotate(dt);
    },
    back,
    show,
    /** Test hook (hilt-shots.mjs): offer the grip chooser as if HILT_RELEASED were true. */
    forceRelease: false,
  };
  return api;
}
