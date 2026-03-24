package com.lakeon.memory;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
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
    public Map<String, Object> createBase(HttpServletRequest req, @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(req);
        return toMemResponse(memoryService.createBase(
            tenant.getId(),
            body.get("name"),
            body.get("description"),
            MemoryBaseType.valueOf(body.getOrDefault("type", "BUILTIN")),
            body.get("embedding_model")
        ));
    }

    @DeleteMapping("/bases/{id}")
    public Map<String, Object> deleteBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        memoryService.deleteBase(tenant.getId(), id);
        return Map.of("status", "deleted");
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
        map.put("created_at", mem.getCreatedAt() != null ? mem.getCreatedAt().toString() : null);
        map.put("updated_at", mem.getUpdatedAt() != null ? mem.getUpdatedAt().toString() : null);
        return map;
    }
}
