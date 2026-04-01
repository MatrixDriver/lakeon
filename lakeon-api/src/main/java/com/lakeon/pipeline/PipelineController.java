package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineEntity pipeline = pipelineService.create(
                tenant.getId(),
                (String) body.get("name"),
                (String) body.get("description"),
                (String) body.get("data_type"),
                (String) body.get("source_template_id"),
                (String) body.get("dag_yaml"));
        return toResponse(pipeline);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return pipelineService.list(tenant.getId()).stream()
                .map(this::toResponse).toList();
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> listTemplates() {
        return pipelineService.listTemplates().stream()
                .map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return toResponse(pipelineService.get(tenant.getId(), id));
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> listVersions(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return pipelineService.listVersions(tenant.getId(), id).stream()
                .map(this::versionToResponse).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public Map<String, Object> getVersion(HttpServletRequest req,
                                           @PathVariable String id,
                                           @PathVariable int version) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return versionToResponse(pipelineService.getVersion(tenant.getId(), id, version));
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createVersion(HttpServletRequest req,
                                              @PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineVersionEntity version = pipelineService.createVersion(
                tenant.getId(), id,
                (String) body.get("dag_yaml"),
                (String) body.get("changelog"));
        return versionToResponse(version);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        pipelineService.delete(tenant.getId(), id);
    }

    private Map<String, Object> toResponse(PipelineEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("data_type", p.getDataType());
        m.put("is_template", p.getIsTemplate());
        m.put("source_template_id", p.getSourceTemplateId());
        m.put("latest_version", p.getLatestVersion());
        m.put("created_at", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        m.put("updated_at", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> versionToResponse(PipelineVersionEntity v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("pipeline_id", v.getPipelineId());
        m.put("version", v.getVersion());
        m.put("dag_yaml", v.getDagYaml());
        m.put("status", v.getStatus());
        m.put("changelog", v.getChangelog());
        m.put("created_at", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
        return m;
    }
}
