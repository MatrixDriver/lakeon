package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void dynamicMembershipOverridesStaticNodesWhenAvailable() {
        LakeonProperties props = placementProps();
        PageserverNodeProvider dynamicNodes = () -> List.of(
            new PageserverNode("ps-0", "http://10.0.0.10:9898", "pageserver-0.pageserver-headless.lakeon.svc.cluster.local", 6400),
            new PageserverNode("ps-1", "http://10.0.0.11:9898", "pageserver-1.pageserver-headless.lakeon.svc.cluster.local", 6400)
        );
        PageserverPlacementService service = new PageserverPlacementService(props, null, null, dynamicNodes);

        List<PageserverNode> nodes = service.configuredNodes();

        assertThat(nodes).extracting(PageserverNode::id).containsExactly("ps-0", "ps-1");
        assertThat(nodes.get(0).httpUrl()).isEqualTo("http://10.0.0.10:9898");
        assertThat(nodes.get(0).pgHost()).isEqualTo("pageserver-0.pageserver-headless.lakeon.svc.cluster.local");
    }

    @Test
    void dynamicMembershipFallsBackToStaticNodesWhenEmpty() {
        LakeonProperties props = placementProps();
        PageserverNodeProvider dynamicNodes = List::of;
        PageserverPlacementService service = new PageserverPlacementService(props, null, null, dynamicNodes);

        List<PageserverNode> nodes = service.configuredNodes();

        assertThat(nodes).extracting(PageserverNode::id).containsExactly("ps-0", "ps-1", "ps-2");
        assertThat(nodes.get(0).httpUrl()).isEqualTo("http://pageserver-0:9898");
    }

    @Test
    void dicerEnabledChoosesLowestLoadHealthyNodeAndPersistsAssignment() {
        LakeonProperties props = placementProps();
        props.getDicer().setEnabled(true);
        props.getDicer().setNodeLoadsRaw("ps-0=0.9,ps-1=0.1,ps-2=0.5");
        PageserverAssignmentRepository repo = mock(PageserverAssignmentRepository.class);
        when(repo.findById("tenant-a:0")).thenReturn(Optional.empty());
        PageserverPlacementService service = new PageserverPlacementService(props, repo);

        PageserverPlacement placement = service.resolve("tenant-a", 0);

        assertThat(placement.node().id()).isEqualTo("ps-1");
        assertThat(placement.source()).isEqualTo("dicer-load-aware");
        verify(repo).save(any(PageserverAssignmentEntity.class));
    }

    @Test
    void dicerLiveSnapshotOverridesStaticLoadsAndUnavailableNodes() {
        LakeonProperties props = placementProps();
        props.getDicer().setEnabled(true);
        props.getDicer().setNodeLoadsRaw("ps-0=0.0,ps-1=0.9,ps-2=0.8");
        PageserverLoadProvider liveLoads = () -> PageserverLoadSnapshot.fresh(
            Map.of("ps-0", 0.01d, "ps-1", 0.4d, "ps-2", 0.2d),
            Set.of("ps-0"),
            Instant.parse("2026-06-24T00:00:00Z"),
            "dicer-live");
        PageserverPlacementService service = new PageserverPlacementService(props, null, liveLoads);

        PageserverPlacement placement = service.resolve("tenant-a", 0);
        List<PageserverNodeStatus> statuses = service.nodeStatuses();

        assertThat(placement.node().id()).isEqualTo("ps-2");
        assertThat(placement.source()).isEqualTo("dicer-live");
        assertThat(statuses).extracting(PageserverNodeStatus::source).containsOnly("dicer-live");
        assertThat(statuses)
            .filteredOn(status -> status.node().id().equals("ps-0"))
            .singleElement()
            .satisfies(status -> assertThat(status.healthy()).isFalse());
    }

    @Test
    void existingAssignmentIsStableWhileNodeIsHealthy() {
        LakeonProperties props = placementProps();
        props.getDicer().setEnabled(true);
        props.getDicer().setNodeLoadsRaw("ps-0=0.9,ps-1=0.1,ps-2=0.5");
        PageserverAssignmentEntity assignment = assignment("tenant-a", 0, "ps-0", 7L, "dicer-load-aware");
        PageserverAssignmentRepository repo = mock(PageserverAssignmentRepository.class);
        when(repo.findById("tenant-a:0")).thenReturn(Optional.of(assignment));
        PageserverPlacementService service = new PageserverPlacementService(props, repo);

        PageserverPlacement placement = service.resolve("tenant-a", 0);

        assertThat(placement.node().id()).isEqualTo("ps-0");
        assertThat(placement.epoch()).isEqualTo(7L);
    }

    @Test
    void failoverMovesAssignmentsOffUnavailableNode() {
        LakeonProperties props = placementProps();
        props.getDicer().setEnabled(true);
        props.getDicer().setNodeLoadsRaw("ps-0=0.1,ps-1=0.2,ps-2=0.3");
        PageserverAssignmentEntity assignment = assignment("tenant-a", 0, "ps-0", 3L, "dicer-load-aware");
        PageserverAssignmentRepository repo = mock(PageserverAssignmentRepository.class);
        PageserverRebalanceEventService eventService = mock(PageserverRebalanceEventService.class);
        when(repo.findAll()).thenReturn(List.of(assignment));
        when(repo.findById("tenant-a:0")).thenReturn(Optional.of(assignment));
        PageserverPlacementService service = new PageserverPlacementService(props, repo, null, null, eventService);

        PageserverRebalancePlan plan = service.failoverNode("ps-0");

        assertThat(plan.dryRun()).isFalse();
        assertThat(plan.moves()).hasSize(1);
        assertThat(plan.moves().get(0).fromNodeId()).isEqualTo("ps-0");
        assertThat(plan.moves().get(0).toNodeId()).isEqualTo("ps-1");
        assertThat(plan.moves().get(0).nextEpoch()).isEqualTo(4L);
        verify(eventService).record(
            eq("FAILOVER_NODE"),
            eq("ADMIN"),
            eq("admin-api"),
            eq("ps-0"),
            eq(plan),
            eq("node-unavailable:ps-0"));
    }

    @Test
    void autoFailoverMovesAssignmentsOffLiveUnavailableNodes() {
        LakeonProperties props = placementProps();
        props.getDicer().setEnabled(true);
        PageserverAssignmentEntity assignment = assignment("tenant-a", 0, "ps-0", 3L, "dicer-live");
        PageserverAssignmentRepository repo = mock(PageserverAssignmentRepository.class);
        PageserverRebalanceEventService eventService = mock(PageserverRebalanceEventService.class);
        when(repo.findAll()).thenReturn(List.of(assignment));
        when(repo.findById("tenant-a:0")).thenReturn(Optional.of(assignment));
        PageserverLoadProvider liveLoads = () -> PageserverLoadSnapshot.fresh(
            Map.of("ps-1", 0.2d, "ps-2", 0.4d),
            Set.of("ps-0"),
            Instant.parse("2026-06-24T00:00:00Z"),
            "dicer-live",
            Map.of());
        PageserverPlacementService service = new PageserverPlacementService(props, repo, liveLoads, null, eventService);

        PageserverRebalancePlan plan = service.failoverUnavailableNodes();

        assertThat(plan.dryRun()).isFalse();
        assertThat(plan.moves()).hasSize(1);
        assertThat(plan.moves().get(0).fromNodeId()).isEqualTo("ps-0");
        assertThat(plan.moves().get(0).toNodeId()).isEqualTo("ps-1");
        verify(repo).save(any(PageserverAssignmentEntity.class));
        verify(eventService).record(
            eq("AUTO_FAILOVER"),
            eq("SCHEDULER"),
            eq("auto-failover"),
            eq("ps-0"),
            eq(plan),
            eq("auto-unavailable:ps-0"));
    }

    @Test
    void dryRunRecordsPlannedRebalanceEvent() {
        LakeonProperties props = placementProps();
        props.getDicer().setEnabled(true);
        props.getDicer().setNodeLoadsRaw("ps-0=0.9,ps-1=0.1,ps-2=0.2");
        PageserverAssignmentEntity assignment = assignment("tenant-a", 0, "ps-0", 1L, "dicer-load-aware");
        PageserverAssignmentRepository repo = mock(PageserverAssignmentRepository.class);
        PageserverRebalanceEventService eventService = mock(PageserverRebalanceEventService.class);
        when(repo.findAll()).thenReturn(List.of(assignment));
        PageserverPlacementService service = new PageserverPlacementService(props, repo, null, null, eventService);

        PageserverRebalancePlan plan = service.rebalanceDryRun();

        assertThat(plan.dryRun()).isTrue();
        assertThat(plan.moves()).hasSize(1);
        verify(eventService).record(
            eq("REBALANCE_DRY_RUN"),
            eq("ADMIN"),
            eq("admin-api"),
            eq(null),
            eq(plan),
            eq("rebalance-dry-run"));
    }

    private LakeonProperties placementProps() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverNodes(List.of(
            new LakeonProperties.PageserverNodeConfig("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-1", "http://pageserver-1:9898", "pageserver-1", 6400),
            new LakeonProperties.PageserverNodeConfig("ps-2", "http://pageserver-2:9898", "pageserver-2", 6400)
        ));
        return props;
    }

    private PageserverAssignmentEntity assignment(String tenantId, int shardId, String nodeId, long epoch, String source) {
        PageserverAssignmentEntity entity = new PageserverAssignmentEntity();
        entity.setId(PageserverAssignmentEntity.id(tenantId, shardId));
        entity.setTenantId(tenantId);
        entity.setShardId(shardId);
        entity.setNodeId(nodeId);
        entity.setEpoch(epoch);
        entity.setSource(source);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
