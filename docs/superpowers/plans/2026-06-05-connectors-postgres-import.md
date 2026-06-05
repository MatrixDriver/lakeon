# Connectors PostgreSQL Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable Connector feature, with PostgreSQL connectors powering the existing DBay database import and sync flow while showing existing OBS connections under the same console concept.

**Architecture:** Add a focused backend connector package that owns connector persistence, secret encryption, PostgreSQL connection testing, and table discovery. Import keeps its existing execution path but accepts an optional connector id and snapshots the resolved PostgreSQL source onto the import task. Console gets a new connector API/page and the import wizard can select an existing PostgreSQL connector or keep the current temporary manual connection flow.

**Tech Stack:** Spring Boot 3.3, Java 17, JPA/Hibernate, Flyway SQL migrations, PostgreSQL JDBC, Vue 3, TypeScript, Vite, Vitest.

---

## File Structure

Backend creates:

- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorType.java`: connector type enum.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorStatus.java`: connector status enum.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorEntity.java`: JPA entity for `connectors`.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorRepository.java`: tenant-scoped repository queries.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorDtos.java`: request/response records and PostgreSQL snapshot records.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorSecretCrypto.java`: AES-GCM secret encryption helper.
- `lakeon-api/src/main/java/com/lakeon/connector/PostgresConnectorAdapter.java`: PostgreSQL test/list table logic.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorService.java`: lifecycle, OBS projection, PG resolution.
- `lakeon-api/src/main/java/com/lakeon/connector/ConnectorController.java`: `/api/v1/connectors` endpoints.
- `lakeon-api/src/main/resources/db/migration/V42__create_connectors.sql`: connectors table and `import_tasks.connector_id`.
- `lakeon-api/src/test/java/com/lakeon/connector/ConnectorServiceTest.java`: unit tests for create/list/test/resolve.
- `lakeon-api/src/test/java/com/lakeon/service/ImportServiceConnectorTest.java`: import connector lineage tests.

Backend modifies:

- `lakeon-api/src/main/java/com/lakeon/service/ImportService.java`: inject connector service and resolve connector snapshots before task creation.
- `lakeon-api/src/main/java/com/lakeon/model/dto/CreateImportRequest.java`: add `connector_id`.
- `lakeon-api/src/main/java/com/lakeon/model/dto/ImportTaskResponse.java`: add connector id/name.
- `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTaskEntity.java`: add `connectorId`.
- `lakeon-api/src/test/java/com/lakeon/service/ImportServiceSyncTest.java`: update constructor calls and request records.

Console creates:

- `lakeon-console/src/api/connectors.ts`: typed connector client.
- `lakeon-console/src/views/connectors/ConnectorsView.vue`: connector list and PostgreSQL create/test UI.
- `lakeon-console/src/__tests__/connectors-api.test.ts`: API payload mapping tests.
- `lakeon-console/src/__tests__/ImportWizardConnector.test.ts`: import wizard connector selection behavior.

Console modifies:

- `lakeon-console/src/layouts/ConsoleLayout.vue`: rename `OBS 连接` to `连接器`.
- `lakeon-console/src/router/index.ts`: add `/connectors`; keep `/datalake/connections` redirect-compatible.
- `lakeon-console/src/api/import.ts`: add connector-aware request fields and table discovery helper.
- `lakeon-console/src/views/database/ImportWizard.vue`: select existing connector or use temporary connection.
- `lakeon-console/src/views/import/ImportEntry.vue`: display connector name when present.
- `lakeon-console/src/views/database/ImportTaskDetail.vue`: display connector lineage when present.

## Task 1: Backend Connector Persistence and API Surface

**Files:**

- Create: `lakeon-api/src/main/resources/db/migration/V42__create_connectors.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorType.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorRepository.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorDtos.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorSecretCrypto.java`
- Create: `lakeon-api/src/test/java/com/lakeon/connector/ConnectorSecretCryptoTest.java`

- [ ] **Step 1: Add failing crypto unit test**

Create `lakeon-api/src/test/java/com/lakeon/connector/ConnectorSecretCryptoTest.java`:

```java
package com.lakeon.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorSecretCryptoTest {
    @Test
    void encrypt_decrypt_roundTripDoesNotExposePlaintext() {
        ConnectorSecretCrypto crypto = new ConnectorSecretCrypto("0123456789abcdef0123456789abcdef");

        String encrypted = crypto.encrypt("{\"username\":\"postgres\",\"password\":\"secret\"}");
        String decrypted = crypto.decrypt(encrypted);

        assertThat(encrypted).doesNotContain("secret");
        assertThat(decrypted).isEqualTo("{\"username\":\"postgres\",\"password\":\"secret\"}");
    }
}
```

- [ ] **Step 2: Run crypto test and verify it fails**

Run:

```bash
cd lakeon-api && mvn -Dtest=ConnectorSecretCryptoTest test
```

Expected: compile failure because `ConnectorSecretCrypto` does not exist.

- [ ] **Step 3: Add migration**

Create `lakeon-api/src/main/resources/db/migration/V42__create_connectors.sql`:

```sql
CREATE TABLE IF NOT EXISTS connectors (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
    config_json TEXT NOT NULL,
    encrypted_secret_json TEXT,
    last_tested_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_connectors_tenant_type ON connectors (tenant_id, type);
CREATE INDEX IF NOT EXISTS idx_connectors_tenant_updated ON connectors (tenant_id, updated_at DESC);

ALTER TABLE import_tasks ADD COLUMN IF NOT EXISTS connector_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_import_tasks_connector_id ON import_tasks (connector_id);
```

- [ ] **Step 4: Add enums**

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorType.java`:

```java
package com.lakeon.connector;

public enum ConnectorType {
    POSTGRESQL,
    OBS
}
```

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorStatus.java`:

```java
package com.lakeon.connector;

public enum ConnectorStatus {
    UNTESTED,
    CONNECTED,
    FAILED
}
```

- [ ] **Step 5: Add entity and repository**

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorEntity.java`:

```java
package com.lakeon.connector;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connectors", indexes = {
    @Index(name = "idx_connectors_tenant_type", columnList = "tenant_id,type"),
    @Index(name = "idx_connectors_tenant_updated", columnList = "tenant_id,updated_at")
})
public class ConnectorEntity {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private ConnectorType type;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConnectorStatus status = ConnectorStatus.UNTESTED;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "encrypted_secret_json", columnDefinition = "TEXT")
    private String encryptedSecretJson;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = "conn_" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public ConnectorType getType() { return type; }
    public void setType(ConnectorType type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ConnectorStatus getStatus() { return status; }
    public void setStatus(ConnectorStatus status) { this.status = status; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getEncryptedSecretJson() { return encryptedSecretJson; }
    public void setEncryptedSecretJson(String encryptedSecretJson) { this.encryptedSecretJson = encryptedSecretJson; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Instant lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorRepository.java`:

```java
package com.lakeon.connector;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorRepository extends JpaRepository<ConnectorEntity, String> {
    List<ConnectorEntity> findAllByTenantIdOrderByUpdatedAtDesc(String tenantId);
    List<ConnectorEntity> findAllByTenantIdAndTypeOrderByUpdatedAtDesc(String tenantId, ConnectorType type);
    Optional<ConnectorEntity> findByIdAndTenantId(String id, String tenantId);
    long countByTenantIdAndType(String tenantId, ConnectorType type);
}
```

- [ ] **Step 6: Add DTOs**

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorDtos.java`:

```java
package com.lakeon.connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.dto.SourceTableInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConnectorDtos {
    private ConnectorDtos() {}

    public record CreateConnectorRequest(
        ConnectorType type,
        String name,
        Map<String, Object> config,
        Map<String, Object> secret
    ) {}

    public record UpdateConnectorRequest(
        String name,
        Map<String, Object> config,
        Map<String, Object> secret
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorResponse(
        String id,
        ConnectorType type,
        String name,
        ConnectorStatus status,
        Map<String, Object> config,
        @JsonProperty("target_summary") String targetSummary,
        @JsonProperty("last_tested_at") Instant lastTestedAt,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("usage_count") Long usageCount,
        @JsonProperty("usage_hint") String usageHint
    ) {}

    public record ConnectorTestResponse(
        boolean ok,
        String error,
        Map<String, Object> metadata
    ) {}

    public record PostgresConnectionSnapshot(
        String connectorId,
        String connectorName,
        String host,
        Integer port,
        String dbname,
        String user,
        String password
    ) {}

    public record PostgresTableListResponse(
        List<SourceTableInfo> tables
    ) {}
}
```

- [ ] **Step 7: Add crypto helper**

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorSecretCrypto.java`:

```java
package com.lakeon.connector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ConnectorSecretCrypto {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public ConnectorSecretCrypto(@Value("${lakeon.connector.secret-key:0123456789abcdef0123456789abcdef}") String rawKey) {
        byte[] bytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalArgumentException("Connector secret key must be 16, 24, or 32 bytes");
        }
        this.key = bytes;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt connector secret", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            System.arraycopy(packed, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt connector secret", e);
        }
    }
}
```

- [ ] **Step 8: Run crypto test**

Run:

```bash
cd lakeon-api && mvn -Dtest=ConnectorSecretCryptoTest test
```

Expected: PASS.

- [ ] **Step 9: Commit Task 1**

```bash
git add lakeon-api/src/main/resources/db/migration/V42__create_connectors.sql \
  lakeon-api/src/main/java/com/lakeon/connector \
  lakeon-api/src/test/java/com/lakeon/connector/ConnectorSecretCryptoTest.java
git commit -m "feat(connectors): add connector persistence model"
```

## Task 2: PostgreSQL Connector Service and Controller

**Files:**

- Create: `lakeon-api/src/main/java/com/lakeon/connector/PostgresConnectorAdapter.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/connector/ConnectorController.java`
- Create: `lakeon-api/src/test/java/com/lakeon/connector/ConnectorServiceTest.java`

- [ ] **Step 1: Write service test for PostgreSQL create and resolve**

Create `lakeon-api/src/test/java/com/lakeon/connector/ConnectorServiceTest.java`:

```java
package com.lakeon.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.connector.ConnectorDtos.CreateConnectorRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectorServiceTest {
    @Mock ConnectorRepository connectorRepository;
    @Mock PostgresConnectorAdapter postgresAdapter;

    ConnectorService service;

    @BeforeEach
    void setUp() {
        service = new ConnectorService(
            connectorRepository,
            null,
            new ObjectMapper(),
            new ConnectorSecretCrypto("0123456789abcdef0123456789abcdef"),
            postgresAdapter
        );
    }

    @Test
    void createPostgresConnector_encryptsSecretAndHidesPassword() {
        when(connectorRepository.save(any(ConnectorEntity.class))).thenAnswer(inv -> {
            ConnectorEntity entity = inv.getArgument(0);
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
    }

    @Test
    void resolvePostgresSnapshot_readsStoredConfigAndSecret() {
        ConnectorEntity entity = new ConnectorEntity();
        entity.setId("conn_pg001");
        entity.setTenantId("tn_1");
        entity.setType(ConnectorType.POSTGRESQL);
        entity.setName("Source PG");
        entity.setConfigJson("{\"host\":\"pg.example.com\",\"port\":5432,\"dbname\":\"appdb\"}");
        entity.setEncryptedSecretJson(new ConnectorSecretCrypto("0123456789abcdef0123456789abcdef")
            .encrypt("{\"user\":\"postgres\",\"password\":\"secret\"}"));

        when(connectorRepository.findByIdAndTenantId("conn_pg001", "tn_1")).thenReturn(Optional.of(entity));

        var snapshot = service.resolvePostgres("tn_1", "conn_pg001");

        assertThat(snapshot.host()).isEqualTo("pg.example.com");
        assertThat(snapshot.port()).isEqualTo(5432);
        assertThat(snapshot.dbname()).isEqualTo("appdb");
        assertThat(snapshot.user()).isEqualTo("postgres");
        assertThat(snapshot.password()).isEqualTo("secret");
    }
}
```

- [ ] **Step 2: Run service test and verify it fails**

Run:

```bash
cd lakeon-api && mvn -Dtest=ConnectorServiceTest test
```

Expected: compile failure because service and adapter do not exist.

- [ ] **Step 3: Implement PostgreSQL adapter**

Create `lakeon-api/src/main/java/com/lakeon/connector/PostgresConnectorAdapter.java`:

```java
package com.lakeon.connector;

import com.lakeon.model.dto.SourceTableInfo;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Component
public class PostgresConnectorAdapter {
    public Map<String, Object> test(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        Properties props = jdbcProperties(snapshot);
        String url = jdbcUrl(snapshot);
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(url, props);
             Statement stmt = conn.createStatement()) {
            ResultSet versionRs = stmt.executeQuery("SELECT version()");
            if (versionRs.next()) result.put("version", versionRs.getString(1));
            result.put("ok", true);
            try {
                ResultSet walRs = stmt.executeQuery("SHOW wal_level");
                if (walRs.next()) result.put("wal_level", walRs.getString(1));
            } catch (Exception ignored) {
                result.put("wal_level", "unknown");
            }
            try {
                ResultSet replRs = stmt.executeQuery("SELECT rolreplication FROM pg_roles WHERE rolname = current_user");
                result.put("has_replication", replRs.next() && replRs.getBoolean(1));
            } catch (Exception ignored) {
                result.put("has_replication", false);
            }
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    public List<SourceTableInfo> listTables(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        String sql = "SELECT t.table_schema, t.table_name, COALESCE(c.reltuples::bigint, 0) " +
            "FROM information_schema.tables t " +
            "LEFT JOIN pg_class c ON c.relname = t.table_name " +
            "AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = t.table_schema) " +
            "WHERE t.table_type = 'BASE TABLE' " +
            "AND t.table_schema NOT IN ('pg_catalog', 'information_schema') " +
            "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass " +
            "AND d.objid = c.oid AND d.deptype = 'e') " +
            "ORDER BY t.table_schema, t.table_name";
        List<SourceTableInfo> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl(snapshot), jdbcProperties(snapshot));
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) tables.add(new SourceTableInfo(rs.getString(1), rs.getString(2), rs.getLong(3)));
            return tables;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list source tables: " + e.getMessage(), e);
        }
    }

    private String jdbcUrl(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        return "jdbc:postgresql://" + snapshot.host() + ":" + snapshot.port() + "/" + snapshot.dbname();
    }

    private Properties jdbcProperties(ConnectorDtos.PostgresConnectionSnapshot snapshot) {
        Properties props = new Properties();
        props.setProperty("user", snapshot.user());
        props.setProperty("password", snapshot.password());
        props.setProperty("loginTimeout", "5");
        props.setProperty("connectTimeout", "5");
        props.setProperty("socketTimeout", "10");
        return props;
    }
}
```

- [ ] **Step 4: Implement service**

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorService.java` with the exact public methods needed by tests and import:

```java
package com.lakeon.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.connector.ConnectorDtos.*;
import com.lakeon.model.dto.SourceTableInfo;
import com.lakeon.obs.connection.ObsConnectionRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConnectorService {
    private final ConnectorRepository connectorRepository;
    private final ObsConnectionRepository obsConnectionRepository;
    private final ObjectMapper objectMapper;
    private final ConnectorSecretCrypto crypto;
    private final PostgresConnectorAdapter postgresAdapter;

    public ConnectorService(ConnectorRepository connectorRepository,
                            ObsConnectionRepository obsConnectionRepository,
                            ObjectMapper objectMapper,
                            ConnectorSecretCrypto crypto,
                            PostgresConnectorAdapter postgresAdapter) {
        this.connectorRepository = connectorRepository;
        this.obsConnectionRepository = obsConnectionRepository;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.postgresAdapter = postgresAdapter;
    }

    @Transactional
    public ConnectorResponse create(String tenantId, CreateConnectorRequest request) {
        if (request.type() != ConnectorType.POSTGRESQL) {
            throw new BadRequestException("Only PostgreSQL connector creation is supported in this API");
        }
        ConnectorEntity entity = new ConnectorEntity();
        entity.setTenantId(tenantId);
        entity.setType(request.type());
        entity.setName(requireText(request.name(), "name"));
        entity.setStatus(ConnectorStatus.UNTESTED);
        entity.setConfigJson(writeJson(request.config()));
        entity.setEncryptedSecretJson(crypto.encrypt(writeJson(request.secret())));
        return toResponse(connectorRepository.save(entity), 0L);
    }

    @Transactional(readOnly = true)
    public List<ConnectorResponse> list(String tenantId) {
        List<ConnectorResponse> pg = connectorRepository.findAllByTenantIdOrderByUpdatedAtDesc(tenantId)
            .stream().map(entity -> toResponse(entity, 0L)).toList();
        if (obsConnectionRepository == null) return pg;
        List<ConnectorResponse> obs = obsConnectionRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream().map(obsConn -> new ConnectorResponse(
                obsConn.getId(),
                ConnectorType.OBS,
                obsConn.getName(),
                ConnectorStatus.CONNECTED,
                Map.of("bucket", obsConn.getBucket(), "endpoint", obsConn.getEndpoint()),
                obsConn.getBucket(),
                obsConn.getLastTestedAt(),
                obsConn.getLastError(),
                obsConn.getCreatedAt(),
                obsConn.getUpdatedAt(),
                0L,
                "对象存储 OBS"
            )).toList();
        return java.util.stream.Stream.concat(pg.stream(), obs.stream()).toList();
    }

    @Transactional(readOnly = true)
    public ConnectorResponse get(String tenantId, String id) {
        return toResponse(find(tenantId, id), 0L);
    }

    @Transactional
    public ConnectorTestResponse test(String tenantId, String id) {
        ConnectorEntity entity = find(tenantId, id);
        if (entity.getType() != ConnectorType.POSTGRESQL) {
            throw new BadRequestException("Connector test through this endpoint only supports PostgreSQL in Phase 1");
        }
        Map<String, Object> metadata = postgresAdapter.test(resolvePostgres(tenantId, id));
        boolean ok = Boolean.TRUE.equals(metadata.get("ok"));
        entity.setLastTestedAt(Instant.now());
        entity.setStatus(ok ? ConnectorStatus.CONNECTED : ConnectorStatus.FAILED);
        entity.setLastError(ok ? null : String.valueOf(metadata.getOrDefault("error", "Connection failed")));
        connectorRepository.save(entity);
        return new ConnectorTestResponse(ok, entity.getLastError(), metadata);
    }

    @Transactional(readOnly = true)
    public List<SourceTableInfo> listPostgresTables(String tenantId, String id) {
        return postgresAdapter.listTables(resolvePostgres(tenantId, id));
    }

    @Transactional(readOnly = true)
    public PostgresConnectionSnapshot resolvePostgres(String tenantId, String id) {
        ConnectorEntity entity = find(tenantId, id);
        if (entity.getType() != ConnectorType.POSTGRESQL) {
            throw new BadRequestException("Connector is not PostgreSQL: " + id);
        }
        Map<String, Object> config = readJson(entity.getConfigJson());
        Map<String, Object> secret = readJson(crypto.decrypt(entity.getEncryptedSecretJson()));
        return new PostgresConnectionSnapshot(
            entity.getId(),
            entity.getName(),
            stringValue(config, "host"),
            intValue(config, "port", 5432),
            stringValue(config, "dbname"),
            stringValue(secret, "user"),
            stringValue(secret, "password")
        );
    }

    private ConnectorEntity find(String tenantId, String id) {
        return connectorRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Connector not found: " + id));
    }

    private ConnectorResponse toResponse(ConnectorEntity entity, Long usageCount) {
        Map<String, Object> config = readJson(entity.getConfigJson());
        return new ConnectorResponse(
            entity.getId(), entity.getType(), entity.getName(), entity.getStatus(), config,
            targetSummary(entity.getType(), config), entity.getLastTestedAt(), entity.getLastError(),
            entity.getCreatedAt(), entity.getUpdatedAt(), usageCount, "数据库导入"
        );
    }

    private String targetSummary(ConnectorType type, Map<String, Object> config) {
        if (type == ConnectorType.POSTGRESQL) {
            return stringValue(config, "host") + ":" + intValue(config, "port", 5432) + "/" + stringValue(config, "dbname");
        }
        return "";
    }

    private String writeJson(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new BadRequestException("Invalid connector JSON"); }
    }

    private Map<String, Object> readJson(String value) {
        try { return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new BadRequestException("Invalid connector JSON"); }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException("Missing connector field: " + field);
        return value.trim();
    }

    private String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new BadRequestException("Missing connector field: " + key);
        return String.valueOf(value);
    }

    private Integer intValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}
