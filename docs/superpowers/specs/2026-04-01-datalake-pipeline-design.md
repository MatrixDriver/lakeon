# 数据生产线（Pipeline）设计文档

> 日期：2026-04-01
> 状态：Draft
> 模块：数据湖 / Pipeline

## 1. 概述

### 1.1 背景

DBay 数据湖已具备 Python Job / Ray Job / 微调三种独立作业能力。用户需要将多个处理步骤串联为可编排的工作流（Pipeline），构建"模型数据生产线"——从原始数据到高质量训练数据集的完整加工链路。

### 1.2 目标

- 可视化 DAG 编辑器 + YAML 配置双入口，定义 Pipeline
- 可复用的算法组件库（平台内置 + 租户内共享）
- 独立的 Pipeline Orchestrator 服务，编排 Ray 集群执行
- 支持 fan-out（1→N 裂变）、条件分支、人工审核暂停/恢复
- 全链路版本管理（Pipeline + 组件 + 数据集）
- 多模态数据集存储引入 Lance 格式
- Phase 1 预置文本和视频两种 Pipeline 模板

### 1.3 验收用例

**视频数据清洗流水线：**

```
原始视频 (10min)
  → 规整适配（分辨率/时长/大小标准化）
  → 视频切片（PySceneDetect 镜头切分 → 42 clips）  [fan-out]
  → 规则清洗（时长>3s、分辨率>480P、长宽比<2、帧率>20、裁剪面积>5）
    → passed (4~5 clips)                            [条件分支]
    → needs_crop → 裁剪处理 → passed/dropped
    → dropped
  → 合并通过的 clips                                 [fan-in]
  → 模型清洗（VQA/水印/字幕/光流）                    [Phase 1 mock]
  → 质检（可配置：模型自动 或 人工暂停审核）            [HUMAN_REVIEW]
  → 内容标注（VICLIP/Caption/运镜）                   [Phase 1 mock]
  → 发布（Lance 数据集，3 clips，留存率 12.1%）
```

## 2. 架构

### 2.1 整体架构

```
lakeon-api (Java)          Pipeline Orchestrator (Python)          Ray Cluster
  │                              │                                    │
  │  CRUD + 触发                  │  DAG 状态机 + 编排                  │  纯计算
  │  Pipeline/组件/数据集 API      │  fan-out/in、条件分支               │  函数级组件执行
  │  ──── 触发运行 ────→           │  暂停/恢复                         │
  │                              │  ──── 提交 Ray task ────→          │
  │                              │  ←── 状态/结果 ────                 │
  │                              │                                    │
  └───── RDS (DAG状态/版本) ──────┘                                    │
                                 └───── OBS (checkpoint/数据集) ───────┘
```

**职责划分：**

| 服务 | 职责 |
|------|------|
| **lakeon-api** | Pipeline / 组件 / 数据集的 CRUD API，触发运行、恢复、取消 |
| **Pipeline Orchestrator** | DAG 解析、状态机、步骤调度、fan-out/in、条件路由、暂停恢复、Ray 交互 |
| **Ray Cluster** | 函数级组件的实际计算执行 |

### 2.2 数据传递策略

**持久 Ray 集群 + OBS 兜底：**

- Pipeline 启动时创建持久 Ray 集群，运行期间保持存活
- 步骤间通过 Ray object store 内存传递（零拷贝），Orchestrator 只传 ObjectRef
- checkpoint 节点异步写 OBS（不阻塞主流程），用于数据预览和故障恢复
- HUMAN_REVIEW 暂停时全量写 OBS，释放 Ray 集群；恢复时启新集群从 OBS 读取
- Pipeline 全部完成后释放 Ray 集群

### 2.3 Ray Job 打包策略

不再每步一个独立 Ray Job，而是向持久集群逐步提交 remote task：

- 连续无中断步骤通过 Ray object store 串联，数据不落盘
- 遇到 fan-out 节点：Orchestrator 拿到结果后动态创建 step_runs，再并行提交
- 遇到 fan-in 节点：等待所有分支完成后合并
- 遇到 HUMAN_REVIEW 节点：写 OBS checkpoint，释放 Ray 集群，暂停等待
- 恢复后启新集群，从 OBS 加载继续执行

## 3. 数据模型

### 3.1 数据集版本化

扩展现有 `datasets` 表，新增 `dataset_versions` 表：

