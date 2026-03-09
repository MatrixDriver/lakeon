package com.lakeon.service;

import com.lakeon.model.enums.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Startup migration: keep CHECK constraints in sync with Java enums.
 */
@Component
public class SchemaMigration {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final DataSource dataSource;

    public SchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOperationTypeConstraint() {
        String values = Arrays.stream(OperationType.values())
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(","));

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE operation_logs DROP CONSTRAINT IF EXISTS operation_logs_operation_type_check");
            st.execute("ALTER TABLE operation_logs ADD CONSTRAINT operation_logs_operation_type_check " +
                    "CHECK (operation_type::text = ANY(ARRAY[" + values + "]))");
            log.info("Synced operation_logs CHECK constraint with OperationType enum: {}", values);
        } catch (Exception e) {
            log.warn("Failed to sync operation_type CHECK constraint: {}", e.getMessage());
        }
    }
}
