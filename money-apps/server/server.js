#!/usr/bin/env node
// Money Apps payments server.
//
// Sells "Pro" unlocks with Stripe Checkout and serves the app pages. It has no
// dependencies (Node 18.17+) and no database: after checkout it hands the browser a
// signed license key, and for subscriptions it re-checks the subscription with
// Stripe whenever the browser refreshes that key.
//
// Environment (see README.md):
//   STRIPE_SECRET_KEY        sk_test_... or sk_live_...           (required for payments)
//   LICENSE_SECRET           long random string used to sign keys (required with a Stripe key)
//   APP_URL                  public URL of the folder holding the app pages (required in live mode)
//   PORT                     default 4242
//   SUPPORT_EMAIL            shown to customers when something goes wrong
//   ALLOWED_ORIGINS          comma-separated origins allowed to call the API cross-origin
//   TRUST_PROXY              number of reverse proxies in front of the server (e.g. 1), so
//                            rate limits use the client IP your proxy reports in X-Forwarded-For
//   RATE_LIMIT_PER_MIN       API POSTs allowed per IP per minute (default 30)
//   PORTAL_LOGIN_URL         optional Stripe customer-portal login link (https://billing.stripe.com/p/login/...)
//   STRIPE_PRICE_<PRODUCT>   optional existing Stripe price ID per product, e.g. STRIPE_PRICE_INVOICE_PRO
//   STRIPE_API_BASE          override the Stripe API URL (used by the tests' mock Stripe)
'use strict';

const http = require('node:http');
const net = require('node:net');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PRODUCTS = JSON.parse(fs.readFileSync(path.join(__dirname, 'products.json'), 'utf8'));
const isProduct = key => typeof key === 'string' && Object.hasOwn(PRODUCTS, key);
const ACTIVE_SUB_STATES = new Set(['active', 'trialing', 'past_due']); // past_due: Stripe is still retrying the card
const LICENSE_TTL_MS = 7 * 24 * 60 * 60 * 1000; // subscription keys must be refreshed within a week
const STATIC_TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json',
};

function loadConfig(env = process.env) {
  const port = Number(env.PORT) || 4242;
  const stripeKey = env.STRIPE_SECRET_KEY || '';
  const origins = new Set();
  for (const raw of (env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean)) {
    try { origins.add(new URL(raw).origin); } catch { console.warn(`[money-apps] Ignoring invalid ALLOWED_ORIGINS entry: ${raw}`); }
  }
  const hops = env.TRUST_PROXY === 'true' ? 1 : Math.max(0, parseInt(env.TRUST_PROXY, 10) || 0);
  return {
    port,
    stripeKey,
    mode: stripeKey ? (/_live_/.test(stripeKey) ? 'live' : 'test') : null,
    stripeApi: (env.STRIPE_API_BASE || 'https://api.stripe.com').replace(/\/+$/, ''),
    licenseSecret: env.LICENSE_SECRET || '',
    appUrl: (env.APP_URL || `http://localhost:${port}`).replace(/\/+$/, ''),
    appUrlSet: Boolean(env.APP_URL),
    support: (env.SUPPORT_EMAIL || '').trim(),
    portalLoginUrl: (env.PORTAL_LOGIN_URL || '').trim(),
    allowedOrigins: origins,
    trustProxyHops: hops,
    rateLimit: Number(env.RATE_LIMIT_PER_MIN) || 30,
    priceOverrides: Object.fromEntries(Object.keys(PRODUCTS).map(k =>
      [k, env['STRIPE_PRICE_' + k.toUpperCase().replace(/[^A-Z0-9]/g, '_')] || ''])),
  };
}

