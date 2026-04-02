package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/pipeline-components")
public class PipelineComponentController {

    private final PipelineComponentService componentService;

    public PipelineComponentController(PipelineComponentService componentService) {
        this.componentService = componentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        PipelineComponentEntity component = componentService.register(
                tenant.getId(),
                (String) body.get("name"),
                (String) body.get("display_name"),
                (String) body.get("category"),
                (String) body.get("data_type"),
                (String) body.get("description"),
                (String) body.get("entrypoint"),
                (String) body.get("params_schema"),
                (String) body.get("input_schema"),
                (String) body.get("output_schema"),
                (String) body.get("output_branches"),
                (Boolean) body.get("requires_gpu"),
                (String) body.get("requires_model"),
                (String) body.get("execution_mode"));
        return toResponse(component);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return componentService.listAvailable(tenant.getId()).stream()
                .map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toResponse(componentService.get(id));
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> listVersions(@PathVariable String id) {
        return componentService.listVersions(id).stream()
                .map(this::versionToResponse).toList();
    }

    @GetMapping("/{id}/versions/{version}")
    public Map<String, Object> getVersion(@PathVariable String id, @PathVariable String version) {
        return versionToResponse(componentService.getVersion(id, version));
    }

    private Map<String, Object> toResponse(PipelineComponentEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("tenant_id", c.getTenantId());
        m.put("name", c.getName());
        m.put("display_name", c.getDisplayName());
        m.put("category", c.getCategory());
        m.put("data_type", c.getDataType());
        m.put("description", c.getDescription());
        m.put("latest_version", c.getLatestVersion());
        m.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("updated_at", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> versionToResponse(PipelineComponentVersionEntity v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("component_id", v.getComponentId());
        m.put("version", v.getVersion());
        m.put("entrypoint", v.getEntrypoint());
        m.put("params_schema", v.getParamsSchema());
        m.put("input_schema", v.getInputSchema());
        m.put("output_schema", v.getOutputSchema());
        m.put("output_branches", v.getOutputBranches());
        m.put("requires_gpu", v.getRequiresGpu());
        m.put("requires_model", v.getRequiresModel());
        m.put("execution_mode", v.getExecutionMode());
        m.put("status", v.getStatus());
        m.put("changelog", v.getChangelog());
        m.put("created_at", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
        return m;
    }
}
