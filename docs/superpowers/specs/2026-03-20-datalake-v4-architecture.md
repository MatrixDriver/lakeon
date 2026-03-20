# Serverless Spark + Ray 统一计算平台架构 V4

> 基于华为云 CCE + CCI · 最终方案
> 转换自 `docs/datalake-v4-architecture.html` · 2026-03-20

---

## 目录

1. [总体架构](#总体架构)
2. [控制面详图](#控制面详图)
3. [数据面详图](#数据面详图)
4. [安全模型](#安全模型)
5. [组件职责](#组件职责)
6. [CCI 能力核查](#cci-能力核查)
7. [POC 计划](#poc-计划)

---

## 总体架构

CCE 承载控制面，CCI 承载全部用户负载（Kata VM 强隔离），Virtual Kubelet 透明桥接。

```
Users / SDK / REST API
         │
┌────────▼────────────────────────────────────────────────────────────────────┐
│  CCE — 控制面（可信区）                                                      │
│                                                                              │
│  Job Gateway              Image Build Pipeline                               │
│  IAM Auth · 参数白名单    requirements.txt → Kaniko → Trivy → SWR            │
│  Job 路由                                                                    │
│         │                                                                    │
│  ┌──────┴──────┐  ┌───────────────┐  ┌─────────────┐  ┌──────────────┐    │
│  │Spark Operator│  │Ray Controller │  │Cert Manager │  │Spec Normalizer│   │
│  │社区版不改    │  │自研 · 最小    │  │per-job mTLS │  │预对齐 CCI    │    │
│  │SparkApp CRD  │  │RBAC           │  │短期证书     │  │固定规格      │    │
│  │             │  │RayJob CRD     │  │TTL=Job生命  │  │              │    │
│  └──────┬──────┘  └───────┬───────┘  └─────────────┘  └──────────────┘    │
│         │                 │                                                  │
│  ┌──────▼─────────────────▼──────────────────────────┐  ┌──────────────┐  │
│  │  Virtual Kubelet HA (×2, leader election)          │  │Spark / Ray   │  │
│  │  CCE API → CCI 透明调度 · 需购买 VPCEP ·           │  │Scaler 独立   │  │
│  │  仅支持 VPC 网络模式                                │  │决策          │  │
│  └──────────────────────┬─────────────────────────────┘  └──────────────┘  │
│                         │ 透明调度                                            │
│  RDS (Job 状态持久化)    │                                                    │
└─────────────────────────┼────────────────────────────────────────────────────┘
                          ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│  CCI — 数据面（Kata VM 隔离 · per-tenant namespace · IAM 权限隔离）              │
│                                                                                  │
│  tenant-a-ns                                                                     │
│  ┌──────────────────────────────────────┐  ┌───────────────────────────────┐   │
│  │  Spark                               │  │  Ray                          │   │
│  │  Driver Pod (Kata VM)                │  │  Ray Head Pod (Kata VM)       │   │
│  │  Signal Bridge sidecar               │  │  GCS → ext. Redis HA          │   │
│  │  metrics-exporter sidecar            │  │  metrics-exporter sidecar     │   │
│  │  K8S API 禁止                        │  │                               │   │
│  │                                      │  │  Ray Worker Pods × N (Kata VM)│   │
│  │  Executor Pods × N (Kata VM)         │  │  STS AK/SK · OBS spill        │   │
│  │  emptyDir 内存缓冲 · mTLS → Celeborn │  │  Spec 预规整                  │   │
│  │                                      │  │                               │   │
│  │  Celeborn Worker × M (Kata VM)       │  │  Image Snapshot Cache         │   │
│  │  EVS SSD PVC (per-worker)            │  │  SWR 镜像预拉取               │   │
│  │  emptyDir 内存层 → EVS → OBS 溢出   │  │  第三方 registry 不可用       │   │
│  │  mTLS · per-app quota                │  │                               │   │
│  │                                      │  │  OBS Object Spill             │   │
│  │  OBS Spill（EVS 溢出时）             │  │  STS 临时 AK/SK               │   │
│  │  STS 临时 AK/SK · per-tenant prefix  │  │  per-tenant bucket prefix     │   │
│  └──────────────────────────────────────┘  └───────────────────────────────┘   │
│                                                                                  │
│  tenant-b-ns（结构完全相同，IAM 隔离）                                           │
│                                                                                  │
│  镜像预拉取池：仅预拉取镜像到 CCI 节点缓存，Pod 创建时才注入身份和凭证            │
│                                                                                  │
│  External Services                                                               │
│  Redis HA (Ray GCS) · OBS per-tenant prefix · SWR 唯一 registry · AOM 日志监控  │
│  VPCEP (CCI 2.0 必须购买)                                                       │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 控制面详图

每个组件的职责边界、RBAC 权限和信号流。

```
User / SDK Request
        │
┌───────▼──────────────────────────────────────┐
│              Job Gateway                      │
│  ① IAM Token 验证                             │
│  ② Spark conf 白名单过滤（禁 spark.kubernetes.*）│
│  ③ 参数注入防范                                │
│  只能 create SparkApplication / RayJob CRD    │
│  不能直接调 CCI API                            │
└───────┬──────────────────────────┬────────────┘
        │                          │
┌───────▼──────────┐  ┌───────────▼──────────┐  ┌─────────────────┐
│  Spark Operator  │  │   Spec Normalizer     │  │  Ray Controller │
│  （社区版）       │  │  拦截 Pod spec        │  │  （自研）        │
│  Watch           │  │  预对齐 CCI 规格       │  │  Watch RayJob   │
│  SparkApp CRD    │  │  3C6G → 4C8G          │  │  CRD            │
│  调 CCE API →    │  │  5C10G → 8C16G        │  │  调 CCE API →   │
│  VK 调度 Driver  │  │  Webhook 实现，透明注入 │  │  VK 调度        │
│  RBAC: SparkApp  │  └───────────────────────┘  │  RBAC: RayJob   │
│  + VK Pod only   │                              │  + VK Pod only  │
└───────┬──────────┘                              └──────┬──────────┘
        │                                                │
┌───────▼──────────┐  ┌────────────────┐  ┌────────────▼──────────┐
│  Signal Bridge   │  │  Cert Manager  │  │  Ray Autoscaler       │
│  Sidecar         │  │  每个 Job 签发  │  │  ResourceDemand →     │
│  （注入 Driver） │  │  短期 mTLS 证书 │  │  Worker 数量决策      │
│  监听 DA 扩缩信号│  │  TTL = Job     │  │  独立决策，不与        │
│  → HTTP →        │  │  生命周期      │  │  Spark Scaler 耦合    │
│  Spark Operator  │  │  自动吊销      │  └───────────────────────┘
└──────────────────┘  └────────────────┘
        │
┌───────▼──────────────────────────────────────────────────────────┐
│         Virtual Kubelet HA（×2 副本，leader election）            │
│  将 CCE K8S API 的 Pod 调度请求透明转发到 CCI                     │
│  维护 Pod 状态双向同步                                            │
│  限制：仅 VPC 网络 CCE Standard/Turbo                            │
│  子网不能与 10.247.0.0/16 重叠                                   │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼
                     CCI 数据面

辅助组件：
  Prometheus (scrape Pod IP sidecar exporter) · RDS (Job 状态持久化，只写)
```

---

## 数据面详图

CCI Kata VM 内的组件结构、存储路径和网络边界。

### Spark（tenant-a-ns）

```
Driver Pod (Kata VM)
├── Spark Driver JVM
├── Signal Bridge sidecar
└── metrics-exporter sidecar
    禁止访问 K8S API · 禁止访问元数据服务 · mTLS 客户端证书
    Spec 预规整（Webhook 注入） · emptyDir 可用
         │ shuffle
Executor Pods × N (Kata VM)
    每个 Pod 独立 Kata VM · emptyDir 内存写缓冲
    HostPath 不可用 → shuffle 写 emptyDir（内存），溢出到 Celeborn
    mTLS 证书 → 连接 Celeborn · requests = limits（CCI 要求）
         │
Celeborn Worker × M (Kata VM)
    HostPath 不可用 → 每个 Worker 挂载独立 EVS SSD PVC
    emptyDir 内存层（热数据）→ EVS SSD（冷数据）→ OBS（溢出）
    mTLS 双向认证 · per-app quota · Job 结束立即清理
    多租隔离：appId 路由 → 独立 partition，不同 appId 物理隔离

Celeborn Master（1 个 per tenant）
    租户路由 · Worker 注册 · 限流 · 不持久化用户数据

OBS Spill（EVS 满时）
    STS 临时 AK/SK（TTL=Job）· per-tenant prefix · IAM Policy 强隔离
```

### Ray（tenant-a-ns）

```
Ray Head Pod (Kata VM)
├── Ray GCS → ext. Redis HA（Head 重启不丢 Job 状态）
├── Ray Scheduler
└── metrics-exporter sidecar
    DaemonSet 不可用 → sidecar 方式采集指标

Ray Worker Pods × N (Kata VM)
    STS 临时 AK/SK · OBS Object Spill · Spec 预规整
    GPU Pod 支持（V100/T4）· 每 Pod 独立 Kata VM
    requests = limits（CCI 要求）· 镜像单层 ≤ 20G 解压

Image Snapshot Cache
    SWR 镜像预拉取到 CCI 节点本地缓存
    第三方 registry 不可用 → 只能用 SWR
    冷启动优化：镜像已在缓存，Pod 创建时跳过拉取

External Redis HA（GCS 外置）
    Head 宕机重启后从 Redis 恢复全部 Job 状态
    消除 Ray Head 单点

OBS Object Spill
    Ray Object Store 内存不足时 spill
    STS AK/SK · per-tenant prefix
    IAM Policy: tenant-a 的 AK 只能访问 /tenant-a/ 前缀
```

### 网络隔离模型（NetworkPolicy 不可用 → 三层替代）

| Layer | 机制 | 防护目标 |
|---|---|---|
| Layer 1 | Kata VM 硬边界 | 防容器逃逸 |
| Layer 2 | namespace 级 IAM | 防越权 |
| Layer 3 | mTLS per-job 证书 | 防仿冒 |

> CCI 官方文档明确：NetworkPolicy 不支持 Kata 容器，用三层组合替代

---

## 安全模型

七层防御 + NetworkPolicy 替代方案 + 平台组件零信任。

### 计算隔离层

#### Layer 1 · Kata VM（最强）— 容器逃逸防护

- 每个 Pod = 独立 Kata 轻量 VM，独立内核
- 宿主机资源对容器完全隔离（CCI 官方确认）
- 用户代码无法突破 VM 边界
- 替代传统 seccomp/AppArmor 的硬边界

#### Layer 2 · IAM + namespace — 租户权限隔离

- 每租户独立 CCI namespace，IAM 细粒度授权
- tenant-a 的 AK/SK 只能操作 tenant-a-ns
- OBS STS 临时凭证 TTL = Job 生命周期
- Celeborn mTLS + appId ACL 双层隔离

#### Layer 3 · mTLS per-job — 身份认证（替代 NetworkPolicy）

- Cert Manager 为每个 Job 签发短期证书
- Driver ↔ Executor ↔ Celeborn 全链路 mTLS
- 没有证书的连接在应用层被拒绝
- NetworkPolicy 不支持 Kata，此层为精细化补充

### 接入 & 平台组件安全层

#### Layer 4 · 接入安全 — Gateway 防线

- IAM Token 强制验证，无 Token 直接拒绝
- Spark conf 白名单：禁止 `spark.kubernetes.*` 系列参数
- 禁止 shell 注入，参数严格类型检查
- 限流：per-user QPS 限制，防资源滥用

#### Layer 5 · 镜像安全 — Image Pipeline

- 禁止用户直接推镜像，只能提交 `requirements.txt`
- Kaniko 在隔离 Pod 中构建，非 privileged
- Trivy 扫描：Critical 级漏洞阻断构建
- Cosign 签名，Controller 只允许运行签名镜像
- CCI 不支持第三方 registry，强制 SWR

#### Layer 6 · 平台组件零信任 — 东西向防护

- Job Gateway：只能写 CRD，不能调 CCI API
- Spark Operator：只能操作 SparkApplication + VK Pod
- Ray Controller：只能操作 RayJob + VK Pod
- Virtual Kubelet：只能调 CCI API，不能操作 CCE 真实节点
- 组件间全部 mTLS，禁止明文内部通信

### 威胁模型对照

| 威胁 | 攻击路径 | 防御机制 | 防御层 |
|---|---|---|---|
| 容器逃逸 | 用户代码利用内核漏洞 | Kata VM 独立内核，无法突破 | L1 |
| 跨租户数据读取 | 访问其他租户 OBS/Celeborn | STS AK/SK + IAM namespace + mTLS appId ACL | L2+L3 |
| 控制集群 | 用户代码调 K8S API | Driver 禁止访问 K8S API | L4 |
| 恶意镜像注入 | 推入带后门的镜像 | 禁止直接推镜像，强制 Pipeline + Trivy + Cosign | L5 |
| Shuffle 数据投毒 | 伪造 Celeborn 连接 | mTLS 双向认证 + per-job 证书 | L3 |
| 磁盘 DoS 攻击 | 写满 Celeborn 磁盘影响其他租户 | per-worker EVS PVC 隔离 + per-app shuffle quota | L2 |
| 平台组件被打 | Gateway RCE → 控制整个平台 | 零信任：Gateway 权限最小化，组件间 mTLS | L6 |
| 参数注入 | 恶意 Spark conf 操控 Executor | Gateway 白名单过滤，禁止 `spark.kubernetes.*` | L4 |

---

## 组件职责

### Job Gateway（自研）

**职责：** IAM Token 验证 · Spark conf 白名单过滤 · 参数类型检查 · 创建 SparkApplication / RayJob CRD · per-user 限流
**RBAC：** create SparkApplication/RayJob · 禁止调 CCI API / RDS

### Spark Operator（社区版不改）

**职责：** Watch SparkApplication CRD，管理状态机 · 调 CCE K8S API 创建 Driver Pod（VK 透明调度到 CCI）· 处理重试、超时、Job 生命周期
**RBAC：** watch/update SparkApplication · create Pod on VK node only

### Ray Controller（自研）

**职责：** Watch RayJob CRD · 创建 Head Pod（VK → CCI），注入 Redis GCS 配置 · 创建 Worker Pod 初始数量 · 管理 Ray 集群生命周期
**RBAC：** watch/update RayJob · create Pod on VK node only

### Virtual Kubelet HA（华为云插件）

**职责：** 以虚拟节点形式接入 CCE，透明转发 Pod 到 CCI · 双副本 + leader election · 需购买 VPCEP（CCI 2.0 要求）
**限制：** 仅支持 VPC 网络模式 CCE 集群 · 子网不能与 10.247.0.0/16 重叠
**RBAC：** 调 CCI API · 禁止操作 CCE 真实节点

### Spec Normalizer（自研 Webhook）

**职责：** MutatingAdmissionWebhook，拦截所有 Pod 创建 · 预对齐到 CCI 固定规格（2C4G / 4C8G / 8C16G…）· 强制 requests = limits
**适用范围：** 所有调度到 VK 节点的 Pod

### Signal Bridge Sidecar（自研 · 注入 Driver）

**职责：** 随 Driver Pod 以 Sidecar 部署 · 监听 Spark Dynamic Allocation 扩缩信号 · 将信号转发给 Spark Operator（HTTP）· 替代 Driver 直接调 K8S API

### Cert Manager（自研 / 社区）

**职责：** 每个 Job 启动时签发短期 mTLS 证书 · TTL 与 Job 生命周期绑定，自动吊销 · Driver、Executor、Celeborn 各持独立证书

### Celeborn RSS（社区 + 改造）

**职责：** 部署在 CCI（Kata VM）· 每个 Worker 挂载独立 EVS SSD PVC（替代 HostPath）· emptyDir 内存层作热数据缓冲 · mTLS + appId ACL 多租隔离 · per-app quota 防磁盘 DoS

### metrics-exporter Sidecar（替代 DaemonSet）

**职责：** 自动注入到 Driver / Head Pod · 暴露 JVM metrics + 资源用量 · Prometheus 通过 Pod IP scrape
**背景：** DaemonSet 在 CCI 不可用，粒度更精细（per-Job 级别指标）

---

## CCI 能力核查

| 能力项 | CCI 支持情况 | 严重度 | V4 方案处理 |
|---|---|---|---|
| **NetworkPolicy** | 不支持（Kata 容器限制，官方文档明确） | 严重 | 三层替代：Kata VM + IAM namespace + mTLS per-job |
| **HostPath 卷** | 不支持（安全性限制） | 严重 | Celeborn 改用 EVS SSD PVC (per-worker) + emptyDir 内存缓冲 |
| **DaemonSet** | 不支持 | 严重 | metrics-exporter sidecar 自动注入 Driver/Head Pod |
| **privileged 容器** | 不支持 | 中等 | SecurityContext capabilities 细粒度替代 |
| **Pod 规格规整** | 自动向上取整到固定规格（6C15G→8C16G） | 中等 | Spec Normalizer Webhook 提交前预对齐，成本可控 |
| **第三方 registry** | 不支持，只能用 SWR | 中等 | Image Pipeline 强制推送到 SWR |
| **镜像单层大小** | 解压后不超过 20G | 中等 | Spark/Ray 基础镜像分层优化，单层控制在限制内 |
| **VK 默认单副本** | 单实例无 HA | 中等 | 明确配置双副本 + leader election |
| **VPCEP 必须购买** | CCI 2.0 需要，按时计费 | 中等 | 纳入成本预算，提前购买规划 |
| **子网冲突约束** | 不能与 10.247.0.0/16 重叠 | 中等 | 网络规划阶段强制检查 |
| **emptyDir（内存型）** | 支持，从 Pod 内存分配 | — | Executor shuffle 写缓冲，建议设为内存的 50% |
| **EVS 云硬盘 PVC** | 支持（高 IO 型 / 通用型） | — | Celeborn Worker 持久化存储 |
| **SFS Turbo 挂载** | 支持 | — | 备选：EVS 性能不足时用 SFS Turbo |
| **OBS 挂载 / Spill** | 支持 | — | Ray Object Spill，Celeborn 溢出兜底 |
| **GPU Pod（V100/T4）** | 支持 | — | Ray GPU Worker 可用，CUDA 版本需匹配 |
| **InitContainer / Sidecar** | 支持 | — | Signal Bridge、metrics-exporter 均以 sidecar 注入 |
| **Service / DNS 互通** | 支持（需开启 Networking 选项） | — | Driver ↔ Executor 服务发现正常 |
| **IAM 细粒度权限** | 支持，namespace 级别 | — | 多租隔离核心机制 |

---

## POC 计划

> 按风险优先级排序，全部通过后才进入正式开发。**总工期：11 个工作日。**

### P0 — EVS PVC on CCI（2 天）

**背景：** HostPath 不可用，Celeborn 必须改用 EVS。EVS 延迟比本地 NVMe 高，直接决定 Celeborn 存储方案。
**通过标准：** EVS 高 IO 型单 Worker 顺序写 ≥ 200MB/s，P99 延迟 ≤ 5ms
**失败降级：** 改用 SFS Turbo，或多块 EVS 条带化

### P1 — Virtual Kubelet + CCI 网络连通性（2 天）

**背景：** 验证 CCE 安装 VK 插件后 Pod 调度到 CCI 的全链路网络，含 metadata endpoint 暴露情况。
**通过标准：** Driver Pod 能通过 Service DNS 找到 Executor，mTLS 握手成功，metadata endpoint 不可达
**失败降级：** metadata 可达则需加 iptables 规则封锁

### P2 — Spark Operator + VK 端到端（3 天）

**背景：** 提交真实 SparkApplication CRD，验证 Signal Bridge 信号传递、DA 扩缩、Celeborn 读写全链路。
**通过标准：** SparkPi 作业完整跑通，DA 触发 Executor 扩容并 idle 后缩容，Celeborn 成功读写
**失败降级：** 改为静态 Executor 数量（放弃 DA）

### P3 — Ray Head + GCS HA（2 天）

**背景：** 验证 GCS 外置 Redis 后 Head 宕机恢复能力。
**通过标准：** Head 重启 ≤ 30s，Job 状态完整恢复，Worker 重新注册后 Task 继续执行，无数据丢失
**失败降级：** 采用 Checkpoint 到 OBS 的 Job 级容错

### P4 — Spec Normalizer + 规格规整（1 天）

**背景：** 验证 Webhook 预对齐覆盖所有边界规格，成本计算可预测。
**通过标准：** 所有测试规格经 Normalizer 后 CCI 不再触发额外规整；成本误差 ≤ 5%

### P5 — 镜像预拉取 + 冷启动测量（1 天）

**背景：** 实测有缓存 vs 无缓存的 Pod 启动时间 P99，给出准确基线。
**通过标准：** 有镜像缓存时 Pod Running P99 ≤ 15s（含 Kata VM 启动开销）
**失败降级：** 评估 CCI ImageSnapshot 功能进一步加速
