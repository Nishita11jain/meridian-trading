# Auth Service (Sprint 8)

NestJS authentication service for the Enterprise Trading Platform. Registration,
login, refresh, and the current-user lookup, against the contract in
`../../contracts/api-schemas/auth-api.yaml`. Replaces the old `auth-stub` test fixture (removed)
as the real token issuer — every other service verifies the tokens this one
signs, with no network call back here.

## Endpoints

| Method | Path | Protected | Notes |
|---|---|---|---|
| POST | `/auth/register` | no | Links a new login to an existing, active trading account; issues no tokens |
| POST | `/auth/login` | no | Throttled; verifies credentials, issues an access + refresh token pair |
| POST | `/auth/refresh` | no | Rotates a refresh token, issues a new pair |
| GET | `/auth/me` | yes (bearer) | Returns the profile of the token's owner |

## Running it

```bash
npm install
npm run build
npm test          # Jest, no live DB or HTTP server required
npm run start:dev # watch mode, port 3000 by default
```

Needs `.env` at the repo root (`../../.env`) populated — see `.env.example` for
the full list: `JWT_SECRET` (shared with `order-service`), `POSTGRES_*`,
`TRADE_API_BASE_URL` (used to validate an `accountId` at registration),
`LOGIN_THROTTLE_*`, and `REFRESH_TOKEN_*`. Migrations
(`src/database/migrations/*.sql`) run automatically on boot against the
`auth` Postgres schema, gated by `AUTH_RUN_MIGRATIONS`.

## API documentation

Once the service is running (default `http://localhost:3000`), the API
documentation is served at:

- `/docs` — interactive Swagger UI
- `/docs/json` — the OpenAPI document as JSON

## Where things live

| Module | What it owns |
|---|---|
| `auth/` | The four HTTP routes and their DTOs |
| `users/` | Credential store, argon2id hashing, the accountId existence/active check |
| `tokens/` | Access token sign/verify, the route guard, refresh token issuance and rotation |
| `database/` | The Postgres pool and migration runner |
| `common/` | The shared `{errorCode, message}` error envelope and a logger that redacts secrets by key name |

See `security-review/` for the OWASP-review write-ups against this service.
