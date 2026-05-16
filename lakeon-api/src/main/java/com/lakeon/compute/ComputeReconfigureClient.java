package com.lakeon.compute;

import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * HTTP client for compute_ctl's {@code POST /configure} endpoint on a specific
 * compute pod. Signs a short-lived RS256 JWT via {@link ComputeJwtSigner},
 * sends the spec JSON body, and returns a {@link Result} record carrying
 * success/status/elapsed-ms so callers can both branch on outcome and emit
 * latency metrics (B4).
 *
 * Designed for the warm-pool reconfigure path: hard per-call timeout
 * (default 1500ms from {@code lakeon.compute-warm-pool.reconfigure-timeout-ms})
 * plus a 500ms connect timeout so a wedged pod cannot block a cold start.
 *
 * Plan: docs/superpowers/plans/2026-05-16-compute-warm-pool.md (Phase B2.3).
 */
@Component
public class ComputeReconfigureClient {
    private static final Logger log = LoggerFactory.getLogger(ComputeReconfigureClient.class);
    private static final int BODY_TRUNC = 256;

    public record Result(boolean success, int statusCode, String errorMessage, long elapsedMs) {}

    private final LakeonProperties props;
    private final ComputeJwtSigner jwtSigner;
    private final HttpClient httpClient;

    public ComputeReconfigureClient(LakeonProperties props, ComputeJwtSigner jwtSigner) {
        this.props = props;
        this.jwtSigner = jwtSigner;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();
    }

    /**
     * POST the given spec JSON to compute_ctl on the target pod. Blocking;
     * guaranteed to return within {@code reconfigureTimeoutMs} (plus a small
     * scheduling slack).
     *
     * @param podName  used as the compute_ctl JWT subject; must equal the
     *                 pod's {@code --compute-id} arg.
     * @param podIp    pod IP. Port comes from
     *                 {@code lakeon.compute-warm-pool.compute-ctl-port}
     *                 (default 3080).
     * @param specJson rendered {@code ComputeSchema} body.
     */
    public Result reconfigure(String podName, String podIp, String specJson) {
        if (!jwtSigner.isConfigured()) {
            return new Result(false, -1, "signer not configured", 0);
        }
        long start = System.currentTimeMillis();
        try {
            String token = jwtSigner.signComputeCtlToken(podName);
            int timeoutMs = props.getComputeWarmPool().getReconfigureTimeoutMs();
            int port = props.getComputeWarmPool().getComputeCtlPort();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + podIp + ":" + port + "/configure"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(specJson))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return new Result(true, code, null, elapsed);
            }
            String body = response.body();
            String truncated = body == null
                ? null
                : body.substring(0, Math.min(body.length(), BODY_TRUNC));
            log.warn("compute_ctl /configure pod={} status={} body={}", podName, code, truncated);
            return new Result(false, code, truncated, elapsed);
        } catch (HttpTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("compute_ctl /configure pod={} timed out after {}ms", podName, elapsed);
            return new Result(false, -1, "timeout after " + elapsed + "ms", elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - start;
            return new Result(false, -1, "interrupted: " + e.getMessage(), elapsed);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("compute_ctl /configure pod={} io error: {}", podName, e.toString());
            return new Result(false, -1, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed);
        }
    }
}