// Catches settings that would take money but then strand the customer.
function checkConfig(cfg) {
  const problems = [];
  if (cfg.stripeKey && cfg.licenseSecret.length < 32) {
    problems.push('LICENSE_SECRET must be set to a random string of at least 32 characters when STRIPE_SECRET_KEY is set.\n' +
      "Generate one with: node -e \"console.log(require('crypto').randomBytes(32).toString('hex'))\"");
  }
  let app;
  try { app = new URL(cfg.appUrl); } catch { /* reported below */ }
  if (!app || !/^https?:$/.test(app.protocol)) problems.push(`APP_URL must be a full http(s) URL, got "${cfg.appUrl}".`);
  else if (cfg.mode === 'live' && (!cfg.appUrlSet || app.protocol !== 'https:' || /^(localhost|127\.|\[::1\])/.test(app.host))) {
    problems.push('In live mode APP_URL must be set to the public https:// address of your app pages ' +
      '(e.g. https://apps.example.com), otherwise Stripe would send paying customers to localhost.');
  }
  if (cfg.portalLoginUrl && !/^https:\/\//.test(cfg.portalLoginUrl)) problems.push('PORTAL_LOGIN_URL must start with https://');
  if (problems.length) throw new Error(problems.join('\n'));
}

// ---------- Licenses: base64url(JSON payload) + "." + base64url(HMAC-SHA256) ----------
// Payload: { v: 1, p: product, m: 'test'|'live', c: customer, s: subscription|null, iat, exp|null }

const b64url = buf => Buffer.from(buf).toString('base64url');

function signLicense(secret, payload) {
  const body = b64url(JSON.stringify(payload));
  return body + '.' + b64url(crypto.createHmac('sha256', secret).update(body).digest());
}

// `mode`, when given, must match the key's mode, so keys bought with test cards don't work in live mode.
function verifyLicense(secret, token, mode) {
  if (!secret || typeof token !== 'string' || token.length > 2048) return null;
  const [body, sig, extra] = token.split('.');
  if (!body || !sig || extra !== undefined) return null;
  const want = crypto.createHmac('sha256', secret).update(body).digest();
  const got = Buffer.from(sig, 'base64url');
  if (got.length !== want.length || !crypto.timingSafeEqual(got, want)) return null;
  try {
    const p = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (!p || p.v !== 1 || !isProduct(p.p)) return null;
    if (mode && p.m !== mode) return null;
    return p;
  } catch { return null; }
}

// ---------- Stripe REST client ----------

class StripeError extends Error {
  constructor(status, body) {
    super((body && body.error && body.error.message) || `Stripe request failed (${status})`);
    this.status = status;
    this.code = body && body.error && body.error.code;
  }
}

function makeStripe(cfg) {
  return async function stripe(method, pathname, params = {}) {
    const query = new URLSearchParams(params).toString();
    const isGet = method === 'GET';
    const res = await fetch(cfg.stripeApi + pathname + (isGet && query ? '?' + query : ''), {
      method,
      headers: {
        Authorization: 'Bearer ' + cfg.stripeKey,
        ...(isGet ? {} : { 'Content-Type': 'application/x-www-form-urlencoded' }),
      },
      body: isGet ? undefined : query,
      signal: AbortSignal.timeout(20000),
    });
    const json = await res.json().catch(() => null);
    if (!res.ok) throw new StripeError(res.status, json);
    return json;
  };
}

// Finds (or creates once) the Stripe price for a product, so no dashboard setup is needed.
// The lookup key includes the amount and interval, so editing products.json makes a new price.
// If someone archives the price in the dashboard, a new one takes over the lookup key.
function makePriceResolver(cfg, stripe) {
  const cache = new Map();
  return function priceFor(key) {
    if (cfg.priceOverrides[key]) return Promise.resolve(cfg.priceOverrides[key]);
    if (!cache.has(key)) {
      const p = PRODUCTS[key];
      const lookupKey = `money-apps:${key}:${p.amount}${p.currency}:${p.mode === 'subscription' ? p.interval : 'once'}`;
      const find = async () => (await stripe('GET', '/v1/prices', { 'lookup_keys[]': lookupKey, active: 'true', limit: '1' })).data[0];
      const pending = (async () => {
        const existing = await find();
        if (existing) return existing.id;
        const params = {
          currency: p.currency, unit_amount: String(p.amount), lookup_key: lookupKey, transfer_lookup_key: 'true',
          'product_data[name]': p.name, 'product_data[metadata][money_apps_product]': key,
        };
        if (p.mode === 'subscription') params['recurring[interval]'] = p.interval;
        try {
          return (await stripe('POST', '/v1/prices', params)).id;
        } catch (err) {
          const raced = await find(); // another server instance may have created it first
          if (raced) return raced.id;
          throw err;
        }
      })();
      cache.set(key, pending);
      pending.catch(() => cache.delete(key)); // retry on the next request
    }
    return cache.get(key);
  };
}

// ---------- HTTP helpers ----------

class HttpError extends Error {
  constructor(status, message, extra, headers) { super(message); this.status = status; this.extra = extra; this.headers = headers; }
}

function readJson(req, limit = 16 * 1024) {
  return new Promise((resolve, reject) => {
    const tooBig = () => {
      // Answer right away, discard the rest of the upload, and close the connection afterwards.
      req.removeAllListeners('data');
      req.resume();
      reject(new HttpError(413, 'Request body too large', undefined, { Connection: 'close' }));
    };
    if (Number(req.headers['content-length']) > limit) return tooBig();
    let size = 0; const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > limit) return tooBig();
      chunks.push(c);
    });
    req.on('end', () => {
      if (size > limit) return;
      if (!chunks.length) return resolve({});
      try {
        const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        resolve(v && typeof v === 'object' && !Array.isArray(v) ? v : {});
      } catch { reject(new HttpError(400, 'Invalid JSON')); }
    });
    req.on('error', reject);
  });
}

