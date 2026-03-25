# DBay 数据湖 — AI 脚本助手 + 多数据集输入设计

> 状态：待实现
> 日期：2026-03-25
> 依赖：`docs/superpowers/specs/2026-03-25-datalake-job-creation-design.md`（创建作业页）

---

## 背景

数据湖创建作业页（已实现）包含 CodeMirror 内联编辑器，用户手写 Python 脚本。现需：
1. 加入 AI 辅助生成能力，与现有 AI SQL 助手（`AiSqlService` + `SqlEditor.vue`）采用相同架构模式
2. 支持多数据集输入——当前仅支持单个 `input_dataset_id`，但数据处理常需多表关联

---

## 设计决策

| 决策项 | 选择 | 原因 |
|--------|------|------|
| AI 上下文 | 数据集元信息 + 列 schema（列名 + 类型） | 足够 AI 生成精确列引用代码，无需传预览数据 |
| Schema 来源 | 导出时预存到 `DatasetEntity.schema_json` | 避免运行时读 Parquet，无需加 Java Parquet 依赖 |
| UI 位置 | 编辑器下方内联面板 | 代码编辑器已占右侧内容区，无空间开侧边栏 |
| 生成模式 | 全脚本替换 | 与 SQL 助手一致，适合初始脚本生成场景 |
| LLM 接入 | 复用 SiliconFlow API + `LakeonProperties.AiConfig` | 已有配置和密钥管理 |
| 数据集依赖 | 不要求预选，AI 自动获取所有数据集 schema | 与 SQL 助手一致，用户只描述需求，AI 决定用哪些数据集 |
| 数据集输入 | 多选（数组） | 数据处理常需多表关联，不应人为限制为单输入 |
| 环境变量命名 | `DATASET_PATH_{name}` | 每个数据集独立变量，用户在环境变量节可直接看到 |

---

## 多数据集输入

### 后端变更

**`DatalakeJobRequest`**：`input_dataset_id`（String）→ `input_dataset_ids`（`List<String>`）

**`PythonJobRunner`**：遍历 `input_dataset_ids`，为每个数据集注入 `DATASET_PATH_{datasetName}` 环境变量（name 取 `DatasetEntity.name`，空格替换为下划线，转小写）。保持向后兼容：若只选一个数据集，同时注入 `DATASET_PATH`（无后缀）。

**Flyway 迁移**：无需改表——`datalake_jobs.spec` 是 TEXT/JSON 字段，已存储完整请求。`datasets` 表不变。

### 前端变更

**`DatalakeJobNewDataset.vue`**：单选 `<select>` 改为**多选列表**（勾选框 + 列表），选中的数据集显示为 chips/tags。

**`DatalakeJobNewEnvVars.vue`**：根据选中的数据集数量动态显示绿色行：
- 选 1 个 `orders`：`🟢 DATASET_PATH = obs://...`
- 选 2 个 `orders` + `users`：`🟢 DATASET_PATH_orders = obs://...` + `🟢 DATASET_PATH_users = obs://...`
- 始终显示 `🟢 OUTPUT_PATH`

**`DatalakeJobNew.vue`**：`form.inputDatasetId`（string）→ `form.inputDatasetIds`（string[]）

**`datalake.ts`**：`input_dataset_id?: string` → `input_dataset_ids?: string[]`

---

## AI 脚本助手 — 后端

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
  "prompt": "关联 orders 和 users，统计每个用户的订单总额",
  "model": "Qwen/Qwen3.5-4B"
}
```

- `prompt`：必填，用户自然语言描述
- `model`：可选，默认 `Qwen/Qwen3.5-4B`

**上下文自动获取**：后端通过 `req.getAttribute("tenant")` 获取 tenantId，自动查询该租户所有 READY 状态数据集及其 `schema_json`，全部注入 LLM 上下文（与 SQL 助手自动获取所有表 schema 一致）。

**响应体**：
```json
{
  "script": "import os\nimport pandas as pd\n...",
  "model": "Qwen/Qwen3.5-4B",
  "input_tokens": 280,
  "output_tokens": 150,
  "used_dataset_ids": ["ds_abc123", "ds_def456"]
}
```

- `used_dataset_ids`：AI 生成脚本中引用的数据集 ID 列表。后端通过匹配生成代码中的 `DATASET_PATH_{name}` 引用与数据集名称来确定。前端收到后自动勾选这些数据集。

错误时返回 `{ "error": "..." }`。

### 3. 新增 `AiScriptService`

复用 `AiSqlService` 的 HTTP 调用模式（Java HttpClient → SiliconFlow `/chat/completions`）。

**System Prompt**：
```
You are a Python data processing expert. Generate a Python script based on the user's request.

Rules:
- Output ONLY the Python script, no explanations, no markdown code fences
- Read input datasets from os.environ["DATASET_PATH_{name}"] (Parquet format). If only one dataset, also available as os.environ["DATASET_PATH"]
- Write output data to the path in os.environ["OUTPUT_PATH"] (Parquet format)
- Use pandas for data processing
- Use the exact column names from the provided dataset schemas
- Always include: import os, import pandas as pd
- Use lowercase for variable names
```

**User Message 格式**（多数据集）：
```
Datasets:

1. orders (12000 rows, 2.1 MB)
   Env var: DATASET_PATH_orders
   Schema:
     - order_id: int64
     - user_id: int64
     - amount: float64

2. users (500 rows, 45 KB)
   Env var: DATASET_PATH_users
   Schema:
     - user_id: int64
     - name: string
     - city: string

Output: OUTPUT_PATH

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

**无需预选数据集**：AI 面板独立工作，不依赖数据集节的选择。后端自动获取所有数据集 schema。

**生成结果处理**：
- 返回的 `script` 替换 CodeMirror 编辑器全部内容，emit `update:script` 通知父组件
- 返回的 `used_dataset_ids` emit `update:usedDatasetIds` 通知父组件
- **`DatalakeJobNew.vue` 监听该事件**：自动将 `form.inputDatasetIds` 设置为 AI 返回的 `used_dataset_ids`，实现数据集自动勾选

### 新增 API 函数

在 `src/api/datalake.ts` 中新增：
```typescript
export interface AiScriptResult {
  script?: string
  error?: string
  model?: string
  input_tokens?: number
  output_tokens?: number
  used_dataset_ids?: string[]
}

export function generateDatalakeScript(prompt: string, model: string) {
  return client.post<AiScriptResult>('/datalake/ai-script/generate', { prompt, model })
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
- 无数据集时仍可使用 AI（但生成质量较低，无 schema 上下文）
- 自定义 system prompt
