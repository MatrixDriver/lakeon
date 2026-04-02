# Notebook Kernel 启动进度展示

## 问题

Ray Notebook 启动时，用户只看到 "Starting kernel..." 状态，无法知道系统在做什么。
Kernel 启动涉及多个阶段（创建 Pod、等待调度、Ray Head 启动、Worker 加入、连接 Kernel），
整个过程可能 30 秒以上，用户体验差。

## 方案

通过 WebSocket 在 init 线程中发送 `progress` 类型消息，前端实时显示当前阶段。

### 消息格式

```json
{"type": "progress", "text": "正在创建 Pod..."}
```

### 阶段定义

**Python Notebook（非 Ray）：**

| 阶段 | text |
|------|------|
| 等待 Pod 就绪 | 正在启动 Kernel... |
| Pod Running，开始 exec | Pod 就绪，正在连接 Kernel... |

**Ray Notebook：**

| 阶段 | text |
|------|------|
| Session STARTING，Pod 未就绪 | 正在启动 Ray Head... |
| Head Pod Running | Ray Head 就绪，等待 Worker 加入... |
| 检测到 worker Running | Worker 加入中 (1/2)... |
| 全部 worker Running 或超时 | 正在连接 Kernel... |

### Worker 等待策略

**仅报告不阻塞**：在 head pod 就绪后，轮询 worker 状态并发送进度消息，
但不阻塞 exec 连接。一旦 head pod 就绪即可连接 Kernel，worker 在后台陆续加入。
`ray.init()` 会自动发现后续加入的 worker。

轮询 worker 状态最多额外等 10 秒，期间每秒检查一次。

## 改动范围

### 后端：`NotebookWebSocketHandler.java`

init 线程改造：

1. 轮询 session 状态时，根据 session 的 `workerCount` 判断是否 Ray
2. 在每个阶段转换时通过 WebSocket 发送 `progress` 消息
3. 对 Ray 集群，在 head pod 就绪后，额外轮询 worker pod 状态（通过 label `lakeon.io/session-id`）
4. 报告 worker 加入进度后，不管 worker 是否全部就绪，都继续连接 Kernel

### 前端：`DatalakeNotebook.vue`

1. 新增 `progressText` ref
2. `handleMessage` 处理 `type === 'progress'`，更新 `progressText`
3. `statusLabel` 在 `starting` 状态时显示 `progressText`（有值时）
4. 收到 `ready` 时清空 `progressText`

### 不改动的文件

- `NotebookCell.vue` — cell 运行提示不变
- `NotebookService.java` — Pod 创建逻辑不变
- `repl_server.py` — 执行层不变
- `notebook.ts` — WebSocket 客户端已自动转发所有 JSON 消息
