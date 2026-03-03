package com.lakeon.repository;

import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DatabaseRepository extends JpaRepository<DatabaseEntity, String> {
    List<DatabaseEntity> findAllByTenantId(String tenantId);
    Optional<DatabaseEntity> findByTenantIdAndName(String tenantId, String name);
    Optional<DatabaseEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<DatabaseEntity> findByNeonTenantId(String neonTenantId);
    List<DatabaseEntity> findAllByStatus(DatabaseStatus status);
    Optional<DatabaseEntity> findByName(String name);
}
