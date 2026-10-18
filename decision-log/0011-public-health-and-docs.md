# 0011 Two things are reachable without a token: GET /health and, for now, the API docs

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-07 |
| Decided by | Team 5 |

## Context

Every route under `/api/v1/` needs a verified token, and the Sprint 10 brief says a route that has to be public needs a decision log entry and a line in the security review. Two things were public or needed to be. `contracts/portfolio-api.yaml` declares `GET /health` with `security: []`, but the service answered its own health route with `AUTH-401` and put it under `/api/v1/portfolio`, so a monitor following the contract could not use it. And Swagger UI and `/v3/api-docs` were open to everyone, because an earlier sprint required the running service to serve its own OpenAPI document.

## Options considered

| Option | For | Against |
|---|---|---|
| Keep the health check behind the token | One rule for every route | Breaks the contract and gives a load balancer or monitor nothing it can call |
| Make the whole `/api/v1/portfolio/**` prefix public | Simple | Exposes the three account routes, which is the finding the review exists to catch |
| Permit exactly `GET /health` | Matches the contract, and it reports only service and dependency status | One more line in the security configuration to keep an eye on |

For the docs we considered closing them now. That would break the earlier requirement that the running service serves its own contract, so they stay open by default and `app.docs.public=false` puts them behind the token.

## Decision

Permit `GET /health` and nothing else; the old `/api/v1/portfolio/health` is gone and needs a token like any other route. Keep the docs public in the local profile and switch them off with `app.docs.public=false` in any deployed one. Each case has a test that proves the other portfolio routes and the closed docs still answer `AUTH-401`.

## Consequences

- The contract and the service agree on health.
- The docs reveal the route list to anyone who can reach the port, which is acceptable locally and must be switched off before the cloud week.
- Advice and strategies keep their health routes behind the token, since their contracts do not ask otherwise.
