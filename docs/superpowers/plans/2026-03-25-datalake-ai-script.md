# Datalake AI Script Assistant + Multi-Dataset Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AI-assisted Python script generation to the datalake job creation page, with multi-dataset input support. AI auto-discovers all tenant datasets and their schemas, generates scripts referencing the right datasets, and auto-selects them in the form.

**Architecture:** Backend `AiScriptService` (mirrors `AiSqlService`) calls SiliconFlow API with all tenant dataset schemas as context. `DatasetEntity` gets `schema_json` field populated at export time. Frontend expands the AI hint in `DatalakeJobNewCode.vue` into an inline panel. Multi-dataset support changes `input_dataset_id` to `input_dataset_ids` array throughout the stack.

**Tech Stack:** Spring Boot / Fabric8 K8s client (backend), Vue 3 + CodeMirror 6 (frontend), SiliconFlow API (LLM), JUnit 5 + Mockito (backend tests).

---

## File Map

**Backend — create:**
- `lakeon-api/src/main/resources/db/migration/V21__add_dataset_schema_json.sql` — Flyway migration
- `lakeon-api/src/main/java/com/lakeon/datalake/AiScriptService.java` — LLM call service
- `lakeon-api/src/test/java/com/lakeon/datalake/AiScriptServiceTest.java` — unit tests

**Backend — modify:**
- `lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java` — add `schemaJson` field
- `lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java` — populate `schemaJson` in `triggerExport()`
- `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java` — `input_dataset_id` → `input_dataset_ids`
- `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java` — inject multi `DATASET_PATH_{name}` env vars
- `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeController.java` — add AI generate endpoint

**Frontend — modify:**
- `lakeon-console/src/api/datalake.ts` — add `AiScriptResult`, `generateDatalakeScript()`, change `input_dataset_id` → `input_dataset_ids`
- `lakeon-console/src/views/datalake/DatalakeJobNew.vue` — `inputDatasetId` → `inputDatasetIds`, pass props, handle AI events
- `lakeon-console/src/views/datalake/components/DatalakeJobNewCode.vue` — add AI panel
- `lakeon-console/src/views/datalake/components/DatalakeJobNewDataset.vue` — single select → multi-select
- `lakeon-console/src/views/datalake/components/DatalakeJobNewEnvVars.vue` — dynamic multi-dataset green rows

---

## Task 1: Flyway migration + DatasetEntity schema_json field

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V21__add_dataset_schema_json.sql`
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java`

- [ ] **Step 1: Create Flyway migration**

Create `V21__add_dataset_schema_json.sql`:
```sql
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS schema_json TEXT;
```

- [ ] **Step 2: Add field to DatasetEntity**

In `DatasetEntity.java`, after the `error` field (around line 57), add:
```java
@Column(name = "schema_json", columnDefinition = "text")
private String schemaJson;
```

Add getter/setter at the end of the class:
```java
public String getSchemaJson() {
    return schemaJson;
}

public void setSchemaJson(String schemaJson) {
    this.schemaJson = schemaJson;
}
```

- [ ] **Step 3: Compile and verify**
```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**
```bash
git add lakeon-api/src/main/resources/db/migration/V21__add_dataset_schema_json.sql \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetEntity.java
git commit -m "feat(dataset): add schema_json field with Flyway migration V21"
```

---

## Task 2: Populate schema_json in DatasetService.triggerExport()

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java`

- [ ] **Step 1: Read DatasetService.java to understand triggerExport()**

Read the file, especially `triggerExport()` and the surrounding context for JDBC connection patterns.

- [ ] **Step 2: Add schema population logic**

In `triggerExport()`, after the compute pod is woken and the JDBC connection info is available, but before the export job is submitted, add schema population for `TABLE_SELECT` datasets.

Find where `triggerExport()` builds the `params` map and inserts the export job. Before that section, add:

