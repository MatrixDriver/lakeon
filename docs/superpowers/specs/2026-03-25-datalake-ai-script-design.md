# DBay 数据湖 — AI 脚本助手设计

> 状态：待实现
> 日期：2026-03-25
> 依赖：`docs/superpowers/specs/2026-03-25-datalake-job-creation-design.md`（创建作业页）

---

## 背景

数据湖创建作业页（已实现）包含 CodeMirror 内联编辑器，用户手写 Python 脚本。现需加入 AI 辅助生成能力，与现有 AI SQL 助手（`AiSqlService` + `SqlEditor.vue`）采用相同架构模式。

---

## 设计决策

| 决策项 | 选择 | 原因 |
|--------|------|------|
| AI 上下文 | 数据集元信息 + 列 schema（列名 + 类型） | 足够 AI 生成精确列引用代码，无需传预览数据 |
| Schema 来源 | 导出时预存到 `DatasetEntity.schema_json` | 避免运行时读 Parquet，无需加 Java Parquet 依赖 |
| UI 位置 | 编辑器下方内联面板 | 代码编辑器已占右侧内容区，无空间开侧边栏 |
| 生成模式 | 全脚本替换 | 与 SQL 助手一致，适合初始脚本生成场景 |
| LLM 接入 | 复用 SiliconFlow API + `LakeonProperties.AiConfig` | 已有配置和密钥管理 |
| 数据集依赖 | 必须先选数据集才能用 AI | 无 schema 上下文时生成质量低 |

---

## 后端

### 1. `DatasetEntity` 新增 `schema_json` 字段

```java
@Column(name = "schema_json", columnDefinition = "text")
private String schemaJson;  // JSON: [{"name":"col1","type":"int64"}, ...]
```

**Flyway 迁移**：创建 `V21__add_dataset_schema_json.sql`：
```sql
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS schema_json TEXT;
```

Schema 在 `DatasetService.triggerExport()` 中填充——此时源数据库 compute pod 已唤醒，JDBC 连接可用。仅对 `TABLE_SELECT` 类型数据集填充；`CUSTOM_SQL` 类型因涉及多表 JOIN，`schema_json` 保持为 null。详见下方「数据集 Schema 填充」节。

### 2. 新增 API：`POST /api/v1/datalake/ai-script/generate`

**Controller**：`DatalakeController`

**请求体**：
```json
{
  "prompt": "过滤 score > 0.8 的行，按 category 分组统计数量",
  "model": "Qwen/Qwen3.5-4B",
  "dataset_id": "ds_abc123"
}
```

- `prompt`：必填，用户自然语言描述
- `model`：可选，默认 `Qwen/Qwen3.5-4B`
- `dataset_id`：必填，用于查询 schema 上下文

**租户隔离**：通过 `req.getAttribute("tenant")` 获取 tenantId，使用 `DatasetRepository.findByIdAndTenantId(datasetId, tenantId)` 查询数据集，确保不能读取其他租户的 schema。

**响应体**：
```json
{
  "script": "import os\nimport pandas as pd\n...",
  "model": "Qwen/Qwen3.5-4B",
  "input_tokens": 280,
  "output_tokens": 150
}
```

错误时返回 `{ "error": "..." }`。

### 3. 新增 `AiScriptService`

复用 `AiSqlService` 的 HTTP 调用模式（Java HttpClient → SiliconFlow `/chat/completions`）。

**System Prompt**：
```
You are a Python data processing expert. Generate a Python script based on the user's request.

Rules:
- Output ONLY the Python script, no explanations, no markdown code fences
- Read input data from the path in os.environ["DATASET_PATH"] (Parquet format)
- Write output data to the path in os.environ["OUTPUT_PATH"] (Parquet format)
- Use pandas for data processing
- Use the exact column names from the provided dataset schema
- Always include: import os, import pandas as pd
- Use lowercase for variable names
```

