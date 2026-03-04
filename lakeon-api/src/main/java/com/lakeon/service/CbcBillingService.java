package com.lakeon.service;

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

    public CbcBillingService(LakeonProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newHttpClient();
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
                    "GET", uri + "/", query, canonicalHeaders, signedHeaders, payloadHash);

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
                    .header("X-Sdk-Date", dateStamp)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
