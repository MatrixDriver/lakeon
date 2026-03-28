# Ray Notebook 热池（Warm Pool）设计

**日期**: 2026-03-28
**状态**: 已批准
**目标**: 将 Ray notebook kernel 启动时间从 ~30s+ 降到 ~13s

## 背景

CCI 上 Ray head pod 冷启动实测数据（2026-03-28）：

| 阶段 | 首次（冷） | 第二次（有缓存） |
|------|-----------|----------------|
| 调度（Pending → ContainerCreating） | 3.7s | 6.6s |
| 镜像拉取 + 容器创建 | 13.5s | 5.3s |
| Ray head 初始化 | 6.0s | 6.0s |
| **总耗时** | **23.3s** | **17.9s** |

- CCI 使用 `flex/` 公共镜像（`flex/ray:2.44-py311-data`），拉取仅 ~340ms，镜像预拉取 DaemonSet 无意义
- Ray head 初始化固定 ~6s，只有热池能消除
- 加上 worker 加入和 WebSocket exec，用户体感 ~30s+

## 架构

```
lakeon-api 启动
    │
    ├─ WarmPoolManager 初始化
    │   └─ 在 datalake-pool namespace 创建 2 个 idle Ray head pods
    │
用户创建 notebook session
    │
    ├─ 1. 从池中领取一个 idle head pod（标记 tenantId）   ~0s
    ├─ 2. 创建 N worker pods 加入 head 集群              ~12s
    └─ 3. WebSocket exec 连接 head pod                   ~1s
    │
    总耗时: ~13s（原先 ~30s+）
    │
池控制器检测到池位空缺
    └─ 异步补充新 idle head pod
    │
用户关闭 session
    └─ 销毁 head + workers，不回收（安全原因）
```

## 安全模型

- **用完即销毁，不回收** — Ray head 有状态（object store、GCS 元数据、用户安装的 pip 包、临时文件、env 变量、子进程），无法可靠重置，必须销毁后补新 pod
- **专用 namespace** — 热池 pod 统一放在 `datalake-pool`，不在租户 namespace 中
- **NetworkPolicy** — `datalake-pool` 默认 deny-all ingress，每个 head pod 只允许同 label 的 workers 和 lakeon-api pod 访问
- **不开 Ray dashboard** — 预创建 pod 去掉 `--dashboard-host`，减少攻击面

## 核心组件

### 1. WarmPoolManager（新类）

位置: `lakeon-api/src/main/java/com/lakeon/notebook/WarmPoolManager.java`

职责：
- `@PostConstruct` — 启动时扫描 `datalake-pool` 中已有 idle pods，纳入管理；不足 2 个则补充
- `@Scheduled(fixedDelay=10s)` — 定时巡检，补充销毁/失败的 pod 到目标数量
- `claimHead(tenantId)` — 原子操作：找到一个 idle pod → label 标记租户 → 返回 pod name + IP
- `releaseHead(podName)` — 直接删除 pod（触发巡检补充）

池配置：
- 池大小: 2（固定）
- namespace: `datalake-pool`
- idle 超时: 30min（防僵尸，自动销毁重建）

### 2. idle head pod 规格

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: warm-ray-head-{uuid8}
  namespace: datalake-pool
  labels:
    lakeon.io/pool: warm
    lakeon.io/status: idle        # idle → claimed
    # 分配后追加:
    # lakeon.io/tenant: {tenantId}
    # lakeon.io/session: {sessionId}
spec:
  nodeSelector:
    type: virtual-kubelet
  tolerations:
    - key: virtual-kubelet.io/provider
      operator: Exists
      effect: NoSchedule
  containers:
    - name: repl
      image: swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data
      command: ["bash", "-c", "ray start --head --port=6379 --num-cpus=0 && sleep infinity"]
      resources:
        requests: { cpu: "500m", memory: "2Gi" }
        limits: { cpu: "2", memory: "4Gi" }
  restartPolicy: Never
```

### 3. NotebookService 改造

`createRayNotebookCluster()` 修改逻辑：

1. 调用 `warmPool.claimHead(tenantId, sessionId)`
2. **如果有 idle pod**:
   - 拿到 pod name + IP
   - 在 `datalake-pool` namespace 创建 ConfigMap（repl_server.py）
   - 在 `datalake-pool` namespace 创建 worker pods，`ray start --address={headIP}:6379`
   - 跳过 head 创建 + head 轮询
3. **如果池空（降级）**:
   - 走原来的冷启动路径（在租户 namespace 创建 head pod）
   - 日志告警 `WARN WarmPool exhausted, falling back to cold start`

### 4. Namespace 初始化

`DatalakeNamespaceManager` 在 API 启动时确保 `datalake-pool` namespace 存在：
- 创建 namespace + label `virtual-node-affinity-injection=enabled`
- 复制 swr-secret + patch default SA（虽然 flex 镜像不需要，但保留以备自定义镜像）
- 创建 NetworkPolicy: deny-all ingress + allow egress

### 5. 生命周期

| 事件 | 动作 |
|------|------|
| API 启动 | 扫描已有 idle pods → 补充到 2 |
| 用户创建 session | claimHead → 标记 claimed → 创建 workers |
| 用户关闭 session | 删除 head + workers → 触发池补充 |
| idle pod 超过 30min | 自动删除重建（防僵尸） |
| pod 异常终止 | 巡检发现缺位 → 补充 |
| API 重启 | 重新扫描，不重复创建 |

## 预期效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| Head 启动 | ~18s | ~0s（池中领取） |
| Workers 加入 | ~12s | ~12s（不变） |
| WebSocket 连接 | ~1s | ~1s（不变） |
| **总启动时间** | **~30s+** | **~13s** |
| CCI 持续占用 | 0 | 2 个 idle pod（500m/2Gi 每个） |

## E2E 测试

1. **池初始化** — API 启动后 `datalake-pool` 中有 2 个 idle pods（status=idle label）
2. **热启动** — 创建 Ray session → head 从池中分配 → workers 加入 → 执行 Python 代码返回结果
3. **池补充** — 关闭 session → pod 销毁 → 30s 内池补充到 2
4. **冷启动降级** — 池空时创建 session 仍然正常工作（走原路径）
5. **租户隔离** — 两个租户分别获得不同 head pod，互不可达

## 不做的事

- **镜像预拉取 DaemonSet** — CCI 用 flex 公共镜像，已有缓存，无需预拉取
- **Worker 预热** — 规格组合太多（0-N workers × small/medium/large），无法预测
- **Pod 回收复用** — 安全风险高，重置不可靠
