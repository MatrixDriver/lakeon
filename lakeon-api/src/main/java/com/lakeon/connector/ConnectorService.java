package com.lakeon.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.connector.ConnectorDtos.ConnectorResponse;
import com.lakeon.connector.ConnectorDtos.ConnectorTestResponse;
import com.lakeon.connector.ConnectorDtos.CreateConnectorRequest;
import com.lakeon.connector.ConnectorDtos.PostgresConnectionSnapshot;
import com.lakeon.model.dto.SourceTableInfo;
import com.lakeon.obs.connection.ObsConnectionEntity;
import com.lakeon.obs.connection.ObsConnectionRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
        if (request == null) {
            throw new BadRequestException("Missing connector request");
        }
        if (request.type() == null) {
            throw new BadRequestException("Missing connector field: type");
        }
        if (request.type() != ConnectorType.POSTGRESQL) {
            throw new BadRequestException("Only PostgreSQL connector creation is supported in Phase 1");
        }
        Map<String, Object> config = normalizedPostgresConfig(request.config());
        Map<String, Object> secret = request.secret() == null ? Map.of() : request.secret();
        stringValue(config, "host");
        stringValue(config, "dbname");
        stringValue(secret, "user");
        stringValue(secret, "password");

        ConnectorEntity entity = new ConnectorEntity();
        entity.setTenantId(tenantId);
        entity.setType(request.type());
        entity.setName(requireText(request.name(), "name"));
        entity.setStatus(ConnectorStatus.UNTESTED);
        entity.setConfigJson(writeJson(config));
        entity.setEncryptedSecretJson(crypto.encrypt(writeJson(secret)));
        return toResponse(connectorRepository.save(entity), 0L);
    }

    @Transactional(readOnly = true)
    public List<ConnectorResponse> list(String tenantId) {
        List<ConnectorResponse> postgres = connectorRepository.findAllByTenantIdOrderByUpdatedAtDesc(tenantId)
            .stream()
            .map(entity -> toResponse(entity, 0L))
            .toList();
        if (obsConnectionRepository == null) {
            return postgres;
        }
        List<ConnectorResponse> obs = obsConnectionRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
            .stream()
            .map(this::toObsResponse)
            .toList();
        return Stream.concat(postgres.stream(), obs.stream()).toList();
    }

    @Transactional(readOnly = true)
    public ConnectorResponse get(String tenantId, String id) {
        return connectorRepository.findByIdAndTenantId(id, tenantId)
            .map(entity -> toResponse(entity, 0L))
            .orElseGet(() -> getObs(tenantId, id));
    }

    @Transactional
    public ConnectorTestResponse test(String tenantId, String id) {
        ConnectorEntity entity = find(tenantId, id);
        if (entity.getType() != ConnectorType.POSTGRESQL) {
            throw new BadRequestException("Connector test only supports PostgreSQL in Phase 1");
        }
        Map<String, Object> metadata = postgresAdapter.test(resolvePostgres(entity));
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
        return resolvePostgres(find(tenantId, id));
    }

    private PostgresConnectionSnapshot resolvePostgres(ConnectorEntity entity) {
        if (entity.getType() != ConnectorType.POSTGRESQL) {
            throw new BadRequestException("Connector is not PostgreSQL: " + entity.getId());
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

    private ConnectorResponse getObs(String tenantId, String id) {
        if (obsConnectionRepository == null) {
            throw new NotFoundException("Connector not found: " + id);
        }
        return obsConnectionRepository.findByIdAndTenantId(id, tenantId)
            .map(this::toObsResponse)
            .orElseThrow(() -> new NotFoundException("Connector not found: " + id));
    }

    private ConnectorResponse toResponse(ConnectorEntity entity, Long usageCount) {
        Map<String, Object> config = readJson(entity.getConfigJson());
        return new ConnectorResponse(
            entity.getId(),
            entity.getType(),
            entity.getName(),
            entity.getStatus(),
            config,
            targetSummary(entity.getType(), config),
            entity.getLastTestedAt(),
            entity.getLastError(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            usageCount,
            "数据库导入"
        );
    }

    private ConnectorResponse toObsResponse(ObsConnectionEntity entity) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("domain_name", entity.getDomainName());
        config.put("agency_name", entity.getAgencyName());
        config.put("obs_endpoint", entity.getObsEndpoint());
        config.put("bucket", entity.getBucket());
        config.put("base_path", entity.getBasePath());
        return new ConnectorResponse(
            entity.getId(),
            ConnectorType.OBS,
            entity.getName(),
            obsStatus(entity.getStatus()),
            config,
            entity.getBucket(),
            entity.getLastTestedAt(),
            null,
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            0L,
            "对象存储 OBS"
        );
    }

    private ConnectorStatus obsStatus(String status) {
        if ("FAILED".equalsIgnoreCase(status)) {
            return ConnectorStatus.FAILED;
        }
        return ConnectorStatus.CONNECTED;
    }

    private String targetSummary(ConnectorType type, Map<String, Object> config) {
        if (type == ConnectorType.POSTGRESQL) {
            return stringValue(config, "host") + ":" + intValue(config, "port", 5432) + "/" + stringValue(config, "dbname");
        }
        return "";
    }

    private Map<String, Object> sanitizedPostgresConfig(Map<String, Object> config) {
        Map<String, Object> sanitized = new LinkedHashMap<>(config == null ? Map.of() : config);
        sanitized.remove("user");
        sanitized.remove("password");
        return sanitized;
    }

    private Map<String, Object> normalizedPostgresConfig(Map<String, Object> config) {
        Map<String, Object> normalized = sanitizedPostgresConfig(config);
        normalized.put("port", postgresPortValue(normalized.get("port")));
        return normalized;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new BadRequestException("Invalid connector JSON");
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BadRequestException("Invalid connector JSON");
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Missing connector field: " + field);
        }
        return value.trim();
    }

    private String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BadRequestException("Missing connector field: " + key);
        }
        return String.valueOf(value);
    }

    private Integer intValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Integer postgresPortValue(Object value) {
        int port;
        if (value == null) {
            port = 5432;
        } else if (value instanceof Number number) {
            port = number.intValue();
        } else {
            try {
                port = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("Invalid connector field: port");
            }
        }
        if (port < 1 || port > 65535) {
            throw new BadRequestException("Invalid connector field: port");
        }
        return port;
    }
}
