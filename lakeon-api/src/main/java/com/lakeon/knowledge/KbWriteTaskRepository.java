package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KbWriteTaskRepository extends JpaRepository<KbWriteTaskEntity, String> {

    Optional<KbWriteTaskEntity> findByIdAndTenantId(String id, String tenantId);

    @Query("SELECT t FROM KbWriteTaskEntity t WHERE t.databaseId = ?1 AND t.status = 'QUEUED' ORDER BY t.createdAt ASC")
    List<KbWriteTaskEntity> findQueuedByDatabaseId(String databaseId);

    @Query("SELECT t FROM KbWriteTaskEntity t WHERE t.databaseId = ?1 AND t.status IN ('QUEUED', 'RUNNING')")
    List<KbWriteTaskEntity> findActiveByDatabaseId(String databaseId);

    Optional<KbWriteTaskEntity> findByJobId(String jobId);

    @Query("SELECT DISTINCT t.databaseId FROM KbWriteTaskEntity t WHERE t.status IN ('QUEUED', 'RUNNING')")
    List<String> findDatabaseIdsWithActiveTasks();

    @Query("SELECT t FROM KbWriteTaskEntity t WHERE t.status = 'RUNNING' AND t.startedAt < ?1")
    List<KbWriteTaskEntity> findStuckRunningBefore(Instant cutoff);

    @Modifying @Transactional
    @Query("UPDATE KbWriteTaskEntity t SET t.status = 'FAILED', t.error = 'Cancelled: KB deleted', t.completedAt = CURRENT_TIMESTAMP WHERE t.kbId = ?1 AND t.status IN ('QUEUED', 'RUNNING')")
    int cancelByKbId(String kbId);
}
