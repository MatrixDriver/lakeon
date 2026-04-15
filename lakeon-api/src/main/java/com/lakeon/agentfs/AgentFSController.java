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
 * URL length / encoding issues with deeply-nested paths (see specs/agentfs-openapi.yaml).
 * Reads use GET with base64url-encoded `path` query parameter for cacheability.
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
        String tenantId = getTenantId(req);
        String path = decodePath(pathB64);
        AgentFileEntity e = svc.get(tenantId, path);
        if (ifNoneMatch != null && ifNoneMatch.equals(e.getEtag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, e.getEtag())
                .header("X-AgentFS-Size", String.valueOf(e.getSize()))
                .header("X-AgentFS-Mtime", String.valueOf(e.getMtimeNs()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(e.getData() == null ? new byte[0] : e.getData());
    }

    @GetMapping("/files/head")
    public Map<String, Object> head(HttpServletRequest req,
                                    @RequestParam("path") String pathB64) {
        String tenantId = getTenantId(req);
        String path = decodePath(pathB64);
        return toEntryResponse(svc.get(tenantId, path));
    }

    @GetMapping("/list")
    public Map<String, Object> list(HttpServletRequest req,
                                    @RequestParam(value = "prefix", required = false, defaultValue = "") String prefixB64,
                                    @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        String tenantId = getTenantId(req);
        String prefix = prefixB64.isEmpty() ? "/" : decodePath(prefixB64);
        List<AgentFileEntity> items = svc.list(tenantId, prefix, recursive);
        List<Map<String, Object>> entries = items.stream().map(this::toEntryResponse).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries);
        out.put("next_cursor", null);
        return out;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest req) {
        return svc.stats(getTenantId(req));
    }

    // ────────────────────── WRITE (path in body) ──────────────────────

    @PostMapping("/files/put")
    public Map<String, Object> putFile(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        String path = reqStr(body, "path");
        byte[] data = decodeData(body.get("data_base64"));
        JsonNode properties = bodyAsJson(body.get("properties"));
        String ifMatch = (String) body.get("if_match");
        String ifNoneMatch = (String) body.get("if_none_match");
        boolean isCreate = svc.getOpt(tenantId, path).isEmpty();
        AgentFileEntity e = svc.put(tenantId, path, data, properties, ifMatch, ifNoneMatch);
        return Map.of("etag", e.getEtag(), "created", isCreate);
    }

    @PostMapping("/files/append")
    public Map<String, Object> appendFile(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        String path = reqStr(body, "path");
        byte[] data = decodeData(body.get("data_base64"));
        AgentFileEntity e = svc.append(tenantId, path, data);
        return Map.of("new_size", e.getSize(), "etag", e.getEtag());
    }

    @PostMapping("/files/delete")
    public ResponseEntity<Void> deleteFile(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        String path = reqStr(body, "path");
        String ifMatch = (String) body.get("if_match");
        svc.delete(tenantId, path, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/files/properties")
    public Map<String, Object> setProps(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        String path = reqStr(body, "path");
        JsonNode props = bodyAsJson(body.get("properties"));
        AgentFileEntity e = svc.setProperties(tenantId, path, props);
        return toEntryResponse(e);
    }

    @PostMapping("/mkdir")
    public ResponseEntity<Map<String, Object>> mkdir(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        String path = reqStr(body, "path");
        JsonNode props = bodyAsJson(body.get("properties"));
        AgentFileEntity e = svc.mkdir(tenantId, path, props);
        return ResponseEntity.status(HttpStatus.CREATED).body(toEntryResponse(e));
    }

    @PostMapping("/rename")
    public ResponseEntity<Void> rename(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        String from = reqStr(body, "from");
        String to = reqStr(body, "to");
        boolean overwrite = Boolean.TRUE.equals(body.get("overwrite"));
        svc.rename(tenantId, from, to, overwrite);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch")
    public Map<String, Object> batch(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String tenantId = getTenantId(req);
        Object opsRaw = body.get("ops");
        if (!(opsRaw instanceof List)) throw new BadRequestException("ops must be an array");
        List<?> ops = (List<?>) opsRaw;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object rawOp : ops) {
            if (!(rawOp instanceof Map)) throw new BadRequestException("op must be object");
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) rawOp;
            String kind = reqStr(op, "op");
            switch (kind) {
                case "put" -> {
                    String p = reqStr(op, "path");
                    byte[] data = decodeData(op.get("data_base64"));
                    JsonNode props = bodyAsJson(op.get("properties"));
                    AgentFileEntity e = svc.put(tenantId, p, data, props,
                            (String) op.get("if_match"), (String) op.get("if_none_match"));
                    results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.getEtag()));
                }
                case "delete" -> {
                    String p = reqStr(op, "path");
                    // Batch delete is idempotent (rm -f semantics) — succeed if path
                    // already absent. Single-call /files/delete keeps strict 404.
                    try {
                        svc.delete(tenantId, p, (String) op.get("if_match"));
                        results.add(Map.of("op", kind, "path", p, "status", "ok"));
                    } catch (com.lakeon.service.exception.NotFoundException ignored) {
                        results.add(Map.of("op", kind, "path", p, "status", "ok_absent"));
                    }
                }
                case "append" -> {
                    String p = reqStr(op, "path");
                    byte[] data = decodeData(op.get("data_base64"));
                    AgentFileEntity e = svc.append(tenantId, p, data);
                    results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.getEtag()));
                }
                case "rename" -> {
                    svc.rename(tenantId,
                            reqStr(op, "from"),
                            reqStr(op, "to"),
                            Boolean.TRUE.equals(op.get("overwrite")));
                    results.add(Map.of("op", kind, "status", "ok"));
                }
                case "mkdir" -> {
                    String p = reqStr(op, "path");
                    AgentFileEntity e = svc.mkdir(tenantId, p, bodyAsJson(op.get("properties")));
                    results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.getEtag()));
                }
                case "set_properties" -> {
                    String p = reqStr(op, "path");
                    AgentFileEntity e = svc.setProperties(tenantId, p, bodyAsJson(op.get("properties")));
                    results.add(Map.of("op", kind, "path", p, "status", "ok", "etag", e.getEtag()));
                }
                default -> throw new BadRequestException("unknown op: " + kind);
            }
        }
        return Map.of("results", results);
    }

    // ────────────────────── helpers ──────────────────────

    private String getTenantId(HttpServletRequest req) {
        TenantEntity t = (TenantEntity) req.getAttribute("tenant");
        if (t == null) throw new BadRequestException("no authenticated tenant");
        return t.getId();
    }

    private Map<String, Object> toEntryResponse(AgentFileEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", e.getPath());
        m.put("kind", e.getKind());
        m.put("size", e.getSize());
        m.put("mtime_ns", e.getMtimeNs());
        m.put("etag", e.getEtag());
        m.put("properties", e.getProperties());
        return m;
    }

    private static String reqStr(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) throw new BadRequestException(key + " required");
        return v.toString();
    }

    private static byte[] decodeData(Object raw) {
        if (raw == null) return new byte[0];
        String s = raw.toString();
        try {
            return Base64.getDecoder().decode(s);
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
        // Jackson already parsed the request body into Map<String,Object>; turn a
        // sub-object back into a JsonNode for service layer.
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        return m.valueToTree(raw);
    }
}
