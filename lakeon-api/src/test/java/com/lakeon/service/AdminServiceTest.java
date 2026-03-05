package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService 单元测试")
class AdminServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private DatabaseRepository databaseRepository;
    @Mock private OperationLogRepository operationLogRepository;
    @Mock private NeonApiClient neonApiClient;
    @Mock private DataSource dataSource;
    @Mock private UsageMeteringService usageMeteringService;

    private LakeonProperties props;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        // Set OBS config
        var obs = new LakeonProperties.ObsConfig();
        obs.setEndpoint("obs.cn-north-4.myhuaweicloud.com");
        obs.setBucket("lakeon-storage");
        obs.setAccessKey("test-ak");
        obs.setSecretKey("test-sk");
        props.setObs(obs);

        // Set cost config
        var cost = new LakeonProperties.CostConfig();
        cost.setCceClusterHourly(1.0);
        cost.setCceNodeHourly(1.5);
        cost.setCceNodeCount(3);
        cost.setElbMonthly(30);
        cost.setRdsMonthly(500);
        cost.setEipMonthly(150);
        cost.setComputeCuHourly(0.5);
        cost.setObsPerGbMonthly(0.099);
        props.setCost(cost);

        adminService = new AdminService(
                tenantRepository, databaseRepository, operationLogRepository,
                neonApiClient, props, dataSource, usageMeteringService);
    }

    @Nested
    @DisplayName("OBS 连通性检查")
    class CheckObs {

        @Test
        @DisplayName("UT-ADM-OBS-001: OBS 未配置 — 返回 unhealthy")
        void checkObs_notConfigured() {
            props.getObs().setEndpoint("");
            props.getObs().setBucket("");

            var result = adminService.checkObs();

            assertThat(result.get("status")).isEqualTo("unhealthy");
            assertThat(result.get("error")).isEqualTo("OBS not configured");
        }

        @Test
        @DisplayName("UT-ADM-OBS-002: OBS 已配置 — 返回 endpoint 和 bucket 信息")
        void checkObs_configured_returnsInfo() {
            when(databaseRepository.findAll()).thenReturn(List.of());

            var result = adminService.checkObs();

            assertThat(result.get("endpoint")).isEqualTo("obs.cn-north-4.myhuaweicloud.com");
            assertThat(result.get("bucket")).isEqualTo("lakeon-storage");
            // The HTTP call may fail in test (no actual OBS), but we verify structure
            assertThat(result).containsKey("status");
        }

        @Test
        @DisplayName("UT-ADM-OBS-003: 桶容量估算 — 基于数据库数量")
        void checkObs_estimatesStorage() {
            var db1 = buildDatabase("db_1", 10);
            var db2 = buildDatabase("db_2", 20);
            when(databaseRepository.findAll()).thenReturn(List.of(db1, db2));

            var result = adminService.checkObs();

            // 10*0.1 + 20*0.1 = 3.0 GB estimated
            assertThat(result.get("total_objects_estimate")).isEqualTo(2);
            assertThat(result.get("total_size_gb_estimate")).isEqualTo(3.0);
        }
    }

    @Nested
    @DisplayName("OBS 集成到 checkAllComponents")
    class CheckAllComponents {

        @Test
        @DisplayName("UT-ADM-HEALTH-001: checkAllComponents 包含 OBS")
        void checkAllComponents_includesObs() {
            when(databaseRepository.findAll()).thenReturn(List.of());

            var result = adminService.checkAllComponents();

            assertThat(result).containsKey("obs");
            @SuppressWarnings("unchecked")
            var obsHealth = (Map<String, Object>) result.get("obs");
            assertThat(obsHealth).containsKey("status");
        }
    }

    @Nested
    @DisplayName("日成本趋势")
    class CostTrend {

        @Test
        @DisplayName("UT-ADM-COST-001: 返回指定天数的趋势数据")
        void getCostTrend_returnsDays() {
            when(tenantRepository.findAll()).thenReturn(List.of());

            var result = adminService.getCostTrend(7);

            assertThat(result).hasSize(7);
        }

        @Test
        @DisplayName("UT-ADM-COST-002: 每天包含 fixed_cost, compute_cost, total_cost")
        void getCostTrend_containsFields() {
            when(tenantRepository.findAll()).thenReturn(List.of());

            var result = adminService.getCostTrend(3);

            for (var day : result) {
                assertThat(day).containsKeys("date", "fixed_cost", "compute_cost", "total_cost");
            }
        }

        @Test
        @DisplayName("UT-ADM-COST-003: 固定成本按日均摊正确")
        void getCostTrend_fixedCostCalculation() {
            when(tenantRepository.findAll()).thenReturn(List.of());

            var result = adminService.getCostTrend(1);

            // Daily fixed = (1.0*24) + (1.5*3*24) + (30/30) + (500/30) + (150/30)
            //             = 24 + 108 + 1 + 16.67 + 5 = 154.67
            var day = result.get(0);
            double fixedCost = ((Number) day.get("fixed_cost")).doubleValue();
            assertThat(fixedCost).isCloseTo(154.67, org.assertj.core.data.Offset.offset(0.1));
        }

        @Test
        @DisplayName("UT-ADM-COST-004: 有租户时计算 compute 成本")
        void getCostTrend_withComputeCost() {
            var tenant = new TenantEntity();
            tenant.setId("tn_cost01");
            tenant.setName("cost-tenant");
            when(tenantRepository.findAll()).thenReturn(List.of(tenant));
            when(usageMeteringService.getTenantComputeCuHours(eq("tn_cost01"), any(), any()))
                    .thenReturn(10.0);

            var result = adminService.getCostTrend(1);

            var day = result.get(0);
            double computeCost = ((Number) day.get("compute_cost")).doubleValue();
            // 10 CU·hours * 0.5 = 5.0
            assertThat(computeCost).isEqualTo(5.0);
        }

        @Test
        @DisplayName("UT-ADM-COST-005: date 格式为 YYYY-MM-DD")
        void getCostTrend_dateFormat() {
            when(tenantRepository.findAll()).thenReturn(List.of());

            var result = adminService.getCostTrend(1);

            String date = (String) result.get(0).get("date");
            assertThat(date).matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    private DatabaseEntity buildDatabase(String id, int storageLimitGb) {
        var db = new DatabaseEntity();
        db.setId(id);
        db.setName("db-" + id);
        db.setTenantId("tn_test");
        db.setStatus(DatabaseStatus.RUNNING);
        db.setStorageLimitGb(storageLimitGb);
        db.setComputeSize("1CU");
        return db;
    }
}
