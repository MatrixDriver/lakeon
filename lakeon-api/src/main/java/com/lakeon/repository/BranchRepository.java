package com.lakeon.repository;

import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.enums.ComputeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<BranchEntity, String> {
    List<BranchEntity> findAllByDatabaseId(String databaseId);
    Optional<BranchEntity> findByIdAndDatabaseId(String id, String databaseId);
    Optional<BranchEntity> findByDatabaseIdAndName(String databaseId, String name);
    Optional<BranchEntity> findByDatabaseIdAndIsDefaultTrue(String databaseId);
    Optional<BranchEntity> findByNeonTimelineId(String neonTimelineId);
    List<BranchEntity> findByComputeStatus(ComputeStatus computeStatus);
    Optional<BranchEntity> findByComputePodName(String podName);
}
