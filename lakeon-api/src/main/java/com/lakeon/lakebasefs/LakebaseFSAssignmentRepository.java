package com.lakeon.lakebasefs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LakebaseFSAssignmentRepository
        extends JpaRepository<LakebaseFSAssignmentEntity, String> {

    Optional<LakebaseFSAssignmentEntity> findByTenantId(String tenantId);
}
