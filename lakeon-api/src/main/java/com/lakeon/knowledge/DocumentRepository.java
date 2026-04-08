package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
    Optional<DocumentEntity> findByIdAndTenantId(String id, String tenantId);

    @Query("SELECT d FROM DocumentEntity d WHERE d.tenantId = :tenantId AND d.kbId = :kbId AND d.docType = :docType AND d.filename = :filename")
    Optional<DocumentEntity> findByTypeAndFilename(
        @Param("tenantId") String tenantId,
        @Param("kbId") String kbId,
        @Param("docType") String docType,
        @Param("filename") String filename);

    List<DocumentEntity> findByTenantIdAndKbIdAndDocType(String tenantId, String kbId, String docType);

    List<DocumentEntity> findAllByTenantIdAndDatabaseIdOrderByCreatedAtDesc(String tenantId, String databaseId);
    List<DocumentEntity> findAllByTenantIdAndKbIdOrderByCreatedAtDesc(String tenantId, String kbId);
    List<DocumentEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<DocumentEntity> findAllByKbId(String kbId);
    List<DocumentEntity> findByDatasourceId(String datasourceId);
    long countByStatus(DocumentStatus status);

    @Query(value = "SELECT COALESCE(SUM(size_bytes), 0) FROM documents WHERE kb_id = :kbId", nativeQuery = true)
    long sumSizeBytesByKbId(@Param("kbId") String kbId);

    @Query(value = "SELECT COALESCE(SUM(obs_size), 0) FROM documents WHERE kb_id = :kbId", nativeQuery = true)
    long sumObsSizeByKbId(@Param("kbId") String kbId);

    @Query(value = "SELECT id FROM documents WHERE kb_id = :kbId AND tenant_id = :tenantId AND tags && CAST(:tags AS text[])",
           nativeQuery = true)
    List<String> findIdsByKbIdAndTenantIdAndTagsContaining(
        @Param("kbId") String kbId,
        @Param("tenantId") String tenantId,
        @Param("tags") String[] tags);

    @Query(value = "SELECT id FROM documents WHERE kb_id = :kbId AND tenant_id = :tenantId AND metadata @> CAST(:metadataJson AS jsonb)",
           nativeQuery = true)
    List<String> findIdsByKbIdAndTenantIdAndMetadataContaining(
        @Param("kbId") String kbId,
        @Param("tenantId") String tenantId,
        @Param("metadataJson") String metadataJson);

    @Query(value = "SELECT id FROM documents WHERE kb_id = :kbId AND tenant_id = :tenantId AND folder = :folder",
           nativeQuery = true)
    List<String> findIdsByKbIdAndTenantIdAndFolder(
        @Param("kbId") String kbId,
        @Param("tenantId") String tenantId,
        @Param("folder") String folder);

    /**
     * CAS lock for rechunk: only transitions from IDLE to IN_PROGRESS.
     * Returns number of rows updated (0 means lock not acquired).
     */
    @Modifying
    @Query("UPDATE DocumentEntity d SET d.rechunkStatus = 'IN_PROGRESS', d.rechunkStartedAt = CURRENT_TIMESTAMP, d.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE d.id = :id AND d.rechunkStatus = com.lakeon.knowledge.RechunkStatus.IDLE")
    int casLockRechunk(@Param("id") String id);

    @Query(value = """
        SELECT * FROM documents
        WHERE tenant_id = :tenantId
          AND (:kbId IS NULL OR kb_id = :kbId)
          AND (:status IS NULL OR status = :status)
          AND (:folder IS NULL OR folder = :folder)
        ORDER BY
          CASE WHEN :sortBy = 'upload_time' AND :sortOrder = 'asc' THEN created_at END ASC,
          CASE WHEN :sortBy = 'upload_time' AND :sortOrder = 'desc' THEN created_at END DESC,
          CASE WHEN :sortBy = 'size_bytes' AND :sortOrder = 'asc' THEN size_bytes END ASC NULLS FIRST,
          CASE WHEN :sortBy = 'size_bytes' AND :sortOrder = 'desc' THEN size_bytes END DESC NULLS LAST,
          CASE WHEN :sortBy = 'chunks_count' AND :sortOrder = 'asc' THEN chunks_count END ASC NULLS FIRST,
          CASE WHEN :sortBy = 'chunks_count' AND :sortOrder = 'desc' THEN chunks_count END DESC NULLS LAST,
          CASE WHEN :sortBy = 'status' AND :sortOrder = 'asc' THEN status END ASC,
          CASE WHEN :sortBy = 'status' AND :sortOrder = 'desc' THEN status END DESC,
          created_at DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<DocumentEntity> findPagedDocuments(
        @Param("tenantId") String tenantId,
        @Param("kbId") String kbId,
        @Param("status") String status,
        @Param("folder") String folder,
        @Param("sortBy") String sortBy,
        @Param("sortOrder") String sortOrder,
        @Param("limit") int limit,
        @Param("offset") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM documents
        WHERE tenant_id = :tenantId
          AND (:kbId IS NULL OR kb_id = :kbId)
          AND (:status IS NULL OR status = :status)
          AND (:folder IS NULL OR folder = :folder)
        """, nativeQuery = true)
    long countDocuments(
        @Param("tenantId") String tenantId,
        @Param("kbId") String kbId,
        @Param("status") String status,
        @Param("folder") String folder);

    @Query(value = """
        SELECT sub.name, COUNT(*) AS doc_count, COALESCE(SUM(sub.size_bytes), 0) AS total_size
        FROM (
          SELECT
            CASE WHEN :parent = '' THEN split_part(folder, '/', 1)
                 ELSE split_part(substring(folder from length(:parent) + 2), '/', 1)
            END AS name,
            size_bytes
          FROM documents
          WHERE tenant_id = :tenantId
            AND kb_id = :kbId
            AND folder != ''
            AND (
              (:parent = '' AND folder != '')
              OR (:parent != '' AND folder LIKE :parent || '/%')
            )
        ) sub
        WHERE sub.name != ''
        GROUP BY sub.name
        ORDER BY sub.name
        """, nativeQuery = true)
    List<Object[]> findSubfolders(
        @Param("tenantId") String tenantId,
        @Param("kbId") String kbId,
        @Param("parent") String parent);

    @Query(value = """
        SELECT status, COUNT(*) as cnt FROM documents
        WHERE tenant_id = :tenantId AND kb_id = :kbId
        GROUP BY status
        """, nativeQuery = true)
    List<Object[]> countByStatusGrouped(
        @Param("tenantId") String tenantId,
        @Param("kbId") String kbId);
}
