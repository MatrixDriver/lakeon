package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Periodically refreshes the SWR docker-registry secret in the compute namespace.
 * SWR temporary tokens expire every ~24 hours; this service refreshes every 12 hours.
 */
@Service
public class SwrSecretRefreshService {

    private static final Logger log = LoggerFactory.getLogger(SwrSecretRefreshService.class);

    private static final String IAM_HOST = "iam.cn-north-4.myhuaweicloud.com";
    private static final String SWR_API_HOST = "swr-api.cn-north-4.myhuaweicloud.com";
    private static final String SECRET_NAME = "swr-secret";
    private static final String SA_NAME = "default";

    private final LakeonProperties props;
    private final KubernetesClient k8sClient;
    private final HttpClient httpClient;

    public SwrSecretRefreshService(LakeonProperties props, KubernetesClient k8sClient) {
        this.props = props;
        this.k8sClient = k8sClient;
        this.httpClient = HttpClient.newHttpClient();
    }

    @PostConstruct
    public void initRefresh() {
        log.info("SwrSecretRefreshService: performing initial SWR secret refresh at startup");
        refreshSwrSecret();
    }

    @Scheduled(fixedRate = 43200000) // every 12 hours
    public void scheduledRefresh() {
        log.info("SwrSecretRefreshService: performing scheduled SWR secret refresh");
        refreshSwrSecret();
    }

    private void refreshSwrSecret() {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();

        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            log.debug("SwrSecretRefreshService: AK/SK not configured (local dev), skipping SWR secret refresh");
            return;
        }

        String namespace = props.getK8s().getNamespace();
        if (namespace == null || namespace.isBlank()) {
            log.warn("SwrSecretRefreshService: k8s.namespace not configured, skipping SWR secret refresh");
            return;
        }

        try {
            String dockerConfigJson = fetchSwrDockerConfig(ak, sk);
            if (dockerConfigJson == null) {
                log.error("SwrSecretRefreshService: failed to fetch SWR docker config token");
                return;
            }

            // Refresh in compute namespace
            updateK8sSecret(namespace, dockerConfigJson);
            patchServiceAccountImagePullSecrets(namespace);
            log.info("SwrSecretRefreshService: refreshed SWR secret in namespace '{}'", namespace);

            // Also refresh in control plane namespace (lakeon) for API/Neon pod image pulls
            String controlNs = "lakeon";
            if (!controlNs.equals(namespace)) {
                try {
                    updateK8sSecret(controlNs, dockerConfigJson);
                    log.info("SwrSecretRefreshService: refreshed SWR secret in namespace '{}'", controlNs);
                } catch (Exception e) {
                    log.warn("SwrSecretRefreshService: failed to refresh in '{}': {}", controlNs, e.getMessage());
                }
            }

            // Refresh in all datalake-* namespaces (per-tenant namespaces for datalake jobs)
            try {
                k8sClient.namespaces().list().getItems().stream()
                        .map(ns -> ns.getMetadata().getName())
                        .filter(name -> name.startsWith("datalake-"))
                        .forEach(dlNs -> {
                            try {
                                updateK8sSecret(dlNs, dockerConfigJson);
                                patchServiceAccountImagePullSecrets(dlNs);
                                log.debug("SwrSecretRefreshService: refreshed SWR secret in datalake namespace '{}'", dlNs);
                            } catch (Exception e) {
                                log.warn("SwrSecretRefreshService: failed to refresh in '{}': {}", dlNs, e.getMessage());
                            }
                        });
                log.info("SwrSecretRefreshService: refreshed SWR secret in all datalake-* namespaces");
            } catch (Exception e) {
                log.warn("SwrSecretRefreshService: failed to list/refresh datalake namespaces: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("SwrSecretRefreshService: error during SWR secret refresh", e);
        }
    }

    /**
     * Call SWR API to obtain a docker login token (docker config JSON).
     * Step 1: Get IAM token using AK/SK
     * Step 2: Call SWR API with IAM token
     */
    private String fetchSwrDockerConfig(String ak, String sk) {
        try {
            // Step 1: Get IAM token
            String iamBody = String.format(
                    "{\"auth\":{\"identity\":{\"methods\":[\"hw_ak_sk\"]," +
                    "\"hw_ak_sk\":{\"access\":{\"key\":\"%s\"},\"secret\":{\"key\":\"%s\"}}}," +
                    "\"scope\":{\"project\":{\"name\":\"cn-north-4\"}}}}", ak, sk);

            HttpRequest iamRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + IAM_HOST + "/v3/auth/tokens"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(iamBody))
                    .build();

            HttpResponse<String> iamResponse = httpClient.send(iamRequest, HttpResponse.BodyHandlers.ofString());
            if (iamResponse.statusCode() != 201) {
                log.error("SwrSecretRefreshService: IAM token request failed HTTP {} - {}",
                        iamResponse.statusCode(), iamResponse.body());
                return null;
            }

            String iamToken = iamResponse.headers().firstValue("x-subject-token").orElse(null);
            if (iamToken == null || iamToken.isBlank()) {
                log.error("SwrSecretRefreshService: IAM response missing x-subject-token header");
                return null;
            }
            log.info("SwrSecretRefreshService: obtained IAM token (length={})", iamToken.length());

            // Step 2: Call SWR API with IAM token
            HttpRequest swrRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + SWR_API_HOST + "/v2/manage/utils/secret"))
                    .header("Content-Type", "application/json")
                    .header("X-Auth-Token", iamToken)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> swrResponse = httpClient.send(swrRequest, HttpResponse.BodyHandlers.ofString());
            log.info("SwrSecretRefreshService: SWR API response HTTP {}", swrResponse.statusCode());

