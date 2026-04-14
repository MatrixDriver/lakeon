package com.lakeon.rayjobs;

import com.lakeon.datalake.DatalakeJobRequest;
import com.lakeon.datalake.DatalakeJobResponse;
import com.lakeon.datalake.DatalakeJobStatus;
import com.lakeon.datalake.DatalakeJobType;
import com.lakeon.datalake.DatalakeLogService;
import com.lakeon.datalake.DatalakeService;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin REST wrapper over DatalakeService for the `pyscaler`/xscale CLI contract:
 *
 *   POST /api/v1/ray-jobs              (multipart: script file + input/output paths)
 *   GET  /api/v1/ray-jobs/{id}         → {state, duration, returncode}
 *   GET  /api/v1/ray-jobs/{id}/logs    → {lines: [...]}
 *
 * Auth: Authorization: Bearer <lk_...> via ApiKeyFilter.
 * Under the hood creates a datalake job of type RAY with the uploaded script
 * as inline_script; input/output paths are passed to the script via env vars.
 */
@RestController
@RequestMapping("/api/v1/ray-jobs")
public class RayJobsController {
    private static final Logger log = LoggerFactory.getLogger(RayJobsController.class);

    private final DatalakeService service;
    private final DatalakeLogService logService;

    public RayJobsController(DatalakeService service, DatalakeLogService logService) {
        this.service = service;
        this.logService = logService;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> submit(HttpServletRequest req,
                                      @RequestParam("script") MultipartFile script,
                                      @RequestParam(value = "input", required = false) String input,
                                      @RequestParam(value = "output", required = false) String output,
                                      @RequestParam(value = "name", required = false) String name,
                                      @RequestParam(value = "workers", required = false) Integer workers) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (script == null || script.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "script file required");
        }

        String scriptContent;
        try {
            scriptContent = new String(script.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read script: " + e.getMessage());
        }

        DatalakeJobRequest body = new DatalakeJobRequest();
        body.setName(name != null && !name.isBlank() ? name : "pyscaler-" + Instant.now().getEpochSecond());
        body.setType(DatalakeJobType.RAY);
        body.setInlineScript(scriptContent);

        Map<String, String> env = new HashMap<>();
        if (input != null && !input.isBlank()) env.put("INPUT_PATH", input);
        if (output != null && !output.isBlank()) env.put("OUTPUT_PATH", output);
        body.setEnvVars(env);

        int w = workers != null && workers > 0 ? Math.min(workers, 32) : 2;
        Map<String, Object> head = new HashMap<>();
        head.put("cpu", "1");
        head.put("memory", "2Gi");
        body.setHead(head);
        Map<String, Object> workerSpec = new HashMap<>();
        workerSpec.put("replicas", w);
        workerSpec.put("cpu", "1");
        workerSpec.put("memory", "2Gi");
        body.setWorkers(workerSpec);

        DatalakeJobResponse created = service.submitJob(tenant.getId(), body);
        log.info("pyscaler job submitted: tenant={} job={} workers={}", tenant.getId(), created.getId(), w);
        return Map.of("job_id", created.getId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        DatalakeJobResponse job = service.getJob(tenant.getId(), id);

        Map<String, Object> out = new HashMap<>();
        out.put("job_id", job.getId());
        out.put("state", pyscalerState(job.getStatus()));
        out.put("duration", durationSeconds(job));
        Integer rc = null;
        if (job.getStatus() == DatalakeJobStatus.SUCCEEDED) rc = 0;
        else if (job.getStatus() == DatalakeJobStatus.FAILED || job.getStatus() == DatalakeJobStatus.CANCELLED) rc = 1;
        if (rc != null) out.put("returncode", rc);
        if (job.getErrorMessage() != null) out.put("error", job.getErrorMessage());
        return out;
    }

    @GetMapping("/{id}/logs")
    public Map<String, Object> logs(HttpServletRequest req,
                                    @PathVariable String id,
                                    @RequestParam(value = "tail", defaultValue = "100") int tail) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        List<String> lines = logService.tailLogs(tenant.getId(), id, tail);
        return Map.of("lines", lines);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static String pyscalerState(DatalakeJobStatus s) {
        return switch (s) {
            case PENDING, STARTING -> "pending";
            case RUNNING -> "running";
            case SUCCEEDED -> "succeeded";
            case FAILED, CANCELLED -> "failed";
        };
    }

    private static double durationSeconds(DatalakeJobResponse job) {
        if (job.getStartedAt() == null) return 0.0;
        Instant end = job.getFinishedAt() != null ? job.getFinishedAt() : Instant.now();
        return Math.max(0.0, (end.toEpochMilli() - job.getStartedAt().toEpochMilli()) / 1000.0);
    }
}
