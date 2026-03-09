#!/bin/bash
# Lakeon 冒烟测试 — 验证核心服务是否可达
#
# 用法:
#   ./deploy/cce/smoke-test.sh          # 独立运行
#   source deploy/cce/smoke-test.sh     # 被 deploy.sh / start.sh 调用

SCRIPT_DIR="${SCRIPT_DIR:-$(cd "$(dirname "$0")" && pwd)}"
export KUBECONFIG=${KUBECONFIG:-~/.kube/cce-lakeon-config}

echo "── 冒烟测试 ──"
PASS=0
FAIL=0

check() {
  local name="$1"
  local result="$2"
  if [ "$result" = "ok" ]; then
    echo "  ✓ $name"
    PASS=$((PASS + 1))
  else
    echo "  ✗ $name — $result"
    FAIL=$((FAIL + 1))
  fi
}

# 1. API Pod Running
API_STATUS=$(kubectl get pods -n lakeon -l app=lakeon-api -o jsonpath='{.items[0].status.phase}' 2>/dev/null)
if [ "$API_STATUS" = "Running" ]; then
  check "API Pod 运行中" "ok"
else
  check "API Pod 运行中" "status=$API_STATUS"
fi

# 2. API HTTPS 可达
API_CODE=$(no_proxy=api.dbay.cloud curl -sk -o /dev/null -w "%{http_code}" --connect-timeout 10 "https://api.dbay.cloud:8443/api/v1/health" 2>/dev/null || echo "000")
if [ "$API_CODE" = "200" ] || [ "$API_CODE" = "404" ]; then
  check "API HTTPS 可达 (HTTP $API_CODE)" "ok"
else
  # fallback: try admin dashboard with token
  API_CODE=$(no_proxy=api.dbay.cloud curl -sk -o /dev/null -w "%{http_code}" --connect-timeout 10 -H "Authorization: Bearer lakeon-sre-2026" "https://api.dbay.cloud:8443/api/v1/admin/dashboard" 2>/dev/null || echo "000")
  if [ "$API_CODE" = "200" ]; then
    check "API HTTPS 可达 (dashboard HTTP $API_CODE)" "ok"
  else
    check "API HTTPS 可达" "HTTP $API_CODE"
  fi
fi

# 3. SRE Dashboard 正常返回数据
DASHBOARD=$(no_proxy=api.dbay.cloud curl -sk --connect-timeout 10 -H "Authorization: Bearer lakeon-sre-2026" "https://api.dbay.cloud:8443/api/v1/admin/dashboard" 2>/dev/null)
if echo "$DASHBOARD" | grep -q '"tenant_count"'; then
  check "SRE Dashboard 数据正常" "ok"
else
  check "SRE Dashboard 数据正常" "返回异常"
fi

# 4. Pageserver 健康
PS_STATUS=$(kubectl exec -n lakeon deploy/pageserver -- curl -s http://localhost:9898/v1/status 2>/dev/null | grep -o '"id"' || echo "")
if [ -n "$PS_STATUS" ]; then
  check "Pageserver 健康" "ok"
else
  check "Pageserver 健康" "无响应"
fi

# 5. Proxy Pod Running
PROXY_STATUS=$(kubectl get pods -n lakeon -l app=proxy -o jsonpath='{.items[0].status.phase}' 2>/dev/null)
if [ "$PROXY_STATUS" = "Running" ]; then
  check "Proxy Pod 运行中" "ok"
else
  check "Proxy Pod 运行中" "status=$PROXY_STATUS"
fi

# 6. PG 连接（快速 TCP 探测）
NEW_EIP=$(grep 'externalHost:' "$SCRIPT_DIR/values-cce.yaml" 2>/dev/null | head -1 | sed 's/.*"\(.*\)".*/\1/')
if [ -n "$NEW_EIP" ]; then
  PG_CONN=$(no_proxy=$NEW_EIP curl -sk --connect-timeout 5 "telnet://$NEW_EIP:4432" 2>&1 || nc -z -w5 "$NEW_EIP" 4432 2>&1 && echo "ok" || echo "fail")
  if echo "$PG_CONN" | grep -q "ok"; then
    check "PG 端口可达 ($NEW_EIP:4432)" "ok"
  else
    # nc might not be available, just check proxy pod is up
    check "PG 端口 ($NEW_EIP:4432)" "Proxy 已运行，端口未验证"
  fi
fi

# Summary
echo ""
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 冒烟测试全部通过 ($PASS/$TOTAL)"
else
  echo "⚠️  冒烟测试: $PASS 通过, $FAIL 失败 (共 $TOTAL)"
fi
