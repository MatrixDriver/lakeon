package com.lakeon.agentfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.service.exception.BadRequestException;

import java.util.Map;
import java.util.Set;

public record AgentFSFolderProfile(
        String displayName,
        String directoryKind,
        String storagePolicy,
        String processingProfile) {

    public static final String KIND_CODEX_HOME = "codex-home";
    public static final String KIND_CLAUDE_HOME = "claude-home";
    public static final String KIND_OPENCLAW_HOME = "openclaw-home";
    public static final String KIND_OPENCODE_HOME = "opencode-home";
    public static final String KIND_ICEBERG_TABLE = "iceberg-table";
    public static final String KIND_LANCE_TABLE = "lance-table";
    public static final String KIND_DATA_DIR = "data-dir";
    public static final String KIND_FILES = "files";

    public static final String STORAGE_AUTO = "auto";
    public static final String STORAGE_INLINE_ONLY = "inline-only";
    public static final String STORAGE_OBJECT_FIRST = "object-first";
    public static final String STORAGE_OBJECT_ONLY = "object-only";
    public static final String STORAGE_TABLE_NATIVE = "table-native";

    public static final String PROCESSING_NONE = "none";
    public static final String PROCESSING_AGENT_HOME = "agent-home";
    public static final String PROCESSING_DATASET = "dataset";
    public static final String PROCESSING_ICEBERG = "iceberg";
    public static final String PROCESSING_LANCE = "lance";
    public static final String PROCESSING_SMALL_FILE_MEMORY = "small-file-memory";

    private static final Set<String> DIRECTORY_KINDS = Set.of(
            KIND_CODEX_HOME,
            KIND_CLAUDE_HOME,
            KIND_OPENCLAW_HOME,
            KIND_OPENCODE_HOME,
            KIND_ICEBERG_TABLE,
            KIND_LANCE_TABLE,
            KIND_DATA_DIR,
            KIND_FILES);

    private static final Set<String> STORAGE_POLICIES = Set.of(
            STORAGE_AUTO,
            STORAGE_INLINE_ONLY,
            STORAGE_OBJECT_FIRST,
            STORAGE_OBJECT_ONLY,
            STORAGE_TABLE_NATIVE);

    private static final Set<String> PROCESSING_PROFILES = Set.of(
            PROCESSING_NONE,
            PROCESSING_AGENT_HOME,
            PROCESSING_DATASET,
            PROCESSING_ICEBERG,
            PROCESSING_LANCE,
            PROCESSING_SMALL_FILE_MEMORY);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, Defaults> DEFAULTS = Map.of(
            KIND_CODEX_HOME, new Defaults(STORAGE_AUTO, PROCESSING_AGENT_HOME),
            KIND_CLAUDE_HOME, new Defaults(STORAGE_AUTO, PROCESSING_AGENT_HOME),
            KIND_OPENCLAW_HOME, new Defaults(STORAGE_AUTO, PROCESSING_AGENT_HOME),
            KIND_OPENCODE_HOME, new Defaults(STORAGE_AUTO, PROCESSING_AGENT_HOME),
            KIND_ICEBERG_TABLE, new Defaults(STORAGE_TABLE_NATIVE, PROCESSING_ICEBERG),
            KIND_LANCE_TABLE, new Defaults(STORAGE_TABLE_NATIVE, PROCESSING_LANCE),
            KIND_DATA_DIR, new Defaults(STORAGE_OBJECT_FIRST, PROCESSING_DATASET),
            KIND_FILES, new Defaults(STORAGE_AUTO, PROCESSING_NONE));

    public static AgentFSFolderProfile normalize(
            String displayName,
            String directoryKind,
            String storagePolicy,
            String processingProfile) {
        String name = requireNonBlank(displayName, "display_name");
        String kind = normalizeToken(directoryKind, KIND_FILES);
        if (!DIRECTORY_KINDS.contains(kind)) {
            throw new BadRequestException("unsupported directory_kind: " + kind);
        }
        Defaults defaults = DEFAULTS.get(kind);
        String storage = normalizeToken(storagePolicy, defaults.storagePolicy());
        String processing = normalizeToken(processingProfile, defaults.processingProfile());
        if (!STORAGE_POLICIES.contains(storage)) {
            throw new BadRequestException("unsupported storage_policy: " + storage);
        }
        if (!PROCESSING_PROFILES.contains(processing)) {
            throw new BadRequestException("unsupported processing_profile: " + processing);
        }
        return new AgentFSFolderProfile(name, kind, storage, processing);
    }

    public boolean routesToMemoryWorker() {
        return PROCESSING_AGENT_HOME.equals(processingProfile)
                || PROCESSING_SMALL_FILE_MEMORY.equals(processingProfile);
    }

    public static boolean propertiesRouteToMemoryWorker(String propertiesJson) {
        String processing = processingProfileFromProperties(propertiesJson);
        if (processing == null) {
            return true;
        }
        return PROCESSING_AGENT_HOME.equals(processing)
                || PROCESSING_SMALL_FILE_MEMORY.equals(processing);
    }

    public static String processingProfileFromProperties(String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(propertiesJson);
            JsonNode profile = root.path("lbfs_profile");
            JsonNode processing = profile.path("processing_profile");
            if (processing.isMissingNode() || processing.isNull() || processing.asText().isBlank()) {
                return null;
            }
            return processing.asText().trim().toLowerCase();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeToken(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim().toLowerCase();
    }

    private static String requireNonBlank(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(field + " required");
        }
        return raw.trim();
    }

    private record Defaults(String storagePolicy, String processingProfile) {}
}
