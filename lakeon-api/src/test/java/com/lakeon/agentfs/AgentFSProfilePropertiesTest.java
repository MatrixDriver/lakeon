package com.lakeon.agentfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFSProfilePropertiesTest {

    @Test
    void memory_worker_accepts_agent_home_profiles() {
        assertTrue(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"agentfs_profile":{"processing_profile":"agent-home"}}
            """));
        assertTrue(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"agentfs_profile":{"processing_profile":"small-file-memory"}}
            """));
    }

    @Test
    void memory_worker_rejects_non_memory_profiles() {
        assertFalse(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"agentfs_profile":{"processing_profile":"dataset"}}
            """));
        assertFalse(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"agentfs_profile":{"processing_profile":"none"}}
            """));
    }

    @Test
    void missing_profile_keeps_legacy_memory_behavior() {
        assertTrue(AgentFSFolderProfile.propertiesRouteToMemoryWorker("{}"));
    }
}
