package com.lakeon.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.connector.ConnectorDtos.CreateConnectorRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectorServiceTest {
    private static final String TEST_KEY = "test-key-1234567890abcdef1234567";

    @Mock
    ConnectorRepository connectorRepository;
    @Mock
    PostgresConnectorAdapter postgresAdapter;

    ConnectorSecretCrypto crypto;
    ConnectorService service;

    @BeforeEach
    void setUp() {
        crypto = new ConnectorSecretCrypto(TEST_KEY);
        service = new ConnectorService(
            connectorRepository,
            null,
            new ObjectMapper(),
            crypto,
            postgresAdapter
        );
    }

    @Test
    void createPostgresConnector_encryptsSecretAndHidesPassword() {
        when(connectorRepository.save(any(ConnectorEntity.class))).thenAnswer(invocation -> {
            ConnectorEntity entity = invocation.getArgument(0);
            entity.setId("conn_pg001");
            return entity;
        });

        var response = service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            new LinkedHashMap<>(Map.of("host", "pg.example.com", "port", 5432, "dbname", "appdb")),
            new LinkedHashMap<>(Map.of("user", "postgres", "password", "secret"))
        ));

        assertThat(response.id()).isEqualTo("conn_pg001");
        assertThat(response.targetSummary()).isEqualTo("pg.example.com:5432/appdb");
        assertThat(response.config()).doesNotContainKey("password");
        assertThat(response.config()).doesNotContainKey("user");
    }

    @Test
    void resolvePostgresSnapshot_readsStoredConfigAndSecret() {
        ConnectorEntity entity = new ConnectorEntity();
        entity.setId("conn_pg001");
        entity.setTenantId("tn_1");
        entity.setType(ConnectorType.POSTGRESQL);
        entity.setName("Source PG");
        entity.setConfigJson("{\"host\":\"pg.example.com\",\"port\":5432,\"dbname\":\"appdb\"}");
        entity.setEncryptedSecretJson(crypto.encrypt("{\"user\":\"postgres\",\"password\":\"secret\"}"));

        when(connectorRepository.findByIdAndTenantId("conn_pg001", "tn_1")).thenReturn(Optional.of(entity));

        var snapshot = service.resolvePostgres("tn_1", "conn_pg001");

        assertThat(snapshot.host()).isEqualTo("pg.example.com");
        assertThat(snapshot.port()).isEqualTo(5432);
        assertThat(snapshot.dbname()).isEqualTo("appdb");
        assertThat(snapshot.user()).isEqualTo("postgres");
        assertThat(snapshot.password()).isEqualTo("secret");
    }
}
