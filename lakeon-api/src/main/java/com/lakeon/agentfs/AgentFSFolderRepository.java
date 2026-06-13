package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentFSFolderRepository extends JpaRepository<AgentFSFolderEntity, String> {
    List<AgentFSFolderEntity> findByTenantIdOrderByDisplayNameAsc(String tenantId);
    Optional<AgentFSFolderEntity> findByTenantIdAndId(String tenantId, String id);
    Optional<AgentFSFolderEntity> findByTenantIdAndDisplayName(String tenantId, String displayName);
}
