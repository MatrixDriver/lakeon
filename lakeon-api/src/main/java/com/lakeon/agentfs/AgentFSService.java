package com.lakeon.agentfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * AgentFS service — runs SQL against the tenant's per-tenant Lakebase
 * (provisioned & routed by AgentFSDatabaseManager).
 *
 * Schema in per-tenant DB:
 *   files(path PK, kind, size, mtime_ns, etag, properties JSONB, data BYTEA, ...)
 */
@Service
public class AgentFSService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentFSDatabaseManager dbm;

    public AgentFSService(AgentFSDatabaseManager dbm) {
        this.dbm = dbm;
    }

    public static class FileRow {
        public String path;
        public String kind;       // "file" | "dir"
        public long size;
        public long mtimeNs;
        public String etag;
        public JsonNode properties;
        public byte[] data;
    }

    // ── reads ──

    public FileRow get(TenantEntity tenant, String path) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            return loadRow(c, norm).orElseThrow(() -> new NotFoundException("path not found: " + path));
        } catch (SQLException e) {
            throw bad("get failed: " + e.getMessage());
        }
    }

    public Optional<FileRow> getOpt(TenantEntity tenant, String path) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            return loadRow(c, norm);
        } catch (SQLException e) {
            throw bad("get failed: " + e.getMessage());
        }
    }

    public List<FileRow> list(TenantEntity tenant, String prefix, boolean recursive) {
        String p = (prefix == null || prefix.isEmpty()) ? "/" : normalize(prefix);
        String pfx = p.endsWith("/") ? p : p + "/";
        try (Connection c = dbm.openConnection(tenant);
             PreparedStatement st = c.prepareStatement(
                 "SELECT path, kind, size, mtime_ns, etag, properties FROM files " +
                 "WHERE path LIKE ? AND path <> ? ORDER BY path")) {
            st.setString(1, pfx + "%");
            st.setString(2, p);
            try (ResultSet rs = st.executeQuery()) {
                List<FileRow> out = new ArrayList<>();
                while (rs.next()) {
                    FileRow r = new FileRow();
                    r.path = rs.getString(1);
                    r.kind = rs.getString(2);
                    r.size = rs.getLong(3);
                    r.mtimeNs = rs.getLong(4);
                    r.etag = rs.getString(5);
                    r.properties = parseJson(rs.getString(6));
                    if (recursive || !r.path.substring(pfx.length()).contains("/")) {
                        out.add(r);
                    }
                }
                return out;
            }
        } catch (SQLException e) {
            throw bad("list failed: " + e.getMessage());
        }
    }

    public Map<String, Object> stats(TenantEntity tenant) {
        try (Connection c = dbm.openConnection(tenant);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*), COALESCE(SUM(size),0), COALESCE(MAX(mtime_ns),0) FROM files")) {
            if (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file_count",    rs.getLong(1));
                m.put("total_bytes",   rs.getLong(2));
                m.put("last_write_ns", rs.getLong(3));
                return m;
            }
            return Map.of("file_count", 0, "total_bytes", 0, "last_write_ns", 0);
        } catch (SQLException e) {
            throw bad("stats failed: " + e.getMessage());
        }
    }

    // ── writes ──

    public FileRow put(TenantEntity tenant, String path, byte[] data,
                       JsonNode properties, String ifMatch, String ifNoneMatch) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            c.setAutoCommit(false);
            try {
                Optional<FileRow> existing = loadRow(c, norm);
                if ("*".equals(ifNoneMatch) && existing.isPresent()) {
                    throw new BadRequestException("precondition_failed: file already exists");
                }
                if (ifMatch != null && !ifMatch.isEmpty()) {
                    if (existing.isEmpty() || !ifMatch.equals(existing.get().etag)) {
                        throw new BadRequestException("precondition_failed: if-match mismatch");
                    }
                }
                ensureParents(c, norm);
                long now = nowNs();
                String etag = sha256(data);
                String props;
                if (properties != null && !properties.isNull()) {
                    props = properties.toString();
                } else if (existing.isPresent() && existing.get().properties != null) {
                    props = existing.get().properties.toString();
                } else {
                    props = "{}";
                }
                upsertFile(c, norm, "file", data == null ? 0 : data.length, now, etag, props,
                           data == null ? new byte[0] : data);
                c.commit();
                return loadRow(c, norm).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw bad("put failed: " + e.getMessage());
        }
    }

    public FileRow append(TenantEntity tenant, String path, byte[] data) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            c.setAutoCommit(false);
            try {
                Optional<FileRow> existing = loadRow(c, norm);
                byte[] base = existing.map(r -> r.data == null ? new byte[0] : r.data).orElse(new byte[0]);
                byte[] inc  = data == null ? new byte[0] : data;
                byte[] combined = new byte[base.length + inc.length];
                System.arraycopy(base, 0, combined, 0, base.length);
                System.arraycopy(inc, 0, combined, base.length, inc.length);
                ensureParents(c, norm);
                long now = nowNs();
                String etag = sha256(combined);
                String props = existing.map(r -> r.properties != null ? r.properties.toString() : "{}").orElse("{}");
                upsertFile(c, norm, "file", combined.length, now, etag, props, combined);
                c.commit();
                return loadRow(c, norm).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw bad("append failed: " + e.getMessage());
        }
    }

    public void delete(TenantEntity tenant, String path, String ifMatch) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            Optional<FileRow> existing = loadRow(c, norm);
            if (existing.isEmpty()) throw new NotFoundException("path not found: " + path);
            if (ifMatch != null && !ifMatch.isEmpty() && !ifMatch.equals(existing.get().etag)) {
                throw new BadRequestException("precondition_failed");
            }
            try (PreparedStatement st = c.prepareStatement("DELETE FROM files WHERE path = ?")) {
                st.setString(1, norm);
                st.executeUpdate();
            }
        } catch (SQLException e) {
            throw bad("delete failed: " + e.getMessage());
        }
    }

    public FileRow mkdir(TenantEntity tenant, String path, JsonNode properties) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            Optional<FileRow> existing = loadRow(c, norm);
            if (existing.isPresent() && "dir".equals(existing.get().kind)) {
                return existing.get();
            }
            ensureParents(c, norm);
            String etag = "dir-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String props = (properties == null || properties.isNull()) ? "{}" : properties.toString();
            upsertFile(c, norm, "dir", 0, nowNs(), etag, props, null);
            return loadRow(c, norm).orElseThrow();
        } catch (SQLException e) {
            throw bad("mkdir failed: " + e.getMessage());
        }
    }

    public void rename(TenantEntity tenant, String from, String to, boolean overwrite) {
        String f = normalize(from);
        String t = normalize(to);
        try (Connection c = dbm.openConnection(tenant)) {
            c.setAutoCommit(false);
            try {
                Optional<FileRow> src = loadRow(c, f);
                if (src.isEmpty()) throw new NotFoundException("path not found: " + from);
                Optional<FileRow> dst = loadRow(c, t);
                if (dst.isPresent() && !overwrite) throw new BadRequestException("target_exists");
                if (dst.isPresent()) {
                    try (PreparedStatement st = c.prepareStatement("DELETE FROM files WHERE path = ?")) {
                        st.setString(1, t);
                        st.executeUpdate();
                    }
                }
                ensureParents(c, t);
                try (PreparedStatement st = c.prepareStatement(
                    "UPDATE files SET path = ?, mtime_ns = ? WHERE path = ?")) {
                    st.setString(1, t);
                    st.setLong(2, nowNs());
                    st.setString(3, f);
                    st.executeUpdate();
                }
                if ("dir".equals(src.get().kind)) {
                    String oldPfx = f.endsWith("/") ? f : f + "/";
                    String newPfx = t.endsWith("/") ? t : t + "/";
                    try (PreparedStatement st = c.prepareStatement(
                        "UPDATE files SET path = ? || substring(path FROM ? + 1) WHERE path LIKE ?")) {
                        st.setString(1, newPfx);
                        st.setInt(2, oldPfx.length());
                        st.setString(3, oldPfx + "%");
                        st.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw bad("rename failed: " + e.getMessage());
        }
    }

    public FileRow setProperties(TenantEntity tenant, String path, JsonNode merge) {
        String norm = normalize(path);
        try (Connection c = dbm.openConnection(tenant)) {
            FileRow existing = loadRow(c, norm).orElseThrow(() ->
                new NotFoundException("path not found: " + path));
            ObjectNode merged = existing.properties instanceof ObjectNode
                ? ((ObjectNode) existing.properties).deepCopy()
                : JsonNodeFactory.instance.objectNode();
            if (merge != null && merge.isObject()) {
                merge.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
            }
            try (PreparedStatement st = c.prepareStatement(
                "UPDATE files SET properties = ?::jsonb, updated_at = now() WHERE path = ?")) {
                st.setString(1, merged.toString());
                st.setString(2, norm);
                st.executeUpdate();
            }
            return loadRow(c, norm).orElseThrow();
        } catch (SQLException e) {
            throw bad("setProperties failed: " + e.getMessage());
        }
    }

    // ── helpers ──

    private Optional<FileRow> loadRow(Connection c, String path) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
            "SELECT path, kind, size, mtime_ns, etag, properties, data FROM files WHERE path = ?")) {
            st.setString(1, path);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                FileRow r = new FileRow();
                r.path = rs.getString(1);
                r.kind = rs.getString(2);
                r.size = rs.getLong(3);
                r.mtimeNs = rs.getLong(4);
                r.etag = rs.getString(5);
                r.properties = parseJson(rs.getString(6));
                r.data = rs.getBytes(7);
                return Optional.of(r);
            }
        }
    }

    private void upsertFile(Connection c, String path, String kind, long size,
                             long mtimeNs, String etag, String propsJson, byte[] data) throws SQLException {
        // Idempotency: skip the UPDATE when nothing meaningful changed.
        // Without this, every PUT re-stamps mtime_ns/updated_at even for
        // identical content, which would trigger spurious CDC events for
        // downstream consumers (Phase 2 memory derivation worker).
        try (PreparedStatement st = c.prepareStatement(
            "INSERT INTO files(path, kind, size, mtime_ns, etag, properties, data, updated_at) " +
            "VALUES(?, ?, ?, ?, ?, ?::jsonb, ?, now()) " +
            "ON CONFLICT(path) DO UPDATE SET " +
            "  kind = EXCLUDED.kind, size = EXCLUDED.size, mtime_ns = EXCLUDED.mtime_ns, " +
            "  etag = EXCLUDED.etag, properties = EXCLUDED.properties, " +
            "  data = EXCLUDED.data, updated_at = now() " +
            "WHERE files.etag IS DISTINCT FROM EXCLUDED.etag " +
            "   OR files.kind IS DISTINCT FROM EXCLUDED.kind " +
            "   OR files.properties IS DISTINCT FROM EXCLUDED.properties")) {
            st.setString(1, path);
            st.setString(2, kind);
            st.setLong(3, size);
            st.setLong(4, mtimeNs);
            st.setString(5, etag);
            st.setString(6, propsJson);
            if (data == null) st.setNull(7, Types.BINARY);
            else st.setBytes(7, data);
            st.executeUpdate();
        }
    }

    private void ensureParents(Connection c, String fullPath) throws SQLException {
        int idx = fullPath.lastIndexOf('/');
        if (idx <= 0) return;
        String parent = fullPath.substring(0, idx);
        if (loadRow(c, parent).isPresent()) return;
        String etag = "dir-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        upsertFile(c, parent, "dir", 0, nowNs(), etag, "{}", null);
        ensureParents(c, parent);
    }

    static String normalize(String path) {
        if (path == null || path.isEmpty()) throw new BadRequestException("path required");
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("/..") || p.contains("../")) {
            throw new BadRequestException("path traversal not allowed: " + path);
        }
        return p;
    }

    static long nowNs() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data == null ? new byte[0] : data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private JsonNode parseJson(String s) {
        if (s == null) return JsonNodeFactory.instance.objectNode();
        try { return JSON.readTree(s); } catch (Exception e) { return JsonNodeFactory.instance.objectNode(); }
    }

    private RuntimeException bad(String msg) { return new BadRequestException(msg); }
}
