package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakebaseFSEventForwarderRoutingTest {

    @Test
    void missing_profile_keeps_legacy_memory_route() {
        assertTrue(LakebaseFSEventForwarder.routesToMemoryWorker(null));
    }

    @Test
    void dataset_profile_routes_to_processing_router() {
        assertFalse(LakebaseFSEventForwarder.routesToMemoryWorker("dataset"));
    }

    @Test
    void builds_folder_entity_for_profile_dispatch() {
        LakebaseFSFolderEntity folder = LakebaseFSEventForwarder.folderForProcessingProfile(
                "tn_1",
                "/datasets/orders.csv",
                "dataset");

        assertEquals("tn_1", folder.getTenantId());
        assertEquals("/datasets/orders.csv", folder.getDisplayName());
        assertEquals("dataset", folder.getProcessingProfile());
    }
}
