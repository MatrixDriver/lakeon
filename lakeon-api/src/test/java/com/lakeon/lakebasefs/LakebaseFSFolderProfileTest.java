package com.lakeon.lakebasefs;

import com.lakeon.service.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LakebaseFSFolderProfileTest {

    @Test
    void defaults_agent_home_folders_to_agent_home_processing() {
        LakebaseFSFolderProfile profile = LakebaseFSFolderProfile.normalize(
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
    void defaults_opencode_home_to_agent_home_processing() {
        LakebaseFSFolderProfile profile = LakebaseFSFolderProfile.normalize(
                "opencode-runtime",
                "opencode-home",
                null,
                null);

        assertEquals("opencode-runtime", profile.displayName());
        assertEquals("opencode-home", profile.directoryKind());
        assertEquals("auto", profile.storagePolicy());
        assertEquals("agent-home", profile.processingProfile());
    }

    @Test
    void defaults_data_dir_to_object_first_dataset_processing() {
        LakebaseFSFolderProfile profile = LakebaseFSFolderProfile.normalize(
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
        LakebaseFSFolderProfile profile = LakebaseFSFolderProfile.normalize(
                "docs",
                "files",
                "inline-only",
                "none");

        assertEquals("files", profile.directoryKind());
        assertEquals("inline-only", profile.storagePolicy());
        assertEquals("none", profile.processingProfile());
    }

    @Test
    void rejects_removed_small_file_memory_processing_profile() {
        assertThrows(BadRequestException.class, () -> LakebaseFSFolderProfile.normalize(
                "docs",
                "files",
                "inline-only",
                "small-file-memory"));
    }
}
