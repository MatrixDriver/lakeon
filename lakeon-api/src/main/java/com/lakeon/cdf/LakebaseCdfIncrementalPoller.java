package com.lakeon.cdf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.iceberg.LakebaseBranchConnectionProvider;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.LakebaseCdfStreamRepository;
import com.lakeon.service.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LakebaseCdfIncrementalPoller {
    private static final Logger log = LoggerFactory.getLogger(LakebaseCdfIncrementalPoller.class);
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String SELECT_EVENTS_SQL = """
            SELECT event_id, op, row_json::text AS row_json, created_at
            FROM _lakeon_iceberg.cdf_change_events
            WHERE stream_id = ?
              AND branch_id = ?
            ORDER BY event_id
            LIMIT ?
            """;
    private static final String DELETE_EVENTS_SQL = """
            DELETE FROM _lakeon_iceberg.cdf_change_events
            WHERE stream_id = ?
              AND branch_id = ?
              AND event_id <= ?
            """;

    private final LakebaseCdfStreamRepository repository;
    private final LakebaseBranchConnectionProvider connectionProvider;
    private final LakebaseCdfWorker worker;
    private final ObjectMapper objectMapper;

    public LakebaseCdfIncrementalPoller(LakebaseCdfStreamRepository repository,
                                        LakebaseBranchConnectionProvider connectionProvider,
                                        LakebaseCdfWorker worker,
                                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.connectionProvider = connectionProvider;
        this.worker = worker;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            initialDelayString = "${lakeon.cdf.incremental.initial-delay-ms:5000}",
            fixedDelayString = "${lakeon.cdf.incremental.poll-interval-ms:5000}")
    public void pollRunningStreams() {
        for (LakebaseCdfStreamEntity stream : repository.findByStatusAndBackfillStatus("RUNNING", "SUCCEEDED")) {
            try {
                drainStream(stream);
            } catch (RuntimeException e) {
                log.warn("CDF incremental poll failed for stream {}: {}", stream.getId(), e.getMessage());
                markFailed(stream, e);
            }
        }
    }

    void drainStream(LakebaseCdfStreamEntity stream) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(stream.getTenantId());
        try (Connection connection = connectionProvider.open(tenant, stream.getDatabaseId(), stream.getBranchId())) {
            List<EventRow> events = readEvents(connection, stream);
            if (events.isEmpty()) {
                return;
            }
            String commitLsn = currentWalLsn(connection);
            List<Map<String, Object>> rows = new ArrayList<>(events.size());
            for (EventRow event : events) {
                rows.add(event.toCdfRow());
            }
            LakebaseCdfWorker.CommitResult result = worker.commitBatch(connection, stream, new CdfBatch(
                    stream.getId(),
                    stream.getBranchId(),
                    commitLsn,
                    commitLsn,
                    "append",
                    rows,
                    0L));
            deleteEvents(connection, stream, events.get(events.size() - 1).eventId());
            markSucceeded(stream, result, events);
        } catch (SQLException e) {
            throw new BadRequestException("failed to drain CDF incremental events: " + e.getMessage());
        }
    }

    private List<EventRow> readEvents(Connection connection, LakebaseCdfStreamEntity stream) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_EVENTS_SQL)) {
            statement.setString(1, stream.getId());
            statement.setString(2, stream.getBranchId());
            statement.setInt(3, DEFAULT_BATCH_SIZE);
            try (ResultSet rs = statement.executeQuery()) {
                List<EventRow> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new EventRow(
                            rs.getLong("event_id"),
                            rs.getString("op"),
                            parseRow(rs.getString("row_json")),
                            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()));
                }
                return events;
            }
        }
    }

    private Map<String, Object> parseRow(String rowJson) {
        try {
            return objectMapper.readValue(rowJson, MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("failed to parse CDF event row JSON: " + e.getMessage());
        }
    }

    private String currentWalLsn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_current_wal_lsn()")) {
            if (!rs.next()) {
                throw new SQLException("pg_current_wal_lsn returned no rows");
            }
            return rs.getString(1);
        }
    }

    private void deleteEvents(Connection connection, LakebaseCdfStreamEntity stream, long maxEventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_EVENTS_SQL)) {
            statement.setString(1, stream.getId());
            statement.setString(2, stream.getBranchId());
            statement.setLong(3, maxEventId);
            statement.executeUpdate();
        }
    }

    private void markSucceeded(
            LakebaseCdfStreamEntity stream,
            LakebaseCdfWorker.CommitResult result,
            List<EventRow> events) {
        stream.setStatus("RUNNING");
        stream.setLastCommitLsn(result.endLsn());
        stream.setLastSnapshotId(result.snapshotId());
        stream.setObservedLagMs(observedLagMs(events));
        stream.setLastError(null);
        repository.save(stream);
    }

    private void markFailed(LakebaseCdfStreamEntity stream, RuntimeException error) {
        stream.setStatus("FAILED");
        stream.setLastError(truncateError(error.getMessage()));
        repository.save(stream);
    }

    private Long observedLagMs(List<EventRow> events) {
        if (events.isEmpty()) {
            return null;
        }
        Instant newest = events.get(events.size() - 1).createdAt();
        return Math.max(0L, Duration.between(newest, Instant.now()).toMillis());
    }

    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "CDF incremental poll failed";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private record EventRow(long eventId, String op, Map<String, Object> row, Instant createdAt) {
        Map<String, Object> toCdfRow() {
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("_lakeon_cdf_op", op == null ? "UNKNOWN" : op.toLowerCase());
            out.put("_lakeon_cdf_event_id", eventId);
            return out;
        }
    }
}