```sql
-- 改造现有 datasets 表
ALTER TABLE datasets ADD COLUMN source_type VARCHAR(20) DEFAULT 'DB_EXPORT';
-- source_type: DB_EXPORT | UPLOAD | PIPELINE_OUTPUT
ALTER TABLE datasets ADD COLUMN latest_version INTEGER DEFAULT 1;

-- 新表
CREATE TABLE dataset_versions (
    id VARCHAR(64) PRIMARY KEY,             -- dsv_xxx
    dataset_id VARCHAR(64) NOT NULL REFERENCES datasets(id),
    version INTEGER NOT NULL,
    format VARCHAR(16) NOT NULL DEFAULT 'PARQUET',  -- PARQUET | LANCE
    obs_path VARCHAR(512),
    row_count BIGINT,
    file_size BIGINT,
    schema_json TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATING',  -- CREATING | READY | FAILED
    source_pipeline_run_id VARCHAR(64),     -- 可追溯来自哪次 pipeline 运行
    source_job_id VARCHAR(64),              -- 兼容现有 job 导出
    created_at TIMESTAMP NOT NULL,
    UNIQUE(dataset_id, version)
);
CREATE INDEX idx_dsv_dataset ON dataset_versions(dataset_id);
```

- 现有 API 和前端默认读 `latest_version`，不影响现有功能
- Pipeline 场景可指定具体版本作为输入
- `format` 区分存储格式：纯结构化用 PARQUET，多模态（视频/图片/音频）用 LANCE

### 3.2 组件库

```sql
CREATE TABLE pipeline_components (
    id VARCHAR(64) PRIMARY KEY,              -- comp_xxx
    tenant_id VARCHAR(64),                   -- null = 平台内置
    name VARCHAR(128) NOT NULL,              -- video_scene_split
    display_name VARCHAR(128) NOT NULL,      -- 视频镜头切分
    category VARCHAR(20) NOT NULL,           -- DATA_PREP | EXTRACT | CLEAN | FILTER | QC | LABEL | PUBLISH
    data_type VARCHAR(20) NOT NULL,          -- TEXT | VIDEO | IMAGE | AUDIO | DOCUMENT | UNIVERSAL
    description TEXT,
    latest_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_comp_tenant ON pipeline_components(tenant_id);
CREATE INDEX idx_comp_category ON pipeline_components(category);

CREATE TABLE pipeline_component_versions (
    id VARCHAR(64) PRIMARY KEY,              -- compv_xxx
    component_id VARCHAR(64) NOT NULL REFERENCES pipeline_components(id),
    version INTEGER NOT NULL,
    entrypoint VARCHAR(256) NOT NULL,        -- lakeon.components.video.scene_split
    params_schema TEXT,                      -- JSON Schema
    input_schema TEXT,                       -- 输入数据格式描述 JSON
    output_schema TEXT,                      -- 输出数据格式描述 JSON
    output_branches TEXT,                    -- JSON array: ["passed","needs_crop","dropped"]
    requires_gpu BOOLEAN DEFAULT FALSE,
    requires_model VARCHAR(128),             -- 模型标识（如 pyscenedetect）
    execution_mode VARCHAR(20) DEFAULT 'FUNCTION',  -- FUNCTION | HUMAN_REVIEW
    status VARCHAR(16) DEFAULT 'DRAFT',      -- DRAFT | PUBLISHED | DEPRECATED
    changelog TEXT,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(component_id, version)
);
CREATE INDEX idx_compv_component ON pipeline_component_versions(component_id);
```

- `params_schema`：JSON Schema 格式，前端 DAG 编辑器据此自动渲染参数配置表单
- `input_schema` / `output_schema`：DAG 编辑器校验连线合法性
- `output_branches`：条件分支组件声明多个输出端口
- `tenant_id = null` 为平台内置，所有租户可见；非 null 为租户自定义，仅本租户可见

### 3.3 Pipeline 定义

