package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;

import java.util.*;

/**
 * Shared utility for generating Neon compute_ctl config JSON.
 */
public class ComputeSpecBuilder {

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;

    public ComputeSpecBuilder(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate compute config JSON for a Neon compute_ctl instance.
     * @param entity database entity with tenant/timeline IDs
     * @param suspendTimeoutSeconds suspend timeout in seconds (0 = never auto-suspend)
     */
    public String generateComputeConfig(DatabaseEntity entity, int suspendTimeoutSeconds) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("format_version", 2);
        spec.put("operation_uuid", UUID.randomUUID().toString());
        spec.put("tenant_id", entity.getNeonTenantId());
        spec.put("timeline_id", entity.getNeonTimelineId());
        String pageserverFqdn = extractPageserverHost() + ".lakeon.svc.cluster.local";
        spec.put("pageserver_connstring", "postgresql://" + pageserverFqdn + ":6400");
        spec.put("safekeeper_connstrings", parseSafekeeperUrls());
        spec.put("mode", "Primary");
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
            } catch (Exception ex) {
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
        String pageserverFqdn = extractPageserverHost() + ".lakeon.svc.cluster.local";
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
                "postgresql://pageserver.lakeon.svc.cluster.local:6400", "vartype", "string"));
        settings.add(Map.of("name", "neon.safekeepers", "value",
                props.getNeon().getSafekeeperUrls(), "vartype", "string"));
        settings.add(Map.of("name", "neon.tenant_id", "value",
                entity.getNeonTenantId(), "vartype", "string"));
        settings.add(Map.of("name", "neon.timeline_id", "value",
                entity.getNeonTimelineId(), "vartype", "string"));
        return settings;
    }

    private String extractPageserverHost() {
        String url = props.getNeon().getPageserverUrl();
        return url.replaceAll("https?://", "").replaceAll(":\\d+$", "");
    }

    private List<String> parseSafekeeperUrls() {
        String urls = props.getNeon().getSafekeeperUrls();
        if (urls == null || urls.isBlank()) return List.of();
        return Arrays.asList(urls.split(","));
    }
}
