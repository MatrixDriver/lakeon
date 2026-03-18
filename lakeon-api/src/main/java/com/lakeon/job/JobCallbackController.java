package com.lakeon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
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

    public JobCallbackController(JobService jobService, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
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

        // Serialize result to JSON if it's a Map
        String resultJson = null;
        Object resultObj = body.get("result");
        if (resultObj instanceof Map) {
            try {
                resultJson = objectMapper.writeValueAsString(resultObj);
            } catch (Exception e) {
                resultJson = resultObj.toString();
            }
        } else if (resultObj instanceof String) {
            resultJson = (String) resultObj;
        }

        boolean ok = jobService.handleCallback(id, token, status, resultJson, error);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().build();
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
