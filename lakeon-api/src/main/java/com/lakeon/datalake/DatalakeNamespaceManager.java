package com.lakeon.datalake;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.networking.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Manages per-tenant datalake namespaces.
 * Ensures namespace exists and is configured with:
 * - swr-secret (image pull secret)
 * - NetworkPolicy (tenant isolation)
 * - ResourceQuota (per-tenant resource limits)
 */
@Component
public class DatalakeNamespaceManager {

    private static final Logger log = LoggerFactory.getLogger(DatalakeNamespaceManager.class);

    private final KubernetesClient k8sClient;
    private final LakeonProperties props;

    public DatalakeNamespaceManager(KubernetesClient k8sClient, LakeonProperties props) {
        this.k8sClient = k8sClient;
        this.props = props;
    }

    /**
     * Ensures the datalake namespace for the given tenant exists and is fully configured.
     *
     * @param tenantId the tenant identifier
     * @return the namespace name
     */
    public String ensureNamespace(String tenantId) {
        String ns = props.getDatalake().getCciNamespacePrefix() + tenantId.replace("_", "-");

        // If namespace already exists, skip creation
        if (k8sClient.namespaces().withName(ns).get() != null) {
            return ns;
        }

        // 1. Create namespace
        k8sClient.namespaces().resource(
            new NamespaceBuilder()
                .withNewMetadata()
                    .withName(ns)
                    .addToLabels("app", "datalake")
                    .addToLabels("lakeon.io/tenant-id", tenantId)
                .endMetadata()
                .build()
        ).create();
        log.info("Created namespace: {}", ns);

        // 2. Copy swr-secret from source namespace
        String sourceNs = props.getK8s().getNamespace();
        try {
            var srcSecret = k8sClient.secrets().inNamespace(sourceNs).withName("swr-secret").get();
            if (srcSecret != null) {
                var newSecret = new SecretBuilder()
                    .withNewMetadata().withName("swr-secret").withNamespace(ns).endMetadata()
                    .withType(srcSecret.getType())
                    .withData(srcSecret.getData())
                    .build();
                k8sClient.secrets().inNamespace(ns).resource(newSecret).createOrReplace();
                // Patch default SA with imagePullSecrets
                k8sClient.serviceAccounts().inNamespace(ns).withName("default")
                    .edit(sa -> new ServiceAccountBuilder(sa)
                        .withImagePullSecrets(
                            new LocalObjectReferenceBuilder().withName("swr-secret").build())
                        .build());
                log.info("Copied swr-secret to namespace: {}", ns);
            }
        } catch (Exception e) {
            log.warn("Failed to copy swr-secret to {}: {}", ns, e.getMessage());
        }

        // 3. Create NetworkPolicy for tenant isolation
        try {
            NetworkPolicy networkPolicy = new NetworkPolicyBuilder()
                .withNewMetadata()
                    .withName("tenant-isolation")
                    .withNamespace(ns)
                .endMetadata()
                .withNewSpec()
                    .withPodSelector(new LabelSelectorBuilder().build())
                    .withPolicyTypes(List.of("Ingress", "Egress"))
                    .withIngress(new NetworkPolicyIngressRuleBuilder()
                        .withFrom(new NetworkPolicyPeerBuilder()
                            .withPodSelector(new LabelSelectorBuilder().build())
                            .build())
                        .build())
                    .withEgress(new NetworkPolicyEgressRuleBuilder().build())
                .endSpec()
                .build();
            k8sClient.network().networkPolicies().inNamespace(ns).resource(networkPolicy).create();
            log.info("Created NetworkPolicy tenant-isolation in namespace: {}", ns);
        } catch (Exception e) {
            log.warn("Failed to create NetworkPolicy in {}: {}", ns, e.getMessage());
        }

        // 4. Create ResourceQuota for tenant resource limits
        try {
            ResourceQuota resourceQuota = new ResourceQuotaBuilder()
                .withNewMetadata()
                    .withName("tenant-quota")
                    .withNamespace(ns)
                .endMetadata()
                .withNewSpec()
                    .withHard(Map.of(
                        "requests.cpu",    new Quantity("20"),
                        "requests.memory", new Quantity("40Gi"),
                        "limits.cpu",      new Quantity("20"),
                        "limits.memory",   new Quantity("40Gi"),
                        "pods",            new Quantity("20")
                    ))
                .endSpec()
                .build();
            k8sClient.resourceQuotas().inNamespace(ns).resource(resourceQuota).create();
            log.info("Created ResourceQuota tenant-quota in namespace: {}", ns);
        } catch (Exception e) {
            log.warn("Failed to create ResourceQuota in {}: {}", ns, e.getMessage());
        }

        return ns;
    }
}
