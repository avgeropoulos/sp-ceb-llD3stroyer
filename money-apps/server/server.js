#!/usr/bin/env node
// Money Apps payments server.
//
// Sells "Pro" unlocks with Stripe Checkout and serves the app pages. It has no
// dependencies (Node 18+) and no database: after checkout it hands the browser a
// signed license key, and for subscriptions it re-checks the subscription with
// Stripe whenever the browser refreshes that key.
//
// Environment:
//   STRIPE_SECRET_KEY   sk_test_... or sk_live_...           (required for payments)
//   LICENSE_SECRET      long random string used to sign keys (required with a Stripe key)
//   APP_URL             public URL where the app pages live  (default http://localhost:PORT)
//   PORT                default 4242
//   ALLOWED_ORIGINS     comma-separated origins allowed to call the API cross-origin
//   TRUST_PROXY         set to 1 behind a reverse proxy so rate limits use X-Forwarded-For
//   RATE_LIMIT_PER_MIN  POST requests allowed per IP per minute (default 30)
//   STRIPE_PRICE_<KEY>  optional existing Stripe price ID per product, e.g. STRIPE_PRICE_INVOICE_PRO
//   STRIPE_API_BASE     override the Stripe API URL (used by the tests' mock Stripe)
'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PRODUCTS = JSON.parse(fs.readFileSync(path.join(__dirname, 'products.json'), 'utf8'));
const ACTIVE_SUB_STATES = new Set(['active', 'trialing', 'past_due']); // past_due: Stripe is still retrying the card
const LICENSE_TTL_MS = 7 * 24 * 60 * 60 * 1000; // subscription keys must be refreshed within a week
const STATIC_TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json',
};

function loadConfig(env = process.env) {
  const port = Number(env.PORT) || 4242;
  return {
    port,
    stripeKey: env.STRIPE_SECRET_KEY || '',
    stripeApi: (env.STRIPE_API_BASE || 'https://api.stripe.com').replace(/\/+$/, ''),
    licenseSecret: env.LICENSE_SECRET || '',
    appUrl: (env.APP_URL || `http://localhost:${port}`).replace(/\/+$/, ''),
    allowedOrigins: new Set((env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean)),
    trustProxy: env.TRUST_PROXY === '1',
    rateLimit: Number(env.RATE_LIMIT_PER_MIN) || 30,
    priceOverrides: Object.fromEntries(Object.keys(PRODUCTS).map(k =>
      [k, env['STRIPE_PRICE_' + k.toUpperCase().replace(/[^A-Z0-9]/g, '_')] || ''])),
  };
}

// ---------- Licenses: base64url(JSON payload) + "." + base64url(HMAC-SHA256) ----------

const b64url = buf => Buffer.from(buf).toString('base64url');

function signLicense(secret, payload) {
  const body = b64url(JSON.stringify(payload));
  return body + '.' + b64url(crypto.createHmac('sha256', secret).update(body).digest());
}

