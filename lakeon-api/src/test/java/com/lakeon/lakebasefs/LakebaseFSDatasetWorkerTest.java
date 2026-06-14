package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakebaseFSDatasetWorkerTest {

    @Test
    void profiles_csv_header_and_sample_rows() {
        byte[] csv = "order_id,amount\no1,12.5\no2,9.0\n".getBytes(StandardCharsets.UTF_8);

        LakebaseFSDataProfile profile = LakebaseFSDatasetWorker.profileCsv("/datasets/orders.csv", csv);

        assertEquals("/datasets/orders.csv", profile.path());
        assertEquals("csv", profile.format());
        assertEquals(List.of("order_id", "amount"), profile.columns());
        assertEquals(2, profile.sampleRowCount());
    }

    @Test
    void queues_external_profiler_for_unsupported_binary_dataset_formats() {
        LakebaseFSDatasetWorker worker = new LakebaseFSDatasetWorker();

        LakebaseFSProcessingResult result = worker.process(
                folder(),
                new LakebaseFSProcessingEvent("tn_1", "/datasets/orders.xlsx", "etag_1", "put"));

        assertTrue(result.accepted());
        assertEquals(false, result.retryable());
        assertEquals("metadata queued for external profiler: /datasets/orders.xlsx", result.message());
    }

    private static LakebaseFSFolderEntity folder() {
        LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
        folder.setTenantId("tn_1");
        folder.setDisplayName("orders");
        folder.setDirectoryKind("data-dir");
        folder.setStoragePolicy("object-first");
        folder.setProcessingProfile("dataset");
        return folder;
    }
}
