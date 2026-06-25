package com.lakeon.repository;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LakebaseCdfStreamRepository extends JpaRepository<LakebaseCdfStreamEntity, String> {
    List<LakebaseCdfStreamEntity> findByTenantIdAndDatabaseId(String tenantId, String databaseId);
    Optional<LakebaseCdfStreamEntity> findByIdAndTenantIdAndDatabaseId(
            String id, String tenantId, String databaseId);
    Optional<LakebaseCdfStreamEntity> findByTenantIdAndDatabaseIdAndBranchIdAndTargetNamespaceAndTargetTable(
            String tenantId, String databaseId, String branchId, String targetNamespace, String targetTable);
}
