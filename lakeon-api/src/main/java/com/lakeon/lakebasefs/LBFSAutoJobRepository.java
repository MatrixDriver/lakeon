package com.lakeon.lakebasefs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LBFSAutoJobRepository extends JpaRepository<LBFSAutoJobEntity, String> {
    List<LBFSAutoJobEntity> findByTenantIdAndFolderIdOrderByCreatedAtDesc(
            String tenantId,
            String folderId);

    Optional<LBFSAutoJobEntity> findFirstByTenantIdAndFolderIdAndSourcePathAndSourceEtagAndProfileOrderByCreatedAtDesc(
            String tenantId,
            String folderId,
            String sourcePath,
            String sourceEtag,
            String profile);
}
