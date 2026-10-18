# Sprint 9 — Trading UI

Angular 21 workspace for the trading front end (Sprint 9), generated with the Angular CLI.
Standalone components and signals throughout, no `NgModule`, zoneless (no `zone.js`
dependency), Vitest as the unit-test builder, Playwright for the end-to-end journeys.

Needs Node 20.19, 22.12 or 24 and above (Angular 21 refuses to start below). Java is needed only
to regenerate the API clients, never to build.

```bash
npm ci
npm run build
npm test
```

## Feature tree

The shape below is fixed so a reviewer can find a feature without searching the workspace. Each
directory under `src/app` carries its own short `README.md`.

```
src/app/
  core/
    auth/            SessionStore (token signals + sessionStorage) and AuthService (login, sign-out)
    config/          API base URLs, the platform API path allow list, the public auth paths
    interceptors/    The one functional interceptor: attaches the bearer token, handles a 401
    guards/          authGuard: redirects a signed-out visitor to sign-in, carrying where they were going
    errors/          The errorCode -> readable-message mapping for both contracts
    orders/          OrderService: our wrapper around the generated Trade clients
    theme/           Light/dark theme
  shared/layout/     App shell (navigation, theme toggle, sign-out) used by every signed-in page
  features/
    sign-in/         Username/password form against /auth/login; the only route that needs no session
    dashboard/       Account and holdings overview
    order-ticket/    Order entry: instrument, side, quantity, price, read-only account
    blotter/         Order history with status badges and the bounded NEW re-read
  clients/           Generated output only, one subdirectory per contract, never hand-edited
e2e/                 Playwright journeys (outside src/, so npm test never runs them)
scripts/             scan-bundle.mjs: the bundle secret scan
```

Nothing under `clients/` is hand-written; it is produced by the pinned OpenAPI generator
configuration, described below.

## Routes

| Route | Guard | Screen |
|---|---|---|
| `/sign-in` | `guestGuard` (no session needed; a signed-in user is sent to the dashboard) | Sign-in |
| `/dashboard` | `authGuard` | Dashboard |
| `/trade` | `authGuard` | Order ticket |
| `/blotter` | `authGuard` | Blotter |
| `/` and any unknown URL | redirect to `/dashboard` | (the guard then sends a signed-out visitor to sign-in) |

A signed-out visitor is redirected to `/sign-in?returnUrl=<where they were going>` and is never
shown an empty screen. After signing in, the return address is followed only if it is a path on
this origin: anything else (another host, `//host`, `javascript:`) falls back to `/dashboard`.

The guard is a usability control, not a security control. The bundle is public and every route in
it is readable. Authorisation is the Trade REST API's decision, taken on every `/api/v1/**` call.

## Development server, the proxy and where the URLs come from

```bash
npm start          # ng serve on http://localhost:4200
```

The browser only ever talks to its own origin. `proxy.conf.js` (wired into `angular.json`
as `proxyConfig`) forwards:

| Path | Goes to |
|---|---|
| `/auth/**` | the Auth service, `http://localhost:3000` |
| `/api/**` | the Trade REST API, `http://localhost:8081` |

So the dev server needs no CORS rule on either service. The base URLs given to the generated
clients live in one file, `src/app/core/config/api-config.ts` (`''`, meaning same origin), and the
interceptor reads the same file. That file holds origins and paths only, never a key, token or
secret, because it ships to the browser.

The proxy applies to `ng serve` only. A production deployment must either serve `/auth` and `/api`
from the same origin behind a reverse proxy (keep the bases `''`), or put absolute URLs in
`api-config.ts`; an absolute URL is then a cross-origin call, and the service must allow this app's
origin in its CORS configuration (the Trade REST API currently allows only `http://localhost:3000`).

## Session and sign-out

`SessionStore` holds the access and refresh tokens as signals and mirrors them into
`sessionStorage`, so a reload in the same tab keeps the user signed in and closing the tab ends the
session. Signing out clears both the signals and the storage. A page reload does not call
`/auth/refresh`; refresh tokens are rotated, and reusing one is treated by the Auth service as theft.

When a call that carried the token comes back `401`, the interceptor clears the session and sends
the user to `/sign-in?returnUrl=<current page>&reason=expired`, where they are told the session
expired. Several calls failing together cause a single redirect. A refused sign-in (no token was
sent) does not redirect.

## The bearer token interceptor

`core/interceptors/auth-header.interceptor.ts` is the only place that sets an `Authorization`
header, registered once in `app.config.ts` through `withInterceptors`. It decides by comparing the
outgoing URL with what we configured, an allow list, so a new third party gets no token by default:

- the origin must be one of ours (from `api-config.ts`), and
- the path must be a platform API path: `/api/...` (the Trade REST API) or `/auth/me`.

`/auth/login`, `/auth/register` and `/auth/refresh` never get a header, and nothing on any other
host does. The generated clients are not configured with a token, so they add no header of their own.

## Order ticket and blotter

