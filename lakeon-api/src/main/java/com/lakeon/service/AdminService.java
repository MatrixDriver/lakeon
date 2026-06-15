package com.lakeon.service;

import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminService {

    private final TenantRepository tenantRepository;
    private final DatabaseRepository databaseRepository;
    private final OperationLogRepository operationLogRepository;

    public AdminService(TenantRepository tenantRepository,
                        DatabaseRepository databaseRepository,
                        OperationLogRepository operationLogRepository,
                        MeterRegistry meterRegistry) {
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
        this.operationLogRepository = operationLogRepository;

        Gauge.builder("lakeon_tenants_total", tenantRepository, TenantRepository::count)
                .description("Total number of tenants")
                .register(meterRegistry);
        Gauge.builder("lakeon_databases_total", databaseRepository, repo -> repo.findAll().size())
                .description("Total number of databases")
                .register(meterRegistry);
    }

    public Map<String, Object> getDashboard() {
        List<DatabaseEntity> databases = databaseRepository.findAll();
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        List<OperationLogEntity> recentOps = operationLogRepository.findByStartedAtAfter(since24h);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_count", tenantRepository.count());
        out.put("database_count", databases.size());
        out.put("databases_by_status", databases.stream()
                .collect(Collectors.groupingBy(db -> db.getStatus().name().toLowerCase(), Collectors.counting())));
        out.put("active_compute_pods", databases.stream()
                .filter(db -> db.getComputePodName() != null && db.getStatus() == DatabaseStatus.RUNNING)
                .count());
        out.put("operation_stats_24h", recentOps.stream()
                .collect(Collectors.groupingBy(op -> op.getOperationType().name().toLowerCase(), Collectors.counting())));
        out.put("component_health", checkAllComponents());
        return out;
    }

    public Map<String, Object> checkAllComponents() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("api", Map.of("status", "healthy"));
        health.put("rds", Map.of("status", "healthy"));
        health.put("lakebase_core", Map.of("status", "healthy"));
        return health;
    }

    public double estimateStorageBytes() {
        return databaseRepository.findAll().stream()
                .mapToDouble(db -> db.getStorageLimitGb() == null ? 0 : db.getStorageLimitGb() * 1024.0 * 1024.0 * 1024.0)
                .sum();
    }

    public String getComponentLogs(String component, int lines) {
        return "Logs moved to Huawei Cloud LTS; component=" + component + ", lines=" + lines;
    }

    public List<Map<String, Object>> getPodEvents(String namespace) {
        return List.of();
    }

    public Map<String, Object> getComputeStats() {
        List<DatabaseEntity> databases = databaseRepository.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", databases.stream().filter(db -> db.getStatus() == DatabaseStatus.RUNNING).count());
        out.put("suspended", databases.stream().filter(db -> db.getStatus() == DatabaseStatus.SUSPENDED).count());
        out.put("deleted", databases.stream().filter(db -> db.getStatus() == DatabaseStatus.DELETED).count());
        return out;
    }
}
