# dbay-sre-mcp 0.2.1 — REST Everywhere + Auth Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two `dbay-sre-mcp 0.2.0` bugs revealed by Plan B production rollout: (1) `LakeonAdminClient` uses wrong auth header (`Admin-Token` 而非 `Authorization: Bearer`),导致全部 7 个 admin REST 工具一直 403; (2) `data_consistency_check` + `stuck_task_query` 直连 lakeon-api 内网 PG (`LAKEON_DB_DSN`),Railway 外网根本连不上。0.2.1 把这俩工具改走 admin REST(对齐"全 REST"原则),lakeon-api 端补 2 个 admin endpoint,删 `LAKEON_DB_DSN` 依赖。

**Architecture:**
- **lakeon-api** (Java/Spring Boot 3.3.5): `AdminController` 加 2 endpoint:`GET /api/v1/admin/data-consistency/{rule}` 和 `GET /api/v1/admin/stuck-tasks`,内部 query JPA repos / `EntityManager` native SQL,返回与原 dbay-sre-mcp 0.2.0 工具相同的 JSON shape。延用现有 `ApiKeyFilter` admin token 鉴权。
- **dbay-sre-mcp** (Python/fastmcp 2.0): `LakeonAdminClient._get` 改用 `Authorization: Bearer {token}` header(修 0.2.0 bug)。`data_consistency_check` + `stuck_task_query` 工具实现替换:不再 `import lakeon_db` + 直查 PG,改 `LakeonAdminClient` 走 admin REST。删 `lakeon_db.py`、删 `LAKEON_DB_DSN` env var 依赖。
- **sre-agent**:Dockerfile bump `0.2.0 → 0.2.1`;`verify_env.py` 完全删除 `LAKEON_DB_DSN`(已经是 OPTIONAL,直接删)。

**Tech Stack:** Spring Boot 3.3.5 + Java 17 + JPA、fastmcp >= 2.0、httpx、pytest、PostgreSQL native SQL via `@Query` 或 `EntityManager`.

**Related:**
- Plan A (0.2.0): [`2026-04-24-dbay-sre-mcp-phase1-enhancement.md`](./2026-04-24-dbay-sre-mcp-phase1-enhancement.md)
- Plan B (watchers + briefings): [`2026-04-25-sre-agent-phase1-watchers-briefings.md`](./2026-04-25-sre-agent-phase1-watchers-briefings.md)

---

## Hard Constraints

1. **0.2.1 必须 backward-compatible 0.2.0** 调用签名 / 返回 JSON shape:`data_consistency_check(rule=..., threshold_minutes=...)` 仍然返回 `{ok, count, violations, ...}`;`stuck_task_query(threshold_minutes=..., type=...)` 仍然返回 `{count, tasks, warnings?}`. Watcher 代码零改动。
2. **lakeon-api endpoint 鉴权** 走现有 `ApiKeyFilter`(`Authorization: Bearer <admin_token>`)— 不要重新造鉴权。
3. **lakeon-api endpoint 必须 read-only** — SELECT only,无 DML。
4. **lakeon-api endpoint 返回 JSON 结构必须跟原 PG 直查的 0.2.0 工具一模一样** — 否则 watcher 解析炸。
5. **dbay-sre-mcp 0.2.1 删 `lakeon_db.py` + `LAKEON_DB_DSN` 依赖完全** — 工具不再有"需要 PG 直连"的代码路径。
6. **修 auth header bug 是阻塞型**(Task 1)— 不修,Plan B 5 个其他 admin REST 工具(find_database / find_tenant / database_status / pod_create_failures / multi_tenant_blast_radius)也都跑不起来。Task 1 自身可以先发 0.2.1-hotfix 让 Plan B 立刻能用,再做后面的 REST 化。
7. **`multi_tenant_blast_radius` 仍走 `LOG_DB_DSN`(dbay-logs PG)** — 不动。它查的是 dbay-logs(已经公网可达),不是 lakeon-api PG。
8. **lakeon-api 部署 in CCE,不 Railway** — Java 改动后需要 `SITE=hwstaff bash deploy/cce/build-and-push-api.sh && KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon`(memory:`feedback_deploy_hwstaff`)。

---

## File Structure (target)

```
lakeon/dbay-sre-mcp/
├── pyproject.toml                                        # MODIFY: version 0.2.0 → 0.2.1
├── CHANGELOG.md                                          # MODIFY: add 0.2.1 entry
├── README.md                                             # MODIFY: env vars table — remove LAKEON_DB_DSN
├── PUBLISH-0.2.0.md                                      # DELETE (was for 0.2.0)
├── PUBLISH-0.2.1.md                                      # NEW
├── src/dbay_sre_mcp/
│   ├── admin_client.py                                   # MODIFY: header fix + add 2 endpoint methods
│   ├── lakeon_db.py                                      # DELETE
│   ├── server.py                                         # UNCHANGED (tool registrations)
│   └── tools/
│       ├── data_consistency_check.py                     # MODIFY: REST-based (uses LakeonAdminClient)
│       └── stuck_task_query.py                           # MODIFY: REST-based
└── tests/
    ├── test_admin_client.py                              # MODIFY: assert Authorization header
    ├── test_data_consistency_check.py                    # MODIFY: mock admin client, not PG
    └── test_stuck_task_query.py                          # MODIFY: mock admin client, not PG

lakeon/lakeon-api/
└── src/main/java/com/lakeon/
    ├── controller/AdminController.java                   # MODIFY: +2 endpoints
    ├── service/admin/                                    # NEW package
    │   ├── DataConsistencyCheckService.java              # NEW (4 invariant rules + dispatcher)
    │   └── StuckTaskQueryService.java                    # NEW (3-table union query)
    └── repo/                                             # NEW custom queries (or use EntityManager native SQL inline)

lakeon/lakeon-api/src/test/java/com/lakeon/
├── controller/AdminControllerDataConsistencyTest.java    # NEW (@WebMvcTest slice)
└── service/admin/
    ├── DataConsistencyCheckServiceTest.java              # NEW
    └── StuckTaskQueryServiceTest.java                    # NEW

lakeon/sre-agent/
├── Dockerfile                                            # MODIFY: ARG DBAY_SRE_MCP_VERSION=0.2.1
├── scripts/verify_env.py                                 # MODIFY: drop LAKEON_DB_DSN from OPTIONAL
└── hermes_config/config.yaml                             # MODIFY: drop LAKEON_DB_DSN from mcp_servers.dbay_sre.env
```

### Module responsibilities

