'use strict';
// Browser test: buys Quick Invoice Pro end to end against the mock Stripe.
// Needs Playwright (`npm i -D playwright && npx playwright install chromium`); skipped otherwise.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { createServer, loadConfig } = require('../server.js');
const { createMockStripe } = require('./mock-stripe.js');

let chromium;
try { ({ chromium } = require('playwright')); } catch { /* skipped below */ }

const PNG_1PX = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
let mock, server, base, browser;

before(async () => {
  if (!chromium) return;
  mock = createMockStripe({ secretKey: 'sk_test_mock' });
  const stripeUrl = await mock.listen();
  const cfg = loadConfig({ STRIPE_SECRET_KEY: 'sk_test_mock', LICENSE_SECRET: 'z'.repeat(40), STRIPE_API_BASE: stripeUrl, RATE_LIMIT_PER_MIN: '10000' });
  server = createServer(cfg);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  cfg.appUrl = base; // Checkout's success/cancel URLs must point back at this server
  browser = await chromium.launch();
});
after(async () => { if (browser) await browser.close(); if (server) server.close(); if (mock) await mock.close(); });

function watch(page) {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  // A 402 is how the server reports an ended subscription, which the browser also logs.
  page.on('console', m => { if (m.type() === 'error' && !/status of 402/.test(m.text())) errors.push(m.text()); });
  return errors;
}

test('Quick Invoice: upgrade, use Pro, restore on another device, lose Pro on cancel', { skip: !chromium && 'playwright not installed' }, async () => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errors = watch(page);
  await page.goto(`${base}/invoice-maker.html`);

  // Free: footer shows, Pro tools are locked and open the upsell
  assert.match(await page.textContent('#inv'), /Made with Quick Invoice/);
  await page.click('#payLink');
  await page.waitForSelector('.pay-modal [data-pay="buy"]');
  await page.keyboard.press('Escape');
  assert.equal(await page.$('.pay-modal'), null);

  // Checkout
  await page.click('#proPanel [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  assert.match(await page.textContent('#amount'), /499 usd \(subscription\)/);
  await page.click('#pay');
  await page.waitForURL(`${base}/invoice-maker.html`); // session_id is stripped from the URL
  await page.waitForSelector('.pay-toast');
  assert.match(await page.textContent('.pay-toast'), /Pro unlocked/);
  await page.waitForFunction(() => !document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));
  assert.match(await page.textContent('#proPanel'), /Quick Invoice Pro is active/);

  // Pro features work
  await page.fill('#payLink', 'https://buy.stripe.com/test_123');
  await page.setInputFiles('#logo', { name: 'logo.png', mimeType: 'image/png', buffer: PNG_1PX });
  await page.waitForSelector('#inv img.logo');
  assert.equal(await page.getAttribute('#inv a.pay', 'href'), 'https://buy.stripe.com/test_123');
  await page.click('[data-color="#0f766e"]');
  assert.match(await page.getAttribute('#inv', 'style'), /#0f766e/);
  await page.fill('#to', 'Acme Corp');
  page.once('dialog', d => d.accept());
  await page.click('#saveClient');
  assert.match(await page.textContent('#clientPick'), /Acme Corp/);

  // Pro survives a reload
  await page.reload();
  await page.waitForSelector('#inv a.pay');

  // Restore on another device with the license key
  const key = await page.evaluate(() => localStorage.getItem('moneyapps.license.invoice-pro'));
  const other = await (await browser.newContext()).newPage();
  const otherErrors = watch(other);
  await other.goto(`${base}/invoice-maker.html`);
  other.once('dialog', d => d.accept(key));
  await other.click('#proPanel [data-pay="restore"]');
  await other.waitForFunction(() => !document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));

  // Manage subscription goes to the Stripe customer portal
  await page.click('#proPanel [data-pay="manage"]');
  await page.waitForURL(/\/portal\?customer=cus_/);
  await page.goto(`${base}/invoice-maker.html`);

  // Cancel in Stripe → next refresh drops Pro
  const sub = [...mock.db.subscriptions.values()][0];
  await fetch(`${mock.url}/__test/subscription`, { method: 'POST', body: new URLSearchParams({ id: sub.id, status: 'canceled' }) });
  assert.equal(await page.evaluate(() => pro.refresh(true)), false);
  await page.waitForFunction(() => document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));
  assert.equal(await page.$('#inv a.pay'), null);
  assert.match(await page.textContent('.pay-toast'), /subscription has ended/);

  assert.deepEqual(errors, []);
  assert.deepEqual(otherErrors, []);
  await ctx.close();
});

