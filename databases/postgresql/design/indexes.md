# Sprint 3 — Index Justifications

## Purpose

Indexes are created only when a named query has a clear access-pattern
benefit.

Every additional index has a write and storage cost because INSERTs and
relevant UPDATEs must maintain the index. This is especially important
for the `orders` table because it is expected to be write-heavy.

The Sprint 3 requirement is to justify three deliberate indexes against
the named queries.

The three deliberate indexes selected are:

1. `ix_orders_account_open_created` → Query 1
2. `ix_orders_account_created` → Query 2
3. `ix_orders_created_on` → Query 4

Primary-key and UNIQUE-constraint indexes are not counted as deliberate
indexes because PostgreSQL creates those automatically. For that reason:

- Query 3 (current holdings for one account) is already served by the
  `positions` primary key `(account_id, symbol)`.
- Query 5 (resolve an account from its customer-facing reference) is
  already served by `uq_accounts_account_id`.


# 1. Query 1 — Open Orders for One Account, Newest First

## Named Query

Query 1 retrieves all `NEW` orders for one account and returns the
newest order first.

## Index Definition

```sql
CREATE INDEX ix_orders_account_open_created
ON orders (account_id, created_on DESC)
WHERE status = 'NEW';
```

## Rationale

This is a **partial** index: it only contains rows where
`status = 'NEW'`. Open orders are a small, constantly-changing subset of
the total `orders` table (most rows eventually settle into `FILLED`,
`REJECTED`, or `CANCELLED` and are never queried by this access pattern
again). Restricting the index to `NEW` rows keeps it small relative to
the full table, which keeps both the index scan and the per-write
maintenance cost cheap on a write-heavy table.

The leading column `account_id` matches the query's equality filter, and
`created_on DESC` lets PostgreSQL satisfy `ORDER BY created_on DESC`
directly from the index without a separate sort step.


# 2. Query 2 — Last 50 Orders for One Account, Any State, Newest First

## Named Query

Query 2 retrieves the most recent 50 orders for one account, regardless
of status, newest first.

## Index Definition

```sql
CREATE INDEX ix_orders_account_created
ON orders (account_id, created_on DESC);
```

## Rationale

Unlike Query 1, this query is not restricted to a single status, so a
partial index cannot be used here — it needs to cover every order for
the account, in any state. The index is still narrow and effective
because:

- `account_id` as the leading column lets PostgreSQL jump directly to
  that account's rows instead of scanning the whole table.
- `created_on DESC` again matches the required sort order, so combined
  with the `LIMIT 50` in the query, PostgreSQL can stop scanning as soon
  as it has collected 50 matching rows, rather than sorting the full
  result set.

This index is deliberately similar to `ix_orders_account_open_created`
but is not redundant with it: the partial index only helps when the
query also filters on `status = 'NEW'`, which Query 2 does not do.


# 3. Query 4 — Every Order Since a Timestamp, Across All Accounts

## Named Query

Query 4 retrieves every order created since a given timestamp, across
all accounts, without filtering by account.

## Index Definition

```sql
CREATE INDEX ix_orders_created_on
ON orders (created_on);
```

## Rationale

This query has no `account_id` filter, so neither of the two
account-scoped indexes above can help it — `account_id` would either be
absent from the predicate entirely or would force a full index scan
regardless of column order. A single-column index on `created_on` lets
PostgreSQL use a range scan (`created_on >= :timestamp`) instead of a
sequential scan of the entire `orders` table, which matters as the table
grows and this query is expected to run frequently (e.g. for downstream
ETL/analytics extraction in later sprints).

A descending sort was not chosen here because the query orders results
ascending (`ORDER BY o.created_on ASC`) from the given timestamp forward,
so an ascending index directly satisfies both the range filter and the
sort order.


# Summary

| Index | Columns | Type | Serves |
|---|---|---|---|
| `ix_orders_account_open_created` | `(account_id, created_on DESC)` | Partial (`status = 'NEW'`) | Query 1 |
| `ix_orders_account_created` | `(account_id, created_on DESC)` | Full | Query 2 |
| `ix_orders_created_on` | `(created_on)` | Full | Query 4 |

Queries 3, 5, and 6 do not require new deliberate indexes: Query 3 and
Query 5 are covered by existing primary-key/unique-constraint indexes,
and Query 6 is scoped to a single account (already narrowed by
`ix_orders_account_created`) before its window functions run in memory
over that reduced row set.
