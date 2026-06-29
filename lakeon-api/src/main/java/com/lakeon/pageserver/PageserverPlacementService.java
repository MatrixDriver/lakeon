package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PageserverPlacementService {
    private static final int DEFAULT_PG_PORT = 6400;

    private final LakeonProperties props;
    private final PageserverAssignmentRepository assignments;
    private final PageserverLoadProvider loadProvider;
    private final PageserverNodeProvider nodeProvider;
    private final PageserverRebalanceEventService eventService;

    public PageserverPlacementService(LakeonProperties props) {
        this(props, (PageserverAssignmentRepository) null);
    }

    @Autowired
    public PageserverPlacementService(LakeonProperties props,
                                      ObjectProvider<PageserverAssignmentRepository> assignmentsProvider,
                                      ObjectProvider<PageserverLoadProvider> loadProvider,
                                      ObjectProvider<PageserverNodeProvider> nodeProvider,
                                      ObjectProvider<PageserverRebalanceEventService> eventService) {
        this(
            props,
            assignmentsProvider.getIfAvailable(),
            loadProvider.getIfAvailable(),
            nodeProvider.getIfAvailable(),
            eventService.getIfAvailable());
    }

    public PageserverPlacementService(LakeonProperties props, PageserverAssignmentRepository assignments) {
        this(props, assignments, null, null);
    }

    public PageserverPlacementService(LakeonProperties props,
                                      PageserverAssignmentRepository assignments,
                                      PageserverLoadProvider loadProvider) {
        this(props, assignments, loadProvider, null);
    }

    public PageserverPlacementService(LakeonProperties props,
                                      PageserverAssignmentRepository assignments,
                                      PageserverLoadProvider loadProvider,
                                      PageserverNodeProvider nodeProvider) {
        this(props, assignments, loadProvider, nodeProvider, null);
    }

    public PageserverPlacementService(LakeonProperties props,
                                      PageserverAssignmentRepository assignments,
                                      PageserverLoadProvider loadProvider,
                                      PageserverNodeProvider nodeProvider,
                                      PageserverRebalanceEventService eventService) {
        this.props = props;
        this.assignments = assignments;
        this.loadProvider = loadProvider;
        this.nodeProvider = nodeProvider;
        this.eventService = eventService;
    }

    @Transactional
    public PageserverPlacement resolve(String tenantId, int shardId) {
        List<PageserverNode> nodes = configuredNodes();
        if (nodes.isEmpty()) {
            return new PageserverPlacement(tenantId, shardId, legacyDefaultNode(), 1L, "legacy-default");
        }
        Map<String, PageserverNode> nodesById = byId(nodes);
        Set<String> unavailable = unavailableNodeIds();

        Optional<PageserverAssignmentEntity> current = findAssignment(tenantId, shardId);
        if (current.isPresent()) {
            PageserverAssignmentEntity entity = current.get();
            PageserverNode currentNode = nodesById.get(entity.getNodeId());
            if (currentNode != null && !unavailable.contains(currentNode.id())) {
                return new PageserverPlacement(tenantId, shardId, currentNode, entity.getEpoch(), entity.getSource());
            }
        }

        PageserverNode selected = selectNode(tenantId, shardId, nodes, unavailable);
        long epoch = current.map(PageserverAssignmentEntity::getEpoch).orElse(0L) + 1L;
        String source = placementSource();
        saveAssignment(tenantId, shardId, selected.id(), epoch, source, "resolve");
        return new PageserverPlacement(tenantId, shardId, selected, epoch, source);
    }

    public PageserverNode defaultNode() {
        List<PageserverNode> nodes = configuredNodes();
        return nodes.isEmpty() ? legacyDefaultNode() : nodes.get(0);
    }

    public List<PageserverNode> configuredNodes() {
        List<PageserverNode> dynamicNodes = dynamicNodes();
        if (!dynamicNodes.isEmpty()) {
            return dynamicNodes;
        }
        return props.getNeon().getPageserverNodes().stream()
            .map(this::toNode)
            .sorted(Comparator.comparing(PageserverNode::id))
            .toList();
    }

    private List<PageserverNode> dynamicNodes() {
        if (nodeProvider == null) {
            return List.of();
        }
        List<PageserverNode> nodes = nodeProvider.nodes();
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
            .sorted(Comparator.comparing(PageserverNode::id))
            .toList();
    }

    public List<PageserverNodeStatus> nodeStatuses() {
        Map<String, Double> loads = loadScores();
        Set<String> unavailable = unavailableNodeIds();
        String source = statusSource();
        return configuredNodes().stream()
            .map(node -> new PageserverNodeStatus(
                node,
                !unavailable.contains(node.id()),
                loads.getOrDefault(node.id(), 0.0d),
                source))
            .toList();
    }

    public List<PageserverPlacement> placements() {
        if (assignments == null) {
            return List.of();
        }
        Map<String, PageserverNode> nodesById = byId(configuredNodes());
        return assignments.findAll().stream()
            .sorted(Comparator.comparing(PageserverAssignmentEntity::getTenantId)
                .thenComparingInt(PageserverAssignmentEntity::getShardId))
            .filter(entity -> nodesById.containsKey(entity.getNodeId()))
            .map(entity -> new PageserverPlacement(
                entity.getTenantId(),
                entity.getShardId(),
                nodesById.get(entity.getNodeId()),
                entity.getEpoch(),
                entity.getSource()))
            .toList();
    }

    public List<PageserverPlacement> placementsForTenant(String tenantId) {
        if (assignments == null) {
            return List.of();
        }
        Map<String, PageserverNode> nodesById = byId(configuredNodes());
        return assignments.findAllByTenantId(tenantId).stream()
            .sorted(Comparator.comparingInt(PageserverAssignmentEntity::getShardId))
            .filter(entity -> nodesById.containsKey(entity.getNodeId()))
            .map(entity -> new PageserverPlacement(
                entity.getTenantId(),
                entity.getShardId(),
                nodesById.get(entity.getNodeId()),
                entity.getEpoch(),
                entity.getSource()))
            .toList();
    }

    @Transactional
    public PageserverRebalancePlan rebalanceDryRun() {
        PageserverRebalancePlan plan = rebalance(true, Set.of(), "rebalance-dry-run");
        recordEvent("REBALANCE_DRY_RUN", "ADMIN", "admin-api", null, plan, "rebalance-dry-run");
        return plan;
    }

    @Transactional
    public PageserverRebalancePlan failoverNode(String nodeId) {
        String reason = "node-unavailable:" + nodeId;
        PageserverRebalancePlan plan = rebalance(false, Set.of(nodeId), reason);
        recordEvent("FAILOVER_NODE", "ADMIN", "admin-api", nodeId, plan, reason);
        return plan;
    }

    @Transactional
    public PageserverRebalancePlan failoverUnavailableNodes() {
        Set<String> unavailable = unavailableNodeIds();
        if (unavailable.isEmpty()) {
            return new PageserverRebalancePlan(false, List.of());
        }
        String reason = "auto-unavailable:" + String.join(",", unavailable);
        PageserverRebalancePlan plan = rebalance(false, unavailable, reason);
        recordEvent("AUTO_FAILOVER", "SCHEDULER", "auto-failover", String.join(",", unavailable), plan, reason);
        return plan;
    }

    @Scheduled(
        initialDelayString = "${lakeon.dicer.live-load-initial-delay-ms:5000}",
        fixedDelayString = "${lakeon.dicer.auto-failover-poll-interval-ms:15000}")
    @Transactional
    public void autoFailoverUnavailableNodes() {
        if (!props.getDicer().isEnabled() || !props.getDicer().isAutoFailoverEnabled()) {
            return;
        }
        Set<String> unavailable = unavailableNodeIds();
        if (!unavailable.isEmpty()) {
            String reason = "auto-unavailable:" + String.join(",", unavailable);
            PageserverRebalancePlan plan = rebalance(false, unavailable, reason);
            recordEvent("AUTO_FAILOVER", "SCHEDULER", "auto-failover", String.join(",", unavailable), plan, reason);
        }
    }

    public Map<String, Map<String, Double>> loadBreakdown() {
        PageserverLoadSnapshot live = liveSnapshot();
        if (live.isFresh()) {
            return live.loadBreakdownByNode();
        }
        return Map.of();
    }

    private PageserverRebalancePlan rebalance(boolean dryRun, Set<String> forcedUnavailable, String reason) {
        if (assignments == null) {
            return new PageserverRebalancePlan(dryRun, List.of());
        }
        List<PageserverNode> nodes = configuredNodes();
        Set<String> unavailable = new HashSet<>(unavailableNodeIds());
        unavailable.addAll(forcedUnavailable);
        List<PageserverRebalancePlan.Move> moves = new ArrayList<>();
        for (PageserverAssignmentEntity assignment : assignments.findAll()) {
            PageserverNode target = selectNode(assignment.getTenantId(), assignment.getShardId(), nodes, unavailable);
            if (!target.id().equals(assignment.getNodeId())) {
                long nextEpoch = assignment.getEpoch() + 1L;
                moves.add(new PageserverRebalancePlan.Move(
                    assignment.getTenantId(),
                    assignment.getShardId(),
                    assignment.getNodeId(),
                    target.id(),
                    nextEpoch,
                    reason));
                if (!dryRun) {
                    saveAssignment(
                        assignment.getTenantId(),
                        assignment.getShardId(),
                        target.id(),
                        nextEpoch,
                        forcedUnavailable.isEmpty() ? "dicer-rebalance" : "failover",
                        reason);
                }
            }
        }
        return new PageserverRebalancePlan(dryRun, moves);
    }

    private void recordEvent(String action,
                             String triggerType,
                             String actor,
                             String targetNodeId,
                             PageserverRebalancePlan plan,
                             String reason) {
        if (eventService != null) {
            eventService.record(action, triggerType, actor, targetNodeId, plan, reason);
        }
    }

    private PageserverNode toNode(LakeonProperties.PageserverNodeConfig config) {
        String httpUrl = requireNonBlank(config.getHttpUrl(), "pageserver node httpUrl is required");
        String id = config.getId() != null && !config.getId().isBlank()
            ? config.getId()
            : hostFromUrl(httpUrl);
        String pgHost = config.getPgHost() != null && !config.getPgHost().isBlank()
            ? config.getPgHost()
            : hostFromUrl(httpUrl);
        int pgPort = config.getPgPort() > 0 ? config.getPgPort() : DEFAULT_PG_PORT;
        return new PageserverNode(id, httpUrl, pgHost, pgPort);
    }

    private Optional<PageserverAssignmentEntity> findAssignment(String tenantId, int shardId) {
        if (assignments == null) {
            return Optional.empty();
        }
        return assignments.findById(PageserverAssignmentEntity.id(tenantId, shardId));
    }

    private void saveAssignment(String tenantId, int shardId, String nodeId, long epoch, String source, String reason) {
        if (assignments == null) {
            return;
        }
        PageserverAssignmentEntity entity = assignments.findById(PageserverAssignmentEntity.id(tenantId, shardId))
            .orElseGet(PageserverAssignmentEntity::new);
        entity.setId(PageserverAssignmentEntity.id(tenantId, shardId));
        entity.setTenantId(tenantId);
        entity.setShardId(shardId);
        entity.setNodeId(nodeId);
        entity.setEpoch(epoch);
        entity.setSource(source);
        entity.setStatus("ACTIVE");
        entity.setUpdatedReason(reason);
        assignments.save(entity);
    }

    private PageserverNode selectNode(String tenantId, int shardId, List<PageserverNode> nodes, Set<String> unavailable) {
        List<PageserverNode> healthy = nodes.stream()
            .filter(node -> !unavailable.contains(node.id()))
            .toList();
        if (healthy.isEmpty()) {
            healthy = nodes;
        }
        if (props.getDicer().isEnabled()) {
            Map<String, Double> loads = loadScores();
            return healthy.stream()
                .min(Comparator
                    .comparingDouble((PageserverNode node) -> loads.getOrDefault(node.id(), 0.0d))
                    .thenComparing(PageserverNode::id))
                .orElseThrow();
        }
        int index = Math.floorMod(hashToInt(tenantId + ":" + shardId), healthy.size());
        return healthy.get(index);
    }

    private Map<String, PageserverNode> byId(List<PageserverNode> nodes) {
        Map<String, PageserverNode> out = new LinkedHashMap<>();
        for (PageserverNode node : nodes) {
            out.put(node.id(), node);
        }
        return out;
    }

    private Map<String, Double> loadScores() {
        PageserverLoadSnapshot live = liveSnapshot();
        if (live.isFresh()) {
            return live.loadScores();
        }
        String raw = props.getDicer().getNodeLoadsRaw();
        Map<String, Double> scores = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return scores;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            if (parts.length == 2) {
                scores.put(parts[0].trim(), Double.parseDouble(parts[1].trim()));
            }
        }
        return scores;
    }

    private Set<String> unavailableNodeIds() {
        Set<String> ids = new HashSet<>();
        PageserverLoadSnapshot live = liveSnapshot();
        if (live.isFresh()) {
            ids.addAll(live.unavailableNodeIds());
        }
        String raw = props.getDicer().getUnavailableNodesRaw();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    private PageserverLoadSnapshot liveSnapshot() {
        if (!props.getDicer().isEnabled() || loadProvider == null) {
            return PageserverLoadSnapshot.empty();
        }
        PageserverLoadSnapshot snapshot = loadProvider.snapshot();
        return snapshot == null ? PageserverLoadSnapshot.empty() : snapshot;
    }

    private String placementSource() {
        PageserverLoadSnapshot live = liveSnapshot();
        if (live.isFresh()) {
            return live.source();
        }
        return props.getDicer().isEnabled() ? "dicer-load-aware" : "static-hash";
    }

    private String statusSource() {
        PageserverLoadSnapshot live = liveSnapshot();
        if (live.isFresh()) {
            return live.source();
        }
        return props.getDicer().isEnabled() ? "dicer" : "configured";
    }

    private PageserverNode legacyDefaultNode() {
        String httpUrl = props.getNeon().getPageserverUrl();
        if (httpUrl == null || httpUrl.isBlank()) {
            httpUrl = "http://localhost:9898";
        }
        PageserverNode node = new PageserverNode("default", httpUrl, hostFromUrl(httpUrl), DEFAULT_PG_PORT);
        return new PageserverNode(node.id(), node.httpUrl(), node.normalizedPgHost(), node.pgPort());
    }

    private String hostFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to legacy parser for short service names without a scheme.
        }
        return url.replaceAll("https?://", "").replaceAll(":\\d+$", "");
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private int hashToInt(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes, 0, Integer.BYTES).getInt();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
