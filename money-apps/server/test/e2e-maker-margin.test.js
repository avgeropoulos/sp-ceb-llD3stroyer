'use strict';
// Browser tests for MakerMargin (maker-margin.html): free flows and limits, buying Pro
// (one-time payment) through the mock Stripe Checkout, and the Pro features.
// Needs Playwright (`npm i -D playwright && npx playwright install chromium`); skipped otherwise.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createServer, loadConfig } = require('../server.js');
const { createMockStripe } = require('./mock-stripe.js');

let chromium;
try { ({ chromium } = require('playwright')); } catch { /* skipped below */ }
const skip = !chromium && 'playwright not installed';

let mock, server, base, browser, licenseKey, tmp;

const SALES_CSV = `Sale Date,Item Name,Buyer,Quantity,Price,Discount Amount,Order Shipping,Order Sales Tax,Item Total,Currency,Transaction ID,Listing ID,Order ID,SKU
11/02/25,8 oz Soy Candle - Lavender,Jane,2,24.00,0,5.00,3.12,48.00,USD,111,9001,5001,CAN-8
11/15/25,8 oz Soy Candle - Lavender,Sam,1,24.00,0,5.00,1.56,24.00,USD,112,9001,5002,CAN-8
12/03/25,Mystery Item,Al,1,10.00,0,0,0,10.00,USD,113,9002,5003,
`;
const STATEMENT_CSV = `Date,Type,Title,Info,Currency,Amount,Fees & Taxes,Net,Tax Details
"November 30, 2025",Fee,Transaction fee: 8 oz Soy Candle,,USD,--,-$3.12,-$3.12,--
"November 30, 2025",Marketing,Etsy Ads,,USD,--,-$4.50,-$4.50,--
"November 20, 2025",Shipping,USPS shipping label,,USD,--,-$9.40,-$9.40,--
`;
const csv = (name, text) => ({ name, mimeType: 'text/csv', buffer: Buffer.from(text) });

before(async () => {
  if (!chromium) return;
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mm-e2e-'));
  mock = createMockStripe({ secretKey: 'sk_test_mock' });
  const stripeUrl = await mock.listen();
  const cfg = loadConfig({ STRIPE_SECRET_KEY: 'sk_test_mock', LICENSE_SECRET: 'm'.repeat(40), STRIPE_API_BASE: stripeUrl, RATE_LIMIT_PER_MIN: '10000' });
  server = createServer(cfg);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  cfg.appUrl = base;
  browser = await chromium.launch();
});
after(async () => {
  if (browser) await browser.close();
  if (server) server.close();
  if (mock) await mock.close();
  if (tmp) fs.rmSync(tmp, { recursive: true, force: true });
});

function watch(page) {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  return errors;
}
async function open(opts = {}) {
  const ctx = await browser.newContext(opts);
  const page = await ctx.newPage();
  const errors = watch(page);
  await page.goto(`${base}/maker-margin.html`);
  return { ctx, page, errors };
}
const text = async (page, sel) => (await page.textContent(sel)).replace(/\s+/g, ' ').trim();
const tab = async (page, name) => { await page.click(`#tabs a[data-tab="${name}"]`); await page.waitForSelector(`#view[data-view="${name}"]`); };
async function candle(page) {
  await page.click('[data-act="kit"][data-kit="candle"]');
  await page.waitForSelector('#rCost');
  return page.evaluate(() => decodeURIComponent(location.hash).slice('#product='.length));
}
async function openProduct(page, name) {
  await tab(page, 'products');
  await page.click(`.pcard:has-text("${name}")`);
  await page.waitForSelector('#view[data-view="editor"] #rCost');
}
async function editMaterialPrice(page, name, price) {
  await tab(page, 'materials');
  await page.click(`#matTable tr:has-text("${name}") [data-act="editMat"]`);
  await page.fill('#mf_tab_price', String(price));
  await page.click('form[data-form="mat"] button[type="submit"]');
  await page.waitForSelector('form[data-form="mat"]', { state: 'detached' });
}
const stubPrint = page => page.evaluate(() => { window.__prints = 0; window.print = () => { window.__prints++; }; });
// The print sheet only renders in print media, so read it there (innerText keeps the table cell breaks).
async function sheet(page) {
  await page.emulateMedia({ media: 'print' });
  const t = await page.evaluate(() => document.getElementById('sheet').innerText);
  await page.emulateMedia({ media: 'screen' });
  return t;
}
async function download(page, selector) {
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click(selector)]);
  const file = path.join(tmp, dl.suggestedFilename());
  await dl.saveAs(file);
  return { name: dl.suggestedFilename(), file, body: fs.readFileSync(file, 'utf8') };
}
const toastText = async page => { await page.waitForSelector('.pay-toast.mm'); return text(page, '.pay-toast.mm'); };
async function importCsv(page, file) {
  await page.evaluate(() => document.querySelectorAll('.pay-toast.mm').forEach(t => t.remove()));
  await page.setInputFiles('#csvFiles', file);
  return toastText(page);
}

