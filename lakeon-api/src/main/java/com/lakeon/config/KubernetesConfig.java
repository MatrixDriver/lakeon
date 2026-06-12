package com.lakeon.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesConfig {

    @Bean
    public KubernetesClient kubernetesClient(LakeonProperties properties) {
        LakeonProperties.DataPlaneConfig dataPlane = properties.getDataPlane();
        if (isBlank(dataPlane.getKubeApiServer())) {
            return new KubernetesClientBuilder().build();
        }

        ConfigBuilder builder = new ConfigBuilder()
                .withMasterUrl(dataPlane.getKubeApiServer())
                .withOauthToken(dataPlane.getKubeToken())
                .withNamespace(defaultIfBlank(dataPlane.getComputeNamespace(), "lakeon-compute"))
                .withRequestTimeout(30_000)
                .withConnectionTimeout(10_000);

        if (isBlank(dataPlane.getKubeCaB64())) {
            builder.withTrustCerts(true);
        } else {
            builder.withCaCertData(dataPlane.getKubeCaB64());
        }

        Config config = builder.build();
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
