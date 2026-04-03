package com.lakeon.knowledge;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final String REWRITE_MODEL = "Qwen/Qwen3.5-4B";
    private static final int MAX_HISTORY_TURNS = 5;
    private static final String SYSTEM_PROMPT = """
        你是查询改写助手。根据对话历史，将用户的最新问题改写为一个独立的、上下文完整的搜索查询。
        只输出改写后的查询，不要解释。
        """;

    public QueryRewriteService(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Rewrite a query using conversation history for context.
     * If no history or AI not configured, returns the original query.
     */
    public String rewriteQuery(String query, List<Map<String, String>> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return query;
        }

        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("AI API key not configured, skipping query rewrite");
            return query; // graceful degradation
        }

        // Truncate to last MAX_HISTORY_TURNS turns (10 messages max)
        int maxMessages = MAX_HISTORY_TURNS * 2;
        List<Map<String, String>> truncated = conversationHistory.size() > maxMessages
                ? conversationHistory.subList(conversationHistory.size() - maxMessages, conversationHistory.size())
                : conversationHistory;

        // Build user message with history
        StringBuilder sb = new StringBuilder("对话历史：\n");
        for (Map<String, String> msg : truncated) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        sb.append("\n用户问题：").append(query).append("\n\n改写后的查询：");

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            String aiModel = props.getAi().getModel();
            requestBody.put("model", aiModel.isEmpty() ? REWRITE_MODEL : aiModel);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", sb.toString())
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 200);
            requestBody.put("chat_template_kwargs", Map.of("enable_thinking", false));

            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Query rewrite API returned {}: {}", response.statusCode(), response.body());
                return query;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String rewritten = root.path("choices").path(0).path("message").path("content").asText("").trim();

            if (rewritten.isBlank()) {
                log.warn("Query rewrite returned empty result, using original query");
                return query;
            }

            log.info("Query rewritten: '{}' -> '{}'", query, rewritten);
            return rewritten;

        } catch (Exception e) {
            log.warn("Query rewrite failed, using original query: {}", e.getMessage());
            return query;
        }
    }
}
