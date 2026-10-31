# Money Apps

Five simple apps, each picked because people already pay for apps like it.
Each one is a single HTML file. They need no build step and no server, and data is saved in the browser.
Open a file in a browser to try it, or host the folder for free on GitHub Pages, Netlify or Cloudflare Pages.

| App | File | Who pays | How it makes money |
|---|---|---|---|
| **Quick Invoice** | `invoice-maker.html` | Freelancers, small businesses | Free with a "Made with Quick Invoice" footer. **Pro $4.99/mo** removes it and adds a logo, saved clients, emailing and payment links. The footer also advertises the app to every client who gets an invoice. |
| **Sub Tracker** | `subscription-tracker.html` | Anyone with too many subscriptions | **Pro $2.99/mo** for reminders before charges. You can also earn **affiliate** fees by pointing users to cheaper plans or providers. |
| **Streaks** | `habit-streaks.html` | Self-improvement crowd | Freemium: the free plan has **3 habits** (this limit is already enforced). **Pro $3.99/mo or $29 lifetime** for unlimited habits, reminders and sync. |
| **Split It** | `bill-splitter.html` | Groups on trips or sharing a house | **$1.99 per trip** unlocks uneven splits, multiple currencies and shared links. Each trip pulls in new users, because the whole group sees the app. |
| **Deep Focus** | `focus-timer.html` | Students, remote workers | **Pro $2.99/mo** for ambient sounds, a site blocker and reports. The free version can also carry a small ad. |

## What works today
- All the core free features. Data stays in the browser's `localStorage`.
- Each page shows a "Pro" box describing the paid tier. These boxes are placeholders. **No payments are wired up.**

## Next steps to start charging
1. **Payments:** the quickest option is a Stripe Payment Link, Lemon Squeezy or Gumroad checkout behind the Pro box. Unlock Pro with a licence key the user pastes into the app.
2. **Accounts and sync:** Pro features like reminders, sync and sharing need a backend, for example Supabase or Firebase.
3. **Distribution:** add a web manifest and service worker so each app installs as a PWA. Then wrap it with Capacitor to publish to the Play Store and App Store.
4. **Traffic:** each app matches common searches ("free invoice generator", "split bill calculator", "pomodoro timer"). One landing page per app with good SEO is the cheapest way to get users.

The best first bet is **Quick Invoice**. Its users are businesses, which pay more readily, and every invoice sent is free advertising.
