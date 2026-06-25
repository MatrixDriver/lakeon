package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PageserverMetricsLoadCollector implements PageserverLoadProvider {
    private static final Logger log = LoggerFactory.getLogger(PageserverMetricsLoadCollector.class);

    private final LakeonProperties props;
    private final HttpClient httpClient;
    private final PageserverNodeProvider nodeProvider;
    private final AtomicReference<PageserverLoadSnapshot> current = new AtomicReference<>(PageserverLoadSnapshot.empty());

    @Autowired
    public PageserverMetricsLoadCollector(LakeonProperties props, ObjectProvider<PageserverNodeProvider> nodeProvider) {
        this(
            props,
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getDicer().getMetricsTimeoutMs())).build(),
            nodeProvider.getIfAvailable());
    }

    PageserverMetricsLoadCollector(LakeonProperties props) {
        this(
            props,
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getDicer().getMetricsTimeoutMs())).build(),
            null);
    }

    PageserverMetricsLoadCollector(LakeonProperties props, PageserverNodeProvider nodeProvider) {
        this(
            props,
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getDicer().getMetricsTimeoutMs())).build(),
            nodeProvider);
    }

    PageserverMetricsLoadCollector(LakeonProperties props, HttpClient httpClient) {
        this(props, httpClient, null);
    }

    PageserverMetricsLoadCollector(LakeonProperties props, HttpClient httpClient, PageserverNodeProvider nodeProvider) {
        this.props = props;
        this.httpClient = httpClient;
        this.nodeProvider = nodeProvider;
    }

    @Override
    public PageserverLoadSnapshot snapshot() {
        PageserverLoadSnapshot snapshot = current.get();
        if (!snapshot.isFresh() || snapshot.observedAt() == null) {
            return PageserverLoadSnapshot.empty();
        }
        long ageMs = Duration.between(snapshot.observedAt(), Instant.now()).toMillis();
        if (ageMs > props.getDicer().getSnapshotTtlMs()) {
            return PageserverLoadSnapshot.empty();
        }
        return snapshot;
    }

    @Scheduled(
        initialDelayString = "${lakeon.dicer.live-load-initial-delay-ms:5000}",
        fixedDelayString = "${lakeon.dicer.live-load-poll-interval-ms:10000}")
    public void refresh() {
        if (!props.getDicer().isEnabled() || !props.getDicer().isLiveLoadEnabled()) {
            current.set(PageserverLoadSnapshot.empty());
            return;
        }

        Map<String, Double> scores = new HashMap<>();
        Set<String> unavailable = new HashSet<>();
        for (PageserverNode node : configuredNodes()) {
            try {
                String metrics = fetchMetrics(node);
                scores.put(node.id(), parseLoadScore(metrics));
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                unavailable.add(node.id());
                log.warn("Failed to collect pageserver metrics for node {} from {}: {}",
                    node.id(), node.httpUrl(), e.getMessage());
            }
        }
        if (scores.isEmpty() && !unavailable.isEmpty()) {
            current.set(PageserverLoadSnapshot.empty());
            return;
        }
        current.set(PageserverLoadSnapshot.fresh(scores, unavailable, Instant.now(), "dicer-live"));
    }

    private String fetchMetrics(PageserverNode node) throws IOException, InterruptedException {
        URI uri = URI.create(trimTrailingSlash(node.httpUrl()) + "/metrics");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(props.getDicer().getMetricsTimeoutMs()))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    double parseLoadScore(String metrics) {
        double score = 0.0d;
        for (String line : metrics.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String metricName = metricName(trimmed);
            if (isLoadMetric(metricName)) {
                score += parseMetricValue(trimmed);
            }
        }
        return score;
    }

    private boolean isLoadMetric(String metricName) {
        return metricName.equals("pageserver_resident_physical_size")
            || metricName.equals("pageserver_current_logical_size")
            || metricName.equals("pageserver_remote_physical_size")
            || metricName.equals("pageserver_io_operations_total")
            || metricName.equals("pageserver_http_requests_total");
    }

    private double parseMetricValue(String line) {
        int lastSpace = line.lastIndexOf(' ');
        if (lastSpace < 0 || lastSpace == line.length() - 1) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(line.substring(lastSpace + 1));
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private String metricName(String line) {
        int space = line.indexOf(' ');
        String nameAndLabels = space >= 0 ? line.substring(0, space) : line;
        int labels = nameAndLabels.indexOf('{');
        return labels >= 0 ? nameAndLabels.substring(0, labels) : nameAndLabels;
    }

    private java.util.List<PageserverNode> configuredNodes() {
        if (nodeProvider != null) {
            java.util.List<PageserverNode> dynamicNodes = nodeProvider.nodes();
            if (dynamicNodes != null && !dynamicNodes.isEmpty()) {
                return dynamicNodes;
            }
        }
        return props.getNeon().getPageserverNodes().stream()
            .map(config -> {
                String httpUrl = config.getHttpUrl();
                String id = config.getId() == null || config.getId().isBlank()
                    ? hostFromUrl(httpUrl)
                    : config.getId();
                String pgHost = config.getPgHost() == null || config.getPgHost().isBlank()
                    ? hostFromUrl(httpUrl)
                    : config.getPgHost();
                int pgPort = config.getPgPort() > 0 ? config.getPgPort() : 6400;
                return new PageserverNode(id, httpUrl, pgHost, pgPort);
            })
            .toList();
    }

    private String hostFromUrl(String url) {
        URI uri = URI.create(url);
        return uri.getHost() == null ? url : uri.getHost();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
