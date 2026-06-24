package com.lakeon.neon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.neon.dto.CreateTenantRequest;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTenant;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.neon.exception.NeonApiException;
import com.lakeon.pageserver.PageserverPlacementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class NeonApiClient {
    private static final Logger log = LoggerFactory.getLogger(NeonApiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final PageserverPlacementService placementService;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 2;

    @Autowired
    public NeonApiClient(LakeonProperties props, PageserverPlacementService placementService) {
        this(props.getNeon().getPageserverUrl(), placementService);
    }

    // Package-private constructor for testing with direct URL
    NeonApiClient(String baseUrl) {
        this(baseUrl, null);
    }

    private NeonApiClient(String baseUrl, PageserverPlacementService placementService) {
        this.baseUrl = baseUrl;
        this.placementService = placementService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private String defaultBaseUrl() {
        return placementService == null ? baseUrl : placementService.defaultNode().httpUrl();
    }

    private String baseUrlForTenant(String tenantId) {
        return placementService == null ? baseUrl : placementService.resolve(tenantId, 0).node().httpUrl();
    }

    /**
     * Create a Neon tenant via location_config API.
     * PUT /v1/tenant/{tenant_id}/location_config
     */
    public NeonTenant createTenant(CreateTenantRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(defaultBaseUrl() + "/v1/tenant"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to create tenant: HTTP " + response.statusCode() + " - " + response.body(), response.statusCode());
            }
            NeonTenant tenant = objectMapper.readValue(response.body(), NeonTenant.class);
            log.info("Created Neon tenant: {}", tenant.getId());
            return tenant;
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to create tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Create a Neon tenant with a specific tenant ID.
     * PUT /v1/tenant/{tenant_id}/location_config
     */
    public NeonTenant createTenant(String tenantId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "mode", "AttachedSingle",
                "generation", 1,
                "tenant_conf", Map.of()
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/location_config"))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to create tenant: HTTP " + response.statusCode(), response.statusCode());
            }
            log.info("Created Neon tenant: {}", tenantId);
            return new NeonTenant(tenantId);
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to create tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Wait for a tenant to become Active (up to timeoutSeconds).
     * Polls GET /v1/tenant/{tenant_id} until state.slug == "Active".
     * If the tenant is not found on pageserver (404), automatically re-attaches it
     * via location_config API — this handles pageserver restarts that lose tenant state.
     */
    public void waitForTenantActive(String tenantId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        boolean reattached = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId)))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var body = objectMapper.readTree(response.body());
                    String state = body.path("state").path("slug").asText("");
                    if ("Active".equals(state)) {
                        log.info("Tenant {} is Active", tenantId);
                        return;
                    }
                    log.debug("Tenant {} state: {}, waiting...", tenantId, state);
                } else if (response.statusCode() == 404 && !reattached) {
                    log.warn("Tenant {} not found on pageserver, re-attaching via location_config", tenantId);
                    reattachTenant(tenantId);
                    reattached = true;
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NeonApiException("Interrupted waiting for tenant active", e);
            } catch (Exception e) {
                log.warn("Error polling tenant {}: {}", tenantId, e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new NeonApiException("Interrupted waiting for tenant active", ie);
                }
            }
        }
        throw new NeonApiException("Tenant " + tenantId + " did not become Active within " + timeoutSeconds + "s");
    }

    /**
     * Re-attach a tenant to pageserver via location_config.
     * Used when a tenant is missing after pageserver restart.
     */
    private void reattachTenant(String tenantId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "mode", "AttachedSingle",
                "generation", 1,
                "tenant_conf", Map.of()
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/location_config"))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("Failed to re-attach tenant {}: HTTP {}", tenantId, response.statusCode());
            } else {
                log.info("Re-attached tenant {} to pageserver", tenantId);
            }
        } catch (Exception e) {
            log.error("Failed to re-attach tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Create a timeline (branch).
     * POST /v1/tenant/{tenant_id}/timeline
     */
    public NeonTimeline createTimeline(String tenantId, CreateTimelineRequest request) {
        try {
            HttpResponse<String> response = postTimeline(tenantId, request);
            if (response.statusCode() == 404) {
                log.warn("Tenant {} not found while creating timeline, re-attaching and retrying", tenantId);
                reattachTenant(tenantId);
                response = postTimeline(tenantId, request);
            }
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to create timeline: HTTP " + response.statusCode(), response.statusCode());
            }
            NeonTimeline timeline = objectMapper.readValue(response.body(), NeonTimeline.class);
            log.info("Created Neon timeline: {} for tenant: {}", timeline.getTimelineId(), tenantId);
            return timeline;
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to create timeline: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> postTimeline(String tenantId, CreateTimelineRequest request) throws Exception {
        String body = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Request body for {@link #createBranch(String, CreateBranchRequest)}.
     * Serialised to snake_case via {@link JsonProperty} for the Neon pageserver API.
     */
    public record CreateBranchRequest(
            @JsonProperty("ancestor_timeline_id") String ancestorTimelineId,
            @JsonProperty("ancestor_start_lsn") String ancestorStartLsn,
            @JsonProperty("new_timeline_id") String newTimelineId) {}

    /**
     * Response from {@link #createBranch(String, CreateBranchRequest)}.
     * {@code lsn} is the LSN at which the new branch begins. It is sourced from the
     * timeline's {@code last_record_lsn} (which equals the ancestor branch point for a
     * fresh branch). If the pageserver response omits {@code last_record_lsn},
     * {@link #createBranch(String, CreateBranchRequest)} falls back to the
     * {@code ancestor_start_lsn} from the request so callers never observe a null LSN.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateBranchResponse(String timelineId, String lsn) {}

    /**
     * Create a branch timeline from an ancestor at a specific LSN.
     * POST /v1/tenant/{tenant_id}/timeline with
     * {ancestor_timeline_id, ancestor_start_lsn, new_timeline_id}.
     * Delegates to {@link #createTimeline(String, CreateTimelineRequest)} to keep
     * HTTP/error-handling logic DRY.
     */
    public CreateBranchResponse createBranch(String tenantId, CreateBranchRequest request) {
        CreateTimelineRequest timelineRequest = CreateTimelineRequest.forBranchAtLsn(
                request.newTimelineId(),
                request.ancestorTimelineId(),
                request.ancestorStartLsn());
        NeonTimeline timeline = createTimeline(tenantId, timelineRequest);
        String resolvedLsn = timeline.getLastRecordLsn() != null
                ? timeline.getLastRecordLsn()
                : request.ancestorStartLsn();
        return new CreateBranchResponse(timeline.getTimelineId(), resolvedLsn);
    }

    /**
     * Delete a tenant.
     * DELETE /v1/tenant/{tenant_id}
     */
    public void deleteTenant(String tenantId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId)))
                .DELETE()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            // Idempotent: 404 is fine (already deleted)
            if (response.statusCode() >= 400 && response.statusCode() != 404) {
                throw new NeonApiException("Failed to delete tenant: HTTP " + response.statusCode(), response.statusCode());
            }
            log.info("Deleted Neon tenant: {}", tenantId);
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to delete tenant: " + e.getMessage(), e);
        }
    }

    /**
     * Get a single timeline.
     * GET /v1/tenant/{tenant_id}/timeline/{timeline_id}
     */
    public NeonTimeline getTimeline(String tenantId, String timelineId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline/" + encodePathSegment(timelineId)))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to get timeline: HTTP " + response.statusCode(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), NeonTimeline.class);
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to get timeline: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a timeline.
     * DELETE /v1/tenant/{tenant_id}/timeline/{timeline_id}
     */
    public void deleteTimeline(String tenantId, String timelineId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline/" + encodePathSegment(timelineId)))
                .DELETE()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() != 404) {
                throw new NeonApiException("Failed to delete timeline: HTTP " + response.statusCode(), response.statusCode());
            }
            log.info("Deleted Neon timeline: {} from tenant: {}", timelineId, tenantId);
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to delete timeline: " + e.getMessage(), e);
        }
    }

    /**
     * List timelines for a tenant.
     * GET /v1/tenant/{tenant_id}/timeline
     */
    public List<NeonTimeline> listTimelines(String tenantId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline"))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to list timelines: HTTP " + response.statusCode(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<List<NeonTimeline>>() {});
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to list timelines: " + e.getMessage(), e);
        }
    }

    /**
     * List all tenants currently attached on pageserver.
     * GET /v1/tenant
     */
    public List<Map<String, Object>> listTenants() {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(defaultBaseUrl() + "/v1/tenant"))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to list tenants: HTTP " + response.statusCode(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to list tenants: " + e.getMessage(), e);
        }
    }

    /**
     * Check pageserver status.
     * GET /v1/status
     */
    public Map<String, Object> getStatus() {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(defaultBaseUrl() + "/v1/status"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Pageserver unhealthy: HTTP " + response.statusCode(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Pageserver unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Response from GET /v1/tenant/{tenant_id}/timeline/{timeline_id}/get_lsn_by_timestamp.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LsnByTimestampResponse(String lsn) {}

    /**
     * Look up the LSN that corresponds to a wall-clock timestamp on a timeline.
     * GET /v1/tenant/{tenant_id}/timeline/{timeline_id}/get_lsn_by_timestamp?timestamp=&lt;RFC3339&gt;
     */
    public String getLsnByTimestamp(String tenantId, String timelineId, Instant timestamp) {
        try {
            String iso = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String encodedIso = URLEncoder.encode(iso, StandardCharsets.UTF_8);
            String url = baseUrlForTenant(tenantId)
                + "/v1/tenant/" + encodePathSegment(tenantId)
                + "/timeline/" + encodePathSegment(timelineId)
                + "/get_lsn_by_timestamp?timestamp=" + encodedIso;
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to get LSN by timestamp: HTTP " + response.statusCode() + " - " + response.body(), response.statusCode());
            }
            LsnByTimestampResponse body = objectMapper.readValue(response.body(), LsnByTimestampResponse.class);
            if (body == null || body.lsn() == null) {
                throw new NeonApiException("Empty response from pageserver for get_lsn_by_timestamp");
            }
            return body.lsn();
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to get LSN by timestamp: " + e.getMessage(), e);
        }
    }

    /**
     * Timeline metadata returned by {@link #getTimelineInfo(String, String)}.
     *
     * <p>{@code latestGcCutoffLsn} is the LSN below which the pageserver has GC'd WAL/layer
     * data — i.e. the earliest LSN you can still PITR to. It is an LSN, not a timestamp;
     * Neon does not expose a timestamp form of the GC horizon. {@code lastRecordLsn} is
     * the head of the timeline (i.e. the latest LSN to which you can restore).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimelineInfo(
        @JsonProperty("timeline_id") String timelineId,
        @JsonProperty("last_record_lsn") String lastRecordLsn,
        @JsonProperty("disk_consistent_lsn") String diskConsistentLsn,
        @JsonProperty("latest_gc_cutoff_lsn") String latestGcCutoffLsn
    ) {}

    /**
     * Get full timeline metadata including head LSN and GC cutoff LSN.
     * GET /v1/tenant/{tenant_id}/timeline/{timeline_id}
     *
     * <p>Distinct from {@link #getTimeline(String, String)} which returns the
     * {@link com.lakeon.neon.dto.NeonTimeline} branch-creation view; this returns the
     * richer {@link TimelineInfo} record used by recovery flows (PITR window discovery).
     */
    public TimelineInfo getTimelineInfo(String tenantId, String timelineId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline/" + encodePathSegment(timelineId)))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to get timeline info: HTTP " + response.statusCode() + " - " + response.body(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), TimelineInfo.class);
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to get timeline info: " + e.getMessage(), e);
        }
    }

    public NeonTenant getTenant(String tenantId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlForTenant(tenantId) + "/v1/tenant/" + encodePathSegment(tenantId)))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new NeonApiException("Failed to get tenant: HTTP " + response.statusCode(), response.statusCode());
            }
            return objectMapper.readValue(response.body(), NeonTenant.class);
        } catch (NeonApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NeonApiException("Failed to get tenant: " + e.getMessage(), e);
        }
    }
}
