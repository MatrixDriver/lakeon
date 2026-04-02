package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    Optional<KnowledgeBaseEntity> findByIdAndTenantId(String id, String tenantId);
    List<KnowledgeBaseEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Modifying
    @Transactional
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.documentCount = COALESCE(kb.documentCount, 0) + :delta WHERE kb.id = :id")
    void incrementDocumentCount(String id, int delta);
}
