package com.lakeon.compute;

import com.lakeon.config.LakeonProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ComputeReconfigureClient} using a local {@link HttpServer}
 * as a mock compute_ctl. The handler keys behavior off the request body so a
 * single context can cover success / 4xx / timeout cases without needing
 * multiple clients or contexts.
 *
 * Request body sentinels:
 *   "FORCE_4XX" -> respond 403 with body {"error":"forbidden"}
 *   "SLOW"      -> sleep 2000ms then respond 200 (used for timeout test)
 *   anything else -> respond 200 with body {"status":"running"}
 */
class ComputeReconfigureClientTest {
    // Duplicated from ComputeJwtSignerTest — single-task scope; refactor to a
    // shared helper if a third test file ends up needing this PEM (B2.4+).
    private static final String TEST_PRIVATE_PEM = """
        -----BEGIN PRIVATE KEY-----
        MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDN0p9GrsdxuaIU
        72R56Wr7VLpRcFvbmEW613V7DnAuazIkDY8g5/GV+aLI3yhcNOoSVRS/WjZfb5up
        e0oFF6dmk54LDbvu47HuqZmAOHVaBna2QZai2AGwbv0sGkA5uaIzO1u8BrGCb//f
        Y2yA56kgBo62w8GyjM0LT84lIZ/2EW0g0GN4lG1BwnvVwI7OFaDj65r8xpgNs9Fq
        1pj9+SReghNjXDN/McR9PiUEwaoNdkAX3gF8GnwP3BOqrsmpdnNceCai+orGg5Fq
        wbZRDddZC+So+lPE3TjWbtEOqN0Dy+MyB9jtl5y+2r8gnrT1c3fpJidfc3r1nsim
        GYoReqJlAgMBAAECggEAKt6RBr2eNIGD8STYkmzr97vSz+Ydd9xcP7mjUlV0R7LT
        n39EfcfZwZFclsamRyhNTbKzbtS5KQEb1L3lcbCW0S5zd11ttKjv1WQ1YOfBh5X7
        kWQRXksr9OX1LQOtt2hDELUvGvdw7xhpXMu+XI4D47QB8y7MUCZ0CcReTU0W3n8s
        aMkW8lyyztFoFRwlgcdgyrQ5uDLKjD76KAWS7VqA8PsSQgWz4A/YRrjeonfjkhox
        0WS6isPy3nqcUD/752Y9J+C6tN3XLBHk1TXto3yruB6P7Z7u+2YC2OMAlF9vkgaX
        X82PCHZcIi4nNPOED7fhjGvUVjMiEguUEJUGiQo2UQKBgQDzYWxc9aWcs7jq5A7d
        WUpWWMRu/VQD/ECLRxVNQKAZdcFCdtL6//87d3j270Xf8wmumduiyepyaAc3bwlA
        KVcjRQeQfLDpxwWr2ypDhxZ3XFnac6PiJOPAOsDC3nu5eUx+2ahTaYZqumxAZYLd
        Ni4rBWRCZcMz66o64S2oKEWblQKBgQDYfqpJ29uooaZ4fjOEZow4KH78PdoieRq/
        FycP3jwTa1HnXcjB3KtIYU/FlWt4+PnC1nB1XroOlt0tT2dAXHQqVRiz3SUstGYJ
        2TAMvBKvxrcrE48nnBoUg5Vihg06xNMwEdvESTHn2Sad6s+IEyQGfDx+0z4zJYyi
        bvIUGv63kQKBgB0IgC7OtwTsg6Cxt/w7zJxkVnqPCdi33NNAlY/zp6Wh4H4XQq/i
        ngXwCKQcgw9mJL+JZyQSRj+DnWjFfCsFQ3nXoEFiPpCEx25q5K3NjaaLg8SFiwVN
        NUYXPCuC8ut7Rt7TBDt/GSPePU+pTGUrM3K6X+1zykeFU3dWqWWn+DXJAoGARzl8
        2qm7XYI5G2Ehn8iBDyS7ik7rCfZfx0hdsInDp/vhyUWAe88WhsyFCxL6daUrvl8A
        Rozwl0Yo4/RAmtsP2LGAXARAa7G59DmA5l+lojC1KDXaHgTsS51ysyQ5DAGfHSxy
        6ePOyGEXpFKRDkqFyqBq4qqqxvbuiq4HdHfhHxECgYEAr2FmgDRl8yf5gDsIkLIE
        ccUHULYgZMYVepx9Uxciiqr1qJ9jtekC1dWbNGO5FcT2tuW4Wu87Mu6FR5yzrq5o
        zNSk0cWgbmTirhizNpt5c6+hnG0ZhIjtdBJqxtshBBemvYcJvWjfgjIuxpyPS/yK
        Kp5CRTey17WLqCWAO9zQP6Y=
        -----END PRIVATE KEY-----
        """;

