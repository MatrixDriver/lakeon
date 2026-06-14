package com.lakeon.lakebasefs;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.Map;

public class FrontmatterParser {

    public record Parsed(Map<String, Object> frontmatter, String body) {}

    private static final YAMLMapper YAML = new YAMLMapper();

    private FrontmatterParser() { /* static only */ }

    public static Parsed parse(String raw) {
        if (raw == null) return new Parsed(Map.of(), "");
        if (!raw.startsWith("---\n")) return new Parsed(Map.of(), raw);
        // Handle empty frontmatter: "---\n---\n..."
        if (raw.startsWith("---\n---\n")) {
            return new Parsed(Map.of(), raw.substring("---\n---\n".length()));
        }
        int end = raw.indexOf("\n---\n", 4);
        if (end < 0) return new Parsed(Map.of(), raw);
        String yaml = raw.substring(4, end);
        String body = raw.substring(end + 5);
        if (yaml.isBlank()) return new Parsed(Map.of(), body);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = YAML.readValue(yaml, Map.class);
            return new Parsed(m == null ? Map.of() : m, body);
        } catch (Exception e) {
            return new Parsed(Map.of(), raw);
        }
    }
}
