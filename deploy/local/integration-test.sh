#!/usr/bin/env bash
#
# Lakeon Integration Tests — Local Deployment Mode
#
# Tests single-tenant and multi-tenant scenarios against a live local K8s cluster.
# Records compute-node startup time for each database creation.
#
# Prerequisites:
#   - Docker Desktop K8s running with Lakeon deployed (helm install)
#   - kubectl, curl, jq available
#   - Port 8080 free (used for port-forward to lakeon-api)
#
# Usage:
#   ./deploy/local/integration-test.sh
#

set -uo pipefail

# ─── Config ──────────────────────────────────────────────────────────────────
API_PORT=18080
API_URL="http://localhost:${API_PORT}"
# Bypass proxy for localhost (Docker Desktop K8s)
export no_proxy="localhost,127.0.0.1"
export NO_PROXY="localhost,127.0.0.1"
NAMESPACE="lakeon"
COMPUTE_NS="lakeon-compute"
TIMEOUT_COMPUTE=120  # seconds to wait for compute pod ready
RUN_ID=$(date +%s | tail -c 6)  # unique suffix for this test run
PASS=0
FAIL=0
TOTAL=0
PF_PID=""

# Cleanup state
TENANT_IDS=()
DB_IDS=()
API_KEYS=()

# ─── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ─── Helpers ─────────────────────────────────────────────────────────────────

