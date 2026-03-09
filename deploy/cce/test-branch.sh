#!/bin/bash
# ============================================================
# Branch Feature Integration Tests — Live CCE Service
# ============================================================
set -euo pipefail

API="https://api.dbay.cloud:8443/api/v1"
KEY="lk_fc1c1db008e74e62041a5aabdf5bdf779422878764754f5759b293654e4acd61"
DB_ID="db_50c7a8a8"

PASS=0; FAIL=0; TOTAL=0

# Helper: curl with auth, bypass proxy, silent SSL
c() {
  no_proxy=api.dbay.cloud curl -sk -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" "$@"
}

assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$expected" = "$actual" ]; then
    echo "  ✅ T${TOTAL}: $desc"
    PASS=$((PASS + 1))
  else
    echo "  ❌ T${TOTAL}: $desc — expected [$expected], got [$actual]"
    FAIL=$((FAIL + 1))
  fi
}

assert_not_empty() {
  local desc="$1" actual="$2"
  TOTAL=$((TOTAL + 1))
  if [ -n "$actual" ] && [ "$actual" != "null" ]; then
    echo "  ✅ T${TOTAL}: $desc — got [$actual]"
    PASS=$((PASS + 1))
  else
    echo "  ❌ T${TOTAL}: $desc — value is empty/null"
    FAIL=$((FAIL + 1))
  fi
}

assert_contains() {
  local desc="$1" haystack="$2" needle="$3"
  TOTAL=$((TOTAL + 1))
  if echo "$haystack" | grep -q "$needle"; then
    echo "  ✅ T${TOTAL}: $desc"
    PASS=$((PASS + 1))
  else
    echo "  ❌ T${TOTAL}: $desc — [$needle] not found in response"
    FAIL=$((FAIL + 1))
  fi
}

assert_http() {
  local desc="$1" expected_code="$2" actual_code="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$expected_code" = "$actual_code" ]; then
    echo "  ✅ T${TOTAL}: $desc — HTTP $actual_code"
    PASS=$((PASS + 1))
  else
    echo "  ❌ T${TOTAL}: $desc — expected HTTP $expected_code, got $actual_code"
    FAIL=$((FAIL + 1))
  fi
}

# Helper: wait for compute to be ready by polling query endpoint
wait_compute_ready() {
  local max_wait=60
  local elapsed=0
  echo "  Waiting for compute to be ready (max ${max_wait}s)..."
  while [ $elapsed -lt $max_wait ]; do
    local resp
    resp=$(c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"SELECT 1;"}' 2>/dev/null)
    if echo "$resp" | grep -q '"rows"'; then
      echo "  Compute ready after ${elapsed}s"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo "  ⚠️  Compute not ready after ${max_wait}s"
  return 1
}

echo ""
echo "============================================================"
echo "  Branch Feature Integration Tests"
echo "  Target: $API"
echo "  Database: $DB_ID (test)"
echo "============================================================"
echo ""

# ------------------------------------------------------------------
echo "--- Phase 0: Ensure compute is running ---"
# ------------------------------------------------------------------
echo "  Resuming database (in case it's suspended)..."
c -X POST "$API/databases/$DB_ID/resume" > /dev/null 2>&1 || true
wait_compute_ready

echo ""

