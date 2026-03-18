package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
    Optional<DocumentEntity> findByIdAndTenantId(String id, String tenantId);
    List<DocumentEntity> findAllByTenantIdAndDatabaseIdOrderByCreatedAtDesc(String tenantId, String databaseId);
    List<DocumentEntity> findAllByTenantIdAndKbIdOrderByCreatedAtDesc(String tenantId, String kbId);
    List<DocumentEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DocumentEntity> findAllByKbId(String kbId);

    @Query(value = "SELECT id FROM documents WHERE kb_id = :kbId AND tenant_id = :tenantId AND tags && CAST(:tags AS text[])",
           nativeQuery = true)
    List<String> findIdsByKbIdAndTenantIdAndTagsContaining(
        @Param("kbId") String kbId,
        @Param("tenantId") String tenantId,
        @Param("tags") String[] tags);

    /**
     * CAS lock for rechunk: only transitions from IDLE to IN_PROGRESS.
     * Returns number of rows updated (0 means lock not acquired).
     */
    @Modifying
    @Query("UPDATE DocumentEntity d SET d.rechunkStatus = 'IN_PROGRESS', d.rechunkStartedAt = CURRENT_TIMESTAMP, d.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE d.id = :id AND d.rechunkStatus = com.lakeon.knowledge.RechunkStatus.IDLE")
    int casLockRechunk(@Param("id") String id);
}
