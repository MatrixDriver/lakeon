package com.lakeon.lakebasefs;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the stale-host detection heuristic that gates the openAdmin retry.
 * We can't unit-test the full retry flow because openAdmin routes through
 * {@link java.sql.DriverManager} (static), but this covers the branching
 * logic that decides whether to reconcile.
 */
class LakebaseFSDatabaseManagerRetryTest {

    @Test
    void detects_database_missing() {
        assertTrue(LakebaseFSDatabaseManager.isStaleHostError(
                new SQLException("FATAL: database \"foo\" does not exist")));
    }

    @Test
    void detects_connection_refused() {
        assertTrue(LakebaseFSDatabaseManager.isStaleHostError(
                new SQLException("Connection refused: connect")));
    }

    @Test
    void detects_unresolved_host() {
        assertTrue(LakebaseFSDatabaseManager.isStaleHostError(
                new SQLException("The connection attempt failed: could not translate host name")));
    }

    @Test
    void detects_no_route_to_host() {
        assertTrue(LakebaseFSDatabaseManager.isStaleHostError(
                new SQLException("No route to host (Host unreachable)")));
    }

    @Test
    void detects_compute_host_not_yet_available() {
        assertTrue(LakebaseFSDatabaseManager.isStaleHostError(
                new SQLException("compute host not available for db lbfs-12345678")));
    }

    @Test
    void rejects_generic_sql_error() {
        assertFalse(LakebaseFSDatabaseManager.isStaleHostError(
                new SQLException("constraint violation")));
    }

    @Test
    void null_message_safe() {
        assertFalse(LakebaseFSDatabaseManager.isStaleHostError(new SQLException()));
    }

    @Test
    void generated_lbfs_database_slug_is_compute_project_name_safe() {
        String slug = LakebaseFSDatabaseManager.newDatabaseSlug();

        assertTrue(slug.matches("lbfs-[a-f0-9]{8}"), slug);
    }

    @Test
    void lbfs_admin_connections_use_internal_admin_credentials() {
        assertTrue(LakebaseFSDatabaseManager.adminJdbcUser().equals("cloud_admin"));
        assertTrue(LakebaseFSDatabaseManager.adminJdbcPassword().equals("cloud-admin-internal"));
    }
}
