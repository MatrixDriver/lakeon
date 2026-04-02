package com.lakeon.pipeline;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/pipeline-runs")
public class PipelineRunController {

    private final PipelineRunService runService;

    public PipelineRunController(PipelineRunService runService) {
        this.runService = runService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> trigger(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        int version = body.get("version") instanceof Number
                ? ((Number) body.get("version")).intValue()
                : Integer.parseInt((String) body.get("version"));
        Object dsv = body.get("input_dataset_version");
        Integer inputDatasetVersion = dsv instanceof Number ? ((Number) dsv).intValue() : null;

        PipelineRunEntity run = runService.trigger(
                tenant.getId(),
                (String) body.get("pipeline_id"),
                version,
                (String) body.get("input_dataset_id"),
                inputDatasetVersion);
        return toResponse(run);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req,
                                           @RequestParam(name = "pipeline_id") String pipelineId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        // Verify pipeline belongs to tenant before listing runs
        return runService.listByPipeline(pipelineId).stream()
                .filter(r -> r.getTenantId().equals(tenant.getId()))
                .map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return toResponse(runService.get(tenant.getId(), id));
    }

    @GetMapping("/{id}/steps")
    public List<Map<String, Object>> listStepRuns(@PathVariable String id) {
        return runService.listStepRuns(id).stream()
                .map(this::stepRunToResponse).toList();
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return toResponse(runService.cancel(tenant.getId(), id));
    }

    private Map<String, Object> toResponse(PipelineRunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("pipeline_id", r.getPipelineId());
        m.put("pipeline_version", r.getPipelineVersion());
        m.put("tenant_id", r.getTenantId());
        m.put("input_dataset_id", r.getInputDatasetId());
        m.put("input_dataset_version", r.getInputDatasetVersion());
        m.put("output_dataset_version_id", r.getOutputDatasetVersionId());
        m.put("status", r.getStatus());
        m.put("started_at", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
        m.put("finished_at", r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
        m.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> stepRunToResponse(PipelineStepRunEntity sr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sr.getId());
        m.put("run_id", sr.getRunId());
        m.put("step_id", sr.getStepId());
        m.put("component_id", sr.getComponentId());
        m.put("component_version", sr.getComponentVersion());
        m.put("status", sr.getStatus());
        m.put("input_ref", sr.getInputRef());
        m.put("output_ref", sr.getOutputRef());
        m.put("checkpoint_path", sr.getCheckpointPath());
        m.put("metrics", sr.getMetrics());
        m.put("error", sr.getError());
        m.put("started_at", sr.getStartedAt() != null ? sr.getStartedAt().toString() : null);
        m.put("finished_at", sr.getFinishedAt() != null ? sr.getFinishedAt().toString() : null);
        m.put("created_at", sr.getCreatedAt() != null ? sr.getCreatedAt().toString() : null);
        return m;
    }
}
