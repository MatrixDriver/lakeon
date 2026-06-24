package com.lakeon.pageserver;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageserverAssignmentRepository extends JpaRepository<PageserverAssignmentEntity, String> {
    List<PageserverAssignmentEntity> findAllByNodeId(String nodeId);
    List<PageserverAssignmentEntity> findAllByTenantId(String tenantId);
}
