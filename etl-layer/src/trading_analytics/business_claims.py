from pathlib import Path

import duckdb


def load_business_claims(database_path: Path) -> dict[str, object]:
    with duckdb.connect(str(database_path), read_only=True) as connection:
        highest_value = connection.execute(
            """
            SELECT i.name, i.symbol, SUM(f.trade_value) AS trade_value
            FROM fact_trades f
            JOIN dim_instrument i ON i.instrument_key = f.instrument_key
            WHERE f.status = 'FILLED'
            GROUP BY i.name, i.symbol ORDER BY trade_value DESC LIMIT 1
            """
        ).fetchone()
        highest_exposure = connection.execute(
            """
            SELECT i.name, i.symbol, SUM(p.quantity * p.average_cost) AS exposure
            FROM portfolio_positions p
            JOIN dim_instrument i ON i.symbol = p.symbol
            WHERE p.quantity > 0
            GROUP BY i.name, i.symbol ORDER BY exposure DESC LIMIT 1
            """
        ).fetchone()
        busiest_day = connection.execute(
            """
            SELECT EXTRACT(HOUR FROM f.created_at) AS hour, COUNT(*) AS order_count, SUM(f.trade_value) AS trade_value
            FROM fact_trades f
            WHERE f.status = 'FILLED'
            GROUP BY hour ORDER BY order_count DESC, trade_value DESC, hour
            LIMIT 1
            """
        ).fetchone()
    return {
        "highest_value": {"name": highest_value[0], "symbol": highest_value[1], "value": float(highest_value[2])},
        "highest_exposure": {"name": highest_exposure[0], "symbol": highest_exposure[1], "value": float(highest_exposure[2])},
        "busiest_hour": {"hour": int(busiest_day[0]), "orders": int(busiest_day[1]), "value": float(busiest_day[2])},
    }