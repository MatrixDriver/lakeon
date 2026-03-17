package com.lakeon.repository;

import com.lakeon.model.entity.VersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VersionRepository extends JpaRepository<VersionEntity, String> {
    List<VersionEntity> findAllByBranchIdOrderByLsnAsc(String branchId);
    Optional<VersionEntity> findByIdAndBranchId(String id, String branchId);
    Optional<VersionEntity> findByBranchIdAndName(String branchId, String name);
    // BIGINT comparison -- correct numeric ordering for LSN values
    List<VersionEntity> findAllByBranchIdAndLsnBetweenOrderByLsnAsc(String branchId, long fromLsn, long toLsn);
    void deleteAllByBranchId(String branchId);
    long countByBranchId(String branchId);
}
