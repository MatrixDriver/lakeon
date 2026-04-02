package com.lakeon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class SreAiService {
    private static final Logger log = LoggerFactory.getLogger(SreAiService.class);
    private static final String MODEL = "deepseek-ai/DeepSeek-V3.2";
    private static final int MAX_TOOL_ROUNDS = 5;

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AdminService adminService;
    private final AlertService alertService;
    private final DatabaseRepository databaseRepository;
    private final OperationLogRepository operationLogRepository;
    private final LogQueryService logQueryService;

    private static final String SYSTEM_PROMPT = """
        你是 DBay 平台的 SRE 智能助手。帮助运维工程师快速定位和诊断系统问题。

        你的工作流程：
        1. 理解用户的问题
        2. 调用工具收集相关系统信息
        3. 分析数据，判断根因
        4. 给出具体的修复建议和操作步骤

        你有两类数据源：
        - 实时数据：系统健康、Pod 日志、K8s 事件、告警、数据库状态
        - 结构化日志：存储在 dbay-logs 数据库中的历史日志，支持按组件/级别/租户/关键词搜索，支持 requestId 调用链追踪

        排查问题时的优先策略：
        - 先用 log_errors 看最近有没有错误
        - 如果有错误，用 log_trace 追踪该请求的完整调用链
        - 用 log_search 搜索相关关键词深入分析
        - 用 log_stats 了解全局错误率和慢操作

        规则：
        - 用中文回答，简洁直接
        - 先收集数据再下结论，不要猜测
        - 给出具体的 kubectl 命令或操作步骤
        - 如果信息不足，说明还需要什么信息
        - 如果问题不在你能力范围内，直接说明
        """;

    // Tool definitions in OpenAI function calling format
    private static final List<Map<String, Object>> TOOLS = List.of(
        tool("get_system_health", "获取所有组件健康状态（API、Pageserver、Safekeeper、Proxy、RDS、OBS）",
            Map.of("type", "object", "properties", Map.of())),
        tool("get_component_logs", "获取指定组件的 Pod 日志",
            Map.of("type", "object",
                "properties", Map.of(
                    "component", Map.of("type", "string", "enum", List.of("lakeon-api", "pageserver", "safekeeper", "proxy"), "description", "组件名称"),
                    "tail", Map.of("type", "integer", "description", "日志行数，默认100")),
                "required", List.of("component"))),
        tool("list_databases", "列出所有数据库实例及其状态",
            Map.of("type", "object",
                "properties", Map.of(
                    "status", Map.of("type", "string", "enum", List.of("RUNNING", "SUSPENDED", "CREATING", "FAILED", "DELETED"), "description", "按状态筛选")),
                "required", List.of())),
        tool("get_database_detail", "获取单个数据库详细信息",
            Map.of("type", "object",
                "properties", Map.of(
                    "id", Map.of("type", "string", "description", "数据库ID")),
                "required", List.of("id"))),
        tool("list_recent_operations", "查看最近的操作日志",
            Map.of("type", "object",
                "properties", Map.of(
                    "tenant_id", Map.of("type", "string", "description", "按租户ID筛选"),
                    "status", Map.of("type", "string", "enum", List.of("SUCCESS", "FAILED", "PENDING"), "description", "按状态筛选")),
                "required", List.of())),
        tool("get_infra_events", "获取 Kubernetes 事件（Pod 调度、拉取镜像、OOM 等）",
            Map.of("type", "object",
                "properties", Map.of(
                    "namespace", Map.of("type", "string", "description", "命名空间，默认 lakeon-compute")),
                "required", List.of())),
        tool("get_alerts", "获取当前活跃的告警列表",
            Map.of("type", "object", "properties", Map.of())),
        tool("get_compute_stats", "获取计算资源统计（运行/暂停的 Pod 数量、资源用量）",
            Map.of("type", "object", "properties", Map.of())),
        tool("log_search", "搜索结构化日志（按组件、级别、租户、关键词筛选）",
            Map.of("type", "object",
                "properties", Map.of(
                    "component", Map.of("type", "string", "description", "组件名称：lakeon-api, knowledge-pipeline, pageserver 等"),
                    "level", Map.of("type", "string", "enum", List.of("ERROR", "WARN", "INFO", "DEBUG"), "description", "日志级别"),
                    "keyword", Map.of("type", "string", "description", "关键词（全文搜索）"),
                    "tenant_id", Map.of("type", "string", "description", "租户ID"),
                    "since", Map.of("type", "string", "description", "时间范围，如 1h, 6h, 24h, 7d"),
                    "limit", Map.of("type", "integer", "description", "返回条数，默认50")),
                "required", List.of())),
        tool("log_trace", "按 requestId 追踪请求调用链（跨组件全链路日志）",
            Map.of("type", "object",
                "properties", Map.of(
                    "request_id", Map.of("type", "string", "description", "请求ID (req_xxxxxxxx)")),
                "required", List.of("request_id"))),
        tool("log_errors", "查看最近的错误和警告日志",
            Map.of("type", "object",
                "properties", Map.of(
                    "since", Map.of("type", "string", "description", "时间范围，默认 1h"),
                    "component", Map.of("type", "string", "description", "按组件筛选")),
                "required", List.of())),
        tool("log_stats", "日志统计概览（各组件日志量、错误数、慢操作 Top10）",
            Map.of("type", "object",
                "properties", Map.of(
                    "since", Map.of("type", "string", "description", "时间范围，默认 24h")),
                "required", List.of()))
    );

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
            "name", name, "description", description, "parameters", parameters));
    }

    public SreAiService(LakeonProperties props, ObjectMapper objectMapper,
                        AdminService adminService, AlertService alertService,
                        DatabaseRepository databaseRepository,
                        OperationLogRepository operationLogRepository,
                        LogQueryService logQueryService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.adminService = adminService;
        this.alertService = alertService;
        this.databaseRepository = databaseRepository;
        this.operationLogRepository = operationLogRepository;
        this.logQueryService = logQueryService;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Execute a tool call and return the result as a string (summarized).
     */
    public String executeTool(String name, JsonNode args) {
        try {
            return switch (name) {
                case "get_system_health" -> objectMapper.writeValueAsString(adminService.checkAllComponents());
                case "get_component_logs" -> {
                    String component = args.path("component").asText("lakeon-api");
                    int tail = args.path("tail").asInt(100);
                    String logs = adminService.getComponentLogs(component, Math.min(tail, 200));
                    // Summarize: keep only ERROR/WARN lines if too long
                    if (logs != null && logs.lines().count() > 80) {
                        String filtered = logs.lines()
                            .filter(l -> l.contains("ERROR") || l.contains("WARN") || l.contains("Exception"))
                            .reduce("", (a, b) -> a + "\n" + b);
                        yield filtered.isBlank() ? "(最近日志中无 ERROR/WARN)" : filtered.strip();
                    }
                    yield logs != null ? logs : "(无日志)";
                }
                case "list_databases" -> {
                    String status = args.has("status") ? args.path("status").asText() : null;
                    List<DatabaseEntity> dbs;
                    if (status != null) {
                        dbs = databaseRepository.findAllByStatus(
                            com.lakeon.model.enums.DatabaseStatus.valueOf(status));
                    } else {
                        dbs = databaseRepository.findAll();
                    }
                    // Summarize
                    var summary = dbs.stream().map(db -> Map.of(
                        "id", db.getId(), "name", db.getName(),
                        "tenant_id", db.getTenantId(),
                        "status", db.getStatus().name(),
                        "status_message", db.getStatusMessage() != null ? db.getStatusMessage() : ""
                    )).toList();
                    yield objectMapper.writeValueAsString(summary);
                }
                case "get_database_detail" -> {
                    String id = args.path("id").asText();
                    DatabaseEntity db = databaseRepository.findById(id).orElse(null);
                    if (db == null) yield "数据库 " + id + " 不存在";
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("id", db.getId());
                    detail.put("name", db.getName());
                    detail.put("tenant_id", db.getTenantId());
                    detail.put("status", db.getStatus().name());
                    detail.put("status_message", db.getStatusMessage());
                    detail.put("compute_size", db.getComputeSize());
                    detail.put("compute_pod_name", db.getComputePodName());
                    detail.put("last_active_at", db.getLastActiveAt());
                    detail.put("created_at", db.getCreatedAt());
                    yield objectMapper.writeValueAsString(detail);
                }
                case "list_recent_operations" -> {
                    String tenantId = args.has("tenant_id") ? args.path("tenant_id").asText() : null;
                    var page = org.springframework.data.domain.PageRequest.of(0, 20,
                        org.springframework.data.domain.Sort.by("startedAt").descending());
                    var ops = (tenantId != null)
                        ? operationLogRepository.findByTenantIdOrderByStartedAtDesc(tenantId, page)
                        : operationLogRepository.findAllByOrderByStartedAtDesc(page);
                    var list = ops.getContent().stream().map(op -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", op.getOperationType());
                        m.put("status", op.getStatus());
                        m.put("tenant_id", op.getTenantId());
                        m.put("error", op.getErrorMessage());
                        m.put("time", op.getStartedAt());
                        return m;
                    }).toList();
                    yield objectMapper.writeValueAsString(list);
                }
                case "get_infra_events" -> {
                    String ns = args.path("namespace").asText("lakeon-compute");
                    yield objectMapper.writeValueAsString(adminService.getPodEvents(ns));
                }
                case "get_alerts" -> objectMapper.writeValueAsString(alertService.getAlerts());
                case "get_compute_stats" -> objectMapper.writeValueAsString(adminService.getComputeStats());
                case "log_search" -> objectMapper.writeValueAsString(logQueryService.search(
                    args.path("component").asText(null),
                    args.path("level").asText(null),
                    args.path("keyword").asText(null),
                    args.path("tenant_id").asText(null),
                    args.path("since").asText("1h"),
                    args.path("limit").asInt(50)));
                case "log_trace" -> objectMapper.writeValueAsString(logQueryService.trace(
                    args.path("request_id").asText()));
                case "log_errors" -> objectMapper.writeValueAsString(logQueryService.errors(
                    args.path("since").asText("1h"),
                    args.path("component").asText(null)));
                case "log_stats" -> objectMapper.writeValueAsString(logQueryService.stats(
                    args.path("since").asText("24h")));
                default -> "未知工具: " + name;
            };
        } catch (Exception e) {
            log.warn("Tool execution failed: {} — {}", name, e.getMessage());
            return "工具调用失败: " + e.getMessage();
        }
    }

    /**
     * Call SiliconFlow chat/completions API.
     */
    public JsonNode callLlm(List<Map<String, Object>> messages) throws Exception {
        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI service not configured (missing API key)");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        String aiModel = props.getAi().getModel();
        requestBody.put("model", aiModel.isEmpty() ? MODEL : aiModel);
        requestBody.put("messages", messages);
        requestBody.put("tools", TOOLS);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 4000);

        String body = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("AI API error " + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * Run the full chat loop: user message → LLM → tool calls → LLM → ... → final answer.
     * Sends SSE events to the emitter as it progresses.
     */
    public void chat(List<Map<String, Object>> userMessages, Map<String, String> context,
                     SseEmitter emitter) {
        try {
            // Build messages
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

            // Append context if provided
            if (context != null && context.containsKey("resource_type")) {
                String ctxMsg = "[系统上下文] 当前关注的资源: " + context.get("resource_type")
                    + "=" + context.getOrDefault("resource_id", "unknown");
                messages.add(Map.of("role", "system", "content", ctxMsg));
            }

            messages.addAll(userMessages);

            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                sendSse(emitter, "thinking", round == 0 ? "正在分析问题..." : "继续分析...");

                JsonNode result = callLlm(messages);
                JsonNode choice = result.path("choices").path(0).path("message");
                String finishReason = result.path("choices").path(0).path("finish_reason").asText("");

                // If LLM wants to call tools
                if (choice.has("tool_calls") && choice.path("tool_calls").isArray()
                        && choice.path("tool_calls").size() > 0) {
                    // Add assistant message with tool_calls to conversation
                    messages.add(objectMapper.convertValue(choice, Map.class));

                    for (JsonNode toolCall : choice.path("tool_calls")) {
                        String toolName = toolCall.path("function").path("name").asText();
                        String toolId = toolCall.path("id").asText();
                        JsonNode toolArgs = objectMapper.readTree(
                            toolCall.path("function").path("arguments").asText("{}"));

                        sendSse(emitter, "tool_call", toolName);

                        String toolResult = executeTool(toolName, toolArgs);

                        // Truncate large results
                        if (toolResult.length() > 3000) {
                            toolResult = toolResult.substring(0, 3000) + "\n...(截断)";
                        }

                        sendSse(emitter, "tool_result", toolName + ": " +
                            (toolResult.length() > 100 ? toolResult.substring(0, 100) + "..." : toolResult));

                        // Add tool result to conversation
                        messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", toolId,
                            "content", toolResult
                        ));
                    }
                    continue; // Next round — let LLM process tool results
                }

                // Final content response
                String content = choice.path("content").asText("");
                if (!content.isBlank()) {
                    sendSse(emitter, "content", content);
                }
                break;
            }

            sendSse(emitter, "done", "");
            emitter.complete();
        } catch (Exception e) {
            log.error("SRE AI chat error", e);
            try {
                sendSse(emitter, "error", "AI 服务异常: " + e.getMessage());
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    private void sendSse(SseEmitter emitter, String type, String content) {
        try {
            Map<String, String> event = Map.of("type", type, "content", content);
            emitter.send(SseEmitter.event()
                .data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getToolDefinitions() {
        return TOOLS;
    }
}