**User Message 格式**：
```
Dataset: {name} ({rowCount} rows, {fileSize} bytes)
Schema:
  - col1: int64
  - col2: string
  - col3: float64

User request: {prompt}
```

**参数**：`temperature: 0.1`，`max_tokens: 2000`，`timeout: 60s`（脚本比 SQL 长，超时比 SQL 助手的 30s 更宽松）

**Markdown 防御性剥离**：与 `AiSqlService` 一致，即使 system prompt 要求不输出 markdown，仍然防御性剥离 `` ```python ... ``` `` 代码围栏。

**Schema 为 null 时的降级**：当 `schema_json` 为 null 时（CUSTOM_SQL 数据集或旧数据集），User Message 中 Schema 部分替换为 `Schema: (unavailable — use generic column references)`，仍然允许生成但提示 AI 使用通用列引用。

**模型列表**：复用 `AiSqlService.AVAILABLE_MODELS` 中的 4 个模型及其定价信息，前端显示与 SQL 助手一致。

---

## 前端

### `DatalakeJobNewCode.vue` 改造

将现有 `ai-hint` 提示条改为可展开的内联面板。

**收起状态**：与现在相同，一行提示 `✨ AI 辅助：描述你想做什么，AI 帮你生成初始脚本`，点击展开。

**展开状态**（编辑器下方面板）：
- **模型选择器**：下拉，与 SQL 助手相同的 4 个模型（Qwen3.5-4B 免费默认、DeepSeek-V3.2、Qwen3-Coder-480B、Qwen3-Coder-30B）
- **Prompt 输入框**：textarea，3 行，placeholder「例：过滤 score > 0.8 的行，按 category 分组统计」
- **生成按钮**：「生成脚本」/ 加载态「生成中...」，支持 Ctrl+Enter 快捷键
- **Token 用量**：生成后显示 `{input} + {output} tokens · ¥{cost}`
- **错误提示**：红色文字

**数据集依赖**：
- 面板从父组件接收 `inputDatasetId` prop
- **需同步修改 `DatalakeJobNew.vue`**：在 `<DatalakeJobNewCode>` 上新增 `:input-dataset-id="form.inputDatasetId"` 传递 prop
- 若未选数据集，显示提示「请先在「数据集」节选择输入数据集」，禁用生成按钮
- `dataset_id` 通过 API 请求传给后端

**生成结果处理**：
- 返回的 `script` 替换 CodeMirror 编辑器全部内容
- 同时 emit `update:script` 通知父组件

### 新增 API 函数

在 `src/api/datalake.ts` 中新增：
```typescript
export interface AiScriptResult {
  script?: string
  error?: string
  model?: string
  input_tokens?: number
  output_tokens?: number
}

export function generateDatalakeScript(prompt: string, model: string, datasetId: string) {
  return client.post<AiScriptResult>('/datalake/ai-script/generate', { prompt, model, dataset_id: datasetId })
}
```

---

## 数据集 Schema 填充

在 `DatasetService.triggerExport()` 中，源数据库 compute pod 唤醒后、提交导出 Job 前，查询源数据库获取列信息：

```sql
SELECT column_name, data_type FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = ?
ORDER BY ordinal_position
```

- **table_name**：从 `DatasetEntity.sourceTables` 获取（`TABLE_SELECT` 类型存储的是单个表名）
- **仅 `TABLE_SELECT` 类型**：`CUSTOM_SQL` 类型因涉及多表 JOIN 无法简单提取列信息，`schema_json` 保持为 null
- 将查询结果序列化为 `[{"name":"col1","type":"int64"}, ...]` 格式 JSON，存入 `schema_json`
- 对于非 SQL 导出型数据集（如未来的文件上传），`schema_json` 为 null

---

## 非目标

- 流式输出（非 MVP）
- 多轮对话 / 上下文记忆
- 代码补全（只做全脚本生成）
- 无数据集时的 AI 生成
- 自定义 system prompt
