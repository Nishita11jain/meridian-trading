# 0013 Preferences are one row per customer; the display currency is applied to totals and the default account is stored only

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-08 |
| Decided by | Team 5 |

## Context

Preferences holds a notification channel and contact detail, a display currency, a theme, an avatar and a default account. We had to choose how to store them, and what sign-in does with each.

## Options considered

| Option | For | Against |
|---|---|---|
| One row in `customer_preferences` with a column per setting | The database checks each value, notifications can join on the channel, and a missing row means "use the defaults" | A new setting is a migration |
| One JSON document per customer | New settings need no migration | Nothing checks the values, and the channel check would live in application code |

## Decision

A table with a column per setting. Sign-in reads the row and applies the theme. The default account is stored and editable on the preferences page, but nothing uses it: one login is tied to one account, and the API answers any other account with `ACC-403`, so there is nothing to choose between. The front end used to copy it and the currency into `sessionStorage`, where nothing read them, and that was removed.

The display currency is applied. Cash and totals are held in rupees; with dollars chosen they are divided by the stored mid USD/INR rate (the same daily rate orders are converted with, without the order margin), so the dashboard, the portfolio summary, cash, profit and loss and the order ticket's cost line follow the choice. A share's own price (average cost, last price, the limit price a customer types) always stays in the currency it trades in, with its symbol, so a price is never shown in a currency it was not quoted in. If the rate has not loaded, amounts are shown in rupees under the rupee symbol rather than guessed.

## Consequences

- Changing the display currency changes how totals read, not what the account holds or what an order costs; orders are still placed and settled in rupees (decision-log/0025).
- A rate that is a day old is good enough for display; it is not used to settle anything.
- When customers with several accounts appear, sign-in is where the default account gets applied.