function verifyLicense(secret, token) {
  if (typeof token !== 'string' || token.length > 2048) return null;
  const [body, sig, extra] = token.split('.');
  if (!body || !sig || extra !== undefined) return null;
  const want = crypto.createHmac('sha256', secret).update(body).digest();
  const got = Buffer.from(sig, 'base64url');
  if (got.length !== want.length || !crypto.timingSafeEqual(got, want)) return null;
  try {
    const p = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    return p && p.v === 1 && typeof p.p === 'string' && PRODUCTS[p.p] ? p : null;
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
          currency: p.currency, unit_amount: String(p.amount), lookup_key: lookupKey,
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

function makeRateLimiter({ perMinute = 30 } = {}) {
  const hits = new Map();
  setInterval(() => hits.clear(), 60 * 1000).unref();
  return ip => {
    const n = (hits.get(ip) || 0) + 1;
    hits.set(ip, n);
    return n <= perMinute;
  };
}

function publicProduct(key) {
  const p = PRODUCTS[key];
  return { name: p.name, mode: p.mode, amount: p.amount, currency: p.currency, interval: p.mode === 'subscription' ? p.interval : null };
}

// ---------- App ----------

function createServer(cfg = loadConfig()) {
  if (cfg.stripeKey && cfg.licenseSecret.length < 32) {
    throw new Error('LICENSE_SECRET must be set to a random string of at least 32 characters when STRIPE_SECRET_KEY is set.\n' +
      "Generate one with: node -e \"console.log(require('crypto').randomBytes(32).toString('hex'))\"");
  }
  const stripe = makeStripe(cfg);
  const priceFor = makePriceResolver(cfg, stripe);
  const allow = makeRateLimiter({ perMinute: cfg.rateLimit });

  function requireStripe() {
    if (!cfg.stripeKey) throw new HttpError(503, 'Payments are not configured on this server (STRIPE_SECRET_KEY is missing).');
  }

  function issue(product, customer, subscription) {
    const now = Date.now();
    const payload = { v: 1, p: product, c: customer || null, s: subscription || null, iat: now, exp: subscription ? now + LICENSE_TTL_MS : null };
    return { license: signLicense(cfg.licenseSecret, payload), product, expires: payload.exp };
  }

  function licenseFrom(body) {
    const lic = verifyLicense(cfg.licenseSecret, body.license);
    if (!lic) throw new HttpError(401, 'This license key is not valid.', { reason: 'invalid' });
    return lic;
  }

  const api = {
    'GET /api/health': async () => ({ ok: true, payments: Boolean(cfg.stripeKey) }),

    'GET /api/products': async () => ({
      payments: Boolean(cfg.stripeKey),
      products: Object.fromEntries(Object.keys(PRODUCTS).map(k => [k, publicProduct(k)])),
    }),

    'POST /api/checkout': async (req, body) => {
      requireStripe();
      const key = body.product;
      if (typeof key !== 'string' || !Object.hasOwn(PRODUCTS, key)) throw new HttpError(400, 'Unknown product.');
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
    'POST /api/license': async (req, body) => {
      requireStripe();
      const id = body.session_id;
      if (typeof id !== 'string' || !/^cs_(test|live)_[A-Za-z0-9]{1,250}$/.test(id)) throw new HttpError(400, 'Invalid checkout session.');
      let session;
      try {
        session = await stripe('GET', `/v1/checkout/sessions/${id}`, { 'expand[]': 'subscription' });
      } catch (err) {
        if (err instanceof StripeError && err.status === 404) throw new HttpError(404, 'Checkout session not found.');
        throw err;
      }
      const key = session.metadata && session.metadata.money_apps_product;
      if (!key || !Object.hasOwn(PRODUCTS, key) || PRODUCTS[key].mode !== session.mode) throw new HttpError(400, 'This checkout session is not for a Money Apps product.');
      if (session.status !== 'complete' || !['paid', 'no_payment_required'].includes(session.payment_status)) {
        throw new HttpError(402, 'Payment has not been completed.', { reason: 'unpaid' });
      }
      const customer = typeof session.customer === 'string' ? session.customer : session.customer && session.customer.id;
      if (session.mode === 'subscription') {
        const sub = session.subscription;
        if (!sub || typeof sub !== 'object' || !ACTIVE_SUB_STATES.has(sub.status)) {
          throw new HttpError(402, 'The subscription is not active.', { reason: 'inactive', status: sub && sub.status });
        }
        return issue(key, customer, sub.id);
      }
      return issue(key, customer, null);
    },

    // Re-checks a subscription with Stripe and returns a fresh key (lifetime keys come back unchanged).
    'POST /api/license/refresh': async (req, body) => {
      const lic = licenseFrom(body);
      if (!lic.s) return { license: body.license, product: lic.p, expires: null };
      requireStripe();
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
      requireStripe();
      const lic = licenseFrom(body);
      if (!lic.c) throw new HttpError(400, 'This license has no customer account to manage.');
      const session = await stripe('POST', '/v1/billing_portal/sessions', {
        customer: lic.c, return_url: `${cfg.appUrl}/${PRODUCTS[lic.p].page}`,
      });
      return { url: session.url };
    },
  };

  function corsHeaders(req) {
    const origin = req.headers.origin;
    if (!origin || !cfg.allowedOrigins.has(origin)) return {};
    return { 'Access-Control-Allow-Origin': origin, 'Vary': 'Origin', 'Access-Control-Allow-Methods': 'GET, POST', 'Access-Control-Allow-Headers': 'Content-Type', 'Access-Control-Max-Age': '600' };
  }

  function clientIp(req) {
    const fwd = cfg.trustProxy && req.headers['x-forwarded-for'];
    return (fwd ? String(fwd).split(',')[0].trim() : req.socket.remoteAddress) || '?';
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

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const isApi = url.pathname.startsWith('/api/');
    const cors = isApi ? corsHeaders(req) : {};
    try {
      if (!isApi) {
        if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed');
        return await serveStatic(req, res, url.pathname);
      }
      if (req.method === 'OPTIONS') { res.writeHead(204, cors); return res.end(); }
      const handler = api[`${req.method} ${url.pathname}`];
      if (!handler) throw new HttpError(404, 'Not found.');
      if (req.method === 'POST' && !/^application\/json\b/i.test(req.headers['content-type'] || '')) throw new HttpError(415, 'Send JSON.');
      if (req.method === 'POST' && !allow(clientIp(req))) throw new HttpError(429, 'Too many requests. Try again in a minute.');
      const body = req.method === 'POST' ? await readJson(req) : {};
      send(res, 200, await handler(req, body), { ...cors, 'Cache-Control': 'no-store' });
    } catch (err) {
      if (err instanceof HttpError) return send(res, err.status, { error: err.message, ...err.extra }, { ...cors, 'Cache-Control': 'no-store', ...err.headers });
      console.error(`[money-apps] ${req.method} ${url.pathname} failed:`, err instanceof StripeError ? `${err.status} ${err.code || ''} ${err.message}` : err);
      send(res, 502, { error: 'The payment provider could not be reached. Please try again.' }, { ...cors, 'Cache-Control': 'no-store' });
    }
  });
  server.requestTimeout = 30 * 1000;
  server.headersTimeout = 15 * 1000;
  return server;
}

module.exports = { createServer, loadConfig, signLicense, verifyLicense, PRODUCTS };

if (require.main === module) {
  let server;
  const cfg = loadConfig();
  try { server = createServer(cfg); } catch (err) { console.error(err.message); process.exit(1); }
  server.listen(cfg.port, () => {
    console.log(`Money Apps running at http://localhost:${cfg.port}`);
    if (!cfg.stripeKey) console.log('Payments are OFF: set STRIPE_SECRET_KEY and LICENSE_SECRET to sell Pro (see README.md).');
    else console.log(`Stripe ${cfg.stripeKey.startsWith('sk_live_') ? 'LIVE' : 'test'} mode. Checkout returns to ${cfg.appUrl}`);
  });
}
