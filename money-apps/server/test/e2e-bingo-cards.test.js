'use strict';
// Browser tests for Bingo Card Studio (bingo-cards.html): free flows and limits, buying Pro (one-time payment)
// through the mock Stripe, and every Pro feature.
// Needs Playwright (`npm i -D playwright && npx playwright install chromium`); skipped otherwise.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { createServer, loadConfig } = require('../server.js');
const { createMockStripe } = require('./mock-stripe.js');

let chromium;
try { ({ chromium } = require('playwright')); } catch { /* skipped below */ }
const skip = !chromium && 'playwright not installed';

const PAGE = '/bingo-cards.html';
const LICENSE_KEY = 'moneyapps.license.bingo-pro';
let mock, server, base, plain, plainBase, browser, license;

before(async () => {
  if (!chromium) return;
  mock = createMockStripe({ secretKey: 'sk_test_mock' });
  const stripeUrl = await mock.listen();
  const cfg = loadConfig({ STRIPE_SECRET_KEY: 'sk_test_mock', LICENSE_SECRET: 'b'.repeat(40), STRIPE_API_BASE: stripeUrl, RATE_LIMIT_PER_MIN: '10000' });
  server = createServer(cfg);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  cfg.appUrl = base;
  plain = createServer(loadConfig({})); // no Stripe key: payments are off
  await new Promise(r => plain.listen(0, '127.0.0.1', r));
  plainBase = `http://127.0.0.1:${plain.address().port}`;
  browser = await chromium.launch();
});
after(async () => {
  if (browser) await browser.close();
  if (server) server.close();
  if (plain) plain.close();
  if (mock) await mock.close();
});

function watch(page, allow = /^$/) {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !allow.test(m.text())) errors.push(m.text()); });
  return errors;
}
async function open(url = PAGE, ctxOpts = {}, { pro = false, at = base } = {}) {
  const ctx = await browser.newContext(ctxOpts);
  if (pro) await ctx.addInitScript(l => { try { if (!localStorage.getItem('moneyapps.license.bingo-pro')) localStorage.setItem('moneyapps.license.bingo-pro', l); } catch { /* blocked */ } }, license);
  const page = await ctx.newPage();
  const errors = watch(page);
  await page.goto(at + url);
  await page.waitForSelector('#gameSel option, #player .card, #player .p-card', { state: 'attached' });
  return { ctx, page, errors };
}
const game = page => page.evaluate(() => BingoApp.game());
const modalOpen = async page => (await page.$('.pay-modal')) !== null;
async function expectUpsell(page, why) {
  await page.waitForSelector('.pay-modal [data-pay="buy"]', { timeout: 3000 }).catch(() => { throw new Error('no upsell for ' + why); });
  await page.keyboard.press('Escape');
  assert.equal(await modalOpen(page), false, why);
}
const pressed = (page, sel) => page.getAttribute(sel, 'aria-pressed');
const pdfPages = buf => (buf.toString('latin1').match(/\/Type\s*\/Page[^s]/g) || []).length;
const mediaBox = buf => { const m = buf.toString('latin1').match(/\/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*\]/); return m ? [+m[1], +m[2]] : null; };
// Builds the print container and returns each printed card's square texts (optionally only card n).
const printedCards = page => page.evaluate(() => {
  BingoApp.buildPrint('cards');
  const out = [...document.querySelectorAll('#print .bc')].map(bc => ({
    cells: [...bc.querySelectorAll('.c')].map(c => c.textContent), free: [...bc.querySelectorAll('.c')].findIndex(c => c.classList.contains('free')),
    meta: bc.querySelector('.bc-meta') ? bc.querySelector('.bc-meta').textContent : '', id: bc.querySelector('.id') ? bc.querySelector('.id').textContent : '',
  }));
  BingoApp.removePrint();
  return out;
});
async function stubPrint(page) {
  await page.evaluate(() => { window.__prints = []; window.print = () => window.__prints.push(document.getElementById('print') ? document.getElementById('print').className + '|' + document.querySelectorAll('#print .bc,#print .cs').length : 'none'); });
}
async function useWords(page, words, size) {
  await page.fill('#items', words.join('\n'));
  if (size) await page.click(`[data-size="${size}"]`);
}
// Opening a #g= link from the same page is only a fragment change, so load it fresh.
async function visit(page, url) { await page.goto('about:blank'); await page.goto(url); }
const WORDS = n => Array.from({ length: n }, (_, i) => `Word ${i + 1}`);

