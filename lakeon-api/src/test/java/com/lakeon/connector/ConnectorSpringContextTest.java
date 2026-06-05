package com.lakeon.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.obs.connection.ObsConnectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {
        ConnectorController.class,
        ConnectorService.class,
        ConnectorSecretCrypto.class,
        PostgresConnectorAdapter.class,
        ConnectorSpringContextTest.MockConnectorConfig.class
    },
    properties = "CONNECTOR_SECRET_KEY=test-key-1234567890abcdef1234567"
)
class ConnectorSpringContextTest {
    @Autowired
    ConnectorController connectorController;

    @Autowired
    ConnectorService connectorService;

    @Test
    void contextInstantiatesConnectorBeansWithSecretKeyProperty() {
        assertThat(connectorController).isNotNull();
        assertThat(connectorService).isNotNull();
    }

    @Configuration
    static class MockConnectorConfig {
        @Bean
        ConnectorRepository connectorRepository() {
            return Mockito.mock(ConnectorRepository.class);
        }

        @Bean
        ObsConnectionRepository obsConnectionRepository() {
            return Mockito.mock(ObsConnectionRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
