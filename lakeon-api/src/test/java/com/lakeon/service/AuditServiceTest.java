package com.lakeon.service;

import com.lakeon.model.dto.AuditConfigResponse;
import com.lakeon.model.dto.UpdateAuditConfigRequest;
import com.lakeon.model.entity.AuditConfigEntity;
import com.lakeon.model.entity.AuditLogEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.AuditConfigRepository;
import com.lakeon.repository.AuditLogRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService 单元测试")
class AuditServiceTest {

    @Mock
    private AuditConfigRepository auditConfigRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @InjectMocks
    private AuditService auditService;

    private TenantEntity testTenant;
    private DatabaseEntity testDatabase;

    @BeforeEach
    void setUp() {
        testTenant = new TenantEntity();
        testTenant.setId("tn_test001");

        testDatabase = new DatabaseEntity();
        testDatabase.setId("db_test001");
        testDatabase.setTenantId("tn_test001");
        testDatabase.setName("my-db");
        testDatabase.setStatus(DatabaseStatus.RUNNING);
    }

    @Nested
    @DisplayName("获取审计配置")
    class GetConfig {

        @Test
        @DisplayName("UT-SVC-AU-001: 已有配置 — 返回现有配置")
        void getConfig_existing() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            AuditConfigEntity config = createConfig("ak_test001", "db_test001", true);
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.of(config));

