# Analytics ETL

Sprint 4 analytics and ETL module for the trading application.

## Purpose

This module extracts Fauxnance end-of-day market data, transforms it into an
analytics-friendly format, and writes it to DuckDB, then renders it as a
self-contained HTML dashboard for the three claims in `claims.md`.

## What's inside

- Fauxnance market-data extraction, with disk caching
- Data transformation and cleaning, with a documented malformed-row policy
- DuckDB analytics database
- Dashboard (`artifacts/dashboard.html`)
- Automated tests

## Data source

- Fauxnance market data API (`GET /candles/{symbol}`), key read from
  `FAUXNANCE_API_KEY`

## Output

The processed analytics data is stored in:

```text
data/analytics.duckdb
```

The dashboard supporting the three claims in `claims.md` is written to:

```text
artifacts/dashboard.html
```

## Running

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[dev]'
.venv/bin/python -m pytest .
trading-analytics INFY.NS RELIANCE.NS TATASTEEL.BO 1y
.venv/bin/python -m trading_analytics.dashboard
```