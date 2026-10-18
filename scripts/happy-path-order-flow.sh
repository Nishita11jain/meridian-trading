#!/usr/bin/env bash
#
# Focused happy path: register -> log in -> place a BUY order -> compare the
# account balance before and after.
#
#   bash scripts/happy-path-order-flow.sh
#
# Assumes auth-service (:3000) and order-service (:8081) are ALREADY
# running; it only checks that they answer and never starts them. It also prints
# whether Kafka is reachable (informational only; it never stops the run). No SSH
# or docker logic lives here. Needs psql and node on the PATH and the root .env
# (POSTGRES_*).
#
# Placing an order does not by itself move cash: the platform debits the
# account once, at settlement, when the trade executor fills it. So the "after"
# balance only differs if settlement has already happened. Set
# SETTLE_WAIT_SECONDS=<n> to pause that long before reading it (default 0).

set -uo pipefail

# ---- Registration settings ---------------------------------------------------
# The user this run registers, and the trading account it is linked to.
# REGISTER_ACCOUNT_ID must be changed to a genuinely unclaimed account before
# each run, or registration will fail with 409 (already claimed). (It fails with
# 422 instead if the account does not exist or is not ACTIVE.) This script does
# NOT delete the test user afterwards, so the username and the account stay
# claimed: change both REGISTER_USERNAME and REGISTER_ACCOUNT_ID for every run,
# or remove the old user by hand:
#   DELETE FROM auth.users WHERE username = '<username>';
REGISTER_USERNAME="${REGISTER_USERNAME:-Snehal.kudf}"
# REGISTER_PASSWORD is NOT set here, so no secret is committed: put it in the root
# .env (git-ignored), e.g.  REGISTER_PASSWORD=<at least 12 characters>
REGISTER_ACCOUNT_ID="${REGISTER_ACCOUNT_ID:-4}"

# ---- Order settings ----------------------------------------------------------
# The order placed for REGISTER_ACCOUNT_ID's account. These are fixed here in the
# file; nothing is looked up in the database or generated.
# ORDER_IDEMPOTENCY_KEY must be changed to a new value before each run, or the
# order call will fail with a duplicate-key error (the platform refuses a second
# order with a key it has already seen).
# The account also needs enough cash for ORDER_QUANTITY x ORDER_PRICE (the limit
# price is what is checked), or the order is rejected for insufficient funds.
ORDER_SYMBOL="${ORDER_SYMBOL:-TCS.NS}"
ORDER_SIDE="${ORDER_SIDE:-BUY}"
ORDER_QUANTITY="${ORDER_QUANTITY:-10}"
ORDER_PRICE="${ORDER_PRICE:-5000.00}"
ORDER_IDEMPOTENCY_KEY="${ORDER_IDEMPOTENCY_KEY:-Buy-try-demo-160}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${ROOT}/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${ROOT}/.env"
  set +a
fi

if [[ -z "${REGISTER_PASSWORD:-}" ]]; then
  echo "REGISTER_PASSWORD is not set. Add a line like REGISTER_PASSWORD=<at least 12 characters>" >&2
  echo "to ${ROOT}/.env (that file is git-ignored), then run this again. Nothing was created." >&2
  exit 2
fi

AUTH_URL="${AUTH_BASE_URL:-http://localhost:3000}"
TRADE_URL="${TRADE_API_BASE_URL:-http://localhost:8081}"
SETTLE_WAIT_SECONDS="${SETTLE_WAIT_SECONDS:-0}"

export PGHOST="${POSTGRES_HOST:-localhost}"
export PGPORT="${POSTGRES_PORT:-5432}"
export PGUSER="${POSTGRES_USER:-postgres}"
export PGPASSWORD="${POSTGRES_PASSWORD:-}"
export PGDATABASE="${POSTGRES_DB:-trading}"

USERNAME="${REGISTER_USERNAME}"
PASSWORD="${REGISTER_PASSWORD}"
IDEM_KEY="${ORDER_IDEMPOTENCY_KEY}"

ACCOUNT_ID=""
TOKEN=""
USER_CREATED=0
ORDER_PLACED=0
FUNDED=0
ORIG_BALANCE=""
BEFORE=""
AFTER=""
ORDER_STATUS=""