```

If `ObsConnectionRepository` method names differ, use the repository's actual tenant-scoped list method and keep the mapping shape unchanged.

- [ ] **Step 5: Implement controller**

Create `lakeon-api/src/main/java/com/lakeon/connector/ConnectorController.java`:

```java
package com.lakeon.connector;

import com.lakeon.connector.ConnectorDtos.*;
import com.lakeon.model.dto.SourceTableInfo;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {
    private final ConnectorService connectorService;
    private final TenantService tenantService;

    public ConnectorController(ConnectorService connectorService, TenantService tenantService) {
        this.connectorService = connectorService;
        this.tenantService = tenantService;
    }

    @GetMapping
    public List<ConnectorResponse> list() {
        TenantEntity tenant = tenantService.getCurrentTenant();
        return connectorService.list(tenant.getId());
    }

    @PostMapping
    public ConnectorResponse create(@RequestBody CreateConnectorRequest request) {
        TenantEntity tenant = tenantService.getCurrentTenant();
        return connectorService.create(tenant.getId(), request);
    }

    @GetMapping("/{id}")
    public ConnectorResponse get(@PathVariable String id) {
        TenantEntity tenant = tenantService.getCurrentTenant();
        return connectorService.get(tenant.getId(), id);
    }

    @PostMapping("/{id}/test")
    public ConnectorTestResponse test(@PathVariable String id) {
        TenantEntity tenant = tenantService.getCurrentTenant();
        return connectorService.test(tenant.getId(), id);
    }

    @GetMapping("/{id}/postgres/tables")
    public List<SourceTableInfo> tables(@PathVariable String id) {
        TenantEntity tenant = tenantService.getCurrentTenant();
        return connectorService.listPostgresTables(tenant.getId(), id);
    }
}
```

- [ ] **Step 6: Run service test**

Run:

```bash
cd lakeon-api && mvn -Dtest=ConnectorServiceTest,ConnectorSecretCryptoTest test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 2**

