package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.hwcloud.HuaweiBssBillingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Huawei Cloud CBC (Cloud Billing Center) integration.
 * Fetches actual billing data filtered by Lakeon resource IDs.
 */
@Service
public class CbcBillingService {
    private static final Logger log = LoggerFactory.getLogger(CbcBillingService.class);

    private static final Map<String, String> SERVICE_LABELS = Map.ofEntries(
        Map.entry("hws.service.type.cce", "CCE 集群"),
        Map.entry("hws.service.type.ecs", "弹性云服务器"),
        Map.entry("hws.service.type.vpc", "虚拟私有云"),
        Map.entry("hws.service.type.eip", "弹性公网 IP"),
        Map.entry("hws.service.type.elb", "弹性负载均衡"),
        Map.entry("hws.service.type.obs", "OBS 对象存储"),
        Map.entry("hws.service.type.rds", "RDS 数据库"),
        Map.entry("hws.service.type.nat", "NAT 网关"),
        Map.entry("hws.service.type.bandwidth", "带宽"),
        Map.entry("hws.service.type.evs", "云硬盘")
    );

    private final LakeonProperties props;
    private final HuaweiBssBillingClient billingClient;

    // Cache: 5 min TTL
    private volatile String cachedBillCycle;
    private volatile Map<String, Object> cachedResult;
    private volatile long cacheTime;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    public CbcBillingService(LakeonProperties props,
                             ObjectMapper objectMapper,
                             HuaweiBssBillingClient billingClient) {
        this.props = props;
        this.billingClient = billingClient;
    }

    /**
     * Get parsed monthly bill filtered by Lakeon resource IDs.
     * Uses res-records API for per-resource billing, filtered by configured resource IDs.
     * Returns null if CBC is not configured or API call fails.
     */
    public Map<String, Object> getMonthlyBillParsed(String billCycle) {
        if (billCycle == null || billCycle.isBlank()) {
            billCycle = YearMonth.now(ZoneOffset.UTC).toString();
        }

        // Check cache
        long now = System.currentTimeMillis();
        if (cachedResult != null && billCycle.equals(cachedBillCycle) && (now - cacheTime) < CACHE_TTL_MS) {
            return cachedResult;
        }

        List<String> resourceIds = props.getCloud().getResourceIds();
        if (resourceIds == null || resourceIds.isEmpty()) {
            log.warn("CBC billing: no resource IDs configured (lakeon.cloud.resource-ids), skipping");
            return null;
        }
        if (!hasCredentials()) {
            log.warn("CBC billing: AK/SK not configured, skipping");
            return null;
        }

        try {
            // Fetch all resource records for the billing cycle, paginated
            List<HuaweiBssBillingClient.ResourceRecord> allRecords = fetchAllResRecords(billCycle);
            if (allRecords == null) return null;

            // Filter by Lakeon resource IDs
            Set<String> idSet = new HashSet<>(resourceIds);
            Map<String, Object> parsed = aggregateRecords(allRecords, idSet, billCycle);

            cachedBillCycle = billCycle;
            cachedResult = parsed;
            cacheTime = now;
            return parsed;
        } catch (Exception e) {
            log.error("Failed to get CBC billing", e);
            return null;
        }
    }

    /**
     * Fetch all res-records pages for a billing cycle.
     */
    private List<HuaweiBssBillingClient.ResourceRecord> fetchAllResRecords(String billCycle) {
        List<HuaweiBssBillingClient.ResourceRecord> allRecords = new ArrayList<>();
        int offset = 0;
        int limit = 100;

        while (true) {
            try {
                HuaweiBssBillingClient.Page page = billingClient.listResourceRecords(billCycle, offset, limit);
                if (page.records() == null || page.records().isEmpty()) break;
                allRecords.addAll(page.records());
                offset += limit;
                if (offset >= page.totalCount()) break;
            } catch (Exception e) {
                log.error("Failed to parse res-records response", e);
                return allRecords.isEmpty() ? null : allRecords;
            }
        }
        return allRecords;
    }

    /**
     * Aggregate records filtered by resource IDs into a cost summary.
     */
    private Map<String, Object> aggregateRecords(List<HuaweiBssBillingClient.ResourceRecord> records,
                                                 Set<String> resourceIds,
                                                 String billCycle) {
        double totalAmount = 0;
        Map<String, Double> byService = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();

        for (HuaweiBssBillingClient.ResourceRecord r : records) {
            String resourceId = r.resourceId() != null ? r.resourceId() : "";
            if (!resourceIds.contains(resourceId)) continue;

            double amount = r.amount() != null ? r.amount().doubleValue() : 0;
            totalAmount += amount;

            String serviceType = r.cloudServiceType() != null ? r.cloudServiceType() : "unknown";
            String label = SERVICE_LABELS.getOrDefault(serviceType, serviceType);
            byService.merge(label, amount, Double::sum);

            String resourceName = r.resourceName() != null ? r.resourceName() : resourceId;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("resource_id", resourceId);
            detail.put("resource_name", resourceName);
            detail.put("service_type", label);
            detail.put("amount", Math.round(amount * 100.0) / 100.0);
            details.add(detail);
        }

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", Math.round(totalAmount * 100.0) / 100.0);

        // Sort breakdown by cost descending
        Map<String, Object> breakdown = new LinkedHashMap<>();
        byService.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .forEach(e -> breakdown.put(e.getKey(), Math.round(e.getValue() * 100.0) / 100.0));
        result.put("breakdown", breakdown);

        // Sort details by amount descending
        details.sort((a, b) -> Double.compare(
            ((Number) b.get("amount")).doubleValue(),
            ((Number) a.get("amount")).doubleValue()));
        result.put("details", details);

        result.put("source", "cbc");
        result.put("bill_cycle", billCycle);
        result.put("resource_count", details.size());
        result.put("currency", "CNY");
        return result;
    }

    private boolean hasCredentials() {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();
        return ak != null && !ak.isBlank() && sk != null && !sk.isBlank();
    }

    /**
     * Fetch raw monthly bill summary (kept for /admin/cost/cbc endpoint).
     */
    public String fetchMonthlyBillSummary(String billCycle) {
        if (!hasCredentials()) {
            log.warn("CBC billing: AK/SK not configured, skipping");
            return null;
        }
        try {
            return billingClient.fetchMonthlyBillSummaryJson(billCycle);
        } catch (Exception e) {
            log.error("CBC monthly summary call failed", e);
            return null;
        }
    }

    public String getCurrentMonthBilling() {
        String cycle = YearMonth.now(ZoneOffset.UTC).toString();
        return fetchMonthlyBillSummary(cycle);
    }
}
