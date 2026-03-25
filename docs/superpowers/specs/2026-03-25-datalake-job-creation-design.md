# DBay 数据湖 — 创建作业页设计

> 状态：待实现
> 日期：2026-03-25
> 参考：`docs/superpowers/specs/2026-03-20-datalake-mvp-design.md`

---

## 背景与问题

当前数据湖创建作业采用**弹窗（Modal）**模式，存在以下问题：

1. **空间不足**：代码来源、OBS 路径、数据集绑定、环境变量无处放
2. **缺失关键字段**：无法提交 Python 代码，入口命令 `python main.py` 但 `main.py` 不存在
3. **扩展性差**：未来增加 GPU 配置、调度、输出数据集注册等字段无法容纳

竞品（AWS Glue Studio、Databricks Lakeflow Jobs）均使用**全页表单**，作业创建是主流程操作，不应挤在弹窗里。

---

## 设计决策

| 决策项 | 选择 | 原因 |
|--------|------|------|
| 页面模式 | 全页（替换弹窗） | 主流程操作，需要足够空间容纳代码编辑器和多个配置节 |
| 布局结构 | 单页 + 左栏配置节导航 | 技术用户不需要被向导牵着走，任意跳转配置节效率更高 |
| 代码来源 | 内联编辑器（MVP）+ OBS 路径（Phase 2） | 内联适合快速脚本，是 AI 生成代码的天然接入点 |
| 数据输入 | 选择 DBay 数据集 → 自动注入 DATASET_PATH | 减少手填 OBS 路径的摩擦，与数据集模块形成闭环 |
| 主要用户 | 技术用户（工程师/数据科学家） | 熟悉 Python，需要代码编辑器而非无代码向导 |

---

## 页面结构

**路由**：`/datalake/jobs/new`（全页，替换原弹窗）

```
┌─────────────────────────────────────────────────────┐
│ DBay Navbar (数据库 | 知识库 | 数据湖)               │
├──────────┬──────────────────────────────────────────┤
│ 左栏     │ 顶部面包屑: 数据湖 / 新建作业             │
│ 应用侧栏 ├──────────────────────────────────────────┤
│          │ ┌──────────┬────────────────────────────┐ │
│ 作业列表 │ │ 配置节   │ 配置内容区（右侧）          │ │
│ 新建作业 │ │ 导航     │                            │ │
│ ──────   │ │ ① 基本   │ [当前显示的配置节内容]      │ │
│ 数据集   │ │ ② 代码 ← │                            │ │
│ 导出     │ │ ③ 数据集 │                            │ │
│          │ │ ④ 资源   │                            │ │
│          │ │ ⑤ 环境变量│                           │ │
│          │ │ ── 高级  │                            │ │
│          │ │ ⑥ 超时   │                            │ │
│          │ └──────────┴────────────────────────────┘ │
├──────────┴──────────────────────────────────────────┤
│ Submit Bar: Python · 内联 · user_behavior_march · [提交作业] │
└─────────────────────────────────────────────────────┘
```

---

## 各配置节详细设计

### ① 基本信息

| 字段 | 类型 | 说明 |
|------|------|------|
| 作业名称 | text input | 必填 |
| 类型 | pill 选择器 | 🐍 Python / ⚡ Ray / 🧠 微调，切换后左栏节目单动态变化 |

完成后折叠为**摘要卡**（名称 + 类型 pills）常驻页面顶部，附「编辑」按钮。

### ② 代码

**Tab 切换**（MVP 先做内联，Phase 2 加 OBS）：

**内联编辑器 Tab**
- Monaco Editor 嵌入，语法高亮（Python）
- 文件名默认 `main.py`，可改
- 工具栏：格式化 / 全屏
- 底部预留 **AI 辅助入口**（Phase 2）：「✨ 描述你想做什么，AI 帮你生成初始脚本」
- 说明文案：通过 `DATASET_PATH` 和 `OUTPUT_PATH` 环境变量读写数据

**OBS 路径 Tab**（Phase 2）
- OBS 路径输入框（带「浏览」按钮）
- 入口命令输入框（`python main.py --arg val`）
- 说明：容器启动时自动 `obs cp <path> /app/` 后执行入口命令

### ③ 数据集

