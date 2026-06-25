package com.lakeon.cdf;

import java.util.List;
import java.util.Map;

public record CdfBatch(
        String streamId,
        String branchId,
        String startLsn,
        String endLsn,
        String operation,
        List<Map<String, Object>> rows,
        long snapshotId) {
}
