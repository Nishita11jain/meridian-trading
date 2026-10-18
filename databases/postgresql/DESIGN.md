# Database

Sprint 3 database layer for the trading application.

## Purpose

This folder contains the PostgreSQL database schema and database-related
artifacts used to persist trading data.

## What's inside

- Database migrations for accounts, instruments, orders, and positions
- Seed data for development and testing
- ER diagram and database design documentation
- Indexes and named queries
- SQL probes for checking database constraints and behaviour

## Main tables

- `accounts` – trading account information and cash balance
- `instruments` – tradable instruments and their status
- `orders` – orders placed by accounts
- `positions` – holdings of instruments for accounts
- `trades` – what was actually executed for a filled order (added in
  migration 007)
- `account_transactions` – append-only ledger of cash movements (added in
  migration 007)
- `market_price`, `price_history`, `watchlists`, `watchlist_items`,
  `price_alerts` – Sprint 10 (Watchlists and Price Alerts) tables, added
  early in migration 009 since they have no dependency on any Sprint 10
  code; see `DecisionLog.md`

## Technology

- PostgreSQL
- SQL
- Shell script for applying migrations

## Applying the database

From the `database` folder:

```bash
./apply.sh
```

Reads its target database from the `TARGET_DATABASE` environment variable,
falling back to `POSTGRES_DB` in the repository `.env` if `TARGET_DATABASE`
is not set. Applies every file in `migrations/` in filename order, then loads
`seed-data.sql` in a single transaction, then `seed/auth-users.sql` (the demo
logins). Fails and exits non-zero the moment anything errors. The CSV files in
`seed/` are readable copies of the seed, not something the script loads; see
`seed/README.md` for who is in it.

## Historical trade data

`orders` (and `positions`) answer "what is true right now" and are indexed
and constrained for that job. The desk's other question — what did we trade
last quarter, by instrument, by week — reads a large slice of history at once
and must not compete with order placement for locks or cache. That question
is answered by a separate analytical store rather than by adding reporting
indexes to the operational tables.

### What is retained, and at what grain

One fact row per **filled** order, one grain: an executed trade. `NEW` and
`CANCELLED` orders are never loaded into the analytical store — they carry no
execution and are already the operational record's job to explain —
`REJECTED` and `CANCELLED` orders stay queryable in `orders` for the
compliance case the brief calls out, but they are not trade history. Beyond
what `orders` already carries (account, instrument, side, quantity, limit
price, status, timestamp), the analytical grain adds the fields the desk
actually asks for: `executed_price` (distinct from the limit `price` the
customer submitted — they diverge once the Sprint 7 executor fills against a
live quote), a derived `trade_value` (`quantity * executed_price`, so every
downstream aggregate query sums a column instead of recomputing a product),
and a `date_key` so trades roll up by day/week/month/quarter without a
per-query date-truncation function.

This is the star schema already committed at
[contracts/analytics-schemas/analytics-schema.sql](../../contracts/analytics-schemas/analytics-schema.sql):
`fact_trades` at one row per source order, with `dim_account`, `dim_instrument`
and `dim_date` resolving the descriptive attributes the desk slices by. Account
and instrument dimensions are modelled slowly-changing (`effective_date`,
`end_date`, `is_current` on `dim_account`) so a trade always reports the
account status and instrument name *as they were on the trade date*, not as
they are today — an account that is `SUSPENDED` now must not rewrite the
history of trades it made while `ACTIVE`.

### How it is populated, and by which component

The Sprint 4 pipeline (`etl-layer/`) populates this store: extract reads
rows, transform reshapes them into the dimension/fact grain above, load
writes them into DuckDB. This sprint's version of extract reads Fauxnance
market-data candles, because the platform's own order flow does not exist
until Sprint 6/7. The extract/transform/load split, and the fact/dimension
shape, are both already built to point at the operational Postgres tables
instead: `analytics-etl` ships a second, parallel pipeline
(`extract_seed.py` / `transform_seed.py` / `load_seed.py`) that reads the
seeded `accounts` / `instruments` / `orders` tables directly and loads them
into the same DuckDB file via this schema, so Sprint 7 changes only which
extract function is wired into the pipeline, not the shape being loaded into
or the tests around the transform.

### Incremental extraction

The nightly extract cannot re-scan the whole of `orders` every run once the
table is months old. It uses named Query 4 — `ix_orders_created_on` — to pull
only `orders` with `created_on` after the last successful load's high-water
mark, tracked as a single `MAX(loaded_at)` (or last-run timestamp) kept by the
loader. Because `fact_trades.source_order_id` is unique, a re-run that
overlaps the previous window (for example after a retry) upserts rather than
duplicates, so the extract can be re-run safely without first computing an
exact non-overlapping window. Only `FILLED` orders reaching that status after
the watermark are loaded; an order that is `NEW` at extraction time and fills
later is picked up on the run after it fills, keyed by its own `created_on`/
fill time rather than being missed.

### Behaviour at a hundred times the volume

At today's seed volume this is not a problem worth solving yet, but the shape
does not have to change to survive it:

- `fact_trades` partitions cleanly by `date_key` (range partitioning by
  month or quarter) because every query the desk runs already filters or
  groups by date. Old partitions become read-only and are the natural unit
  for archival.
- Retention is a business decision, not a technical one: nothing here
  proposes deleting trade history, since it is the legal record. What
  partitioning buys is the ability to move older partitions to cheaper
  storage or a separate archive database without touching recent, hot
  partitions.
- DuckDB is a single-file, single-writer store, which is adequate for one
  nightly batch load and interactive analysis, but a hundred times the
  volume with concurrent analysts would justify moving the same schema to a
  server-based warehouse. The schema does not need to change for that move,
  only the load target — which is exactly the seam `load.py` already
  isolates.

### Cost

Write cost is one batch append per day (or per Sprint 7 trade event, once
that exists), not a cost paid per operational write — the analytical store is
never written synchronously with an order. Operational complexity is one
extra scheduled job and one extra file/database to keep backed up; against
that, every reporting query is now isolated from `orders`, so a slow desk
query can no longer hold a lock or contend for cache with order placement.
