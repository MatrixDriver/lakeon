下面是一个可以直接用于“喂给 AI 做开发设计”的**完整 Markdown 架构文档**，包含：

- ✅ Serverless Spark + Ray 统一架构  
- ✅ 关键组件职责  
- ✅ 调度/扩缩模型  
- ✅ RSS + Ray spilling 设计  
- ✅ 全链路安全模型（重点）  
- ✅ SVG 架构图（可直接渲染）

---

# 🧠 Serverless Spark + Ray 统一计算平台架构设计

---

# 1. 🎯 设计目标

- ✅ 支持 **Spark + Ray 双引擎**
- ✅ 完全 Serverless（无用户集群）
- ✅ 多租户强隔离（防逃逸 / 防越权）
- ✅ 弹性扩缩（按需计费）
- ✅ 最大化复用社区能力
- ✅ 最小化自研复杂度

---

# 2. 🏗️ 总体架构（SVG）

```svg
<svg width="1000" height="720" xmlns="http://www.w3.org/2000/svg">

  <!-- User Layer -->
  <rect x="300" y="20" width="400" height="50" fill="#e3f2fd" stroke="#1e88e5"/>
  <text x="500" y="50" text-anchor="middle" font-size="14">Users / SDK / API</text>

  <!-- Gateway -->
  <rect x="300" y="90" width="400" height="60" fill="#e8f5e9" stroke="#43a047"/>
  <text x="500" y="125" text-anchor="middle" font-size="14">Unified Job Gateway (Auth / Quota / API)</text>

  <!-- Control Plane -->
  <rect x="150" y="180" width="700" height="200" fill="#fff3e0" stroke="#fb8c00"/>
  <text x="500" y="200" text-anchor="middle" font-size="14">Control Plane (CCE)</text>

  <!-- Spark Controller -->
  <rect x="200" y="220" width="250" height="60" fill="#ffe0b2" stroke="#ef6c00"/>
  <text x="325" y="255" text-anchor="middle" font-size="12">Spark Controller (Forked Operator)</text>

  <!-- Ray Controller -->
  <rect x="550" y="220" width="250" height="60" fill="#ffe0b2" stroke="#ef6c00"/>
  <text x="675" y="255" text-anchor="middle" font-size="12">Ray Controller</text>

  <!-- Autoscaler -->
  <rect x="350" y="300" width="300" height="60" fill="#fff8e1" stroke="#f9a825"/>
  <text x="500" y="335" text-anchor="middle" font-size="12">Unified Autoscaler</text>

  <!-- Data Plane -->
  <rect x="100" y="420" width="800" height="260" fill="#ede7f6" stroke="#5e35b1"/>
  <text x="500" y="440" text-anchor="middle" font-size="14">Data Plane (CCI - Kata Containers)</text>

  <!-- Spark -->
  <rect x="140" y="460" width="320" height="180" fill="#d1c4e9" stroke="#512da8"/>
  <text x="300" y="480" text-anchor="middle" font-size="12">Spark Cluster</text>
  <text x="300" y="510" text-anchor="middle" font-size="11">Driver (Kata VM)</text>
  <text x="300" y="530" text-anchor="middle" font-size="11">Executor x N (Kata VM)</text>

  <!-- Ray -->
  <rect x="540" y="460" width="320" height="180" fill="#d1c4e9" stroke="#512da8"/>
  <text x="700" y="480" text-anchor="middle" font-size="12">Ray Cluster</text>
  <text x="700" y="510" text-anchor="middle" font-size="11">Head (Kata VM)</text>
  <text x="700" y="530" text-anchor="middle" font-size="11">Worker x N (Kata VM)</text>

  <!-- RSS -->
  <rect x="180" y="660" width="250" height="40" fill="#fce4ec" stroke="#c2185b"/>
  <text x="305" y="685" text-anchor="middle" font-size="11">Remote Shuffle (Celeborn)</text>

  <!-- OBS -->
  <rect x="600" y="660" width="250" height="40" fill="#e0f7fa" stroke="#00838f"/>
  <text x="725" y="685" text-anchor="middle" font-size="11">OBS (Ray Spill / Data)</text>

</svg>
```

---

# 3. 🧩 核心组件设计

## 3.1 Gateway（统一入口）

职责：

- 认证（IAM / Token）
- 参数校验（防注入）
- 多租户隔离
- Job 路由（Spark / Ray）

---

## 3.2 Spark Controller（Fork Operator）

✅ 复用能力：

- 状态机
- 重试机制
- 作业生命周期

