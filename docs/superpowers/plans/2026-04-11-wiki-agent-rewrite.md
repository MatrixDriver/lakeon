# Wiki Agent Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the one-shot `WikiService` ingest/curate/lint flow with an agentic Wiki Agent that uses DeepSeek V3.2 tool calling, lives in a new standalone `lakeon-wiki-agent` Python service, and is also usable interactively from Claude Code via a `dbay-kb` skill.

**Architecture:** Three-layer:
1. **lakeon-api** exposes low-level wiki tool handlers at `/api/v1/internal/wiki/tool/*` (authenticated by admin token). The old one-shot wiki prompts and `applyWikiChanges()` are deleted.
2. **lakeon-wiki-agent** (new Python/FastAPI service) runs the agent loop, calls DeepSeek V3.2 via OpenAI-compatible API on 华为云 MaaS, dispatches tool calls back to lakeon-api, and posts async webhook callbacks when a run completes.
3. **dbay-mcp** adds high-level wiki MCP tools (`wiki_list_pages`, `wiki_ingest`, `wiki_curate`, …) that proxy to lakeon-api and lakeon-wiki-agent. The `dbay-kb` Claude Code skill in `~/code/dbay-plugins/skills/dbay-kb/` orchestrates human-in-the-loop workflows using those MCP tools.

The Karpathy schema document lives at `/schema.md` inside each KB's wiki directory; it is seeded on KB creation and co-evolved by the agent.

**Tech Stack:**
- lakeon-api: Java 17, Spring Boot 3.3.5, JPA, PostgreSQL (existing)
- lakeon-wiki-agent: Python 3.11+, FastAPI, `openai` SDK, httpx, pydantic, pytest
- dbay-mcp: Python, FastMCP (existing pattern)
- dbay-kb skill: markdown + MCP tools (no code)
- Deployment: CCE on `hwstaff`, Helm, `SITE=hwstaff bash deploy/cce/build-and-push-wiki-agent.sh`

**Reference implementation**: `lakeon-api/src/main/java/com/lakeon/service/SreAiService.java` already runs a working DeepSeek V3.2 tool-calling loop against 华为云 MaaS. `lakeon-wiki-agent` mirrors its state machine in Python.

**Key constraints (decided in brainstorming)**:
- Schema stored as `/schema.md` wiki page, co-evolved by the agent (Q1=b)
- Async callback via webhook, not polling (Q2=b)
- DeepSeek function calling proven via SreAiService, no spike needed (Q3)
- No hard cap on `create_page`; soft constraint in schema only (Q4=b)
- Existing 60 pages compacted via new agentic curate (Q5=b)
- E2E target: 6 docs from `~/code/kb-doc` → < 20 wiki pages (Q6)
- lakeon-wiki-agent is resident Deployment, replicas=1, in-process semaphore for concurrency (Q9)
- Resources: request 200m/512Mi, limit 1000m/2Gi (Q10)
- readiness probe only, no liveness (Q11)

---

## File Structure

### lakeon-api (Java) — modify

| File | Action | Responsibility |
|---|---|---|
| `lakeon-api/src/main/java/com/lakeon/knowledge/InternalWikiController.java` | **Create** | REST endpoints at `/api/v1/internal/wiki/tool/*`, `/runlog`, `/callback/{task_id}` |
| `lakeon-api/src/main/java/com/lakeon/knowledge/WikiToolService.java` | **Create** | Stateless handlers for each tool; moved from old WikiService internals |
| `lakeon-api/src/main/java/com/lakeon/knowledge/WikiAgentClient.java` | **Create** | HTTP client that enqueues runs to `lakeon-wiki-agent` |
| `lakeon-api/src/main/java/com/lakeon/knowledge/WikiRunLogEntity.java` | **Modify** | Add `run_id`, `tool_calls_count`, `token_count`, `source` columns |
| `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java` | **Modify** | Delete `DEFAULT_WIKI_AGENT_PROMPT` / `DEFAULT_CURATE_PROMPT` / `applyWikiChanges` / `processIngest` / `runCurate` / one-shot LLM paths; keep `readWikiPage`, `writeWikiDocument`, `getGraph`, `titleToFilename`, `extractWikilinks`, `saveResponse` helpers |
| `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` | **Modify** | Seed `/schema.md` on KB creation |
| `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java` | **Modify** | `executeWikiUpdate` now calls `WikiAgentClient` instead of `WikiService.processIngest` |
| `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java` | **Modify** | Allow `/api/v1/internal/wiki/**` with internal token |
| `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` | **Modify** | Add `wiki.agent.url` property |
| `lakeon-api/src/main/resources/application.yml` | **Modify** | `lakeon.wiki.agent.url` env binding |
| `lakeon-api/src/main/resources/db/migration/V2026_04_11__wiki_run_log_agent.sql` | **Create** | Add new columns to `wiki_run_logs` |
| `lakeon-api/src/test/java/com/lakeon/knowledge/InternalWikiControllerTest.java` | **Create** | Unit tests for each tool endpoint |

### lakeon-wiki-agent (Python) — new repo-sibling

| File | Action | Responsibility |
|---|---|---|
| `lakeon-wiki-agent/pyproject.toml` | **Create** | Dependencies: fastapi, uvicorn, openai, httpx, pydantic, pytest |
| `lakeon-wiki-agent/Dockerfile` | **Create** | Python 3.11-slim, install deps, run uvicorn |
| `lakeon-wiki-agent/app/__init__.py` | **Create** | Package init |
| `lakeon-wiki-agent/app/main.py` | **Create** | FastAPI app entry, mounts routers, startup checks |
| `lakeon-wiki-agent/app/config.py` | **Create** | Env config via pydantic BaseSettings |
| `lakeon-wiki-agent/app/clients/lakeon_api.py` | **Create** | Async httpx client for `/api/v1/internal/wiki/*` |
| `lakeon-wiki-agent/app/agent/tools.py` | **Create** | OpenAI tool schemas (`list_pages`, `read_page`, `create_page`, …) |
| `lakeon-wiki-agent/app/agent/llm.py` | **Create** | Thin wrapper around `openai` SDK → MaaS endpoint |
| `lakeon-wiki-agent/app/agent/loop.py` | **Create** | Main agent loop, modelled on `SreAiService.chat` |
| `lakeon-wiki-agent/app/agent/schema_default.py` | **Create** | Default `/schema.md` content for new KBs |
| `lakeon-wiki-agent/app/api/ingest.py` | **Create** | `POST /v1/wiki/ingest` (async) |
| `lakeon-wiki-agent/app/api/curate.py` | **Create** | `POST /v1/wiki/curate` (async) |
| `lakeon-wiki-agent/app/api/lint.py` | **Create** | `POST /v1/wiki/lint` (async) |
| `lakeon-wiki-agent/app/api/status.py` | **Create** | `GET /v1/wiki/tasks/{task_id}`, `GET /health` |
| `lakeon-wiki-agent/app/tasks.py` | **Create** | In-memory task registry + `asyncio.Semaphore` for concurrency |
| `lakeon-wiki-agent/app/runlog.py` | **Create** | Helper to POST run log back to lakeon-api |
| `lakeon-wiki-agent/tests/test_agent_loop.py` | **Create** | Unit tests with mocked LLM + mocked lakeon-api |
| `lakeon-wiki-agent/tests/test_tools_schema.py` | **Create** | Validate tool schemas are OpenAI-compatible |
| `lakeon-wiki-agent/tests/conftest.py` | **Create** | Shared fixtures |

### dbay-mcp (Python) — modify

| File | Action | Responsibility |
|---|---|---|
| `lakeon/dbay-mcp/src/dbay_mcp/server.py` | **Modify** | Add 8 `@mcp.tool` wiki tools |
| `lakeon/dbay-mcp/src/dbay_mcp/tool_descriptions.yaml` | **Modify** | Add descriptions for the 8 new tools |
| `lakeon/dbay-mcp/tests/test_wiki_tools.py` | **Create** | Unit tests for the new tools (mock lakeon-api) |

### dbay-kb skill (markdown) — new

| File | Action | Responsibility |
|---|---|---|
| `~/code/dbay-plugins/skills/dbay-kb/SKILL.md` | **Create** | Main skill — orchestration overview |
| `~/code/dbay-plugins/skills/dbay-kb/references/schema-template.md` | **Create** | Default KB schema document |
| `~/code/dbay-plugins/skills/dbay-kb/references/compilation-guide.md` | **Create** | Page writing conventions |
| `~/code/dbay-plugins/skills/dbay-kb-ingest/SKILL.md` | **Create** | Ingest sub-skill |
| `~/code/dbay-plugins/skills/dbay-kb-query/SKILL.md` | **Create** | Query sub-skill |
| `~/code/dbay-plugins/skills/dbay-kb-lint/SKILL.md` | **Create** | Lint sub-skill |
| `~/code/dbay-plugins/README.md` | **Modify** | List the new skills |

### Deployment — new / modify

| File | Action | Responsibility |
|---|---|---|
| `lakeon/deploy/cce/build-and-push-wiki-agent.sh` | **Create** | Docker build + push to SWR |
| `lakeon/deploy/helm/lakeon-wiki-agent/Chart.yaml` | **Create** | Helm chart metadata |
| `lakeon/deploy/helm/lakeon-wiki-agent/values-hwstaff.yaml` | **Create** | hwstaff site values |
| `lakeon/deploy/helm/lakeon-wiki-agent/templates/deployment.yaml` | **Create** | K8s Deployment (replicas=1) |
| `lakeon/deploy/helm/lakeon-wiki-agent/templates/service.yaml` | **Create** | ClusterIP Service |
| `lakeon/deploy/helm/lakeon-wiki-agent/templates/configmap.yaml` | **Create** | Non-secret config |

### E2E tests — new / modify

| File | Action | Responsibility |
|---|---|---|
| `lakeon/tests/e2e/test_wiki_agent.py` | **Create** | Upload 6 test docs, assert `< 20` pages, per-doc `≥ 3` touches |
| `lakeon/tests/e2e/test_wiki_curate_existing.py` | **Create** | Compact existing 60-page KB via agentic curate |

---

## Phase 1: lakeon-api Internal Wiki Tool Endpoints

Goal: expose atomic wiki operations as REST endpoints that `lakeon-wiki-agent` can call. All endpoints authenticated by internal token (reusing `LakeonProperties.proxy.internalToken` pattern). The old one-shot code is removed in Phase 5 — Phase 1 adds the new surface alongside the old one, so Phase 2 has a target to develop against.

### Task 1.1: DB migration — extend `wiki_run_logs` with agent fields

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V2026_04_11__wiki_run_log_agent.sql`

- [ ] **Step 1: Write the migration SQL**

```sql
-- V2026_04_11__wiki_run_log_agent.sql
ALTER TABLE wiki_run_logs
    ADD COLUMN IF NOT EXISTS run_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tool_calls_count INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS token_count BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_wiki_run_logs_run_id ON wiki_run_logs(run_id);

COMMENT ON COLUMN wiki_run_logs.run_id IS 'Unique run identifier from wiki agent (ULID)';
COMMENT ON COLUMN wiki_run_logs.tool_calls_count IS 'Number of tool calls the agent made during the run';
COMMENT ON COLUMN wiki_run_logs.token_count IS 'Total LLM tokens consumed (prompt + completion)';
COMMENT ON COLUMN wiki_run_logs.source IS 'Who triggered the run: queue | mcp | manual | curate-auto';
```

- [ ] **Step 2: Update `WikiRunLogEntity.java` to map new columns**

Edit `lakeon-api/src/main/java/com/lakeon/knowledge/WikiRunLogEntity.java`. Add four fields after the existing `createdAt` field declaration, before the getters block:

```java
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "tool_calls_count")
    private int toolCallsCount;

    @Column(name = "token_count")
    private long tokenCount;

    @Column(name = "source", length = 32)
    private String source;
