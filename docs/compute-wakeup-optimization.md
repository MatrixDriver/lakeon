# 计算节点弹性唤醒优化方案

## 1. 背景与目标

### 1.1 当前状态

Lakeon 的计算节点（compute-node）采用按需创建 K8s Pod 的方式。当数据库处于 SUSPENDED 状态时，用户连接触发唤醒流程，当前端到端耗时约 **10-17 秒**。

当前唤醒流程：

```
用户连接 → Proxy → API(wakeCompute) → 创建 ConfigMap → 创建 Pod
→ K8s 调度 → 拉取镜像(1.3GB) → 容器启动 → compute_ctl 初始化
→ PG 启动 → Readiness Probe 通过 → 转发连接
```

各阶段耗时分解：

| 阶段 | 耗时 | 说明 |
|------|------|------|
| API 处理 + ConfigMap/Pod 创建 | ~30ms | K8s API 调用 |
| K8s Pod 调度 + 容器启动 | ~1-3s | 调度器分配节点、CRI 创建容器 |
| 镜像拉取 | 0s（缓存）/ 30-120s（冷） | compute-node-v17 镜像 ~1.3GB |
| Readiness Probe 初始延迟 | 5s | `initialDelaySeconds=5` |
| compute_ctl + PG 启动 | ~3-5s | Neon 扩展加载、连接 pageserver |
| API 轮询等待（1s 间隔） | ~1-2s | 轮询 pod Ready 状态 |

### 1.2 优化目标

在 tenant 和 timeline 已创建的前提下，从用户发起连接到可执行 SQL 查询：

- **第一阶段目标**：500ms - 1s（Pod 保留 + 进程冻结）
- **第二阶段目标**：200-500ms（Warm Pool 预热池）
- **长期目标**：连接 <10ms，首查询 200-500ms（Proxy 连接缓冲）

---

## 2. 业界调研

### 2.1 各平台方案对比

| 平台 | 唤醒延迟 | 核心策略 | 架构特点 |
|------|----------|----------|----------|
| **Neon** | ~500ms | 无状态 compute + PgBouncer 连接缓冲 | NeonVM（QEMU/KVM 轻量虚机），非 K8s Pod |
| **CockroachDB** | 亚秒级 | Warm Pool 预热池（SIGMOD 2025） | SQL/KV 分层，预热 SQL Pod 动态绑定租户 |
| **Aurora Serverless v2** | ~15-30s | ACU 扩缩容 | 无预热，完全冷启动 |
| **Turso** | 极快 | 嵌入式 SQLite + 按需加载 128KiB | 非 C/S 架构，不可比 |
| **Supabase** | 分钟级 | 实例级暂停/恢复 | 全量重启 |

### 2.2 Neon 方案分析

Neon 不使用 Warm Pool，仍能实现 ~500ms 唤醒，原因：

1. **NeonVM 替代 K8s Pod**：基于 QEMU/KVM 的轻量虚机，fork 进程启动 ~200-300ms，远快于 K8s Pod 生命周期（API server → scheduler → kubelet → CRI）
2. **完全无状态 compute**：不存储任何数据文件，启动时通过网络按需从 pageserver 拉取页面，无 WAL 重放
3. **PgBouncer 连接缓冲**：`query_wait_timeout=120s`，连接立即成功，查询排队等待 compute 就绪

Lakeon 使用标准 K8s Pod，无法直接复制 Neon 的 NeonVM 方案。

### 2.3 CockroachDB 方案分析（业界最佳实践）

CockroachDB Serverless 的 Warm Pool 方案（SIGMOD 2025 论文）：

1. **预热 SQL Pod 池**：预创建通用 SQL Pod，已完全初始化但未绑定租户
2. **动态绑定**：唤醒时"盖章"注入租户 ID + 证书，亚秒级完成
3. **SQL/KV 分层**：SQL Pod 无状态，KV/存储层共享
4. **Proxy 层**：SNI 识别租户，least-connections 路由

