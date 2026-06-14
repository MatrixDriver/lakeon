package com.lakeon.lakebasefs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LakebaseFSFolderRepository extends JpaRepository<LakebaseFSFolderEntity, String> {
    List<LakebaseFSFolderEntity> findByTenantIdOrderByDisplayNameAsc(String tenantId);
    Optional<LakebaseFSFolderEntity> findByTenantIdAndId(String tenantId, String id);
    Optional<LakebaseFSFolderEntity> findByTenantIdAndDisplayName(String tenantId, String displayName);
}