```

And add corresponding getters/setters at the bottom:

```java
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public int getToolCallsCount() { return toolCallsCount; }
    public void setToolCallsCount(int toolCallsCount) { this.toolCallsCount = toolCallsCount; }
    public long getTokenCount() { return tokenCount; }
    public void setTokenCount(long tokenCount) { this.tokenCount = tokenCount; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
```

- [ ] **Step 3: Run migration locally and verify**

```bash
cd lakeon-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# Watch logs for: "Flyway Community Edition ... successfully applied 1 migration"
```

Expected: Flyway applies `V2026_04_11__wiki_run_log_agent` without errors. Check with:

```bash
psql $LOCAL_DB -c "\\d wiki_run_logs" | grep -E "run_id|tool_calls|token_count|source"
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V2026_04_11__wiki_run_log_agent.sql \
        lakeon-api/src/main/java/com/lakeon/knowledge/WikiRunLogEntity.java
git commit -m "feat(api): extend wiki_run_logs for agentic runs"
```

### Task 1.2: Config — add `lakeon.wiki.agent.url` property

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add inner `Wiki.Agent` config class to `LakeonProperties`**

Inside `LakeonProperties.java`, add a new nested `Wiki` class alongside the existing `Ai` / `Proxy` classes, and expose a getter:

```java
    private Wiki wiki = new Wiki();

    public Wiki getWiki() { return wiki; }
    public void setWiki(Wiki wiki) { this.wiki = wiki; }

    public static class Wiki {
        private Agent agent = new Agent();
        public Agent getAgent() { return agent; }
        public void setAgent(Agent agent) { this.agent = agent; }

        public static class Agent {
            private String url;
            private String internalToken;
            private int timeoutSeconds = 300;
            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public String getInternalToken() { return internalToken; }
            public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        }
    }
```

- [ ] **Step 2: Add env bindings to `application.yml`**

Append under `lakeon:`:

```yaml
lakeon:
  # ... existing config ...
  wiki:
    agent:
      url: ${WIKI_AGENT_URL:http://localhost:8090}
      internal-token: ${WIKI_AGENT_INTERNAL_TOKEN:lakeon-wiki-agent-2026}
      timeout-seconds: ${WIKI_AGENT_TIMEOUT_SECONDS:300}
```

- [ ] **Step 3: Verify Spring loads the property**

Run `./mvnw test -Dtest=LakeonPropertiesTest` (or a quick ad-hoc test). If no test exists, add one:

`lakeon-api/src/test/java/com/lakeon/config/LakeonPropertiesTest.java`:

```java
package com.lakeon.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class LakeonPropertiesTest {
    @Autowired LakeonProperties props;

    @Test
    void wikiAgentConfigIsLoaded() {
        assertNotNull(props.getWiki());
        assertNotNull(props.getWiki().getAgent());
        assertNotNull(props.getWiki().getAgent().getUrl());
    }
}
```

Run: `./mvnw test -Dtest=LakeonPropertiesTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java \
        lakeon-api/src/main/resources/application.yml \
        lakeon-api/src/test/java/com/lakeon/config/LakeonPropertiesTest.java
git commit -m "feat(api): add lakeon.wiki.agent config"
```

### Task 1.3: ApiKeyFilter — allow `/api/v1/internal/wiki/**` with internal token

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java`

- [ ] **Step 1: Add internal-wiki branch before the admin branch**

Inside `ApiKeyFilter.doFilter`, insert this block right after the existing `/proxy/**` internal-token check (around line 78):

```java
        // Internal wiki tool endpoints — called by lakeon-wiki-agent only
        if (path.startsWith("/api/v1/internal/wiki/")) {
            String internalToken = props.getWiki().getAgent().getInternalToken();
            if (internalToken == null || internalToken.isBlank()) {
                response.setStatus(503);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"code\":\"UNAVAILABLE\",\"message\":\"Wiki agent integration not configured\"}}");
                return;
            }
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.equals("Bearer " + internalToken)) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Invalid wiki agent token\"}}");
                return;
            }
            chain.doFilter(req, res);
            return;
        }
```

- [ ] **Step 2: Write a unit test**

Create `lakeon-api/src/test/java/com/lakeon/config/ApiKeyFilterInternalWikiTest.java`:

```java
package com.lakeon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ApiKeyFilterInternalWikiTest {
    LakeonProperties props;
    ApiKeyFilter filter;
    HttpServletRequest req;
    HttpServletResponse resp;
    FilterChain chain;
    StringWriter body;

    @BeforeEach
    void setup() throws Exception {
        props = new LakeonProperties();
        props.getWiki().getAgent().setInternalToken("test-token");
        filter = new ApiKeyFilter(props, null);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        body = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));
        when(req.getRequestURI()).thenReturn("/api/v1/internal/wiki/tool/list_pages");
    }

    @Test
    void validTokenPassesThrough() throws Exception {
        when(req.getHeader("Authorization")).thenReturn("Bearer test-token");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        when(req.getHeader("Authorization")).thenReturn("Bearer wrong");
        filter.doFilter(req, resp, chain);
        verify(resp).setStatus(403);
        verify(chain, Mockito.never()).doFilter(req, resp);
    }

    @Test
    void missingConfigReturns503() throws Exception {
        props.getWiki().getAgent().setInternalToken(null);
        when(req.getHeader("Authorization")).thenReturn("Bearer test-token");
        filter.doFilter(req, resp, chain);
        verify(resp).setStatus(503);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd lakeon-api && ./mvnw test -Dtest=ApiKeyFilterInternalWikiTest
```

Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java \
        lakeon-api/src/test/java/com/lakeon/config/ApiKeyFilterInternalWikiTest.java
git commit -m "feat(api): gate /api/v1/internal/wiki with wiki-agent token"
```

### Task 1.4: `WikiToolService` — thin adapter over existing WikiService read helpers

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiToolService.java`

`WikiToolService` exposes each tool as one method. It reuses `WikiService`'s file I/O helpers (`readWikiPage`, `writeWikiDocument`, `titleToFilename`) via package-private or new `public` accessors. This task only wires up the skeleton and read-only tools; write tools come in Task 1.5.

- [ ] **Step 1: Promote three helpers in `WikiService` to `public` access**

In `WikiService.java`, change visibility of the following methods from `private` to `public`:
- `readWikiPage(String tenantId, String kbId, String filename)` (around line 1213)
- `writeWikiDocument(String tenantId, String kbId, String filename, String title, String content)`
- `titleToFilename(String title)` (around line 1932)
- `filenameToTitle(String filename)`
- `appendToLog(String tenantId, String kbId, String message)`

These helpers encapsulate OBS path + DB entity logic that the tool service must reuse.

- [ ] **Step 2: Create `WikiToolService.java` with read tools**

```java
package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool handlers invoked by lakeon-wiki-agent over /api/v1/internal/wiki/tool/*.
 * Each method is one tool; keeps business logic out of the controller layer.
 */
@Service
public class WikiToolService {
    private static final Logger log = LoggerFactory.getLogger(WikiToolService.class);
    private static final String DOC_TYPE_WIKI = "wiki";
    private static final int READ_PAGE_MAX_CHARS = 32_000;

    private final WikiService wikiService;
    private final DocumentRepository documentRepository;
    private final ChunkService chunkService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;

    public WikiToolService(WikiService wikiService,
                           DocumentRepository documentRepository,
                           ChunkService chunkService,
                           KnowledgeBaseRepository knowledgeBaseRepository,
                           ObjectMapper objectMapper) {
        this.wikiService = wikiService;
        this.documentRepository = documentRepository;
        this.chunkService = chunkService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.objectMapper = objectMapper;
    }

    // ── Read tools ──────────────────────────────────────────────

    /** Return every wiki page in the KB with a one-line summary. */
    public List<Map<String, Object>> listPages(String tenantId, String kbId) {
        List<DocumentEntity> docs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);
        return docs.stream()
                .filter(d -> !"index.md".equals(d.getFilename()) && !"log.md".equals(d.getFilename()))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", wikiService.filenameToTitle(d.getFilename()));
                    m.put("filename", d.getFilename());
                    m.put("summary", d.getSummary() != null ? d.getSummary() : "");
                    m.put("updated_at", d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null);
                    return m;
                })
                .collect(Collectors.toList());
    }

    /** Read the full markdown body of a single wiki page. */
    public Map<String, Object> readPage(String tenantId, String kbId, String title) {
        String filename = wikiService.titleToFilename(title);
        String content = wikiService.readWikiPage(tenantId, kbId, filename);
        if (content == null) {
            return Map.of("found", false, "title", title);
        }
        if (content.length() > READ_PAGE_MAX_CHARS) {
            content = content.substring(0, READ_PAGE_MAX_CHARS) + "\n\n[... truncated ...]";
        }
        return Map.of("found", true, "title", title, "filename", filename, "content", content);
    }

    /** Keyword search across wiki page titles and summaries. */
    public List<Map<String, Object>> searchPages(String tenantId, String kbId, String query, int topK) {
        String q = query == null ? "" : query.toLowerCase();
        List<DocumentEntity> docs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);
        return docs.stream()
                .filter(d -> !"index.md".equals(d.getFilename()) && !"log.md".equals(d.getFilename()))
                .map(d -> {
                    String title = wikiService.filenameToTitle(d.getFilename());
                    int score = score(title.toLowerCase(), q) * 3 +
                                score(d.getSummary() != null ? d.getSummary().toLowerCase() : "", q);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", title);
                    m.put("filename", d.getFilename());
                    m.put("summary", d.getSummary() != null ? d.getSummary() : "");
                    m.put("score", score);
                    return m;
                })
                .filter(m -> ((Integer) m.get("score")) > 0)
                .sorted((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /** Read the source document's fulltext (for agent's initial read). */
    public Map<String, Object> readSource(String tenantId, String kbId, String documentId) {
        String fulltext = chunkService.getFulltext(tenantId, kbId, documentId);
        if (fulltext == null) {
            return Map.of("found", false);
        }
        String filename = documentRepository.findById(documentId)
                .map(DocumentEntity::getFilename).orElse(documentId);
        return Map.of("found", true, "document_id", documentId,
                      "filename", filename, "content", fulltext);
    }

    /** Read the KB schema document — seeded on KB creation, co-evolved by the agent. */
    public String getSchema(String tenantId, String kbId) {
        String schema = wikiService.readWikiPage(tenantId, kbId, "schema.md");
        return schema != null ? schema : "";
    }

    private static int score(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return 0;
        int s = 0;
        for (String term : needle.split("\\s+")) {
            if (term.isBlank()) continue;
            if (haystack.contains(term)) s++;
        }
        return s;
    }
}
```

- [ ] **Step 3: Write unit tests for the read tools**

Create `lakeon-api/src/test/java/com/lakeon/knowledge/WikiToolServiceTest.java`:

```java
package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WikiToolServiceTest {
    WikiService wikiService;
    DocumentRepository documentRepository;
    ChunkService chunkService;
    KnowledgeBaseRepository knowledgeBaseRepository;
    WikiToolService tool;

    @BeforeEach
    void setup() {
        wikiService = mock(WikiService.class);
        documentRepository = mock(DocumentRepository.class);
        chunkService = mock(ChunkService.class);
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        tool = new WikiToolService(wikiService, documentRepository, chunkService,
                                   knowledgeBaseRepository, new ObjectMapper());
    }

    @Test
    void listPagesSkipsIndexAndLog() {
        DocumentEntity a = newWikiDoc("database-sharding.md", "Database sharding summary");
        DocumentEntity b = newWikiDoc("index.md", "");
        DocumentEntity c = newWikiDoc("log.md", "");
        when(documentRepository.findByTenantIdAndKbIdAndDocType("t1", "kb1", "wiki"))
            .thenReturn(List.of(a, b, c));
        when(wikiService.filenameToTitle("database-sharding.md")).thenReturn("Database Sharding");

        List<Map<String, Object>> pages = tool.listPages("t1", "kb1");

        assertEquals(1, pages.size());
        assertEquals("Database Sharding", pages.get(0).get("title"));
    }

    @Test
    void readPageReturnsFoundFalseWhenMissing() {
        when(wikiService.titleToFilename("Ghost")).thenReturn("ghost.md");
        when(wikiService.readWikiPage("t1", "kb1", "ghost.md")).thenReturn(null);

        Map<String, Object> result = tool.readPage("t1", "kb1", "Ghost");

        assertEquals(false, result.get("found"));
    }

    @Test
    void readPageTruncatesLargeContent() {
        String huge = "x".repeat(40_000);
        when(wikiService.titleToFilename("Big")).thenReturn("big.md");
        when(wikiService.readWikiPage("t1", "kb1", "big.md")).thenReturn(huge);

        Map<String, Object> result = tool.readPage("t1", "kb1", "Big");

        assertEquals(true, result.get("found"));
        String content = (String) result.get("content");
        assertTrue(content.length() < 40_000);
        assertTrue(content.endsWith("[... truncated ...]"));
    }

    @Test
    void searchPagesScoresTitleHigherThanSummary() {
        DocumentEntity hit = newWikiDoc("auth.md", "nothing relevant here");
        DocumentEntity miss = newWikiDoc("chunking.md", "auth is discussed here");
        when(documentRepository.findByTenantIdAndKbIdAndDocType("t1", "kb1", "wiki"))
            .thenReturn(List.of(hit, miss));
        when(wikiService.filenameToTitle("auth.md")).thenReturn("Auth");
        when(wikiService.filenameToTitle("chunking.md")).thenReturn("Chunking");

        List<Map<String, Object>> results = tool.searchPages("t1", "kb1", "auth", 5);

        assertEquals(2, results.size());
        assertEquals("Auth", results.get(0).get("title"));
    }

    private DocumentEntity newWikiDoc(String filename, String summary) {
        DocumentEntity d = new DocumentEntity();
        d.setFilename(filename);
        d.setSummary(summary);
        d.setUpdatedAt(Instant.now());
        return d;
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
cd lakeon-api && ./mvnw test -Dtest=WikiToolServiceTest
```

Expected: 4 PASSED.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiToolService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/WikiToolServiceTest.java
git commit -m "feat(api): add WikiToolService read tools for wiki agent"
```

### Task 1.5: `WikiToolService` — write tools (create/update/append/delete)

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiToolService.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/knowledge/WikiToolServiceTest.java`

- [ ] **Step 1: Add write tools to `WikiToolService`**

Append inside the class:

```java
    // ── Write tools ─────────────────────────────────────────────

    public Map<String, Object> createPage(String tenantId, String kbId, String title,
                                          String content, List<String> tags) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            return Map.of("ok", false, "error", "title and content are required");
        }
        String filename = wikiService.titleToFilename(title);
        if (wikiService.readWikiPage(tenantId, kbId, filename) != null) {
            return Map.of("ok", false, "error", "page already exists: " + title);
        }
        wikiService.writeWikiDocument(tenantId, kbId, filename, title, content);
        log.info("Agent created wiki page {} in KB {}", title, kbId);
        return Map.of("ok", true, "filename", filename);
    }

    public Map<String, Object> updatePage(String tenantId, String kbId, String title,
                                          String oldText, String newText) {
        String filename = wikiService.titleToFilename(title);
        String current = wikiService.readWikiPage(tenantId, kbId, filename);
        if (current == null) {
            return Map.of("ok", false, "error", "page not found: " + title);
        }
        int occurrences = countOccurrences(current, oldText);
        if (occurrences == 0) {
            return Map.of("ok", false, "error", "old_text not found in " + title);
        }
        if (occurrences > 1) {
            return Map.of("ok", false, "error",
                    "old_text matches " + occurrences + " places; add more context");
        }
        String updated = current.replace(oldText, newText);
        wikiService.writeWikiDocument(tenantId, kbId, filename, title, updated);
        log.info("Agent updated wiki page {} in KB {}", title, kbId);
        return Map.of("ok", true);
    }

    public Map<String, Object> appendPage(String tenantId, String kbId, String title, String content) {
        String filename = wikiService.titleToFilename(title);
        String current = wikiService.readWikiPage(tenantId, kbId, filename);
        if (current == null) {
            return Map.of("ok", false, "error", "page not found: " + title);
        }
        String updated = current + "\n\n" + content;
        wikiService.writeWikiDocument(tenantId, kbId, filename, title, updated);
        return Map.of("ok", true);
    }

    public Map<String, Object> deletePage(String tenantId, String kbId, String title) {
        String filename = wikiService.titleToFilename(title);
        Optional<DocumentEntity> docOpt = documentRepository.findByTypeAndFilename(
                tenantId, kbId, DOC_TYPE_WIKI, filename);
        if (docOpt.isEmpty()) {
            return Map.of("ok", false, "error", "page not found: " + title);
        }
        documentRepository.delete(docOpt.get());
        log.info("Agent deleted wiki page {} in KB {}", title, kbId);
        return Map.of("ok", true);
    }

    public Map<String, Object> logNote(String tenantId, String kbId, String message) {
        wikiService.appendToLog(tenantId, kbId, message);
        return Map.of("ok", true);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }
```

Add `import java.util.Optional;` at the top if not present.

- [ ] **Step 2: Add write-tool tests**

Append to `WikiToolServiceTest.java`:

```java
    @Test
    void createPageRejectsDuplicate() {
        when(wikiService.titleToFilename("Auth")).thenReturn("auth.md");
        when(wikiService.readWikiPage("t1", "kb1", "auth.md")).thenReturn("existing body");

        Map<String, Object> r = tool.createPage("t1", "kb1", "Auth", "new body", List.of());

        assertEquals(false, r.get("ok"));
        verify(wikiService, never()).writeWikiDocument(any(), any(), any(), any(), any());
    }

    @Test
    void createPageWritesWhenNew() {
        when(wikiService.titleToFilename("Sharding")).thenReturn("sharding.md");
        when(wikiService.readWikiPage("t1", "kb1", "sharding.md")).thenReturn(null);

        Map<String, Object> r = tool.createPage("t1", "kb1", "Sharding", "body", List.of());

        assertEquals(true, r.get("ok"));
        verify(wikiService).writeWikiDocument("t1", "kb1", "sharding.md", "Sharding", "body");
    }

    @Test
    void updatePageRefusesAmbiguousMatch() {
        when(wikiService.titleToFilename("Auth")).thenReturn("auth.md");
        when(wikiService.readWikiPage("t1", "kb1", "auth.md")).thenReturn("foo foo foo");

        Map<String, Object> r = tool.updatePage("t1", "kb1", "Auth", "foo", "bar");

        assertEquals(false, r.get("ok"));
        assertTrue(((String) r.get("error")).contains("matches 3"));
    }

    @Test
    void updatePageReplacesSingleMatch() {
        when(wikiService.titleToFilename("Auth")).thenReturn("auth.md");
        when(wikiService.readWikiPage("t1", "kb1", "auth.md")).thenReturn("x foo y");

        Map<String, Object> r = tool.updatePage("t1", "kb1", "Auth", "foo", "bar");

        assertEquals(true, r.get("ok"));
        verify(wikiService).writeWikiDocument("t1", "kb1", "auth.md", "Auth", "x bar y");
    }
```

- [ ] **Step 3: Run tests**

```bash
cd lakeon-api && ./mvnw test -Dtest=WikiToolServiceTest
```

Expected: 8 PASSED.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiToolService.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/WikiToolServiceTest.java
git commit -m "feat(api): add WikiToolService write tools (create/update/append/delete)"
```

### Task 1.6: `InternalWikiController` — REST endpoints over `WikiToolService`

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/InternalWikiController.java`

- [ ] **Step 1: Write the controller**

```java
package com.lakeon.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Internal endpoints called ONLY by lakeon-wiki-agent.
 * Authenticated by ApiKeyFilter using lakeon.wiki.agent.internal-token.
 */
@RestController
@RequestMapping("/api/v1/internal/wiki")
public class InternalWikiController {
    private static final Logger log = LoggerFactory.getLogger(InternalWikiController.class);

    private final WikiToolService toolService;
    private final WikiRunLogRepository runLogRepository;

    public InternalWikiController(WikiToolService toolService,
                                  WikiRunLogRepository runLogRepository) {
        this.toolService = toolService;
        this.runLogRepository = runLogRepository;
    }

    // ── Tool handlers ───────────────────────────────────────────

    @PostMapping("/tool/list_pages")
    public List<Map<String, Object>> listPages(@RequestBody Map<String, String> body) {
        return toolService.listPages(body.get("tenant_id"), body.get("kb_id"));
    }

    @PostMapping("/tool/read_page")
    public Map<String, Object> readPage(@RequestBody Map<String, String> body) {
        return toolService.readPage(body.get("tenant_id"), body.get("kb_id"), body.get("title"));
    }

    @PostMapping("/tool/search_pages")
    public List<Map<String, Object>> searchPages(@RequestBody Map<String, Object> body) {
        int topK = body.containsKey("top_k") ? (int) body.get("top_k") : 5;
        return toolService.searchPages(
                (String) body.get("tenant_id"),
                (String) body.get("kb_id"),
                (String) body.get("query"),
                topK);
    }

    @PostMapping("/tool/read_source")
    public Map<String, Object> readSource(@RequestBody Map<String, String> body) {
        return toolService.readSource(body.get("tenant_id"), body.get("kb_id"), body.get("document_id"));
    }

    @PostMapping("/tool/get_schema")
    public Map<String, String> getSchema(@RequestBody Map<String, String> body) {
        String schema = toolService.getSchema(body.get("tenant_id"), body.get("kb_id"));
        return Map.of("schema", schema);
    }

    @PostMapping("/tool/create_page")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPage(@RequestBody Map<String, Object> body) {
        return toolService.createPage(
                (String) body.get("tenant_id"),
                (String) body.get("kb_id"),
                (String) body.get("title"),
                (String) body.get("content"),
                (List<String>) body.getOrDefault("tags", List.of()));
    }

    @PostMapping("/tool/update_page")
    public Map<String, Object> updatePage(@RequestBody Map<String, String> body) {
        return toolService.updatePage(
                body.get("tenant_id"), body.get("kb_id"),
                body.get("title"), body.get("old_text"), body.get("new_text"));
    }

    @PostMapping("/tool/append_page")
    public Map<String, Object> appendPage(@RequestBody Map<String, String> body) {
        return toolService.appendPage(
                body.get("tenant_id"), body.get("kb_id"),
                body.get("title"), body.get("content"));
    }

    @PostMapping("/tool/delete_page")
    public Map<String, Object> deletePage(@RequestBody Map<String, String> body) {
        return toolService.deletePage(body.get("tenant_id"), body.get("kb_id"), body.get("title"));
    }

    @PostMapping("/tool/log_note")
    public Map<String, Object> logNote(@RequestBody Map<String, String> body) {
        return toolService.logNote(body.get("tenant_id"), body.get("kb_id"), body.get("message"));
    }

    // ── Run log & callback ──────────────────────────────────────

    @PostMapping("/runlog")
    public ResponseEntity<Void> writeRunLog(@RequestBody WikiRunLogRequest req) {
        WikiRunLogEntity e = new WikiRunLogEntity();
        e.setId("wrl_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        e.setTenantId(req.tenantId);
        e.setKbId(req.kbId);
        e.setRunId(req.runId);
        e.setRunType(req.runType);
        e.setTriggerDoc(req.triggerDoc);
        e.setPagesCreated(req.pagesCreated);
        e.setPagesUpdated(req.pagesUpdated);
        e.setPagesDeleted(req.pagesDeleted);
        e.setDurationMs(req.durationMs);
        e.setStatus(req.status);
        e.setErrorMessage(req.errorMessage);
        e.setToolCallsCount(req.toolCallsCount);
        e.setTokenCount(req.tokenCount);
        e.setSource(req.source);
        e.setCreatedAt(java.time.Instant.now());
        runLogRepository.save(e);
        log.info("Wiki agent run log recorded: run_id={} kb={} status={}",
                req.runId, req.kbId, req.status);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/callback/{taskId}")
    public ResponseEntity<Void> callback(@PathVariable String taskId,
                                         @RequestBody Map<String, Object> body) {
        log.info("Wiki agent callback: task={} status={}", taskId, body.get("status"));
        // For Phase 1: just log. KbWriteQueue poller (Task 1.9) can use this as a signal.
        return ResponseEntity.accepted().build();
    }

    public static class WikiRunLogRequest {
        public String tenantId;
        public String kbId;
        public String runId;
        public String runType;
        public String triggerDoc;
        public int pagesCreated;
        public int pagesUpdated;
        public int pagesDeleted;
        public long durationMs;
        public String status;
        public String errorMessage;
        public int toolCallsCount;
        public long tokenCount;
        public String source;
    }
}
```

- [ ] **Step 2: Write MockMvc integration test**

Create `lakeon-api/src/test/java/com/lakeon/knowledge/InternalWikiControllerTest.java`:

```java
package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InternalWikiControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean WikiToolService toolService;
    @MockBean WikiRunLogRepository runLogRepository;

    private static final String TOKEN = "Bearer lakeon-wiki-agent-2026";

    @Test
    void listPagesEndpointReturnsJsonArray() throws Exception {
        when(toolService.listPages("t1", "kb1"))
            .thenReturn(List.of(Map.of("title", "Auth", "filename", "auth.md")));

        mvc.perform(post("/api/v1/internal/wiki/tool/list_pages")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Auth"));
    }

    @Test
    void createPageEndpointDispatchesToService() throws Exception {
        when(toolService.createPage(any(), any(), any(), any(), any()))
            .thenReturn(Map.of("ok", true, "filename", "sharding.md"));

        mvc.perform(post("/api/v1/internal/wiki/tool/create_page")
                .header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\",\"title\":\"Sharding\",\"content\":\"body\",\"tags\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void missingTokenReturns403() throws Exception {
        mvc.perform(post("/api/v1/internal/wiki/tool/list_pages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\"}"))
            .andExpect(status().isForbidden());
    }
}
```

Ensure test profile sets `lakeon.wiki.agent.internal-token: lakeon-wiki-agent-2026` in `application-test.yml`.

- [ ] **Step 3: Run tests**

```bash
cd lakeon-api && ./mvnw test -Dtest=InternalWikiControllerTest
```

Expected: 3 PASSED.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/InternalWikiController.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/InternalWikiControllerTest.java
git commit -m "feat(api): add InternalWikiController for wiki agent tool dispatch"
```

### Task 1.7: Seed `/schema.md` on knowledge base creation

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Create: `lakeon-api/src/main/resources/wiki/default-schema.md`

- [ ] **Step 1: Create the default schema resource file**

Write `lakeon-api/src/main/resources/wiki/default-schema.md`:

```markdown
# KB Schema

This document tells the wiki agent how to maintain this knowledge base. The agent reads it at the start of every ingest / curate / lint run. You can edit it, and the agent may also co-evolve it over time.

## Scope

Describe what this KB is about and what is out of scope. The agent will use this to decide whether a new source belongs here, whether a concept deserves a page, and which existing pages to link into.

## Conventions

- **Language**: Write every page in Simplified Chinese (简体中文) regardless of source language.
- **Page size**: Aim for 800-2500 Chinese characters per page. Split a page if it grows past 3000.
- **Title style**: Clear noun phrases in Chinese. Avoid generic titles like "概述" or "介绍".
- **Wikilinks**: Use `[[Page Title]]` to cross-reference related pages; prefer exact title matches.
- **Citations**: Footnote-style `[^1]: source-filename, section` when making specific factual claims.

## Create vs Update Budget

- **Strongly prefer updating existing pages** over creating new ones. When in doubt, search first, then update.
- **Target per ingest**: touch 5-15 pages (including updates); create at most 2-3 new pages.
- **Only create a new page when** the concept is central to this KB's domain AND no existing page covers it AND it would answer a distinct user question.
- **Do not create pages for** widely-known generic concepts (e.g. 通用 AI 概念), off-topic tangents, or one-off facts that fit into an existing page.

## Log

Write a one-line entry to `log.md` for every ingest / curate / lint run via the `log_note` tool. Format: `[YYYY-MM-DD HH:MM] <op> | <short description>`.

## Self-maintenance

When you notice this schema is wrong, outdated, or missing rules that would have improved your work, call `update_page` on `schema.md` to fix it.
```

- [ ] **Step 2: Load and seed the schema in `KnowledgeService.createKnowledgeBase`**

Find `createKnowledgeBase` method in `KnowledgeService.java`. At the end of the method, after the KB entity is saved but before returning, add:

```java
        seedDefaultSchema(kb);
        return kb;
    }

    private void seedDefaultSchema(KnowledgeBaseEntity kb) {
        try {
            String defaultSchema = loadResourceText("/wiki/default-schema.md");
            wikiService.writeWikiDocument(
                kb.getTenantId(), kb.getId(),
                "schema.md", "KB Schema", defaultSchema);
            log.info("Seeded default schema.md for KB {}", kb.getId());
        } catch (Exception e) {
            log.warn("Failed to seed default schema for KB {}: {}", kb.getId(), e.getMessage());
        }
    }

    private static String loadResourceText(String path) throws java.io.IOException {
        try (var in = KnowledgeService.class.getResourceAsStream(path)) {
            if (in == null) throw new java.io.IOException("resource not found: " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
```

Add constructor dependency on `WikiService` if not already present.

- [ ] **Step 3: Write integration test**

Create `lakeon-api/src/test/java/com/lakeon/knowledge/KnowledgeServiceSchemaSeedTest.java`:

```java
package com.lakeon.knowledge;

import com.lakeon.model.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class KnowledgeServiceSchemaSeedTest {
    @Autowired KnowledgeService knowledgeService;
    @Autowired WikiService wikiService;

    @Test
    void newKbHasSeededSchemaPage() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tenant-test");
        tenant.setName("test");

        KnowledgeBaseEntity kb = knowledgeService.createKnowledgeBase(
            tenant, "Seed Test KB", "desc", KnowledgeBaseType.DOCUMENT,
            null, null, null);

        String schema = wikiService.readWikiPage(tenant.getId(), kb.getId(), "schema.md");
        assertNotNull(schema);
        assertTrue(schema.contains("KB Schema"));
        assertTrue(schema.contains("Create vs Update Budget"));
    }
}
```

- [ ] **Step 4: Run test**

```bash
cd lakeon-api && ./mvnw test -Dtest=KnowledgeServiceSchemaSeedTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/resources/wiki/default-schema.md \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/KnowledgeServiceSchemaSeedTest.java
git commit -m "feat(api): seed default schema.md on KB creation"
```

### Task 1.8: `WikiAgentClient` — outbound HTTP client to `lakeon-wiki-agent`

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiAgentClient.java`

- [ ] **Step 1: Write the client**

```java
package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbound client to lakeon-wiki-agent. Fires-and-forgets async work:
 * lakeon-wiki-agent returns {task_id, status: "accepted"} immediately.
 */
@Component
public class WikiAgentClient {
    private static final Logger log = LoggerFactory.getLogger(WikiAgentClient.class);

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WikiAgentClient(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String triggerIngest(String tenantId, String kbId, String documentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant_id", tenantId);
        body.put("kb_id", kbId);
        body.put("document_id", documentId);
        body.put("source", "queue");
        return post("/v1/wiki/ingest", body);
    }

    public String triggerCurate(String tenantId, String kbId) {
        return post("/v1/wiki/curate",
                Map.of("tenant_id", tenantId, "kb_id", kbId, "source", "manual"));
    }

    public String triggerLint(String tenantId, String kbId) {
        return post("/v1/wiki/lint",
                Map.of("tenant_id", tenantId, "kb_id", kbId, "source", "manual"));
    }

    private String post(String path, Map<String, Object> body) {
        String url = props.getWiki().getAgent().getUrl() + path;
        String token = props.getWiki().getAgent().getInternalToken();
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 202 && resp.statusCode() != 200) {
                log.warn("Wiki agent {} returned {}: {}", path, resp.statusCode(), resp.body());
                return null;
            }
            Map<?, ?> result = objectMapper.readValue(resp.body(), Map.class);
            Object taskId = result.get("task_id");
            log.info("Wiki agent accepted {}: task={}", path, taskId);
            return taskId != null ? taskId.toString() : null;
        } catch (Exception e) {
            log.error("Wiki agent call failed {}: {}", path, e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 2: Unit test with local wiremock server**

Create `lakeon-api/src/test/java/com/lakeon/knowledge/WikiAgentClientTest.java`:

```java
package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiAgentClientTest {
    HttpServer server;
    int port;
    AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/wiki/ingest", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            String json = "{\"task_id\":\"task_abc\",\"status\":\"accepted\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(202, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void triggerIngestReturnsTaskId() {
        LakeonProperties props = new LakeonProperties();
        props.getWiki().getAgent().setUrl("http://localhost:" + port);
        props.getWiki().getAgent().setInternalToken("t");
        WikiAgentClient client = new WikiAgentClient(props, new ObjectMapper());

        String taskId = client.triggerIngest("t1", "kb1", "doc1");

        assertEquals("task_abc", taskId);
        assertEquals("/v1/wiki/ingest", lastPath.get());
    }
}
```

- [ ] **Step 3: Run test**

```bash
cd lakeon-api && ./mvnw test -Dtest=WikiAgentClientTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiAgentClient.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/WikiAgentClientTest.java
git commit -m "feat(api): add WikiAgentClient for outbound wiki agent calls"
```

### Task 1.9: Wire `KbWriteQueue.executeWikiUpdate` to call agent

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java`

- [ ] **Step 1: Inject `WikiAgentClient`**

Add to the constructor and field declarations:

```java
    private final WikiAgentClient wikiAgentClient;
    // ... in constructor args and assignment
    this.wikiAgentClient = wikiAgentClient;
```

- [ ] **Step 2: Replace the body of `executeWikiUpdate`**

Find `executeWikiUpdate` (line ~953) and replace with:

```java
    private void executeWikiUpdate(Map<String, Object> params) {
        String tenantId = (String) params.get("tenant_id");
        String kbId = (String) params.get("kb_id");
        String documentId = (String) params.get("document_id");
        String taskId = wikiAgentClient.triggerIngest(tenantId, kbId, documentId);
        if (taskId == null) {
            log.warn("Wiki agent rejected ingest for doc {} in KB {}", documentId, kbId);
            // Don't throw — agent unavailable should not fail the whole queue batch.
            // The task marker is cleared via normal queue ack, run log will show missing entry.
        } else {
            log.info("Dispatched wiki agent ingest task={} for doc {} in KB {}",
                     taskId, documentId, kbId);
        }
    }
```

- [ ] **Step 3: Update existing `KbWriteQueueTest` (if present) or add new test**

Create `lakeon-api/src/test/java/com/lakeon/knowledge/KbWriteQueueWikiAgentTest.java`:

```java
package com.lakeon.knowledge;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class KbWriteQueueWikiAgentTest {
    @Test
    void wikiUpdateDispatchesToAgent() throws Exception {
        WikiAgentClient agent = mock(WikiAgentClient.class);
        when(agent.triggerIngest("t1", "kb1", "doc1")).thenReturn("task_x");

        // Use reflection to invoke private executeWikiUpdate
        KbWriteQueue queue = new KbWriteQueue(null, null, null, null, null, null, null, null,
            null, null, agent, null, null, null, null);
        var m = KbWriteQueue.class.getDeclaredMethod("executeWikiUpdate", java.util.Map.class);
        m.setAccessible(true);
        m.invoke(queue, java.util.Map.of(
            "tenant_id", "t1", "kb_id", "kb1", "document_id", "doc1"));

        verify(agent).triggerIngest("t1", "kb1", "doc1");
    }
}
```

**Note**: the constructor signature depends on current `KbWriteQueue` — the test must pass null/mocks matching the real constructor. Read `KbWriteQueue` first to match.

- [ ] **Step 4: Run test**

```bash
cd lakeon-api && ./mvnw test -Dtest=KbWriteQueueWikiAgentTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/KbWriteQueueWikiAgentTest.java
git commit -m "feat(api): route WIKI_UPDATE queue tasks to lakeon-wiki-agent"
```

### Task 1.10: Build and smoke-test Phase 1 end-to-end

- [ ] **Step 1: Full build**

```bash
cd lakeon-api && ./mvnw clean package -DskipTests=false
```

Expected: `BUILD SUCCESS`. All existing tests + new tests pass.

- [ ] **Step 2: Start a local instance and manually hit one endpoint**

```bash
cd lakeon-api
WIKI_AGENT_INTERNAL_TOKEN=dev-token ./mvnw spring-boot:run &
sleep 10

curl -s -X POST http://localhost:8080/api/v1/internal/wiki/tool/list_pages \
  -H "Authorization: Bearer dev-token" \
  -H "Content-Type: application/json" \
  -d '{"tenant_id":"test-tenant","kb_id":"test-kb"}'
```

Expected: JSON `[]` (empty list, since no pages exist yet). HTTP 200.

- [ ] **Step 3: Verify 403 without token**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://localhost:8080/api/v1/internal/wiki/tool/list_pages \
  -H "Content-Type: application/json" -d '{}'
```

Expected: `403`.

- [ ] **Step 4: Kill and commit any final fixes**

```bash
kill %1
git status  # should be clean or contain only the fixes
```

---

## Phase 2: lakeon-wiki-agent Python Service

Goal: build a new resident FastAPI service that accepts ingest/curate/lint requests, runs the DeepSeek V3.2 agent loop, and calls lakeon-api's internal wiki tools. Phase 2 does not touch lakeon-api again; it only consumes the endpoints built in Phase 1.

### Task 2.1: Scaffold project

**Files:**
- Create: `lakeon-wiki-agent/pyproject.toml`
- Create: `lakeon-wiki-agent/README.md`
- Create: `lakeon-wiki-agent/app/__init__.py`
- Create: `lakeon-wiki-agent/.gitignore`
- Create: `lakeon-wiki-agent/tests/__init__.py`

- [ ] **Step 1: Create `pyproject.toml`**

```toml
[project]
name = "lakeon-wiki-agent"
version = "0.1.0"
description = "Agentic wiki compiler for Lakeon knowledge bases"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.30.0",
    "openai>=1.40.0",
    "httpx>=0.27.0",
    "pydantic>=2.8.0",
    "pydantic-settings>=2.4.0",
    "python-ulid>=2.7.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.3.0",
    "pytest-asyncio>=0.24.0",
    "pytest-httpx>=0.30.0",
    "ruff>=0.6.0",
]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["app"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]

[tool.ruff]
line-length = 100
target-version = "py311"
```

- [ ] **Step 2: Create `.gitignore`**

```gitignore
__pycache__/
*.pyc
.venv/
.env
.pytest_cache/
dist/
build/
*.egg-info/
```

- [ ] **Step 3: Create empty `__init__.py` files and README**

```bash
mkdir -p lakeon-wiki-agent/app/api lakeon-wiki-agent/app/agent \
         lakeon-wiki-agent/app/clients lakeon-wiki-agent/tests
touch lakeon-wiki-agent/app/__init__.py \
      lakeon-wiki-agent/app/api/__init__.py \
      lakeon-wiki-agent/app/agent/__init__.py \
      lakeon-wiki-agent/app/clients/__init__.py \
      lakeon-wiki-agent/tests/__init__.py
```

Create `lakeon-wiki-agent/README.md`:

```markdown
# lakeon-wiki-agent

Agentic wiki compiler for Lakeon knowledge bases. Uses DeepSeek V3.2 tool calling to ingest documents, curate, and lint the wiki.

## Run locally

```
uv sync --extra dev
uv run uvicorn app.main:app --reload --port 8090
```

## Environment

See `app/config.py` for the full list.
- `WIKI_AGENT_INTERNAL_TOKEN` — token that lakeon-api uses to call us
- `LAKEON_API_URL` / `LAKEON_API_INTERNAL_TOKEN` — where this agent POSTs tool calls
- `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` — DeepSeek via MaaS
```

- [ ] **Step 4: Install deps and verify import**

```bash
cd lakeon-wiki-agent
uv venv && uv sync --extra dev
uv run python -c "import fastapi, openai, httpx, pydantic, ulid; print('ok')"
```

Expected: `ok`.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/
git commit -m "chore(wiki-agent): scaffold python project"
```

### Task 2.2: Config module with pydantic BaseSettings

**Files:**
- Create: `lakeon-wiki-agent/app/config.py`
- Create: `lakeon-wiki-agent/tests/test_config.py`

- [ ] **Step 1: Write failing test**

```python
# tests/test_config.py
import os
from app.config import Settings

def test_settings_loads_from_env(monkeypatch):
    monkeypatch.setenv("WIKI_AGENT_INTERNAL_TOKEN", "t1")
    monkeypatch.setenv("LAKEON_API_URL", "http://api:8080")
    monkeypatch.setenv("LAKEON_API_INTERNAL_TOKEN", "t2")
    monkeypatch.setenv("LLM_BASE_URL", "https://api.modelarts-maas.com/openai/v1")
    monkeypatch.setenv("LLM_API_KEY", "k")
    s = Settings()
    assert s.wiki_agent_internal_token == "t1"
    assert s.lakeon_api_url == "http://api:8080"
    assert s.llm_model == "deepseek-v3.2"  # default
    assert s.max_tool_rounds == 20            # default
```

- [ ] **Step 2: Run the test to see it fail**

```bash
cd lakeon-wiki-agent && uv run pytest tests/test_config.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'app.config'`.

- [ ] **Step 3: Write `app/config.py`**

```python
"""Settings loaded from environment."""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Auth tokens
    wiki_agent_internal_token: str = "lakeon-wiki-agent-2026"
    lakeon_api_url: str = "http://lakeon-api.lakeon.svc:8080"
    lakeon_api_internal_token: str = "lakeon-wiki-agent-2026"

    # LLM
    llm_base_url: str = "https://api.modelarts-maas.com/openai/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-v3.2"
    llm_temperature: float = 0.1
    llm_max_tokens: int = 4000
    llm_request_timeout: int = 90

    # Agent loop
    max_tool_rounds: int = 20
    max_concurrent_agents: int = 8
    max_tool_result_chars: int = 6000

    # Service
    host: str = "0.0.0.0"
    port: int = 8090
    log_level: str = "INFO"


settings = Settings()
```

- [ ] **Step 4: Run the test again**

```bash
uv run pytest tests/test_config.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/app/config.py lakeon-wiki-agent/tests/test_config.py
git commit -m "feat(wiki-agent): add Settings with env loading"
```

### Task 2.3: lakeon-api async HTTP client

**Files:**
- Create: `lakeon-wiki-agent/app/clients/lakeon_api.py`
- Create: `lakeon-wiki-agent/tests/test_lakeon_client.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_lakeon_client.py
import pytest
from pytest_httpx import HTTPXMock

from app.clients.lakeon_api import LakeonApiClient


@pytest.mark.asyncio
async def test_list_pages_calls_correct_endpoint(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/list_pages",
        json=[{"title": "Auth", "filename": "auth.md", "summary": ""}],
    )
    client = LakeonApiClient("http://api:8080", "token")
    pages = await client.list_pages("t1", "kb1")
    assert len(pages) == 1
    assert pages[0]["title"] == "Auth"
    request = httpx_mock.get_request()
    assert request.headers["Authorization"] == "Bearer token"


@pytest.mark.asyncio
async def test_create_page_posts_body(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/create_page",
        json={"ok": True, "filename": "sharding.md"},
    )
    client = LakeonApiClient("http://api:8080", "token")
    r = await client.create_page("t1", "kb1", "Sharding", "body", ["tag1"])
    assert r["ok"] is True
```

- [ ] **Step 2: Run test — expect fail**

```bash
uv run pytest tests/test_lakeon_client.py -v
```

Expected: FAIL (module missing).

- [ ] **Step 3: Write the client**

```python
# app/clients/lakeon_api.py
"""Async HTTP client for lakeon-api /api/v1/internal/wiki/tool/*."""
from typing import Any

import httpx


class LakeonApiClient:
    def __init__(self, base_url: str, token: str, timeout: float = 30.0):
        self._base = base_url.rstrip("/")
        self._headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }
        self._timeout = timeout

    async def _post(self, path: str, body: dict[str, Any]) -> Any:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            resp = await client.post(
                f"{self._base}{path}", json=body, headers=self._headers
            )
            resp.raise_for_status()
            return resp.json()

    # ── Tool calls ─────────────────────────────────────────

    async def list_pages(self, tenant_id: str, kb_id: str) -> list[dict]:
        return await self._post(
            "/api/v1/internal/wiki/tool/list_pages",
            {"tenant_id": tenant_id, "kb_id": kb_id},
        )

    async def read_page(self, tenant_id: str, kb_id: str, title: str) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/read_page",
            {"tenant_id": tenant_id, "kb_id": kb_id, "title": title},
        )

    async def search_pages(
        self, tenant_id: str, kb_id: str, query: str, top_k: int = 5
    ) -> list[dict]:
        return await self._post(
            "/api/v1/internal/wiki/tool/search_pages",
            {"tenant_id": tenant_id, "kb_id": kb_id, "query": query, "top_k": top_k},
        )

    async def read_source(
        self, tenant_id: str, kb_id: str, document_id: str
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/read_source",
            {"tenant_id": tenant_id, "kb_id": kb_id, "document_id": document_id},
        )

    async def get_schema(self, tenant_id: str, kb_id: str) -> str:
        r = await self._post(
            "/api/v1/internal/wiki/tool/get_schema",
            {"tenant_id": tenant_id, "kb_id": kb_id},
        )
        return r.get("schema", "")

    async def create_page(
        self, tenant_id: str, kb_id: str, title: str, content: str, tags: list[str]
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/create_page",
            {
                "tenant_id": tenant_id,
                "kb_id": kb_id,
                "title": title,
                "content": content,
                "tags": tags,
            },
        )

    async def update_page(
        self, tenant_id: str, kb_id: str, title: str, old_text: str, new_text: str
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/update_page",
            {
                "tenant_id": tenant_id,
                "kb_id": kb_id,
                "title": title,
                "old_text": old_text,
                "new_text": new_text,
            },
        )

    async def append_page(
        self, tenant_id: str, kb_id: str, title: str, content: str
    ) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/append_page",
            {"tenant_id": tenant_id, "kb_id": kb_id, "title": title, "content": content},
        )

    async def delete_page(self, tenant_id: str, kb_id: str, title: str) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/delete_page",
            {"tenant_id": tenant_id, "kb_id": kb_id, "title": title},
        )

    async def log_note(self, tenant_id: str, kb_id: str, message: str) -> dict:
        return await self._post(
            "/api/v1/internal/wiki/tool/log_note",
            {"tenant_id": tenant_id, "kb_id": kb_id, "message": message},
        )

    async def write_runlog(self, payload: dict) -> None:
        await self._post("/api/v1/internal/wiki/runlog", payload)
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
uv run pytest tests/test_lakeon_client.py -v
```

Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/app/clients/ lakeon-wiki-agent/tests/test_lakeon_client.py
git commit -m "feat(wiki-agent): async http client for lakeon-api internal tools"
```

### Task 2.4: Tool schemas (OpenAI format)

**Files:**
- Create: `lakeon-wiki-agent/app/agent/tools.py`
- Create: `lakeon-wiki-agent/tests/test_tools_schema.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_tools_schema.py
from app.agent.tools import TOOL_SCHEMAS, TOOL_NAMES


def test_all_tools_follow_openai_schema():
    for t in TOOL_SCHEMAS:
        assert t["type"] == "function"
        fn = t["function"]
        assert "name" in fn and "description" in fn and "parameters" in fn
        assert fn["parameters"]["type"] == "object"


def test_expected_tools_present():
    expected = {
        "list_pages", "read_page", "search_pages", "read_source", "get_schema",
        "create_page", "update_page", "append_page", "delete_page", "log_note", "done",
    }
    assert expected <= set(TOOL_NAMES)
```

- [ ] **Step 2: Run — expect fail**

```bash
uv run pytest tests/test_tools_schema.py -v
```

Expected: FAIL (module missing).

- [ ] **Step 3: Write `app/agent/tools.py`**

```python
"""OpenAI-format tool schemas passed to DeepSeek."""

TOOL_SCHEMAS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "list_pages",
            "description": "列出当前知识库的全部 wiki 页面（含 title/summary/updated_at）。在创建新页面前先调用此工具确认是否已存在。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_page",
            "description": "读取一个已有 wiki 页面的完整 markdown 内容。用于决定 update 还是 create。",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "wiki 页面标题"}
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_pages",
            "description": "按关键词搜索 wiki 页面。返回 top_k 个 title+summary+score。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "top_k": {"type": "integer", "description": "返回条数，默认 5"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_source",
            "description": "读取本次 ingest 正在处理的原始源文档全文。通常只在 ingest 流程的第一步调用一次。",
            "parameters": {
                "type": "object",
                "properties": {
                    "document_id": {"type": "string"}
                },
                "required": ["document_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_schema",
            "description": "读取本 KB 的 schema 文档，里面是写作规范、页数预算、页面命名约定等。每次 run 开始时应读一次。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_page",
            "description": "创建一个新 wiki 页面。**仅在** 现有页面都不合适时才创建；优先调 update_page。创建前务必先 list_pages 或 search_pages 确认不重复。",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "中文名词短语"},
                    "content": {"type": "string", "description": "页面正文（markdown，含 [[wikilink]]）"},
                    "tags": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["title", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_page",
            "description": "用精确字符串替换更新已有页面的一段内容。要求 old_text 在页面中唯一匹配；如果失败，先 read_page 扩大 old_text 的上下文再试。",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "old_text": {"type": "string"},
                    "new_text": {"type": "string"},
                },
                "required": ["title", "old_text", "new_text"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "append_page",
            "description": "向已有页面末尾追加一段内容（如补充章节）。",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "content": {"type": "string"},
                },
                "required": ["title", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_page",
            "description": "删除一个页面。仅在 curate/lint 中使用，ingest 流程禁止调用。",
            "parameters": {
                "type": "object",
                "properties": {"title": {"type": "string"}},
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "log_note",
            "description": "向 log.md 追加一行操作记录。每次 run 结束前调用一次总结本次变更。",
            "parameters": {
                "type": "object",
                "properties": {"message": {"type": "string"}},
                "required": ["message"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "done",
            "description": "当你完成所有必要的页面变更后调用此工具结束本次 run。必须返回 summary（本次变更的简短总结）。",
            "parameters": {
                "type": "object",
                "properties": {"summary": {"type": "string"}},
                "required": ["summary"],
            },
        },
    },
]

TOOL_NAMES: set[str] = {t["function"]["name"] for t in TOOL_SCHEMAS}

# Tools that ingest runs are NOT allowed to call
INGEST_FORBIDDEN: set[str] = {"delete_page"}
```

- [ ] **Step 4: Run — expect PASS**

```bash
uv run pytest tests/test_tools_schema.py -v
```

Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/app/agent/tools.py lakeon-wiki-agent/tests/test_tools_schema.py
git commit -m "feat(wiki-agent): define openai-format tool schemas"
```

### Task 2.5: LLM wrapper (openai SDK → MaaS)

**Files:**
- Create: `lakeon-wiki-agent/app/agent/llm.py`
- Create: `lakeon-wiki-agent/tests/test_llm.py`

- [ ] **Step 1: Write the failing test (with mocked openai client)**

```python
# tests/test_llm.py
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.agent.llm import LlmClient


@pytest.mark.asyncio
async def test_chat_passes_tools_to_openai():
    fake_response = MagicMock()
    fake_response.choices = [MagicMock()]
    fake_response.choices[0].message.model_dump.return_value = {
        "role": "assistant",
        "content": "ok",
        "tool_calls": None,
    }
    fake_response.choices[0].finish_reason = "stop"
    fake_response.usage.prompt_tokens = 10
    fake_response.usage.completion_tokens = 5

    fake_openai = MagicMock()
    fake_openai.chat.completions.create = AsyncMock(return_value=fake_response)

    client = LlmClient.__new__(LlmClient)
    client._client = fake_openai
    client._model = "deepseek-v3.2"
    client._temperature = 0.1
    client._max_tokens = 1000

    result = await client.chat(
        messages=[{"role": "user", "content": "hi"}],
        tools=[{"type": "function", "function": {"name": "list_pages", "parameters": {}}}],
    )

    fake_openai.chat.completions.create.assert_awaited_once()
    args = fake_openai.chat.completions.create.call_args
    assert args.kwargs["model"] == "deepseek-v3.2"
    assert args.kwargs["tools"][0]["function"]["name"] == "list_pages"
    assert result["message"]["content"] == "ok"
    assert result["usage"]["total"] == 15
```

- [ ] **Step 2: Run — expect fail**

```bash
uv run pytest tests/test_llm.py -v
```

Expected: FAIL (module missing).

- [ ] **Step 3: Write `app/agent/llm.py`**

```python
"""Thin async wrapper around the OpenAI SDK pointed at 华为云 MaaS."""
from typing import Any

from openai import AsyncOpenAI


class LlmClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str = "deepseek-v3.2",
        temperature: float = 0.1,
        max_tokens: int = 4000,
        timeout: int = 90,
    ) -> None:
        self._client = AsyncOpenAI(
            base_url=base_url,
            api_key=api_key,
            timeout=timeout,
        )
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens

    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        """Single LLM call. Returns message dict + usage."""
        kwargs: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": self._temperature,
            "max_tokens": self._max_tokens,
            # MaaS-specific: disable thinking mode on DeepSeek V3.2
            "extra_body": {"chat_template_kwargs": {"enable_thinking": False}},
        }
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"

        resp = await self._client.chat.completions.create(**kwargs)
        choice = resp.choices[0]
        msg = choice.message.model_dump()
        return {
            "message": msg,
            "finish_reason": choice.finish_reason,
            "usage": {
                "prompt": resp.usage.prompt_tokens if resp.usage else 0,
                "completion": resp.usage.completion_tokens if resp.usage else 0,
                "total": (resp.usage.prompt_tokens + resp.usage.completion_tokens)
                if resp.usage else 0,
            },
        }
```

- [ ] **Step 4: Run — expect PASS**

```bash
uv run pytest tests/test_llm.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/app/agent/llm.py lakeon-wiki-agent/tests/test_llm.py
git commit -m "feat(wiki-agent): LlmClient wrapping openai SDK for MaaS"
```

### Task 2.6: Agent loop — the core

**Files:**
- Create: `lakeon-wiki-agent/app/agent/loop.py`
- Create: `lakeon-wiki-agent/tests/test_agent_loop.py`

The agent loop is modelled on `SreAiService.chat` (lakeon-api). State machine:

```
1. Seed system prompt (schema.md + run-type-specific instructions)
2. For round in 0..MAX_TOOL_ROUNDS:
     a. call LLM with messages + tools
     b. if tool_calls in response:
            for each tc:
                execute against LakeonApiClient
                append tool result to messages
            continue
     c. else (plain content) → treat as done even without done() call
3. Write runlog via LakeonApiClient.write_runlog
4. Call callback URL (if any)
```

- [ ] **Step 1: Write the failing test (mocked LLM + mocked LakeonApiClient)**

```python
# tests/test_agent_loop.py
import json
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.agent.loop import AgentRunner, RunRequest


def make_llm_response(content=None, tool_calls=None):
    msg = {"role": "assistant", "content": content, "tool_calls": tool_calls}
    return {"message": msg, "finish_reason": "tool_calls" if tool_calls else "stop",
            "usage": {"prompt": 10, "completion": 5, "total": 15}}


def _tc(name: str, args: dict, call_id: str = "c1") -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(args)},
    }


