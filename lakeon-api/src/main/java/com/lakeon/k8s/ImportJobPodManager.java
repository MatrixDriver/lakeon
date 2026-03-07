package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTableTaskEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ImportJobPodManager {
    private static final Logger log = LoggerFactory.getLogger(ImportJobPodManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;

    public ImportJobPodManager(KubernetesClient k8sClient, LakeonProperties props, ObjectMapper objectMapper) {
        this.k8sClient = k8sClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Launch an import Job Pod for the given task.
     * Creates a per-task Secret, ConfigMap, and Pod.
     * Returns the pod name.
     */
    public String launchJobPod(ImportTaskEntity task, List<ImportTableTaskEntity> tableTasks, DatabaseEntity targetDb) {
        String namespace = props.getK8s().getNamespace();
        String safePodId = task.getId().replace("_", "-");
        String podName = "import-" + safePodId;
        String configMapName = podName + "-config";
        String secretName = podName + "-secret";

        // Build task.json content
        String taskJson = buildTaskJson(task, tableTasks, targetDb);

        // Create per-task Secret with source DB password
        Secret secret = new SecretBuilder()
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-import",
                    "lakeon.io/task-id", task.getId()
                ))
            .endMetadata()
            .addToStringData("source-password", task.getSourcePassword() != null ? task.getSourcePassword() : "")
            .build();
        k8sClient.secrets().inNamespace(namespace).resource(secret).serverSideApply();
        log.info("Created import Secret: {}/{}", namespace, secretName);

        // Create per-task ConfigMap with task.json
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-import",
                    "lakeon.io/task-id", task.getId()
                ))
            .endMetadata()
            .addToData("task.json", taskJson)
            .build();
        k8sClient.configMaps().inNamespace(namespace).resource(configMap).serverSideApply();
        log.info("Created import ConfigMap: {}/{}", namespace, configMapName);

        // Create the import Pod
        Pod pod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                    "app", "lakeon-import",
                    "lakeon.io/task-id", task.getId()
                ))
            .endMetadata()
            .withNewSpec()
                .withImagePullSecrets(
                    props.getK8s().getImagePullSecrets().stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
                        .toList()
                )
                .withRestartPolicy("Never")
                .addNewContainer()
                    .withName("import")
                    .withImage(props.getK8s().getImportImage())
                    .withCommand("bash", "/scripts/import.sh")
                    .withNewResources()
                        .withRequests(Map.of(
                            "cpu", new Quantity("100m"),
                            "memory", new Quantity("256Mi")
                        ))
                        .withLimits(Map.of(
                            "cpu", new Quantity("500m"),
                            "memory", new Quantity("512Mi")
                        ))
                    .endResources()
                    .addNewVolumeMount()
                        .withName("scripts")
                        .withMountPath("/scripts")
                        .withReadOnly(true)
                    .endVolumeMount()
                    .addNewVolumeMount()
                        .withName("config")
                        .withMountPath("/config")
                        .withReadOnly(true)
                    .endVolumeMount()
                    .addNewVolumeMount()
                        .withName("secrets")
                        .withMountPath("/secrets")
                        .withReadOnly(true)
                    .endVolumeMount()
                .endContainer()
                .addNewVolume()
                    .withName("scripts")
                    .withNewConfigMap()
                        .withName("lakeon-import-script")
                    .endConfigMap()
                .endVolume()
                .addNewVolume()
                    .withName("config")
                    .withNewConfigMap()
                        .withName(configMapName)
                    .endConfigMap()
                .endVolume()
                .addNewVolume()
                    .withName("secrets")
                    .withNewSecret()
                        .withSecretName(secretName)
                    .endSecret()
                .endVolume()
            .endSpec()
            .build();

        k8sClient.pods().inNamespace(namespace).resource(pod).create();
        log.info("Created import Pod: {}/{}", namespace, podName);

        return podName;
    }

    /**
     * Delete the import Pod, ConfigMap, and Secret for the given task.
     */
    public void deleteJobPod(String taskId) {
        String namespace = props.getK8s().getNamespace();
        String safePodId = taskId.replace("_", "-");
        String podName = "import-" + safePodId;
        String configMapName = podName + "-config";
        String secretName = podName + "-secret";

        try {
            k8sClient.pods().inNamespace(namespace).withName(podName).delete();
            log.info("Deleted import Pod: {}/{}", namespace, podName);
        } catch (Exception e) {
            log.warn("Failed to delete import Pod {}: {}", podName, e.getMessage());
        }

        try {
            k8sClient.configMaps().inNamespace(namespace).withName(configMapName).delete();
            log.info("Deleted import ConfigMap: {}/{}", namespace, configMapName);
        } catch (Exception e) {
            log.warn("Failed to delete import ConfigMap {}: {}", configMapName, e.getMessage());
        }

        try {
            k8sClient.secrets().inNamespace(namespace).withName(secretName).delete();
            log.info("Deleted import Secret: {}/{}", namespace, secretName);
        } catch (Exception e) {
            log.warn("Failed to delete import Secret {}: {}", secretName, e.getMessage());
        }
    }

    private String buildTaskJson(ImportTaskEntity task, List<ImportTableTaskEntity> tableTasks, DatabaseEntity targetDb) {
        String callbackUrl = "http://lakeon-api.lakeon.svc.cluster.local:8080/api/v1/import/callback/" + task.getId();

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", task.getSourceHost());
        source.put("port", task.getSourcePort());
        source.put("dbname", task.getSourceDbname());
        source.put("user", task.getSourceUser());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("host", targetDb.getComputeHost());
        target.put("port", 55433);
        target.put("dbname", targetDb.getName());
        target.put("user", "cloud_admin");
        target.put("password", "cloud-admin-internal");

        List<Map<String, String>> tables = new ArrayList<>();
        for (ImportTableTaskEntity t : tableTasks) {
            tables.add(Map.of(
                "id", t.getId(),
                "schema", t.getSchemaName(),
                "table", t.getTableName()
            ));
        }

        Map<String, Object> taskConfig = new LinkedHashMap<>();
        taskConfig.put("callback_url", callbackUrl);
        taskConfig.put("source", source);
        taskConfig.put("target", target);
        taskConfig.put("conflict_strategy", task.getConflictStrategy().name());
        taskConfig.put("tables", tables);

        try {
            return objectMapper.writeValueAsString(taskConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build import task JSON", e);
        }
    }
}