**Order ticket** (`/trade`). Validated before submission: quantity a whole number greater than
zero, price greater than zero with at most two decimal places, symbol in the shape the contract
allows (`AAPL`, `INFY.NS`, `FX:EURUSD`, `X:BTC`). The account comes from `/auth/me` and is read-only,
because an editable account field would move an authorisation decision into the browser. The
API still enforces business rules 1 to 8; the form only stops the obvious mistakes reaching the wire.
Leaving the ticket with an order typed but not sent asks first (our own prompt for a link or Back
inside the app, the browser's for closing or reloading the tab); an empty form, or one just
submitted, leaves silently.
The response is shown as returned, so an accepted order reads `NEW`. The ticket uses one idempotency
key per attempt: a retry after no response reuses it (so a second order cannot be placed), and a
definite answer, accepted or refused with an error code, ends the attempt.

**Blotter** (`/blotter`). Every order for the account, newest first, rejections included, with a
badge per status (`NEW`, `FILLED`, `REJECTED`, `CANCELLED`) carrying the word as well as the colour.
An order at `NEW` is normal: the API accepted it and the executor has not resolved it yet. While any
order is at `NEW` the screen re-reads order history every 4 seconds, at most 20 times (about 80
seconds), stops as soon as nothing is at `NEW`, and then says on screen that automatic refresh has
stopped and the user can press Refresh (which starts a fresh round). It never re-posts an order: the
same key returns `ORD-409` and a new key places a second order.

## Error messages

`core/errors/error-messages.ts` maps every `errorCode` in both contracts to a sentence a trader can
act on. `ErrorCode` is derived from the two generated `ErrorCodeEnum` types, so a code added to either
contract is a compile error until it has a message. `messageFor(error)` reads
`error.error.errorCode` (an `HttpErrorResponse` carries the API's `{errorCode, message}` body there),
never the server `message`; status 0 (no response: the service is down, or a cross-origin rule blocks
the request) and an unknown code each have their own fallback sentence.

The eight codes: `ACC-404`, `ACC-403`, `INS-404`, `ORD-400`, `ORD-409`, `VAL-422`, `AUTH-401`,
`AUTH-409`. The unit test iterates both generated enums, so none can be added without a message.
`AUTH-409` (username already taken) comes only from registration, and there is no registration
screen; to see it, register an existing username against the Auth service:

```bash
curl -i -X POST http://localhost:3000/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"<existing user>","password":"<12+ characters>","accountId":1}'
```

## Regenerating typed clients

The UI uses TypeScript clients generated from `../contracts/api-schemas/auth-api.yaml` and
`../contracts/api-schemas/trade-api.yaml`. Never edit a file under `src/app/clients/`. When a contract changes:

```bash
npm run generate
```

This runs every entry under `generators` in `openapitools.json`, one per contract:

| Entry | Contract | Output |
|---|---|---|
| `auth` | `../contracts/api-schemas/auth-api.yaml` | `src/app/clients/auth-client/` |
| `trade` | `../contracts/api-schemas/trade-api.yaml` | `src/app/clients/trade-client/` |

- **Pinning.** The generator is pinned at `7.14.0` in `openapitools.json`, and the CLI wrapper at
  exactly `2.13.3` in `package.json`, so two people generate identical output.
- **Committed.** The output is committed (not ignored), so a clone builds with no Java and no
  network. Commit regenerated output in the same commit as the code that adapts to it. To check for a
  stale client, run `npm run generate` and confirm `git diff` shows nothing.
- **Why `skipValidateSpec` is on.** The generator's OpenAPI 3.1 validator requires
  `info.license.identifier`, which the specification makes optional and the contracts do not carry.
  The contracts are correct and are not ours to edit, so only that validation is skipped.
- **No `NgModule`, no barrels.** The sprint forbids `NgModule` anywhere. The generator always writes
  `api.module.ts` and an `index.ts` that re-exports it, so both are listed in each client's
  `.openapi-generator-ignore` and are not generated. Import from the specific files:

```ts
import { OrdersService } from '../../clients/trade-client/api/orders.service';
import { PlaceOrderRequest } from '../../clients/trade-client/model/models';
```

- **Wrap an awkward shape in our own service** outside the tree, as `core/auth/auth.service.ts` and
  `core/orders/order.service.ts` do. Components use those wrappers, not the generated services.

## Unit tests

```bash
npm test             # ng test: Vitest with Angular's TestBed
```

Specs are `*.spec.ts` beside the code they cover, under `src/`. The two assessed interceptor cases
are named for what they assert: "a request to your API carries the bearer token" and "a request to a
third-party origin does not carry the bearer token".

What the specs cover, by area:

| Area | Spec | Covers |
|---|---|---|
| Sign-in | `features/sign-in/sign-in.spec.ts` | validation, valid sign-in and redirect, a readable error with the password cleared and focused, the return address followed only when it is a path on this origin (off-origin, `//host`, `javascript:`, `data:` and userinfo tricks all refused), the session-expired notice |
| Session | `core/auth/*.spec.ts` | token signals, `sessionStorage`, sign-out clears the session, refresh |
| Interceptor | `core/interceptors/auth-header.interceptor.spec.ts` | token on `/api/` and `/auth/me` only, never on the public auth routes (with or without a trailing slash), third parties, look-alike paths and hosts; a `401` clears the session and redirects once |
| Guards and routes | `core/guards/auth.guard.spec.ts`, `app.routes.spec.ts` | redirect with `returnUrl`, every route but sign-in guarded, catch-all |
| Error messages | `core/errors/error-messages.spec.ts` | every code in both generated enums, unknown code, status 0, the server message never shown |
| Order ticket | `features/order-ticket/order-ticket.spec.ts` | quantity, price and symbol rules, read-only account, the account failing to load, BUY and SELL, one idempotency key per attempt |
| Blotter | `features/blotter/blotter.spec.ts` | newest first, rejections, all four badges with their word, `NEW` still working, bounded re-read, the stopped notice, refresh |

### Script tests

The bundle scan and the generated clients have tests that run under Node's own test runner, because
they check files on disk and not Angular code:

```bash
npm run test:scripts
```

- `scripts/scan-bundle.test.mjs` builds small fake bundles and checks the scan exits non-zero and names
  the pattern for each thing that must never reach a browser, never prints a secret value, and reports
  a clean bundle as clean.
- `scripts/generated-clients.test.mjs` needs no Java. It checks the generator is pinned, there is one
  entry per contract, every file in each client tree is one the generator wrote, no `NgModule` or
  barrel exists, nothing imports a barrel, and the clients are not stale against the contracts
  (every schema has a model, every operation a method, the error codes match). It is a guard, not a
  replacement for `npm run generate` followed by `git diff`.

## End-to-end tests (Playwright)

The journeys run against the real running stack (Auth service, Trade REST API, broker, database)
and the dev server, never a stub. One-time setup: `npx playwright install chromium`.

| Variable | Used for |
|---|---|
| `E2E_BASE_URL` | the app's address (default `http://localhost:4200`) |
| `E2E_USERNAME`, `E2E_PASSWORD` | a registered user |
| `E2E_ACCOUNT_ID` | the account that user may trade (the order journey checks the read-only field) |
| `E2E_SYMBOL` | an instrument that can be traded |
| `E2E_TRADE_API`, `E2E_AUTH_API` | checked reachable by a global pre-flight (`e2e/global-setup.ts`) before any browser opens; defaults to `http://localhost:8081`/`:3000` |

Every call the specs themselves make still goes through the app's own origin and the proxy — the two
addresses above are read once, up front, purely to fail fast with a clear message if the real backend
isn't up, rather than have every test in the run fail individually with an opaque timeout. Each journey
starts from a clean sign-in and leaves nothing behind for another, so run each file on its own, in its
own process:

```bash
npx playwright test e2e/login.spec.ts
npx playwright test e2e/place-order.spec.ts
```

For local runs, keep credentials in `frontend/.env.e2e` (git-ignored). Playwright config auto-loads this file,
so you do not need to set environment variables in every terminal session. Start from
`frontend/.env.e2e.example`.

The Auth service limits `POST /auth/login` to 5 requests per address per 15 minutes by default
(`LOGIN_THROTTLE_LIMIT` and `LOGIN_THROTTLE_TTL_SECONDS` in the root `.env`), and every login counts,
successful or not. `login.spec.ts` alone signs in five times, so start the Auth service with
`LOGIN_THROTTLE_LIMIT=100` before running the journeys, or the later ones get a 429 that the screen
can only show as "Something went wrong".

`npm run e2e` runs the `chromium` project only — exactly `e2e/login.spec.ts` and
`e2e/place-order.spec.ts`, the two assessed, real-stack journeys, in one process. Order-placement
assertions accept `NEW`, `FILLED` or `REJECTED`, because the executor resolves an order asynchronously.

Two more projects exist for other purposes and are never run by `npm run e2e`:
- `npm run e2e:mocked` — `e2e-mocked/journeys-mocked.spec.ts`, backend-free specs that stub the
  network to drive refusals a real stack rarely produces on demand (expired session, throttling).
- `npm run e2e:manual` — `e2e-manual/*.spec.ts`, slow-motion, screenshotting walkthroughs for
  watching a flow with your own eyes. Not assessed, reads credentials from the same environment
  variables as the real journeys (no credentials are written into these files).

## Nothing secret in the bundle

A front-end build has no private part: minification is not obfuscation and a `.map` file is the
source back again. The application never calls the Fauxnance API; prices reach the browser only
through a service of ours. After a production build, search the output:

```bash
npm run build
npm run scan:bundle                 # scans dist/sprint-09-trading-ui
SCAN_LITERALS=<value>,<value> npm run scan:bundle
```

`scripts/scan-bundle.mjs` looks for `x-api-key`, `api_key`, `api-key`, `fauxnance`, an
`execute-api.<region>.amazonaws.com` host, `jwt_secret`, a secret assigned a long literal and a
three-part JWT, in every text file including source maps. It also searches for the literal values of
our own key and signing secret, read from `../.env` (git-ignored) and from `SCAN_LITERALS`; values
are never printed. It exits non-zero on any hit. A key that reached a bundle is published: revoke
it, do not just delete the line.
