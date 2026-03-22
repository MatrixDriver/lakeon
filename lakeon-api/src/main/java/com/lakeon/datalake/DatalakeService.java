package com.lakeon.datalake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetStatus;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final LakeonProperties properties;

    @Autowired(required = false)
    private DatasetRepository datasetRepository;

    @Autowired(required = false)
    private PythonJobRunner pythonJobRunner;

    @Autowired(required = false)
    private RayJobRunner rayJobRunner;

    @Autowired(required = false)
    private FinetuneJobRunner finetuneJobRunner;

    public DatalakeService(DatalakeJobRepository repository, ObjectMapper objectMapper, LakeonProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
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

        // Resolve input dataset: inject DATASET_PATH env var if input_dataset_id is provided
        if (req.getInputDatasetId() != null && datasetRepository != null) {
            DatasetEntity dataset = datasetRepository.findById(req.getInputDatasetId())
                    .orElseThrow(() -> new NotFoundException("Dataset not found: " + req.getInputDatasetId()));
            if (dataset.getStatus() != DatasetStatus.READY) {
                throw new BadRequestException("Dataset is not ready: " + req.getInputDatasetId()
                        + " (status=" + dataset.getStatus() + ")");
            }
            String bucket = properties.getObs().getBucket();
            String datasetPath = "s3://" + bucket + "/" + dataset.getObsPath();
            Map<String, String> envVars = req.getEnvVars() != null ? new HashMap<>(req.getEnvVars()) : new HashMap<>();
            envVars.put("DATASET_PATH", datasetPath);
            req.setEnvVars(envVars);
        }

        // TODO: output_dataset_name — create output DatasetEntity after job completes
        // This requires DatalakeStatusPoller integration (planned).

        try {
            if (req.getType() == DatalakeJobType.PYTHON && pythonJobRunner != null) {
                pythonJobRunner.start(entity, req);
            } else if (req.getType() == DatalakeJobType.RAY && rayJobRunner != null) {
                rayJobRunner.start(entity, req);
            } else if (req.getType() == DatalakeJobType.FINETUNE && finetuneJobRunner != null) {
                finetuneJobRunner.start(entity, req);
            }
        } catch (Exception e) {
            entity.setStatus(DatalakeJobStatus.FAILED);
            String errMsg = "Failed to start job: " + e.getMessage();
            if (errMsg.length() > 250) errMsg = errMsg.substring(0, 250);
            entity.setErrorMessage(errMsg);
            entity.setFinishedAt(java.time.Instant.now());
            repository.save(entity);
        }

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
        if (entity.getType() == DatalakeJobType.PYTHON
                && entity.getK8sJobName() != null
                && pythonJobRunner != null) {
            pythonJobRunner.cancel(entity);
            return;
        } else if (entity.getType() == DatalakeJobType.RAY
                && entity.getRayJobName() != null
                && rayJobRunner != null) {
            rayJobRunner.cancel(entity);
            return;
        } else if (entity.getType() == DatalakeJobType.FINETUNE
                && entity.getRayJobName() != null
                && finetuneJobRunner != null) {
            finetuneJobRunner.cancel(entity);
            return;
        }
        entity.setStatus(DatalakeJobStatus.CANCELLED);
        repository.save(entity);
    }
}