| Module | Responsibility |
|---|---|
| `LakeonAdminClient._get` | Use `Authorization: Bearer <token>` header (was `Admin-Token` — bug fix) |
| `LakeonAdminClient.data_consistency_check(rule, threshold_minutes)` | NEW method: GET `/admin/data-consistency/{rule}` |
| `LakeonAdminClient.stuck_task_query(threshold_minutes, type)` | NEW method: GET `/admin/stuck-tasks` |
| `tools/data_consistency_check.py` | Calls `LakeonAdminClient.data_consistency_check`. NO PG. |
| `tools/stuck_task_query.py` | Calls `LakeonAdminClient.stuck_task_query`. NO PG. |
| Java `DataConsistencyCheckService` | Run invariant rule SQL on lakeon DB; return `Map<String,Object>` matching legacy 0.2.0 JSON. 4 rules hard-coded. |
| Java `StuckTaskQueryService` | Union 3 task tables, filter by threshold + optional type, graceful UndefinedTable handling. |
| Java `AdminController` | Thin REST wrappers: GET → service → JSON. |

---

## Work Breakdown — 8 Tasks

| Group | Tasks | What it produces |
|---|---|---|
| A. Hot-fix auth | 1 | LakeonAdminClient header fix; **release 0.2.1-hotfix** so Plan B 5 工具立刻能用 |
| B. lakeon-api endpoints (Java) | 2-4 | 2 new admin endpoints + 2 service classes + integration tests; deploy to CCE |
| C. dbay-sre-mcp REST 化 | 5-6 | 2 tools rewritten to use REST instead of PG |
| D. Cleanup + ship | 7-8 | Delete lakeon_db.py + LAKEON_DB_DSN refs; release 0.2.1; bump sre-agent + deploy |

---

## Group A: Hot-fix auth header (release 0.2.1 immediately)

### Task 1: Fix `LakeonAdminClient` auth header + release 0.2.1