```bash
git add lakeon-api/src/main/java/com/lakeon/connector lakeon-api/src/test/java/com/lakeon/connector
git commit -m "feat(connectors): add postgres connector API"
```

## Task 3: Import Service Connector Integration

**Files:**

- Modify: `lakeon-api/src/main/java/com/lakeon/model/dto/CreateImportRequest.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/model/dto/ImportTaskResponse.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/model/entity/ImportTaskEntity.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/service/ImportService.java`
- Modify: `lakeon-api/src/test/java/com/lakeon/service/ImportServiceSyncTest.java`
- Create: `lakeon-api/src/test/java/com/lakeon/service/ImportServiceConnectorTest.java`

- [ ] **Step 1: Write failing import connector test**

Create `lakeon-api/src/test/java/com/lakeon/service/ImportServiceConnectorTest.java`:

```java
package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.connector.ConnectorDtos.PostgresConnectionSnapshot;
import com.lakeon.connector.ConnectorService;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.k8s.ImportJobPodManager;
import com.lakeon.model.dto.CreateImportRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportServiceConnectorTest {
    @Mock ImportTaskRepository importTaskRepository;
    @Mock ImportTableTaskRepository importTableTaskRepository;
    @Mock DatabaseRepository databaseRepository;
    @Mock ImportJobPodManager importJobPodManager;
    @Mock ComputePodManager computePodManager;
    @Mock DatabaseService databaseService;
    @Mock OperationLogService operationLogService;
    @Mock ConnectorService connectorService;

    ImportService service;
    TenantEntity tenant;
    DatabaseEntity database;

    @BeforeEach
    void setUp() {
        LakeonProperties props = new LakeonProperties();
        LakeonProperties.SyncConfig sync = new LakeonProperties.SyncConfig();
        sync.setMaxTasks(10);
        props.setSync(sync);
        service = new ImportService(importTaskRepository, importTableTaskRepository, databaseRepository,
            importJobPodManager, computePodManager, databaseService, operationLogService, props, connectorService);
        TransactionSynchronizationManager.initSynchronization();
        tenant = new TenantEntity();
        tenant.setId("tn_1");
        database = new DatabaseEntity();
        database.setId("db_1");
        database.setTenantId("tn_1");
        database.setName("target");
        database.setStatus(DatabaseStatus.RUNNING);
    }

    @Test
    void createImport_withConnectorIdSnapshotsSourceAndConnectorLineage() {
        when(databaseRepository.findByIdAndTenantId("db_1", "tn_1")).thenReturn(Optional.of(database));
        when(importTaskRepository.findAllByDatabaseIdAndTenantIdOrderByCreatedAtDesc("db_1", "tn_1")).thenReturn(List.of());
        when(operationLogService.startOperation(any(), any(), any(), any())).thenReturn(new OperationLogEntity());
        when(connectorService.resolvePostgres("tn_1", "conn_pg001")).thenReturn(new PostgresConnectionSnapshot(
            "conn_pg001", "Source PG", "pg.example.com", 5432, "sourcedb", "srcuser", "srcpass"
        ));
        when(importTaskRepository.save(any(ImportTaskEntity.class))).thenAnswer(inv -> {
            ImportTaskEntity entity = inv.getArgument(0);
            entity.setId("imp_1");
            return entity;
        });

        var response = service.createImport(tenant, "db_1", new CreateImportRequest(
            "conn_pg001", null, null, null, null, null,
            ImportMode.FULL, ConflictStrategy.APPEND, null
        ));

        assertThat(response.connectorId()).isEqualTo("conn_pg001");
        assertThat(response.connectorName()).isEqualTo("Source PG");
        assertThat(response.sourceHost()).isEqualTo("pg.example.com");
        assertThat(response.sourceDbname()).isEqualTo("sourcedb");
    }
}
```

