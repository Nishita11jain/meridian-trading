# DuckDB analytics database

`analytics.duckdb` is generated, not committed (`*.duckdb` is git-ignored). Create it with
`python etl-layer/scripts/create_duckdb.py`, then run the ETL (`etl-layer/README.md`); the schema is
[contracts/analytics-schemas/analytics-schema.sql](../../../contracts/analytics-schemas/analytics-schema.sql).
