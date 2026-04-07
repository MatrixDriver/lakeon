package com.lakeon.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface WikiRunLogRepository extends JpaRepository<WikiRunLogEntity, String> {
    List<WikiRunLogEntity> findByKbIdOrderByCreatedAtDesc(String kbId, Pageable pageable);
    List<WikiRunLogEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    @Query("SELECT w FROM WikiRunLogEntity w ORDER BY w.createdAt DESC")
    List<WikiRunLogEntity> findAllRecent(Pageable pageable);

    @Query("SELECT w.kbId, COUNT(w), SUM(w.pagesCreated), SUM(w.pagesDeleted) FROM WikiRunLogEntity w WHERE w.tenantId = :tenantId GROUP BY w.kbId")
    List<Object[]> statsPerKb(@Param("tenantId") String tenantId);
}
