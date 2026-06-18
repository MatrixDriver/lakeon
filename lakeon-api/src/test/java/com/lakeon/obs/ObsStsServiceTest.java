package com.lakeon.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.hwcloud.HuaweiIamCredentialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObsStsServiceTest {

    private ObsStsService service;

    @BeforeEach
    void setUp() {
        LakeonProperties props = new LakeonProperties();
        props.getObs().setBucket("lakeon-storage");
        props.getObs().setRegion("cn-north-4");
        service = new ObsStsService(props, new ObjectMapper());
    }

    @Test
    void buildPolicyScopesToTenant() {
        Map<String, Object> policy = service.buildPolicy("tn_abc123");

        assertThat(policy).containsKey("Statement");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) policy.get("Statement");
        // Two statements: (1) object-level Allow on tenant prefixes,
        // (2) bucket-level ListBucket restricted via StringLike prefix condition.
        assertThat(statements).hasSize(2);

        // Statement 1: object-level Allow
        Map<String, Object> objectStatement = statements.get(0);
        assertThat(objectStatement).containsEntry("Effect", "Allow");
        @SuppressWarnings("unchecked")
        List<String> objectActions = (List<String>) objectStatement.get("Action");
        assertThat(objectActions).contains(
                "obs:object:GetObject",
                "obs:object:PutObject",
                "obs:object:AbortMultipartUpload",
                "obs:object:ListMultipartUploadParts"
        );
        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) objectStatement.get("Resource");
        assertThat(resources).anyMatch(r -> r.contains("/tenant-tn_abc123/"));
        assertThat(resources).allMatch(r -> r.contains("lakeon-storage"));
        assertThat(resources).allMatch(r -> r.startsWith("obs:*:*:object:"));

        // Statement 2: bucket-level ListBucket with prefix condition
        Map<String, Object> listStatement = statements.get(1);
        assertThat(listStatement).containsEntry("Effect", "Allow");
        @SuppressWarnings("unchecked")
        List<String> listActions = (List<String>) listStatement.get("Action");
        assertThat(listActions).containsExactly("obs:bucket:ListBucket");
        @SuppressWarnings("unchecked")
        List<String> listResources = (List<String>) listStatement.get("Resource");
        assertThat(listResources).containsExactly("obs:*:*:bucket:lakeon-storage");

        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) listStatement.get("Condition");
        assertThat(condition).containsKey("StringLike");
        @SuppressWarnings("unchecked")
        Map<String, Object> stringLike = (Map<String, Object>) condition.get("StringLike");
        assertThat(stringLike).containsKey("obs:prefix");
        @SuppressWarnings("unchecked")
        List<String> prefixes = (List<String>) stringLike.get("obs:prefix");
        assertThat(prefixes).contains(
                "tenant-tn_abc123/"
        );
    }

    @Test
    void buildPolicyUsesBucketFromConfig() {
        LakeonProperties props = new LakeonProperties();
        props.getObs().setBucket("my-custom-bucket");
        ObsStsService customService = new ObsStsService(props, new ObjectMapper());

        Map<String, Object> policy = customService.buildPolicy("tn_xyz");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) policy.get("Statement");
        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) statements.get(0).get("Resource");
        assertThat(resources).allMatch(r -> r.contains("my-custom-bucket"));
        assertThat(resources).noneMatch(r -> r.contains("lakeon-storage"));
    }

    @Test
    void buildPolicyNoDoublePrefix() {
        // tenantId already contains "tn_" prefix — ensure no "tn_tn_" double prefix in resource ARNs
        Map<String, Object> policy = service.buildPolicy("tn_myorg");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) policy.get("Statement");
        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) statements.get(0).get("Resource");
        assertThat(resources).noneMatch(r -> r.contains("tn_tn_"));
        assertThat(resources).allMatch(r -> r.contains("tn_myorg"));
    }

    @Test
    void getCredentialsUsesIamSdkClientWithTenantPolicy() {
        LakeonProperties props = new LakeonProperties();
        props.getObs().setBucket("lakeon-storage");
        props.getObs().setRegion("cn-north-4");
        props.getObs().setAccessKey("ak");
        props.getObs().setSecretKey("sk");
        RecordingIamCredentialClient iam = new RecordingIamCredentialClient();
        ObsStsService sdkBackedService = new ObsStsService(props, new ObjectMapper(), iam);

        ObsStsService.StsCredentials credentials = sdkBackedService.getCredentials("tn_abc123");

        assertThat(credentials.accessKey()).isEqualTo("tmp-ak");
        assertThat(credentials.secretKey()).isEqualTo("tmp-sk");
        assertThat(credentials.sessionToken()).isEqualTo("tmp-token");
        assertThat(iam.lastDurationSeconds).isEqualTo(7200);
        assertThat(iam.lastPolicy).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) iam.lastPolicy.get("Statement");
        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) statements.get(0).get("Resource");
        assertThat(resources).anyMatch(r -> r.contains("/tenant-tn_abc123/"));
    }

    private static class RecordingIamCredentialClient implements HuaweiIamCredentialClient {
        Map<String, Object> lastPolicy;
        int lastDurationSeconds;

        @Override
        public TemporaryCredentials createTemporaryAccessKeyByToken(Map<String, Object> policy,
                                                                    int durationSeconds) {
            this.lastPolicy = policy;
            this.lastDurationSeconds = durationSeconds;
            return new TemporaryCredentials(
                    "tmp-ak",
                    "tmp-sk",
                    "tmp-token",
                    Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public TemporaryCredentials createTemporaryAccessKeyByAgency(String domainId,
                                                                     String domainName,
                                                                     String agencyName,
                                                                     int durationSeconds,
                                                                     Map<String, Object> policy) {
            throw new AssertionError("agency credentials should not be used for tenant-scoped DBay OBS STS");
        }
    }
}
