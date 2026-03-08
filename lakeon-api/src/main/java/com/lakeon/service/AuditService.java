package com.lakeon.service;

import com.lakeon.model.dto.AuditConfigResponse;
import com.lakeon.model.dto.AuditLogResponse;
import com.lakeon.model.dto.UpdateAuditConfigRequest;
import com.lakeon.model.entity.AuditConfigEntity;
import com.lakeon.model.entity.AuditLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.AuditConfigRepository;
import com.lakeon.repository.AuditLogRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final Pattern DDL_PATTERN = Pattern.compile(
            "^\\s*(CREATE|ALTER|DROP|TRUNCATE|COMMENT|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DML_PATTERN = Pattern.compile(
            "^\\s*(INSERT|UPDATE|DELETE|MERGE)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "^\\s*(SELECT|WITH)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT_PATTERN = Pattern.compile(
            "(?:FROM|INTO|UPDATE|TABLE|ON|INDEX\\s+ON)\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?([\"\\w]+\\.)?([\"\\w]+)",
            Pattern.CASE_INSENSITIVE);

    private final AuditConfigRepository auditConfigRepository;
    private final AuditLogRepository auditLogRepository;
    private final DatabaseRepository databaseRepository;

    public AuditService(AuditConfigRepository auditConfigRepository,
                        AuditLogRepository auditLogRepository,
                        DatabaseRepository databaseRepository) {
        this.auditConfigRepository = auditConfigRepository;
        this.auditLogRepository = auditLogRepository;
        this.databaseRepository = databaseRepository;
    }

    public AuditConfigResponse getConfig(TenantEntity tenant, String dbId) {
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        AuditConfigEntity config = auditConfigRepository.findByDatabaseId(dbId)
                .orElseGet(() -> createDefaultConfig(dbId, tenant.getId()));

        return toConfigResponse(config);
    }

    @Transactional
    public AuditConfigResponse updateConfig(TenantEntity tenant, String dbId, UpdateAuditConfigRequest request) {
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        AuditConfigEntity config = auditConfigRepository.findByDatabaseId(dbId)
                .orElseGet(() -> createDefaultConfig(dbId, tenant.getId()));

        if (request.enabled() != null) config.setEnabled(request.enabled());
        if (request.logDdl() != null) config.setLogDdl(request.logDdl());
        if (request.logDml() != null) config.setLogDml(request.logDml());
        if (request.logSelect() != null) config.setLogSelect(request.logSelect());
        if (request.retentionDays() != null) config.setRetentionDays(request.retentionDays());

        config = auditConfigRepository.save(config);
        log.info("Updated audit config for database {}: enabled={}, ddl={}, dml={}, select={}, retention={}",
                dbId, config.isEnabled(), config.isLogDdl(), config.isLogDml(), config.isLogSelect(),
                config.getRetentionDays());

        return toConfigResponse(config);
    }

    public Map<String, Object> getLogs(TenantEntity tenant, String dbId, String type, int page, int size) {
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        Page<AuditLogEntity> result;

        if (type != null && !type.isBlank()) {
            result = auditLogRepository.findByDatabaseIdAndStatementTypeOrderByTimestampDesc(
                    dbId, type.toUpperCase(), pageable);
        } else {
            result = auditLogRepository.findByDatabaseIdOrderByTimestampDesc(dbId, pageable);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", result.getContent().stream().map(this::toLogResponse).toList());
        response.put("total", result.getTotalElements());
        response.put("page", result.getNumber());
        response.put("total_pages", result.getTotalPages());
        return response;
    }

    /**
     * Record an audit log entry when SQL is executed through the API's SQL editor.
     * This is a simulated/stub approach since pgaudit may not be available in Neon compute.
     */
    @Transactional
    public void recordAuditLog(String dbId, String tenantId, String userName, String sql,
                               String clientAddr, long durationMs) {
        AuditConfigEntity config = auditConfigRepository.findByDatabaseId(dbId).orElse(null);
        if (config == null || !config.isEnabled()) {
            return;
        }

        String statementType = classifyStatement(sql);
        if (statementType == null) return;

        // Check if this type of statement should be logged
        boolean shouldLog = switch (statementType) {
            case "DDL" -> config.isLogDdl();
            case "DML" -> config.isLogDml();
            case "SELECT" -> config.isLogSelect();
            default -> false;
        };

        if (!shouldLog) return;

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setDatabaseId(dbId);
        auditLog.setTenantId(tenantId);
        auditLog.setUserName(userName);
        auditLog.setStatement(sql);
        auditLog.setStatementType(statementType);
        auditLog.setObjectName(extractObjectName(sql));
        auditLog.setClientAddr(clientAddr);
        auditLog.setDuration(durationMs);
        auditLog.setTimestamp(Instant.now());

        auditLogRepository.save(auditLog);
        log.debug("Recorded audit log for database {}: type={}, sql={}", dbId, statementType,
                sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);
    }

    @Transactional
    public void cleanupOldLogs(String dbId, int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        auditLogRepository.deleteByDatabaseIdAndTimestampBefore(dbId, cutoff);
        log.info("Cleaned up audit logs older than {} days for database {}", retentionDays, dbId);
    }

    /**
     * Admin API: global audit log query with optional filters.
     */
    public Map<String, Object> getLogsForAdmin(String tenantId, String dbId, String type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        Page<AuditLogEntity> result;

        if (tenantId != null && dbId != null && type != null) {
            result = auditLogRepository.findByTenantIdAndDatabaseIdAndStatementTypeOrderByTimestampDesc(
                    tenantId, dbId, type.toUpperCase(), pageable);
        } else if (tenantId != null && dbId != null) {
            result = auditLogRepository.findByTenantIdAndDatabaseIdOrderByTimestampDesc(
                    tenantId, dbId, pageable);
        } else if (tenantId != null && type != null) {
            result = auditLogRepository.findByTenantIdAndStatementTypeOrderByTimestampDesc(
                    tenantId, type.toUpperCase(), pageable);
        } else if (tenantId != null) {
            result = auditLogRepository.findByTenantIdOrderByTimestampDesc(tenantId, pageable);
        } else if (type != null) {
            result = auditLogRepository.findByStatementTypeOrderByTimestampDesc(type.toUpperCase(), pageable);
        } else {
            result = auditLogRepository.findAllByOrderByTimestampDesc(pageable);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", result.getContent().stream().map(this::toLogResponse).toList());
        response.put("total", result.getTotalElements());
        response.put("page", result.getNumber());
        response.put("total_pages", result.getTotalPages());
        return response;
    }

    private AuditConfigEntity createDefaultConfig(String dbId, String tenantId) {
        AuditConfigEntity config = new AuditConfigEntity();
        config.setDatabaseId(dbId);
        config.setTenantId(tenantId);
        config.setEnabled(false);
        config.setLogDdl(true);
        config.setLogDml(false);
        config.setLogSelect(false);
        config.setRetentionDays(30);
        return auditConfigRepository.save(config);
    }

    static String classifyStatement(String sql) {
        if (sql == null || sql.isBlank()) return null;
        String trimmed = sql.trim();
        if (DDL_PATTERN.matcher(trimmed).find()) return "DDL";
        if (DML_PATTERN.matcher(trimmed).find()) return "DML";
        if (SELECT_PATTERN.matcher(trimmed).find()) return "SELECT";
        return null;
    }

    static String extractObjectName(String sql) {
        if (sql == null) return null;
        Matcher m = OBJECT_PATTERN.matcher(sql);
        if (m.find()) {
            String schema = m.group(1);
            String table = m.group(2);
            if (schema != null) {
                return schema + table;
            }
            return table;
        }
        return null;
    }

    private AuditConfigResponse toConfigResponse(AuditConfigEntity entity) {
        return new AuditConfigResponse(
                entity.getId(),
                entity.getDatabaseId(),
                entity.getTenantId(),
                entity.isEnabled(),
                entity.isLogDdl(),
                entity.isLogDml(),
                entity.isLogSelect(),
                entity.getRetentionDays(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private AuditLogResponse toLogResponse(AuditLogEntity entity) {
        return new AuditLogResponse(
                entity.getId(),
                entity.getDatabaseId(),
                entity.getTenantId(),
                entity.getTimestamp(),
                entity.getUserName(),
                entity.getStatement(),
                entity.getStatementType(),
                entity.getObjectName(),
                entity.getClientAddr(),
                entity.getDuration()
        );
    }
}
