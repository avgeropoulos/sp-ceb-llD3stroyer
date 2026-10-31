'use strict';
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const { createServer, loadConfig, signLicense, verifyLicense, rateKey, PRODUCTS } = require('../server.js');
const { createMockStripe } = require('./mock-stripe.js');

const SECRET = 'x'.repeat(40);
let mock, server, base;

before(async () => {
  mock = createMockStripe({ secretKey: 'sk_test_mock' });
  const stripeUrl = await mock.listen();
  const cfg = loadConfig({ STRIPE_SECRET_KEY: 'sk_test_mock', LICENSE_SECRET: SECRET, STRIPE_API_BASE: stripeUrl, APP_URL: 'https://apps.example.com', ALLOWED_ORIGINS: 'https://pages.example.com/', RATE_LIMIT_PER_MIN: '10000' });
  server = createServer(cfg);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => { server.close(); await mock.close(); });

const post = (path, body, headers = {}) => fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
const sessionIdFrom = url => url.split('/pay/')[1];

async function buy(product) {
  const res = await post('/api/checkout', { product });
  assert.equal(res.status, 200);
  const { url } = await res.json();
  const sid = sessionIdFrom(url);
  return { sid, session: mock.db.sessions.get(sid) };
}

test('refuses to start with Stripe but without a strong LICENSE_SECRET', () => {
  assert.throws(() => createServer(loadConfig({ STRIPE_SECRET_KEY: 'sk_test_x', LICENSE_SECRET: 'short' })), /LICENSE_SECRET/);
  assert.doesNotThrow(() => createServer(loadConfig({})));
});

test('products.json entries are well-formed', () => {
  for (const [key, p] of Object.entries(PRODUCTS)) {
    assert.match(key, /^[a-z0-9-]+$/, key);
    assert.ok(['subscription', 'payment'].includes(p.mode), `${key} mode`);
    assert.ok(Number.isInteger(p.amount) && p.amount >= 50, `${key} amount is in cents`);
    assert.match(p.currency, /^[a-z]{3}$/);
    if (p.mode === 'subscription') assert.ok(['day', 'week', 'month', 'year'].includes(p.interval), `${key} interval`);
    assert.ok(require('node:fs').existsSync(require('node:path').join(__dirname, '..', '..', p.page)), `${key} page ${p.page} exists`);
  }
});

test('license signing round-trips and rejects tampering', () => {
  const key = Object.keys(PRODUCTS)[0];
  const tok = signLicense(SECRET, { v: 1, p: key, c: 'cus_1', s: null, iat: 1, exp: null });
  assert.equal(verifyLicense(SECRET, tok).c, 'cus_1');
  assert.equal(verifyLicense('y'.repeat(40), tok), null);
  const [body, sig] = tok.split('.');
  const forged = Buffer.from(JSON.stringify({ v: 1, p: key, c: 'cus_2', s: null, iat: 1, exp: null })).toString('base64url');
  assert.equal(verifyLicense(SECRET, `${forged}.${sig}`), null);
  assert.equal(verifyLicense(SECRET, `${body}.${sig}.x`), null);
  assert.equal(verifyLicense(SECRET, signLicense(SECRET, { v: 1, p: 'not-a-product', c: null, s: null })), null);
  assert.equal(verifyLicense(SECRET, 42), null);
});

for (const [key, p] of Object.entries(PRODUCTS)) {
  test(`${key}: full ${p.mode} purchase flow`, async () => {
    const { sid, session } = await buy(key);
    assert.equal(session.mode, p.mode);
    assert.equal(session.success_url, `https://apps.example.com/${p.page}?session_id={CHECKOUT_SESSION_ID}`);
    assert.equal(session._amount, p.amount);

    // Checkout not finished yet
    let res = await post('/api/license', { session_id: sid });
    assert.equal(res.status, 402);
    assert.equal((await res.json()).reason, 'incomplete');
    // Finished, but the payment hasn't cleared yet (e.g. a bank debit)
    session.status = 'complete';
    res = await post('/api/license', { session_id: sid });
    assert.equal(res.status, 402);
    assert.equal((await res.json()).reason, 'unpaid');

    await fetch(`${mock.url}/__test/complete`, { method: 'POST', body: new URLSearchParams({ id: sid }) });
    res = await post('/api/license', { session_id: sid });
    assert.equal(res.status, 200);
    const lic = await res.json();
    const payload = verifyLicense(SECRET, lic.license);
    assert.equal(payload.p, key);
    assert.ok(payload.c.startsWith('cus_'));

    if (p.mode === 'subscription') {
      assert.ok(payload.s.startsWith('sub_'));
      assert.ok(payload.exp > Date.now());
      res = await post('/api/license/refresh', { license: lic.license });
      assert.equal(res.status, 200);
      // Cancel → refresh is refused, so the browser drops Pro
      await fetch(`${mock.url}/__test/subscription`, { method: 'POST', body: new URLSearchParams({ id: payload.s, status: 'canceled' }) });
      res = await post('/api/license/refresh', { license: lic.license });
      assert.equal(res.status, 402);
      assert.equal((await res.json()).reason, 'inactive');
    } else {
      assert.equal(payload.s, null);
      assert.equal(payload.exp, null);
      res = await post('/api/license/refresh', { license: lic.license });
      assert.equal(res.status, 200);
      assert.equal((await res.json()).license, lic.license);
    }

    res = await post('/api/portal', { license: lic.license });
    assert.equal(res.status, 200);
    assert.match((await res.json()).url, /portal\?customer=cus_/);
  });
}

test('price is created once and then reused', async () => {
  const key = Object.keys(PRODUCTS)[0];
  await buy(key); await buy(key);
  const prices = [...mock.db.prices.values()].filter(x => x.lookup_key.startsWith(`money-apps:${key}:`));
  assert.equal(prices.length, 1);
});

test('rejects bad input', async () => {
  assert.equal((await post('/api/checkout', { product: 'nope' })).status, 400);
  assert.equal((await post('/api/checkout', { product: '__proto__' })).status, 400);
  assert.equal((await post('/api/checkout', { product: 'constructor' })).status, 400);
  assert.equal((await post('/api/license', { session_id: '../../v1/customers' })).status, 400);
  assert.equal((await post('/api/license', { session_id: 'cs_test_doesnotexist' })).status, 404);
  assert.equal((await post('/api/license/refresh', { license: 'garbage' })).status, 401);
  assert.equal((await post('/api/portal', { license: 'a.b' })).status, 401);
  const notJson = await fetch(base + '/api/checkout', { method: 'POST', body: 'product=x' });
  assert.equal(notJson.status, 415);
  const badJson = await fetch(base + '/api/checkout', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{' });
  assert.equal(badJson.status, 400);
  const big = await post('/api/checkout', { product: 'x'.repeat(20000) });
  assert.equal(big.status, 413);
});

test('a session for something else cannot mint a license', async () => {
  const key = Object.keys(PRODUCTS)[0];
  const { sid, session } = await buy(key);
  session.metadata = {};
  await fetch(`${mock.url}/__test/complete`, { method: 'POST', body: new URLSearchParams({ id: sid }) });
  assert.equal((await post('/api/license', { session_id: sid })).status, 400);
});

test('serves app pages but never the server folder or dotfiles', async () => {
  const page = Object.values(PRODUCTS)[0].page;
  assert.equal((await fetch(`${base}/${page}`)).status, 200);
  assert.equal((await fetch(`${base}/pay.js`)).headers.get('content-type'), 'text/javascript; charset=utf-8');
  assert.equal((await fetch(`${base}/`)).status, 200);
  for (const p of ['/server/server.js', '/server/products.json', '/%2e%2e/README.md', '/..%2f..%2fetc/passwd', '/server%2fserver.js', '/.git/config', '/README.md']) {
    assert.equal((await fetch(base + p)).status, 404, p);
  }
  assert.equal((await fetch(`${base}/${page}`, { method: 'POST' })).status, 405);
});

test('CORS only for allowed origins', async () => {
  let res = await fetch(base + '/api/products', { headers: { Origin: 'https://pages.example.com' } });
  assert.equal(res.headers.get('access-control-allow-origin'), 'https://pages.example.com');
  res = await fetch(base + '/api/products', { headers: { Origin: 'https://evil.example.com' } });
  assert.equal(res.headers.get('access-control-allow-origin'), null);
  res = await fetch(base + '/api/checkout', { method: 'OPTIONS', headers: { Origin: 'https://pages.example.com' } });
  assert.equal(res.status, 204);
});

test('without Stripe configured, payment endpoints say so', async () => {
  const s = createServer(loadConfig({}));
  await new Promise(r => s.listen(0, '127.0.0.1', r));
  const res = await fetch(`http://127.0.0.1:${s.address().port}/api/checkout`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ product: Object.keys(PRODUCTS)[0] }) });
  assert.equal(res.status, 503);
  s.close();
});

