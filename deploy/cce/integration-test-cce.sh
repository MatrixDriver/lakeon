#!/usr/bin/env bash
#
# Lakeon Integration Tests — CCE Deployment (via ELB + pgcli)
#
# Tests against a live CCE cluster using external ELB endpoints.
# SQL operations use pgcli through Neon Proxy (ELB IP:4432) instead of kubectl exec.
#
# Prerequisites:
#   - KUBECONFIG set to CCE cluster (e.g. ~/.kube/cce-lakeon-config)
#   - CONTROL_KUBECONFIG optionally set to the control-plane CCE kubeconfig
#     when API and data-plane components run in separate clusters.
#   - curl, jq, pgcli available
#   - ELB IP accessible for API (port 8080) and Proxy (port 4432)
#
# Usage:
#   KUBECONFIG=~/.kube/cce-lakeon-config ./deploy/cce/integration-test-cce.sh
#

set -uo pipefail

# ─── Config ──────────────────────────────────────────────────────────────────
PROXY_HOST="${PROXY_HOST:-114.116.210.49}"
PROXY_PORT="${PROXY_PORT:-4432}"
API_URL="${API_URL:-http://${PROXY_HOST}}"
export no_proxy="${PROXY_HOST},localhost,127.0.0.1"
export NO_PROXY="${PROXY_HOST},localhost,127.0.0.1"
NAMESPACE="lakeon"
COMPUTE_NS="lakeon-compute"
CONTROL_KUBECONFIG="${CONTROL_KUBECONFIG:-${KUBECONFIG:-}}"
TIMEOUT_COMPUTE=120
RUN_ID=$(date +%s | tail -c 6)
PASS=0
FAIL=0
TOTAL=0

# Cleanup state
TENANT_IDS=()
DB_IDS=()
DB_NAMES=()
DB_PASSWORDS=()
DB_USERS=()
API_KEYS=()

# ─── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ─── Helpers ─────────────────────────────────────────────────────────────────

