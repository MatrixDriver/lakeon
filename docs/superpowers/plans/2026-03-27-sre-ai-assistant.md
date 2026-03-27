# SRE AI Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an AI-powered diagnostic assistant to the SRE admin console that can query system data via function calling and provide root cause analysis.

**Architecture:** Backend `SreAiService` + `SreAiController` handle the LLM conversation loop with tool calling against existing admin services. Frontend `AiChatPanel.vue` provides a right-side sliding chat panel with SSE streaming. `AiDiagnoseButton.vue` adds contextual diagnose buttons on abnormal resources.

**Tech Stack:** Spring Boot (Java 17), SiliconFlow OpenAI-compatible API (DeepSeek V3.2), Vue 3 + TypeScript, SSE (Server-Sent Events)

---

### Task 1: Create SreAiService — tool definitions and execution

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/SreAiService.java`

- [ ] **Step 1: Create the service class with tool definitions**

Create `lakeon-api/src/main/java/com/lakeon/service/SreAiService.java`:

```java
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

    private static final String SYSTEM_PROMPT = """
        你是 DBay 平台的 SRE 智能助手。帮助运维工程师快速定位和诊断系统问题。

        你的工作流程：
        1. 理解用户的问题
        2. 调用工具收集相关系统信息
        3. 分析数据，判断根因
        4. 给出具体的修复建议和操作步骤

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
            Map.of("type", "object", "properties", Map.of()))
    );

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
            "name", name, "description", description, "parameters", parameters));
    }

    public SreAiService(LakeonProperties props, ObjectMapper objectMapper,
                        AdminService adminService, AlertService alertService,
                        DatabaseRepository databaseRepository,
                        OperationLogRepository operationLogRepository) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.adminService = adminService;
        this.alertService = alertService;
        this.databaseRepository = databaseRepository;
        this.operationLogRepository = operationLogRepository;
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
                    String opStatus = args.has("status") ? args.path("status").asText() : null;
                    var page = org.springframework.data.domain.PageRequest.of(0, 20,
                        org.springframework.data.domain.Sort.by("createdAt").descending());
                    var ops = (tenantId != null)
                        ? operationLogRepository.findByTenantId(tenantId, page)
                        : operationLogRepository.findAll(page);
                    var list = ops.getContent().stream().map(op -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", op.getType());
                        m.put("status", op.getStatus());
                        m.put("tenant_id", op.getTenantId());
                        m.put("resource_id", op.getResourceId());
                        m.put("error", op.getErrorMessage());
                        m.put("created_at", op.getCreatedAt());
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
        requestBody.put("model", MODEL);
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
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/jacky/code/lakeon/lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/SreAiService.java
git commit -m "feat(sre-ai): add SreAiService with tool definitions and execution"
```

---

### Task 2: Create SreAiController — SSE endpoint

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/SreAiController.java`

- [ ] **Step 1: Create the controller**

Create `lakeon-api/src/main/java/com/lakeon/controller/SreAiController.java`:

```java
package com.lakeon.controller;

import com.lakeon.service.SreAiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/admin")
public class SreAiController {

    private final SreAiService sreAiService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SreAiController(SreAiService sreAiService) {
        this.sreAiService = sreAiService;
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/ai/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min timeout

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        Map<String, String> context = (Map<String, String>) body.get("context");

        executor.execute(() -> sreAiService.chat(messages, context, emitter));

        return emitter;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/jacky/code/lakeon/lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/SreAiController.java
git commit -m "feat(sre-ai): add SSE chat endpoint at /admin/ai/chat"
```

---

### Task 3: Add AI chat API method to admin client

**Files:**
- Modify: `lakeon-admin/src/api/admin.ts`

- [ ] **Step 1: Add aiChat method**

In `lakeon-admin/src/api/admin.ts`, add at the end before the closing `}`:

```typescript

  // AI Assistant
  aiChat: (messages: Array<{role: string; content: string}>, context?: {resource_type: string; resource_id: string}) => {
    const token = localStorage.getItem('lakeon_admin_token')
    const baseUrl = '/api/v1/admin'
    const directUrl = 'https://api.dbay.cloud:8443/api/v1/admin'

    // Try proxy first, fall back to direct — use fetch for SSE (axios doesn't support streaming)
    return fetch(`${baseUrl}/ai/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ messages, context }),
    }).then(res => {
      if (res.status === 502 || res.status === 503 || res.status === 504) {
        // Retry with direct URL
        return fetch(`${directUrl}/ai/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
          },
          body: JSON.stringify({ messages, context }),
        })
      }
      return res
    })
  },
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-admin/src/api/admin.ts
git commit -m "feat(sre-ai): add aiChat SSE method to admin API client"
```

---

### Task 4: Create AiChatPanel component

**Files:**
- Create: `lakeon-admin/src/components/AiChatPanel.vue`

- [ ] **Step 1: Create the chat panel component**

Create `lakeon-admin/src/components/AiChatPanel.vue`:

```vue
<template>
  <!-- Floating button -->
  <button v-if="!open" class="ai-fab" @click="open = true" title="AI 助手">
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
      <line x1="10" y1="21" x2="14" y2="21"/>
    </svg>
  </button>

  <!-- Chat panel -->
  <div v-if="open" class="ai-panel">
    <div class="ai-panel-header">
      <span class="ai-panel-title">AI 诊断助手</span>
      <div style="display: flex; gap: 8px;">
        <button class="ai-header-btn" @click="clearChat" title="清空对话">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m-1 0v12a2 2 0 01-2 2H9a2 2 0 01-2-2V6"/></svg>
        </button>
        <button class="ai-header-btn" @click="open = false" title="关闭">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </button>
      </div>
    </div>

    <div class="ai-messages" ref="messagesEl">
      <div v-if="chatMessages.length === 0" class="ai-empty">
        <p>我是 DBay SRE 智能助手，可以帮你：</p>
        <ul>
          <li>诊断数据库启动失败原因</li>
          <li>分析组件异常日志</li>
          <li>排查 K8s Pod 调度问题</li>
          <li>查看系统健康和告警状态</li>
        </ul>
        <p style="color: #999; font-size: 12px; margin-top: 12px;">输入问题开始诊断</p>
      </div>
      <div v-for="(msg, i) in chatMessages" :key="i" :class="'ai-msg ai-msg-' + msg.role">
        <div v-if="msg.role === 'user'" class="ai-msg-content">{{ msg.content }}</div>
        <div v-else class="ai-msg-content">
          <!-- Thinking / tool calls -->
          <div v-if="msg.steps && msg.steps.length" class="ai-steps">
            <div v-for="(step, j) in msg.steps" :key="j" class="ai-step">
              <span v-if="step.type === 'thinking'" class="ai-step-thinking">{{ step.content }}</span>
              <span v-else-if="step.type === 'tool_call'" class="ai-step-tool">
                <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor" style="vertical-align: -1px;"><path d="M14.7 6.3l-5-5a1 1 0 0 0-1.4 0l-5 5a1 1 0 0 0 1.4 1.4L8 4.4V13a1 1 0 1 0 2 0V4.4l3.3 3.3a1 1 0 0 0 1.4-1.4z"/></svg>
                调用 {{ step.content }}
              </span>
              <span v-else-if="step.type === 'tool_result'" class="ai-step-result">{{ step.content }}</span>
            </div>
          </div>
          <!-- Final content with markdown-like rendering -->
          <div v-if="msg.content" class="ai-answer" v-html="renderMarkdown(msg.content)"></div>
          <!-- Streaming indicator -->
          <span v-if="msg.streaming" class="ai-typing">●</span>
        </div>
      </div>
    </div>

    <div class="ai-input-area">
      <textarea
        ref="inputEl"
        v-model="inputText"
        placeholder="描述你遇到的问题..."
        rows="2"
        @keydown.enter.exact.prevent="sendMessage"
        :disabled="streaming"
      ></textarea>
      <button class="ai-send-btn" @click="sendMessage" :disabled="streaming || !inputText.trim()">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M2 21l21-9L2 3v7l15 2-15 2z"/></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { adminApi } from '../api/admin'

interface ChatStep {
  type: 'thinking' | 'tool_call' | 'tool_result' | 'error'
  content: string
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  steps?: ChatStep[]
  streaming?: boolean
}

const open = ref(false)
const inputText = ref('')
const streaming = ref(false)
const chatMessages = ref<ChatMessage[]>([])
const messagesEl = ref<HTMLElement>()
const inputEl = ref<HTMLTextAreaElement>()

// Expose for external control (diagnose button)
const pendingContext = ref<{resource_type: string; resource_id: string} | undefined>()

function openWithContext(resourceType: string, resourceId: string, question: string) {
  open.value = true
  pendingContext.value = { resource_type: resourceType, resource_id: resourceId }
  inputText.value = question
  nextTick(() => sendMessage())
}

function clearChat() {
  chatMessages.value = []
  pendingContext.value = undefined
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || streaming.value) return

  chatMessages.value.push({ role: 'user', content: text })
  inputText.value = ''
  streaming.value = true

  // Add assistant placeholder
  const assistantMsg: ChatMessage = { role: 'assistant', content: '', steps: [], streaming: true }
  chatMessages.value.push(assistantMsg)
  scrollToBottom()

  try {
    // Build message history for API
    const apiMessages = chatMessages.value
      .filter(m => m.role === 'user' || (m.role === 'assistant' && m.content && !m.streaming))
      .map(m => ({ role: m.role, content: m.content }))

    const response = await adminApi.aiChat(apiMessages, pendingContext.value)
    pendingContext.value = undefined // Clear context after first use

    if (!response.ok) {
      assistantMsg.content = `请求失败: ${response.status}`
      assistantMsg.streaming = false
      streaming.value = false
      return
    }

    const reader = response.body?.getReader()
    if (!reader) {
      assistantMsg.content = '无法读取响应流'
      assistantMsg.streaming = false
      streaming.value = false
      return
    }

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const jsonStr = line.slice(5).trim()
        if (!jsonStr) continue

        try {
          const event = JSON.parse(jsonStr)
          if (event.type === 'content') {
            assistantMsg.content = event.content
          } else if (event.type === 'thinking' || event.type === 'tool_call' || event.type === 'tool_result') {
            assistantMsg.steps!.push({ type: event.type, content: event.content })
          } else if (event.type === 'error') {
            assistantMsg.content = event.content
          }
          scrollToBottom()
        } catch { /* skip malformed events */ }
      }
    }
  } catch (e: any) {
    assistantMsg.content = '连接失败: ' + (e.message || '网络错误')
  }

  assistantMsg.streaming = false
  streaming.value = false
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
}

function renderMarkdown(text: string): string {
  // Simple markdown: bold, code blocks, inline code, line breaks
  return text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
}

// Watch for open state to focus input
watch(open, (val) => {
  if (val) nextTick(() => inputEl.value?.focus())
})

defineExpose({ openWithContext })
</script>

<style scoped>
.ai-fab {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #0073e6;
  color: #fff;
  border: none;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  transition: transform 0.15s;
}
.ai-fab:hover { transform: scale(1.08); }

.ai-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 420px;
  height: 100vh;
  background: #fff;
  border-left: 1px solid #e2e8f0;
  box-shadow: -4px 0 16px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
  z-index: 1001;
}

.ai-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #e2e8f0;
  background: #f8fafc;
}
.ai-panel-title { font-weight: 600; font-size: 14px; }
.ai-header-btn {
  background: none; border: none; cursor: pointer; padding: 4px;
  color: #64748b; border-radius: 4px;
}
.ai-header-btn:hover { background: #e2e8f0; }

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-empty {
  color: #64748b;
  font-size: 13px;
  padding: 24px 8px;
}
.ai-empty ul { margin: 8px 0; padding-left: 20px; }
.ai-empty li { margin: 4px 0; }

.ai-msg-user .ai-msg-content {
  background: #0073e6;
  color: #fff;
  padding: 8px 12px;
  border-radius: 12px 12px 2px 12px;
  max-width: 85%;
  align-self: flex-end;
  font-size: 13px;
  margin-left: auto;
}

.ai-msg-assistant .ai-msg-content {
  background: #f1f5f9;
  padding: 10px 14px;
  border-radius: 12px 12px 12px 2px;
  max-width: 95%;
  font-size: 13px;
}

.ai-steps {
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ai-step { font-size: 12px; color: #64748b; }
.ai-step-thinking { font-style: italic; }
.ai-step-tool { color: #0073e6; }
.ai-step-result { color: #059669; font-size: 11px; }

.ai-answer { line-height: 1.6; }
.ai-answer :deep(pre) {
  background: #1e293b;
  color: #e2e8f0;
  padding: 8px 12px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 12px;
  margin: 8px 0;
}
.ai-answer :deep(code) {
  background: #e2e8f0;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}
.ai-answer :deep(pre code) {
  background: none;
  padding: 0;
}

.ai-typing {
  display: inline-block;
  animation: blink 1s infinite;
  color: #0073e6;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

.ai-input-area {
  border-top: 1px solid #e2e8f0;
  padding: 12px;
  display: flex;
  gap: 8px;
  background: #f8fafc;
}
.ai-input-area textarea {
  flex: 1;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  resize: none;
  outline: none;
  font-family: inherit;
}
.ai-input-area textarea:focus { border-color: #0073e6; }

.ai-send-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #0073e6;
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  align-self: flex-end;
}
.ai-send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-admin/src/components/AiChatPanel.vue
git commit -m "feat(sre-ai): add AiChatPanel component with SSE streaming"
```

---

### Task 5: Integrate AiChatPanel into AdminLayout

**Files:**
- Modify: `lakeon-admin/src/layouts/AdminLayout.vue`

- [ ] **Step 1: Add chat panel to layout template**

In `lakeon-admin/src/layouts/AdminLayout.vue`, add before the closing `</div>` of `console-layout` (line 116):

```html
      <!-- AI Chat Panel -->
      <AiChatPanel ref="aiChatRef" />
```

So lines 111-117 become:

```html
      <!-- Main Content -->
      <main class="console-main">
        <router-view />
      </main>

      <!-- AI Chat Panel -->
      <AiChatPanel ref="aiChatRef" />
    </div>
  </div>
```

- [ ] **Step 2: Add imports and provide/inject for diagnose**

Update the script section (line 119-132) to:

```typescript
import { ref, provide } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminAuthStore } from '../stores/auth'
import AiChatPanel from '../components/AiChatPanel.vue'

const router = useRouter()
const authStore = useAdminAuthStore()
const sidebarOpen = ref(false)
const aiChatRef = ref<InstanceType<typeof AiChatPanel>>()

function handleLogout() {
  authStore.logout()
  router.push('/login')
}

function openAiDiagnose(resourceType: string, resourceId: string, question: string) {
  aiChatRef.value?.openWithContext(resourceType, resourceId, question)
}

provide('openAiDiagnose', openAiDiagnose)
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-admin/src/layouts/AdminLayout.vue
git commit -m "feat(sre-ai): integrate AiChatPanel into AdminLayout with provide/inject"
```

---

### Task 6: Add diagnose buttons to DatabaseList

**Files:**
- Modify: `lakeon-admin/src/views/databases/DatabaseList.vue`

- [ ] **Step 1: Add diagnose button for FAILED databases**

In `lakeon-admin/src/views/databases/DatabaseList.vue`, find the operations `<td>` in the table row (around line 69-71 where the delete button is). Add a diagnose button before the delete button, only for FAILED status:

Find:
```html
            <td>
              <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteOne(db)">删除</button>
```

Replace with:
```html
            <td>
              <button v-if="db.status === 'FAILED'" class="btn btn-text btn-small" style="color: #0073e6;" @click="diagnose(db)">AI 诊断</button>
              <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteOne(db)">删除</button>
```

- [ ] **Step 2: Add inject and diagnose function in script**

In the script section, add after the existing imports:

```typescript
import { inject } from 'vue'

const openAiDiagnose = inject<(type: string, id: string, q: string) => void>('openAiDiagnose')

function diagnose(db: Database) {
  const q = `数据库 ${db.name} (${db.id}) 状态为 FAILED，错误信息: "${db.status_message || '无'}"。请诊断原因并给出修复建议。`
  openAiDiagnose?.('database', db.id, q)
}
```

Update the import line to include `inject`:
```typescript
import { ref, computed, onMounted, inject } from 'vue'
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-admin/src/views/databases/DatabaseList.vue
git commit -m "feat(sre-ai): add AI diagnose button to FAILED databases"
```

---

### Task 7: Add diagnose buttons to SystemHealth

**Files:**
- Modify: `lakeon-admin/src/views/system/SystemHealth.vue`

- [ ] **Step 1: Add diagnose button to unhealthy components**

In `lakeon-admin/src/views/system/SystemHealth.vue`, find the health card status area. After the status text line (`{{ comp.healthy ? '正常运行' : '异常' }}`), add:

```html
    <button v-if="!comp.healthy" class="btn btn-text btn-small" style="color: #0073e6; margin-top: 4px;"
      @click.stop="diagnose(comp)">AI 诊断</button>
```

- [ ] **Step 2: Add inject and diagnose function in script**

Add to the script section:

```typescript
import { inject } from 'vue'

const openAiDiagnose = inject<(type: string, id: string, q: string) => void>('openAiDiagnose')

function diagnose(comp: { name: string; label: string }) {
  const q = `组件 ${comp.label} (${comp.name}) 状态异常。请检查健康状态和日志，诊断原因。`
  openAiDiagnose?.('component', comp.name, q)
}
```

Update the import to include `inject`.

- [ ] **Step 3: Commit**

```bash
git add lakeon-admin/src/views/system/SystemHealth.vue
git commit -m "feat(sre-ai): add AI diagnose button to unhealthy components"
```

---

### Task 8: Verify AdminService methods exist

The SreAiService depends on `AdminService.checkAllComponents()`, `AdminService.getComponentLogs()`, `AdminService.getPodEvents()`, and `AdminService.getComputeStats()`. These are called by AdminController and should already exist. This task verifies they exist and are accessible.

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/SreAiService.java` (if needed)

- [ ] **Step 1: Verify AdminService methods exist**

Run: `cd /Users/jacky/code/lakeon && grep -n "checkAllComponents\|getComponentLogs\|getPodEvents\|getComputeStats" lakeon-api/src/main/java/com/lakeon/service/AdminService.java`

If any method is missing or has a different signature, adjust SreAiService accordingly.

- [ ] **Step 2: Verify OperationLogRepository has findByTenantId with Pageable**

Run: `cd /Users/jacky/code/lakeon && grep -n "findByTenantId\|findAll" lakeon-api/src/main/java/com/lakeon/repository/OperationLogRepository.java`

If `findByTenantId(String, Pageable)` doesn't exist, add it or use an alternative query approach.

- [ ] **Step 3: Fix any compilation issues**

Run: `cd /Users/jacky/code/lakeon/lakeon-api && mvn compile -q`

Fix any issues and commit:

```bash
git add -A lakeon-api/src/main/java/com/lakeon/
git commit -m "fix(sre-ai): fix method signatures to match existing services"
```

---

### Task 9: End-to-end verification

- [ ] **Step 1: Verify backend compiles**

Run: `cd /Users/jacky/code/lakeon/lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify frontend type checks**

Run: `cd /Users/jacky/code/lakeon/lakeon-admin && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 3: Push and deploy**

```bash
git push
IMAGE_TAG=0.9.110 ./deploy/cce/build-and-push-api.sh
# Update values-cce.yaml tag to 0.9.110
./deploy/cce/deploy.sh
```

- [ ] **Step 4: Manual test**

1. Open SRE console
2. Verify floating AI button appears bottom-right
3. Click to open chat panel
4. Type "系统健康状态如何？"
5. Verify: AI calls get_system_health tool, shows thinking steps, returns analysis
6. Type "有没有 FAILED 状态的数据库？"
7. Verify: AI calls list_databases tool, analyzes results
8. Go to database list, if any FAILED DB exists, verify "AI 诊断" button appears
9. Click "AI 诊断", verify chat opens with pre-filled question
