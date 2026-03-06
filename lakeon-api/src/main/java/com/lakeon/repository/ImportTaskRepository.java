package com.lakeon.repository;

import com.lakeon.model.entity.ImportTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ImportTaskRepository extends JpaRepository<ImportTaskEntity, String> {
    List<ImportTaskEntity> findAllByDatabaseIdAndTenantIdOrderByCreatedAtDesc(String databaseId, String tenantId);
    Optional<ImportTaskEntity> findByIdAndTenantId(String id, String tenantId);
}
