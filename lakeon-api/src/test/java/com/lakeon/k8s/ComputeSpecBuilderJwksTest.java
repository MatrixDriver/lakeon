package com.lakeon.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ComputeSpecBuilderJwksTest {
    private static LakeonProperties newProps() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverUrl("http://pageserver:9898");
        props.getNeon().setSafekeeperUrls("safekeeper-0:5454");
        return props;
    }

    @Test
    void generateComputeConfig_includesJwksKey_whenPublicJwkConfigured() throws Exception {
        LakeonProperties props = newProps();
        props.getComputeJwt().setPublicJwk(
            "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"k1\",\"n\":\"AA\",\"e\":\"AQAB\"}");
        ObjectMapper om = new ObjectMapper();
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om);

        DatabaseEntity e = new DatabaseEntity();
        e.setId("db_test"); e.setName("test"); e.setNeonTenantId("t"); e.setNeonTimelineId("tl");

        String json = builder.generateComputeConfig(e, 0);
        JsonNode root = om.readTree(json);
        JsonNode keys = root.path("compute_ctl_config").path("jwks").path("keys");
        assertThat(keys.isArray()).isTrue();
        assertThat(keys.size()).isEqualTo(1);
        assertThat(keys.get(0).get("kid").asText()).isEqualTo("k1");
    }

    @Test
    void generateComputeConfig_emptyJwks_whenNoPublicKeyConfigured() throws Exception {
        LakeonProperties props = newProps();
        // publicJwk left null/empty
        ObjectMapper om = new ObjectMapper();
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om);
        DatabaseEntity e = new DatabaseEntity();
        e.setId("db_test"); e.setName("test"); e.setNeonTenantId("t"); e.setNeonTimelineId("tl");

        String json = builder.generateComputeConfig(e, 0);
        JsonNode keys = om.readTree(json).path("compute_ctl_config").path("jwks").path("keys");
        assertThat(keys.isArray()).isTrue();
        assertThat(keys.size()).isEqualTo(0);
    }
}
