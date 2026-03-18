package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
    Optional<DocumentEntity> findByIdAndTenantId(String id, String tenantId);
    List<DocumentEntity> findAllByTenantIdAndDatabaseIdOrderByCreatedAtDesc(String tenantId, String databaseId);
    List<DocumentEntity> findAllByTenantIdAndKbIdOrderByCreatedAtDesc(String tenantId, String kbId);
    List<DocumentEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DocumentEntity> findAllByKbId(String kbId);
}
