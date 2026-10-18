"""
Ties extract / transform / load together, and exposes each as its own CLI
subcommand so a teammate can run any one stage on its own -- e.g. to
inspect stg_orders_extracted before trusting transform's output, or to
re-run just load after fixing a dead-lettered row's root cause.

    python -m fact_trades.src.pipeline extract  --database-path data/analytics.duckdb
    python -m fact_trades.src.pipeline transform --database-path data/analytics.duckdb
    python -m fact_trades.src.pipeline load      --database-path data/analytics.duckdb
    python -m fact_trades.src.pipeline run       --database-path data/analytics.duckdb   # all three, in order
"""

import argparse
from pathlib import Path

import duckdb

# Absolute import: fact_trades.src sits as its own top-level package next to
# trading_analytics under etl-layer/src/, not nested inside it -- so a
# relative "..db" no longer reaches trading_analytics/db.py, and has to be
# named explicitly instead.
from trading_analytics.db import get_postgres_connection
from .schema import ensure_schema
from .extract import run_extract
from .transform import run_transform
from .load import run_load


def _connect(database_path: Path) -> duckdb.DuckDBPyConnection:
    """Opens the DuckDB file, creating its parent folder first if needed --
    duckdb.connect() will happily create the .duckdb file itself, but it
    does not create a missing parent directory, which is an easy first-run
    trap otherwise. Also ensures the schema exists, so every entry point
    below can just call this and start working immediately."""

    database_path.parent.mkdir(parents=True, exist_ok=True)
    duck_con = duckdb.connect(str(database_path))
    ensure_schema(duck_con)
    return duck_con


def run_pipeline(database_path: Path) -> None:
    """Convenience: extract, then transform, then load, in one call. If
    there's nothing new, stops after extract -- transform/load have
    nothing to do and are skipped, not run against an empty batch."""

    duck_con = _connect(database_path)

    pg_conn = get_postgres_connection()
    try:
        batch_id = run_extract(pg_conn, duck_con)
    finally:
        pg_conn.close()

    if not batch_id:
        print("No new orders since the last watermark. Nothing to do.")
        duck_con.close()
        return

    valid_count, dead_count = run_transform(duck_con, batch_id=batch_id)
    written = run_load(duck_con, batch_id=batch_id)

    print(f"batch {batch_id}: {valid_count} valid, {dead_count} dead-lettered, {written} fact rows written/updated")
    duck_con.close()


def _cmd_extract(args: argparse.Namespace) -> None:
    duck_con = _connect(args.database_path)
    pg_conn = get_postgres_connection()
    try:
        batch_id = run_extract(pg_conn, duck_con)
    finally:
        pg_conn.close()
    print(batch_id if batch_id else "No new orders since the last watermark.")
    duck_con.close()


def _cmd_transform(args: argparse.Namespace) -> None:
    duck_con = _connect(args.database_path)
    valid_count, dead_count = run_transform(duck_con, batch_id=args.batch_id)
    print(f"{valid_count} valid, {dead_count} dead-lettered")
    duck_con.close()


def _cmd_load(args: argparse.Namespace) -> None:
    duck_con = _connect(args.database_path)
    written = run_load(duck_con, batch_id=args.batch_id)
    print(f"{written} fact rows written/updated")
    duck_con.close()


def _cmd_run(args: argparse.Namespace) -> None:
    run_pipeline(args.database_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="FACT_TRADES incremental load (SEC2-672)")
    subparsers = parser.add_subparsers(dest="stage", required=True)

    # --database-path is defined once here and attached to every subcommand,
    # so it works whether it's typed before or after the stage name --
    # argparse only accepts a parent parser's own options before the
    # subcommand, which is an easy trap to fall into otherwise.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--database-path", type=Path, default=Path("data/analytics.duckdb"))

    subparsers.add_parser("extract", parents=[common]).set_defaults(func=_cmd_extract)

    p_transform = subparsers.add_parser("transform", parents=[common])
    p_transform.add_argument("--batch-id", default=None)
    p_transform.set_defaults(func=_cmd_transform)

    p_load = subparsers.add_parser("load", parents=[common])
    p_load.add_argument("--batch-id", required=True)
    p_load.set_defaults(func=_cmd_load)

    subparsers.add_parser("run", parents=[common]).set_defaults(func=_cmd_run)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()