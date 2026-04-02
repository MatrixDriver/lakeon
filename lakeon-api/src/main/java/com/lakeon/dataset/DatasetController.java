package com.lakeon.dataset;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping("/preview")
    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return datasetService.preview(tenant.getId(),
            (String) body.get("database_id"),
            (String) body.get("query_mode"),
            (List<Map<String, Object>>) body.get("tables"),
            (String) body.get("sql"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetEntity ds = datasetService.create(tenant.getId(),
            (String) body.get("name"),
            (String) body.get("description"),
            (String) body.get("database_id"),
            (String) body.get("query_mode"),
            (List<Map<String, Object>>) body.get("tables"),
            (String) body.get("sql"));
        return toResponse(ds);
    }

    @PostMapping("/{id}/export")
    public Map<String, Object> triggerExport(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetEntity ds = datasetService.triggerExport(tenant.getId(), id);
        return toResponse(ds);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req,
                                          @RequestParam(required = false) String status) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetStatus ds = null;
        if (status != null && !status.isBlank()) {
            ds = DatasetStatus.valueOf(status.toUpperCase());
        }
        return datasetService.listDatasets(tenant.getId(), ds).stream()
            .map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return datasetService.getDatasetResponse(tenant.getId(), id);
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> listVersions(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return datasetService.listVersions(tenant.getId(), id);
    }

    @PostMapping("/upload-urls")
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadUrls(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return datasetService.generateUploadUrls(tenant.getId(),
            (String) body.get("name"),
            (String) body.get("description"),
            (List<String>) body.get("files"));
    }

    @PostMapping("/{id}/finalize")
    public Map<String, Object> finalize(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatasetEntity ds = datasetService.finalizeUpload(tenant.getId(), id);
        return toResponse(ds);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        datasetService.deleteDataset(tenant.getId(), id);
    }

    private Map<String, Object> toResponse(DatasetEntity ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("name", ds.getName());
        m.put("description", ds.getDescription());
        m.put("source_type", ds.getSourceType().name());
        m.put("database_id", ds.getDatabaseId());
        m.put("status", ds.getStatus().name());
        m.put("row_count", ds.getRowCount());
        m.put("file_size", ds.getFileSize());
        m.put("file_count", ds.getFileCount());
        m.put("obs_path", ds.getObsPath());
        m.put("job_id", ds.getJobId());
        m.put("error", ds.getError());
        m.put("created_at", ds.getCreatedAt() != null ? ds.getCreatedAt().toString() : null);
        m.put("updated_at", ds.getUpdatedAt() != null ? ds.getUpdatedAt().toString() : null);
        return m;
    }
}
