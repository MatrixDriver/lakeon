package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakebaseFSBasicProcessingWorkerTest {

    @Test
    void dataset_worker_accepts_dataset_events() {
        LakebaseFSDatasetWorker worker = new LakebaseFSDatasetWorker();
        LakebaseFSFolderEntity folder = folder("dataset");

        LakebaseFSProcessingResult result = worker.process(
                folder,
                new LakebaseFSProcessingEvent("tn_1", "/datasets/orders.csv", "e1", "put"));

        assertTrue(result.accepted());
        assertEquals(false, result.retryable());
        assertTrue(result.message().contains("dataset"));
    }

    @Test
    void table_worker_accepts_iceberg_and_lance_events() {
        LakebaseFSTableWorker icebergWorker = new LakebaseFSTableWorker();
        LakebaseFSLanceTableWorker lanceWorker = new LakebaseFSLanceTableWorker();

        LakebaseFSProcessingResult iceberg = icebergWorker.process(
                folder("iceberg"),
                new LakebaseFSProcessingEvent("tn_1", "/tables/orders/metadata/v1.metadata.json", "e1", "put"));
        LakebaseFSProcessingResult lance = lanceWorker.process(
                folder("lance"),
                new LakebaseFSProcessingEvent("tn_1", "/vectors/_versions/1.manifest", "e2", "put"));

        assertTrue(iceberg.accepted());
        assertTrue(iceberg.message().contains("iceberg"));
        assertTrue(lance.accepted());
        assertTrue(lance.message().contains("lance"));
    }

    private static LakebaseFSFolderEntity folder(String processingProfile) {
        LakebaseFSFolderEntity folder = new LakebaseFSFolderEntity();
        folder.setTenantId("tn_1");
        folder.setDisplayName("test");
        folder.setDirectoryKind("files");
        folder.setStoragePolicy("auto");
        folder.setProcessingProfile(processingProfile);
        return folder;
    }
}
