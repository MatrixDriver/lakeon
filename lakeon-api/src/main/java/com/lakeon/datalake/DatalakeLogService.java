package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class DatalakeLogService {
    private static final Logger log = LoggerFactory.getLogger(DatalakeLogService.class);

    private final KubernetesClient k8sClient;
    private final DatalakeJobRepository repository;
    private final LakeonProperties props;

    public DatalakeLogService(KubernetesClient k8sClient, DatalakeJobRepository repository, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.repository = repository;
        this.props = props;
    }

    /**
     * Stream logs from a datalake job via SSE.
     * For PYTHON: streams from the single K8s Job Pod.
     * For RAY/FINETUNE: streams from Head Pod + Worker Pods with pod-name prefixes.
     *
     * Runs log streaming in a background virtual thread (SseEmitter with 30-minute timeout).
     */
    public SseEmitter streamLogs(String tenantId, String jobId) {
        DatalakeJobEntity job = repository.findById(jobId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));

        if (!tenantId.equals(job.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // 30-minute timeout for long-running jobs
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        Thread thread = new Thread(() -> {
            try {
                if (job.getType() == DatalakeJobType.PYTHON) {
                    streamPythonLogs(job, emitter);
                } else {
                    streamRayLogs(job, emitter);
                }
                emitter.complete();
            } catch (Exception e) {
                log.warn("Log streaming failed for job {}: {}", jobId, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        thread.setDaemon(true);
        thread.start();

        return emitter;
    }

    private void streamPythonLogs(DatalakeJobEntity job, SseEmitter emitter) throws Exception {
        if (job.getCciNamespace() == null || job.getK8sJobName() == null) {
            // Try OBS persisted logs
            if (streamObsLogs(job, emitter)) return;
            sendLine(emitter, "[no pod assigned yet]");
            return;
        }

        // Find the Pod created by the K8s Job
        List<Pod> pods = k8sClient.pods().inNamespace(job.getCciNamespace())
            .withLabel("job-name", job.getK8sJobName())
            .list().getItems();

        if (pods.isEmpty()) {
            // Pod gone — try OBS persisted logs
            if (streamObsLogs(job, emitter)) return;
            sendLine(emitter, "[pod not yet scheduled]");
            return;
        }

        // Stream logs from the first (only) pod
        Pod pod = pods.get(0);
        String podName = pod.getMetadata().getName();
        streamPodLogs(job.getCciNamespace(), podName, emitter);
    }

    private void streamRayLogs(DatalakeJobEntity job, SseEmitter emitter) throws Exception {
        if (job.getCciNamespace() == null || job.getRayJobName() == null) {
            sendLine(emitter, "[no Ray cluster assigned yet]");
            return;
        }

        // Find all pods in the Ray cluster labeled with the job ID
        List<Pod> pods = k8sClient.pods().inNamespace(job.getCciNamespace())
            .withLabel("lakeon.io/job-id", job.getId())
            .list().getItems();

        if (pods.isEmpty()) {
            if (streamObsLogs(job, emitter)) return;
            sendLine(emitter, "[Ray cluster pods not yet scheduled]");
            return;
        }

        // Stream logs from each pod sequentially (head first, then workers)
        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            sendLine(emitter, "=== Pod: " + podName + " ===");
            streamPodLogs(job.getCciNamespace(), podName, emitter);
        }
    }

    private void streamPodLogs(String namespace, String podName, SseEmitter emitter) throws Exception {
        try (LogWatch logWatch = k8sClient.pods().inNamespace(namespace).withName(podName).watchLog()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sendLine(emitter, line);
                }
            }
        }
    }

    /**
     * Stream logs from OBS (persisted after job completion).
     * Returns true if logs were found and streamed, false otherwise.
     */
    private boolean streamObsLogs(DatalakeJobEntity job, SseEmitter emitter) throws Exception {
        if (job.getLogObsPath() == null || job.getLogObsPath().isBlank()) return false;

        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();
        if (ak == null || ak.isBlank()) return false;

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk)))
                .region(Region.of(props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4"))
                .build()) {

            var response = s3.getObject(GetObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(job.getLogObsPath())
                    .build());

            String content = new String(response.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                sendLine(emitter, line);
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to read OBS logs for job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    private void sendLine(SseEmitter emitter, String line) throws Exception {
        emitter.send(SseEmitter.event().data(line));
    }
}