```sql
CREATE TABLE pipelines (
    id VARCHAR(64) PRIMARY KEY,              -- pipe_xxx
    tenant_id VARCHAR(64) NOT NULL,          -- 平台预置模板用 "system"
    name VARCHAR(256) NOT NULL,
    description TEXT,
    data_type VARCHAR(20),                   -- TEXT | VIDEO | IMAGE | AUDIO | DOCUMENT
    is_template BOOLEAN DEFAULT FALSE,
    source_template_id VARCHAR(64),          -- 基于哪个模板创建
    latest_version INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_pipe_tenant ON pipelines(tenant_id);

CREATE TABLE pipeline_versions (
    id VARCHAR(64) PRIMARY KEY,              -- pipev_xxx
    pipeline_id VARCHAR(64) NOT NULL REFERENCES pipelines(id),
    version INTEGER NOT NULL,
    dag_yaml TEXT NOT NULL,                  -- 完整 DAG 定义 YAML
    status VARCHAR(16) DEFAULT 'DRAFT',      -- DRAFT | PUBLISHED | DEPRECATED
    changelog TEXT,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(pipeline_id, version)
);
CREATE INDEX idx_pipev_pipeline ON pipeline_versions(pipeline_id);
```

### 3.4 Pipeline 运行记录

```sql
CREATE TABLE pipeline_runs (
    id VARCHAR(64) PRIMARY KEY,              -- run_xxx
    pipeline_id VARCHAR(64) NOT NULL REFERENCES pipelines(id),
    pipeline_version INTEGER NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    input_dataset_id VARCHAR(64),
    input_dataset_version INTEGER,
    output_dataset_version_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- PENDING | RUNNING | PAUSED | SUCCEEDED | FAILED | CANCELLED
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_run_pipeline ON pipeline_runs(pipeline_id);
CREATE INDEX idx_run_tenant ON pipeline_runs(tenant_id);
CREATE INDEX idx_run_status ON pipeline_runs(status);

CREATE TABLE pipeline_step_runs (
    id VARCHAR(64) PRIMARY KEY,              -- sr_xxx
    run_id VARCHAR(64) NOT NULL REFERENCES pipeline_runs(id),
    step_id VARCHAR(128) NOT NULL,           -- DAG 中的步骤 id
    component_id VARCHAR(64),
    component_version INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- PENDING | RUNNING | PAUSED | SUCCEEDED | FAILED | SKIPPED
    input_ref TEXT,                          -- JSON: 实际输入数据引用
    output_ref TEXT,                         -- JSON: 实际输出数据引用
    checkpoint_path VARCHAR(512),            -- OBS checkpoint 路径
    metrics TEXT,                            -- JSON: 运行指标
    error TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_sr_run ON pipeline_step_runs(run_id);
CREATE INDEX idx_sr_status ON pipeline_step_runs(status);
```

- `pipeline_runs` 记录运行时锁定的 pipeline 版本 + 数据集版本
- `pipeline_step_runs` 是前端实时 DAG 视图的数据来源
- `metrics` 示例：`{"input_count":42,"output_count":5,"drop_count":37,"retention":"11.9%"}`

## 4. 组件库设计

### 4.1 组件接口规范

每个组件是一个 Python 函数，使用 `@Component` 装饰器声明元信息：

```python
from lakeon.pipeline import Component, ComponentContext

@Component(
    name="video_scene_split",
    display_name="视频镜头切分",
    category="EXTRACT",
    data_type="VIDEO",
    params_schema={
        "threshold": {"type": "number", "default": 27, "description": "切分灵敏度"},
        "min_scene_length": {"type": "number", "default": 1.0, "description": "最短镜头时长(秒)"}
    },
    input_schema={"type": "video", "format": ["mp4", "avi"]},
    output_schema={"type": "video_clips", "format": "mp4"},
)
def video_scene_split(ctx: ComponentContext) -> dict:
    video_path = ctx.input["video"]
    threshold = ctx.params.get("threshold", 27)
    clips = detect_scenes(video_path, threshold)
    ctx.report({"input_count": 1, "output_count": len(clips)})
    return ctx.fan_out(clips)
```

### 4.2 ComponentContext API

| 方法 | 用途 |
|------|------|
| `ctx.input` | 上游输出数据（Ray ObjectRef 或 OBS 路径） |
| `ctx.params` | 用户配置参数 |
| `ctx.obs.read/write` | OBS 读写 |
| `ctx.checkpoint()` | 持久化中间结果到 OBS（异步） |
| `ctx.report(metrics)` | 上报统计指标 |
| `ctx.fan_out(items)` | 输出裂变为 N 个数据单元 |
| `ctx.classify(item, label)` | 条件分支——给数据单元打标签 |
| `ctx.log(msg)` | 日志，流式回传前端 |

### 4.3 条件分支组件示例