| 子区域 | 说明 |
|--------|------|
| 输入数据集 | 下拉选择 DBay 数据集（仅 READY 状态），显示行数 + 大小。选中后自动将 OBS 路径注入 `DATASET_PATH` 环境变量（绿色，不可手动删除） |
| 输出 OBS 路径 | 文本输入，留空则自动生成 `obs://lakeon-storage/{tenant_id}/jobs/{job_id}/output/`，注入 `OUTPUT_PATH` |
| 输出数据集（Phase 2） | 可选「创建新数据集」，作业完成后自动注册 DatasetEntity |

### ④ 资源

**Python 类型**：
- CPU（默认 1）、内存（默认 2Gi）：卡片 + 滑块或下拉

**Ray 类型**（左栏拆分为「Head 配置」「Worker 配置」两节）：
- Head：CPU / 内存
- Worker：副本数、CPU / 内存（每副本）

**微调类型**（左栏节替换为「模型 & 超参数」）：
- 基础模型选择（Qwen2.5-7B 等）
- GPU 类型 + 数量
- 超参数（epochs、batch_size、learning_rate、lora_rank）

### ⑤ 环境变量

KV 表格：
- `DATASET_PATH`（绿色，自动注入，不可删）
- `OUTPUT_PATH`（绿色，自动注入，不可删）
- 用户自定义变量（白色，可增删）
- 底部「＋ 添加环境变量」按钮

### ⑥ 超时 & 重试（高级）

- 超时秒数（默认 3600）
- 失败重试次数（默认 0，范围 0–3）

---

## Submit Bar（底部常驻）

```
Python · 内联脚本 · 输入: user_behavior_march · CPU 1 / 内存 2Gi    [← 上一节]  [提交作业 →]
```

- 实时更新配置摘要
- 「提交作业」调用 `POST /api/v1/datalake/jobs`，成功后跳转到作业详情页

---

## 类型切换时的节目单变化

| 类型 | 左栏配置节 |
|------|-----------|
| Python | 基本信息 / 代码 / 数据集 / 资源 / 环境变量 / 超时 |
| Ray | 基本信息 / 代码 / 数据集 / Head 配置 / Worker 配置 / 环境变量 / 超时 |
| 微调 | 基本信息 / 模型 & 超参数 / 数据集 / GPU 资源 / 环境变量 / 超时 |

---

## 后端影响

当前 `DatalakeJobRequest` 已有所需字段。需新增：

| 字段 | 说明 |
|------|------|
| `inline_script` | string，内联脚本内容（MVP）|
| `input_dataset_id` | UUID，选中的 DBay 数据集 ID（已有，`DatalakeService` 已处理注入逻辑）|
| `output_path` | string，OBS 输出路径前缀（可选，留空自动生成）|

后端在创建 K8s Job 时：
1. 将 `inline_script` 写入 ConfigMap，挂载到容器 `/app/main.py`
2. `DATASET_PATH` 从 `input_dataset_id` 解析注入
3. `OUTPUT_PATH` 使用 `output_path` 或自动生成路径

---

## 前端影响

**路由变更**：
- 删除 `DatalakeJobs.vue` 中的提交弹窗
- 新增路由 `/datalake/jobs/new` → `DatalakeJobNew.vue`（新建组件）
- 「新建作业」按钮改为 `router.push('/datalake/jobs/new')`

**新增组件**：
- `DatalakeJobNew.vue`：主页面，管理左栏节状态、整体表单数据
- `DatalakeJobNewBasic.vue`：基本信息节
- `DatalakeJobNewCode.vue`：代码节（含 Monaco Editor）
- `DatalakeJobNewDataset.vue`：数据集节
- `DatalakeJobNewResources.vue`：资源节（按类型渲染）
- `DatalakeJobNewEnvVars.vue`：环境变量节

**依赖**：
- Monaco Editor（`@monaco-editor/vue`）或轻量替代（CodeMirror 6）

---

## 非目标（本次不做）

- OBS 路径模式（Phase 2）
- AI 辅助生成脚本（Phase 2）
- 输出数据集自动注册（Phase 2）
- 定时调度（Cron）配置
- Ray / 微调类型的完整表单（可以用占位页，等 CCI 基础设施就绪）
