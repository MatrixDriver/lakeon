package com.lakeon.repository;

import com.lakeon.model.entity.QueryHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface QueryHistoryRepository extends JpaRepository<QueryHistoryEntity, Long> {

    Page<QueryHistoryEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Page<QueryHistoryEntity> findByTenantIdAndDatabaseIdOrderByCreatedAtDesc(
            String tenantId, String databaseId, Pageable pageable);

    @Query("SELECT q FROM QueryHistoryEntity q WHERE q.tenantId = :tenantId " +
           "AND LOWER(q.sqlText) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY q.createdAt DESC")
    Page<QueryHistoryEntity> searchByKeyword(String tenantId, String keyword, Pageable pageable);

    @Query("SELECT q FROM QueryHistoryEntity q WHERE q.tenantId = :tenantId " +
           "AND q.databaseId = :databaseId " +
           "AND LOWER(q.sqlText) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY q.createdAt DESC")
    Page<QueryHistoryEntity> searchByDatabaseAndKeyword(
            String tenantId, String databaseId, String keyword, Pageable pageable);

    long countByTenantId(String tenantId);

    @Modifying
    @Query("DELETE FROM QueryHistoryEntity q WHERE q.tenantId = :tenantId")
    void deleteAllByTenantId(String tenantId);

    @Modifying
    @Query("DELETE FROM QueryHistoryEntity q WHERE q.tenantId = :tenantId AND q.databaseId = :databaseId")
    void deleteAllByTenantIdAndDatabaseId(String tenantId, String databaseId);
}
