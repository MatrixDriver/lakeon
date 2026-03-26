# DBay MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an MCP endpoint to lakeon-api exposing knowledge search and memory tools to Claude Code.

**Architecture:** Single `@RestController` at `POST /mcp` implementing MCP Streamable HTTP (JSON-RPC 2.0). No Spring AI dependency — the protocol is 3 methods (`initialize`, `tools/list`, `tools/call`). Auth reuses existing `ApiKeyFilter`. Tools delegate to existing `KnowledgeService`, `ChunkService`, and `MemoryService`.

**Tech Stack:** Spring Boot 3.3.5, Jackson for JSON-RPC, existing Service layer.

**Spec:** `docs/superpowers/specs/2026-03-26-mcp-server-design.md`

---

## File Structure

```
lakeon-api/src/main/java/com/lakeon/mcp/
├── McpController.java          # POST /mcp — JSON-RPC dispatch
├── McpToolRegistry.java        # Tool definitions + dispatch table
├── KnowledgeMcpTools.java      # 4 knowledge tool implementations
└── MemoryMcpTools.java         # 5 memory tool implementations

lakeon-api/src/test/java/com/lakeon/mcp/
├── McpControllerTest.java      # JSON-RPC protocol tests
└── McpToolRegistryTest.java    # Tool registry tests

deploy/cce/sites/hwstaff/values.yaml  # API pod 2C/4Gi
```

---

