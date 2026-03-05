package com.lakeon.service;

import com.lakeon.model.entity.AlertEntity;
import com.lakeon.model.entity.AlertRuleEntity;
import com.lakeon.repository.AlertRepository;
import com.lakeon.repository.AlertRuleRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alertRepository;
    private final AlertRuleRepository ruleRepository;
    private final AdminService adminService;
    private final MeterRegistry meterRegistry;
    private final HttpClient httpClient;

    public AlertService(AlertRepository alertRepository,
                        AlertRuleRepository ruleRepository,
                        AdminService adminService,
                        MeterRegistry meterRegistry) {
        this.alertRepository = alertRepository;
        this.ruleRepository = ruleRepository;
        this.adminService = adminService;
        this.meterRegistry = meterRegistry;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        initDefaultRules();
    }

    private void initDefaultRules() {
        if (ruleRepository.count() == 0) {
            createRule("component_down", "component_down", 0.0, "critical", 10);
            createRule("wakeup_failure", "wakeup_failure", 0.0, "critical", 10);
            createRule("api_high_latency", "api_latency", 2000.0, "warning", 5);
            createRule("storage_limit", "storage_limit", 90.0, "warning", 30);
            createRule("pod_abnormal", "pod_abnormal", 300.0, "warning", 10);
        }
    }

    private void createRule(String name, String checkType, double threshold, String severity, int cooldown) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setName(name);
        rule.setCheckType(checkType);
        rule.setThreshold(threshold);
        rule.setEnabled(true);
        rule.setCooldownMinutes(cooldown);
        ruleRepository.save(rule);
    }

    @Scheduled(fixedRate = 60000)
    public void runChecks() {
        List<AlertRuleEntity> rules = ruleRepository.findByEnabledTrue();
        for (AlertRuleEntity rule : rules) {
            try {
                checkRule(rule);
            } catch (Exception e) {
                log.warn("Alert check failed for rule {}: {}", rule.getName(), e.getMessage());
            }
        }
    }

    private void checkRule(AlertRuleEntity rule) {
        boolean shouldFire = switch (rule.getCheckType()) {
            case "component_down" -> checkComponentDown();
            case "wakeup_failure" -> checkWakeupFailure();
            case "api_latency" -> checkApiLatency(rule.getThreshold());
            case "storage_limit" -> checkStorageLimit(rule.getThreshold());
            case "pod_abnormal" -> false; // TODO: implement pod state check
            default -> false;
        };

        List<AlertEntity> firingAlerts = alertRepository.findByRuleNameAndStatus(rule.getName(), "firing");

        if (shouldFire) {
            // Check cooldown
            if (rule.getLastFiredAt() != null) {
                Instant cooldownEnd = rule.getLastFiredAt().plus(Duration.ofMinutes(rule.getCooldownMinutes()));
                if (Instant.now().isBefore(cooldownEnd)) {
                    return;
                }
            }

            if (firingAlerts.isEmpty()) {
                String message = buildMessage(rule);
                AlertEntity alert = new AlertEntity();
                alert.setRuleName(rule.getName());
                alert.setSeverity(rule.getCheckType().equals("component_down") || rule.getCheckType().equals("wakeup_failure") ? "critical" : "warning");
                alert.setMessage(message);
                alert.setStatus("firing");
                alert.setFiredAt(Instant.now());
                alertRepository.save(alert);

                rule.setLastFiredAt(Instant.now());
                ruleRepository.save(rule);

                sendWebhook(rule, alert);
                log.info("Alert fired: {} - {}", rule.getName(), message);
            }
        } else {
            // Auto-resolve any firing alerts for this rule
            for (AlertEntity alert : firingAlerts) {
                alert.setStatus("resolved");
                alert.setResolvedAt(Instant.now());
                alertRepository.save(alert);
                log.info("Alert resolved: {}", rule.getName());
            }
        }
    }

    private boolean checkComponentDown() {
        Map<String, Object> health = adminService.checkAllComponents();
        for (var entry : health.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> component = (Map<String, Object>) entry.getValue();
                if ("unhealthy".equals(component.get("status"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkWakeupFailure() {
        Counter counter = meterRegistry.find("lakeon_compute_wakeup_failures_total").counter();
        return counter != null && counter.count() > 0;
    }

    private boolean checkApiLatency(double thresholdMs) {
        Timer timer = meterRegistry.find("http.server.requests").timer();
        if (timer == null || timer.count() == 0) return false;
        double maxMs = timer.max(java.util.concurrent.TimeUnit.MILLISECONDS);
        return maxMs > thresholdMs;
    }

    private boolean checkStorageLimit(double thresholdPercent) {
        // thresholdPercent is percentage (e.g. 90.0)
        // For now, check if estimateStorageBytes > 50GB * thresholdPercent/100
        double used = adminService.estimateStorageBytes();
        double limit = 50.0 * 1024 * 1024 * 1024; // 50GB default
        return (used / limit * 100) > thresholdPercent;
    }

    private String buildMessage(AlertRuleEntity rule) {
        return switch (rule.getCheckType()) {
            case "component_down" -> "One or more components are unhealthy";
            case "wakeup_failure" -> "Compute wakeup failures detected";
            case "api_latency" -> "API P99 latency exceeds " + rule.getThreshold() + "ms";
            case "storage_limit" -> "Storage usage exceeds " + rule.getThreshold() + "% of limit";
            case "pod_abnormal" -> "Compute pod in abnormal state for > " + rule.getThreshold() + "s";
            default -> "Alert triggered: " + rule.getName();
        };
    }

    private void sendWebhook(AlertRuleEntity rule, AlertEntity alert) {
        if (rule.getWebhookUrl() == null || rule.getWebhookUrl().isBlank()) return;

        try {
            String json = buildWebhookPayload(alert);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rule.getWebhookUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> log.info("Webhook response for {}: {}", rule.getName(), resp.statusCode()))
                .exceptionally(e -> { log.warn("Webhook failed for {}: {}", rule.getName(), e.getMessage()); return null; });
        } catch (Exception e) {
            log.warn("Failed to send webhook for {}: {}", rule.getName(), e.getMessage());
        }
    }

    private String buildWebhookPayload(AlertEntity alert) {
        // Build a generic webhook payload that works with WeChat Work, DingTalk, etc.
        // WeChat Work format: {"msgtype":"markdown","markdown":{"content":"..."}}
        String content = "**Lakeon Alert**\\n" +
            "> Rule: " + alert.getRuleName() + "\\n" +
            "> Severity: " + alert.getSeverity() + "\\n" +
            "> Message: " + alert.getMessage() + "\\n" +
            "> Time: " + alert.getFiredAt();
        return "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"" + content + "\"}}";
    }

    // ── Public API methods ──

    public List<AlertEntity> getAlerts() {
        return alertRepository.findAllByOrderByFiredAtDesc();
    }

    public List<AlertRuleEntity> getRules() {
        return ruleRepository.findAll();
    }

    public AlertRuleEntity updateRule(String ruleId, Map<String, Object> updates) {
        AlertRuleEntity rule = ruleRepository.findById(ruleId)
            .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Alert rule not found: " + ruleId));

        if (updates.containsKey("enabled")) {
            rule.setEnabled((Boolean) updates.get("enabled"));
        }
        if (updates.containsKey("threshold")) {
            rule.setThreshold(((Number) updates.get("threshold")).doubleValue());
        }
        if (updates.containsKey("webhook_url")) {
            rule.setWebhookUrl((String) updates.get("webhook_url"));
        }
        if (updates.containsKey("cooldown_minutes")) {
            rule.setCooldownMinutes(((Number) updates.get("cooldown_minutes")).intValue());
        }
        return ruleRepository.save(rule);
    }

    public Map<String, Object> testWebhook(String webhookUrl) {
        try {
            String json = "{\"msgtype\":\"text\",\"text\":{\"content\":\"Lakeon alert test notification\"}}";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return Map.of("status", resp.statusCode(), "body", resp.body());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
