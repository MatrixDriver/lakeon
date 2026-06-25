package com.lakeon.cdf;

import java.sql.SQLException;

public interface BackfillBatchCommitter {
    void commitBatch(CdfBatch batch) throws SQLException;
}
