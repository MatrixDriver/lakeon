package com.lakeon.notebook;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface NotebookRepository extends JpaRepository<NotebookEntity, String> {
    List<NotebookEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
    Optional<NotebookEntity> findByIdAndTenantId(String id, String tenantId);
}
