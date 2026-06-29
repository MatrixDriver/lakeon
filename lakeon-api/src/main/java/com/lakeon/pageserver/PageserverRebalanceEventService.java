package com.lakeon.pageserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PageserverRebalanceEventService {

    private final PageserverRebalanceEventRepository repository;
    private final ObjectMapper objectMapper;

    public PageserverRebalanceEventService(PageserverRebalanceEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PageserverRebalanceEventEntity record(String action,
                                                 String triggerType,
                                                 String actor,
                                                 String targetNodeId,
                                                 PageserverRebalancePlan plan,
                                                 String reason) {
        PageserverRebalanceEventEntity event = new PageserverRebalanceEventEntity();
        event.setAction(action);
        event.setTriggerType(triggerType);
        event.setActor(actor);
        event.setTargetNodeId(targetNodeId);
        event.setDryRun(plan.dryRun());
        event.setMoveCount(plan.moves().size());
        event.setStatus(plan.moves().isEmpty() ? "NOOP" : (plan.dryRun() ? "PLANNED" : "APPLIED"));
        event.setReason(reason);
        event.setMovesJson(serializeMoves(plan.moves()));
        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<PageserverRebalanceEventEntity> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, bounded));
    }

    public List<Map<String, Object>> parseMoves(PageserverRebalanceEventEntity event) {
        String movesJson = event.getMovesJson();
        if (movesJson == null || movesJson.isBlank()) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> moves = objectMapper.readValue(movesJson, List.class);
            return moves;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String serializeMoves(List<PageserverRebalancePlan.Move> moves) {
        List<Map<String, Object>> rows = moves.stream().map(move -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenant_id", move.tenantId());
            row.put("shard_id", move.shardId());
            row.put("from_node_id", move.fromNodeId());
            row.put("to_node_id", move.toNodeId());
            row.put("next_epoch", move.nextEpoch());
            row.put("reason", move.reason());
            return row;
        }).toList();
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
