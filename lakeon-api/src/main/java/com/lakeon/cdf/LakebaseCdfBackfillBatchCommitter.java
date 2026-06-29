package com.lakeon.cdf;

import com.lakeon.iceberg.LakebaseBranchConnectionProvider;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.model.entity.TenantEntity;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

@Component
public class LakebaseCdfBackfillBatchCommitter implements BackfillBatchCommitter {
    private final LakebaseBranchConnectionProvider connectionProvider;
    private final LakebaseCdfWorker worker;

    public LakebaseCdfBackfillBatchCommitter(LakebaseBranchConnectionProvider connectionProvider,
                                             LakebaseCdfWorker worker) {
        this.connectionProvider = connectionProvider;
        this.worker = worker;
    }

    @Override
    public LakebaseCdfWorker.CommitResult commitBatch(LakebaseCdfStreamEntity stream, CdfBatch batch)
            throws SQLException {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(stream.getTenantId());
        try (Connection connection = connectionProvider.open(tenant, stream.getDatabaseId(), stream.getBranchId())) {
            return worker.commitBatch(connection, stream, batch);
        }
    }
}
