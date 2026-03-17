package com.lakeon.repository;

import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DatabaseRepository extends JpaRepository<DatabaseEntity, String> {
    List<DatabaseEntity> findAllByTenantId(String tenantId);
    Optional<DatabaseEntity> findByTenantIdAndName(String tenantId, String name);
    Optional<DatabaseEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<DatabaseEntity> findByNeonTenantId(String neonTenantId);
    List<DatabaseEntity> findAllByStatus(DatabaseStatus status);
    Optional<DatabaseEntity> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DatabaseEntity d WHERE d.id = :id AND d.tenantId = :tenantId")
    Optional<DatabaseEntity> findByIdAndTenantIdForUpdate(@Param("id") String id, @Param("tenantId") String tenantId);
}
