package com.lakeon.agentfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class AgentFSService {

    private final AgentFileRepository repo;

    public AgentFSService(AgentFileRepository repo) {
        this.repo = repo;
    }

    // ────────────────────────── read ops ──────────────────────────

    public AgentFileEntity get(String tenantId, String path) {
        return repo.findByTenantIdAndPath(tenantId, normalize(path))
                .orElseThrow(() -> new NotFoundException("path not found: " + path));
    }

    public Optional<AgentFileEntity> getOpt(String tenantId, String path) {
        return repo.findByTenantIdAndPath(tenantId, normalize(path));
    }

    public List<AgentFileEntity> list(String tenantId, String prefix, boolean recursive) {
        String p = prefix == null ? "" : prefix;
        String normPrefix = p.isEmpty() ? "/" : normalize(p);
        if (!normPrefix.endsWith("/")) {
            normPrefix = normPrefix + "/";
        }
        List<AgentFileEntity> all = repo.findByPrefix(tenantId, normPrefix);
        if (recursive) return all;
        // Keep only direct children (no further "/" after prefix)
        List<AgentFileEntity> out = new ArrayList<>();
        for (AgentFileEntity f : all) {
            String rest = f.getPath().substring(normPrefix.length());
            if (!rest.contains("/")) out.add(f);
        }
        return out;
    }

    public Map<String, Object> stats(String tenantId) {
        Long maxMtime = repo.maxMtimeByTenantId(tenantId);
        return Map.of(
                "file_count", repo.countByTenantId(tenantId),
                "total_bytes", repo.sumSizeByTenantId(tenantId),
                "last_write_ns", maxMtime == null ? 0L : maxMtime
        );
    }

    // ────────────────────────── write ops ──────────────────────────

    @Transactional
    public AgentFileEntity put(String tenantId, String path, byte[] data,
                               JsonNode properties, String ifMatch, String ifNoneMatch) {
        String normPath = normalize(path);
        Optional<AgentFileEntity> existingOpt = repo.findByTenantIdAndPath(tenantId, normPath);

        if ("*".equals(ifNoneMatch) && existingOpt.isPresent()) {
            throw new BadRequestException("precondition_failed: file already exists");
        }
        if (ifMatch != null && !ifMatch.isEmpty()) {
            if (existingOpt.isEmpty() || !ifMatch.equals(existingOpt.get().getEtag())) {
                throw new BadRequestException("precondition_failed: if-match mismatch");
            }
        }

        AgentFileEntity e = existingOpt.orElseGet(AgentFileEntity::new);
        if (existingOpt.isEmpty()) {
            e.setTenantId(tenantId);
            e.setPath(normPath);
            e.setCreatedAt(Instant.now());
        }
        e.setKind("file");
        e.setSize(data == null ? 0 : data.length);
        e.setMtimeNs(nowNs());
        e.setEtag(sha256(data));
        e.setData(data);
        if (properties != null && !properties.isNull()) {
            e.setProperties(properties);
        } else if (existingOpt.isEmpty()) {
            e.setProperties(JsonNodeFactory.instance.objectNode());
        }
        e.setUpdatedAt(Instant.now());
        ensureParentExists(tenantId, normPath);
        return repo.save(e);
    }

    @Transactional
    public AgentFileEntity append(String tenantId, String path, byte[] data) {
        String normPath = normalize(path);
        AgentFileEntity e = repo.findByTenantIdAndPath(tenantId, normPath).orElse(null);
        byte[] combined;
        if (e == null) {
            e = new AgentFileEntity();
            e.setTenantId(tenantId);
            e.setPath(normPath);
            e.setKind("file");
            e.setCreatedAt(Instant.now());
            combined = data == null ? new byte[0] : data;
        } else {
            byte[] existing = e.getData() == null ? new byte[0] : e.getData();
            byte[] incoming = data == null ? new byte[0] : data;
            combined = new byte[existing.length + incoming.length];
            System.arraycopy(existing, 0, combined, 0, existing.length);
            System.arraycopy(incoming, 0, combined, existing.length, incoming.length);
        }
        e.setSize(combined.length);
        e.setMtimeNs(nowNs());
        e.setEtag(sha256(combined));
        e.setData(combined);
        e.setUpdatedAt(Instant.now());
        ensureParentExists(tenantId, normPath);
        return repo.save(e);
    }

    @Transactional
    public void delete(String tenantId, String path, String ifMatch) {
        String normPath = normalize(path);
        AgentFileEntity e = repo.findByTenantIdAndPath(tenantId, normPath).orElse(null);
        if (e == null) throw new NotFoundException("path not found: " + path);
        if (ifMatch != null && !ifMatch.isEmpty() && !ifMatch.equals(e.getEtag())) {
            throw new BadRequestException("precondition_failed");
        }
        repo.deleteByTenantIdAndPath(tenantId, normPath);
    }

    @Transactional
    public AgentFileEntity mkdir(String tenantId, String path, JsonNode properties) {
        String normPath = normalize(path);
        Optional<AgentFileEntity> existingOpt = repo.findByTenantIdAndPath(tenantId, normPath);
        if (existingOpt.isPresent() && "dir".equals(existingOpt.get().getKind())) {
            return existingOpt.get();
        }
        AgentFileEntity e = new AgentFileEntity();
        e.setTenantId(tenantId);
        e.setPath(normPath);
        e.setKind("dir");
        e.setSize(0);
        e.setMtimeNs(nowNs());
        e.setEtag("dir-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        e.setData(null);
        if (properties != null && !properties.isNull()) {
            e.setProperties(properties);
        }
        ensureParentExists(tenantId, normPath);
        return repo.save(e);
    }

    @Transactional
    public void rename(String tenantId, String from, String to, boolean overwrite) {
        String normFrom = normalize(from);
        String normTo = normalize(to);
        AgentFileEntity src = repo.findByTenantIdAndPath(tenantId, normFrom).orElse(null);
        if (src == null) throw new NotFoundException("path not found: " + from);

        Optional<AgentFileEntity> dstOpt = repo.findByTenantIdAndPath(tenantId, normTo);
        if (dstOpt.isPresent() && !overwrite) {
            throw new BadRequestException("target_exists");
        }
        dstOpt.ifPresent(repo::delete);

        // For a file, just delete+insert with new key. For a dir, also move
        // descendants by prefix rewrite (atomic in single tx).
        repo.deleteByTenantIdAndPath(tenantId, normFrom);
        AgentFileEntity moved = new AgentFileEntity();
        moved.setTenantId(tenantId);
        moved.setPath(normTo);
        moved.setKind(src.getKind());
        moved.setSize(src.getSize());
        moved.setMtimeNs(nowNs());
        moved.setEtag(src.getEtag());
        moved.setProperties(src.getProperties());
        moved.setData(src.getData());
        moved.setCreatedAt(src.getCreatedAt());
        moved.setUpdatedAt(Instant.now());
        repo.save(moved);

        if ("dir".equals(src.getKind())) {
            String oldPrefix = normFrom.endsWith("/") ? normFrom : normFrom + "/";
            String newPrefix = normTo.endsWith("/") ? normTo : normTo + "/";
            List<AgentFileEntity> descendants = repo.findByPrefix(tenantId, oldPrefix);
            for (AgentFileEntity d : descendants) {
                String newChildPath = newPrefix + d.getPath().substring(oldPrefix.length());
                repo.deleteByTenantIdAndPath(tenantId, d.getPath());
                AgentFileEntity nd = new AgentFileEntity();
                nd.setTenantId(tenantId);
                nd.setPath(newChildPath);
                nd.setKind(d.getKind());
                nd.setSize(d.getSize());
                nd.setMtimeNs(d.getMtimeNs());
                nd.setEtag(d.getEtag());
                nd.setProperties(d.getProperties());
                nd.setData(d.getData());
                nd.setCreatedAt(d.getCreatedAt());
                nd.setUpdatedAt(Instant.now());
                repo.save(nd);
            }
        }
    }

    @Transactional
    public AgentFileEntity setProperties(String tenantId, String path, JsonNode merge) {
        AgentFileEntity e = get(tenantId, path);
        ObjectNode existing = e.getProperties() instanceof ObjectNode
                ? ((ObjectNode) e.getProperties()).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        if (merge != null && merge.isObject()) {
            merge.fields().forEachRemaining(entry -> existing.set(entry.getKey(), entry.getValue()));
        }
        e.setProperties(existing);
        e.setUpdatedAt(Instant.now());
        return repo.save(e);
    }

    // ────────────────────────── helpers ──────────────────────────

    /** Ensure all ancestor directories exist; creates them on demand. */
    private void ensureParentExists(String tenantId, String fullPath) {
        int idx = fullPath.lastIndexOf('/');
        if (idx <= 0) return; // root parent is implicit
        String parent = fullPath.substring(0, idx);
        if (repo.findByTenantIdAndPath(tenantId, parent).isEmpty()) {
            AgentFileEntity dir = new AgentFileEntity();
            dir.setTenantId(tenantId);
            dir.setPath(parent);
            dir.setKind("dir");
            dir.setSize(0);
            dir.setMtimeNs(nowNs());
            dir.setEtag("dir-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            dir.setProperties(JsonNodeFactory.instance.objectNode());
            repo.save(dir);
            ensureParentExists(tenantId, parent);
        }
    }

    /** Normalize: ensure leading "/", strip trailing "/", disallow ".." */
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
}