- [ ] **Step 2: Run import connector test and verify it fails**

Run:

```bash
cd lakeon-api && mvn -Dtest=ImportServiceConnectorTest test
```

Expected: compile failure because constructor and DTO signatures have not been updated.

- [ ] **Step 3: Update request and response DTOs**

Modify `CreateImportRequest` to put `connectorId` first:

```java
public record CreateImportRequest(
    @JsonProperty("connector_id") String connectorId,
    @JsonProperty("source_host") String sourceHost,
    @JsonProperty("source_port") Integer sourcePort,
    @JsonProperty("source_dbname") String sourceDbname,
    @JsonProperty("source_user") String sourceUser,
    @JsonProperty("source_password") String sourcePassword,
    ImportMode mode,
    @JsonProperty("conflict_strategy") ConflictStrategy conflictStrategy,
    List<String> tables
) {}
```

Modify `ImportTaskResponse` by adding these fields after source user:

```java
@JsonProperty("connector_id") String connectorId,
@JsonProperty("connector_name") String connectorName,
```

- [ ] **Step 4: Update import task entity**

In `ImportTaskEntity`, add field and accessors near source fields:

```java
@Column(name = "connector_id", length = 64)
private String connectorId;

public String getConnectorId() {
    return connectorId;
}

public void setConnectorId(String connectorId) {
    this.connectorId = connectorId;
}
```