这与 Lakeon 的存算分离架构（compute 无状态 + pageserver 存储层）高度契合。

### 2.4 通用架构模式

业界 Serverless 数据库普遍采用**分层挂起**策略：

| 层级 | 状态 | 唤醒延迟 | 资源成本 |
|------|------|----------|----------|
| **Hot** | compute 活跃，缓存热 | <10ms | 全量计算成本 |
| **Warm** | compute 挂起，proxy 活跃 | ~500ms | 仅 proxy + 存储 |
| **Cold** | 完全释放，数据在对象存储 | 秒级~分钟级 | 仅存储成本 |

---

## 3. 华为云 CCE Autopilot 评估

### 3.1 CCE Autopilot 概述

CCE Autopilot 是华为云基于 QingTian 架构的 Serverless K8s 服务：

- **VM 级安全隔离**：底层使用安全容器（类似 Kata Container），非传统 runc
- **多级资源池预热**：预置计算资源池，Pod 调度时直接分配
- **镜像快照加速**：容器镜像预缓存为快照，跳过镜像拉取
- **按秒计费**：最小 0.25 vCPU，Pod 删除后零成本
- **免运维**：无需管理节点、操作系统、安全补丁

### 3.2 对 Lakeon 的适用性分析

| 维度 | CCE Autopilot | 普通 CCE |
|------|---------------|----------|
| Pod 启动速度 | 秒级（~3-5s，含镜像快照） | ~10-15s（需预拉取镜像） |
| 节点运维 | 全托管 | 需管理节点池 |
| 镜像处理 | 快照加速，接近零拉取时间 | DaemonSet 预拉取 |
| suspend 成本 | Pod 删除后零成本 | 节点常驻成本 |
| Warm Pool 补充速度 | ~3-5s | ~10-15s |
| 计费灵活性 | 按秒、按 Pod | 按节点（固定成本） |

**结论**：CCE Autopilot 适合作为底座，但其秒级 Pod 启动仍无法满足 100-300ms 目标，需要在应用层叠加优化。

---

## 4. 方案设计

### 4.1 总体架构：三层唤醒

采用 Hot / Warm / Cold 三层唤醒架构，部署在 CCE Autopilot 上：

```
┌─────────────────────────────────────────────────┐
│                   用户连接                        │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│              Neon Proxy（连接缓冲）               │
│         立即 accept 连接，后台触发唤醒             │
└──────────────────────┬──────────────────────────┘
                       │
              ┌────────┴────────┐
              ▼                 ▼
     ┌─────────────┐   ┌──────────────┐
     │  Hot / Warm  │   │    Cold      │
     │  Pod 已存在  │   │  Pod 已销毁   │
     └──────┬──────┘   └──────┬───────┘
            │                  │
            ▼                  ▼
   重启 PG 进程           从 Warm Pool
    ~500ms-1s            分配预热 Pod
                         ~200-500ms
```

### 4.2 第一阶段：Pod 保留 + 进程冻结（目标 500ms-1s）

**核心思路**：suspend 时不删除 Pod，只停止 PG 进程；resume 时在同一 Pod 内重启 PG。

#### 4.2.1 suspend 流程改造

当前：
```
suspend → 删除 Pod → 删除 ConfigMap → 状态置为 SUSPENDED
```

改造后：
```
suspend → 通过 compute_ctl HTTP API 停止 PG → 状态置为 SUSPENDED
        → 启动分层超时计时器
```

#### 4.2.2 resume 流程改造

当前：
```
resume → 创建 ConfigMap → 创建 Pod → 等待 Pod Ready → 状态置为 RUNNING
```

改造后：
```
resume → 检查 Pod 是否存在
       → [Pod 存在] 通过 compute_ctl HTTP API 重启 PG → ~500ms-1s
       → [Pod 不存在] 走现有创建流程（Autopilot 秒级创建）
```

#### 4.2.3 分层超时策略

