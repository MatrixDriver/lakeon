package com.lakeon.repository;

import com.lakeon.model.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {

    Page<AuditLogEntity> findByDatabaseIdOrderByTimestampDesc(String databaseId, Pageable pageable);

    Page<AuditLogEntity> findByDatabaseIdAndStatementTypeOrderByTimestampDesc(
            String databaseId, String statementType, Pageable pageable);

    void deleteByDatabaseIdAndTimestampBefore(String databaseId, Instant before);

    Page<AuditLogEntity> findByTenantIdOrderByTimestampDesc(String tenantId, Pageable pageable);

    Page<AuditLogEntity> findByTenantIdAndDatabaseIdOrderByTimestampDesc(
            String tenantId, String databaseId, Pageable pageable);

    Page<AuditLogEntity> findByTenantIdAndStatementTypeOrderByTimestampDesc(
            String tenantId, String statementType, Pageable pageable);

    Page<AuditLogEntity> findByTenantIdAndDatabaseIdAndStatementTypeOrderByTimestampDesc(
            String tenantId, String databaseId, String statementType, Pageable pageable);

    Page<AuditLogEntity> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<AuditLogEntity> findByStatementTypeOrderByTimestampDesc(String statementType, Pageable pageable);
}
