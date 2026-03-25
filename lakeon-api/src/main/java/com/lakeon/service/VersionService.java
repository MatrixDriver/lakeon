package com.lakeon.service;

import com.lakeon.model.dto.CreateVersionRequest;
import com.lakeon.model.dto.SquashVersionsRequest;
import com.lakeon.model.dto.VersionResponse;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.entity.VersionEntity;
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

import com.lakeon.k8s.ComputePodManager;

import java.security.SecureRandom;
import java.util.List;

@Service
@Transactional
public class VersionService {
    private static final Logger log = LoggerFactory.getLogger(VersionService.class);

    private final VersionRepository versionRepository;
    private final BranchRepository branchRepository;
    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final ComputePodManager computePodManager;

    public VersionService(VersionRepository versionRepository,
                          BranchRepository branchRepository,
                          DatabaseRepository databaseRepository,
                          NeonApiClient neonApiClient,
                          ComputePodManager computePodManager) {
        this.versionRepository = versionRepository;
        this.branchRepository = branchRepository;
        this.databaseRepository = databaseRepository;
        this.neonApiClient = neonApiClient;
        this.computePodManager = computePodManager;
    }

    public VersionResponse create(TenantEntity tenant, String dbId, String branchId,
                                  CreateVersionRequest request) {
        DatabaseEntity dbEntity = validateBranchAccess(tenant, dbId, branchId);
        BranchEntity branch = branchRepository.findByIdAndDatabaseId(branchId, dbId).get();

        // Check duplicate name
        versionRepository.findByBranchIdAndName(branchId, request.name()).ifPresent(existing -> {
            throw new ConflictException("Version '" + request.name() + "' already exists");
        });

        // Flush WAL before snapshotting to ensure all data is durable
        String podName = branch.getComputePodName() != null ? branch.getComputePodName() : dbEntity.getComputePodName();
        if (podName != null && computePodManager.isPodReady(podName)) {
            computePodManager.executeCheckpoint(podName);
        }

        // Resolve LSN
        String lsnHex;
        if (request.atLsn() != null && !request.atLsn().isBlank()) {
            lsnHex = request.atLsn();
        } else {
            NeonTimeline timeline = neonApiClient.getTimeline(
                    dbEntity.getNeonTenantId(), branch.getNeonTimelineId());
            lsnHex = timeline.getLastRecordLsn();
        }
        long lsn = LsnUtil.parse(lsnHex);

        // Create snapshot timeline
        String snapshotTimelineId = generateHexId();
        neonApiClient.createTimeline(dbEntity.getNeonTenantId(),
                CreateTimelineRequest.forBranchAtLsn(snapshotTimelineId,
                        branch.getNeonTimelineId(), lsnHex));

        // Save entity
        VersionEntity entity = new VersionEntity();
        entity.setBranchId(branchId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setLsn(lsn);
        entity.setLsnHex(lsnHex);
        entity.setSnapshotTimelineId(snapshotTimelineId);
        entity.setCreatedBy(tenant.getUsername() != null ? tenant.getUsername() : tenant.getName());

        entity = versionRepository.save(entity);
        log.info("Created version '{}' on branch {} at LSN {}", request.name(), branchId, lsnHex);
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<VersionResponse> list(TenantEntity tenant, String dbId, String branchId) {
        validateBranchAccess(tenant, dbId, branchId);
        return versionRepository.findAllByBranchIdOrderByLsnAsc(branchId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionResponse get(TenantEntity tenant, String dbId, String branchId,
                               String versionId) {
        validateBranchAccess(tenant, dbId, branchId);
        VersionEntity entity = versionRepository.findByIdAndBranchId(versionId, branchId)
                .orElseThrow(() -> new NotFoundException("Version not found: " + versionId));
        return toResponse(entity);
    }

    public void delete(TenantEntity tenant, String dbId, String branchId, String versionId) {
        DatabaseEntity dbEntity = validateBranchAccess(tenant, dbId, branchId);
        VersionEntity entity = versionRepository.findByIdAndBranchId(versionId, branchId)
                .orElseThrow(() -> new NotFoundException("Version not found: " + versionId));

        // Delete snapshot timeline from pageserver (may fail with 412 if timeline has children)
        if (entity.getSnapshotTimelineId() != null) {
            try {
                neonApiClient.deleteTimeline(dbEntity.getNeonTenantId(),
                        entity.getSnapshotTimelineId());
            } catch (Exception e) {
                log.warn("Could not delete snapshot timeline {} for version '{}': {}",
                        entity.getSnapshotTimelineId(), entity.getName(), e.getMessage());
            }
        }
        versionRepository.delete(entity);
        log.info("Deleted version '{}' from branch {}", entity.getName(), branchId);
    }

    public List<VersionResponse> squash(TenantEntity tenant, String dbId, String branchId,
                                        SquashVersionsRequest request) {
        DatabaseEntity dbEntity = validateBranchAccess(tenant, dbId, branchId);

        VersionEntity fromVersion = versionRepository.findByIdAndBranchId(
                        request.fromVersionId(), branchId)
                .orElseThrow(() -> new NotFoundException(
                        "From version not found: " + request.fromVersionId()));
        VersionEntity toVersion = versionRepository.findByIdAndBranchId(
                        request.toVersionId(), branchId)
                .orElseThrow(() -> new NotFoundException(
                        "To version not found: " + request.toVersionId()));

        // Get all versions in LSN range
        List<VersionEntity> versionsInRange =
                versionRepository.findAllByBranchIdAndLsnBetweenOrderByLsnAsc(
                        branchId, fromVersion.getLsn(), toVersion.getLsn());

        // Need at least 3 versions (from + middle + to) to have something to squash
        if (versionsInRange.size() < 3) {
            throw new BadRequestException("Need at least 3 versions in range to squash; got " + versionsInRange.size());
        }

        // Delete middle versions (exclude from and to)
        for (VersionEntity v : versionsInRange) {
            if (!v.getId().equals(fromVersion.getId()) && !v.getId().equals(toVersion.getId())) {
                if (v.getSnapshotTimelineId() != null) {
                    try {
                        neonApiClient.deleteTimeline(dbEntity.getNeonTenantId(),
                                v.getSnapshotTimelineId());
                    } catch (Exception e) {
                        log.warn("Could not delete snapshot timeline {} for version '{}': {}",
                                v.getSnapshotTimelineId(), v.getName(), e.getMessage());
                    }
                }
                versionRepository.delete(v);
                log.info("Squashed version '{}' from branch {}", v.getName(), branchId);
            }
        }

        // Return remaining versions
        return versionRepository.findAllByBranchIdOrderByLsnAsc(branchId).stream()
                .map(this::toResponse)
                .toList();
    }

    private DatabaseEntity validateBranchAccess(TenantEntity tenant, String dbId,
                                                 String branchId) {
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
        branchRepository.findByIdAndDatabaseId(branchId, dbId)
                .orElseThrow(() -> new NotFoundException("Branch not found: " + branchId));
        return dbEntity;
    }

    private VersionResponse toResponse(VersionEntity entity) {
        return VersionResponse.builder()
                .id(entity.getId())
                .branchId(entity.getBranchId())
                .name(entity.getName())
                .description(entity.getDescription())
                .lsn(entity.getLsnHex())
                .snapshotTimelineId(entity.getSnapshotTimelineId())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
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
