package com.lakeon.lakebasefs;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LakebaseFSService#append(TenantEntity, String, byte[], String)}.
 *
 * Verifies the new four-arg overload's {@code if_match} precondition handling:
 *   1. matching etag → succeeds and returns the updated row
 *   2. stale etag    → throws BadRequestException whose message contains
 *                      "precondition_failed"
 *   3. null/empty    → legacy behavior, no precondition check
 *
 * Strategy: mock {@link LakebaseFSDatabaseManager} so every call to
 * {@code openConnection} returns a fresh JDBC connection to a real Postgres
 * spun up via Testcontainers. The {@code append} SQL relies on Postgres-only
 * features (JSONB, {@code ON CONFLICT … DO UPDATE}, {@code IS DISTINCT FROM},
 * {@code ::jsonb} casts), so H2 in PG mode isn't sufficient. The full Spring
 * context is not loaded — only the {@code LakebaseFSService} bean is constructed
 * by hand.
 */
@Testcontainers
class AppendIfMatchTest {

    @Container
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lbfs_append_test")
            .withUsername("test")
            .withPassword("test");

    private LakebaseFSDatabaseManager dbm;
    private LakebaseFSService svc;
    private TenantEntity tenant;

    @BeforeEach
    void setup() throws SQLException {
        // Reset schema between tests so each test starts from a clean files
        // table. Matches the production schema defined in
        // LakebaseFSDatabaseManager#FILES_SCHEMA, minus the CDC trigger (not
        // exercised by append).
        try (Connection c = openPg(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS files");
            st.execute("""
                CREATE TABLE files (
                    path        TEXT PRIMARY KEY,
                    kind        VARCHAR(8)  NOT NULL,
                    size        BIGINT      NOT NULL DEFAULT 0,
                    mtime_ns    BIGINT      NOT NULL,
                    etag        VARCHAR(64) NOT NULL,
                    properties  JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    data        BYTEA,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        }

        dbm = mock(LakebaseFSDatabaseManager.class);
        when(dbm.openConnection(any(TenantEntity.class))).thenAnswer(inv -> openPg());

        svc = new LakebaseFSService(dbm);
        tenant = new TenantEntity();
        tenant.setId("test-tenant");
    }

    private Connection openPg() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @Test
    void append_with_matching_etag_succeeds() {
        // First write seeds the row and gives us its etag.
        LakebaseFSService.FileRow first = svc.append(tenant, "/memory/notes.md", "hello".getBytes(), null);
        assertNotNull(first.etag, "initial append must produce an etag");
        assertEquals(5, first.size);

        // Re-append with the freshly-returned etag → must succeed.
        LakebaseFSService.FileRow second = svc.append(
                tenant, "/memory/notes.md", " world".getBytes(), first.etag);

        assertEquals(11, second.size, "appended content extends the file");
        assertNotEquals(first.etag, second.etag, "etag must rotate on content change");
        assertArrayEquals("hello world".getBytes(), second.data,
                "appended bytes must follow the original bytes");
    }

    @Test
    void append_with_stale_etag_throws_precondition_failed() {
        LakebaseFSService.FileRow seeded = svc.append(tenant, "/memory/notes.md", "hello".getBytes(), null);
        assertNotNull(seeded.etag);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                svc.append(tenant, "/memory/notes.md", " world".getBytes(), "stale-etag"));

        assertTrue(ex.getMessage().contains("precondition_failed"),
                "exception message must contain 'precondition_failed', got: " + ex.getMessage());

        // The failed append must not have mutated the row.
        LakebaseFSService.FileRow after = svc.get(tenant, "/memory/notes.md");
        assertEquals(seeded.etag, after.etag, "stale-etag append must not mutate the row");
        assertEquals(5, after.size);
    }

    @Test
    void append_to_missing_path_with_ifmatch_throws_precondition_failed() {
        // ifMatch set but the row does not exist yet → must be a precondition
        // failure (matches the implementation contract).
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                svc.append(tenant, "/memory/notes.md", "hello".getBytes(), "any-etag"));
        assertTrue(ex.getMessage().contains("precondition_failed"),
                "exception message must contain 'precondition_failed', got: " + ex.getMessage());
    }

    @Test
    void append_with_null_ifmatch_is_legacy_path() {
        // No row exists yet — legacy path appends from empty base.
        LakebaseFSService.FileRow created = svc.append(tenant, "/memory/notes.md", "abc".getBytes(), null);
        assertEquals(3, created.size);
        assertArrayEquals("abc".getBytes(), created.data);

        // A second null-ifMatch call still works, no precondition check.
        LakebaseFSService.FileRow extended = svc.append(tenant, "/memory/notes.md", "def".getBytes(), null);
        assertEquals(6, extended.size);
        assertArrayEquals("abcdef".getBytes(), extended.data);
    }

    @Test
    void append_with_empty_ifmatch_is_legacy_path() {
        // Empty-string ifMatch must behave like null per the implementation contract.
        LakebaseFSService.FileRow created = svc.append(tenant, "/memory/notes.md", "abc".getBytes(), "");
        assertEquals(3, created.size);

        LakebaseFSService.FileRow extended = svc.append(tenant, "/memory/notes.md", "def".getBytes(), "");
        assertEquals(6, extended.size);
    }

    @Test
    void single_arg_overload_still_works() {
        // Existing callers (controller, batch — Task 2/3) must keep compiling
        // and behaving exactly as before.
        LakebaseFSService.FileRow row = svc.append(tenant, "/memory/notes.md", "hello".getBytes());
        assertEquals(5, row.size);
        assertArrayEquals("hello".getBytes(), row.data);
    }
}
