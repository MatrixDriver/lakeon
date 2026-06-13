package com.lakeon.agentfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFSBasicProcessingWorkerTest {

    @Test
    void dataset_worker_accepts_dataset_events() {
        AgentFSDatasetWorker worker = new AgentFSDatasetWorker();
        AgentFSFolderEntity folder = folder("dataset");

        AgentFSProcessingResult result = worker.process(
                folder,
                new AgentFSProcessingEvent("tn_1", "/datasets/orders.csv", "e1", "put"));

        assertTrue(result.accepted());
        assertEquals(false, result.retryable());
        assertTrue(result.message().contains("dataset"));
    }

    @Test
    void table_worker_accepts_iceberg_and_lance_events() {
        AgentFSTableWorker icebergWorker = new AgentFSTableWorker();
        AgentFSLanceTableWorker lanceWorker = new AgentFSLanceTableWorker();

        AgentFSProcessingResult iceberg = icebergWorker.process(
                folder("iceberg"),
                new AgentFSProcessingEvent("tn_1", "/tables/orders/metadata/v1.metadata.json", "e1", "put"));
        AgentFSProcessingResult lance = lanceWorker.process(
                folder("lance"),
                new AgentFSProcessingEvent("tn_1", "/vectors/_versions/1.manifest", "e2", "put"));

        assertTrue(iceberg.accepted());
        assertTrue(iceberg.message().contains("iceberg"));
        assertTrue(lance.accepted());
        assertTrue(lance.message().contains("lance"));
    }

    private static AgentFSFolderEntity folder(String processingProfile) {
        AgentFSFolderEntity folder = new AgentFSFolderEntity();
        folder.setTenantId("tn_1");
        folder.setDisplayName("test");
        folder.setDirectoryKind("files");
        folder.setStoragePolicy("auto");
        folder.setProcessingProfile(processingProfile);
        return folder;
    }
}
