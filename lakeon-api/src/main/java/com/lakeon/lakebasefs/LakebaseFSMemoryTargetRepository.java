package com.lakeon.lakebasefs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LakebaseFSMemoryTargetRepository
        extends JpaRepository<LakebaseFSMemoryTargetEntity, String> {

    Optional<LakebaseFSMemoryTargetEntity> findByTenantId(String tenantId);
}
