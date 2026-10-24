// ui/hud.js: the in-battle HUD (#hud, z 10, pointer-events none). DESIGN.md §11 ui/*.
// Polls ctx.player and ctx.fx.cameraOverridden every frame; events drive only transient effects.
// DOM text changes only when its value changes, and only transform/opacity animate.
// The hilt grip (docs-hilt-grip.md §E.3, §E.4): the lock-on marker with the last-strike chip, the
// side arrows, the closing ring on the head, the portrait column and the coach toasts.
import { clamp, smoothstep } from '../core/math.js';
import { sensitivityKey } from '../core/grip.js';
import { GESTURE_DEFAULTS, GRIPS, SENSITIVITY_MIN, SENSITIVITY_MAX, slashThreshold } from '../motion/gestures.js';
// Namespace import: a renamed export degrades to "no fade" instead of breaking the module graph.
import * as campaign from '../game/campaign.js';

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII'];
/** 1 → 'I' … 12 → 'XII' (falls back to digits). */
export const roman = (n) => ROMAN[n] || String(n);

/** 12345 → '12,345' (locale-independent). */
export const groupDigits = (n) => String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

/** Seconds → 'm:ss'. */
export const clock = (s) => {
  const t = Math.max(0, Math.ceil(s));
  return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
};

/** How long a subtitle stays up for a line of text (seconds). */
export const subtitleSeconds = (text, greek) => clamp(1.8 + 0.055 * String(text || '').length, 2.4, 7) + (greek ? 0.8 : 0);

const HELMET = '<svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2C9 2 5 7.5 5 14v12c0 2 1.5 3.5 3.5 3.5H12V22l-3-1.5V15h5.5v13.5h3V15H23v5.5L20 22v7.5h3.5c2 0 3.5-1.5 3.5-3.5V14c0-6.5-4-12-11-12Z" fill="currentColor"/><path d="M5.5 10C3 6 6 1.5 10 1M26.5 10C29 6 26 1.5 22 1" stroke="currentColor" stroke-width="1.2" fill="none" opacity=".55"/></svg>';
const SPEAR = '<svg viewBox="0 0 10 40" aria-hidden="true"><path d="M5 1 7.4 9 5 11.5 2.6 9Z" fill="currentColor"/><path d="M5 11v25" stroke="currentColor" stroke-width="1.6"/><path d="M3.8 35.5h2.4V39H3.8z" fill="currentColor"/></svg>';
const CHEVRON = '<svg viewBox="-20 -20 40 40" aria-hidden="true"><path d="M-11-11 0-1 11-11M-11-2 0 8 11-2" fill="none" stroke="#120806" stroke-width="7" stroke-linejoin="miter"/><path d="M-11-11 0-1 11-11M-11-2 0 8 11-2" fill="none" stroke="currentColor" stroke-width="3.6" stroke-linejoin="miter"/></svg>';
// Hurt arc: a dark backing stroke under the red one keeps it reading as dark red over the sky.
const ARC = '<svg viewBox="-100 -100 200 200" aria-hidden="true"><path d="M-50.5-72.1A88 88 0 0 1 50.5-72.1" fill="none" stroke="url(#hud-arc-b)" stroke-width="13" stroke-linecap="round"/><path d="M-50.5-72.1A88 88 0 0 1 50.5-72.1" fill="none" stroke="url(#hud-arc-g)" stroke-width="9" stroke-linecap="round"/><path d="M-30-80.6A86 86 0 0 1 30-80.6" fill="none" stroke="#ffb59a" stroke-width="2" stroke-linecap="round" opacity=".55"/></svg>';

// The lock-on marker's off-screen arrow (points down, +y; turned toward the target).
const LOCK_ARROW = '<svg viewBox="-12 -12 24 24" aria-hidden="true"><path d="M-9-6 0 6 9-6Z" fill="currentColor" stroke="#120806" stroke-width="2.5" paint-order="stroke" stroke-linejoin="round"/></svg>';
// The sensor meter's thresholds per grip (the hilt profile overrides a few, motion/gestures.js).
const HILT_GESTURE = Object.freeze(Object.assign({}, GESTURE_DEFAULTS, GRIPS && GRIPS.hilt ? GRIPS.hilt.params : null));

const GREEK_LONG = 24; // a Greek subtitle longer than this is set tighter and smaller
const MAX_CHEV = 6;
const MAX_ARCS = 4;
const ARC_LIFE = 1.4;
const ARC_FLOOR = 0.4;       // the red stays at least this opaque…
const ARC_FLOOR_UNTIL = 1.2; // …until this age, then fades out over the rest of its life
const ARC_BACK = 2.2;        // the dark backing fades this much slower than the red (capped at 1)
const ARC_W0 = 9;            // red stroke width (viewBox units) when fresh…
const ARC_W1 = 3;            // …and at the end of its life

// Lock-on marker (docs-hilt-grip.md §E.4).
const LOCK_ARM = 14;         // px: a corner bracket's arm (the element's size)
const LOCK_MARGIN = 40;      // px: off screen, the marker clamps this far inside the edge
const STRIKE_WINDOW = 0.35;  // s (game time): a strike with no hit by then is a MISS…
const THROW_WINDOW = 2.5;    // s: …a throw's spear has this long to land
const CHIP_SETTLED = 0.8;    // s (real time): the chip stays until the phone has been at guard this long
const CHIP = ['', 'MISS', 'GUARDED', 'HIT', 'KILL']; // by rank
const CHIP_CLS = ['', 'is-miss', 'is-guard', 'is-hit', 'is-kill'];
// Coach toasts in the first hilt battles (before the hilt Agoge, once a session each) and the over-speed warning.
const COACH = Object.freeze({
  shield: 'Thumb on the glass: shield',
  swipe: 'Swipe: change foe',
  spear: 'Cock back behind your shoulder, then snap forward',
  overSpeed: 'Easy: a firm flick is enough',
});
const COACH_SECONDS = 4;
const OVERSPEED_GAP = 60;    // s (real time): the over-speed toast at most this often

// Portrait column (§E.3): each element's home is its CSS top (style.css's portrait block).
const COL_GAP = 8;           // px between neighbours in the column
const COL_GUARD = 0.40;      // share of the height: the column ends above the foe's head ring…
const COL_FLOOR = 0.72;      // …or, with no foe close, above the fist
const COL_OLD = 1, COL_COMBO = 2, COL_BANNER = 4, COL_NEW = 8, COL_VOLLEY = 16, COL_ALL = 31;
// What yields when the column does not fit, first to last: the older subtitle row, the title card,
// the combo popup (the player's own feat, briefly), the newest subtitle row (the volley warning never yields).
const COL_DROP = [COL_OLD, COL_BANNER, COL_COMBO, COL_NEW];

/**
 * The lock-on box for a target whose head-to-feet height projects to `hPx` (§E.4): width
 * clamp(0.45·h, 40, 160), height clamp(0.62·h, 56, 220). Writes `out.w`, `out.h`; returns `out`.
 */
export function lockBoxSize(hPx, out) {
  const h = Number.isFinite(hPx) ? Math.max(0, hPx) : 0;
  out.w = clamp(0.45 * h, 40, 160);
  out.h = clamp(0.62 * h, 56, 220);
  return out;
}

/**
 * Where an element of the portrait column goes: its home `top` (px), or just under `above` (the
 * bottom of the shown element above it, −Infinity for none), and below the respite row
 * [`rTop`, `rBot`) if it would cover it (pass rTop = Infinity when there is none). Returns the top.
 */
export function columnTop(top, height, above, rTop = Infinity, rBot = -Infinity, gap = COL_GAP) {
  let y = Math.max(top, above + gap);
  if (y < rBot && y + height > rTop) y = rBot + gap;
  return y;
}

