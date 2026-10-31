# Money Apps

Simple apps, each picked because people already pay for apps like it.
Each app is a single HTML file, and data stays in the user's browser.
A small Node server sells "Pro" upgrades with **Stripe** and serves the pages.

| App | File | Who pays | How it makes money | Payments |
|---|---|---|---|---|
| **Quick Invoice** | `invoice-maker.html` | Freelancers, small businesses | **Pro $4.99/mo:** removes the footer and adds a logo, brand colour, a "Pay online" button, saved clients and email drafts. The free footer also advertises the app to every client who gets an invoice. | ✅ Stripe |
| **Bingo Card Studio** | `bingo-cards.html` | Teachers, party and shower hosts, office events, senior centres, bar hosts | **Pro $14.99 once:** up to 500 unique cards, a winner checker, a full-screen projector caller, clue mode, large print, pictures, 90-ball tickets and player links. Free cards carry a footer that reaches every player. Demand peaks in December. | ✅ Stripe |
| **MakerMargin** | `maker-margin.html` | Etsy and handmade sellers, home bakers | **Pro $24 once:** unlimited products, profit for each sales channel after fees, the knock-on effect of material price changes, a batch planner, Etsy CSV import and a year-end summary. The closest competitor (Craftybase) charges about $24/month. | ✅ Stripe |
| **Sub Tracker** | `subscription-tracker.html` | Anyone with too many subscriptions | Pro reminders, plus affiliate links to cheaper plans | Planned |
| **Streaks** | `habit-streaks.html` | Self-improvement crowd | Free plan has 3 habits. Pro is unlimited (subscription or lifetime). | Planned |
| **Split It** | `bill-splitter.html` | Groups on trips or sharing a house | $1.99 per trip for uneven splits and multiple currencies | Planned |
| **Deep Focus** | `focus-timer.html` | Students, remote workers | Pro sounds, site blocker, reports | Planned |

## Run it

```bash
cd money-apps/server
node server.js            # Node 18+, nothing to install
```

Open <http://localhost:4242>. The apps work without Stripe. If someone clicks **Upgrade**, they're told that payments aren't set up yet.

## Take payments with Stripe

1. **Create a Stripe account** at <https://dashboard.stripe.com>. Stay in **test mode** to start. Copy the secret key (`sk_test_…`) from *Developers → API keys*.
2. **Set up the customer side in Stripe.** Do this in test mode and again in live mode:
   - *Settings → Billing → Customer portal → Activate*. This is where subscribers cancel or change their card. Copy its **login link** for `PORTAL_LOGIN_URL` (see below).
   - *Settings → Customer emails → Successful payments → on*. Customers get a receipt, and the receipt's email address lets you find them later if they lose their key.
3. **Start the server with your keys:**
   ```bash
   export STRIPE_SECRET_KEY=sk_test_...
   export LICENSE_SECRET=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")
   export SUPPORT_EMAIL=you@example.com
   node server.js
   ```
   Generate `LICENSE_SECRET` **once**, store it in your host's secret settings, and **never change it**: changing it invalidates every license already sold. Keys bought in test mode never work in live mode, so you can keep the same secret when you go live.
4. **Try a purchase:** open Quick Invoice, click **Upgrade**, and pay with the test card `4242 4242 4242 4242` (any future date and any CVC). You land back in the app with Pro unlocked. Bingo Card Studio and MakerMargin work the same way, as one-time purchases.
5. **Go live.** The server refuses to start in live mode if `APP_URL` is missing or isn't `https://`, so customers are never sent to localhost.
   - Switch to your `sk_live_…` key.
   - Set `APP_URL` to the public `https://` address of the folder that holds the pages, e.g. `https://apps.example.com`. Stripe sends buyers back there.
   - Set `TRUST_PROXY=1` on hosts that put a proxy in front of your app (Render, Railway, Fly.io, Heroku, or nginx on a VPS). Without it, every visitor shares one rate limit.
   - Deploy the whole `money-apps` folder and run `node server/server.js` on any host with Node 18.17+. Most hosts set `PORT` for you.

