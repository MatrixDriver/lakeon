package com.lakeon.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.datalake.DatalakeNamespaceManager;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PipelineRunService {
    private static final Logger log = LoggerFactory.getLogger(PipelineRunService.class);

    private final PipelineRunRepository runRepository;
    private final PipelineStepRunRepository stepRunRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;
    private final DatalakeNamespaceManager nsManager;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${lakeon.orchestrator.url:http://pipeline-orchestrator:8090}")
    private String orchestratorUrl;

    public PipelineRunService(PipelineRunRepository runRepository,
                               PipelineStepRunRepository stepRunRepository,
                               PipelineRepository pipelineRepository,
                               PipelineVersionRepository pipelineVersionRepository,
                               DatalakeNamespaceManager nsManager,
                               ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.stepRunRepository = stepRunRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.nsManager = nsManager;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Transactional
    public PipelineRunEntity trigger(String tenantId, String pipelineId, int version,
                                      String inputDatasetId, Integer inputDatasetVersion) {
        // Verify pipeline exists
        PipelineEntity pipeline = pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)
                .orElseThrow(() -> new NotFoundException("Pipeline not found: " + pipelineId));

        // Verify version exists
        PipelineVersionEntity pipelineVersion = pipelineVersionRepository
                .findByPipelineIdAndVersion(pipelineId, version)
                .orElseThrow(() -> new NotFoundException(
                        "Pipeline version not found: " + pipelineId + " v" + version));

        // Create run
        PipelineRunEntity run = new PipelineRunEntity();
        run.setTenantId(tenantId);
        run.setPipelineId(pipelineId);
        run.setPipelineVersion(version);
        run.setInputDatasetId(inputDatasetId);
        run.setInputDatasetVersion(inputDatasetVersion);
        run.setStatus(PipelineRunStatus.PENDING);
        runRepository.save(run);

        // Parse dag_yaml and create step runs
        String dagYaml = pipelineVersion.getDagYaml();
        if (dagYaml != null && !dagYaml.isBlank()) {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> dag = yaml.load(dagYaml);
            if (dag != null && dag.containsKey("steps")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> steps = (List<Map<String, Object>>) dag.get("steps");
                if (steps != null) {
                    for (Map<String, Object> step : steps) {
                        PipelineStepRunEntity sr = new PipelineStepRunEntity();
                        sr.setRunId(run.getId());
                        sr.setStepId((String) step.get("id"));
                        sr.setComponentId((String) step.get("component"));
                        // handle component_version (may be Integer or Number)
                        Object cvObj = step.get("component_version");
                        if (cvObj instanceof Number) {
                            sr.setComponentVersion(((Number) cvObj).intValue());
                        }
                        sr.setStatus(PipelineStepRunStatus.PENDING);
                        stepRunRepository.save(sr);
                    }
                }
            }
        }

        log.info("Triggered pipeline run {} for pipeline {} v{} tenant {}",
                run.getId(), pipelineId, version, tenantId);

        // Ensure tenant's datalake namespace exists before orchestrator submits jobs
        try {
            nsManager.ensureNamespace(tenantId);
        } catch (Exception e) {
            log.warn("Failed to ensure namespace for tenant {}: {}", tenantId, e.getMessage());
        }

        // Notify orchestrator to start execution (async, fire-and-forget)
        notifyOrchestrator(run.getId(), pipelineId, version, tenantId,
                inputDatasetId, inputDatasetVersion);

        return run;
    }

    private void notifyOrchestrator(String runId, String pipelineId, int version,
                                     String tenantId, String inputDatasetId,
                                     Integer inputDatasetVersion) {
        new Thread(() -> {
            try {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("run_id", runId);
                body.put("pipeline_id", pipelineId);
                body.put("pipeline_version", version);
                body.put("tenant_id", tenantId);
                if (inputDatasetId != null) {
                    body.put("input_dataset_id", inputDatasetId);
                }
                if (inputDatasetVersion != null) {
                    body.put("input_dataset_version", inputDatasetVersion);
                }

                String json = objectMapper.writeValueAsString(body);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(orchestratorUrl + "/runs"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Orchestrator accepted run {}: {}", runId, response.statusCode());
                } else {
                    log.error("Orchestrator rejected run {}: {} {}",
                            runId, response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.error("Failed to notify orchestrator for run {}: {}", runId, e.getMessage());
            }
        }, "orch-notify-" + runId).start();
    }

    public PipelineRunEntity get(String tenantId, String runId) {
        return runRepository.findByIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> new NotFoundException("Pipeline run not found: " + runId));
    }

    public List<PipelineRunEntity> listByPipeline(String pipelineId) {
        return runRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
    }

    public List<PipelineStepRunEntity> listStepRuns(String runId) {
        return stepRunRepository.findByRunIdOrderByCreatedAtAsc(runId);
    }

    @Transactional
    public PipelineRunEntity cancel(String tenantId, String runId) {
        PipelineRunEntity run = get(tenantId, runId);
        if (run.getStatus() == PipelineRunStatus.SUCCEEDED
                || run.getStatus() == PipelineRunStatus.CANCELLED) {
            throw new BadRequestException(
                    "Cannot cancel run in status: " + run.getStatus());
        }
        run.setStatus(PipelineRunStatus.CANCELLED);
        run.setFinishedAt(Instant.now());
        runRepository.save(run);
        log.info("Cancelled pipeline run {} for tenant {}", runId, tenantId);
        return run;
    }
}
