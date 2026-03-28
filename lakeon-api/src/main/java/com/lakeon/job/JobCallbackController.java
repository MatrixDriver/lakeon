package com.lakeon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.knowledge.KbWriteQueue;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobCallbackController {

    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final KbWriteQueue kbWriteQueue;

    public JobCallbackController(JobService jobService, ObjectMapper objectMapper,
                                  @Lazy KbWriteQueue kbWriteQueue) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.kbWriteQueue = kbWriteQueue;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitJob(HttpServletRequest req,
                                         @RequestBody Map<String, Object> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        String typeStr = (String) body.get("type");
        JobType type = JobType.valueOf(typeStr);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");

        JobEntity job = jobService.submitJob(tenant, type, params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", job.getId());
        result.put("type", job.getType());
        result.put("status", job.getStatus());
        result.put("createdAt", job.getCreatedAt());
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getJob(HttpServletRequest req,
                                                       @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        try {
            JobEntity job = jobService.getJob(tenant.getId(), id);
            return ResponseEntity.ok(jobToMap(job));
        } catch (com.lakeon.service.exception.NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping
    public List<Map<String, Object>> listJobs(HttpServletRequest req,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String status) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        JobType jobType = type != null ? JobType.valueOf(type) : null;
        JobStatus jobStatus = status != null ? JobStatus.valueOf(status) : null;

        return jobService.listJobs(tenant.getId(), jobType, jobStatus)
                .stream()
                .map(this::jobToMap)
                .collect(Collectors.toList());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(HttpServletRequest req,
                                                          @PathVariable String id) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        try {
            JobEntity job = jobService.cancelJob(tenant.getId(), id);
            return ResponseEntity.ok(jobToMap(job));
        } catch (com.lakeon.service.exception.NotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/callback")
    public ResponseEntity<Void> handleCallback(@PathVariable String id,
                                                @RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        String status = (String) body.get("status");
        String error = (String) body.get("error");
        String errorCategory = (String) body.get("error_category");
        String failedStage = body.get("failed_stage") != null ? body.get("failed_stage").toString() : null;

        // Serialize result to JSON, injecting error_category and failed_stage so
        // KbWriteQueue.parseErrorCategory() can find them inside resultJson.
        String resultJson = null;
        Object resultObj = body.get("result");
        if (resultObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) resultObj;
            if (errorCategory != null) {
                resultMap.put("error_category", errorCategory);
            }
            if (failedStage != null) {
                resultMap.put("failed_stage", failedStage);
            }
            try {
                resultJson = objectMapper.writeValueAsString(resultMap);
            } catch (Exception e) {
                resultJson = resultObj.toString();
            }
        } else if (resultObj instanceof String) {
            resultJson = (String) resultObj;
        } else if (errorCategory != null || failedStage != null) {
            // No result map but has error metadata — create minimal result JSON
            Map<String, Object> metaMap = new java.util.LinkedHashMap<>();
            if (errorCategory != null) metaMap.put("error_category", errorCategory);
            if (failedStage != null) metaMap.put("failed_stage", failedStage);
            try {
                resultJson = objectMapper.writeValueAsString(metaMap);
            } catch (Exception e) {
                resultJson = metaMap.toString();
            }
        }

        boolean ok = jobService.handleCallback(id, token, status, resultJson, error);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Get a fresh database connstr for a job pod.
     * Wakes the compute pod and returns the latest connection string.
     * Authenticated by job callback token (same as callback endpoint).
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/{id}/connstr")
    public ResponseEntity<?> getConnstr(@PathVariable String id, @RequestParam String token) {
        JobEntity job = jobService.findById(id);
        if (job == null || !token.equals(job.getCallbackToken())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            // Extract database_id from job params
            Map<String, Object> params = objectMapper.readValue(job.getParams(), Map.class);
            String databaseId = (String) params.get("database_id");
            if (databaseId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No database_id in job params"));
            }
            String connstr = kbWriteQueue.wakeAndGetConnstr(databaseId);
            return ResponseEntity.ok(Map.of("connstr", connstr));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Failed to wake compute: " + e.getMessage()));
        }
    }

    private Map<String, Object> jobToMap(JobEntity job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId());
        map.put("type", job.getType());
        map.put("status", job.getStatus());
        if (job.getParams() != null) map.put("params", job.getParams());
        if (job.getResult() != null) map.put("result", job.getResult());
        if (job.getError() != null) map.put("error", job.getError());
        map.put("createdAt", job.getCreatedAt());
        if (job.getStartedAt() != null) map.put("startedAt", job.getStartedAt());
        if (job.getCompletedAt() != null) map.put("completedAt", job.getCompletedAt());
        return map;
    }
}
