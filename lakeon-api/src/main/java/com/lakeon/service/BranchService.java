package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.BranchResponse;
import com.lakeon.model.dto.BranchTreeNode;
import com.lakeon.model.dto.BranchTreeResponse;
import com.lakeon.model.dto.CreateBranchRequest;
import com.lakeon.model.dto.RestoreBranchRequest;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.entity.VersionEntity;
import com.lakeon.model.enums.BranchStatus;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.BranchType;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.VersionRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.util.LsnUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BranchService {
    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final VersionRepository versionRepository;
    private final NeonApiClient neonApiClient;
    private final ComputePodManager computePodManager;
    private final OperationLogService operationLogService;

    public BranchService(DatabaseRepository databaseRepository,
                         BranchRepository branchRepository,
                         VersionRepository versionRepository,
                         NeonApiClient neonApiClient,
                         ComputePodManager computePodManager,
                         OperationLogService operationLogService) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.versionRepository = versionRepository;
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
        branch.setStatus(BranchStatus.ACTIVE);
        // Connection URI: keep same PG database name, route via options=endpoint=dbName--branchName
        // Base URI format: postgres://user@host:port/dbName?options=endpoint%3DdbName
        // Branch URI: postgres://user@host:port/dbName?options=endpoint%3DdbName--branchName
        String baseUri = dbEntity.getConnectionUri();
        String endpointParam = "options=endpoint%3D" + dbEntity.getName();
        String branchEndpointParam = "options=endpoint%3D" + dbEntity.getName() + "--" + request.name();
        branch.setConnectionUri(baseUri.replace(endpointParam, branchEndpointParam));

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
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        List<BranchEntity> allBranches = branchRepository.findAllByDatabaseId(dbId);

        // Fetch live Neon timeline data for enrichment (LSN, size)
        Map<String, NeonTimeline> timelineMap;
        try {
            List<NeonTimeline> timelines = neonApiClient.listTimelines(dbEntity.getNeonTenantId());
            timelineMap = timelines.stream()
                .collect(Collectors.toMap(NeonTimeline::getTimelineId, t -> t, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Failed to fetch Neon timelines for branch list enrichment: {}", e.getMessage());
            timelineMap = Map.of();
        }

        Map<String, NeonTimeline> finalTimelineMap = timelineMap;
        return allBranches.stream().map(branch -> {
            BranchResponse resp = toResponse(branch);
            NeonTimeline timeline = branch.getNeonTimelineId() != null
                ? finalTimelineMap.get(branch.getNeonTimelineId()) : null;
            if (timeline != null) {
                resp.setAncestorLsn(timeline.getAncestorLsn());
                resp.setLastRecordLsn(timeline.getLastRecordLsn());
                resp.setCurrentLogicalSizeBytes(timeline.getCurrentLogicalSize());
            }
            return resp;
        }).toList();
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
    public BranchResponse promote(TenantEntity tenant, String dbId, String branchId) {
        // Pessimistic lock prevents concurrent Promote/Restore
        DatabaseEntity db = databaseRepository.findByIdAndTenantIdForUpdate(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found"));
        BranchEntity target = branchRepository.findByIdAndDatabaseId(branchId, dbId)
            .orElseThrow(() -> new NotFoundException("Branch not found"));

        if (target.getIsDefault()) {
            throw new BadRequestException("Branch is already the default");
        }

        // Find current default
        BranchEntity currentDefault = branchRepository.findByDatabaseIdAndIsDefaultTrue(dbId)
            .orElseThrow(() -> new IllegalStateException("No default branch found"));

        // Demote current default
        currentDefault.setIsDefault(false);
        currentDefault.setName(currentDefault.getName() + "-before-promote-" + Instant.now().getEpochSecond());
        currentDefault.setBranchType(BranchType.BACKUP);
        branchRepository.save(currentDefault);

        // Promote target
        target.setIsDefault(true);
        branchRepository.save(target);

        // Update database entity: timeline + compute fields sync to promoted branch
        db.setNeonTimelineId(target.getNeonTimelineId());
        db.setComputePodName(target.getComputePodName());
        db.setComputeHost(target.getComputeHost());
        db.setComputePort(target.getComputePort() != null ? target.getComputePort() : 0);
        // If promoted branch has no running compute, mark DB as suspended so wakeCompute works
        if (target.getComputeStatus() == null || target.getComputeStatus() == com.lakeon.model.enums.ComputeStatus.SUSPENDED) {
            db.setStatus(DatabaseStatus.SUSPENDED);
        }
        databaseRepository.save(db);

        return toResponse(target);
    }

    @Transactional
    public BranchResponse restore(TenantEntity tenant, String dbId, String branchId,
                                   RestoreBranchRequest request) {
        // Pessimistic lock prevents concurrent Promote/Restore
        DatabaseEntity db = databaseRepository.findByIdAndTenantIdForUpdate(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found"));
        BranchEntity branch = branchRepository.findByIdAndDatabaseId(branchId, dbId)
            .orElseThrow(() -> new NotFoundException("Branch not found"));

        // Resolve target LSN
        String targetLsnHex;
        long targetLsn;
        String ancestorTimelineId;
        if (request.targetVersionId() != null) {
            VersionEntity targetVersion = versionRepository.findByIdAndBranchId(request.targetVersionId(), branchId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
            targetLsn = targetVersion.getLsn();
            targetLsnHex = targetVersion.getLsnHex();
            ancestorTimelineId = targetVersion.getSnapshotTimelineId();
        } else if (request.targetLsn() != null) {
            targetLsnHex = request.targetLsn();
            targetLsn = LsnUtil.parse(targetLsnHex);
            ancestorTimelineId = branch.getNeonTimelineId();
        } else {
            throw new BadRequestException("Either target_version_id or target_lsn is required");
        }

        String oldTimelineId = branch.getNeonTimelineId();

        // 1. Create backup branch (keeps old timeline — DO NOT delete it, it's ancestor of snapshot timelines)
        BranchEntity backup = new BranchEntity();
        backup.setName(branch.getName() + "-backup-" + Instant.now().getEpochSecond());
        backup.setDatabaseId(dbId);
        backup.setNeonTimelineId(oldTimelineId);
        backup.setParentBranchId(branch.getParentBranchId());
        backup.setParentBranchName(branch.getParentBranchName());
        backup.setIsDefault(false);
        backup.setStatus(BranchStatus.ACTIVE);
        backup.setBranchType(BranchType.BACKUP);
        branchRepository.save(backup);

        // 2. Create new timeline from target point
        String newTimelineId = generateHexId();
        neonApiClient.createTimeline(db.getNeonTenantId(),
            CreateTimelineRequest.forBranchAtLsn(newTimelineId, ancestorTimelineId, targetLsnHex));

        // 3. Update branch to new timeline
        branch.setNeonTimelineId(newTimelineId);
        branchRepository.save(branch);

        // 4. Move versions after target to backup branch
        List<VersionEntity> allVersions = versionRepository.findAllByBranchIdOrderByLsnAsc(branchId);
        for (VersionEntity v : allVersions) {
            if (v.getLsn() > targetLsn) {
                v.setBranchId(backup.getId());
                versionRepository.save(v);
            }
        }

        // 5. Update database active timeline if this is default branch
        if (branch.getIsDefault()) {
            db.setNeonTimelineId(newTimelineId);
            if (db.getComputePodName() != null) {
                computePodManager.deleteComputePod(db.getComputePodName(), true);
            }
            db.setComputePodName(null);
            db.setComputeHost(null);
            db.setComputePort(null);
            databaseRepository.save(db);
            computePodManager.createComputePod(db);
            databaseRepository.save(db);
        }

        return toResponse(branch);
    }

    private BranchResponse toResponse(BranchEntity entity) {
        return BranchResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .parentBranch(entity.getParentBranchName())
            .isDefault(entity.getIsDefault())
            .status(entity.getStatus() != null ? entity.getStatus().name() : null)
            .computeStatus(entity.getComputeStatus() != null ? entity.getComputeStatus().name() : null)
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
