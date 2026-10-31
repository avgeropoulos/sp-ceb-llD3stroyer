// pay.js: Stripe-powered "Pro" unlock shared by the Money Apps.
// It talks to server/server.js. Usage:
//
//   const pro = Pay.init('invoice-pro', { onChange: isPro => render() });
//   pro.isPro()                 -> true when a valid license is stored
//   pro.buy()                   -> sends the user to Stripe Checkout
//   pro.renderPanel(el, {...})  -> standard upgrade / "Pro active" box
//
// By default the API is on the same origin as the page. To host the pages
// somewhere else, set window.MONEY_APPS_API = 'https://your-api.example.com'
// before loading this script.
(function () {
  'use strict';
  const API = String(window.MONEY_APPS_API || '').replace(/\/+$/, '');
  const DAY = 24 * 60 * 60 * 1000;
  const REFRESH_AFTER = DAY;     // re-check a subscription with the server at most once a day
  const OFFLINE_GRACE = 3 * DAY; // keep Pro this long past expiry if the server can't be reached

  const storeKey = product => 'moneyapps.license.' + product;
  const read = k => { try { return localStorage.getItem(k); } catch { return null; } };
  const write = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch { /* storage blocked */ } };

  function decode(token) {
    try {
      const body = String(token).split('.')[0].replace(/-/g, '+').replace(/_/g, '/');
      const bytes = Uint8Array.from(atob(body), c => c.charCodeAt(0));
      const p = JSON.parse(new TextDecoder().decode(bytes));
      return p && p.v === 1 ? p : null;
    } catch { return null; }
  }

  async function call(path, body) {
    if (location.protocol === 'file:' && !API) {
      throw new Error('Payments need the Money Apps server. Run "node server/server.js" and open the app from http://localhost:4242.');
    }
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
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const e = new Error(data.error || 'Something went wrong (' + res.status + ').');
      e.status = res.status; e.reason = data.reason;
      throw e;
    }
    return data;
  }

  function toast(msg, ms) {
    const el = document.createElement('div');
    el.className = 'pay-toast';
    el.setAttribute('role', 'status');
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), ms || 4500);
  }

  const esc = t => String(t).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function init(product, opts) {
    opts = opts || {};
    const listeners = [];
    if (opts.onChange) listeners.push(opts.onChange);
    let busy = false;
    let panel = null; // the main panel; the upsell modal renders its own copy
    let modal = null;

    const license = () => read(storeKey(product));
    const info = () => { const t = license(); const p = t && decode(t); return p && p.p === product ? p : null; };
    function isPro() {
      const p = info();
      if (!p) return false;
      return p.exp == null || Date.now() < p.exp + OFFLINE_GRACE;
    }
    function changed() {
      const pro = isPro();
      if (modal && pro) modal.close();
      rerender();
      listeners.forEach(fn => { try { fn(pro); } catch (e) { console.error(e); } });
    }
    function rerender() {
      if (panel) renderPanel(panel.el, panel.opts, true);
      if (modal) renderPanel(modal.el, modal.opts, true);
    }
    function setLicense(token) { write(storeKey(product), token); changed(); }
    function fail(err) { (opts.onError || (e => alert(e.message)))(err); }

    async function buy() {
      if (busy) return;
      busy = true; rerender();
      try {
        const { url } = await call('/api/checkout', { product });
        location.assign(url);
      } catch (err) {
        busy = false; rerender();
        fail(err);
      }
    }

    async function manage() {
      try {
        const { url } = await call('/api/portal', { license: license() });
        location.assign(url);
      } catch (err) { fail(err); }
    }

    // Re-validates the stored license. Drops it only when the server says it's
    // invalid or the subscription has ended, never just because we're offline.
    async function refresh(force) {
      const p = info();
      if (!p || p.exp == null) return isPro();
      if (!force && Date.now() - p.iat < REFRESH_AFTER) return isPro();
      try {
        const data = await call('/api/license/refresh', { license: license() });
        if (data.license !== license()) setLicense(data.license);
      } catch (err) {
        if (err.status === 401 || err.status === 402) {
          setLicense(null);
          toast(err.reason === 'inactive' ? 'Your Pro subscription has ended. You can resubscribe any time.' : 'Your Pro license is no longer valid.');
        }
      }
      return isPro();
    }

    async function restore(key) {
      key = String(key || '').trim();
      const p = decode(key);
      if (!p || p.p !== product) { fail(new Error('That license key is not for this app.')); return false; }
      try {
        const data = await call('/api/license/refresh', { license: key });
        setLicense(data.license);
        toast('Pro unlocked. Thanks for your support!');
        return true;
      } catch (err) { fail(err); return false; }
    }

    async function copyKey() {
      const key = license();
      try { await navigator.clipboard.writeText(key); toast('License key copied. Keep it somewhere safe.'); }
      catch { prompt('Your license key:', key); }
    }

    // Standard Pro box. opts: { name, price, perks: [..], note }
    function renderPanel(el, o, internal) {
      if (!internal) panel = { el, opts: o };
      el.classList.add('card', 'pro', 'pay-panel');
      const p = info();
      if (isPro()) {
        el.innerHTML = '<div class="pay-row"><div><b>✓ ' + esc(o.name) + ' is active</b><div class="muted">' +
          (p.exp == null ? 'Lifetime license. Thank you!' : 'Subscription. Thank you for supporting the app!') + '</div></div>' +
          '<div class="pay-actions">' + (p.c && p.exp != null ? '<button class="ghost" data-pay="manage">Manage subscription</button>' : '') +
          '<button class="ghost" data-pay="copy">Copy license key</button></div></div>';
      } else {
        el.innerHTML = '<div class="pay-row"><div><b>' + esc(o.name) + '</b> · ' + esc(o.price) +
          '<ul class="pay-perks">' + (o.perks || []).map(x => '<li>' + esc(x) + '</li>').join('') + '</ul>' +
          (o.note ? '<div class="muted" style="font-size:.85rem">' + esc(o.note) + '</div>' : '') + '</div>' +
          '<div class="pay-actions"><button data-pay="buy"' + (busy ? ' disabled' : '') + '>' + (busy ? 'Opening checkout…' : 'Upgrade') + '</button>' +
          '<button class="ghost" data-pay="restore">I have a license key</button></div></div>' +
          '<div class="muted" style="font-size:.8rem;margin-top:8px">Secure checkout by Stripe.</div>';
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

    // Shows the upgrade box in a modal, e.g. when a free user clicks a Pro feature.
    function upsell(reason) {
      if (isPro() || modal) return;
      const back = document.createElement('div');
      back.className = 'pay-modal';
      back.innerHTML = '<div class="card pay-dialog" role="dialog" aria-modal="true"><button class="ghost pay-close" aria-label="Close">✕</button>' +
        (reason ? '<p style="margin:0 32px 12px 0">' + esc(reason) + '</p>' : '') + '<div></div></div>';
      document.body.appendChild(back);
      const onKey = e => { if (e.key === 'Escape') close(); };
      function close() { back.remove(); document.removeEventListener('keydown', onKey); modal = null; }
      modal = { el: back.querySelector('.pay-dialog > div'), opts: (panel && panel.opts) || opts.panel || { name: 'Pro', price: '' }, close };
      renderPanel(modal.el, modal.opts, true);
      back.addEventListener('click', e => { if (e.target === back || e.target.closest('.pay-close')) close(); });
      document.addEventListener('keydown', onKey);
      const btn = modal.el.querySelector('[data-pay="buy"]'); if (btn) btn.focus();
    }

    // Finish a checkout: Stripe redirects back with ?session_id=cs_...
    const ready = (async () => {
      const q = new URLSearchParams(location.search);
      const sid = q.get('session_id');
      const cancelled = q.get('checkout') === 'cancelled';
      if (sid || cancelled) {
        q.delete('session_id'); q.delete('checkout');
        history.replaceState(null, '', location.pathname + (q.toString() ? '?' + q : '') + location.hash);
      }
      if (cancelled) toast('Checkout cancelled. You have not been charged.');
      if (sid) {
        for (let attempt = 0; attempt < 4; attempt++) {
          try {
            const data = await call('/api/license', { session_id: sid });
            setLicense(data.license);
            toast('Pro unlocked. Thanks for your purchase!', 6000);
            return isPro();
          } catch (err) {
            // Stripe can take a moment to mark a session paid (e.g. delayed payment methods).
            if (err.reason === 'unpaid' && attempt < 3) { await new Promise(r => setTimeout(r, 1500 * (attempt + 1))); continue; }
            fail(new Error('We could not confirm your payment: ' + err.message + ' If you were charged, contact support and we will sort it out.'));
            return isPro();
          }
        }
      }
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
