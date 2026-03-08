package com.lakeon.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * When SSL is enabled, add an additional HTTP connector for internal
 * cluster communication (e.g., Neon proxy auth-endpoint).
 * Port is configurable via LAKEON_INTERNAL_HTTP_PORT (default 8088).
 */
@Configuration
@ConditionalOnProperty(name = "server.ssl.enabled", havingValue = "true")
public class HttpConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpConnectorCustomizer() {
        int port = Integer.parseInt(System.getenv().getOrDefault("LAKEON_INTERNAL_HTTP_PORT", "8088"));
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setScheme("http");
            connector.setPort(port);
            connector.setSecure(false);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
