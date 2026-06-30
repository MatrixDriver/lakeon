package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PageserverKubernetesNodeProvider implements PageserverNodeProvider {
    private static final Logger log = LoggerFactory.getLogger(PageserverKubernetesNodeProvider.class);
    private static final Pattern ORDINAL_NAME = Pattern.compile(".*-(\\d+)$");

    private final LakeonProperties props;
    private final Supplier<List<Pod>> podSupplier;

    @Autowired
    public PageserverKubernetesNodeProvider(LakeonProperties props, KubernetesClient kubernetesClient) {
        this(props, () -> kubernetesClient.pods()
            .inNamespace(namespace(props))
            .withLabelSelector(props.getNeon().getPageserverDiscoveryLabelSelector())
            .list()
            .getItems());
    }

    PageserverKubernetesNodeProvider(LakeonProperties props, Supplier<List<Pod>> podSupplier) {
        this.props = props;
        this.podSupplier = podSupplier;
    }

    @Override
    public List<PageserverNode> nodes() {
        if (!props.getNeon().isPageserverDiscoveryEnabled()) {
            return List.of();
        }
        try {
            return podSupplier.get().stream()
                .filter(this::isReady)
                .filter(pod -> pod.getStatus() != null && notBlank(pod.getStatus().getPodIP()))
                .sorted(Comparator.comparing(this::ordinal).thenComparing(pod -> pod.getMetadata().getName()))
                .map(this::toNode)
                .toList();
        } catch (RuntimeException e) {
            log.warn("Failed to discover pageserver pods from Kubernetes: {}", e.getMessage());
            return List.of();
        }
    }

    private PageserverNode toNode(Pod pod) {
        String podName = pod.getMetadata().getName();
        int ordinal = ordinal(pod);
        String id = ordinal >= 0 ? "ps-" + ordinal : podName;
        String namespace = namespace(props);
        String headless = props.getNeon().getPageserverDiscoveryHeadlessService();
        String pgHost = podName + "." + headless + "." + namespace + ".svc.cluster.local";
        String httpUrl = "http://" + pod.getStatus().getPodIP() + ":" + props.getNeon().getPageserverDiscoveryHttpPort();
        return new PageserverNode(id, httpUrl, pgHost, props.getNeon().getPageserverDiscoveryPgPort(),
            pod.getMetadata().getUid());
    }

    private boolean isReady(Pod pod) {
        if (pod.getMetadata() == null || !notBlank(pod.getMetadata().getName())) {
            return false;
        }
        if (pod.getStatus() == null || !"Running".equalsIgnoreCase(pod.getStatus().getPhase())) {
            return false;
        }
        return pod.getStatus().getConditions() != null
            && pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus()));
    }

    private int ordinal(Pod pod) {
        if (pod.getMetadata() == null || pod.getMetadata().getName() == null) {
            return -1;
        }
        Matcher matcher = ORDINAL_NAME.matcher(pod.getMetadata().getName());
        if (!matcher.matches()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String namespace(LakeonProperties props) {
        String namespace = props.getNeon().getPageserverDiscoveryNamespace();
        if (notBlank(namespace)) {
            return namespace;
        }
        namespace = props.getDataPlane().getNamespace();
        return notBlank(namespace) ? namespace : "lakeon";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
