package com.lakeon.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiSchemaSeederTest {
    WikiService wikiService;
    WikiSchemaSeeder seeder;

    @BeforeEach
    void setup() {
        wikiService = mock(WikiService.class);
        seeder = new WikiSchemaSeeder(wikiService);
    }

    @Test
    void defaultSchemaResourceIsOnClasspath() {
        assertNotNull(WikiSchemaSeeder.class.getResourceAsStream("/wiki/default-schema.md"),
                "default-schema.md must be on the classpath");
    }

    @Test
    void onEventWritesSchemaPage() {
        seeder.onKnowledgeBaseCreated(new KnowledgeBaseCreatedEvent("t1", "kb1"));

        verify(wikiService).writeWikiDocument(
                eq("t1"), eq("kb1"), eq("schema.md"), eq("KB Schema"),
                argThat(content -> content.contains("KB Schema")
                        && content.contains("Create vs Update Budget")
                        && content.contains("Self-maintenance")));
    }

    @Test
    void seedFailureIsLoggedButNotPropagated() {
        doThrow(new RuntimeException("obs down"))
                .when(wikiService).writeWikiDocument(any(), any(), any(), any(), any());

        // Should not throw
        seeder.onKnowledgeBaseCreated(new KnowledgeBaseCreatedEvent("t1", "kb1"));

        verify(wikiService).writeWikiDocument(any(), any(), any(), any(), any());
    }
}
