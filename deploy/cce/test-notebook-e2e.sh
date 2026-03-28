#!/usr/bin/env bash
#
# Notebook E2E 测试 — CLI 模拟完整 notebook 会话
#
# 流程: 创建 session → WebSocket 连接 → 发 Python 代码 → 验证输出 → 清理
#
# 用法:
#   ./deploy/cce/test-notebook-e2e.sh                   # 默认 ray + 1 worker
#   ./deploy/cce/test-notebook-e2e.sh python-data 0     # 非 Ray session
#
# 前置条件:
#   - websocat 已安装 (brew install websocat)
#   - LAKEON_API_TOKEN 环境变量已设置

set -euo pipefail

API_URL="${LAKEON_API_URL:-https://api.dbay.cloud:8443}"
API_TOKEN="${LAKEON_API_TOKEN:-}"
IMAGE_KEY="${1:-ray}"
WORKER_COUNT="${2:-1}"
TIMEOUT=60

if [[ -z "$API_TOKEN" ]]; then
    echo "ERROR: LAKEON_API_TOKEN 未设置"
    exit 1
fi

# 检查 websocat
if ! command -v websocat &>/dev/null; then
    echo "ERROR: websocat 未安装，请执行: brew install websocat"
    exit 1
fi

now_sec() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
FAILURES=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILURES=$((FAILURES + 1)); }

echo "=== Notebook E2E Test ==="
echo "Image: $IMAGE_KEY, Workers: $WORKER_COUNT"
echo ""

# ── Step 1: 清理已有 session ─────────────────────────────────
echo "[Step 1] 清理已有 session..."
EXISTING=$(curl -s -k "$API_URL/api/v1/datalake/notebook/sessions/current" \
    -H "Authorization: Bearer $API_TOKEN" 2>/dev/null)
EXISTING_ID=$(echo "$EXISTING" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || echo "")
if [[ -n "$EXISTING_ID" ]]; then
    curl -s -k -X DELETE "$API_URL/api/v1/datalake/notebook/sessions/$EXISTING_ID" \
        -H "Authorization: Bearer $API_TOKEN" >/dev/null 2>&1
    echo "  已删除旧 session: $EXISTING_ID"
    sleep 2
fi

# ── Step 2: 创建 session ─────────────────────────────────────
echo "[Step 2] 创建 notebook session..."
T_CREATE=$(now_sec)
RESPONSE=$(curl -s -k -X POST "$API_URL/api/v1/datalake/notebook/sessions" \
    -H "Authorization: Bearer $API_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"image\":\"$IMAGE_KEY\",\"worker_count\":$WORKER_COUNT,\"worker_size\":\"small\"}")
T_CREATED=$(now_sec)
CREATE_TIME=$(python3 -c "print(f'{$T_CREATED - $T_CREATE:.1f}s')")

STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "FAIL")
POD_NAME=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('pod_name',''))" 2>/dev/null || echo "")
SESSION_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

if [[ "$STATUS" == "RUNNING" ]]; then
    pass "Session 创建成功 ($CREATE_TIME): $SESSION_ID"
else
    fail "Session 创建失败: $RESPONSE"
    exit 1
fi

if [[ "$POD_NAME" == warm-ray-head-* ]]; then
    pass "使用热池 pod: $POD_NAME"
else
    echo "  ℹ 冷启动 pod: $POD_NAME"
fi

# Cleanup on exit
cleanup() {
    echo ""
    echo "[Cleanup] 删除 session..."
    curl -s -k -X DELETE "$API_URL/api/v1/datalake/notebook/sessions/$SESSION_ID" \
        -H "Authorization: Bearer $API_TOKEN" >/dev/null 2>&1 || true
    echo "  已清理"
}
trap cleanup EXIT

# ── Step 3: WebSocket 连接 + 执行代码 ────────────────────────
echo "[Step 3] WebSocket 连接 + 执行 Python 代码..."

# 构建 WebSocket URL
WS_URL=$(echo "$API_URL" | sed 's|^https://|wss://|; s|^http://|ws://|')
WS_ENDPOINT="$WS_URL/ws/notebook?token=$API_TOKEN"

# 创建临时文件接收输出
OUTFILE=$(mktemp /tmp/notebook-e2e-XXXXXX.txt)
trap "rm -f $OUTFILE; cleanup" EXIT

# 发送简单代码: print(1+1)
CODE='{"type":"execute","code":"print(1+1)"}'

echo "  连接 WebSocket..."
# websocat: 发送代码，等待响应，超时退出
T_WS=$(now_sec)
(echo "$CODE"; sleep "$TIMEOUT") | timeout "$TIMEOUT" websocat --insecure "$WS_ENDPOINT" > "$OUTFILE" 2>/dev/null &
WS_PID=$!

# 等待收到包含 "2" 的输出（最多 TIMEOUT 秒）
GOT_READY=false
GOT_RESULT=false
for i in $(seq 1 "$TIMEOUT"); do
    if [[ -f "$OUTFILE" ]]; then
        # 检查 ready 消息
        if ! $GOT_READY && grep -q '"type":"ready"' "$OUTFILE" 2>/dev/null; then
            T_READY=$(now_sec)
            READY_TIME=$(python3 -c "print(f'{$T_READY - $T_WS:.1f}s')")
            pass "Kernel ready ($READY_TIME)"
            GOT_READY=true
        fi
        # 检查执行结果
        if $GOT_READY && grep -q '"output"' "$OUTFILE" 2>/dev/null; then
            T_RESULT=$(now_sec)
            # 检查输出是否包含 "2"
            if grep -q '"2"' "$OUTFILE" 2>/dev/null || grep -q ': "2\\n"' "$OUTFILE" 2>/dev/null || grep -q '2' "$OUTFILE" 2>/dev/null; then
                EXEC_TIME=$(python3 -c "print(f'{$T_RESULT - $T_READY:.1f}s')")
                pass "代码执行成功 ($EXEC_TIME): print(1+1) = 2"
                GOT_RESULT=true
                break
            fi
        fi
    fi
    sleep 1
done

# 杀掉 websocat
kill $WS_PID 2>/dev/null; wait $WS_PID 2>/dev/null || true

if ! $GOT_READY; then
    fail "Kernel 未就绪 (${TIMEOUT}s 超时)"
    echo "  WebSocket 输出:"
    cat "$OUTFILE" 2>/dev/null | head -20
fi

if $GOT_READY && ! $GOT_RESULT; then
    fail "代码执行无输出"
    echo "  WebSocket 输出:"
    cat "$OUTFILE" 2>/dev/null | head -20
fi

# ── Summary ──────────────────────────────────────────────────
echo ""
echo "============================================"
echo "  Session 创建: $CREATE_TIME"
if $GOT_READY; then
    echo "  Kernel 就绪: $READY_TIME"
fi
if $GOT_RESULT; then
    echo "  代码执行: $EXEC_TIME"
fi
echo "  总耗时: $(python3 -c "print(f'{$(now_sec) - $T_CREATE:.1f}s')")"
echo ""
if [[ "$FAILURES" -eq 0 ]]; then
    echo "  ✅ All tests passed"
else
    echo "  ❌ $FAILURES test(s) FAILED"
fi
echo "============================================"

rm -f "$OUTFILE"
exit "$FAILURES"