- [ ] **Step 5: Update ImportService constructor and source resolution**

Inject `ConnectorService` as nullable-compatible final dependency:

```java
private final ConnectorService connectorService;
```

Add it to the constructor after `LakeonProperties props`:

```java
ConnectorService connectorService
```

At the top of `createImport`, after database validation and before sync limit checks, resolve the request:

```java
CreateImportRequest effectiveReq = req;
String connectorName = null;
if (req.connectorId() != null && !req.connectorId().isBlank()) {
    var snapshot = connectorService.resolvePostgres(tenant.getId(), req.connectorId());
    connectorName = snapshot.connectorName();
    effectiveReq = new CreateImportRequest(
        snapshot.connectorId(),
        snapshot.host(),
        snapshot.port(),
        snapshot.dbname(),
        snapshot.user(),
        snapshot.password(),
        req.mode(),
        req.conflictStrategy(),
        req.tables()
    );
}
```

Then replace subsequent `req` reads in `createImport` with `effectiveReq` for source fields, mode, conflict strategy, and tables. Set connector lineage:

```java
task.setConnectorId(effectiveReq.connectorId());
```

Update the async call to pass `effectiveReq`.

- [ ] **Step 6: Add connector name to response without changing import storage**

In `toResponse`, use connector id only initially:

```java
String connectorName = task.getConnectorId();
```

Then include `task.getConnectorId()` and `connectorName` in the `ImportTaskResponse` constructor. This gives the UI a stable lineage value immediately. A later small enhancement can resolve display names for list/detail if needed, but the connector page and import creation response already return real names.

- [ ] **Step 7: Update existing import tests for new constructor signature**

In `ImportServiceSyncTest`, update constructor creation:

```java
importService = new ImportService(
    importTaskRepository,
    importTableTaskRepository,
    databaseRepository,
    importJobPodManager,
    computePodManager,
    databaseService,
    operationLogService,
    props,
    null
);
```

Update each `new CreateImportRequest(...)` by prepending `null` for connector id.

- [ ] **Step 8: Run import tests**

Run:

```bash
cd lakeon-api && mvn -Dtest=ImportServiceConnectorTest,ImportServiceSyncTest test
```

Expected: PASS.

- [ ] **Step 9: Commit Task 3**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/dto/CreateImportRequest.java \
  lakeon-api/src/main/java/com/lakeon/model/dto/ImportTaskResponse.java \
  lakeon-api/src/main/java/com/lakeon/model/entity/ImportTaskEntity.java \
  lakeon-api/src/main/java/com/lakeon/service/ImportService.java \
  lakeon-api/src/test/java/com/lakeon/service/ImportServiceSyncTest.java \
  lakeon-api/src/test/java/com/lakeon/service/ImportServiceConnectorTest.java
git commit -m "feat(import): support connector-backed imports"
```

## Task 4: Console Connector API

**Files:**

- Create: `lakeon-console/src/api/connectors.ts`
- Create: `lakeon-console/src/__tests__/connectors-api.test.ts`
- Modify: `lakeon-console/src/api/import.ts`

- [ ] **Step 1: Write API tests**

Create `lakeon-console/src/__tests__/connectors-api.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn((url: string) => Promise.resolve({ url })),
    post: vi.fn((url: string, body?: any) => Promise.resolve({ url, body })),
  },
}))

import client from '../api/client'
import { connectorsApi } from '../api/connectors'