            if (swrResponse.statusCode() == 200) {
                return swrResponse.body();
            } else {
                log.error("SwrSecretRefreshService: SWR API error HTTP {} - {}",
                        swrResponse.statusCode(), swrResponse.body());
                return null;
            }
        } catch (Exception e) {
            log.error("SwrSecretRefreshService: SWR API call failed", e);
            return null;
        }
    }

    /**
     * Delete and recreate the swr-secret in the given namespace.
     */
    private void updateK8sSecret(String namespace, String dockerConfigJson) {
        String encoded = Base64.getEncoder().encodeToString(
                dockerConfigJson.getBytes(StandardCharsets.UTF_8));

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(SECRET_NAME)
                    .withNamespace(namespace)
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .addToData(".dockerconfigjson", encoded)
                .build();

        // Delete existing secret if present
        k8sClient.secrets().inNamespace(namespace).withName(SECRET_NAME).delete();

        // Create new secret
        k8sClient.secrets().inNamespace(namespace).resource(secret).create();
        log.debug("SwrSecretRefreshService: secret '{}' created/updated in namespace '{}'",
                SECRET_NAME, namespace);
    }

    /**
     * Ensure the default service account in the namespace has swr-secret as an imagePullSecret.
     */
    private void patchServiceAccountImagePullSecrets(String namespace) {
        try {
            k8sClient.serviceAccounts()
                    .inNamespace(namespace)
                    .withName(SA_NAME)
                    .edit(sa -> new ServiceAccountBuilder(sa)
                            .editOrNewMetadata()
                                .withName(SA_NAME)
                                .withNamespace(namespace)
                            .endMetadata()
                            .withImagePullSecrets(
                                    new io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder()
                                            .withName(SECRET_NAME)
                                            .build())
                            .build());
            log.debug("SwrSecretRefreshService: patched service account '{}' imagePullSecrets in namespace '{}'",
                    SA_NAME, namespace);
        } catch (Exception e) {
            log.warn("SwrSecretRefreshService: failed to patch service account imagePullSecrets in namespace '{}': {}",
                    namespace, e.getMessage());
        }
    }

}
