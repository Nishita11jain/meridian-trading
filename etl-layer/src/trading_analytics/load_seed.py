import duckdb


def load_to_duckdb(data):

    conn = duckdb.connect(
        "data/analytics.duckdb"
    )


    for table, df in data.items():

        conn.execute(
            f"""
            CREATE OR REPLACE TABLE {table}
            AS SELECT *
            FROM df
            """
        )


    conn.close()