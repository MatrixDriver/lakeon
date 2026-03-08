package com.lakeon.repository;

import com.lakeon.model.entity.AuditConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditConfigRepository extends JpaRepository<AuditConfigEntity, String> {

    Optional<AuditConfigEntity> findByDatabaseId(String databaseId);

    List<AuditConfigEntity> findByTenantId(String tenantId);
}
