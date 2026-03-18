# Job 框架 & Knowledge Pipeline 路线图

> 2026-03-18 规划，目标：建立通用 Job 基础设施，支撑 Knowledge Pipeline 和数据飞轮两条管线

---

## 一、核心决策：双轨架构（2026-03-18 确定）

### 信任边界划分

```
┌──────────────────────────────────────────────────────┐
│  Trusted Plane (我们的代码)                            │
│  ─────────────────────────────                        │
│  CCE 弹性节点池 (lakeon/role=compute)                  │
│  • 知识管线 (parse/chunk/embed/graph)                  │
│  • 训练数据导出 (PG → Lance)                           │
│  • 平台模型训练 (SFT/DPO)                              │
│  • 冷启动 ~8s, 本地 NVMe, RDS/OBS 直连                 │
│  • 无需隔离 — 代码是我们写的，可信                        │
├──────────────────────────────────────────────────────┤
│  Untrusted Plane (用户代码) — Phase 3 再做              │
│  ─────────────────────────────                        │
│  CCI Serverless (per-tenant namespace)                │
│  • 用户自定义 Ray Job / UDF                            │
│  • 用户的数据分析、自定义 embedding、自定义 transform     │
│  • 冷启动 34s — 用户可接受（提交任务后异步等结果）          │
│  • 天然沙箱: Kata microVM, 无 hostNetwork, 无节点访问   │
│  • 用户 Job 不连 RDS — 通过 API 读写，只读 OBS Lance    │
└──────────────────────────────────────────────────────┘
```

### 为什么 Trusted Plane 用 CCE 而不是 CCI

| 对比项 | CCE 弹性节点池 | CCI Serverless |
|--------|---------------|----------------|
| 冷启动 | ~8s (弹性节点已存在) | ~10s (镜像已缓存) |
| RDS/OBS | 直连 (VPC 内) | ✅ 已验证可达（需 CCI 网络资源指向正确 VPC） |
| 本地 NVMe | 有，可 prefetch Lance 数据 | 无 |
| setrlimit | 已解决 (node-init DaemonSet) | ✅ 已验证 OK（Ray 无此 syscall 需求） |
| 公网出口 | NAT 网关 | ✅ 已验证可达（同 VPC 共享 NAT） |
| 成本 | 弹性 min=1 max=5, 10min 缩容 | 按秒计费 |
| 安全隔离 | 不需要 — 代码是我们的 | 天然隔离 |

**结论**：我们自己的 Job 不需要 Kata microVM 隔离，用 CCE 弹性节点池性能更好、问题更少。CCI 留给未来用户自定义 Job。

### 为什么不需要 KubeRay

KubeRay 解决的是**长期运行 Ray Cluster 的生命周期管理**（扩缩容、故障恢复、版本升级）。我们的场景不同：

- Job 是短生命周期、用完即销毁
- API 本身就是 Job 编排器（已有 ImportJobPodManager 经验）
- 弹性节点池已有 autoscaler，不需要 KubeRay 管扩缩容

---

## 二、背景：两条管线的共同需求

Knowledge Pipeline（文档解析→embedding→存储）和数据飞轮（PG→OBS Lance→Ray 训练）共享以下基础设施：

| 共同基础 | Knowledge Pipeline | 数据飞轮 |
|---------|-------------------|---------|
| CCE Job Pod 编排 | 解析→分块→embedding | PG export→Lance→训练数据集 |
| OBS 读写 | 原始文档 + chunk 存储 | Lance 文件 + 模型产物 |
| PG 连接 | 写 embedding 到 pgvector | 读 memory/knowledge 数据 |
| Job 状态追踪 | 用户查看进度 | 内部监控 |

**结论**：先建通用 Job 基础设施，两条管线作为具体 Job 类型运行其上。

---

## 三、Pod 编排模式

**模式 1：单 Pod Job（Phase 1，覆盖绝大多数场景）**

```
API → 创建 CCE Job Pod (Ray single-node, nodeSelector: lakeon/role=compute)
    → 跑完 → 回调 API → Pod 自动销毁
```

文档解析、embedding、数据导出，单 Pod 完全够用。Ray 的价值是编程模型（Ray Data pipeline），不是分布式集群。

**模式 2：临时 Ray Cluster（大数据量任务，Phase 2）**

处理大量数据（百万级 embedding、TB 级数据导出、模型训练）时需要多 Pod 并行：

```
API → 创建 Head Pod on CCE (ray start --head)
    → 等待 Head 就绪，获取 Head Pod IP
    → 创建 N 个 Worker Pod (ray start --address=head_ip:6379)
    → 等待所有 Worker 注册到 Head
    → 向 Head 提交 Ray Job (ray job submit)
    → 轮询/回调 Job 完成状态
    → 销毁所有 Pod (Head + Workers)
```

