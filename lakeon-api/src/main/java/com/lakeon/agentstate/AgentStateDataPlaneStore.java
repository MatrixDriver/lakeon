package com.lakeon.agentstate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentStateDataPlaneStore {
    private static final int STATEMENT_TIMEOUT_SECONDS = 60;

    private final DatabaseRepository databaseRepository;
    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;

    public AgentStateDataPlaneStore(DatabaseRepository databaseRepository,
                                    DatabaseService databaseService,
                                    ObjectMapper objectMapper) {
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
        this.objectMapper = objectMapper;
    }

    public DataPlaneWorkspace createWorkspace(DatabaseEntity database, String taskRunId) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            String workspaceId = id("ws");
            String rootBranchId = id("awb");
            Instant now = Instant.now();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO agent_state.workspaces (id, task_run_id, database_id, root_branch_id, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, workspaceId);
                ps.setString(2, taskRunId);
                ps.setString(3, database.getId());
                ps.setString(4, rootBranchId);
                ps.setObject(5, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO agent_state.branches
                        (id, workspace_id, parent_branch_id, stage_run_id, name, hypothesis, status, created_at)
                    VALUES (?, ?, NULL, NULL, 'root', NULL, 'active', ?)
                    """)) {
                ps.setString(1, rootBranchId);
                ps.setString(2, workspaceId);
                ps.setObject(3, now);
                ps.executeUpdate();
            }
            return new DataPlaneWorkspace(workspaceId, rootBranchId, database.getId(), now);
        } catch (SQLException e) {
            throw new ServiceException("Failed to create agent state workspace in data plane: " + e.getMessage(), e);
        }
    }

    public AgentStateDtos.BranchDetailResponse forkBranch(
            DatabaseEntity database,
            String workspaceId,
            String parentBranchId,
            String stageRunId,
            String hypothesis) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            String resolvedParentId = parentBranchId == null || parentBranchId.isBlank()
                    ? findRootBranchId(conn, workspaceId)
                    : parentBranchId;
            String branchId = id("awb");
            Instant now = Instant.now();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO agent_state.branches
                        (id, workspace_id, parent_branch_id, stage_run_id, name, hypothesis, status, created_at)
                    VALUES (?, ?, ?, ?, 'branch', ?, 'active', ?)
                    """)) {
                ps.setString(1, branchId);
                ps.setString(2, workspaceId);
                ps.setString(3, resolvedParentId);
                ps.setString(4, stageRunId);
                ps.setString(5, hypothesis);
                ps.setObject(6, now);
                ps.executeUpdate();
            }
            return new AgentStateDtos.BranchDetailResponse(
                    branchId, workspaceId, resolvedParentId, stageRunId, "branch", hypothesis, "active", now);
        } catch (SQLException e) {
            throw new ServiceException("Failed to fork agent state branch in data plane: " + e.getMessage(), e);
        }
    }

    public AgentStateDtos.StageRunDetailResponse createStageRun(
            DatabaseEntity database,
            String taskRunId,
            String stageId,
            String branchId,
            String contextPackId) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            String id = id("stage");
            Instant now = Instant.now();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO agent_state.stage_runs
                        (id, task_run_id, stage_id, status, branch_id, context_pack_id, created_at)
                    VALUES (?, ?, ?, 'running', ?, ?, ?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, taskRunId);
                ps.setString(3, stageId);
                ps.setString(4, branchId);
                ps.setString(5, contextPackId);
                ps.setObject(6, now);
                ps.executeUpdate();
            }
            return new AgentStateDtos.StageRunDetailResponse(id, taskRunId, stageId, "running", branchId, contextPackId, now);
        } catch (SQLException e) {
            throw new ServiceException("Failed to create agent stage run in data plane: " + e.getMessage(), e);
        }
    }

    public AgentStateDtos.IdResponse appendStateCommit(
            DatabaseEntity database,
            String taskRunId,
            String stageRunId,
            String branchId,
            String summary) {
        return appendVersion(database, taskRunId, branchId, stageRunId, "state_commit", "committed", summary,
                Map.of("summary", summary == null ? "" : summary));
    }

    public AgentStateDtos.IdResponse recordArtifact(
            DatabaseEntity database,
            String taskRunId,
            String stageRunId,
            String branchId,
            String kind) {
        return appendVersion(database, taskRunId, branchId, stageRunId, "artifact", "recorded", kind,
                Map.of("kind", kind));
    }

    public AgentStateDtos.IdResponse createEvidencePacket(
            DatabaseEntity database,
            String taskRunId,
            String branchId,
            String claim,
            List<String> evidenceRefs) {
        return appendVersion(database, taskRunId, branchId, null, "evidence_packet", "pending", claim,
                Map.of("claim", claim, "evidence_refs", evidenceRefs == null ? List.of() : evidenceRefs));
    }

    public AgentStateDtos.IdResponse appendAuditEvent(
            DatabaseEntity database,
            String taskRunId,
            String branchId,
            String action,
            String result,
            String reason) {
        return appendVersion(database, taskRunId, branchId, null, "audit_event", result, action,
                Map.of("action", action, "result", result, "reason", reason == null ? "" : reason));
    }

    public AgentStateDtos.IdResponse appendManifestVersion(
            DatabaseEntity database,
            String taskRunId,
            String branchId,
            String stageRunId,
            String kind,
            String summary,
            Map<String, Object> manifest) {
        return appendVersion(database, taskRunId, branchId, stageRunId, kind, "recorded", summary, manifest);
    }

    public AgentStateDtos.DataPlaneDetail loadDetail(DatabaseEntity database, String taskRunId, AgentWorkspaceEntity workspace) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            List<AgentStateDtos.StageRunDetailResponse> stages = listStages(conn, taskRunId);
            List<AgentStateDtos.BranchDetailResponse> branches = listBranches(conn, workspace.getId());
            List<VersionRow> versions = listVersions(conn, taskRunId);
            return new AgentStateDtos.DataPlaneDetail(
                    stages,
                    branches,
                    versions.stream().filter(row -> "state_commit".equals(row.kind())).map(this::toCommit).toList(),
                    versions.stream().filter(row -> "artifact".equals(row.kind())).map(this::toArtifact).toList(),
                    versions.stream().filter(row -> "evidence_packet".equals(row.kind())).map(this::toEvidence).toList(),
                    versions.stream().filter(row -> "audit_event".equals(row.kind())).map(this::toAudit).toList());
        } catch (SQLException e) {
            throw new ServiceException("Failed to load agent state from data plane: " + e.getMessage(), e);
        }
    }

    public java.util.Optional<AgentStateDtos.EvidencePacketDetailResponse> findEvidencePacket(DatabaseEntity database, String evidencePacketId) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, task_run_id, branch_id, stage_run_id, kind, version_no, status, summary, payload::text, created_at
                    FROM agent_state.versions
                    WHERE id = ? AND kind = 'evidence_packet'
                    """)) {
                ps.setString(1, evidencePacketId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return java.util.Optional.empty();
                    VersionRow row = new VersionRow(
                            rs.getString("id"),
                            rs.getString("task_run_id"),
                            rs.getString("branch_id"),
                            rs.getString("stage_run_id"),
                            rs.getString("kind"),
                            rs.getLong("version_no"),
                            rs.getString("status"),
                            rs.getString("summary"),
                            fromJsonObject(rs.getString("payload")),
                            rs.getObject("created_at", Instant.class));
                    return java.util.Optional.of(toEvidence(row));
                }
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to load evidence packet from data plane: " + e.getMessage(), e);
        }
    }

    public java.util.Optional<AgentStateDtos.RestorePlanResponse> restorePlan(DatabaseEntity database, String versionId) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, payload::text
                    FROM agent_state.versions
                    WHERE id = ?
                    """)) {
                ps.setString(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return java.util.Optional.empty();
                    Map<String, Object> payload = fromJsonObject(rs.getString("payload"));
                    List<String> restorableRefs = stringList(payload.get("artifacts"));
                    List<String> missingRefs = stringList(payload.get("missing"));
                    return java.util.Optional.of(new AgentStateDtos.RestorePlanResponse(
                            rs.getString("id"), restorableRefs, missingRefs, missingRefs.isEmpty()));
                }
            }
        } catch (SQLException e) {
            throw new ServiceException("Failed to restore agent state version from data plane: " + e.getMessage(), e);
        }
    }

    private AgentStateDtos.IdResponse appendVersion(
            DatabaseEntity database,
            String taskRunId,
            String branchId,
            String stageRunId,
            String kind,
            String status,
            String summary,
            Map<String, Object> payload) {
        try (Connection conn = getConnection(database)) {
            ensureSchema(conn);
            String id = id(prefixForKind(kind));
            long versionNo = nextVersionNo(conn, taskRunId, branchId);
            Instant now = Instant.now();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO agent_state.versions
                        (id, task_run_id, branch_id, stage_run_id, kind, version_no, status, summary, payload, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, taskRunId);
                ps.setString(3, branchId);
                ps.setString(4, stageRunId);
                ps.setString(5, kind);
                ps.setLong(6, versionNo);
                ps.setString(7, status);
                ps.setString(8, summary);
                ps.setString(9, toJson(payload == null ? Map.of() : payload));
                ps.setObject(10, now);
                ps.executeUpdate();
            }
            return new AgentStateDtos.IdResponse(id);
        } catch (SQLException e) {
            throw new ServiceException("Failed to append agent state version in data plane: " + e.getMessage(), e);
        }
    }

    private void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE SCHEMA IF NOT EXISTS agent_state
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_state.workspaces (
                        id TEXT PRIMARY KEY,
                        task_run_id TEXT NOT NULL,
                        database_id TEXT NOT NULL,
                        root_branch_id TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_state.branches (
                        id TEXT PRIMARY KEY,
                        workspace_id TEXT NOT NULL REFERENCES agent_state.workspaces(id) ON DELETE CASCADE,
                        parent_branch_id TEXT,
                        stage_run_id TEXT,
                        name TEXT NOT NULL,
                        hypothesis TEXT,
                        status TEXT NOT NULL DEFAULT 'active',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_state.stage_runs (
                        id TEXT PRIMARY KEY,
                        task_run_id TEXT NOT NULL,
                        stage_id TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'running',
                        branch_id TEXT,
                        context_pack_id TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_state.versions (
                        id TEXT PRIMARY KEY,
                        task_run_id TEXT NOT NULL,
                        branch_id TEXT,
                        stage_run_id TEXT,
                        kind TEXT NOT NULL,
                        version_no BIGINT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'recorded',
                        summary TEXT,
                        payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_agent_state_versions_task_created
                    ON agent_state.versions (task_run_id, created_at)
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_agent_state_versions_branch_version
                    ON agent_state.versions (branch_id, version_no)
                    """);
        }
    }

    private Connection getConnection(DatabaseEntity db) throws SQLException {
        databaseService.wakeCompute(db);
        DatabaseEntity refreshed = databaseRepository.findById(db.getId()).orElse(db);
        String host = refreshed.getComputeHost() == null ? db.getComputeHost() : refreshed.getComputeHost();
        int port = refreshed.getComputePort() != null ? refreshed.getComputePort() : (db.getComputePort() == null ? 55433 : db.getComputePort());
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + refreshed.getName() + "?sslmode=disable";
        Connection conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
        try (Statement st = conn.createStatement()) {
            st.execute("SET statement_timeout = '" + STATEMENT_TIMEOUT_SECONDS + "s'");
        }
        return conn;
    }

    private String findRootBranchId(Connection conn, String workspaceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT root_branch_id FROM agent_state.workspaces WHERE id = ?
                """)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private long nextVersionNo(Connection conn, String taskRunId, String branchId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COALESCE(MAX(version_no), 0) + 1
                FROM agent_state.versions
                WHERE task_run_id = ? AND COALESCE(branch_id, '') = COALESCE(?, '')
                """)) {
            ps.setString(1, taskRunId);
            ps.setString(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 1;
            }
        }
    }

    private List<AgentStateDtos.StageRunDetailResponse> listStages(Connection conn, String taskRunId) throws SQLException {
        List<AgentStateDtos.StageRunDetailResponse> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, task_run_id, stage_id, status, branch_id, context_pack_id, created_at
                FROM agent_state.stage_runs
                WHERE task_run_id = ?
                ORDER BY created_at ASC
                """)) {
            ps.setString(1, taskRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AgentStateDtos.StageRunDetailResponse(
                            rs.getString("id"),
                            rs.getString("task_run_id"),
                            rs.getString("stage_id"),
                            rs.getString("status"),
                            rs.getString("branch_id"),
                            rs.getString("context_pack_id"),
                            rs.getObject("created_at", Instant.class)));
                }
            }
        }
        return rows;
    }

    private List<AgentStateDtos.BranchDetailResponse> listBranches(Connection conn, String workspaceId) throws SQLException {
        List<AgentStateDtos.BranchDetailResponse> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, workspace_id, parent_branch_id, stage_run_id, name, hypothesis, status, created_at
                FROM agent_state.branches
                WHERE workspace_id = ?
                ORDER BY created_at ASC
                """)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AgentStateDtos.BranchDetailResponse(
                            rs.getString("id"),
                            rs.getString("workspace_id"),
                            rs.getString("parent_branch_id"),
                            rs.getString("stage_run_id"),
                            rs.getString("name"),
                            rs.getString("hypothesis"),
                            rs.getString("status"),
                            rs.getObject("created_at", Instant.class)));
                }
            }
        }
        return rows;
    }

    private List<VersionRow> listVersions(Connection conn, String taskRunId) throws SQLException {
        List<VersionRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, task_run_id, branch_id, stage_run_id, kind, version_no, status, summary, payload::text, created_at
                FROM agent_state.versions
                WHERE task_run_id = ?
                ORDER BY created_at ASC, version_no ASC
                """)) {
            ps.setString(1, taskRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new VersionRow(
                            rs.getString("id"),
                            rs.getString("task_run_id"),
                            rs.getString("branch_id"),
                            rs.getString("stage_run_id"),
                            rs.getString("kind"),
                            rs.getLong("version_no"),
                            rs.getString("status"),
                            rs.getString("summary"),
                            fromJsonObject(rs.getString("payload")),
                            rs.getObject("created_at", Instant.class)));
                }
            }
        }
        return rows;
    }

    private AgentStateDtos.StateCommitDetailResponse toCommit(VersionRow row) {
        return new AgentStateDtos.StateCommitDetailResponse(
                row.id(), row.taskRunId(), row.stageRunId(), row.branchId(), row.summary(), row.createdAt());
    }

    private AgentStateDtos.ArtifactDetailResponse toArtifact(VersionRow row) {
        return new AgentStateDtos.ArtifactDetailResponse(
                row.id(), row.taskRunId(), row.stageRunId(), row.branchId(),
                String.valueOf(row.payload().getOrDefault("kind", "artifact")), row.createdAt());
    }

    private AgentStateDtos.EvidencePacketDetailResponse toEvidence(VersionRow row) {
        return new AgentStateDtos.EvidencePacketDetailResponse(
                row.id(),
                row.taskRunId(),
                row.branchId(),
                String.valueOf(row.payload().getOrDefault("claim", row.summary())),
                row.status(),
                stringList(row.payload().get("evidence_refs")),
                row.createdAt());
    }

    private AgentStateDtos.AuditEventDetailResponse toAudit(VersionRow row) {
        return new AgentStateDtos.AuditEventDetailResponse(
                row.id(),
                row.taskRunId(),
                row.branchId(),
                String.valueOf(row.payload().getOrDefault("action", row.summary())),
                String.valueOf(row.payload().getOrDefault("result", row.status())),
                blankToNull(String.valueOf(row.payload().getOrDefault("reason", ""))),
                row.createdAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid agent state payload", e);
        }
    }

    private Map<String, Object> fromJsonObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid agent state payload", e);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String prefixForKind(String kind) {
        return switch (kind) {
            case "state_commit" -> "commit";
            case "artifact" -> "artifact";
            case "evidence_packet" -> "evidence";
            case "audit_event" -> "audit";
            case "runtime_event" -> "runtime";
            default -> "ver";
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record DataPlaneWorkspace(String id, String rootBranchId, String databaseId, Instant createdAt) {}

    private record VersionRow(
            String id,
            String taskRunId,
            String branchId,
            String stageRunId,
            String kind,
            long versionNo,
            String status,
            String summary,
            Map<String, Object> payload,
            Instant createdAt) {}
}
