#!/usr/bin/env bash
#
# Datalake E2E Integration Tests
#
# Tests the /api/v1/datalake/jobs API against a live deployment.
#
# Usage:
#   API_URL=https://api.dbay.cloud:8443 ./deploy/cce/integration-test-datalake.sh
#   ./deploy/cce/integration-test-datalake.sh  # auto-detect via SITE config
#
# Environment:
#   API_URL   — Base URL (auto-detected from site config if not set)
#   SKIP_COMPLETION_TEST — Set to 1 to skip the job-completion test (default: 0)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ─── Config ──────────────────────────────────────────────────────────────────
NAMESPACE="lakeon"
RUN_ID=$(date +%s | tail -c 6)
PASS=0
FAIL=0
TOTAL=0
SKIP_COMPLETION_TEST="${SKIP_COMPLETION_TEST:-0}"
JOB_COMPLETION_TIMEOUT="${JOB_COMPLETION_TIMEOUT:-120}"

TENANT_IDS=()
ADMIN_TOKEN="${ADMIN_TOKEN:-lakeon-sre-2026}"

# ─── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${CYAN}[INFO]${NC} $*"; }
pass() { echo -e "${GREEN}[PASS]${NC} $*"; PASS=$((PASS + 1)); TOTAL=$((TOTAL + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $*"; FAIL=$((FAIL + 1)); TOTAL=$((TOTAL + 1)); }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
skip() { echo -e "${YELLOW}[SKIP]${NC} $*"; }

# ─── Auto-detect API_URL ─────────────────────────────────────────────────────
detect_api_url() {
    if [[ -n "${API_URL:-}" ]]; then
        log "Using API_URL: ${API_URL}"
        return 0
    fi

    # Try site config
    if [[ -f "$SCRIPT_DIR/site.sh" ]]; then
        source "$SCRIPT_DIR/site.sh" 2>/dev/null || true
    fi

    if [[ -n "${API_URL:-}" ]]; then
        log "Using API_URL from site config: ${API_URL}"
        return 0
    fi

    echo "ERROR: API_URL not set. Usage: API_URL=https://... $0" >&2
    exit 1
}

# ─── Curl helpers ─────────────────────────────────────────────────────────────

datalake_post() {
    local api_key="$1"
    local body="$2"
    curl -sk -X POST "${API_URL}/api/v1/datalake/jobs" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d "$body"
}

datalake_get() {
    local api_key="$1"
    local job_id="$2"
    curl -sk "${API_URL}/api/v1/datalake/jobs/${job_id}" \
        -H "Authorization: Bearer ${api_key}"
}

datalake_list() {
    local api_key="$1"
    local query="${2:-}"
    curl -sk "${API_URL}/api/v1/datalake/jobs${query}" \
        -H "Authorization: Bearer ${api_key}"
}

datalake_cancel() {
    local api_key="$1"
    local job_id="$2"
    curl -sk -o /dev/null -w "%{http_code}" \
        -X DELETE "${API_URL}/api/v1/datalake/jobs/${job_id}" \
        -H "Authorization: Bearer ${api_key}"
}

datalake_status_code() {
    local api_key="$1"
    local method="${2:-GET}"
    local path="$3"
    shift 3
    curl -sk -o /dev/null -w "%{http_code}" \
        -X "$method" "${API_URL}${path}" \
        -H "Authorization: Bearer ${api_key}" \
        "$@"
}

create_tenant() {
    local name="$1"
    sleep 2  # avoid rate limiting between tenant registrations
    # Generate invite code via admin API
    local code
    code=$(curl -sk -X POST "${API_URL}/api/v1/admin/invite-codes" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"note": "e2e-test"}' | jq -r '.code' 2>/dev/null)
    if [[ -z "$code" || "$code" == "null" ]]; then
        echo '{"error":"failed to get invite code"}'
        return 1
    fi
    # Register tenant
    local username
    username="dl${name//[^a-z0-9]/}$(date +%s | tail -c4)"
    curl -sk -X POST "${API_URL}/api/v1/tenants" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"${name}\", \"username\": \"${username}\", \"password\": \"Test123456!\", \"inviteCode\": \"${code}\"}"
}

# Wait for job to reach a terminal status, returns final status
wait_job_terminal() {
    local api_key="$1"
    local job_id="$2"
    local timeout="${3:-$JOB_COMPLETION_TIMEOUT}"
    local start
    start=$(date +%s)
    local elapsed=0

    while (( elapsed < timeout )); do
        local status
        status=$(datalake_get "$api_key" "$job_id" | jq -r '.status' 2>/dev/null || echo "")
        case "$status" in
            SUCCEEDED|FAILED|CANCELLED)
                echo "$status"
                return 0
                ;;
        esac
        sleep 5
        elapsed=$(( $(date +%s) - start ))
    done
    echo "TIMEOUT"
    return 1
}

# ─── Prereqs ─────────────────────────────────────────────────────────────────

check_prerequisites() {
    log "Checking prerequisites..."
    for cmd in curl jq; do
        if ! command -v "$cmd" &>/dev/null; then
            echo "ERROR: ${cmd} not found in PATH" >&2
            exit 1
        fi
    done

    local health
    health=$(curl -sk --connect-timeout 10 "${API_URL}/actuator/health" 2>/dev/null || echo "")
    if echo "$health" | grep -q "UP"; then
        log "API health OK: ${API_URL}"
    else
        echo "ERROR: Cannot reach API at ${API_URL}/actuator/health" >&2
        echo "Response: ${health}" >&2
        exit 1
    fi
    log "Prerequisites OK"
}

# ═══════════════════════════════════════════════════════════════════════════════
#  SUITE 1: Basic Job CRUD
# ═══════════════════════════════════════════════════════════════════════════════

test_basic_crud() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  SUITE 1: Basic Job CRUD"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    # Create test tenant
    local tenant_resp api_key tenant_id
    tenant_resp=$(create_tenant "dl-crud-${RUN_ID}")
    api_key=$(echo "$tenant_resp" | jq -r '.api_key')
    tenant_id=$(echo "$tenant_resp" | jq -r '.id')
    if [[ -z "$api_key" || "$api_key" == "null" ]]; then
        fail "SUITE1: Cannot create test tenant — skipping suite"
        return 1
    fi
    TENANT_IDS+=("$tenant_id")
    log "Created tenant: ${tenant_id}"

    # ── DL-E2E-001: Submit PYTHON job ─────────────────────────────────
    log "DL-E2E-001: Submit PYTHON job"
    local submit_resp job_id job_status job_type job_name
    submit_resp=$(datalake_post "$api_key" '{
        "name": "test-job-'"$RUN_ID"'",
        "type": "PYTHON",
        "entrypoint": "python -c \"import time; time.sleep(300)\"",
        "resources": {"cpu": "0.1", "memory": "128Mi"},
        "timeout_seconds": 600
    }')
    job_id=$(echo "$submit_resp" | jq -r '.id')
    job_status=$(echo "$submit_resp" | jq -r '.status')
    job_type=$(echo "$submit_resp" | jq -r '.type')
    job_name=$(echo "$submit_resp" | jq -r '.name')

    if [[ -n "$job_id" && "$job_id" != "null" ]]; then
        pass "DL-E2E-001a: Job submitted, id=${job_id}"
    else
        fail "DL-E2E-001a: Submit failed: $(echo "$submit_resp" | jq -c .)"
        return 1
    fi

    if [[ "$job_status" == "PENDING" || "$job_status" == "STARTING" || "$job_status" == "RUNNING" || "$job_status" == "FAILED" ]]; then
        pass "DL-E2E-001b: Initial status=${job_status}"
    else
        fail "DL-E2E-001b: Unexpected status: ${job_status}"
    fi

    if [[ "$job_type" == "PYTHON" ]]; then
        pass "DL-E2E-001c: Type=PYTHON"
    else
        fail "DL-E2E-001c: Unexpected type: ${job_type}"
    fi

    if [[ "$job_name" == "test-job-${RUN_ID}" ]]; then
        pass "DL-E2E-001d: Name correct"
    else
        fail "DL-E2E-001d: Name mismatch: ${job_name}"
    fi

    # ── DL-E2E-002: Get job by ID ──────────────────────────────────────
    log "DL-E2E-002: Get job by ID"
    local get_resp get_id get_type get_status
    get_resp=$(datalake_get "$api_key" "$job_id")
    get_id=$(echo "$get_resp" | jq -r '.id')
    get_type=$(echo "$get_resp" | jq -r '.type')
    get_status=$(echo "$get_resp" | jq -r '.status')

    if [[ "$get_id" == "$job_id" ]]; then
        pass "DL-E2E-002a: Get returns correct job id"
    else
        fail "DL-E2E-002a: ID mismatch: expected ${job_id}, got ${get_id}"
    fi

    if [[ "$get_type" == "PYTHON" ]]; then
        pass "DL-E2E-002b: Get returns correct type"
    else
        fail "DL-E2E-002b: Type mismatch: ${get_type}"
    fi

    # ── DL-E2E-003: List jobs ──────────────────────────────────────────
    log "DL-E2E-003: List jobs"
    local list_resp list_count
    list_resp=$(datalake_list "$api_key")
    list_count=$(echo "$list_resp" | jq 'length' 2>/dev/null || echo "0")

    if [[ "$list_count" -ge 1 ]]; then
        pass "DL-E2E-003: Listed ${list_count} job(s)"
    else
        fail "DL-E2E-003: Expected ≥1 jobs, got ${list_count}"
    fi

    # ── DL-E2E-004: List with status filter ───────────────────────────
    log "DL-E2E-004: List with status filter"
    local filter_resp filter_count
    # Filter by the current status of our job
    filter_resp=$(datalake_list "$api_key" "?status=${get_status}")
    filter_count=$(echo "$filter_resp" | jq 'length' 2>/dev/null || echo "0")
    local job_in_filter
    job_in_filter=$(echo "$filter_resp" | jq --arg id "$job_id" '[.[] | select(.id == $id)] | length')

    if [[ "$job_in_filter" -ge 1 ]]; then
        pass "DL-E2E-004a: Job appears in status=${get_status} filter"
    else
        fail "DL-E2E-004a: Job not found in status=${get_status} filter (count=${filter_count})"
    fi

    # ── DL-E2E-005: Cancel job ─────────────────────────────────────────
    log "DL-E2E-005: Cancel job"
    # Only cancel if not already in terminal state
    local current_status
    current_status=$(datalake_get "$api_key" "$job_id" | jq -r '.status')

    if [[ "$current_status" == "PENDING" || "$current_status" == "STARTING" || "$current_status" == "RUNNING" ]]; then
        local cancel_code
        cancel_code=$(datalake_cancel "$api_key" "$job_id")
        if [[ "$cancel_code" == "204" ]]; then
            pass "DL-E2E-005a: Cancel returns 204"
        else
            fail "DL-E2E-005a: Cancel returned HTTP ${cancel_code}, expected 204"
        fi

        sleep 2
        local after_cancel_status
        after_cancel_status=$(datalake_get "$api_key" "$job_id" | jq -r '.status')
        if [[ "$after_cancel_status" == "CANCELLED" ]]; then
            pass "DL-E2E-005b: Status=CANCELLED after cancel"
        else
            fail "DL-E2E-005b: Expected CANCELLED, got ${after_cancel_status}"
        fi

        # Cancel again → 400 (already terminal)
        local cancel2_code
        cancel2_code=$(datalake_cancel "$api_key" "$job_id")
        if [[ "$cancel2_code" == "400" ]]; then
            pass "DL-E2E-005c: Cancel on terminal job returns 400"
        else
            fail "DL-E2E-005c: Expected 400, got HTTP ${cancel2_code}"
        fi
    else
        warn "DL-E2E-005: Job already in terminal state (${current_status}), testing cancel on terminal job"
        local cancel_code
        cancel_code=$(datalake_cancel "$api_key" "$job_id")
        if [[ "$cancel_code" == "400" ]]; then
            pass "DL-E2E-005: Cancel on terminal job returns 400"
        else
            fail "DL-E2E-005: Expected 400, got HTTP ${cancel_code}"
        fi
    fi

    # ── DL-E2E-006: Get non-existent job → 404 ────────────────────────
    log "DL-E2E-006: Get non-existent job"
    local sc
    sc=$(datalake_status_code "$api_key" "GET" "/api/v1/datalake/jobs/non-existent-job-id-00000")
    if [[ "$sc" == "404" ]]; then
        pass "DL-E2E-006: Non-existent job returns 404"
    else
        fail "DL-E2E-006: Expected 404, got HTTP ${sc}"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  SUITE 2: Tenant Isolation
# ═══════════════════════════════════════════════════════════════════════════════

test_tenant_isolation() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  SUITE 2: Tenant Isolation"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    # Create two tenants
    local ta_resp tb_resp key_a key_b id_a id_b
    ta_resp=$(create_tenant "dl-ta-${RUN_ID}")
    key_a=$(echo "$ta_resp" | jq -r '.api_key')
    id_a=$(echo "$ta_resp" | jq -r '.id')

    tb_resp=$(create_tenant "dl-tb-${RUN_ID}")
    key_b=$(echo "$tb_resp" | jq -r '.api_key')
    id_b=$(echo "$tb_resp" | jq -r '.id')

    if [[ -z "$key_a" || "$key_a" == "null" || -z "$key_b" || "$key_b" == "null" ]]; then
        fail "SUITE2: Cannot create test tenants — skipping suite"
        return 1
    fi
    TENANT_IDS+=("$id_a" "$id_b")
    log "Created tenants: A=${id_a}, B=${id_b}"

    # Each tenant submits a job
    local job_a_resp job_b_resp job_a_id job_b_id
    job_a_resp=$(datalake_post "$key_a" '{
        "name": "job-a-'"$RUN_ID"'",
        "type": "PYTHON",
        "entrypoint": "python -c \"print(1)\""
    }')
    job_a_id=$(echo "$job_a_resp" | jq -r '.id')

    job_b_resp=$(datalake_post "$key_b" '{
        "name": "job-b-'"$RUN_ID"'",
        "type": "PYTHON",
        "entrypoint": "python -c \"print(2)\""
    }')
    job_b_id=$(echo "$job_b_resp" | jq -r '.id')

    if [[ -z "$job_a_id" || "$job_a_id" == "null" || -z "$job_b_id" || "$job_b_id" == "null" ]]; then
        fail "SUITE2: Cannot submit test jobs — skipping isolation checks"
        return 1
    fi
    log "Tenant A job: ${job_a_id}, Tenant B job: ${job_b_id}"

    # ── DL-E2E-010: Tenant A cannot get Tenant B's job ────────────────
    log "DL-E2E-010: Cross-tenant GET protection"
    local sc_a_on_b sc_b_on_a
    sc_a_on_b=$(datalake_status_code "$key_a" "GET" "/api/v1/datalake/jobs/${job_b_id}")
    if [[ "$sc_a_on_b" == "403" || "$sc_a_on_b" == "404" ]]; then
        pass "DL-E2E-010a: Tenant A cannot get Tenant B's job (HTTP ${sc_a_on_b})"
    else
        fail "DL-E2E-010a: Expected 403/404, got HTTP ${sc_a_on_b}"
    fi

    sc_b_on_a=$(datalake_status_code "$key_b" "GET" "/api/v1/datalake/jobs/${job_a_id}")
    if [[ "$sc_b_on_a" == "403" || "$sc_b_on_a" == "404" ]]; then
        pass "DL-E2E-010b: Tenant B cannot get Tenant A's job (HTTP ${sc_b_on_a})"
    else
        fail "DL-E2E-010b: Expected 403/404, got HTTP ${sc_b_on_a}"
    fi

    # ── DL-E2E-011: List isolation ─────────────────────────────────────
    log "DL-E2E-011: List isolation"
    local list_a list_b count_a count_b
    list_a=$(datalake_list "$key_a")
    count_a=$(echo "$list_a" | jq 'length')
    list_b=$(datalake_list "$key_b")
    count_b=$(echo "$list_b" | jq 'length')

    local b_in_a
    b_in_a=$(echo "$list_a" | jq --arg id "$job_b_id" '[.[] | select(.id == $id)] | length')
    if [[ "$b_in_a" == "0" ]]; then
        pass "DL-E2E-011a: Tenant A list does not contain Tenant B's job"
    else
        fail "DL-E2E-011a: Tenant A list contains Tenant B's job!"
    fi

    local a_in_b
    a_in_b=$(echo "$list_b" | jq --arg id "$job_a_id" '[.[] | select(.id == $id)] | length')
    if [[ "$a_in_b" == "0" ]]; then
        pass "DL-E2E-011b: Tenant B list does not contain Tenant A's job"
    else
        fail "DL-E2E-011b: Tenant B list contains Tenant A's job!"
    fi

    # ── DL-E2E-012: Tenant A cannot cancel Tenant B's job ─────────────
    log "DL-E2E-012: Cross-tenant cancel protection"
    local sc_cancel_a_on_b sc_cancel_b_on_a
    sc_cancel_a_on_b=$(datalake_cancel "$key_a" "$job_b_id")
    if [[ "$sc_cancel_a_on_b" == "403" || "$sc_cancel_a_on_b" == "404" ]]; then
        pass "DL-E2E-012a: Tenant A cannot cancel Tenant B's job (HTTP ${sc_cancel_a_on_b})"
    else
        fail "DL-E2E-012a: Expected 403/404, got HTTP ${sc_cancel_a_on_b}"
    fi

    sc_cancel_b_on_a=$(datalake_cancel "$key_b" "$job_a_id")
    if [[ "$sc_cancel_b_on_a" == "403" || "$sc_cancel_b_on_a" == "404" ]]; then
        pass "DL-E2E-012b: Tenant B cannot cancel Tenant A's job (HTTP ${sc_cancel_b_on_a})"
    else
        fail "DL-E2E-012b: Expected 403/404, got HTTP ${sc_cancel_b_on_a}"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  SUITE 3: Auth & Validation
# ═══════════════════════════════════════════════════════════════════════════════

test_auth_and_validation() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  SUITE 3: Auth & Validation"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    # Create tenant for validation tests
    local tenant_resp api_key tenant_id
    tenant_resp=$(create_tenant "dl-val-${RUN_ID}")
    api_key=$(echo "$tenant_resp" | jq -r '.api_key')
    tenant_id=$(echo "$tenant_resp" | jq -r '.id')
    if [[ -z "$api_key" || "$api_key" == "null" ]]; then
        fail "SUITE3: Cannot create test tenant — skipping suite"
        return 1
    fi
    TENANT_IDS+=("$tenant_id")

    # ── DL-E2E-020: No auth → 401 ─────────────────────────────────────
    log "DL-E2E-020: No auth header"
    local sc
    sc=$(curl -sk -o /dev/null -w "%{http_code}" "${API_URL}/api/v1/datalake/jobs")
    if [[ "$sc" == "401" ]]; then
        pass "DL-E2E-020: No auth returns 401"
    else
        fail "DL-E2E-020: Expected 401, got HTTP ${sc}"
    fi

    # ── DL-E2E-021: Invalid API key → 401 ────────────────────────────
    log "DL-E2E-021: Invalid API key"
    sc=$(datalake_status_code "invalid-key-xyz-12345" "GET" "/api/v1/datalake/jobs")
    if [[ "$sc" == "401" ]]; then
        pass "DL-E2E-021: Invalid key returns 401"
    else
        fail "DL-E2E-021: Expected 401, got HTTP ${sc}"
    fi

    # ── DL-E2E-022: Submit without name → 400 ─────────────────────────
    log "DL-E2E-022: Submit without name"
    sc=$(curl -sk -o /dev/null -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/datalake/jobs" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d '{"type": "PYTHON", "entrypoint": "python -c \"print(1)\""}')
    if [[ "$sc" == "400" ]]; then
        pass "DL-E2E-022: Missing name returns 400"
    else
        fail "DL-E2E-022: Expected 400, got HTTP ${sc}"
    fi

    # ── DL-E2E-023: Submit without type → 400 ─────────────────────────
    log "DL-E2E-023: Submit without type"
    sc=$(curl -sk -o /dev/null -w "%{http_code}" \
        -X POST "${API_URL}/api/v1/datalake/jobs" \
        -H "Authorization: Bearer ${api_key}" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"test-no-type-${RUN_ID}\"}")
    if [[ "$sc" == "400" ]]; then
        pass "DL-E2E-023: Missing type returns 400"
    else
        fail "DL-E2E-023: Expected 400, got HTTP ${sc}"
    fi

    # ── DL-E2E-024: Invalid status filter → 400 ───────────────────────
    log "DL-E2E-024: Invalid status filter"
    sc=$(datalake_status_code "$api_key" "GET" "/api/v1/datalake/jobs?status=BOGUS")
    if [[ "$sc" == "400" ]]; then
        pass "DL-E2E-024: Invalid status filter returns 400"
    else
        fail "DL-E2E-024: Expected 400, got HTTP ${sc}"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  SUITE 4: Job Completion (optional, requires VK/CCI infra)
# ═══════════════════════════════════════════════════════════════════════════════

test_job_completion() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  SUITE 4: Job Completion"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    if [[ "$SKIP_COMPLETION_TEST" == "1" ]]; then
        skip "SUITE4: Skipped (SKIP_COMPLETION_TEST=1)"
        return 0
    fi

    # Create tenant
    local tenant_resp api_key tenant_id
    tenant_resp=$(create_tenant "dl-comp-${RUN_ID}")
    api_key=$(echo "$tenant_resp" | jq -r '.api_key')
    tenant_id=$(echo "$tenant_resp" | jq -r '.id')
    if [[ -z "$api_key" || "$api_key" == "null" ]]; then
        fail "SUITE4: Cannot create test tenant — skipping suite"
        return 1
    fi
    TENANT_IDS+=("$tenant_id")

    # ── DL-E2E-030: Submit PYTHON job that succeeds ────────────────────
    log "DL-E2E-030: Submit PYTHON job and wait for SUCCEEDED (timeout=${JOB_COMPLETION_TIMEOUT}s)"
    local submit_resp job_id
    submit_resp=$(datalake_post "$api_key" '{
        "name": "success-job-'"$RUN_ID"'",
        "type": "PYTHON",
        "entrypoint": "python -c \"print('"'"'hello datalake'"'"')\"",
        "resources": {"cpu": "0.1", "memory": "128Mi"},
        "timeout_seconds": 60
    }')
    job_id=$(echo "$submit_resp" | jq -r '.id')

    if [[ -z "$job_id" || "$job_id" == "null" ]]; then
        fail "DL-E2E-030: Failed to submit job"
        return 1
    fi
    log "  Job submitted: ${job_id}"

    local final_status
    if final_status=$(wait_job_terminal "$api_key" "$job_id" "$JOB_COMPLETION_TIMEOUT"); then
        if [[ "$final_status" == "SUCCEEDED" ]]; then
            pass "DL-E2E-030: PYTHON job completed with SUCCEEDED"
        elif [[ "$final_status" == "FAILED" ]]; then
            local err_msg
            err_msg=$(datalake_get "$api_key" "$job_id" | jq -r '.errorMessage')
            warn "DL-E2E-030: Job FAILED (may be VK/CCI infra issue): ${err_msg}"
            fail "DL-E2E-030: Expected SUCCEEDED, got FAILED: ${err_msg}"
        else
            fail "DL-E2E-030: Unexpected terminal status: ${final_status}"
        fi
    else
        fail "DL-E2E-030: Job did not complete within ${JOB_COMPLETION_TIMEOUT}s"
        warn "  Hint: set SKIP_COMPLETION_TEST=1 if VK/CCI is not configured"
    fi

    # ── DL-E2E-031: finishedAt is set on completed job ────────────────
    log "DL-E2E-031: finishedAt set after completion"
    local finished_at
    finished_at=$(datalake_get "$api_key" "$job_id" | jq -r '.finishedAt')
    if [[ -n "$finished_at" && "$finished_at" != "null" ]]; then
        pass "DL-E2E-031: finishedAt is set: ${finished_at}"
    else
        fail "DL-E2E-031: finishedAt is null on completed job"
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════════════════════

main() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  Datalake E2E Integration Tests"
    echo "  $(date '+%Y-%m-%d %H:%M:%S')"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""

    detect_api_url
    check_prerequisites

    test_basic_crud
    test_tenant_isolation
    test_auth_and_validation
    test_job_completion

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
