package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageserverKubernetesNodeProviderTest {

    @Test
    void discoversReadyPageserverPodsAsDynamicNodes() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverDiscoveryEnabled(true);
        props.getDataPlane().setNamespace("lakeon");
        PageserverKubernetesNodeProvider provider = new PageserverKubernetesNodeProvider(props, () -> List.of(
            pod("pageserver-1", "10.0.0.11", true),
            pod("pageserver-0", "10.0.0.10", true),
            pod("pageserver-2", "10.0.0.12", false)
        ));

        List<PageserverNode> nodes = provider.nodes();

        assertThat(nodes).extracting(PageserverNode::id).containsExactly("ps-0", "ps-1");
        assertThat(nodes.get(0).httpUrl()).isEqualTo("http://10.0.0.10:9898");
        assertThat(nodes.get(0).pgHost()).isEqualTo("pageserver-0.pageserver-headless.lakeon.svc.cluster.local");
        assertThat(nodes.get(0).pgPort()).isEqualTo(6400);
    }

    @Test
    void returnsEmptyWhenDiscoveryIsDisabled() {
        LakeonProperties props = new LakeonProperties();
        props.getNeon().setPageserverDiscoveryEnabled(false);
        PageserverKubernetesNodeProvider provider = new PageserverKubernetesNodeProvider(props, () -> List.of(
            pod("pageserver-0", "10.0.0.10", true)
        ));

        assertThat(provider.nodes()).isEmpty();
    }

    private Pod pod(String name, String podIp, boolean ready) {
        return new PodBuilder()
            .withNewMetadata()
                .withName(name)
            .endMetadata()
            .withNewStatus()
                .withPhase("Running")
                .withPodIP(podIp)
                .addNewCondition()
                    .withType("Ready")
                    .withStatus(ready ? "True" : "False")
                .endCondition()
            .endStatus()
            .build();
    }
}