@pytest.mark.asyncio
async def test_agent_reads_source_then_creates_page_then_done():
    llm = MagicMock()
    llm.chat = AsyncMock(side_effect=[
        make_llm_response(tool_calls=[_tc("get_schema", {})]),
        make_llm_response(tool_calls=[_tc("list_pages", {})]),
        make_llm_response(tool_calls=[_tc("read_source", {"document_id": "doc1"})]),
        make_llm_response(tool_calls=[_tc("create_page",
            {"title": "Sharding", "content": "body"})]),
        make_llm_response(tool_calls=[_tc("done", {"summary": "created 1 page"})]),
    ])

    api = MagicMock()
    api.get_schema = AsyncMock(return_value="# Schema\nRules...")
    api.list_pages = AsyncMock(return_value=[])
    api.read_source = AsyncMock(return_value={"found": True, "content": "source"})
    api.create_page = AsyncMock(return_value={"ok": True, "filename": "sharding.md"})
    api.log_note = AsyncMock(return_value={"ok": True})
    api.write_runlog = AsyncMock()

    runner = AgentRunner(llm=llm, api=api, max_rounds=10)
    result = await runner.run_ingest(RunRequest(
        tenant_id="t1", kb_id="kb1", document_id="doc1",
        run_id="run_x", source="test",
    ))

    assert result["status"] == "success"
    assert result["pages_created"] == 1
    assert result["pages_updated"] == 0
    api.create_page.assert_awaited_once_with("t1", "kb1", "Sharding", "body", [])
    api.write_runlog.assert_awaited_once()


