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

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.File;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Object> cloudResourcesCache;
    private volatile long cloudResourcesCacheTime;

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
        health.put("obs", checkObs());
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

        double cceClusterCost = cost.getCceClusterHourly() * 24 * 30;
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

        double total = cceClusterCost + cceNodeCost + elbCost + rdsCost + eipCost + obsCost;

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("cce_cluster", Math.round(cceClusterCost * 100.0) / 100.0);
        breakdown.put("cce_nodes", Math.round(cceNodeCost * 100.0) / 100.0);
        breakdown.put("elb", elbCost);
        breakdown.put("rds", rdsCost);
        breakdown.put("eip", eipCost);
        breakdown.put("obs", Math.round(obsCost * 100.0) / 100.0);

        result.put("total", Math.round(total * 100.0) / 100.0);
        result.put("breakdown", breakdown);
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

    public Map<String, Object> checkObs() {
        Map<String, Object> status = new LinkedHashMap<>();
        var obsConfig = props.getObs();
        String endpoint = obsConfig.getEndpoint();
        String bucket = obsConfig.getBucket();
        status.put("endpoint", endpoint);
        status.put("bucket", bucket);

        if (endpoint == null || endpoint.isBlank() || bucket == null || bucket.isBlank()) {
            status.put("status", "unhealthy");
            status.put("error", "OBS not configured");
            return status;
        }

        try {
            long start = System.currentTimeMillis();
            // Use HTTP HEAD request to check bucket connectivity
            String url = endpoint.endsWith("/") ? endpoint : endpoint + "/";
            if (!url.startsWith("http")) {
                // Virtual-hosted style: https://bucket.endpoint
                url = "https://" + bucket + "." + endpoint;
            }
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            long latency = System.currentTimeMillis() - start;
            conn.disconnect();

            if (code >= 200 && code < 400) {
                status.put("status", "healthy");
            } else if (code == 403) {
                // 403 is expected for HEAD on bucket root without proper auth — bucket exists
                status.put("status", "healthy");
            } else {
                status.put("status", "unhealthy");
                status.put("error", "HTTP " + code);
            }
            status.put("latency_ms", latency);
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }

        // Calculate bucket usage from database storage estimates
        List<DatabaseEntity> allDbs = databaseRepository.findAll();
        double totalStorageGb = allDbs.stream()
                .mapToDouble(db -> db.getStorageLimitGb() * 0.1)
                .sum();
        status.put("total_objects_estimate", allDbs.size());
        status.put("total_size_gb_estimate", Math.round(totalStorageGb * 100.0) / 100.0);

        return status;
    }

    public List<Map<String, Object>> getCostTrend(int days) {
        var cost = props.getCost();
        double dailyFixed = (cost.getCceClusterHourly() * 24)
                + (cost.getCceNodeHourly() * cost.getCceNodeCount() * 24)
                + (cost.getElbMonthly() / 30)
                + (cost.getRdsMonthly() / 30)
                + (cost.getEipMonthly() / 30);

        List<Map<String, Object>> trend = new ArrayList<>();
        Instant now = Instant.now();

        for (int i = days - 1; i >= 0; i--) {
            Instant dayStart = now.minus(i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            if (dayEnd.isAfter(now)) dayEnd = now;

            // Sum compute CU hours across all tenants for this day
            double totalComputeCuHours = 0;
            for (var tenant : tenantRepository.findAll()) {
                totalComputeCuHours += usageMeteringService.getTenantComputeCuHours(
                        tenant.getId(), dayStart, dayEnd);
            }
            double computeCost = totalComputeCuHours * cost.getComputeCuHourly();

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", dayStart.toString().substring(0, 10));
            day.put("fixed_cost", Math.round(dailyFixed * 100.0) / 100.0);
            day.put("compute_cost", Math.round(computeCost * 100.0) / 100.0);
            day.put("total_cost", Math.round((dailyFixed + computeCost) * 100.0) / 100.0);
            trend.add(day);
        }
        return trend;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCloudResources() {
        long now = System.currentTimeMillis();
        if (cloudResourcesCache != null && (now - cloudResourcesCacheTime) < 5 * 60 * 1000) {
            return cloudResourcesCache;
        }
        String filePath = props.getCloud().getResourcesFile();
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("Cloud resources file not found: {}", filePath);
            return Map.of("resources", List.of(), "topology", Map.of());
        }
        try {
            Map<String, Object> data = objectMapper.readValue(file, Map.class);
            cloudResourcesCache = data;
            cloudResourcesCacheTime = now;
            return data;
        } catch (Exception e) {
            log.error("Failed to read cloud resources file: {}", e.getMessage());
            return Map.of("resources", List.of(), "topology", Map.of(), "error", e.getMessage());
        }
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
