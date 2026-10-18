# Security Review - auth-service (SEC2-684)

**Reviewed by:** Parthasarathi Bhattacharya
**Date of review:** 2026-09-23
**Commit reviewed:** `6ece565fd9dfa374c8a9b3ac8a70a9efcbe6699b`
**OWASP Top Ten version:** 2021

**Note on provenance.** `security-review/TEMPLATE.md`, which SEC2-684 says to copy as the starting point for this file, does not exist anywhere in this repository at the reviewed commit — confirmed with `find . -iname "*security-review*" -o -iname "TEMPLATE.md"` from the repo root, which returns nothing. This file was written directly against the OWASP Top Ten 2021 categories the sprint README names, since there was no template to copy. Whoever owns SEC2-684's acceptance criteria should be told the template never existed, separately from the content below.

All seven fixes cited as "Fixed" in this review landed together in a single commit, `6ece565` ("Fixed the Changes before the stories 682") — they were not committed individually, so that one hash is cited for all of them rather than invented per-fix hashes.

---

## Findings by OWASP category

### A01:2021 – Broken Access Control

**Finding:** `GET /auth/me` reads the caller's identity exclusively from `request.user`, populated by `JwtAuthGuard` after signature, expiry and issuer verification (`src/tokens/current-user.decorator.ts:10-13`, consumed at `src/auth/auth.controller.ts:52`). No query parameter, header, or body field can substitute a different identity — the decorator never reads anything but the guard-set value. Separately, registration ignores any client-supplied role: `RegisterRequest` accepts an optional `roles` array (`src/auth/dto/register.dto.ts:29-32`), but `AuthService.register()` hardcodes `roles: ['CUSTOMER']` regardless of what was sent (`src/auth/auth.service.ts:41`), so self-registration cannot escalate to `ADMIN`.

**Disposition:** Mitigated. Both controls are enforced in code, and the identity-source control is exercised by `jwt-auth.guard.spec.ts`'s five guard tests (all passing as of the reviewed commit).

---

### A02:2021 – Cryptographic Failures

**Finding:** Passwords are hashed with argon2id; the cost parameters were benchmarked on the dev machine and the reasoning is recorded in-line rather than assumed (`src/users/password.service.ts:4-19`): four combinations were measured (34.1ms / 43.3ms / 135.9ms / 177.2ms), and `timeCost=2, memoryCost=65536` (~136ms) was chosen deliberately over the faster options to land past the "order of a tenth of a second" target, and over the slower option to avoid making login the platform's cheapest DoS target. Refresh tokens are hashed with SHA-256 before storage, not argon2/bcrypt — a deliberate choice recorded in the migration itself (`src/database/migrations/002_create_refresh_tokens_table.sql:7-9`: "Refresh tokens are short-lived (7 days) and rotated frequently, so password-strength hashing... is unnecessary overhead"), implemented at `src/tokens/refresh-token.repository.ts:34-36`. The JWT signing algorithm is pinned explicitly on both sides, not read from the token's own header: `{algorithm: 'HS256', ...}` at sign time and `algorithms: ['HS256']` at verify time (`src/tokens/token.service.ts:61` and `:78`), closing the algorithm-confusion hole where a verifier trusts an attacker-chosen `alg`.

**Disposition:** Mitigated for the mechanisms reviewed. One related open item — the shared HS256 secret's rotation status — is carried under A04 below rather than duplicated here.

---

### A03:2021 – Injection

**Finding:** Every database query in both repositories uses `pg`'s parameterized placeholders; none build SQL by string concatenation. `src/users/users.repository.ts:47-51`: `INSERT INTO auth.users (id, username, password_hash, account_id, roles) VALUES ($1, $2, $3, $4, $5)`. `src/tokens/refresh-token.repository.ts:66-70`: `SELECT id, user_id, token_hash, expires_at, revoked, created_at FROM auth.refresh_tokens WHERE user_id = $1 AND expires_at > now()`. Every other query in both files (`refresh-token.repository.ts`'s `save`, `verifyAndGetUserId`, `revoke`, `delete`, `findByUserId`) follows the same `$n` placeholder pattern.

**Disposition:** Mitigated. No injectable query path found in either repository this review covered.

---

### A04:2021 – Insecure Design

