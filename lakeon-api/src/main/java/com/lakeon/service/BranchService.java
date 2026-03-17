package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.BranchResponse;
import com.lakeon.model.dto.BranchTreeNode;
import com.lakeon.model.dto.BranchTreeResponse;
import com.lakeon.model.dto.CreateBranchRequest;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.BranchStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BranchService {
    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final NeonApiClient neonApiClient;
    private final ComputePodManager computePodManager;
    private final OperationLogService operationLogService;

    public BranchService(DatabaseRepository databaseRepository,
                         BranchRepository branchRepository,
                         NeonApiClient neonApiClient,
                         ComputePodManager computePodManager,
                         OperationLogService operationLogService) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.neonApiClient = neonApiClient;
        this.computePodManager = computePodManager;
        this.operationLogService = operationLogService;
    }

    @Transactional
    public BranchResponse create(TenantEntity tenant, String dbId, CreateBranchRequest request) {
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // Check for duplicate branch name
        branchRepository.findByDatabaseIdAndName(dbId, request.name()).ifPresent(existing -> {
            throw new ConflictException("Branch '" + request.name() + "' already exists");
        });

        // Generate new timeline ID
        String newTimelineId = generateHexId();

        // Determine ancestor timeline ID
        String ancestorTimelineId;
        final String resolvedParentBranchId;
        String parentBranchName;
        String requestParentId = request.parentBranchId();

        if (requestParentId != null && !requestParentId.isBlank()) {
            // Look up parent branch to get its neonTimelineId
            BranchEntity parentBranch = branchRepository.findByIdAndDatabaseId(requestParentId, dbId)
                .orElseThrow(() -> new NotFoundException("Parent branch not found: " + requestParentId));
            ancestorTimelineId = parentBranch.getNeonTimelineId();
            parentBranchName = parentBranch.getName();
            resolvedParentBranchId = requestParentId;
        } else {
            // Default: branch from the database's main timeline
            ancestorTimelineId = dbEntity.getNeonTimelineId();
            parentBranchName = "main";
            // Find the default branch ID
            resolvedParentBranchId = branchRepository.findAllByDatabaseId(dbId).stream()
                .filter(BranchEntity::getIsDefault)
                .findFirst()
                .map(BranchEntity::getId)
                .orElse(null);
        }

        // Create timeline with optional ancestor LSN
        CreateTimelineRequest timelineRequest = request.ancestorLsn() != null
            ? CreateTimelineRequest.forBranchAtLsn(newTimelineId, ancestorTimelineId, request.ancestorLsn())
            : CreateTimelineRequest.forBranch(newTimelineId, ancestorTimelineId);
        NeonTimeline timeline = neonApiClient.createTimeline(dbEntity.getNeonTenantId(), timelineRequest);

        // Create branch entity
        BranchEntity branch = new BranchEntity();
        branch.setName(request.name());
        branch.setDatabaseId(dbId);
        branch.setNeonTimelineId(timeline.getTimelineId());
        branch.setParentBranchId(resolvedParentBranchId);
        branch.setParentBranchName(parentBranchName);
        branch.setIsDefault(false);
        branch.setStatus(BranchStatus.CREATING);
        branch.setConnectionUri(dbEntity.getConnectionUri() + "?branch=" + request.name());

        // Optionally start compute
        if (Boolean.TRUE.equals(request.startCompute())) {
            DatabaseEntity branchDbEntity = new DatabaseEntity();
            branchDbEntity.setId(dbEntity.getId());
            branchDbEntity.setName(dbEntity.getName());
            branchDbEntity.setTenantId(dbEntity.getTenantId());
            branchDbEntity.setNeonTenantId(dbEntity.getNeonTenantId());
            branchDbEntity.setNeonTimelineId(timeline.getTimelineId());
            branchDbEntity.setComputeSize(dbEntity.getComputeSize());
            branchDbEntity.setDbUser(dbEntity.getDbUser());
            branchDbEntity.setDbPassword(dbEntity.getDbPassword());
            computePodManager.createComputePod(branchDbEntity);
        }

        branch = branchRepository.save(branch);
        return toResponse(branch);
    }

    public List<BranchResponse> list(TenantEntity tenant, String dbId) {
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        return branchRepository.findAllByDatabaseId(dbId).stream()
            .map(this::toResponse)
            .toList();
    }

    public BranchResponse get(TenantEntity tenant, String dbId, String branchId) {
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        BranchEntity branch = branchRepository.findByIdAndDatabaseId(branchId, dbId)
            .orElseThrow(() -> new NotFoundException("Branch not found: " + branchId));

        return toResponse(branch);
    }

    @Transactional
    public void delete(TenantEntity tenant, String dbId, String branchId) {
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        BranchEntity branch = branchRepository.findByIdAndDatabaseId(branchId, dbId)
            .orElseThrow(() -> new NotFoundException("Branch not found: " + branchId));

        if (branch.getIsDefault()) {
            throw new BadRequestException("Cannot delete default branch");
        }

        if (branch.getComputePodName() != null) {
            computePodManager.deleteComputePod(branch.getComputePodName());
        }

        if (branch.getNeonTimelineId() != null) {
            neonApiClient.deleteTimeline(dbEntity.getNeonTenantId(), branch.getNeonTimelineId());
        }

        branchRepository.delete(branch);
    }

    public BranchTreeResponse getTree(TenantEntity tenant, String dbId) {
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        List<BranchEntity> allBranches = branchRepository.findAllByDatabaseId(dbId);

        // Fetch live Neon timeline data for enrichment
        Map<String, NeonTimeline> timelineMap;
        try {
            List<NeonTimeline> timelines = neonApiClient.listTimelines(dbEntity.getNeonTenantId());
            timelineMap = timelines.stream()
                .collect(Collectors.toMap(NeonTimeline::getTimelineId, t -> t, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Failed to fetch Neon timelines for enrichment: {}", e.getMessage());
            timelineMap = Map.of();
        }

        Map<String, NeonTimeline> finalTimelineMap = timelineMap;
        List<BranchTreeNode> nodes = allBranches.stream().map(branch -> {
            NeonTimeline timeline = branch.getNeonTimelineId() != null
                ? finalTimelineMap.get(branch.getNeonTimelineId()) : null;
            return new BranchTreeNode(
                branch.getId(),
                branch.getName(),
                branch.getParentBranchId(),
                branch.getIsDefault(),
                timeline != null ? timeline.getAncestorLsn() : null,
                timeline != null ? timeline.getLastRecordLsn() : null,
                timeline != null ? timeline.getCurrentLogicalSize() : null,
                branch.getCreatedAt()
            );
        }).toList();

        return new BranchTreeResponse(nodes);
    }

    @Transactional
    public BranchResponse switchActive(TenantEntity tenant, String dbId, String branchId) {
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        BranchEntity targetBranch = branchRepository.findByIdAndDatabaseId(branchId, dbId)
            .orElseThrow(() -> new NotFoundException("Branch not found: " + branchId));

        if (dbEntity.getNeonTimelineId().equals(targetBranch.getNeonTimelineId())) {
            throw new BadRequestException("Branch is already active");
        }

        var opLog = operationLogService.startOperation(
            dbId, tenant.getId(), dbEntity.getName(), OperationType.SWITCH_BRANCH);

        try {
            // Update database to point to the target branch's timeline
            dbEntity.setNeonTimelineId(targetBranch.getNeonTimelineId());

            // If compute pod exists, delete and recreate it
            if (dbEntity.getComputePodName() != null) {
                computePodManager.deleteComputePod(dbEntity.getComputePodName(), true);
                String podName = computePodManager.createComputePod(dbEntity);
                dbEntity.setComputePodName(podName);
            }

            databaseRepository.save(dbEntity);
            operationLogService.completeOperation(opLog, null);
            log.info("Switched database {} to branch {} (timeline {})",
                dbId, branchId, targetBranch.getNeonTimelineId());
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }

        return toResponse(targetBranch);
    }

    private BranchResponse toResponse(BranchEntity entity) {
        return BranchResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .parentBranch(entity.getParentBranchName())
            .isDefault(entity.getIsDefault())
            .status(entity.getStatus() != null ? entity.getStatus().name().toLowerCase() : null)
            .computeStatus(entity.getComputeStatus() != null ? entity.getComputeStatus().name().toLowerCase() : null)
            .connectionUri(entity.getConnectionUri())
            .parentBranchId(entity.getParentBranchId())
            .neonTimelineId(entity.getNeonTimelineId())
            .createdAt(entity.getCreatedAt())
            .branchType(entity.getBranchType() != null ? entity.getBranchType().name() : null)
            .build();
    }

    private String generateHexId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
