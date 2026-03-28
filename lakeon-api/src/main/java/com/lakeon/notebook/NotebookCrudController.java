package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/datalake/notebooks")
public class NotebookCrudController {

    private final NotebookRepository notebookRepository;
    private final NotebookStorageService storageService;
    private final LakeonProperties props;

    public NotebookCrudController(NotebookRepository notebookRepository,
                                   NotebookStorageService storageService,
                                   LakeonProperties props) {
        this.notebookRepository = notebookRepository;
        this.storageService = storageService;
        this.props = props;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TenantEntity requireTenant(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        return tenant;
    }

    private NotebookEntity requireNotebook(String id, String tenantId) {
        return notebookRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found"));
    }

    private Map<String, Object> notebookToMap(NotebookEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("image", e.getImage());
        m.put("dataset_ids", e.getDatasetIds());
        m.put("obs_path", e.getObsPath());
        m.put("created_at", e.getCreatedAt());
        m.put("updated_at", e.getUpdatedAt());
        return m;
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /** POST / — Create notebook */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req,
                                      @RequestBody Map<String, Object> body) {
        TenantEntity tenant = requireTenant(req);
        String name = (String) body.get("name");
        String image = body.get("image") != null ? (String) body.get("image") : "python-data";
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }

        NotebookEntity nb = new NotebookEntity();
        nb.setTenantId(tenant.getId());
        nb.setName(name);
        nb.setImage(image);
        notebookRepository.save(nb);  // triggers @PrePersist to generate id

        String obsPath = "notebooks/" + tenant.getId() + "/" + nb.getId() + "/notebook.json";
        nb.setObsPath(obsPath);
        notebookRepository.save(nb);

        String emptyContent = "{\"cells\":[],\"image\":\"" + image + "\",\"datasetIds\":[]}";
        storageService.write(obsPath, emptyContent);

        return notebookToMap(nb);
    }

    /** GET / — List notebooks (metadata only) */
    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        TenantEntity tenant = requireTenant(req);
        return notebookRepository.findByTenantIdOrderByUpdatedAtDesc(tenant.getId())
                .stream()
                .map(this::notebookToMap)
                .collect(Collectors.toList());
    }

    /** GET /{id} — Get notebook with OBS content */
    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());
        Map<String, Object> result = notebookToMap(nb);
        result.put("content", storageService.read(nb.getObsPath()));
        return result;
    }

    /** PUT /{id} — Save raw JSON content */
    @PutMapping("/{id}")
    public Map<String, Object> save(HttpServletRequest req,
                                     @PathVariable String id,
                                     @RequestBody String body,
                                     @RequestParam(defaultValue = "false") boolean version) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());

        storageService.write(nb.getObsPath(), body);
        if (version) {
            storageService.createVersionSnapshot(nb.getObsPath(), body);
        }

        nb.setUpdatedAt(Instant.now());
        notebookRepository.save(nb);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated_at", nb.getUpdatedAt());
        return result;
    }

    /** PATCH /{id} — Rename */
    @PatchMapping("/{id}")
    public Map<String, Object> rename(HttpServletRequest req,
                                       @PathVariable String id,
                                       @RequestBody Map<String, Object> body) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());
        String newName = (String) body.get("name");
        if (newName == null || newName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        nb.setName(newName);
        notebookRepository.save(nb);
        return notebookToMap(nb);
    }

    /** DELETE /{id} — Delete notebook + all OBS files */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());
        String prefix = "notebooks/" + tenant.getId() + "/" + id + "/";
        storageService.deleteAll(prefix);
        notebookRepository.delete(nb);
    }

    // -------------------------------------------------------------------------
    // Versions
    // -------------------------------------------------------------------------

    /** GET /{id}/versions — List version timestamps */
    @GetMapping("/{id}/versions")
    public List<String> listVersions(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());
        return storageService.listVersions(nb.getObsPath());
    }

    /** GET /{id}/versions/{ts} — Get version content */
    @GetMapping("/{id}/versions/{ts}")
    public ResponseEntity<String> getVersion(HttpServletRequest req,
                                              @PathVariable String id,
                                              @PathVariable String ts) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());
        String content = storageService.readVersion(nb.getObsPath(), ts);
        if (content == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(content);
    }

    /** POST /{id}/versions/{ts}/restore — Restore version as current */
    @PostMapping("/{id}/versions/{ts}/restore")
    public ResponseEntity<String> restoreVersion(HttpServletRequest req,
                                                  @PathVariable String id,
                                                  @PathVariable String ts) {
        TenantEntity tenant = requireTenant(req);
        NotebookEntity nb = requireNotebook(id, tenant.getId());
        String content = storageService.readVersion(nb.getObsPath(), ts);
        if (content == null) return ResponseEntity.notFound().build();
        storageService.write(nb.getObsPath(), content);
        nb.setUpdatedAt(Instant.now());
        notebookRepository.save(nb);
        return ResponseEntity.ok(content);
    }
}
