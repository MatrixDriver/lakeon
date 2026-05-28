package com.lakeon.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lakeon.config.LakeonProperties;
import com.lakeon.hwcloud.HuaweiIamCredentialClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private final HuaweiIamCredentialClient iamCredentialClient;
    private final Cache<String, StsCredentials> cache;

    @Autowired
    public ObsStsService(LakeonProperties props,
                         ObjectMapper objectMapper,
                         HuaweiIamCredentialClient iamCredentialClient) {
        this.props = props;
        this.iamCredentialClient = iamCredentialClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(100, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
    }

    ObsStsService(LakeonProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, null);
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
        List<String> tenantPrefixes = List.of(
                "datasets/" + tenantId + "/",
                "knowledge/" + tenantId + "/",
                "tenant-" + tenantId + "/",
                "datalake-logs/" + tenantId + "/",
                "datasources/" + tenantId + "/"
        );
        List<String> resources = tenantPrefixes.stream()
                .map(p -> "obs:*:*:object:" + bucket + "/" + p + "*")
                .toList();

        Map<String, Object> objectStatement = Map.of(
                "Effect", "Allow",
                "Action", List.of(
                        "obs:object:GetObject",
                        "obs:object:PutObject",
                        "obs:object:AbortMultipartUpload",
                        "obs:object:ListMultipartUploadParts"
                ),
                "Resource", resources
        );

        // ListBucket restricted to tenant prefixes only via Condition
        Map<String, Object> listStatement = Map.of(
                "Effect", "Allow",
                "Action", List.of("obs:bucket:ListBucket"),
                "Resource", List.of("obs:*:*:bucket:" + bucket),
                "Condition", Map.of(
                        "StringLike", Map.of(
                                "obs:prefix", tenantPrefixes
                        )
                )
        );

        return Map.of(
                "Version", "1.1",
                "Statement", List.of(objectStatement, listStatement)
        );
    }

    /**
     * Uses Huawei Cloud IAM SDK AK/SK signing directly. This avoids the older
     * AK/SK -> IAM token -> STS token exchange and lets the SDK generate Authorization.
     */
    private StsCredentials fetchFromIam(String tenantId) {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();

        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            log.debug("ObsStsService: AK/SK not configured (local dev), cannot fetch STS credentials for tenant '{}'", tenantId);
            throw new IllegalStateException("OBS AK/SK not configured; cannot fetch STS credentials");
        }
        if (iamCredentialClient == null) {
            throw new IllegalStateException("Huawei IAM credential client not configured");
        }

        try {
            Map<String, Object> policy = buildPolicy(tenantId);
            HuaweiIamCredentialClient.TemporaryCredentials credential =
                    iamCredentialClient.createTemporaryAccessKeyByToken(policy, 7200);

            log.info("ObsStsService: fetched STS credentials for tenant '{}', expires at {}",
                    tenantId, credential.expiresAt());
            return new StsCredentials(
                    credential.accessKey(),
                    credential.secretKey(),
                    credential.securityToken(),
                    credential.expiresAt());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("ObsStsService: failed to fetch STS credentials for tenant '{}'", tenantId, e);
            throw new RuntimeException("Failed to fetch STS credentials for tenant " + tenantId, e);
        }
    }
}
