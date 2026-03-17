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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.api.model.Quantity;
import io.micrometer.core.instrument.Timer;

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
    private final MeterRegistry meterRegistry;
    private final KubernetesClient k8sClient;
    private final CbcBillingService cbcBillingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Object> cloudResourcesCache;
    private volatile long cloudResourcesCacheTime;

    public AdminService(TenantRepository tenantRepository,
                        DatabaseRepository databaseRepository,
                        OperationLogRepository operationLogRepository,
                        NeonApiClient neonApiClient,
                        LakeonProperties props,
                        DataSource dataSource,
                        UsageMeteringService usageMeteringService,
                        MeterRegistry meterRegistry,
                        KubernetesClient k8sClient,
                        CbcBillingService cbcBillingService) {
        this.tenantRepository = tenantRepository;
        this.databaseRepository = databaseRepository;
        this.operationLogRepository = operationLogRepository;
        this.neonApiClient = neonApiClient;
        this.props = props;
        this.dataSource = dataSource;
        this.usageMeteringService = usageMeteringService;
        this.meterRegistry = meterRegistry;
        this.k8sClient = k8sClient;
        this.cbcBillingService = cbcBillingService;

        Gauge.builder("lakeon_tenants_total", tenantRepository, TenantRepository::count)
            .description("Total number of tenants")
            .register(meterRegistry);

        Gauge.builder("lakeon_databases_total", databaseRepository, repo -> repo.findAll().size())
            .description("Total number of databases")
            .register(meterRegistry);

        Gauge.builder("lakeon_storage_used_bytes", this, AdminService::estimateStorageBytes)
            .description("Estimated storage used in bytes")
            .register(meterRegistry);
    }

    public double estimateStorageBytes() {
        try {
            return databaseRepository.findAll().stream()
                .mapToDouble(db -> db.getStorageLimitGb() * 0.1 * 1024 * 1024 * 1024)
                .sum();
        } catch (Exception e) {
            return 0;
        }
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
        health.put("api", checkApiPod());
        health.put("pageserver", checkPageserver());
        health.put("safekeeper", checkSafekeeper());
        health.put("storage_broker", checkStorageBroker());
        health.put("proxy", checkProxy());
        health.put("rds", checkRds());
        health.put("obs", checkObs());
        health.put("elb", checkElb());
        return health;
    }

    private Map<String, Object> checkApiPod() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            var pods = k8sClient.pods().inNamespace("lakeon")
                    .withLabel("app", "lakeon-api").list().getItems();
            if (!pods.isEmpty()) {
                var pod = pods.get(0);
                var phase = pod.getStatus().getPhase();
                status.put("status", "Running".equals(phase) ? "healthy" : "unhealthy");
                status.put("pod", pod.getMetadata().getName());
                status.put("node", pod.getSpec().getNodeName());
                status.put("ip", pod.getStatus().getPodIP());
            } else {
                status.put("status", "unhealthy");
                status.put("error", "No API pod found");
            }
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        return status;
    }

    private Map<String, Object> checkStorageBroker() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            var pods = k8sClient.pods().inNamespace("lakeon")
                    .withLabel("app", "storage-broker").list().getItems();
            if (!pods.isEmpty() && "Running".equals(pods.get(0).getStatus().getPhase())) {
                status.put("status", "healthy");
            } else {
                status.put("status", "unhealthy");
                status.put("error", pods.isEmpty() ? "No pod found" : pods.get(0).getStatus().getPhase());
            }
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        return status;
    }

    private Map<String, Object> checkElb() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            var services = k8sClient.services().inNamespace("lakeon")
                    .withLabel("app", "lakeon-api").list().getItems();
            if (!services.isEmpty()) {
                var svc = services.get(0);
                var ingress = svc.getStatus().getLoadBalancer().getIngress();
                if (ingress != null && !ingress.isEmpty()) {
                    status.put("status", "healthy");
                    status.put("ip", ingress.get(0).getIp());
                } else {
                    status.put("status", "healthy");
                    status.put("type", svc.getSpec().getType());
                }
            } else {
                status.put("status", "unknown");
                status.put("error", "No API service found");
            }
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        return status;
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

    /**
     * Fetch and parse key metrics from pageserver's Prometheus endpoint.
     * Used to detect cache pressure, OBS back-reads, and sharding needs.
     */
    public Map<String, Object> getPageserverMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String metricsUrl = props.getNeon().getPageserverUrl().replace(":" +
                props.getNeon().getPageserverUrl().replaceAll(".*:(\\d+)$", "$1"),
                ":9898") + "/metrics";
            // Actually just use the configured pageserver URL's host with port 9898
            String baseUrl = props.getNeon().getPageserverUrl();
            String host = baseUrl.replaceAll("https?://", "").replaceAll(":\\d+$", "");
            metricsUrl = "http://" + host + ":9898/metrics";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpResponse<String> resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(metricsUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                result.put("error", "HTTP " + resp.statusCode());
                return result;
            }

            String body = resp.body();
            // Parse key metrics from Prometheus text format
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("current_bytes", parseMetric(body, "pageserver_page_cache_size_current_bytes"));
            cache.put("max_bytes", parseMetric(body, "pageserver_page_cache_size_max_bytes"));
            cache.put("evicted_layers", parseMetric(body, "pageserver_layer_completed_evictions"));
            cache.put("evicted_bytes", parseMetric(body, "pageserver_disk_usage_based_eviction_evicted_bytes_total"));
            result.put("cache", cache);

            Map<String, Object> remote = new LinkedHashMap<>();
            remote.put("ondemand_download_count", parseMetric(body, "pageserver_wait_ondemand_download_seconds_global_count{task_kind=\"PageRequestHandler\"}"));
            remote.put("ondemand_download_seconds", parseMetric(body, "pageserver_wait_ondemand_download_seconds_global_sum{task_kind=\"PageRequestHandler\"}"));
            remote.put("s3_request_count", parseSumOfMetrics(body, "remote_storage_s3_request_seconds_count"));
            result.put("remote_reads", remote);

            Map<String, Object> tenants = new LinkedHashMap<>();
            tenants.put("active", parseMetric(body, "pageserver_tenant_states_count{state=\"Active\"}"));
            tenants.put("attaching", parseMetric(body, "pageserver_tenant_states_count{state=\"Attaching\"}"));
            tenants.put("broken", parseMetric(body, "pageserver_tenant_states_count{state=\"Broken\"}"));
            result.put("tenants", tenants);

            Map<String, Object> memory = new LinkedHashMap<>();
            double rssBytes = parseMetric(body, "process_resident_memory_bytes");
            memory.put("rss_mb", Math.round(rssBytes / 1024 / 1024));
            result.put("memory", memory);

            Map<String, Object> wal = new LinkedHashMap<>();
            wal.put("redo_count", parseMetric(body, "pageserver_wal_redo_seconds_count"));
            wal.put("redo_seconds", parseMetric(body, "pageserver_wal_redo_seconds_sum"));
            result.put("wal_redo", wal);

            // Health assessment
            double evictedLayers = parseMetric(body, "pageserver_layer_completed_evictions");
            double downloadCount = parseMetric(body, "pageserver_wait_ondemand_download_seconds_global_count{task_kind=\"PageRequestHandler\"}");
            int activeTenants = (int) parseMetric(body, "pageserver_tenant_states_count{state=\"Active\"}");

            String pressure;
            if (evictedLayers > 0 && downloadCount > 100) pressure = "high";
            else if (evictedLayers > 0 || downloadCount > 50) pressure = "medium";
            else pressure = "low";
            result.put("pressure", pressure);
            result.put("shard_recommended", activeTenants > 50 || "high".equals(pressure));

        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    private double parseMetric(String body, String metricName) {
        // Handle metrics with labels: find exact line match
        for (String line : body.split("\n")) {
            if (line.startsWith("#")) continue;
            if (metricName.contains("{")) {
                // Label-based match
                if (line.startsWith(metricName)) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        try { return Double.parseDouble(parts[parts.length - 1]); } catch (NumberFormatException e) { return 0; }
                    }
                }
            } else {
                // Simple metric name match (no labels)
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && parts[0].equals(metricName)) {
                    try { return Double.parseDouble(parts[1]); } catch (NumberFormatException e) { return 0; }
                }
            }
        }
        return 0;
    }

    private double parseSumOfMetrics(String body, String metricPrefix) {
        double sum = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith("#")) continue;
            if (line.startsWith(metricPrefix)) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try { sum += Double.parseDouble(parts[parts.length - 1]); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return sum;
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
        // Try CBC real billing data first
        try {
            Map<String, Object> cbcData = cbcBillingService.getMonthlyBillParsed(null);
            if (cbcData != null) {
                log.debug("Using CBC real billing data");
                return cbcData;
            }
        } catch (Exception e) {
            log.warn("CBC billing failed, falling back to estimates: {}", e.getMessage());
        }

        // Fallback: config-based estimates
        return estimateMonthlyCostFromConfig();
    }

    private Map<String, Object> estimateMonthlyCostFromConfig() {
        var cost = props.getCost();
        Map<String, Object> result = new LinkedHashMap<>();

        double cceClusterCost = cost.getCceClusterHourly() * 24 * 30;
        double cceNodeCost = cost.getCceNodeHourly() * cost.getCceNodeCount() * 24 * 30;
        double elbCost = cost.getElbMonthly();
        double rdsCost = cost.getRdsMonthly();
        double eipCost = cost.getEipMonthly();

        List<DatabaseEntity> allDbs = databaseRepository.findAll();
        double totalStorageGb = allDbs.stream()
                .mapToDouble(db -> db.getStorageLimitGb() * 0.1)
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
        result.put("source", "estimate");
        return result;
    }

    public Map<String, Object> getComputeStats() {
        List<OperationLogEntity> resumeOps = operationLogRepository
                .findByOperationTypeInAndStatusAndDurationMsNotNull(
                        List.of(OperationType.RESUME),
                        OperationStatus.SUCCESS);

        List<Long> durations = resumeOps.stream()
                .map(OperationLogEntity::getDurationMs)
                .filter(d -> d >= 500) // filter out no-op (pod already running)
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
            // Virtual-hosted style: https://bucket.obs.region.myhuaweicloud.com/
            String host = endpoint.replaceFirst("^https?://", "");
            String url = "https://" + bucket + "." + host + "/";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            long latency = System.currentTimeMillis() - start;
            conn.disconnect();

            if (code >= 200 && code < 400) {
                status.put("status", "healthy");
            } else if (code == 403 || code == 405) {
                // 403/405 expected for unauthenticated requests — bucket exists
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

    // ── Logs ──

    public String getComponentLogs(String component, int tail) {
        String namespace = props.getK8s().getNamespace().replace("-compute", "");
        String targetNamespace = namespace;

        if (component.startsWith("compute-")) {
            targetNamespace = props.getK8s().getNamespace();
            try {
                var podResource = k8sClient.pods().inNamespace(targetNamespace).withName(component);
                String logs = tail > 0 ? podResource.tailingLines(tail).getLog() : podResource.getLog();
                return logs != null ? logs : "";
            } catch (Exception e) {
                log.warn("Failed to get logs for {}/{}: {}", targetNamespace, component, e.getMessage());
                return "Error: " + e.getMessage();
            }
        }

        // For infra components, find running pod by app label
        try {
            var pods = k8sClient.pods().inNamespace(targetNamespace)
                .withLabel("app", component).list().getItems();
            if (pods.isEmpty()) {
                return "Error: no pods found with label app=" + component + " in namespace " + targetNamespace;
            }
            // Prefer Running pods
            var pod = pods.stream()
                .filter(p -> "Running".equals(p.getStatus().getPhase()))
                .findFirst()
                .orElse(pods.get(0));
            String podName = pod.getMetadata().getName();
            var podResource = k8sClient.pods().inNamespace(targetNamespace).withName(podName);
            String logs = tail > 0 ? podResource.tailingLines(tail).getLog() : podResource.getLog();
            return logs != null ? logs : "";
        } catch (Exception e) {
            log.warn("Failed to get logs for {}/{}: {}", targetNamespace, component, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ── Metrics Summary ──

    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        // JVM metrics
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heap_used_mb", getGaugeValue("jvm.memory.used", "area", "heap") / (1024 * 1024));
        jvm.put("heap_max_mb", getGaugeValue("jvm.memory.max", "area", "heap") / (1024 * 1024));
        jvm.put("threads", getGaugeValue("jvm.threads.live"));
        jvm.put("gc_pause_ms", getTimerMean("jvm.gc.pause") * 1000);
        result.put("jvm", jvm);

        // API metrics
        Map<String, Object> api = new LinkedHashMap<>();
        Timer httpTimer = meterRegistry.find("http.server.requests").timer();
        if (httpTimer != null && httpTimer.count() > 0) {
            double meanMs = httpTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
            double maxMs = httpTimer.max(java.util.concurrent.TimeUnit.MILLISECONDS);
            api.put("request_rate_1m", Math.round(httpTimer.count() / Math.max(1, httpTimer.totalTime(java.util.concurrent.TimeUnit.SECONDS))));
            api.put("p50_ms", Math.round(meanMs));
            api.put("p95_ms", Math.round(meanMs * 1.5));
            api.put("p99_ms", Math.round(maxMs));
        } else {
            api.put("request_rate_1m", 0);
            api.put("p50_ms", 0);
            api.put("p95_ms", 0);
            api.put("p99_ms", 0);
        }
        result.put("api", api);

        // Compute metrics
        Map<String, Object> compute = new LinkedHashMap<>();
        io.micrometer.core.instrument.Gauge activePods = meterRegistry.find("lakeon_compute_pods_active").gauge();
        compute.put("active_pods", activePods != null ? (int) activePods.value() : 0);
        Timer wakeupTimer = meterRegistry.find("lakeon_compute_wakeup_seconds").timer();
        if (wakeupTimer != null && wakeupTimer.count() > 0) {
            compute.put("wakeup_p50_ms", Math.round(wakeupTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)));
        } else {
            compute.put("wakeup_p50_ms", 0);
        }
        // Warm/cold wake breakdown
        Timer warmTimer = meterRegistry.find("lakeon_compute_wakeup_seconds").tag("path", "warm").timer();
        Timer coldTimer = meterRegistry.find("lakeon_compute_wakeup_seconds").tag("path", "cold").timer();
        compute.put("warm_wake_count", warmTimer != null ? (int) warmTimer.count() : 0);
        compute.put("warm_wake_avg_ms", warmTimer != null && warmTimer.count() > 0
            ? Math.round(warmTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)) : 0);
        compute.put("cold_wake_count", coldTimer != null ? (int) coldTimer.count() : 0);
        compute.put("cold_wake_avg_ms", coldTimer != null && coldTimer.count() > 0
            ? Math.round(coldTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)) : 0);
        io.micrometer.core.instrument.Counter failCounter = meterRegistry.find("lakeon_compute_wakeup_failures_total").counter();
        compute.put("wakeup_failures", failCounter != null ? (int) failCounter.count() : 0);
        result.put("compute", compute);

        // Database stats
        Map<String, Object> databases = new LinkedHashMap<>();
        List<DatabaseEntity> allDbs = databaseRepository.findAll();
        databases.put("total", allDbs.size());
        databases.put("running", allDbs.stream().filter(d -> d.getStatus() == DatabaseStatus.RUNNING).count());
        databases.put("suspended", allDbs.stream().filter(d -> d.getStatus() == DatabaseStatus.SUSPENDED).count());
        result.put("databases", databases);

        // Storage
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("used_gb", Math.round(estimateStorageBytes() / (1024.0 * 1024 * 1024) * 100) / 100.0);
        result.put("storage", storage);

        return result;
    }

    private double getGaugeValue(String name) {
        io.micrometer.core.instrument.Gauge g = meterRegistry.find(name).gauge();
        return g != null ? g.value() : 0;
    }

    private double getGaugeValue(String name, String tagKey, String tagValue) {
        var meters = meterRegistry.find(name).tag(tagKey, tagValue).gauges();
        double sum = 0;
        for (var g : meters) sum += g.value();
        return sum;
    }

    private double getTimerMean(String name) {
        Timer t = meterRegistry.find(name).timer();
        return t != null ? t.mean(java.util.concurrent.TimeUnit.SECONDS) : 0;
    }

    // ── Infrastructure ──

    public List<Map<String, Object>> getInfraNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        try {
            var nodeList = k8sClient.nodes().list().getItems();

            // Try to get metrics — optional, requires metrics-server
            Map<String, NodeMetrics> metricsMap = new java.util.HashMap<>();
            try {
                for (NodeMetrics nm : k8sClient.top().nodes().metrics().getItems()) {
                    metricsMap.put(nm.getMetadata().getName(), nm);
                }
            } catch (Exception ignored) {}

            for (var k8sNode : nodeList) {
                String nodeName = k8sNode.getMetadata().getName();
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", nodeName);

                // Status from Ready condition (no metrics-server needed)
                String status = "Unknown";
                if (k8sNode.getStatus() != null && k8sNode.getStatus().getConditions() != null) {
                    status = k8sNode.getStatus().getConditions().stream()
                        .filter(c -> "Ready".equals(c.getType()))
                        .findFirst()
                        .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady")
                        .orElse("Unknown");
                }
                node.put("status", status);

                // Capacity (no metrics-server needed)
                double cpuTotal = 0, memTotal = 0;
                if (k8sNode.getStatus() != null && k8sNode.getStatus().getCapacity() != null) {
                    cpuTotal = parseCpuQuantity(k8sNode.getStatus().getCapacity().get("cpu"));
                    memTotal = parseMemQuantity(k8sNode.getStatus().getCapacity().get("memory"));
                    node.put("cpu_total_cores", cpuTotal);
                    node.put("mem_total_gb", Math.round(memTotal / (1024.0 * 1024 * 1024) * 100) / 100.0);
                }

                // Usage from metrics-server (optional)
                NodeMetrics nm = metricsMap.get(nodeName);
                if (nm != null) {
                    double cpuUsed = parseCpuQuantity(nm.getUsage().get("cpu"));
                    double memUsed = parseMemQuantity(nm.getUsage().get("memory"));
                    node.put("cpu_used_cores", Math.round(cpuUsed * 1000) / 1000.0);
                    node.put("cpu_percent", cpuTotal > 0 ? Math.round(cpuUsed / cpuTotal * 10000) / 100.0 : 0);
                    node.put("mem_used_gb", Math.round(memUsed / (1024.0 * 1024 * 1024) * 100) / 100.0);
                    node.put("mem_percent", memTotal > 0 ? Math.round(memUsed / memTotal * 10000) / 100.0 : 0);
                }
                nodes.add(node);
            }
        } catch (Exception e) {
            log.warn("Failed to get node info: {}", e.getMessage());
        }
        return nodes;
    }

    public List<Map<String, Object>> getInfraPods() {
        List<Map<String, Object>> pods = new ArrayList<>();
        try {
            String mainNs = props.getK8s().getNamespace().replace("-compute", "");
            String computeNs = props.getK8s().getNamespace();

            // Collect all pods from both namespaces (no metrics-server needed)
            List<io.fabric8.kubernetes.api.model.Pod> allPods = new ArrayList<>();
            allPods.addAll(k8sClient.pods().inNamespace(mainNs).list().getItems());
            allPods.addAll(k8sClient.pods().inNamespace(computeNs).list().getItems());

            // Try to get metrics — optional
            Map<String, PodMetrics> metricsMap = new java.util.HashMap<>();
            try {
                for (PodMetrics pm : k8sClient.top().pods().inNamespace(mainNs).metrics().getItems())
                    metricsMap.put(pm.getMetadata().getName(), pm);
                for (PodMetrics pm : k8sClient.top().pods().inNamespace(computeNs).metrics().getItems())
                    metricsMap.put(pm.getMetadata().getName(), pm);
            } catch (Exception ignored) {}

            for (var p : allPods) {
                Map<String, Object> pod = new LinkedHashMap<>();
                pod.put("name", p.getMetadata().getName());
                pod.put("namespace", p.getMetadata().getNamespace());

                String phase = p.getStatus() != null ? p.getStatus().getPhase() : "Unknown";
                pod.put("phase", phase != null ? phase : "Unknown");

                boolean ready = p.getStatus() != null && p.getStatus().getConditions() != null &&
                    p.getStatus().getConditions().stream()
                        .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
                pod.put("ready", ready);

                int restarts = 0;
                if (p.getStatus() != null && p.getStatus().getContainerStatuses() != null) {
                    restarts = p.getStatus().getContainerStatuses().stream()
                        .mapToInt(cs -> cs.getRestartCount() != null ? cs.getRestartCount() : 0).sum();
                }
                pod.put("restarts", restarts);

                PodMetrics pm = metricsMap.get(p.getMetadata().getName());
                if (pm != null) {
                    double totalCpu = 0, totalMem = 0;
                    for (var c : pm.getContainers()) {
                        totalCpu += parseCpuQuantity(c.getUsage().get("cpu"));
                        totalMem += parseMemQuantity(c.getUsage().get("memory"));
                    }
                    pod.put("cpu_cores", Math.round(totalCpu * 1000) / 1000.0);
                    pod.put("mem_mb", Math.round(totalMem / (1024 * 1024) * 10) / 10.0);
                }
                pods.add(pod);
            }

            pods.sort((a, b) -> {
                double ma = a.containsKey("mem_mb") ? ((Number) a.get("mem_mb")).doubleValue() : 0;
                double mb = b.containsKey("mem_mb") ? ((Number) b.get("mem_mb")).doubleValue() : 0;
                return Double.compare(mb, ma);
            });
        } catch (Exception e) {
            log.warn("Failed to get pod info: {}", e.getMessage());
        }
        return pods;
    }

    private double parseCpuQuantity(Quantity q) {
        if (q == null) return 0;
        String val = q.getAmount();
        String format = q.getFormat();
        try {
            double amount = Double.parseDouble(val);
            if ("n".equals(format)) return amount / 1_000_000_000;
            if ("m".equals(format)) return amount / 1000;
            return amount;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseMemQuantity(Quantity q) {
        if (q == null) return 0;
        String val = q.getAmount();
        String format = q.getFormat();
        try {
            double amount = Double.parseDouble(val);
            if ("Ki".equals(format)) return amount * 1024;
            if ("Mi".equals(format)) return amount * 1024 * 1024;
            if ("Gi".equals(format)) return amount * 1024 * 1024 * 1024;
            return amount;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Pod Events ──

    public List<Map<String, Object>> getPodEvents(String namespace) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Event> events = k8sClient.v1().events().inNamespace(namespace).list().getItems();
            Instant oneHourAgo = Instant.now().minus(6, ChronoUnit.HOURS);

            for (Event event : events) {
                String lastTimestamp = event.getLastTimestamp();
                Instant eventTime = null;
                if (lastTimestamp != null && !lastTimestamp.isBlank()) {
                    try {
                        eventTime = Instant.parse(lastTimestamp);
                    } catch (Exception ignored) {}
                }
                if (eventTime == null && event.getEventTime() != null) {
                    try {
                        eventTime = Instant.parse(event.getEventTime().getTime());
                    } catch (Exception ignored) {}
                }
                if (eventTime == null || eventTime.isBefore(oneHourAgo)) {
                    continue;
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", event.getType());
                entry.put("reason", event.getReason());
                entry.put("message", event.getMessage());
                entry.put("object", event.getInvolvedObject() != null ? event.getInvolvedObject().getName() : "");
                entry.put("last_time", eventTime.toString());
                entry.put("count", event.getCount() != null ? event.getCount() : 1);
                result.add(entry);
            }

            result.sort((a, b) -> ((String) b.get("last_time")).compareTo((String) a.get("last_time")));
            if (result.size() > 50) {
                return result.subList(0, 50);
            }
        } catch (Exception e) {
            log.warn("Failed to get pod events for namespace {}: {}", namespace, e.getMessage());
        }
        return result;
    }

    // ── Elastic Node Pool ──

    public Map<String, Object> getNodePoolStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        var k8sConfig = props.getK8s();
        result.put("pool_name", k8sConfig.getNodePoolName());
        result.put("min_nodes", k8sConfig.getNodePoolMin());
        result.put("max_nodes", k8sConfig.getNodePoolMax());
        result.put("scale_down_unneeded_minutes", k8sConfig.getScaleDownUnneededMinutes());

        try {
            // Get compute-labeled nodes
            var nodeSelector = k8sConfig.getComputeNodeSelector();
            var nodeListBuilder = k8sClient.nodes();
            io.fabric8.kubernetes.api.model.NodeList nodeList;
            if (!nodeSelector.isEmpty()) {
                var entry = nodeSelector.entrySet().iterator().next();
                nodeList = k8sClient.nodes().withLabel(entry.getKey(), entry.getValue()).list();
            } else {
                nodeList = k8sClient.nodes().list();
            }

            // Metrics (optional)
            Map<String, NodeMetrics> metricsMap = new java.util.HashMap<>();
            try {
                for (NodeMetrics nm : k8sClient.top().nodes().metrics().getItems()) {
                    metricsMap.put(nm.getMetadata().getName(), nm);
                }
            } catch (Exception ignored) {}

            // Count compute pods per node
            String computeNs = k8sConfig.getNamespace();
            Map<String, Integer> podCountPerNode = new java.util.HashMap<>();
            Map<String, Instant> lastPodStartPerNode = new java.util.HashMap<>();
            try {
                var computePods = k8sClient.pods().inNamespace(computeNs).list().getItems();
                for (var pod : computePods) {
                    // Skip DaemonSet-owned pods
                    boolean isDaemonSet = pod.getMetadata().getOwnerReferences() != null &&
                        pod.getMetadata().getOwnerReferences().stream()
                            .anyMatch(ref -> "DaemonSet".equals(ref.getKind()));
                    if (isDaemonSet) continue;

                    String nodeName = pod.getSpec().getNodeName();
                    if (nodeName != null) {
                        podCountPerNode.merge(nodeName, 1, Integer::sum);
                        // Track last pod start time for idle estimation
                        if (pod.getStatus() != null && pod.getStatus().getStartTime() != null) {
                            try {
                                Instant startTime = Instant.parse(pod.getStatus().getStartTime());
                                lastPodStartPerNode.merge(nodeName, startTime,
                                    (a, b) -> a.isAfter(b) ? a : b);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}

            double totalCpu = 0, totalMem = 0, usedCpu = 0, usedMem = 0;
            int readyNodes = 0;
            List<Map<String, Object>> nodeDetails = new ArrayList<>();

            for (var k8sNode : nodeList.getItems()) {
                String nodeName = k8sNode.getMetadata().getName();
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", nodeName);

                // Status
                String status = "Unknown";
                if (k8sNode.getStatus() != null && k8sNode.getStatus().getConditions() != null) {
                    status = k8sNode.getStatus().getConditions().stream()
                        .filter(c -> "Ready".equals(c.getType()))
                        .findFirst()
                        .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady")
                        .orElse("Unknown");
                }
                node.put("status", status);
                if ("Ready".equals(status)) readyNodes++;

                // Capacity
                double cpuCap = 0, memCap = 0;
                if (k8sNode.getStatus() != null && k8sNode.getStatus().getCapacity() != null) {
                    cpuCap = parseCpuQuantity(k8sNode.getStatus().getCapacity().get("cpu"));
                    memCap = parseMemQuantity(k8sNode.getStatus().getCapacity().get("memory"));
                }
                node.put("cpu_total_cores", cpuCap);
                node.put("mem_total_gb", Math.round(memCap / (1024.0 * 1024 * 1024) * 100) / 100.0);
                totalCpu += cpuCap;
                totalMem += memCap;

                // Usage from metrics
                NodeMetrics nm = metricsMap.get(nodeName);
                if (nm != null) {
                    double cpuUsed = parseCpuQuantity(nm.getUsage().get("cpu"));
                    double memUsed = parseMemQuantity(nm.getUsage().get("memory"));
                    node.put("cpu_used_cores", Math.round(cpuUsed * 1000) / 1000.0);
                    node.put("cpu_percent", cpuCap > 0 ? Math.round(cpuUsed / cpuCap * 10000) / 100.0 : 0);
                    node.put("mem_used_gb", Math.round(memUsed / (1024.0 * 1024 * 1024) * 100) / 100.0);
                    node.put("mem_percent", memCap > 0 ? Math.round(memUsed / memCap * 10000) / 100.0 : 0);
                    usedCpu += cpuUsed;
                    usedMem += memUsed;
                }

                // Pod count and idle detection
                int podCount = podCountPerNode.getOrDefault(nodeName, 0);
                node.put("pod_count", podCount);
                boolean idle = podCount == 0;
                node.put("idle", idle);

                if (idle) {
                    // Estimate scale-down time: if no user pods, autoscaler will remove after scaleDownUnneededMinutes
                    // We don't know exactly when it became idle, so show it as "eligible for scale-down"
                    node.put("scale_down_eligible", true);
                } else {
                    node.put("scale_down_eligible", false);
                }

                nodeDetails.add(node);
            }

            result.put("current_nodes", nodeList.getItems().size());
            result.put("ready_nodes", readyNodes);
            result.put("total_cpu_cores", totalCpu);
            result.put("total_mem_gb", Math.round(totalMem / (1024.0 * 1024 * 1024) * 100) / 100.0);
            if (!metricsMap.isEmpty()) {
                result.put("used_cpu_cores", Math.round(usedCpu * 1000) / 1000.0);
                result.put("used_mem_gb", Math.round(usedMem / (1024.0 * 1024 * 1024) * 100) / 100.0);
                result.put("cpu_percent", totalCpu > 0 ? Math.round(usedCpu / totalCpu * 10000) / 100.0 : 0);
                result.put("mem_percent", totalMem > 0 ? Math.round(usedMem / totalMem * 10000) / 100.0 : 0);
            }
            result.put("nodes", nodeDetails);
        } catch (Exception e) {
            log.warn("Failed to get node pool status: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getAutoscalingEvents() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> eventList = new ArrayList<>();
        int scaleUpCount24h = 0, scaleDownCount24h = 0;
        String lastScaleUp = null, lastScaleDown = null;

        try {
            List<Event> events = k8sClient.v1().events().inNamespace("kube-system").list().getItems();
            Instant cutoff = Instant.now().minus(48, ChronoUnit.HOURS);
            Instant cutoff24h = Instant.now().minus(24, ChronoUnit.HOURS);

            for (Event event : events) {
                // Filter by cluster-autoscaler source
                String component = null;
                if (event.getSource() != null) component = event.getSource().getComponent();
                if (event.getReportingComponent() != null && component == null) component = event.getReportingComponent();
                if (component == null || !component.contains("cluster-autoscaler")) continue;

                String reason = event.getReason();
                if (reason == null) continue;
                boolean isScaleEvent = reason.contains("ScaleUp") || reason.contains("ScaleDown") ||
                    reason.contains("ScaledUp") || reason.contains("ScaleDownEmpty") ||
                    reason.contains("ScaleDownCandidate") || reason.contains("RemovingNode") ||
                    reason.contains("NodeRemoved");
                if (!isScaleEvent) continue;

                Instant eventTime = null;
                String lastTimestamp = event.getLastTimestamp();
                if (lastTimestamp != null && !lastTimestamp.isBlank()) {
                    try { eventTime = Instant.parse(lastTimestamp); } catch (Exception ignored) {}
                }
                if (eventTime == null && event.getEventTime() != null) {
                    try { eventTime = Instant.parse(event.getEventTime().getTime()); } catch (Exception ignored) {}
                }
                if (eventTime == null || eventTime.isBefore(cutoff)) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", event.getType());
                entry.put("reason", reason);
                entry.put("message", event.getMessage());
                entry.put("object", event.getInvolvedObject() != null ? event.getInvolvedObject().getName() : "");
                entry.put("last_time", eventTime.toString());
                entry.put("count", event.getCount() != null ? event.getCount() : 1);
                eventList.add(entry);

                // 24h summary
                if (!eventTime.isBefore(cutoff24h)) {
                    if (reason.contains("Up") || reason.contains("ScaledUp")) {
                        scaleUpCount24h++;
                        if (lastScaleUp == null || eventTime.toString().compareTo(lastScaleUp) > 0)
                            lastScaleUp = eventTime.toString();
                    }
                    if (reason.contains("Down") || reason.contains("Remov")) {
                        scaleDownCount24h++;
                        if (lastScaleDown == null || eventTime.toString().compareTo(lastScaleDown) > 0)
                            lastScaleDown = eventTime.toString();
                    }
                }
            }

            eventList.sort((a, b) -> ((String) b.get("last_time")).compareTo((String) a.get("last_time")));
        } catch (Exception e) {
            log.warn("Failed to get autoscaling events: {}", e.getMessage());
        }

        result.put("events", eventList);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scale_up_count_24h", scaleUpCount24h);
        summary.put("scale_down_count_24h", scaleDownCount24h);
        summary.put("last_scale_up", lastScaleUp);
        summary.put("last_scale_down", lastScaleDown);
        result.put("summary", summary);
        return result;
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
