// pay.js: Stripe-powered "Pro" unlock shared by the Money Apps.
// It talks to server/server.js. Usage:
//
//   const pro = Pay.init('invoice-pro', { onChange: isPro => render() });
//   pro.isPro()                 -> true when a valid license is stored
//   pro.buy()                   -> sends the user to Stripe Checkout
//   pro.renderPanel(el, {...})  -> standard upgrade / "Pro active" box
//   pro.upsell(reason)          -> the same box in a dialog, for locked features
//
// By default the API is on the same origin as the page. To host the pages
// somewhere else, set window.MONEY_APPS_API = 'https://your-api.example.com'
// before loading this script.
(function () {
  'use strict';
  const API = String(window.MONEY_APPS_API || '').replace(/\/+$/, '');
  const DAY = 24 * 60 * 60 * 1000;
  const REFRESH_AFTER = DAY;        // re-check a subscription with the server at most once a day
  const OFFLINE_GRACE = 3 * DAY;    // keep Pro this long past expiry if the server can't be reached
  const PENDING_MAX_AGE = 30 * DAY; // keep retrying a paid-but-unconfirmed checkout this long
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  // localStorage, falling back to memory when the browser blocks it (Pro then lasts for this visit).
  const mem = new Map();
  const read = k => { try { return localStorage.getItem(k); } catch { return mem.has(k) ? mem.get(k) : null; } };
  function write(k, v) {
    try {
      if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v);
      mem.delete(k);
      return true;
    } catch {
      if (v == null) mem.delete(k); else mem.set(k, v);
      return false;
    }
  }

  function decode(token) {
    try {
      const body = String(token).split('.')[0].replace(/-/g, '+').replace(/_/g, '/');
      const bytes = Uint8Array.from(atob(body), c => c.charCodeAt(0));
      const p = JSON.parse(new TextDecoder().decode(bytes));
      return p && p.v === 1 && typeof p.p === 'string' ? p : null;
    } catch { return null; }
  }

  function serverNeeded() {
    return new Error('Payments need the Money Apps server. Run "node server/server.js" and open the app from http://localhost:4242.');
  }

  async function call(path, body) {
    if (location.protocol === 'file:' && !API) throw serverNeeded();
    let res;
    try {
      res = await fetch(API + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
      });
    } catch {
      const e = new Error('Could not reach the payments server. Check your connection and try again.');
      e.offline = true;
      throw e;
    }
    let data = null;
    try { data = await res.json(); } catch { /* not our server, e.g. a proxy or captive portal */ }
    if (!res.ok || !data || typeof data !== 'object') {
      const e = new Error((data && data.error) || 'Something went wrong (' + res.status + '). Please try again.');
      e.status = res.status;
      e.reason = data && data.reason; // only our server sets this
      e.transient = !data || res.status >= 500 || res.status === 429;
      throw e;
    }
    return data;
  }

  // Prices and the support address come from the server, so products.json is the single source of truth.
  let catalog = null;
  function loadCatalog() {
    if (!catalog) {
      catalog = (location.protocol === 'file:' && !API)
        ? Promise.resolve(null)
        : fetch(API + '/api/products').then(r => (r.ok ? r.json() : null)).catch(() => null);
    }
    return catalog;
  }
  function priceLabel(p) {
    try {
      const whole = p.amount % 100 === 0;
      const money = new Intl.NumberFormat(undefined, { style: 'currency', currency: p.currency.toUpperCase(), minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 }).format(p.amount / 100);
      return p.mode === 'subscription' ? money + '/' + p.interval : money + ' once';
    } catch { return null; }
  }

  function toast(msg, ms) {
    document.querySelectorAll('.pay-toast').forEach(t => t.remove());
    const el = document.createElement('div');
    el.className = 'pay-toast';
    el.setAttribute('role', 'status');
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), ms || 4500);
  }

  const esc = t => String(t).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

  function init(product, opts) {
    opts = opts || {};
    const LICENSE_KEY = 'moneyapps.license.' + product;
    const PENDING_KEY = 'moneyapps.pending.' + product;
    const listeners = [];
    if (opts.onChange) listeners.push(opts.onChange);
    let busy = false;
    let claiming = null;
    let panel = null; // the main panel; the upsell dialog renders its own copy
    let modal = null;
    let lastPro = null;
    let serverPrice = null;
    let support = null;

    const license = () => read(LICENSE_KEY);
    const info = () => { const t = license(); const p = t && decode(t); return p && p.p === product ? p : null; };
    const looksValid = t => typeof t === 'string' && t.includes('.') && (decode(t) || {}).p === product;
    function isPro() {
      const p = info();
      if (!p) return false;
      return p.exp == null || Date.now() < p.exp + OFFLINE_GRACE;
    }
    function changed() {
      const pro = isPro();
      lastPro = pro;
      if (modal && pro) modal.close();
      rerender();
      listeners.forEach(fn => { try { fn(pro); } catch (e) { console.error(e); } });
    }
    function rerender() {
      if (panel) renderPanel(panel.el, panel.opts, true);
      if (modal) renderPanel(modal.el, modal.opts, true);
    }
    function setLicense(token) {
      const saved = write(LICENSE_KEY, token);
      changed();
      if (token && !saved) toast('Your browser is blocking storage, so Pro will only last until you close this page. Use “Copy license key” to keep it.', 9000);
    }
    function fail(err) { (opts.onError || (e => alert(e.message)))(err); }
    const helpLine = () => (support ? ' Contact ' + support + ' if you need help.' : '');

    const readPending = () => { try { const v = JSON.parse(read(PENDING_KEY)); return v && typeof v.sid === 'string' ? v : null; } catch { return null; } };
    const clearPending = () => write(PENDING_KEY, null);

    // Turns a paid checkout into a license. The session id is saved before anything can fail,
    // so a dropped connection or a slow bank payment never loses a purchase.
    function claimPending(fromRedirect) {
      if (claiming) return claiming;
      claiming = (async () => {
        const pend = readPending();
        if (!pend) return;
        if (Date.now() - pend.t > PENDING_MAX_AGE) { clearPending(); return; }
        for (let attempt = 0; ; attempt++) {
          try {
            const data = await call('/api/license', { session_id: pend.sid });
            if (!looksValid(data.license)) throw Object.assign(new Error('Unexpected reply from the payments server.'), { transient: true });
            clearPending();
            setLicense(data.license);
            toast('Pro unlocked. Thanks for your purchase!', 6000);
            return;
          } catch (err) {
            if (err.reason === 'unpaid') {
              if (fromRedirect && attempt < 3) { await sleep(1500 * (attempt + 1)); continue; }
              if (fromRedirect) toast('Thanks! Your payment is still processing. Pro will unlock automatically when it clears, so you can close this page.', 9000);
              return;
            }
            if (err.offline || err.transient) {
              if (attempt < 2) { await sleep(2000 * (attempt + 1)); continue; }
              toast('We could not confirm your payment yet. We will try again automatically the next time you open this page.' + helpLine(), 9000);
              return;
            }
            clearPending();
            fail(new Error('We could not confirm your payment: ' + err.message + (/contact/i.test(err.message) ? '' : ' If you were charged, please get in touch and we will sort it out.' + helpLine())));
            return;
          }
        }
      })().finally(() => { claiming = null; });
      return claiming;
    }

    async function buy() {
      if (busy) return;
      if (isPro()) { changed(); toast('Pro is already active here.'); return; }
      if (readPending() && !confirm('A payment from this browser is still being confirmed. Buy again anyway?')) { claimPending(false); return; }
      busy = true; rerender();
      try {
        const { url } = await call('/api/checkout', { product });
        if (typeof url !== 'string' || !/^https?:\/\//.test(url)) throw new Error('Unexpected reply from the payments server.');
        location.assign(url);
      } catch (err) {
        busy = false; rerender();
        fail(err);
      }
    }

    async function manage() {
      try {
        const { url } = await call('/api/portal', { license: license() });
        if (typeof url !== 'string' || !/^https:\/\/|^http:\/\/(localhost|127\.)/.test(url)) throw new Error('Unexpected reply from the payments server.');
        location.assign(url);
      } catch (err) { fail(err); }
    }

    // Re-validates the stored license. Drops it only when our server says it's invalid or
    // the subscription has ended, never because we're offline or the server is misconfigured.
    async function refresh(force) {
      const p = info();
      if (!p || p.exp == null) return isPro();
      if (!force && Date.now() - p.iat < REFRESH_AFTER) return isPro();
      try {
        const data = await call('/api/license/refresh', { license: license() });
        if (looksValid(data.license) && data.license !== license()) setLicense(data.license);
      } catch (err) {
        if (err.reason === 'invalid' || err.reason === 'inactive') {
          setLicense(null);
          toast(err.reason === 'inactive' ? 'Your Pro subscription has ended. You can resubscribe any time.' : 'Your Pro license is no longer valid.' + helpLine(), 7000);
        }
      }
      return isPro();
    }

    async function restore(key) {
      key = String(key || '').trim();
      if (!looksValid(key)) { fail(new Error('That license key is not for this app.')); return false; }
      try {
        const data = await call('/api/license/refresh', { license: key });
        if (!looksValid(data.license)) throw new Error('Unexpected reply from the payments server.');
        setLicense(data.license);
        toast('Pro unlocked. Thanks for your support!');
        return true;
      } catch (err) { fail(err); return false; }
    }

    async function copyKey() {
      const key = license();
      if (!key) return;
      try { await navigator.clipboard.writeText(key); toast('License key copied. Keep it somewhere safe.'); }
      catch { prompt('Your license key:', key); }
    }

    // Standard Pro box. o: { name, price, perks: [..], note }
    function renderPanel(el, o, internal) {
      if (!internal) panel = { el, opts: o };
      el.classList.add('card', 'pro', 'pay-panel');
      const p = info();
      const help = support ? '<div class="muted" style="font-size:.8rem;margin-top:8px">Questions? <a href="mailto:' + esc(encodeURIComponent(support).replace(/%40/g, '@')) + '">' + esc(support) + '</a></div>' : '';
      if (isPro()) {
        el.innerHTML = '<div class="pay-row"><div><b>✓ ' + esc(o.name) + ' is active</b><div class="muted">' +
          (p.exp == null ? 'Lifetime license. Thank you!' : 'Subscription. Thank you for supporting the app!') + '</div></div>' +
          '<div class="pay-actions">' + (p.c && p.exp != null ? '<button class="ghost" data-pay="manage">Manage subscription</button>' : '') +
          '<button class="ghost" data-pay="copy">Copy license key</button></div></div>' + help;
      } else {
        el.innerHTML = '<div class="pay-row"><div><b class="pay-name">' + esc(o.name) + '</b> · <span class="pay-price">' + esc(serverPrice || o.price || '') + '</span>' +
          '<ul class="pay-perks">' + (o.perks || []).map(x => '<li>' + esc(x) + '</li>').join('') + '</ul>' +
          (o.note ? '<div class="muted" style="font-size:.85rem">' + esc(o.note) + '</div>' : '') + '</div>' +
          '<div class="pay-actions"><button data-pay="buy"' + (busy ? ' disabled' : '') + '>' + (busy ? 'Opening checkout…' : 'Upgrade') + '</button>' +
          '<button class="ghost" data-pay="restore">I have a license key</button></div></div>' +
          '<div class="muted" style="font-size:.8rem;margin-top:8px">Secure checkout by Stripe.</div>' + help;
      }
      el.onclick = e => {
        const a = e.target.closest('[data-pay]');
        if (!a) return;
        const act = a.dataset.pay;
        if (act === 'buy') buy();
        else if (act === 'manage') manage();
        else if (act === 'copy') copyKey();
        else if (act === 'restore') { const k = prompt('Paste your license key:'); if (k) restore(k); }
      };
    }

    // Shows the upgrade box in a dialog, e.g. when a free user clicks a Pro feature.
    let dialogSeq = 0;
    function upsell(reason) {
      if (isPro() || modal) return;
      const returnTo = document.activeElement;
      const id = 'pay-dialog-' + product + '-' + (++dialogSeq);
      const back = document.createElement('div');
      back.className = 'pay-modal';
      back.innerHTML = '<div class="card pay-dialog" role="dialog" aria-modal="true" aria-labelledby="' + id + '"><button class="ghost pay-close" aria-label="Close">✕</button>' +
        '<p id="' + id + '" style="margin:0 32px 12px 0">' + esc(reason || 'This is a Pro feature.') + '</p><div></div></div>';
      document.body.appendChild(back);
      const dialog = back.querySelector('.pay-dialog');
      const onKey = e => {
        if (e.key === 'Escape') { e.preventDefault(); close(); return; }
        if (e.key !== 'Tab') return;
        const items = [...dialog.querySelectorAll(FOCUSABLE)];
        if (!items.length) return;
        const first = items[0], last = items[items.length - 1];
        if (!dialog.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
        else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      };
      function close() {
        back.remove();
        document.removeEventListener('keydown', onKey, true);
        modal = null;
        if (returnTo && document.contains(returnTo) && typeof returnTo.focus === 'function') returnTo.focus();
      }
      modal = { el: dialog.querySelector(':scope > div'), opts: (panel && panel.opts) || opts.panel || { name: 'Pro', price: '' }, close };
      renderPanel(modal.el, modal.opts, true);
      back.addEventListener('click', e => { if (e.target === back || e.target.closest('.pay-close')) close(); });
      document.addEventListener('keydown', onKey, true);
      const btn = modal.el.querySelector('[data-pay="buy"]'); if (btn) btn.focus();
    }

    // Keep every open tab in sync, and recover from the Back button after visiting Checkout.
    window.addEventListener('storage', e => { if (e.key === LICENSE_KEY || e.key === null) changed(); });
    window.addEventListener('pageshow', e => { if (e.persisted) { busy = false; changed(); } });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && isPro() !== lastPro) changed(); });
    window.addEventListener('online', () => { if (readPending()) claimPending(false); });

    loadCatalog().then(c => {
      if (!c) return;
      if (typeof c.support === 'string' && c.support) support = c.support;
      const p = c.products && c.products[product];
      if (p) serverPrice = priceLabel(p);
      rerender();
    });

    // Finish a checkout: Stripe redirects back with ?session_id=cs_...
    const ready = (async () => {
      lastPro = isPro();
      const q = new URLSearchParams(location.search);
      const sid = q.get('session_id');
      const cancelled = q.get('checkout') === 'cancelled';
      if (sid && /^cs_(test|live)_[A-Za-z0-9]+$/.test(sid)) write(PENDING_KEY, JSON.stringify({ sid, t: Date.now() }));
      if (sid || cancelled) {
        q.delete('session_id'); q.delete('checkout');
        history.replaceState(history.state, '', location.pathname + (q.toString() ? '?' + q : '') + location.hash);
      }
      if (cancelled) toast('Checkout cancelled. You have not been charged.');
      if (readPending()) await claimPending(Boolean(sid));
      return refresh(false);
    })();

    return {
      product, isPro, info, buy, manage, refresh, restore, copyKey, renderPanel, upsell, ready,
      onChange(fn) { listeners.push(fn); },
      licenseKey: license,
    };
  }

  window.Pay = { init, decode };
})();