            AuditConfigResponse result = auditService.getConfig(testTenant, "db_test001");

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo("ak_test001");
            assertThat(result.enabled()).isTrue();
            assertThat(result.logDdl()).isTrue();
        }

        @Test
        @DisplayName("UT-SVC-AU-002: 无配置 — 创建默认配置")
        void getConfig_createDefault() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.empty());
            when(auditConfigRepository.save(any(AuditConfigEntity.class)))
                    .thenAnswer(inv -> {
                        AuditConfigEntity e = inv.getArgument(0);
                        e.setId("ak_new001");
                        e.setCreatedAt(Instant.now());
                        e.setUpdatedAt(Instant.now());
                        return e;
                    });

            AuditConfigResponse result = auditService.getConfig(testTenant, "db_test001");

            assertThat(result).isNotNull();
            assertThat(result.enabled()).isFalse();
            assertThat(result.logDdl()).isTrue();
            assertThat(result.retentionDays()).isEqualTo(30);
            verify(auditConfigRepository).save(any(AuditConfigEntity.class));
        }

        @Test
        @DisplayName("UT-SVC-AU-003: 数据库不存在 — 抛出 NotFoundException")
        void getConfig_dbNotFound() {
            when(databaseRepository.findByIdAndTenantId("db_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> auditService.getConfig(testTenant, "db_nonexist"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("更新审计配置")
    class UpdateConfig {

        @Test
        @DisplayName("UT-SVC-AU-004: 更新配置 — 启用审计和 DML 日志")
        void updateConfig_enableAudit() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            AuditConfigEntity config = createConfig("ak_test001", "db_test001", false);
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.of(config));
            when(auditConfigRepository.save(any(AuditConfigEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateAuditConfigRequest(true, null, true, null, 60);
            AuditConfigResponse result = auditService.updateConfig(testTenant, "db_test001", request);

            assertThat(result.enabled()).isTrue();
            assertThat(result.logDml()).isTrue();
            assertThat(result.retentionDays()).isEqualTo(60);
            // logDdl should remain unchanged (true by default)
            assertThat(result.logDdl()).isTrue();
        }

        @Test
        @DisplayName("UT-SVC-AU-005: 数据库不存在 — 抛出 NotFoundException")
        void updateConfig_dbNotFound() {
            when(databaseRepository.findByIdAndTenantId("db_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            var request = new UpdateAuditConfigRequest(true, null, null, null, null);
            assertThatThrownBy(() -> auditService.updateConfig(testTenant, "db_nonexist", request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("查询审计日志")
    class GetLogs {

        @Test
        @DisplayName("UT-SVC-AU-006: 分页查询 — 返回日志列表")
        void getLogs_success() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            AuditLogEntity log1 = createAuditLog("al_001", "DDL", "CREATE TABLE users");
            AuditLogEntity log2 = createAuditLog("al_002", "SELECT", "SELECT * FROM users");
            Page<AuditLogEntity> page = new PageImpl<>(List.of(log1, log2), PageRequest.of(0, 20), 2);
            when(auditLogRepository.findByDatabaseIdOrderByTimestampDesc(eq("db_test001"), any(PageRequest.class)))
                    .thenReturn(page);

            Map<String, Object> result = auditService.getLogs(testTenant, "db_test001", null, 0, 20);

            assertThat(result).containsKey("data");
            assertThat(result).containsEntry("total", 2L);
            assertThat(result).containsEntry("page", 0);
        }

        @Test
        @DisplayName("UT-SVC-AU-007: 按类型过滤 — 返回指定类型日志")
        void getLogs_filterByType() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            AuditLogEntity log1 = createAuditLog("al_001", "DDL", "CREATE TABLE users");
            Page<AuditLogEntity> page = new PageImpl<>(List.of(log1), PageRequest.of(0, 20), 1);
            when(auditLogRepository.findByDatabaseIdAndStatementTypeOrderByTimestampDesc(
                    eq("db_test001"), eq("DDL"), any(PageRequest.class)))
                    .thenReturn(page);

            Map<String, Object> result = auditService.getLogs(testTenant, "db_test001", "DDL", 0, 20);

            assertThat(result).containsEntry("total", 1L);
        }
    }

    @Nested
    @DisplayName("记录审计日志")
    class RecordAuditLog {

        @Test
        @DisplayName("UT-SVC-AU-008: 审计已启用 + DDL — 记录日志")
        void recordLog_ddlEnabled() {
            AuditConfigEntity config = createConfig("ak_test001", "db_test001", true);
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.of(config));

            auditService.recordAuditLog("db_test001", "tn_test001", "cloud_admin",
                    "CREATE TABLE users (id int)", null, 50);

            verify(auditLogRepository).save(any(AuditLogEntity.class));
        }

        @Test
        @DisplayName("UT-SVC-AU-009: 审计未启用 — 不记录日志")
        void recordLog_disabled() {
            AuditConfigEntity config = createConfig("ak_test001", "db_test001", false);
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.of(config));

            auditService.recordAuditLog("db_test001", "tn_test001", "cloud_admin",
                    "CREATE TABLE users (id int)", null, 50);

            verify(auditLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-SVC-AU-010: DML 未启用 — 不记录 INSERT")
        void recordLog_dmlDisabled() {
            AuditConfigEntity config = createConfig("ak_test001", "db_test001", true);
            config.setLogDml(false);
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.of(config));

            auditService.recordAuditLog("db_test001", "tn_test001", "cloud_admin",
                    "INSERT INTO users VALUES (1, 'test')", null, 30);

            verify(auditLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-SVC-AU-011: 无配置 — 不记录日志")
        void recordLog_noConfig() {
            when(auditConfigRepository.findByDatabaseId("db_test001"))
                    .thenReturn(Optional.empty());

            auditService.recordAuditLog("db_test001", "tn_test001", "cloud_admin",
                    "SELECT 1", null, 5);

            verify(auditLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("清理旧日志")
    class CleanupOldLogs {

        @Test
        @DisplayName("UT-SVC-AU-012: 清理旧日志 — 调用删除方法")
        void cleanup_success() {
            auditService.cleanupOldLogs("db_test001", 30);

            verify(auditLogRepository).deleteByDatabaseIdAndTimestampBefore(eq("db_test001"), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("SQL 分类")
    class SqlClassification {

        @Test
        @DisplayName("UT-SVC-AU-013: DDL 语句分类")
        void classifyDdl() {
            assertThat(AuditService.classifyStatement("CREATE TABLE users (id int)")).isEqualTo("DDL");
            assertThat(AuditService.classifyStatement("ALTER TABLE users ADD COLUMN name text")).isEqualTo("DDL");
            assertThat(AuditService.classifyStatement("DROP TABLE users")).isEqualTo("DDL");
            assertThat(AuditService.classifyStatement("  TRUNCATE TABLE users")).isEqualTo("DDL");
        }

        @Test
        @DisplayName("UT-SVC-AU-014: DML 语句分类")
        void classifyDml() {
            assertThat(AuditService.classifyStatement("INSERT INTO users VALUES (1)")).isEqualTo("DML");
            assertThat(AuditService.classifyStatement("UPDATE users SET name = 'x'")).isEqualTo("DML");
            assertThat(AuditService.classifyStatement("DELETE FROM users WHERE id = 1")).isEqualTo("DML");
        }

        @Test
        @DisplayName("UT-SVC-AU-015: SELECT 语句分类")
        void classifySelect() {
            assertThat(AuditService.classifyStatement("SELECT * FROM users")).isEqualTo("SELECT");
            assertThat(AuditService.classifyStatement("WITH cte AS (SELECT 1) SELECT * FROM cte")).isEqualTo("SELECT");
        }

        @Test
        @DisplayName("UT-SVC-AU-016: 空/未知语句 — 返回 null")
        void classifyUnknown() {
            assertThat(AuditService.classifyStatement(null)).isNull();
            assertThat(AuditService.classifyStatement("")).isNull();
            assertThat(AuditService.classifyStatement("EXPLAIN SELECT 1")).isNull();
        }
    }

    @Nested
    @DisplayName("对象名提取")
    class ObjectNameExtraction {

        @Test
        @DisplayName("UT-SVC-AU-017: 从 SQL 提取表名")
        void extractObjectName() {
            assertThat(AuditService.extractObjectName("SELECT * FROM users")).isEqualTo("users");
            assertThat(AuditService.extractObjectName("INSERT INTO orders VALUES (1)")).isEqualTo("orders");
            assertThat(AuditService.extractObjectName("UPDATE products SET name = 'x'")).isEqualTo("products");
            assertThat(AuditService.extractObjectName("CREATE TABLE IF NOT EXISTS logs (id int)")).isEqualTo("logs");
        }

        @Test
        @DisplayName("UT-SVC-AU-018: 带 schema 的表名")
        void extractObjectNameWithSchema() {
            assertThat(AuditService.extractObjectName("SELECT * FROM public.users")).isEqualTo("public.users");
        }
    }

    private AuditConfigEntity createConfig(String id, String dbId, boolean enabled) {
        AuditConfigEntity config = new AuditConfigEntity();
        config.setId(id);
        config.setDatabaseId(dbId);
        config.setTenantId("tn_test001");
        config.setEnabled(enabled);
        config.setLogDdl(true);
        config.setLogDml(false);
        config.setLogSelect(false);
        config.setRetentionDays(30);
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        return config;
    }

    private AuditLogEntity createAuditLog(String id, String type, String sql) {
        AuditLogEntity log = new AuditLogEntity();
        log.setId(id);
        log.setDatabaseId("db_test001");
        log.setTenantId("tn_test001");
        log.setTimestamp(Instant.now());
        log.setUserName("cloud_admin");
        log.setStatement(sql);
        log.setStatementType(type);
        log.setDuration(50L);
        return log;
    }
}