```
SUSPENDED (Pod 保留, 0.25vCPU 最小规格)
    │
    ├── < 30 分钟：Pod 保留，原地唤醒 ~500ms-1s
    │
    └── ≥ 30 分钟：销毁 Pod，释放资源
                    下次唤醒走 Cold 路径 ~3-5s
```

超时阈值可配置，通过定时任务（已有的 `ComputeLifecycleService` 30s 检查循环）实现。

#### 4.2.4 代码改动范围

| 文件 | 改动 |
|------|------|
| `ComputePodManager.java` | 新增 `stopCompute()` / `restartCompute()` 方法，调用 compute_ctl HTTP API |
| `ComputeLifecycleService.java` | suspend 逻辑改为停进程而非删 Pod；新增分层超时回收 |
| `DatabaseService.java` | resume 逻辑增加 Pod 存在性判断 |
| `LakeonProperties.java` | 新增 `suspend.pod-retain-minutes` 配置项 |
| `application.yml` | 新增配置默认值 |

#### 4.2.5 Readiness Probe 调优

同步优化，不依赖架构改造：

```yaml
# 当前
initialDelaySeconds: 5
periodSeconds: 2

# 优化后
initialDelaySeconds: 1
periodSeconds: 1
```

API 轮询间隔从 1s 降到 200ms。

### 4.3 第二阶段：Warm Pool 预热池（目标 200-500ms）

**核心思路**：维护 N 个已启动的空闲 compute Pod，唤醒时从池中分配并动态绑定 tenant/timeline。

#### 4.3.1 Warm Pool 架构

```
┌──────────────────────────────────────────┐
│           WarmPoolManager                │
│                                          │
│  Pool: [pod-1(idle), pod-2(idle), ...]   │
│  MinSize: 3    MaxSize: 10               │
│  ReplenishThreshold: 2                   │
│                                          │
│  assign(tenantId, timelineId) → Pod      │
│  release(pod) → 销毁                     │
│  replenish() → 后台创建新 Pod            │
└──────────────────────────────────────────┘
```

#### 4.3.2 分配流程

```
唤醒请求
  → WarmPoolManager.assign(tenantId, timelineId)
  → 从池中取一个 idle Pod
  → 调用 compute_ctl HTTP API 绑定 tenant/timeline 配置
  → PG 连接 pageserver，加载数据
  → 返回 Pod 地址（~200-500ms）

池水位检查
  → 当前 idle 数量 < ReplenishThreshold
  → 后台异步创建新 Pod 补充（Autopilot ~3-5s）
```

#### 4.3.3 安全性

- 分配前：Pod 是全新创建的，无任何租户数据
- 归还时：不归还，直接销毁 Pod，池后台补充新的干净 Pod
- 租户隔离：每个 Pod 同一时间只服务一个租户

#### 4.3.4 池大小策略

| 场景 | MinSize | 说明 |
|------|---------|------|
| 开发/测试 | 1-2 | 低成本 |
| 生产-小规模 | 3-5 | 覆盖突发唤醒 |
| 生产-大规模 | 按历史峰值 | 可结合 HPA 动态调整 |

#### 4.3.5 代码改动范围

| 文件 | 改动 |
|------|------|
| `WarmPoolManager.java`（新增） | 池管理：创建、分配、补充、监控 |
| `ComputeLifecycleService.java` | Cold 路径唤醒改为从池分配 |
| `LakeonProperties.java` | 新增 `warm-pool.*` 配置项 |
| `application.yml` | 池大小、补充阈值配置 |

#### 4.3.6 前置验证

Warm Pool 方案依赖 compute_ctl HTTP API（端口 3080）支持动态重新配置 tenant/timeline。需要验证：

1. compute_ctl 是否提供配置更新 API
2. PG 是否能在不重启的情况下切换 Neon GUC 参数
3. 切换后连接 pageserver 的延迟

如果 compute_ctl 不支持动态绑定，备选方案是池中 Pod 只完成 PG 二进制启动，绑定时通过 configMap 更新 + PG 重启（预计增加 ~200ms）。

### 4.4 增强优化：Proxy 连接缓冲

