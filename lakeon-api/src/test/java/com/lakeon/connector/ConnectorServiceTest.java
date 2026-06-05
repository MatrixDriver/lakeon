package com.lakeon.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.connector.ConnectorDtos.CreateConnectorRequest;
import com.lakeon.model.dto.SourceTableInfo;
import com.lakeon.obs.connection.ObsConnectionEntity;
import com.lakeon.obs.connection.ObsConnectionRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectorServiceTest {
    private static final String TEST_KEY = "test-key-1234567890abcdef1234567";

    @Mock
    ConnectorRepository connectorRepository;
    @Mock
    ObsConnectionRepository obsConnectionRepository;
    @Mock
    PostgresConnectorAdapter postgresAdapter;

    ConnectorSecretCrypto crypto;
    ConnectorService service;

    @BeforeEach
    void setUp() {
        crypto = new ConnectorSecretCrypto(TEST_KEY);
        service = new ConnectorService(
            connectorRepository,
            obsConnectionRepository,
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
    void createPostgresConnector_normalizesNumericStringPort() throws Exception {
        ArgumentCaptor<ConnectorEntity> entityCaptor = ArgumentCaptor.forClass(ConnectorEntity.class);
        when(connectorRepository.save(any(ConnectorEntity.class))).thenAnswer(invocation -> {
            ConnectorEntity entity = invocation.getArgument(0);
            entity.setId("conn_pg001");
            return entity;
        });

        var response = service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            new LinkedHashMap<>(Map.of("host", "pg.example.com", "port", "15432", "dbname", "appdb")),
            postgresSecret()
        ));

        verify(connectorRepository).save(entityCaptor.capture());
        Map<String, Object> storedConfig = new ObjectMapper().readValue(
            entityCaptor.getValue().getConfigJson(),
            new TypeReference<LinkedHashMap<String, Object>>() {
            }
        );
        assertThat(storedConfig).containsEntry("port", 15432);
        assertThat(response.config()).containsEntry("port", 15432);
        assertThat(response.targetSummary()).isEqualTo("pg.example.com:15432/appdb");
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

    @Test
    void list_includesObsConnectionProjection() {
        ObsConnectionEntity obs = new ObsConnectionEntity();
        obs.setId("oc_1");
        obs.setTenantId("tn_1");
        obs.setName("Raw Lake");
        obs.setDomainName("example-domain");
        obs.setAgencyName("lakeon-agency");
        obs.setObsEndpoint("https://obs.example.com");
        obs.setBucket("source-bucket");
        obs.setBasePath("imports/");
        obs.setStatus("ACTIVE");
        obs.setLastTestedAt(Instant.parse("2026-06-05T01:00:00Z"));
        obs.setCreatedAt(Instant.parse("2026-06-05T00:00:00Z"));
        obs.setUpdatedAt(Instant.parse("2026-06-05T00:30:00Z"));

        when(connectorRepository.findAllByTenantIdOrderByUpdatedAtDesc("tn_1")).thenReturn(List.of());
        when(obsConnectionRepository.findAllByTenantIdOrderByCreatedAtDesc("tn_1")).thenReturn(List.of(obs));

        var responses = service.list("tn_1");

        assertThat(responses).hasSize(1);
        var response = responses.get(0);
        assertThat(response.id()).isEqualTo("oc_1");
        assertThat(response.type()).isEqualTo(ConnectorType.OBS);
        assertThat(response.status()).isEqualTo(ConnectorStatus.CONNECTED);
        assertThat(response.config()).containsEntry("bucket", "source-bucket");
        assertThat(response.config()).containsEntry("obs_endpoint", "https://obs.example.com");
        assertThat(response.lastError()).isNull();
    }

    @Test
    void get_crossTenantMissThrowsNotFoundException() {
        when(connectorRepository.findByIdAndTenantId("conn_pg001", "tn_2")).thenReturn(Optional.empty());
        when(obsConnectionRepository.findByIdAndTenantId("conn_pg001", "tn_2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("tn_2", "conn_pg001"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Connector not found");
    }

    @Test
    void test_updatesStatusLastTestedAtLastErrorAndDelegatesToAdapter() {
        ConnectorEntity entity = postgresEntity();
        when(connectorRepository.findByIdAndTenantId("conn_pg001", "tn_1")).thenReturn(Optional.of(entity));
        when(postgresAdapter.test(argThat(snapshot ->
            "pg.example.com".equals(snapshot.host())
                && snapshot.port() == 5432
                && "appdb".equals(snapshot.dbname())
                && "postgres".equals(snapshot.user())
                && "secret".equals(snapshot.password())
        ))).thenReturn(Map.of("ok", false, "error", "connection refused"));

        var result = service.test("tn_1", "conn_pg001");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("connection refused");
        assertThat(entity.getStatus()).isEqualTo(ConnectorStatus.FAILED);
        assertThat(entity.getLastTestedAt()).isNotNull();
        assertThat(entity.getLastError()).isEqualTo("connection refused");
        verify(connectorRepository).save(entity);
        verify(postgresAdapter).test(any(ConnectorDtos.PostgresConnectionSnapshot.class));
    }

    @Test
    void listPostgresTables_delegatesToAdapterWithResolvedSnapshot() {
        ConnectorEntity entity = postgresEntity();
        List<SourceTableInfo> tables = List.of(new SourceTableInfo("public", "orders", 42));
        when(connectorRepository.findByIdAndTenantId("conn_pg001", "tn_1")).thenReturn(Optional.of(entity));
        when(postgresAdapter.listTables(any(ConnectorDtos.PostgresConnectionSnapshot.class))).thenReturn(tables);

        var result = service.listPostgresTables("tn_1", "conn_pg001");

        assertThat(result).isEqualTo(tables);
        ArgumentCaptor<ConnectorDtos.PostgresConnectionSnapshot> snapshotCaptor =
            ArgumentCaptor.forClass(ConnectorDtos.PostgresConnectionSnapshot.class);
        verify(postgresAdapter).listTables(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().host()).isEqualTo("pg.example.com");
        assertThat(snapshotCaptor.getValue().password()).isEqualTo("secret");
    }

    @Test
    void create_rejectsNullRequest() {
        assertThatThrownBy(() -> service.create("tn_1", null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("request");
    }

    @Test
    void create_rejectsMissingType() {
        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            null,
            "Source PG",
            postgresConfig(),
            postgresSecret()
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("type");
    }

    @Test
    void create_rejectsMissingConfigHost() {
        Map<String, Object> config = new LinkedHashMap<>(postgresConfig());
        config.remove("host");

        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            config,
            postgresSecret()
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("host");
    }

    @Test
    void create_rejectsMissingConfigDbname() {
        Map<String, Object> config = new LinkedHashMap<>(postgresConfig());
        config.remove("dbname");

        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            config,
            postgresSecret()
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("dbname");
    }

    @Test
    void create_rejectsInvalidPortString() {
        Map<String, Object> config = new LinkedHashMap<>(postgresConfig());
        config.put("port", "not-a-port");

        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            config,
            postgresSecret()
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("port");
    }

    @Test
    void create_rejectsOutOfRangePort() {
        Map<String, Object> config = new LinkedHashMap<>(postgresConfig());
        config.put("port", 70000);

        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            config,
            postgresSecret()
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("port");
    }

    @Test
    void create_rejectsMissingSecretUser() {
        Map<String, Object> secret = new LinkedHashMap<>(postgresSecret());
        secret.remove("user");

        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            postgresConfig(),
            secret
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("user");
    }

    @Test
    void create_rejectsMissingSecretPassword() {
        Map<String, Object> secret = new LinkedHashMap<>(postgresSecret());
        secret.remove("password");

        assertThatThrownBy(() -> service.create("tn_1", new CreateConnectorRequest(
            ConnectorType.POSTGRESQL,
            "Source PG",
            postgresConfig(),
            secret
        )))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("password");
    }

    private ConnectorEntity postgresEntity() {
        ConnectorEntity entity = new ConnectorEntity();
        entity.setId("conn_pg001");
        entity.setTenantId("tn_1");
        entity.setType(ConnectorType.POSTGRESQL);
        entity.setName("Source PG");
        entity.setConfigJson("{\"host\":\"pg.example.com\",\"port\":5432,\"dbname\":\"appdb\"}");
        entity.setEncryptedSecretJson(crypto.encrypt("{\"user\":\"postgres\",\"password\":\"secret\"}"));
        return entity;
    }

    private Map<String, Object> postgresConfig() {
        return new LinkedHashMap<>(Map.of("host", "pg.example.com", "port", 5432, "dbname", "appdb"));
    }

    private Map<String, Object> postgresSecret() {
        return new LinkedHashMap<>(Map.of("user", "postgres", "password", "secret"));
    }
}
