package com.lakeon.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("LakeonProperties wiki.agent binding")
class LakeonPropertiesTest {

    @Test
    @DisplayName("default wiki.agent values on a fresh instance")
    void wikiAgentDefaults() {
        LakeonProperties props = new LakeonProperties();
        assertNotNull(props.getWiki(), "wiki should be non-null by default");
        assertNotNull(props.getWiki().getAgent(), "wiki.agent should be non-null by default");
        // URL/internalToken default to null (filled in by application.yml env binding);
        // timeoutSeconds has a Java-level default of 300.
        assertEquals(300, props.getWiki().getAgent().getTimeoutSeconds());
    }

    @Test
    @DisplayName("lakeon.wiki.agent.* binds via relaxed binding (internal-token -> internalToken)")
    void wikiAgentBindingFromProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("lakeon.wiki.agent.url", "http://localhost:8090");
        properties.put("lakeon.wiki.agent.internal-token", "lakeon-wiki-agent-2026");
        properties.put("lakeon.wiki.agent.timeout-seconds", "300");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        LakeonProperties bound = new Binder(source)
            .bind("lakeon", LakeonProperties.class)
            .get();

        assertNotNull(bound.getWiki());
        assertNotNull(bound.getWiki().getAgent());
        assertEquals("http://localhost:8090", bound.getWiki().getAgent().getUrl());
        assertEquals("lakeon-wiki-agent-2026", bound.getWiki().getAgent().getInternalToken());
        assertEquals(300, bound.getWiki().getAgent().getTimeoutSeconds());
    }
}
