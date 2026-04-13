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
 * Outbound client to lakeon-wiki-agent. Fire-and-forget: the agent returns
 * {@code {task_id, status: "accepted"}} immediately and runs asynchronously.
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
        // Force HTTP/1.1 — uvicorn does not support HTTP/2 h2c upgrade from
        // plain-text "Upgrade: h2c" headers (Java HttpClient's default probe).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
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

    /**
     * Fetch the status snapshot of a wiki agent task by its ID.
     * Returns a map with keys like task_id, status, result, error.
     * Returns a minimal error map if the agent is unreachable — never throws.
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        String baseUrl = props.getWiki().getAgent().getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return Map.of("status", "error", "error", "wiki agent not configured");
        }
        String url = baseUrl + "/v1/wiki/tasks/" + taskId;
        String token = props.getWiki().getAgent().getInternalToken();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return Map.of("status", "not_found", "task_id", taskId);
            }
            if (resp.statusCode() != 200) {
                log.warn("Wiki agent /v1/wiki/tasks/{} returned {}: {}",
                        taskId, resp.statusCode(), resp.body());
                return Map.of("status", "error",
                        "error", "wiki agent returned " + resp.statusCode());
            }
            Map<?, ?> result = objectMapper.readValue(resp.body(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) result;
            return typed;
        } catch (Exception e) {
            log.warn("Wiki agent /v1/wiki/tasks/{} call failed: {}", taskId, e.toString());
            return Map.of("status", "error", "error", e.toString());
        }
    }

    /**
     * Open an SSE stream to the wiki-agent /v1/wiki/chat endpoint.
     * Uses HttpURLConnection instead of HttpClient for reliable chunked streaming
     * (HttpClient may buffer the entire response before exposing the InputStream).
     * Returns an InputStream the caller reads line-by-line, or null if unreachable.
     * Caller is responsible for closing the stream.
     */
    public java.io.InputStream streamChat(String tenantId, String kbId, String question,
                                           java.util.List<Map<String, String>> history,
                                           String mode, String documentId) {
        String baseUrl = props.getWiki().getAgent().getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Wiki agent URL not configured; cannot stream chat");
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenant_id", tenantId);
            body.put("kb_id", kbId);
            body.put("question", question);
            body.put("history", history);
            if (mode != null) body.put("mode", mode);
            if (documentId != null) body.put("document_id", documentId);
            String json = objectMapper.writeValueAsString(body);
            String token = props.getWiki().getAgent().getInternalToken();

            var url = new java.net.URL(baseUrl + "/v1/wiki/chat");
            var conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(300_000);
            // Disable buffering so we get data as it arrives
            conn.setChunkedStreamingMode(0);

            try (var out = conn.getOutputStream()) {
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                log.warn("Wiki agent chat returned HTTP {}", status);
                conn.disconnect();
                return null;
            }
            return conn.getInputStream();
        } catch (Exception e) {
            log.warn("Wiki agent chat failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns null when the agent is unreachable/misconfigured. Callers should
     * log and continue — agent availability must not fail queue draining.
     *
     * <p>No retry at either layer (KbWriteQueue.executeWikiUpdate logs and skips).
     * Rationale: wiki ingestion is best-effort background enrichment; a transient
     * agent outage should not pile up retries or block document parse/summarize.
     * See {@link com.lakeon.knowledge.KbWriteQueue#executeWikiUpdate} for the
     * authoritative decision and the TODO for a future reconciliation job.
     */
    private String post(String path, Map<String, Object> body) {
        String baseUrl = props.getWiki().getAgent().getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Wiki agent URL not configured; cannot call {}", path);
            return null;
        }
        String url = baseUrl + path;
        String token = props.getWiki().getAgent().getInternalToken();
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(props.getWiki().getAgent().getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 202 && resp.statusCode() != 200) {
                log.warn("Wiki agent {} returned {}: {}", path, resp.statusCode(),
                        truncate(resp.body(), 200));
                return null;
            }
            Map<?, ?> result = objectMapper.readValue(resp.body(), Map.class);
            Object taskId = result.get("task_id");
            if (taskId == null) {
                log.warn("Wiki agent {} returned 2xx without task_id: {}",
                        path, truncate(resp.body(), 200));
                return null;
            }
            log.debug("Wiki agent accepted {}: task={}", path, taskId);
            return taskId.toString();
        } catch (Exception e) {
            log.warn("Wiki agent call failed {}: {}", path, e.toString(), e);
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