```java
// Populate schema_json for TABLE_SELECT datasets
if (dataset.getSourceType() == DatasetSourceType.DB_EXPORT && dataset.getSourceTables() != null) {
    try {
        // sourceTables is stored as JSON: [{"name":"orders","columns":["a","b"]}] or plain string
        String tableName = dataset.getSourceTables();
        if (tableName.startsWith("[")) {
            var tables = objectMapper.readValue(tableName, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>(){});
            if (!tables.isEmpty()) {
                tableName = (String) tables.get(0).get("name");
            }
        }
        String schemaQuery = "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position";
        java.util.List<java.util.Map<String, String>> columns = new java.util.ArrayList<>();
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://" + computeHost + ":" + computePort + "/" + db.getName(),
                "cloud_admin", "");
             var ps = conn.prepareStatement(schemaQuery)) {
            ps.setString(1, tableName);
            var rs = ps.executeQuery();
            while (rs.next()) {
                columns.add(java.util.Map.of(
                    "name", rs.getString("column_name"),
                    "type", rs.getString("data_type")));
            }
        }
        if (!columns.isEmpty()) {
            dataset.setSchemaJson(objectMapper.writeValueAsString(columns));
        }
    } catch (Exception e) {
        log.warn("Failed to populate schema_json for dataset {}: {}", dataset.getId(), e.getMessage());
    }
}
```

Note: Read the actual `triggerExport()` to find the correct variable names for `computeHost`, `computePort`, and the database entity. The above is the pattern — adapt variable names to match existing code.

- [ ] **Step 3: Build and verify**
```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/dataset/DatasetService.java
git commit -m "feat(dataset): populate schema_json from source DB columns in triggerExport()"
```

---

## Task 3: Multi-dataset backend — DatalakeJobRequest + PythonJobRunner

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java`

- [ ] **Step 1: Read DatalakeJobRequest.java and update field**

Change `input_dataset_id` field from `String` to `List<String>`:

Replace:
```java
@JsonProperty("input_dataset_id")
private String inputDatasetId;
```

With:
```java
@JsonProperty("input_dataset_ids")
private java.util.List<String> inputDatasetIds;
```

Update getter/setter accordingly:
```java
public java.util.List<String> getInputDatasetIds() {
    return inputDatasetIds;
}

public void setInputDatasetIds(java.util.List<String> inputDatasetIds) {
    this.inputDatasetIds = inputDatasetIds;
}
```

Remove the old `getInputDatasetId()` / `setInputDatasetId()` methods.

- [ ] **Step 2: Read PythonJobRunner.java and update env var injection**

In `PythonJobRunner.start()`, find where `DATASET_PATH` is injected (currently handles single `input_dataset_id`). Replace that logic with multi-dataset injection.

Read the file first to find the existing `DATASET_PATH` injection code. Then replace it with:

```java
// Inject DATASET_PATH_{name} for each selected dataset
if (req.getInputDatasetIds() != null && !req.getInputDatasetIds().isEmpty()) {
    for (String dsId : req.getInputDatasetIds()) {
        var ds = datasetRepository.findById(dsId).orElse(null);
        if (ds != null && ds.getObsPath() != null) {
            String varName = "DATASET_PATH";
            if (req.getInputDatasetIds().size() > 1) {
                varName = "DATASET_PATH_" + ds.getName().replaceAll("\\s+", "_").toLowerCase();
            }
            envVars.add(new EnvVarBuilder().withName(varName).withValue(ds.getObsPath()).build());
        }
    }
    // For single dataset, also inject plain DATASET_PATH for backward compat
    if (req.getInputDatasetIds().size() == 1) {
        // Already injected as DATASET_PATH above
    }
}
```

**Important**: `PythonJobRunner`'s constructor currently takes `KubernetesClient`, `LakeonProperties`, `DatalakeJobRepository`. You MUST add `DatasetRepository` as a new constructor parameter:
```java
private final DatasetRepository datasetRepository;