**Finding:** Refresh-token revocation and hard_expiry are both enabled by design:

**Revocation:** Enabled by default. `REFRESH_TOKEN_REVOCATION_ENABLED` defaults to `true` in code (`src/tokens/refresh-token.service.ts:27`), independent of what `.env` sets. The one-row-per-user schema (`uq_auth_refresh_tokens_user_id`, `002_create_refresh_tokens_table.sql:24`) means a rotated-past token's hash is actually gone from the table on the next rotation, not merely flagged — a replayed old token fails lookup outright, verified behaviourally in `refresh-token.service.spec.ts`'s "the declared revocation behaviour holds for a token that has already been exchanged" test.

**Hard Expiry (SEC2-680 fix):** Fixed sliding-window vulnerability. Previously (pre-fix), `expires_at = now() + 7days` on every refresh extended the session indefinitely. Fixed design (commit `7636408`): 
- Refresh tokens now store immutable `created_at` (login timestamp) and `hard_expiry` (created_at + 7 days).
- Every refresh updates only `token_hash`, leaving `created_at` and `hard_expiry` unchanged.
- After 7 days from initial login, all refresh attempts fail with "Session hard expired, please login again" (`refresh-token.service.ts:82`).
- The window is held by application code, not by the database. Migration `003_update_refresh_tokens_fixed_expiry.sql` adds `hard_expiry`, a `CHECK (hard_expiry > created_at)` and an `updated_at` trigger, but nothing in Postgres stops an `UPDATE` of `created_at` or `hard_expiry`. A refresh never writes them (`updateTokenHashOnly`); a login deliberately rewrites them, because a login starts a new session.
- Tests verify: "hard_expiry_is_NOT_extended_when_token_is_refreshed" and "cannot_refresh_after_hard_expiry_has_passed" in `refresh-token.service.spec.ts`.
- **Defect found in a later test, and fixed.** `RefreshTokenRepository.save()` (called by login) upserted only `token_hash` and `revoked` on conflict, so the window stayed anchored to the user's very first login: from day 8 a user could log in but no refresh token ever worked. The upsert now also sets `created_at` and `hard_expiry`. Checked against a real Postgres, inside a transaction that was rolled back: before the change a login on day 8 left the refresh token unusable, after it the window restarts at seven days. `refresh-token.repository.spec.ts` asserts the statement resets both columns and that a refresh never writes them.

**No tokens on registration:** Registration deliberately issues no session (`auth.service.ts:43`'s comment: "Do not issue tokens on registration"), so an unauthenticated route can't become a session factory.

**JWT_SECRET rotation:** The development `JWT_SECRET` published in `.env.example` and used verbatim in the root `.env` (`mission-control-shared-secret-key-32-bytes-minimum`) has not been rotated since the project's inception, and nothing in this service enforces or tracks rotation.

**Disposition:** 
- **Revocation:** Mitigated — both one-time use and revocation are load-bearing, tested, and not merely documented intent.
- **Hard Expiry:** Mitigated — a fixed 7-day window per login, enforced in application code. No extension via refresh; a new login starts a new window (the defect where it did not is fixed, see above).
- **No tokens on registration:** Mitigated — design prevents session factory vulnerability.
- **JWT_SECRET rotation:** Accepted for development — the sprint README itself calls keeping it "defensible in a training stack," but that's a standing decision the team should record explicitly in policy. See Outstanding Items #3.



### A05:2021 – Security Misconfiguration

**Finding:** The global `ValidationPipe` rejects unknown fields (`whitelist: true, forbidNonWhitelisted: true`, `src/main.ts:14-20`). `.env` is gitignored and confirmed never tracked (`git status --porcelain --ignored` shows it only under `!!`, never `M` or `A`). This review found and fixed a real misconfiguration in the reviewed commit: `ConfigModule.forRoot()` had no `envFilePath`, so it defaulted to looking for `.env` inside `auth-service/` itself rather than the root `.env` that actually holds `JWT_SECRET` — `npm start` failed on every boot with "JWT_SECRET must be set and at least 32 characters long" regardless of the real secret's validity. A second, related misconfiguration surfaced once the first was fixed: `token.service.ts` and `account-validation.service.ts` both read `process.env.JWT_SECRET` as top-level module constants, which JavaScript evaluates before `ConfigModule.forRoot()` (itself just an argument evaluated later, inside the same file's `@Module` decorator) ever runs — so the check always lost that ordering race and crashed regardless of `envFilePath`. Both fixed in commit `6ece565`: `app.module.ts` now passes `envFilePath: '../.env'`, and both files read the secret lazily instead of at import time.

