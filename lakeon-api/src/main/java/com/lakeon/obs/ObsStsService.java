package com.lakeon.obs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Provides tenant-scoped OBS STS temporary credentials via Huawei Cloud IAM.
 * Credentials are cached in Caffeine for 23 hours (IAM STS token validity is 24 hours).
 */
@Service
public class ObsStsService {

    private static final Logger log = LoggerFactory.getLogger(ObsStsService.class);

    public record StsCredentials(String accessKey, String secretKey, String sessionToken, Instant expiresAt) {}

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Cache<String, StsCredentials> cache;

    public ObsStsService(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(23, TimeUnit.HOURS)
                .maximumSize(1000)
                .build();
    }

    /**
     * Returns cached or freshly-fetched STS credentials scoped to the given tenant.
     */
    public StsCredentials getCredentials(String tenantId) {
        return cache.get(tenantId, this::fetchFromIam);
    }

    /**
     * Builds an IAM policy JSON document that restricts access to the tenant's OBS prefixes.
     * Package-private for testing.
     */
    Map<String, Object> buildPolicy(String tenantId) {
        String bucket = props.getObs().getBucket();
        List<String> resources = List.of(
                "obs:*:*:object:" + bucket + "/datasets/" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/knowledge/" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/tenant-" + tenantId + "/*",
                "obs:*:*:object:" + bucket + "/datalake-logs/" + tenantId + "/*"
        );

        Map<String, Object> statement = Map.of(
                "Effect", "Allow",
                "Action", List.of(
                        "obs:object:GetObject",
                        "obs:object:PutObject",
                        "obs:object:DeleteObject",
                        "obs:object:AbortMultipartUpload",
                        "obs:object:ListMultipartUploadParts"
                ),
                "Resource", resources
        );

        return Map.of(
                "Version", "1.1",
                "Statement", List.of(statement)
        );
    }

    /**
     * Two-step IAM call:
     * 1. AK/SK → IAM token (POST /v3/auth/tokens with hw_ak_sk method)
     * 2. IAM token + policy → STS credentials (POST /v3.0/OS-CREDENTIAL/securitytokens)
     */
    private StsCredentials fetchFromIam(String tenantId) {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();

        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            log.debug("ObsStsService: AK/SK not configured (local dev), cannot fetch STS credentials for tenant '{}'", tenantId);
            throw new IllegalStateException("OBS AK/SK not configured; cannot fetch STS credentials");
        }

        String region = props.getObs().getRegion();

        try {
            // Step 1: Get IAM token using AK/SK
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
                log.error("ObsStsService: IAM token request failed HTTP {} - {}",
                        iamResponse.statusCode(), iamResponse.body());
                throw new RuntimeException("IAM token request failed with status " + iamResponse.statusCode());
            }

            String iamToken = iamResponse.headers().firstValue("x-subject-token").orElse(null);
            if (iamToken == null || iamToken.isBlank()) {
                log.error("ObsStsService: IAM response missing x-subject-token header");
                throw new RuntimeException("IAM response missing x-subject-token header");
            }
            log.debug("ObsStsService: obtained IAM token (length={}) for tenant '{}'", iamToken.length(), tenantId);

            // Step 2: Get STS credentials with tenant-scoped policy
            Map<String, Object> policy = buildPolicy(tenantId);
            Map<String, Object> stsBody = Map.of(
                    "auth", Map.of(
                            "identity", Map.of(
                                    "methods", List.of("token"),
                                    "policy", policy,
                                    "token", Map.of("duration_seconds", 86400)
                            )
                    )
            );

            String stsBodyJson = objectMapper.writeValueAsString(stsBody);
            HttpRequest stsRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://iam." + region + ".myhuaweicloud.com/v3.0/OS-CREDENTIAL/securitytokens"))
                    .header("Content-Type", "application/json")
                    .header("X-Auth-Token", iamToken)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(stsBodyJson))
                    .build();

            HttpResponse<String> stsResponse = httpClient.send(stsRequest, HttpResponse.BodyHandlers.ofString());
            if (stsResponse.statusCode() != 200 && stsResponse.statusCode() != 201) {
                log.error("ObsStsService: STS credentials request failed HTTP {} - {}",
                        stsResponse.statusCode(), stsResponse.body());
                throw new RuntimeException("STS credentials request failed with status " + stsResponse.statusCode());
            }

            JsonNode root = objectMapper.readTree(stsResponse.body());
            JsonNode credential = root.path("credential");
            String accessKey = credential.path("access").asText();
            String secretKey = credential.path("secret").asText();
            String sessionToken = credential.path("securitytoken").asText();
            String expiresAtStr = credential.path("expires_at").asText();
            Instant expiresAt = Instant.parse(expiresAtStr);

            log.info("ObsStsService: fetched STS credentials for tenant '{}', expires at {}", tenantId, expiresAt);
            return new StsCredentials(accessKey, secretKey, sessionToken, expiresAt);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("ObsStsService: failed to fetch STS credentials for tenant '{}'", tenantId, e);
            throw new RuntimeException("Failed to fetch STS credentials for tenant " + tenantId, e);
        }
    }
}
