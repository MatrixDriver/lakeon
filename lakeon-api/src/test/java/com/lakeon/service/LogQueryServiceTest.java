package com.lakeon.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogQueryServiceTest {

    @Test
    void normalizeJdbcUrl_convertsPostgresScheme() {
        assertEquals(
            "jdbc:postgresql://host:5432/db?sslmode=require",
            LogQueryService.normalizeJdbcUrl("postgres://host:5432/db?sslmode=require"));
    }

    @Test
    void normalizeJdbcUrl_convertsPostgresqlScheme() {
        assertEquals(
            "jdbc:postgresql://host:5432/db",
            LogQueryService.normalizeJdbcUrl("postgresql://host:5432/db"));
    }

    @Test
    void normalizeJdbcUrl_preservesJdbcPrefix() {
        assertEquals(
            "jdbc:postgresql://host:5432/db",
            LogQueryService.normalizeJdbcUrl("jdbc:postgresql://host:5432/db"));
    }

    @Test
    void normalizeJdbcUrl_handlesUserInfoInUri() {
        assertEquals(
            "jdbc:postgresql://user:pass@host:5432/db?sslmode=require",
            LogQueryService.normalizeJdbcUrl("postgres://user:pass@host:5432/db?sslmode=require"));
    }
}