**Disposition:** Fixed (commit `6ece565`), verified live this session: `npm start` now boots cleanly (`[Nest] ... Nest application successfully started`), confirmed listening, and handled a real `POST /auth/register` request with genuine application logic (see Evidence table). One separate, unresolved item found during verification: `@nestjs/throttler@6.7.0` ships a CommonJS build incompatible with the installed `@nestjs/common@12.0.4` (ESM-only, no CJS entry point) — importing either crashes Jest specifically. Live testing this session confirmed the *running* service is unaffected (`ThrottlerModule dependencies initialized` cleanly under `nest start`, and the throttle guard demonstrably works — see Evidence table), so this is a test-tooling gap, not a runtime one, but it's still an unresolved dependency mismatch. See Outstanding Items #4.

---

### A06:2021 – Vulnerable and Outdated Components

**Finding:** `npm audit` reports 4 vulnerabilities (3 low, 1 high). The high one is `tmp <=0.2.5` (GHSA-52f5-9888-hmc6, GHSA-ph9p-34f9-6g65 — arbitrary temp file/directory write and path traversal via symlink), with no fix available upstream. Dependency chain: `tmp → external-editor → inquirer → @nestjs/mau`.

**Disposition:** Accepted. `@nestjs/mau` sits under `devDependencies` (`package.json:40-43`, alongside `@nestjs/cli` and `@nestjs/schematics`) — confirmed zero references to "mau" anywhere in `src/` via grep. Confirmed in `Dockerfile`: the builder stage runs `npm run build && npm prune --production` (line 10) BEFORE the runner stage copies `node_modules` from it (line 19), so devDependencies including this entire chain are stripped before the image is built — this vulnerable code never reaches the deployed service. No fix exists for the root `tmp` vulnerability itself. Residual risk: none in the deployed service; the only theoretical exposure is a developer's own local machine running Nest CLI tooling, an accepted pre-existing risk of the framework itself.

---

### A07:2021 – Identification and Authentication Failures

**Finding:** `auth-service`'s own guard validates signature, expiry and issuer together in one call (`token.service.ts`'s `jwt.verify(token, getJwtSecret(), {algorithms: ['HS256'], issuer: JWT_ISSUER})`), exercised by `jwt-auth.guard.spec.ts`'s "refuses a token with the wrong issuer" test. `trade-api-service` now does the same: `SecurityConfig.jwtDecoder` builds its decoder with `JwtValidators.createDefaultWithIssuer(issuer)` (issuer from `app.security.jwt-issuer`, default `auth-service`), so a correctly signed token whose `iss` is missing or different is refused with the uniform `AUTH-401`, as `contracts/api-schemas/auth-api.yaml` requires ("Consumers must validate the configured issuer"). It also refuses a token with no positive whole-number `accountId` claim, so a malformed token can no longer reach a controller and fail there with a 500; the account a token may trade is that claim and nothing else (the old username lookup table is gone).

**Evidence:** `SecurityAndAccountAccessWebMvcTest`: `correctlySignedTokenFromAnotherIssuerReturnsAuth401`, `correctlySignedTokenWithNoIssuerReturnsAuth401`, `tokenFromTheConfiguredIssuerIsAccepted`, `correctlySignedTokenWithNoAccountIdReturnsAuth401NotA500`, `anAccountIdThatIsNotAPositiveWholeNumberReturnsAuth401`. The instructor's live run on 28 September agreed for the issuer (`iss: evil-issuer` gave 401, no `iss` gave 401).

