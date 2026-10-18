# 0005 Unauthorized watchlist access returns 404, not 403

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

The Watchlists routes (`/api/v1/watchlists/{id}` for read, rename, delete, reorder and flag; `/api/v1/watchlists/{id}/instruments`) and the alert routes (`DELETE /api/v1/alerts/{id}`) take an id chosen by the client. The account comes only from the verified token. If an id belongs to another account, the API must reject the request.

The question is: should the response be 403 (Forbidden — you don't have permission) or 404 (Not Found — the resource doesn't exist)?

This decision is relevant to OWASP A01 (Broken Access Control). The choice reveals information about the system.

## Options considered

| Option | For | Against |
|---|---|---|
| 403 Forbidden | Clear to the client: "you exist but can't access this"; honest about why the request failed | Leaks information: an attacker can probe for valid IDs and learn which accounts exist |
| 404 Not Found | Hides information: attacker can't probe for valid IDs; looks like the resource doesn't exist to unauthorized users | Misleading: if the resource exists, the response is factually incorrect |

## Decision

Unauthorized watchlist access returns 404. The reasoning is defensive: we do not want to leak information about which watchlist or alert ids exist through response codes. An attacker probing with sequential watchlist or alert ids receives the same `WL-404` / `ALERT-404` for an id that does not exist and one that belongs to someone else, so ids cannot be enumerated.

This is a common pattern in financial systems where account enumeration is a recognized attack vector. The security-review documents this as an intentional design choice (not a bug).

## Consequences

- Clients cannot distinguish between "the resource doesn't exist" and "you don't have permission." They must handle both as 404.
- Testing must verify that 404 is returned for both cases: non-existent ID and unauthorized access.
- Error logging may need to be more verbose (log the accountId mismatch at WARN level so support can debug, but don't expose it to the client).
- This applies to Watchlists (`/api/v1/watchlists/{id}`) but not necessarily to Preferences or Notifications routes, where ACC-403 may be appropriate (see routes in their respective controllers).
