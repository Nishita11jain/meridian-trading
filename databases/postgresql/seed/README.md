# Seed data

What `apply.sh` loads into an empty database, and who can sign in.

## Logins

Every login uses the password `Meridian#2026`. There is one login per trading account.

| Username | Holder | Account | Status | What they show |
|---|---|---|---|---|
| `arun.us` | Arun U S | ETP-4101 | ACTIVE | Rupee blue chips, US tech and ETH. Mixed wins and losses, some profit already booked |
| `nishita.jain` | Nishita Jain | ETP-4102 | ACTIVE | Mostly US stocks, display currency set to USD, a fully sold QQQ position (quantity 0) |
| `parthasarathi.bhattacharya` | Parthasarathi Bhattacharya | ETP-4103 | ACTIVE | Mostly Indian shares, slightly down overall: two sells at a loss and one at a gain |
| `ananya.p` | Ananya P | ETP-4104 | ACTIVE | Crypto-heavy with large unrealised gains, one profitable partial sell and one loss-making exit |
| `neel.varma` | Neel Varma | ETP-4105 | SUSPENDED | Holds three positions; orders are refused with `ACCOUNT_SUSPENDED` |

Five more accounts have no login and exist to cover other cases: ETP-4106 Ishita Kapoor (only ₹500 left, so a buy is
refused for funds), ETP-4107 Rohan Iyer (suspended, cash untouched), ETP-4108 Kavya Menon (closed, zero balance),
ETP-4109 Aditya Rao and ETP-4110 Simran Joshi (small active accounts).

## What the data is

- **Instruments:** 50 NSE shares, 50 US shares and ETFs, 25 crypto pairs, the FX pairs, and one delisted share.
- **Trading history:** from mid June to early October 2026, 69 fills across the accounts. Every price is a real daily
  close from the Fauxnance API, so the profit and loss on the portfolio page is real movement, not invented numbers.
- **Consistent by construction:** each fill has one trade and one ledger row, positions use the weighted-average-cost
  rule, and cash equals the ledger. Dollar fills are booked in rupees at that day's USD/INR close plus the 0.5% order
  margin, with the rate kept on the order and the trade, the same way settlement does it. `checks/ledger-reconciliation.sql`
  returns no problem rows.
- **Around the trading:** the latest prices and exchange rates, preferences, watchlists, price alerts (some already
  triggered) and notifications. Candle and price history is not seeded: it is pulled on demand, and the portfolio value
  chart builds up from the fills and the stored quotes until it has history.
- **Orders that did not fill:** rejected for funds, for a suspended account and for a delisted share; four cancelled; one
  still `NEW` on the suspended account, which the executor will refuse when it starts.

The prices come from the live API at the time the seed was made. The market-data poller replaces them with live quotes
as soon as the executor is running, so the portfolio moves from the seeded close to the current price.

## Loading it

```bash
cd databases/postgresql
TARGET_DATABASE=<empty database> ./apply.sh
```

It needs an empty database: the seed uses plain inserts, so running it twice fails on the first duplicate. To start
again, drop and recreate the database. `seed/auth-users.sql` is the only part that can be re-run safely.

The CSV files here are readable copies of the same data. The SQL is what gets applied.
