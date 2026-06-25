package com.lakeon.k8s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.pageserver.PageserverPlacementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Shared utility for generating Neon compute_ctl config JSON.
 *
 * Registered as a {@link Component} so warm-pool code can inject it
 * directly. Compute pod creation constructs it with the same
 * placement service used for Neon tenant/timeline APIs.
 */
@Component
public class ComputeSpecBuilder {

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final PageserverPlacementService placementService;

    public ComputeSpecBuilder(LakeonProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, new PageserverPlacementService(props));
    }

    @Autowired
    public ComputeSpecBuilder(LakeonProperties props, ObjectMapper objectMapper,
                              PageserverPlacementService placementService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.placementService = placementService;
    }

    /**
     * Generate compute config JSON for a Neon compute_ctl instance in Primary mode.
     * @param entity database entity with tenant/timeline IDs
     * @param suspendTimeoutSeconds suspend timeout in seconds (0 = never auto-suspend)
     */
    public String generateComputeConfig(DatabaseEntity entity, int suspendTimeoutSeconds) {
        return generateComputeConfig(entity, suspendTimeoutSeconds, "Primary");
    }

    /**
     * Generate compute config JSON with explicit mode.
     * Replica mode is used by warm-pool idle pods so multiple can share the
     * same mock tenant without fighting on the safekeeper primary lock.
     * @param mode one of "Primary", "Replica", or "Static" (with LSN). Neon's
     *             ComputeMode enum — see libs/compute_api/src/spec.rs upstream.
     */
    public String generateComputeConfig(DatabaseEntity entity, int suspendTimeoutSeconds, String mode) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("format_version", 2);
        spec.put("operation_uuid", UUID.randomUUID().toString());
        spec.put("tenant_id", entity.getNeonTenantId());
        spec.put("timeline_id", entity.getNeonTimelineId());
        spec.put("pageserver_connstring", buildPageserverPgConnstring(entity));
        spec.put("safekeeper_connstrings", parseSafekeeperUrls());
        spec.put("mode", mode);
        spec.put("suspend_timeout_seconds", suspendTimeoutSeconds);

        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("cluster_id", "lakeon_" + entity.getId());
        cluster.put("name", entity.getName());
        cluster.put("state", "restarted");
        cluster.put("roles", List.of(
            Map.of(
                "name", entity.getDbUser() != null ? entity.getDbUser() : "lakeon",
                "encrypted_password", entity.getDbPassword() != null ? entity.getDbPassword() : ""
            ),
            Map.of(
                "name", "cloud_admin",
                "encrypted_password", com.lakeon.util.ScramUtils.generateScramHash("cloud-admin-internal")
            )
        ));
        cluster.put("databases", List.of(Map.of(
            "name", entity.getName(),
            "owner", entity.getDbUser() != null ? entity.getDbUser() : "lakeon"
        )));
        cluster.put("settings", getDefaultPgSettings(entity));
        spec.put("cluster", cluster);

        List<Map<String, Object>> jwksKeys = new ArrayList<>();
        String publicJwk = props.getComputeJwt().getPublicJwk();
        if (publicJwk != null && !publicJwk.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> jwk = objectMapper.readValue(publicJwk, Map.class);
                jwksKeys.add(jwk);
            } catch (JsonProcessingException ex) {
                throw new RuntimeException("Invalid COMPUTE_JWT_PUBLIC_JWK: " + ex.getMessage(), ex);
            }
        }
        Map<String, Object> config = Map.of(
            "spec", spec,
            "compute_ctl_config", Map.of("jwks", Map.of("keys", jwksKeys))
        );
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate compute config", e);
        }
    }

    public List<Map<String, String>> getDefaultPgSettings(DatabaseEntity entity) {
        List<Map<String, String>> settings = new ArrayList<>();
        settings.add(Map.of("name", "shared_preload_libraries", "value", "neon", "vartype", "string"));
        settings.add(Map.of("name", "fsync", "value", "off", "vartype", "bool"));
        settings.add(Map.of("name", "wal_level", "value", "logical", "vartype", "enum"));
        settings.add(Map.of("name", "wal_log_hints", "value", "on", "vartype", "bool"));
        settings.add(Map.of("name", "log_connections", "value", "on", "vartype", "bool"));
        settings.add(Map.of("name", "port", "value", "55433", "vartype", "integer"));
        settings.add(Map.of("name", "shared_buffers", "value", "128MB", "vartype", "string"));
        settings.add(Map.of("name", "max_connections", "value", "100", "vartype", "integer"));
        settings.add(Map.of("name", "listen_addresses", "value", "0.0.0.0", "vartype", "string"));
        settings.add(Map.of("name", "neon.pageserver_connstring", "value",
                buildPageserverPgConnstring(entity), "vartype", "string"));
        settings.add(Map.of("name", "neon.safekeepers", "value",
                props.getNeon().getSafekeeperUrls(), "vartype", "string"));
        settings.add(Map.of("name", "neon.tenant_id", "value",
                entity.getNeonTenantId(), "vartype", "string"));
        settings.add(Map.of("name", "neon.timeline_id", "value",
                entity.getNeonTimelineId(), "vartype", "string"));
        return settings;
    }

    String buildPageserverPgConnstring() {
        return placementService.defaultNode().pgConnstring();
    }

    String buildPageserverPgConnstring(DatabaseEntity entity) {
        if (entity != null && entity.getNeonTenantId() != null && !entity.getNeonTenantId().isBlank()) {
            return placementService.resolve(entity.getNeonTenantId(), 0).node().pgConnstring();
        }
        return buildPageserverPgConnstring();
    }

    String resolvePageserverPgHost() {
        String url = props.getNeon().getPageserverUrl();
        String host = url.replaceAll("https?://", "").replaceAll(":\\d+$", "");
        if (host.contains(".") || isIpv4(host)) {
            return host;
        }
        return host + ".lakeon.svc.cluster.local";
    }

    private boolean isIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseSafekeeperUrls() {
        String urls = props.getNeon().getSafekeeperUrls();
        if (urls == null || urls.isBlank()) return List.of();
        return Arrays.asList(urls.split(","));
    }
}
