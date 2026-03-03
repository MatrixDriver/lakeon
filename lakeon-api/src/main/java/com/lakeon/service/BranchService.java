package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.BranchResponse;
import com.lakeon.model.dto.CreateBranchRequest;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.BranchStatus;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
public class BranchService {
    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final NeonApiClient neonApiClient;
    private final ComputePodManager computePodManager;

    public BranchService(DatabaseRepository databaseRepository,
                         BranchRepository branchRepository,
                         NeonApiClient neonApiClient,
                         ComputePodManager computePodManager) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.neonApiClient = neonApiClient;
        this.computePodManager = computePodManager;
    }

    @Transactional
    public BranchResponse create(TenantEntity tenant, String dbId, CreateBranchRequest request) {
        DatabaseEntity dbEntity = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // Generate new timeline ID
        String newTimelineId = generateHexId();

        // Create timeline branched from the main timeline
        NeonTimeline timeline = neonApiClient.createTimeline(dbEntity.getNeonTenantId(),
            new CreateTimelineRequest(newTimelineId, dbEntity.getNeonTimelineId()));

        // Create branch entity
        BranchEntity branch = new BranchEntity();
        branch.setName(request.name());
        branch.setDatabaseId(dbId);
        branch.setNeonTimelineId(timeline.getTimelineId());
        branch.setParentBranchName("main");
        branch.setIsDefault(false);
        branch.setStatus(BranchStatus.CREATING);
        branch.setConnectionUri(dbEntity.getConnectionUri() + "?branch=" + request.name());

        // Optionally start compute
        if (Boolean.TRUE.equals(request.startCompute())) {
            // Create a temp entity for the pod manager
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

    private BranchResponse toResponse(BranchEntity entity) {
        return BranchResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .parentBranch(entity.getParentBranchName())
            .isDefault(entity.getIsDefault())
            .status(entity.getStatus() != null ? entity.getStatus().name().toLowerCase() : null)
            .computeStatus(entity.getComputeStatus() != null ? entity.getComputeStatus().name().toLowerCase() : null)
            .connectionUri(entity.getConnectionUri())
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
