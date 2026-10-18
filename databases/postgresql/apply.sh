#!/usr/bin/env bash

set -euo pipefail

# ============================================================
# Sprint 3 - Database Apply Script
# ============================================================

if [[ -z "${PGDATABASE}" ]]; then
    if [[ -f "../../.env" ]]; then
        set -a
        source "../../.env"
        set +a
    fi
    PGDATABASE="${PGDATABASE}"
fi

if [[ -z "${PGDATABASE}" ]]; then
    echo "ERROR: PGDATABASE is not set and POSTGRES_DB is not set."
    exit 1
fi

PGHOST="${PGHOST}"
PGPORT="${PGPORT}"
PGUSER="${PGUSER}"
PGPASSWORD="${PGPASSWORD}"

export PGHOST
export PGPORT
export PGUSER
export PGPASSWORD

echo "=========================================="
echo " Sprint 3 Database Apply"
echo "=========================================="
echo "Host     : ${PGHOST}"
echo "Port     : ${PGPORT}"
echo "User     : ${PGUSER}"
echo "Database : ${PGDATABASE}"
echo

echo "Applying migrations..."
for f in $(ls migrations/*.sql | sort); do
    echo "  -> ${f}"
    psql --dbname="${PGDATABASE}" --set=ON_ERROR_STOP=1 -f "${f}"
done
echo "All migrations applied successfully."

echo "Applying seed data..."
psql --dbname="${PGDATABASE}" --set=ON_ERROR_STOP=1 \
     --single-transaction -f seed-data.sql
echo "Seed data applied successfully."

echo "Applying seed logins..."
psql --dbname="${PGDATABASE}" --set=ON_ERROR_STOP=1 -f seed/auth-users.sql
echo "Seed logins applied successfully."

psql --dbname="${PGDATABASE}" --set=ON_ERROR_STOP=1 <<'SQL'
\echo 'Final row counts:'

SELECT 'accounts' AS table_name, COUNT(*) AS row_count FROM accounts
UNION ALL
SELECT 'instruments', COUNT(*) FROM instruments
UNION ALL
SELECT 'orders', COUNT(*) FROM orders
UNION ALL
SELECT 'positions', COUNT(*) FROM positions
UNION ALL
SELECT 'trades', COUNT(*) FROM trades
UNION ALL
SELECT 'account_transactions', COUNT(*) FROM account_transactions
UNION ALL
SELECT 'auth.users (logins)', COUNT(*) FROM auth.users
ORDER BY table_name;
SQL

echo
echo "=========================================="
echo " APPLY SUCCESSFUL"
echo "=========================================="