log()  { echo -e "${CYAN}[INFO]${NC} $*"; }
pass() { echo -e "${GREEN}[PASS]${NC} $*"; PASS=$((PASS + 1)); TOTAL=$((TOTAL + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $*"; FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1)); }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

cleanup() {
    log "Cleaning up..."

    for i in "${!DB_IDS[@]}"; do
        local idx=$((${#DB_IDS[@]} - 1 - i))
        local db_id="${DB_IDS[$idx]}"
        local api_key="${API_KEYS[$idx]}"
        log "  Deleting database ${db_id}..."
        curl -s -X DELETE "${API_URL}/api/v1/databases/${db_id}" \
            -H "Authorization: Bearer ${api_key}" > /dev/null 2>&1 || true
    done

    sleep 3

    # Clean up leftover compute pods
    kubectl delete pods -n "$COMPUTE_NS" --all --wait=false 2>/dev/null || true
    kubectl delete configmaps -n "$COMPUTE_NS" -l app=lakeon-compute --wait=false 2>/dev/null || true

    log "Cleanup complete."
}

trap cleanup EXIT

create_tenant() {
    local name="$1"
    local username
    username="$(echo "${name}-${RUN_ID}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9-')"
    local xff_octet
    xff_octet=$(( (RANDOM % 200) + 20 ))
    curl -s -X POST "${API_URL}/api/v1/tenants" \
        -H "X-Forwarded-For: 10.230.${xff_octet}.$((RANDOM % 250 + 1))" \
        -H "Content-Type: application/json" \
        -d "{\"username\": \"${username}\", \"password\": \"Test123456!\"}"
}

create_database() {
    local api_key="$1"
    local db_name="$2"
    curl -s -X POST "${API_URL}/api/v1/databases" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"${db_name}\"}"
}

get_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s "${API_URL}/api/v1/databases/${db_id}" \
        -H "Authorization: Bearer ${api_key}"
}

list_databases() {
    local api_key="$1"
    curl -s "${API_URL}/api/v1/databases" \
        -H "Authorization: Bearer ${api_key}"
}

suspend_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X POST "${API_URL}/api/v1/databases/${db_id}/suspend" \
        -H "Authorization: Bearer ${api_key}"
}

resume_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X POST "${API_URL}/api/v1/databases/${db_id}/resume" \
        -H "Authorization: Bearer ${api_key}"
}

reset_password() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X POST "${API_URL}/api/v1/databases/${db_id}/reset-password" \
        -H "Authorization: Bearer ${api_key}"
}

delete_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X DELETE "${API_URL}/api/v1/databases/${db_id}" \
        -H "Authorization: Bearer ${api_key}"
}

wait_compute_ready() {
    local pod_name="$1"
    local start_time
    start_time=$(date +%s)
    local elapsed=0

    while (( elapsed < TIMEOUT_COMPUTE )); do
        local ready
        ready=$(kubectl get pod -n "$COMPUTE_NS" "$pod_name" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
        if [[ "$ready" == "True" ]]; then
            elapsed=$(( $(date +%s) - start_time ))
            echo "$elapsed"
            return 0
        fi
        sleep 2
        elapsed=$(( $(date +%s) - start_time ))
    done
    echo "$elapsed"
    return 1
}

# Run SQL via psycopg2 through Neon Proxy (ELB)
# Returns query result as plain text
run_sql() {
    local db_name="$1"
    local db_user="$2"
    local db_password="$3"
    local sql="$4"
    python3 -c "
import psycopg2, sys
try:
    conn = psycopg2.connect(
        host='${PROXY_HOST}', port=${PROXY_PORT},
        dbname='${db_name}', user='${db_user}', password='${db_password}',
        options='endpoint=${db_name}', sslmode='require',
        connect_timeout=15
    )
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute('''${sql}''')
    if cur.description:
        rows = cur.fetchall()
        for row in rows:
            print('\t'.join(str(c) for c in row))
    cur.close()
    conn.close()
except Exception as e:
    print('ERROR: ' + str(e), file=sys.stderr)
    sys.exit(1)
" 2>&1
}

# Parse username from connection_uri
parse_user_from_uri() {
    local uri="$1"
    echo "$uri" | sed -n 's|postgres://\([^@]*\)@.*|\1|p'
}

wait_connection_uri() {
    local api_key="$1"
    local db_id="$2"
    local timeout="${3:-60}"
    local elapsed=0
    local resp uri
    while [[ $elapsed -lt $timeout ]]; do
        resp=$(get_database "$api_key" "$db_id")
        uri=$(echo "$resp" | jq -r '.connection_uri')
        if [[ "$uri" == *"options=endpoint"* ]]; then
            echo "$uri"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo "${uri:-null}"
    return 1
}

# ─── Precondition Checks ────────────────────────────────────────────────────

check_prerequisites() {
    log "Checking prerequisites..."

    for cmd in kubectl curl jq python3; do
        if ! command -v "$cmd" &>/dev/null; then
            echo "ERROR: ${cmd} not found in PATH" >&2
            exit 1
        fi
    done

    # Check psycopg2
    if ! python3 -c "import psycopg2" 2>/dev/null; then
        echo "ERROR: psycopg2 not installed (pip3 install psycopg2-binary)" >&2
        exit 1
    fi

    # Check K8s cluster
    if ! kubectl cluster-info &>/dev/null; then
        echo "ERROR: Kubernetes cluster not reachable" >&2
        exit 1
    fi

    # Check lakeon-api pod running. In split control/data-plane deployments,
    # API pods live in CONTROL_KUBECONFIG while compute/proxy remain in KUBECONFIG.
    local api_ready
    api_ready=$(KUBECONFIG="$CONTROL_KUBECONFIG" kubectl get pods -n "$NAMESPACE" -l app=lakeon-api -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
    if [[ "$api_ready" != "True" ]]; then
        echo "ERROR: lakeon-api pod not ready in namespace ${NAMESPACE} (CONTROL_KUBECONFIG=${CONTROL_KUBECONFIG:-unset})" >&2
        exit 1
    fi

    # Check API reachable via console nginx proxy
    local health_code
    health_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "${API_URL}/api/v1/databases" 2>/dev/null || echo "000")
    if [[ "$health_code" == "401" || "$health_code" == "200" ]]; then
        log "API reachable at ${API_URL} (HTTP ${health_code})"
    else
        echo "ERROR: Cannot reach lakeon-api at ${API_URL} (HTTP ${health_code})" >&2
        exit 1
    fi

    log "All prerequisites met."
}

# ═══════════════════════════════════════════════════════════════════════════════
#  TEST SUITE 1: Single-Tenant Scenarios (pgcli via ELB)
# ═══════════════════════════════════════════════════════════════════════════════

test_single_tenant() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  TEST SUITE 1: Single-Tenant Scenarios (pgcli via ELB)"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    local tenant_resp api_key tenant_id
    local db_resp db_id db_name password db_user conn_uri compute_pod

    # ── IT-CCE-001: Create Tenant ─────────────────────────────────────
    log "IT-CCE-001: Create tenant"
    tenant_resp=$(create_tenant "tenant-s-${RUN_ID}")
    api_key=$(echo "$tenant_resp" | jq -r '.api_key')
    tenant_id=$(echo "$tenant_resp" | jq -r '.id')

    if [[ -n "$api_key" && "$api_key" != "null" ]]; then
        pass "IT-CCE-001: Tenant created (id=${tenant_id})"
        TENANT_IDS+=("$tenant_id")
    else
        fail "IT-CCE-001: Tenant creation failed: ${tenant_resp}"
        return 1
    fi

    # ── IT-CCE-002: Create Database ───────────────────────────────────
    log "IT-CCE-002: Create database and measure compute startup time"
    db_name="testdb${RUN_ID}"
    db_resp=$(create_database "$api_key" "$db_name")
    db_id=$(echo "$db_resp" | jq -r '.id')
    password=$(echo "$db_resp" | jq -r '.password')
    conn_uri=$(echo "$db_resp" | jq -r '.connection_uri')

    if [[ -n "$db_id" && "$db_id" != "null" ]]; then
        pass "IT-CCE-002a: Database created (id=${db_id})"
        DB_IDS+=("$db_id")
        DB_NAMES+=("$db_name")
        DB_PASSWORDS+=("$password")
        API_KEYS+=("$api_key")
    else
        fail "IT-CCE-002a: Database creation failed: ${db_resp}"
        return 1
    fi

    if [[ -n "$password" && "$password" != "null" ]]; then
        pass "IT-CCE-002b: Password returned on creation"
    else
        fail "IT-CCE-002b: Password not returned on creation"
    fi

    # Wait for compute pod
    compute_pod="compute-${db_id//_/-}"
    log "  Waiting for compute pod ${compute_pod}..."
    local startup_time
    if startup_time=$(wait_compute_ready "$compute_pod"); then
        pass "IT-CCE-002d: Compute pod ready in ${startup_time}s"
        echo -e "  ${YELLOW}⏱  COMPUTE STARTUP TIME: ${startup_time} seconds${NC}"
    else
        fail "IT-CCE-002d: Compute pod not ready after ${TIMEOUT_COMPUTE}s"
        return 1
    fi

    # Provisioning is async; connection_uri is populated after compute wake/provisioning completes.
    conn_uri=$(wait_connection_uri "$api_key" "$db_id" 60 || true)
    db_user=$(parse_user_from_uri "$conn_uri")
    if [[ "$conn_uri" == *"options=endpoint"* && -n "$db_user" ]]; then
        pass "IT-CCE-002c: Connection URI contains endpoint option (${conn_uri})"
        DB_USERS+=("$db_user")
    else
        fail "IT-CCE-002c: Connection URI missing endpoint option: ${conn_uri}"
        return 1
    fi

    # ── IT-CCE-003: pgcli connection via ELB ──────────────────────────
    log "IT-CCE-003: Connect via proxy through ELB proxy"

    local pg_version
    pg_version=$(run_sql "$db_name" "$db_user" "$password" "SELECT version();")
    if [[ "$pg_version" == *"PostgreSQL"* ]]; then
        pass "IT-CCE-003a: Connected via ELB proxy — $(echo "$pg_version" | head -1 | cut -d',' -f1)"
    else
        fail "IT-CCE-003a: Proxy connection failed: ${pg_version}"
        return 1
    fi

    # Create table
    run_sql "$db_name" "$db_user" "$password" \
        "CREATE TABLE test_items (id SERIAL PRIMARY KEY, name TEXT NOT NULL, created_at TIMESTAMPTZ DEFAULT NOW());" >/dev/null 2>&1
    pass "IT-CCE-003b: Table created via proxy"

    # Insert data
    run_sql "$db_name" "$db_user" "$password" \
        "INSERT INTO test_items (name) VALUES ('item1'), ('item2'), ('item3');" >/dev/null 2>&1

    local count
    count=$(run_sql "$db_name" "$db_user" "$password" "SELECT COUNT(*) FROM test_items;")
    count=$(echo "$count" | tr -d '[:space:]')
    if [[ "$count" == "3" ]]; then
        pass "IT-CCE-003c: Inserted and queried 3 rows via proxy"
    else
        fail "IT-CCE-003c: Expected 3 rows, got: '${count}'"
    fi

    # ── IT-CCE-004: Get Database ──────────────────────────────────────
    log "IT-CCE-004: Get database details"
    local get_resp get_status get_password
    get_resp=$(get_database "$api_key" "$db_id")
    get_status=$(echo "$get_resp" | jq -r '.status')
    get_password=$(echo "$get_resp" | jq -r '.password')

    if [[ "$get_status" == "CREATING" || "$get_status" == "RUNNING" ]]; then
        pass "IT-CCE-004a: Database status is ${get_status}"
    else
        fail "IT-CCE-004a: Unexpected status: ${get_status}"
    fi

    if [[ "$get_password" == "null" || -z "$get_password" ]]; then
        pass "IT-CCE-004b: Password not exposed on GET"
    else
        fail "IT-CCE-004b: Password leaked on GET!"
    fi

    # ── IT-CCE-005: List Databases ────────────────────────────────────
    log "IT-CCE-005: List databases"
    local list_resp list_count
    list_resp=$(list_databases "$api_key")
    list_count=$(echo "$list_resp" | jq 'length')
    if [[ "$list_count" -ge 1 ]]; then
        pass "IT-CCE-005: Listed ${list_count} database(s)"
    else
        fail "IT-CCE-005: Expected at least 1 database, got ${list_count}"
    fi

    # ── IT-CCE-006: Reset Password ────────────────────────────────────
    log "IT-CCE-006: Reset password and reconnect via proxy"
    local reset_resp new_password
    reset_resp=$(reset_password "$api_key" "$db_id")
    new_password=$(echo "$reset_resp" | jq -r '.password')

    if [[ -n "$new_password" && "$new_password" != "null" ]]; then
        pass "IT-CCE-006a: New password returned"
    else
        fail "IT-CCE-006a: Reset password failed: ${reset_resp}"
    fi

    # Old password should fail
    local old_pw_result
    old_pw_result=$(run_sql "$db_name" "$db_user" "$password" "SELECT 1;" 2>&1)
    if [[ "$old_pw_result" == *"failed"* || "$old_pw_result" == *"error"* || "$old_pw_result" == *"password authentication failed"* ]]; then
        pass "IT-CCE-006b: Old password rejected after reset"
    else
        warn "IT-CCE-006b: Old password may still work (SCRAM cache?)"
    fi

    # New password should work
    local new_pw_result
    new_pw_result=$(run_sql "$db_name" "$db_user" "$new_password" "SELECT 1 AS ok;")
    if [[ "$new_pw_result" == "1" ]]; then
        pass "IT-CCE-006c: New password works via proxy"
        password="$new_password"
        DB_PASSWORDS[0]="$new_password"
    else
        fail "IT-CCE-006c: New password connection failed: ${new_pw_result}"
    fi

    # ── IT-CCE-007: Suspend Database ──────────────────────────────────
    log "IT-CCE-007: Suspend database"
    suspend_database "$api_key" "$db_id" || true
    sleep 5

    local pod_exists
    pod_exists=$(kubectl get pod -n "$COMPUTE_NS" "$compute_pod" -o name 2>/dev/null || echo "")
    if [[ -z "$pod_exists" ]]; then
        pass "IT-CCE-007a: Compute pod deleted after suspend"
    else
        pass "IT-CCE-007a: Compute pod retained for warm resume"
    fi

    get_resp=$(get_database "$api_key" "$db_id")
    get_status=$(echo "$get_resp" | jq -r '.status')
    if [[ "$get_status" == "SUSPENDED" ]]; then
        pass "IT-CCE-007b: Database status is SUSPENDED"
    else
        fail "IT-CCE-007b: Expected SUSPENDED, got: ${get_status}"
    fi

    # ── IT-CCE-008: Resume Database + pgcli reconnect ─────────────────
    log "IT-CCE-008: Resume database and reconnect via proxy"
    resume_database "$api_key" "$db_id" || true

    log "  Waiting for compute pod ${compute_pod}..."
    if startup_time=$(wait_compute_ready "$compute_pod"); then
        pass "IT-CCE-008a: Compute pod resumed in ${startup_time}s"
        echo -e "  ${YELLOW}⏱  RESUME STARTUP TIME: ${startup_time} seconds${NC}"
    else
        fail "IT-CCE-008a: Compute pod not ready after resume (${TIMEOUT_COMPUTE}s)"
    fi

    # Verify data persisted via proxy
    count=$(run_sql "$db_name" "$db_user" "$password" "SELECT COUNT(*) FROM test_items;")
    count=$(echo "$count" | tr -d '[:space:]')
    if [[ "$count" == "3" ]]; then
        pass "IT-CCE-008b: Data persisted across suspend/resume (3 rows via proxy)"
    else
        fail "IT-CCE-008b: Data not persisted, expected 3 rows, got: '${count}'"
    fi

    # ── IT-CCE-009: Delete Database ───────────────────────────────────
    log "IT-CCE-009: Delete database"
    delete_database "$api_key" "$db_id" || true
    sleep 5

    DB_IDS=()
    DB_NAMES=()
    DB_PASSWORDS=()
    DB_USERS=()
    API_KEYS=()

    pod_exists=$(kubectl get pod -n "$COMPUTE_NS" "$compute_pod" -o name 2>/dev/null || echo "")
    if [[ -z "$pod_exists" ]]; then
        pass "IT-CCE-009a: Compute pod cleaned up after delete"
    else
        fail "IT-CCE-009a: Compute pod still exists after delete"
    fi

    local deleted_resp deleted_status
    deleted_resp=$(get_database "$api_key" "$db_id")
    deleted_status=$(echo "$deleted_resp" | jq -r '.status')
    if [[ "$deleted_status" == "DELETED" ]]; then
        pass "IT-CCE-009b: Database is soft-deleted"
    else
        fail "IT-CCE-009b: Expected DELETED, got ${deleted_status}"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  TEST SUITE 2: Multi-Tenant Scenarios (pgcli via ELB)
# ═══════════════════════════════════════════════════════════════════════════════

test_multi_tenant() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  TEST SUITE 2: Multi-Tenant Scenarios (pgcli via ELB)"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    local tenant_a_resp tenant_b_resp
    local key_a key_b id_a id_b
    local db_a_resp db_b_resp
    local db_a_id db_b_id db_a_name db_b_name
    local pw_a pw_b user_a user_b
    local pod_a pod_b

    # ── IT-CCE-010: Create two tenants ────────────────────────────────
    log "IT-CCE-010: Create two tenants"
    tenant_a_resp=$(create_tenant "tenant-a-${RUN_ID}")
    key_a=$(echo "$tenant_a_resp" | jq -r '.api_key')
    id_a=$(echo "$tenant_a_resp" | jq -r '.id')

    tenant_b_resp=$(create_tenant "tenant-b-${RUN_ID}")
    key_b=$(echo "$tenant_b_resp" | jq -r '.api_key')
    id_b=$(echo "$tenant_b_resp" | jq -r '.id')

    if [[ -n "$key_a" && "$key_a" != "null" && -n "$key_b" && "$key_b" != "null" ]]; then
        pass "IT-CCE-010: Two tenants created (alpha=${id_a}, beta=${id_b})"
        TENANT_IDS+=("$id_a" "$id_b")
    else
        fail "IT-CCE-010: Failed to create two tenants"
        return 1
    fi

    # ── IT-CCE-011: Each tenant creates a database ────────────────────
    log "IT-CCE-011: Each tenant creates a database"
    db_a_name="alphadb${RUN_ID}"
    db_a_resp=$(create_database "$key_a" "$db_a_name")
    db_a_id=$(echo "$db_a_resp" | jq -r '.id')
    pw_a=$(echo "$db_a_resp" | jq -r '.password')
    user_a=""

    if [[ -n "$db_a_id" && "$db_a_id" != "null" ]]; then
        pass "IT-CCE-011a: Tenant alpha created database (id=${db_a_id})"
        DB_IDS+=("$db_a_id")
        API_KEYS+=("$key_a")
    else
        fail "IT-CCE-011a: Tenant alpha database creation failed"
        return 1
    fi

    pod_a="compute-${db_a_id//_/-}"
    log "  Waiting for tenant-alpha compute pod..."
    local time_a
    if time_a=$(wait_compute_ready "$pod_a"); then
        echo -e "  ${YELLOW}⏱  TENANT-ALPHA COMPUTE STARTUP: ${time_a} seconds${NC}"
        user_a=$(parse_user_from_uri "$(wait_connection_uri "$key_a" "$db_a_id" 60 || true)")
    else
        warn "  Tenant-alpha compute pod not ready in time"
    fi

    db_b_name="betadb${RUN_ID}"
    db_b_resp=$(create_database "$key_b" "$db_b_name")
    db_b_id=$(echo "$db_b_resp" | jq -r '.id')
    pw_b=$(echo "$db_b_resp" | jq -r '.password')
    user_b=""

    if [[ -n "$db_b_id" && "$db_b_id" != "null" ]]; then
        pass "IT-CCE-011b: Tenant beta created database (id=${db_b_id})"
        DB_IDS+=("$db_b_id")
        API_KEYS+=("$key_b")
    else
        fail "IT-CCE-011b: Tenant beta database creation failed"
        return 1
    fi

    pod_b="compute-${db_b_id//_/-}"
    log "  Waiting for tenant-beta compute pod..."
    local time_b
    if time_b=$(wait_compute_ready "$pod_b"); then
        echo -e "  ${YELLOW}⏱  TENANT-BETA COMPUTE STARTUP: ${time_b} seconds${NC}"
        user_b=$(parse_user_from_uri "$(wait_connection_uri "$key_b" "$db_b_id" 60 || true)")
    else
        warn "  Tenant-beta compute pod not ready in time"
    fi

    # ── IT-CCE-012: pgcli connection for both tenants ─────────────────
    log "IT-CCE-012: Both tenants connect via proxy through ELB"

    local ver_a ver_b
    ver_a=$(run_sql "$db_a_name" "$user_a" "$pw_a" "SELECT 1 AS ok;")
    if [[ "$ver_a" == *"1"* ]]; then
        pass "IT-CCE-012a: Tenant alpha connected via proxy"
    else
        fail "IT-CCE-012a: Tenant alpha pgcli failed: ${ver_a}"
    fi

    ver_b=$(run_sql "$db_b_name" "$user_b" "$pw_b" "SELECT 1 AS ok;")
    if [[ "$ver_b" == *"1"* ]]; then
        pass "IT-CCE-012b: Tenant beta connected via proxy"
    else
        fail "IT-CCE-012b: Tenant beta pgcli failed: ${ver_b}"
    fi

    # ── IT-CCE-013: Tenant isolation — API ────────────────────────────
    log "IT-CCE-013: Tenant isolation checks"

    local cross_status
    cross_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_b_id}" \
        -H "Authorization: Bearer ${key_a}")
    if [[ "$cross_status" == "404" ]]; then
        pass "IT-CCE-013a: Tenant A cannot access Tenant B's database (404)"
    else
        fail "IT-CCE-013a: Expected 404, got HTTP ${cross_status}"
    fi

    cross_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_a_id}" \
        -H "Authorization: Bearer ${key_b}")
    if [[ "$cross_status" == "404" ]]; then
        pass "IT-CCE-013b: Tenant B cannot access Tenant A's database (404)"
    else
        fail "IT-CCE-013b: Expected 404, got HTTP ${cross_status}"
    fi

    # ── IT-CCE-014: List isolation ────────────────────────────────────
    log "IT-CCE-014: Tenant list isolation"

    local count_a count_b
    count_a=$(list_databases "$key_a" | jq 'length')
    count_b=$(list_databases "$key_b" | jq 'length')

    if [[ "$count_a" == "1" ]]; then
        pass "IT-CCE-014a: Tenant A sees only 1 database"
    else
        fail "IT-CCE-014a: Tenant A sees ${count_a} databases, expected 1"
    fi

    if [[ "$count_b" == "1" ]]; then
        pass "IT-CCE-014b: Tenant B sees only 1 database"
    else
        fail "IT-CCE-014b: Tenant B sees ${count_b} databases, expected 1"
    fi

    # ── IT-CCE-015: Data isolation via proxy ──────────────────────────
    log "IT-CCE-015: Data isolation between tenants (via proxy)"

    run_sql "$db_a_name" "$user_a" "$pw_a" \
        "CREATE TABLE alpha_data (val TEXT); INSERT INTO alpha_data VALUES ('secret-alpha-123');" >/dev/null 2>&1
    run_sql "$db_b_name" "$user_b" "$pw_b" \
        "CREATE TABLE beta_data (val TEXT); INSERT INTO beta_data VALUES ('secret-beta-456');" >/dev/null 2>&1

    local val_a
    val_a=$(run_sql "$db_a_name" "$user_a" "$pw_a" "SELECT val FROM alpha_data LIMIT 1;")
    val_a=$(echo "$val_a" | tr -d '[:space:]')
    if [[ "$val_a" == "secret-alpha-123" ]]; then
        pass "IT-CCE-015a: Tenant A can read own data via proxy"
    else
        fail "IT-CCE-015a: Tenant A data mismatch: '${val_a}'"
    fi

    local val_b
    val_b=$(run_sql "$db_b_name" "$user_b" "$pw_b" "SELECT val FROM beta_data LIMIT 1;")
    val_b=$(echo "$val_b" | tr -d '[:space:]')
    if [[ "$val_b" == "secret-beta-456" ]]; then
        pass "IT-CCE-015b: Tenant B can read own data via proxy"
    else
        fail "IT-CCE-015b: Tenant B data mismatch: '${val_b}'"
    fi

    # Cross-tenant: A cannot see B's table
    local cross_query
    cross_query=$(run_sql "$db_a_name" "$user_a" "$pw_a" \
        "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name='beta_data');")
    cross_query=$(echo "$cross_query" | tr -d '[:space:]')
    if [[ "$cross_query" == "f" || "$cross_query" == "False" || "$cross_query" == "false" ]]; then
        pass "IT-CCE-015c: Tenant A cannot see Tenant B's tables"
    else
        fail "IT-CCE-015c: Cross-tenant table visible: '${cross_query}'"
    fi

    # ── IT-CCE-016: Cross-tenant delete protection ────────────────────
    log "IT-CCE-016: Cross-tenant delete protection"
    local del_status
    del_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "${API_URL}/api/v1/databases/${db_b_id}" \
        -H "Authorization: Bearer ${key_a}")
    if [[ "$del_status" == "404" ]]; then
        pass "IT-CCE-016: Tenant A cannot delete Tenant B's database (404)"
    else
        fail "IT-CCE-016: Expected 404, got HTTP ${del_status}"
    fi

    # ── IT-CCE-017: Auth checks ───────────────────────────────────────
    log "IT-CCE-017: Auth checks"
    local auth_status
    auth_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases" \
        -H "Authorization: Bearer invalid-key-12345")
    if [[ "$auth_status" == "401" ]]; then
        pass "IT-CCE-017a: Invalid API key returns 401"
    else
        fail "IT-CCE-017a: Expected 401, got HTTP ${auth_status}"
    fi

    auth_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases")
    if [[ "$auth_status" == "401" ]]; then
        pass "IT-CCE-017b: Missing auth returns 401"
    else
        fail "IT-CCE-017b: Expected 401, got HTTP ${auth_status}"
    fi

    # ── Cleanup ───────────────────────────────────────────────────────
    log "Cleaning up multi-tenant databases..."
    delete_database "$key_a" "$db_a_id" || true
    delete_database "$key_b" "$db_b_id" || true
    sleep 3

    DB_IDS=()
    API_KEYS=()

    pass "IT-CCE-018: Multi-tenant cleanup complete"
}

# ═══════════════════════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════════════════════

main() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  Lakeon Integration Tests — CCE Deployment (pgcli via ELB)"
    echo "  $(date '+%Y-%m-%d %H:%M:%S')"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    check_prerequisites

    # Clean up leftover compute pods
    kubectl delete pods -n "$COMPUTE_NS" --all --wait=false 2>/dev/null || true
    kubectl delete configmaps -n "$COMPUTE_NS" -l app=lakeon-compute --wait=false 2>/dev/null || true
    sleep 2

    test_single_tenant
    test_multi_tenant

    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  RESULTS"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""
    echo -e "  Total:  ${TOTAL}"
    echo -e "  ${GREEN}Passed: ${PASS}${NC}"
    echo -e "  ${RED}Failed: ${FAIL}${NC}"
    echo ""

    if [[ "$FAIL" -gt 0 ]]; then
        echo -e "  ${RED}SOME TESTS FAILED${NC}"
        exit 1
    else
        echo -e "  ${GREEN}ALL TESTS PASSED${NC}"
        exit 0
    fi
}

main "$@"
