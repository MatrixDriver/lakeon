package com.lakeon.config;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Huawei Cloud OBS Java SDK client as a Spring bean.
 *
 * <p>Only activated when {@code lakeon.obs.endpoint} is configured (skipped in local dev /
 * tests where the SDK would fail at startup without credentials). The bean is destroyed
 * via {@link ObsClient#close()} on shutdown to release the underlying HTTP connection pool.
 *
 * <p>This bean is the low-level SDK handle. Production code should depend on
 * {@link com.lakeon.obs.LakeonObsClient} (a thin wrapper with explicit PUT/GET/LIST/DELETE
 * semantics + If-Match support) rather than calling {@link ObsClient} directly.
 */
@Configuration
public class ObsConfig {

    private static final Logger log = LoggerFactory.getLogger(ObsConfig.class);

    /**
     * Build the OBS SDK client from the same {@code lakeon.obs.*} properties used by
     * {@link com.lakeon.obs.ObsStsService}. Returns a singleton client; the SDK is
     * thread-safe.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "lakeon.obs", name = "endpoint")
    public ObsClient obsClient(LakeonProperties props) {
        String endpoint = props.getObs().getEndpoint();
        String accessKey = props.getObs().getAccessKey();
        String secretKey = props.getObs().getSecretKey();

        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.warn("ObsConfig: lakeon.obs.access-key / secret-key are blank; OBS direct client will fail on use");
        }

        ObsConfiguration cfg = new ObsConfiguration();
        cfg.setEndPoint(endpoint);
        // Reasonable defaults for control-plane usage (manifest writes are tiny JSON blobs).
        cfg.setSocketTimeout(30_000);
        cfg.setConnectionTimeout(10_000);
        cfg.setMaxErrorRetry(2);

        log.info("ObsConfig: initializing OBS client endpoint={}", endpoint);
        return new ObsClient(accessKey == null ? "" : accessKey,
                             secretKey == null ? "" : secretKey,
                             cfg);
    }
}
