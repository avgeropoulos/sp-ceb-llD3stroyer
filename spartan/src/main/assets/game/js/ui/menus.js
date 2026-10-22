// ui/menus.js: title, settings, pause, game over, how to play, motion safety notice (#menus, z 30).
// DESIGN.md §9, §10 (pause/back), §11 ui/*. Settings apply live and are saved with ctx.saveSettings().
import { clamp } from '../core/math.js';

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
});

/** Settings fields (§9). `tab` groups them; every field is live-applied. */
export const FIELDS = [
  { tab: 'controls', key: 'control', type: 'seg', label: 'Controls', options: [['motion', 'Motion'], ['touch', 'Touch']] },
  { tab: 'controls', key: 'sensitivity', type: 'range', label: 'Sensitivity', hint: 'Motion: higher lets gentler swings count', min: 0.5, max: 2, step: 0.05, fmt: (v) => `${v.toFixed(2)}×` },
  { tab: 'controls', key: 'gyroLook', type: 'toggle', label: 'Gyro look', hint: 'Motion: turn your body to turn the view' },
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
    <circle cx="288" cy="24" r="8" class="d-btn"/><text x="275" y="27" class="d-small" text-anchor="end">swap weapon</text>
    <circle cx="288" cy="56" r="12" class="d-btn d-throwbtn"/><text x="272" y="59" class="d-small" text-anchor="end">throw: hold, release</text>
    <circle cx="54" cy="112" r="21" class="d-ring"/><circle cx="58" cy="108" r="9" class="d-btn"/><text x="80" y="134" class="d-small">move</text>
    <circle cx="50" cy="64" r="16" class="d-btn"/><text x="50" y="66.5" class="d-tiny" text-anchor="middle">SHIELD</text>
    <circle cx="90" cy="72" r="11" class="d-btn"/><text x="90" y="74.5" class="d-tiny" text-anchor="middle">BASH</text>
    ${ARROW(50, 46, 50, 32, 'd-arrow')}<text x="58" y="40" class="d-small">swipe up: bash</text>
    <path d="M160 16v118" class="d-divider"/>
    ${ARROW(180, 98, 244, 74, 'd-arrow d-hot')}<text x="182" y="114" class="d-small">swipe fast: slash</text>
    <circle cx="246" cy="124" r="4.5" class="d-hotfill"/><text x="256" y="127" class="d-small">tap: stab</text>
    <path d="M182 50q22-12 44 0" class="d-arc"/><text x="184" y="40" class="d-small">drag slowly: look</text></svg>`,
  grip: '<svg viewBox="0 0 64 64"><rect x="25" y="6" width="15" height="30" rx="3" class="d-phone"/><rect x="28" y="9" width="9" height="20" rx="1" class="d-glass"/><rect x="19" y="27" width="28" height="21" rx="8" class="d-phone"/><path class="d-cut" d="M23 33h20M23 39h20M24 45h18"/><path class="d-body" d="M19 33q-3-9 6-12"/><rect x="24" y="47" width="18" height="13" class="d-phone"/></svg>',
  strap: '<svg viewBox="0 0 64 64"><rect x="10" y="8" width="16" height="28" rx="3" class="d-phone"/><rect x="13" y="11" width="10" height="18" rx="1" class="d-glass"/><path class="d-cord" d="M18 36c0 12 10 17 20 13"/><path class="d-body" d="M44 46 58 60"/><ellipse cx="42" cy="46" rx="6" ry="8" transform="rotate(-45 42 46)" class="d-band"/></svg>',
  space: '<svg viewBox="0 0 64 64"><circle cx="32" cy="32" r="25" class="d-ring d-dash"/><circle cx="32" cy="21" r="6" class="d-head"/><path d="M23 46l4-17h10l4 17z" class="d-phone"/><path class="d-arrow" d="M32 4v-1M60 32h1M4 32H3"/></svg>',
  hold: '<svg viewBox="0 0 64 64"><path class="d-motion" d="M8 44q4-22 22-30M14 52q6-18 18-24"/><g transform="rotate(25 40 34)"><rect x="33" y="8" width="14" height="26" rx="3" class="d-phone"/><rect x="28" y="26" width="24" height="18" rx="7" class="d-phone"/><path class="d-cut" d="M31 31h18M31 37h18"/><rect x="32" y="43" width="16" height="14" class="d-phone"/></g></svg>',
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
  let pendingMotion = false;
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
          <button type="button" class="mn-btn mn-quiet" data-act="howto">How to play</button>
          <button type="button" class="mn-btn mn-quiet" data-act="settings">Settings</button>
        </div>
      </div>
      <div class="mn-toast" role="status"></div>`);
    s.querySelector('.mn-epigraph p').textContent = `“${TEXT.epigraph}”`;
    s.querySelector('.mn-epigraph cite').textContent = TEXT.epigraphCite;
    bindSeg(s.querySelector('.mn-seg'), 'control');
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
        setSetting(f.key, !settings[f.key]);
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
        applySetting(f.key, val);
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
          <div class="mn-page" data-page="touch">
            <div class="mn-touch">
              <div class="mn-touch-fig">${DIAGRAMS.layout}</div>
              <ul class="mn-list">
                <li><b>Left thumb</b> moves with the stick; hold <b>Shield</b> to block.</li>
                <li><b>Swipe</b> fast on the right to slash that way; <b>tap</b> to stab; <b>drag slowly</b> to look.</li>
                <li><b>Bash</b>: tap Bash, or swipe up from the Shield.</li>
                <li><b>Throw</b>: with a spear in hand, hold Throw to wind up and release. Drag off it to cancel.</li>
              </ul>
            </div>
          </div>
          <div class="mn-page" data-page="keys">
            <div class="mn-keys">${KEYS.map(([k, d]) => `<div><kbd>${k}</kbd><span>${d}</span></div>`).join('')}</div>
          </div>
        </div>
      </div>`);
    bindTabs(s, settings.control === 'motion' ? 'motion' : 'touch');
  }

  function buildSafety() {
    screen('safety', 'mn-safety mn-dim mn-dark', `
      <div class="mn-panel mn-panel-wide">
        <div class="mn-meander"></div>
        <div class="mn-kicker">Motion controls</div>
        <h2 class="mn-h">Before you take up the phone</h2>
        <ul class="mn-safe">
          <li>${DIAGRAMS.grip}<span><b>Hold it firmly</b> in your sword hand.</span></li>
          <li>${DIAGRAMS.strap}<span><b>Use a wrist strap</b> if you have one.</span></li>
          <li>${DIAGRAMS.space}<span><b>Clear the space</b> around you: people, lamps, walls.</span></li>
          <li class="is-warn">${DIAGRAMS.hold}<span><b>Never let go when throwing.</b> A firm flick is enough.</span></li>
        </ul>
        <div class="mn-row-btns">
          <button type="button" class="mn-btn mn-primary" data-act="safety-accept">I understand</button>
          <button type="button" class="mn-btn mn-quiet" data-act="safety-touch">Use touch instead</button>
        </div>
      </div>`);
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

  // ---- settings application ----
  function applySetting(key, value) {
    settings[key] = value;
    switch (key) {
      case 'sensitivity':
        if (ctx.gestures && ctx.gestures.setSensitivity) ctx.gestures.setSensitivity(value);
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

  function setSetting(key, value) {
    applySetting(key, value);
    ctx.saveSettings();
    refreshFields();
  }

  function refreshFields() {
    for (const seg of root.querySelectorAll('.mn-seg')) {
      const key = seg.dataset.field;
      for (const b of seg.querySelectorAll('button')) {
        b.classList.toggle('is-on', String(settings[key]) === b.dataset.v);
        if (key === 'control' && b.dataset.v === 'motion') b.classList.toggle('is-na', motionSupported === false);
      }
    }
    for (const row of screens.settings.querySelectorAll('.mn-field[data-key]')) {
      const f = FIELDS.find((x) => x.key === row.dataset.key);
      if (f.type === 'toggle') {
        const on = !!settings[f.key];
        const t = row.querySelector('.mn-toggle');
        t.classList.toggle('is-on', on);
        t.setAttribute('aria-checked', String(on));
      } else if (f.type === 'range') {
        const input = row.querySelector('input');
        const val = Number.isFinite(+settings[f.key]) ? +settings[f.key] : f.min;
        input.value = String(val);
        row.querySelector('output').textContent = f.fmt(val);
        paintRange(input, f);
      }
      if (f.key === 'sensitivity' || f.key === 'gyroLook') row.classList.toggle('is-off', settings.control !== 'motion');
      if (f.key === 'aimAssist') row.classList.toggle('is-off', settings.control === 'motion');
    }
  }

  // ---- motion controls: safety → permission → support → fallback ----
  function chooseControl(v) {
    if (v !== 'motion') {
      pendingMotion = false;
      setSetting('control', 'touch');
      return;
    }
    if (!settings.safetyAccepted) {
      pendingMotion = true;
      push('safety');
      return;
    }
    enableMotion();
  }

  // Must run inside the click handler: iOS only grants motion from a user gesture.
  function enableMotion() {
    pendingMotion = false;
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
          toast(TEXT.motionOn);
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
    else if (ctx.waves && ctx.waves.day) k = ctx.waves.wave ? `Day ${roman(ctx.waves.day)} · Wave ${roman(ctx.waves.wave)}` : `Day ${roman(ctx.waves.day)}`;
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

  function act(name, b) {
    if (ctx.audio && ctx.audio.unlock) ctx.audio.unlock();
    switch (name) {
      case 'fight':
        sfx('uiConfirm');
        startMode('battle', { day: 1 });
        break;
      case 'continue':
        sfx('uiConfirm');
        startMode('battle', { day: clamp(loadBest().day | 0, 1, 3) });
        break;
      case 'agoge':
        sfx('uiConfirm');
        startMode('training', {});
        break;
      case 'settings':
        sfx('uiTap');
        push('settings');
        break;
      case 'howto':
        sfx('uiTap');
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
        else ctx.setMode(pausedFrom, { day: (ctx.waves && ctx.waves.day) || 1 });
        break;
      case 'quit':
        if (!confirmFirst(b)) return sfx('uiTap');
        sfx('uiTap');
        ctx.setMode('title');
        break;
      case 'again':
        sfx('uiConfirm');
        startMode('battle', { day: clamp((lastRun && lastRun.day) || 1, 1, 3) });
        break;
      case 'title':
        sfx('uiTap');
        ctx.setMode('title');
        break;
      case 'safety-read':
        sfx('uiTap');
        push('safety');
        break;
      case 'safety-accept':
        sfx('uiConfirm');
        settings.safetyAccepted = true;
        ctx.saveSettings();
        pop();
        if (pendingMotion) enableMotion();
        break;
      case 'safety-touch':
        sfx('uiTap');
        pendingMotion = false;
        pop();
        setSetting('control', 'touch');
        break;
      default:
        break;
    }
    return undefined;
  }

  /** Handles Android back / Esc. True when a menu consumed it. */
  function back() {
    if (stack.length > 1) {
      if (current() === 'safety') pendingMotion = false;
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

  window.addEventListener('keydown', (e) => {
    if (e.defaultPrevented || !current()) return;
    if (e.code === 'Escape' || (e.code === 'KeyP' && current() === 'pause')) {
      if (!back() && current() === 'pause') ctx.resume();
      e.preventDefault();
    }
  });

  return {
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
    },
    back,
    show,
  };
}