// Constructor: add DatasetRepository parameter
public PythonJobRunner(KubernetesClient k8sClient, LakeonProperties props,
                       DatalakeJobRepository repository, DatasetRepository datasetRepository) {
    // ... existing assignments ...
    this.datasetRepository = datasetRepository;
}
```
Add `import com.lakeon.dataset.DatasetRepository;` and `import com.lakeon.dataset.DatasetEntity;` to the imports. Also update `PythonJobRunnerTest.java` to mock and inject `DatasetRepository` in the constructor call.

- [ ] **Step 3: Fix any compilation errors from the input_dataset_id → input_dataset_ids rename**

Search across the codebase for references to `getInputDatasetId()` or `inputDatasetId` and update them:
```bash
grep -r "inputDatasetId\|getInputDatasetId\|input_dataset_id" lakeon-api/src --include="*.java" | grep -v target
```

- [ ] **Step 4: Build and run tests**
```bash
cd lakeon-api && mvn test -pl . -Dtest=PythonJobRunnerTest -q 2>&1 | tail -20
```

Fix any test failures caused by the field rename.

- [ ] **Step 5: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRequest.java \
        lakeon-api/src/main/java/com/lakeon/datalake/PythonJobRunner.java
git commit -m "feat(datalake): multi-dataset input — input_dataset_ids array, DATASET_PATH_{name} env vars"
```

---