❗改造：

- CRD → DB
- Driver/Executor → 全由 Controller 创建（CCI）
- 不依赖 Driver 调 K8S API

---

## 3.3 Ray Controller

职责：

- 创建 Ray Head（CCI）
- 创建 Worker（CCI）
- 管理 Ray 集群生命周期
- 替代 Ray Operator

---

## 3.4 Unified Autoscaler（关键）

统一接口：

```
scale(job_id, engine, desired_workers)
```

### Spark

- 输入：
  - executor idle
  - stage backlog
- 输出：
  - Executor 数量

### Ray

- 输入：
  - pending tasks
  - cluster resources
- 输出：
  - Worker 数量

---

# 4. 🔄 数据层设计

---

## 4.1 Spark：Remote Shuffle Service（必须）

### 组件：

- Apache Celeborn（推荐）

### 架构：

```
Executor → RSS → Executor
```

### 多租隔离：

- appId / tenantId 隔离
- mTLS / token
- 独立目录或磁盘

---

## 4.2 Ray：Object Spilling

### 配置：

```
object_spilling_config → OBS
```

### 行为：

```
内存不足 / 节点销毁
→ spill 到 OBS
```

---

# 5. 🔐 安全架构（重点）

---

## 5.1 威胁模型

| 风险 | 来源 |
|---|---|
| 容器逃逸 | 用户代码 |
| 数据泄露 | shuffle / object |
| 横向攻击 | Pod 网络 |
| 权限滥用 | K8S API |
| 恶意镜像 | 用户提交 |

---

## 5.2 7层防御体系

---

### ✅ Layer 1：接入安全

- IAM + Token
- API Gateway 限流

---

### ✅ Layer 2：参数安全

- Spark conf 白名单
- 禁止：
  - `spark.kubernetes.*`
  - shell 注入

---

### ✅ Layer 3：镜像安全

- ❗禁止用户自定义镜像
- ✅ 白名单镜像（SWR）
- ✅ 漏洞扫描

---

### ✅ Layer 4：K8S 隔离

- 每租户独立 Namespace
- 独立 ServiceAccount
- 最小权限（Executor 无 API 权限）

---

### ✅ Layer 5：Pod 安全

强制：

```
runAsNonRoot: true
readOnlyRootFilesystem: true
allowPrivilegeEscalation: false
capabilities.drop: ALL
```

---

### ✅ Layer 6：网络隔离

NetworkPolicy：

- ✅ 只允许：
  - Driver ↔ Executor
  - Head ↔ Worker
- ❌ 禁止：
  - metadata service
  - K8S API
  - 跨租户通信

---

### ✅ Layer 7：VM级隔离（核心）

CCI + Kata：

```
每个 Pod = 独立 VM
```

防护：

- ✅ 容器逃逸无效
- ✅ 内核隔离
- ✅ 租户隔离

---

# 6. ⚠️ 关键安全点（必须落实）

---

## ❗1. Driver / Head 必须在 CCI（Kata）

原因：

- 会执行用户代码
- 是最高风险点

---

## ❗2. 禁止 Pod 访问 K8S API

否则：

```
用户代码 → 控制集群
```

---

## ❗3. RSS 数据隔离

防：

- 越权读取
- 数据投毒

---

## ❗4. OBS 权限隔离

- 临时 AK/SK
- 按租户 bucket

---

## ❗5. Autoscaler 不信任用户

- 不允许用户控制扩缩策略
- 防资源攻击

---

# 7. 🧠 统一抽象模型

```
            Serverless Compute Platform
                     │
        ┌────────────┴────────────┐
        │                         │
   Spark Engine              Ray Engine
        │                         │
   Shuffle (RSS)            Object Store
        │                         │
        └────────────┬────────────┘
                     │
              External Storage
```

---

# ✅ 最终总结

> ✅ 一个平台（统一入口 + 安全 + 多租）  
> ✅ 两个引擎（Spark / Ray 独立控制）  
> ✅ 一个数据外部化策略（Shuffle / Object）  
> ✅ 一个统一扩缩层（Autoscaler）  
> ✅ 一个核心安全基石（Kata VM 隔离）

---

如果你下一步要落地，我建议优先做这三块（ROI最高）：

1. ✅ Spark + RSS（先跑通闭环）  
2. ✅ Ray + spilling（验证模型）  
3. ✅ Autoscaler（统一能力的关键）

---

如果你需要，我可以再给你一版：

👉 **“最小可落地版本（MVP）架构 + 组件拆分 + repo 结构”**

