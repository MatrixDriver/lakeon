package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontmatterParserTest {

    @Test
    void parses_yaml_frontmatter_and_strips_body() {
        String raw = "---\nname: xyz\ntype: feedback\n---\n\nHello world\n";
        var r = FrontmatterParser.parse(raw);
        assertEquals("feedback", r.frontmatter().get("type"));
        assertEquals("\nHello world\n", r.body());
    }

    @Test
    void no_frontmatter_returns_whole_body() {
        var r = FrontmatterParser.parse("just body\n");
        assertTrue(r.frontmatter().isEmpty());
        assertEquals("just body\n", r.body());
    }

    @Test
    void empty_frontmatter_is_tolerated() {
        var r = FrontmatterParser.parse("---\n---\nbody\n");
        assertTrue(r.frontmatter().isEmpty());
        assertEquals("body\n", r.body());
    }

    @Test
    void malformed_frontmatter_falls_back_to_plain_body() {
        // Unterminated frontmatter marker — treat entire text as body.
        String raw = "---\nname: xyz\nno closing marker\n";
        var r = FrontmatterParser.parse(raw);
        assertTrue(r.frontmatter().isEmpty());
        assertEquals(raw, r.body());
    }
}
