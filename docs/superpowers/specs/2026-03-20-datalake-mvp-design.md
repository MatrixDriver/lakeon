# DBay 数据湖 MVP 设计

> 状态：进行中（Python Job CCI 端到端已验证，Ray/GPU/计量待完成）
> 日期：2026-03-20，最后更新：2026-03-25
> 参考：`docs/superpowers/specs/2026-03-20-datalake-v4-architecture.md`（V4 基础设施方案）

---

## 目标

在 DBay 现有"数据库"和"知识库"两个模块基础上，新增**数据湖**模块，支持用户提交 Serverless 计算任务（Python、Ray、模型微调），运行在 CCI Kata VM 强隔离环境中。

**非目标（MVP 不做）：**
- Spark 支持（Phase 2）
- Workspace 交互开发环境
- Job 调度 / Cron
- 数据集管理 UI
- 动态镜像构建（requirements.txt → Kaniko → SWR，Phase 2）
- OBS STS 临时凭证（Phase 2，MVP 用服务账号 AK/SK + per-tenant 路径强制）

---

## 一、整体架构

### 模块关系

```
Console (Vue 3)
├── 数据库    → lakeon-api /api/v1/databases/**    (现有)
├── 知识库    → lakeon-api /api/v1/knowledge/**    (现有)
└── 数据湖    → lakeon-api /api/v1/datalake/**     (新增)

lakeon-api (Spring Boot 单体扩展)
├── com.lakeon.database       (现有)
├── com.lakeon.knowledge      (现有)
└── com.lakeon.datalake       (新增)
```

### 执行层架构

```
lakeon-api
└── DatalakeService
    ├── PythonJobRunner  → K8s Job → VK → CCI 单 Pod (Kata VM)
    ├── RayJobRunner     → RayJob CRD → KubeRay Operator → VK → CCI (Head + Workers)
    └── FinetuneJobRunner → 注入内置模板参数 → RayJobRunner

CCE 控制面（现有集群新增组件）
├── KubeRay Operator          Watch RayJob CRD，管理 Ray 集群生命周期
├── Virtual Kubelet HA ×2     透明转发 Pod 到 CCI（leader election）
├── Spec Normalizer Webhook   预对齐 CCI 固定规格，强制 requests=limits
└── Image Build Pipeline      requirements.txt → Kaniko → Trivy → SWR

CCI 数据面（per-tenant namespace: datalake-{user_id}）
├── Python Job：单 Pod (Kata VM)，python:3.11-slim，跑完销毁
├── Ray Head Pod (Kata VM) + Ray Worker Pods × N (Kata VM)
└── 微调任务：同 Ray，Worker 使用 GPU 规格
```

### Job Framework 与 Knowledge Pipeline 的关系

两套系统**保持独立**，不合并：

| | KB Pipeline | 数据湖任务 |
|---|---|---|
| 执行层 | kbwrite pod（单容器） | Ray cluster / 单 Pod |
| 并发模型 | 串行队列（pageserver 单写约束） | 并行 |
| 用户可见 | 否（内部任务） | 是（用户主动提交） |
| 触发方式 | 文档上传自动触发 | 用户主动提交 |

共用点仅限：同一 RDS 实例存状态、同一 API Key 认证。

---

## 二、任务类型

### 三种类型的基础设施差异

| | Python 任务 | Ray 任务 | 微调任务 |
|---|---|---|---|
| 执行方式 | 单容器 | Ray 集群（Head + N Workers） | Ray Train 模板（GPU） |
| 镜像 | `python:3.11-slim` + requirements | Ray 完整镜像（~2GB+） | Ray + CUDA 镜像 |
| CCI Pod 数 | 1 | 1 Head + N Workers | 1 Head + N GPU Workers |
| 适用场景 | 轻量脚本、数据清洗、API 调用、数据飞轮处理 | 分布式并行、大规模数据处理 | LLM 微调（Qwen/LLaMA） |
| 用户写代码 | 是 | 是 | 否（填表单） |

### 为什么用 VK

KubeRay Operator 只认标准 Kubernetes API，不知道 CCI 的存在。VK 让 CCI 伪装成普通 K8s 节点，使 KubeRay 零修改直接运行，同时 Pod 实际跑在 CCI Kata VM 里获得强隔离。VK 不在数据路径上（Ray 计算流量直接 Pod-to-Pod），仅参与 Pod 生命周期操作（创建/删除/状态同步），DBay 规模下不是性能瓶颈。

---

## 三、API 设计

### Endpoints

```
POST   /api/v1/datalake/jobs           提交任务
GET    /api/v1/datalake/jobs           列出任务（分页，按状态过滤）
GET    /api/v1/datalake/jobs/{id}      任务详情
DELETE /api/v1/datalake/jobs/{id}      取消任务
GET    /api/v1/datalake/jobs/{id}/logs 实时日志（SSE）
```

认证：复用现有 DBay API Key（`lk_` + 64 hex），auth 中间件解析出 `tenant_id`（与现有模块一致）。

### 请求体