API 自己编排临时 Cluster，用完即毁。不需要 CRD，就是普通 Pod 创建/销毁。

**Cluster 规模由 Job 类型和数据量决定**：
- 小任务（单文档解析）：模式 1，1 Pod
- 中任务（批量 embedding 10k+ 文档）：1 Head + 2-4 Workers
- 大任务（数据飞轮全量导出/训练）：1 Head + 5-20 Workers
- API 根据 `params.scale` 或自动估算数据量来决定 Worker 数

**CCE Pod 间网络**：CCE overlay 网络下同 namespace Pod 天然互通，无需额外配置。

---

## 四、安全模型

### Trusted Plane（Phase 1-2）：预定义 Job 类型

用户通过 API 触发预定义 Job 类型（`document_parse`、`embedding`、`export_lance` 等），不直接提交代码。

- 代码可信，无恶意风险
- OBS/RDS 凭据受控注入，scope 明确
- CCE 弹性节点池，nodeSelector 隔离 compute 负载

### Untrusted Plane（Phase 3）：用户自定义 Job

等有用户需求时，用 CCI Serverless 运行用户代码：

| 安全特性 | CCI 表现 |
|---------|---------|
| 内核隔离 | Kata microVM，独立内核，等同 AWS Fargate |
| 特权模式 | 明确禁止 |
| hostNetwork/hostPID | 禁止，无法访问宿主机 |
| 网络隔离 | VPC 级别 + per-tenant namespace |
| GPU/Ascend | 支持 |

开放用户 Job 的额外要求：
- Per-tenant namespace + NetworkPolicy 阻断跨租户流量
- Ray 无内置认证（CVE-2023-48022），必须靠网络隔离
- 计量计费（CPU·秒 / GPU·秒）
- 用户 Job 不连 RDS — 数据流单向: API 写入 PG+OBS → 用户 Job 只读 OBS Lance → 结果写回 OBS → API 获取结果

---

## 五、PG → OBS 数据导出

### 导出方案：DuckDB 一条 SQL 直写 Parquet

不通过应用层中转，DuckDB 直连 PG 并写 Parquet 到 OBS：

```python
import duckdb

conn = duckdb.connect()
conn.execute("INSTALL postgres; LOAD postgres; INSTALL httpfs; LOAD httpfs;")
conn.execute("""
    SET s3_endpoint = 'obs.cn-north-4.myhuaweicloud.com';
    SET s3_access_key_id = '...';
    SET s3_secret_access_key = '...';
    SET s3_url_style = 'path';
""")

# 一条 SQL: PG → DuckDB 列式引擎 → Parquet → OBS
conn.execute("""
    COPY (
        SELECT id, content, embedding, metadata, created_at
        FROM postgres_scan('host=... dbname=... user=...', 'public', 'chunks')
    )
    TO 's3://lakeon-storage/tenant/xxx/training/chunks.parquet'
    (FORMAT PARQUET, ROW_GROUP_SIZE 100000, COMPRESSION ZSTD)
""")
```

**为什么用 DuckDB 而不是 COPY → Arrow → Parquet**：
- 一条 SQL 完成，零应用代码，零中间文件
- DuckDB 流式处理，内存可控（不像 Arrow RecordBatch 可能 OOM）
- 原生 ZSTD 压缩、`PARTITION_BY` 分区写入
- 依赖只有 `pip install duckdb`（vs pyarrow + psycopg2 + boto3）

### 存储格式分层策略

| 数据类型 | 格式 | 原因 |
|---------|------|------|
| 文本 + 结构化 (chunks, embeddings, SFT/DPO 数据) | **Parquet** | Apache 顶级项目，生态成熟 (Spark/Flink/Trino/Ray)，顺序扫描最优，OBS 兼容性久经考验 |
| 多模态 (图片+音频+视频，Phase 3+) | **Lance** | 大二进制 + 元数据同格式存储，column pruning 可只读元数据不碰二进制列 |

**为什么 Phase 1-2 用 Parquet 而非 Lance**：
- Lance 2022 年开源，社区小，生产案例主要围绕 LanceDB 向量检索
- Parquet 是 Apache 顶级项目，Netflix/Apple/Salesforce 生产验证多年
- 我们的场景（全量写 + 顺序扫描）是 Parquet 最强项，不需要 Lance 的 O(1) 随机访问
- 向量检索已经有 pgvector，不需要 LanceDB
- Ray 对 Parquet 的支持最成熟（`ray.data.read_parquet()` 是 Ray Data 最早支持的格式）

