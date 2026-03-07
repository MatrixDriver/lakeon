package com.lakeon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Huawei Cloud CBC (Cloud Billing Center) integration.
 * Fetches actual billing data filtered by Lakeon resource IDs.
 */
@Service
public class CbcBillingService {
    private static final Logger log = LoggerFactory.getLogger(CbcBillingService.class);
    private static final String CBC_HOST = "bss.myhuaweicloud.com";
    private static final DateTimeFormatter ISO_BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("yyyyMMdd");

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
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Cache: 5 min TTL
    private volatile String cachedBillCycle;
    private volatile Map<String, Object> cachedResult;
    private volatile long cacheTime;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    public CbcBillingService(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
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

        try {
            // Fetch all resource records for the billing cycle, paginated
            List<JsonNode> allRecords = fetchAllResRecords(billCycle);
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
    private List<JsonNode> fetchAllResRecords(String billCycle) {
        List<JsonNode> allRecords = new ArrayList<>();
        int offset = 0;
        int limit = 100;

        while (true) {
            String query = "bill_cycle=" + billCycle + "&offset=" + offset + "&limit=" + limit;
            String raw = callCbcApi("/v2/bills/customer-bills/res-records", query);
            if (raw == null) return allRecords.isEmpty() ? null : allRecords;

            try {
                JsonNode root = objectMapper.readTree(raw);
                JsonNode records = root.get("monthly_records");
                if (records == null || !records.isArray() || records.isEmpty()) break;

                for (JsonNode r : records) {
                    allRecords.add(r);
                }

                int totalCount = root.has("total_count") ? root.get("total_count").asInt() : 0;
                offset += limit;
                if (offset >= totalCount) break;
            } catch (Exception e) {
                log.error("Failed to parse res-records response", e);
                break;
            }
        }
        return allRecords;
    }

    /**
     * Aggregate records filtered by resource IDs into a cost summary.
     */
    private Map<String, Object> aggregateRecords(List<JsonNode> records, Set<String> resourceIds, String billCycle) {
        double totalAmount = 0;
        Map<String, Double> byService = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();

        for (JsonNode r : records) {
            String resourceId = r.has("resource_id") ? r.get("resource_id").asText() : "";
            if (!resourceIds.contains(resourceId)) continue;

            double amount = r.has("amount") ? r.get("amount").asDouble() : 0;
            totalAmount += amount;

            String serviceType = r.has("cloud_service_type") ? r.get("cloud_service_type").asText() : "unknown";
            String label = SERVICE_LABELS.getOrDefault(serviceType, serviceType);
            byService.merge(label, amount, Double::sum);

            String resourceName = r.has("resource_name") ? r.get("resource_name").asText() : resourceId;
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

    /**
     * Call a CBC API endpoint with SDK-HMAC-SHA256 signing.
     */
    private String callCbcApi(String uri, String query) {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();
        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            log.warn("CBC billing: AK/SK not configured, skipping");
            return null;
        }

        try {
            Instant now = Instant.now();
            String dateStamp = ISO_BASIC.format(now.atOffset(ZoneOffset.UTC));
            String dateShort = DATE_SHORT.format(now.atOffset(ZoneOffset.UTC));

            String signedHeaders = "host;x-sdk-date";
            String canonicalHeaders = "host:" + CBC_HOST + "\n" + "x-sdk-date:" + dateStamp + "\n";
            String payloadHash = sha256Hex("");

            String canonicalRequest = String.join("\n",
                    "GET", uri, query, canonicalHeaders, signedHeaders, payloadHash);

            String credentialScope = dateShort + "//bss/sdk_request";
            String stringToSign = String.join("\n",
                    "SDK-HMAC-SHA256", dateStamp, credentialScope,
                    sha256Hex(canonicalRequest));

            byte[] kDate = hmacSha256(("SDK" + sk).getBytes(StandardCharsets.UTF_8), dateShort);
            byte[] kRegion = hmacSha256(kDate, "");
            byte[] kService = hmacSha256(kRegion, "bss");
            byte[] kSigning = hmacSha256(kService, "sdk_request");
            String signature = hexEncode(hmacSha256(kSigning, stringToSign));

            String authorization = String.format(
                    "SDK-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s",
                    ak, credentialScope, signedHeaders, signature);

            String url = "https://" + CBC_HOST + uri + "?" + query;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Host", CBC_HOST)
                    .header("X-Sdk-Date", dateStamp)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("CBC API {} response: HTTP {}", uri, response.statusCode());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                log.error("CBC API error: HTTP {} - {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("CBC API call failed: {}", uri, e);
            return null;
        }
    }

    /**
     * Fetch raw monthly bill summary (kept for /admin/cost/cbc endpoint).
     */
    public String fetchMonthlyBillSummary(String billCycle) {
        return callCbcApi("/v2/bills/customer-bills/monthly-sum", "bill_cycle=" + billCycle);
    }

    public String getCurrentMonthBilling() {
        String cycle = YearMonth.now(ZoneOffset.UTC).toString();
        return fetchMonthlyBillSummary(cycle);
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hexEncode(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
