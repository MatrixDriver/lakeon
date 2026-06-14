package com.lakeon.lakebasefs;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class LakebaseFSDatasetWorker implements LakebaseFSProcessingWorker {

    @Override
    public String processingProfile() {
        return LakebaseFSFolderProfile.PROCESSING_DATASET;
    }

    @Override
    public LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event) {
        String path = event.path() == null ? "" : event.path().toLowerCase();
        if (path.endsWith(".xlsx") || path.endsWith(".parquet") || path.endsWith(".orc")) {
            return LakebaseFSProcessingResult.done("metadata queued for external profiler: " + event.path());
        }
        if (path.endsWith(".csv") || path.endsWith(".tsv") || path.endsWith(".jsonl") || path.endsWith(".ndjson")) {
            return LakebaseFSProcessingResult.done("dataset metadata observed: " + event.path());
        }
        return LakebaseFSProcessingResult.done("dataset event accepted: " + event.path());
    }

    public static LakebaseFSDataProfile profileCsv(String path, byte[] data) {
        String raw = new String(data == null ? new byte[0] : data, StandardCharsets.UTF_8);
        List<String> lines = raw.lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return new LakebaseFSDataProfile(path, "csv", List.of(), 0, Map.of());
        }
        List<String> columns = Arrays.stream(lines.get(0).split(",", -1))
                .map(String::trim)
                .toList();
        return new LakebaseFSDataProfile(path, "csv", columns, Math.max(0, lines.size() - 1), Map.of());
    }
}
