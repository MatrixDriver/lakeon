package com.lakeon.memory;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final com.lakeon.repository.DatabaseRepository databaseRepository;

    public MemoryController(MemoryService memoryService,
                            com.lakeon.repository.DatabaseRepository databaseRepository) {
        this.memoryService = memoryService;
        this.databaseRepository = databaseRepository;
    }

    @GetMapping("/bases")
    public List<Map<String, Object>> listBases(HttpServletRequest req) {
        TenantEntity tenant = getTenant(req);
        return memoryService.listBases(tenant.getId()).stream()
                .map(this::toMemResponse)
                .toList();
    }

    @GetMapping("/bases/{id}")
    public Map<String, Object> getBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return toMemResponse(memoryService.getBase(tenant.getId(), id));
    }

    @PostMapping("/bases")
    public Map<String, Object> createBase(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        boolean oneLlmMode = Boolean.TRUE.equals(body.get("one_llm_mode"));
        String scene = (String) body.getOrDefault("scene", "CHAT_ASSISTANT");
        if (!java.util.List.of("DEVELOPER_TOOL", "CHAT_ASSISTANT").contains(scene)) {
            throw new com.lakeon.service.exception.BadRequestException("Invalid scene: " + scene + ". Must be DEVELOPER_TOOL or CHAT_ASSISTANT");
        }
        boolean encrypted = Boolean.TRUE.equals(body.get("encrypted"));
        String encryptedDek = (String) body.get("encrypted_dek");
        String kdfSalt = (String) body.get("kdf_salt");
        Integer embeddingDim = body.get("embedding_dim") != null
                ? ((Number) body.get("embedding_dim")).intValue() : null;
        return toMemResponse(memoryService.createBase(
            tenant,
            (String) body.get("name"),
            (String) body.get("description"),
            MemoryBaseType.valueOf(body.getOrDefault("type", "BUILTIN").toString()),
            (String) body.get("embedding_model"),
            oneLlmMode,
            scene,
            encrypted,
            encryptedDek,
            kdfSalt,
            embeddingDim
        ));
    }

    @DeleteMapping("/bases/{id}")
    public Map<String, Object> deleteBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        memoryService.deleteBase(tenant.getId(), id);
        return Map.of("status", "deleted");
    }

    // ── Proxy endpoints to Python memory microservice ──────────

    @PostMapping("/bases/{id}/ingest")
    public Object ingest(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        // Encrypted bases require pre-computed embedding (proof that client encrypted locally)
        MemoryBaseEntity mem = memoryService.getBase(tenant.getId(), id);
        if (Boolean.TRUE.equals(mem.getEncrypted()) && !body.containsKey("embedding")) {
            throw new com.lakeon.service.exception.BadRequestException(
                "Server rejected plaintext memory for encrypted base. " +
                "Your client is sending unencrypted content. Please upgrade: pip install --upgrade dbay-mcp>=0.5.2");
        }
        Object result = memoryService.proxyPost(tenant.getId(), id, "/ingest", body);
        memoryService.refreshCountAsync(tenant.getId(), id);
        return result;
    }

    @PostMapping("/bases/{id}/recall")
    public Object recall(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyPost(tenant.getId(), id, "/recall", body);
    }

    @PostMapping("/bases/{id}/digest")
    public Object digest(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyPost(tenant.getId(), id, "/digest", null);
    }

    @PostMapping("/bases/{id}/ingest_extracted")
    public Object ingestExtracted(HttpServletRequest req, @PathVariable String id,
                                   @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        Object result = memoryService.proxyPost(tenant.getId(), id, "/ingest_extracted", body);
        memoryService.refreshCountAsync(tenant.getId(), id);
        return result;
    }

    @PostMapping("/bases/{id}/digest_extracted")
    public Object digestExtracted(HttpServletRequest req, @PathVariable String id,
                                   @RequestBody Map<String, Object> body) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyPost(tenant.getId(), id, "/digest_extracted", body);
    }

    @GetMapping("/bases/{id}/memories")
    public Object listMemories(HttpServletRequest req, @PathVariable String id,
            @RequestParam(required = false) String memory_type,
            @RequestParam(defaultValue = "0") String offset,
            @RequestParam(defaultValue = "20") String limit) {
        TenantEntity tenant = getTenant(req);
        Map<String, String> params = new HashMap<>();
        if (memory_type != null) params.put("memory_type", memory_type);
        params.put("offset", offset);
        params.put("limit", limit);
        return memoryService.proxyGet(tenant.getId(), id, "/memories", params);
    }

    @GetMapping("/bases/{id}/memories/{memoryId}")
    public Object getMemory(HttpServletRequest req, @PathVariable String id, @PathVariable int memoryId) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyGet(tenant.getId(), id, "/memories/" + memoryId, null);
    }

    @DeleteMapping("/bases/{id}/memories/{memoryId}")
    public Object deleteMemory(HttpServletRequest req, @PathVariable String id, @PathVariable int memoryId) {
        TenantEntity tenant = getTenant(req);
        Object result = memoryService.proxyDelete(tenant.getId(), id, "/memories/" + memoryId);
        memoryService.refreshCountAsync(tenant.getId(), id);
        return result;
    }

    @GetMapping("/bases/{id}/raw_messages")
    public Object listRawMessages(HttpServletRequest req, @PathVariable String id,
            @RequestParam(defaultValue = "0") String offset,
            @RequestParam(defaultValue = "20") String limit,
            @RequestParam(required = false) String op) {
        TenantEntity tenant = getTenant(req);
        Map<String, String> params = new HashMap<>();
        params.put("offset", offset);
        params.put("limit", limit);
        if (op != null && !op.isEmpty()) params.put("op", op);
        return memoryService.proxyGet(tenant.getId(), id, "/raw_messages", params);
    }

    @GetMapping("/bases/{id}/raw_messages/{messageId}")
    public Object getRawMessage(HttpServletRequest req, @PathVariable String id, @PathVariable String messageId) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyGet(tenant.getId(), id, "/raw_messages/" + messageId, null);
    }

    @GetMapping("/bases/{id}/stats")
    public Object stats(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyGet(tenant.getId(), id, "/stats", null);
    }

    @GetMapping("/bases/{id}/traits")
    public Object traits(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyGet(tenant.getId(), id, "/traits", null);
    }

    @GetMapping("/bases/{id}/graph")
    public Object graph(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return memoryService.proxyGet(tenant.getId(), id, "/graph", null);
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toMemResponse(MemoryBaseEntity mem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", mem.getId());
        map.put("tenant_id", mem.getTenantId());
        map.put("name", mem.getName());
        map.put("description", mem.getDescription());
        map.put("type", mem.getType().name());
        map.put("database_id", mem.getDatabaseId());
        map.put("status", mem.getStatus());
        map.put("memory_count", mem.getMemoryCount());
        map.put("trait_count", mem.getTraitCount());
        map.put("embedding_model", mem.getEmbeddingModel());
        map.put("error", mem.getError());
        map.put("one_llm_mode", Boolean.TRUE.equals(mem.getOneLlmMode()));
        map.put("scene", mem.getScene());
        map.put("encrypted", Boolean.TRUE.equals(mem.getEncrypted()));
        map.put("encrypted_dek", mem.getEncryptedDek());
        map.put("kdf_salt", mem.getKdfSalt());
        map.put("embedding_dim", mem.getEmbeddingDim());
        map.put("created_at", mem.getCreatedAt() != null ? mem.getCreatedAt().toString() : null);
        map.put("updated_at", mem.getUpdatedAt() != null ? mem.getUpdatedAt().toString() : null);
        if (mem.getDatabaseId() != null) {
            databaseRepository.findById(mem.getDatabaseId()).ifPresent(db ->
                    map.put("database_status", db.getStatus().name()));
        }
        return map;
    }
}