You don't need to create products or prices in the Stripe dashboard. The server creates them the first time someone checks out, using `server/products.json`. To change a price, edit `amount` there (in cents). The Upgrade box shows the new price automatically. Existing subscribers keep their old price; new buyers get the new one. Also update the price shown on the landing page (`index.html`) and in the app's perks text, if it's mentioned there. To use a price you already made in Stripe instead, set `STRIPE_PRICE_INVOICE_PRO=price_...` (or `STRIPE_PRICE_BINGO_PRO`, `STRIPE_PRICE_MAKERMARGIN_PRO`).

### Hosting the pages somewhere else

If the pages live on GitHub Pages, Netlify or similar and the server is elsewhere:
- set `APP_URL` to the full address of the folder with the pages, **including any path**, e.g. `https://you.github.io/my-repo/money-apps`;
- set `ALLOWED_ORIGINS` to just the scheme and host of that site, e.g. `https://you.github.io` (no path);
- add `<script>window.MONEY_APPS_API='https://your-server.example.com'</script>` before `pay.js` in each page.

### All settings

| Variable | Purpose |
|---|---|
| `STRIPE_SECRET_KEY` | Your Stripe secret key. Payments are off without it. |
| `LICENSE_SECRET` | Random string (32+ characters) that signs license keys. Required whenever Stripe is on. |
| `APP_URL` | Public URL of the folder with the pages (default `http://localhost:PORT`). Required as `https://` in live mode. |
| `SUPPORT_EMAIL` | Shown in the Upgrade box and in payment error messages. |
| `PORTAL_LOGIN_URL` | Recommended. Stripe's customer-portal login link (`https://billing.stripe.com/p/login/…`). "Manage subscription" opens it, and Stripe emails the customer a one-time code. Without it, anyone holding a copy of a license key can open that customer's billing page. |
| `PORT` | Default `4242`. |
| `TRUST_PROXY` | Number of proxies in front of the server, usually `1`, so rate limits see the real client IP. |
| `ALLOWED_ORIGINS` | Comma-separated origins (scheme and host only) allowed to call the API from another domain. |
| `RATE_LIMIT_PER_MIN` | API POSTs allowed per IP per minute (default 30). |
| `STRIPE_PRICE_<PRODUCT>` | Optional: use an existing Stripe price for a product. |

## How payments work

```
Upgrade → POST /api/checkout → Stripe Checkout → back to the app with ?session_id=…
        → POST /api/license (server checks the session with Stripe) → signed license key saved in the browser
```

- **No database and no webhooks.** The license key is signed with `LICENSE_SECRET`, so it can't be edited without being detected. Subscription keys last 7 days. The app refreshes them about once a day, and each refresh asks Stripe whether the subscription is still active. When someone cancels, Pro switches off at the end of the period they paid for.
- **Purchases are never lost.** The browser saves the checkout session before confirming it, and keeps retrying if the connection drops or a bank payment takes days to clear.
- **Offline-friendly.** If the server can't be reached, Pro keeps working for 3 more days. A key is only removed when your server says it is invalid or the subscription has ended.
- **Another device:** in the Pro box, click *Copy license key*, then click *I have a license key* on the other device.
- **Lost key?** Find the customer in the Stripe dashboard and re-issue a key with the same keys the server uses:
  `STRIPE_SECRET_KEY=... LICENSE_SECRET=... node server.js issue-license bingo-pro cus_...`
  For a subscription, add the subscription ID: `... issue-license invoice-pro cus_... sub_...`
- **Honest limit:** Pro features run in the browser. Someone skilled with the browser's developer tools could switch them on without paying. That's normal for low-priced browser apps; truly protecting a feature would need it to run on the server.

## Tests

```bash
cd money-apps/server
npm test            # API tests against a mock Stripe (no network needed)
npm run test:e2e    # browser purchase flow; needs Playwright: npm i -D playwright && npx playwright install chromium
```
