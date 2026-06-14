package com.lakeon.agentfs;

import java.util.List;
import java.util.Map;

public record AgentFSDataProfile(
        String path,
        String format,
        List<String> columns,
        int sampleRowCount,
        Map<String, Object> statistics) {
}
