package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.DatabaseUsageSummary;
import com.lakeon.model.dto.TenantUsageSummary;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UsageMeteringService {

    private static final List<OperationType> START_TYPES = List.of(OperationType.CREATE, OperationType.RESUME);
    private static final List<OperationType> STOP_TYPES = List.of(OperationType.SUSPEND, OperationType.DELETE);
    private static final List<OperationType> ALL_LIFECYCLE_TYPES = List.of(
            OperationType.CREATE, OperationType.RESUME, OperationType.SUSPEND, OperationType.DELETE);

    private final OperationLogRepository operationLogRepository;
    private final DatabaseRepository databaseRepository;
    private final TenantRepository tenantRepository;
    private final LakeonProperties props;

    public UsageMeteringService(OperationLogRepository operationLogRepository,
                                DatabaseRepository databaseRepository,
                                TenantRepository tenantRepository,
                                LakeonProperties props) {
        this.operationLogRepository = operationLogRepository;
        this.databaseRepository = databaseRepository;
        this.tenantRepository = tenantRepository;
        this.props = props;
    }

    public TenantUsageSummary getTenantUsage(String tenantId, Instant from, Instant to) {
        String tenantName = tenantRepository.findById(tenantId)
                .map(t -> t.getName())
                .orElse(tenantId);

        List<OperationLogEntity> logs = operationLogRepository
                .findByTenantIdAndOperationTypeInAndStatusAndStartedAtBetweenOrderByStartedAtAsc(
                        tenantId, ALL_LIFECYCLE_TYPES, OperationStatus.SUCCESS, from, to);

        Map<String, List<OperationLogEntity>> byDatabase = logs.stream()
                .collect(Collectors.groupingBy(OperationLogEntity::getDatabaseId, LinkedHashMap::new, Collectors.toList()));

        List<DatabaseUsageSummary> dbSummaries = new ArrayList<>();
        for (var entry : byDatabase.entrySet()) {
            dbSummaries.add(computeDatabaseUsage(entry.getKey(), entry.getValue(), to));
        }

        long totalSeconds = dbSummaries.stream().mapToLong(DatabaseUsageSummary::computeSeconds).sum();
        double totalCuHours = dbSummaries.stream().mapToDouble(DatabaseUsageSummary::computeCuHours).sum();
        double totalCost = dbSummaries.stream().mapToDouble(DatabaseUsageSummary::estimatedCost).sum();

        return new TenantUsageSummary(tenantId, tenantName, dbSummaries,
                totalSeconds, round2(totalCuHours), round2(totalCost));
    }

    public DatabaseUsageSummary getDatabaseUsage(String databaseId, Instant from, Instant to) {
        List<OperationLogEntity> logs = operationLogRepository
                .findByDatabaseIdAndOperationTypeInAndStatusAndStartedAtBetweenOrderByStartedAtAsc(
                        databaseId, ALL_LIFECYCLE_TYPES, OperationStatus.SUCCESS, from, to);
        return computeDatabaseUsage(databaseId, logs, to);
    }

    public List<TenantUsageSummary> getAllTenantsUsage(Instant from, Instant to) {
        return tenantRepository.findAll().stream()
                .map(t -> getTenantUsage(t.getId(), from, to))
                .toList();
    }

    /**
     * Calculate total compute-CU-hours for a tenant in the given time range.
     * Used by AdminService for cost estimation.
     */
    public double getTenantComputeCuHours(String tenantId, Instant from, Instant to) {
        return getTenantUsage(tenantId, from, to).totalComputeCuHours();
    }

    private DatabaseUsageSummary computeDatabaseUsage(String databaseId, List<OperationLogEntity> logs, Instant queryEnd) {
        DatabaseEntity db = databaseRepository.findById(databaseId).orElse(null);
        String dbName = db != null ? db.getName() : databaseId;
        String computeSize = db != null ? db.getComputeSize() : "1CU";
        int cu = parseComputeUnits(computeSize);

        long totalSeconds = 0;
        Instant runStart = null;

        for (OperationLogEntity log : logs) {
            OperationType type = log.getOperationType();
            if (START_TYPES.contains(type)) {
                if (runStart == null) {
                    runStart = log.getStartedAt();
                }
            } else if (STOP_TYPES.contains(type)) {
                if (runStart != null) {
                    totalSeconds += Duration.between(runStart, log.getStartedAt()).getSeconds();
                    runStart = null;
                }
            }
        }

        // If compute is still running (last event was a start event), count up to queryEnd
        if (runStart != null) {
            totalSeconds += Duration.between(runStart, queryEnd).getSeconds();
        }

        double cuHours = (totalSeconds * cu) / 3600.0;
        double cost = cuHours * props.getCost().getComputeCuHourly();

        return new DatabaseUsageSummary(databaseId, dbName, computeSize,
                totalSeconds, round2(cuHours), round2(cost));
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

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
