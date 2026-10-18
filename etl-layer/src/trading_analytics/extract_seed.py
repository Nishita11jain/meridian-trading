import pandas as pd
from .db import get_postgres_connection


def extract_table(query):

    conn = get_postgres_connection()

    try:
        df = pd.read_sql(query, conn)
        return df

    finally:
        conn.close()



def extract_data():

    instruments = extract_table("""
        SELECT *
        FROM instruments;
    """)


    accounts = extract_table("""
        SELECT *
        FROM accounts;
    """)


    orders = extract_table("""
        SELECT *
        FROM orders;
    """)


    positions = extract_table("""
        SELECT *
        FROM positions;
    """)


    return {
        "instruments": instruments,
        "accounts": accounts,
        "orders": orders,
        "positions": positions
    }