package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PageserverMetricsLoadCollectorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void refreshParsesPrometheusMetricsIntoLoadSnapshot() throws Exception {
        String body = """
            # HELP pageserver_resident_physical_size pageserver memory bytes
            pageserver_resident_physical_size 2048
            pageserver_current_logical_size 4096
            pageserver_remote_physical_size 8192
            """;
        int port = startServer(200, body);
        LakeonProperties props = propsFor("http://127.0.0.1:" + port);
        PageserverMetricsLoadCollector collector = new PageserverMetricsLoadCollector(props);

        collector.refresh();

        PageserverLoadSnapshot snapshot = collector.snapshot();
        assertThat(snapshot.isFresh()).isTrue();
        assertThat(snapshot.source()).isEqualTo("dicer-live");
        assertThat(snapshot.unavailableNodeIds()).isEmpty();
        assertThat(snapshot.loadScores()).containsEntry("ps-0", 14336.0d);
    }

    @Test
    void refreshCollectsMetricsFromDynamicMembership() throws Exception {
        String body = "pageserver_resident_physical_size 1024\n";
        int port = startServer(200, body);
        LakeonProperties props = propsFor("http://static-pageserver:9898");
        PageserverNodeProvider dynamicNodes = () -> java.util.List.of(
            new PageserverNode("ps-dynamic", "http://127.0.0.1:" + port, "pageserver-0.pageserver-headless.lakeon.svc.cluster.local", 6400)
        );
        PageserverMetricsLoadCollector collector = new PageserverMetricsLoadCollector(props, dynamicNodes);

        collector.refresh();

        PageserverLoadSnapshot snapshot = collector.snapshot();
        assertThat(snapshot.isFresh()).isTrue();
        assertThat(snapshot.loadScores()).containsEntry("ps-dynamic", 1024.0d);
        assertThat(snapshot.loadScores()).doesNotContainKey("ps-0");
    }

    @Test
    void refreshDoesNotPublishSnapshotWhenAllMetricsFetchesFail() throws Exception {
        int port = startServer(503, "down");
        LakeonProperties props = propsFor("http://127.0.0.1:" + port);
        PageserverMetricsLoadCollector collector = new PageserverMetricsLoadCollector(props);

        collector.refresh();

        PageserverLoadSnapshot snapshot = collector.snapshot();
        assertThat(snapshot.isFresh()).isFalse();
        assertThat(snapshot.loadScores()).isEmpty();
        assertThat(snapshot.unavailableNodeIds()).isEmpty();
    }

    private int startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private LakeonProperties propsFor(String httpUrl) {
        LakeonProperties props = new LakeonProperties();
        props.getDicer().setEnabled(true);
        props.getDicer().setLiveLoadEnabled(true);
        props.getDicer().setMetricsTimeoutMs(500);
        props.getDicer().setSnapshotTtlMs(Duration.ofMinutes(1).toMillis());
        props.getNeon().setPageserverNodes(java.util.List.of(
            new LakeonProperties.PageserverNodeConfig("ps-0", httpUrl, "pageserver-0", 6400)
        ));
        return props;
    }
}