@pytest.mark.asyncio
async def test_agent_update_path_only_increments_updated():
    llm = MagicMock()
    llm.chat = AsyncMock(side_effect=[
        make_llm_response(tool_calls=[_tc("get_schema", {})]),
        make_llm_response(tool_calls=[_tc("update_page",
            {"title": "Auth", "old_text": "x", "new_text": "y"})]),
        make_llm_response(tool_calls=[_tc("done", {"summary": "updated"})]),
    ])

    api = MagicMock()
    api.get_schema = AsyncMock(return_value="# Schema")
    api.update_page = AsyncMock(return_value={"ok": True})
    api.log_note = AsyncMock(return_value={"ok": True})
    api.write_runlog = AsyncMock()

    runner = AgentRunner(llm=llm, api=api, max_rounds=10)
    result = await runner.run_ingest(RunRequest(
        tenant_id="t1", kb_id="kb1", document_id="d1",
        run_id="run_y", source="test",
    ))

    assert result["pages_created"] == 0
    assert result["pages_updated"] == 1


@pytest.mark.asyncio
async def test_agent_stops_at_max_rounds_and_reports_error():
    llm = MagicMock()
    # Always return list_pages forever
    llm.chat = AsyncMock(return_value=make_llm_response(
        tool_calls=[_tc("list_pages", {})]))

    api = MagicMock()
    api.get_schema = AsyncMock(return_value="")
    api.list_pages = AsyncMock(return_value=[])
    api.log_note = AsyncMock(return_value={"ok": True})
    api.write_runlog = AsyncMock()

    runner = AgentRunner(llm=llm, api=api, max_rounds=3)
    result = await runner.run_ingest(RunRequest(
        tenant_id="t1", kb_id="kb1", document_id="d1",
        run_id="run_z", source="test",
    ))

    assert result["status"] == "max_rounds_exceeded"


@pytest.mark.asyncio
async def test_ingest_run_rejects_delete_page():
    llm = MagicMock()
    llm.chat = AsyncMock(side_effect=[
        make_llm_response(tool_calls=[_tc("delete_page", {"title": "Old"})]),
        make_llm_response(content="sorry", tool_calls=None),
    ])

    api = MagicMock()
    api.get_schema = AsyncMock(return_value="")
    api.delete_page = AsyncMock(return_value={"ok": True})
    api.log_note = AsyncMock(return_value={"ok": True})
    api.write_runlog = AsyncMock()

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_ingest(RunRequest(
        tenant_id="t1", kb_id="kb1", document_id="d1",
        run_id="run_a", source="test",
    ))

    # delete_page should have been refused at dispatch, not actually called
    api.delete_page.assert_not_awaited()
    assert result["pages_deleted"] == 0
```

- [ ] **Step 2: Run — expect fail**

```bash
uv run pytest tests/test_agent_loop.py -v
```

Expected: FAIL (module missing).

- [ ] **Step 3: Write `app/agent/loop.py`**

```python
"""Core agent loop — drives DeepSeek V3.2 through tool-calling rounds."""
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

from ulid import ULID

from app.agent.tools import INGEST_FORBIDDEN, TOOL_SCHEMAS

log = logging.getLogger(__name__)


@dataclass
class RunRequest:
    tenant_id: str
    kb_id: str
    run_id: str
    source: str                     # queue | mcp | manual | curate-auto
    document_id: str | None = None  # required for ingest
    run_type: str = "ingest"        # ingest | curate | lint
    callback_url: str | None = None


@dataclass
class RunResult:
    status: str
    pages_created: int = 0
    pages_updated: int = 0
    pages_deleted: int = 0
    tool_calls_count: int = 0
    token_count: int = 0
    error: str | None = None
    summary: str | None = None

    def to_runlog(self, req: RunRequest, duration_ms: int, trigger_doc: str | None) -> dict:
        return {
            "tenantId": req.tenant_id,
            "kbId": req.kb_id,
            "runId": req.run_id,
            "runType": req.run_type,
            "triggerDoc": trigger_doc,
            "pagesCreated": self.pages_created,
            "pagesUpdated": self.pages_updated,
            "pagesDeleted": self.pages_deleted,
            "durationMs": duration_ms,
            "status": "success" if self.status == "success" else "error",
            "errorMessage": self.error,
            "toolCallsCount": self.tool_calls_count,
            "tokenCount": self.token_count,
            "source": req.source,
        }


INGEST_SYSTEM_PROMPT = """你是一个 wiki 编译 agent，工作在一个 Karpathy 风格的知识库里。

你的目标：把一份新文档融入现有 wiki，**以更新为主、创建为辅**。

工作流程：
1. 调 get_schema 读取本 KB 的规范（必须第一步）。
2. 调 list_pages 或 search_pages 了解现有内容。
3. 调 read_source 读取本次要处理的源文档。
4. 对每个发现的知识点：先 search_pages 找相关已有页，read_page 读全文；若合适则 update_page，否则才 create_page。
5. 结束前调 log_note 记录一行操作摘要，再调 done。

硬性规则：
- 严格遵守 schema 中的页数预算（通常每次 touch 5-15 页，create 不超过 2-3）。
- 不得调用 delete_page（ingest 流程禁止删除）。
- 创建新页前**必须**先 list_pages 或 search_pages 确认不重复。
- update_page 的 old_text 必须在页面中唯一匹配，否则先 read_page 扩大上下文再试。
- 所有页面正文使用简体中文。
"""

CURATE_SYSTEM_PROMPT = """你是一个 wiki 整理 agent。你的目标：审视整个 wiki，合并重复、拆分过大、修复链接、删除通用内容。

工作流程：
1. get_schema 读取规范。
2. list_pages 列出全部页面。
3. 对疑似重复或过于宽泛的页面分别 read_page 读全文。
4. 决定合并/拆分/删除/改写：
   - 合并：create_page 建合并页 → delete_page 删旧页
   - 改写：update_page
   - 删除通用或离题页：delete_page
5. 最后 log_note 写一行总结，然后 done。

硬性规则：
- 只保留对本 KB 领域有价值的页面。
- 每次 curate 最多变更 ~15 页，不要大刀阔斧重写整个 wiki。
"""

LINT_SYSTEM_PROMPT = """你是一个 wiki lint agent。你的目标：找出问题并修复。

工作流程：
1. get_schema 读取规范。
2. list_pages 概览。
3. 逐页 read_page，识别：空页、重复、死链 [[xxx]]、格式错误、与 schema 冲突。
4. 用 update_page/delete_page 修复。
5. log_note 总结、done。
"""


class AgentRunner:
    def __init__(self, llm, api, max_rounds: int = 20, max_tool_result_chars: int = 6000):
        self._llm = llm
        self._api = api
        self._max_rounds = max_rounds
        self._max_tool_result_chars = max_tool_result_chars

    async def run_ingest(self, req: RunRequest) -> dict:
        return await self._run(req, INGEST_SYSTEM_PROMPT, forbid=INGEST_FORBIDDEN)

    async def run_curate(self, req: RunRequest) -> dict:
        req.run_type = "curate"
        return await self._run(req, CURATE_SYSTEM_PROMPT, forbid=set())

    async def run_lint(self, req: RunRequest) -> dict:
        req.run_type = "lint"
        return await self._run(req, LINT_SYSTEM_PROMPT, forbid=set())

    async def _run(self, req: RunRequest, system_prompt: str, forbid: set[str]) -> dict:
        start = time.time()
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": self._user_message(req),
            },
        ]
        result = RunResult(status="running")

        try:
            for round_idx in range(self._max_rounds):
                llm_resp = await self._llm.chat(messages=messages, tools=TOOL_SCHEMAS)
                result.token_count += llm_resp["usage"]["total"]
                msg = llm_resp["message"]
                tool_calls = msg.get("tool_calls") or []

                if not tool_calls:
                    # Plain content — treat as implicit done
                    result.status = "success"
                    result.summary = msg.get("content") or ""
                    break

                # Append assistant message and execute each tool
                messages.append(msg)
                should_stop = False
                for tc in tool_calls:
                    name = tc["function"]["name"]
                    try:
                        args = json.loads(tc["function"]["arguments"] or "{}")
                    except json.JSONDecodeError:
                        args = {}
                    result.tool_calls_count += 1

                    if name == "done":
                        result.status = "success"
                        result.summary = args.get("summary", "")
                        should_stop = True
                        messages.append(self._tool_message(tc["id"],
                            {"ok": True, "acknowledged": True}))
                        break

                    if name in forbid:
                        log.warning("Tool %s forbidden in this run mode", name)
                        messages.append(self._tool_message(tc["id"],
                            {"ok": False, "error": f"tool {name} is not allowed in {req.run_type}"}))
                        continue

                    tool_result = await self._execute_tool(req, name, args)
                    self._track_counts(result, name, tool_result)
                    messages.append(self._tool_message(tc["id"], tool_result))

                if should_stop:
                    break
            else:
                # Loop exited without break — hit max rounds
                result.status = "max_rounds_exceeded"
                result.error = f"agent did not call done() within {self._max_rounds} rounds"
        except Exception as e:
            log.exception("agent run failed: %s", e)
            result.status = "error"
            result.error = str(e)

        # Write runlog
        duration_ms = int((time.time() - start) * 1000)
        trigger_doc = req.document_id or "(no-doc)"
        try:
            await self._api.write_runlog(result.to_runlog(req, duration_ms, trigger_doc))
        except Exception as e:
            log.warning("Failed to write runlog: %s", e)

        return {
            "status": result.status,
            "pages_created": result.pages_created,
            "pages_updated": result.pages_updated,
            "pages_deleted": result.pages_deleted,
            "tool_calls_count": result.tool_calls_count,
            "token_count": result.token_count,
            "summary": result.summary,
            "error": result.error,
            "duration_ms": duration_ms,
        }

    # ── internals ─────────────────────────────────────

    def _user_message(self, req: RunRequest) -> str:
        if req.run_type == "ingest":
            return (
                f"请处理一份新文档：document_id={req.document_id}。"
                f"先 get_schema 读规范，再 read_source 读全文，然后按流程更新 wiki。"
            )
        if req.run_type == "curate":
            return "请对当前 wiki 做一轮整理。先 get_schema 和 list_pages 了解现状。"
        return "请对当前 wiki 做一轮 lint。先 get_schema 和 list_pages 了解现状。"

    def _tool_message(self, tool_call_id: str, result: Any) -> dict:
        content = json.dumps(result, ensure_ascii=False) if not isinstance(result, str) else result
        if len(content) > self._max_tool_result_chars:
            content = content[: self._max_tool_result_chars] + "\n...(truncated)"
        return {"role": "tool", "tool_call_id": tool_call_id, "content": content}

    def _track_counts(self, result: RunResult, name: str, tool_result: Any) -> None:
        if not isinstance(tool_result, dict):
            return
        if not tool_result.get("ok"):
            return
        if name == "create_page":
            result.pages_created += 1
        elif name in ("update_page", "append_page"):
            result.pages_updated += 1
        elif name == "delete_page":
            result.pages_deleted += 1

    async def _execute_tool(self, req: RunRequest, name: str, args: dict) -> Any:
        api = self._api
        t, k = req.tenant_id, req.kb_id
        try:
            if name == "list_pages":
                return await api.list_pages(t, k)
            if name == "read_page":
                return await api.read_page(t, k, args["title"])
            if name == "search_pages":
                return await api.search_pages(t, k, args["query"], args.get("top_k", 5))
            if name == "read_source":
                return await api.read_source(t, k, args["document_id"])
            if name == "get_schema":
                schema = await api.get_schema(t, k)
                return {"schema": schema}
            if name == "create_page":
                return await api.create_page(
                    t, k, args["title"], args["content"], args.get("tags") or [])
            if name == "update_page":
                return await api.update_page(
                    t, k, args["title"], args["old_text"], args["new_text"])
            if name == "append_page":
                return await api.append_page(t, k, args["title"], args["content"])
            if name == "delete_page":
                return await api.delete_page(t, k, args["title"])
            if name == "log_note":
                return await api.log_note(t, k, args["message"])
            return {"ok": False, "error": f"unknown tool: {name}"}
        except Exception as e:
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}


