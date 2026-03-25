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
        assertThat(statements).hasSize(1);

        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) statements.get(0).get("Resource");
        assertThat(resources).anyMatch(r -> r.contains("/datasets/tn_abc123/"));
        assertThat(resources).anyMatch(r -> r.contains("/knowledge/tn_abc123/"));
        assertThat(resources).allMatch(r -> r.contains("lakeon-storage"));
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
