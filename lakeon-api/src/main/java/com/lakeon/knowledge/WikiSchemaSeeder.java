package com.lakeon.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a default {@code schema.md} wiki page into every newly-created knowledge base.
 * Runs AFTER_COMMIT so a seed failure cannot poison the createKnowledgeBase transaction.
 *
 * The default schema is loaded once at class-load time from the classpath; if the resource
 * is missing, this fact is logged at ERROR on startup and seeding becomes a no-op.
 */
@Component
public class WikiSchemaSeeder {
    private static final Logger log = LoggerFactory.getLogger(WikiSchemaSeeder.class);
    private static final String DEFAULT_SCHEMA_CONTENT = loadDefaultSchemaOrEmpty();

    private final WikiService wikiService;

    public WikiSchemaSeeder(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onKnowledgeBaseCreated(KnowledgeBaseCreatedEvent event) {
        if (DEFAULT_SCHEMA_CONTENT.isEmpty()) {
            log.warn("Skipping schema seed for KB {} because default-schema.md is missing from classpath",
                    event.getKbId());
            return;
        }
        try {
            wikiService.writeWikiDocument(
                    event.getTenantId(), event.getKbId(),
                    "schema.md", "KB Schema", DEFAULT_SCHEMA_CONTENT);
            log.info("Seeded default schema.md for KB {} (tenant {})",
                    event.getKbId(), event.getTenantId());
        } catch (Exception e) {
            log.warn("Failed to seed default schema for KB {} (tenant {}): {}",
                    event.getKbId(), event.getTenantId(), e.toString(), e);
        }
    }

    private static String loadDefaultSchemaOrEmpty() {
        try (var in = WikiSchemaSeeder.class.getResourceAsStream("/wiki/default-schema.md")) {
            if (in == null) {
                LoggerFactory.getLogger(WikiSchemaSeeder.class)
                        .error("default-schema.md missing from classpath at class load");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LoggerFactory.getLogger(WikiSchemaSeeder.class)
                    .error("Failed to read default-schema.md: {}", e.getMessage());
            return "";
        }
    }
}