test('rate limits bursts of POSTs', async () => {
  const s = createServer(loadConfig({ RATE_LIMIT_PER_MIN: '5' }));
  await new Promise(r => s.listen(0, '127.0.0.1', r));
  const codes = [];
  for (let i = 0; i < 7; i++) {
    codes.push((await fetch(`http://127.0.0.1:${s.address().port}/api/license/refresh`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"license":"x"}' })).status);
  }
  s.close();
  assert.deepEqual(codes, [503, 503, 503, 503, 503, 429, 429]);
});

async function listen(env) {
  const s = createServer(loadConfig(env));
  await new Promise(r => s.listen(0, '127.0.0.1', r));
  return { s, url: `http://127.0.0.1:${s.address().port}` };
}
const postTo = (url, path, body, headers = {}) => fetch(url + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });

test('a malformed request line does not crash the server', async () => {
  for (const target of ['//[', '//%zz/', 'http://localhost:99999/']) {
    const reply = await new Promise((resolve, reject) => {
      const sock = net.connect(server.address().port, '127.0.0.1', () => sock.write(`GET ${target} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n`));
      let data = ''; sock.on('data', d => { data += d; }); sock.on('end', () => resolve(data)); sock.on('error', reject);
    });
    assert.match(reply, /^HTTP\/1\.1 (400|404)/, target);
  }
  assert.equal((await fetch(base + '/api/health')).status, 200);
});

test('refuses dangerous live-mode settings', () => {
  const live = { STRIPE_SECRET_KEY: 'sk_live_x', LICENSE_SECRET: SECRET };
  assert.throws(() => createServer(loadConfig(live)), /APP_URL/);
  assert.throws(() => createServer(loadConfig({ ...live, APP_URL: 'http://apps.example.com' })), /APP_URL/);
  assert.throws(() => createServer(loadConfig({ ...live, APP_URL: 'https://localhost:4242' })), /APP_URL/);
  assert.throws(() => createServer(loadConfig({ APP_URL: 'not a url' })), /APP_URL/);
  assert.doesNotThrow(() => createServer(loadConfig({ ...live, APP_URL: 'https://apps.example.com/tools' })));
});

test('test-mode keys are rejected by a live-mode server', async () => {
  const key = Object.keys(PRODUCTS).find(k => PRODUCTS[k].mode === 'payment') || Object.keys(PRODUCTS)[0];
  const testKey = signLicense(SECRET, { v: 1, p: key, m: 'test', c: 'cus_1', s: null, iat: 1, exp: null });
  const { s, url } = await listen({ STRIPE_SECRET_KEY: 'sk_live_x', LICENSE_SECRET: SECRET, APP_URL: 'https://apps.example.com' });
  const res = await postTo(url, '/api/license/refresh', { license: testKey });
  s.close();
  assert.equal(res.status, 401);
  assert.equal((await res.json()).reason, 'invalid');
  assert.equal(verifyLicense(SECRET, testKey, 'test').c, 'cus_1');
  assert.equal(verifyLicense(SECRET, testKey, 'live'), null);
});

test('a server without licensing configured never calls a key invalid', async () => {
  const key = Object.keys(PRODUCTS)[0];
  const lic = signLicense(SECRET, { v: 1, p: key, m: 'test', c: 'cus_1', s: null, iat: 1, exp: null });
  const { s, url } = await listen({});
  const res = await postTo(url, '/api/license/refresh', { license: lic });
  const portal = await postTo(url, '/api/portal', { license: lic });
  s.close();
  assert.equal(res.status, 503);
  assert.equal((await res.json()).reason, undefined);
  assert.equal(portal.status, 503);
});

test('an archived price is replaced instead of breaking checkout', async () => {
  const key = Object.keys(PRODUCTS)[0];
  await buy(key);
  await fetch(`${mock.url}/__test/archive-prices`, { method: 'POST' });
  // A fresh server instance (empty price cache) must create a new price that takes over the lookup key.
  const { s, url } = await listen({ STRIPE_SECRET_KEY: 'sk_test_mock', LICENSE_SECRET: SECRET, STRIPE_API_BASE: mock.url, RATE_LIMIT_PER_MIN: '1000' });
  const res = await postTo(url, '/api/checkout', { product: key });
  s.close();
  assert.equal(res.status, 200);
  const active = [...mock.db.prices.values()].filter(x => x.active && x.lookup_key && x.lookup_key.startsWith(`money-apps:${key}:`));
  assert.equal(active.length, 1);
});

test('portal: friendly error when Stripe refuses, and PORTAL_LOGIN_URL is used when set', async () => {
  const key = Object.keys(PRODUCTS)[0];
  const lic = signLicense(SECRET, { v: 1, p: key, m: 'test', c: 'cus_unknown', s: null, iat: 1, exp: null });
  let res = await post('/api/portal', { license: lic });
  assert.equal(res.status, 503);
  assert.match((await res.json()).error, /not available/);
  const { s, url } = await listen({ STRIPE_SECRET_KEY: 'sk_test_mock', LICENSE_SECRET: SECRET, STRIPE_API_BASE: mock.url, PORTAL_LOGIN_URL: 'https://billing.stripe.com/p/login/test_abc', SUPPORT_EMAIL: 'help@example.com' });
  res = await postTo(url, '/api/portal', { license: lic });
  const products = await (await fetch(url + '/api/products')).json();
  s.close();
  assert.equal((await res.json()).url, 'https://billing.stripe.com/p/login/test_abc');
  assert.equal(products.support, 'help@example.com');
});

test('TRUST_PROXY uses the address the proxy appended, not one the client sent', async () => {
  const { s, url } = await listen({ TRUST_PROXY: '1', RATE_LIMIT_PER_MIN: '3' });
  const codes = [];
  for (let i = 0; i < 5; i++) {
    codes.push((await postTo(url, '/api/license/refresh', { license: 'x' }, { 'X-Forwarded-For': `10.0.${i}.1, 203.0.113.7` })).status);
  }
  // A different real client (rightmost entry) has its own bucket.
  codes.push((await postTo(url, '/api/license/refresh', { license: 'x' }, { 'X-Forwarded-For': '203.0.113.8' })).status);
  s.close();
  assert.deepEqual(codes, [503, 503, 503, 429, 429, 503]);
});

test('rate-limit keys group IPv6 by /64 and unwrap IPv4-mapped addresses', () => {
  assert.equal(rateKey('2001:db8:1:2:aaaa::1'), rateKey('2001:db8:1:2:bbbb:cccc:dddd:eeee'));
  assert.notEqual(rateKey('2001:db8:1:2::1'), rateKey('2001:db8:1:3::1'));
  assert.equal(rateKey('::ffff:203.0.113.7'), '203.0.113.7');
  assert.equal(rateKey('203.0.113.7'), '203.0.113.7');
});
