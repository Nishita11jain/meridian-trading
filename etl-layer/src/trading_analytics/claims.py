import duckdb


DB_PATH = "data/analytics.duckdb"


def run_claims():

    conn = duckdb.connect(DB_PATH)

    # ============================================================
    # CLAIM 1
    # RELIANCE.NS accounts for the highest executed trading value
    # ============================================================

    claim1 = conn.execute("""
        SELECT
            i.symbol,
            i.instrument_name,
            SUM(o.quantity * o.price) AS total_trading_value,
            COUNT(o.id) AS total_orders
        FROM orders o
        JOIN instruments i
            ON o.symbol = i.symbol
        WHERE o.status = 'FILLED'
        GROUP BY
            i.symbol,
            i.instrument_name
        ORDER BY total_trading_value DESC;
    """).df()


    # ============================================================
    # CLAIM 2
    # Majority of submitted orders are successfully executed
    # ============================================================

    claim2 = conn.execute("""
        SELECT
            status,
            COUNT(*) AS order_count,
            ROUND(
                COUNT(*) * 100.0 /
                SUM(COUNT(*)) OVER (),
                2
            ) AS percentage
        FROM orders
        GROUP BY status
        ORDER BY order_count DESC;
    """).df()


    # ============================================================
    # CLAIM 3
    # Trading activity is concentrated during specific hours
    # ============================================================

    claim3 = conn.execute("""
        SELECT
            EXTRACT(HOUR FROM created_on) AS trading_hour,
            COUNT(*) AS order_count,
            SUM(quantity * price) AS trading_value
        FROM orders
        WHERE status = 'FILLED'
        GROUP BY
            EXTRACT(HOUR FROM created_on)
        ORDER BY trading_hour;
    """).df()


    # ============================================================
    # DISPLAY RESULTS
    # ============================================================

    print("\n" + "=" * 60)
    print("CLAIM 1: TRADING VALUE BY INSTRUMENT")
    print("=" * 60)

    print(claim1.to_string(index=False))


    print("\n" + "=" * 60)
    print("CLAIM 2: ORDER EXECUTION EFFECTIVENESS")
    print("=" * 60)

    print(claim2.to_string(index=False))


    print("\n" + "=" * 60)
    print("CLAIM 3: TRADING ACTIVITY BY HOUR")
    print("=" * 60)

    print(claim3.to_string(index=False))


    conn.close()

    return claim1, claim2, claim3


if __name__ == "__main__":
    run_claims()