/** Seconds of a Day 3 phase's fade (§13 Phase C: "A 2 s fade"), from the payload or campaign data. */
export function phaseFade(e) {
  if (!e) return 0;
  if (Number.isFinite(e.fade)) return Math.max(0, e.fade);
  const days = campaign && Array.isArray(campaign.DAYS) ? campaign.DAYS : [];
  for (const d of days) {
    if (d.day !== e.day || !Array.isArray(d.phases)) continue;
    for (const p of d.phases) if (p.id === e.phase) return Math.max(0, +p.fade || 0);
  }
  return 0;
}

/**
 * Opacity of a hurt arc's red stroke `age` seconds into its `life`: linear from `peak`, but held at
 * ARC_FLOOR or more until ARC_FLOOR_UNTIL (so the red never thins out over the sky), then down to 0.
 */
export function arcOpacity(age, life = ARC_LIFE, peak = 1) {
  if (!(age >= 0) || age >= life) return 0;
  const lin = peak * (1 - age / life);
  const until = Math.min(ARC_FLOOR_UNTIL, life);
  const floor = age < until ? ARC_FLOOR : ARC_FLOOR * clamp((life - age) / Math.max(1e-3, life - until), 0, 1);
  return Math.max(lin, floor);
}

/**
 * Opacity of the dark backing under the red: it fades slower than the red, so a faded arc darkens
 * the sky behind it and reads as dark red rather than red-over-blue mauve.
 */
export function arcBacking(age, life = ARC_LIFE, peak = 1) {
  return Math.min(1, ARC_BACK * arcOpacity(age, life, peak));
}

/** Width of the arc's red stroke (viewBox units): it thins from ARC_W0 to ARC_W1 as it fades. */
export function arcWidth(age, life = ARC_LIFE) {
  return ARC_W1 + (ARC_W0 - ARC_W1) * clamp(1 - age / life, 0, 1);
}

/**
 * Opacity of the black curtain `t` seconds into a fade that darkens over `a`, holds for `h`
 * and clears over `o` (smoothstep edges).
 */
export function curtainOpacity(t, a, h, o) {
  if (t < 0 || t >= a + h + o) return 0;
  if (t < a) return smoothstep(0, a, t);
  if (t < a + h) return 1;
  return 1 - smoothstep(a + h, a + h + o, t);
}

/**
 * Creates the HUD.
 * @param {object} ctx shared context (§4)
 * @returns {{ update(dt:number):void, reset():void, banner(text:string, sub?:string, seconds?:number):void }}
 */