test('MakerMargin: first run, candle kit, cost per unit, suggested prices and the Etsy fee breakdown', { skip }, async () => {
  const { ctx, page, errors } = await open();
  assert.deepEqual(await page.locator('#firstRun .chip').allTextContents(), ['Candles', 'Soap', 'Cookies & cakes', 'Jewelry', 'Resin', 'Start from scratch']);

  const id = await candle(page);
  assert.equal(await text(page, '#edTitle'), '8 oz Soy Candle');
  assert.match(await text(page, '#exampleNote'), /Example prices, replace with yours/);
  assert.equal(await text(page, '#rCost'), '$6.29');
  assert.equal(await text(page, '#rLab'), '$1.67');
  assert.equal(await text(page, '#rOvh'), '$0.57');
  assert.ok(Math.abs(await page.evaluate(i => MM.unitCost(i), id) - 6.2938) < 0.0001);
  const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('makermargin.v1')));
  assert.equal(stored.v, 1);
  assert.equal(stored.materials.length, 5);
  assert.equal(stored.products.length, 1);

  // Suggested prices with ending "none" (default)
  assert.equal(await text(page, '#sugW'), '$12.59');
  assert.equal(await text(page, '#sugR'), '$25.18');

  // Etsy US at $24, $0 shipping
  assert.equal(await text(page, '#fListing'), '−$0.20');
  assert.equal(await text(page, '#fTx'), '−$1.56');
  assert.equal(await text(page, '#fProc'), '−$0.97');
  assert.equal(await text(page, '#fTotal'), '$2.73');
  assert.equal(await text(page, '#fNet'), '$14.98');
  assert.equal(await text(page, '#fMargin'), '62.4%');
  assert.equal(await page.getAttribute('#fMargin .mb', 'class'), 'mb good');
  assert.equal(await text(page, '#fPph'), '$179.71/h');
  await page.check('#pAds');
  assert.equal(await text(page, '#fAds'), '−$3.60');
  assert.equal(await text(page, '#fTotal'), '$6.33');
  assert.equal(await text(page, '#fNet'), '$11.38');
  await page.uncheck('#pAds');
  assert.equal(await text(page, '#fNet'), '$14.98');

  // "How is this calculated?" shows the formula with this product's numbers
  await page.click('details.how summary');
  assert.match(await text(page, '#rHow'), /60 min ÷ 60 × \$20\.00\/h ÷ 12 = \$1\.67/);

  // What-if slider: live net profit, and "Use" sets the price
  await page.evaluate(() => { const s = document.getElementById('pSlide'); s.value = '3'; s.dispatchEvent(new Event('input', { bubbles: true })); });
  assert.match(await text(page, '#rWhat'), /At \$18\.88 \(3\.00× cost\)/);
  await page.click('#rWhat [data-act="usePrice"]');
  assert.equal(await page.inputValue('#pPrice'), '18.88');
  await page.fill('#pPrice', '24,00'); // commas are accepted
  assert.equal(await text(page, '#fNet'), '$14.98');

  // Price ending .99
  await tab(page, 'fees');
  await page.selectOption('#set_ending', '.99');
  await openProduct(page, '8 oz Soy Candle');
  assert.equal(await text(page, '#sugW'), '$12.99');
  assert.equal(await text(page, '#sugR'), '$25.99');
  await tab(page, 'fees');
  await page.selectOption('#set_ending', 'none');

  // Product list: counter, card with margin badge
  await tab(page, 'products');
  assert.equal(await text(page, '#pCount'), '1 of 5 products (free)');
  const card = await text(page, '.pcard');
  assert.match(card, /\$6\.29/);
  assert.match(card, /\$14\.98/);
  assert.match(card, /62\.4%/);

  // Free cost sheet has the footer
  await openProduct(page, '8 oz Soy Candle');
  await stubPrint(page);
  await page.click('[data-act="printCost"]');
  assert.equal(await page.evaluate(() => window.__prints), 1);
  const cs = await sheet(page);
  assert.match(cs, /8 oz Soy Candle/);
  assert.match(cs, /Cost per unit\s+\$6\.29/);
  assert.match(cs, /Made with MakerMargin · free product pricing calculator/);
  await page.emulateMedia({ media: 'print' });
  assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector('main')).display), 'none');
  assert.equal(await page.evaluate(() => getComputedStyle(document.getElementById('sheet')).display), 'block');
  await page.emulateMedia({ media: 'screen' });

  // Pro sections stay visible but locked; they open the upsell
  assert.ok(await page.isVisible('#rCh .blurwrap'));
  await page.click('#rCh [data-act="unlock"]');
  await page.waitForSelector('.pay-modal [data-pay="buy"]');
  assert.match(await text(page, '.pay-modal'), /MakerMargin Pro/);
  await page.keyboard.press('Escape');
  await tab(page, 'plan');
  assert.ok(await page.isVisible('#locked-plan .blurwrap'));
  await tab(page, 'profit');
  assert.ok(await page.isVisible('#locked-profit [data-act="unlock"]'));

  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: unit conversion, waste, unit families, inline new material, blocked delete', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await candle(page);

  await tab(page, 'materials');
  assert.equal(await text(page, '#mCount'), '5 of 20 materials (free)');
  assert.match(await text(page, '#matTable tr:has-text("Soy wax")'), /\$32\.00 for 10 lb.*\$0\.20 \/ oz.*1 product/);
  await page.click('[data-act="newMat"]');
  await page.fill('#mf_tab_name', 'Butter');
  await page.fill('#mf_tab_price', '4.80');
  await page.fill('#mf_tab_size', '1');
  await page.selectOption('#mf_tab_unit', 'lb');
  assert.match(await text(page, 'form[data-form="mat"] [data-preview]'), /\$0\.30 per oz/);
  await page.click('form[data-form="mat"] button[type="submit"]');
  await page.waitForSelector('#matTable tr:has-text("Butter")');

  await openProduct(page, '8 oz Soy Candle');
  await page.click('[data-act="addLine"]');
  const last = page.locator('#lines .line').last();
  await last.locator('.l-mat').selectOption({ label: 'Butter' });
  await last.locator('[data-lf="qty"]').fill('227');
  await last.locator('.l-unit').selectOption('g');
  assert.equal(await last.locator('[data-lc]').textContent(), '$2.40');
  await last.locator('[data-lf="waste"]').fill('10');
  assert.equal(await last.locator('[data-lc]').textContent(), '$2.64');
  assert.deepEqual(await last.locator('.l-unit option').allTextContents(), ['g', 'kg', 'oz', 'lb']);
  // A material sold by "each" only offers count units
  const wicks = page.locator('#lines .line').nth(2);
  assert.equal(await wicks.locator('.l-mat option:checked').textContent(), 'Cotton wicks');
  assert.deepEqual(await wicks.locator('.l-unit option').allTextContents(), ['each', 'dozen']);

  // "+ New material..." opens the material form inline and fills the line
  await page.click('[data-act="addLine"]');
  await page.locator('#lines .line').last().locator('.l-mat').selectOption('__new');
  await page.waitForSelector('#inlineMat form');
  await page.fill('#mf_inline_name', 'Gift box');
  await page.fill('#mf_inline_price', '18');
  await page.fill('#mf_inline_size', '12');
  await page.click('#inlineMat button[type="submit"]');
  await page.waitForSelector('#inlineMat form', { state: 'detached' });
  assert.equal(await page.locator('#lines .line').last().locator('.l-mat option:checked').textContent(), 'Gift box');
  assert.equal(await page.locator('#lines .line').last().locator('[data-lc]').textContent(), '$1.50');

  // Removing a line updates the cost
  const before = await text(page, '#rCost');
  await page.locator('#lines .line').last().locator('[data-act="rmLine"]').click();
  assert.notEqual(await text(page, '#rCost'), before);

  // Deleting a material that products use is blocked and lists them
  await tab(page, 'materials');
  await page.click('#matTable tr:has-text("Soy wax") [data-act="delMat"]');
  assert.match(await text(page, '#matErr'), /“Soy wax” is used in 8 oz Soy Candle/);
  assert.equal(await page.locator('#matTable tr:has-text("Soy wax")').count(), 1);
  // An unused material can be deleted after confirming
  page.once('dialog', d => d.accept());
  await page.click('#matTable tr:has-text("Gift box") [data-act="delMat"]');
  await page.waitForSelector('#matTable tr:has-text("Gift box")', { state: 'detached' });

  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: free limits open the upsell and create nothing; backup stays available', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await page.evaluate(() => {
    const materials = Array.from({ length: 20 }, (_, i) => ({ id: 'm' + i, name: 'Material ' + i, packPrice: 10, packSize: 10, packUnit: 'each' }));
    const products = Array.from({ length: 5 }, (_, i) => ({ id: 'p' + i, name: 'Product ' + i, yield: 1, lines: [{ matId: 'm' + i, qty: 1, unit: 'each', per: 'unit' }], laborMin: 10, price: 10 }));
    localStorage.setItem('makermargin.v1', JSON.stringify({ v: 1, settings: { lastBackup: Date.now() }, materials, products }));
  });
  await page.reload();
  const counts = () => page.evaluate(() => { const d = JSON.parse(localStorage.getItem('makermargin.v1')); return [d.products.length, d.materials.length]; });
  const expectUpsell = async () => {
    await page.waitForSelector('.pay-modal');
    assert.match(await text(page, '.pay-modal'), /Unlimited products and materials/);
    await page.click('.pay-modal .pay-close');
    assert.equal(await page.$('.pay-modal'), null);
  };

  assert.equal(await text(page, '#pCount'), '5 of 5 products (free)');
  await page.click('[data-act="newProduct"]');
  await expectUpsell();
  assert.equal(await page.locator('.pcard').count(), 5);

  await openProduct(page, 'Product 0');
  await page.click('[data-act="dup"]');
  await expectUpsell();
  await page.locator('#lines .line').first().locator('.l-mat').selectOption('__new');
  await expectUpsell();
  assert.equal(await page.$('#inlineMat form'), null);

  await tab(page, 'materials');
  assert.equal(await text(page, '#mCount'), '20 of 20 materials (free)');
  await page.click('.toolbar [data-act="newMat"]');
  await expectUpsell();
  assert.equal(await page.$('form[data-form="mat"]'), null);

  // A kit link with existing data offers to add, which would also go over the cap
  await page.goto(`${base}/maker-margin.html?kit=candle`);
  await page.click('#kitOffer [data-act="addKit"]');
  await expectUpsell();
  assert.deepEqual(await counts(), [5, 20]);

  // Backup and restore stay free
  await tab(page, 'backup');
  const b = await download(page, 'button[data-act="backup"]');
  assert.match(b.name, /^makermargin-backup-\d{4}-\d{2}-\d{2}\.json$/);
  assert.equal(JSON.parse(b.body).products.length, 5);
  assert.equal(await page.locator('#restoreFile').count(), 1);

  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: free price change updates costs with a teaser; backup, wipe, restore; backup banner', { skip }, async () => {
  const { ctx, page, errors } = await open();
  const id = await candle(page);

  await editMaterialPrice(page, 'Soy wax', 40);
  assert.match(await text(page, '#teaser'), /See which products just fell below your target margin \(Pro\)/);
  assert.equal(await page.$('#ripple'), null);
  assert.ok(Math.abs(await page.evaluate(i => MM.unitCost(i), id) - 6.6238) < 0.0001);
  await openProduct(page, '8 oz Soy Candle');
  assert.equal(await text(page, '#rCost'), '$6.62');
  assert.equal(await text(page, '#fNet'), '$14.65');

  // No banner right after first use
  assert.equal(await page.$('#backupBanner'), null);

  // Backup, wipe, reload (first-run chips), restore
  await tab(page, 'backup');
  const b = await download(page, 'button[data-act="backup"]');
  const data = JSON.parse(b.body);
  assert.equal(data.v, 1);
  assert.equal(data.materials.length, 5);
  assert.ok(data.settings.lastBackup > 0);
  assert.match(await text(page, '#lastBackup'), /Last backup: (?!never)/);
  await page.evaluate(() => localStorage.removeItem('makermargin.v1'));
  await page.goto(`${base}/maker-margin.html`);
  await page.waitForSelector('#firstRun .chip');
  await tab(page, 'backup');
  page.once('dialog', d => { assert.match(d.message(), /1 product, 5 materials/); d.accept(); });
  await page.setInputFiles('#restoreFile', b.file);
  assert.match(await toastText(page), /Backup restored/);
  await tab(page, 'products');
  assert.equal(await page.locator('.pcard').count(), 1);
  assert.match(await text(page, '.pcard'), /\$6\.62/);
  const restored = await page.evaluate(() => JSON.parse(localStorage.getItem('makermargin.v1')));
  assert.equal(restored.materials.length, 5);
  assert.equal(restored.channels.length, 5);

  // A file that is not a backup is refused and nothing changes
  await tab(page, 'backup');
  await page.setInputFiles('#restoreFile', { name: 'x.json', mimeType: 'application/json', buffer: Buffer.from('{"hello":1}') });
  assert.match(await toastText(page), /not a MakerMargin backup/);

  // Banner after 15 days without a backup; "Later" hides it
  await page.evaluate(() => { const d = JSON.parse(localStorage.getItem('makermargin.v1')); d.settings.lastBackup = Date.now() - 15 * 864e5; localStorage.setItem('makermargin.v1', JSON.stringify(d)); });
  await page.reload();
  assert.match(await text(page, '#backupBanner'), /Your data lives only in this browser\. Download a backup\./);
  await page.click('#backupBanner [data-act="later"]');
  assert.equal(await page.$('#backupBanner'), null);
  await page.reload();
  assert.equal(await page.$('#backupBanner'), null);

  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: ?kit= loads a kit on a fresh browser and offers to add it otherwise', { skip }, async () => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errors = watch(page);
  await page.goto(`${base}/maker-margin.html?kit=soap`);
  await page.waitForSelector('#rCost');
  assert.equal(await text(page, '#edTitle'), 'Lavender Cold Process Soap');
  assert.ok(!page.url().includes('kit='));
  assert.match(await text(page, '#rCost'), /^\$\d+\.\d\d$/);

  await page.goto(`${base}/maker-margin.html?kit=candles`);
  assert.match(await text(page, '#kitOffer'), /Add the Candles starter kit\?/);
  assert.equal(await page.locator('.pcard').count(), 1);
  await page.click('#kitOffer [data-act="addKit"]');
  await page.waitForSelector('#rCost');
  assert.equal(await text(page, '#edTitle'), '8 oz Soy Candle');
  await tab(page, 'products');
  assert.equal(await page.locator('.pcard').count(), 2);
  // Both kits share "Fragrance oil" (same unit family), so it is reused, not duplicated
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('makermargin.v1')).materials.filter(m => m.name === 'Fragrance oil').length), 1);

  for (const k of ['cookies', 'jewelry', 'resin']) {
    const c = await browser.newContext();
    const p = await c.newPage();
    const e = watch(p);
    await p.goto(`${base}/maker-margin.html?kit=${k}`);
    await p.waitForSelector('#rCost');
    assert.match(await text(p, '#fNet'), /^-?\$\d+\.\d\d$/);
    assert.deepEqual(e, []);
    await c.close();
  }
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: hostile or malformed stored data is validated and escaped', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await page.evaluate(() => {
    localStorage.setItem('makermargin.v1', JSON.stringify({
      v: 1,
      settings: { currency: '<b>$', hourly: 'abc', overheadPct: 1e9, ending: 'x', lastBackup: 'garbage' },
      materials: [null, 5, { id: '<x>', name: '<img src=x onerror="window.__pwned=1">', packPrice: '1,5', packSize: -3, packUnit: 'constructor', history: 'no' }],
      products: [{ id: 'bad id', name: '<img src=y onerror="window.__pwned=2">', sku: { a: 1 }, lines: [{ matId: '<x>', qty: 'NaN', unit: 'toString' }, 'junk'], yield: 0, price: '24' }],
      channels: 'nope',
      sales: { lines: { '__proto__': {}, '2025-11|sku:<b>': { name: '<img src=z onerror="window.__pwned=3">', units: '2' } }, months: { '2025-13': {} }, map: { 'sku:<b>': '<bad id>' } },
      plan: { rows: [{ productId: {}, qty: 'x' }] },
    }));
  });
  await page.reload();
  await page.waitForSelector('.pcard');
  await page.click('.pcard');
  await page.waitForSelector('#rCost');
  await tab(page, 'materials');
  await tab(page, 'fees');
  await tab(page, 'backup');
  await tab(page, 'products');
  assert.equal(await page.evaluate(() => window.__pwned), undefined);
  assert.equal(await page.evaluate(() => document.querySelectorAll('#view img').length), 0);
  assert.match(await text(page, '.pcard'), /<img src=y/);
  // The next save writes the cleaned-up data back
  await tab(page, 'backup');
  await page.fill('#sBiz', 'Shop');
  const d = await page.evaluate(() => JSON.parse(localStorage.getItem('makermargin.v1')));
  assert.equal(d.settings.business, 'Shop');
  assert.equal(d.settings.currency, 'b$');
  assert.equal(d.settings.hourly, 20);
  assert.equal(d.settings.ending, 'none');
  assert.equal(d.settings.lastBackup, null);
  assert.equal(d.materials.length, 1);
  assert.deepEqual([d.materials[0].packUnit, d.materials[0].packSize, d.materials[0].packPrice], ['each', 1, 1.5]);
  assert.equal(d.products[0].yield, 1);
  assert.equal(d.products[0].sku, '');
  assert.deepEqual(d.products[0].lines.map(l => l.matId), ['']);
  assert.equal(d.channels.length, 5);
  assert.deepEqual(Object.keys(d.sales.lines), ['2025-11|sku:<b>']);
  assert.deepEqual(Object.keys(d.sales.months), []);
  assert.deepEqual(Object.keys(d.sales.map), []);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: buy Pro (one-time) through Stripe Checkout, then every Pro feature', { skip }, async () => {
  const { ctx, page, errors } = await open();
  const id = await candle(page);

  // Checkout from the Pro panel on the Backup tab
  await tab(page, 'backup');
  assert.match(await text(page, '#proSlot'), /MakerMargin Pro · \$24 once/);
  await page.click('#proSlot [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  assert.match(await page.textContent('#amount'), /2400 usd \(payment\)/);
  await page.click('#pay');
  await page.waitForURL(`${base}/maker-margin.html`);
  await page.waitForSelector('.pay-toast:not(.mm)');
  assert.match(await page.textContent('.pay-toast:not(.mm)'), /Pro unlocked/);
  await page.waitForFunction(() => document.body.classList.contains('is-pro'));
  await tab(page, 'backup');
  assert.match(await text(page, '#proSlot'), /MakerMargin Pro is active.*Lifetime license/);
  licenseKey = await page.evaluate(() => localStorage.getItem('moneyapps.license.makermargin-pro'));
  assert.equal(JSON.parse(Buffer.from(licenseKey.split('.')[0], 'base64url')).exp, null);
  assert.equal(await text(page, '#getPro'), 'Pro ✓');

  // Every-channel table at $24, $0 shipping
  await openProduct(page, '8 oz Soy Candle');
  const net = ch => text(page, `#rCh tr[data-ch="${ch}"] .c-net`);
  assert.equal(await net('etsy'), '$14.98');
  assert.equal(await net('etsy-ads'), '$11.38');
  assert.equal(await net('shopify'), '$16.71');
  assert.equal(await net('amazon'), '$14.11');
  assert.equal(await net('fair'), '$16.93');
  assert.equal(await net('wholesale'), '$6.30');
  assert.match(await page.getAttribute('#rCh tr[data-ch="etsy-ads"]', 'class'), /below/);
  assert.ok(Math.abs(await page.evaluate(i => MM.channelNet(i, 'amazon', 5), id) - (5 - 1 - 6.293833)) < 1e-4);
  await page.fill('#pPrice', '5');
  assert.equal(await text(page, '#rCh tr[data-ch="amazon"] .c-fee'), '$1.00 (min)');
  assert.match(await page.getAttribute('#rCh tr[data-ch="amazon"]', 'class'), /below/);
  await page.fill('#pPrice', '24');

  // Craft fair booth fee spread over expected sales; custom channel
  await tab(page, 'fees');
  await page.fill('#ch_fair_boothFee', '60');
  await page.fill('#ch_fair_expectedSales', '100');
  await page.click('[data-act="addCh"]');
  await page.fill('#channels .chan:last-of-type .chan-name', 'Farmers market');
  await page.locator('#channels .chan').last().locator('[data-chf="pct"]').fill('5');
  await page.uncheck('[data-chid="shopify"] [data-chf="on"]');
  await openProduct(page, '8 oz Soy Candle');
  assert.equal(await text(page, '#rCh tr[data-ch="fair"] .c-fee'), '$1.37');
  assert.equal(await text(page, '#rCh tr[data-ch="fair"] .c-net'), '$16.33');
  assert.match(await text(page, '#rCh'), /Farmers market/);
  assert.equal(await page.$('#rCh tr[data-ch="shopify"]'), null);

  // Pro cost sheet: no footer
  await stubPrint(page);
  await page.click('[data-act="printCost"]');
  assert.doesNotMatch(await sheet(page), /Made with MakerMargin/);

  // Duplicate is no longer capped
  await page.click('[data-act="dup"]');
  await page.waitForFunction(() => document.getElementById('edTitle').textContent === '8 oz Soy Candle (copy)');
  page.once('dialog', d => d.accept());
  await page.click('[data-act="delProduct"]');
  await page.waitForSelector('.pcard');
  assert.equal(await page.locator('.pcard').count(), 1);

  // Batch planner
  await tab(page, 'plan');
  await page.selectOption('[data-pl="productId"]', { label: '8 oz Soy Candle' });
  await page.fill('[data-pl="qty"]', '48');
  assert.equal(await text(page, '#batchTable .c-batches'), '4');
  const shop = async name => text(page, `#shopTable tr:has-text("${name}")`);
  assert.match(await shop('Soy wax'), /18 lb.*2 × 10 lb.*\$64\.00.*2 lb/);
  assert.match(await shop('Fragrance oil'), /24 oz.*2 × 16 oz.*\$56\.00.*8 oz/);
  assert.match(await shop('Cotton wicks'), /1 × 100 each.*\$9\.00/);
  assert.match(await shop('8 oz amber jar'), /4 × 12 each.*\$84\.00/);
  assert.match(await shop('Labels'), /1 × 100 each.*\$14\.00/);
  assert.equal(await text(page, '#planSpend'), '$227.00');
  assert.equal(await text(page, '#planRev'), '$1,152.00');
  const profit = parseFloat((await text(page, '#planProfit')).replace(/[$,]/g, ''));
  assert.ok(Math.abs(profit - 718.86) <= 0.05, String(profit));
  const viaApi = await page.evaluate(i => MM.plan([{ productId: i, qty: 48 }]), id);
  assert.equal(viaApi.spend, 227);
  assert.ok(Math.abs(viaApi.profit - 718.86) <= 0.05);
  await page.fill('[data-pl="qty"]', '50');
  assert.equal(await text(page, '#batchTable .c-batches'), '5');
  assert.equal(await text(page, '#batchTable .c-note'), 'makes 60, 10 spare');
  await page.selectOption('#planCh', 'fair');
  assert.match(await text(page, '#planOut .totals'), /Craft fair/);
  await page.click('[data-act="printPlan"]');
  const plan = await sheet(page);
  assert.match(plan, /Shopping list/);
  assert.match(plan, /makes 60, 10 spare/);

  // Etsy CSV import
  await tab(page, 'profit');
  assert.match(await importCsv(page, csv('EtsySoldOrderItems2025.csv', SALES_CSV)), /^Imported 3 sales for Nov 2025 – Dec 2025$/);
  const nov = k => text(page, `#monthTable tr[data-month="2025-11"] .c-${k}`);
  const checkNov = async (fees, net) => {
    assert.equal(await nov('units'), '3');
    assert.equal(await nov('sales'), '$72.00');
    assert.equal(await nov('ship'), '$10.00');
    assert.equal(await nov('fees'), fees);
    assert.equal(await nov('cogs'), '$18.88');
    assert.equal(await nov('net'), net);
  };
  await checkNov('$8.89 est.', '$54.23');
  assert.match(await text(page, '#matchTable tr[data-key="sku:CAN-8"]'), /auto/);
  assert.match(await text(page, '#unmatched'), /^1 listing not matched, so its cost is missing: Mystery Item$/);

  // Importing the same file again replaces the months instead of adding
  assert.match(await importCsv(page, csv('EtsySoldOrderItems2025.csv', SALES_CSV)), /Imported 3 sales/);
  await checkNov('$8.89 est.', '$54.23');

  // Statement: actual fees, ads and postage replace the estimate
  assert.match(await importCsv(page, csv('etsy_statement_2025_11.csv', STATEMENT_CSV)), /Imported statement \(3 lines\) for Nov 2025/);
  await checkNov('$3.12', '$46.10');
  assert.equal(await nov('ads'), '$4.50');
  assert.equal(await nov('postage'), '$9.40');
  assert.match(await text(page, '#monthTable tr[data-month="2025-12"] .c-fees'), /\$1\.40 est\./);

  // Matching the unmatched listing to "Ignore" clears the notice
  await page.selectOption('#matchTable tr[data-key="name:mystery item"] select', 'ignore');
  assert.equal(await page.$('#unmatched'), null);
  await page.selectOption('#matchTable tr[data-key="name:mystery item"] select', '');
  assert.match(await text(page, '#unmatched'), /Mystery Item/);

  // Year-end summary, CSV export and print
  assert.match(await text(page, '#yeTax'), /Sales tax collected and paid by Etsy: \$4\.68 \(not your income\)/);
  assert.equal(await text(page, '#yeGross'), '$92.00');
  assert.match(await text(page, '#yearEnd'), /not tax advice/);
  const ex = await download(page, '[data-act="exportCsv"]');
  assert.equal(ex.name, 'makermargin-profit-2025.csv');
  const rows = ex.body.trim().split(/\r\n/);
  assert.equal(rows.length, 4);
  assert.match(rows[0], /^Month,Units,Sales,Shipping charged,Fees,Fees estimated,Ads,Postage,Refunds,Cost of goods,Net profit/);
  assert.match(rows[1], /^2025-11,3,72\.00,10\.00,3\.12,no,4\.50,9\.40,0\.00,18\.88,46\.10,4\.68$/);
  assert.match(rows[3], /^Total,4,/);
  await page.click('[data-act="printYear"]');
  const ye = await sheet(page);
  assert.match(ye, /2025 profit summary/);
  assert.match(ye, /Estimates for planning, not tax advice/);
  await page.emulateMedia({ media: 'print' });
  assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgb(255, 255, 255)');
  await page.emulateMedia({ media: 'screen' });

  // Header mapper: "Item Name" renamed, same totals after mapping
  assert.match(await importCsv(page, csv('renamed.csv', SALES_CSV.replace('Item Name', 'Listing Title'))), /need matching/);
  await page.waitForSelector('#mapperBox');
  assert.match(await text(page, '#mapperBox'), /renamed\.csv/);
  await page.selectOption('#map_name', { label: 'Listing Title' });
  await page.evaluate(() => document.querySelectorAll('.pay-toast.mm').forEach(t => t.remove()));
  await page.click('[data-act="mapImport"]');
  assert.match(await toastText(page), /Imported 3 sales/);
  await page.waitForSelector('#mapperBox', { state: 'detached' });
  await checkNov('$3.12', '$46.10');

  // Price-change ripple and price history
  await editMaterialPrice(page, 'Soy wax', 40);
  assert.match(await text(page, '#ripple h2'), /This price change affects 1 product/);
  assert.match(await text(page, '#ripple tbody tr'), /8 oz Soy Candle.*\$6\.29 → \$6\.62/);
  assert.equal(await page.$('#teaser'), null);
  await page.click('#matTable tr:has-text("Soy wax") details.hist summary');
  const today = await page.evaluate(() => new Date().toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }));
  const hist = await text(page, '#matTable tr:has-text("Soy wax") details.hist');
  assert.ok(hist.includes('$32.00 for 10 lb · until ' + today), hist);
  await page.click('#ripple a:has-text("Jump to product")');
  await page.waitForSelector('#rCost');
  assert.equal(await text(page, '#fNet'), '$14.65');

  // Wholesale line sheet: business name, SKU, wholesale, MSRP, minimum order, no footer
  await tab(page, 'products');
  await page.click('[data-act="lineSheet"]');
  await page.fill('#lsBiz', 'Wick & Wax Co.');
  await page.fill('#lsMin', '$150 or 6 per scent');
  await page.click('[data-act="printLS"]');
  const ls = await sheet(page);
  assert.match(ls, /Wick & Wax Co\./);
  assert.match(ls, /Wholesale line sheet/);
  assert.match(ls, /8 oz Soy Candle\s+CAN-8\s+\$13\.25\s+\$24\.00/);
  assert.match(ls, /Minimum order: \$150 or 6 per scent/);
  assert.doesNotMatch(ls, /Made with/);

  // Offline after the first load: tabs and CSV import keep working
  await ctx.setOffline(true);
  for (const t of ['materials', 'fees', 'plan', 'backup', 'profit']) await tab(page, t);
  assert.match(await importCsv(page, csv('EtsySoldOrderItems2025.csv', SALES_CSV)), /Imported 3 sales/);
  await ctx.setOffline(false);

  // The license key restores Pro in another browser
  const other = await (await browser.newContext()).newPage();
  const otherErrors = watch(other);
  await other.goto(`${base}/maker-margin.html`);
  await tab(other, 'plan');
  assert.ok(await other.isVisible('#locked-plan'));
  await tab(other, 'backup');
  other.once('dialog', d => d.accept(licenseKey));
  await other.click('#proSlot [data-pay="restore"]');
  await other.waitForFunction(() => document.body.classList.contains('is-pro'));
  await tab(other, 'plan');
  assert.equal(await other.$('#locked-plan'), null);

  assert.deepEqual(errors, []);
  assert.deepEqual(otherErrors, []);
  await ctx.close();
});