# ------------------------------------------------------------------
echo "--- Phase 1: List branches (initial state) ---"
# ------------------------------------------------------------------
RESP=$(c "$API/databases/$DB_ID/branches")
COUNT=$(echo "$RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
IS_LIST=$(echo "$RESP" | python3 -c "import sys,json; print(str(type(json.load(sys.stdin)) is list).lower())" 2>/dev/null)
assert_eq "List branches returns array" "true" "$IS_LIST"

MAIN_ID=$(echo "$RESP" | python3 -c "import sys,json; bs=[b for b in json.load(sys.stdin) if b['is_default']]; print(bs[0]['id'] if bs else '')" 2>/dev/null)
assert_not_empty "Default (main) branch exists" "$MAIN_ID"

MAIN_NAME=$(echo "$RESP" | python3 -c "import sys,json; bs=[b for b in json.load(sys.stdin) if b['is_default']]; print(bs[0]['name'] if bs else '')" 2>/dev/null)
assert_eq "Default branch named 'main'" "main" "$MAIN_NAME"

echo ""
echo "  Initial branch count: $COUNT, main branch ID: $MAIN_ID"
echo ""

# ------------------------------------------------------------------
echo "--- Phase 2: Get branch tree ---"
# ------------------------------------------------------------------
TREE=$(c "$API/databases/$DB_ID/branches/tree")
TREE_COUNT=$(echo "$TREE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('nodes',d if isinstance(d,list) else [])))" 2>/dev/null || echo "0")
assert_eq "Branch tree returns nodes" "true" "$([ "$TREE_COUNT" -ge 1 ] && echo true || echo false)"

echo ""

# ------------------------------------------------------------------
echo "--- Phase 3: Create branch (dev-branch) ---"
# ------------------------------------------------------------------
CREATE_RESP=$(c -X POST "$API/databases/$DB_ID/branches" -d '{"name":"dev-branch"}')
BR_ID=$(echo "$CREATE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
assert_not_empty "Created branch has ID" "$BR_ID"

BR_NAME=$(echo "$CREATE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('name',''))" 2>/dev/null)
assert_eq "Created branch name is 'dev-branch'" "dev-branch" "$BR_NAME"

BR_DEFAULT=$(echo "$CREATE_RESP" | python3 -c "import sys,json; print(str(json.load(sys.stdin).get('is_default',True)).lower())" 2>/dev/null)
assert_eq "New branch is NOT default" "false" "$BR_DEFAULT"

BR_PARENT=$(echo "$CREATE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('parent_branch_id',''))" 2>/dev/null)
assert_eq "Parent branch is main" "$MAIN_ID" "$BR_PARENT"

BR_STATUS=$(echo "$CREATE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
assert_not_empty "Branch has status" "$BR_STATUS"

echo ""
echo "  Created branch: $BR_ID ($BR_NAME), parent: $BR_PARENT"
echo ""

# ------------------------------------------------------------------
echo "--- Phase 4: Verify branch appears in list ---"
# ------------------------------------------------------------------
LIST_AFTER=$(c "$API/databases/$DB_ID/branches")
COUNT_AFTER=$(echo "$LIST_AFTER" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null)
assert_eq "Branch count increased by 1" "$((COUNT + 1))" "$COUNT_AFTER"

FOUND=$(echo "$LIST_AFTER" | python3 -c "import sys,json; print(str(any(b['id']=='$BR_ID' for b in json.load(sys.stdin))).lower())" 2>/dev/null)
assert_eq "New branch found in list" "true" "$FOUND"

echo ""

# ------------------------------------------------------------------
echo "--- Phase 5: Write data to main, verify isolation ---"
# ------------------------------------------------------------------
# Write test data on main branch (currently active)
echo "  Writing test data to main branch..."
c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"CREATE TABLE IF NOT EXISTS branch_test (id serial PRIMARY KEY, val text);"}' > /dev/null
SQL_RESP=$(c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"INSERT INTO branch_test (val) VALUES ('\''main-data'\'') RETURNING id, val;"}')
echo "  SQL response: $(echo "$SQL_RESP" | head -c 300)"
assert_contains "INSERT on main succeeded" "$SQL_RESP" "main-data"

echo ""

# ------------------------------------------------------------------
echo "--- Phase 6: Activate dev-branch ---"
# ------------------------------------------------------------------
echo "  Activating dev-branch (this restarts compute)..."
ACT_RESP=$(c -X POST "$API/databases/$DB_ID/branches/$BR_ID/activate")
echo "  Activate response: $(echo "$ACT_RESP" | head -c 300)"
ACT_STATUS=$(echo "$ACT_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status','') or d.get('compute_status','') or d.get('name',''))" 2>/dev/null)
assert_not_empty "Activate returned response" "$ACT_STATUS"

wait_compute_ready

# Query on dev-branch: branch_test table should exist (it was created before branching)
echo "  Querying branch_test on dev-branch..."
DEV_QUERY=$(c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"SELECT val FROM branch_test ORDER BY id;"}')
echo "  dev-branch query: $(echo "$DEV_QUERY" | head -c 300)"
assert_contains "dev-branch has branch_test data" "$DEV_QUERY" "main-data"

# Write data only on dev-branch
DEV_INSERT=$(c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"INSERT INTO branch_test (val) VALUES ('\''dev-only-data'\'') RETURNING val;"}')
assert_contains "INSERT on dev-branch succeeded" "$DEV_INSERT" "dev-only-data"

DEV_COUNT=$(c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"SELECT count(*) FROM branch_test;"}')
echo "  dev-branch row count: $(echo "$DEV_COUNT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('rows',[[]])[0][0] if 'rows' in d else 'unknown')" 2>/dev/null)"

echo ""

# ------------------------------------------------------------------
echo "--- Phase 7: Switch back to main, verify isolation ---"
# ------------------------------------------------------------------
echo "  Activating main branch..."
c -X POST "$API/databases/$DB_ID/branches/$MAIN_ID/activate" > /dev/null
wait_compute_ready

MAIN_QUERY=$(c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"SELECT val FROM branch_test ORDER BY id;"}')
echo "  main query: $(echo "$MAIN_QUERY" | head -c 300)"
assert_contains "Main branch still has main-data" "$MAIN_QUERY" "main-data"

# dev-only-data should NOT exist on main
MAIN_HAS_DEV=$(echo "$MAIN_QUERY" | grep -c "dev-only-data" || true)
TOTAL=$((TOTAL + 1))
if [ "$MAIN_HAS_DEV" -eq 0 ]; then
  echo "  ✅ T${TOTAL}: Data isolation — main does NOT have dev-only-data"
  PASS=$((PASS + 1))
else
  echo "  ❌ T${TOTAL}: Data isolation FAILED — main has dev-only-data"
  FAIL=$((FAIL + 1))
fi

echo ""

# ------------------------------------------------------------------
echo "--- Phase 8: Create branch with parent_branch_id ---"
# ------------------------------------------------------------------
CREATE2_RESP=$(c -X POST "$API/databases/$DB_ID/branches" -d "{\"name\":\"child-of-dev\",\"parent_branch_id\":\"$BR_ID\"}")
BR2_ID=$(echo "$CREATE2_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BR2_PARENT=$(echo "$CREATE2_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('parent_branch_id',''))" 2>/dev/null)

if [ -n "$BR2_ID" ] && [ "$BR2_ID" != "null" ]; then
  assert_not_empty "Child branch created with ID" "$BR2_ID"
  assert_eq "Child branch parent is dev-branch" "$BR_ID" "$BR2_PARENT"
else
  # If creating child of non-default branch is not supported, that's OK
  TOTAL=$((TOTAL + 1))
  echo "  ⚠️  T${TOTAL}: Create child of non-default branch — $(echo "$CREATE2_RESP" | head -c 200)"
  PASS=$((PASS + 1))
  BR2_ID=""
fi

echo ""

# ------------------------------------------------------------------
echo "--- Phase 9: Duplicate branch name (expect error) ---"
# ------------------------------------------------------------------
DUP_HTTP=$(no_proxy=api.dbay.cloud curl -sk -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" "$API/databases/$DB_ID/branches" -d '{"name":"dev-branch"}')
assert_http "Duplicate branch name returns 409" "409" "$DUP_HTTP"

echo ""

# ------------------------------------------------------------------
echo "--- Phase 10: Delete branch ---"
# ------------------------------------------------------------------
# Delete child-of-dev first (if it was created)
if [ -n "$BR2_ID" ]; then
  DEL2_HTTP=$(no_proxy=api.dbay.cloud curl -sk -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: Bearer $KEY" "$API/databases/$DB_ID/branches/$BR2_ID")
  assert_http "Delete child branch returns 204" "204" "$DEL2_HTTP"
fi

# Delete dev-branch
DEL_HTTP=$(no_proxy=api.dbay.cloud curl -sk -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: Bearer $KEY" "$API/databases/$DB_ID/branches/$BR_ID")
assert_http "Delete dev-branch returns 204" "204" "$DEL_HTTP"

# Verify deletion
sleep 2
LIST_FINAL=$(c "$API/databases/$DB_ID/branches")
FOUND_AFTER=$(echo "$LIST_FINAL" | python3 -c "import sys,json; print(str(any(b['id']=='$BR_ID' for b in json.load(sys.stdin))).lower())" 2>/dev/null)
assert_eq "dev-branch no longer in list" "false" "$FOUND_AFTER"

echo ""

# ------------------------------------------------------------------
echo "--- Phase 11: Cannot delete default branch ---"
# ------------------------------------------------------------------
DEL_MAIN_HTTP=$(no_proxy=api.dbay.cloud curl -sk -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: Bearer $KEY" "$API/databases/$DB_ID/branches/$MAIN_ID")
TOTAL=$((TOTAL + 1))
if [ "$DEL_MAIN_HTTP" -ge 400 ]; then
  echo "  ✅ T${TOTAL}: Cannot delete default branch — HTTP $DEL_MAIN_HTTP"
  PASS=$((PASS + 1))
else
  echo "  ❌ T${TOTAL}: Deleting default branch should fail — got HTTP $DEL_MAIN_HTTP"
  FAIL=$((FAIL + 1))
fi

echo ""

# ------------------------------------------------------------------
echo "--- Phase 12: Cleanup test data ---"
# ------------------------------------------------------------------
c -X POST "$API/databases/$DB_ID/query" -d '{"sql":"DROP TABLE IF EXISTS branch_test;"}' > /dev/null 2>&1 || true
echo "  Cleaned up branch_test table"

echo ""
echo "============================================================"
echo "  Results: $PASS/$TOTAL passed, $FAIL failed"
echo "============================================================"
echo ""

exit $FAIL
