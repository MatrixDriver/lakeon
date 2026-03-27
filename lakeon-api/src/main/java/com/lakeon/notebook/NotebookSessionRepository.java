package com.lakeon.notebook;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotebookSessionRepository extends JpaRepository<NotebookSessionEntity, String> {
    Optional<NotebookSessionEntity> findByTenantIdAndStatus(String tenantId, NotebookSessionStatus status);
    List<NotebookSessionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<NotebookSessionEntity> findByStatusAndLastActiveAtBefore(NotebookSessionStatus status, Instant cutoff);
}