## Task 4: AiScriptService — LLM call service

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/datalake/AiScriptService.java`
- Create: `lakeon-api/src/test/java/com/lakeon/datalake/AiScriptServiceTest.java`

- [ ] **Step 1: Read AiSqlService.java for the pattern to follow**

Read `lakeon-api/src/main/java/com/lakeon/service/AiSqlService.java` in full. Note: AVAILABLE_MODELS, HttpClient setup, system prompt, response parsing, markdown stripping.

- [ ] **Step 2: Create AiScriptService.java**

Create `lakeon-api/src/main/java/com/lakeon/datalake/AiScriptService.java`, following the `AiSqlService` pattern:

```java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiScriptService {

    private static final Logger log = LoggerFactory.getLogger(AiScriptService.class);
    private final LakeonProperties props;
    private final DatasetRepository datasetRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Reuse same models as AiSqlService
    public static final List<Map<String, Object>> AVAILABLE_MODELS = List.of(
        Map.of("id", "Qwen/Qwen3.5-4B", "name", "Qwen 3.5 4B", "description", "轻量快速，免费", "input_price", 0.0, "output_price", 0.0),
        Map.of("id", "deepseek-ai/DeepSeek-V3.2", "name", "DeepSeek V3.2", "description", "高性价比推理", "input_price", 2.0, "output_price", 3.0),
        Map.of("id", "Qwen/Qwen3-Coder-480B-A35B-Instruct", "name", "Qwen3 Coder 480B", "description", "最强代码生成", "input_price", 8.0, "output_price", 16.0),
        Map.of("id", "Qwen/Qwen3-Coder-30B-A3B-Instruct", "name", "Qwen3 Coder 30B", "description", "代码生成，经济型", "input_price", 0.7, "output_price", 2.8)
    );

    private static final String SYSTEM_PROMPT = """
            You are a Python data processing expert. Generate a Python script based on the user's request.

            Rules:
            - Output ONLY the Python script, no explanations, no markdown code fences
            - Read input datasets from os.environ["DATASET_PATH_{name}"] (Parquet format). If only one dataset, also available as os.environ["DATASET_PATH"]
            - Write output data to the path in os.environ["OUTPUT_PATH"] (Parquet format)
            - Use pandas for data processing
            - Use the exact column names from the provided dataset schemas
            - Always include: import os, import pandas as pd
            - Use lowercase for variable names
            """;

    public AiScriptService(LakeonProperties props, DatasetRepository datasetRepository, ObjectMapper objectMapper) {
        this.props = props;
        this.datasetRepository = datasetRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Map<String, Object> generateScript(String tenantId, String prompt, String modelId) {
        String apiKey = props.getAi().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Map.of("error", "AI service not configured (missing API key)");
        }

        if (modelId == null || modelId.isBlank()) {
            modelId = "Qwen/Qwen3.5-4B";
        }

        // Build dataset context from all READY datasets for this tenant
        String datasetContext = buildDatasetContext(tenantId);

        // Build user message
        String userMessage = datasetContext + "\nOutput: OUTPUT_PATH\n\nUser request: " + prompt;

        try {
            // Build request body (same pattern as AiSqlService)
            Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.1,
                "max_tokens", 2000
            );

            String body = objectMapper.writeValueAsString(requestBody);
            String baseUrl = props.getAi().getBaseUrl();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("AI service error: status={}, body={}", response.statusCode(), response.body());
                return Map.of("error", "AI service error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String script = root.path("choices").path(0).path("message").path("content").asText("");

            // Defensive markdown stripping
            if (script.startsWith("```")) {
                script = script.replaceAll("^```(?:python)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }

            long inputTokens = root.path("usage").path("prompt_tokens").asLong(0);
            long outputTokens = root.path("usage").path("completion_tokens").asLong(0);

            // Determine which datasets were used by matching DATASET_PATH_{name} in generated code
            List<String> usedDatasetIds = findUsedDatasets(tenantId, script);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("script", script);
            result.put("model", modelId);
            result.put("input_tokens", inputTokens);
            result.put("output_tokens", outputTokens);
            result.put("used_dataset_ids", usedDatasetIds);
            return result;

        } catch (Exception e) {
            log.error("AI script generation failed", e);
            return Map.of("error", "AI service error: " + e.getMessage());
        }
    }

    String buildDatasetContext(String tenantId) {
        List<DatasetEntity> datasets = datasetRepository
                .findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY);

        if (datasets.isEmpty()) {
            return "Datasets: (none available)";
        }

        StringBuilder sb = new StringBuilder("Datasets:\n");
        int idx = 1;
        for (DatasetEntity ds : datasets) {
            String envVarName = "DATASET_PATH_" + ds.getName().replaceAll("\\s+", "_").toLowerCase();
            sb.append("\n").append(idx++).append(". ").append(ds.getName());
            if (ds.getRowCount() != null) sb.append(" (").append(ds.getRowCount()).append(" rows");
            if (ds.getFileSize() != null) sb.append(", ").append(formatSize(ds.getFileSize()));
            if (ds.getRowCount() != null) sb.append(")");
            sb.append("\n   Env var: ").append(envVarName).append("\n");

            if (ds.getSchemaJson() != null) {
                sb.append("   Schema:\n");
                try {
                    List<Map<String, String>> cols = objectMapper.readValue(ds.getSchemaJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    for (Map<String, String> col : cols) {
                        sb.append("     - ").append(col.get("name")).append(": ").append(col.get("type")).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("   Schema: (parse error)\n");
                }
            } else {
                sb.append("   Schema: (unavailable — use generic column references)\n");
            }
        }
        return sb.toString();
    }

    List<String> findUsedDatasets(String tenantId, String script) {
        List<DatasetEntity> datasets = datasetRepository
                .findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY);
        List<String> usedIds = new ArrayList<>();
        for (DatasetEntity ds : datasets) {
            String envVarName = "DATASET_PATH_" + ds.getName().replaceAll("\\s+", "_").toLowerCase();
            if (script.contains(envVarName)) {
                usedIds.add(ds.getId());
            }
        }
        // If script uses plain DATASET_PATH (no suffix) and only one dataset exists, include it
        if (usedIds.isEmpty() && datasets.size() == 1 && script.contains("DATASET_PATH")) {
            usedIds.add(datasets.get(0).getId());
        }
        return usedIds;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
```

- [ ] **Step 3: Write unit test**

Create `AiScriptServiceTest.java` to test `buildDatasetContext()` and `findUsedDatasets()`:

```java
package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiScriptService 单元测试")
class AiScriptServiceTest {

    @Mock DatasetRepository datasetRepository;
    AiScriptService service;

    @BeforeEach
    void setUp() {
        LakeonProperties props = new LakeonProperties();
        service = new AiScriptService(props, datasetRepository, new ObjectMapper());
    }

    private DatasetEntity makeDataset(String id, String name, String schemaJson) {
        DatasetEntity ds = new DatasetEntity();
        ds.setId(id);
        ds.setTenantId("t1");
        ds.setName(name);
        ds.setStatus(DatasetStatus.READY);
        ds.setRowCount(1000L);
        ds.setFileSize(50000L);
        ds.setSchemaJson(schemaJson);
        return ds;
    }

    @Test
    @DisplayName("buildDatasetContext includes schema when available")
    void buildDatasetContextWithSchema() {
        DatasetEntity ds = makeDataset("ds1", "orders",
                "[{\"name\":\"order_id\",\"type\":\"int64\"},{\"name\":\"amount\",\"type\":\"float64\"}]");
        when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc("t1", DatasetStatus.READY))
                .thenReturn(List.of(ds));

        String ctx = service.buildDatasetContext("t1");

        assertThat(ctx).contains("orders");
        assertThat(ctx).contains("DATASET_PATH_orders");
        assertThat(ctx).contains("order_id: int64");
        assertThat(ctx).contains("amount: float64");
    }

    @Test
    @DisplayName("buildDatasetContext handles null schema")
    void buildDatasetContextNullSchema() {
        DatasetEntity ds = makeDataset("ds2", "raw data", null);
        when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc("t1", DatasetStatus.READY))
                .thenReturn(List.of(ds));

        String ctx = service.buildDatasetContext("t1");

        assertThat(ctx).contains("DATASET_PATH_raw_data");
        assertThat(ctx).contains("unavailable");
    }

    @Test
    @DisplayName("findUsedDatasets matches dataset env vars in script")
    void findUsedDatasetsMatches() {
        DatasetEntity ds1 = makeDataset("ds1", "orders", null);
        DatasetEntity ds2 = makeDataset("ds2", "users", null);
        when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc("t1", DatasetStatus.READY))
                .thenReturn(List.of(ds1, ds2));

        String script = "df = pd.read_parquet(os.environ['DATASET_PATH_orders'])";
        List<String> used = service.findUsedDatasets("t1", script);

        assertThat(used).contains("ds1");
    }

    @Test
    @DisplayName("buildDatasetContext empty when no datasets")
    void buildDatasetContextEmpty() {
        when(datasetRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc("t1", DatasetStatus.READY))
                .thenReturn(List.of());

        String ctx = service.buildDatasetContext("t1");
        assertThat(ctx).contains("none available");
    }
}
```

- [ ] **Step 4: Run tests**
```bash
cd lakeon-api && mvn test -pl . -Dtest=AiScriptServiceTest -q 2>&1 | tail -20
```

- [ ] **Step 5: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/AiScriptService.java \
        lakeon-api/src/test/java/com/lakeon/datalake/AiScriptServiceTest.java
git commit -m "feat(datalake): add AiScriptService — LLM-based Python script generation with dataset schema context"
```

---

## Task 5: DatalakeController — AI generate endpoint

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeController.java`

- [ ] **Step 1: Read DatalakeController.java**

Understand the existing endpoint patterns and how `getTenant()` works.

- [ ] **Step 2: Add AI generate endpoint and models endpoint**

Add `AiScriptService` to the constructor (alongside existing `DatalakeService` and `DatalakeLogService` parameters). Then add the endpoints:

```java
private final AiScriptService aiScriptService;

// Update constructor to include AiScriptService:
// public DatalakeController(DatalakeService datalakeService, DatalakeLogService logService, AiScriptService aiScriptService) {
//     ...
//     this.aiScriptService = aiScriptService;
// }

@PostMapping("/ai-script/generate")
public Map<String, Object> generateScript(HttpServletRequest req,
                                          @RequestBody Map<String, String> body) {
    String tenantId = getTenant(req).getId();
    String prompt = body.get("prompt");
    if (prompt == null || prompt.isBlank()) {
        return Map.of("error", "prompt is required");
    }
    String model = body.get("model");
    return aiScriptService.generateScript(tenantId, prompt, model);
}

@GetMapping("/ai-script/models")
public List<Map<String, Object>> getScriptModels() {
    return AiScriptService.AVAILABLE_MODELS;
}
```

- [ ] **Step 3: Build and verify**
```bash
cd lakeon-api && mvn compile -q
```

- [ ] **Step 4: Commit**
```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeController.java
git commit -m "feat(datalake): add AI script generate and models endpoints"
```

---

## Task 6: Frontend — multi-dataset support (API types, Dataset component, EnvVars, main page)

**Files:**
- Modify: `lakeon-console/src/api/datalake.ts`
- Modify: `lakeon-console/src/views/datalake/DatalakeJobNew.vue`
- Modify: `lakeon-console/src/views/datalake/components/DatalakeJobNewDataset.vue`
- Modify: `lakeon-console/src/views/datalake/components/DatalakeJobNewEnvVars.vue`

- [ ] **Step 1: Update API types in datalake.ts**

Change `input_dataset_id?: string` to `input_dataset_ids?: string[]` in `DatalakeJobSubmitRequest`.

Add AI script types:
```typescript
export interface AiScriptResult {
  script?: string
  error?: string
  model?: string
  input_tokens?: number
  output_tokens?: number
  used_dataset_ids?: string[]
}

export function generateDatalakeScript(prompt: string, model: string) {
  return client.post<AiScriptResult>('/datalake/ai-script/generate', { prompt, model })
}
```

- [ ] **Step 2: Update DatalakeJobNew.vue — form field and props**

Read the file first. Changes needed:

a) In `form` ref, change `inputDatasetId: ''` to `inputDatasetIds: [] as string[]`

b) Update `<DatalakeJobNewDataset>` component usage — change props from `:input-dataset-id` to `:input-dataset-ids`, and update emit handler

c) Update `<DatalakeJobNewCode>` to listen for `update:usedDatasetIds`:
```vue
<DatalakeJobNewCode
  v-else-if="currentSection === 'code'"
  :script="form.inlineScript"
  @update:script="form.inlineScript = $event"
  @update:usedDatasetIds="form.inputDatasetIds = $event"
/>
```

d) Update `<DatalakeJobNewEnvVars>` — change `:input-dataset-id` to `:input-dataset-ids`

e) In `handleSubmit()`, change `input_dataset_id: form.value.inputDatasetId || undefined` to `input_dataset_ids: form.value.inputDatasetIds.length ? form.value.inputDatasetIds : undefined`

f) In the submit bar template, change `v-if="form.inputDatasetId"` to `v-if="form.inputDatasetIds.length"` and update the text to show count: `· 输入数据集 ×{{ form.inputDatasetIds.length }}`

- [ ] **Step 3: Rewrite DatalakeJobNewDataset.vue — single select → multi-select**

Read the current file, then rewrite the dataset selection from `<select>` to a checkbox list with dataset chips.

The template should show:
- A list of READY datasets with checkboxes
- Selected datasets shown as removable chips/tags above the list
- Keep the output path field unchanged

Replace the `<select>` with:
```vue
<div class="dataset-list">
  <div v-for="d in datasets" :key="d.id" class="dataset-item"
       :class="{ selected: isSelected(d.id) }" @click="toggleDataset(d.id)">
    <input type="checkbox" :checked="isSelected(d.id)" @click.stop />
    <span class="dataset-name">{{ d.name }}</span>
    <span class="dataset-meta">{{ d.rowCount?.toLocaleString() ?? '?' }} 行 · {{ formatSize(d.fileSizeBytes) }}</span>
  </div>
</div>
```

Update props: `inputDatasetId: string` → `inputDatasetIds: string[]`
Update emit: `update:inputDatasetId` → `update:inputDatasetIds`

Add toggle logic:
```typescript
function isSelected(id: string) { return props.inputDatasetIds.includes(id) }
function toggleDataset(id: string) {
  const updated = isSelected(id)
    ? props.inputDatasetIds.filter(i => i !== id)
    : [...props.inputDatasetIds, id]
  emit('update:inputDatasetIds', updated)
}
```

- [ ] **Step 4: Update DatalakeJobNewEnvVars.vue — dynamic multi-dataset green rows**

Read the current file. Change props: `inputDatasetId: string` → `inputDatasetIds: string[]`

The template should show green rows based on selected datasets. Need dataset name info — accept `datasets` prop (array of `{id, name, obsPath}`) from parent, or fetch locally.

Simpler approach: accept `inputDatasetNames` prop (string[]) from parent alongside `inputDatasetIds`. The parent can resolve names from the dataset list.

Alternative (simpler): just show the count. E.g., "DATASET_PATH_* × 3 datasets selected".

Simplest approach for now: show `DATASET_PATH` if 1 dataset, show `DATASET_PATH_{name} × N` if multiple. The exact paths aren't known to the frontend (they're injected by the backend at job creation time).

Update the auto-injected section:
```vue
<!-- Single dataset -->
<div v-if="inputDatasetIds.length === 1" class="env-row env-auto">
  <div class="env-cell env-key">DATASET_PATH</div>
  <div class="env-cell">OBS 路径（自动注入）</div>
  <div class="env-cell env-del">—</div>
</div>
<!-- Multiple datasets -->
<template v-else-if="inputDatasetIds.length > 1">
  <div class="env-row env-auto">
    <div class="env-cell env-key">DATASET_PATH_*</div>
    <div class="env-cell">{{ inputDatasetIds.length }} 个数据集路径（自动注入）</div>
    <div class="env-cell env-del">—</div>
  </div>
</template>
```

- [ ] **Step 5: Build to verify**
```bash
cd lakeon-console && npm run build 2>&1 | tail -20
```

- [ ] **Step 6: Commit**
```bash
git add lakeon-console/src/api/datalake.ts \
        lakeon-console/src/views/datalake/DatalakeJobNew.vue \
        lakeon-console/src/views/datalake/components/DatalakeJobNewDataset.vue \
        lakeon-console/src/views/datalake/components/DatalakeJobNewEnvVars.vue
git commit -m "feat(datalake): multi-dataset input — checkbox list, dynamic env var display"
```

---

## Task 7: Frontend — AI panel in DatalakeJobNewCode.vue

**Files:**
- Modify: `lakeon-console/src/views/datalake/components/DatalakeJobNewCode.vue`

- [ ] **Step 1: Read the current DatalakeJobNewCode.vue**

Understand the existing structure, especially the `ai-hint` section at the bottom.

- [ ] **Step 2: Add AI panel — replace ai-hint with expandable panel**

Read `lakeon-console/src/components/SqlEditor.vue` to understand the AI panel pattern used in the SQL assistant (model selector, prompt textarea, generate button, token display).

Replace the `ai-hint` div with an expandable AI panel:

```vue
<!-- AI Panel -->
<div class="ai-panel" :class="{ expanded: aiOpen }">
  <div class="ai-toggle" @click="aiOpen = !aiOpen">
    ✨ <strong>AI 辅助</strong>：描述你想做什么，AI 帮你生成脚本
    <span class="ai-toggle-arrow">{{ aiOpen ? '▼' : '▶' }}</span>
  </div>
  <div v-if="aiOpen" class="ai-body">
    <div class="ai-row">
      <label class="ai-label">模型</label>
      <select v-model="aiModel" class="ai-select">
        <option v-for="m in models" :key="m.id" :value="m.id">
          {{ m.name }} {{ m.input_price === 0 ? '(免费)' : `(¥${m.input_price}/${m.output_price} per M)` }}
        </option>
      </select>
    </div>
    <textarea
      v-model="aiPrompt"
      class="ai-prompt"
      rows="3"
      placeholder="例：过滤 score > 0.8 的行，按 category 分组统计数量"
      :disabled="aiLoading"
      @keydown.ctrl.enter="handleGenerate"
      @keydown.meta.enter="handleGenerate"
    ></textarea>
    <div class="ai-actions">
      <button class="ai-btn" :disabled="aiLoading || !aiPrompt.trim()" @click="handleGenerate">
        {{ aiLoading ? '生成中...' : '生成脚本' }}
      </button>
      <span v-if="aiTokens" class="ai-tokens">
        {{ aiTokens.input }} + {{ aiTokens.output }} tokens
        <template v-if="aiTokens.cost > 0"> · ¥{{ aiTokens.cost.toFixed(4) }}</template>
      </span>
    </div>
    <div v-if="aiError" class="ai-error">{{ aiError }}</div>
  </div>
</div>
```

- [ ] **Step 3: Add script logic for AI panel**

Add to the `<script setup>`:

```typescript
import { generateDatalakeScript } from '../../../api/datalake'

const emit = defineEmits<{
  'update:script': [value: string]
  'update:usedDatasetIds': [value: string[]]
}>()

const aiOpen = ref(false)
const aiPrompt = ref('')
const aiModel = ref('Qwen/Qwen3.5-4B')
const aiLoading = ref(false)
const aiError = ref('')
const aiTokens = ref<{ input: number; output: number; cost: number } | null>(null)

const models = [
  { id: 'Qwen/Qwen3.5-4B', name: 'Qwen 3.5 4B', input_price: 0, output_price: 0 },
  { id: 'deepseek-ai/DeepSeek-V3.2', name: 'DeepSeek V3.2', input_price: 2.0, output_price: 3.0 },
  { id: 'Qwen/Qwen3-Coder-480B-A35B-Instruct', name: 'Qwen3 Coder 480B', input_price: 8.0, output_price: 16.0 },
  { id: 'Qwen/Qwen3-Coder-30B-A3B-Instruct', name: 'Qwen3 Coder 30B', input_price: 0.7, output_price: 2.8 },
]

async function handleGenerate() {
  if (!aiPrompt.value.trim() || aiLoading.value) return
  aiLoading.value = true
  aiError.value = ''
  aiTokens.value = null
  try {
    const res = await generateDatalakeScript(aiPrompt.value, aiModel.value)
    const data = res.data as any
    if (data.error) {
      aiError.value = data.error
      return
    }
    if (data.script && view) {
      // Replace editor content
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: data.script }
      })
      emit('update:script', data.script)
    }
    if (data.used_dataset_ids) {
      emit('update:usedDatasetIds', data.used_dataset_ids)
    }
    // Token usage
    const m = models.find(m => m.id === aiModel.value)
    const inputCost = (data.input_tokens || 0) * (m?.input_price || 0) / 1_000_000
    const outputCost = (data.output_tokens || 0) * (m?.output_price || 0) / 1_000_000
    aiTokens.value = {
      input: data.input_tokens || 0,
      output: data.output_tokens || 0,
      cost: inputCost + outputCost
    }
  } catch (e: any) {
    aiError.value = e?.response?.data?.error?.message || e.message || 'Generation failed'
  } finally {
    aiLoading.value = false
  }
}
```

- [ ] **Step 4: Add CSS for AI panel**

Add styles to the `<style scoped>` section:
```css
.ai-panel { margin-top: 12px; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }
.ai-panel.expanded { border-color: rgba(99,102,241,.3); }
.ai-toggle { display: flex; align-items: center; gap: 8px; padding: 10px 14px; font-size: 12px; color: #6366f1; cursor: pointer; background: rgba(99,102,241,.04); }
.ai-toggle:hover { background: rgba(99,102,241,.08); }
.ai-toggle-arrow { margin-left: auto; font-size: 10px; }
.ai-body { padding: 12px 14px; border-top: 1px solid #e2e8f0; background: #fff; }
.ai-row { margin-bottom: 8px; }
.ai-label { font-size: 11px; font-weight: 600; color: #374151; margin-right: 8px; }
.ai-select { font-size: 12px; padding: 4px 8px; border: 1px solid #e2e8f0; border-radius: 4px; color: #334155; outline: none; }
.ai-prompt { width: 100%; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 10px; font-size: 12px; color: #334155; outline: none; resize: vertical; font-family: inherit; }
.ai-prompt:focus { border-color: #6366f1; }
.ai-actions { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
.ai-btn { background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; border: none; padding: 6px 16px; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; }
.ai-btn:disabled { opacity: 0.5; cursor: default; }
.ai-tokens { font-size: 11px; color: #94a3b8; }
.ai-error { margin-top: 8px; font-size: 11px; color: #ef4444; }
```

- [ ] **Step 5: Remove old ai-hint styles**

Delete the `.ai-hint` CSS class that's no longer used.

- [ ] **Step 6: Build to verify**
```bash
cd lakeon-console && npm run build 2>&1 | tail -20
```

- [ ] **Step 7: Commit**
```bash
git add lakeon-console/src/views/datalake/components/DatalakeJobNewCode.vue
git commit -m "feat(datalake): AI script generation panel — model select, prompt, generate, token display"
```

---

## Task 8: Smoke test

- [ ] **Step 1: Backend build + tests**
```bash
cd lakeon-api && mvn test -q 2>&1 | tail -10
```

- [ ] **Step 2: Frontend build**
```bash
cd lakeon-console && npm run build 2>&1 | tail -10
```

- [ ] **Step 3: Manual verification checklist**

Start dev server (`cd lakeon-console && npm run dev`) and verify:

1. Navigate to `/datalake/jobs/new`
2. In「数据集」section: datasets appear as checkboxes, can select multiple
3. In「环境变量」section: green rows update based on selection count
4. In「代码」section: AI panel shows, can expand
5. In AI panel: model selector works, can type prompt, Ctrl+Enter triggers
6. Generated script replaces editor content
7. `used_dataset_ids` auto-selects datasets in the dataset section