test('Quick Invoice: cancelling checkout leaves you on the free plan', { skip: !chromium && 'playwright not installed' }, async () => {
  const page = await (await browser.newContext()).newPage();
  const errors = watch(page);
  await page.goto(`${base}/invoice-maker.html`);
  await page.click('#proPanel [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  await page.click('#cancel');
  await page.waitForURL(`${base}/invoice-maker.html`);
  await page.waitForSelector('.pay-toast');
  assert.match(await page.textContent('.pay-toast'), /cancelled/);
  assert.match(await page.textContent('#inv'), /Made with Quick Invoice/);
  assert.deepEqual(errors, []);
});

test('Quick Invoice: a forged license edited into localStorage does not survive a refresh', { skip: !chromium && 'playwright not installed' }, async () => {
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${base}/invoice-maker.html`);
  await page.evaluate(() => {
    const body = btoa(JSON.stringify({ v: 1, p: 'invoice-pro', c: 'cus_x', s: 'sub_x', iat: 0, exp: Date.now() + 1e9 })).replace(/=+$/, '');
    localStorage.setItem('moneyapps.license.invoice-pro', body + '.forged');
  });
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));
  assert.equal(await page.evaluate(() => localStorage.getItem('moneyapps.license.invoice-pro')), null);
});

async function buyInvoicePro(page) {
  await page.click('#proPanel [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  await page.click('#pay');
}

test('Quick Invoice: a purchase survives a failed first license exchange', { skip: !chromium && 'playwright not installed' }, async () => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(`${base}/invoice-maker.html`);
  let failures = 0;
  await page.route('**/api/license', route => { failures++; return route.abort('connectionreset'); });
  await buyInvoicePro(page);
  await page.waitForFunction(() => /try again automatically/.test(document.querySelector('.pay-toast')?.textContent || ''), null, { timeout: 15000 });
  assert.ok(failures >= 1);
  assert.match(await page.evaluate(() => localStorage.getItem('moneyapps.pending.invoice-pro')), /cs_test_/);
  assert.match(await page.textContent('#inv'), /Made with Quick Invoice/);
  // Connection back: the next visit claims the purchase
  await page.unroute('**/api/license');
  await page.reload();
  await page.waitForFunction(() => !document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));
  assert.equal(await page.evaluate(() => localStorage.getItem('moneyapps.pending.invoice-pro')), null);
  await ctx.close();
});

test('Quick Invoice: keys survive server errors, other tabs update, Back re-enables Upgrade', { skip: !chromium && 'playwright not installed' }, async () => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const other = await ctx.newPage();
  await page.goto(`${base}/invoice-maker.html`);
  await other.goto(`${base}/invoice-maker.html`);
  assert.equal(await other.textContent('#proPanel .pay-price'), '$4.99/month'); // price comes from products.json via the server

  // Back from Checkout without paying: the button must not stay stuck on "Opening checkout…"
  await page.click('#proPanel [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  await page.goBack();
  await page.waitForSelector('#proPanel [data-pay="buy"]:not([disabled])');

  await buyInvoicePro(page);
  await page.waitForFunction(() => !document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));
  await other.waitForFunction(() => !document.querySelector('#inv').textContent.includes('Made with Quick Invoice'));

  // A misbehaving server or proxy (503, HTML, 401 without our reason) must not delete a paid key
  for (const reply of [{ status: 503, body: 'Service Unavailable' }, { status: 200, body: '<html>captive portal</html>' }, { status: 401, body: '{"error":"proxy auth"}', contentType: 'application/json' }]) {
    await page.route('**/api/license/refresh', route => route.fulfill(reply));
    assert.equal(await page.evaluate(() => pro.refresh(true)), true, JSON.stringify(reply));
    await page.unroute('**/api/license/refresh');
  }
  assert.ok(await page.evaluate(() => localStorage.getItem('moneyapps.license.invoice-pro')));
  await ctx.close();
});

test('Quick Invoice: keyboard users can tab past Pro fields; the dialog traps and returns focus', { skip: !chromium && 'playwright not installed' }, async () => {
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${base}/invoice-maker.html`);
  await page.focus('#payLink');
  await page.keyboard.press('Tab');
  assert.equal(await page.$('.pay-modal'), null, 'focusing alone must not open the upsell');
  await page.focus('#payLink');
  await page.keyboard.press('a');
  await page.waitForSelector('.pay-modal');
  assert.equal(await page.inputValue('#payLink'), '');
  for (let i = 0; i < 6; i++) await page.keyboard.press('Tab');
  assert.ok(await page.evaluate(() => document.querySelector('.pay-dialog').contains(document.activeElement)), 'focus stays in the dialog');
  await page.keyboard.press('Escape');
  assert.equal(await page.evaluate(() => document.activeElement.id), 'payLink');
});

test('Quick Invoice: printing shows only the invoice', { skip: !chromium && 'playwright not installed' }, async () => {
  const page = await (await browser.newContext()).newPage();
  await page.goto(`${base}/invoice-maker.html`);
  await page.emulateMedia({ media: 'print' });
  assert.equal(await page.isVisible('h1'), false);
  assert.equal(await page.isVisible('#proPanel'), false);
  assert.equal(await page.isVisible('#inv'), true);
  const today = await page.evaluate(() => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); });
  assert.match(await page.textContent('#inv'), new RegExp(today));
});