```python
@Component(
    name="rule_filter",
    display_name="规则清洗",
    category="FILTER",
    data_type="VIDEO",
    params_schema={
        "min_duration": {"type": "number", "default": 3},
        "min_resolution": {"type": "number", "default": 480},
        "max_aspect_ratio": {"type": "number", "default": 2},
        "min_fps": {"type": "number", "default": 20},
        "min_crop_area": {"type": "number", "default": 5},
    },
    output_branches=["passed", "needs_crop", "dropped"],
)
def rule_filter(ctx: ComponentContext) -> dict:
    clip = ctx.input["clip"]
    meta = ffprobe_metadata(clip)

    if meta["duration"] < ctx.params["min_duration"]:
        return ctx.classify(clip, "dropped")
    if meta["resolution"] < ctx.params["min_resolution"]:
        return ctx.classify(clip, "dropped")
    if meta["aspect_ratio"] > ctx.params["max_aspect_ratio"]:
        return ctx.classify(clip, "needs_crop")
    # ... 更多规则

    return ctx.classify(clip, "passed")
```

### 4.4 组件来源

- **平台内置**：`tenant_id = null`，预装在 Orchestrator 镜像中
- **用户自带模型**：用户上传模型权重到 OBS，注册为组件时指定 `requires_model`
- **租户自定义**：用户在 Notebook 开发调试 → Console 注册（上传 .py + 填元信息）→ 存入 OBS `obs://{tenant}/components/{comp_id}/{version}/`

### 4.5 Phase 1 预置组件清单

| 组件 | 类型 | 真实/Mock | 依赖 |
|------|------|-----------|------|
| `video_normalize` | 规整适配 | 真实 | ffmpeg/ffprobe |
| `video_scene_split` | 视频切片 | 真实 | PySceneDetect |
| `rule_filter` | 规则清洗 | 真实 | ffprobe |
| `video_crop` | 裁剪处理 | 真实 | ffmpeg |
| `model_filter_mock` | 模型清洗 | Mock | — |
| `quality_check` | 质检 | 真实 | 人工审核 UI |
| `video_labeling_mock` | 内容标注 | Mock | — |
| `dataset_publish` | 发布 | 真实 | Lance |
| `text_dedup` | 文本去重 | 真实 | MinHash |
| `text_clean` | 文本清洗 | 真实 | — |
| `text_tokenize` | 分词统计 | 真实 | jieba/tiktoken |
| `text_quality_score` | 文本质量评分 | 真实（规则） | — |

## 5. Pipeline Orchestrator 设计

### 5.1 服务结构

```
Pipeline Orchestrator (Python, 独立 Pod)
  ├── API Server (FastAPI)
  │     ├── POST /runs          — 接收 lakeon-api 触发
  │     ├── POST /runs/{id}/resume — 人工审核后恢复
  │     └── POST /runs/{id}/cancel — 取消运行
  │
  ├── DAG Engine
  │     ├── DAGParser: YAML → 内存 DAG 图
  │     ├── DAGScheduler: 拓扑排序，确定可执行步骤
  │     ├── FanOutHandler: 1→N 裂变，动态扩展 step_runs
  │     ├── BranchRouter: 条件分支路由
  │     ├── FanInHandler: 合并多个分支结果
  │     └── PauseManager: HUMAN_REVIEW 暂停/恢复
  │
  ├── Ray Client
  │     ├── 创建/管理持久 Ray 集群
  │     ├── 提交 remote task
  │     ├── 监听 task 状态
  │     └── 管理 ObjectRef 生命周期
  │
  ├── State Manager
  │     ├── 读写 RDS (pipeline_runs / pipeline_step_runs)
  │     └── 故障恢复：重启后从 RDS 恢复进行中的 run
  │
  └── Checkpoint Manager
        ├── 异步写 checkpoint 到 OBS
        └── 恢复时从 OBS 加载
```

### 5.2 与 lakeon-api 的交互

Orchestrator 不直接暴露给前端，lakeon-api 是唯一网关：

- **lakeon-api → Orchestrator**：触发运行、恢复、取消（内部 HTTP）
- **状态同步**：共享 RDS，Orchestrator 直接写状态，lakeon-api 直接读
- **前端 → lakeon-api**：读取 `pipeline_runs` / `pipeline_step_runs` 展示实时 DAG

### 5.3 DAG 状态机

步骤状态流转：

