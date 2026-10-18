# Trade Advice & Signals

Sprint 10 stretch extension. A read-only module inside the Trade REST API: it informs a customer
who trades on an instrument they hold or watch; it never trades.

## What it does

For every symbol an account holds (a non-zero position) or watches (an item on any of its
watchlists), this module can state a view -- BUY, SELL or HOLD -- with the reasoning and the
numbers behind it, computed from an SMA20/SMA50 crossover over daily Fauxnance candles.

## Cache-aside, not scheduled

There is no background job. `GET /api/v1/advice/{accountId}/signals` only ever reads the
`trade_signal` cache table -- it never calls Fauxnance. The only action that may call out is the
explicit customer action, `POST /api/v1/advice/{accountId}/signals/{symbol}/analyse`:

- Inside the cooldown (`advice.cooldown-hours`, default 24h) it serves the cached row -- no external
  call.
- Past the cooldown it calls `FauxnanceCandleClient`, recomputes, and caches the new result.
- A failed refresh with an older cached signal on hand serves that signal marked `stale: true`
  rather than failing the whole call; it only raises `ADV-503` when there is nothing cached to fall
  back to.

This bounds the module's entire Fauxnance footprint to symbols a customer has actually asked about,
not to the size of the instrument catalogue, and keeps every call to the quota-shared Fauxnance API
key an explicit, user-triggered event rather than an implicit one.

## Access control

Every route addresses an account in the path and compares it to the verified token's `accountId`
claim; a mismatch is `ACC-403`, the same answer every other extension module gives. A symbol outside
the account's holdings and watchlists is `ADV-404` -- advice is never offered for an arbitrary
tradable instrument the customer has shown no interest in.

## Tables

- `candle_cache` -- the raw Fauxnance candle response per symbol, with `expires_at` as the stated
  TTL.
- `trade_signal` -- the latest computed direction, strength and rationale per symbol.

Both are keyed by symbol alone, not by account: a signal is a property of the instrument, shared by
every customer who holds or watches it, the same way `market_price` is shared rather than
duplicated per account.