function send(res, status, body, headers = {}) {
  const isJson = typeof body !== 'string' && !Buffer.isBuffer(body);
  const data = isJson ? JSON.stringify(body) : body;
  res.writeHead(status, {
    'Content-Type': isJson ? 'application/json; charset=utf-8' : 'text/plain; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    ...headers,
  });
  res.end(data);
}

// Fixed-window limiter. The map is capped, so a flood of distinct addresses can't exhaust memory.
function makeRateLimiter({ perMinute = 30, maxKeys = 50000 } = {}) {
  const hits = new Map();
  setInterval(() => hits.clear(), 60 * 1000).unref();
  return key => {
    const n = (hits.get(key) || 0) + 1;
    if (n === 1 && hits.size >= maxKeys) return false;
    hits.set(key, n);
    return n <= perMinute;
  };
}

function expandIPv6(ip) {
  if (!ip.includes('::')) return ip.split(':');
  const [head, tail] = ip.split('::');
  const h = head ? head.split(':') : [];
  const t = tail ? tail.split(':') : [];
  return [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t];
}

// IPv6 users usually control a whole /64, so they share one rate-limit bucket.
function rateKey(ip) {
  ip = String(ip || '').replace(/%.*$/, '');
  if (ip.startsWith('::ffff:') && net.isIPv4(ip.slice(7))) return ip.slice(7);
  if (net.isIPv6(ip)) return expandIPv6(ip.toLowerCase()).slice(0, 4).map(g => g.replace(/^0+(?=.)/, '')).join(':') + '::/64';
  return ip || '?';
}

function formatPrice(p) {
  return { name: p.name, mode: p.mode, amount: p.amount, currency: p.currency, interval: p.mode === 'subscription' ? p.interval : null };
}

// ---------- App ----------