sql() { psql -X -qAt -v ON_ERROR_STOP=1 -c "$1"; }
# json <expr>: read JSON on stdin, print a JS expression evaluated on `d`.
json() { node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{try{const d=JSON.parse(s);const v=('"$1"');process.stdout.write(v===undefined||v===null?"":String(v))}catch(e){}})'; }
say() { printf '%s\n' "$*"; }
step() { printf '\n== Step %s: %s\n' "$1" "$2"; }
pass() { printf '   PASS  %s\n' "$*"; }
fail() { printf '   FAIL  %s\n' "$*"; }

die() {
  fail "$*"
  exit 1
}

# get_balance: print cashBalance from GET /api/v1/accounts/{id}, or nothing.
get_balance() {
  curl -s --max-time 15 -H "Authorization: Bearer ${TOKEN}" "${TRADE_URL}/api/v1/accounts/${ACCOUNT_ID}" | json 'd.cashBalance'
}

# kafka_reachable <host> <port>: true if a TCP connection is accepted AND the far
# end holds it open. Kafka never speaks first, so a live broker makes the read
# time out; an SSH tunnel with nothing behind it closes the connection at once.
kafka_reachable() {
  local host="$1" port="$2" rc
  (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null || return 1
  exec 3<>"/dev/tcp/${host}/${port}"
  read -r -t 2 -u 3 _ 2>/dev/null; rc=$?
  exec 3<&- 3>&-
  [[ ${rc} -gt 128 ]]
}

# ---------------------------------------------------------------- 1. reachable
step 1 "Check that both services answer"
up=1
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${AUTH_URL}/" || true)"
if [[ "${code}" =~ ^[0-9]{3}$ && "${code}" != "000" ]]; then
  pass "auth-service is up at ${AUTH_URL} (HTTP ${code})"
else
  fail "auth-service is NOT up at ${AUTH_URL}"; up=0
fi
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${TRADE_URL}/api/v1/accounts/1" || true)"
if [[ "${code}" =~ ^[0-9]{3}$ && "${code}" != "000" ]]; then
  pass "order-service is up at ${TRADE_URL} (HTTP ${code} without a token)"
else
  fail "order-service is NOT up at ${TRADE_URL}"; up=0
fi
# Kafka: informational only, never stops the run. Checks the address trade-api-
# service is configured with (KAFKA_BOOTSTRAP_SERVERS in .env, else localhost:9092)
# from THIS machine; it does not prove that order-service itself is connected.
KAFKA_ADDR="${KAFKA_CHECK_ADDRESS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}}"
KAFKA_ADDR="${KAFKA_ADDR%%,*}"
if kafka_reachable "${KAFKA_ADDR%:*}" "${KAFKA_ADDR##*:}"; then
  pass "Kafka is CONNECTED (${KAFKA_ADDR} accepts connections)"
else
  fail "Kafka is NOT connected (${KAFKA_ADDR} is not reachable) -- placing an order will fail with HTTP 500"
fi
if [[ "${up}" != "1" ]]; then
  echo
  echo "Stopping: start the service(s) marked FAIL above, then run this again. Nothing was created."
  exit 2
fi

# ------------------------------------------------------------------ 2. register
step 2 "Register the configured user (${REGISTER_USERNAME}) on account ${REGISTER_ACCOUNT_ID}"
ACCOUNT_ID="${REGISTER_ACCOUNT_ID}"
if [[ ! "${ACCOUNT_ID}" =~ ^[0-9]+$ ]]; then
  ACCOUNT_ID=""
  die "REGISTER_ACCOUNT_ID must be a number (got '${REGISTER_ACCOUNT_ID}')"
fi
say "   using account id ${ACCOUNT_ID}, username ${USERNAME}"
resp="$(curl -s -w '\n%{http_code}' --max-time 15 -X POST "${AUTH_URL}/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\",\"accountId\":${ACCOUNT_ID}}")"
status="${resp##*$'\n'}"; body="${resp%$'\n'*}"
say "   HTTP ${status}  ${body}"
if [[ "${status}" == "201" ]]; then
  USER_CREATED=1; pass "registered"
else
  die "registration failed"
fi