```
PENDING → RUNNING → SUCCEEDED
                  → FAILED → (可重试，默认最多 2 次) → RUNNING
                  → SKIPPED (条件分支未命中)
         RUNNING → PAUSED → (人工确认) → RUNNING
```

Pipeline 整体状态聚合规则：
- **RUNNING**：至少一个步骤 RUNNING
- **PAUSED**：有步骤 PAUSED，无步骤 RUNNING
- **SUCCEEDED**：所有步骤 SUCCEEDED 或 SKIPPED
- **FAILED**：有步骤 FAILED，且无可重试

### 5.4 容错与恢复

| 场景 | 处理 |
|------|------|
| 步骤失败 | 根据组件配置重试（默认最多 2 次），超过则 FAILED |
| Ray 集群异常 | Orchestrator 检测后从最近 checkpoint 恢复 |
| Orchestrator 重启 | 从 RDS 读取 RUNNING/PAUSED 的 runs，恢复调度 |
| Ray 集群不可用 | 等待重试，超时后 pipeline FAILED |

## 6. 前端交互

### 6.1 页面结构

```
/datalake/pipelines                    → Pipeline 列表页
/datalake/pipelines/new                → 创建（DAG 编辑器）
/datalake/pipelines/:id                → 详情（版本列表 + 运行历史）
/datalake/pipelines/:id/edit           → 编辑（DAG 编辑器）
/datalake/pipelines/:id/runs/:runId    → 运行详情（实时 DAG 视图）
/datalake/components                   → 组件库页面
/datalake/components/register          → 注册自定义组件
```

### 6.2 DAG 编辑器

三栏布局：

- **左栏 · 组件面板**：按 category 分组（彩色标识），支持搜索过滤，拖拽到画布添加节点，区分平台内置/租户自定义
- **中央 · DAG 画布**：基于 Vue Flow，节点间拖拽连线，自动校验输入输出类型兼容性，fan-out/fan-in/条件分支可视化，缩放/平移/自动布局
- **右栏 · 属性面板**：点击节点显示参数配置（根据 `params_schema` 自动渲染表单），输出分支连线管理，checkpoint 开关

**工具栏**：撤销/重做、YAML 视图切换（双向同步）、从模板创建、保存草稿/发布版本

### 6.3 运行监控

运行详情页复用 DAG 画布（只读模式）：

| 功能 | 说明 |
|------|------|
| 节点实时变色 | 灰(PENDING) → 蓝(RUNNING) → 绿(SUCCEEDED) / 红(FAILED) / 黄(PAUSED) |
| 节点指标气泡 | 显示 metrics（如 "42→5 clips, 留存 11.9%"） |
| 点击节点 | 侧面板：日志流、运行时长、输入输出引用 |
| Checkpoint 预览 | checkpoint 节点有"预览"按钮，展示中间数据 |
| 人工审核面板 | PAUSED 节点展开审核界面：缩略图网格，勾选通过/淘汰，确认后恢复 |
| 整体统计 | 顶部汇总：总耗时、各步骤耗时、留存率、数据量变化 |

### 6.4 前端技术选型

| 需求 | 方案 |
|------|------|
| DAG 画布 | Vue Flow（Vue 3 生态，与项目一致） |
| YAML 编辑器 | Monaco Editor（已在 DatalakeJobNew 中使用） |
| YAML ↔ DAG 同步 | 双向实时同步 |
| 视频预览 | HTML5 Video + ffmpeg 截帧缩略图 |

## 7. 预置模板

### 7.1 视频数据清洗流水线

