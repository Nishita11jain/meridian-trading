import argparse
from pathlib import Path

from .client import get_base_url
from .pipeline import run_symbols


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the trading analytics ETL")
    parser.add_argument("symbols", nargs="+")
    parser.add_argument("range_name")
    parser.add_argument("--database-path", type=Path, default=Path("data/analytics.duckdb"))
    args = parser.parse_args()
    run_symbols(get_base_url(), args.database_path, args.symbols, args.range_name)


if __name__ == "__main__":
    main()