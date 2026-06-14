package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentFSProcessingJobRepository extends JpaRepository<AgentFSProcessingJobEntity, String> {
    List<AgentFSProcessingJobEntity> findByTenantIdAndFolderIdOrderByCreatedAtDesc(
            String tenantId,
            String folderId);
}