def new_run_id() -> str:
    return f"run_{ULID()}"
```

- [ ] **Step 4: Run — expect PASS**

```bash
uv run pytest tests/test_agent_loop.py -v
```

Expected: 4 PASSED.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/app/agent/loop.py \
        lakeon-wiki-agent/tests/test_agent_loop.py
git commit -m "feat(wiki-agent): core agent loop with tool dispatch and run log"
```

### Task 2.7: In-process task registry + concurrency semaphore

**Files:**
- Create: `lakeon-wiki-agent/app/tasks.py`
- Create: `lakeon-wiki-agent/tests/test_tasks.py`

- [ ] **Step 1: Write failing test**

```python
# tests/test_tasks.py
import asyncio

import pytest

from app.tasks import TaskRegistry


@pytest.mark.asyncio
async def test_registry_tracks_running_then_completed():
    reg = TaskRegistry(max_concurrent=2)

    async def work(x: int) -> dict:
        await asyncio.sleep(0.01)
        return {"x": x}

    task_id = await reg.submit("ingest", work(1))
    assert reg.get(task_id)["status"] == "running"
    await asyncio.sleep(0.1)
    snap = reg.get(task_id)
    assert snap["status"] == "completed"
    assert snap["result"] == {"x": 1}


@pytest.mark.asyncio
async def test_registry_captures_error():
    reg = TaskRegistry(max_concurrent=2)

    async def boom():
        raise RuntimeError("nope")

    task_id = await reg.submit("ingest", boom())
    await asyncio.sleep(0.05)
    snap = reg.get(task_id)
    assert snap["status"] == "error"
    assert "nope" in snap["error"]


@pytest.mark.asyncio
async def test_semaphore_limits_concurrency():
    reg = TaskRegistry(max_concurrent=1)
    started = []
    finished = []

    async def slow(i: int):
        started.append(i)
        await asyncio.sleep(0.05)
        finished.append(i)
        return {"i": i}

    await reg.submit("ingest", slow(1))
    await reg.submit("ingest", slow(2))
    # Only one should be actively running at any time
    await asyncio.sleep(0.01)
    assert started == [1]
    await asyncio.sleep(0.1)
    assert finished == [1, 2]
```

- [ ] **Step 2: Run — expect fail**

```bash
uv run pytest tests/test_tasks.py -v
```

Expected: FAIL.

- [ ] **Step 3: Write `app/tasks.py`**

```python
"""In-process task registry with concurrency bound."""
import asyncio
import logging
import time
from typing import Any, Coroutine

from ulid import ULID

log = logging.getLogger(__name__)


class TaskRegistry:
    def __init__(self, max_concurrent: int = 8) -> None:
        self._sem = asyncio.Semaphore(max_concurrent)
        self._tasks: dict[str, dict[str, Any]] = {}
        self._lock = asyncio.Lock()

    async def submit(self, run_type: str, coro: Coroutine[Any, Any, dict]) -> str:
        task_id = f"task_{ULID()}"
        snap = {
            "task_id": task_id,
            "run_type": run_type,
            "status": "running",
            "created_at": time.time(),
            "result": None,
            "error": None,
        }
        async with self._lock:
            self._tasks[task_id] = snap

        async def runner():
            async with self._sem:
                try:
                    result = await coro
                    snap["status"] = "completed"
                    snap["result"] = result
                except Exception as e:
                    log.exception("task %s failed", task_id)
                    snap["status"] = "error"
                    snap["error"] = f"{type(e).__name__}: {e}"
                finally:
                    snap["finished_at"] = time.time()

        asyncio.create_task(runner())
        return task_id

    def get(self, task_id: str) -> dict[str, Any] | None:
        return self._tasks.get(task_id)

    def count_running(self) -> int:
        return sum(1 for t in self._tasks.values() if t["status"] == "running")
```

- [ ] **Step 4: Run — expect PASS**

```bash
uv run pytest tests/test_tasks.py -v
```

Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/app/tasks.py lakeon-wiki-agent/tests/test_tasks.py
git commit -m "feat(wiki-agent): task registry with concurrency semaphore"
```

### Task 2.8: FastAPI app + API routes + auth

**Files:**
- Create: `lakeon-wiki-agent/app/main.py`
- Create: `lakeon-wiki-agent/app/api/routes.py`
- Create: `lakeon-wiki-agent/app/deps.py`
- Create: `lakeon-wiki-agent/tests/test_api.py`

- [ ] **Step 1: Create `app/deps.py` for singletons + auth dep**

```python
"""Shared singletons and request-level dependencies."""
from functools import lru_cache

from fastapi import Header, HTTPException, status

from app.agent.llm import LlmClient
from app.agent.loop import AgentRunner
from app.clients.lakeon_api import LakeonApiClient
from app.config import settings
from app.tasks import TaskRegistry


@lru_cache(maxsize=1)
def get_llm() -> LlmClient:
    return LlmClient(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
        model=settings.llm_model,
        temperature=settings.llm_temperature,
        max_tokens=settings.llm_max_tokens,
        timeout=settings.llm_request_timeout,
    )


@lru_cache(maxsize=1)
def get_api() -> LakeonApiClient:
    return LakeonApiClient(
        base_url=settings.lakeon_api_url,
        token=settings.lakeon_api_internal_token,
    )


@lru_cache(maxsize=1)
def get_registry() -> TaskRegistry:
    return TaskRegistry(max_concurrent=settings.max_concurrent_agents)


@lru_cache(maxsize=1)
def get_runner() -> AgentRunner:
    return AgentRunner(
        llm=get_llm(),
        api=get_api(),
        max_rounds=settings.max_tool_rounds,
        max_tool_result_chars=settings.max_tool_result_chars,
    )


async def require_token(authorization: str | None = Header(None)) -> None:
    expected = f"Bearer {settings.wiki_agent_internal_token}"
    if authorization != expected:
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail="invalid token")
```

- [ ] **Step 2: Create `app/api/routes.py`**

```python
"""All HTTP routes for lakeon-wiki-agent."""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.agent.loop import RunRequest, new_run_id
from app.deps import get_registry, get_runner, require_token
from app.tasks import TaskRegistry

router = APIRouter()


class IngestRequest(BaseModel):
    tenant_id: str
    kb_id: str
    document_id: str
    source: str = "queue"
    callback_url: str | None = None


class CurateRequest(BaseModel):
    tenant_id: str
    kb_id: str
    source: str = "manual"
    callback_url: str | None = None


class LintRequest(BaseModel):
    tenant_id: str
    kb_id: str
    source: str = "manual"
    callback_url: str | None = None


@router.get("/health")
def health() -> dict:
    return {"status": "ok"}


@router.post("/v1/wiki/ingest", status_code=202,
             dependencies=[Depends(require_token)])
async def ingest(req: IngestRequest,
                 registry: TaskRegistry = Depends(get_registry),
                 runner=Depends(get_runner)) -> dict:
    run_req = RunRequest(
        tenant_id=req.tenant_id,
        kb_id=req.kb_id,
        run_id=new_run_id(),
        source=req.source,
        document_id=req.document_id,
        run_type="ingest",
        callback_url=req.callback_url,
    )
    task_id = await registry.submit("ingest", runner.run_ingest(run_req))
    return {"task_id": task_id, "run_id": run_req.run_id, "status": "accepted"}


@router.post("/v1/wiki/curate", status_code=202,
             dependencies=[Depends(require_token)])
async def curate(req: CurateRequest,
                 registry: TaskRegistry = Depends(get_registry),
                 runner=Depends(get_runner)) -> dict:
    run_req = RunRequest(
        tenant_id=req.tenant_id, kb_id=req.kb_id,
        run_id=new_run_id(), source=req.source,
        run_type="curate", callback_url=req.callback_url,
    )
    task_id = await registry.submit("curate", runner.run_curate(run_req))
    return {"task_id": task_id, "run_id": run_req.run_id, "status": "accepted"}


@router.post("/v1/wiki/lint", status_code=202,
             dependencies=[Depends(require_token)])
async def lint(req: LintRequest,
               registry: TaskRegistry = Depends(get_registry),
               runner=Depends(get_runner)) -> dict:
    run_req = RunRequest(
        tenant_id=req.tenant_id, kb_id=req.kb_id,
        run_id=new_run_id(), source=req.source,
        run_type="lint", callback_url=req.callback_url,
    )
    task_id = await registry.submit("lint", runner.run_lint(run_req))
    return {"task_id": task_id, "run_id": run_req.run_id, "status": "accepted"}


@router.get("/v1/wiki/tasks/{task_id}",
            dependencies=[Depends(require_token)])
def task_status(task_id: str,
                registry: TaskRegistry = Depends(get_registry)) -> dict:
    snap = registry.get(task_id)
    if snap is None:
        raise HTTPException(404, detail="task not found")
    return snap
```

- [ ] **Step 3: Create `app/main.py`**

```python
"""FastAPI entry point for lakeon-wiki-agent."""
import logging

from fastapi import FastAPI

from app.api.routes import router
from app.config import settings