test('no Stripe key: the editor works and Upgrade says payments are not set up', { skip }, async () => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errors = watch(page, /status of 503/); // pay.js reports the server's 503
  await page.goto(plainBase + PAGE);
  await page.waitForSelector('#pvWrap .bc');
  assert.match(await page.textContent('#pvWrap'), /Made with Bingo Card Studio/);
  const dialog = new Promise(r => page.once('dialog', d => { r(d.message()); d.accept(); }));
  await page.click('#proPanel [data-pay="buy"]');
  assert.match(await dialog, /not configured/i);
  // Still works afterwards
  await page.click('#aCaller');
  await page.keyboard.press('Space');
  assert.match(await page.textContent('#cCount'), /^1 of \d+ called$/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('free: load the baby shower pack and print 10 unique cards on exactly 5 Letter pages', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await page.selectOption('#pack', 'baby-shower');
  await page.click('.bmodal [data-c="replace"]'); // the default game already has words, so it asks Replace or Add
  assert.match(await page.inputValue('#items'), /^Diapers\nBottle/);
  assert.equal(await page.inputValue('#title'), 'Baby Shower Bingo');
  await page.click('[data-size="5"]');
  assert.equal(await page.isChecked('#freeOn'), true);
  await page.fill('#count', '10');
  assert.equal(await pressed(page, '[data-per="2"]'), 'true');
  await page.click('[data-paper="letter"]');
  assert.equal(await page.textContent('#pvInfo'), '10 cards · 5 pages · Letter');
  // Preview: first two cards exactly as printed, with ID, game code and the free footer
  assert.equal(await page.locator('#pvWrap .bc').count(), 2);
  const seed = (await game(page)).seed;
  assert.match(await page.textContent('#pvWrap'), new RegExp(`#001 · Game ${seed}`));

  await stubPrint(page);
  await page.click('#aPrint');
  assert.deepEqual(await page.evaluate(() => window.__prints), ['|10']);
  assert.equal(await page.locator('#print .sheet').count(), 5);
  const pack = new Set((await page.inputValue('#items')).split('\n'));
  const cards = await page.$$eval('#print .bc', els => els.map(bc => ({
    cells: [...bc.querySelectorAll('.c')].map(c => ({ t: c.textContent, free: c.classList.contains('free') })),
    id: bc.querySelector('.id').textContent, foot: bc.querySelector('.bc-meta').textContent,
  })));
  assert.equal(cards.length, 10);
  cards.forEach((c, i) => {
    assert.equal(c.cells.length, 25);
    assert.equal(c.cells[12].free, true, 'FREE in the centre');
    assert.match(c.cells[12].t, /FREE/);
    const words = c.cells.filter(x => !x.free).map(x => x.t);
    assert.equal(new Set(words).size, 24, '24 distinct items');
    words.forEach(w => assert.ok(pack.has(w), w));
    assert.equal(c.id, `#${String(i + 1).padStart(3, '0')} · Game ${seed}`);
    assert.match(c.foot, /Made with Bingo Card Studio/);
  });
  const pdf = await page.pdf({ preferCSSPageSize: true });
  assert.equal(pdfPages(pdf), 5);
  assert.deepEqual(mediaBox(pdf), [612, 792]);

  // Randomised call sheet with tick boxes, as one more print job
  await page.click('#aCalls');
  assert.equal(await page.locator('#print .cs li .box').count(), pack.size);
  assert.match(await page.textContent('#print .cs header'), /Baby Shower Bingo/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('free: the card limit and Pro controls open the upsell and change nothing', { skip }, async () => {
  const { ctx, page, errors } = await open();
  const before = await game(page);
  await page.fill('#count', '30');
  assert.equal(await page.inputValue('#count'), '10');
  await expectUpsell(page, 'more than 10 cards');
  assert.equal((await game(page)).count, 10);
  assert.match(await page.textContent('#proPanel'), /\$14\.99 once/);

  await page.click('[data-per="4"]'); await expectUpsell(page, '4 per page');
  await page.click('[data-per="1"]'); await expectUpsell(page, '1 per page');
  assert.equal(await pressed(page, '[data-per="2"]'), 'true');
  await page.click('#cutLines'); await expectUpsell(page, 'cut lines');
  assert.equal(await page.isChecked('#cutLines'), false);
  await page.click('#largePrint'); await expectUpsell(page, 'large print');
  assert.equal(await page.isChecked('#largePrint'), false);
  await page.click('[data-mode="n90"]'); await expectUpsell(page, '90-ball');
  await page.click('[data-mode="clue"]'); await expectUpsell(page, 'clue mode');
  assert.equal(await pressed(page, '[data-mode="words"]'), 'true');
  await page.click('[data-theme="neon"]'); await expectUpsell(page, 'pro theme');
  assert.equal(await pressed(page, '[data-theme="classic"]'), 'true');
  await page.click('#font'); await expectUpsell(page, 'font');
  await page.click('#footerText'); await expectUpsell(page, 'footer');
  await page.click('#addPics'); await expectUpsell(page, 'pictures');
  await page.click('#aPlayers'); await expectUpsell(page, 'player links');
  await page.click('#dupGame'); await expectUpsell(page, 'duplicate');
  await page.click('[data-theme="pastel"]'); // free themes work
  assert.equal(await pressed(page, '[data-theme="pastel"]'), 'true');
  await page.click('#aCaller');
  await page.click('#cCheck'); await expectUpsell(page, 'check a card');
  await page.click('#cFull'); await expectUpsell(page, 'full screen');
  await page.click('#caller h2');
  await page.keyboard.press('f'); await expectUpsell(page, 'F key');
  assert.equal(await page.isHidden('#stage'), true);
  await page.click('#speakOn'); await expectUpsell(page, 'read aloud');
  assert.equal(await page.isChecked('#speakOn'), false);

  const after = await game(page);
  for (const k of ['mode', 'count', 'perPage', 'cutLines', 'largePrint', 'font', 'footerText', 'items', 'seed']) assert.deepEqual(after[k], before[k], k);
  assert.match(await page.textContent('#pvWrap'), /Made with Bingo Card Studio/);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('free: too few words blocks printing, duplicates are removed, exactly enough words warns', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await useWords(page, WORDS(20), 5);
  assert.equal(await page.isChecked('#freeOn'), true);
  assert.match(await page.textContent('#itemsMsg'), /Add 4 more/);
  assert.match(await page.textContent('#counter'), /20 items · a 5×5 card needs 24|20 items · a 5x5 card needs 24/);
  for (const id of ['#aPrint', '#aCaller', '#aShare']) assert.equal(await page.isDisabled(id), true, id);
  assert.match(await page.textContent('#pvWrap'), /Add 4 more items/);
  await page.click('[data-size="4"]');
  for (const id of ['#aPrint', '#aCaller', '#aShare']) assert.equal(await page.isEnabled(id), true, id);
  assert.equal(await page.isHidden('#itemsMsg'), true);

  await useWords(page, [...WORDS(24), 'word 3', 'WORD 4'], 5);
  assert.match(await page.textContent('#itemsNote'), /Removed 2 duplicate lines/);
  assert.equal((await game(page)).items.length, 24);
  assert.match(await page.textContent('#itemsWarn'), /Every card has the same words, so many players will win at once/);
  await page.locator('#items').blur();
  assert.equal((await page.inputValue('#items')).split('\n').length, 24, 'duplicates removed from the list');
  // 3x3 works with 8 words plus the free space
  await useWords(page, WORDS(8), 3);
  assert.equal(await page.isEnabled('#aPrint'), true);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('free: card #7 is identical after a reload and from a shared link, and changes with a new set', { skip }, async () => {
  const { ctx, page, errors } = await open(PAGE, { permissions: ['clipboard-read', 'clipboard-write'] });
  const card7 = (await printedCards(page))[6];
  await page.reload();
  await page.waitForSelector('#pvWrap .bc');
  assert.deepEqual((await printedCards(page))[6], card7);

  await page.click('#aShare');
  await page.waitForSelector('.toast');
  assert.match(await page.textContent('.toast'), /Game link copied/);
  const link = await page.evaluate(() => navigator.clipboard.readText());
  assert.equal(link, await page.evaluate(() => BingoApp.shareUrl()));
  assert.match(link, /#g=[A-Za-z0-9_-]+$/);

  // A co-host opens it in a private window
  const other = await open(link.replace(base, ''));
  await other.page.waitForSelector('.toast');
  assert.match(await other.page.textContent('.toast'), /Opened the shared game/);
  assert.equal(other.page.url(), base + PAGE, 'the fragment is cleared after import');
  assert.deepEqual((await printedCards(other.page))[6], card7);
  // Opening the same link again does not make a second copy
  await visit(other.page, link);
  await other.page.waitForSelector('.toast');
  assert.match(await other.page.textContent('.toast'), /already in your saved games/);
  assert.equal(await other.page.locator('#gameSel option').count(), 1);
  assert.deepEqual(other.errors, []);
  await other.ctx.close();

  const seed = (await game(page)).seed;
  await page.click('#newSeed');
  const seed2 = (await game(page)).seed;
  assert.notEqual(seed2, seed);
  assert.match(await page.textContent('#seedOut'), new RegExp(seed2));
  assert.notDeepEqual((await printedCards(page))[6].cells, card7.cells);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('card engine: uniqueness, 75-ball columns, 90-ball tickets and winner checks', { skip }, async () => {
  const { ctx, page, errors } = await open();
  const r = await page.evaluate(() => {
    const words = n => Array.from({ length: n }, (_, i) => 'w' + i);
    const out = {};
    for (const n of [40, 24]) {
      const cards = BingoApp.makeCards({ mode: 'words', items: words(n), size: 5, free: true, seed: 'K7P2Q' }, 500);
      out[n] = { len: cards.length, sigs: new Set(cards.map(c => c.sig)).size, err: cards.error,
        noRepeats: cards.every(c => new Set(c.cells).size === 25 && c.cells[12] === -1 && c.cells.filter(v => v >= 0).length === 24) };
    }
    const g75 = { mode: 'n75', seed: 'AB12C', free: true };
    const c75 = BingoApp.makeCards(g75, 100);
    out.n75 = { len: c75.length, sigs: new Set(c75.map(c => c.sig)).size, bad: [] };
    for (const c of c75) for (let col = 0; col < 5; col++) {
      const vals = [0, 1, 2, 3, 4].map(r => c.cells[r * 5 + col]).filter(v => v !== -1).map(v => v + 1);
      const want = col === 2 ? 4 : 5;
      if (vals.length !== want || new Set(vals).size !== want || vals.some(v => v < 15 * col + 1 || v > 15 * col + 15)) out.n75.bad.push([c.no, col, vals]);
      if (col === 2 && c.cells[12] !== -1) out.n75.bad.push([c.no, 'free']);
    }
    const t = BingoApp.ticket90('Z9Y8X', 1000), lo = [1, 10, 20, 30, 40, 50, 60, 70, 80], hi = [9, 19, 29, 39, 49, 59, 69, 79, 90];
    out.n90 = { len: t.length, sigs: new Set(t.map(x => x.sig)).size, bad: [] };
    for (const x of t) {
      if (x.rows.length !== 3 || x.rows.some(r => r.length !== 9 || r.filter(v => v != null).length !== 5)) out.n90.bad.push([x.no, 'rows']);
      for (let c = 0; c < 9; c++) {
        const col = x.rows.map(r => r[c]).filter(v => v != null);
        if (!col.length) out.n90.bad.push([x.no, c, 'empty']);
        if (col.some(v => v < lo[c] || v > hi[c])) out.n90.bad.push([x.no, c, 'range']);
        if (col.some((v, i) => i && v <= col[i - 1])) out.n90.bad.push([x.no, c, 'order']);
      }
    }
    // Same seed gives the same cards; card N only depends on cards 1..N
    out.det = JSON.stringify(BingoApp.makeCards(g75, 7)[6]) === JSON.stringify(c75[6]);
    const ten = { mode: 'n75', seed: 'AB12C', count: 10, calls: [] };
    out.none = BingoApp.check(ten, 3, 'line').label;
    out.blackout = BingoApp.check({ ...ten, calls: Array.from({ length: 75 }, (_, i) => i) }, 3, 'blackout').label;
    out.missing = BingoApp.check(ten, 999, 'line').label;
    const c3 = c75[2], corners = [0, 4, 20, 24].map(i => c3.cells[i]);
    out.corners = BingoApp.check({ ...ten, calls: corners }, 3, 'corners').label;
    out.cornersLess = BingoApp.check({ ...ten, calls: corners.slice(1) }, 3, 'corners').label;
    const t1 = BingoApp.ticket90('Z9Y8X', 1)[0], row1 = t1.rows[0].filter(v => v != null).map(v => v - 1);
    const g90 = { mode: 'n90', seed: 'Z9Y8X', count: 6, calls: row1 };
    out.n90one = BingoApp.check(g90, 1, 'one').label;
    out.n90two = BingoApp.check(g90, 1, 'two').label;
    return out;
  });
  assert.deepEqual(r[40], { len: 500, sigs: 500, err: '', noRepeats: true });
  assert.deepEqual(r[24], { len: 500, sigs: 500, err: '', noRepeats: true });
  assert.deepEqual(r.n75, { len: 100, sigs: 100, bad: [] });
  assert.deepEqual(r.n90, { len: 1000, sigs: 1000, bad: [] });
  assert.equal(r.det, true);
  assert.match(r.none, /^Not yet: the closest line needs 4 more: /);
  assert.match(r.blackout, /^BINGO: blackout$/);
  assert.equal(r.missing, 'Card #999 is not in this game (1-10)');
  assert.equal(r.corners, 'BINGO: four corners');
  assert.match(r.cornersLess, /^Not yet: four corners needs 1 more: [BO] \d+$/);
  assert.equal(r.n90one, 'BINGO: one line (row 1)');
  assert.match(r.n90two, /^Not yet: two lines needs 5 more: /);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('free caller (75-ball): 75 Space draws, Backspace undoes, calls survive a reload, Reset clears', { skip }, async () => {
  const { ctx, page, errors } = await open();
  await page.click('[data-mode="n75"]');
  assert.equal(await page.textContent('#pvWrap .bc-let'), 'BINGO');
  await page.click('#aCaller');
  assert.equal(await page.isVisible('#caller'), true);
  assert.equal(await page.isHidden('#preview'), true);
  for (let i = 0; i < 75; i++) await page.keyboard.press('Space');
  assert.equal(await page.textContent('#cCount'), '75 of 75 called');
  assert.equal(await page.isVisible('#cDone'), true);
  assert.equal(await page.textContent('#cDone'), 'Everything has been called');
  assert.equal(await page.isDisabled('#cDraw'), true);
  const calls = (await game(page)).calls;
  assert.equal(new Set(calls).size, 75);
  await page.keyboard.press('Space');
  assert.equal((await game(page)).calls.length, 75);
  await page.keyboard.press('Backspace');
  assert.equal(await page.textContent('#cCount'), '74 of 75 called');
  assert.deepEqual((await game(page)).calls, calls.slice(0, 74));
  // The current call shows its column letter; history is newest first
  const last = calls[73];
  assert.equal((await page.textContent('#callBig')).trim(), 'BINGO'[Math.floor(last / 15)] + (last + 1));
  assert.equal(await page.textContent('#cHist span:first-child'), 'BINGO'[Math.floor(last / 15)] + ' ' + (last + 1));
  // Clicker keys are Pro: a free user just gets a tip
  await page.keyboard.press('PageDown');
  assert.equal((await game(page)).calls.length, 74);
  await page.reload();
  await page.click('#aCaller');
  assert.equal(await page.textContent('#cCount'), '74 of 75 called');
  page.once('dialog', d => d.accept());
  await page.click('#cReset');
  assert.equal(await page.textContent('#cCount'), '0 of 75 called');
  assert.equal(await page.isEnabled('#cDraw'), true);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('free: 3 saved games, landing links (?pack, ?mode) and shared links at the limit', { skip }, async () => {
  const { ctx, page, errors } = await open(PAGE + '?pack=christmas');
  assert.equal(page.url(), base + PAGE, 'the hook is removed from the URL');
  assert.equal(await page.inputValue('#title'), 'Christmas Bingo');
  assert.match(await page.inputValue('#items'), /Reindeer/);
  assert.equal(await pressed(page, '[data-size="5"]'), 'true');
  assert.equal(await page.locator('#gameSel option').count(), 1, 'no extra default game');
  await page.goto(base + PAGE + '?mode=n75');
  assert.equal(await pressed(page, '[data-mode="n75"]'), 'true');
  assert.equal(await page.locator('#gameSel option').count(), 2);
  await page.click('#newGame');
  assert.equal(await page.locator('#gameSel option').count(), 3);
  assert.match(await page.textContent('#itemsMsg'), /Add 24 more/);
  await page.click('#newGame');
  await expectUpsell(page, 'fourth game');
  assert.equal(await page.locator('#gameSel option').count(), 3);

  // A shared link while at the limit replaces nothing and waits
  const other = await open(PAGE + '?pack=halloween');
  const hallowLink = await other.page.evaluate(() => BingoApp.shareUrl());
  await other.ctx.close();
  await visit(page, hallowLink);
  await page.waitForSelector('.pay-modal');
  await page.keyboard.press('Escape');
  assert.equal(await page.isVisible('#pending'), true);
  assert.match(await page.textContent('#pending'), /Halloween Bingo.*delete one or upgrade/);
  assert.equal(await page.locator('#gameSel option').count(), 3);
  // Deleting a game opens the waiting one
  page.once('dialog', d => d.accept());
  await page.click('#delGame');
  assert.equal(await page.isHidden('#pending'), true);
  assert.equal(await page.locator('#gameSel option').count(), 3);
  assert.equal(await page.inputValue('#title'), 'Halloween Bingo');
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('buy Pro: one-time Stripe checkout unlocks a lifetime license, then restore it in another browser', { skip }, async () => {
  const { ctx, page, errors } = await open(PAGE, { permissions: ['clipboard-read', 'clipboard-write'] });
  await page.click('#proPanel [data-pay="buy"]');
  await page.waitForURL(/\/pay\/cs_test_/);
  assert.equal(await page.textContent('#amount'), '1499 usd (payment)');
  await page.click('#pay');
  await page.waitForURL(base + PAGE);
  await page.waitForSelector('.pay-toast');
  assert.match(await page.textContent('.pay-toast'), /Pro unlocked/);
  await page.waitForFunction(() => document.querySelector('#proPanel').textContent.includes('Bingo Card Studio Pro is active'));
  assert.match(await page.textContent('#proPanel'), /Lifetime license/);
  license = await page.evaluate(k => localStorage.getItem(k), LICENSE_KEY);
  assert.ok(license && license.includes('.'));
  const sessions = [...mock.db.sessions.values()];
  assert.equal(sessions[sessions.length - 1].mode, 'payment');

  // Footer gone, 500 cards allowed
  await page.waitForSelector('#pvWrap .bc');
  assert.doesNotMatch(await page.textContent('#pvWrap'), /Made with Bingo Card Studio/);
  await page.fill('#count', '500');
  assert.equal(await modalOpen(page), false);
  assert.equal(await page.textContent('#pvInfo'), '500 cards · 250 pages · Letter');
  const t0 = Date.now();
  await stubPrint(page);
  await page.click('#aPrint');
  assert.ok(Date.now() - t0 < 3000, '500 cards build in under 3s');
  assert.deepEqual(await page.evaluate(() => window.__prints), ['|500']);
  const ids = await page.$$eval('#print .id', els => els.map(e => e.textContent.split(' ')[0]));
  assert.equal(new Set(ids).size, 500);
  assert.equal(ids[499], '#500');

  // Copy license key, then "I have a license key" in a fresh browser
  await page.click('#proPanel [data-pay="copy"]');
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), license);
  const other = await open();
  other.page.once('dialog', d => d.accept(license));
  await other.page.click('#proPanel [data-pay="restore"]');
  await other.page.waitForFunction(() => document.body.classList.contains('is-pro'));
  assert.doesNotMatch(await other.page.textContent('#pvWrap'), /Made with Bingo Card Studio/);
  assert.deepEqual(other.errors, []);
  await other.ctx.close();
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('Pro printing: footer text, 4 per page with cut lines, large print, A4 and 90-ball tickets', { skip: skip || false }, async () => {
  assert.ok(license, 'needs the license bought in the previous test');
  const { ctx, page, errors } = await open(PAGE, {}, { pro: true });
  await page.waitForSelector('#pvWrap .bc');
  await page.fill('#footerText', 'Room 12 Review Day');
  assert.match(await page.textContent('#pvWrap .bc-meta'), /Room 12 Review Day/);
  await page.fill('#count', '10');
  await page.click('[data-per="4"]');
  await page.click('#cutLines');
  await page.click('[data-theme="chalkboard"]');
  await page.selectOption('#font', 'serif');
  assert.equal(await page.textContent('#pvInfo'), '10 cards · 3 pages · Letter');
  assert.equal(await page.locator('#pvWrap .sheet.cut .bc.th-chalkboard.f-serif').count(), 4);
  await page.evaluate(() => BingoApp.buildPrint('cards'));
  assert.equal(await page.locator('#print .sheet.pp4.cut').count(), 3);
  assert.equal(pdfPages(await page.pdf({ preferCSSPageSize: true })), 3);

  // Cards are not clipped at 1, 2 or 4 per page, on Letter or A4
  for (const paper of ['letter', 'a4']) {
    await page.click(`[data-paper="${paper}"]`);
    for (const per of ['1', '2', '4']) {
      await page.click(`[data-per="${per}"]`);
      await page.evaluate(() => BingoApp.buildPrint('cards'));
      await page.emulateMedia({ media: 'print' });
      const clipped = await page.evaluate(() => [...document.querySelectorAll('#print .slot')].filter(s => {
        const a = s.getBoundingClientRect(), b = s.firstElementChild.getBoundingClientRect();
        return b.left < a.left - 0.5 || b.right > a.right + 0.5 || b.top < a.top - 0.5 || b.bottom > a.bottom + 0.5;
      }).length);
      await page.emulateMedia({ media: 'screen' });
      assert.equal(clipped, 0, `${paper} ${per}/page`);
      const pdf = await page.pdf({ preferCSSPageSize: true });
      assert.equal(pdfPages(pdf), Math.ceil(10 / per), `${paper} ${per}/page pages`);
      const box = mediaBox(pdf);
      if (paper === 'a4') assert.ok(Math.abs(box[0] - 595.3) < 2 && Math.abs(box[1] - 841.9) < 2, 'A4 is 210x297mm: ' + box);
    }
  }

  // Large print: 1 per page, 28pt+ text, thick borders, black on white
  await page.click('[data-paper="letter"]');
  await page.click('[data-size="4"]');
  await page.click('#largePrint');
  assert.equal(await pressed(page, '[data-per="1"]'), 'true');
  assert.equal(await page.isDisabled('[data-per="4"]'), true);
  await page.evaluate(() => BingoApp.buildPrint('cards'));
  await page.emulateMedia({ media: 'print' });
  const lp = await page.evaluate(() => {
    const c = document.querySelector('#print .bc.lp .c:not(.free)'), grid = document.querySelector('#print .bc.lp .bc-grid');
    return { font: parseFloat(getComputedStyle(c).fontSize), border: parseFloat(getComputedStyle(grid).borderTopWidth), color: getComputedStyle(c).color, line: getComputedStyle(grid).borderTopColor, perSheet: document.querySelector('#print .sheet').querySelectorAll('.bc').length };
  });
  await page.emulateMedia({ media: 'screen' });
  assert.ok(lp.font >= 28 * 4 / 3 - 0.01, 'item text is at least 28pt: ' + lp.font);
  assert.ok(lp.border >= 6, 'thick borders: ' + lp.border);
  assert.equal(lp.color, 'rgb(17, 17, 17)');
  assert.equal(lp.line, 'rgb(0, 0, 0)');
  assert.equal(lp.perSheet, 1);
  assert.equal(pdfPages(await page.pdf({ preferCSSPageSize: true })), 10);
  await page.click('#largePrint');

  // 90-ball: 6 tickets per page, 15 numbers each
  await page.click('[data-mode="n90"]');
  await page.fill('#count', '12');
  assert.equal(await page.textContent('#pvInfo'), '12 tickets · 2 pages · Letter');
  await page.evaluate(() => BingoApp.buildPrint('cards'));
  const tickets = await page.$$eval('#print .bc.t90', els => els.map(e => e.querySelectorAll('.c:not(.blank)').length));
  assert.deepEqual(tickets, Array(12).fill(15));
  assert.equal(await page.locator('#print .sheet.pp6').count(), 2);
  assert.equal(pdfPages(await page.pdf({ preferCSSPageSize: true })), 2);
  // Call sheet for 90-ball groups the numbers by tens
  await page.evaluate(() => BingoApp.buildPrint('calls'));
  assert.equal(await page.locator('#print .cs .grp.g9 > div').count(), 9);
  assert.equal(await page.locator('#print .cs li').count(), 90);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('Pro caller: check a card by number, any-line winners, blackout', { skip }, async () => {
  assert.ok(license);
  const { ctx, page, errors } = await open(PAGE, {}, { pro: true });
  await page.click('[data-mode="n75"]');
  await page.fill('#count', '10');
  await page.click('#aCaller');
  await page.click('#cCheck');
  await page.fill('#ckNo', '3');
  await page.keyboard.press('Enter');
  assert.match(await page.textContent('#ckOut .verdict'), /^Not yet: the closest line needs 4 more: /);
  await page.fill('#ckNo', '999');
  await page.keyboard.press('Enter');
  assert.equal(await page.textContent('#ckOut .verdict'), 'Card #999 is not in this game (1-10)');
  await page.keyboard.press('Escape');

  // Keep drawing until a card reports BINGO for any line
  await page.click('#caller h2');
  let winner = 0;
  for (let i = 0; i < 75 && !winner; i++) {
    await page.keyboard.press('ArrowRight');
    winner = await page.evaluate(() => { const g = BingoApp.game(); for (let n = 1; n <= 10; n++) if (BingoApp.check(g, n, 'line').bingo) return n; return 0; });
  }
  assert.ok(winner > 0);
  await page.keyboard.press('c');
  await page.fill('#ckNo', String(winner));
  await page.keyboard.press('Enter');
  assert.match(await page.textContent('#ckOut .verdict.good'), /^BINGO: (row \d|column [BINGO]|diagonal)/);
  const outlined = await page.$$eval('#ckOut .c.win:not(.free)', els => els.map(e => +e.textContent - 1));
  assert.ok(outlined.length >= 4);
  const calls = new Set((await game(page)).calls);
  const history = await page.$$eval('#cHist span', els => els.map(e => e.textContent));
  for (const v of outlined) {
    assert.ok(calls.has(v), `outlined ${v + 1} was called`);
    assert.ok(history.includes('BINGO'[Math.floor(v / 15)] + ' ' + (v + 1)), `outlined ${v + 1} is in the history`);
  }
  await page.keyboard.press('Escape');

  // Blackout after every number has been drawn
  await page.selectOption('#pattern', 'blackout');
  await page.click('#caller h2');
  for (let i = 0; i < 75; i++) await page.keyboard.press('PageDown');
  assert.equal(await page.textContent('#cCount'), '75 of 75 called');
  await page.click('#cCheck');
  await page.fill('#ckNo', '3');
  await page.keyboard.press('Enter');
  assert.equal(await page.textContent('#ckOut .verdict'), 'BINGO: blackout');
  assert.equal(await page.locator('#ckOut .c.win').count(), 25);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('Pro projector: F for full screen, clicker keys, call board, read-aloud, reduced motion', { skip }, async () => {
  assert.ok(license);
  const { ctx, page, errors } = await open(PAGE, {}, { pro: true });
  await page.click('[data-mode="n75"]');
  await page.click('#aCaller');
  await page.evaluate(() => {
    window.__spoken = [];
    window.SpeechSynthesisUtterance = function (text) { this.text = text; };
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { speak: u => window.__spoken.push(u.text), cancel() {} } });
  });
  await page.evaluate(() => { // headless Chromium may refuse real full screen, so record the request
    const orig = Element.prototype.requestFullscreen;
    Element.prototype.requestFullscreen = function (...a) { window.__fullscreen = this.id; return orig.apply(this, a); };
  });
  await page.click('#caller h2');
  await page.keyboard.press('f');
  await page.waitForSelector('#stage:not([hidden])');
  assert.equal(await page.evaluate(() => window.__fullscreen), 'stage');
  await page.keyboard.press('PageDown');
  assert.equal(await page.getAttribute('#stage', 'data-shuffling'), '1', 'animated draw');
  await page.waitForFunction(() => document.getElementById('stage').dataset.shuffling === '0');
  for (const k of ['ArrowRight', 'ArrowDown']) { await page.keyboard.press(k); await page.waitForFunction(() => document.getElementById('stage').dataset.shuffling === '0'); }
  let g = await game(page);
  assert.equal(g.calls.length, 3);
  const lit = await page.$$eval('#stBoard i.on', els => els.map(e => +e.textContent - 1).sort((a, b) => a - b));
  assert.deepEqual(lit, g.calls.slice().sort((a, b) => a - b));
  const last = g.calls[2];
  assert.equal((await page.textContent('#stCall')).trim(), 'BINGO'[Math.floor(last / 15)] + (last + 1));
  assert.equal(await page.locator('#stLast span').count(), 2);
  await page.keyboard.press('PageUp');
  assert.equal((await game(page)).calls.length, 2);
  await page.keyboard.press('ArrowLeft');
  assert.equal((await game(page)).calls.length, 1);
  // Read aloud
  await page.click('#stSpeak');
  assert.equal(await pressed(page, '#stSpeak'), 'true');
  await page.click('#stage', { position: { x: 5, y: 300 } });
  await page.keyboard.press('PageDown');
  await page.waitForFunction(() => window.__spoken.length === 1);
  g = await game(page);
  const spoken = await page.evaluate(() => window.__spoken[0]);
  assert.equal(spoken, 'BINGO'[Math.floor(g.calls[1] / 15)] + '. ' + (g.calls[1] + 1));
  await page.keyboard.press('Escape');
  await page.waitForSelector('#stage', { state: 'hidden' });
  await page.waitForFunction(() => !document.fullscreenElement);
  assert.deepEqual(errors, []);
  await ctx.close();

  // With reduced motion there is no shuffle: the final call shows at once
  const rm = await open(PAGE, { reducedMotion: 'reduce' }, { pro: true });
  await rm.page.click('[data-mode="n75"]');
  await rm.page.click('#aCaller');
  await rm.page.click('#cFull');
  await rm.page.keyboard.press('PageDown');
  assert.notEqual(await rm.page.getAttribute('#stage', 'data-shuffling'), '1');
  const c = (await game(rm.page)).calls[0];
  assert.equal((await rm.page.textContent('#stCall')).trim(), 'BINGO'[Math.floor(c / 15)] + (c + 1));
  assert.deepEqual(rm.errors, []);
  await rm.ctx.close();
});

test('Pro questions & answers: cards show the answer, the caller reads the clue, the call sheet lists both', { skip }, async () => {
  assert.ok(license);
  const { ctx, page, errors } = await open(PAGE, {}, { pro: true });
  await page.click('#newGame');
  await page.click('[data-mode="clue"]');
  assert.match(await page.textContent('#itemsLbl'), /answer \| clue/);
  const qa = [['cat', 'a pet that meows'], ['cow', 'gives us milk'], ['owl', 'hoots at night'], ['bee', 'makes honey'], ['fox', 'a sly red animal'],
    ['hen', 'lays eggs'], ['ant', 'a tiny insect in a colony'], ['pig', 'rolls in mud'], ['yak', 'a hairy ox']];
  await page.fill('#items', qa.map(([a, q]) => `${a} | ${q}`).join('\n'));
  await page.click('[data-size="3"]');
  await page.uncheck('#freeOn');
  const preview = await page.textContent('#pvWrap .bc');
  assert.match(preview, /cat/);
  assert.doesNotMatch(preview, /a pet that meows/);
  await page.click('#aCaller');
  let found = false;
  for (let i = 0; i < 9 && !found; i++) {
    await page.keyboard.press('Space');
    found = (await page.textContent('#callBig .q')) === 'a pet that meows';
  }
  assert.ok(found, 'cat was drawn');
  assert.doesNotMatch(await page.textContent('#callBig'), /\bcat\b/);
  assert.equal(await page.textContent('#cHist span:first-child'), '?');
  await page.click('#cReveal');
  assert.equal(await page.textContent('#callBig .ans'), 'cat');
  assert.equal(await page.textContent('#cHist span:first-child'), 'cat');
  await page.evaluate(() => BingoApp.buildPrint('calls'));
  const sheet = await page.textContent('#print .cs');
  for (const [a, q] of qa) assert.ok(sheet.includes(`${q} → ${a}`), q);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('Pro pictures: uploads become captioned squares, survive a reload, and are left out of links', { skip }, async () => {
  assert.ok(license);
  const { ctx, page, errors } = await open(PAGE, { permissions: ['clipboard-read', 'clipboard-write'] }, { pro: true });
  const img = (type, color) => page.evaluate(([t, c]) => {
    const cv = document.createElement('canvas'); cv.width = 600; cv.height = 400;
    const x = cv.getContext('2d'); x.fillStyle = c; x.fillRect(0, 0, 600, 400);
    return cv.toDataURL(t).split(',')[1];
  }, [type, color]).then(b => Buffer.from(b, 'base64'));
  await page.click('#newGame');
  await useWords(page, ['Apple', 'Banana', 'Cherry', 'Grape', 'Lemon', 'Mango'], 3);
  await page.uncheck('#freeOn');
  await page.setInputFiles('#picInput', [
    { name: 'pumpkin.png', mimeType: 'image/png', buffer: await img('image/png', '#f97316') },
    { name: 'IMG_2041.jpg', mimeType: 'image/jpeg', buffer: await img('image/jpeg', '#16a34a') },
    { name: 'blue-sky.png', mimeType: 'image/png', buffer: await img('image/png', '#2563eb') },
  ]);
  await page.waitForFunction(() => document.querySelectorAll('#pics .chip img').length === 3);
  assert.deepEqual(await page.$$eval('#pics [data-cap]', els => els.map(e => e.value)), ['pumpkin', '', 'blue sky']);
  await page.fill('#pics .chip:nth-child(2) [data-cap]', 'Green tree');
  assert.match(await page.textContent('#counter'), /^9 items/);
  // Downscaled to 240px JPEGs in IndexedDB
  const stored = await page.evaluate(() => new Promise(res => {
    const r = indexedDB.open('bingo'); r.onsuccess = () => { const q = r.result.transaction('img').objectStore('img').getAll(); q.onsuccess = () => res(q.result.map(b => b.type)); };
  }));
  assert.deepEqual(stored, ['image/jpeg', 'image/jpeg', 'image/jpeg']);
  const sizes = await page.$$eval('#pics .chip img', els => Promise.all(els.map(e => e.decode().then(() => [e.naturalWidth, e.naturalHeight]))));
  sizes.forEach(s => assert.deepEqual(s, [240, 160]));
  // Every 3x3 card holds all 9 items, so each prints the 3 pictures with captions
  await page.evaluate(() => BingoApp.buildPrint('cards'));
  const pics = await page.$$eval('#print .bc', els => els.map(e => [...e.querySelectorAll('.c.pic')].map(c => (c.querySelector('img') ? 'img:' : '') + c.textContent).sort()));
  pics.forEach(p => assert.deepEqual(p, ['img:Green tree', 'img:blue sky', 'img:pumpkin']));
  await page.evaluate(() => BingoApp.removePrint());

  await page.reload();
  await page.waitForFunction(() => document.querySelectorAll('#pics .chip img').length === 3 && document.querySelectorAll('#pvWrap .c.pic img').length === 3);
  assert.deepEqual(await page.$$eval('#pics [data-cap]', els => els.map(e => e.value)), ['pumpkin', 'Green tree', 'blue sky']);
  assert.ok(await page.$eval('#pvWrap .c.pic img', e => e.decode().then(() => e.naturalWidth > 0)));

  await page.click('#aShare');
  await page.waitForSelector('.toast');
  assert.match(await page.textContent('.toast'), /Pictures are not included in game links/);
  // Removing a picture deletes it from IndexedDB once no game uses it
  await page.click('#pics .chip:first-child [data-rmpic]');
  await page.waitForFunction(() => new Promise(res => {
    const r = indexedDB.open('bingo'); r.onsuccess = () => { const q = r.result.transaction('img').objectStore('img').count(); q.onsuccess = () => res(q.result === 2); };
  }));
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('Pro player links: a phone opens only card #5, identical to the print, and marks survive a reload', { skip }, async () => {
  assert.ok(license);
  const { ctx, page, errors } = await open(PAGE, {}, { pro: true });
  await page.selectOption('#pack', 'office');
  await page.click('.bmodal [data-c="replace"]');
  await page.fill('#count', '30');
  await page.click('#aPlayers');
  const lines = (await page.inputValue('#plText')).split('\n');
  assert.equal(lines.length, 30);
  assert.match(lines[4], /^Card 5: http.*#g=[A-Za-z0-9_-]+&card=5$/);
  const link = lines[4].replace('Card 5: ', '');
  await page.keyboard.press('Escape');
  const printed5 = (await printedCards(page))[4];

  const phone = await open(link.replace(base, ''), { viewport: { width: 375, height: 740 } });
  const p = phone.page;
  await p.waitForSelector('#player .p-card .bc');
  assert.equal(await p.locator('.bc').count(), 1);
  assert.equal(await p.isHidden('#app'), true);
  assert.match(await p.textContent('.p-head'), /Card #005/);
  const cells = await p.$$eval('.p-card .c', els => els.map(c => c.textContent));
  assert.deepEqual(cells, printed5.cells);
  assert.equal(await p.textContent('.p-card .id'), printed5.id);
  assert.equal(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await p.click('.p-card .c[data-i="0"]');
  await p.click('.p-card .c[data-i="6"]');
  await p.click('.p-card .c[data-i="6"]');
  await p.click('.p-card .c[data-i="24"]');
  const marked = () => p.$$eval('.p-card .c.mk', els => els.map(e => +e.dataset.i));
  assert.deepEqual(await marked(), [0, 12, 24]); // 12 is the free space
  await p.reload();
  await p.waitForSelector('#player .p-card .bc');
  assert.deepEqual(await marked(), [0, 12, 24]);
  await p.click('#pBingo');
  assert.match(await p.textContent('.bmodal'), /BINGO!.*Card #005/s);
  assert.equal(await p.locator('#gameSel option').count(), 0, 'a player link does not import a game');
  assert.equal(await p.evaluate(() => localStorage.getItem('bingo.v1')), null);
  assert.deepEqual(phone.errors, []);
  await phone.ctx.close();
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('Pro saved games: more than 3 games, and Duplicate copies every setting with a new id', { skip }, async () => {
  assert.ok(license);
  const { ctx, page, errors } = await open(PAGE, {}, { pro: true });
  for (let i = 0; i < 4; i++) await page.click('#newGame');
  assert.equal(await page.locator('#gameSel option').count(), 5);
  await page.selectOption('#pack', 'music');
  await page.click('[data-theme="neon"]');
  await page.click('[data-per="4"]');
  await page.click('[data-paper="a4"]');
  await page.fill('#count', '120');
  await page.fill('#footerText', 'Trivia Tuesday');
  const a = await game(page);
  await page.click('#dupGame');
  const b = await game(page);
  assert.notEqual(b.id, a.id);
  assert.equal(b.title, a.title + ' (copy)');
  for (const k of ['mode', 'items', 'size', 'free', 'freeLabel', 'letters', 'count', 'perPage', 'paper', 'cutLines', 'largePrint', 'theme', 'font', 'footerText', 'seed']) assert.deepEqual(b[k], a[k], k);
  assert.equal(await page.locator('#gameSel option').count(), 6);
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('phone and dark mode: no sideways scroll at 375px, 40px+ controls, reachable actions, white print', { skip }, async () => {
  const { ctx, page, errors } = await open(PAGE, { viewport: { width: 375, height: 740 }, colorScheme: 'dark' });
  const res = await page.evaluate(() => {
    const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; };
    const small = [...document.querySelectorAll('#app button, #app select, #app textarea, #app input:not([type=checkbox]):not([type=file]), #app label.check')]
      .filter(vis).filter(e => e.getBoundingClientRect().height < 40).map(e => e.id || e.textContent.trim().slice(0, 20));
    const bar = document.getElementById('actions').getBoundingClientRect();
    return { sw: document.documentElement.scrollWidth, small, barFixed: getComputedStyle(document.getElementById('actions')).position, barBottom: bar.bottom, h: innerHeight,
      bg: getComputedStyle(document.body).backgroundColor, paper: getComputedStyle(document.querySelector('.paper')).backgroundColor, card: getComputedStyle(document.querySelector('#pvWrap .bc')).backgroundColor };
  });
  assert.ok(res.sw <= 375, 'scrollWidth ' + res.sw);
  assert.deepEqual(res.small, []);
  assert.equal(res.barFixed, 'fixed');
  assert.ok(res.barBottom <= res.h);
  assert.equal(res.bg, 'rgb(16, 19, 28)');
  assert.equal(res.paper, 'rgb(255, 255, 255)');
  assert.equal(res.card, 'rgb(255, 255, 255)');
  // Caller and preview at phone width
  await page.click('#aCaller');
  await page.keyboard.press('Space');
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= 375));
  await page.click('#aCaller');
  await page.evaluate(() => BingoApp.buildPrint('cards'));
  await page.emulateMedia({ media: 'print', colorScheme: 'dark' });
  const print = await page.evaluate(() => ({ body: getComputedStyle(document.body).backgroundColor, card: getComputedStyle(document.querySelector('#print .bc')).backgroundColor, text: getComputedStyle(document.querySelector('#print .c:not(.free)')).color, main: getComputedStyle(document.querySelector('#app')).display }));
  assert.deepEqual(print, { body: 'rgb(255, 255, 255)', card: 'rgb(255, 255, 255)', text: 'rgb(17, 17, 17)', main: 'none' });
  assert.deepEqual(errors, []);
  await ctx.close();
});

test('hostile data: malformed storage, broken links and HTML in titles are handled safely', { skip }, async () => {
  const ctx = await browser.newContext();
  await ctx.addInitScript(() => {
    if (location.protocol !== 'http:' || sessionStorage.getItem('seeded')) return;
    sessionStorage.setItem('seeded', '1');
    localStorage.setItem('bingo.v1', JSON.stringify({ currentId: { x: 1 }, speak: 'yes', games: [
      { id: '<x>', title: '<img src=x onerror="window.__xss=1">', mode: 'evil', size: 99, count: -5, perPage: 3, theme: '__proto__', font: 'constructor', seed: '"><script>',
        items: [{ t: '<b onmouseover=alert(1)>bold</b>' }, { t: 123 }, null, 'plain', ...Array.from({ length: 30 }, (_, i) => 'w' + i), { t: 'x', img: '../../etc' }], calls: ['a', -1, 1e9, 2, 2] },
      'notobj', null, [1, 2],
    ] }));
    localStorage.setItem('bingo.daub.ABCDE.1', '{"not":"an array"}');
  });
  const page = await ctx.newPage();
  const errors = watch(page);
  await page.goto(base + PAGE);
  await page.waitForSelector('#pvWrap .bc');
  const g = await game(page);
  assert.equal(g.title, '<img src=x onerror="window.__xss=1">');
  assert.equal(g.mode, 'words');
  assert.equal(g.size, 5);
  assert.equal(g.count, 1);
  assert.equal(g.perPage, 2);
  assert.equal(g.theme, 'classic');
  assert.equal(g.font, 'system');
  assert.match(g.seed, /^[0-9A-Z]{5}$/);
  assert.deepEqual(g.calls, [2]);
  assert.ok(g.items.every(i => typeof i.t === 'string' && !i.img));
  assert.equal(await page.locator('#pvWrap .bc-title img, #gameSel img, #pvWrap b[onmouseover]').count(), 0);
  assert.match(await page.textContent('#pvWrap .bc-title'), /<img src=x/);
  assert.equal(await page.evaluate(() => window.__xss), undefined);
  assert.equal(await page.locator('#gameSel option').count(), 1);

  // Broken game links and player links
  await visit(page, base + PAGE + '#g=not-a-real-game');
  await page.waitForSelector('.toast');
  assert.match(await page.textContent('.toast'), /could not be read/);
  const evil = Buffer.from(JSON.stringify({ v: 1, title: '<svg onload=alert(1)>', items: ['<i>a</i>'], seed: 'ABCDE', count: 3 })).toString('base64url');
  await visit(page, base + PAGE + '#g=' + evil + '&card=9');
  assert.match(await page.textContent('#player'), /Card #9 is not in this game \(1-3\)/);
  await visit(page, base + PAGE + '#g=' + evil + '&card=x');
  assert.match(await page.textContent('#player'), /not in this game/);
  await visit(page, base + PAGE + '#g=%%%&card=1');
  assert.match(await page.textContent('#player'), /could not be read/);
  assert.equal(await page.locator('#player svg, #player i').count(), 0);
  // Storage that is not JSON at all
  await page.evaluate(() => localStorage.setItem('bingo.v1', '{not json'));
  await page.goto(base + PAGE);
  await page.waitForSelector('#pvWrap .bc');
  assert.equal(await page.locator('#gameSel option').count(), 1);
  assert.deepEqual(errors, []);
  await ctx.close();
});