export function createHud(ctx) {
  const THREE = ctx.THREE;
  const settings = ctx.settings;

  const root = document.createElement('div');
  root.id = 'hud';
  root.innerHTML = `
    <svg class="hud-defs" width="0" height="0" aria-hidden="true"><defs>
      <linearGradient id="hud-arc-g" x1="0" x2="1" y1="0" y2="0">
        <stop offset="0" stop-color="#c0261b" stop-opacity="0"/><stop offset=".3" stop-color="#d8331f"/>
        <stop offset=".5" stop-color="#ff5a36"/><stop offset=".7" stop-color="#d8331f"/><stop offset="1" stop-color="#c0261b" stop-opacity="0"/>
      </linearGradient>
      <linearGradient id="hud-arc-b" x1="0" x2="1" y1="0" y2="0">
        <stop offset="0" stop-color="#140000" stop-opacity="0"/><stop offset=".3" stop-color="#140000" stop-opacity=".75"/>
        <stop offset=".7" stop-color="#140000" stop-opacity=".75"/><stop offset="1" stop-color="#140000" stop-opacity="0"/>
      </linearGradient></defs></svg>
    <div class="hud-curtain"></div>
    <div class="hud-letterbox"><i class="hud-lb-top"></i><i class="hud-lb-bot"></i></div>
    <div class="hud-arcs"></div>
    <div class="hud-reticle"></div>
    <div class="hud-top hud-top-l">
      <div class="hud-health"><span class="hud-helmet">${HELMET}</span>
        <div class="hud-hp"><i class="hud-hp-trail"></i><i class="hud-hp-fill"></i><i class="hud-hp-ticks"></i></div></div>
      <div class="hud-spears"></div>
    </div>
    <div class="hud-top hud-top-r">
      <div class="hud-score"><b class="hud-score-n">0</b><span class="hud-chain">×1.0</span></div>
      <div class="hud-meta"><span class="hud-kills"><b>0</b> slain</span><span class="hud-wave"></span></div>
      <div class="hud-toasts"></div>
    </div>
    <div class="hud-volley"><span class="hud-volley-t">Shields up!</span><span class="hud-volley-s"></span></div>
    <div class="hud-combo"></div><div class="hud-combo"></div>
    <div class="hud-banner"><div class="hud-banner-k"></div><div class="hud-banner-t"></div><div class="hud-banner-rule"></div><div class="hud-banner-s"></div></div>
    <div class="hud-focus"><i class="hud-focus-hp"><b></b></i></div>
    <div class="hud-lock"><div class="hud-lock-in">
      <i class="hud-lock-c hud-lock-tl"><b class="hud-lock-res"></b></i><i class="hud-lock-c hud-lock-tr"></i><i class="hud-lock-c hud-lock-bl"></i><i class="hud-lock-c hud-lock-br"></i>
      <i class="hud-lock-hp"><b></b></i><i class="hud-lock-arrow">${LOCK_ARROW}</i></div></div>
    <i class="hud-lock-more hud-lock-more-l">‹</i><i class="hud-lock-more hud-lock-more-r">›</i>
    <div class="hud-chevs"></div>
    <div class="hud-subs"><div class="hud-sub"><span class="hud-sub-g"></span><span class="hud-sub-l"><b></b><span></span></span></div><div class="hud-sub"><span class="hud-sub-g"></span><span class="hud-sub-l"><b></b><span></span></span></div></div>
    <div class="hud-respite"><div class="hud-respite-l"><span>Respite</span><b class="hud-respite-t">0:15</b></div>
      <button class="hud-ready" data-hit type="button"><span>Ready</span><i class="hud-ready-lock"></i></button></div>
    <div class="hud-debug"><div class="hud-dbg-row"><span>rate</span><i><b class="hud-dbg-m"></b><em class="hud-dbg-m1"></em><em class="hud-dbg-m2"></em></i></div>
      <div class="hud-dbg-row"><span>push</span><i><b class="hud-dbg-f"></b><em class="hud-dbg-f1"></em></i></div>
      <div class="hud-dbg-row"><span>β</span><i><b class="hud-dbg-b"></b><em class="hud-dbg-b1"></em></i></div>
      <div class="hud-dbg-txt"></div></div>`;
  ctx.overlay.appendChild(root);
  const q = (s) => root.querySelector(s);

  // ---- element refs ----
  const hpFill = q('.hud-hp-fill');
  const hpTrail = q('.hud-hp-trail');
  const health = q('.hud-health');
  const spearsEl = q('.hud-spears');
  const spearIcons = [];
  for (let i = 0; i < 4; i++) {
    const s = document.createElement('i');
    s.className = 'hud-spear';
    s.innerHTML = SPEAR;
    spearsEl.appendChild(s);
    spearIcons.push(s);
  }
  const scoreN = q('.hud-score-n');
  const chainEl = q('.hud-chain');
  const killsN = q('.hud-kills b');
  const waveEl = q('.hud-wave');
  const toastsEl = q('.hud-toasts');
  const topR = q('.hud-top-r');
  const volleyEl = q('.hud-volley');
  const combos = root.querySelectorAll('.hud-combo');
  const bannerEl = q('.hud-banner');
  const bannerK = q('.hud-banner-k');
  const bannerT = q('.hud-banner-t');
  const bannerS = q('.hud-banner-s');
  const subRows = Array.from(root.querySelectorAll('.hud-sub')).map((el) => ({
    el, g: el.querySelector('.hud-sub-g'), name: el.querySelector('b'), text: el.querySelector('.hud-sub-l span'), left: 0,
  }));
  const respiteEl = q('.hud-respite');
  const respiteT = q('.hud-respite-t');
  const readyBtn = q('.hud-ready');
  const readyLock = q('.hud-ready-lock');
  const curtainEl = q('.hud-curtain');
  const focusEl = q('.hud-focus');
  const focusHp = q('.hud-focus-hp b');
  const debugEl = q('.hud-debug');
  const dbg = {
    m: q('.hud-dbg-m'), m1: q('.hud-dbg-m1'), m2: q('.hud-dbg-m2'),
    f: q('.hud-dbg-f'), f1: q('.hud-dbg-f1'), b: q('.hud-dbg-b'), b1: q('.hud-dbg-b1'), txt: q('.hud-dbg-txt'),
  };
  const subsEl = q('.hud-subs');
  const lockEl = q('.hud-lock');
  const lockCorners = [q('.hud-lock-tl'), q('.hud-lock-tr'), q('.hud-lock-bl'), q('.hud-lock-br')];
  const lockHp = q('.hud-lock-hp');
  const lockHpFill = q('.hud-lock-hp b');
  const lockArrow = q('.hud-lock-arrow');
  const lockRes = q('.hud-lock-res');
  const lockMoreL = q('.hud-lock-more-l');
  const lockMoreR = q('.hud-lock-more-r');

  const arcs = [];
  const arcsEl = q('.hud-arcs');
  for (let i = 0; i < MAX_ARCS; i++) {
    const el = document.createElement('div');
    el.className = 'hud-arc';
    el.innerHTML = ARC;
    arcsEl.appendChild(el);
    const [back, red, hi] = el.querySelectorAll('path');
    arcs.push({ el, back, red, hi, age: 9, life: ARC_LIFE, peak: 1, lastOp: -1, lastW: ARC_W0 });
  }
  const chevs = [];
  const chevsEl = q('.hud-chevs');
  for (let i = 0; i < MAX_CHEV; i++) {
    const el = document.createElement('div');
    el.className = 'hud-chev';
    el.innerHTML = `<i class="hud-chev-ring"></i><span class="hud-chev-arrow">${CHEVRON}</span>`;
    chevsEl.appendChild(el);
    chevs.push({ el, ring: el.firstChild, arrow: el.lastChild, actor: null, t0: 0, tts: 1, hot: false, off: null, shown: false, tf: '', rf: '', sf: '' });
  }
  const toasts = [];
  for (let i = 0; i < 3; i++) {
    const el = document.createElement('div');
    el.className = 'hud-toast';
    toastsEl.appendChild(el);
    toasts.push({ el, left: 0 });
  }

  // ---- state ----
  const last = {};
  let visible = null;
  let cinematic = null;
  let vw = window.innerWidth;
  let vh = window.innerHeight;
  window.addEventListener('resize', () => {
    vw = window.innerWidth;
    vh = window.innerHeight;
    portrait = vh > vw;
    col.dirty = true;
  });

  let bannerLeft = 0;
  let bannerKind = '';
  const bannerQueue = [];
  let comboIdx = 0;
  const comboLeft = [0, 0];
  let volleyLeft = 0;
  let volleyRaised = null;
  let respite = { on: false, left: 0, elapsed: 0, armed: false, shownSec: -1, locked: null, lockShown: -1 };
  let hurt = 0;
  let hbPhase = 0;
  const overlay = { vignette: 0, hurt: 0, helmet: false };
  const sent = { vignette: -1, hurt: -1, helmet: null };
  let dbgClock = 0;
  let daysSeen = 0;             // dayStart cards since the last reset (the first one needs no curtain)
  const curtain = { t: -1, a: 0, h: 0, o: 0, shown: 0 };
  let focusShown = false;
  let focusTf = '';
  let focusHpShown = -1;
  const v = new THREE.Vector3();
  // Lock-on marker: what is written now (rounded px), so the DOM changes only on change.
  const lock = { shown: false, x: NaN, y: NaN, w: -1, h: -1, off: null, windup: null, hpOn: null, hpShown: -1, rot: NaN, newLeft: 0, newPending: false };
  const box = { w: 0, h: 0 };
  // The player's last strike (§E.4 chip): rank 0 none … 4 KILL (CHIP); until: game time of a MISS.
  const strike = { until: -1, rank: 0, shown: 0, settled: 0 };
  // Coach toasts already shown this session (not reset per battle), and the last over-speed toast.
  const coached = { shield: false, swipe: false, spear: false };
  let overSpeedAt = -Infinity;
  // Portrait column: the push (px) written to each element's --push, and a pending re-layout.
  const col = {
    dirty: true, guard: false, subs: 0, volley: 0, banner: 0, combo0: 0, combo1: 0,
    cOld: false, cNew: false, cVolley: false, cBanner: false, cCombo0: false, cCombo1: false,
    toastB: 0, hNew: 0, hOld: 0, hVolley: 0, hBanner: 0, hCombo0: 0, hCombo1: 0, rTop: 0, rBot: 0,
  };
  let chevOn = false;         // a wind-up chevron is on screen (this frame)
  let portrait = vh > vw;

  // ---- helpers ----
  function setText(el, key, text) {
    if (last[key] === text) return;
    last[key] = text;
    el.textContent = text;
  }
  function setClass(el, key, cls, on) {
    if (last[key] === on) return;
    last[key] = on;
    el.classList.toggle(cls, on);
  }

  function showBanner(kicker, title, sub, seconds, kind) {
    if (bannerLeft > 1.0 && bannerKind === 'card' && kind !== 'card') {
      if (bannerQueue.length < 3) bannerQueue.push([kicker, title, sub, seconds, kind]);
      return;
    }
    bannerK.textContent = kicker || '';
    bannerT.textContent = title || '';
    bannerS.textContent = sub || '';
    bannerEl.classList.toggle('is-small', kind !== 'card');
    bannerEl.classList.toggle('is-quote', kind === 'card' && String(title || '').length > 34);
    bannerEl.classList.toggle('no-sub', !sub);
    // Restart the entrance: drop the class this frame, add it back on the next update.
    bannerEl.classList.remove('is-shown');
    bannerLeft = Math.max(1, seconds || 3);
    bannerKind = kind;
    last.bannerIn = false;
    col.dirty = true;
  }

  function hideTransients() {
    bannerLeft = 0;
    bannerQueue.length = 0;
    bannerEl.classList.remove('is-shown');
    comboLeft[0] = comboLeft[1] = 0;
    combos[0].classList.remove('is-shown');
    combos[1].classList.remove('is-shown');
    volleyLeft = 0;
    volleyEl.classList.remove('is-shown');
    endRespite();
    for (const r of subRows) {
      r.left = 0;
      r.el.classList.remove('is-shown');
    }
    for (const a of arcs) {
      a.age = 9;
      a.el.style.opacity = '0';
      a.lastOp = 0;
    }
    for (const c of chevs) releaseChev(c);
    for (const t of toasts) {
      t.left = 0;
      t.el.classList.remove('is-shown');
    }
    hurt = 0;
    clearCurtain();
    strike.until = -1;
    strike.rank = 0;
    col.dirty = true;
  }

  function setArcWidth(a, w) {
    a.lastW = w;
    a.red.setAttribute('stroke-width', String(w));
    a.back.setAttribute('stroke-width', String(w + 4));
    a.hi.setAttribute('stroke-width', String(Math.round(w * 2 / ARC_W0 * 4) / 4));
  }

  function endRespite() {
    if (respite.on) col.dirty = true;
    respite.on = false;
    respite.armed = false;
    respiteEl.classList.remove('is-shown');
    readyBtn.classList.remove('is-shown', 'is-armed');
  }

  function toast(text, cls, seconds = 1.8) {
    let pick = toasts[0];
    for (const t of toasts) if (t.left < pick.left) pick = t;
    pick.el.textContent = text;
    pick.el.className = `hud-toast ${cls || ''}`;
    // Newest on top: move it to the front of the column.
    toastsEl.insertBefore(pick.el, toastsEl.firstChild);
    pick.left = seconds;
    requestShow(pick.el);
    col.dirty = true;
  }

  // Coach toasts (docs-hilt-grip.md §A.6): in a hilt battle before the hilt Agoge is done, each
  // once a session (a "Fight now" is remembered for the session only, too).
  function coach(key) {
    if (coached[key] || ctx.mode !== 'battle' || ctx.input.grip !== 'hilt' || settings.hiltAgogeDone === true) return;
    coached[key] = true;
    toast(COACH[key], 'is-coach', COACH_SECONDS);
  }

  // Elements whose entrance animation must restart get 'is-shown' on the next update.
  const pendingShow = [];
  function requestShow(el) {
    el.classList.remove('is-shown');
    pendingShow.push(el);
  }

  function releaseChev(c) {
    c.actor = null;
    if (c.shown) {
      c.shown = false;
      c.el.classList.remove('is-shown', 'is-hot', 'is-off');
      c.hot = false;
      c.off = null;
    }
  }

  /** Fades to black and back (world changes hide behind it; waves waits ~1 s for the card). */
  function startCurtain(a, h, o) {
    curtain.t = 0;
    curtain.a = a;
    curtain.h = h;
    curtain.o = o;
  }
  function clearCurtain() {
    curtain.t = -1;
    if (curtain.shown !== 0) {
      curtain.shown = 0;
      curtainEl.style.opacity = '0';
    }
  }

  // ---- events (transient effects only) ----
  const ev = ctx.events;
  ev.on('playerHurt', (e) => {
    const dmg = (e && e.damage) || 8;
    hurt = Math.max(hurt, clamp(0.45 + dmg / 25, 0.5, 1));
    if (e && Number.isFinite(e.relYaw)) {
      let a = arcs[0];
      for (const x of arcs) if (x.age > a.age) a = x;
      a.age = 0;
      a.peak = clamp(0.55 + dmg / 30, 0.6, 1);
      // Rotated by −relYaw from screen-top: a source on the left (relYaw > 0) lights the left side.
      a.el.style.transform = `rotate(${(-e.relYaw).toFixed(3)}rad)`;
    }
  });

  ev.on('enemyWindup', (e) => {
    if (!e || !e.targetIsPlayer || !e.actor) return;
    let c = null;
    for (const x of chevs) if (x.actor === e.actor) c = x;
    if (!c) for (const x of chevs) if (!x.actor) { c = x; break; }
    if (!c) {
      c = chevs[0];
      for (const x of chevs) if (x.t0 < c.t0) c = x;
    }
    c.actor = e.actor;
    c.t0 = ctx.time.gnow;
    c.tts = Math.max(0.2, e.timeToStrike || 0.8);
    coach('shield');
  });

  ev.on('combo', (row) => {
    if (!row) return;
    const el = combos[comboIdx];
    comboLeft[comboIdx ^ 1] = Math.min(comboLeft[comboIdx ^ 1], 0.01);
    el.innerHTML = '<div class="hud-combo-in"><span class="hud-combo-n"></span><span class="hud-combo-b"></span></div>';
    el.querySelector('.hud-combo-n').textContent = row.name || '';
    el.querySelector('.hud-combo-b').textContent = row.bonus ? `+${row.bonus}` : '';
    el.classList.toggle('is-big', !!(row.slowMo || (row.seq && row.seq.length >= 3)));
    requestShow(el);
    comboLeft[comboIdx] = 1.7;
    comboIdx ^= 1;
    col.dirty = true;
  });

  ev.on('kill', (e) => {
    if (!e || !e.byPlayer) return;
    if (e.cause === 'sea') toast('Into the Gulf  +150', 'is-sea');
    else if (e.spear) toast('Spear kill  +250', 'is-spear');
  });
  ev.on('block', (e) => {
    if (e && e.by === 'player' && e.perfect) toast('Perfect block  +50', 'is-perfect');
    else if (e && e.by === 'player' && e.kind === 'guardBreak') toast('Guard broken', 'is-break');
  });

  const dayKicker = (e) => (e && e.day ? `Day ${roman(e.day)}` : '');
  const cardSeconds = (e, def) => (e && Number.isFinite(e.seconds) && e.seconds > 0 ? e.seconds : def);
  ev.on('dayStart', (e) => {
    endRespite();
    // A later day (or dusk) changes the light behind the card: cover the view while it does.
    if (daysSeen++ > 0) startCurtain(0.5, 1.2, 1.1);
    showBanner(dayKicker(e), (e && e.title) || dayKicker(e), e && e.subtitle, cardSeconds(e, 4.5), 'card');
  });
  ev.on('phase', (e) => {
    endRespite();
    const fade = phaseFade(e);
    if (fade > 0) startCurtain(fade * 0.3, fade * 0.3, fade * 0.4);
    showBanner(dayKicker(e), e && e.title, e && e.subtitle, cardSeconds(e, 4), 'card');
  });
  ev.on('waveStart', (e) => {
    endRespite();
    showBanner(dayKicker(e), (e && e.title) || (e && e.wave ? `Wave ${roman(e.wave)}` : 'They come'), e && e.subtitle, Math.min(cardSeconds(e, 2.6), 3.2), 'small');
  });
  ev.on('waveCleared', (e) => {
    showBanner(dayKicker(e), (e && e.title) || 'The wave breaks', e && e.subtitle, Math.min(cardSeconds(e, 2.4), 3.2), 'small');
  });
  ev.on('respite', (e) => {
    respite.on = true;
    respite.left = (e && e.seconds) || 15;
    respite.elapsed = 0;
    respite.armed = false;
    respite.shownSec = -1;
    respite.locked = null;
    respite.lockShown = -1;
    respiteEl.classList.add('is-shown');
    readyBtn.classList.add('is-shown');
    readyBtn.classList.remove('is-armed');
    col.dirty = true;
    if ((e && e.title) || (e && e.subtitle)) showBanner(dayKicker(e), (e && e.title) || 'Respite', e && e.subtitle, 3, 'small');
  });
  ev.on('gameOver', hideTransients);
  // The player falls: clear the transients (a day card would still welcome them) and fade the HUD out.
  ev.on('playerDeath', () => {
    hideTransients();
    root.classList.add('is-fallen');
  });

  ev.on('voice', (e) => {
    if (!e || !e.text) return;
    const narrator = !e.speaker || e.speaker === 'Narrator';
    // The Agoge panel already shows the Narrator's instruction.
    if (narrator && ctx.training && ctx.training.active) return;
    const [a, b] = subRows; // a: upper, b: lower (newest)
    if (b.left > 0) {
      a.g.textContent = b.g.textContent;
      a.name.textContent = b.name.textContent;
      a.text.textContent = b.text.textContent;
      a.el.classList.toggle('has-g', b.el.classList.contains('has-g'));
      a.el.classList.toggle('g-long', b.el.classList.contains('g-long'));
      a.el.classList.toggle('no-name', b.el.classList.contains('no-name'));
      a.left = Math.min(b.left, 2.2);
      a.el.classList.add('is-shown');
    }
    b.g.textContent = e.greek || '';
    b.name.textContent = narrator ? '' : e.speaker;
    b.text.textContent = e.text;
    b.el.classList.toggle('has-g', !!e.greek);
    b.el.classList.toggle('g-long', String(e.greek || '').length > GREEK_LONG);
    b.el.classList.toggle('no-name', narrator);
    // A recorded line stays up at least as long as it is spoken.
    const spoken = e.clip && e.clip.dur > 0 ? e.clip.dur + 0.8 : 0;
    b.left = Math.max(subtitleSeconds(e.text, e.greek), spoken);
    requestShow(b.el);
    col.dirty = true;
  });

  ev.on('volley', (e) => {
    const ph = e && e.phase;
    if (ph === 'warning') {
      volleyLeft = 6;
      volleyRaised = null;
      requestShow(volleyEl);
      col.dirty = true;
    } else if (ph === 'impact') {
      volleyLeft = Math.min(volleyLeft, 0.9);
    } else if (ph === 'end') {
      volleyLeft = 0;
    }
  });

  // Lock-on (hilt): a new target scales the marker in (is-new, 150 ms).
  ev.on('lockOn', (e) => {
    if (!e || !e.target || e.target === e.prev) return;
    lock.newPending = true;
    lockEl.classList.remove('is-new');
  });

  // The last strike (§E.4): a player swing opens a window; the best byPlayer hit in it (KILL > HIT >
  // GUARDED) is the result, none is a MISS. A throw waits for its spear to land.
  ev.on('swing', (e) => {
    const throwing = !!(e && e.kind === 'throw');
    strike.until = ctx.time.gnow + (throwing ? THROW_WINDOW : Math.max(STRIKE_WINDOW, ((e && e.strikeIn) || 0) + 0.1));
    strike.rank = 0;
  });
  function strikeResult(rank) {
    if (rank <= strike.rank) return;
    strike.rank = rank;
    strike.settled = 0;
  }
  ev.on('hit', (e) => {
    if (!e || !e.byPlayer || strike.until < 0 || ctx.time.gnow > strike.until) return;
    strikeResult(e.killed ? 4 : e.guarded ? 2 : 3);
  });
  ev.on('spearImpact', (e) => {
    if (strike.until < 0 || !e || e.target) return;
    strike.until = -1;
    strikeResult(1);
  });
  // Over-speed (hilt battles): any gesture flagged fast, the end-of-swing 'overSpeed' notice included.
  ev.on('gesture', (e) => {
    if (!e || !e.fast || ctx.mode !== 'battle' || ctx.input.grip !== 'hilt') return;
    if (ctx.time.now - overSpeedAt < OVERSPEED_GAP) return;
    overSpeedAt = ctx.time.now;
    toast(COACH.overSpeed, 'is-coach', COACH_SECONDS);
  });

  readyBtn.addEventListener('click', () => {
    if (!respite.on) return;
    if (respite.elapsed >= 6) callReady();
    else {
      respite.armed = true;
      readyBtn.classList.add('is-armed');
    }
    if (ctx.audio && ctx.audio.play) ctx.audio.play('uiConfirm');
  });
  function callReady() {
    respite.armed = false;
    if (ctx.waves && typeof ctx.waves.ready === 'function') ctx.waves.ready();
    readyBtn.classList.add('is-armed');
  }

  // ---- per-frame pieces ----
  function project(actor, lift, out) {
    const p = actor.pos;
    return projectPoint(p.x, p.y + (actor.height || 1.75) + lift, p.z, out);
  }
  function projectPoint(x, y, z, out) {
    const cam = ctx.camera;
    v.set(x, y, z);
    v.applyMatrix4(cam.matrixWorldInverse);
    const cz = v.z;
    if (cz < -0.15) {
      v.applyMatrix4(cam.projectionMatrix);
      out.x = (v.x + 1) * 0.5 * vw;
      out.y = (1 - v.y) * 0.5 * vh;
      out.front = true;
    } else {
      // Behind the camera: map the bearing so 'behind' is the bottom of the screen.
      out.x = vw * 0.5 + v.x * 1000;
      out.y = vh * 0.5 + Math.max(cz, 0.2) * 1000;
      out.front = false;
    }
    return out;
  }
  const pp = { x: 0, y: 0, front: false };
  const pp2 = { x: 0, y: 0, front: false };

  function updateChevrons() {
    const gnow = ctx.time.gnow;
    // Hilt portrait: the closing ring sits on the head, below the banners and the collapsed Agoge
    // panel and above the thumb (§E.3); the edge margin is 40 px there.
    const onHead = portrait && ctx.input.grip === 'hilt';
    const m = portrait ? 40 : 46;
    const lift = onHead ? -0.12 : 0.5;
    chevOn = false;
    for (const c of chevs) {
      const a = c.actor;
      if (!a) continue;
      const age = gnow - c.t0;
      if (!a.alive || age > c.tts + 0.25 || (a.state && a.state !== 'windup' && age > 0.15)) {
        releaseChev(c);
        continue;
      }
      project(a, lift, pp);
      const onScreen = pp.front && pp.x > m && pp.x < vw - m && pp.y > m && pp.y < vh - m;
      let x = pp.x, y = pp.y, rot = 0;
      if (!onScreen) {
        const dx = pp.x - vw / 2;
        const dy = pp.y - vh / 2;
        const hx = vw / 2 - m, hy = vh / 2 - m;
        const s = Math.min(hx / Math.max(Math.abs(dx), 1e-3), hy / Math.max(Math.abs(dy), 1e-3));
        x = vw / 2 + dx * s;
        y = vh / 2 + dy * s;
        rot = Math.atan2(dy, dx) - Math.PI / 2; // the glyph points down (+y); turn it toward the actor
      }
      const left = Math.max(0, c.tts - age);
      const tf = `translate(${x.toFixed(1)}px,${y.toFixed(1)}px)`;
      const rf = `rotate(${rot.toFixed(3)}rad)`;
      if (tf !== c.tf) c.el.style.transform = c.tf = tf;
      if (rf !== c.rf) c.arrow.style.transform = c.rf = rf;
      // The ring closes on the glyph at the strike moment (perfect-block cue).
      const sf = `scale(${(1 + 1.6 * clamp(left / c.tts, 0, 1)).toFixed(3)})`;
      if (sf !== c.sf) c.ring.style.transform = c.sf = sf;
      const hot = left < 0.3;
      if (hot !== c.hot) {
        c.hot = hot;
        c.el.classList.toggle('is-hot', hot);
      }
      if (c.off !== !onScreen) {
        c.off = !onScreen;
        c.el.classList.toggle('is-off', !onScreen);
      }
      if (!c.shown) {
        c.shown = true;
        c.el.classList.add('is-shown');
      }
      if (onScreen) chevOn = true;
    }
  }

  function updateFocus(p) {
    const t = p && p.focusTarget;
    // The hilt grip's lock-on marker carries the foe's health instead (§E.4).
    const show = !!(t && t.alive && t.team !== 'greek' && t.role !== 'dummy' && Number.isFinite(t.maxHp) && t.maxHp > 0) && !cinematic && ctx.input.grip !== 'hilt';
    if (show) {
      project(t, 0.22, pp);
      if (!pp.front || pp.x < 0 || pp.x > vw || pp.y < 0 || pp.y > vh) {
        if (focusShown) {
          focusShown = false;
          focusEl.classList.remove('is-shown');
        }
        return;
      }
      const tf = `translate(${pp.x.toFixed(1)}px,${pp.y.toFixed(1)}px)`;
      if (tf !== focusTf) focusEl.style.transform = focusTf = tf;
      const f = Math.round(clamp(t.hp / t.maxHp, 0, 1) * 100);
      if (f !== focusHpShown) {
        focusHpShown = f;
        focusHp.style.transform = `scaleX(${f / 100})`;
      }
    }
    if (show !== focusShown) {
      focusShown = show;
      focusEl.classList.toggle('is-shown', show);
    }
  }

  /**
   * The lock-on marker (§E.4): four corner brackets around the locked foe, his health above them and
   * the last-strike chip under the top-left one. Each piece is moved by its own translate3d, so the
   * box resizes without layout; every string is written only when its rounded value changes.
   */
  function updateLock(p, dt) {
    const hilt = ctx.input.grip === 'hilt' && !cinematic && !!p;
    const t = hilt ? p.lockTarget : null;
    const show = !!(t && t.alive !== false && t.pos);
    if (show) {
      const pos = t.pos;
      const ht = t.height || 1.75;
      projectPoint(pos.x, pos.y + 0.62 * ht, pos.z, pp);
      let x = pp.x, y = pp.y, rot = NaN;
      const m = LOCK_MARGIN;
      const onScreen = pp.front && x > m && x < vw - m && y > m && y < vh - m;
      if (onScreen) {
        // The projected head-to-feet height sizes the box.
        projectPoint(pos.x, pos.y + ht, pos.z, pp2);
        const head = pp2.y;
        projectPoint(pos.x, pos.y, pos.z, pp2);
        lockBoxSize(pp2.y - head, box);
      } else {
        // Off screen (during a turn): clamped to the edge, an arrow toward him (the chevron maths).
        const dx = x - vw / 2, dy = y - vh / 2;
        const s = Math.min((vw / 2 - m) / Math.max(Math.abs(dx), 1e-3), (vh / 2 - m) / Math.max(Math.abs(dy), 1e-3));
        x = vw / 2 + dx * s;
        y = vh / 2 + dy * s;
        rot = Math.round((Math.atan2(dy, dx) - Math.PI / 2) * 50) / 50;
        lockBoxSize(0, box);
      }
      const rx = Math.round(x), ry = Math.round(y);
      if (rx !== lock.x || ry !== lock.y) {
        lock.x = rx;
        lock.y = ry;
        lockEl.style.transform = `translate3d(${rx}px,${ry}px,0)`;
      }
      const w = Math.round(box.w / 2) * 2, h = Math.round(box.h / 2) * 2;
      if (w !== lock.w || h !== lock.h) {
        lock.w = w;
        lock.h = h;
        const l = -w / 2, r = w / 2 - LOCK_ARM, tp = -h / 2, b = h / 2 - LOCK_ARM;
        lockCorners[0].style.transform = `translate3d(${l}px,${tp}px,0)`;
        lockCorners[1].style.transform = `translate3d(${r}px,${tp}px,0)`;
        lockCorners[2].style.transform = `translate3d(${l}px,${b}px,0)`;
        lockCorners[3].style.transform = `translate3d(${r}px,${b}px,0)`;
        // His health sits above the box, between his head and the bracket (the bottom is behind the fist).
        lockHp.style.transform = `translate3d(-50%,${tp - 10}px,0)`;
      }
      const off = !onScreen;
      if (off !== lock.off) {
        lock.off = off;
        lockEl.classList.toggle('is-off', off);
      }
      if (off && rot !== lock.rot) {
        lock.rot = rot;
        lockArrow.style.transform = `rotate(${rot}rad)`;
      }
      const windup = t.state === 'windup' && !!t.targetIsPlayer;
      if (windup !== lock.windup) {
        lock.windup = windup;
        lockEl.classList.toggle('is-windup', windup);
      }
      const hpOn = t.role !== 'dummy' && Number.isFinite(t.maxHp) && t.maxHp > 0;
      if (hpOn !== lock.hpOn) {
        lock.hpOn = hpOn;
        lockEl.classList.toggle('has-hp', hpOn);
      }
      if (hpOn) {
        const f = Math.round(clamp(t.hp / t.maxHp, 0, 1) * 100);
        if (f !== lock.hpShown) {
          lock.hpShown = f;
          lockHpFill.style.transform = `scaleX(${f / 100})`;
        }
      }
    }
    if (show !== lock.shown) {
      lock.shown = show;
      lockEl.classList.toggle('is-shown', show);
    }
    // is-new restarts on every new target: dropped by the lockOn handler, added back here.
    if (lock.newPending) {
      lock.newPending = false;
      lock.newLeft = 0.15;
      lockEl.classList.add('is-new');
    } else if (lock.newLeft > 0) {
      lock.newLeft -= dt;
      if (lock.newLeft <= 0) lockEl.classList.remove('is-new');
    }
    // A swipe has somewhere to go: the side arrows.
    const sides = hilt ? p.lockSides : null;
    setClass(lockMoreL, 'lockMoreL', 'is-shown', !!(sides && sides.left));
    setClass(lockMoreR, 'lockMoreR', 'is-shown', !!(sides && sides.right));
    updateChip(dt);
  }

  // The last-strike chip: shown once the strike resolves, until the phone has been at guard for
  // CHIP_SETTLED (real time), so the result is still there when the player looks again.
  function updateChip(dt) {
    if (strike.until >= 0 && ctx.time.gnow > strike.until) {
      strike.until = -1;
      if (strike.rank === 0) strikeResult(1);
    }
    if (strike.rank > 0) {
      strike.settled = ctx.input.phoneSettled ? strike.settled + dt : 0;
      if (strike.settled >= CHIP_SETTLED && strike.until < 0) strike.rank = 0;
    }
    if (strike.rank !== strike.shown) {
      if (strike.shown) lockRes.classList.remove(CHIP_CLS[strike.shown]);
      strike.shown = strike.rank;
      lockRes.textContent = CHIP[strike.rank];
      if (strike.rank) lockRes.classList.add(CHIP_CLS[strike.rank]);
      lockRes.classList.toggle('is-shown', strike.rank > 0);
    }
  }

  // The coach toasts that wait for a state rather than an event.
  function updateCoach(p) {
    if (!p || ctx.mode !== 'battle' || ctx.input.grip !== 'hilt' || settings.hiltAgogeDone === true) return;
    if (!coached.swipe && p.lockTarget && p.lockSides && (p.lockSides.left || p.lockSides.right)) coach('swipe');
    if (!coached.spear && p.weapon === 'spear' && p.hasSpearInHand) coach('spear');
  }

  /**
   * The portrait column (§E.3): under the toasts, the subtitles, the volley warning, the title card
   * and the combo popup each have a home (their CSS top); one that is shown pushes the next down so
   * they never overlap, and none covers the respite row (it goes below). The column ends above a
   * floor: the foe's head ring while one is locked or winding up on screen (COL_GUARD), else above
   * the fist (COL_FLOOR). What does not fit yields, least important first (COL_DROP): it fades out
   * until there is room again. It runs only when something in the column shows or hides
   * (col.dirty), measures what is shown then (offsetTop ignores the transforms), and moves the
   * elements by a transform (--push).
   */
  function layoutColumn() {
    col.dirty = false;
    const on = portrait;
    col.toastB = -Infinity;
    if (on) {
      // (a toast's offsetParent is the positioned .hud-top-r, so its offsetTop includes the list's)
      for (const t of toasts) if (t.left > 0) col.toastB = Math.max(col.toastB, topR.offsetTop + t.el.offsetTop + t.el.offsetHeight);
    }
    col.hNew = on && subRows[1].left > 0 ? subRows[1].el.offsetHeight : 0;
    col.hOld = on && subRows[0].left > 0 ? subRows[0].el.offsetHeight : 0;
    col.hVolley = on && volleyLeft > 0 ? volleyEl.offsetHeight : 0;
    col.hBanner = on && bannerLeft > 0 ? bannerEl.offsetHeight : 0;
    col.hCombo0 = on && comboLeft[0] > 0 ? combos[0].offsetHeight : 0;
    col.hCombo1 = on && comboLeft[1] > 0 ? combos[1].offsetHeight : 0;
    col.rTop = on && respite.on ? respiteEl.offsetTop : Infinity;
    col.rBot = on && respite.on ? col.rTop + respiteEl.offsetHeight : -Infinity;
    const floor = (col.guard ? COL_GUARD : COL_FLOOR) * vh;
    let keep = COL_ALL;
    for (let i = 0; i < COL_DROP.length && placeColumn(keep, false) > floor; i++) keep &= ~COL_DROP[i];
    placeColumn(keep, true);
  }
  // Places the column with the items in `keep` (COL_* bits); returns its bottom. write: apply it.
  // An item that yields or ends keeps its push, so it fades out where it stands.
  function placeColumn(keep, write) {
    let above = col.toastB;
    let bottom = -Infinity;
    let y;
    const subsH = (keep & COL_NEW ? col.hNew : 0) + (keep & COL_OLD && col.hOld ? 6 + col.hOld : 0);
    if (subsH > 0) {
      y = columnTop(subsEl.offsetTop, subsH, above, col.rTop, col.rBot);
      above = bottom = y + subsH;
      if (write) setPush(subsEl, 'subs', y - subsEl.offsetTop);
    }
    if (keep & COL_VOLLEY && col.hVolley > 0) {
      y = columnTop(volleyEl.offsetTop, col.hVolley, above, col.rTop, col.rBot);
      above = bottom = y + col.hVolley;
      if (write) setPush(volleyEl, 'volley', y - volleyEl.offsetTop);
    }
    if (keep & COL_BANNER && col.hBanner > 0) {
      y = columnTop(bannerEl.offsetTop, col.hBanner, above, col.rTop, col.rBot);
      above = bottom = y + col.hBanner;
      if (write) setPush(bannerEl, 'banner', y - bannerEl.offsetTop);
    }
    const hc = keep & COL_COMBO ? Math.max(col.hCombo0, col.hCombo1) : 0;
    if (hc > 0) {
      y = columnTop(combos[0].offsetTop, hc, above, col.rTop, col.rBot);
      bottom = y + hc;
      if (write) {
        if (col.hCombo0 > 0) setPush(combos[0], 'combo0', y - combos[0].offsetTop);
        if (col.hCombo1 > 0) setPush(combos[1], 'combo1', y - combos[1].offsetTop);
      }
    }
    if (write) {
      setCrowded(subRows[0].el, 'cOld', col.hOld > 0 && !(keep & COL_OLD));
      setCrowded(subsEl, 'cNew', col.hNew > 0 && !(keep & COL_NEW));
      setCrowded(volleyEl, 'cVolley', col.hVolley > 0 && !(keep & COL_VOLLEY));
      setCrowded(bannerEl, 'cBanner', col.hBanner > 0 && !(keep & COL_BANNER));
      const cc = (col.hCombo0 > 0 || col.hCombo1 > 0) && !(keep & COL_COMBO);
      setCrowded(combos[0], 'cCombo0', cc);
      setCrowded(combos[1], 'cCombo1', cc);
    }
    return bottom;
  }
  function setCrowded(el, key, on) {
    if (col[key] === on) return;
    col[key] = on;
    el.classList.toggle('is-crowded', on);
  }
  function setPush(el, key, px) {
    const v = Math.max(0, Math.round(px));
    if (v === col[key]) return;
    col[key] = v;
    el.style.setProperty('--push', `${v}px`);
  }

  function updateDebug(dt) {
    const g = ctx.gestures;
    const on = !!(settings.sensorDebug && g && (settings.control === 'motion' || ctx.motionDemo));
    setClass(debugEl, 'dbgOn', 'is-shown', on);
    if (!on) return;
    dbgClock -= dt;
    if (dbgClock > 0) return;
    dbgClock = 1 / 15;
    const d = g.debug || {};
    // Each grip has its own sensitivity (core/grip.js) and the hilt profile its own thresholds.
    const s = clamp(settings[sensitivityKey(settings)] || 1, SENSITIVITY_MIN, SENSITIVITY_MAX);
    const P = ctx.input.grip === 'hilt' ? HILT_GESTURE : GESTURE_DEFAULTS;
    const span = 14;
    dbg.m.style.transform = `scaleX(${clamp((d.m || 0) / span, 0, 1).toFixed(3)})`;
    dbg.m1.style.transform = `translateX(${((P.swingStart / s / span) * 100).toFixed(1)}%)`;
    dbg.m2.style.transform = `translateX(${((slashThreshold(s, P) / span) * 100).toFixed(1)}%)`;
    dbg.f.style.transform = `scaleX(${clamp((d.fwdAcc || 0) / 30, 0, 1).toFixed(3)})`;
    dbg.f1.style.transform = `translateX(${((12 / s / 30) * 100).toFixed(1)}%)`;
    const bspan = 1.6 + P.windupBeta + 0.4; // β from −1.6 to a little past the wind-up mark
    dbg.b.style.transform = `scaleX(${clamp(((d.beta || 0) + 1.6) / bspan, 0, 1).toFixed(3)})`;
    dbg.b1.style.transform = `translateX(${(((P.windupBeta + 1.6) / bspan) * 100).toFixed(1)}%)`;
    const le = d.lastEvent;
    const evText = le ? (typeof le === 'string' ? le : `${le.type || ''}${Number.isFinite(le.power) ? ' ' + le.power.toFixed(2) : ''}`) : '–';
    const rate = ctx.platform && ctx.platform.motion && ctx.platform.motion.rate ? Math.round(ctx.platform.motion.rate()) : 0;
    const txt = `${d.phase || 'idle'} · ${d.lookState || '–'} · ${rate} Hz · ${evText}`;
    setText(dbg.txt, 'dbgTxt', txt);
  }

  function updateOverlay(dt, p, mode) {
    const vmod = ctx.viewmodel;
    if (!vmod || typeof vmod.setOverlay !== 'function') return;
    const fp = mode === 'battle' || mode === 'training' || mode === 'paused';
    let vig = 0;
    if (fp && p && p.maxHp > 0) {
      const frac = clamp(p.hp / p.maxHp, 0, 1);
      const low = p.alive === false || frac <= 0 ? 1 : smoothstep(0.45, 0.12, frac);
      if (low > 0) {
        if (mode !== 'paused') hbPhase = (hbPhase + dt * (1.0 + 0.7 * low)) % 1;
        const f = hbPhase;
        const beat = Math.exp(-f * 11) + 0.65 * (f > 0.2 ? Math.exp(-(f - 0.2) * 11) : 0);
        vig = clamp(low * (0.78 + 0.22 * beat), 0, 1);
      }
    }
    if (mode !== 'paused') hurt = Math.max(0, hurt - dt / 0.5);
    overlay.vignette = vig;
    overlay.hurt = fp ? hurt : 0;
    overlay.helmet = fp && !!settings.helmetView;
    if (Math.abs(overlay.vignette - sent.vignette) > 0.004 || Math.abs(overlay.hurt - sent.hurt) > 0.004 || overlay.helmet !== sent.helmet) {
      sent.vignette = overlay.vignette;
      sent.hurt = overlay.hurt;
      sent.helmet = overlay.helmet;
      vmod.setOverlay(overlay);
    }
  }

  function updateStats(p, mode) {
    if (!p) return;
    const maxHp = p.maxHp || 100;
    const hp = clamp(p.hp, 0, maxHp);
    if (hp !== last.hp) {
      const heal = last.hp !== undefined && hp > last.hp;
      last.hp = hp;
      const f = (hp / maxHp).toFixed(4);
      health.classList.toggle('is-heal', heal);
      hpFill.style.transform = `scaleX(${f})`;
      hpTrail.style.transform = `scaleX(${f})`;
      setClass(health, 'hpLow', 'is-low', hp / maxHp <= 0.3);
    }
    const spears = p.spearsLeft | 0;
    const spearKey = spears * 4 + (p.weapon === 'spear' ? 2 : 0) + (p.hasSpearInHand ? 1 : 0);
    if (spearKey !== last.spearKey) {
      last.spearKey = spearKey;
      for (let i = 0; i < spearIcons.length; i++) {
        spearIcons[i].classList.toggle('is-on', i < spears);
        spearIcons[i].classList.toggle('is-hand', i === 0 && p.weapon === 'spear' && !!p.hasSpearInHand);
      }
      setClass(spearsEl, 'spearsNone', 'is-none', spears === 0);
    }
    // Numbers are compared first and formatted only when they change (no per-frame strings).
    const score = p.score || 0;
    if (score !== last.scoreV) {
      last.scoreV = score;
      scoreN.textContent = groupDigits(score);
    }
    const chain10 = Math.round((p.chain || 1) * 10);
    if (chain10 !== last.chain10) {
      last.chain10 = chain10;
      chainEl.textContent = `×${(chain10 / 10).toFixed(1)}`;
      setClass(chainEl, 'chainOn', 'is-on', chain10 > 10);
    }
    const kills = p.kills | 0;
    if (kills !== last.killsV) {
      last.killsV = kills;
      killsN.textContent = String(kills);
    }
    const agoge = mode === 'training' || (mode === 'paused' && !!(ctx.training && ctx.training.active));
    const w = ctx.waves;
    let key = agoge ? -1 : 0;
    let resting = false;
    if (!agoge && w) {
      resting = 'inRespite' in w ? !!w.inRespite : w.state === 'respite';
      const ph = w.phase === 'B' ? 1 : w.phase === 'C' ? 2 : 0;
      key = (w.day | 0) * 10000 + (w.wave | 0) * 10 + ph * 2 + (resting ? 1 : 0);
    }
    if (key !== last.waveKey) {
      last.waveKey = key;
      let wave = '';
      if (agoge) wave = 'The Agoge';
      else if (w && w.day) {
        let part = w.wave ? `Wave ${roman(w.wave)}` : '';
        if (w.phase === 'B' && w.wave) part = `Surge ${roman(w.wave)}`;
        else if (w.phase === 'C') part = 'The Kolonos';
        if (resting) part = 'Respite';
        wave = part ? `Day ${roman(w.day)} · ${part}` : `Day ${roman(w.day)}`;
      }
      waveEl.textContent = wave;
    }
    setClass(root, 'training', 'is-training', agoge);
    const reticle = p.weapon === 'spear' && !!p.hasSpearInHand;
    setClass(root, 'reticle', 'has-reticle', reticle);
  }

  function updateTimers(dt, gdt) {
    // Curtain (game time, so it stays in step with waves' behind-the-card timeline).
    if (curtain.t >= 0) {
      curtain.t += gdt;
      const op = Math.round(curtainOpacity(curtain.t, curtain.a, curtain.h, curtain.o) * 100) / 100;
      if (op !== curtain.shown) {
        curtain.shown = op;
        curtainEl.style.opacity = String(op);
      }
      if (curtain.t >= curtain.a + curtain.h + curtain.o) clearCurtain();
    }
    // Banner and queue.
    if (bannerLeft > 0) {
      if (!last.bannerIn) {
        last.bannerIn = true;
        bannerEl.classList.add('is-shown');
      }
      bannerLeft -= dt;
      if (bannerLeft <= 0) {
        bannerEl.classList.remove('is-shown');
        last.bannerIn = false;
        col.dirty = true;
        const next = bannerQueue.shift();
        if (next) showBanner(...next);
      }
    } else if (bannerQueue.length) {
      showBanner(...bannerQueue.shift());
    }
    for (let i = 0; i < 2; i++) {
      if (comboLeft[i] > 0) {
        comboLeft[i] -= dt;
        if (comboLeft[i] <= 0) {
          combos[i].classList.remove('is-shown');
          col.dirty = true;
        }
      }
    }
    for (const t of toasts) {
      if (t.left > 0) {
        t.left -= dt;
        if (t.left <= 0) {
          t.el.classList.remove('is-shown');
          col.dirty = true;
        }
      }
    }
    for (const r of subRows) {
      if (r.left > 0) {
        r.left -= dt;
        if (r.left <= 0) {
          r.el.classList.remove('is-shown');
          col.dirty = true;
        }
      }
    }
    for (const a of arcs) {
      if (a.age < a.life) {
        a.age += dt;
        // The red and its backing fade separately (not as one group): the backing outlasts the red.
        const op = Math.round(arcOpacity(a.age, a.life, a.peak) * 100) / 100;
        if (op !== a.lastOp) {
          a.lastOp = op;
          a.el.style.opacity = op > 0 ? '1' : '0';
          a.red.setAttribute('opacity', String(op));
          a.hi.setAttribute('opacity', String(Math.round(op * 55) / 100));
          a.back.setAttribute('opacity', String(Math.round(arcBacking(a.age, a.life, a.peak) * 100) / 100));
        }
        // The stroke thins as it fades (quarter-unit steps, so only a few attribute writes per hit).
        const w = Math.round(arcWidth(a.age, a.life) * 4) / 4;
        if (w !== a.lastW) setArcWidth(a, w);
      }
    }
    // Volley warning: bronze and steady once the shield is up.
    if (volleyLeft > 0) {
      volleyLeft -= dt;
      const p = ctx.player;
      const raised = !!(p && p.shieldRaise >= 0.4) || !!ctx.input.shieldHeld;
      if (raised !== volleyRaised) {
        volleyRaised = raised;
        volleyEl.classList.toggle('is-raised', raised);
      }
      if (volleyLeft <= 0) {
        volleyEl.classList.remove('is-shown');
        col.dirty = true;
      }
    }
    // Respite countdown (game time) and READY.
    if (respite.on) {
      const w = ctx.waves;
      // waves owns the clock when it exposes it (READY may cut it short); else count down here.
      const polled = !!(w && w.inRespite === true && Number.isFinite(w.respiteLeft));
      respite.left = polled ? w.respiteLeft : respite.left - gdt;
      respite.elapsed += gdt;
      const sec = Math.max(0, Math.ceil(respite.left));
      if (sec !== respite.shownSec) {
        respite.shownSec = sec;
        respiteT.textContent = clock(sec);
      }
      const locked = polled && typeof w.canReady === 'boolean' ? !w.canReady : respite.elapsed < 6;
      if (locked !== respite.locked) {
        respite.locked = locked;
        readyBtn.classList.toggle('is-locked', locked);
      }
      const lk = Math.round(clamp(respite.elapsed / 6, 0, 1) * 50);
      if (lk !== respite.lockShown) {
        respite.lockShown = lk;
        readyLock.style.transform = `scaleX(${(lk / 50).toFixed(2)})`;
      }
      if (respite.armed && !locked) callReady();
      const over = respite.left <= -0.5 || (w && w.inRespite === false) || (w && typeof w.state === 'string' && w.state !== 'respite' && respite.elapsed > 0.3);
      if (over) endRespite();
    }
  }

  // ---- API ----
  function update(dt) {
    const mode = ctx.mode;
    const show = mode === 'battle' || mode === 'training' || mode === 'paused';
    if (show !== visible) {
      visible = show;
      root.classList.toggle('is-visible', show);
      if (!show) hideTransients();
    }
    const p = ctx.player;
    updateOverlay(dt, p, mode);
    if (!show) return;
    for (const el of pendingShow) el.classList.add('is-shown');
    pendingShow.length = 0;

    const cine = !!(ctx.fx && ctx.fx.cameraOverridden);
    if (cine !== cinematic) {
      cinematic = cine;
      root.classList.toggle('is-cinematic', cine);
    }
    const uiDt = mode === 'paused' ? 0 : dt;
    const gdt = mode === 'paused' ? 0 : ctx.time.gdt;
    updateStats(p, mode);
    updateChevrons();
    updateFocus(p);
    updateLock(p, dt);
    updateCoach(p);
    updateTimers(uiDt, gdt);
    updateDebug(dt);
    // A foe close in the view (locked, or winding up on screen) lowers the column's floor.
    const guard = portrait && ((lock.shown && !lock.off) || chevOn);
    if (guard !== col.guard) {
      col.guard = guard;
      col.dirty = true;
    }
    if (col.dirty) layoutColumn();
  }

  function reset() {
    hideTransients();
    root.classList.remove('is-fallen');
    daysSeen = 0;
    for (const k of Object.keys(last)) delete last[k];
    volleyRaised = null;
    cinematic = null;
    root.classList.remove('is-cinematic');
    focusShown = false;
    focusEl.classList.remove('is-shown');
    lock.shown = false;
    lock.off = lock.windup = lock.hpOn = null;
    lock.x = lock.y = lock.rot = NaN;
    lock.w = lock.h = lock.hpShown = -1;
    lock.newLeft = 0;
    lock.newPending = false;
    lockEl.classList.remove('is-shown', 'is-new', 'is-off', 'is-windup', 'has-hp');
    strike.shown = 0;
    lockRes.className = 'hud-lock-res';
    lockRes.textContent = '';
    sent.vignette = -1;
    sent.hurt = -1;
    sent.helmet = null;
    hbPhase = 0;
  }

  return {
    update,
    reset,
    /** Shows a title card: `text` large, `sub` below it, for `seconds`. */
    banner(text, sub = '', seconds = 3) {
      showBanner('', text, sub, seconds, 'card');
    },
  };
}
