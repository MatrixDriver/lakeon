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
