"""Create a DuckDB database from an SQL schema file.

Usage:
    python scripts/create_duckdb.py <database_path> <schema_sql_path>

Example:
    python scripts/create_duckdb.py data/analytics.duckdb ../contracts/analytics-schemas/analytics-schema.sql
"""
from pathlib import Path
import sys

import duckdb


def main(argv):
    if len(argv) != 3:
        print("Usage: python scripts/create_duckdb.py <database_path> <schema_sql_path>")
        return 2

    _, database_path, schema_path = argv

    db_file = Path(database_path)
    schema_file = Path(schema_path)

    if not schema_file.exists():
        print(f"Schema file not found: {schema_file}")
        return 2

    db_file.parent.mkdir(parents=True, exist_ok=True)

    sql = schema_file.read_text(encoding="utf-8")

    con = duckdb.connect(str(db_file))

    # Execute the full schema SQL. DuckDB accepts multiple statements.
    con.execute(sql)
    con.close()

    print(f"Created DuckDB at {db_file} using schema {schema_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
