#!/usr/bin/env bash
#
# Lakeon Console API 端到端测试
#
# 验证 Console 前端所用的全部 API 接口，通过 ELB 访问（模拟真实用户路径）。
#
# 覆盖接口：
#   租户: POST /tenants, GET /tenants/me, POST /tenants/{id}/regenerate-key
#   数据库: POST/GET/PATCH/DELETE /databases, POST suspend/resume
#   分支: GET/POST/DELETE /databases/{id}/branches
#   操作日志: GET /databases/{id}/operations, GET /operations/recent
#   认证: 401 on invalid key, 401 on missing auth
#
# 用法:
#   API_URL=http://<elb-ip> ./deploy/cce/console-api-test.sh
#
# 如未设 API_URL，自动检测 lakeon-console Service 的 ELB IP。

set -uo pipefail

NAMESPACE="lakeon"
COMPUTE_NS="lakeon-compute"
TIMEOUT_COMPUTE=120
RUN_ID=$(date +%s | tail -c 6)
PASS=0
FAIL=0
TOTAL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m'

export no_proxy="localhost,127.0.0.1"
export NO_PROXY="localhost,127.0.0.1"

# ─── Auto-detect API URL from console ELB ─────────────────────────────────────
detect_api_url() {
    if [[ -n "${API_URL:-}" ]]; then
        echo "Using provided API_URL: ${API_URL}"
        return 0
    fi

    echo "Detecting ELB IP for lakeon-console..."
    local elb_ip=""
    for i in $(seq 1 30); do
        elb_ip=$(kubectl get svc lakeon-console -n "$NAMESPACE" \
            -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
        if [[ -n "$elb_ip" ]]; then
            break
        fi
        sleep 2
    done

    if [[ -z "$elb_ip" ]]; then
        echo "ERROR: Cannot detect ELB IP. Set API_URL manually." >&2
        exit 1
    fi

    API_URL="http://${elb_ip}"
    echo "Detected: ${API_URL}"
}

# ─── Test helpers ──────────────────────────────────────────────────────────────
assert() {
    local name="$1" condition="$2"
    TOTAL=$((TOTAL + 1))
    if eval "$condition"; then
        PASS=$((PASS + 1))
        echo -e "  ${GREEN}✓${NC} ${name}"
    else
        FAIL=$((FAIL + 1))
        echo -e "  ${RED}✗${NC} ${name}"
    fi
}

api() {
    local method="$1" path="$2"
    shift 2
    curl -s -X "$method" "${API_URL}${path}" \
        -H "Content-Type: application/json" \
        --noproxy '*' \
        "$@"
}

api_auth() {
    local method="$1" path="$2" key="$3"
    shift 3
    curl -s -X "$method" "${API_URL}${path}" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${key}" \
        --noproxy '*' \
        "$@"
}

api_status() {
    local method="$1" path="$2"
    shift 2
    curl -s -o /dev/null -w "%{http_code}" -X "$method" "${API_URL}${path}" \
        -H "Content-Type: application/json" \
        --noproxy '*' \
        "$@"
}

wait_compute_ready() {
    local db_id="$1"
    local pod_name="compute-${db_id//_/-}"
    local start=$(date +%s)
    for i in $(seq 1 "$TIMEOUT_COMPUTE"); do
        local ready
        ready=$(kubectl get pod -n "$COMPUTE_NS" "$pod_name" \
            -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
        if [[ "$ready" == "True" ]]; then
            local elapsed=$(( $(date +%s) - start ))
            echo -e "  ${YELLOW}compute ready in ${elapsed}s${NC}"
            return 0
        fi
        sleep 1
    done
    echo -e "  ${RED}compute timeout (${TIMEOUT_COMPUTE}s)${NC}"
    return 1
}

# ─── Main ──────────────────────────────────────────────────────────────────────
detect_api_url

echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Lakeon Console API E2E Tests${NC}"
echo -e "${CYAN}  Target: ${API_URL}${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"

# ══════════════════════════════════════════════════════════════════════════════
# 1. 认证测试
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 1. 认证测试 ──${NC}"

STATUS=$(api_status GET "/api/v1/tenants/me")
assert "无 Auth header → 401" '[[ "$STATUS" == "401" ]]'

STATUS=$(api_status GET "/api/v1/tenants/me" -H "Authorization: Bearer invalid_key_12345")
assert "无效 API Key → 401" '[[ "$STATUS" == "401" ]]'

STATUS=$(api_status GET "/api/v1/databases" -H "Authorization: Bearer invalid_key_12345")
assert "无效 Key 访问数据库列表 → 401" '[[ "$STATUS" == "401" ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 2. 租户管理
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 2. 租户管理 ──${NC}"

TENANT_RESP=$(api POST "/api/v1/tenants" -d "{\"name\":\"console-test-${RUN_ID}\"}")
TENANT_ID=$(echo "$TENANT_RESP" | jq -r '.id')
API_KEY=$(echo "$TENANT_RESP" | jq -r '.api_key')
assert "创建租户" '[[ -n "$TENANT_ID" && "$TENANT_ID" != "null" ]]'
assert "返回 API Key" '[[ "$API_KEY" == lk_* ]]'

ME_RESP=$(api_auth GET "/api/v1/tenants/me" "$API_KEY")
ME_ID=$(echo "$ME_RESP" | jq -r '.id')
ME_NAME=$(echo "$ME_RESP" | jq -r '.name')
assert "GET /tenants/me 返回当前租户" '[[ "$ME_ID" == "$TENANT_ID" ]]'
assert "租户名称正确" '[[ "$ME_NAME" == "console-test-'${RUN_ID}'" ]]'

REGEN_RESP=$(api_auth POST "/api/v1/tenants/${TENANT_ID}/regenerate-key" "$API_KEY")
NEW_KEY=$(echo "$REGEN_RESP" | jq -r '.api_key')
assert "重新生成 API Key" '[[ -n "$NEW_KEY" && "$NEW_KEY" != "null" && "$NEW_KEY" != "$API_KEY" ]]'

# 旧 key 失效
OLD_STATUS=$(api_status GET "/api/v1/tenants/me" -H "Authorization: Bearer ${API_KEY}")
assert "旧 API Key 失效 → 401" '[[ "$OLD_STATUS" == "401" ]]'

# 用新 key 继续
API_KEY="$NEW_KEY"
ME_RESP2=$(api_auth GET "/api/v1/tenants/me" "$API_KEY")
ME_ID2=$(echo "$ME_RESP2" | jq -r '.id')
assert "新 API Key 有效" '[[ "$ME_ID2" == "$TENANT_ID" ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 3. 数据库 CRUD
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 3. 数据库 CRUD ──${NC}"

# 创建数据库
DB_RESP=$(api_auth POST "/api/v1/databases" "$API_KEY" -d '{"name":"consoledb"}')
DB_ID=$(echo "$DB_RESP" | jq -r '.id')
DB_STATUS=$(echo "$DB_RESP" | jq -r '.status')
DB_PASSWORD=$(echo "$DB_RESP" | jq -r '.password')
DB_CONN=$(echo "$DB_RESP" | jq -r '.connection_uri')
assert "创建数据库" '[[ -n "$DB_ID" && "$DB_ID" != "null" ]]'
assert "状态为 RUNNING" '[[ "$DB_STATUS" == "RUNNING" ]]'
assert "返回密码" '[[ -n "$DB_PASSWORD" && "$DB_PASSWORD" != "null" ]]'
assert "返回连接串" '[[ "$DB_CONN" == postgres://* ]]'

# 等待 compute 就绪
wait_compute_ready "$DB_ID"

# 获取单个数据库
GET_RESP=$(api_auth GET "/api/v1/databases/${DB_ID}" "$API_KEY")
GET_NAME=$(echo "$GET_RESP" | jq -r '.name')
GET_STATUS=$(echo "$GET_RESP" | jq -r '.status')
assert "GET 数据库详情" '[[ "$GET_NAME" == "consoledb" ]]'
assert "GET 不返回密码" '[[ "$(echo "$GET_RESP" | jq -r '.password')" == "null" ]]'

# 列出数据库
LIST_RESP=$(api_auth GET "/api/v1/databases" "$API_KEY")
LIST_COUNT=$(echo "$LIST_RESP" | jq 'length')
assert "数据库列表包含 1 个" '[[ "$LIST_COUNT" -ge 1 ]]'

# 更新数据库
UPD_RESP=$(api_auth PATCH "/api/v1/databases/${DB_ID}" "$API_KEY" \
    -d '{"compute_size":"2cu","suspend_timeout":"10m","storage_limit_gb":20}')
UPD_SIZE=$(echo "$UPD_RESP" | jq -r '.compute_size')
UPD_TIMEOUT=$(echo "$UPD_RESP" | jq -r '.suspend_timeout')
UPD_LIMIT=$(echo "$UPD_RESP" | jq -r '.storage_limit_gb')
assert "更新 compute_size" '[[ "$UPD_SIZE" == "2cu" ]]'
assert "更新 suspend_timeout" '[[ "$UPD_TIMEOUT" == "10m" ]]'
assert "更新 storage_limit_gb" '[[ "$UPD_LIMIT" == "20" ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 4. 数据库挂起 / 唤醒
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 4. 挂起 / 唤醒 ──${NC}"

# 挂起
api_auth POST "/api/v1/databases/${DB_ID}/suspend" "$API_KEY" > /dev/null
sleep 2
SUSP_RESP=$(api_auth GET "/api/v1/databases/${DB_ID}" "$API_KEY")
SUSP_STATUS=$(echo "$SUSP_RESP" | jq -r '.status')
assert "挂起后状态 SUSPENDED" '[[ "$SUSP_STATUS" == "SUSPENDED" ]]'

# 确认 compute pod 被删除
POD_NAME="compute-${DB_ID//_/-}"
sleep 3
POD_EXISTS=$(kubectl get pod -n "$COMPUTE_NS" "$POD_NAME" 2>&1 || true)
assert "compute Pod 已删除" 'echo "$POD_EXISTS" | grep -qi "not found"'

# 唤醒
api_auth POST "/api/v1/databases/${DB_ID}/resume" "$API_KEY" > /dev/null
RESUME_RESP=$(api_auth GET "/api/v1/databases/${DB_ID}" "$API_KEY")
RESUME_STATUS=$(echo "$RESUME_RESP" | jq -r '.status')
assert "唤醒后状态 RUNNING" '[[ "$RESUME_STATUS" == "RUNNING" ]]'

wait_compute_ready "$DB_ID"

# 幂等性：重复唤醒不报错
RESUME_STATUS2=$(api_status POST "/api/v1/databases/${DB_ID}/resume" -H "Authorization: Bearer ${API_KEY}")
assert "重复唤醒 → 200 (幂等)" '[[ "$RESUME_STATUS2" == "200" ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 5. 分支管理
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 5. 分支管理 ──${NC}"

# 列出分支（默认有 main）
BR_LIST=$(api_auth GET "/api/v1/databases/${DB_ID}/branches" "$API_KEY")
BR_COUNT=$(echo "$BR_LIST" | jq 'length')
MAIN_BR=$(echo "$BR_LIST" | jq -r '.[0].name')
MAIN_BR_ID=$(echo "$BR_LIST" | jq -r '.[0].id')
assert "默认分支列表有 1 个" '[[ "$BR_COUNT" -eq 1 ]]'
assert "默认分支名 main" '[[ "$MAIN_BR" == "main" ]]'

# 创建分支
NEW_BR_RESP=$(api_auth POST "/api/v1/databases/${DB_ID}/branches" "$API_KEY" \
    -d '{"name":"dev"}')
NEW_BR_ID=$(echo "$NEW_BR_RESP" | jq -r '.id')
NEW_BR_NAME=$(echo "$NEW_BR_RESP" | jq -r '.name')
assert "创建分支 dev" '[[ "$NEW_BR_NAME" == "dev" ]]'
assert "分支有 ID" '[[ -n "$NEW_BR_ID" && "$NEW_BR_ID" != "null" ]]'

# 列出分支（应有 2 个）
BR_LIST2=$(api_auth GET "/api/v1/databases/${DB_ID}/branches" "$API_KEY")
BR_COUNT2=$(echo "$BR_LIST2" | jq 'length')
assert "分支列表有 2 个" '[[ "$BR_COUNT2" -eq 2 ]]'

# 删除分支
DEL_BR_STATUS=$(api_status DELETE "/api/v1/databases/${DB_ID}/branches/${NEW_BR_ID}" \
    -H "Authorization: Bearer ${API_KEY}")
assert "删除分支 dev → 204 或 200" '[[ "$DEL_BR_STATUS" == "204" || "$DEL_BR_STATUS" == "200" ]]'

# 确认分支已删
BR_LIST3=$(api_auth GET "/api/v1/databases/${DB_ID}/branches" "$API_KEY")
BR_COUNT3=$(echo "$BR_LIST3" | jq 'length')
assert "分支列表回到 1 个" '[[ "$BR_COUNT3" -eq 1 ]]'

# 不能删除默认分支
DEL_MAIN_STATUS=$(api_status DELETE "/api/v1/databases/${DB_ID}/branches/${MAIN_BR_ID}" \
    -H "Authorization: Bearer ${API_KEY}")
assert "删除默认分支 → 400/403/409" '[[ "$DEL_MAIN_STATUS" =~ ^(400|403|409)$ ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 6. 操作日志
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 6. 操作日志 ──${NC}"

# 数据库操作日志（分页）
OPS_RESP=$(api_auth GET "/api/v1/databases/${DB_ID}/operations?page=0&size=10" "$API_KEY")
OPS_TOTAL=$(echo "$OPS_RESP" | jq -r '.totalElements')
OPS_CONTENT=$(echo "$OPS_RESP" | jq -r '.content | length')
assert "操作日志有记录" '[[ "$OPS_TOTAL" -gt 0 ]]'
assert "分页返回 content 数组" '[[ "$OPS_CONTENT" -gt 0 ]]'

# 检查操作类型包含 CREATE
HAS_CREATE=$(echo "$OPS_RESP" | jq '[.content[].operationType] | any(. == "CREATE")')
assert "包含 CREATE 操作" '[[ "$HAS_CREATE" == "true" ]]'

# 检查操作类型包含 SUSPEND
HAS_SUSPEND=$(echo "$OPS_RESP" | jq '[.content[].operationType] | any(. == "SUSPEND")')
assert "包含 SUSPEND 操作" '[[ "$HAS_SUSPEND" == "true" ]]'

# 检查操作类型包含 RESUME
HAS_RESUME=$(echo "$OPS_RESP" | jq '[.content[].operationType] | any(. == "RESUME")')
assert "包含 RESUME 操作" '[[ "$HAS_RESUME" == "true" ]]'

# 按类型筛选
OPS_FILT=$(api_auth GET "/api/v1/databases/${DB_ID}/operations?type=CREATE&page=0&size=10" "$API_KEY")
OPS_FILT_COUNT=$(echo "$OPS_FILT" | jq '.content | length')
assert "按 CREATE 筛选有结果" '[[ "$OPS_FILT_COUNT" -gt 0 ]]'

# 全局最近操作
RECENT_RESP=$(api_auth GET "/api/v1/operations/recent" "$API_KEY")
RECENT_COUNT=$(echo "$RECENT_RESP" | jq 'length')
assert "最近操作列表有记录" '[[ "$RECENT_COUNT" -gt 0 ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 7. SQL 连通性验证
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 7. SQL 连通性 ──${NC}"

POD_NAME="compute-${DB_ID//_/-}"
SQL_VER=$(kubectl exec -n "$COMPUTE_NS" "$POD_NAME" -- \
    psql -U cloud_admin -d postgres -p 55433 -t -A -c "SELECT version();" 2>/dev/null || echo "")
assert "psql 可连接 compute" '[[ "$SQL_VER" == *"PostgreSQL"* ]]'

# 建表 + 写入 + 查询
kubectl exec -n "$COMPUTE_NS" "$POD_NAME" -- \
    psql -U cloud_admin -d postgres -p 55433 -t -A -c "
        CREATE TABLE IF NOT EXISTS console_test (id SERIAL PRIMARY KEY, val TEXT);
        INSERT INTO console_test (val) VALUES ('hello');
    " > /dev/null 2>&1
SQL_COUNT=$(kubectl exec -n "$COMPUTE_NS" "$POD_NAME" -- \
    psql -U cloud_admin -d postgres -p 55433 -t -A -c "SELECT count(*) FROM console_test;" 2>/dev/null || echo "0")
assert "写入并查询数据" '[[ "$SQL_COUNT" -ge 1 ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 8. 多租户隔离
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 8. 多租户隔离 ──${NC}"

# 创建第二个租户
T2_RESP=$(api POST "/api/v1/tenants" -d "{\"name\":\"console-t2-${RUN_ID}\"}")
T2_KEY=$(echo "$T2_RESP" | jq -r '.api_key')

# 租户 2 看不到租户 1 的数据库
T2_LIST=$(api_auth GET "/api/v1/databases" "$T2_KEY")
T2_COUNT=$(echo "$T2_LIST" | jq 'length')
assert "租户 2 数据库列表为空" '[[ "$T2_COUNT" -eq 0 ]]'

# 租户 2 无法访问租户 1 的数据库
T2_GET_STATUS=$(api_status GET "/api/v1/databases/${DB_ID}" -H "Authorization: Bearer ${T2_KEY}")
assert "跨租户访问数据库 → 404" '[[ "$T2_GET_STATUS" == "404" ]]'

# 租户 2 无法删除租户 1 的数据库
T2_DEL_STATUS=$(api_status DELETE "/api/v1/databases/${DB_ID}" -H "Authorization: Bearer ${T2_KEY}")
assert "跨租户删除数据库 → 404" '[[ "$T2_DEL_STATUS" == "404" ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 9. 清理
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}── 9. 清理 ──${NC}"

DEL_STATUS=$(api_status DELETE "/api/v1/databases/${DB_ID}" -H "Authorization: Bearer ${API_KEY}")
assert "删除数据库 → 204 或 200" '[[ "$DEL_STATUS" == "204" || "$DEL_STATUS" == "200" ]]'

sleep 3
POD_DEL=$(kubectl get pod -n "$COMPUTE_NS" "$POD_NAME" 2>&1 || true)
assert "compute Pod 已回收" 'echo "$POD_DEL" | grep -qi "not found"'

# 删除后列表为空
FINAL_LIST=$(api_auth GET "/api/v1/databases" "$API_KEY")
FINAL_COUNT=$(echo "$FINAL_LIST" | jq 'length')
assert "数据库列表为空" '[[ "$FINAL_COUNT" -eq 0 ]]'

# ══════════════════════════════════════════════════════════════════════════════
# 结果
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
if [[ $FAIL -eq 0 ]]; then
    echo -e "${GREEN}  ALL PASSED: ${PASS}/${TOTAL}${NC}"
else
    echo -e "${RED}  FAILED: ${FAIL}/${TOTAL} (${PASS} passed)${NC}"
fi
echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
echo ""

exit $FAIL
