package com.lakeon.agentfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentFSFolderProfileTest {

    @Test
    void defaults_agent_home_folders_to_agent_home_processing() {
        AgentFSFolderProfile profile = AgentFSFolderProfile.normalize(
                "work-codex",
                "codex-home",
                null,
                null);

        assertEquals("work-codex", profile.displayName());
        assertEquals("codex-home", profile.directoryKind());
        assertEquals("auto", profile.storagePolicy());
        assertEquals("agent-home", profile.processingProfile());
    }

    @Test
    void defaults_data_dir_to_object_first_dataset_processing() {
        AgentFSFolderProfile profile = AgentFSFolderProfile.normalize(
                "warehouse",
                "data-dir",
                null,
                null);

        assertEquals("data-dir", profile.directoryKind());
        assertEquals("object-first", profile.storagePolicy());
        assertEquals("dataset", profile.processingProfile());
    }

    @Test
    void honors_explicit_storage_and_processing_overrides() {
        AgentFSFolderProfile profile = AgentFSFolderProfile.normalize(
                "docs",
                "files",
                "inline-only",
                "small-file-memory");

        assertEquals("files", profile.directoryKind());
        assertEquals("inline-only", profile.storagePolicy());
        assertEquals("small-file-memory", profile.processingProfile());
    }
}
