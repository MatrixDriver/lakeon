package com.lakeon.neon;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.lakeon.config.LakeonProperties;
import com.lakeon.pageserver.PageserverPlacement;
import com.lakeon.pageserver.PageserverPlacementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class NeonApiClientPlacementTest {
    private final WireMockServer ps0 = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private final WireMockServer ps1 = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private final WireMockServer ps2 = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @AfterEach
    void stopServers() {
        ps0.stop();
        ps1.stop();
        ps2.stop();
    }

    @Test
    void tenantScopedCallsUsePlacementPageserverUrl() {
        ps0.start();
        ps1.start();
        ps2.start();
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverUrl(ps0.baseUrl());
        props.getNeon().setPageserverNodes(List.of(
            new LakeonProperties.PageserverNodeConfig("ps-0", ps0.baseUrl(), "pageserver-0", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-1", ps1.baseUrl(), "pageserver-1", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-2", ps2.baseUrl(), "pageserver-2", 6400)
        ));
        PageserverPlacementService placementService = new PageserverPlacementService(props);
        PageserverPlacement placement = placementService.resolve("tenant-a", 0);
        WireMockServer expected = serverFor(placement.node().id());
        expected.stubFor(put(urlEqualTo("/v1/tenant/tenant-a/location_config"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        NeonApiClient client = new NeonApiClient(props, placementService);
        client.createTenant("tenant-a");

        expected.verify(putRequestedFor(urlEqualTo("/v1/tenant/tenant-a/location_config")));
        assertThat(List.of(ps0, ps1, ps2).stream()
            .filter(server -> server != expected)
            .mapToInt(server -> server.findAllUnmatchedRequests().size() + server.getAllServeEvents().size())
            .sum()).isEqualTo(0);
    }

    private WireMockServer serverFor(String nodeId) {
        return switch (nodeId) {
            case "ps-0" -> ps0;
            case "ps-1" -> ps1;
            case "ps-2" -> ps2;
            default -> throw new IllegalArgumentException("unexpected node id " + nodeId);
        };
    }
}
