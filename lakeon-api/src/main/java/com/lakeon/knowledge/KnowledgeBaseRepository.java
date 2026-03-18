package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    Optional<KnowledgeBaseEntity> findByIdAndTenantId(String id, String tenantId);
    List<KnowledgeBaseEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
}
