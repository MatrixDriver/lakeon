package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WikiAgentClientTest {
    HttpServer server;
    int port;
    AtomicReference<String> lastPath = new AtomicReference<>();
    AtomicReference<String> lastAuth = new AtomicReference<>();
    AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void installHandler(String path, int status, String body) {
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                lastPath.set(ex.getRequestURI().getPath());
                lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
                try (InputStream in = ex.getRequestBody()) {
                    lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            }
        });
        server.start();
    }

    private WikiAgentClient newClient() {
        LakeonProperties props = new LakeonProperties();
        props.getWiki().getAgent().setUrl("http://localhost:" + port);
        props.getWiki().getAgent().setInternalToken("test-token");
        return new WikiAgentClient(props, new ObjectMapper());
    }

    @Test
    void triggerIngestReturnsTaskIdOn202() {
        installHandler("/v1/wiki/ingest", 202, "{\"task_id\":\"task_abc\",\"status\":\"accepted\"}");

        String taskId = newClient().triggerIngest("t1", "kb1", "doc1");

        assertEquals("task_abc", taskId);
        assertEquals("/v1/wiki/ingest", lastPath.get());
        assertEquals("Bearer test-token", lastAuth.get());
        assertTrue(lastBody.get().contains("\"tenant_id\":\"t1\""));
        assertTrue(lastBody.get().contains("\"kb_id\":\"kb1\""));
        assertTrue(lastBody.get().contains("\"document_id\":\"doc1\""));
        assertTrue(lastBody.get().contains("\"source\":\"queue\""));
    }

    @Test
    void triggerCurateReturnsTaskIdOn200() {
        installHandler("/v1/wiki/curate", 200, "{\"task_id\":\"task_xyz\",\"status\":\"accepted\"}");

        String taskId = newClient().triggerCurate("t1", "kb1");

        assertEquals("task_xyz", taskId);
        assertTrue(lastBody.get().contains("\"source\":\"manual\""));
    }

    @Test
    void triggerLintReturnsTaskId() {
        installHandler("/v1/wiki/lint", 202, "{\"task_id\":\"task_lint\"}");

        String taskId = newClient().triggerLint("t1", "kb1");

        assertEquals("task_lint", taskId);
    }

    @Test
    void returnsNullOnNon2xxResponse() {
        installHandler("/v1/wiki/ingest", 500, "{\"error\":\"boom\"}");

        String taskId = newClient().triggerIngest("t1", "kb1", "doc1");

        assertNull(taskId);
    }

    @Test
    void returnsNullOnMissingUrlConfig() {
        LakeonProperties props = new LakeonProperties();
        props.getWiki().getAgent().setUrl("");  // blank
        props.getWiki().getAgent().setInternalToken("t");
        WikiAgentClient client = new WikiAgentClient(props, new ObjectMapper());

        String taskId = client.triggerIngest("t1", "kb1", "doc1");

        assertNull(taskId);
    }

    @Test
    void returnsNullOnConnectionFailure() {
        // Point at a closed port — OS returns connect-refused immediately
        LakeonProperties props = new LakeonProperties();
        props.getWiki().getAgent().setUrl("http://localhost:1");
        props.getWiki().getAgent().setInternalToken("t");
        WikiAgentClient client = new WikiAgentClient(props, new ObjectMapper());

        String taskId = client.triggerIngest("t1", "kb1", "doc1");

        assertNull(taskId);
    }

    @Test
    void returnsNullWhen2xxResponseHasNoTaskId() {
        installHandler("/v1/wiki/ingest", 200, "{\"status\":\"error\",\"reason\":\"no capacity\"}");

        String taskId = newClient().triggerIngest("t1", "kb1", "doc1");

        assertNull(taskId);
    }
}