```yaml
name: 视频数据清洗流水线
data_type: VIDEO
description: 从原始视频到清洗标注后的视频片段数据集

steps:
  - id: normalize
    component: video_normalize
    component_version: 1
    params: { target_resolution: "1080p", target_format: "mp4" }
    inputs: { video: "$input.dataset" }
    outputs: { video: normalized }

  - id: scene_split
    component: video_scene_split
    component_version: 1
    params: { threshold: 27, min_scene_length: 1.0 }
    inputs: { video: normalize.video }
    fan_out: true
    checkpoint: true
    outputs: { clips: split_clips }

  - id: rule_filter
    component: rule_filter
    component_version: 1
    depends_on: [scene_split]
    params: { min_duration: 3, min_resolution: 480, max_aspect_ratio: 2, min_fps: 20, min_crop_area: 5 }
    inputs: { clip: scene_split.clips }
    output_branches: [passed, needs_crop, dropped]
    outputs: { passed: passed_clip, needs_crop: crop_clip }

  - id: crop
    component: video_crop
    component_version: 1
    condition: "rule_filter.needs_crop"
    inputs: { clip: rule_filter.crop_clip }
    outputs: { clip: cropped_clip }

  - id: merge_clean
    type: merge
    inputs: [rule_filter.passed_clip, crop.clip]
    outputs: { clips: merged_clips }

  - id: model_filter
    component: model_filter_mock
    component_version: 1
    depends_on: [merge_clean]
    params: { checks: [vqa, watermark, subtitle, optical_flow] }
    inputs: { clips: merge_clean.clips }
    checkpoint: true
    outputs: { clips: cleaned_clips }

  - id: qc
    component: quality_check
    component_version: 1
    execution_mode: HUMAN_REVIEW
    depends_on: [model_filter]
    inputs: { clips: model_filter.clips }
    outputs: { clips: approved_clips }

  - id: labeling
    component: video_labeling_mock
    component_version: 1
    depends_on: [qc]
    params: { tasks: [viclip_tag, caption, camera_motion] }
    inputs: { clips: qc.clips }
    outputs: { clips: labeled_clips }

  - id: publish
    component: dataset_publish
    component_version: 1
    depends_on: [labeling]
    inputs: { clips: labeling.clips }
    output_dataset: { name: "清洗后视频数据集", format: LANCE }
```

### 7.2 文本数据清洗流水线

```yaml
name: 文本数据清洗流水线
data_type: TEXT
description: 从原始文本到高质量训练数据集

steps:
  - id: dedup
    component: text_dedup
    component_version: 1
    params: { method: "minhash", similarity_threshold: 0.85 }
    inputs: { text: "$input.dataset" }
    checkpoint: true
    outputs: { text: deduped }

  - id: clean
    component: text_clean
    component_version: 1
    depends_on: [dedup]
    params: { remove_html: true, normalize_whitespace: true, remove_urls: true, min_length: 50, max_length: 100000, language_filter: ["zh", "en"] }
    inputs: { text: dedup.text }
    outputs: { text: cleaned }

  - id: tokenize_stats
    component: text_tokenize
    component_version: 1
    depends_on: [clean]
    params: { tokenizer: "tiktoken", compute_stats: true }
    inputs: { text: clean.text }
    checkpoint: true
    outputs: { text: tokenized }

  - id: quality_score
    component: text_quality_score
    component_version: 1
    depends_on: [tokenize_stats]
    params: { scorer: "rule", min_score: 0.6 }
    inputs: { text: tokenize_stats.text }
    output_branches: [passed, low_quality]
    outputs: { passed: good_text }

  - id: qc
    component: quality_check
    component_version: 1
    execution_mode: HUMAN_REVIEW
    depends_on: [quality_score]
    inputs: { text: quality_score.good_text }
    outputs: { text: approved_text }

  - id: publish
    component: dataset_publish
    component_version: 1
    depends_on: [qc]
    inputs: { text: qc.text }
    output_dataset: { name: "清洗后文本数据集", format: PARQUET }
```

### 7.3 模板使用方式

| 操作 | 说明 |
|------|------|
| 从模板创建 | 复制模板的 dag_yaml，`source_template_id` 指向模板 |
| 修改模板 | 用户可增删步骤、修改参数、调整连线 |
| 空白创建 | 不选模板，从空画布开始 |
| 模板更新 | 平台更新模板不影响已创建的 pipeline（快照复制，非引用） |

## 8. Phase 分期

### Phase 1（当前）
- Pipeline 框架：数据模型、Orchestrator、DAG 编辑器、运行监控
- 组件库框架：接口规范、注册机制、平台内置 + 租户自定义
- 数据集版本化 + Lance 格式引入
- 预置模板：视频 + 文本
- 预置组件：12 个（8 真实 + 4 mock）
- 触发方式：仅手动
- 全链路版本管理

### Phase 2（后续）
- 模型类组件替换 mock：VQA、水印检测、VICLIP、Caption
- 用户自带模型注册
- 定时调度 + 事件触发
- 更多数据类型模板：图片理解、音频 TTS/ASR、文档
- 数据集 lineage（完整血缘追踪）
- 组件市场（跨租户共享）
