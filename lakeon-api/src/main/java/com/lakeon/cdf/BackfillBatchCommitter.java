package com.lakeon.cdf;

import com.lakeon.model.entity.LakebaseCdfStreamEntity;

import java.sql.SQLException;

public interface BackfillBatchCommitter {
    void commitBatch(LakebaseCdfStreamEntity stream, CdfBatch batch) throws SQLException;
}