**Python 任务**
```json
{
  "name": "my-etl",
  "type": "PYTHON",
  "entrypoint": "python main.py",
  "requirements": "pandas==2.0\nrequests",
  "envVars": { "INPUT_PATH": "s3://bucket/data/" },
  "resources": { "cpu": "2", "memory": "4Gi" },
  "timeoutSeconds": 3600
}
```

**Ray 任务**
```json
{
  "name": "my-ray-job",
  "type": "RAY",
  "entrypoint": "python train.py",
  "requirements": "ray==2.10\nnumpy",
  "head":    { "cpu": "2", "memory": "4Gi" },
  "workers": { "count": 4, "cpu": "4", "memory": "8Gi" },
  "timeoutSeconds": 7200
}
```

**微调任务**
```json
{
  "name": "my-finetune",
  "type": "FINETUNE",
  "baseModel": "Qwen2.5-7B",
  "datasetPath": "s3://my-bucket/train.jsonl",
  "outputPath":  "s3://my-bucket/checkpoints/",
  "hyperparams": {
    "epochs": 3,
    "batchSize": 4,
    "learningRate": 2e-4,
    "loraRank": 16
  },
  "gpu": { "type": "V100", "count": 2 }
}
```

### 任务状态流转

```
PENDING → STARTING → RUNNING → SUCCEEDED
                              → FAILED
                              → CANCELLED
```

- `PENDING`：已入队，等待资源
- `STARTING`：CCI Pod 启动中（Python 快，Ray 需等 Head + Workers 就绪）
- `RUNNING`：正在执行

> MVP 使用预置镜像（不支持自定义 requirements.txt），因此无 `BUILDING` 状态。动态镜像构建（Kaniko Pipeline）在 Phase 2 加入，届时增加 `BUILDING` 状态。
>
> 终态名称与现有 `com.lakeon.job.JobStatus` 对齐，使用 `SUCCEEDED` 而非 `COMPLETED`。状态机整体形状不同（`DatalakeJobStatus` 多出 `STARTING` 状态用于表示 Ray 集群多 Pod 启动阶段），两个枚举彼此独立。

---

## 四、lakeon-api 代码结构

### 与现有 Job 框架的关系

`com.lakeon.datalake` 是一个**独立的新包**，与现有的 `com.lakeon.job` 包**无继承关系**：

- 现有 `com.lakeon.job`：服务 Knowledge Pipeline（kbwrite pod），使用 `jobs` 表，`tenant_id` 为 String 类型，状态机为 `PENDING → RUNNING → SUCCEEDED/FAILED/CANCELLED`
- 新增 `com.lakeon.datalake`：服务数据湖用户任务，使用独立的 `datalake_jobs` 表，包含 `cci_namespace`、`ray_job_name` 等 CCI 特有字段

`DatalakeJobEntity` 不继承 `JobEntity`，`DatalakeJobStatus` 是独立的枚举，不复用 `JobStatus`。两套系统共用点仅为：同一 RDS 实例、同一 API Key 认证、同一 `tenant_id` 格式。

### 包结构

```
com.lakeon.datalake
├── DatalakeController       REST endpoints
├── DatalakeService          任务生命周期管理、状态机
├── DatalakeJobEntity        RDS 持久化（见下方表定义）
├── DatalakeJobStatus        PENDING | STARTING | RUNNING | SUCCEEDED | FAILED | CANCELLED
├── DatalakeJobType          PYTHON | RAY | FINETUNE
├── DatalakeJobRepository    Spring Data JPA
├── PythonJobRunner          创建 K8s Job → VK → CCI 单 Pod
├── RayJobRunner             创建 RayJob CRD → KubeRay → VK → CCI
├── FinetuneJobRunner        注入内置模板参数 → 复用 RayJobRunner
└── DatalakeLogService       SSE 日志流：聚合 Head + Worker Pod 日志，完成后持久化到 OBS
```

### 日志方案

- **运行中**：通过 CCI Pod log API（经由 VK）实时 SSE 推流给客户端
- **Ray 任务**：聚合 Head Pod + 所有 Worker Pod 日志，按时间戳排序，带 Pod 标识前缀
- **已完成/失败**：日志持久化到 OBS（`s3://lakeon/tenant-{id}/jobs/{job_id}/logs.txt`），支持事后查询
- POC P1 需验证：VK 下 CCI Pod log API 是否可通过标准 K8s log endpoint 访问

### 预置镜像（MVP）

用户选择基础镜像，不支持自定义 requirements.txt 动态构建（Phase 2）：

| 镜像标签 | 用途 | 预装包 |
|---|---|---|
| `python:3.11-slim` | Python 轻量任务 | 无 |
| `python:3.11-data` | 数据处理 | pandas, numpy, requests, pyarrow |
| `ray:2.10-py311` | Ray 分布式任务 | ray[default] |
| `ray:2.10-py311-gpu` | 微调任务 | ray[train], torch, transformers |

### RDS 新增表

`datalake_jobs` 是独立的新表，与现有 `jobs` 表无外键关联。