### Task 1: MCP JSON-RPC Controller

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/mcp/McpController.java`
- Create: `lakeon-api/src/test/java/com/lakeon/mcp/McpControllerTest.java`

This task builds the protocol layer — parses JSON-RPC, dispatches to methods, returns JSON-RPC responses. No tools yet.

- [ ] **Step 1: Write failing test for `initialize` method**

```java
// McpControllerTest.java
package com.lakeon.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(McpController.class)
class McpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private McpToolRegistry toolRegistry;

    @Test
    void initialize_returnsServerInfo() throws Exception {
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize",
                      "params": {
                        "protocolVersion": "2025-03-26",
                        "capabilities": {},
                        "clientInfo": {"name": "claude-code", "version": "1.0"}
                      }
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.serverInfo.name").value("dbay"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists());
    }

    @Test
    void unknownMethod_returnsError() throws Exception {
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc": "2.0", "id": 2, "method": "unknown/method", "params": {}}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd lakeon-api && mvn test -pl . -Dtest=McpControllerTest -q`
Expected: compilation error (McpController doesn't exist)

- [ ] **Step 3: Implement McpController**

```java
// McpController.java
package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class McpController {
    private final ObjectMapper mapper;
    private final McpToolRegistry toolRegistry;

    public McpController(ObjectMapper mapper, McpToolRegistry toolRegistry) {
        this.mapper = mapper;
        this.toolRegistry = toolRegistry;
    }

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<JsonNode> handle(HttpServletRequest request, @RequestBody JsonNode body) {
        String method = body.path("method").asText("");
        JsonNode id = body.get("id");
        JsonNode params = body.path("params");

        JsonNode result = switch (method) {
            case "initialize" -> handleInitialize();
            case "notifications/initialized" -> null;  // notification, no response
            case "tools/list" -> toolRegistry.listTools();
            case "tools/call" -> toolRegistry.callTool(request, params);
            default -> makeError(id, -32601, "Method not found: " + method);
        };

        if (result == null) {
            return ResponseEntity.accepted().build();
        }

        // Wrap in JSON-RPC response if not already an error
        if (!result.has("error")) {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.ok(result);
    }

    private JsonNode handleInitialize() {
        return mapper.valueToTree(Map.of(
            "protocolVersion", "2025-03-26",
            "capabilities", Map.of("tools", Map.of()),
            "serverInfo", Map.of("name", "dbay", "version", "1.0.0")
        ));
    }

    private JsonNode makeError(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }
}
```

- [ ] **Step 4: Create stub McpToolRegistry**

```java
// McpToolRegistry.java
package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class McpToolRegistry {
    private final ObjectMapper mapper;

    public McpToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode listTools() {
        return mapper.valueToTree(java.util.Map.of("tools", java.util.List.of()));
    }

    public JsonNode callTool(HttpServletRequest request, JsonNode params) {
        String toolName = params.path("name").asText("");
        return mapper.valueToTree(java.util.Map.of(
            "content", java.util.List.of(java.util.Map.of(
                "type", "text", "text", "Unknown tool: " + toolName
            )),
            "isError", true
        ));
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cd lakeon-api && mvn test -Dtest=McpControllerTest -q`
Expected: PASS

Note: The `@WebMvcTest` will load only `McpController`. The `ApiKeyFilter` is a servlet `Filter` `@Component` — it will be loaded. The test requests don't include auth headers. Either:
- Add `@MockBean TenantService tenantService` and `@MockBean LakeonProperties props` to satisfy the filter's dependencies, OR
- If the filter blocks the request, exclude it: `@WebMvcTest(value = McpController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyFilter.class))`

Adjust the test as needed to make it pass.

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/mcp/ lakeon-api/src/test/java/com/lakeon/mcp/
git commit -m "feat(mcp): JSON-RPC controller with initialize and method dispatch"
```

---

### Task 2: Tool Registry with Tool Definitions

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/mcp/McpToolRegistry.java`
- Create: `lakeon-api/src/test/java/com/lakeon/mcp/McpToolRegistryTest.java`

Build the tool registry that holds tool definitions (JSON Schema) and dispatches `tools/call` to the right implementation.

- [ ] **Step 1: Write failing test for tool listing**

```java
// McpToolRegistryTest.java
@Test
void listTools_returns9tools() {
    JsonNode result = registry.listTools();
    JsonNode tools = result.get("tools");
    assertEquals(9, tools.size());
    // Verify a known tool exists
    boolean hasSearch = false;
    for (JsonNode tool : tools) {
        if ("knowledge_search".equals(tool.get("name").asText())) {
            hasSearch = true;
            assertNotNull(tool.get("inputSchema"));
            assertTrue(tool.get("inputSchema").has("properties"));
        }
    }
    assertTrue(hasSearch);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd lakeon-api && mvn test -Dtest=McpToolRegistryTest -q`
Expected: FAIL (0 tools returned)

- [ ] **Step 3: Implement tool registration in McpToolRegistry**

Replace the stub `McpToolRegistry` with the full implementation:

```java
package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;

@Component
public class McpToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private final ObjectMapper mapper;
    private final List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    private final Map<String, BiFunction<TenantEntity, JsonNode, Object>> handlers = new HashMap<>();

    public McpToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Called by tool providers during initialization to register tools. */
    public void register(String name, String description,
                         Map<String, Object> inputSchema,
                         BiFunction<TenantEntity, JsonNode, Object> handler) {
        toolDefinitions.add(Map.of(
            "name", name,
            "description", description,
            "inputSchema", inputSchema
        ));
        handlers.put(name, handler);
    }

    public JsonNode listTools() {
        return mapper.valueToTree(Map.of("tools", toolDefinitions));
    }

    public JsonNode callTool(HttpServletRequest request, JsonNode params) {
        String toolName = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        BiFunction<TenantEntity, JsonNode, Object> handler = handlers.get(toolName);
        if (handler == null) {
            return errorResult("Unknown tool: " + toolName);
        }

        TenantEntity tenant = (TenantEntity) request.getAttribute("tenant");
        if (tenant == null) {
            return errorResult("Authentication required");
        }

        try {
            Object result = handler.apply(tenant, arguments);
            String text = result instanceof String ? (String) result : mapper.writeValueAsString(result);
            return mapper.valueToTree(Map.of(
                "content", List.of(Map.of("type", "text", "text", text))
            ));
        } catch (Exception e) {
            log.error("Tool {} failed: {}", toolName, e.getMessage(), e);
            return errorResult(e.getMessage());
        }
    }

    private JsonNode errorResult(String message) {
        return mapper.valueToTree(Map.of(
            "content", List.of(Map.of("type", "text", "text", "Error: " + message)),
            "isError", true
        ));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd lakeon-api && mvn test -Dtest=McpToolRegistryTest -q`
Expected: FAIL (still 0 tools — tool providers not yet registered). This is expected, we'll make it pass in tasks 3 and 4.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/mcp/McpToolRegistry.java \
        lakeon-api/src/test/java/com/lakeon/mcp/McpToolRegistryTest.java
git commit -m "feat(mcp): tool registry with dispatch and JSON Schema definitions"
```

---

### Task 3: Knowledge MCP Tools

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/mcp/KnowledgeMcpTools.java`

Registers 4 knowledge tools with the `McpToolRegistry`. Each delegates to existing `KnowledgeService` / `ChunkService`.

- [ ] **Step 1: Implement KnowledgeMcpTools**

```java
package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.lakeon.knowledge.ChunkService;
import com.lakeon.knowledge.KnowledgeBaseEntity;
import com.lakeon.knowledge.KnowledgeService;
import com.lakeon.model.entity.TenantEntity;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KnowledgeMcpTools {
    private final McpToolRegistry registry;
    private final KnowledgeService knowledgeService;
    private final ChunkService chunkService;

    public KnowledgeMcpTools(McpToolRegistry registry, KnowledgeService knowledgeService,
                              ChunkService chunkService) {
        this.registry = registry;
        this.knowledgeService = knowledgeService;
        this.chunkService = chunkService;
    }

    @PostConstruct
    void register() {
        registry.register("knowledge_list_bases",
            "List all knowledge bases for the current user.",
            schema(Map.of(), List.of()),  // no required params
            this::listBases);

        registry.register("knowledge_search",
            "Semantic search across a knowledge base. Returns matching text chunks ranked by relevance.",
            schema(Map.of(
                "kb_id", prop("string", "Knowledge base ID"),
                "query", prop("string", "Search query in natural language"),
                "top_k", prop("integer", "Number of results to return (default 5)")
            ), List.of("kb_id", "query")),
            this::search);

        registry.register("knowledge_list_documents",
            "List all documents in a knowledge base.",
            schema(Map.of(
                "kb_id", prop("string", "Knowledge base ID")
            ), List.of("kb_id")),
            this::listDocuments);

        registry.register("knowledge_get_chunk",
            "Get the full content of a specific chunk by document ID and chunk index.",
            schema(Map.of(
                "kb_id", prop("string", "Knowledge base ID"),
                "document_id", prop("string", "Document ID"),
                "chunk_index", prop("integer", "Chunk index (0-based)")
            ), List.of("kb_id", "document_id", "chunk_index")),
            this::getChunk);
    }

    private Object listBases(TenantEntity tenant, JsonNode args) {
        List<KnowledgeBaseEntity> kbs = knowledgeService.listKnowledgeBases(tenant.getId());
        return kbs.stream().map(kb -> Map.of(
            "id", kb.getId(),
            "name", kb.getName(),
            "description", Objects.toString(kb.getDescription(), ""),
            "document_count", kb.getDocumentCount() != null ? kb.getDocumentCount() : 0
        )).toList();
    }

    private Object search(TenantEntity tenant, JsonNode args) {
        String kbId = args.get("kb_id").asText();
        String query = args.get("query").asText();
        int topK = args.has("top_k") ? args.get("top_k").asInt() : 5;
        return knowledgeService.search(tenant.getId(), kbId, query, topK,
                null, null, true, null);
    }

    private Object listDocuments(TenantEntity tenant, JsonNode args) {
        String kbId = args.get("kb_id").asText();
        return knowledgeService.listDocuments(tenant.getId(), kbId, null).stream()
            .map(doc -> Map.of(
                "id", doc.getId(),
                "filename", doc.getFilename(),
                "format", doc.getFormat(),
                "status", doc.getStatus().name(),
                "chunks_count", doc.getChunksCount() != null ? doc.getChunksCount() : 0
            )).toList();
    }

    private Object getChunk(TenantEntity tenant, JsonNode args) {
        String kbId = args.get("kb_id").asText();
        String docId = args.get("document_id").asText();
        int chunkIndex = args.get("chunk_index").asInt();
        return chunkService.getChunk(tenant.getId(), kbId, docId, chunkIndex);
    }

    // ── Schema helpers ──

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (!required.isEmpty()) s.put("required", required);
        return s;
    }

    private static Map<String, String> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/mcp/KnowledgeMcpTools.java
git commit -m "feat(mcp): knowledge tools — list_bases, search, list_documents, get_chunk"
```

---

### Task 4: Memory MCP Tools

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/mcp/MemoryMcpTools.java`

Registers 5 memory tools. Delegates to existing `MemoryService` which proxies to the Python microservice.

- [ ] **Step 1: Implement MemoryMcpTools**

```java
package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.lakeon.memory.MemoryService;
import com.lakeon.model.entity.TenantEntity;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MemoryMcpTools {
    private final McpToolRegistry registry;
    private final MemoryService memoryService;

    public MemoryMcpTools(McpToolRegistry registry, MemoryService memoryService) {
        this.registry = registry;
        this.memoryService = memoryService;
    }

    @PostConstruct
    void register() {
        registry.register("memory_recall",
            "Search memories by semantic similarity. Use this to recall cross-project knowledge, user preferences, credentials, or past decisions.",
            schema(Map.of(
                "base_id", prop("string", "Memory base ID"),
                "query", prop("string", "What to recall, in natural language"),
                "memory_types", arrayProp("string", "Filter by types: fact, episode, procedural, decision, rejection, convention"),
                "top_k", prop("integer", "Number of results (default 10)")
            ), List.of("base_id", "query")),
            this::recall);

        registry.register("memory_ingest",
            "Store a memory. The server extracts and categorizes the memory from the content.",
            schema(Map.of(
                "base_id", prop("string", "Memory base ID"),
                "content", prop("string", "Content to store as memory"),
                "role", prop("string", "Role: user or assistant (default: user)"),
                "memory_type", prop("string", "Type: fact, episode, procedural, decision, rejection, convention (default: fact)"),
                "importance", prop("number", "Importance score 0-1 (default 0.5)")
            ), List.of("base_id", "content")),
            this::ingest);

        registry.register("memory_ingest_extracted",
            "Store pre-extracted memories. Use agent-extract mode: you decide what's worth remembering and categorize it before sending.",
            schema(Map.of(
                "base_id", prop("string", "Memory base ID"),
                "memories", arrayProp("object", "Array of {content, memory_type, importance?}")
            ), List.of("base_id", "memories")),
            this::ingestExtracted);

        registry.register("memory_list",
            "List memories in a memory base, optionally filtered by type.",
            schema(Map.of(
                "base_id", prop("string", "Memory base ID"),
                "memory_type", prop("string", "Filter: fact, episode, procedural, decision, rejection, convention"),
                "limit", prop("integer", "Max results (default 20)"),
                "offset", prop("integer", "Pagination offset (default 0)")
            ), List.of("base_id")),
            this::listMemories);

        registry.register("memory_delete",
            "Delete a specific memory by ID.",
            schema(Map.of(
                "base_id", prop("string", "Memory base ID"),
                "memory_id", prop("integer", "Memory ID to delete")
            ), List.of("base_id", "memory_id")),
            this::deleteMemory);
    }

    private Object recall(TenantEntity tenant, JsonNode args) {
        String baseId = args.get("base_id").asText();
        Map<String, Object> body = new HashMap<>();
        body.put("query", args.get("query").asText());
        if (args.has("top_k")) body.put("top_k", args.get("top_k").asInt());
        if (args.has("memory_types")) {
            List<String> types = new ArrayList<>();
            args.get("memory_types").forEach(n -> types.add(n.asText()));
            body.put("memory_types", types);
        }
        return memoryService.proxyPost(tenant.getId(), baseId, "/recall", body);
    }

    private Object ingest(TenantEntity tenant, JsonNode args) {
        String baseId = args.get("base_id").asText();
        Map<String, Object> body = new HashMap<>();
        body.put("content", args.get("content").asText());
        if (args.has("role")) body.put("role", args.get("role").asText());
        if (args.has("memory_type")) body.put("memory_type", args.get("memory_type").asText());
        if (args.has("importance")) body.put("importance", args.get("importance").asDouble());
        return memoryService.proxyPost(tenant.getId(), baseId, "/ingest", body);
    }

    private Object ingestExtracted(TenantEntity tenant, JsonNode args) {
        String baseId = args.get("base_id").asText();
        Map<String, Object> body = new HashMap<>();
        List<Map<String, Object>> memories = new ArrayList<>();
        args.get("memories").forEach(m -> {
            Map<String, Object> mem = new HashMap<>();
            mem.put("content", m.get("content").asText());
            mem.put("memory_type", m.get("memory_type").asText());
            if (m.has("importance")) mem.put("importance", m.get("importance").asDouble());
            memories.add(mem);
        });
        body.put("memories", memories);
        return memoryService.proxyPost(tenant.getId(), baseId, "/ingest_extracted", body);
    }

    private Object listMemories(TenantEntity tenant, JsonNode args) {
        String baseId = args.get("base_id").asText();
        Map<String, String> params = new HashMap<>();
        if (args.has("memory_type")) params.put("memory_type", args.get("memory_type").asText());
        params.put("limit", args.has("limit") ? String.valueOf(args.get("limit").asInt()) : "20");
        params.put("offset", args.has("offset") ? String.valueOf(args.get("offset").asInt()) : "0");
        return memoryService.proxyGet(tenant.getId(), baseId, "/memories", params);
    }

    private Object deleteMemory(TenantEntity tenant, JsonNode args) {
        String baseId = args.get("base_id").asText();
        int memoryId = args.get("memory_id").asInt();
        return memoryService.proxyDelete(tenant.getId(), baseId, "/memories/" + memoryId);
    }

    // ── Schema helpers ──

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (!required.isEmpty()) s.put("required", required);
        return s;
    }

    private static Map<String, String> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> arrayProp(String itemType, String description) {
        return Map.of("type", "array", "description", description,
                       "items", Map.of("type", itemType));
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run registry test — should now have 9 tools**

Update `McpToolRegistryTest` to use Spring context with all tool providers, or manually verify:

Run: `cd lakeon-api && mvn compile -q && echo "OK"`
Expected: OK

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/mcp/MemoryMcpTools.java
git commit -m "feat(mcp): memory tools — recall, ingest, ingest_extracted, list, delete"
```

---

### Task 5: Auth Integration — Ensure `/mcp` Requires API Key

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java` (verify, may not need changes)

The existing `ApiKeyFilter` authenticates all paths not explicitly excluded. Since `/mcp` is not in the exclusion list, it will already require `Authorization: Bearer lk_...`. Verify this works.

- [ ] **Step 1: Add integration test for auth on /mcp**

Add to `McpControllerTest.java`:

```java
@Test
void mcp_withoutAuth_returns401() throws Exception {
    // ApiKeyFilter should block unauthenticated requests
    // If using @WebMvcTest with the filter active, this should return 401
    mockMvc.perform(post("/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
                """))
            .andExpect(status().isUnauthorized());
}
```

Note: This test may need adjustment depending on whether `@WebMvcTest` loads the `ApiKeyFilter`. If the filter is excluded in other tests, create a separate integration test class that loads the full context.

- [ ] **Step 2: Verify the filter covers `/mcp`**

Read `ApiKeyFilter.java` — confirm `/mcp` is NOT in any exclusion path. The only excluded paths are:
- `/actuator/**`
- `/proxy/**` (internal token)
- `/api/v1/admin/**` (admin token)
- `POST /api/v1/tenants`
- `POST /api/v1/auth/login`
- `GET /api/v1/auth/check-username`
- `POST /api/v1/trial`
- `/api/v1/import/callback/**`
- `/api/v1/jobs/*/callback`

`/mcp` is not excluded → auth is required. No code change needed.

- [ ] **Step 3: Commit** (if any changes were needed)

```bash
git commit -m "test(mcp): verify API key auth required on /mcp endpoint"
```

---

### Task 6: API Pod Resource Bump

**Files:**
- Modify: `deploy/cce/sites/hwstaff/values.yaml`

- [ ] **Step 1: Update API pod resources to 2C/4Gi**

In `deploy/cce/sites/hwstaff/values.yaml`, change the `api.resources` section:

```yaml
api:
  resources:
    requests:
      cpu: "200m"
      memory: "512Mi"
    limits:
      cpu: "2"
      memory: "4Gi"
```

- [ ] **Step 2: Commit**

```bash
git add deploy/cce/sites/hwstaff/values.yaml
git commit -m "deploy: bump API pod resources to 2C/4Gi for MCP server"
```

---

### Task 7: End-to-End Verification

No new files — integration test using the running API.

- [ ] **Step 1: Build and verify locally**

```bash
cd lakeon-api && mvn compile -q && echo "BUILD OK"
```

- [ ] **Step 2: Test MCP protocol locally with curl**

Start the API locally (or use a test instance), then:

```bash
# Initialize
curl -s -X POST http://localhost:8090/mcp \
  -H "Authorization: Bearer lk_<your-key>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' | jq .

# List tools
curl -s -X POST http://localhost:8090/mcp \
  -H "Authorization: Bearer lk_<your-key>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | jq '.result.tools[].name'

# Search knowledge
curl -s -X POST http://localhost:8090/mcp \
  -H "Authorization: Bearer lk_<your-key>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"knowledge_list_bases","arguments":{}}}' | jq .
```

- [ ] **Step 3: Test with Claude Code**

```bash
claude mcp add --scope project --transport http dbay \
  http://localhost:8090/mcp \
  --header "Authorization: Bearer lk_<your-key>"
```

Then in a new CC session, verify:
- Tools appear in tool list
- `knowledge_search` returns results
- `memory_recall` returns results

- [ ] **Step 4: Deploy to CCE and test**

```bash
IMAGE_TAG=<next-version> ./deploy/cce/build-and-push-api.sh
./deploy/cce/deploy.sh
```

Then configure CC with the production endpoint:

```bash
claude mcp add --scope user --transport http dbay \
  https://api.dbay.cloud:8443/mcp \
  --header "Authorization: Bearer lk_<your-key>"
```

- [ ] **Step 5: Commit any fixes**
