# 0010 Automated Strategy Execution's identity: a minted internal token, never a borrowed customer session

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-07 |
| Decided by | Team 5 |

## Context

A strategy fires from `StrategyMarketDataConsumer`, inside the Kafka listener thread, with no customer
logged in and no bearer token on the call stack. It must still place an order through
`POST /api/v1/orders`, which `SecurityConfig` protects like every other route: `anyRequest().authenticated()`,
a verified JWT with a positive-integer `accountId` claim. Something has to produce that token.

## Options considered

| Option | For | Against |
|---|---|---|
| Call `OrderService.placeOrder(...)` directly, in-process, skipping HTTP | No token needed at all; fewer moving parts | Bypasses `SecurityConfig`'s filter chain and `OrderController`'s own validation entirely -- a bug in the strategy evaluator would have a second, unguarded path into order placement, with none of the route's own defenses. The stretch brief names this explicitly as the shortcut not to take. |
| Cache a customer's real session token from their last sign-in and reuse it | Looks like "a real user acting" | A session token expires; reusing an expired or revoked one is wrong, and storing a live customer credential anywhere the strategy engine can read it is a credential-handling liability for no benefit -- the strategy isn't the customer, it shouldn't pretend to be. |
| Mint a short-lived, single-purpose internal JWT at the moment of firing, call the real `POST /api/v1/orders` route over HTTP (even though it's the same process) | Goes through the exact same filter chain, controller validation and idempotency check a human's request would; the token is scoped to one `accountId`, expires in seconds, and is never returned to anyone; the order row this produces is attributable to the strategy by its own audit trail (`strategy_execution_log`), not to a forged customer action | A self-HTTP call inside one JVM has a small latency/complexity cost over a direct method call |

## Decision

Mint a short-lived (30s) internal JWT at the moment a strategy fires, signed with the same shared
`app.security.jwt-secret` the platform already uses. `iss` must be the platform's real configured
issuer (`JwtDecoder` refuses anything else, same as every other token), so the machine-origin marker
instead lives in `sub` (`"strategy-engine:<strategyId>"`), alongside the one claim that matters for
authorization, `accountId` (the strategy's own account). Use it for exactly one call to
`POST /api/v1/orders` over real HTTP to this same service's own port, then discard it. This is the
same minting pattern `auth-service`'s `AccountValidationService` already uses for its own
internal-only check (`sub: 'auth-service-internal-check'`, real `iss`), applied here for the same
reason: the one thing that can mint a valid token is the one thing that should, rather than
widening what counts as a valid session.

## Consequences

- The order route's validation, authorization and idempotency check are never bypassed, no matter
  which caller -- human or strategy -- placed the order; there is exactly one path into order
  placement, not two.
- A strategy can only ever place an order for its own account: the minted token's `accountId` is the
  strategy's own, and `PlaceOrderRequest.accountId()` must match the token's claim or `OrderServiceImpl`
  refuses it with `ACC-403`, the same check a forged customer request would hit.
- `app.internal-base-url` must be configured to this service's own reachable address; a strategy
  cannot fire if that is misconfigured, which is a startup-time check worth adding rather than a
  silent no-op.
- The token is never logged, never persisted, and never returned from any route -- only
  `strategy_execution_log` records that a strategy (not a token) caused an order.
