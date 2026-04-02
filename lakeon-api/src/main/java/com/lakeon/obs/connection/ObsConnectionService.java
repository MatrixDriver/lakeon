package com.lakeon.obs.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ObsConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ObsConnectionService.class);

    private final ObsConnectionRepository repository;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ObsConnectionService(ObsConnectionRepository repository,
                                LakeonProperties props,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Transactional
    public ObsConnectionEntity create(String tenantId, String name, String domainName,
                                       String agencyName, String obsEndpoint,
                                       String bucket, String basePath) {
        if (name == null || name.isBlank()) throw new BadRequestException("Connection name is required");
        if (domainName == null || domainName.isBlank()) throw new BadRequestException("Domain name is required");
        if (agencyName == null || agencyName.isBlank()) throw new BadRequestException("Agency name is required");
        if (bucket == null || bucket.isBlank()) throw new BadRequestException("Bucket name is required");

        ObsConnectionEntity entity = new ObsConnectionEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDomainName(domainName);
        entity.setAgencyName(agencyName);
        entity.setBucket(bucket);
        entity.setStatus("ACTIVE");

        if (obsEndpoint != null && !obsEndpoint.isBlank()) {
            entity.setObsEndpoint(obsEndpoint);
        }
        if (basePath != null && !basePath.isBlank()) {
            entity.setBasePath(basePath);
        }

        repository.save(entity);
        log.info("Created OBS connection {} for tenant {}", entity.getId(), tenantId);
        return entity;
    }

    public List<ObsConnectionEntity> list(String tenantId) {
        return repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public ObsConnectionEntity get(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException("OBS connection not found: " + id));
    }

    @Transactional
    public void delete(String tenantId, String id) {
        ObsConnectionEntity entity = get(tenantId, id);
        repository.delete(entity);
        log.info("Deleted OBS connection {} for tenant {}", id, tenantId);
    }

    /**
     * Test connection by trying to obtain agency credentials and list bucket objects.
     * Phase 1: simplified — uses DBay's own AK/SK to try listing the user's bucket.
     */
    @Transactional
    public ObsConnectionEntity testConnection(String tenantId, String id) {
        ObsConnectionEntity conn = get(tenantId, id);

        try {
            // Try to get temporary credentials via IAM agency
            AgencyCredentials creds = getAgencyCredentials(conn);

            // Use temporary credentials to list bucket objects (verify access)
            listBucketObjects(conn, creds, conn.getBasePath(), 1);

            conn.setStatus("ACTIVE");
            conn.setLastTestedAt(Instant.now());
            repository.save(conn);
            log.info("OBS connection {} test succeeded", id);
        } catch (Exception e) {
            conn.setStatus("FAILED");
            conn.setLastTestedAt(Instant.now());
            repository.save(conn);
            log.warn("OBS connection {} test failed: {}", id, e.getMessage());
            throw new BadRequestException("Connection test failed: " + e.getMessage());
        }

        return conn;
    }

    /**
     * Browse files in the connected OBS bucket using temporary agency credentials.
     */
    public List<Map<String, Object>> browseFiles(String tenantId, String connectionId, String path) {
        ObsConnectionEntity conn = get(tenantId, connectionId);

        try {
            AgencyCredentials creds = getAgencyCredentials(conn);
            return listBucketObjects(conn, creds, path, 200);
        } catch (Exception e) {
            log.warn("Failed to browse OBS connection {}: {}", connectionId, e.getMessage());
            throw new BadRequestException("Failed to browse: " + e.getMessage());
        }
    }

    /**
     * Get temporary credentials for a connection (for pipeline use).
     */
    public AgencyCredentials getTemporaryCredentials(String tenantId, String connectionId) {
        ObsConnectionEntity conn = get(tenantId, connectionId);
        return getAgencyCredentials(conn);
    }

    // --- IAM Agency Credential Fetching ---

    public record AgencyCredentials(String accessKey, String secretKey, String securityToken, Instant expiresAt) {}

    /**
     * Two-step process:
     * 1. Get IAM token using DBay's AK/SK
     * 2. Use IAM token to assume role (agency) and get temporary credentials
     */
    private AgencyCredentials getAgencyCredentials(ObsConnectionEntity conn) {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();
        String region = props.getObs().getRegion();

        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            throw new IllegalStateException("OBS AK/SK not configured; cannot fetch agency credentials");
        }

        try {
            // Step 1: Get IAM token using DBay's AK/SK
            String iamBody = String.format(
                    "{\"auth\":{\"identity\":{\"methods\":[\"hw_ak_sk\"]," +
                    "\"hw_ak_sk\":{\"access\":{\"key\":\"%s\"},\"secret\":{\"key\":\"%s\"}}}," +
                    "\"scope\":{\"project\":{\"name\":\"%s\"}}}}", ak, sk, region);

            HttpRequest iamRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://iam." + region + ".myhuaweicloud.com/v3/auth/tokens"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(iamBody))
                    .build();

            HttpResponse<String> iamResponse = httpClient.send(iamRequest, HttpResponse.BodyHandlers.ofString());
            if (iamResponse.statusCode() != 201) {
                throw new RuntimeException("IAM token request failed: HTTP " + iamResponse.statusCode());
            }

            String iamToken = iamResponse.headers().firstValue("x-subject-token")
                    .orElseThrow(() -> new RuntimeException("IAM response missing x-subject-token"));

            // Step 2: Assume role via agency
            String assumeBody = objectMapper.writeValueAsString(Map.of(
                "auth", Map.of(
                    "identity", Map.of(
                        "methods", List.of("assume_role"),
                        "assume_role", Map.of(
                            "domain_name", conn.getDomainName(),
                            "agency_name", conn.getAgencyName(),
                            "duration_seconds", 3600
                        )
                    )
                )
            ));

            HttpRequest assumeRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://iam." + region + ".myhuaweicloud.com/v3.0/OS-CREDENTIAL/securitytokens"))
                    .header("Content-Type", "application/json")
                    .header("X-Auth-Token", iamToken)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(assumeBody))
                    .build();

            HttpResponse<String> assumeResponse = httpClient.send(assumeRequest, HttpResponse.BodyHandlers.ofString());
            if (assumeResponse.statusCode() != 200 && assumeResponse.statusCode() != 201) {
                throw new RuntimeException("Agency assume_role failed: HTTP " + assumeResponse.statusCode()
                        + " - " + assumeResponse.body());
            }

            JsonNode root = objectMapper.readTree(assumeResponse.body());
            JsonNode credential = root.path("credential");
            return new AgencyCredentials(
                    credential.path("access").asText(),
                    credential.path("secret").asText(),
                    credential.path("securitytoken").asText(),
                    Instant.parse(credential.path("expires_at").asText())
            );

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get agency credentials: " + e.getMessage(), e);
        }
    }

    /**
     * List objects in OBS bucket using S3-compatible API with temporary credentials.
     * Uses AWS S3 SDK since OBS is S3-compatible.
     */
    private List<Map<String, Object>> listBucketObjects(ObsConnectionEntity conn,
                                                         AgencyCredentials creds,
                                                         String prefix, int maxKeys) {
        try {
            var awsCreds = software.amazon.awssdk.auth.credentials.AwsSessionCredentials.create(
                    creds.accessKey(), creds.secretKey(), creds.securityToken());
            var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                    .endpointOverride(URI.create(conn.getObsEndpoint()))
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(awsCreds))
                    .region(software.amazon.awssdk.regions.Region.of(props.getObs().getRegion()))
                    .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                            .pathStyleAccessEnabled(false).build())
                    .build();

            String effectivePrefix = prefix;
            if (effectivePrefix == null || effectivePrefix.isBlank()) {
                effectivePrefix = conn.getBasePath() != null ? conn.getBasePath() : "";
            }
            // Ensure prefix ends with / for directory-like listing (unless empty)
            if (!effectivePrefix.isEmpty() && !effectivePrefix.endsWith("/")) {
                effectivePrefix += "/";
            }

            var request = software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                    .bucket(conn.getBucket())
                    .prefix(effectivePrefix)
                    .delimiter("/")
                    .maxKeys(maxKeys)
                    .build();

            var response = s3.listObjectsV2(request);

            List<Map<String, Object>> items = new ArrayList<>();

            // Add common prefixes (directories)
            if (response.commonPrefixes() != null) {
                for (var cp : response.commonPrefixes()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", cp.prefix());
                    item.put("name", extractName(cp.prefix()));
                    item.put("type", "directory");
                    items.add(item);
                }
            }

            // Add objects (files)
            if (response.contents() != null) {
                for (var obj : response.contents()) {
                    // Skip the prefix itself
                    if (obj.key().equals(effectivePrefix)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", obj.key());
                    item.put("name", extractName(obj.key()));
                    item.put("type", "file");
                    item.put("size", obj.size());
                    item.put("last_modified", obj.lastModified() != null ? obj.lastModified().toString() : null);
                    items.add(item);
                }
            }

            s3.close();
            return items;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list OBS objects: " + e.getMessage(), e);
        }
    }

    private String extractName(String key) {
        if (key.endsWith("/")) key = key.substring(0, key.length() - 1);
        int idx = key.lastIndexOf('/');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }
}
