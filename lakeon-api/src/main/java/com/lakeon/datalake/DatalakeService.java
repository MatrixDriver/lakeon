package com.lakeon.datalake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class DatalakeService {

    private static final Set<DatalakeJobStatus> TERMINAL_STATUSES = Set.of(
            DatalakeJobStatus.SUCCEEDED,
            DatalakeJobStatus.FAILED,
            DatalakeJobStatus.CANCELLED
    );

    private final DatalakeJobRepository repository;
    private final ObjectMapper objectMapper;

    public DatalakeService(DatalakeJobRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public DatalakeJobResponse submitJob(String tenantId, DatalakeJobRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.getType() == null) {
            throw new BadRequestException("type is required");
        }

        String spec;
        try {
            spec = objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize job spec", e);
        }

        DatalakeJobEntity entity = new DatalakeJobEntity();
        entity.setTenantId(tenantId);
        entity.setName(req.getName());
        entity.setType(req.getType());
        entity.setStatus(DatalakeJobStatus.PENDING);
        entity.setSpec(spec);

        entity = repository.save(entity);

        // Runners will be dispatched in Tasks 4-6; for now just return PENDING
        return DatalakeJobResponse.from(entity);
    }

    public DatalakeJobResponse getJob(String tenantId, String jobId) {
        DatalakeJobEntity entity = repository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return DatalakeJobResponse.from(entity);
    }

    public List<DatalakeJobResponse> listJobs(String tenantId, DatalakeJobStatus status) {
        List<DatalakeJobEntity> entities;
        if (status == null) {
            entities = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        } else {
            entities = repository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        }
        return entities.stream()
                .map(DatalakeJobResponse::from)
                .toList();
    }

    public void cancelJob(String tenantId, String jobId) {
        DatalakeJobEntity entity = repository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (TERMINAL_STATUSES.contains(entity.getStatus())) {
            throw new BadRequestException("Job is already in terminal state: " + entity.getStatus());
        }
        entity.setStatus(DatalakeJobStatus.CANCELLED);
        repository.save(entity);
        // Actual K8s resource cleanup will be added in Tasks 4-6
    }
}
