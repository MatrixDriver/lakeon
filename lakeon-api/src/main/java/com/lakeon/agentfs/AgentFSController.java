package com.lakeon.agentfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * AgentFS REST controller.
 *
 * All write endpoints take `path` in the body (not the URL) to avoid
 * URL length / encoding issues with deeply-nested paths.
 * Reads use GET with base64url-encoded `path` query parameter.
 */
@RestController
@RequestMapping("/api/v1/agentfs")
public class AgentFSController {

    private final AgentFSService svc;

    public AgentFSController(AgentFSService svc) {
        this.svc = svc;
    }

    // ────────────────────── READ ──────────────────────

    @GetMapping("/files")
    public ResponseEntity<byte[]> readFile(HttpServletRequest req,
                                           @RequestParam("path") String pathB64,
                                           @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TenantEntity tenant = getTenant(req);
        String path = decodePath(pathB64);
        AgentFSService.FileRow e = svc.get(tenant, path);
        if (ifNoneMatch != null && ifNoneMatch.equals(e.etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, e.etag)
                .header("X-AgentFS-Size", String.valueOf(e.size))
                .header("X-AgentFS-Mtime", String.valueOf(e.mtimeNs))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(e.data == null ? new byte[0] : e.data);
    }

    @GetMapping("/files/head")
    public Map<String, Object> head(HttpServletRequest req,
                                    @RequestParam("path") String pathB64) {
        TenantEntity tenant = getTenant(req);
        return toEntry(svc.get(tenant, decodePath(pathB64)));
    }

    @GetMapping("/list")
    public Map<String, Object> list(HttpServletRequest req,
                                    @RequestParam(value = "prefix", required = false, defaultValue = "") String prefixB64,
                                    @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        TenantEntity tenant = getTenant(req);
        String prefix = prefixB64.isEmpty() ? "/" : decodePath(prefixB64);
        List<AgentFSService.FileRow> items = svc.list(tenant, prefix, recursive);
        List<Map<String, Object>> entries = items.stream().map(this::toEntry).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries);
        out.put("next_cursor", null);
        return out;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest req) {
        return svc.stats(getTenant(req));
    }

    // ────────────────────── WRITE (path in body) ──────────────────────

    @PostMapping("/files/put")
    public Map<String, Object> putFile(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String path = reqStr(body, "path");
        byte[] data = decodeData(body.get("data_base64"));
        JsonNode properties = bodyAsJson(body.get("properties"));
        String ifMatch = (String) body.get("if_match");
        String ifNoneMatch = (String) body.get("if_none_match");
        boolean isCreate = svc.getOpt(tenant, path).isEmpty();
        AgentFSService.FileRow e = svc.put(tenant, path, data, properties, ifMatch, ifNoneMatch);
        return Map.of("etag", e.etag, "created", isCreate);
    }

    @PostMapping("/files/append")
    public Map<String, Object> appendFile(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String path = reqStr(body, "path");
        byte[] data = decodeData(body.get("data_base64"));
        String ifMatch = (String) body.get("if_match");
        AgentFSService.FileRow e = svc.append(tenant, path, data, ifMatch);
        return Map.of("new_size", e.size, "etag", e.etag);
    }

