package com.lakeon.agentfs;

import java.util.regex.Pattern;

public class PathWhitelist {

    private static final Pattern GLOBAL_MEMORY =
        Pattern.compile("^/memory/[^/]+\\.md$");
    private static final Pattern PROJECT_MEMORY =
        Pattern.compile("^/projects/[^/]+/memory/[^/]+\\.md$");

    private PathWhitelist() { /* static only */ }

    public static boolean accept(String path) {
        if (path == null || path.isEmpty()) return false;
        if (path.endsWith("/MEMORY.md")) return false;
        return GLOBAL_MEMORY.matcher(path).matches()
            || PROJECT_MEMORY.matcher(path).matches();
    }

    public static String frontmatterTypeToMemoryType(String frontmatterType) {
        if (frontmatterType == null) return "fact";
        return switch (frontmatterType) {
            case "feedback" -> "procedural";
            case "project"  -> "episode";
            case "reference", "user" -> "fact";
            default -> "fact";
        };
    }
}
