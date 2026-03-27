package com.lakeon.notebook;

import com.lakeon.repository.TenantRepository;
import com.lakeon.model.entity.TenantEntity;
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

    private final TenantRepository tenantRepository;
    private final NotebookService notebookService;
    private final KubernetesClient k8sClient;
    private final Map<String, ExecConnection> execConnections = new ConcurrentHashMap<>();

    public NotebookWebSocketHandler(TenantRepository tenantRepository,
                                     NotebookService notebookService,
                                     KubernetesClient k8sClient) {
        this.tenantRepository = tenantRepository;
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
        TenantEntity tenant = tenantRepository.findByApiKey(token).orElse(null);
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
            OutputForwarder forwarder = new OutputForwarder(wsSession);
            ExecWatch exec = k8sClient.pods()
                    .inNamespace(session.getNamespace())
                    .withName(session.getPodName())
                    .redirectingInput()
                    .writingOutput(forwarder)
                    .writingError(forwarder)
                    .exec("python", "/app/repl_server.py");
            OutputStream stdin = exec.getInput();
            log.info("Created exec connection to pod {}/{}", session.getNamespace(), session.getPodName());
            return new ExecConnection(exec, stdin);
        } catch (Exception e) {
            log.error("Failed to exec into notebook pod {}: {}", session.getPodName(), e.getMessage());
            return null;
        }
    }

    // Forwards pod stdout/stderr to WebSocket as JSON lines
    private static class OutputForwarder extends OutputStream {
        private final WebSocketSession wsSession;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        OutputForwarder(WebSocketSession wsSession) { this.wsSession = wsSession; }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n') {
                flush();
            } else {
                buffer.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    flush();
                } else {
                    buffer.write(b[i]);
                }
            }
        }

        @Override
        public void flush() {
            if (buffer.size() == 0) return;
            String line = buffer.toString(StandardCharsets.UTF_8).trim();
            buffer.reset();
            if (line.isEmpty()) return;
            try {
                if (wsSession.isOpen()) {
                    wsSession.sendMessage(new TextMessage(line));
                }
            } catch (Exception e) {
                // WS closed, ignore
            }
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
