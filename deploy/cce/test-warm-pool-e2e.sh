#!/usr/bin/env bash
#
# Warm Pool E2E Test
#
# Tests the warm pool feature on a live CCI cluster:
#   1. Pool Initialization   — assert idle pods >= 2
#   2. Session Creation      — warm start via API, assert <COLD_THRESHOLD latency
#   3. Session Cleanup       — DELETE session, assert pool replenishes to 2 idle pods
#
# Usage:
#   export KUBECONFIG=~/.kube/cce-lakeon-config
#   export API_URL=https://your-api-host
#   export API_TOKEN=your-token   # optional; skips Tests 2+3 if unset
#   ./deploy/cce/test-warm-pool-e2e.sh

set -euo pipefail

# ── 配置 ────────────────────────────────────────────────────────────
POOL_NS="datalake-pool"
API_URL="${API_URL:-}"
API_TOKEN="${API_TOKEN:-}"

POLL_INTERVAL=2        # 秒
REPLENISH_TIMEOUT=30   # Test 3 最大等待秒数
COLD_THRESHOLD=10      # 超过此秒数视为冷启动（测试警告）

# ── 工具函数 ─────────────────────────────────────────────────────────
now_sec() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    local msg="$1"
    PASS_COUNT=$(( PASS_COUNT + 1 ))
    echo "  [PASS] $msg"
}

fail() {
    local msg="$1"
    FAIL_COUNT=$(( FAIL_COUNT + 1 ))
    echo "  [FAIL] $msg"
}

diff_sec() {
    local start=$1 end=$2
    python3 -c "print(f'{$end - $start:.2f}')"
}

# ── 前置检查 ─────────────────────────────────────────────────────────
echo "=== Warm Pool E2E Test ==="
echo "Pool namespace : $POOL_NS"
echo "API URL        : ${API_URL:-<not set>}"
echo "API token      : ${API_TOKEN:+<set>}${API_TOKEN:-<not set>}"
echo ""

if [[ -z "${KUBECONFIG:-}" ]]; then
    echo "WARNING: KUBECONFIG is not set; using default ~/.kube/config"
fi

# ── Test 1: Pool Initialization ──────────────────────────────────────
echo "--- Test 1: Pool Initialization ---"

IDLE_PODS=$(kubectl get pods -n "$POOL_NS" \
    -l "lakeon.io/pool=warm,lakeon.io/status=idle" \
    --field-selector=status.phase=Running \
    --no-headers 2>/dev/null | wc -l | tr -d ' ')

echo "  Idle running pods in $POOL_NS: $IDLE_PODS"

if (( IDLE_PODS >= 2 )); then
    pass "Warm pool has >= 2 idle pods (found $IDLE_PODS)"
else
    fail "Warm pool has < 2 idle pods (found $IDLE_PODS, expected >= 2)"
fi
echo ""

# ── Test 2: Session Creation (warm start) ────────────────────────────
SESSION_ID=""
TEST2_RAN=false

if [[ -z "$API_TOKEN" ]]; then
    echo "--- Test 2: Session Creation (SKIPPED — API_TOKEN not set) ---"
    echo ""
