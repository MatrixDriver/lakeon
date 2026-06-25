package com.lakeon.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.pageserver.PageserverPlacementService;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

    @Test
    void buildPageserverPgConnstring_usesServiceDnsForShortServiceName() {
        LakeonProperties props = newProps();
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, new ObjectMapper());

        assertThat(builder.buildPageserverPgConnstring())
            .isEqualTo("postgresql://pageserver.lakeon.svc.cluster.local:6400");
    }

    @Test
    void buildPageserverPgConnstring_usesConfiguredIpDirectly() {
        LakeonProperties props = newProps();
        props.getNeon().setPageserverUrl("http://192.168.0.175:9898");
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, new ObjectMapper());

        assertThat(builder.buildPageserverPgConnstring())
            .isEqualTo("postgresql://192.168.0.175:6400");
    }

    @Test
    void buildPageserverPgConnstring_keepsConfiguredFqdn() {
        LakeonProperties props = newProps();
        props.getNeon().setPageserverUrl("http://pageserver.lakeon.svc.cluster.local:9898");
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, new ObjectMapper());

        assertThat(builder.buildPageserverPgConnstring())
            .isEqualTo("postgresql://pageserver.lakeon.svc.cluster.local:6400");
    }

    @Test
    void generateComputeConfig_usesTenantPlacementPageserverConnstring() throws Exception {
        LakeonProperties props = newProps();
        props.getNeon().setPageserverNodes(List.of(
            new LakeonProperties.PageserverNodeConfig("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-1", "http://pageserver-1:9898", "pageserver-1", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-2", "http://pageserver-2:9898", "pageserver-2", 6400)
        ));
        ObjectMapper om = new ObjectMapper();
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om, new PageserverPlacementService(props));
        DatabaseEntity e = new DatabaseEntity();
        e.setId("db_test");
        e.setName("test");
        e.setNeonTenantId("tenant-a");
        e.setNeonTimelineId("timeline-a");

        String json = builder.generateComputeConfig(e, 0);
        String conn = om.readTree(json).path("spec").path("pageserver_connstring").asText();
        String settingConn = om.readTree(json).path("spec").path("cluster").path("settings").findValues("value")
            .stream()
            .map(JsonNode::asText)
            .filter(v -> v.startsWith("postgresql://pageserver-"))
            .findFirst()
            .orElse("");

        assertThat(conn).startsWith("postgresql://pageserver-");
        assertThat(conn).endsWith(".lakeon.svc.cluster.local:6400");
        assertThat(settingConn).isEqualTo(conn);
    }

    @Test
    void generateComputeConfig_prefersPageserverWhereTenantIsAttached() throws Exception {
        WireMockServer ps0 = new WireMockServer(0);
        WireMockServer ps1 = new WireMockServer(0);
        ps0.start();
        ps1.start();
        try {
            ps0.stubFor(get(urlEqualTo("/v1/tenant/tenant-a"))
                .willReturn(aResponse().withStatus(404)));
            ps1.stubFor(get(urlEqualTo("/v1/tenant/tenant-a"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"tenant-a\"}")));

            LakeonProperties props = newProps();
            props.getNeon().setPageserverNodes(List.of(
                new LakeonProperties.PageserverNodeConfig("ps-0", ps0.baseUrl(), "pageserver-0", 6400),
                new LakeonProperties.PageserverNodeConfig("ps-1", ps1.baseUrl(), "pageserver-1", 6400)
            ));
            ObjectMapper om = new ObjectMapper();
            ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om, new PageserverPlacementService(props));
            DatabaseEntity e = new DatabaseEntity();
            e.setId("db_test");
            e.setName("test");
            e.setNeonTenantId("tenant-a");
            e.setNeonTimelineId("timeline-a");

            String json = builder.generateComputeConfig(e, 0);
            JsonNode root = om.readTree(json);

            assertThat(root.path("spec").path("pageserver_connstring").asText())
                .isEqualTo("postgresql://pageserver-1.lakeon.svc.cluster.local:6400");
            assertThat(root.path("spec").path("cluster").path("settings").findValues("value").stream()
                .map(JsonNode::asText)
                .filter("postgresql://pageserver-1.lakeon.svc.cluster.local:6400"::equals)
                .count()).isEqualTo(1);
        } finally {
            ps0.stop();
            ps1.stop();
        }
    }
}