function createServer(cfg = loadConfig()) {
  checkConfig(cfg);
  const stripe = makeStripe(cfg);
  const priceFor = makePriceResolver(cfg, stripe);
  const allow = makeRateLimiter({ perMinute: cfg.rateLimit });
  const contact = cfg.support ? ` Contact ${cfg.support} if you need help.` : '';

  function requireStripe() {
    if (!cfg.stripeKey) throw new HttpError(503, 'Payments are not configured on this server (STRIPE_SECRET_KEY is missing).');
  }

  function issue(product, customer, subscription) {
    const now = Date.now();
    const payload = { v: 1, p: product, m: cfg.mode, c: customer || null, s: subscription || null, iat: now, exp: subscription ? now + LICENSE_TTL_MS : null };
    return { license: signLicense(cfg.licenseSecret, payload), product, expires: payload.exp };
  }

  // A key is only called invalid when this server can actually check it, so a
  // misconfigured server never makes browsers throw away keys people paid for.
  function licenseFrom(body) {
    if (!cfg.stripeKey || cfg.licenseSecret.length < 32) throw new HttpError(503, 'Licensing is not configured on this server.');
    const lic = verifyLicense(cfg.licenseSecret, body.license, cfg.mode);
    if (!lic) throw new HttpError(401, 'This license key is not valid.' + contact, { reason: 'invalid' });
    return lic;
  }

  const api = {
    'GET /api/health': async () => ({ ok: true, payments: Boolean(cfg.stripeKey) }),

    'GET /api/products': async () => ({
      payments: Boolean(cfg.stripeKey),
      support: cfg.support || null,
      products: Object.fromEntries(Object.keys(PRODUCTS).map(k => [k, formatPrice(PRODUCTS[k])])),
    }),

    'POST /api/checkout': async (req, body) => {
      requireStripe();
      const key = body.product;
      if (!isProduct(key)) throw new HttpError(400, 'Unknown product.');
      const p = PRODUCTS[key];
      const page = `${cfg.appUrl}/${p.page}`;
      const params = {
        mode: p.mode,
        'line_items[0][price]': await priceFor(key),
        'line_items[0][quantity]': '1',
        success_url: `${page}?session_id={CHECKOUT_SESSION_ID}`,
        cancel_url: `${page}?checkout=cancelled`,
        allow_promotion_codes: 'true',
        'metadata[money_apps_product]': key,
      };
      if (p.mode === 'subscription') params['subscription_data[metadata][money_apps_product]'] = key;
      else params.customer_creation = 'always';
      const session = await stripe('POST', '/v1/checkout/sessions', params);
      return { url: session.url };
    },

    // Exchanges a completed Checkout Session (from the success redirect) for a license key.
    // Safe to call repeatedly: the browser retries until it gets an answer.
    'POST /api/license': async (req, body) => {
      requireStripe();
      if (cfg.licenseSecret.length < 32) throw new HttpError(503, 'Licensing is not configured on this server.');
      const id = body.session_id;
      if (typeof id !== 'string' || !/^cs_(test|live)_[A-Za-z0-9]{1,250}$/.test(id)) throw new HttpError(400, 'Invalid checkout session.');
      let session;
      try {
        session = await stripe('GET', `/v1/checkout/sessions/${id}`, { 'expand[]': 'subscription' });
      } catch (err) {
        if (err instanceof StripeError && err.status === 404) throw new HttpError(404, 'Checkout session not found.' + contact);
        throw err;
      }
      const key = session.metadata && session.metadata.money_apps_product;
      if (!isProduct(key) || PRODUCTS[key].mode !== session.mode) throw new HttpError(400, 'This checkout session is not for a Money Apps product.');
      if (session.status !== 'complete') throw new HttpError(402, 'Checkout was not completed.', { reason: 'incomplete' });
      if (!['paid', 'no_payment_required'].includes(session.payment_status)) {
        // e.g. bank debits that take a few days to clear
        throw new HttpError(402, 'Your payment is still processing.', { reason: 'unpaid' });
      }
      const customer = typeof session.customer === 'string' ? session.customer : session.customer && session.customer.id;
      if (session.mode === 'subscription') {
        const sub = session.subscription;
        if (!sub || typeof sub !== 'object' || !ACTIVE_SUB_STATES.has(sub.status)) {
          throw new HttpError(402, 'The subscription is not active.' + contact, { reason: 'inactive', status: sub && sub.status });
        }
        return issue(key, customer, sub.id);
      }
      return issue(key, customer, null);
    },

    // Re-checks a subscription with Stripe and returns a fresh key (lifetime keys come back unchanged).
    'POST /api/license/refresh': async (req, body) => {
      const lic = licenseFrom(body);
      if (!lic.s) return { license: body.license, product: lic.p, expires: null };
      let sub;
      try {
        sub = await stripe('GET', `/v1/subscriptions/${encodeURIComponent(lic.s)}`);
      } catch (err) {
        if (err instanceof StripeError && err.status === 404) throw new HttpError(402, 'The subscription no longer exists.', { reason: 'inactive', status: 'missing' });
        throw err;
      }
      if (!ACTIVE_SUB_STATES.has(sub.status)) throw new HttpError(402, 'The subscription is not active.', { reason: 'inactive', status: sub.status });
      return issue(lic.p, lic.c, lic.s);
    },

    // Opens the Stripe customer portal (cancel, change card, download invoices).
    'POST /api/portal': async (req, body) => {
      const lic = licenseFrom(body);
      if (cfg.portalLoginUrl) return { url: cfg.portalLoginUrl }; // Stripe emails the customer a one-time code
      if (!lic.c) throw new HttpError(400, 'This license has no customer account to manage.' + contact);
      try {
        const session = await stripe('POST', '/v1/billing_portal/sessions', {
          customer: lic.c, return_url: `${cfg.appUrl}/${PRODUCTS[lic.p].page}`,
        });
        return { url: session.url };
      } catch (err) {
        if (err instanceof StripeError && err.status >= 400 && err.status < 500) {
          console.error(`[money-apps] Customer portal unavailable: ${err.message} (activate it under Stripe Settings → Billing → Customer portal)`);
          throw new HttpError(503, 'Subscription management is not available right now.' + contact);
        }
        throw err;
      }
    },
  };

  function corsHeaders(req) {
    const origin = req.headers.origin;
    if (!origin || !cfg.allowedOrigins.has(origin)) return {};
    return { 'Access-Control-Allow-Origin': origin, 'Vary': 'Origin', 'Access-Control-Allow-Methods': 'GET, POST', 'Access-Control-Allow-Headers': 'Content-Type', 'Access-Control-Max-Age': '600' };
  }

  // With TRUST_PROXY=N, the client is the Nth address from the right of X-Forwarded-For:
  // each trusted proxy appends what it saw, and everything further left is client-supplied.
  function clientIp(req) {
    let ip = req.socket.remoteAddress;
    if (cfg.trustProxyHops > 0) {
      const chain = String(req.headers['x-forwarded-for'] || '').split(',').map(s => s.trim()).filter(Boolean);
      const candidate = chain[chain.length - cfg.trustProxyHops];
      if (candidate && net.isIP(candidate.replace(/%.*$/, ''))) ip = candidate;
    }
    return rateKey(ip);
  }

  async function serveStatic(req, res, pathname) {
    let rel;
    try { rel = decodeURIComponent(pathname); } catch { return send(res, 400, 'Bad request'); }
    if (rel === '/' || rel === '') rel = '/index.html';
    const file = path.resolve(ROOT, '.' + rel);
    const inServerDir = file === __dirname || file.startsWith(__dirname + path.sep);
    const type = STATIC_TYPES[path.extname(file).toLowerCase()];
    if (!file.startsWith(ROOT + path.sep) || inServerDir || !type || rel.split('/').some(s => s.startsWith('.'))) return send(res, 404, 'Not found');
    let data;
    try { data = await fs.promises.readFile(file); } catch { return send(res, 404, 'Not found'); }
    res.writeHead(200, {
      'Content-Type': type, 'Content-Length': data.length, 'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'same-origin',
    });
    res.end(req.method === 'HEAD' ? undefined : data);
  }

  async function handle(req, res) {
    let url;
    try { url = new URL(req.url, 'http://localhost'); } catch { return send(res, 400, 'Bad request'); }
    const isApi = url.pathname.startsWith('/api/');
    const cors = isApi ? corsHeaders(req) : {};
    try {
      if (!isApi) {
        if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed');
        return await serveStatic(req, res, url.pathname);
      }
      if (req.method === 'OPTIONS') { res.writeHead(204, cors); return res.end(); }
      const handler = Object.hasOwn(api, `${req.method} ${url.pathname}`) && api[`${req.method} ${url.pathname}`];
      if (!handler) throw new HttpError(404, 'Not found.');
      if (req.method === 'POST' && !/^application\/json\b/i.test(req.headers['content-type'] || '')) throw new HttpError(415, 'Send JSON.');
      if (req.method === 'POST' && !allow(clientIp(req))) throw new HttpError(429, 'Too many requests. Try again in a minute.');
      const body = req.method === 'POST' ? await readJson(req) : {};
      send(res, 200, await handler(req, body), { ...cors, 'Cache-Control': 'no-store' });
    } catch (err) {
      if (err instanceof HttpError) return send(res, err.status, { error: err.message, ...err.extra }, { ...cors, 'Cache-Control': 'no-store', ...err.headers });
      console.error(`[money-apps] ${req.method} ${url.pathname} failed:`, err instanceof StripeError ? `${err.status} ${err.code || ''} ${err.message}` : err);
      send(res, 502, { error: 'The payment provider could not be reached. Please try again.' + contact }, { ...cors, 'Cache-Control': 'no-store' });
    }
  }

  const server = http.createServer((req, res) => {
    // Last line of defence: nothing a request does may take the whole process down.
    handle(req, res).catch(err => {
      console.error('[money-apps] Unhandled error:', err);
      if (!res.headersSent) send(res, 500, 'Server error'); else res.destroy();
    });
  });
  server.requestTimeout = 30 * 1000;
  server.headersTimeout = 15 * 1000;
  return server;
}

