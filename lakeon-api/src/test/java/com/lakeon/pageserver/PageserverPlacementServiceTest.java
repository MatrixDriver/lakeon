package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageserverPlacementServiceTest {

    @Test
    void resolvesConfiguredNodesInDeterministicOrder() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverNodes(List.of(
            new LakeonProperties.PageserverNodeConfig("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-1", "http://pageserver-1:9898", "pageserver-1", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-2", "http://pageserver-2:9898", "pageserver-2", 6400)
        ));
        PageserverPlacementService service = new PageserverPlacementService(props);

        PageserverPlacement first = service.resolve("tenant-a", 0);
        PageserverPlacement second = service.resolve("tenant-a", 0);

        assertThat(first).isEqualTo(second);
        assertThat(first.tenantId()).isEqualTo("tenant-a");
        assertThat(first.shardId()).isEqualTo(0);
        assertThat(first.node().id()).startsWith("ps-");
        assertThat(first.node().httpUrl()).startsWith("http://pageserver-");
        assertThat(first.epoch()).isEqualTo(1L);
        assertThat(first.source()).isEqualTo("static-hash");
    }

    @Test
    void fallsBackToLegacyPageserverUrlWhenNoNodesConfigured() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverUrl("http://pageserver:9898");
        PageserverPlacementService service = new PageserverPlacementService(props);

        PageserverPlacement placement = service.resolve("tenant-a", 0);

        assertThat(placement.node().id()).isEqualTo("default");
        assertThat(placement.node().httpUrl()).isEqualTo("http://pageserver:9898");
        assertThat(placement.node().pgHost()).isEqualTo("pageserver.lakeon.svc.cluster.local");
        assertThat(placement.node().pgPort()).isEqualTo(6400);
        assertThat(placement.source()).isEqualTo("legacy-default");
    }

    @Test
    void shortPgHostIsExpandedToClusterDns() {
        PageserverNode node = new PageserverNode("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400);

        assertThat(node.pgConnstring()).isEqualTo("postgresql://pageserver-0.lakeon.svc.cluster.local:6400");
    }

    @Test
    void fqdnAndIpPgHostsAreKeptAsConfigured() {
        PageserverNode fqdn = new PageserverNode(
            "ps-0", "http://pageserver-0.lakeon.svc.cluster.local:9898",
            "pageserver-0.lakeon.svc.cluster.local", 6400);
        PageserverNode ip = new PageserverNode("ps-ip", "http://192.168.0.10:9898", "192.168.0.10", 6400);

        assertThat(fqdn.pgConnstring()).isEqualTo("postgresql://pageserver-0.lakeon.svc.cluster.local:6400");
        assertThat(ip.pgConnstring()).isEqualTo("postgresql://192.168.0.10:6400");
    }

    @Test
    void parsesRawPageserverNodesFromEnvironmentFriendlyString() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverNodesRaw(String.join(",",
            "ps-0=http://pageserver-0.pageserver-headless.lakeon.svc.cluster.local:9898|pageserver-0.pageserver-headless.lakeon.svc.cluster.local|6400",
            "ps-1=http://pageserver-1.pageserver-headless.lakeon.svc.cluster.local:9898|pageserver-1.pageserver-headless.lakeon.svc.cluster.local|6400"
        ));

        List<PageserverNode> nodes = new PageserverPlacementService(props).configuredNodes();

        assertThat(nodes).extracting(PageserverNode::id).containsExactly("ps-0", "ps-1");
        assertThat(nodes.get(1).httpUrl()).isEqualTo("http://pageserver-1.pageserver-headless.lakeon.svc.cluster.local:9898");
    }
}