    private HttpServer server;
    private int port;
    private AtomicReference<String> lastAuthHeader;
    private AtomicReference<String> lastContentType;
    private AtomicReference<String> lastBody;
    private ComputeReconfigureClient client;
    private LakeonProperties props;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        lastAuthHeader = new AtomicReference<>();
        lastContentType = new AtomicReference<>();
        lastBody = new AtomicReference<>();
        server.createContext("/configure", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);

            int status;
            byte[] payload;
            if ("FORCE_4XX".equals(body)) {
                status = 403;
                payload = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
            } else if ("SLOW".equals(body)) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                status = 200;
                payload = "{\"status\":\"running\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                payload = "{\"status\":\"running\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();

        props = new LakeonProperties();
        props.getComputeJwt().setPrivateKey(TEST_PRIVATE_PEM);
        props.getComputeJwt().setKid("test-key-1");
        props.getComputeWarmPool().setReconfigureTimeoutMs(1500);
        props.getComputeWarmPool().setComputeCtlPort(port);
        ComputeJwtSigner signer = new ComputeJwtSigner(props);
        client = new ComputeReconfigureClient(props, signer);
    }

    @AfterEach
    void teardown() {
        if (server != null) server.stop(0);
    }

    @Test
    void reconfigure_success_returns200() {
        String spec = "{\"spec\":1}";
        ComputeReconfigureClient.Result r = client.reconfigure("pod-1", "127.0.0.1", spec);

        assertThat(r.success()).isTrue();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.errorMessage()).isNull();
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(0).isLessThan(1500);

        // Server received auth + content-type + body
        assertThat(lastAuthHeader.get()).startsWith("Bearer ");
        assertThat(lastContentType.get()).isEqualTo("application/json");
        assertThat(lastBody.get()).isEqualTo(spec);
    }

    @Test
    void reconfigure_4xx_returnsFailureWithBody() {
        ComputeReconfigureClient.Result r = client.reconfigure("pod-1", "127.0.0.1", "FORCE_4XX");

        assertThat(r.success()).isFalse();
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.errorMessage()).isEqualTo("{\"error\":\"forbidden\"}");
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reconfigure_signerNotConfigured_returnsFailureImmediately() {
        LakeonProperties empty = new LakeonProperties();
        ComputeReconfigureClient c = new ComputeReconfigureClient(empty, new ComputeJwtSigner(empty));

        ComputeReconfigureClient.Result r = c.reconfigure("pod-x", "127.0.0.1", "{}");

        assertThat(r.success()).isFalse();
        assertThat(r.statusCode()).isEqualTo(-1);
        assertThat(r.errorMessage()).containsIgnoringCase("signer not configured");
        assertThat(r.elapsedMs()).isEqualTo(0);
    }

    @Test
    void reconfigure_timeout_returnsTimeoutFailure() {
        // Server sleeps 2s; configure client to time out at 200ms.
        props.getComputeWarmPool().setReconfigureTimeoutMs(200);
        ComputeJwtSigner signer = new ComputeJwtSigner(props);
        ComputeReconfigureClient c = new ComputeReconfigureClient(props, signer);

        ComputeReconfigureClient.Result r = c.reconfigure("pod-1", "127.0.0.1", "SLOW");

        assertThat(r.success()).isFalse();
        assertThat(r.statusCode()).isEqualTo(-1);
        assertThat(r.errorMessage()).containsIgnoringCase("timeout");
        // Timer fires somewhere between the configured 200ms and the 2000ms server sleep.
        // Loose upper bound (1500ms) because java.net.http.HttpClient's timer wakes at
        // coarse intervals on some JDKs — observed 553/686/810ms locally. The crucial
        // guarantee is `<< 2000ms` (server sleep): below that, the timer DID fire
        // before the server would have responded. Tighter bounds risk flaking on slow
        // CI runners without buying more regression catching power.
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(150).isLessThan(1500);
    }
}
