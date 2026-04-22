package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentFSMemoryTargetRepository
        extends JpaRepository<AgentFSMemoryTargetEntity, String> {

    Optional<AgentFSMemoryTargetEntity> findByTenantId(String tenantId);
}