    @PostMapping("/files/delete")
    public ResponseEntity<Void> deleteFile(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String path = reqStr(body, "path");
        String ifMatch = (String) body.get("if_match");
        svc.delete(tenant, path, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/files/properties")
    public Map<String, Object> setProps(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String path = reqStr(body, "path");
        JsonNode props = bodyAsJson(body.get("properties"));
        return toEntry(svc.setProperties(tenant, path, props));
    }

    @PostMapping("/mkdir")
    public ResponseEntity<Map<String, Object>> mkdir(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String path = reqStr(body, "path");
        JsonNode props = bodyAsJson(body.get("properties"));
        AgentFSService.FileRow e = svc.mkdir(tenant, path, props);
        return ResponseEntity.status(HttpStatus.CREATED).body(toEntry(e));
    }

    @PostMapping("/rename")
    public ResponseEntity<Void> rename(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        String from = reqStr(body, "from");
        String to = reqStr(body, "to");
        boolean overwrite = Boolean.TRUE.equals(body.get("overwrite"));
        svc.rename(tenant, from, to, overwrite);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch")
    public Map<String, Object> batch(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        Object opsRaw = body.get("ops");
        if (!(opsRaw instanceof List)) throw new BadRequestException("ops must be an array");
        List<?> ops = (List<?>) opsRaw;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object rawOp : ops) {
            if (!(rawOp instanceof Map)) throw new BadRequestException("op must be object");
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) rawOp;
            String kind = reqStr(op, "op");
            try {
                switch (kind) {
                    case "put" -> {
                        String p = reqStr(op, "path");
                        byte[] data = decodeData(op.get("data_base64"));
                        JsonNode props = bodyAsJson(op.get("properties"));
                        AgentFSService.FileRow e = svc.put(tenant, p, data, props,
                                (String) op.get("if_match"), (String) op.get("if_none_match"));
                        results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.etag));
                    }
                    case "delete" -> {
                        String p = reqStr(op, "path");
                        try {
                            svc.delete(tenant, p, (String) op.get("if_match"));
                            results.add(Map.of("op", kind, "path", p, "status", "ok"));
                        } catch (com.lakeon.service.exception.NotFoundException ignored) {
                            results.add(Map.of("op", kind, "path", p, "status", "ok_absent"));
                        }
                    }
                    case "append" -> {
                        String p = reqStr(op, "path");
                        byte[] data = decodeData(op.get("data_base64"));
                        AgentFSService.FileRow e = svc.append(tenant, p, data, (String) op.get("if_match"));
                        results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.etag));
                    }
                    case "rename" -> {
                        svc.rename(tenant,
                                reqStr(op, "from"),
                                reqStr(op, "to"),
                                Boolean.TRUE.equals(op.get("overwrite")));
                        results.add(Map.of("op", kind, "status", "ok"));
                    }
                    case "mkdir" -> {
                        String p = reqStr(op, "path");
                        AgentFSService.FileRow e = svc.mkdir(tenant, p, bodyAsJson(op.get("properties")));
                        results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.etag));
                    }
                    case "set_properties" -> {
                        String p = reqStr(op, "path");
                        AgentFSService.FileRow e = svc.setProperties(tenant, p, bodyAsJson(op.get("properties")));
                        results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.etag));
                    }
                    default -> throw new BadRequestException("unknown op: " + kind);
                }
            } catch (BadRequestException be) {
                String msg = be.getMessage() == null ? "" : be.getMessage();
                if (msg.contains("precondition_failed")) {
                    Object p = op.get("path");
                    java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("op", kind);
                    if (p != null) r.put("path", p.toString());
                    r.put("status", "precondition_failed");
                    results.add(r);
                } else {
                    throw be;
                }
            }
        }
        return Map.of("results", results);
    }

    // ────────────────────── helpers ──────────────────────

    private TenantEntity getTenant(HttpServletRequest req) {
        TenantEntity t = (TenantEntity) req.getAttribute("tenant");
        if (t == null) throw new BadRequestException("no authenticated tenant");
        return t;
    }

    private Map<String, Object> toEntry(AgentFSService.FileRow e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", e.path);
        m.put("kind", e.kind);
        m.put("size", e.size);
        m.put("mtime_ns", e.mtimeNs);
        m.put("etag", e.etag);
        m.put("properties", e.properties);
        return m;
    }

    private static String reqStr(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) throw new BadRequestException(key + " required");
        return v.toString();
    }

    private static byte[] decodeData(Object raw) {
        if (raw == null) return new byte[0];
        try {
            return Base64.getDecoder().decode(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("data_base64 not valid base64");
        }
    }

    private static String decodePath(String b64url) {
        if (b64url == null || b64url.isEmpty()) {
            throw new BadRequestException("path query param required");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(b64url);
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("path not valid base64url");
        }
    }

    private static JsonNode bodyAsJson(Object raw) {
        if (raw == null) return null;
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        return m.valueToTree(raw);
    }
}