else
    echo "--- Test 2: Session Creation (warm start) ---"

    T_START=$(now_sec)

    RESPONSE=$(curl -s -k -X POST "$API_URL/api/v1/datalake/notebook/sessions" \
        -H "Authorization: Bearer $API_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"image":"ray","worker_count":1,"worker_size":"small"}' 2>/dev/null)

    T_END=$(now_sec)
    ELAPSED=$(diff_sec "$T_START" "$T_END")

    echo "  Request took: ${ELAPSED}s"
    echo "  Response: $RESPONSE"

    STATUS=$(echo "$RESPONSE"    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',''))" 2>/dev/null || echo "")
    NS_RESP=$(echo "$RESPONSE"   | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('namespace',''))" 2>/dev/null || echo "")
    POD_NAME=$(echo "$RESPONSE"  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('podName',''))" 2>/dev/null || echo "")
    SESSION_ID=$(echo "$RESPONSE"| python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id','') or d.get('sessionId',''))" 2>/dev/null || echo "")

    # Assert: status == RUNNING
    if [[ "$STATUS" == "RUNNING" ]]; then
        pass "Session status is RUNNING"
    else
        fail "Session status expected RUNNING, got '$STATUS'"
    fi

    # Assert: namespace == POOL_NS
    if [[ "$NS_RESP" == "$POOL_NS" ]]; then
        pass "Session namespace is $POOL_NS"
    else
        fail "Session namespace expected '$POOL_NS', got '$NS_RESP'"
    fi

    # Assert: podName starts with "warm-ray-head-"
    if [[ "$POD_NAME" == warm-ray-head-* ]]; then
        pass "Pod name starts with 'warm-ray-head-' ($POD_NAME)"
    else
        fail "Pod name expected 'warm-ray-head-*', got '$POD_NAME'"
    fi

    # Warm start latency advisory
    CMP=$(python3 -c "print('fast' if $ELAPSED < $COLD_THRESHOLD else 'slow')")
    if [[ "$CMP" == "fast" ]]; then
        pass "Warm start latency ${ELAPSED}s < ${COLD_THRESHOLD}s threshold"
    else
        fail "Warm start latency ${ELAPSED}s >= ${COLD_THRESHOLD}s (possible cold start?)"
    fi

    TEST2_RAN=true
    echo ""
fi

# ── Test 3: Session Cleanup & Pool Replenish ─────────────────────────
if [[ "$TEST2_RAN" != "true" ]]; then
    echo "--- Test 3: Session Cleanup & Pool Replenish (SKIPPED — Test 2 did not run) ---"
    echo ""
else
    echo "--- Test 3: Session Cleanup & Pool Replenish ---"

    if [[ -z "$SESSION_ID" ]]; then
        fail "No session ID available from Test 2; cannot delete session"
        echo ""
    else
        echo "  Deleting session: $SESSION_ID"
        DEL_STATUS=$(curl -s -k -o /dev/null -w "%{http_code}" \
            -X DELETE "$API_URL/api/v1/datalake/notebook/sessions/$SESSION_ID" \
            -H "Authorization: Bearer $API_TOKEN" 2>/dev/null || echo "000")

        if [[ "$DEL_STATUS" =~ ^2 ]]; then
            pass "Session DELETE returned HTTP $DEL_STATUS"
        else
            fail "Session DELETE returned HTTP $DEL_STATUS (expected 2xx)"
        fi

        # Poll until idle pod count >= 2 again
        echo "  Waiting up to ${REPLENISH_TIMEOUT}s for pool to replenish..."
        T_DELETE=$(now_sec)
        REPLENISHED=false

        while true; do
            NOW=$(now_sec)
            WAITED=$(diff_sec "$T_DELETE" "$NOW")
            WAITED_INT=$(python3 -c "print(int($NOW - $T_DELETE))")

            IDLE_NOW=$(kubectl get pods -n "$POOL_NS" \
                -l "lakeon.io/pool=warm,lakeon.io/status=idle" \
                --field-selector=status.phase=Running \
                --no-headers 2>/dev/null | wc -l | tr -d ' ')

            echo "  [${WAITED}s] Idle pods: $IDLE_NOW"

            if (( IDLE_NOW >= 2 )); then
                pass "Pool replenished to >= 2 idle pods in ${WAITED}s"
                REPLENISHED=true
                break
            fi

            if (( WAITED_INT >= REPLENISH_TIMEOUT )); then
                fail "Pool did not replenish to 2 idle pods within ${REPLENISH_TIMEOUT}s (current: $IDLE_NOW)"
                break
            fi

            sleep "$POLL_INTERVAL"
        done
    fi
    echo ""
fi

# ── 汇总 ─────────────────────────────────────────────────────────────
echo "============================================"
echo "  Warm Pool E2E Test Summary"
echo "============================================"
echo "  PASS: $PASS_COUNT"
echo "  FAIL: $FAIL_COUNT"
echo "============================================"
echo ""

exit "$FAIL_COUNT"
