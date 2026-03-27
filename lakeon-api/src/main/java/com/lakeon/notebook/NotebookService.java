package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import com.lakeon.dataset.DatasetEntity;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetStatus;
import com.lakeon.datalake.DatalakeNamespaceManager;
import com.lakeon.obs.ObsStsService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class NotebookService {

    private static final Logger log = LoggerFactory.getLogger(NotebookService.class);
    private static final int IDLE_MINUTES = 30;

    private final NotebookSessionRepository sessionRepo;
    private final DatasetRepository datasetRepo;
    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final DatalakeNamespaceManager nsManager;
    private final ObsStsService obsStsService;

    public NotebookService(NotebookSessionRepository sessionRepo,
                           DatasetRepository datasetRepo,
                           KubernetesClient k8sClient,
                           LakeonProperties props,
                           DatalakeNamespaceManager nsManager,
                           ObsStsService obsStsService) {
        this.sessionRepo = sessionRepo;
        this.datasetRepo = datasetRepo;
        this.k8sClient = k8sClient;
        this.props = props;
        this.nsManager = nsManager;
        this.obsStsService = obsStsService;
    }

    /**
     * Returns an existing RUNNING or STARTING session for the tenant, or creates a new one.
     * Max 1 active session per tenant.
     */
    public NotebookSessionEntity getOrCreateSession(String tenantId, String imageKey, List<String> datasetIds) {
        // Check for existing active session (RUNNING or STARTING)
        Optional<NotebookSessionEntity> running = sessionRepo.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.RUNNING);
        if (running.isPresent()) {
            return running.get();
        }
        Optional<NotebookSessionEntity> starting = sessionRepo.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.STARTING);
        if (starting.isPresent()) {
            return starting.get();
        }

        // Create new session entity
        NotebookSessionEntity session = new NotebookSessionEntity();
        session.setTenantId(tenantId);
        session.setStatus(NotebookSessionStatus.STARTING);
        if (datasetIds != null && !datasetIds.isEmpty()) {
            session.setDatasetIds(String.join(",", datasetIds));
        }

        // Resolve image
        LakeonProperties.DatalakeConfig dl = props.getDatalake();
        String resolvedImageKey = (imageKey != null && !imageKey.isBlank()) ? imageKey : "python-data";
        String image = dl.getPresetImages().getOrDefault(resolvedImageKey,
                dl.getPresetImages().getOrDefault("python-data", "python:3.11-slim"));
        session.setImage(image);

        // Save to get generated ID
        session = sessionRepo.save(session);

        // Derive pod name
        String podName = "notebook-" + session.getId().replace("_", "-");
        String ns = nsManager.ensureNamespace(tenantId);
        session.setPodName(podName);
        session.setNamespace(ns);
        session = sessionRepo.save(session);

        // Create the pod
        try {
            createNotebookPod(session, tenantId, datasetIds, image, podName, ns);
            session.setStatus(NotebookSessionStatus.RUNNING);
            session = sessionRepo.save(session);
        } catch (Exception e) {
            log.error("Failed to create notebook pod for tenant {}: {}", tenantId, e.getMessage());
            session.setStatus(NotebookSessionStatus.STOPPED);
            session = sessionRepo.save(session);
        }

        return session;
    }

    /**
     * Finds the active (RUNNING or STARTING) session for the tenant.
     */
    public Optional<NotebookSessionEntity> getSession(String tenantId) {
        Optional<NotebookSessionEntity> running = sessionRepo.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.RUNNING);
        if (running.isPresent()) return running;
        return sessionRepo.findByTenantIdAndStatus(tenantId, NotebookSessionStatus.STARTING);
    }

    /**
     * Stops the session: deletes pod + configmap, sets status to STOPPED.
     */
    public void stopSession(String tenantId, String sessionId) {
        NotebookSessionEntity session = sessionRepo.findById(sessionId)
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Session not found: " + sessionId));

        if (session.getStatus() == NotebookSessionStatus.STOPPED) {
            return;
        }

        deletePodAndConfigMap(session);
        session.setStatus(NotebookSessionStatus.STOPPED);
        sessionRepo.save(session);
        log.info("Stopped notebook session {} for tenant {}", sessionId, tenantId);
    }

    /**
     * Updates lastActiveAt for the session to prevent idle reaping.
     */
    public void touchSession(String sessionId) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setLastActiveAt(Instant.now());
            sessionRepo.save(s);
        });
    }

    /**
     * Scheduled task: stop sessions idle for more than 30 minutes.
     */
    @Scheduled(fixedDelay = 60_000)
    public void reapIdleSessions() {
        Instant cutoff = Instant.now().minusSeconds(IDLE_MINUTES * 60L);

        List<NotebookSessionEntity> idleRunning = sessionRepo.findByStatusAndLastActiveAtBefore(
                NotebookSessionStatus.RUNNING, cutoff);
        List<NotebookSessionEntity> idleStarting = sessionRepo.findByStatusAndLastActiveAtBefore(
                NotebookSessionStatus.STARTING, cutoff);

        List<NotebookSessionEntity> idle = new ArrayList<>();
        idle.addAll(idleRunning);
        idle.addAll(idleStarting);

        for (NotebookSessionEntity session : idle) {
            try {
                log.info("Reaping idle notebook session {} (tenant={}, lastActive={})",
                        session.getId(), session.getTenantId(), session.getLastActiveAt());
                deletePodAndConfigMap(session);
                session.setStatus(NotebookSessionStatus.STOPPED);
                sessionRepo.save(session);
            } catch (Exception e) {
                log.warn("Failed to reap session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void createNotebookPod(NotebookSessionEntity session,
                                   String tenantId,
                                   List<String> datasetIds,
                                   String image,
                                   String podName,
                                   String ns) {
        LakeonProperties.DatalakeConfig dl = props.getDatalake();

        // Load repl_server.py from classpath
        String replScript = loadReplServerScript();

        // Create ConfigMap with repl_server.py
        String cmName = podName + "-repl";
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(ns)
                .endMetadata()
                .addToData("repl_server.py", replScript)
                .build();
        k8sClient.configMaps().inNamespace(ns).resource(cm).createOrReplace();
        log.info("Created repl ConfigMap: {}/{}", ns, cmName);

        // Build env vars
        List<EnvVar> envVars = new ArrayList<>();

        // OBS STS credentials
        ObsStsService.StsCredentials stsCreds;
        try {
            stsCreds = obsStsService.getCredentials(tenantId);
            envVars.add(new EnvVarBuilder().withName("OBS_ACCESS_KEY_ID").withValue(stsCreds.accessKey()).build());
            envVars.add(new EnvVarBuilder().withName("OBS_SECRET_ACCESS_KEY").withValue(stsCreds.secretKey()).build());
            envVars.add(new EnvVarBuilder().withName("OBS_SECURITY_TOKEN").withValue(stsCreds.sessionToken()).build());
        } catch (Exception e) {
            log.warn("Could not fetch STS credentials for tenant {}: {}", tenantId, e.getMessage());
            envVars.add(new EnvVarBuilder().withName("OBS_ACCESS_KEY_ID").withValue("").build());
            envVars.add(new EnvVarBuilder().withName("OBS_SECRET_ACCESS_KEY").withValue("").build());
            envVars.add(new EnvVarBuilder().withName("OBS_SECURITY_TOKEN").withValue("").build());
        }

        LakeonProperties.ObsConfig obsConf = props.getObs();
        envVars.add(new EnvVarBuilder().withName("OBS_ENDPOINT").withValue(
                obsConf.getEndpoint() != null ? obsConf.getEndpoint() : "").build());
        envVars.add(new EnvVarBuilder().withName("OBS_BUCKET").withValue(
                obsConf.getBucket() != null ? obsConf.getBucket() : "").build());
        envVars.add(new EnvVarBuilder().withName("OBS_REGION").withValue(
                obsConf.getRegion() != null ? obsConf.getRegion() : "cn-north-4").build());

        // Dataset path env vars
        if (datasetIds != null) {
            for (int i = 0; i < datasetIds.size(); i++) {
                String dsId = datasetIds.get(i);
                Optional<DatasetEntity> dsOpt = datasetRepo.findByIdAndTenantId(dsId, tenantId);
                if (dsOpt.isPresent() && dsOpt.get().getStatus() == DatasetStatus.READY) {
                    String obsPath = dsOpt.get().getObsPath();
                    String dsName = dsOpt.get().getName();
                    envVars.add(new EnvVarBuilder()
                            .withName("DATASET_PATH_" + i)
                            .withValue(obsPath != null ? obsPath : "")
                            .build());
                    envVars.add(new EnvVarBuilder()
                            .withName("DATASET_NAME_" + i)
                            .withValue(dsName != null ? dsName : dsId)
                            .build());
                }
            }
        }

        // Toleration for VK
        Toleration vkToleration = new TolerationBuilder()
                .withKey("virtual-kubelet.io/provider")
                .withOperator("Exists")
                .withEffect("NoSchedule")
                .build();

        // Container
        Container container = new ContainerBuilder()
                .withName("repl")
                .withImage(image)
                .withCommand("python", "/app/repl_server.py")
                .withStdin(true)
                .withStdinOnce(false)
                .withTty(false)
                .withEnv(envVars)
                .withNewResources()
                    .withRequests(Map.of(
                            "cpu", new Quantity("500m"),
                            "memory", new Quantity("2Gi")))
                    .withLimits(Map.of(
                            "cpu", new Quantity("2"),
                            "memory", new Quantity("4Gi")))
                .endResources()
                .withVolumeMounts(new VolumeMountBuilder()
                        .withName("repl-vol")
                        .withMountPath("/app/repl_server.py")
                        .withSubPath("repl_server.py")
                        .withReadOnly(true)
                        .build())
                .build();

        // Pod spec
        List<LocalObjectReference> pullSecrets = props.getK8s().getImagePullSecrets().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
                .toList();

        PodSpec podSpec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withImagePullSecrets(pullSecrets)
                .withNodeSelector(Map.of(dl.getVkNodeSelectorKey(), dl.getVkNodeSelectorValue()))
                .withTolerations(vkToleration)
                .withContainers(container)
                .withVolumes(new VolumeBuilder()
                        .withName("repl-vol")
                        .withNewConfigMap()
                            .withName(cmName)
                        .endConfigMap()
                        .build())
                .build();

        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(ns)
                    .withLabels(Map.of(
                            "app", "notebook",
                            "lakeon.io/tenant-id", tenantId,
                            "lakeon.io/session-id", session.getId()))
                .endMetadata()
                .withSpec(podSpec)
                .build();

        k8sClient.pods().inNamespace(ns).resource(pod).create();
        log.info("Created notebook pod: {}/{}", ns, podName);
    }

    private void deletePodAndConfigMap(NotebookSessionEntity session) {
        String ns = session.getNamespace();
        String podName = session.getPodName();

        if (ns == null || podName == null) return;

        try {
            k8sClient.pods().inNamespace(ns).withName(podName).delete();
            log.info("Deleted notebook pod: {}/{}", ns, podName);
        } catch (Exception e) {
            log.warn("Failed to delete notebook pod {}/{}: {}", ns, podName, e.getMessage());
        }

        try {
            String cmName = podName + "-repl";
            k8sClient.configMaps().inNamespace(ns).withName(cmName).delete();
            log.info("Deleted notebook ConfigMap: {}/{}", ns, cmName);
        } catch (Exception e) {
            log.warn("Failed to delete notebook ConfigMap for {}: {}", podName, e.getMessage());
        }
    }

    private String loadReplServerScript() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("repl_server.py")) {
            if (is == null) {
                log.warn("repl_server.py not found in classpath, using empty script");
                return "# repl_server.py not found\n";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load repl_server.py from classpath", e);
            return "# Failed to load repl_server.py\n";
        }
    }
}
