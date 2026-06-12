package com.lakeon.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesConfigTest {

    @Test
    void kubernetesClient_usesDataPlaneConfigWhenProvided() {
        LakeonProperties props = new LakeonProperties();
        props.getDataPlane().setKubeApiServer("https://10.0.0.8:5443");
        props.getDataPlane().setKubeToken("token-abc");
        props.getDataPlane().setKubeCaB64("ca-data");
        props.getDataPlane().setComputeNamespace("lakeon-compute");

        try (KubernetesClient client = new KubernetesConfig().kubernetesClient(props)) {
            var config = client.getConfiguration();

            assertThat(config.getMasterUrl()).isEqualTo("https://10.0.0.8:5443/");
            assertThat(config.getOauthToken()).isEqualTo("token-abc");
            assertThat(config.getCaCertData()).isEqualTo("ca-data");
            assertThat(config.getNamespace()).isEqualTo("lakeon-compute");
            assertThat(config.isTrustCerts()).isFalse();
        }
    }

    @Test
    void kubernetesClient_trustsCertsWhenDataPlaneCaIsNotProvided() {
        LakeonProperties props = new LakeonProperties();
        props.getDataPlane().setKubeApiServer("https://10.0.0.8:5443");
        props.getDataPlane().setKubeToken("token-abc");

        try (KubernetesClient client = new KubernetesConfig().kubernetesClient(props)) {
            var config = client.getConfiguration();

            assertThat(config.getMasterUrl()).isEqualTo("https://10.0.0.8:5443/");
            assertThat(config.isTrustCerts()).isTrue();
        }
    }
}
