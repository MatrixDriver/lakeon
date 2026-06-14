package com.lakeon.agentfs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LBFSAutoJobRepository extends JpaRepository<LBFSAutoJobEntity, String> {
    List<LBFSAutoJobEntity> findByTenantIdAndFolderIdOrderByCreatedAtDesc(
            String tenantId,
            String folderId);
}