module.exports = { createServer, loadConfig, signLicense, verifyLicense, rateKey, PRODUCTS };

// Support tool: re-issue a lost license key for a customer you found in the Stripe dashboard.
//   STRIPE_SECRET_KEY=... LICENSE_SECRET=... node server.js issue-license <product> <cus_...> [sub_...]
function issueLicenseCli(args, cfg) {
  const [product, customer, subscription] = args;
  if (!isProduct(product) || !/^cus_\w+$/.test(customer || '') || (subscription && !/^sub_\w+$/.test(subscription))) {
    console.error(`Usage: node server.js issue-license <product> <cus_...> [sub_...]\nProducts: ${Object.keys(PRODUCTS).join(', ')}`);
    process.exit(1);
  }
  if (PRODUCTS[product].mode === 'subscription' && !subscription) {
    console.error(`${product} is a subscription: pass the customer's subscription ID (sub_...) too.`);
    process.exit(1);
  }
  if (cfg.licenseSecret.length < 32 || !cfg.mode) {
    console.error('Set LICENSE_SECRET and STRIPE_SECRET_KEY to the same values the server uses (the key decides test or live mode).');
    process.exit(1);
  }
  const now = Date.now();
  const sub = PRODUCTS[product].mode === 'subscription' ? subscription : null;
  // Subscription keys are issued already expired so the app re-checks the subscription with Stripe right away.
  console.log(signLicense(cfg.licenseSecret, { v: 1, p: product, m: cfg.mode, c: customer, s: sub, iat: 0, exp: sub ? now : null }));
}

if (require.main === module) {
  const cfg = loadConfig();
  if (process.argv[2] === 'issue-license') { issueLicenseCli(process.argv.slice(3), cfg); process.exit(0); }
  let server;
  try { server = createServer(cfg); } catch (err) { console.error(err.message); process.exit(1); }
  server.listen(cfg.port, () => {
    console.log(`Money Apps running at http://localhost:${cfg.port}`);
    if (!cfg.stripeKey) console.log('Payments are OFF: set STRIPE_SECRET_KEY and LICENSE_SECRET to sell Pro (see README.md).');
    else console.log(`Stripe ${cfg.mode.toUpperCase()} mode. Checkout returns to ${cfg.appUrl}`);
    if (cfg.stripeKey && !cfg.support) console.log('Tip: set SUPPORT_EMAIL so customers know who to contact.');
  });
}
