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
 * Fetches actual billing data using AK/SK authentication.
 */
@Service
public class CbcBillingService {
    private static final Logger log = LoggerFactory.getLogger(CbcBillingService.class);
    private static final String CBC_HOST = "bss.myhuaweicloud.com";
    private static final DateTimeFormatter ISO_BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LakeonProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Cache: avoid hitting CBC API too frequently (5 min TTL)
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
     * Get parsed monthly bill summary with breakdown by service type.
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

        String raw = fetchMonthlyBillSummary(billCycle);
        if (raw == null) return null;

        try {
            Map<String, Object> parsed = parseBillResponse(raw);
            parsed.put("bill_cycle", billCycle);
            // Update cache
            cachedBillCycle = billCycle;
            cachedResult = parsed;
            cacheTime = now;
            return parsed;
        } catch (Exception e) {
            log.error("Failed to parse CBC response", e);
            return null;
        }
    }

    /**
     * Parse CBC monthly-sum response into a structured breakdown.
     */
    private Map<String, Object> parseBillResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        Map<String, Object> result = new LinkedHashMap<>();

        // Map Huawei Cloud service types to readable names
        Map<String, String> serviceLabels = Map.ofEntries(
            Map.entry("hws.service.type.cce", "CCE \\u96C6\\u7FA4"),
            Map.entry("hws.service.type.ecs", "\\u5F39\\u6027\\u4E91\\u670D\\u52A1\\u5668"),
            Map.entry("hws.service.type.vpc", "\\u865A\\u62DF\\u79C1\\u6709\\u4E91"),
            Map.entry("hws.service.type.eip", "\\u5F39\\u6027\\u516C\\u7F51 IP"),
            Map.entry("hws.service.type.elb", "\\u5F39\\u6027\\u8D1F\\u8F7D\\u5747\\u8861"),
            Map.entry("hws.service.type.obs", "OBS \\u5BF9\\u8C61\\u5B58\\u50A8"),
            Map.entry("hws.service.type.rds", "RDS \\u6570\\u636E\\u5E93"),
            Map.entry("hws.service.type.nat", "NAT \\u7F51\\u5173"),
            Map.entry("hws.service.type.bandwidth", "\\u5E26\\u5BBD")
        );

        double totalAmount = 0;
        Map<String, Double> breakdown = new LinkedHashMap<>();

        JsonNode billSums = root.get("bill_sums");
        if (billSums != null && billSums.isArray()) {
            for (JsonNode item : billSums) {
                String serviceType = item.has("cloud_service_type") ? item.get("cloud_service_type").asText() : "unknown";
                double amount = item.has("amount") ? item.get("amount").asDouble() : 0;
                totalAmount += amount;

                String label = serviceLabels.getOrDefault(serviceType, serviceType);
                breakdown.merge(label, amount, Double::sum);
            }
        }

        result.put("total", Math.round(totalAmount * 100.0) / 100.0);
        // Sort breakdown by cost descending
        Map<String, Object> sortedBreakdown = new LinkedHashMap<>();
        breakdown.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .forEach(e -> sortedBreakdown.put(e.getKey(), Math.round(e.getValue() * 100.0) / 100.0));
        result.put("breakdown", sortedBreakdown);
        result.put("source", "cbc");
        result.put("currency", root.has("currency") ? root.get("currency").asText() : "CNY");

        return result;
    }

    /**
     * Fetch monthly bill summary from CBC API.
     * Returns the raw JSON response as a string, or null on failure.
     */
    public String fetchMonthlyBillSummary(String billCycle) {
        String ak = props.getObs().getAccessKey();
        String sk = props.getObs().getSecretKey();
        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            log.warn("CBC billing: AK/SK not configured, skipping");
            return null;
        }

        try {
            String uri = "/v2/bills/customer-bills/monthly-sum";
            String query = "bill_cycle=" + billCycle;
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
            log.info("CBC API response: HTTP {}", response.statusCode());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                log.error("CBC API error: HTTP {} - {}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("CBC billing fetch failed", e);
            return null;
        }
    }

    /**
     * Get current month billing summary.
     */
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
