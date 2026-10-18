import duckdb
import matplotlib.pyplot as plt
import os


DB_PATH = "data/analytics.duckdb"
OUTPUT_DIR = "output"


os.makedirs(OUTPUT_DIR, exist_ok=True)


def generate_charts():

    conn = duckdb.connect(DB_PATH)


    # ============================================================
    # CHART 1
    # Trading Value by Instrument
    # ============================================================

    claim1 = conn.execute("""
        SELECT
            symbol,
            SUM(quantity * price) AS trading_value
        FROM orders
        WHERE status = 'FILLED'
        GROUP BY symbol
        ORDER BY trading_value DESC;
    """).df()


    plt.figure(figsize=(8, 5))

    plt.bar(
        claim1["symbol"],
        claim1["trading_value"]
    )

    plt.title(
        "Executed Trading Value by Instrument"
    )

    plt.xlabel(
        "Instrument"
    )

    plt.ylabel(
        "Trading Value (INR)"
    )

    plt.xticks(rotation=30)

    plt.tight_layout()

    plt.savefig(
        f"{OUTPUT_DIR}/claim1_trading_value.png",
        dpi=300
    )

    plt.close()


    # ============================================================
    # CHART 2
    # Order Execution Effectiveness
    # ============================================================

    claim2 = conn.execute("""
        SELECT
            status,
            COUNT(*) AS order_count
        FROM orders
        GROUP BY status
        ORDER BY order_count DESC;
    """).df()


    plt.figure(figsize=(7, 5))

    plt.bar(
        claim2["status"],
        claim2["order_count"]
    )

    plt.title(
        "Order Execution Status"
    )

    plt.xlabel(
        "Order Status"
    )

    plt.ylabel(
        "Number of Orders"
    )

    plt.tight_layout()

    plt.savefig(
        f"{OUTPUT_DIR}/claim2_order_execution.png",
        dpi=300
    )

    plt.close()


    # ============================================================
    # CHART 3
    # Trading Activity by Hour
    # ============================================================

    claim3 = conn.execute("""
        SELECT
            EXTRACT(HOUR FROM created_on) AS trading_hour,
            COUNT(*) AS order_count
        FROM orders
        WHERE status = 'FILLED'
        GROUP BY trading_hour
        ORDER BY trading_hour;
    """).df()


    plt.figure(figsize=(9, 5))

    plt.plot(
        claim3["trading_hour"],
        claim3["order_count"],
        marker="o"
    )

    plt.title(
        "Executed Trading Activity by Hour"
    )

    plt.xlabel(
        "Trading Hour"
    )

    plt.ylabel(
        "Number of Filled Orders"
    )

    plt.xticks(
        claim3["trading_hour"]
    )

    plt.grid(True)

    plt.tight_layout()

    plt.savefig(
        f"{OUTPUT_DIR}/claim3_trading_time.png",
        dpi=300
    )

    plt.close()


    conn.close()

    print("\nCharts generated successfully!")

    print(
        f"Charts saved in: {OUTPUT_DIR}/"
    )


if __name__ == "__main__":
    generate_charts()