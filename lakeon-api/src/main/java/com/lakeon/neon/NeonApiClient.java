package com.lakeon.neon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.neon.dto.CreateTenantRequest;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTenant;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.neon.exception.NeonApiException;
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
import java.util.List;
import java.util.Map;

@Component
public class NeonApiClient {
    private static final Logger log = LoggerFactory.getLogger(NeonApiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 2;

    @Autowired
    public NeonApiClient(LakeonProperties props) {
        this(props.getNeon().getPageserverUrl());
    }

    // Package-private constructor for testing with direct URL
    NeonApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    /**
     * Create a Neon tenant via location_config API.
     * PUT /v1/tenant/{tenant_id}/location_config
     */
    public NeonTenant createTenant(CreateTenantRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant"))
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
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId) + "/location_config"))
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
     */
    public void waitForTenantActive(String tenantId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId)))
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
     * Create a timeline (branch).
     * POST /v1/tenant/{tenant_id}/timeline
     */
    public NeonTimeline createTimeline(String tenantId, CreateTimelineRequest request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
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

    /**
     * Delete a tenant.
     * DELETE /v1/tenant/{tenant_id}
     */
    public void deleteTenant(String tenantId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId)))
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
     * Delete a timeline.
     * DELETE /v1/tenant/{tenant_id}/timeline/{timeline_id}
     */
    public void deleteTimeline(String tenantId, String timelineId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline/" + encodePathSegment(timelineId)))
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
     * Get a single timeline.
     * GET /v1/tenant/{tenant_id}/timeline/{timeline_id}
     */
    public NeonTimeline getTimeline(String tenantId, String timelineId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline/" + encodePathSegment(timelineId)))
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
     * List timelines for a tenant.
     * GET /v1/tenant/{tenant_id}/timeline
     */
    public List<NeonTimeline> listTimelines(String tenantId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId) + "/timeline"))
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
     * Get tenant info.
     * GET /v1/tenant/{tenant_id}
     */
    /**
     * Check pageserver status.
     * GET /v1/status
     */
    public Map<String, Object> getStatus() {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/status"))
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

    public NeonTenant getTenant(String tenantId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/tenant/" + encodePathSegment(tenantId)))
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