**Files:**
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py`
- Modify: `lakeon/dbay-sre-mcp/tests/test_admin_client.py`
- Modify: `lakeon/dbay-sre-mcp/pyproject.toml` (version 0.2.0 → 0.2.1)
- Modify: `lakeon/dbay-sre-mcp/CHANGELOG.md`

**Why first:** This single-line fix unblocks all 5 Plan B watchers. Even before the REST-ification (Tasks 5-6), shipping just this fix lets `find_database`, `find_tenant`, `database_status`, `pod_create_failures`, `multi_tenant_blast_radius` actually work in production.

- [ ] **Step 1.1: Fix the test first (verify expected header)**

In `lakeon/dbay-sre-mcp/tests/test_admin_client.py`, find `test_admin_token_in_header` and update:

```python
def test_admin_token_in_header(monkeypatch):
    captured: dict = {}

    class TrackHttp(_FakeHttp):
        def get(self, url, headers=None, params=None, timeout=None):
            captured["headers"] = headers
            return super().get(url, headers, params, timeout)

    fake = TrackHttp({
        ("GET", "https://x/api/v1/admin/databases/d1"): {"id": "d1"},
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://x/api/v1", token="my-secret-token")
    c.get_database(db_id="d1")
    assert captured["headers"]["Authorization"] == "Bearer my-secret-token"
    assert "Admin-Token" not in captured["headers"]
```

- [ ] **Step 1.2: Run test — fails**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
PYTHONPATH=src python3 -m pytest tests/test_admin_client.py::test_admin_token_in_header -v
```
Expected: FAIL with `KeyError: 'Authorization'` (header is currently `Admin-Token`).

- [ ] **Step 1.3: Fix the implementation**

In `/Users/jacky/code/lakeon/dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py`, find the `_get` method (around line 36):

```python
    def _get(self, path: str, params: dict | None = None) -> dict | None:
        url = f"{self._base_url}{path}"
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.get(
                url,
                headers={"Admin-Token": self._token},
                params=params,
                timeout=self._timeout,
            )
```

Change `headers={"Admin-Token": self._token}` to `headers={"Authorization": f"Bearer {self._token}"}`:

```python
    def _get(self, path: str, params: dict | None = None) -> dict | None:
        url = f"{self._base_url}{path}"
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.get(
                url,
                headers={"Authorization": f"Bearer {self._token}"},
                params=params,
                timeout=self._timeout,
            )
```

- [ ] **Step 1.4: Run all admin_client tests — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
PYTHONPATH=src python3 -m pytest tests/test_admin_client.py -v
```
Expected: 5 passed (including the updated `test_admin_token_in_header`).

- [ ] **Step 1.5: Bump version + CHANGELOG**

`pyproject.toml`:
```toml
version = "0.2.1"
```

`CHANGELOG.md` — prepend new section above `## 0.2.0`:

```markdown
## 0.2.1 (2026-04-25) — hotfix + REST cleanup

### Fixed (CRITICAL)

- `LakeonAdminClient` was sending `Admin-Token: <token>` header but lakeon-api
  expects `Authorization: Bearer <token>` (per `ApiKeyFilter.java:111`). All
  7 admin-REST-based tools (`find_database`, `find_tenant`, `database_status`,
  `data_consistency_check`, `stuck_task_query`, `pod_create_failures`,
  `multi_tenant_blast_radius` indirectly) returned 403 in production until
  this fix. Header is now `Authorization: Bearer ...`.

### Changed

- `data_consistency_check` and `stuck_task_query` now go through lakeon-api
  admin REST endpoints (`/admin/data-consistency/{rule}` and `/admin/stuck-tasks`)
  instead of direct PG (`LAKEON_DB_DSN`). Reasons:
  - lakeon-api PG is on CCE internal network (192.168.x.x), unreachable from
    Railway-hosted sre-agent.
  - Aligns with the "all admin tools go via REST" principle established for
    the other 5 new tools.
  - Removes `LAKEON_DB_DSN` env requirement entirely.

### Removed

- `dbay_sre_mcp/lakeon_db.py` (no longer needed).
- `LAKEON_DB_DSN` env var support — `data_consistency_check` and
  `stuck_task_query` no longer connect to PG directly.
```

- [ ] **Step 1.6: Build wheel + verify**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
rm -rf dist/dbay_sre_mcp-0.2.1*
python3 -m build --wheel
ls dist/dbay_sre_mcp-0.2.1-*.whl
```
Expected: wheel built.

- [ ] **Step 1.7: Commit Task 1 (do NOT publish to PyPI yet — Tasks 5-6 will modify the same package)**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py \
        dbay-sre-mcp/tests/test_admin_client.py \
        dbay-sre-mcp/pyproject.toml \
        dbay-sre-mcp/CHANGELOG.md
git commit -m "fix(sre-mcp): use Authorization Bearer header (was Admin-Token, caused 403)"
```

---

## Group B: lakeon-api admin endpoints (Java)

### Task 2: `DataConsistencyCheckService` — Java service for 4 invariant rules

**Why:** Move the 4 rule SQLs from `dbay-sre-mcp/tools/data_consistency_check.py` into a Java service that lakeon-api owns. Same SQL, same return JSON shape, just lives in lakeon-api now.

**Files:**
- Create: `lakeon/lakeon-api/src/main/java/com/lakeon/service/admin/DataConsistencyCheckService.java`
- Create: `lakeon/lakeon-api/src/test/java/com/lakeon/service/admin/DataConsistencyCheckServiceTest.java`

- [ ] **Step 2.1: Write failing test**

```java
// lakeon/lakeon-api/src/test/java/com/lakeon/service/admin/DataConsistencyCheckServiceTest.java
package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DataConsistencyCheckServiceTest {

    private EntityManager em;
    private Query query;
    private DataConsistencyCheckService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        service = new DataConsistencyCheckService(em);
    }

    @Test
    void listsAvailableRules() {
        Map<String, Object> result = service.run("__list__", 10);
        assertThat(result.get("rules")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> rules = (List<String>) result.get("rules");
        assertThat(rules).contains("kb_implies_db_id", "enqueued_implies_drained",
                "db_ready_implies_pod_running", "schema_seeded");
    }

    @Test
    void unknownRuleReturnsErrorPayload() {
        Map<String, Object> result = service.run("bogus_rule", 10);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("message").toString()).contains("unknown");
    }

    @Test
    void okWhenNoViolations() {
        when(query.getResultList()).thenReturn(List.of());
        Map<String, Object> result = service.run("kb_implies_db_id", 10);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).isEmpty();
    }

    @Test
    void countAndShapeWhenViolationsPresent() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"kb_a", "demo", "t_xyz", null},
                new Object[]{"kb_b", "test", "t_xyz", null}
        ));
        Map<String, Object> result = service.run("kb_implies_db_id", 10);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).get("kb_id")).isEqualTo("kb_a");
        assertThat(violations.get(0).get("name")).isEqualTo("demo");
    }

    @Test
    void enqueuedImpliesDrainedUsesThresholdParam() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"write_42", "kb_abc", java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 600}
        ));
        Map<String, Object> result = service.run("enqueued_implies_drained", 5);
        verify(query).setParameter("threshold_minutes", 5);
        assertThat(result.get("count")).isEqualTo(1);
    }
}
```

- [ ] **Step 2.2: Run — fails (class doesn't exist)**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test --tests DataConsistencyCheckServiceTest 2>&1 | tail -10
```
Expected: compile error (no `DataConsistencyCheckService` class).

- [ ] **Step 2.3: Implement service**

```java
// lakeon/lakeon-api/src/main/java/com/lakeon/service/admin/DataConsistencyCheckService.java
package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read-only invariant checks against lakeon-api production DB.
 * Mirrors the 4 rules originally implemented in dbay-sre-mcp 0.2.0
 * Python tool. Returned shape is identical to the Python tool so
 * downstream consumers (sre-agent watchers) need no changes.
 */
@Service
public class DataConsistencyCheckService {

    private final EntityManager em;

    public DataConsistencyCheckService(EntityManager em) {
        this.em = em;
    }

    private record RuleSpec(String description, String sql,
                            List<String> columns, List<String> params) {}

    private static final Map<String, RuleSpec> RULES = Map.of(
            "kb_implies_db_id", new RuleSpec(
                    "Knowledge bases marked READY but with NULL db_id (event timing bug)",
                    """
                    SELECT id, name, tenant_id, db_id
                    FROM knowledge_base
                    WHERE status = 'READY' AND db_id IS NULL
                    """,
                    List.of("kb_id", "name", "tenant_id", "db_id"),
                    List.of()),
            "enqueued_implies_drained", new RuleSpec(
                    "Writes enqueued but not drained beyond threshold (tx commit ordering bug)",
                    """
                    SELECT id, kb_id, enqueued_at,
                           EXTRACT(EPOCH FROM (NOW() - enqueued_at))::int AS age_sec
                    FROM kb_write_queue
                    WHERE drained_at IS NULL
                      AND enqueued_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY enqueued_at ASC
                    """,
                    List.of("write_id", "kb_id", "enqueued_at", "age_sec"),
                    List.of("threshold_minutes")),
            "db_ready_implies_pod_running", new RuleSpec(
                    "Databases marked READY but compute_host is unknown / pod missing",
                    """
                    SELECT id, name, tenant_id, status, compute_host
                    FROM database
                    WHERE status = 'READY' AND (compute_host IS NULL OR compute_host = '')
                    """,
                    List.of("db_id", "name", "tenant_id", "status", "compute_host"),
                    List.of()),
            "schema_seeded", new RuleSpec(
                    "Wiki-enabled KBs missing their wiki_schema row (seeder listener bug)",
                    """
                    SELECT kb.id, kb.name, kb.tenant_id
                    FROM knowledge_base kb
                    LEFT JOIN wiki_schema ws ON ws.kb_id = kb.id
                    WHERE kb.wiki_enabled = true AND ws.id IS NULL
                    """,
                    List.of("kb_id", "name", "tenant_id"),
                    List.of()));

    public List<String> availableRules() {
        return new ArrayList<>(RULES.keySet());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> run(String rule, int thresholdMinutes) {
        if ("__list__".equals(rule)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rules", availableRules());
            Map<String, String> details = new LinkedHashMap<>();
            RULES.forEach((k, v) -> details.put(k, v.description()));
            out.put("details", details);
            return out;
        }
        RuleSpec spec = RULES.get(rule);
        if (spec == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("message", "unknown rule '" + rule + "'; available: " + availableRules());
            return err;
        }

        Query q = em.createNativeQuery(spec.sql());
        if (spec.params().contains("threshold_minutes")) {
            q.setParameter("threshold_minutes", thresholdMinutes);
        }
        q.setMaxResults(100);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<Map<String, Object>> violations = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i < spec.columns().size() && i < row.length; i++) {
                r.put(spec.columns().get(i), row[i]);
            }
            violations.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", violations.isEmpty());
        out.put("rule", rule);
        out.put("description", spec.description());
        out.put("count", violations.size());
        out.put("violations", violations);
        return out;
    }
}
```

- [ ] **Step 2.4: Run — passes**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test --tests DataConsistencyCheckServiceTest 2>&1 | tail -10
```
Expected: 5 passed.

- [ ] **Step 2.5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add lakeon-api/src/main/java/com/lakeon/service/admin/DataConsistencyCheckService.java \
        lakeon-api/src/test/java/com/lakeon/service/admin/DataConsistencyCheckServiceTest.java
git commit -m "feat(api): DataConsistencyCheckService — 4 invariant rules (admin REST)"
```

---

### Task 3: `StuckTaskQueryService` — Java service for stuck async tasks

**Files:**
- Create: `lakeon/lakeon-api/src/main/java/com/lakeon/service/admin/StuckTaskQueryService.java`
- Create: `lakeon/lakeon-api/src/test/java/com/lakeon/service/admin/StuckTaskQueryServiceTest.java`

- [ ] **Step 3.1: Failing test**

```java
// lakeon/lakeon-api/src/test/java/com/lakeon/service/admin/StuckTaskQueryServiceTest.java
package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StuckTaskQueryServiceTest {

    private EntityManager em;
    private Query query;
    private StuckTaskQueryService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        service = new StuckTaskQueryService(em);
    }

    @Test
    void emptyResultReturnsZeroCount() {
        when(query.getResultList()).thenReturn(List.of());
        Map<String, Object> result = service.run(10, null);
        assertThat(result.get("count")).isEqualTo(0);
    }

    @Test
    void hitFromOneTable() {
        when(query.getResultList())
                .thenReturn(List.of(new Object[]{
                        "task_42", "kb_abc", "WIKI_UPDATE", "in_progress",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 700
                }))
                .thenReturn(List.of())
                .thenReturn(List.of());
        Map<String, Object> result = service.run(5, null);
        assertThat(result.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks.get(0).get("task_type")).isEqualTo("WIKI_UPDATE");
        assertThat(tasks.get(0).get("source")).isEqualTo("wiki_run_logs");
    }

    @Test
    void filterByType() {
        when(query.getResultList())
                .thenReturn(List.of(new Object[]{
                        "task_a", "kb_a", "WIKI_UPDATE", "in_progress",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 700}))
                .thenReturn(List.of(new Object[]{
                        "task_b", null, "FUSE_BACKFILL", "in_progress",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 700}))
                .thenReturn(List.of());
        Map<String, Object> result = service.run(5, "WIKI_UPDATE");
        assertThat(result.get("count")).isEqualTo(1);
    }

    @Test
    void undefinedTableHandledGracefully() {
        when(query.getResultList())
                .thenReturn(List.of())   // wiki_run_logs
                .thenReturn(List.of())   // lbfs_jobs
                .thenThrow(new PersistenceException("relation does not exist"));  // kb_processing_tasks
        Map<String, Object> result = service.run(10, null);
        assertThat(result.get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).isNotNull();
        assertThat(warnings.toString()).contains("kb_processing_tasks");
    }
}
```

- [ ] **Step 3.2: Run — fails**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test --tests StuckTaskQueryServiceTest 2>&1 | tail -10
```
Expected: compile error.

- [ ] **Step 3.3: Implement service**

```java
// lakeon/lakeon-api/src/main/java/com/lakeon/service/admin/StuckTaskQueryService.java
package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read-only stuck task query across known async task tables.
 * Mirrors the dbay-sre-mcp 0.2.0 stuck_task_query Python tool;
 * graceful UndefinedTable handling preserves robustness.
 */
@Service
public class StuckTaskQueryService {

    private final EntityManager em;

    public StuckTaskQueryService(EntityManager em) {
        this.em = em;
    }

    private record TableSpec(String tableName, String sql) {}

    private static final List<TableSpec> SOURCES = List.of(
            new TableSpec("wiki_run_logs",
                    """
                    SELECT id, kb_id, task_type, status, started_at,
                           EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
                    FROM wiki_run_logs
                    WHERE status = 'in_progress'
                      AND started_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY started_at ASC
                    """),
            new TableSpec("lbfs_jobs",
                    """
                    SELECT id, NULL::text AS kb_id, job_type AS task_type, status, started_at,
                           EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
                    FROM lbfs_jobs
                    WHERE status = 'in_progress'
                      AND started_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY started_at ASC
                    """),
            new TableSpec("kb_processing_tasks",
                    """
                    SELECT id, kb_id, task_type, status, started_at,
                           EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
                    FROM kb_processing_tasks
                    WHERE status = 'in_progress'
                      AND started_at < NOW() - (:threshold_minutes || ' minutes')::interval
                    ORDER BY started_at ASC
                    """));

    private static final List<String> COLUMNS =
            List.of("task_id", "kb_id", "task_type", "status", "started_at", "age_sec");

    public Map<String, Object> run(int thresholdMinutes, String typeFilter) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (TableSpec src : SOURCES) {
            try {
                List<Map<String, Object>> rows = querySource(src, thresholdMinutes);
                for (Map<String, Object> row : rows) {
                    if (typeFilter != null && !typeFilter.isBlank()
                            && !typeFilter.equals(row.get("task_type"))) {
                        continue;
                    }
                    row.put("source", src.tableName());
                    tasks.add(row);
                }
            } catch (PersistenceException ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
                if (msg.contains("does not exist") || msg.contains("undefined")) {
                    warnings.add("table " + src.tableName()
                            + " does not exist in this DB; skipped");
                } else {
                    warnings.add("query against " + src.tableName() + " failed: "
                            + (ex.getMessage() == null ? "(no message)" : ex.getMessage()));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", tasks.size());
        out.put("threshold_minutes", thresholdMinutes);
        out.put("tasks", tasks);
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        return out;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true,
            noRollbackFor = PersistenceException.class)
    protected List<Map<String, Object>> querySource(TableSpec src, int thresholdMinutes) {
        Query q = em.createNativeQuery(src.sql());
        q.setParameter("threshold_minutes", thresholdMinutes);
        q.setMaxResults(50);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i < COLUMNS.size() && i < row.length; i++) {
                r.put(COLUMNS.get(i), row[i]);
            }
            out.add(r);
        }
        return out;
    }
}
```

- [ ] **Step 3.4: Run — pass**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test --tests StuckTaskQueryServiceTest 2>&1 | tail -10
```
Expected: 4 passed.

- [ ] **Step 3.5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add lakeon-api/src/main/java/com/lakeon/service/admin/StuckTaskQueryService.java \
        lakeon-api/src/test/java/com/lakeon/service/admin/StuckTaskQueryServiceTest.java
git commit -m "feat(api): StuckTaskQueryService — union 3 task tables (admin REST)"
```

---

### Task 4: AdminController endpoints + WebMvcTest slice

**Files:**
- Modify: `lakeon/lakeon-api/src/main/java/com/lakeon/controller/AdminController.java` (+2 endpoints + 2 service injections)
- Create: `lakeon/lakeon-api/src/test/java/com/lakeon/controller/AdminControllerDataConsistencyTest.java`

- [ ] **Step 4.1: Failing controller test**

```java
// lakeon/lakeon-api/src/test/java/com/lakeon/controller/AdminControllerDataConsistencyTest.java
package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.service.admin.DataConsistencyCheckService;
import com.lakeon.service.admin.StuckTaskQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
class AdminControllerDataConsistencyTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private DataConsistencyCheckService dccService;
    @MockBean private StuckTaskQueryService stqService;
    // Existing AdminController already has many other dependencies;
    // they are added as @MockBean in the existing AdminControllerTest setup.
    // For this NEW test class, copy the @MockBean list from there if compile fails.

    @Test
    void dataConsistencyEndpointReturnsServiceJson() throws Exception {
        when(dccService.run(eq("kb_implies_db_id"), anyInt())).thenReturn(Map.of(
                "ok", false, "rule", "kb_implies_db_id", "count", 2,
                "violations", List.of(Map.of("kb_id", "kb_a"), Map.of("kb_id", "kb_b"))
        ));
        mvc.perform(get("/api/v1/admin/data-consistency/kb_implies_db_id")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.violations[0].kb_id").value("kb_a"));
    }

    @Test
    void stuckTasksEndpointReturnsServiceJson() throws Exception {
        when(stqService.run(eq(10), eq(""))).thenReturn(Map.of(
                "count", 1, "threshold_minutes", 10,
                "tasks", List.of(Map.of("task_id", "t_42", "task_type", "WIKI_UPDATE",
                        "source", "wiki_run_logs", "age_sec", 700))
        ));
        mvc.perform(get("/api/v1/admin/stuck-tasks?threshold_minutes=10")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.tasks[0].task_type").value("WIKI_UPDATE"));
    }
}
```

**NOTE:** `@WebMvcTest(AdminController.class)` activates Spring's slice test. Existing test in repo already provides the broader `@MockBean` list — if compile fails on missing beans, copy from the existing `AdminControllerTest.java` setup section into this new test.

- [ ] **Step 4.2: Run — fails**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test --tests AdminControllerDataConsistencyTest 2>&1 | tail -15
```
Expected: 404 from MockMvc (endpoints not yet defined).

- [ ] **Step 4.3: Add 2 endpoints + service injection in AdminController**

In `/Users/jacky/code/lakeon/lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`:

(a) Find the constructor / field declarations. Add these 2 fields:
```java
    private final DataConsistencyCheckService dataConsistencyCheckService;
    private final StuckTaskQueryService stuckTaskQueryService;
```

(b) Add these to the constructor parameter list and assignment list. Match existing style.

(c) Add imports at top of file:
```java
import com.lakeon.service.admin.DataConsistencyCheckService;
import com.lakeon.service.admin.StuckTaskQueryService;
```

(d) Add the 2 endpoint methods (anywhere in the class, suggest near the existing `/admin/operations` to group SRE-facing endpoints):

```java
    // ── SRE: Data consistency invariants ───────────────────────────

    @GetMapping("/data-consistency/{rule}")
    public Map<String, Object> dataConsistencyCheck(
            @PathVariable String rule,
            @RequestParam(name = "threshold_minutes", defaultValue = "10") int thresholdMinutes) {
        return dataConsistencyCheckService.run(rule, thresholdMinutes);
    }

    // ── SRE: Stuck async tasks ─────────────────────────────────────

    @GetMapping("/stuck-tasks")
    public Map<String, Object> stuckTaskQuery(
            @RequestParam(name = "threshold_minutes", defaultValue = "10") int thresholdMinutes,
            @RequestParam(required = false, defaultValue = "") String type) {
        return stuckTaskQueryService.run(thresholdMinutes, type);
    }
```

- [ ] **Step 4.4: Run — pass**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test --tests AdminControllerDataConsistencyTest 2>&1 | tail -15
```
Expected: 2 passed.

- [ ] **Step 4.5: Run full test suite — no regression**

```bash
cd /Users/jacky/code/lakeon/lakeon-api
./gradlew test 2>&1 | tail -10
```
Expected: existing tests still pass.

- [ ] **Step 4.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java \
        lakeon-api/src/test/java/com/lakeon/controller/AdminControllerDataConsistencyTest.java
git commit -m "feat(api): /admin/data-consistency/{rule} + /admin/stuck-tasks endpoints"
```

- [ ] **Step 4.7: Build + push image to CCE**

```bash
cd /Users/jacky/code/lakeon
SITE=hwstaff bash deploy/cce/build-and-push-api.sh 2>&1 | tail -5
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=180s
```
Expected: rollout completes successfully.

- [ ] **Step 4.8: Smoke check the new endpoints in production**

```bash
curl -sS -i -H "Authorization: Bearer lakeon-sre-2026" \
  "https://api.dbay.cloud:8443/api/v1/admin/data-consistency/__list__" | tail -5
curl -sS -i -H "Authorization: Bearer lakeon-sre-2026" \
  "https://api.dbay.cloud:8443/api/v1/admin/stuck-tasks?threshold_minutes=10" | tail -5
```
Expected: both 200, JSON has `rules` array (1st) / `count` field (2nd).

---

## Group C: dbay-sre-mcp REST 化

### Task 5: REST-ify `data_consistency_check`

**Files:**
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py` (add `data_consistency_check` method)
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/data_consistency_check.py` (replace PG with admin REST)
- Modify: `lakeon/dbay-sre-mcp/tests/test_data_consistency_check.py` (mock admin client, not PG)
- Modify: `lakeon/dbay-sre-mcp/tests/test_admin_client.py` (test the new admin client method)

- [ ] **Step 5.1: Add `data_consistency_check` method to LakeonAdminClient**

In `admin_client.py`, after the existing `system_health()` method, add:

```python
    # ---- SRE-only ----

    def data_consistency_check(self, *, rule: str, threshold_minutes: int = 10) -> dict:
        """GET /admin/data-consistency/{rule}?threshold_minutes=N"""
        body = self._get(
            f"/admin/data-consistency/{rule}",
            params={"threshold_minutes": threshold_minutes},
        )
        return body or {"ok": False, "message": "no response from admin endpoint"}

    def stuck_task_query(self, *, threshold_minutes: int = 10, type: str = "") -> dict:
        """GET /admin/stuck-tasks?threshold_minutes=N&type=X"""
        params: dict = {"threshold_minutes": threshold_minutes}
        if type:
            params["type"] = type
        body = self._get("/admin/stuck-tasks", params=params)
        return body or {"count": 0, "tasks": []}
```

- [ ] **Step 5.2: Add admin client tests for these 2 methods**

Append to `tests/test_admin_client.py`:

```python
def test_data_consistency_check_calls_endpoint(monkeypatch):
    fake = _FakeHttp({
        ("GET", "https://x/api/v1/admin/data-consistency/kb_implies_db_id"):
            {"ok": False, "count": 2, "violations": [{"kb_id": "k1"}]},
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://x/api/v1", token="t")
    out = c.data_consistency_check(rule="kb_implies_db_id")
    assert out["count"] == 2


def test_stuck_task_query_calls_endpoint(monkeypatch):
    fake = _FakeHttp({
        ("GET", "https://x/api/v1/admin/stuck-tasks"):
            {"count": 1, "tasks": [{"task_id": "t_42"}]},
    })
    monkeypatch.setattr(httpx, "Client", lambda *a, **kw: fake)
    c = LakeonAdminClient(base_url="https://x/api/v1", token="t")
    out = c.stuck_task_query(threshold_minutes=5)
    assert out["count"] == 1
```

- [ ] **Step 5.3: Rewrite `tools/data_consistency_check.py` to use admin client**

Replace entire file contents:

```python
"""data_consistency_check tool — calls lakeon-api /admin/data-consistency/{rule}.

In 0.2.1, this tool no longer connects to PG directly. The 4 invariant rules
are implemented in the lakeon-api Java service and exposed as admin REST
endpoints. This keeps lakeon DB credentials inside the CCE network and
avoids requiring LAKEON_DB_DSN env on the dbay-sre-mcp side.
"""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


# Static list mirrors the Java service's RULES map. The dynamic '__list__' rule
# also returns these — but having them here lets callers query without a round
# trip when they just want to know the names.
AVAILABLE_RULES = [
    "kb_implies_db_id",
    "enqueued_implies_drained",
    "db_ready_implies_pod_running",
    "schema_seeded",
]


def data_consistency_check_impl(
    *,
    rule: str,
    threshold_minutes: int = 10,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    admin = _admin or LakeonAdminClient()
    result = admin.data_consistency_check(rule=rule, threshold_minutes=threshold_minutes)
    return json.dumps(result, ensure_ascii=False)
```

- [ ] **Step 5.4: Rewrite tests/test_data_consistency_check.py**

Replace entire file:

```python
"""Tests for data_consistency_check tool — now mocks LakeonAdminClient (REST)."""
import json
from unittest.mock import MagicMock

from dbay_sre_mcp.tools.data_consistency_check import (
    AVAILABLE_RULES,
    data_consistency_check_impl,
)


def _fake_admin(response: dict):
    c = MagicMock()
    c.data_consistency_check = lambda *, rule, threshold_minutes=10: response
    return c


def test_lists_available_rules():
    """AVAILABLE_RULES still exposes the 4 rule names statically."""
    assert {"kb_implies_db_id", "enqueued_implies_drained",
            "db_ready_implies_pod_running", "schema_seeded"} <= set(AVAILABLE_RULES)


def test_passes_through_admin_response():
    admin = _fake_admin({
        "ok": False, "count": 2,
        "violations": [{"kb_id": "kb_a"}, {"kb_id": "kb_b"}],
        "rule": "kb_implies_db_id",
    })
    out = json.loads(data_consistency_check_impl(
        rule="kb_implies_db_id", _admin=admin,
    ))
    assert out["ok"] is False
    assert out["count"] == 2
    assert out["violations"][0]["kb_id"] == "kb_a"


def test_passes_threshold_minutes_to_admin():
    captured = {}
    admin = MagicMock()

    def fake_dcc(*, rule, threshold_minutes):
        captured["rule"] = rule
        captured["threshold_minutes"] = threshold_minutes
        return {"ok": True, "count": 0, "violations": []}

    admin.data_consistency_check = fake_dcc
    data_consistency_check_impl(
        rule="enqueued_implies_drained", threshold_minutes=5, _admin=admin,
    )
    assert captured["threshold_minutes"] == 5


def test_list_dispatch_via_admin():
    """__list__ is delegated to admin endpoint (not local short-circuit)."""
    admin = _fake_admin({"rules": AVAILABLE_RULES,
                         "details": {r: "desc" for r in AVAILABLE_RULES}})
    out = json.loads(data_consistency_check_impl(rule="__list__", _admin=admin))
    assert "rules" in out
    assert set(out["rules"]) >= set(AVAILABLE_RULES)
```

- [ ] **Step 5.5: Run tests — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
PYTHONPATH=src python3 -m pytest tests/test_data_consistency_check.py tests/test_admin_client.py -v
```
Expected: 4 + 7 = 11 passed.

- [ ] **Step 5.6: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/admin_client.py \
        dbay-sre-mcp/src/dbay_sre_mcp/tools/data_consistency_check.py \
        dbay-sre-mcp/tests/test_admin_client.py \
        dbay-sre-mcp/tests/test_data_consistency_check.py
git commit -m "refactor(sre-mcp): data_consistency_check via admin REST (was PG direct)"
```

---

### Task 6: REST-ify `stuck_task_query`

**Files:**
- Modify: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/tools/stuck_task_query.py`
- Modify: `lakeon/dbay-sre-mcp/tests/test_stuck_task_query.py`

(`LakeonAdminClient.stuck_task_query` was already added in Task 5.1.)

- [ ] **Step 6.1: Rewrite tools/stuck_task_query.py**

Replace entire file:

```python
"""stuck_task_query tool — calls lakeon-api /admin/stuck-tasks.

In 0.2.1, this tool no longer connects to PG directly. The 3-table union
query is implemented in the lakeon-api Java service.
"""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


def stuck_task_query_impl(
    *,
    threshold_minutes: int = 10,
    type: Optional[str] = None,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    admin = _admin or LakeonAdminClient()
    result = admin.stuck_task_query(threshold_minutes=threshold_minutes,
                                    type=type or "")
    return json.dumps(result, ensure_ascii=False)
```

- [ ] **Step 6.2: Rewrite tests/test_stuck_task_query.py**

```python
"""Tests for stuck_task_query — now mocks LakeonAdminClient (REST)."""
import json
from unittest.mock import MagicMock

from dbay_sre_mcp.tools.stuck_task_query import stuck_task_query_impl


def _fake_admin(response: dict):
    c = MagicMock()
    c.stuck_task_query = lambda *, threshold_minutes=10, type="": response
    return c


def test_no_stuck_tasks():
    admin = _fake_admin({"count": 0, "tasks": []})
    out = json.loads(stuck_task_query_impl(_admin=admin))
    assert out["count"] == 0
    assert out["tasks"] == []


def test_stuck_tasks_passthrough():
    admin = _fake_admin({
        "count": 2, "threshold_minutes": 10,
        "tasks": [
            {"task_id": "t1", "task_type": "WIKI_UPDATE", "source": "wiki_run_logs",
             "status": "in_progress", "age_sec": 700},
            {"task_id": "t2", "task_type": "FUSE_BACKFILL", "source": "lbfs_jobs",
             "status": "in_progress", "age_sec": 800},
        ],
    })
    out = json.loads(stuck_task_query_impl(threshold_minutes=10, _admin=admin))
    assert out["count"] == 2
    assert out["tasks"][0]["task_type"] == "WIKI_UPDATE"


def test_type_filter_passed_to_admin():
    captured = {}
    admin = MagicMock()

    def fake_stq(*, threshold_minutes, type):
        captured["threshold_minutes"] = threshold_minutes
        captured["type"] = type
        return {"count": 0, "tasks": []}

    admin.stuck_task_query = fake_stq
    stuck_task_query_impl(threshold_minutes=5, type="WIKI_UPDATE", _admin=admin)
    assert captured["type"] == "WIKI_UPDATE"
    assert captured["threshold_minutes"] == 5


def test_warnings_field_preserved():
    admin = _fake_admin({
        "count": 0, "tasks": [],
        "warnings": ["table kb_processing_tasks does not exist; skipped"],
    })
    out = json.loads(stuck_task_query_impl(_admin=admin))
    assert "warnings" in out
    assert "kb_processing_tasks" in out["warnings"][0]
```

- [ ] **Step 6.3: Run — pass**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
PYTHONPATH=src python3 -m pytest tests/test_stuck_task_query.py -v
```
Expected: 4 passed.

- [ ] **Step 6.4: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/src/dbay_sre_mcp/tools/stuck_task_query.py \
        dbay-sre-mcp/tests/test_stuck_task_query.py
git commit -m "refactor(sre-mcp): stuck_task_query via admin REST (was PG direct)"
```

---

## Group D: Cleanup + ship

### Task 7: Delete `lakeon_db.py` + `LAKEON_DB_DSN` references; full suite green

**Files:**
- Delete: `lakeon/dbay-sre-mcp/src/dbay_sre_mcp/lakeon_db.py`
- Modify: `lakeon/dbay-sre-mcp/README.md` (env vars table — remove `LAKEON_DB_DSN`)
- Create: `lakeon/dbay-sre-mcp/PUBLISH-0.2.1.md`
- Delete: `lakeon/dbay-sre-mcp/PUBLISH-0.2.0.md`
- Modify: `lakeon/sre-agent/scripts/verify_env.py` (drop LAKEON_DB_DSN entry)
- Modify: `lakeon/sre-agent/hermes_config/config.yaml` (drop LAKEON_DB_DSN env)
- Modify: `lakeon/sre-agent/.env.example` (drop LAKEON_DB_DSN)
- Modify: `lakeon/sre-agent/Dockerfile` (DBAY_SRE_MCP_VERSION=0.2.0 → 0.2.1)

- [ ] **Step 7.1: Delete lakeon_db.py**

```bash
cd /Users/jacky/code/lakeon
git rm dbay-sre-mcp/src/dbay_sre_mcp/lakeon_db.py
```

- [ ] **Step 7.2: README env vars table — drop LAKEON_DB_DSN row**

In `dbay-sre-mcp/README.md`, find the env vars table (Plan A added it during Task 9). The current row:

```markdown
| `LAKEON_DB_DSN` | data_consistency_check, stuck_task_query | lakeon-api production Postgres (read-only role recommended) |
```

Delete that row. The remaining rows (`LOG_DB_DSN`, `LAKEON_ADMIN_TOKEN`, `LAKEON_API_BASE_URL`) stay.

Also update the "Consistency & queues" section's "Used by" if it references `LAKEON_DB_DSN`. Make sure it now says these tools go through `LAKEON_ADMIN_TOKEN`.

- [ ] **Step 7.3: PUBLISH-0.2.1.md**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
git rm PUBLISH-0.2.0.md
```

Create `dbay-sre-mcp/PUBLISH-0.2.1.md`:

```markdown
# Publish dbay-sre-mcp 0.2.1 to PyPI

## What's in 0.2.1

- **Critical fix**: `LakeonAdminClient` now sends `Authorization: Bearer <token>`
  (was `Admin-Token: <token>` in 0.2.0, causing all 7 admin REST tools to 403).
- `data_consistency_check` and `stuck_task_query` now go through admin REST
  endpoints, not PG direct. Removes `LAKEON_DB_DSN` env requirement.
- Requires lakeon-api with `/admin/data-consistency/{rule}` and `/admin/stuck-tasks`
  endpoints (released alongside this version).

## Publish

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
rm -rf dist/dbay_sre_mcp-0.2.1*
python3 -m build --wheel
PYPI_TOKEN=$(grep '^password' ~/.pypirc | head -1 | cut -d'=' -f2 | tr -d ' ')
uv publish --token "$PYPI_TOKEN" dist/dbay_sre_mcp-0.2.1*
```

## Verify

```bash
pip download --no-deps --dest /tmp/verify dbay-sre-mcp==0.2.1
```

## Then bump sre-agent

`sre-agent/Dockerfile` already pins `DBAY_SRE_MCP_VERSION=0.2.1` (Task 7).
Push any commit to trigger Railway rebuild.
```

- [ ] **Step 7.4: sre-agent verify_env + config + .env.example — drop LAKEON_DB_DSN**

`sre-agent/scripts/verify_env.py` — Find the OPTIONAL list. Delete the `LAKEON_DB_DSN` entry:

```python
OPTIONAL = [
    ("FEISHU_VERIFICATION_TOKEN", "only required if FEISHU_CONNECTION_MODE=webhook"),
    ("FEISHU_ENCRYPT_KEY", "only required if FEISHU_CONNECTION_MODE=webhook"),
    ("OBS_PREFIX", "defaults to 'agent-log/'"),
    ("LAKEON_API_BASE_URL", "defaults to https://api.dbay.cloud:8443/api/v1 if unset"),
]
```

`sre-agent/hermes_config/config.yaml` — Find the `mcp_servers.dbay_sre.env` block and delete the `LAKEON_DB_DSN: "${LAKEON_DB_DSN}"` line. Final shape:
```yaml
    env:
      LOG_DB_DSN: "${LOG_DB_DSN}"
      LAKEON_ADMIN_TOKEN: "${LAKEON_ADMIN_TOKEN}"
      LAKEON_API_BASE_URL: "${LAKEON_API_BASE_URL}"
```

`sre-agent/.env.example` — delete the `LAKEON_DB_DSN=...` line.

- [ ] **Step 7.5: Bump sre-agent Dockerfile to 0.2.1**

```bash
sed -i.bak 's/DBAY_SRE_MCP_VERSION=0.2.0/DBAY_SRE_MCP_VERSION=0.2.1/' \
    /Users/jacky/code/lakeon/sre-agent/Dockerfile
rm /Users/jacky/code/lakeon/sre-agent/Dockerfile.bak
grep DBAY_SRE_MCP_VERSION /Users/jacky/code/lakeon/sre-agent/Dockerfile
```
Expected: `ARG DBAY_SRE_MCP_VERSION=0.2.1`

- [ ] **Step 7.6: Run full dbay-sre-mcp test suite**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
PYTHONPATH=src python3 -m pytest tests/ -v 2>&1 | tail -3
```
Expected: 50+ passed (existing minus the 4 deleted PG-mock tests + 4 new REST tests + the test_admin_client extension).

- [ ] **Step 7.7: Commit**

```bash
cd /Users/jacky/code/lakeon
git add dbay-sre-mcp/README.md \
        dbay-sre-mcp/PUBLISH-0.2.1.md \
        sre-agent/scripts/verify_env.py \
        sre-agent/hermes_config/config.yaml \
        sre-agent/.env.example \
        sre-agent/Dockerfile
# git rm already staged the 2 deletions
git commit -m "chore(sre-mcp+sre-agent): drop LAKEON_DB_DSN, bump to 0.2.1"
```

---

### Task 8: Publish 0.2.1 + Railway redeploy + smoke test

**Files:**
- Build artifact: `lakeon/dbay-sre-mcp/dist/dbay_sre_mcp-0.2.1-*.whl`
- Modify: `lakeon/sre-agent/reports/phase1-progress.md` (add 0.2.1 section)

- [ ] **Step 8.1: Build + publish 0.2.1 to PyPI**

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
rm -rf dist/dbay_sre_mcp-0.2.1*
python3 -m build --wheel 2>&1 | tail -3
PYPI_TOKEN=$(grep '^password' ~/.pypirc | head -1 | cut -d'=' -f2 | tr -d ' ')
uv publish --token "$PYPI_TOKEN" dist/dbay_sre_mcp-0.2.1* 2>&1 | tail -5
```
Expected: "Uploading dbay_sre_mcp-0.2.1-py3-none-any.whl" with no error after.

- [ ] **Step 8.2: Verify PyPI has it**

```bash
sleep 5  # propagation
pip download --no-deps --dest /tmp/verify-0.2.1 dbay-sre-mcp==0.2.1 2>&1 | tail -3
```
Expected: "Saved /tmp/verify-0.2.1/dbay_sre_mcp-0.2.1-py3-none-any.whl".

- [ ] **Step 8.3: Push commits to trigger Railway rebuild**

(Tasks 1+5+6+7 commits should already be in `main`. Empty push if needed.)

```bash
cd /Users/jacky/code/lakeon
git log --oneline -8
git push 2>&1 | tail -3
```

- [ ] **Step 8.4: Wait + verify Railway deploy**

Railway picks up changes auto. Monitor: `railway logs --service lakeon-sre-agent --build -n 30` until build succeeds. (Controller will monitor; subagent can stop at this step.)

After deploy, runtime smoke check:
```bash
railway logs --service lakeon-sre-agent -n 30 2>&1 | grep -E "data_consistency_watcher|stuck_task_watcher|pod_create_failure_watcher" | head -10
```
Expected: previous errors gone:
- ❌ `failed: Client error '403'` should be ❌→ ✅ "no new incidents" or actual results
- ❌ `failed: 'LAKEON_DB_DSN'` should disappear

- [ ] **Step 8.5: Append phase1-progress.md**

Open `/Users/jacky/code/lakeon/sre-agent/reports/phase1-progress.md`. Append:

```markdown

## 0.2.1 hot-fix + REST cleanup (DONE)

Fixed the 0.2.0 production rollout issues:
- [x] LakeonAdminClient header bug (`Admin-Token` → `Authorization: Bearer`)
- [x] `data_consistency_check` REST-ified (no more LAKEON_DB_DSN)
- [x] `stuck_task_query` REST-ified
- [x] lakeon-api: `DataConsistencyCheckService` + `StuckTaskQueryService`
- [x] lakeon-api: `/admin/data-consistency/{rule}` + `/admin/stuck-tasks` endpoints
- [x] Deleted `dbay_sre_mcp/lakeon_db.py`
- [x] Removed `LAKEON_DB_DSN` from sre-agent .env.example, verify_env, config.yaml
- [x] Published dbay-sre-mcp 0.2.1 to PyPI
- [x] Railway sre-agent rebuilt on 0.2.1

## Validation after 0.2.1

1. Within 2 min: `pod_create_failure_watcher` logs no longer show 403 (auth fixed)
2. Within 5 min: `stuck_task_watcher` no longer crashes with `'LAKEON_DB_DSN'` KeyError
3. Within 15 min: `data_consistency_watcher` runs successfully (4 rules return)
4. Existing watchers (cold_start, fuse_queue, multi_tenant_blast_radius) keep running
```

- [ ] **Step 8.6: Commit + push**

```bash
cd /Users/jacky/code/lakeon
git add sre-agent/reports/phase1-progress.md
git commit -m "docs(phase1): 0.2.1 REST-everywhere completion + validation"
git push 2>&1 | tail -3
```

---

## Self-Review Checklist

- [x] **Spec coverage:** Auth fix (Task 1), 2 lakeon-api endpoints (Tasks 2-4), REST-ify 2 tools (Tasks 5-6), cleanup (Task 7), ship (Task 8). All mapped.
- [x] **Backward compat:** All 7 dbay-sre-mcp tool signatures + return JSON shapes UNCHANGED. Watcher code (Plan B) requires zero updates.
- [x] **No placeholders:** every step has concrete code or commands.
- [x] **Type/identifier consistency:** `LakeonAdminClient`, `data_consistency_check_impl`, `stuck_task_query_impl`, `DataConsistencyCheckService`, `StuckTaskQueryService` spelled identically.
- [x] **TDD:** every change has failing-test → impl → passing-test cycle.
- [x] **Hard constraints:** ApiKeyFilter auth respected (Task 4 uses Bearer header); endpoints read-only (`@Transactional(readOnly = true)`); JSON shape preserved (Tasks 2/3 mirror Python tool's exact field names + structure); LAKEON_DB_DSN removed completely (Task 7).
- [x] **Phase 0a unchanged:** cold_start_watcher / outcome_checker untouched. Plan B watchers untouched (use same tool signatures).

## Open Risks During Execution

1. **`@WebMvcTest(AdminController.class)` requires the full @MockBean list.** Existing `AdminControllerTest` in repo has the broader list — copy it into Task 4's new test class verbatim if compile complains. List won't change much; tedium not difficulty.
2. **Java service SQL assumes specific table/column names** (`knowledge_base.status`, `kb_write_queue.drained_at`, `wiki_run_logs.task_type`). If schema in production differs, runtime errors during Task 4.8 smoke test. Mitigation: that smoke test is the bridge — if a query fails, fix the SQL in-place and redeploy.
3. **`StuckTaskQueryService.querySource` uses `Propagation.REQUIRES_NEW`** so an UndefinedTable error in one query doesn't poison the outer transaction. Spring's transaction proxy is on `@Service` beans — if you put `querySource` as `private` it won't be proxied. The plan made it `protected` which Spring will proxy. Don't refactor to `private`.
4. **PyPI cache propagation 0.2.1 → Railway pip install** can take 1-2 min. If Railway build pulls 0.2.0 again, redeploy via `railway redeploy --service lakeon-sre-agent --yes` after `pip download` confirms 0.2.1.
5. **lakeon-api CCE deploy depends on `SITE=hwstaff bash deploy/cce/build-and-push-api.sh`** — if that script fails, fall back to manual `docker build && docker push && kubectl rollout restart`. CLAUDE.md feedback says SITE=hwstaff is the default.
6. **Plan B watchers will start working immediately** after 0.2.1 deploy. If `pod_create_failures` returns real failures, you'll get DM bursts — initial 10-min may be noisy because dedupe state is empty. Acceptable; settles after first cycle.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-04-25-dbay-sre-mcp-0.2.1-rest-everywhere.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, two-stage review.
**2. Inline Execution** — batch with checkpoints.

Per user request: **subagent for both Python AND Java tasks** (Tasks 2-4 are Java/Spring). Same workflow as before.

Which approach?
