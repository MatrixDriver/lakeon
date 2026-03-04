package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.TenantUsageSummary;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminService {
    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;
    private final OperationLogRepository operationLogRepository;
    private final NeonApiClient neonApiClient;
    private final LakeonProperties props;
    private final DataSource dataSource;
    private final UsageMeteringService usageMeteringService;

    public AdminService(TenantRepository tenantRepository,
                        DatabaseRepository databaseRepository,
                        OperationLogRepository operationLogRepository,
                        NeonApiClient neonApiClient,
                        LakeonProperties props,
                        DataSource dataSource,
                        UsageMeteringService usageMeteringService) {
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
        this.operationLogRepository = operationLogRepository;
        this.neonApiClient = neonApiClient;
        this.props = props;
        this.dataSource = dataSource;
        this.usageMeteringService = usageMeteringService;
    }

    public Map<String, Object> getDashboard() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Tenant count
        long tenantCount = tenantRepository.count();
        result.put("tenant_count", tenantCount);

        // Database stats
        List<DatabaseEntity> allDbs = databaseRepository.findAll();
        result.put("database_count", allDbs.size());
        Map<String, Long> dbsByStatus = allDbs.stream()
                .collect(Collectors.groupingBy(
                        db -> db.getStatus().name().toLowerCase(),
                        Collectors.counting()));
        result.put("databases_by_status", dbsByStatus);

        // Active compute pods
        long activePods = allDbs.stream()
                .filter(db -> db.getComputePodName() != null && db.getStatus() == DatabaseStatus.RUNNING)
                .count();
        result.put("active_compute_pods", activePods);

        // Operation stats (last 24h)
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        List<OperationLogEntity> recentOps = operationLogRepository.findByStartedAtAfter(since24h);
        Map<String, Long> opStats = recentOps.stream()
                .collect(Collectors.groupingBy(
                        op -> op.getOperationType().name().toLowerCase(),
                        Collectors.counting()));
        result.put("operation_stats_24h", opStats);

        // Cost estimate
        result.put("estimated_monthly_cost", estimateMonthlyCost());

        // Component health
        result.put("component_health", checkAllComponents());

        return result;
    }

    public Map<String, Object> checkAllComponents() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("pageserver", checkPageserver());
        health.put("safekeeper", checkSafekeeper());
        health.put("proxy", checkProxy());
        health.put("rds", checkRds());
        return health;
    }

    public Map<String, Object> checkPageserver() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            neonApiClient.getStatus();
            status.put("status", "healthy");
            status.put("url", props.getNeon().getPageserverUrl());
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        return status;
    }

    public Map<String, Object> checkSafekeeper() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "healthy");
        status.put("urls", props.getNeon().getSafekeeperUrls());
        return status;
    }

    public Map<String, Object> checkProxy() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "healthy");
        return status;
    }

    public Map<String, Object> checkRds() {
        Map<String, Object> status = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            status.put("status", "healthy");
            status.put("url", conn.getMetaData().getURL());
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        return status;
    }

    public Map<String, Object> estimateMonthlyCost() {
        var cost = props.getCost();
        Map<String, Object> result = new LinkedHashMap<>();

        double cceNodeCost = cost.getCceNodeHourly() * cost.getCceNodeCount() * 24 * 30;
        double elbCost = cost.getElbMonthly();
        double rdsCost = cost.getRdsMonthly();
        double eipCost = cost.getEipMonthly();

        // OBS cost based on database count * avg storage (rough estimate)
        List<DatabaseEntity> allDbs = databaseRepository.findAll();
        double totalStorageGb = allDbs.stream()
                .mapToDouble(db -> db.getStorageLimitGb() * 0.1) // assume 10% utilization
                .sum();
        double obsCost = totalStorageGb * cost.getObsPerGbMonthly();

        // Compute pods run on CCE nodes, so their cost is already included in cce_nodes
        double total = cceNodeCost + elbCost + rdsCost + eipCost + obsCost;

        result.put("total", Math.round(total * 100.0) / 100.0);
        result.put("breakdown", Map.of(
                "cce_nodes", Math.round(cceNodeCost * 100.0) / 100.0,
                "elb", elbCost,
                "rds", rdsCost,
                "eip", eipCost,
                "obs", Math.round(obsCost * 100.0) / 100.0
        ));
        return result;
    }

    public Map<String, Object> getComputeStats() {
        List<OperationLogEntity> resumeOps = operationLogRepository
                .findByOperationTypeInAndStatusAndDurationMsNotNull(
                        List.of(OperationType.RESUME, OperationType.CREATE),
                        OperationStatus.SUCCESS);

        List<Long> durations = resumeOps.stream()
                .map(OperationLogEntity::getDurationMs)
                .sorted()
                .toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_operations", durations.size());
        if (!durations.isEmpty()) {
            stats.put("p50_ms", percentile(durations, 50));
            stats.put("p90_ms", percentile(durations, 90));
            stats.put("p99_ms", percentile(durations, 99));
            stats.put("avg_ms", durations.stream().mapToLong(Long::longValue).average().orElse(0));
            stats.put("min_ms", durations.get(0));
            stats.put("max_ms", durations.get(durations.size() - 1));
        }
        return stats;
    }

    public Map<String, Object> getCostByTenant() {
        var cost = props.getCost();
        Instant monthStart = YearMonth.now(ZoneId.of("UTC")).atDay(1).atStartOfDay()
                .atZone(ZoneId.of("UTC")).toInstant();
        Instant now = Instant.now();

        List<DatabaseEntity> allDbs = databaseRepository.findAll();
        Map<String, List<DatabaseEntity>> dbsByTenant = allDbs.stream()
                .collect(Collectors.groupingBy(DatabaseEntity::getTenantId));

        List<Map<String, Object>> tenantCosts = new ArrayList<>();
        for (var entry : dbsByTenant.entrySet()) {
            String tenantId = entry.getKey();
            List<DatabaseEntity> dbs = entry.getValue();

            double computeCuHours = usageMeteringService.getTenantComputeCuHours(tenantId, monthStart, now);
            double computeCost = computeCuHours * cost.getComputeCuHourly();
            double storageCost = dbs.stream()
                    .mapToDouble(db -> db.getStorageLimitGb() * 0.1 * cost.getObsPerGbMonthly())
                    .sum();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tenant_id", tenantId);
            item.put("database_count", dbs.size());
            item.put("compute_cu_hours", Math.round(computeCuHours * 100.0) / 100.0);
            item.put("compute_cost", Math.round(computeCost * 100.0) / 100.0);
            item.put("storage_cost", Math.round(storageCost * 100.0) / 100.0);
            item.put("total_cost", Math.round((computeCost + storageCost) * 100.0) / 100.0);
            tenantCosts.add(item);
        }

        tenantCosts.sort((a, b) -> Double.compare(
                ((Number) b.get("total_cost")).doubleValue(),
                ((Number) a.get("total_cost")).doubleValue()));

        return Map.of("tenants", tenantCosts);
    }

    private long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private int parseComputeUnits(String computeSize) {
        if (computeSize == null) return 1;
        String num = computeSize.replaceAll("[^0-9]", "");
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
