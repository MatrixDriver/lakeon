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

@Service
public class AiScriptService {
    private static final Logger log = LoggerFactory.getLogger(AiScriptService.class);

    private final LakeonProperties props;
    private final DatasetRepository datasetRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Model definitions with pricing (CNY per million tokens)
    public static final List<Map<String, Object>> AVAILABLE_MODELS = List.of(
        Map.of(
            "id", "deepseek-ai/DeepSeek-V3.2",
            "name", "DeepSeek V3.2",
            "input_price", 2.0,
            "output_price", 3.0,
            "desc", "综合能力强，性价比高"
        ),
        Map.of(
            "id", "Qwen/Qwen3-Coder-480B-A35B-Instruct",
            "name", "Qwen3 Coder 480B",
            "input_price", 8.0,
            "output_price", 16.0,
            "desc", "最强代码模型，脚本生成质量最高"
        ),
        Map.of(
            "id", "Qwen/Qwen3-Coder-30B-A3B-Instruct",
            "name", "Qwen3 Coder 30B",
            "input_price", 0.7,
            "output_price", 2.8,
            "desc", "轻量代码模型，速度快价格低"
        )
    );

    private static final String SYSTEM_PROMPT = """
        You are a Python data processing expert. The user will describe what they want to process, and you will generate a Python script using pandas.

        Rules:
        - Output ONLY the Python script, no explanations, no markdown code fences
        - Access datasets via environment variables: DATASET_PATH_{name} (e.g., DATASET_PATH_sales for a dataset named "sales")
          where {name} is the lowercased, space-replaced-by-underscore version of the dataset name
        - Write output files to the path given by the OUTPUT_PATH environment variable
        - Use pandas for data manipulation; import os to read environment variables
        - If the user's request is ambiguous, make reasonable assumptions
        - Include basic error handling where appropriate
        - Keep the script concise and readable
        """;

    public AiScriptService(LakeonProperties props, DatasetRepository datasetRepository, ObjectMapper objectMapper) {
        this.props = props;
        this.datasetRepository = datasetRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Generate a Python data processing script from natural language using the specified model.
     * Automatically discovers all READY datasets for the tenant and injects their schema as context.
     */
    public Map<String, Object> generateScript(String tenantId, String prompt, String modelId) {
        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return Map.of("error", "AI service not configured (missing API key)");
        }

        // Use global override model if configured, otherwise validate user selection
        String aiModel = props.getAi().getModel();
        if (!aiModel.isEmpty()) {
            modelId = aiModel;
        } else if (modelId == null || modelId.isBlank()) {
            modelId = AVAILABLE_MODELS.get(0).get("id").toString();
        }

        List<DatasetEntity> datasets = datasetRepository
            .findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY);

        String datasetContext = buildDatasetContext(tenantId);
        String userMessage = "Available datasets:\n" + datasetContext + "\n\nUser request: " + prompt;

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMessage)
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 4000);
            requestBody.put("chat_template_kwargs", Map.of("enable_thinking", false));

            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("AI API returned {}: {}", response.statusCode(), response.body());
                return Map.of("error", "AI service error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String script = root.path("choices").path(0).path("message").path("content").asText("").trim();

            // Strip markdown code fences if present
            if (script.startsWith("```")) {
                script = script.replaceAll("^```(?:python)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }

            // Extract usage info
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            List<String> usedDatasetIds = findUsedDatasets(datasets, script);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("script", script);
            result.put("model", modelId);
            result.put("input_tokens", inputTokens);
            result.put("output_tokens", outputTokens);
            result.put("used_dataset_ids", usedDatasetIds);
            return result;

        } catch (Exception e) {
            log.error("AI script generation failed: {}", e.getMessage());
            return Map.of("error", "AI service error: " + e.getMessage());
        }
    }

    /**
     * Build a human-readable context string describing all READY datasets for the tenant,
     * including their env var names, row counts, and column schemas.
     */
    public String buildDatasetContext(String tenantId) {
        List<DatasetEntity> datasets = datasetRepository
            .findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, DatasetStatus.READY);

        if (datasets.isEmpty()) {
            return "none available";
        }

        StringBuilder sb = new StringBuilder();
        for (DatasetEntity ds : datasets) {
            String envVarName = "DATASET_PATH_" + ds.getName().replaceAll("\\s+", "_").toLowerCase();
            sb.append("Dataset: ").append(ds.getName()).append("\n");
            sb.append("  Env var: ").append(envVarName).append("\n");
            if (ds.getRowCount() != null) {
                sb.append("  Rows: ").append(ds.getRowCount()).append("\n");
            }
            if (ds.getSchemaJson() != null) {
                try {
                    JsonNode cols = objectMapper.readTree(ds.getSchemaJson());
                    sb.append("  Columns:\n");
                    for (JsonNode col : cols) {
                        sb.append("    - ").append(col.path("name").asText())
                          .append(" (").append(col.path("type").asText()).append(")\n");
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse schemaJson for dataset {}: {}", ds.getId(), e.getMessage());
                    sb.append("  Columns: unavailable\n");
                }
            } else {
                sb.append("  Columns: unavailable\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Determine which datasets were referenced in the generated script by matching
     * DATASET_PATH_{name} environment variable names.
     */
    public List<String> findUsedDatasets(List<DatasetEntity> datasets, String script) {
        List<String> usedIds = new ArrayList<>();

        // Match specific DATASET_PATH_{name} per dataset
        for (DatasetEntity ds : datasets) {
            String envVarName = "DATASET_PATH_" + ds.getName().replaceAll("\\s+", "_").toLowerCase();
            if (script.contains(envVarName)) {
                usedIds.add(ds.getId());
            }
        }

        // If script uses plain DATASET_PATH and only one dataset, include it
        if (usedIds.isEmpty() && datasets.size() == 1 && script.contains("DATASET_PATH")) {
            usedIds.add(datasets.get(0).getId());
        }

        return usedIds;
    }
}