**Phase 3+ 引入 Lance 的条件**: 当知识管线支持图片/音频/视频处理器，需要存储大二进制和元数据在同一个数据集中时。

### 时延估算

10 万条 chunks 导出：

| 步骤 | DuckDB 方案 |
|------|------------|
| PG → DuckDB 列式引擎 | ~3s |
| DuckDB → Parquet → OBS | ~3s |
| **总计** | **~6-7s** |
| Ray read_parquet 顺序扫描 | ~5-20s/GB |

### Job Pod 性能注意事项

| 问题 | 影响 | 解决方案 |
|------|------|---------|
| `/dev/shm` 只有 64MB | Ray object store 启动失败或 OOM | Job Pod spec 挂载 `emptyDir: {medium: Memory, sizeLimit: 2Gi}` 到 `/dev/shm` |
| Embedding 模型放不下 | Qwen3-Embedding-8B 需 ~16GB，弹性节点只有 8GB | Phase 1 用 BGE-M3 (568M, ~1.5GB, 1024维多语言)，Phase 2 再考虑 GPU 节点 |
| OBS per-bucket 限流 | ~3500 req/s per prefix，多 worker 并发读可能 503 | 按 tenant 分 prefix + Ray `concurrency` 参数限制并行度 |

---

## 六、实现路线

### Phase 1：通用 Job 框架 + Knowledge Pipeline MVP

#### 1a. 通用 Job 框架

基于现有 ImportJobPodManager 模式，抽象出通用层：

```
com.lakeon.job/
├── JobEntity.java              # 通用 Job 实体 (type, status, input, output, tenant_id)
├── JobRepository.java
├── JobService.java             # 提交/查询/取消，按 type 分发
├── JobType.java                # DOCUMENT_PARSE, EMBEDDING, EXPORT_LANCE, TRAINING
├── JobStatus.java              # PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED
├── JobPodManager.java          # CCE Pod 创建/监控/清理 (nodeSelector: lakeon/role=compute)
├── JobCallbackController.java  # 通用回调端点
└── JobScheduledTasks.java      # 孤儿检测、超时清理
```

**与 ImportJobPodManager 的区别**：
- Pod 调度到 CCE 弹性节点池（`nodeSelector: lakeon/role=compute`）
- Job 配置通用化：`Map<String, String> params` 替代 import-specific 字段
- 镜像可配置：不同 Job 类型用不同 Ray 镜像
- OBS 凭据统一注入

**API 设计**：
```
POST   /api/v1/jobs              # 提交 Job (type + params)
GET    /api/v1/jobs/{id}         # 查询状态
GET    /api/v1/jobs              # 列表（支持 type/status 过滤）
DELETE /api/v1/jobs/{id}         # 取消
```

#### 1b. Knowledge Pipeline MVP

作为第一个 Job 类型实现。

```
用户上传文档 → API 存 OBS
    → 提交 Job Pod: document_parse (Marker 解析 → structure-aware chunking)
    → 提交 Job Pod: embedding (Qwen3-Embedding-8B 批量向量化)
    → 写入用户 PG (pgvector + pg_search)
    → 回调 API 更新状态
```

**Job 镜像**：基于 `rayproject/ray:2.44.1-py312` 构建自定义镜像，预装：
- Marker（PDF/DOCX 解析）
- sentence-transformers + Qwen3-Embedding-8B（或通过 OBS 加载模型权重）
- psycopg2（写 PG）
- boto3（读写 OBS）

**MCP 端点**：`knowledge_search`
- Hybrid retrieval: pgvector cosine similarity + pg_search BM25 + RRF fusion
- 直接查用户的 PG 数据库，不经过额外服务

**单 Pod 还是 Pipeline？**
- Phase 1 简化为单 Pod：一个 Pod 内串行完成 parse → chunk → embed → write
- 未来优化：拆成多个 Job 步骤，支持断点续跑

### Phase 2：数据飞轮管线

```
PG (memory/knowledge 数据)
    → Job Pod: DuckDB export_parquet → OBS Parquet
    → Ray Cluster on CCE: 数据清洗/标注/格式化
    → OpenRLHF on CCE: SFT → DPO → RL 训练
    → 模型产物输出到 OBS
```

- **PG → Parquet 导出**: DuckDB postgres_scan 直写 OBS（单 Pod，一条 SQL）
- **训练数据处理**: 临时 Ray Cluster on CCE 弹性节点池（多 Pod）
- **RL 训练框架**: OpenRLHF (Ray 原生，支持 SFT/DPO/PPO/GRPO/REINFORCE++)
  - 4B-8B 模型不需要 VeRL 的 3D 并行（那是给 70B+ 的）
  - 不用 AReaL（蚂蚁）— 不依赖 Ray，增加架构复杂度
  - VeRL 留作后备，模型规模升到 70B+ 时切换