describe('connectorsApi', () => {
  it('creates PostgreSQL connector with config and secret split', async () => {
    await connectorsApi.createPostgres({
      name: 'Source PG',
      host: 'pg.example.com',
      port: 5432,
      dbname: 'appdb',
      user: 'postgres',
      password: 'secret',
    })

    expect(client.post).toHaveBeenCalledWith('/connectors', {
      type: 'POSTGRESQL',
      name: 'Source PG',
      config: { host: 'pg.example.com', port: 5432, dbname: 'appdb' },
      secret: { user: 'postgres', password: 'secret' },
    })
  })
})
```

- [ ] **Step 2: Run API test and verify it fails**

Run:

```bash
cd lakeon-console && npm test -- connectors-api.test.ts
```

Expected: fail because `connectors.ts` does not exist.

- [ ] **Step 3: Implement connector API client**

Create `lakeon-console/src/api/connectors.ts`:

```ts
import client from './client'
import type { SourceTableInfo } from './import'

export type ConnectorType = 'POSTGRESQL' | 'OBS'
export type ConnectorStatus = 'UNTESTED' | 'CONNECTED' | 'FAILED'

export interface Connector {
  id: string
  type: ConnectorType
  name: string
  status: ConnectorStatus
  config: Record<string, any>
  target_summary: string
  last_tested_at: string | null
  last_error: string | null
  created_at: string | null
  updated_at: string | null
  usage_count: number
  usage_hint: string | null
}

export interface CreatePostgresConnectorInput {
  name: string
  host: string
  port: number
  dbname: string
  user: string
  password: string
}

export const connectorsApi = {
  list: () => client.get<Connector[]>('/connectors'),
  createPostgres: (input: CreatePostgresConnectorInput) => client.post<Connector>('/connectors', {
    type: 'POSTGRESQL',
    name: input.name,
    config: {
      host: input.host,
      port: input.port,
      dbname: input.dbname,
    },
    secret: {
      user: input.user,
      password: input.password,
    },
  }),
  test: (id: string) => client.post<{ ok: boolean; error?: string; metadata: Record<string, any> }>(`/connectors/${id}/test`),
  listPostgresTables: (id: string) => client.get<SourceTableInfo[]>(`/connectors/${id}/postgres/tables`, { timeout: 60000 }),
}
```

- [ ] **Step 4: Extend import API types**

Modify `lakeon-console/src/api/import.ts` so `ImportTask` includes:

```ts
connector_id: string | null
connector_name: string | null
```

Modify `importApi.create` input to allow connector id and optional source fields:

```ts
create: (dbId: string, data: {
  connectorId?: string
  sourceHost?: string; sourcePort?: number; sourceDbname?: string;
  sourceUser?: string; sourcePassword?: string;
  mode: string; conflictStrategy: string; tables?: string[]
}) => client.post<ImportTask>(`/databases/${dbId}/import`, {
  connector_id: data.connectorId,
  source_host: data.sourceHost,
  source_port: data.sourcePort,
  source_dbname: data.sourceDbname,
  source_user: data.sourceUser,
  source_password: data.sourcePassword,
  mode: data.mode,
  conflict_strategy: data.conflictStrategy,
  tables: data.tables,
}),
```

- [ ] **Step 5: Run console API tests**

Run:

```bash
cd lakeon-console && npm test -- connectors-api.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add lakeon-console/src/api/connectors.ts lakeon-console/src/api/import.ts lakeon-console/src/__tests__/connectors-api.test.ts
git commit -m "feat(console): add connector API client"
```

## Task 5: Connector Page and Navigation

**Files:**

- Create: `lakeon-console/src/views/connectors/ConnectorsView.vue`
- Modify: `lakeon-console/src/router/index.ts`
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`

- [ ] **Step 1: Add route and navigation**

Modify `ConsoleLayout.vue` under the `数据源` group:

```ts
{
  title: '数据源',
  items: [
    { label: '连接器', to: '/connectors', icon: '◇' },
  ],
},
```

Modify `router/index.ts` by adding:

```ts
{ path: 'connectors', name: 'Connectors', component: () => import('../views/connectors/ConnectorsView.vue') },
{ path: 'datalake/connections', redirect: '/connectors' },
```

Keep the old route only as a redirect so external links do not break.

- [ ] **Step 2: Create connector page**

Create `lakeon-console/src/views/connectors/ConnectorsView.vue`:

```vue
<template>
  <div class="page-shell">
    <div class="page-header">
      <div>
        <h1>连接器</h1>
        <p>管理外部 PostgreSQL、对象存储 OBS 等数据源，供数据迁移和后续入库能力复用。</p>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">新建 PostgreSQL</button>
    </div>

    <div class="connector-grid">
      <article v-for="connector in connectors" :key="connector.id" class="connector-card">
        <div class="card-top">
          <div>
            <h3>{{ connector.name }}</h3>
            <p>{{ connector.target_summary || typeLabel(connector.type) }}</p>
          </div>
          <span class="status-pill" :class="connector.status.toLowerCase()">{{ statusLabel(connector.status) }}</span>
        </div>
        <div class="meta-row">
          <span>{{ typeLabel(connector.type) }}</span>
          <span>{{ connector.usage_hint || '未使用' }}</span>
        </div>
        <div class="card-actions">
          <button class="btn btn-default btn-small" :disabled="connector.type !== 'POSTGRESQL' || testingId === connector.id" @click="testConnector(connector.id)">
            {{ testingId === connector.id ? '测试中...' : '测试连接' }}
          </button>
          <router-link v-if="connector.type === 'POSTGRESQL'" class="btn btn-default btn-small" to="/import">用于数据迁移</router-link>
        </div>
        <p v-if="connector.last_error" class="error-text">{{ connector.last_error }}</p>
      </article>
    </div>

    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>新建 PostgreSQL 连接器</h3>
          <button class="dialog-close" @click="showCreate = false">&times;</button>
        </div>
        <div class="dialog-body">
          <label class="form-label">名称</label>
          <input v-model="form.name" class="form-input" />
          <label class="form-label">主机</label>
          <input v-model="form.host" class="form-input" />
          <div class="form-row">
            <div class="form-group form-half">
              <label class="form-label">端口</label>
              <input v-model.number="form.port" type="number" class="form-input" />
            </div>
            <div class="form-group form-half">
              <label class="form-label">数据库</label>
              <input v-model="form.dbname" class="form-input" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group form-half">
              <label class="form-label">用户名</label>
              <input v-model="form.user" class="form-input" />
            </div>
            <div class="form-group form-half">
              <label class="form-label">密码</label>
              <input v-model="form.password" type="password" class="form-input" />
            </div>
          </div>
          <p v-if="error" class="error-text">{{ error }}</p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" :disabled="!formValid || creating" @click="createConnector">
            {{ creating ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { connectorsApi, type Connector, type ConnectorStatus, type ConnectorType } from '../../api/connectors'

const connectors = ref<Connector[]>([])
const showCreate = ref(false)
const creating = ref(false)
const testingId = ref('')
const error = ref('')
const form = ref({ name: '', host: '', port: 5432, dbname: '', user: 'postgres', password: '' })

const formValid = computed(() => form.value.name && form.value.host && form.value.port && form.value.dbname && form.value.user && form.value.password)

onMounted(load)

async function load() {
  const res = await connectorsApi.list()
  connectors.value = res.data
}

async function createConnector() {
  creating.value = true
  error.value = ''
  try {
    await connectorsApi.createPostgres(form.value)
    showCreate.value = false
    form.value = { name: '', host: '', port: 5432, dbname: '', user: 'postgres', password: '' }
    await load()
  } catch (e: any) {
    error.value = e.response?.data?.error?.message || e.message || '创建连接器失败'
  } finally {
    creating.value = false
  }
}

async function testConnector(id: string) {
  testingId.value = id
  try {
    await connectorsApi.test(id)
    await load()
  } finally {
    testingId.value = ''
  }
}

function typeLabel(type: ConnectorType) {
  if (type === 'POSTGRESQL') return 'PostgreSQL'
  return '对象存储 OBS'
}

function statusLabel(status: ConnectorStatus) {
  if (status === 'CONNECTED') return '已连接'
  if (status === 'FAILED') return '连接失败'
  return '未测试'
}
</script>

<style scoped>
.page-shell { padding: 56px 64px; }
.page-header { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; margin-bottom: 24px; }
.page-header h1 { margin: 0; font-size: 30px; color: #172033; }
.page-header p { margin: 8px 0 0; color: #6b7890; }
.connector-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
.connector-card { border: 1px solid #d9e2ef; border-radius: 8px; background: #fff; padding: 18px; }
.card-top { display: flex; justify-content: space-between; gap: 16px; }
.card-top h3 { margin: 0; font-size: 18px; color: #172033; }
.card-top p { margin: 6px 0 0; color: #6b7890; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
.meta-row { display: flex; justify-content: space-between; margin-top: 18px; color: #6b7890; font-size: 13px; }
.card-actions { display: flex; gap: 8px; margin-top: 18px; }
.status-pill { border-radius: 999px; padding: 3px 10px; font-size: 12px; height: fit-content; background: #eef2f7; color: #536175; }
.status-pill.connected { background: #e9f8ef; color: #237044; }
.status-pill.failed { background: #fff0f0; color: #b42318; }
.error-text { color: #b42318; font-size: 13px; margin-top: 10px; }
.form-row { display: flex; gap: 12px; }
.form-half { flex: 1; }
.form-label { display: block; margin: 12px 0 4px; color: #64748b; font-size: 13px; }
.form-input { width: 100%; box-sizing: border-box; border: 1px solid #cbd5e1; border-radius: 4px; padding: 8px 10px; }
</style>
```

- [ ] **Step 3: Run console build**

Run:

```bash
cd lakeon-console && npm run build
```

Expected: build passes.

- [ ] **Step 4: Commit Task 5**

```bash
git add lakeon-console/src/views/connectors/ConnectorsView.vue lakeon-console/src/router/index.ts lakeon-console/src/layouts/ConsoleLayout.vue
git commit -m "feat(console): add connectors page"
```

## Task 6: Import Wizard Connector Selection

**Files:**

- Modify: `lakeon-console/src/views/database/ImportWizard.vue`
- Modify: `lakeon-console/src/views/import/ImportEntry.vue`
- Modify: `lakeon-console/src/views/database/ImportTaskDetail.vue`
- Create: `lakeon-console/src/__tests__/ImportWizardConnector.test.ts`

- [ ] **Step 1: Write import wizard test**

Create `lakeon-console/src/__tests__/ImportWizardConnector.test.ts`:

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ImportWizard from '../views/database/ImportWizard.vue'

vi.mock('../api/connectors', () => ({
  connectorsApi: {
    list: vi.fn(() => Promise.resolve({ data: [
      { id: 'conn_pg001', type: 'POSTGRESQL', name: 'Source PG', status: 'CONNECTED', target_summary: 'pg:5432/app', config: {}, usage_count: 0 },
    ] })),
    listPostgresTables: vi.fn(() => Promise.resolve({ data: [{ schema: 'public', table: 'orders', estimated_rows: 2 }] })),
  },
}))

vi.mock('../api/import', () => ({
  importApi: {
    create: vi.fn(() => Promise.resolve({ data: { id: 'imp_1' } })),
  },
}))

describe('ImportWizard connector mode', () => {
  it('shows connector selection when visible', async () => {
    const wrapper = mount(ImportWizard, { props: { dbId: 'db_1', visible: true } })
    await Promise.resolve()

    expect(wrapper.text()).toContain('选择连接器')
    expect(wrapper.text()).toContain('Source PG')
    expect(wrapper.text()).toContain('临时连接')
  })
})
```

- [ ] **Step 2: Run wizard test and verify it fails**

Run:

```bash
cd lakeon-console && npm test -- ImportWizardConnector.test.ts
```

Expected: fail because the wizard does not render connector mode yet.

- [ ] **Step 3: Update wizard imports and state**

In `ImportWizard.vue`, add:

```ts
import { connectorsApi, type Connector } from '../../api/connectors'
```

Add state:

```ts
const connectionMode = ref<'connector' | 'temporary'>('connector')
const connectors = ref<Connector[]>([])
const selectedConnectorId = ref('')
```

Load connectors when visible:

```ts
async function loadConnectors() {
  const res = await connectorsApi.list()
  connectors.value = res.data.filter(c => c.type === 'POSTGRESQL')
  if (!selectedConnectorId.value && connectors.value.length > 0) selectedConnectorId.value = connectors.value[0].id
}
```

Call `loadConnectors()` inside the visible watcher before resetting test state.

- [ ] **Step 4: Update step 1 template**

At the top of Step 1, add mode tabs before manual fields:

```vue
<div class="connector-mode">
  <button class="mode-btn" :class="{ active: connectionMode === 'connector' }" @click="connectionMode = 'connector'">选择连接器</button>
  <button class="mode-btn" :class="{ active: connectionMode === 'temporary' }" @click="connectionMode = 'temporary'">临时连接</button>
