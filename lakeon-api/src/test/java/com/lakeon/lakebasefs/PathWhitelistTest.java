package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathWhitelistTest {

    @Test
    void accepts_global_memory_md() {
        assertTrue(PathWhitelist.accept("/memory/feedback_x.md"));
        assertTrue(PathWhitelist.accept("/memory/user_y.md"));
    }

    @Test
    void accepts_per_project_memory_md() {
        assertTrue(PathWhitelist.accept("/projects/proj-a/memory/project_foo.md"));
        assertTrue(PathWhitelist.accept("/projects/-Users-jacky-code-lakeon/memory/feedback_e2e_testing.md"));
    }

    @Test
    void rejects_generated_memory_md_index() {
        assertFalse(PathWhitelist.accept("/memory/MEMORY.md"));
        assertFalse(PathWhitelist.accept("/projects/X/memory/MEMORY.md"));
    }

    @Test
    void rejects_unrelated_paths() {
        assertFalse(PathWhitelist.accept("/tasks/x.md"));
        assertFalse(PathWhitelist.accept("/projects/X/foo.jsonl"));
        assertFalse(PathWhitelist.accept("/projects/X/memory/nested/sub.md"));  // nested subdirs not supported in MVP
        assertFalse(PathWhitelist.accept(null));
        assertFalse(PathWhitelist.accept(""));
    }

    @Test
    void maps_frontmatter_type_to_memory_type() {
        assertEquals("convention", PathWhitelist.frontmatterTypeToMemoryType("feedback"));
        assertEquals("decision",   PathWhitelist.frontmatterTypeToMemoryType("project"));
        assertEquals("fact",       PathWhitelist.frontmatterTypeToMemoryType("reference"));
        assertEquals("fact",       PathWhitelist.frontmatterTypeToMemoryType("user"));
        assertEquals("fact",       PathWhitelist.frontmatterTypeToMemoryType(null));
        assertEquals("fact",       PathWhitelist.frontmatterTypeToMemoryType("unknown"));
    }
}
