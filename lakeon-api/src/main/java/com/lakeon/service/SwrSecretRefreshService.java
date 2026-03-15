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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Periodically refreshes the SWR docker-registry secret in the compute namespace.
 * SWR temporary tokens expire every ~24 hours; this service refreshes every 12 hours.
 */
@Service
public class SwrSecretRefreshService {

    private static final Logger log = LoggerFactory.getLogger(SwrSecretRefreshService.class);

    private static final String SWR_HOST = "swr.cn-north-4.myhuaweicloud.com";
    private static final String SWR_REGION = "cn-north-4";
    private static final String SECRET_NAME = "swr-secret";
    private static final String SA_NAME = "default";

    private static final DateTimeFormatter ISO_BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("yyyyMMdd");

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
        } catch (Exception e) {
            log.error("SwrSecretRefreshService: error during SWR secret refresh", e);
        }
    }

    /**
     * Call SWR API to obtain a docker login token (docker config JSON).
     */
    private String fetchSwrDockerConfig(String ak, String sk) {
        try {
            Instant now = Instant.now();
            String dateStamp = ISO_BASIC.format(now.atOffset(ZoneOffset.UTC));
            String dateShort = DATE_SHORT.format(now.atOffset(ZoneOffset.UTC));

            String uri = "/v2/manage/utils/secret";
            String body = "{}";

            String signedHeaders = "host;x-sdk-date";
            String canonicalHeaders = "host:" + SWR_HOST + "\n" + "x-sdk-date:" + dateStamp + "\n";
            String payloadHash = sha256Hex(body);

            String canonicalRequest = String.join("\n",
                    "POST", uri, "", canonicalHeaders, signedHeaders, payloadHash);

            String credentialScope = dateShort + "/" + SWR_REGION + "/swr/sdk_request";
            String stringToSign = String.join("\n",
                    "SDK-HMAC-SHA256", dateStamp, credentialScope,
                    sha256Hex(canonicalRequest));

            byte[] kDate = hmacSha256(("SDK" + sk).getBytes(StandardCharsets.UTF_8), dateShort);
            byte[] kRegion = hmacSha256(kDate, SWR_REGION);
            byte[] kService = hmacSha256(kRegion, "swr");
            byte[] kSigning = hmacSha256(kService, "sdk_request");
            String signature = hexEncode(hmacSha256(kSigning, stringToSign));

            String authorization = String.format(
                    "SDK-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s",
                    ak, credentialScope, signedHeaders, signature);

            String url = "https://" + SWR_HOST + uri;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Host", SWR_HOST)
                    .header("X-Sdk-Date", dateStamp)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("SwrSecretRefreshService: SWR API response HTTP {}", response.statusCode());

            if (response.statusCode() == 200) {
                return response.body();
            } else {
                log.error("SwrSecretRefreshService: SWR API error HTTP {} - {}",
                        response.statusCode(), response.body());
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

    // --- Signing helpers (same pattern as CbcBillingService) ---

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hexEncode(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
