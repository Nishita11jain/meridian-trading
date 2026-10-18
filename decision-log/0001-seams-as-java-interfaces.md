# 0001 Seams are Java interfaces, not HTTP

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

Watchlists has to hand a crossed price alert to Notifications, and Notifications has to ask Preferences which channel a customer wants. All of this runs inside the one order-service process, and the alert path has no customer making a request, so there is no token to carry.

## Options considered

| Option | For | Against |
|---|---|---|
| Java interface inside the service | No token needed, no network hop, easy to mock in tests, can be swapped later | Both modules are compiled together, so they cannot be deployed separately |
| HTTP call between the modules | Looks like a real service boundary | Needs a token the alert path does not have, and sending one through the front door would let a customer reach an internal path |
| Kafka event from Watchlists to Notifications | Fully decoupled | The alert and its delivery stop sharing a transaction, so a crossing could be marked triggered without a message ever being sent |

## Decision

Interfaces. Notifications implements `NotificationDelivery` and `CustomerChannelResolver`; Watchlists and Notifications call them directly. Nothing outside a module imports anything inside it except these interfaces.

## Consequences

- The alert, its triggered flag and the delivery commit or fail together, which is what decision 0009 relies on.
- If Notifications is ever split out, the interface becomes an adapter around an HTTP call and the callers do not change.
- The modules cannot be deployed apart, which is fine for this sprint.
