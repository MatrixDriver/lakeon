package com.lakeon.notebook;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotebookWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(NotebookWebSocketHandler.class);

    private final TenantService tenantService;
    private final NotebookService notebookService;
    private final KubernetesClient k8sClient;
    private final Map<String, ExecConnection> execConnections = new ConcurrentHashMap<>();

    public NotebookWebSocketHandler(TenantService tenantService,
                                     NotebookService notebookService,
                                     KubernetesClient k8sClient) {
        this.tenantService = tenantService;
        this.notebookService = notebookService;
        this.k8sClient = k8sClient;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        String token = UriComponentsBuilder.fromUri(wsSession.getUri()).build()
                .getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("Missing token"));
            return;
        }
        TenantEntity tenant = tenantService.authenticateByApiKey(token);
        if (tenant == null) {
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid token"));
            return;
        }
        wsSession.getAttributes().put("tenantId", tenant.getId());
        log.info("Notebook WS connected: tenant={}, wsSession={}", tenant.getId(), wsSession.getId());

        // Create exec connection eagerly so client receives "ready" message
        Thread initThread = new Thread(() -> {
            try {
                // Wait for pod to be running (poll up to 30s)
                NotebookSessionEntity session = null;
                for (int i = 0; i < 30; i++) {
                    session = notebookService.getSession(tenant.getId()).orElse(null);
                    if (session != null && session.getStatus() == NotebookSessionStatus.RUNNING) break;
                    Thread.sleep(1000);
                }
                if (session == null || session.getStatus() != NotebookSessionStatus.RUNNING) {
                    wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Kernel pod not ready after 30s.\"}"));
                    return;
                }
                ExecConnection conn = createExecConnection(session, wsSession);
                if (conn != null) {
                    execConnections.put(wsSession.getId(), conn);
                } else {
                    wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Failed to connect to kernel pod.\"}"));
                }
            } catch (Exception e) {
                log.warn("Failed to init exec for WS {}: {}", wsSession.getId(), e.getMessage());
            }
        }, "notebook-init-" + wsSession.getId());
        initThread.setDaemon(true);
        initThread.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        String tenantId = (String) wsSession.getAttributes().get("tenantId");
        if (tenantId == null) { wsSession.close(CloseStatus.POLICY_VIOLATION); return; }

        notebookService.getSession(tenantId).ifPresent(s -> notebookService.touchSession(s.getId()));

        ExecConnection conn = execConnections.get(wsSession.getId());
        if (conn == null || conn.closed) {
            wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Kernel not connected yet. Please wait...\"}"));
            return;
        }

        try {
            conn.stdin.write((message.getPayload() + "\n").getBytes(StandardCharsets.UTF_8));
            conn.stdin.flush();
        } catch (IOException e) {
            log.warn("Failed to write to pod stdin: {}", e.getMessage());
            execConnections.remove(wsSession.getId());
            conn.close();
            wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Kernel connection lost.\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        ExecConnection conn = execConnections.remove(wsSession.getId());
        if (conn != null) conn.close();
        log.info("Notebook WS disconnected: wsSession={}", wsSession.getId());
    }

    private ExecConnection createExecConnection(NotebookSessionEntity session, WebSocketSession wsSession) {
        try {
            ExecWatch exec = k8sClient.pods()
                    .inNamespace(session.getNamespace())
                    .withName(session.getPodName())
                    .redirectingInput()
                    .redirectingOutput()
                    .redirectingError()
                    .exec("python", "-u", "/app/repl_server.py");

            OutputStream stdin = exec.getInput();
            InputStream stdout = exec.getOutput();
            InputStream stderr = exec.getError();

            log.info("Exec streams: stdin={}, stdout={}, stderr={}", stdin != null, stdout != null, stderr != null);

            // Background thread: read stdout line by line, forward to WS
            Thread readerThread = new Thread(() -> {
                log.info("Exec reader thread started for {}", session.getPodName());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("Exec stdout: {}", line);
                        if (!line.isBlank() && wsSession.isOpen()) {
                            try {
                                wsSession.sendMessage(new TextMessage(line));
                            } catch (Exception e) {
                                log.warn("Failed to send WS message: {}", e.getMessage());
                                break;
                            }
                        }
                    }
                    log.info("Exec reader thread ended (readLine returned null)");
                } catch (Exception e) {
                    log.warn("Exec stdout reader error: {}", e.getMessage());
                }
            }, "notebook-reader-" + session.getId());
            readerThread.setDaemon(true);
            readerThread.start();

            // Background thread for stderr (forward as error messages)
            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("Notebook stderr: {}", line);
                    }
                } catch (Exception ignored) {}
            }, "notebook-stderr-" + session.getId());
            errThread.setDaemon(true);
            errThread.start();

            log.info("Created exec connection to pod {}/{}", session.getNamespace(), session.getPodName());
            return new ExecConnection(exec, stdin);
        } catch (Exception e) {
            log.error("Failed to exec into notebook pod {}: {}", session.getPodName(), e.getMessage(), e);
            return null;
        }
    }

    private static class ExecConnection {
        final ExecWatch exec;
        final OutputStream stdin;
        volatile boolean closed = false;
        ExecConnection(ExecWatch exec, OutputStream stdin) { this.exec = exec; this.stdin = stdin; }
        void close() { closed = true; try { exec.close(); } catch (Exception ignored) {} }
    }
}