# --------------------------------------------------------------------- 3. login
step 3 "Log in"
resp="$(curl -s -w '\n%{http_code}' --max-time 15 -X POST "${AUTH_URL}/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")"
status="${resp##*$'\n'}"; body="${resp%$'\n'*}"
TOKEN="$(json 'd.accessToken' <<<"${body}")"
if [[ "${status}" == "200" && -n "${TOKEN}" ]]; then
  pass "HTTP 200, access token received (${#TOKEN} characters)"
else
  die "login returned HTTP ${status}: ${body}"
fi

# ---------------------------------------------------------- 4. balance before
step 4 "Read the balance before the order"
BEFORE="$(get_balance)"
if [[ -z "${BEFORE}" ]]; then
  die "could not read the balance from GET /api/v1/accounts/${ACCOUNT_ID}"
fi
say "   Balance BEFORE order: ${BEFORE}"

# ------------------------------------------------------------------- 5. funding
step 5 "Fund the account if it is empty"
if [[ "$(node -e "console.log(Number('${BEFORE}')===0)")" == "true" ]]; then
  ORIG_BALANCE="${BEFORE}"
  if sql "UPDATE public.accounts SET cash_balance = 500000.00 WHERE id = ${ACCOUNT_ID}" >/dev/null 2>&1; then
    FUNDED=1
    pass "balance was 0, set to 500000.00 by direct SQL"
    say "   NOTE: this is a test-setup convenience, not part of the real flow being measured."
    BEFORE="$(get_balance)"
    say "   Balance BEFORE order (after funding): ${BEFORE}"
  else
    die "funding UPDATE failed"
  fi
else
  say "   balance is not 0 (${BEFORE}), no funding needed"
fi

# ---------------------------------------------------------------- 6. order settings
step 6 "Use the configured order"
say "   ${ORDER_SIDE} ${ORDER_QUANTITY} x ${ORDER_SYMBOL} @ ${ORDER_PRICE}, account ${ACCOUNT_ID}, key ${ORDER_IDEMPOTENCY_KEY}"

# ---------------------------------------------------------------- 7. place order
step 7 "Place the order"
resp="$(curl -s -w '\n%{http_code}' --max-time 90 -X POST "${TRADE_URL}/api/v1/orders" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCOUNT_ID},\"symbol\":\"${ORDER_SYMBOL}\",\"side\":\"${ORDER_SIDE}\",\"quantity\":${ORDER_QUANTITY},\"price\":${ORDER_PRICE},\"idempotencyKey\":\"${ORDER_IDEMPOTENCY_KEY}\"}")"
status="${resp##*$'\n'}"; body="${resp%$'\n'*}"
say "   HTTP ${status}"
say "   ${body}"
ORDER_STATUS="$(json 'd.status' <<<"${body}")"
if [[ "${status}" =~ ^2 ]]; then
  ORDER_PLACED=1
  pass "order accepted (status ${ORDER_STATUS:-unknown})"
else
  fail "order was not accepted"
fi

# ---------------------------------------------------------------- 8. balance after
step 8 "Read the balance after the order"
if [[ "${SETTLE_WAIT_SECONDS}" != "0" ]]; then
  say "   waiting ${SETTLE_WAIT_SECONDS}s before reading (SETTLE_WAIT_SECONDS)"
  sleep "${SETTLE_WAIT_SECONDS}"
fi
AFTER="$(get_balance)"
say "   Balance AFTER order: ${AFTER:-<could not be read>}"

# -------------------------------------------------------------------- 9. summary
step 9 "Summary"
say "   Balance BEFORE order: ${BEFORE}"
say "   Balance AFTER order:  ${AFTER:-<unknown>}"
if [[ -n "${AFTER}" ]]; then
  DIFF="$(node -e "console.log((Number('${AFTER}')-Number('${BEFORE}')).toFixed(2))")"
  say "   Difference:           ${DIFF}"
  if [[ "${DIFF}" == "0.00" ]]; then
    say "   Result: the balance did NOT change (order status: ${ORDER_STATUS:-unknown} -- cash only moves once the order settles)."
  else
    say "   Result: the balance changed by ${DIFF}."
  fi
else
  say "   Result: the after-balance could not be read, so no comparison is possible."
fi