</div>

<div v-if="connectionMode === 'connector'" class="form-group">
  <label class="form-label">PostgreSQL 连接器 <span class="required">*</span></label>
  <select v-model="selectedConnectorId" class="form-input">
    <option v-for="connector in connectors" :key="connector.id" :value="connector.id">
      {{ connector.name }} — {{ connector.target_summary }}
    </option>
  </select>
  <div v-if="connectors.length === 0" class="hint-text">还没有 PostgreSQL 连接器，请先到连接器页面创建，或使用临时连接。</div>
</div>

<div v-if="connectionMode === 'temporary'">
  <!-- keep the existing manual host/port/db/user/password fields here -->
</div>
```

Move the current manual fields inside the `temporary` block.

- [ ] **Step 5: Update table loading and create behavior**

Update `connFormValid`:

```ts
const connFormValid = computed(() => {
  if (connectionMode.value === 'connector') return !!selectedConnectorId.value
  return form.value.host && form.value.port && form.value.dbname && form.value.user && form.value.password
})
```

Update `loadSourceTables`:

```ts
if (connectionMode.value === 'connector') {
  const res = await connectorsApi.listPostgresTables(selectedConnectorId.value)
  sourceTables.value = res.data
  return true
}
```

Keep the existing manual `importApi.listSourceTables` branch for temporary connections.

Update `handleCreate`:

```ts
const payload = connectionMode.value === 'connector'
  ? {
      connectorId: selectedConnectorId.value,
      mode: form.value.mode,
      conflictStrategy: form.value.conflictStrategy,
      tables: (form.value.mode === 'SELECTIVE' || form.value.mode === 'SYNC') ? form.value.selectedTables : undefined,
    }
  : {
      sourceHost: form.value.host,
      sourcePort: form.value.port,
      sourceDbname: form.value.dbname,
      sourceUser: form.value.user,
      sourcePassword: form.value.password,
      mode: form.value.mode,
      conflictStrategy: form.value.conflictStrategy,
      tables: (form.value.mode === 'SELECTIVE' || form.value.mode === 'SYNC') ? form.value.selectedTables : undefined,
    }
const res = await importApi.create(props.dbId, payload)
```

- [ ] **Step 6: Update confirmation source display**

Use a computed summary:

```ts
const selectedConnector = computed(() => connectors.value.find(c => c.id === selectedConnectorId.value))
const sourceSummary = computed(() => {
  if (connectionMode.value === 'connector') return selectedConnector.value?.name + ' · ' + selectedConnector.value?.target_summary
  return `${form.value.host}:${form.value.port}/${form.value.dbname}`
})
```

Replace confirmation source row with:

```vue
<div class="summary-row"><span class="summary-label">源数据库:</span> {{ sourceSummary }}</div>
```

- [ ] **Step 7: Update import list/detail display**

In `ImportEntry.vue` and `ImportTaskDetail.vue`, when rendering source, prefer:

```vue
{{ task.connector_name || task.connector_id || `${task.source_host}:${task.source_port}/${task.source_dbname}` }}
```

Keep the old host/db fallback for existing tasks.

- [ ] **Step 8: Add wizard styles**

Add scoped styles to `ImportWizard.vue`:

```css
.connector-mode {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border: 1px solid #d9e2ef;
  border-radius: 6px;
  margin-bottom: 16px;
}
.mode-btn {
  border: 0;
  background: transparent;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
  color: #536175;
}
.mode-btn.active {
  background: #172033;
  color: #fff;
}
.hint-text {
  color: #6b7890;
  font-size: 13px;
  margin-top: 6px;
}
```

- [ ] **Step 9: Run wizard test and build**

Run:

```bash
cd lakeon-console && npm test -- ImportWizardConnector.test.ts && npm run build
```

Expected: PASS.

- [ ] **Step 10: Commit Task 6**

```bash
git add lakeon-console/src/views/database/ImportWizard.vue \
  lakeon-console/src/views/import/ImportEntry.vue \
  lakeon-console/src/views/database/ImportTaskDetail.vue \
  lakeon-console/src/__tests__/ImportWizardConnector.test.ts
git commit -m "feat(console): select connectors in import wizard"
```

## Task 7: Full Verification and Deploy Readiness

**Files:**

- Modify only if verification exposes defects in files touched by Tasks 1-6.

- [ ] **Step 1: Run backend focused tests**

Run:

```bash
cd lakeon-api && mvn -Dtest=ConnectorSecretCryptoTest,ConnectorServiceTest,ImportServiceConnectorTest,ImportServiceSyncTest test
```

Expected: PASS.

- [ ] **Step 2: Run backend package**

Run:

```bash
cd lakeon-api && mvn clean package -DskipTests
```

Expected: build success and no stale class issues.

- [ ] **Step 3: Run console tests and build**

Run:

```bash
cd lakeon-console && npm test -- connectors-api.test.ts ImportWizardConnector.test.ts && npm run build
```

Expected: PASS.

- [ ] **Step 4: Check worktree**

Run:

```bash
git status --short --branch
```

Expected: only intentional changes are present. Preserve unrelated `deploy/cce/sites/hwstaff/values.yaml` if still modified.

- [ ] **Step 5: Manual smoke checklist**

Use local or deployed console with a reachable PostgreSQL source:

```text
1. Open /connectors.
2. Create PostgreSQL connector.
3. Test connector and confirm status becomes 已连接.
4. Open /import and choose target DBay database.
5. Start import wizard.
6. Select the connector.
7. Confirm table list loads.
8. Create FULL import.
9. Confirm import task appears and shows connector lineage.
```

- [ ] **Step 6: Commit verification fixes if any**

If Step 1-5 required fixes:

```bash
git add <fixed-files>
git commit -m "fix(connectors): address verification issues"
```

If no fixes were needed, do not create an empty commit.