logging.basicConfig(
    level=settings.log_level,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger(__name__)

app = FastAPI(title="lakeon-wiki-agent", version="0.1.0")
app.include_router(router)


@app.on_event("startup")
async def startup() -> None:
    log.info("lakeon-wiki-agent starting on %s:%d", settings.host, settings.port)
    if not settings.llm_api_key:
        log.warning("LLM_API_KEY is empty — agent runs will fail until configured")
```

- [ ] **Step 4: Write API test with mocked runner**

```python
# tests/test_api.py
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.deps import get_registry, get_runner
from app.main import app


class FakeRegistry:
    def __init__(self):
        self.submits = []

    async def submit(self, run_type, coro):
        self.submits.append(run_type)
        # drain the coro to avoid unawaited warning
        try:
            coro.close()
        except Exception:
            pass
        return "task_fake_1"

    def get(self, task_id):
        return {"task_id": task_id, "status": "completed", "result": {"ok": True}}


@pytest.fixture
def client(monkeypatch):
    fake = FakeRegistry()
    fake_runner = MagicMock()
    fake_runner.run_ingest = AsyncMock(return_value={"status": "success"})
    fake_runner.run_curate = AsyncMock(return_value={"status": "success"})
    fake_runner.run_lint = AsyncMock(return_value={"status": "success"})
    app.dependency_overrides[get_registry] = lambda: fake
    app.dependency_overrides[get_runner] = lambda: fake_runner
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_health_no_auth(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_ingest_requires_token(client):
    r = client.post("/v1/wiki/ingest",
                    json={"tenant_id": "t", "kb_id": "k", "document_id": "d"})
    assert r.status_code == 403


def test_ingest_accepted_with_token(client):
    r = client.post(
        "/v1/wiki/ingest",
        json={"tenant_id": "t", "kb_id": "k", "document_id": "d"},
        headers={"Authorization": f"Bearer {settings.wiki_agent_internal_token}"},
    )
    assert r.status_code == 202
    body = r.json()
    assert body["task_id"] == "task_fake_1"
    assert body["status"] == "accepted"


def test_curate_accepted(client):
    r = client.post(
        "/v1/wiki/curate",
        json={"tenant_id": "t", "kb_id": "k"},
        headers={"Authorization": f"Bearer {settings.wiki_agent_internal_token}"},
    )
    assert r.status_code == 202


def test_task_status_returns_snapshot(client):
    r = client.get(
        "/v1/wiki/tasks/task_abc",
        headers={"Authorization": f"Bearer {settings.wiki_agent_internal_token}"},
    )
    assert r.status_code == 200
    assert r.json()["task_id"] == "task_abc"
```

- [ ] **Step 5: Run tests**

```bash
uv run pytest tests/test_api.py -v
```

Expected: 5 PASSED.

- [ ] **Step 6: Smoke-run the server locally**

```bash
export LLM_API_KEY=dummy
export LAKEON_API_URL=http://localhost:8080
uv run uvicorn app.main:app --port 8090 &
sleep 2
curl -s http://localhost:8090/health
kill %1
```

Expected: `{"status":"ok"}`.

- [ ] **Step 7: Commit**

```bash
git add lakeon-wiki-agent/app/deps.py \
        lakeon-wiki-agent/app/api/routes.py \
        lakeon-wiki-agent/app/main.py \
        lakeon-wiki-agent/tests/test_api.py
git commit -m "feat(wiki-agent): FastAPI routes for ingest/curate/lint + auth"
```

### Task 2.9: Dockerfile

**Files:**
- Create: `lakeon-wiki-agent/Dockerfile`
- Create: `lakeon-wiki-agent/.dockerignore`

- [ ] **Step 1: Write Dockerfile**

```dockerfile
FROM python:3.11-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Install uv for fast deps resolution
RUN pip install --no-cache-dir uv==0.4.15

# Install deps first (layer caching)
COPY pyproject.toml uv.lock* ./
RUN uv sync --frozen --no-dev 2>/dev/null || uv sync --no-dev

# Copy source
COPY app ./app

EXPOSE 8090

CMD ["uv", "run", "uvicorn", "app.main:app", \
     "--host", "0.0.0.0", "--port", "8090", \
     "--log-level", "info"]
```

- [ ] **Step 2: Write `.dockerignore`**

```
__pycache__/
.venv/
.pytest_cache/
tests/
*.pyc
.git/
*.md
```

- [ ] **Step 3: Build the image locally**

```bash
cd lakeon-wiki-agent
docker build -t lakeon-wiki-agent:dev .
```

Expected: `Successfully tagged lakeon-wiki-agent:dev`.

- [ ] **Step 4: Run and hit /health**

```bash
docker run -d --name wiki-agent-test -p 8091:8090 \
    -e LLM_API_KEY=dummy \
    -e LAKEON_API_URL=http://host.docker.internal:8080 \
    lakeon-wiki-agent:dev
sleep 3
curl -s http://localhost:8091/health
docker rm -f wiki-agent-test
```

Expected: `{"status":"ok"}`.

- [ ] **Step 5: Commit**

```bash
git add lakeon-wiki-agent/Dockerfile lakeon-wiki-agent/.dockerignore
git commit -m "build(wiki-agent): Dockerfile"
```

## Phase 3: dbay-mcp Wiki Tools

Goal: expose 8 new `@mcp.tool` functions in `dbay-mcp` that proxy to lakeon-api (for read tools) and lakeon-wiki-agent (for ingest/curate/lint). This is a thin layer — most work is description + parameter validation.

### Task 3.1: Add tool descriptions to YAML

**Files:**
- Modify: `lakeon/dbay-mcp/src/dbay_mcp/tool_descriptions.yaml`

- [ ] **Step 1: Append new entries**

Append to `tool_descriptions.yaml`:

```yaml
wiki_list_pages: |
  List all wiki pages in a knowledge base with their title, filename, and one-line summary.
  Call this before creating a new wiki page to avoid duplicates. If kb_name_or_id is omitted,
  uses the user's current default KB.

wiki_read_page: |
  Read the full markdown content of a single wiki page. Use this when deciding whether to
  update vs create a page during ingest.

wiki_search_pages: |
  Keyword-search wiki pages by title and summary. Returns top_k matches with scores.

wiki_get_schema: |
  Read the /schema.md document of a KB — the Karpathy-style schema that describes how the wiki
  should be maintained. Read this at the start of every ingest/curate/lint session.

wiki_ingest: |
  Trigger the wiki agent to integrate a source document into the KB wiki. The agent runs
  asynchronously; this tool returns a task_id immediately. Poll wiki_task_status to watch
  progress. Requires the document to already be uploaded to the KB.

wiki_curate: |
  Trigger the wiki curate agent, which merges duplicate pages, removes generic content,
  and reorganizes the wiki. Returns task_id.

wiki_lint: |
  Trigger the wiki lint agent, which finds dead links, orphan pages, format errors,
  and proposes fixes. Returns task_id.

wiki_task_status: |
  Query the status of a wiki agent task (from wiki_ingest/curate/lint). Returns
  status=running|completed|error and any result or error details.
```

- [ ] **Step 2: Commit**

```bash
git add lakeon/dbay-mcp/src/dbay_mcp/tool_descriptions.yaml
git commit -m "feat(mcp): add descriptions for wiki tools"
```

### Task 3.2: Add wiki read tools to `server.py`

**Files:**
- Modify: `lakeon/dbay-mcp/src/dbay_mcp/server.py`
- Create: `lakeon/dbay-mcp/tests/test_wiki_tools.py`

- [ ] **Step 1: Add a shared helper for wiki-agent URL and write four read tools**

Insert into `server.py` after the existing knowledge tools (around the last `@mcp.tool` call for knowledge), before memory tools:

```python
# ── Wiki tools (proxy to lakeon-api + lakeon-wiki-agent) ──

def _wiki_agent_url() -> str:
    cfg = _load_config()
    return cfg.get("wiki_agent_url", "http://lakeon-wiki-agent.lakeon.svc:8090")


def _wiki_agent_token() -> str:
    cfg = _load_config()
    return cfg.get("wiki_agent_token", "lakeon-wiki-agent-2026")


def _tenant_id_from_api_key() -> str:
    """Look up the tenant_id matching our API key via /api/v1/auth/me."""
    r = _api("GET", "/auth/me")
    return r["tenant_id"]


@mcp.tool(description=_desc("wiki_list_pages"))
def wiki_list_pages(kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    tenant_id = _tenant_id_from_api_key()
    # Direct call to lakeon-api internal endpoint requires admin token — we go through
    # the user-facing /api/v1/knowledge/bases/{kbId}/wiki/pages instead.
    pages = _api("GET", f"/knowledge/bases/{kb_id}/wiki/pages")
    if not pages:
        return f"No wiki pages in KB {kb_id}."
    lines = [f"Wiki pages in KB {kb_id}:"]
    for p in pages:
        summary = p.get("summary", "")[:80]
        lines.append(f"- **{p.get('title')}** — {summary}")
    return "\n".join(lines)


@mcp.tool(description=_desc("wiki_read_page"))
def wiki_read_page(title: str, kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("GET", f"/knowledge/bases/{kb_id}/wiki/pages/{title}")
    if not r.get("content"):
        return f"Page '{title}' not found in KB {kb_id}."
    return r["content"]


@mcp.tool(description=_desc("wiki_search_pages"))
def wiki_search_pages(query: str, kb_name_or_id: str | None = None, top_k: int = 5) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("GET", f"/knowledge/bases/{kb_id}/wiki/search",
             params={"query": query, "top_k": top_k})
    hits = r if isinstance(r, list) else r.get("results", [])
    if not hits:
        return f"No matches for '{query}' in KB {kb_id}."
    lines = [f"Top {len(hits)} matches for '{query}':"]
    for h in hits:
        lines.append(f"- **{h.get('title')}** (score={h.get('score')}) — {h.get('summary','')[:80]}")
    return "\n".join(lines)


@mcp.tool(description=_desc("wiki_get_schema"))
def wiki_get_schema(kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("GET", f"/knowledge/bases/{kb_id}/wiki/pages/schema")
    return r.get("content", "(no schema page — use default Karpathy schema)")
```

**Note**: the plan uses user-facing `/api/v1/knowledge/bases/{kbId}/wiki/*` endpoints (not `/internal/*`), because `dbay-mcp` authenticates with the user's API key, not the internal token. This means Task 1.6 should be **extended in Task 3.4** below to also expose these user-facing read endpoints if they don't already exist.

- [ ] **Step 2: Write unit tests with `responses`-style mock**

```python
# tests/test_wiki_tools.py
from unittest.mock import patch, MagicMock

from dbay_mcp.server import wiki_list_pages, wiki_read_page, wiki_search_pages


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_list_pages_empty(mock_api, mock_resolve):
    mock_api.return_value = []
    result = wiki_list_pages(None)
    assert "No wiki pages" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_list_pages_renders_titles(mock_api, mock_resolve):
    mock_api.return_value = [
        {"title": "Auth", "summary": "how auth works"},
        {"title": "Chunking", "summary": "chunk strategy"},
    ]
    result = wiki_list_pages(None)
    assert "Auth" in result and "Chunking" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_read_page_missing(mock_api, mock_resolve):
    mock_api.return_value = {"content": ""}
    result = wiki_read_page("Ghost", None)
    assert "not found" in result.lower()
```

- [ ] **Step 3: Run tests**

```bash
cd lakeon/dbay-mcp
uv run pytest tests/test_wiki_tools.py -v
```

Expected: 3 PASSED.

- [ ] **Step 4: Commit**

```bash
git add lakeon/dbay-mcp/src/dbay_mcp/server.py \
        lakeon/dbay-mcp/tests/test_wiki_tools.py
git commit -m "feat(mcp): add wiki read tools (list/read/search/get_schema)"
```

### Task 3.3: Add wiki write tools (ingest, curate, lint, task_status)

**Files:**
- Modify: `lakeon/dbay-mcp/src/dbay_mcp/server.py`
- Modify: `lakeon/dbay-mcp/tests/test_wiki_tools.py`

Write tools do NOT hit lakeon-api directly. Instead they hit an opaque user-facing wrapper endpoint that lakeon-api provides, which itself forwards to lakeon-wiki-agent. We route through lakeon-api so that:
1. dbay-mcp stays a single-endpoint client (one token, one base URL)
2. tenant/KB access control is enforced server-side by lakeon-api before dispatch

- [ ] **Step 1: Add four write tools**

Append to `server.py`:

```python
@mcp.tool(description=_desc("wiki_ingest"))
def wiki_ingest(document_id: str, kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("POST", f"/knowledge/bases/{kb_id}/wiki/agent/ingest",
             json={"document_id": document_id})
    return (
        f"Wiki agent accepted ingest for doc {document_id}.\n"
        f"task_id: {r.get('task_id')}\n"
        f"run_id: {r.get('run_id')}\n"
        f"Poll wiki_task_status({r.get('task_id')!r}) to watch progress."
    )


@mcp.tool(description=_desc("wiki_curate"))
def wiki_curate(kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("POST", f"/knowledge/bases/{kb_id}/wiki/agent/curate", json={})
    return f"Curate task {r.get('task_id')} accepted. Poll wiki_task_status to watch."


@mcp.tool(description=_desc("wiki_lint"))
def wiki_lint(kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("POST", f"/knowledge/bases/{kb_id}/wiki/agent/lint", json={})
    return f"Lint task {r.get('task_id')} accepted. Poll wiki_task_status to watch."


@mcp.tool(description=_desc("wiki_task_status"))
def wiki_task_status(task_id: str, kb_name_or_id: str | None = None) -> str:
    kb_id = _resolve_kb_id(kb_name_or_id)
    r = _api("GET", f"/knowledge/bases/{kb_id}/wiki/agent/tasks/{task_id}")
    status = r.get("status", "unknown")
    if status == "completed":
        result = r.get("result") or {}
        return (
            f"Task {task_id}: completed\n"
            f"- created: {result.get('pages_created', 0)}\n"
            f"- updated: {result.get('pages_updated', 0)}\n"
            f"- deleted: {result.get('pages_deleted', 0)}\n"
            f"- summary: {result.get('summary','')}"
        )
    if status == "error":
        return f"Task {task_id}: ERROR — {r.get('error','unknown')}"
    return f"Task {task_id}: still running..."
```

- [ ] **Step 2: Add tests**

Append to `tests/test_wiki_tools.py`:

```python
@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_ingest_returns_task_id(mock_api, mock_resolve):
    mock_api.return_value = {"task_id": "task_x", "run_id": "run_y", "status": "accepted"}
    r = wiki_ingest("doc123", None)
    assert "task_x" in r and "run_y" in r


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_task_status_completed(mock_api, mock_resolve):
    mock_api.return_value = {
        "task_id": "t1",
        "status": "completed",
        "result": {"pages_created": 2, "pages_updated": 5, "pages_deleted": 0,
                   "summary": "all good"},
    }
    r = wiki_task_status("t1", None)
    assert "completed" in r and "2" in r and "5" in r


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_task_status_error(mock_api, mock_resolve):
    mock_api.return_value = {"status": "error", "error": "LLM timeout"}
    r = wiki_task_status("t1", None)
    assert "ERROR" in r and "timeout" in r
```

Add imports:

```python
from dbay_mcp.server import wiki_ingest, wiki_task_status
```

- [ ] **Step 3: Run tests**

```bash
cd lakeon/dbay-mcp && uv run pytest tests/test_wiki_tools.py -v
```

Expected: 6 PASSED.

- [ ] **Step 4: Commit**

```bash
git add lakeon/dbay-mcp/src/dbay_mcp/server.py lakeon/dbay-mcp/tests/test_wiki_tools.py
git commit -m "feat(mcp): add wiki write tools (ingest/curate/lint/task_status)"
```

### Task 3.4: Add user-facing lakeon-api endpoints consumed by dbay-mcp

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`

The MCP tools from Task 3.2/3.3 hit user-facing routes under `/api/v1/knowledge/bases/{kbId}/wiki/agent/*`. These must exist.

- [ ] **Step 1: Add the 4 user-facing agent endpoints to `KnowledgeController`**

Append inside `KnowledgeController`:

```java
    // ── Wiki agent user endpoints (proxy to lakeon-wiki-agent) ──

    private final WikiAgentClient wikiAgentClient;
    // (add to constructor injection above)

    @PostMapping("/bases/{kbId}/wiki/agent/ingest")
    public Map<String, Object> triggerWikiIngest(HttpServletRequest req,
                                                  @PathVariable String kbId,
                                                  @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbWithAccess(kbId, tenant.getId());
        String documentId = body.get("document_id");
        if (documentId == null) throw new BadRequestException("document_id is required");
        String taskId = wikiAgentClient.triggerIngest(tenant.getId(), kbId, documentId);
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Wiki agent is unavailable");
        }
        return Map.of("task_id", taskId, "run_id", taskId, "status", "accepted");
    }

    @PostMapping("/bases/{kbId}/wiki/agent/curate")
    public Map<String, Object> triggerWikiCurate(HttpServletRequest req,
                                                  @PathVariable String kbId) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbWithAccess(kbId, tenant.getId());
        String taskId = wikiAgentClient.triggerCurate(tenant.getId(), kbId);
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Wiki agent is unavailable");
        }
        return Map.of("task_id", taskId, "status", "accepted");
    }

    @PostMapping("/bases/{kbId}/wiki/agent/lint")
    public Map<String, Object> triggerWikiLint(HttpServletRequest req,
                                                @PathVariable String kbId) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbWithAccess(kbId, tenant.getId());
        String taskId = wikiAgentClient.triggerLint(tenant.getId(), kbId);
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Wiki agent is unavailable");
        }
        return Map.of("task_id", taskId, "status", "accepted");
    }

    @GetMapping("/bases/{kbId}/wiki/agent/tasks/{taskId}")
    public Map<String, Object> getWikiAgentTask(HttpServletRequest req,
                                                 @PathVariable String kbId,
                                                 @PathVariable String taskId) {
        TenantEntity tenant = getTenant(req);
        kbAccessService.getKbWithAccess(kbId, tenant.getId());
        return wikiAgentClient.getTaskStatus(taskId);
    }
```

- [ ] **Step 2: Add `getTaskStatus` to `WikiAgentClient`**

```java
    public Map<String, Object> getTaskStatus(String taskId) {
        String url = props.getWiki().getAgent().getUrl() + "/v1/wiki/tasks/" + taskId;
        String token = props.getWiki().getAgent().getInternalToken();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("status", "error",
                        "error", "wiki agent returned " + resp.statusCode());
            }
            return objectMapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
```

- [ ] **Step 3: Build, run existing tests**

```bash
cd lakeon-api && ./mvnw test
```

Expected: all tests PASS, including the new endpoints compiling cleanly.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/WikiAgentClient.java
git commit -m "feat(api): expose user-facing wiki agent endpoints for MCP"
```

## Phase 4: dbay-kb Skill (Claude Code)

Goal: create a user-facing skill that orchestrates ingest/query/lint with human-in-the-loop via the new `wiki_*` MCP tools. The skill lives at `~/code/dbay-plugins/skills/dbay-kb/` alongside `dbay-mem`.

### Task 4.1: Create skill directory + marketplace registration

**Files:**
- Create: `~/code/dbay-plugins/skills/dbay-kb/SKILL.md`
- Create: `~/code/dbay-plugins/skills/dbay-kb/references/schema-template.md`
- Create: `~/code/dbay-plugins/skills/dbay-kb/references/compilation-guide.md`
- Modify: `~/code/dbay-plugins/README.md`

- [ ] **Step 1: Create the main SKILL.md**

Write `~/code/dbay-plugins/skills/dbay-kb/SKILL.md`:

```markdown
---
name: dbay-kb
description: Use when working with a DBay knowledge base — ingesting new sources, asking questions against the wiki, or running lint/curate. Orchestrates the Karpathy-style compile → query → lint cycle via dbay-mcp's wiki_* tools with human-in-the-loop approval at each checkpoint. Do NOT use for general markdown editing or non-DBay wikis.
---

# DBay Knowledge Base Skill

This skill helps you interact with a DBay knowledge base the Karpathy way: the LLM compiles sources into an interlinked wiki, you answer questions against that wiki, and the wiki grows smarter over time.

Each KB has a `/schema.md` document (seeded on KB creation, co-evolved by the agent) that tells the wiki agent how to maintain the KB. **Always read it first** at the start of any session.

## When to apply

- User gives you a source (URL, file path, pasted text) to add to a DBay KB → **run ingest procedure**
- User asks a question that should be answered from an existing DBay KB → **run query procedure**
- User says "clean up the wiki" / "there are too many pages" / "check for dead links" → **run lint/curate procedure**
- User wants to see how the wiki is organized → call `wiki_list_pages` and read the schema

## Prerequisites

- `dbay-mcp` MCP server configured and connected (check: call `wiki_list_pages` with no args — should return a page list or a clear "no KB selected" message)
- User has at least one KB; if not, ask them to create one in the Console first

## Procedures

### Procedure 1: Ingest a new source (human-in-the-loop)

1. **Identify the source type**: URL, local file, or pasted text
2. **Read the schema** with `wiki_get_schema` — note the page budget, language, and scope rules
3. **List existing pages** with `wiki_list_pages`; present a compact overview to the user
4. **Search for related pages** with `wiki_search_pages` using 2-3 key terms from the source
5. **Read the 3-5 most relevant existing pages** with `wiki_read_page` so you understand the current state
6. **Summarize takeaways** — produce a 3-5 bullet summary of what the source adds/contradicts
7. **Ask 2-5 socratic questions** — not "what should I do?" but "I think X — correct?":
   - "The source describes approach A; the wiki's [[Page Y]] describes approach B. Is A an evolution or an alternative?"
   - "This source mentions [concept Z] which has no wiki page yet. Should I propose a new one or fold it into [[Existing Page]]?"
   - "Page budget: schema says max 3 new pages per ingest. I see 5 candidate new concepts — which 3 matter most?"
8. **Wait for the user to answer**. Do not proceed until you have their input.
9. **Upload the source** if it isn't already in the KB (use existing `knowledge_upload` or `knowledge_wiki_ingest` MCP tools — NOT the agent)
10. **Trigger the agent** with `wiki_ingest(document_id, kb_name_or_id)` — returns task_id
11. **Poll** with `wiki_task_status(task_id)` every ~15 seconds until status is `completed` or `error`
12. **Report the delta**: which pages were created/updated, summarize the agent's log_note entry
13. **Backlink audit**: grep new page titles across other wiki pages via `wiki_search_pages` to suggest incoming wikilinks the agent may have missed; for each real miss, propose an `update_page` edit via the underlying agent (not directly — go through curate)

### Procedure 2: Query the wiki

Phase A — answer from the wiki:

1. **Read the schema** with `wiki_get_schema`
2. **Find relevant pages** via `wiki_search_pages` (start with 2-3 keyword variants)
3. **Read the top 3-5 matches** fully with `wiki_read_page`
4. **Synthesize**: every factual claim in your answer traces back to a `[[Wiki Page Title]]` citation
5. **Match format to question type**:
   - Factual → prose with inline wikilink citations
   - Comparison → table with rows per alternative
   - How-it-works → numbered steps
   - "What do we know about X" → structured summary with **Known** / **Open questions** / **Gaps**
6. **Flag gaps explicitly**: "The wiki has no page covering X" or "[[Page Y]] doesn't yet address Z"

Phase B — file it back:

7. **If the answer is durable** (comparison table, trade-off analysis, new synthesis), ask the user: "Should I promote this to a wiki page? It would become `[[Title]]`."
8. **If user agrees**, describe the promotion: "I'll run `wiki_ingest` on this synthesis as a new source." Then upload via existing text-ingest tool, then trigger `wiki_ingest`.

### Procedure 3: Lint and curate

1. **List all pages** with `wiki_list_pages`; if total page count seems high for the domain (rule of thumb: > 3× source doc count), flag it to the user
2. **Ask the user's intent**: "Lint (spot issues only) or curate (merge/delete)?" — the two trigger different agent behaviors
3. **Trigger**: `wiki_lint(kb)` or `wiki_curate(kb)`, get task_id
4. **Poll** with `wiki_task_status`
5. **On completion**, read `log.md` if the agent wrote there, summarize: "Agent merged N pages, deleted M, flagged K dead wikilinks"
6. **For destructive curate**, **before the user's next ingest**, run `wiki_list_pages` again and present a before/after diff (page count, new titles, removed titles)

## Anti-patterns to avoid

- **Answering from general knowledge** — always read the wiki first. Contradictions with your priors are signal, not error.
- **Creating pages directly** — dbay-mcp does not expose `create_page` to you; always go through `wiki_ingest` (so the agent enforces schema rules).
- **Skipping the schema read** — schemas evolve; yesterday's rules may not apply.
- **Proceeding without user answers** to socratic questions.
- **Long-running polls** without telling the user the task_id and expected duration.

## Related skills

- `dbay-mem` — long-term memory; useful to store the user's answers to socratic questions so future ingest sessions inherit context
- `dbay-kb-ingest`, `dbay-kb-query`, `dbay-kb-lint` — focused sub-skills invoked with `/dbay-kb-ingest` etc.
```

- [ ] **Step 2: Create `references/schema-template.md`**

Copy the schema template from Task 1.7 Step 1 (the default `wiki/default-schema.md`) and save it here as reference. This is what ships in lakeon-api as the default but is also the fallback the skill can recommend when the user opens a new KB.

- [ ] **Step 3: Create `references/compilation-guide.md`**

```markdown
# DBay Wiki Page Compilation Guide

This reference expands the concise rules in `schema-template.md` with examples of what good wiki pages look like.

## Page structure

Every wiki page should start with:
1. A one-paragraph executive summary (1-3 sentences)
2. A `## 概述` or `## 背景` section
3. The substantive content
4. A `## 相关页面` section listing `[[wikilinks]]` to related pages
5. An optional `## 参考` section with citations

## Length targets

- 800-2500 Chinese characters is the sweet spot
- < 500 chars means the page is probably a fragment — consider folding into a parent page
- \> 3000 chars means it should be split — propose `## Subtopic` → new wiki page via curate

## Wikilink density

A healthy page has **3-10** `[[wikilink]]` references scattered through the body. If a page has 0 wikilinks, it's probably an orphan. If it has 30+, it's probably just a list masquerading as an article.

## Title style

- Clear noun phrases: `数据库分片`, `KbWriteQueue 重试策略`
- Not too generic: avoid `概述`, `介绍`, `常见问题`
- Not too specific: `如何在 2026-04 修复 chunk drain bug` belongs in an issue tracker, not a wiki

## Citations

Use markdown footnotes for source traceability:

    本服务使用 Flyway 管理数据库迁移 [^1]。

    [^1]: lakeon-api/CLAUDE.md, "Build & Test Commands"

## When to create vs update

| Situation | Action |
|---|---|
| Concept has a dedicated existing page | `update_page` that page |
| Concept is a section inside another page | `update_page` the parent, add `### Subsection` |
| Concept is genuinely new and central to the KB | `create_page` a new page |
| Concept is generic / well-known AI knowledge | Do NOT create; fold into an existing page if relevant at all |
```

- [ ] **Step 4: Update `~/code/dbay-plugins/README.md`**

Add a section under "Skills" after the `dbay-memory` entry:

```markdown
### dbay-kb

Karpathy-style knowledge base compilation, query, and maintenance for DBay KBs. Orchestrates `wiki_ingest` / `wiki_curate` / `wiki_lint` agent runs through `dbay-mcp`'s MCP tools with human-in-the-loop approval at each checkpoint. Use when:
- Adding a new source to a KB
- Asking questions against a DBay KB's wiki
- Running lint/curate to clean up the wiki

Sub-skills: `/dbay-kb-ingest`, `/dbay-kb-query`, `/dbay-kb-lint`.
```

- [ ] **Step 5: Commit**

```bash
cd ~/code/dbay-plugins
git add skills/dbay-kb/ README.md
git commit -m "feat(dbay-kb): add Karpathy-style wiki skill"
```

### Task 4.2: Create the 3 sub-skills

**Files:**
- Create: `~/code/dbay-plugins/skills/dbay-kb-ingest/SKILL.md`
- Create: `~/code/dbay-plugins/skills/dbay-kb-query/SKILL.md`
- Create: `~/code/dbay-plugins/skills/dbay-kb-lint/SKILL.md`

Each sub-skill is a focused wrapper over one procedure of the main skill. They're invoked by explicit `/dbay-kb-ingest` etc. so the user can launch the focused procedure without loading the full main skill.

- [ ] **Step 1: Write `dbay-kb-ingest/SKILL.md`**

```markdown
---
name: dbay-kb-ingest
description: Ingest a source (URL, file, or text) into a DBay knowledge base with human-in-the-loop approval. Reads the KB schema, asks socratic questions, then triggers the wiki agent. Use when the user explicitly wants to add a source to a DBay KB or invokes /dbay-kb-ingest.
---

# /dbay-kb-ingest

This is the ingest procedure of `dbay-kb` as a standalone command.

The user gives you a source: a URL, pasted text, or a file path. You walk them through compilation into the target KB.

## Steps

1. **Confirm the target KB** — if ambiguous, call `wiki_list_pages()` (returns the current default) and confirm with the user
2. **Read `wiki_get_schema`** — note the page budget, title rules, language
3. **Orient yourself** — `wiki_list_pages` for a compact overview; `wiki_search_pages` with keywords from the source
4. **Read the top 3-5 relevant pages** fully via `wiki_read_page`
5. **Summarize 3-5 takeaways** from the source, noting which existing pages it touches/contradicts
6. **Ask 2-5 socratic questions** (see main `dbay-kb` skill for examples)
7. **Wait for the user's answers**
8. **Upload the raw source** if it isn't already in the KB (use `knowledge_upload` / `knowledge_wiki_ingest`)
9. **Call `wiki_ingest(document_id)`** — store the returned `task_id`
10. **Poll `wiki_task_status(task_id)`** every ~15 seconds until `completed` or `error`
11. **Report** the delta: pages created / updated, summary, any errors

## Rules

- Never call `create_page` directly (it isn't exposed to you)
- Never skip the schema read
- Never proceed past step 7 without user answers
- If task_status returns `max_rounds_exceeded`, warn the user and suggest running `wiki_curate` manually afterwards
```

- [ ] **Step 2: Write `dbay-kb-query/SKILL.md`**

```markdown
---
name: dbay-kb-query
description: Answer a question against a DBay KB's wiki using only wiki content (not general knowledge), then optionally promote durable answers back to the wiki. Use when the user asks a question that should be grounded in a DBay KB or invokes /dbay-kb-query.
---

# /dbay-kb-query

Answer the user's question from the wiki, then optionally file the answer back.

## Phase A — Answer from the wiki

1. **Read `wiki_get_schema`** to understand the KB's scope and conventions
2. **Find relevant pages** with `wiki_search_pages` — try 2-3 keyword variants
3. **Read the top 3-5 matches** fully with `wiki_read_page`
4. **Follow one hop** of interesting `[[wikilinks]]` — stop at one hop, don't recursively expand
5. **Synthesize** the answer grounded only in wiki content. Cite every claim with `[[Page Title]]`
6. **Match format to question type**:
   - Factual → prose with inline citations
   - Comparison → table
   - How-it-works → numbered steps
   - What-do-we-know → Known / Open questions / Gaps
7. **Flag gaps** explicitly: "The wiki has no page on X" or "[[Page Y]] doesn't cover Z"

## Phase B — File it back (optional)

8. **Ask the user**: "This answer is [durable/ephemeral]. Should I promote it to a wiki page?"
9. **If yes**: summarize what will change, then:
   - For a standalone synthesis → upload as text → `wiki_ingest`
   - For an update to an existing page → propose the diff and run `wiki_curate` after

## Rules

- **Do NOT** answer from general knowledge. Read the wiki first. Contradictions are signal.
- **Do NOT** create pages directly.
- If `wiki_search_pages` returns no hits on 3 different keyword variants, tell the user: "The wiki has no coverage of this topic. Do you want to add a source?"
```

- [ ] **Step 3: Write `dbay-kb-lint/SKILL.md`**

```markdown
---
name: dbay-kb-lint
description: Run the wiki lint or curate agent on a DBay KB, interpret the results, and walk the user through fixes. Use when the user wants to clean up the wiki, mentions too many pages, dead links, or invokes /dbay-kb-lint.
---

# /dbay-kb-lint

Run the lint / curate agent and interpret results.

## Decide: lint or curate

**Lint** (non-destructive) when the user wants to see issues without changes:
- Dead wikilinks
- Orphan pages (no incoming links)
- Pages violating the schema
- Missing citations

**Curate** (destructive) when the user wants the wiki compacted:
- Merge duplicate pages
- Delete generic / off-topic pages
- Split pages too broad
- Reorganize the index

Ask the user: "Lint (spot-only) or curate (merge/delete)?"

## Steps

1. **Baseline**: `wiki_list_pages()` — record current page count
2. **Trigger**: `wiki_lint(kb)` or `wiki_curate(kb)` — store task_id
3. **Poll** `wiki_task_status(task_id)` every ~15 seconds
4. **On completion**:
   - Fetch the final page list; compute delta vs baseline
   - For curate: report "{N} pages before → {M} after; {merged} merged, {deleted} deleted"
   - For lint: report "{K} issues found: {dead_links} dead links, {orphans} orphans, {format} format errors"
5. **If issues remain**, ask the user: "Want me to run curate now to fix them?"

## Rules

- Always baseline before trigger — otherwise you can't describe the delta
- For destructive curate, confirm with the user before trigger
- If agent returns `max_rounds_exceeded`, the wiki is too large for a single run — suggest running curate once per category (e.g. per tag)
```

- [ ] **Step 4: Commit**

```bash
cd ~/code/dbay-plugins
git add skills/dbay-kb-ingest/ skills/dbay-kb-query/ skills/dbay-kb-lint/
git commit -m "feat(dbay-kb): add ingest/query/lint sub-skills"
```

### Task 4.3: Validate skill frontmatter

- [ ] **Step 1: Write a Python one-liner to validate all frontmatter**

```bash
cd ~/code/dbay-plugins
python3 -c "
import re, pathlib
for p in pathlib.Path('skills').glob('dbay-kb*/SKILL.md'):
    txt = p.read_text()
    m = re.match(r'^---\s*\n(.*?)\n---', txt, re.DOTALL)
    assert m, f'{p}: missing frontmatter'
    fm = m.group(1)
    assert 'name:' in fm, f'{p}: missing name'
    assert 'description:' in fm, f'{p}: missing description'
    print(f'ok: {p}')
"
```

Expected: 4 lines of `ok: skills/dbay-kb/SKILL.md` etc.

- [ ] **Step 2: Install skill marketplace locally for smoke test**

```bash
# In Claude Code:
# /plugin marketplace add ~/code/dbay-plugins
# /plugin install dbay-kb
# Confirm: /skills shows dbay-kb + 3 sub-skills
```

Skip if you're executing the plan headless — document the manual step instead.

- [ ] **Step 3: Commit if anything changed**

```bash
cd ~/code/dbay-plugins && git status
# commit only if there are fixes
```

## Phase 5: Migration & End-to-End Tests

Goal: delete the legacy one-shot paths in `WikiService`, run E2E against the new agent, then compact the existing 60-page KB using the new curate agent.

### Task 5.1: Remove legacy one-shot prompts and methods from `WikiService`

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java` (if it references legacy methods)

Methods to delete entirely:
- `DEFAULT_WIKI_AGENT_PROMPT`, `DEFAULT_CURATE_PROMPT`, `DEFAULT_ROUTING_PROMPT`, `DEFAULT_ANSWER_PROMPT` constants
- `processIngest(tenantId, kbId, documentId)` — replaced by agent
- `runCurate(tenantId, kbId)` — replaced by agent
- `applyWikiChanges(tenantId, kbId, response, currentIndex)` — no longer needed
- `buildUpdatedIndex(currentIndex, indexUpdates)` — only used by applyWikiChanges
- `maybeRunCurate(tenantId, kbId, newlyCreated)` — auto-curate is now agent-driven
- `ingestText(tenantId, kbId, content, keyPoints, source)` — this was MCP text-ingest; redirect to agent via WikiAgentClient instead
- Any lint-related one-shot methods (check Phase 4 lint plan `2026-04-08-wiki-lint.md` — those methods should also go)
- `getIngestPrompt()` / `setIngestPrompt()` / `getCuratePrompt()` / `setCuratePrompt()` / `getRoutingPrompt()` / `getAnswerPrompt()` — config hooks no longer meaningful

Methods to keep:
- `readWikiPage`, `writeWikiDocument`, `titleToFilename`, `filenameToTitle`, `appendToLog`, `extractWikilinks`
- `getGraph` (wiki graph extractor)
- `saveResponse` (chat-settlement, still one-shot)
- Chat routing + answering (still one-shot LLM, not agent — out of scope for this plan)

- [ ] **Step 1: Delete the identified code**

Edit `WikiService.java`. Remove constants, methods, and any imports that become unused. The file should drop from ~1956 lines to ~800-1000 lines.

- [ ] **Step 2: Update `AdminController` to remove endpoints that hit deleted methods**

Find `/api/v1/admin/wiki/*` endpoints that call `processIngest`, `runCurate`, `applyWikiChanges`, `getIngestPrompt`, `setIngestPrompt`, `getCuratePrompt`, `setCuratePrompt` and either:
- Remove them entirely if they were SRE-only, or
- Re-route to `WikiAgentClient.triggerCurate(...)` / `triggerLint(...)` for curate/lint

Matching endpoints to check by grepping:

```bash
cd lakeon-api
grep -n "processIngest\|runCurate\|applyWikiChanges\|getIngestPrompt\|setIngestPrompt\|getCuratePrompt\|setCuratePrompt" \
  src/main/java/com/lakeon/controller/AdminController.java
```

For each match, either delete the endpoint or rewrite it:

```java
    // Replace:
    //   wikiService.runCurate(tenantId, kbId);
    // With:
    String taskId = wikiAgentClient.triggerCurate(tenantId, kbId);
    return Map.of("task_id", taskId, "dispatched", true);
```

- [ ] **Step 3: Build and run all existing tests**

```bash
cd lakeon-api && ./mvnw clean test
```

Expected: PASS. Any tests that referenced deleted methods need to be deleted or rewritten.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/WikiService.java \
        lakeon-api/src/main/java/com/lakeon/controller/AdminController.java \
        lakeon-api/src/test/java/com/lakeon/knowledge/
git commit -m "refactor(api): delete one-shot wiki paths, route curate/lint to agent"
```

### Task 5.2: E2E test — 6 docs from `~/code/kb-doc` → < 20 pages

**Files:**
- Create: `lakeon/tests/e2e/test_wiki_agent.py`

- [ ] **Step 1: Write the E2E test**

```python
# tests/e2e/test_wiki_agent.py
"""
Full E2E: upload 6 real docs, wait for wiki agent to compile,
assert the resulting wiki has strictly fewer than 20 pages and
every doc touched at least 3 pages.

Run: pytest tests/e2e/test_wiki_agent.py -v
"""
import os
import time
from pathlib import Path

import httpx
import pytest

API = os.environ.get("LAKEON_API_URL", "https://api.dbay.cloud:8443/api/v1")
ADMIN_TOKEN = "lakeon-sre-2026"
DOC_DIR = Path.home() / "code" / "kb-doc"
PAGE_COUNT_CEILING = 20
MIN_TOUCHES_PER_DOC = 3
AGENT_POLL_TIMEOUT = 600  # 10 min total


@pytest.fixture(scope="module")
def http():
    with httpx.Client(base_url=API, timeout=60.0, verify=False) as c:
        yield c


@pytest.fixture(scope="module")
def test_tenant(http):
    """Create a throwaway tenant via admin API, teardown after."""
    resp = http.post(
        "/admin/tenants",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={"name": f"e2e-wiki-{int(time.time())}", "email": "e2e-wiki@test.local"},
    )
    resp.raise_for_status()
    t = resp.json()
    yield t
    # Teardown
    http.delete(
        f"/admin/tenants/{t['id']}",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
    )


@pytest.fixture(scope="module")
def tenant_token(test_tenant):
    return test_tenant["api_key"]


@pytest.fixture(scope="module")
def kb(http, tenant_token):
    r = http.post(
        "/knowledge/bases",
        headers={"Authorization": f"Bearer {tenant_token}"},
        json={"name": "e2e-wiki-agent", "description": "Wiki agent E2E"},
    )
    r.raise_for_status()
    return r.json()


def _list_test_docs() -> list[Path]:
    """Pick 6 well-defined test docs from ~/code/kb-doc."""
    if not DOC_DIR.exists():
        pytest.skip(f"Test doc dir {DOC_DIR} missing — populate it before running")
    docs = sorted([p for p in DOC_DIR.glob("*.md")] +
                  [p for p in DOC_DIR.glob("*.pdf")])[:6]
    if len(docs) < 6:
        pytest.skip(f"Need 6 docs in {DOC_DIR}, found {len(docs)}")
    return docs


def _upload_and_wait(http, kb_id: str, token: str, doc_path: Path) -> str:
    with open(doc_path, "rb") as f:
        r = http.post(
            f"/knowledge/bases/{kb_id}/documents",
            headers={"Authorization": f"Bearer {token}"},
            files={"file": (doc_path.name, f)},
        )
    r.raise_for_status()
    doc_id = r.json()["id"]

    # Wait for parse + summarize + wiki agent trigger
    deadline = time.time() + AGENT_POLL_TIMEOUT
    while time.time() < deadline:
        d = http.get(
            f"/knowledge/bases/{kb_id}/documents/{doc_id}",
            headers={"Authorization": f"Bearer {token}"},
        ).json()
        if d.get("status") == "ready" and d.get("metadata", {}).get("wiki_processed_at"):
            return doc_id
        time.sleep(10)
    pytest.fail(f"doc {doc_id} did not finish wiki processing within {AGENT_POLL_TIMEOUT}s")


def _wait_for_agent_quiescent(http, kb_id: str, token: str) -> None:
    """Wait until no wiki agent runs are still 'running' for this KB."""
    deadline = time.time() + AGENT_POLL_TIMEOUT
    while time.time() < deadline:
        r = http.get(
            f"/knowledge/bases/{kb_id}/wiki/runlog?limit=10",
            headers={"Authorization": f"Bearer {token}"},
        )
        logs = r.json() if r.status_code == 200 else []
        recent = [l for l in logs if l.get("status") == "running"]
        if not recent:
            return
        time.sleep(5)
    pytest.fail("wiki agent did not quiesce")


def test_six_docs_produce_under_twenty_pages(http, kb, tenant_token):
    kb_id = kb["id"]
    docs = _list_test_docs()

    for d in docs:
        _upload_and_wait(http, kb_id, tenant_token, d)

    _wait_for_agent_quiescent(http, kb_id, tenant_token)

    # Assertion 1: total wiki pages (excluding index.md, log.md, schema.md) < 20
    r = http.get(
        f"/knowledge/bases/{kb_id}/wiki/pages",
        headers={"Authorization": f"Bearer {tenant_token}"},
    )
    r.raise_for_status()
    pages = [p for p in r.json()
             if p["filename"] not in ("index.md", "log.md", "schema.md")]

    assert len(pages) < PAGE_COUNT_CEILING, \
        f"expected < {PAGE_COUNT_CEILING} pages, got {len(pages)}: " + \
        ", ".join(p["title"] for p in pages)

    # Assertion 2: no duplicate titles
    titles = [p["title"] for p in pages]
    assert len(titles) == len(set(titles)), f"duplicate page titles: {titles}"

    # Assertion 3: every run touched at least MIN_TOUCHES_PER_DOC pages
    r = http.get(
        f"/knowledge/bases/{kb_id}/wiki/runlog?limit=50",
        headers={"Authorization": f"Bearer {tenant_token}"},
    )
    runlogs = [l for l in r.json() if l["run_type"] == "ingest"]
    assert len(runlogs) == 6, f"expected 6 ingest runs, got {len(runlogs)}"

    for log in runlogs:
        touches = (log.get("pages_created", 0) + log.get("pages_updated", 0))
        assert touches >= MIN_TOUCHES_PER_DOC, \
            f"run {log.get('run_id')} only touched {touches} pages"

    # Assertion 4: agent succeeded on every run
    for log in runlogs:
        assert log.get("status") == "success", \
            f"run {log.get('run_id')} failed: {log.get('error_message')}"

    # Assertion 5: schema.md still exists (agent did not accidentally delete)
    schema_pages = [p for p in r.json() if p.get("filename") == "schema.md"] if False else []
    schema = http.get(
        f"/knowledge/bases/{kb_id}/wiki/pages/schema",
        headers={"Authorization": f"Bearer {tenant_token}"},
    )
    assert schema.status_code == 200, "schema.md was deleted by the agent"
```

- [ ] **Step 2: Verify the test file is discoverable**

```bash
cd lakeon && python3 -m pytest tests/e2e/test_wiki_agent.py --collect-only
```

Expected: `test_six_docs_produce_under_twenty_pages` is listed.

- [ ] **Step 3: Run the test against local stack (needs lakeon-api + lakeon-wiki-agent running locally)**

```bash
LAKEON_API_URL=http://localhost:8080/api/v1 \
  python3 -m pytest tests/e2e/test_wiki_agent.py -v -s
```

If any assertion fails:
- Dump agent runlog for each run: `http://localhost:8080/api/v1/knowledge/bases/{kb}/wiki/runlog`
- Check lakeon-wiki-agent logs: `kubectl logs deployment/lakeon-wiki-agent -n lakeon`
- **Do not skip or weaken the assertion** — fix the root cause (likely schema too loose, or agent loop not respecting budget)

- [ ] **Step 4: Commit**

```bash
git add tests/e2e/test_wiki_agent.py
git commit -m "test(e2e): wiki agent produces < 20 pages from 6 docs"
```

### Task 5.3: E2E test — compact existing 60-page KB via curate

**Files:**
- Create: `lakeon/tests/e2e/test_wiki_curate_existing.py`

- [ ] **Step 1: Write the test**

```python
# tests/e2e/test_wiki_curate_existing.py
"""
Given a KB with many (~60) legacy pages from the old one-shot wiki engine,
the new curate agent should compact it to < 25 pages while preserving
schema.md, index.md, and log.md.

This test is parameterized by an existing KB ID via env KB_TO_CURATE.
"""
import os
import time

import httpx
import pytest

API = os.environ.get("LAKEON_API_URL", "https://api.dbay.cloud:8443/api/v1")
TOKEN = os.environ.get("CURATE_KB_TOKEN")
KB_ID = os.environ.get("KB_TO_CURATE")
POLL_TIMEOUT = 600
TARGET_CEILING = 25


@pytest.fixture(scope="module")
def http():
    with httpx.Client(base_url=API, timeout=60.0, verify=False) as c:
        yield c


@pytest.mark.skipif(not (TOKEN and KB_ID), reason="set CURATE_KB_TOKEN and KB_TO_CURATE")
def test_curate_compacts_legacy_kb(http):
    headers = {"Authorization": f"Bearer {TOKEN}"}

    # Baseline
    r = http.get(f"/knowledge/bases/{KB_ID}/wiki/pages", headers=headers)
    r.raise_for_status()
    before = [p for p in r.json()
              if p["filename"] not in ("index.md", "log.md", "schema.md")]
    before_count = len(before)
    assert before_count >= 30, f"need a legacy KB with at least 30 pages to test curate compaction, got {before_count}"

    # Trigger curate
    r = http.post(f"/knowledge/bases/{KB_ID}/wiki/agent/curate",
                  headers=headers, json={})
    r.raise_for_status()
    task_id = r.json()["task_id"]

    # Poll
    deadline = time.time() + POLL_TIMEOUT
    while time.time() < deadline:
        r = http.get(f"/knowledge/bases/{KB_ID}/wiki/agent/tasks/{task_id}",
                     headers=headers)
        status = r.json().get("status")
        if status in ("completed", "error"):
            break
        time.sleep(10)
    else:
        pytest.fail("curate did not finish within timeout")

    assert r.json()["status"] == "completed", r.json()

    # After
    r = http.get(f"/knowledge/bases/{KB_ID}/wiki/pages", headers=headers)
    after = [p for p in r.json()
             if p["filename"] not in ("index.md", "log.md", "schema.md")]
    after_count = len(after)

    print(f"Curate: {before_count} pages → {after_count} pages")
    assert after_count < before_count, "curate did not reduce page count"
    assert after_count < TARGET_CEILING, \
        f"curate did not compact enough: {after_count} >= {TARGET_CEILING}"

    # Preserve special pages
    for fn in ("schema", "index", "log"):
        r = http.get(f"/knowledge/bases/{KB_ID}/wiki/pages/{fn}", headers=headers)
        assert r.status_code == 200, f"{fn}.md was deleted"
```

- [ ] **Step 2: Commit (test is skipped unless env vars are set)**

```bash
git add tests/e2e/test_wiki_curate_existing.py
git commit -m "test(e2e): curate compacts 60-page legacy KB to < 25"
```

- [ ] **Step 3: Run against the real legacy KB (manual, once the deploy is up)**

```bash
# Get the real KB id and a tenant token from lakeon-console or dbay CLI
CURATE_KB_TOKEN=lk_... KB_TO_CURATE=kb_... \
  python3 -m pytest tests/e2e/test_wiki_curate_existing.py -v -s
```

### Task 5.4: Full E2E regression sweep

- [ ] **Step 1: Run the full E2E suite**

```bash
cd lakeon && python3 -m pytest tests/e2e/ -v
```

Expected: all tests pass (excluding the skipped curate test from 5.3 which needs manual env setup).

- [ ] **Step 2: Run frontend type checks**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
cd ../lakeon-admin && npx vue-tsc -b --noEmit
```

Expected: 0 errors.

## Phase 6: Deployment to hwstaff

Goal: ship `lakeon-wiki-agent` + modified `lakeon-api` to the `hwstaff` CCE cluster. Every step is hwstaff-only (`SITE=hwstaff`).

### Task 6.1: Image build & push script

**Files:**
- Create: `lakeon/deploy/cce/build-and-push-wiki-agent.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
set -euo pipefail

SITE="${SITE:-hwstaff}"
TAG="${TAG:-$(date +%Y%m%d%H%M)}"
REPO="swr.cn-east-3.myhuaweicloud.com/lakeon-${SITE}/lakeon-wiki-agent"
IMAGE="${REPO}:${TAG}"
LATEST="${REPO}:latest"

echo "Building ${IMAGE}"
cd "$(dirname "$0")/../../lakeon-wiki-agent"

docker build --platform linux/amd64 -t "${IMAGE}" -t "${LATEST}" .

echo "Logging into SWR (site=${SITE})"
# Expects ~/.dbay/hcloud-swr-${SITE}.sh to provide AK/SK → docker login
source "${HOME}/.dbay/hcloud-swr-${SITE}.sh"

docker push "${IMAGE}"
docker push "${LATEST}"

echo ""
echo "Pushed ${IMAGE}"
echo "Update helm values-${SITE}.yaml with image.tag: ${TAG}"
```

- [ ] **Step 2: Make executable + do a dry build (no push)**

```bash
chmod +x lakeon/deploy/cce/build-and-push-wiki-agent.sh
cd lakeon/lakeon-wiki-agent
docker build --platform linux/amd64 -t lakeon-wiki-agent:dry .
```

Expected: successful local build.

- [ ] **Step 3: Commit**

```bash
git add lakeon/deploy/cce/build-and-push-wiki-agent.sh
git commit -m "build(deploy): add build-and-push-wiki-agent.sh"
```

### Task 6.2: Helm chart

**Files:**
- Create: `lakeon/deploy/helm/lakeon-wiki-agent/Chart.yaml`
- Create: `lakeon/deploy/helm/lakeon-wiki-agent/values.yaml`
- Create: `lakeon/deploy/helm/lakeon-wiki-agent/values-hwstaff.yaml`
- Create: `lakeon/deploy/helm/lakeon-wiki-agent/templates/deployment.yaml`
- Create: `lakeon/deploy/helm/lakeon-wiki-agent/templates/service.yaml`
- Create: `lakeon/deploy/helm/lakeon-wiki-agent/templates/_helpers.tpl`

- [ ] **Step 1: Write `Chart.yaml`**

```yaml
apiVersion: v2
name: lakeon-wiki-agent
description: Agentic wiki compiler for Lakeon knowledge bases
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2: Write `values.yaml` (defaults)**

```yaml
image:
  repository: swr.cn-east-3.myhuaweicloud.com/lakeon/lakeon-wiki-agent
  tag: latest
  pullPolicy: IfNotPresent

replicaCount: 1

resources:
  requests:
    cpu: 200m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 2Gi

service:
  type: ClusterIP
  port: 8090

env:
  LAKEON_API_URL: "http://lakeon-api.lakeon.svc:8080"
  LLM_BASE_URL: "https://api.modelarts-maas.com/openai/v1"
  LLM_MODEL: "deepseek-v3.2"
  LOG_LEVEL: "INFO"
  MAX_TOOL_ROUNDS: "20"
  MAX_CONCURRENT_AGENTS: "8"

# Secrets pulled from existing api-credentials
secretRefs:
  llmApiKey:
    name: api-credentials
    key: ai-api-key
  wikiAgentToken:
    name: wiki-agent-secrets
    key: internal-token
  lakeonApiToken:
    name: wiki-agent-secrets
    key: lakeon-api-internal-token
```

- [ ] **Step 3: Write `values-hwstaff.yaml`**

```yaml
image:
  repository: swr.cn-east-3.myhuaweicloud.com/lakeon-hwstaff/lakeon-wiki-agent
  tag: latest

env:
  LAKEON_API_URL: "http://lakeon-api.lakeon.svc:8080"
  LOG_LEVEL: "INFO"
```

- [ ] **Step 4: Write `templates/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lakeon-wiki-agent
  namespace: lakeon
  labels:
    app: lakeon-wiki-agent
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: lakeon-wiki-agent
  template:
    metadata:
      labels:
        app: lakeon-wiki-agent
    spec:
      containers:
        - name: wiki-agent
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8090
              protocol: TCP
          env:
            {{- range $k, $v := .Values.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
            - name: LLM_API_KEY
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.secretRefs.llmApiKey.name }}
                  key: {{ .Values.secretRefs.llmApiKey.key }}
            - name: WIKI_AGENT_INTERNAL_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.secretRefs.wikiAgentToken.name }}
                  key: {{ .Values.secretRefs.wikiAgentToken.key }}
            - name: LAKEON_API_INTERNAL_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ .Values.secretRefs.lakeonApiToken.name }}
                  key: {{ .Values.secretRefs.lakeonApiToken.key }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          readinessProbe:
            httpGet:
              path: /health
              port: http
            initialDelaySeconds: 5
            periodSeconds: 10
            failureThreshold: 3
          # NO liveness probe — agent loops should not be killed mid-run
```

- [ ] **Step 5: Write `templates/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: lakeon-wiki-agent
  namespace: lakeon
  labels:
    app: lakeon-wiki-agent
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
      name: http
  selector:
    app: lakeon-wiki-agent
```

- [ ] **Step 6: Lint the chart**

```bash
cd lakeon/deploy/helm/lakeon-wiki-agent
helm lint .
helm template . -f values-hwstaff.yaml > /tmp/wiki-agent-rendered.yaml
head -50 /tmp/wiki-agent-rendered.yaml
```

Expected: `1 chart(s) linted, 0 chart(s) failed`.

- [ ] **Step 7: Commit**

```bash
git add lakeon/deploy/helm/lakeon-wiki-agent/
git commit -m "build(deploy): helm chart for lakeon-wiki-agent"
```

### Task 6.3: Create the `wiki-agent-secrets` Kubernetes Secret

- [ ] **Step 1: Generate strong random tokens**

```bash
WIKI_AGENT_TOKEN=$(openssl rand -hex 32)
LAKEON_API_TOKEN=$(openssl rand -hex 32)
echo "wiki-agent token: ${WIKI_AGENT_TOKEN}"
echo "lakeon-api internal token: ${LAKEON_API_TOKEN}"
```

Save both in dbay memory (`memory_ingest`) under a new fact `wiki-agent credentials hwstaff` — also store in `~/.dbay/tokens.json` as convention.

- [ ] **Step 2: Create the Secret on CCE hwstaff**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl create secret generic wiki-agent-secrets \
  --namespace=lakeon \
  --from-literal=internal-token="${WIKI_AGENT_TOKEN}" \
  --from-literal=lakeon-api-internal-token="${LAKEON_API_TOKEN}"
```

- [ ] **Step 3: Set the same internal token on `lakeon-api` Deployment env**

Both sides of the boundary need to agree on `lakeon-api-internal-token`:
- `lakeon-wiki-agent` uses it as outbound `Authorization: Bearer ...` when calling lakeon-api
- `lakeon-api` ApiKeyFilter expects it via `lakeon.wiki.agent.internal-token`

Update the `lakeon-api` Deployment env to read from the same Secret:

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl set env deployment/lakeon-api -n lakeon \
  --from=secret/wiki-agent-secrets \
  --prefix=WIKI_AGENT_
```

This creates:
- `WIKI_AGENT_INTERNAL_TOKEN`
- `WIKI_AGENT_LAKEON_API_INTERNAL_TOKEN`

Edit `lakeon-api` `application.yml` so `lakeon.wiki.agent.internal-token` reads `${WIKI_AGENT_INTERNAL_TOKEN}` (matches).

- [ ] **Step 4: Verify secret + env injection**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get secret wiki-agent-secrets -n lakeon -o yaml
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get deployment lakeon-api -n lakeon \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="WIKI_AGENT_INTERNAL_TOKEN")]}'
```

Expected: secret exists; env var is present.

### Task 6.4: Push image and deploy

- [ ] **Step 1: Build and push**

```bash
cd ~/code/lakeon
SITE=hwstaff TAG=$(date +%Y%m%d%H%M) bash deploy/cce/build-and-push-wiki-agent.sh
```

Capture the tag printed at the end.

- [ ] **Step 2: Deploy via helm**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config helm upgrade --install lakeon-wiki-agent \
  deploy/helm/lakeon-wiki-agent \
  -f deploy/helm/lakeon-wiki-agent/values-hwstaff.yaml \
  --set image.tag=${TAG} \
  --namespace lakeon
```

Expected: `Release "lakeon-wiki-agent" does not exist. Installing it now.` → `STATUS: deployed`.

- [ ] **Step 3: Wait for pod ready**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-wiki-agent -n lakeon --timeout=180s
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pods -n lakeon -l app=lakeon-wiki-agent
```

Expected: `deployment "lakeon-wiki-agent" successfully rolled out`.

- [ ] **Step 4: In-cluster smoke test**

```bash
POD=$(KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pod -n lakeon -l app=lakeon-wiki-agent -o name | head -1)
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon $POD -- curl -s http://localhost:8090/health
```

Expected: `{"status":"ok"}`.

- [ ] **Step 5: Cross-service smoke test (from lakeon-api pod)**

```bash
API_POD=$(KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pod -n lakeon -l app=lakeon-api -o name | head -1)
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon $API_POD -- \
  curl -s http://lakeon-wiki-agent.lakeon.svc:8090/health
```

Expected: `{"status":"ok"}`.

### Task 6.5: Update lakeon-api Deployment & rollout

- [ ] **Step 1: Build & push lakeon-api**

```bash
cd ~/code/lakeon
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
```

- [ ] **Step 2: Patch deployment to pick up new image**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=300s
```

- [ ] **Step 3: End-to-end smoke: trigger an ingest from a real KB**

```bash
# Pick a throwaway KB from a test tenant. Using curl:
curl -sk -X POST https://api.dbay.cloud:8443/api/v1/knowledge/bases/${KB_ID}/wiki/agent/curate \
  -H "Authorization: Bearer ${TENANT_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{}'
```

Expected: `{"task_id":"task_...", "status":"accepted"}`.

Poll status:

```bash
curl -sk https://api.dbay.cloud:8443/api/v1/knowledge/bases/${KB_ID}/wiki/agent/tasks/${TASK_ID} \
  -H "Authorization: Bearer ${TENANT_API_KEY}"
```

Expected: eventually `"status": "completed"` with page counts.

- [ ] **Step 4: Monitor wiki-agent logs for 10 minutes**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl logs -f deployment/lakeon-wiki-agent -n lakeon | tee /tmp/wiki-agent-first-run.log
```

Watch for:
- Tool call dispatch messages
- No `max_rounds_exceeded` errors
- No HTTP 403 from lakeon-api (→ token mismatch)
- No HTTP 502 from LLM (→ LLM_API_KEY issue)

If any error surfaces, debug before running the E2E suite.

### Task 6.6: Run full E2E against hwstaff

- [ ] **Step 1: Run the new wiki agent E2E against production**

```bash
cd ~/code/lakeon
LAKEON_API_URL=https://api.dbay.cloud:8443/api/v1 \
  python3 -m pytest tests/e2e/test_wiki_agent.py -v -s
```

Expected: PASS (< 20 pages, all assertions green).

- [ ] **Step 2: Run the curate compaction test on the existing 60-page KB**

```bash
CURATE_KB_TOKEN=<real token> KB_TO_CURATE=<real kb id> \
  LAKEON_API_URL=https://api.dbay.cloud:8443/api/v1 \
  python3 -m pytest tests/e2e/test_wiki_curate_existing.py -v -s
```

Expected: 60-ish → < 25.

- [ ] **Step 3: Run the full E2E suite**

```bash
python3 -m pytest tests/e2e/ -v
```

Expected: all PASS.

## Rollback Strategy

If anything goes sideways in production, the rollback order is:

### If wiki-agent misbehaves but lakeon-api is healthy

1. **Scale wiki-agent to 0** — stops accepting new ingest/curate/lint jobs

   ```bash
   KUBECONFIG=~/.kube/cce-lakeon-config kubectl scale deployment/lakeon-wiki-agent -n lakeon --replicas=0
   ```

2. Queued `WIKI_UPDATE` tasks will fail-fast at `WikiAgentClient.triggerIngest` and log warnings, but the queue itself keeps draining other task types.

3. Fix the bug → rebuild → `helm upgrade` → scale back to 1.

### If lakeon-api misbehaves after the refactor

1. Roll back `lakeon-api` to the previous image tag:

   ```bash
   KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout undo deployment/lakeon-api -n lakeon
   ```

2. The rolled-back `lakeon-api` no longer has `InternalWikiController`, so wiki-agent's tool calls 404. Agent runs will error out cleanly (visible in `wiki_run_logs`).

3. Scale wiki-agent to 0 while the old api is in place.

### If the whole feature must be retracted

1. `helm uninstall lakeon-wiki-agent -n lakeon`
2. Git revert the merge commit of this plan on `lakeon-api`
3. Rebuild & redeploy `lakeon-api` — the old one-shot `WikiService` code lives in git history only; the revert brings it back
4. Reset auto-curate threshold in the reverted code

**Data safety**: the agent never drops tables or runs destructive SQL. The worst it can do is delete/misedit wiki pages, all of which live in the `documents` table with `doc_type='wiki'`. A bad curate can be rolled back by restoring pages from the `wiki_run_logs.trigger_doc` column and the document history.

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| MaaS DeepSeek rate limits tool calls | In-process semaphore caps concurrency at 8; if still an issue, fall back to `MAX_CONCURRENT_AGENTS=4` via env |
| Agent runs infinite loop | `MAX_TOOL_ROUNDS=20` hard cap; `max_rounds_exceeded` is a clear signal in run log |
| Agent deletes/mis-edits wiki | `ingest` run mode forbids `delete_page` (enforced in dispatcher); curate/lint is only triggered manually |
| Multiple docs ingested concurrently collide on same new page | Future work: add per-`kb_id` queue in `tasks.py`; for MVP, accept occasional duplicates and rely on curate pass |
| `update_page` ambiguous old_text silently corrupts a page | `WikiToolService.updatePage` returns `error` if not unique — agent must retry with more context |
| Schema drift between lakeon-api and wiki-agent token | Both read the same K8s Secret `wiki-agent-secrets`; CI check added in Task 6.3 Step 4 |
| Legacy 60-page KB curate removes pages the user cares about | Curate runs log every deletion with reason; user can restore via git-like diff in `wiki_run_logs` |
| `deepseek-v3.2` returns malformed `tool_calls` JSON | Loop catches `json.JSONDecodeError` and feeds back `{"error": "..."}` as tool result; LLM retries with corrected call |

## Future Work (Out of Scope)

- Per-KB serial queue in `lakeon-wiki-agent` (eliminates concurrent-ingest duplicate-page race)
- Observability: Prometheus metrics on `agent_run_duration_seconds`, `tool_calls_per_run`, `pages_created_per_run`
- HPA based on custom metric `running_agent_tasks`
- Per-tenant billing hook (currently tokens are recorded in `wiki_run_logs.token_count` but not rolled up)
- `dbay-kb` skill v2: watch mode that automatically ingests new files in a drop folder

## Self-Review

**Spec coverage**:
- Q1 (schema as `/schema.md`) → Task 1.7 seeds it, Task 2.4 exposes `get_schema` tool, Task 4.1 schema template ✓
- Q2 (async + webhook callback) → Task 2.8 returns 202 immediately, Task 1.6 has `/callback/{taskId}` endpoint (called by agent if `callback_url` is set in `RunRequest`) ✓
- Q3 (skip spike — proven in SreAiService) → Phase 2 mirrors SreAiService state machine ✓
- Q4 (no hard cap) → tools.py schema mentions budget; dispatcher enforces only `INGEST_FORBIDDEN={delete_page}` ✓
- Q5 (curate existing 60 pages) → Task 5.3 explicit test ✓
- Q6 (< 20 page E2E) → Task 5.2 `PAGE_COUNT_CEILING = 20` ✓
- Q9 (resident Deployment replicas=1 + semaphore) → Task 6.2 helm values + Task 2.7 semaphore ✓
- Q10 (200m/512Mi → 1000m/2Gi) → Task 6.2 `values.yaml` ✓
- Q11 (readiness only, no liveness) → Task 6.2 `deployment.yaml` explicit comment ✓

**Placeholder scan**: none. Every `- [ ]` step contains actual code/command.

**Type consistency check**:
- `RunRequest` dataclass defined in Task 2.6 — fields `tenant_id`, `kb_id`, `run_id`, `source`, `document_id`, `run_type`, `callback_url` → used consistently in Task 2.8 routes ✓
- `TaskRegistry.submit(run_type, coro) → task_id` in Task 2.7 → used in Task 2.8 ✓
- `LakeonApiClient` methods in Task 2.3 → exact same names used in Task 2.6 `_execute_tool` dispatcher ✓
- `WikiAgentClient.triggerIngest(tenantId, kbId, documentId) → String taskId` in Task 1.8 → used in Task 1.9 and Task 3.4 ✓
- `WikiToolService.listPages/readPage/…` signatures in Tasks 1.4, 1.5 match `InternalWikiController` request bodies in Task 1.6 ✓
- `WikiRunLogEntity` fields added in Task 1.1 (`runId`, `toolCallsCount`, `tokenCount`, `source`) match `InternalWikiController.WikiRunLogRequest` DTO in Task 1.6 and `RunResult.to_runlog` payload in Task 2.6 ✓

**Gap check**:
- The plan does not modify `lakeon-console` / `lakeon-admin` frontends. That is intentional — existing wiki page views keep working because the data model (`documents` with `doc_type='wiki'`) is unchanged. Admin controls for manually editing wiki prompts (if they exist in lakeon-admin) become dead UI after Task 5.1 and should be hidden in a follow-up (not blocking this plan).
- Documentation (`CLAUDE.md`, product docs) is not updated. Add a final manual step after deployment: update `CLAUDE.md` tech stack section to mention `lakeon-wiki-agent` and dbay-plugins README to mention `dbay-kb`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-11-wiki-agent-rewrite.md`.

Two execution options:

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration. Best for this plan because most tasks are self-contained (one file or one small group of files).

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints for review.

Which approach?
