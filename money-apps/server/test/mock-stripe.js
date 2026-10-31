// A tiny stand-in for the parts of the Stripe API that server.js uses, plus a fake
// hosted Checkout page. Only for tests: Stripe itself is unreachable from CI sandboxes.
'use strict';
const http = require('node:http');
const crypto = require('node:crypto');

const id = prefix => prefix + crypto.randomBytes(12).toString('hex');

function createMockStripe({ secretKey = 'sk_test_mock' } = {}) {
  const db = { prices: new Map(), sessions: new Map(), subscriptions: new Map(), customers: new Set(), calls: [] };
  let base = '';

  function json(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  }
  const notFound = (res, what) => json(res, 404, { error: { type: 'invalid_request_error', code: 'resource_missing', message: `No such ${what}` } });

  function readForm(req) {
    return new Promise(resolve => {
      let raw = '';
      req.on('data', c => { raw += c; });
      req.on('end', () => resolve(raw));
    });
  }

  function complete(session) {
    const customer = id('cus_');
    db.customers.add(customer);
    session.status = 'complete';
    session.payment_status = 'paid';
    session.customer = customer;
    if (session.mode === 'subscription') {
      const sub = { id: id('sub_'), object: 'subscription', status: 'active', customer, metadata: session._subMeta };
      db.subscriptions.set(sub.id, sub);
      session.subscription = sub.id;
    }
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const raw = req.method === 'POST' ? await readForm(req) : url.search.slice(1);
    const params = Object.fromEntries(new URLSearchParams(raw));
    const parts = url.pathname.split('/').filter(Boolean);

    // ---- Fake hosted Checkout page (what the browser sees) ----
    if (parts[0] === 'pay') {
      const s = db.sessions.get(parts[1]);
      if (!s) { res.writeHead(404); return res.end('no session'); }
      if (parts[2] === 'complete' && req.method === 'POST') {
        complete(s);
        res.writeHead(303, { Location: s.success_url.replace('{CHECKOUT_SESSION_ID}', s.id) });
        return res.end();
      }
      res.writeHead(200, { 'Content-Type': 'text/html' });
      return res.end(`<!doctype html><title>Mock Checkout</title><h1>Mock Stripe Checkout</h1><p id="amount">${s._amount} ${s._currency} (${s.mode})</p>
        <form method="post" action="/pay/${s.id}/complete"><button id="pay">Pay</button></form><a id="cancel" href="${s.cancel_url}">Back</a>`);
    }

    if (parts[0] === 'portal') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      return res.end('<!doctype html><title>Mock Customer Portal</title><h1>Mock Customer Portal</h1>');
    }

    // ---- Test controls ----
    if (parts[0] === '__test') {
      if (parts[1] === 'subscription' && req.method === 'POST') {
        const sub = db.subscriptions.get(params.id);
        if (!sub) return notFound(res, 'subscription');
        sub.status = params.status;
        return json(res, 200, sub);
      }
      if (parts[1] === 'complete' && req.method === 'POST') {
        const s = db.sessions.get(params.id);
        if (!s) return notFound(res, 'session');
        complete(s);
        return json(res, 200, s);
      }
      return json(res, 200, { calls: db.calls });
    }

    // ---- API ----
    db.calls.push(`${req.method} ${url.pathname}`);
    if (req.headers.authorization !== 'Bearer ' + secretKey) {
      return json(res, 401, { error: { type: 'invalid_request_error', message: 'Invalid API Key provided' } });
    }
    const route = `${req.method} /${parts.slice(1, 3).join('/')}`;

    if (route === 'GET /prices') {
      const key = params['lookup_keys[]'];
      return json(res, 200, { object: 'list', data: [...db.prices.values()].filter(p => p.lookup_key === key && p.active) });
    }
    if (route === 'POST /prices') {
      if ([...db.prices.values()].some(p => p.lookup_key === params.lookup_key)) {
        return json(res, 400, { error: { type: 'invalid_request_error', message: 'lookup_key already exists' } });
      }
      const p = { id: id('price_'), object: 'price', active: true, lookup_key: params.lookup_key, currency: params.currency,
        unit_amount: Number(params.unit_amount), recurring: params['recurring[interval]'] ? { interval: params['recurring[interval]'] } : null,
        product: id('prod_'), _name: params['product_data[name]'] };
      db.prices.set(p.id, p);
      return json(res, 200, p);
    }
    if (route === 'POST /checkout/sessions') {
      const price = db.prices.get(params['line_items[0][price]']);
      if (!price) return notFound(res, 'price');
      if (params.mode === 'subscription' && !price.recurring) return json(res, 400, { error: { message: 'recurring price required' } });
      if (params.mode === 'payment' && price.recurring) return json(res, 400, { error: { message: 'one-time price required' } });
      if (params.mode === 'subscription' && params.customer_creation) return json(res, 400, { error: { message: 'customer_creation can only be used in payment mode' } });
      const metadata = {};
      const subMeta = {};
      for (const [k, v] of Object.entries(params)) {
        const m = k.match(/^metadata\[(.+)\]$/); if (m) metadata[m[1]] = v;
        const n = k.match(/^subscription_data\[metadata\]\[(.+)\]$/); if (n) subMeta[n[1]] = v;
      }
      const s = { id: id('cs_test_'), object: 'checkout.session', mode: params.mode, status: 'open', payment_status: 'unpaid',
        customer: null, subscription: null, metadata, success_url: params.success_url, cancel_url: params.cancel_url,
        _amount: price.unit_amount, _currency: price.currency, _subMeta: subMeta };
      s.url = `${base}/pay/${s.id}`;
      db.sessions.set(s.id, s);
      return json(res, 200, s);
    }
    if (req.method === 'GET' && parts[1] === 'checkout' && parts[2] === 'sessions' && parts[3]) {
      const s = db.sessions.get(parts[3]);
      if (!s) return notFound(res, 'checkout.session');
      const out = { ...s };
      if (params['expand[]'] === 'subscription' && s.subscription) out.subscription = db.subscriptions.get(s.subscription);
      return json(res, 200, out);
    }
    if (req.method === 'GET' && parts[1] === 'subscriptions' && parts[2]) {
      const sub = db.subscriptions.get(decodeURIComponent(parts[2]));
      return sub ? json(res, 200, sub) : notFound(res, 'subscription');
    }
    if (route === 'POST /billing_portal/sessions') {
      if (!db.customers.has(params.customer)) return notFound(res, 'customer');
      return json(res, 200, { id: id('bps_'), url: `${base}/portal?customer=${params.customer}&return=${encodeURIComponent(params.return_url)}` });
    }
    return notFound(res, route);
  });

  return {
    db, server,
    listen: () => new Promise(r => server.listen(0, '127.0.0.1', () => { base = `http://127.0.0.1:${server.address().port}`; r(base); })),
    close: () => new Promise(r => server.close(r)),
    get url() { return base; },
  };
}

module.exports = { createMockStripe };
