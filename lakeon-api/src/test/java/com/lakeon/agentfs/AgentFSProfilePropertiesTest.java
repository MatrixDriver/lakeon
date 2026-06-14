package com.lakeon.agentfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFSProfilePropertiesTest {

    @Test
    void memory_worker_accepts_agent_home_profiles_only() {
        assertTrue(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"lbfs_profile":{"processing_profile":"agent-home"}}
            """));
    }

    @Test
    void memory_worker_rejects_non_memory_profiles() {
        assertFalse(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"lbfs_profile":{"processing_profile":"dataset"}}
            """));
        assertFalse(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"lbfs_profile":{"processing_profile":"none"}}
            """));
        assertFalse(AgentFSFolderProfile.propertiesRouteToMemoryWorker("""
            {"lbfs_profile":{"processing_profile":"small-file-memory"}}
            """));
    }

    @Test
    void missing_profile_defaults_to_memory_behavior() {
        assertTrue(AgentFSFolderProfile.propertiesRouteToMemoryWorker("{}"));
    }
}
