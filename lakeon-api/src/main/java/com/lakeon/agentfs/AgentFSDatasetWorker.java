package com.lakeon.agentfs;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class AgentFSDatasetWorker implements AgentFSProcessingWorker {

    @Override
    public String processingProfile() {
        return AgentFSFolderProfile.PROCESSING_DATASET;
    }

    @Override
    public AgentFSProcessingResult process(AgentFSFolderEntity folder, AgentFSProcessingEvent event) {
        String path = event.path() == null ? "" : event.path().toLowerCase();
        if (path.endsWith(".xlsx") || path.endsWith(".parquet") || path.endsWith(".orc")) {
            return AgentFSProcessingResult.done("metadata queued for external profiler: " + event.path());
        }
        if (path.endsWith(".csv") || path.endsWith(".tsv") || path.endsWith(".jsonl") || path.endsWith(".ndjson")) {
            return AgentFSProcessingResult.done("dataset metadata observed: " + event.path());
        }
        return AgentFSProcessingResult.done("dataset event accepted: " + event.path());
    }

    public static AgentFSDataProfile profileCsv(String path, byte[] data) {
        String raw = new String(data == null ? new byte[0] : data, StandardCharsets.UTF_8);
        List<String> lines = raw.lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return new AgentFSDataProfile(path, "csv", List.of(), 0, Map.of());
        }
        List<String> columns = Arrays.stream(lines.get(0).split(",", -1))
                .map(String::trim)
                .toList();
        return new AgentFSDataProfile(path, "csv", columns, Math.max(0, lines.size() - 1), Map.of());
    }
}