log()  { echo -e "${CYAN}[INFO]${NC} $*"; }
pass() { echo -e "${GREEN}[PASS]${NC} $*"; PASS=$((PASS + 1)); TOTAL=$((TOTAL + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $*"; FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1)); }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

cleanup() {
    log "Cleaning up..."

    # Delete databases (reverse order)
    for i in "${!DB_IDS[@]}"; do
        local idx=$((${#DB_IDS[@]} - 1 - i))
        local db_id="${DB_IDS[$idx]}"
        local api_key="${API_KEYS[$idx]}"
        log "  Deleting database ${db_id}..."
        curl -s -X DELETE "${API_URL}/api/v1/databases/${db_id}" \
            -H "Authorization: Bearer ${api_key}" > /dev/null 2>&1 || true
    done

    # Wait briefly for compute pods to terminate
    sleep 3

    # Kill port-forward
    if [[ -n "$PF_PID" ]] && kill -0 "$PF_PID" 2>/dev/null; then
        kill "$PF_PID" 2>/dev/null || true
        wait "$PF_PID" 2>/dev/null || true
    fi

    # Clean up leftover compute pods
    kubectl delete pods -n "$COMPUTE_NS" --all --wait=false 2>/dev/null || true
    kubectl delete configmaps -n "$COMPUTE_NS" -l app=lakeon-compute --wait=false 2>/dev/null || true

    log "Cleanup complete."
}

trap cleanup EXIT

start_port_forward() {
    # Kill any existing port-forward on this port
    lsof -ti:${API_PORT} | xargs kill -9 2>/dev/null || true
    sleep 1

    kubectl port-forward -n "$NAMESPACE" svc/lakeon-api "${API_PORT}:8080" &
    PF_PID=$!

    # Wait for port-forward to be ready (up to 15s)
    local attempts=0
    while (( attempts < 15 )); do
        if curl -s "${API_URL}/actuator/health" 2>/dev/null | grep -q "UP"; then
            log "Port-forward established (PID=${PF_PID})"
            return 0
        fi
        sleep 1
        attempts=$((attempts + 1))
    done
    echo "ERROR: Cannot reach lakeon-api at ${API_URL} after 15s" >&2
    exit 1
}

# Create tenant, returns JSON response
create_tenant() {
    local name="$1"
    curl -s -X POST "${API_URL}/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"${name}\"}"
}

# Create database, returns JSON response
create_database() {
    local api_key="$1"
    local db_name="$2"
    curl -s -X POST "${API_URL}/api/v1/databases" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"${db_name}\"}"
}

# Get database
get_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s "${API_URL}/api/v1/databases/${db_id}" \
        -H "Authorization: Bearer ${api_key}"
}

# List databases
list_databases() {
    local api_key="$1"
    curl -s "${API_URL}/api/v1/databases" \
        -H "Authorization: Bearer ${api_key}"
}

# Suspend database
suspend_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X POST "${API_URL}/api/v1/databases/${db_id}/suspend" \
        -H "Authorization: Bearer ${api_key}"
}

# Resume database
resume_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X POST "${API_URL}/api/v1/databases/${db_id}/resume" \
        -H "Authorization: Bearer ${api_key}"
}

# Delete database
delete_database() {
    local api_key="$1"
    local db_id="$2"
    curl -s -X DELETE "${API_URL}/api/v1/databases/${db_id}" \
        -H "Authorization: Bearer ${api_key}"
}

# Wait for compute pod to become ready, returns elapsed seconds
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

# Run SQL via kubectl exec on compute pod
run_sql() {
    local pod_name="$1"
    local sql="$2"
    kubectl exec -n "$COMPUTE_NS" "$pod_name" -- \
        psql -U cloud_admin -d postgres -p 55433 -t -A -c "$sql" 2>/dev/null
}

# ─── Precondition Checks ────────────────────────────────────────────────────

check_prerequisites() {
    log "Checking prerequisites..."

    for cmd in kubectl curl jq; do
        if ! command -v "$cmd" &>/dev/null; then
            echo "ERROR: ${cmd} not found in PATH" >&2
            exit 1
        fi
    done

    # Check K8s cluster
    if ! kubectl cluster-info &>/dev/null; then
        echo "ERROR: Kubernetes cluster not reachable" >&2
        exit 1
    fi

    # Check lakeon-api pod running
    local api_ready
    api_ready=$(kubectl get pods -n "$NAMESPACE" -l app=lakeon-api -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
    if [[ "$api_ready" != "True" ]]; then
        echo "ERROR: lakeon-api pod not ready in namespace ${NAMESPACE}" >&2
        exit 1
    fi

    # Check pageserver running
    local ps_ready
    ps_ready=$(kubectl get pods -n "$NAMESPACE" -l app=pageserver -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
    if [[ "$ps_ready" != "True" ]]; then
        echo "ERROR: pageserver pod not ready" >&2
        exit 1
    fi

    log "All prerequisites met."
}

# ═══════════════════════════════════════════════════════════════════════════════
#  TEST SUITE 1: Single-Tenant Scenarios
# ═══════════════════════════════════════════════════════════════════════════════

test_single_tenant() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  TEST SUITE 1: Single-Tenant Scenarios"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    local tenant_resp api_key tenant_id
    local db_resp db_id db_name password compute_pod

    # ── IT-E2E-001: Create Tenant ────────────────────────────────────────
    log "IT-E2E-001: Create tenant"
    tenant_resp=$(create_tenant "tenant-s-${RUN_ID}")
    api_key=$(echo "$tenant_resp" | jq -r '.api_key')
    tenant_id=$(echo "$tenant_resp" | jq -r '.id')

    if [[ -n "$api_key" && "$api_key" != "null" ]]; then
        pass "IT-E2E-001: Tenant created (id=${tenant_id})"
        TENANT_IDS+=("$tenant_id")
    else
        fail "IT-E2E-001: Tenant creation failed"
        return 1
    fi

    # ── IT-E2E-002: Create Database + Compute Startup Time ───────────────
    log "IT-E2E-002: Create database and measure compute startup time"
    db_name="testdb${RUN_ID}"
    local create_start
    create_start=$(date +%s)
    db_resp=$(create_database "$api_key" "$db_name")
    db_id=$(echo "$db_resp" | jq -r '.id')
    password=$(echo "$db_resp" | jq -r '.password')
    local conn_uri
    conn_uri=$(echo "$db_resp" | jq -r '.connection_uri')

    if [[ -n "$db_id" && "$db_id" != "null" ]]; then
        pass "IT-E2E-002a: Database created (id=${db_id})"
        DB_IDS+=("$db_id")
        API_KEYS+=("$api_key")
    else
        fail "IT-E2E-002a: Database creation failed"
        return 1
    fi

    # Check password returned
    if [[ -n "$password" && "$password" != "null" ]]; then
        pass "IT-E2E-002b: Password returned on creation"
    else
        fail "IT-E2E-002b: Password not returned on creation"
    fi

    # Check connection_uri format
    if [[ "$conn_uri" == postgres://* ]]; then
        pass "IT-E2E-002c: Connection URI format valid (${conn_uri})"
    else
        fail "IT-E2E-002c: Connection URI format invalid: ${conn_uri}"
    fi

    # Wait for compute pod to be ready
    compute_pod="compute-${db_id//_/-}"
    log "  Waiting for compute pod ${compute_pod} to become ready..."
    local startup_time
    if startup_time=$(wait_compute_ready "$compute_pod"); then
        pass "IT-E2E-002d: Compute pod ready in ${startup_time}s"
        echo -e "  ${YELLOW}⏱  COMPUTE STARTUP TIME: ${startup_time} seconds${NC}"
    else
        fail "IT-E2E-002d: Compute pod not ready after ${TIMEOUT_COMPUTE}s"
        return 1
    fi

    # ── IT-E2E-003: SQL Operations ───────────────────────────────────────
    log "IT-E2E-003: Run SQL operations on compute"

    # Check PG version
    local pg_version
    pg_version=$(run_sql "$compute_pod" "SELECT version();")
    if [[ "$pg_version" == *"PostgreSQL"* ]]; then
        pass "IT-E2E-003a: PostgreSQL version: $(echo "$pg_version" | head -1 | cut -d',' -f1)"
    else
        fail "IT-E2E-003a: Could not get PostgreSQL version"
    fi

    # Create table
    local create_result
    create_result=$(run_sql "$compute_pod" "CREATE TABLE test_items (id SERIAL PRIMARY KEY, name TEXT NOT NULL, created_at TIMESTAMPTZ DEFAULT NOW());")
    if [[ $? -eq 0 ]]; then
        pass "IT-E2E-003b: Table created successfully"
    else
        fail "IT-E2E-003b: Table creation failed"
    fi

    # Insert data
    run_sql "$compute_pod" "INSERT INTO test_items (name) VALUES ('item1'), ('item2'), ('item3');"
    local count
    count=$(run_sql "$compute_pod" "SELECT COUNT(*) FROM test_items;")
    if [[ "$count" == "3" ]]; then
        pass "IT-E2E-003c: Inserted and queried 3 rows"
    else
        fail "IT-E2E-003c: Expected 3 rows, got: ${count}"
    fi

    # ── IT-E2E-004: Get Database ─────────────────────────────────────────
    log "IT-E2E-004: Get database details"
    local get_resp get_status get_password
    get_resp=$(get_database "$api_key" "$db_id")
    get_status=$(echo "$get_resp" | jq -r '.status')
    get_password=$(echo "$get_resp" | jq -r '.password')

    if [[ "$get_status" == "CREATING" || "$get_status" == "RUNNING" ]]; then
        pass "IT-E2E-004a: Database status is ${get_status}"
    else
        fail "IT-E2E-004a: Unexpected status: ${get_status}"
    fi

    # Password should NOT be returned on GET
    if [[ "$get_password" == "null" || -z "$get_password" ]]; then
        pass "IT-E2E-004b: Password not exposed on GET"
    else
        fail "IT-E2E-004b: Password leaked on GET!"
    fi

    # ── IT-E2E-005: List Databases ───────────────────────────────────────
    log "IT-E2E-005: List databases"
    local list_resp list_count
    list_resp=$(list_databases "$api_key")
    list_count=$(echo "$list_resp" | jq 'length')
    if [[ "$list_count" -ge 1 ]]; then
        pass "IT-E2E-005: Listed ${list_count} database(s)"
    else
        fail "IT-E2E-005: Expected at least 1 database, got ${list_count}"
    fi

    # ── IT-E2E-006: Suspend Database ─────────────────────────────────────
    log "IT-E2E-006: Suspend database"
    suspend_database "$api_key" "$db_id" || true
    sleep 3

    # Compute pod should be deleted
    local pod_exists
    pod_exists=$(kubectl get pod -n "$COMPUTE_NS" "$compute_pod" -o name 2>/dev/null || echo "")
    if [[ -z "$pod_exists" ]]; then
        pass "IT-E2E-006a: Compute pod deleted after suspend"
    else
        fail "IT-E2E-006a: Compute pod still exists after suspend"
    fi

    # Status should be SUSPENDED
    get_resp=$(get_database "$api_key" "$db_id")
    get_status=$(echo "$get_resp" | jq -r '.status')
    if [[ "$get_status" == "SUSPENDED" ]]; then
        pass "IT-E2E-006b: Database status is SUSPENDED"
    else
        fail "IT-E2E-006b: Expected SUSPENDED, got: ${get_status}"
    fi

    # ── IT-E2E-007: Resume Database + Startup Time ───────────────────────
    log "IT-E2E-007: Resume database and measure compute startup time"
    local resume_start
    resume_start=$(date +%s)
    resume_database "$api_key" "$db_id" || true

    compute_pod="compute-${db_id//_/-}"
    log "  Waiting for compute pod ${compute_pod} to become ready..."
    if startup_time=$(wait_compute_ready "$compute_pod"); then
        pass "IT-E2E-007a: Compute pod resumed and ready in ${startup_time}s"
        echo -e "  ${YELLOW}⏱  RESUME STARTUP TIME: ${startup_time} seconds${NC}"
    else
        fail "IT-E2E-007a: Compute pod not ready after resume (${TIMEOUT_COMPUTE}s)"
    fi

    # Verify data persisted after suspend/resume
    count=$(run_sql "$compute_pod" "SELECT COUNT(*) FROM test_items;" 2>/dev/null || echo "0")
    if [[ "$count" == "3" ]]; then
        pass "IT-E2E-007b: Data persisted across suspend/resume (3 rows)"
    else
        fail "IT-E2E-007b: Data not persisted, expected 3 rows, got: ${count}"
    fi

    # ── IT-E2E-008: Delete Database ──────────────────────────────────────
    log "IT-E2E-008: Delete database"
    delete_database "$api_key" "$db_id" || true
    sleep 3

    # Remove from cleanup arrays since we already deleted
    DB_IDS=("${DB_IDS[@]/$db_id/}")
    API_KEYS=("${API_KEYS[@]/$api_key/}")

    # Pod should be gone
    pod_exists=$(kubectl get pod -n "$COMPUTE_NS" "$compute_pod" -o name 2>/dev/null || echo "")
    if [[ -z "$pod_exists" ]]; then
        pass "IT-E2E-008a: Compute pod cleaned up after delete"
    else
        fail "IT-E2E-008a: Compute pod still exists after delete"
    fi

    # DB should not be gettable
    local get_status_code
    get_status_code=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_id}" \
        -H "Authorization: Bearer ${api_key}")
    if [[ "$get_status_code" == "404" ]]; then
        pass "IT-E2E-008b: Database returns 404 after delete"
    else
        fail "IT-E2E-008b: Expected 404, got HTTP ${get_status_code}"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  TEST SUITE 2: Multi-Tenant Scenarios
# ═══════════════════════════════════════════════════════════════════════════════

test_multi_tenant() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  TEST SUITE 2: Multi-Tenant Scenarios"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    local tenant_a_resp tenant_b_resp
    local key_a key_b id_a id_b
    local db_a_resp db_b_resp
    local db_a_id db_b_id
    local pw_a pw_b
    local pod_a pod_b

    # ── IT-E2E-010: Create two tenants ───────────────────────────────────
    log "IT-E2E-010: Create two tenants"
    tenant_a_resp=$(create_tenant "tenant-a-${RUN_ID}")
    key_a=$(echo "$tenant_a_resp" | jq -r '.api_key')
    id_a=$(echo "$tenant_a_resp" | jq -r '.id')

    tenant_b_resp=$(create_tenant "tenant-b-${RUN_ID}")
    key_b=$(echo "$tenant_b_resp" | jq -r '.api_key')
    id_b=$(echo "$tenant_b_resp" | jq -r '.id')

    if [[ -n "$key_a" && "$key_a" != "null" && -n "$key_b" && "$key_b" != "null" ]]; then
        pass "IT-E2E-010: Two tenants created (alpha=${id_a}, beta=${id_b})"
        TENANT_IDS+=("$id_a" "$id_b")
    else
        fail "IT-E2E-010: Failed to create two tenants"
        return 1
    fi

    # ── IT-E2E-011: Each tenant creates a database ──────────────────────
    log "IT-E2E-011: Each tenant creates a database"
    db_a_resp=$(create_database "$key_a" "alphadb${RUN_ID}")
    db_a_id=$(echo "$db_a_resp" | jq -r '.id')
    pw_a=$(echo "$db_a_resp" | jq -r '.password')

    if [[ -n "$db_a_id" && "$db_a_id" != "null" ]]; then
        pass "IT-E2E-011a: Tenant alpha created database (id=${db_a_id})"
        DB_IDS+=("$db_a_id")
        API_KEYS+=("$key_a")
    else
        fail "IT-E2E-011a: Tenant alpha database creation failed"
        return 1
    fi

    # Measure startup time for first tenant's compute
    pod_a="compute-${db_a_id//_/-}"
    log "  Waiting for tenant-a-${RUN_ID} compute pod..."
    local time_a
    if time_a=$(wait_compute_ready "$pod_a"); then
        echo -e "  ${YELLOW}⏱  TENANT-ALPHA COMPUTE STARTUP: ${time_a} seconds${NC}"
    else
        warn "  Tenant-alpha compute pod not ready in time"
    fi

    db_b_resp=$(create_database "$key_b" "betadb${RUN_ID}")
    db_b_id=$(echo "$db_b_resp" | jq -r '.id')
    pw_b=$(echo "$db_b_resp" | jq -r '.password')

    if [[ -n "$db_b_id" && "$db_b_id" != "null" ]]; then
        pass "IT-E2E-011b: Tenant beta created database (id=${db_b_id})"
        DB_IDS+=("$db_b_id")
        API_KEYS+=("$key_b")
    else
        fail "IT-E2E-011b: Tenant beta database creation failed"
        return 1
    fi

    # Measure startup time for second tenant's compute
    pod_b="compute-${db_b_id//_/-}"
    log "  Waiting for tenant-b-${RUN_ID} compute pod..."
    local time_b
    if time_b=$(wait_compute_ready "$pod_b"); then
        echo -e "  ${YELLOW}⏱  TENANT-BETA COMPUTE STARTUP: ${time_b} seconds${NC}"
    else
        warn "  Tenant-beta compute pod not ready in time"
    fi

    # ── IT-E2E-012: Tenant isolation — cannot access other's databases ───
    log "IT-E2E-012: Tenant isolation checks"

    # Tenant A tries to get Tenant B's database
    local cross_status
    cross_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_b_id}" \
        -H "Authorization: Bearer ${key_a}")
    if [[ "$cross_status" == "404" ]]; then
        pass "IT-E2E-012a: Tenant A cannot access Tenant B's database (404)"
    else
        fail "IT-E2E-012a: Expected 404 for cross-tenant access, got HTTP ${cross_status}"
    fi

    # Tenant B tries to get Tenant A's database
    cross_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_a_id}" \
        -H "Authorization: Bearer ${key_b}")
    if [[ "$cross_status" == "404" ]]; then
        pass "IT-E2E-012b: Tenant B cannot access Tenant A's database (404)"
    else
        fail "IT-E2E-012b: Expected 404 for cross-tenant access, got HTTP ${cross_status}"
    fi

    # ── IT-E2E-013: Tenant isolation — list only shows own databases ─────
    log "IT-E2E-013: Tenant list isolation"

    local list_a list_b count_a count_b
    list_a=$(list_databases "$key_a")
    count_a=$(echo "$list_a" | jq 'length')
    list_b=$(list_databases "$key_b")
    count_b=$(echo "$list_b" | jq 'length')

    if [[ "$count_a" == "1" ]]; then
        pass "IT-E2E-013a: Tenant A sees only 1 database"
    else
        fail "IT-E2E-013a: Tenant A sees ${count_a} databases, expected 1"
    fi

    if [[ "$count_b" == "1" ]]; then
        pass "IT-E2E-013b: Tenant B sees only 1 database"
    else
        fail "IT-E2E-013b: Tenant B sees ${count_b} databases, expected 1"
    fi

    # ── IT-E2E-014: Data isolation — write to each compute pod ───────────
    log "IT-E2E-014: Data isolation between compute pods"

    # Write to tenant A
    run_sql "$pod_a" "CREATE TABLE alpha_data (val TEXT);"
    run_sql "$pod_a" "INSERT INTO alpha_data VALUES ('secret-alpha-123');"

    # Write to tenant B
    run_sql "$pod_b" "CREATE TABLE beta_data (val TEXT);"
    run_sql "$pod_b" "INSERT INTO beta_data VALUES ('secret-beta-456');"

    # Verify tenant A can read its own data
    local val_a
    val_a=$(run_sql "$pod_a" "SELECT val FROM alpha_data LIMIT 1;")
    if [[ "$val_a" == "secret-alpha-123" ]]; then
        pass "IT-E2E-014a: Tenant A can read own data"
    else
        fail "IT-E2E-014a: Tenant A data mismatch: ${val_a}"
    fi

    # Verify tenant A cannot see tenant B's table
    local cross_query
    cross_query=$(run_sql "$pod_a" "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name='beta_data');" 2>/dev/null || echo "error")
    if [[ "$cross_query" == "f" ]]; then
        pass "IT-E2E-014b: Tenant A cannot see Tenant B's tables"
    else
        fail "IT-E2E-014b: Cross-tenant table visible: ${cross_query}"
    fi

    # Verify tenant B can read its own data
    local val_b
    val_b=$(run_sql "$pod_b" "SELECT val FROM beta_data LIMIT 1;")
    if [[ "$val_b" == "secret-beta-456" ]]; then
        pass "IT-E2E-014c: Tenant B can read own data"
    else
        fail "IT-E2E-014c: Tenant B data mismatch: ${val_b}"
    fi

    # ── IT-E2E-015: Tenant isolation — cannot delete other's database ────
    log "IT-E2E-015: Cross-tenant delete protection"
    local del_status
    del_status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "${API_URL}/api/v1/databases/${db_b_id}" \
        -H "Authorization: Bearer ${key_a}")
    if [[ "$del_status" == "404" ]]; then
        pass "IT-E2E-015: Tenant A cannot delete Tenant B's database (404)"
    else
        fail "IT-E2E-015: Expected 404 for cross-tenant delete, got HTTP ${del_status}"
    fi

    # ── IT-E2E-016: Auth — invalid API key ───────────────────────────────
    log "IT-E2E-016: Auth checks"
    local auth_status
    auth_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases" \
        -H "Authorization: Bearer invalid-key-12345")
    if [[ "$auth_status" == "401" ]]; then
        pass "IT-E2E-016a: Invalid API key returns 401"
    else
        fail "IT-E2E-016a: Expected 401 for invalid key, got HTTP ${auth_status}"
    fi

    # No auth header
    auth_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "${API_URL}/api/v1/databases")
    if [[ "$auth_status" == "401" ]]; then
        pass "IT-E2E-016b: Missing auth returns 401"
    else
        fail "IT-E2E-016b: Expected 401 for missing auth, got HTTP ${auth_status}"
    fi

    # ── Cleanup: delete both databases ───────────────────────────────────
    log "Cleaning up multi-tenant databases..."
    delete_database "$key_a" "$db_a_id" || true
    delete_database "$key_b" "$db_b_id" || true
    sleep 3

    # Remove from global cleanup arrays
    DB_IDS=()
    API_KEYS=()

    pass "IT-E2E-017: Multi-tenant cleanup complete"
}

# ═══════════════════════════════════════════════════════════════════════════════
#  TEST SUITE 3: Branch & Version Scenarios
# ═══════════════════════════════════════════════════════════════════════════════

test_branch_version() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  TEST SUITE 3: Branch & Version Scenarios"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    local tenant_resp api_key tenant_id
    local db_resp db_id compute_pod

    # ── Setup: Create tenant and database ────────────────────────────────
    log "Setup: Create tenant and database for branch/version tests"
    tenant_resp=$(create_tenant "tenant-bv-${RUN_ID}")
    api_key=$(echo "$tenant_resp" | jq -r '.api_key')
    tenant_id=$(echo "$tenant_resp" | jq -r '.id')

    if [[ -z "$api_key" || "$api_key" == "null" ]]; then
        fail "Setup: Failed to create tenant for branch/version tests"
        return 1
    fi
    TENANT_IDS+=("$tenant_id")

    db_resp=$(create_database "$api_key" "bvtestdb${RUN_ID}")
    db_id=$(echo "$db_resp" | jq -r '.id')

    if [[ -z "$db_id" || "$db_id" == "null" ]]; then
        fail "Setup: Failed to create database for branch/version tests"
        return 1
    fi
    DB_IDS+=("$db_id")
    API_KEYS+=("$api_key")

    # Wait for compute pod to be ready
    compute_pod="compute-${db_id//_/-}"
    log "  Waiting for compute pod ${compute_pod} to become ready..."
    local startup_time
    if startup_time=$(wait_compute_ready "$compute_pod"); then
        log "  Compute pod ready in ${startup_time}s"
    else
        fail "Setup: Compute pod not ready after ${TIMEOUT_COMPUTE}s"
        return 1
    fi

    # ── Get default branch ID ─────────────────────────────────────────────
    log "  Fetching default branch ID..."
    local branches_resp default_branch_id
    branches_resp=$(curl -s "${API_URL}/api/v1/databases/${db_id}/branches" \
        -H "Authorization: Bearer ${api_key}")
    default_branch_id=$(echo "$branches_resp" | jq -r '.[] | select(.is_default == true) | .id')

    if [[ -z "$default_branch_id" || "$default_branch_id" == "null" ]]; then
        fail "Setup: Could not find default branch"
        return 1
    fi
    log "  Default branch ID: ${default_branch_id}"

    # ════════════════════════════════════════════════════════════════════
    #  Version CRUD Tests
    # ════════════════════════════════════════════════════════════════════

    # ── IT-E2E-020: Create first version ─────────────────────────────────
    log "IT-E2E-020: Create version on default branch"
    local ver1_resp ver1_id ver1_name ver1_status_code
    ver1_status_code=$(curl -s -o /tmp/ver1_resp.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d '{"name": "test-version-1", "description": "First version"}')
    ver1_resp=$(cat /tmp/ver1_resp.json)
    ver1_id=$(echo "$ver1_resp" | jq -r '.id')
    ver1_name=$(echo "$ver1_resp" | jq -r '.name')

    if [[ "$ver1_status_code" == "201" ]]; then
        pass "IT-E2E-020a: Version created with HTTP 201"
    else
        fail "IT-E2E-020a: Expected 201, got HTTP ${ver1_status_code}"
    fi

    if [[ -n "$ver1_id" && "$ver1_id" != "null" ]]; then
        pass "IT-E2E-020b: Version has ID (id=${ver1_id})"
    else
        fail "IT-E2E-020b: Version missing ID"
    fi

    if [[ "$ver1_name" == "test-version-1" ]]; then
        pass "IT-E2E-020c: Version name matches"
    else
        fail "IT-E2E-020c: Version name mismatch: ${ver1_name}"
    fi

    local ver1_lsn
    ver1_lsn=$(echo "$ver1_resp" | jq -r '.lsn')
    if [[ -n "$ver1_lsn" && "$ver1_lsn" != "null" ]]; then
        pass "IT-E2E-020d: Version has LSN (lsn=${ver1_lsn})"
    else
        fail "IT-E2E-020d: Version missing LSN"
    fi

    # ── IT-E2E-021: List versions ─────────────────────────────────────────
    log "IT-E2E-021: List versions on default branch"
    local list_ver_resp list_ver_count list_ver_status_code
    list_ver_status_code=$(curl -s -o /tmp/list_ver_resp.json -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}")
    list_ver_resp=$(cat /tmp/list_ver_resp.json)
    list_ver_count=$(echo "$list_ver_resp" | jq 'length')

    if [[ "$list_ver_status_code" == "200" ]]; then
        pass "IT-E2E-021a: List versions returns HTTP 200"
    else
        fail "IT-E2E-021a: Expected 200, got HTTP ${list_ver_status_code}"
    fi

    if [[ "$list_ver_count" -ge 1 ]]; then
        pass "IT-E2E-021b: List contains at least 1 version (count=${list_ver_count})"
    else
        fail "IT-E2E-021b: Expected at least 1 version, got ${list_ver_count}"
    fi

    # Verify created version is in the list
    local found_in_list
    found_in_list=$(echo "$list_ver_resp" | jq -r --arg id "$ver1_id" '.[] | select(.id == $id) | .id')
    if [[ "$found_in_list" == "$ver1_id" ]]; then
        pass "IT-E2E-021c: Created version found in list"
    else
        fail "IT-E2E-021c: Created version not found in list"
    fi

    # ── IT-E2E-022: Get version by ID ─────────────────────────────────────
    log "IT-E2E-022: Get version by ID"
    local get_ver_resp get_ver_status_code get_ver_name
    get_ver_status_code=$(curl -s -o /tmp/get_ver_resp.json -w "%{http_code}" \
        "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions/${ver1_id}" \
        -H "Authorization: Bearer ${api_key}")
    get_ver_resp=$(cat /tmp/get_ver_resp.json)
    get_ver_name=$(echo "$get_ver_resp" | jq -r '.name')

    if [[ "$get_ver_status_code" == "200" ]]; then
        pass "IT-E2E-022a: Get version returns HTTP 200"
    else
        fail "IT-E2E-022a: Expected 200, got HTTP ${get_ver_status_code}"
    fi

    if [[ "$get_ver_name" == "test-version-1" ]]; then
        pass "IT-E2E-022b: Get version name matches"
    else
        fail "IT-E2E-022b: Version name mismatch on get: ${get_ver_name}"
    fi

    # ── IT-E2E-023: Create second version ────────────────────────────────
    log "IT-E2E-023: Create second version"
    local ver2_resp ver2_id ver2_status_code
    ver2_status_code=$(curl -s -o /tmp/ver2_resp.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d '{"name": "test-version-2", "description": "Second version"}')
    ver2_resp=$(cat /tmp/ver2_resp.json)
    ver2_id=$(echo "$ver2_resp" | jq -r '.id')

    if [[ "$ver2_status_code" == "201" && -n "$ver2_id" && "$ver2_id" != "null" ]]; then
        pass "IT-E2E-023: Second version created (id=${ver2_id})"
    else
        fail "IT-E2E-023: Failed to create second version (HTTP ${ver2_status_code})"
    fi

    # ── IT-E2E-024: Delete first version ─────────────────────────────────
    log "IT-E2E-024: Delete first version"
    local del_ver_status_code
    del_ver_status_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions/${ver1_id}" \
        -H "Authorization: Bearer ${api_key}")

    if [[ "$del_ver_status_code" == "204" ]]; then
        pass "IT-E2E-024: Delete version returns HTTP 204"
    else
        fail "IT-E2E-024: Expected 204, got HTTP ${del_ver_status_code}"
    fi

    # ── IT-E2E-025: List versions after delete — only second remains ──────
    log "IT-E2E-025: List versions after delete"
    local list_after_del_resp list_after_del_count
    list_after_del_resp=$(curl -s \
        "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}")
    list_after_del_count=$(echo "$list_after_del_resp" | jq 'length')

    local ver1_still_present
    ver1_still_present=$(echo "$list_after_del_resp" | jq -r --arg id "$ver1_id" '.[] | select(.id == $id) | .id')
    local ver2_still_present
    ver2_still_present=$(echo "$list_after_del_resp" | jq -r --arg id "$ver2_id" '.[] | select(.id == $id) | .id')

    if [[ -z "$ver1_still_present" ]]; then
        pass "IT-E2E-025a: Deleted version no longer in list"
    else
        fail "IT-E2E-025a: Deleted version still appears in list"
    fi

    if [[ "$ver2_still_present" == "$ver2_id" ]]; then
        pass "IT-E2E-025b: Second version still present after first deleted"
    else
        fail "IT-E2E-025b: Second version missing after first deleted"
    fi

    # ════════════════════════════════════════════════════════════════════
    #  Promote Test
    # ════════════════════════════════════════════════════════════════════

    # ── IT-E2E-026: Create a new branch to promote ───────────────────────
    log "IT-E2E-026: Create branch for promote test"
    local new_branch_resp new_branch_id new_branch_status_code
    new_branch_status_code=$(curl -s -o /tmp/new_branch_resp.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d '{"name": "promote-test-branch"}')
    new_branch_resp=$(cat /tmp/new_branch_resp.json)
    new_branch_id=$(echo "$new_branch_resp" | jq -r '.id')

    if [[ "$new_branch_status_code" == "201" && -n "$new_branch_id" && "$new_branch_id" != "null" ]]; then
        pass "IT-E2E-026: New branch created for promote (id=${new_branch_id})"
    else
        fail "IT-E2E-026: Failed to create branch for promote (HTTP ${new_branch_status_code})"
        # Skip promote tests if branch creation failed
    fi

    # ── IT-E2E-027: Promote the new branch ───────────────────────────────
    log "IT-E2E-027: Promote branch to default"
    local promote_resp promote_status_code
    promote_status_code=$(curl -s -o /tmp/promote_resp.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches/${new_branch_id}/promote" \
        -H "Authorization: Bearer ${api_key}")

    if [[ "$promote_status_code" == "200" ]]; then
        pass "IT-E2E-027a: Promote returns HTTP 200"
    else
        fail "IT-E2E-027a: Expected 200, got HTTP ${promote_status_code}"
    fi

    # ── IT-E2E-028: Verify promotion — new branch is now default ─────────
    log "IT-E2E-028: Verify branch promotion"
    local branches_after_promote new_branch_is_default old_branch_renamed
    branches_after_promote=$(curl -s \
        "${API_URL}/api/v1/databases/${db_id}/branches" \
        -H "Authorization: Bearer ${api_key}")

    new_branch_is_default=$(echo "$branches_after_promote" | jq -r --arg id "$new_branch_id" \
        '.[] | select(.id == $id) | .is_default')
    if [[ "$new_branch_is_default" == "true" ]]; then
        pass "IT-E2E-028a: Promoted branch is now default"
    else
        fail "IT-E2E-028a: Promoted branch is not marked as default"
    fi

    # Old default branch should be renamed with "-before-promote-" suffix
    old_branch_renamed=$(echo "$branches_after_promote" | jq -r \
        '[.[] | select(.name | contains("-before-promote-"))] | length')
    if [[ "$old_branch_renamed" -ge 1 ]]; then
        pass "IT-E2E-028b: Old default branch renamed with '-before-promote-' suffix"
    else
        fail "IT-E2E-028b: Old default branch not renamed with '-before-promote-' suffix"
    fi

    # Update default_branch_id to the newly promoted branch for restore tests
    default_branch_id="$new_branch_id"

    # ════════════════════════════════════════════════════════════════════
    #  Restore Test
    # ════════════════════════════════════════════════════════════════════

    # ── IT-E2E-029: Create two versions for restore test ─────────────────
    log "IT-E2E-029: Create two versions for restore test"
    local restore_ver1_resp restore_ver1_id restore_ver1_status_code
    restore_ver1_status_code=$(curl -s -o /tmp/restore_ver1.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d '{"name": "restore-version-1", "description": "Version to restore to"}')
    restore_ver1_resp=$(cat /tmp/restore_ver1.json)
    restore_ver1_id=$(echo "$restore_ver1_resp" | jq -r '.id')

    if [[ "$restore_ver1_status_code" == "201" && -n "$restore_ver1_id" && "$restore_ver1_id" != "null" ]]; then
        pass "IT-E2E-029a: First restore version created (id=${restore_ver1_id})"
    else
        fail "IT-E2E-029a: Failed to create first restore version (HTTP ${restore_ver1_status_code})"
    fi

    local restore_ver2_resp restore_ver2_id restore_ver2_status_code
    restore_ver2_status_code=$(curl -s -o /tmp/restore_ver2.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d '{"name": "restore-version-2", "description": "Second version after first"}')
    restore_ver2_resp=$(cat /tmp/restore_ver2.json)
    restore_ver2_id=$(echo "$restore_ver2_resp" | jq -r '.id')

    if [[ "$restore_ver2_status_code" == "201" && -n "$restore_ver2_id" && "$restore_ver2_id" != "null" ]]; then
        pass "IT-E2E-029b: Second restore version created (id=${restore_ver2_id})"
    else
        fail "IT-E2E-029b: Failed to create second restore version (HTTP ${restore_ver2_status_code})"
    fi

    # ── IT-E2E-030: Restore to first version ─────────────────────────────
    log "IT-E2E-030: Restore branch to first version"
    local restore_resp restore_status_code
    restore_status_code=$(curl -s -o /tmp/restore_resp.json -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/restore" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d "{\"target_version_id\": \"${restore_ver1_id}\"}")

    if [[ "$restore_status_code" == "200" ]]; then
        pass "IT-E2E-030: Restore returns HTTP 200"
    else
        fail "IT-E2E-030: Expected 200, got HTTP ${restore_status_code}"
    fi

    # ── IT-E2E-031: Verify restore — backup branch created ───────────────
    log "IT-E2E-031: Verify restore created backup branch"
    local branches_after_restore backup_branch_count
    branches_after_restore=$(curl -s \
        "${API_URL}/api/v1/databases/${db_id}/branches" \
        -H "Authorization: Bearer ${api_key}")
    backup_branch_count=$(echo "$branches_after_restore" | jq -r \
        '[.[] | select(.name | contains("-backup-"))] | length')

    if [[ "$backup_branch_count" -ge 1 ]]; then
        pass "IT-E2E-031a: Backup branch with '-backup-' in name created after restore"
    else
        fail "IT-E2E-031a: No backup branch found after restore"
    fi

    # ── IT-E2E-032: Verify restore — first version still present ─────────
    log "IT-E2E-032: Verify first version present on default branch after restore"
    local versions_after_restore restore_ver1_still_present
    versions_after_restore=$(curl -s \
        "${API_URL}/api/v1/databases/${db_id}/branches/${default_branch_id}/versions" \
        -H "Authorization: Bearer ${api_key}")
    restore_ver1_still_present=$(echo "$versions_after_restore" | jq -r \
        --arg id "$restore_ver1_id" '.[] | select(.id == $id) | .id')

    if [[ "$restore_ver1_still_present" == "$restore_ver1_id" ]]; then
        pass "IT-E2E-032: Target version still present on default branch after restore"
    else
        fail "IT-E2E-032: Target version not found on default branch after restore"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════════════════════

main() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  Lakeon Integration Tests — Local Deployment"
    echo "  $(date '+%Y-%m-%d %H:%M:%S')"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    check_prerequisites
    start_port_forward

    # Clean up any leftover compute pods from prior runs
    kubectl delete pods -n "$COMPUTE_NS" --all --wait=false 2>/dev/null || true
    kubectl delete configmaps -n "$COMPUTE_NS" -l app=lakeon-compute --wait=false 2>/dev/null || true
    sleep 2

    test_single_tenant
    test_multi_tenant
    test_branch_version

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
