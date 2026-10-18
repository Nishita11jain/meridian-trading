

# Sprint 4 Symbol Universe and Claims

## Chosen Universe

The Sprint 4 analysis is deliberately limited to three Indian instruments so
that the charts, data checks, and review discussion remain manageable.

| Symbol | Exchange | Reason for inclusion |
|---|---|---|
| `INFY.NS` | NSE | Large, widely followed IT-services company and already present in the local one-year Fauxnance cache for fast sanity checks. |
| `RELIANCE.NS` | NSE | Large, diversified Indian company providing exposure beyond the IT sector. |
| `TATASTEEL.BO` | BSE | Adds a BSE-listed industrial/materials instrument, providing a second exchange and a different sector. |

These three symbols satisfy the Sprint 4 scope while remaining small enough to
inspect manually.

The symbols follow the Fauxnance exchange format:

- `.NS` – NSE
- `.BO` – BSE

`AAPL` is used as a test fixture only and is not part of the Sprint 4 universe.

---

## ETL Design

The ETL pipeline is divided into four clear responsibilities:

- `extract.py` – obtains the raw market-data response and returns it unchanged.
- `transform.py` – validates and shapes the data without network or filesystem access.
- `load.py` – writes the transformed analytical data to the database.
- `pipeline.py` – coordinates the ETL stages by passing the output of one stage to the next.

The command-line adapter is kept separately in `cli.py`, so configuration and
argument parsing do not leak into the transformation logic.

This separation keeps the ETL stages independently testable and makes the
pipeline easier to maintain.

---

## Claims

| # | Claim | Chart artefact |
|---|---|---|
| 1 | Infosys finished highest on the normalized closing-price path over the latest 27 valid sessions, followed by Tata Steel and Reliance. | artifacts/dashboard.html#relative-price-path |
| 2 | Reliance had the most resilient closing-price path over the latest 27 valid sessions, with the shallowest maximum drawdown of the three instruments, followed by Tata Steel and then Infosys. | artifacts/dashboard.html#drawdown |
| 3 | All three instruments contributed the same number of valid observations, so their recent price-path and drawdown ordering is directly comparable within this sample. | artifacts/dashboard.html#evidence-table |

---

## Evidence Status

All three symbols were extracted from Fauxnance using the requested `1y`
range. The latest 27 valid sessions were then selected after transformation.

**⚠ Regenerate this table from the current cache before the review — run
`python -m trading_analytics.dashboard` and copy the figures it prints. The
numbers below were correct as of an earlier cache pull and no longer match
`artifacts/dashboard.html`.**

| Symbol | Candles | Available dates | Close return | Maximum drawdown |
|---|---:|---|---:|---:|
| `INFY.NS` | 27 | *(refresh)* | *(refresh)* | *(refresh)* |
| `RELIANCE.NS` | 27 | *(refresh)* | *(refresh)* | *(refresh)* |
| `TATASTEEL.BO` | 27 | *(refresh)* | *(refresh)* | *(refresh)* |

### Summary

Within the current 27-session sample, Infosys has the highest close return
and the deepest drawdown of the three; Reliance has the shallowest drawdown.
Exact figures are in the table above once refreshed.

The results describe only the selected period and should not be interpreted as
representative of the wider Indian market.

Close return is calculated using the first and last valid closing prices in the
selected sample. Maximum drawdown is calculated using the lowest close relative
to the previous closing-price peak.

These figures are descriptive project analytics and are **not investment advice**.

---

## Notes

Symbols in scope: `INFY.NS`, `RELIANCE.NS`, `TATASTEEL.BO` (all NSE/BSE),
pulled over the `1y` Fauxnance range and narrowed to the latest 27 sessions
with a valid close on all three instruments. Rows dropped by the transform
(missing fields, invalid dates, non-numeric values, `high < low`, negative
volume, null numeric values) are logged and excluded rather than raising —
see `tests/fixtures/readme.md` for the full policy per defect type.

