package com.lakeon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
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
public class AiSqlService {
    private static final Logger log = LoggerFactory.getLogger(AiSqlService.class);

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Model definitions with pricing (CNY per million tokens)
    public static final List<Map<String, Object>> AVAILABLE_MODELS = List.of(
        Map.of(
            "id", "Qwen/Qwen3.5-4B",
            "name", "Qwen3.5 4B",
            "input_price", 0.0,
            "output_price", 0.0,
            "desc", "免费模型，适合日常 SQL 生成"
        ),
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
            "desc", "最强代码模型，SQL 生成质量最高"
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
        You are a PostgreSQL SQL expert assistant. The user will describe what they want to query in natural language, and you will generate the correct SQL statement.

        Rules:
        - Output ONLY the SQL statement, no explanations, no markdown code fences
        - Use the exact table and column names from the provided schema
        - Use PostgreSQL syntax
        - If the user's request is ambiguous, make reasonable assumptions
        - For SELECT queries, add appropriate ORDER BY and LIMIT when sensible
        - Use lowercase for SQL keywords for readability
        """;

    public AiSqlService(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Generate SQL from natural language using the specified model.
     */
    public Map<String, Object> generateSql(String prompt, String schemaInfo, String modelId) {
        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            return Map.of("error", "AI service not configured (missing API key)");
        }

        // Validate model
        if (modelId == null || modelId.isBlank()) {
            modelId = AVAILABLE_MODELS.get(0).get("id").toString();
        }

        String userMessage = "Database schema:\n" + schemaInfo + "\n\nUser request: " + prompt;

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", modelId);
            requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMessage)
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 2000);

            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("AI API returned {}: {}", response.statusCode(), response.body());
                return Map.of("error", "AI service error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String sql = root.path("choices").path(0).path("message").path("content").asText("").trim();

            // Strip markdown code fences if present
            if (sql.startsWith("```")) {
                sql = sql.replaceAll("^```(?:sql)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }

            // Extract usage info
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sql", sql);
            result.put("model", modelId);
            result.put("input_tokens", inputTokens);
            result.put("output_tokens", outputTokens);
            return result;

        } catch (Exception e) {
            log.error("AI SQL generation failed: {}", e.getMessage());
            return Map.of("error", "AI service error: " + e.getMessage());
        }
    }
}