Two further weaknesses in this area were found in a later review and fixed:
- **Login throttle window.** `LOGIN_THROTTLE_TTL_SECONDS` (900) was handed to `@nestjs/throttler` v6, which reads `ttl` in milliseconds, so the "15 minute" window was 900 ms long and the limit of 5 cleared itself in about a second (live: `X-RateLimit-Reset: 1`). `loginThrottleOptions` (`src/common/login-throttle.ts`) now converts seconds to milliseconds and refuses a non-positive value at start-up; `login-throttle.spec.ts` covers it. Expect five login requests per address per 15 minutes; raise `LOGIN_THROTTLE_LIMIT` in a local `.env` for repeated manual or end-to-end runs.
- **Dummy hash cost.** The hash the unknown-username path verifies against was generated without `parallelism`, so it was `p=4` against the real hashes' `p=1`: a timing difference between "no such user" and "wrong password". `DummyHashService` now uses the same `ARGON2_OPTIONS` object as `PasswordService`, and `dummy-hash.spec.ts` compares the two.

**Disposition:** Fixed.

---

### A08:2021 – Software and Data Integrity Failures

**Finding:** Builds are reproducible from a committed `package-lock.json` — verified this session with a real `npm ci` on a machine with no prior `node_modules`, which installed exactly 587 packages as locked, no resolution drift. The multi-stage `Dockerfile` builds from source in a `builder` stage and only copies the result of `npm prune --production` into the `runner` stage (`Dockerfile:10` prunes, `:19` copies from the pruned tree), so no build tooling or devDependency ships in the deployed image. No CI/CD pipeline exists in this repository to review for build or deploy integrity controls — `find . -path "*workflows*"` from the repo root returns nothing.

**Disposition:** Mitigated for the build mechanics actually reviewed. Out of scope: there is no CI/CD pipeline yet for this repository to assess signing, SBOM, or provenance controls against.

---

### A09:2021 – Security Logging and Monitoring Failures

**Finding:** A redacting logger was built this session (`src/common/redacting-logger.ts`, commit `6ece565`) that strips `password`, `accessToken`, `refreshToken`, `token`, and `authorization` (case- and separator-insensitive) at any object nesting depth before a log call reaches the console, tested against nested objects and arrays in `redacting-logger.spec.ts`. It is wired into the two places previously using a plain, unredacted `Logger`: `dummy-hash.ts:26` and `account-validation.service.ts:28`. That coverage is real but partial: `http-exception.filter.ts` — the single `@Catch()` handler for every exception this service produces, including a genuine unexpected 500 that isn't a normal auth failure — contains no logging call anywhere in the file. Its `catch()` method destructures `request` at line 10 and never uses it again; every branch only builds and returns a JSON response. This means an unexpected server error, a malformed request reaching the generic fallback, or any other unanticipated exception leaves no trace anywhere, redacted or otherwise.

**Disposition:** Partial. The redaction mechanism itself is sound and tested where it's actually used, but overall security-event visibility is incomplete — an attack or bug that hits the generic exception path is invisible to any log. See Outstanding Items #5.

---

### A10:2021 – Server-Side Request Forgery (SSRF)

**Finding:** The only outbound request this service makes is `AccountValidationService.isUsable()`'s call to `${TRADE_API_BASE_URL}/api/v1/accounts/${accountId}` (`account-validation.service.ts:45`). `TRADE_API_BASE_URL` is a fixed, environment-configured value, never client input. The only client-supplied piece of that URL is `accountId`, which `RegisterRequest`'s validators constrain to a positive integer (`@IsInt() @Min(1)`, `register.dto.ts:24-27`) and which is used only as a numeric path segment — never to construct a hostname, scheme, or arbitrary path fragment.

**Disposition:** Mitigated. No attacker-controlled destination is reachable through this request.

---

## Evidence

