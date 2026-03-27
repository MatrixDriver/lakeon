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
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        String tenantId = (String) wsSession.getAttributes().get("tenantId");
        if (tenantId == null) { wsSession.close(CloseStatus.POLICY_VIOLATION); return; }

        NotebookSessionEntity session = notebookService.getSession(tenantId).orElse(null);
        if (session == null || session.getStatus() != NotebookSessionStatus.RUNNING) {
            wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"No active kernel. Start a session first.\"}"));
            return;
        }
        notebookService.touchSession(session.getId());

        ExecConnection conn = execConnections.get(wsSession.getId());
        if (conn == null || conn.closed) {
            execConnections.remove(wsSession.getId());
            conn = createExecConnection(session, wsSession);
            if (conn == null) {
                wsSession.sendMessage(new TextMessage("{\"type\":\"error\",\"traceback\":\"Failed to connect to kernel pod.\"}"));
                return;
            }
            execConnections.put(wsSession.getId(), conn);
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

            // Background thread: read stdout line by line, forward to WS
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank() && wsSession.isOpen()) {
                            try {
                                wsSession.sendMessage(new TextMessage(line));
                            } catch (Exception e) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!e.getMessage().contains("closed")) {
                        log.warn("Exec stdout reader error: {}", e.getMessage());
                    }
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