- 复用 Phase 1 的 Job 框架，新增 `EXPORT_PARQUET` 和 `TRAINING` Job 类型

### Phase 3：用户自定义 Job（CCI Serverless，等用户需求）

前提条件：
- [ ] OBS VPC Endpoint 配置通过
- [ ] 构建用户 Ray base image（不含 compute_ctl，绕过 setrlimit）
- [ ] Per-tenant CCI namespace + NetworkPolicy 验证
- [ ] 计量系统就绪
- [ ] 用户 Job 镜像安全扫描
- [ ] API 支持用户上传 Python 脚本 / 自定义镜像

CCI 验证结果（2026-03-18 已完成）：
- [x] Ray 进程正常启动 — setrlimit(CORE) OK，Ray init + 分布式任务 OK
- [x] RDS 可连接 — CCI 网络资源必须指向 RDS 所在的 VPC `d66706ac`
- [x] 公网可达 — NAT 网关 `d022d5a3` 在同 VPC，pip install OK
- [x] OBS 可达 — VPC Endpoint 已配置，DNS 解析到内网 IP
- [x] 冷启动 ~10s（python:3.12-slim 镜像，含 ENI 分配）
- [ ] 多 Pod 网络互通：同 namespace CCI Pod 能否通过 Pod IP 直连（待验证）
- [ ] Ray Cluster 组建：Head Pod + Worker Pod（待验证）

CCI 关键配置（验证时踩的坑）：
- CCI Network 资源的 `attachedVPC` 必须是 `d66706ac`（CCE/RDS/NAT 所在 VPC），不是 `32c3c8fa`
- `networkID` = `f28729ee`，`subnetID` = `2455b2f4`（neutron subnet ID）
- `availableZone` 只能选 `cn-north-4a` 或 `cn-north-4g`
- SWR 镜像拉取需要 `imagepull-secret`（通过 `hcloud SWR CreateAuthorizationToken` 生成）
- CCI Pod 日志在 Pod 终止后不可读，需在运行时获取

---

## 七、基础设施依赖

### Phase 1 需要（CCE 弹性节点池，已有）

- 弹性节点池 `dbay-compute-pool` — 已存在，min=1 max=5
- nodeSelector `lakeon/role=compute` — 已配置
- node-init DaemonSet (containerd LimitCORE) — 已部署
- OBS SDK（API 层上传文档、读取结果）
- Job Pod 内 OBS 读写（S3 兼容 + AK/SK 通过 Secret 注入）

### Phase 3 需要（CCI，未来）

- CCE Virtual Kubelet 插件（或 CCI API 直连）
- OBS VPC Endpoint
- CCI namespace 模板 + RBAC
- Per-tenant NetworkPolicy

---

## 八、决策记录

| 决策 | 原因 |
|------|------|
| **Trusted Plane 用 CCE 弹性节点池** | 冷启动 8s (vs CCI 34s)、RDS/OBS 直连、本地 NVMe、setrlimit 已解决；我们的代码不需要 Kata 隔离 |
| **Untrusted Plane 用 CCI (Phase 3)** | 用户代码不可信，需要 Kata microVM 隔离；不影响管控面和其他租户 |
| **CCI 验证已完成 (2026-03-18)** | Ray+RDS+OBS+公网全部 OK；CCI 技术风险已消除，Phase 3 随时可做 |
| 不用 KubeRay | Job 是短生命周期用完即毁，不需要持久 Ray Cluster 管理；CCE autoscaler 管扩缩容 |
| API 直接编排 Job Pod | 复用 ImportJobPodManager 模式，API 本身就是 Job 编排器 |
| Phase 1 单 Pod Job | 文档解析/embedding 单机够用，分布式是过度设计 |
| 先建通用 Job 框架再做具体管线 | Knowledge Pipeline 和数据飞轮共享 80% 基础设施 |
| **PG 导出用 DuckDB 直写 Parquet** | 一条 SQL 完成 PG→Parquet→OBS，零应用代码，依赖只需 `pip install duckdb` |
| **Phase 1-2 用 Parquet，多模态时引入 Lance** | Parquet 生态成熟 (Apache 顶级项目)，顺序扫描最优；Lance 留给多模态大二进制场景 |
| **RL 训练用 OpenRLHF** | Ray 原生集成，4B-8B 模型规模匹配，SFT/DPO/PPO/GRPO 全覆盖；VeRL 留作 70B+ 后备 |
| **Phase 1 Embedding 用 BGE-M3** | 568M 参数，4C/8G CCE 节点能跑；Qwen3-8B 需 16GB 放不下 |