| Check | Command | Real result |
|---|---|---|
| Full test suite | `npm test` (in `auth-service/`) | `Test Suites: 8 passed, 8 total` / `Tests: 1 skipped, 30 passed, 31 total`, exit code 0 |
| Dependency audit | `npm audit` | `tmp <=0.2.5` `Severity: high` ... `No fix available` / `4 vulnerabilities (3 low, 1 high)` |
| Live boot | `npm start` | `[Nest] ... [NestApplication] Nest application successfully started`; confirmed listening via `curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/` → `200` |
| Real registration request | `curl -X POST http://localhost:3000/auth/register -d '{"username":"audit.test.user",...}'` | `HTTP/1.1 422` / `{"errorCode":"VAL-422","message":"Invalid input"}` — correct, because `trade-api-service` wasn't running to validate `accountId`; server log confirmed the real cause: `[AccountValidationService] Account check for id=1 failed: fetch failed` |
| Throttle, before the RATE-429 fix | 6× `curl -X POST http://localhost:3000/auth/login` in a row | Attempts 1-5: `401` with `X-RateLimit-Remaining` counting `4 → 0`; attempt 6: `429 Too Many Requests` with body `{"errorCode":"VAL-422","message":"Invalid input"}` (wrong — status/body mismatch) |
| Throttle, after the RATE-429 fix | Same 6× `curl` sequence, re-run against the fixed code | Attempts 1-5 unchanged; attempt 6: `429 Too Many Requests` with body `{"errorCode":"RATE-429","message":"Too many attempts, try again later"}` (correct) |
| Component placement (A06) | `grep -n '"dependencies"\|"devDependencies"\|"@nestjs/mau"' package.json` | `@nestjs/mau` at `package.json:42`, under `devDependencies` (section starts `:40`) |
| No mau reference in app code (A06) | `grep -rn "mau" src --include=*.ts -i` | Zero matches (grep exit code 1) |
| Prune before copy (A06) | `Dockerfile` read directly | Line 10: `RUN npm run build && npm prune --production` (builder stage); line 19: `COPY --from=builder /usr/src/app/node_modules ./node_modules` (runner stage, reads the already-pruned tree) |
| Reproducible install (A08) | `npm ci` on a machine with no prior `node_modules` | `added 587 packages, and audited 588 packages` — no resolution drift from the lock file |
| No CI pipeline (A08) | `find . -path "*workflows*"` from repo root | No output — no CI/CD configuration exists in this repository |

---

## Outstanding items

1. ~~**`trade-api-service` does not validate the `iss` claim**~~ **Resolved:** it now does, and refuses a token without a numeric `accountId` (see A07). Original text: **`trade-api-service` did not validate the `iss` claim** (`SecurityConfig.java`, `JwtValidators.createDefault()`). Owner: team decision on whether this is acceptable long-term given SEC2-682's "no Java change" constraint, or whether that constraint should be revisited for a follow-up sprint. Target: before final demo, or explicitly deferred in writing if not resolved by then.
2. **`RATE-429` is not in `contracts/api-schemas/auth-api.yaml`'s `ErrorResponse.errorCode` enum.** This service introduced it (commit `6ece565`) to stop a throttled request from returning a misleading body, but the contract itself only lists `AUTH-401`, `AUTH-409`, `VAL-422`. Owner: whoever owns `contracts/api-schemas/auth-api.yaml` needs to add a real, agreed error code for a throttled response — this service should not decide that unilaterally, and other consumers of the contract need to know about it too.
3. **The development `JWT_SECRET` has not been rotated since project inception.** Owner: team decision on a rotation policy before treating any deployment of this branch as more than a training exercise.
4. **`@nestjs/throttler@6.7.0` is incompatible with the installed `@nestjs/common@12.0.4` under Jest's ESM loader.** Confirmed: importing either crashes Jest with "Must use import to load ES Module." Live testing this session showed the running service itself is unaffected, but the throttle guard currently has no direct Jest-level test because of this — only the live curl evidence above. Owner: dependency version resolution (bump `@nestjs/throttler`, or otherwise reconcile the versions) so the guard can be tested directly.
5. **`http-exception.filter.ts` logs nothing for any exception**, including unexpected 500s. No security-event visibility exists outside the normal 401/409/422/429 response paths. Owner: add a logging call through `RedactingLogger` (to stay consistent with the A09 fix already in place) before this can be called resolved.
6. ~~**A07's finding rests on static analysis, not a confirmed live HTTP response.**~~ **Resolved:** confirmed live by the instructor on 28 September, and covered by the tests listed under A07. Original text: **A07's finding rested on static analysis, not a confirmed live HTTP response.** The actual test — a forged-issuer token sent to a running `trade-api-service` — has not been performed in any session covering this branch. Owner: run that test before treating A07's residual-risk judgment as fully proven.
7. **`@nestjs/observe` ships telemetry to a third party.** It is wired in `app.module.ts` with placeholder keys (`YOUR_APP_KEY`, `YOUR_APP_SECRET`) and tries to send data at runtime (live: "Telemetry rejected (401)"). Owner: decide whether a training service should ship telemetry at all. If it stays, real keys belong in `.env`, never in source. Open.
