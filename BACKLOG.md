# Sprint 10 backlog

Team 5. Status is as of 7 October 2026. The build order follows the chain: preferences, then notifications, then watchlists. Portfolio runs alongside because nothing waits on it.

## Scope

**Building by Friday:** the four modules (Preferences, Notifications, Watchlists and Alerts, Portfolio) and the two stretch ones (Trade Advice, Strategy Execution). The stretch ones only started once the chain worked.

**Not building:** real email and SMS delivery (they are recorded as queued, only in-app is actually sent), encrypting contact details in the database, and any channel that posts to a customer's own URL.

**Routes for the three modules without a contract:**

| Module | Routes |
|---|---|
| Preferences | `GET /api/v1/preferences/{accountId}`, `PUT .../notifications`, `.../display`, `.../avatar` |
| Notifications | `GET /api/v1/notifications/{accountId}`, `GET .../{id}`, `POST .../read`, `POST .../{id}/read`, `POST .../{id}/unread` |
| Watchlists | `/api/v1/watchlists` and its instrument routes, `/api/v1/alerts`, `/api/v1/market/quotes` |

Portfolio follows `contracts/portfolio-api.yaml`. Every route compares the token's account with the account being asked for.

## Stories

| Story | What it needs to do | Owner | Status |
|---|---|---|---|
| 699 Preferences | Saves a default account and alert channel per customer, survives a restart, applied at sign-in, refuses other accounts | Nishita | Done |
| 700 Notifications | Reads `trade-events` in its own group, handles filled, rejected and cancelled, takes the channel from preferences, ignores replays | Nishita | Done |
| 701 Watchlists and alerts | Watchlists with live prices, alerts with a threshold and direction, delivered through notifications, capped per account | Ananya | Done |
| 702 Portfolio and P&L | Follows the published contract (reconciled on 8 October): summary and P&L in rupees using a stored daily USD/INR rate, `/health`, stale and partial answers marked, realised P&L booked at the sale. Prices come from `market_price`, not Fauxnance per request | Partha | Done |
| Sprint 6 order service must not regress | The six contract endpoints answer as before, tests stay green, the image still builds. Found and fixed today: a rejected order froze the whole account. The existing rejection test covers it but only runs with the database variables set | Nishita | Done, fix still to be committed |
| 698 The two seams | Preferences to notifications and notifications to watchlists agreed as Java interfaces, with what each does when the other fails. Each consumer has its own group id, written in its README | Nishita | Done, see decisions 0001, 0003 and 0009 |
| 703 End to end on live data | A stored channel drives a trade notification and an alert arrives on the same channel, on real quotes and real rows | Arun | Done, run on 7 October |
| 704 Security review | One OWASP review across all the modules | Partha | Done, five follow-ups listed in the review |
| 705 Decision log | One file per decision with a rejected option | Ananya | 10 entries, the floor is six |
| 724 Trade advice | BUY, SELL or HOLD with the reasoning, from cached daily candles | Arun | Done |
| 725 Strategy execution | Crossover and Bollinger rules, orders placed through the order route, spend caps, instant disable | Arun | Done |
| 706 Demo | Run the chain live, show a replayed event giving one message, trace a portfolio figure to a live quote, start the stack from a clean checkout | Everyone | To do |

## Still open

- Commit the work (the lock fix, the dead-letter topic, the dependency overrides, the health route), and have CI run the database integration tests, since they skip without the Postgres variables.
- Move to a Spring Boot release that fixes Tomcat and Spring, then drop the version overrides.
- A Java dependency scan in CI.
- Rehearse the demo and make sure a fresh checkout starts, since cloud week depends on it.
