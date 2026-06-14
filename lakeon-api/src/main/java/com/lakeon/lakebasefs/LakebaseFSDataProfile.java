package com.lakeon.lakebasefs;

import java.util.List;
import java.util.Map;

public record LakebaseFSDataProfile(
        String path,
        String format,
        List<String> columns,
        int sampleRowCount,
        Map<String, Object> statistics) {
}
