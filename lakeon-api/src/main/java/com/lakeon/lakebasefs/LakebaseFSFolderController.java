package com.lakeon.lakebasefs;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lbfs/folders")
public class LakebaseFSFolderController {

    private final LakebaseFSFolderService service;

    public LakebaseFSFolderController(LakebaseFSFolderService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest req) {
        List<Map<String, Object>> folders = service.list(getTenant(req)).stream()
                .map(this::toMap)
                .toList();
        return Map.of("folders", folders);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return toMap(service.create(getTenant(req), profileFromBody(body)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        return toMap(service.get(getTenant(req), id));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(
            HttpServletRequest req,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return toMap(service.update(getTenant(req), id, profileFromBody(body)));
    }

    private LakebaseFSFolderProfile profileFromBody(Map<String, Object> body) {
        return LakebaseFSFolderProfile.normalize(
                str(body, "display_name"),
                str(body, "directory_kind"),
                str(body, "storage_policy"),
                str(body, "processing_profile"));
    }

    private static String str(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        return raw == null ? null : raw.toString();
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        TenantEntity t = (TenantEntity) req.getAttribute("tenant");
        if (t == null) throw new BadRequestException("no authenticated tenant");
        return t;
    }

    private Map<String, Object> toMap(LakebaseFSFolderEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("display_name", e.getDisplayName());
        m.put("directory_kind", e.getDirectoryKind());
        m.put("storage_policy", e.getStoragePolicy());
        m.put("processing_profile", e.getProcessingProfile());
        m.put("status", e.getStatus());
        m.put("created_at", e.getCreatedAt());
        m.put("updated_at", e.getUpdatedAt());
        return m;
    }
}
