#!/usr/bin/env bash
#
# Lakeon CCE 端到端演示脚本
#
# 演示完整使用流程：创建租户 → 创建数据库 → 等待计算节点 → 写入数据 → 查询 → 清理
#
# 用法:
#   KUBECONFIG=~/.kube/cce-lakeon-config ./deploy/cce/demo.sh
#

set -uo pipefail

API_PORT=18080
API_URL="http://localhost:${API_PORT}"
NAMESPACE="lakeon"
COMPUTE_NS="lakeon-compute"
export no_proxy="localhost,127.0.0.1"
export NO_PROXY="localhost,127.0.0.1"

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PF_PID=""

cleanup() {
    if [[ -n "$PF_PID" ]] && kill -0 "$PF_PID" 2>/dev/null; then
        kill "$PF_PID" 2>/dev/null; wait "$PF_PID" 2>/dev/null
    fi
}
trap cleanup EXIT

die() { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }
step() { echo -e "\n${CYAN}── $* ──${NC}"; }
ok()   { echo -e "${GREEN}✓${NC} $*"; }

run_sql() {
    kubectl exec -n "$COMPUTE_NS" "$1" -- \
        psql -U cloud_admin -d postgres -p 55433 -t -A -c "$2" 2>/dev/null
}

# ─── 启动 port-forward ────────────────────────────────────────────────────
step "连接 API (port-forward)"
lsof -ti:${API_PORT} | xargs kill -9 2>/dev/null || true
sleep 1
kubectl port-forward -n "$NAMESPACE" svc/lakeon-api "${API_PORT}:8080" &>/dev/null &
PF_PID=$!

for i in $(seq 1 15); do
    if curl -s "${API_URL}/actuator/health" 2>/dev/null | grep -q "UP"; then
        ok "API 连接成功"
        break
    fi
    [[ $i -eq 15 ]] && die "无法连接 API"
    sleep 1
done

# ─── 1. 创建租户 ──────────────────────────────────────────────────────────
step "1. 创建租户"
TENANT_RESP=$(curl -s -X POST "${API_URL}/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -d '{"name":"demo-tenant"}')

TENANT_ID=$(echo "$TENANT_RESP" | jq -r '.id')
API_KEY=$(echo "$TENANT_RESP" | jq -r '.api_key')
[[ -z "$API_KEY" || "$API_KEY" == "null" ]] && die "创建租户失败: $TENANT_RESP"
ok "租户: $TENANT_ID"
ok "API Key: ${API_KEY:0:20}..."

# ─── 2. 创建数据库 ────────────────────────────────────────────────────────
step "2. 创建数据库"
DB_RESP=$(curl -s -X POST "${API_URL}/api/v1/databases" \
    -H "Authorization: Bearer ${API_KEY}" \
    -H "Content-Type: application/json" \
    -d '{"name":"demodb"}')

DB_ID=$(echo "$DB_RESP" | jq -r '.id')
DB_PASSWORD=$(echo "$DB_RESP" | jq -r '.password')
CONN_URI=$(echo "$DB_RESP" | jq -r '.connection_uri')
[[ -z "$DB_ID" || "$DB_ID" == "null" ]] && die "创建数据库失败: $DB_RESP"
ok "数据库: $DB_ID"
ok "连接串: $CONN_URI"

# ─── 3. 等待计算节点就绪 ──────────────────────────────────────────────────
step "3. 等待计算节点就绪"
POD_NAME="compute-${DB_ID//_/-}"
START=$(date +%s)
for i in $(seq 1 120); do
    READY=$(kubectl get pod -n "$COMPUTE_NS" "$POD_NAME" \
        -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
    if [[ "$READY" == "True" ]]; then
        ELAPSED=$(( $(date +%s) - START ))
        ok "计算节点就绪 (${ELAPSED}s)"
        break
    fi
    [[ $i -eq 120 ]] && die "计算节点超时 (120s)"
    sleep 1
done

# ─── 4. 写入 10 条数据 ────────────────────────────────────────────────────
step "4. 写入 10 条数据"

run_sql "$POD_NAME" "
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);"
ok "建表: users"

run_sql "$POD_NAME" "
INSERT INTO users (name, email) VALUES
    ('张三', 'zhangsan@example.com'),
    ('李四', 'lisi@example.com'),
    ('王五', 'wangwu@example.com'),
    ('赵六', 'zhaoliu@example.com'),
    ('孙七', 'sunqi@example.com'),
    ('周八', 'zhouba@example.com'),
    ('吴九', 'wujiu@example.com'),
    ('郑十', 'zhengshi@example.com'),
    ('陈一', 'chenyi@example.com'),
    ('林二', 'liner@example.com');"
ok "插入 10 条记录"

# ─── 5. 查询数据 ──────────────────────────────────────────────────────────
step "5. 查询数据"

echo ""
echo "  SELECT * FROM users:"
echo "  ┌────┬──────┬──────────────────────────┬─────────────────────┐"
echo "  │ ID │ 姓名 │ 邮箱                     │ 创建时间            │"
echo "  ├────┼──────┼──────────────────────────┼─────────────────────┤"

run_sql "$POD_NAME" "SELECT id, name, email, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') FROM users ORDER BY id;" | \
while IFS='|' read -r id name email ts; do
    printf "  │ %2s │ %-4s │ %-24s │ %s │\n" "$id" "$name" "$email" "$ts"
done

echo "  └────┴──────┴──────────────────────────┴─────────────────────┘"

ROW_COUNT=$(run_sql "$POD_NAME" "SELECT count(*) FROM users;")
echo ""
ok "共 ${ROW_COUNT} 条记录"

# ─── 6. 聚合查询 ──────────────────────────────────────────────────────────
step "6. 聚合查询"

PG_VER=$(run_sql "$POD_NAME" "SELECT version();")
echo -e "  PG 版本: ${YELLOW}${PG_VER}${NC}"

DB_SIZE=$(run_sql "$POD_NAME" "SELECT pg_size_pretty(pg_database_size('postgres'));")
echo -e "  数据库大小: ${YELLOW}${DB_SIZE}${NC}"

TABLE_COUNT=$(run_sql "$POD_NAME" "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
echo -e "  公共表数量: ${YELLOW}${TABLE_COUNT}${NC}"

# ─── 7. 清理 ──────────────────────────────────────────────────────────────
step "7. 清理"

curl -s -X DELETE "${API_URL}/api/v1/databases/${DB_ID}" \
    -H "Authorization: Bearer ${API_KEY}" > /dev/null
ok "数据库已删除"

sleep 2
DELETED=$(kubectl get pod -n "$COMPUTE_NS" "$POD_NAME" 2>&1 || true)
if echo "$DELETED" | grep -q "NotFound\|not found"; then
    ok "计算节点已回收"
else
    echo -e "${YELLOW}  计算节点回收中...${NC}"
fi

echo ""
echo -e "${GREEN}═══ 演示完成 ═══${NC}"
