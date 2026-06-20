#!/usr/bin/env bash
# stress_test.sh — Distributed Rate Limiter stress test
#
# Fires TOTAL_REQUESTS concurrent HTTP requests against /api/resource and
# reports throughput, per-request latency, and 200/429 distribution.
#
# Usage:
#   ./src/test/scripts/stress_test.sh [TOTAL_REQUESTS] [CONCURRENCY] [CAPACITY] [REFILL_RATE]
#
# Defaults: 200 requests, 50 concurrent, capacity=50, refillRate=1
#
# Requirements: bash, curl
# The Spring Boot service must be running on localhost:8080.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOTAL="${1:-200}"
CONCURRENCY="${2:-50}"
CAPACITY="${3:-50}"
REFILL_RATE="${4:-1}"
KEY="stress-$$-$(date +%s)"
TMPDIR_RESULTS=$(mktemp -d)
trap 'rm -rf "$TMPDIR_RESULTS"' EXIT

# ── helpers ──────────────────────────────────────────────────────────────────
print_header() {
  echo ""
  echo "╔══════════════════════════════════════════════════════╗"
  printf  "║  %-52s║\n" "$1"
  echo "╚══════════════════════════════════════════════════════╝"
}

# ── configure key ────────────────────────────────────────────────────────────
print_header "Distributed Rate Limiter — Stress Test"
echo "  URL         : $BASE_URL/api/resource"
echo "  Key         : $KEY"
echo "  Capacity    : $CAPACITY tokens"
echo "  Refill rate : $REFILL_RATE token/sec"
echo "  Requests    : $TOTAL total, $CONCURRENCY concurrent"
echo ""

HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/resource" -H "X-API-Key: ping" || true)
if [[ "$HTTP" != "200" && "$HTTP" != "429" ]]; then
  echo "ERROR: service not reachable at $BASE_URL (got HTTP $HTTP). Start it first."
  exit 1
fi

curl -s -X PUT "$BASE_URL/api/admin/limits/$KEY" \
  -H "Content-Type: application/json" \
  -d "{\"capacity\":$CAPACITY,\"refillRate\":$REFILL_RATE}" > /dev/null
echo "Bucket configured. Starting requests..."
echo ""

# ── fire requests in batches of CONCURRENCY ──────────────────────────────────
WALL_START=$(date +%s%N)

i=0
while (( i < TOTAL )); do
  batch_end=$(( i + CONCURRENCY ))
  (( batch_end > TOTAL )) && batch_end=$TOTAL

  pids=()
  for (( j=i; j<batch_end; j++ )); do
    (
      T0=$(date +%s%N)
      STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
                 -H "X-API-Key: $KEY" "$BASE_URL/api/resource")
      T1=$(date +%s%N)
      MS=$(( (T1 - T0) / 1000000 ))
      echo "$STATUS $MS" >> "$TMPDIR_RESULTS/$j"
    ) &
    pids+=($!)
  done

  for pid in "${pids[@]}"; do wait "$pid" || true; done
  i=$batch_end
done

WALL_END=$(date +%s%N)
WALL_MS=$(( (WALL_END - WALL_START) / 1000000 ))

# ── aggregate results ─────────────────────────────────────────────────────────
ALLOWED=0; REJECTED=0; OTHER=0
SUM_MS=0; MIN_MS=99999; MAX_MS=0
declare -A BUCKETS  # latency histogram buckets: 0-9,10-19,...

for f in "$TMPDIR_RESULTS"/*; do
  read -r STATUS MS < "$f"
  case "$STATUS" in
    200) ALLOWED=$((ALLOWED+1)) ;;
    429) REJECTED=$((REJECTED+1)) ;;
    *)   OTHER=$((OTHER+1)) ;;
  esac
  SUM_MS=$((SUM_MS + MS))
  (( MS < MIN_MS )) && MIN_MS=$MS
  (( MS > MAX_MS )) && MAX_MS=$MS
  bucket=$(( MS / 10 * 10 ))
  BUCKETS[$bucket]=$(( ${BUCKETS[$bucket]:-0} + 1 ))
done

COMPLETED=$(( ALLOWED + REJECTED + OTHER ))
AVG_MS=$(( SUM_MS / COMPLETED ))
THROUGHPUT=$(echo "scale=1; $COMPLETED * 1000 / $WALL_MS" | bc)

# ── print results ─────────────────────────────────────────────────────────────
print_header "Results"
printf "  %-22s %s\n"  "Total completed:"   "$COMPLETED / $TOTAL"
printf "  %-22s %s\n"  "Allowed (200):"     "$ALLOWED"
printf "  %-22s %s\n"  "Rejected (429):"    "$REJECTED"
[ "$OTHER" -gt 0 ] && printf "  %-22s %s\n" "Other errors:" "$OTHER"
echo ""
printf "  %-22s %s\n"  "Wall-clock time:"   "${WALL_MS}ms"
printf "  %-22s %s req/s\n" "Throughput:"   "$THROUGHPUT"
echo ""
printf "  %-22s min=%dms  avg=%dms  max=%dms\n" "Latency:" "$MIN_MS" "$AVG_MS" "$MAX_MS"
echo ""

# latency histogram
print_header "Latency histogram"
for bucket in $(echo "${!BUCKETS[@]}" | tr ' ' '\n' | sort -n); do
  count=${BUCKETS[$bucket]}
  bar=$(printf '%0.s█' $(seq 1 $(( count * 40 / COMPLETED + 1 ))))
  printf "  %3d–%3dms │ %-42s %d\n" "$bucket" "$(( bucket+9 ))" "$bar" "$count"
done
echo ""

# Atomicity check
# The upper bound on allowed requests is capacity + tokens that could have
# refilled during the wall-clock time of the test.  A true race condition
# would allow far more than this ceiling (e.g. every thread reads the same
# non-zero token count before any write commits).
print_header "Atomicity check"
REFILL_DURING_TEST=$(echo "scale=0; $WALL_MS * $REFILL_RATE / 1000" | bc)
MAX_ALLOWED=$(( CAPACITY + REFILL_DURING_TEST ))
EXCESS=$(( ALLOWED - MAX_ALLOWED ))
echo "  Capacity              : $CAPACITY tokens"
echo "  Refill during test    : ~$REFILL_DURING_TEST tokens  (${WALL_MS}ms × ${REFILL_RATE}/s)"
echo "  Max theoretically OK  : $MAX_ALLOWED tokens"
echo "  Actually allowed      : $ALLOWED tokens"
echo ""
if (( ALLOWED <= MAX_ALLOWED )); then
  echo "  PASS — allowed ($ALLOWED) ≤ max expected ($MAX_ALLOWED)"
  echo "         Lua EVAL atomicity is holding correctly."
else
  echo "  FAIL — allowed ($ALLOWED) exceeds max expected ($MAX_ALLOWED) by $EXCESS"
  echo "         Possible race condition — investigate Lua script."
  exit 2
fi
echo ""
echo "  Tip: use refillRate=1 to minimize refill noise in the atomicity check."
echo ""
