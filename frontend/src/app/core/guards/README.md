# core/guards

- `auth.guard.ts`: the route guard applied to every route except sign-in. A signed-out visitor is
  redirected to `/sign-in` carrying the address they were going to (`returnUrl`), never shown an empty
  screen.
- `guest.guard.ts`: the guard on `/sign-in` itself. A signed-in user is sent on to the page in
  `returnUrl` (or the dashboard) instead of being shown a login form, so the sign-in page never
  appears in front of a user who is already logged in. A signed-out visitor goes straight through.
- `unsaved-changes.guard.ts`: asks "leave this page?" before the user leaves a screen that reports
  unsent input (the order ticket), for a link or Back/Forward inside the app. It asks nothing when
  there is nothing to lose, and never once the session is over (an expired session or a sign-out
  must not get stuck behind a prompt). Closing the tab or leaving the site is handled by the
  screen's own `beforeunload` handler, which only the browser can word.
- `return-url.ts`: the one rule both use, `safeReturnPath`: a `returnUrl` is followed only if it is a
  path on this origin, so a crafted link cannot send a user elsewhere. It also refuses `/sign-in`
  itself, so there is no redirect loop.

A usability control, not a security control: the bundle is public and every route in it is readable.
Authorisation is the Trade REST API's decision, taken on every `/api/v1/**` call.
