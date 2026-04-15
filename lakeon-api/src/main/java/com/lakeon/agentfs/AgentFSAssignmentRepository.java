package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentFSAssignmentRepository
        extends JpaRepository<AgentFSAssignmentEntity, String> {

    Optional<AgentFSAssignmentEntity> findByTenantId(String tenantId);
}
