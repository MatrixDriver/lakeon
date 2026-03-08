package com.lakeon.repository;

import com.lakeon.model.entity.BackupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BackupRepository extends JpaRepository<BackupEntity, String> {

    List<BackupEntity> findByDatabaseIdOrderByCreatedAtDesc(String databaseId);

    List<BackupEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    int countByDatabaseId(String databaseId);

    Optional<BackupEntity> findByIdAndTenantId(String id, String tenantId);
}