test('MakerMargin: cancelling checkout leaves you on the free plan', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await tab(page, 'backup');
  await page.click('#proSlot [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  await page.click('#cancel');
  await page.waitForURL(`${base}/maker-margin.html`);
  await page.waitForSelector('.pay-toast');
  assert.match(await page.textContent('.pay-toast'), /cancelled/);
  assert.equal(await page.evaluate(() => document.body.classList.contains('is-pro')), false);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('MakerMargin: 375px phone has no horizontal scroll, tables stack, decimal keypads', { skip }, async () => {
  for (const colorScheme of ['light', 'dark']) {
    const { ctx, page, errors } = await open({ viewport: { width: 375, height: 740 }, colorScheme, isMobile: true, hasTouch: true });
    if (licenseKey) await page.evaluate(k => localStorage.setItem('moneyapps.license.makermargin-pro', k), licenseKey);
    await page.reload();
    const noScroll = async where => {
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      assert.ok(over <= 0, `${where} scrolls sideways by ${over}px`);
    };
    await noScroll('first run');
    await candle(page);
    await noScroll('editor');
    for (const sel of ['#pPrice', '#pShip', '#pYield', '#pLabor', '#pHourly', '#lines [data-lf="qty"]', '#lines [data-lf="waste"]']) {
      assert.equal(await page.getAttribute(sel, 'inputmode'), 'decimal', sel);
    }
    for (const t of ['products', 'materials', 'fees', 'plan', 'profit', 'backup']) {
      await tab(page, t);
      if (t === 'plan' && licenseKey) await page.fill('[data-pl="qty"]', '50');
      if (t === 'profit' && licenseKey) {
        await page.setInputFiles('#csvFiles', csv('sales.csv', SALES_CSV));
        await page.waitForSelector('#monthTable');
        assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector('#monthTable thead')).display), 'none');
        assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector('#monthTable tr')).display), 'block');
      }
      await noScroll(t);
    }
    await tab(page, 'materials');
    assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector('#matTable thead')).display), 'none');
    assert.deepEqual(errors, []);
    await ctx.close();
  }
});
