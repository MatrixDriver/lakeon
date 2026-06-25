package com.lakeon.iceberg;

import com.lakeon.model.entity.TenantEntity;

import java.sql.Connection;
import java.sql.SQLException;

public interface LakebaseBranchConnectionProvider {
    Connection open(TenantEntity tenant, String databaseId, String branchId) throws SQLException;
}