独立于阶段一/二，可随时叠加。

**改造点**：Neon Proxy 的 `ProxyAdapterController` 唤醒逻辑改为异步：

```
用户连接 → Proxy accept TCP 连接（<10ms）
        → 后台调用 wakeCompute（异步）
        → compute 就绪后透明转发
        → 用户感知：连接成功，首条 SQL 等 200ms-1s
```

当前 Proxy 行为是同步阻塞等待 compute 就绪后才建立连接，改为连接缓冲后用户体验显著改善。

---

## 5. 部署架构

```
CCE Autopilot 集群
├── lakeon namespace
│   ├── lakeon-api（含 WarmPoolManager）
│   ├── pageserver
│   ├── safekeeper (StatefulSet)
│   ├── storage-broker
│   ├── proxy（连接缓冲增强）
│   └── Warm Pool Pods（idle, 0.25vCPU）
│
├── lakeon-compute namespace
│   ├── compute-xxx（活跃租户 Pod）
│   ├── compute-yyy（Warm 状态，PG 已停止）
│   └── warm-pool-zzz（预热池 Pod）
│
└── 华为云服务
    ├── OBS（对象存储）
    ├── RDS（元数据库）
    ├── 镜像快照（compute-node-v17 预缓存）
    └── AOM / LTS / APM（可观测性）
```

---

## 6. 实施计划

### 第一阶段：Pod 保留 + 进程冻结

| 步骤 | 内容 | 预期效果 |
|------|------|----------|
| 1 | 验证 compute_ctl HTTP API（停止/重启 PG） | 确认技术可行性 |
| 2 | 改造 suspend 逻辑：停进程替代删 Pod | suspend 后 Pod 保留 |
| 3 | 改造 resume 逻辑：检测 Pod 存在性，原地重启 | 唤醒 ~500ms-1s |
| 4 | 实现分层超时回收（默认 30 分钟） | 资源自动释放 |
| 5 | Readiness Probe 调优 | Cold 路径缩短 ~5s |
| 6 | 集成测试验证 | 确保功能正确 |

### 第二阶段：Warm Pool 预热池

| 步骤 | 内容 | 预期效果 |
|------|------|----------|
| 1 | 验证 compute_ctl 动态绑定 API | 确认技术可行性 |
| 2 | 实现 WarmPoolManager | 池创建/分配/补充 |
| 3 | Cold 路径改为从池分配 | 唤醒 ~200-500ms |
| 4 | 池大小自适应策略 | 资源效率优化 |
| 5 | 监控指标：池水位、分配延迟、补充速度 | 运维可观测 |

### 增强阶段：Proxy 连接缓冲

| 步骤 | 内容 | 预期效果 |
|------|------|----------|
| 1 | Proxy 唤醒逻辑改为异步 | 连接 <10ms |
| 2 | 连接排队 + 超时机制 | 用户体验优化 |

---

## 7. 风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| compute_ctl HTTP API 不支持动态绑定 | Warm Pool 方案受阻 | 备选：configMap 更新 + PG 重启 |
| CCE Autopilot Pod 启动慢于预期 | Warm Pool 补充速度受限 | 增大池 MinSize，或退回普通 CCE |
| suspended Pod 长期保留增加成本 | 资源浪费 | 分层超时策略 + 最小规格（0.25vCPU） |
| Warm Pool 耗尽（突发唤醒） | 部分请求退化为 Cold 路径 | 池大小动态调整 + 监控告警 |

---

## 8. 预期效果

| 场景 | 当前 | 第一阶段 | 第二阶段 | 增强 |
|------|------|----------|----------|------|
| 活跃租户唤醒（<30min） | ~10-17s | **~500ms-1s** | ~500ms-1s | 连接 <10ms |
| 长期挂起唤醒（>30min） | ~10-17s | ~3-5s (Autopilot) | **~200-500ms** | 连接 <10ms |
| 首次创建（含 tenant/timeline） | ~17s | ~17s | ~17s | ~17s |
