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
        // memories.memory_type ∈ {fact, episode, procedural, decision, rejection, convention}
        // (V32 CHECK constraint in memory-svc schema)
        //
        // feedback_*.md — user preferences and conventions ("don't use emoji",
        //   "deploy via hwstaff") → convention
        // project_*.md  — architectural decisions, benchmark facts → decision
        // reference_*.md, user_*.md — static facts / identity → fact
        if (frontmatterType == null) return "fact";
        return switch (frontmatterType) {
            case "feedback" -> "convention";
            case "project"  -> "decision";
            case "reference", "user" -> "fact";
            default -> "fact";
        };
    }
}