```sql
CREATE TABLE datalake_jobs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     VARCHAR(64) NOT NULL,  -- 与现有模块一致，String 类型
  name          VARCHAR(128) NOT NULL, -- 用户自定义，per-tenant 不唯一
  type          VARCHAR(16) NOT NULL,  -- PYTHON | RAY | FINETUNE
  status        VARCHAR(16) NOT NULL,  -- PENDING | STARTING | RUNNING | SUCCEEDED | FAILED | CANCELLED
  spec          JSONB NOT NULL,        -- 完整请求体快照
  cci_namespace VARCHAR(64),           -- datalake-{tenant_id}
  ray_job_name  VARCHAR(128),          -- RayJob CRD name（RAY/FINETUNE）
  k8s_job_name  VARCHAR(128),          -- K8s Job name（PYTHON）
  base_image    VARCHAR(256),          -- 使用的预置镜像
  log_obs_path  VARCHAR(512),          -- 完成后日志 OBS 路径
  started_at    TIMESTAMPTZ,
  finished_at   TIMESTAMPTZ,
  core_hours    DECIMAL(10,4),         -- 计量：核·小时
  gpu_hours     DECIMAL(10,4),         -- 计量：GPU·小时（FINETUNE）
  error_message TEXT,
  created_at    TIMESTAMPTZ DEFAULT NOW(),
  updated_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_datalake_jobs_tenant_id ON datalake_jobs(tenant_id);
CREATE INDEX idx_datalake_jobs_status    ON datalake_jobs(status);
```

---

## 五、Console 数据湖模块

### 导航位置

```
左侧导航
├── 概览
├── 数据库
├── 知识库
└── 数据湖   ← 新增，位于知识库下方
```

### 页面结构

```
数据湖
├── 任务列表    (默认落地页)
├── 提交任务    (表单页，三种类型 Tab 切换)
└── 任务详情    (从列表跳入)
```

### 任务列表页

| 字段 | 说明 |
|---|---|
| 任务名称 | 用户自定义 |
| 类型 | Python / Ray / 微调 |
| 状态 | 排队中 / 启动中 / 运行中 / 已完成 / 失败 |
| 提交时间 | — |
| 耗时 | 运行中实时显示，结束后显示总时长 |
| 计量 | 核·小时 / GPU·小时 |
| 操作 | 查看详情 / 取消（运行中） |

### 任务详情页

```
任务名 · 状态 · [取消]
类型 · 提交时间 · 耗时 · 计量
────────────────────────────
资源用量（Workers 数、规格、已用 Core·Hour）
────────────────────────────
日志（实时滚动）
────────────────────────────
输出（OBS 路径，可点击）
```

---

## 六、安全隔离

沿用 V4 三层隔离模型：

| Layer | 机制 | 防护目标 |
|---|---|---|
| L1 | CCI Kata VM 硬边界 | 防容器逃逸，用户代码无法突破 VM 边界 |
| L2 | per-tenant CCI namespace + IAM | 防越权，tenant-a 无法访问 tenant-b 资源 |
| L3 | OBS per-tenant prefix 强制隔离 | MVP：服务账号 AK/SK，API 层强制注入 `s3://lakeon/tenant-{id}/` 前缀，用户代码无法访问其他租户路径。Phase 2 升级为 STS 临时凭证（TTL = Job 生命周期）。 |

Python 任务（单 Pod）与 Ray 任务（多 Pod）均运行在各自租户的 CCI namespace 内，互不可见。

---

## 七、基础设施前置条件

开始 POC 前需确认：

| 项目 | 说明 |
|---|---|
| VPCEP | 开通 CCI 2.0 所需，按时计费，需预算确认 |
| CCI 服务权限 | 账户开通 CCI，确认 GPU 配额（V100/T4）|
| 子网规划 | 确认现有 VPC 子网不与 `10.247.0.0/16` 冲突 |
| SWR 基础镜像 | 准备 `python:3.11-slim`、`ray:2.10-py311`、Ray+CUDA 镜像 |
| CCE 节点资源 | 评估现有节点是否能承载新增控制面组件 |

---

## 八、POC 计划

全部通过后才进入正式开发。总工期：**6 个工作日**。

| 优先级 | 验证项 | 工期 | 风险 | 失败降级 |
|---|---|---|---|---|
| P0 | VK 安装 + CCI 网络连通性 | 2 天 | VPCEP / 子网冲突 | 排查网络规划后重试 |
| P1 | Python Job 单 Pod 端到端（提交→运行→完成）+ SSE 日志验证 | 1 天 | VK log API 是否通 | POC 阶段临时改为轮询验证功能，生产 API 保持 SSE 不变 |
| P2 | KubeRay + Ray Job 端到端（含 Spec Normalizer，Head + Worker 日志聚合） | 2 天 | Spec 规整适配 | 静态规格先跳过 Normalizer |
| P3 | 预置镜像推送到 SWR，CCI 拉取验证（第三方 registry 不可用） | 0.5 天 | 低 | — |

> Spark 的 P0（Celeborn EVS 吞吐测试）MVP 不需要，Spark 是 Phase 2。
