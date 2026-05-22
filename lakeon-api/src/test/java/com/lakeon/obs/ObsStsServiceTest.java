package com.lakeon.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertThat(resources).anyMatch(r -> r.contains("/datasets/tn_abc123/"));
        assertThat(resources).anyMatch(r -> r.contains("/knowledge/tn_abc123/"));
        assertThat(resources).anyMatch(r -> r.contains("/tenant-tn_abc123/"));
        assertThat(resources).anyMatch(r -> r.contains("/datalake-logs/tn_abc123/"));
        assertThat(resources).anyMatch(r -> r.contains("/datasources/tn_abc123/"));
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
                "datasets/tn_abc123/",
                "knowledge/tn_abc123/",
                "tenant-tn_abc123/",
                "datalake-logs/tn_abc123/",
                "datasources/tn_abc123/"
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
}
