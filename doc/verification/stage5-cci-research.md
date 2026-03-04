# Stage 5：CCI 弹性计算研究报告

> 日期：2026-03-04
> 状态：**阻塞** — 需要 VPC 终端节点才能继续

## 目标

验证混合部署方案的可行性：有状态组件（pageserver / safekeeper / storage-broker / proxy / lakeon-api）运行在 CCE，compute 节点弹性调度到 CCI（云容器实例），实现 serverless 计算层。

## 核心验证项

| # | 验证项 | 结果 |
|---|--------|------|
| 1 | CCI 上 `compute_ctl` 的 `setrlimit(CORE, INFINITY)` 是否通过 | **未验证**（镜像拉取失败） |
| 2 | CCI Pod 到 VPC 内网的网络连通性 | **未验证** |
| 3 | CCE bursting 插件 → CCI 调度链路 | **通过** |
| 4 | CCI Pod 拉取 SWR 镜像 | **失败** |

## 环境

- CCE 集群：cn-north-4，Standard，2 节点（s6.xlarge.2）
- CCE 弹性插件：CCE Cloud Bursting Engine for CCI（virtual-kubelet 1.5.75）
- SWR 组织：`lakeon`（已设为公开）

## 实施过程

### 1. 安装 CCE 弹性到 CCI 插件

在 CCE 控制台 → 插件管理 → 安装「CCE 突发弹性引擎（对接 CCI）」。安装后自动创建：

- `bursting-node` 虚拟节点
- virtual-kubelet Deployment（kube-system 命名空间）
- resource-syncer、webhook、profile-controller 等组件

### 2. CCI 调度方式

通过 Pod label 控制调度目标：

```yaml
metadata:
  labels:
    bursting.cci.io/burst-to-cci: "enforce"  # 强制调度到 CCI
```

### 3. 镜像拉取问题排查

#### 尝试 1：使用 `swr-secret`（手动创建的 docker-registry secret）
```yaml
imagePullSecrets:
- name: swr-secret
```
**结果**：`ImagePullBackOff`

#### 尝试 2：使用 `default-secret`（CCE 自动创建的 SWR 凭证）
```yaml
imagePullSecrets:
- name: default-secret
```
**结果**：`ErrImagePull`

#### 尝试 3：使用 `imagepull-secret`（CCI 文档要求的固定名称）

手动从 `default-secret` 复制创建：
```bash
kubectl create secret docker-registry imagepull-secret \
  --from-file=.dockerconfigjson=<default-secret-content> \
  -n lakeon-compute
```
**结果**：`ImagePullBackOff`

#### 尝试 4：不带 `imagePullSecrets`（SWR 镜像已设为公开）
**结果**：`ErrImagePull`

#### 尝试 5：使用 Docker Hub 公开镜像 `busybox:1.36`
**结果**：`ImagePullBackOff`（CCI 在中国区无法访问 Docker Hub）

### 4. 根因定位

通过 `kubectl get pod -o jsonpath='{.status}'` 获取 CCI 侧详细错误：

```
rpc error: code = Unknown desc = failed to pull and unpack image
"swr.cn-north-4.myhuaweicloud.com/lakeon/busybox:1.36":
failed to resolve reference: failed to do request:
Head "https://swr.cn-north-4.myhuaweicloud.com/v2/lakeon/busybox/manifests/1.36":
dial tcp 100.125.40.98:443: i/o timeout
```

**根因：CCI Pod 网络不通 SWR**。CCI 容器实例访问 `swr.cn-north-4.myhuaweicloud.com`（解析到 `100.125.40.98:443`）时 TCP 连接超时。

### 5. CCE 节点 ulimit 基线对比

在普通 CCE 节点上运行 busybox 容器，确认 ulimit 值：

```
core file size (blocks)    (-c) unlimited    ← compute_ctl 需要此值
data seg size (kb)         (-d) unlimited
file size (blocks)         (-f) unlimited
open files                 (-n) 1048576
max user processes         (-u) 1048576
virtual memory (kb)        (-v) unlimited
```

CCE 节点的 `core file size = unlimited` 满足 `compute_ctl` 的 `setrlimit(CORE, INFINITY)` 要求。CCI 侧尚未验证。

## 阻塞项

### VPC 终端节点（VPC Endpoint）

CCI 弹性到 CCI 的文档前置条件明确要求购买 VPC 终端节点。CCI Pod 运行在独立的 Kata VM 中，网络访问云服务（SWR、OBS 等）需要通过 VPC Endpoint 路由。

**需要创建的终端节点：**

| 服务 | 终端节点 | 用途 |
|------|----------|------|
| SWR | `com.myhuaweicloud.cn-north-4.swr` | 拉取容器镜像 |
| OBS | `com.myhuaweicloud.cn-north-4.obs` | pageserver 远程存储（如果 compute 直接访问） |
| DNS | `com.myhuaweicloud.cn-north-4.dns` | 域名解析（可能需要） |

**操作步骤：**
1. VPC 控制台 → VPC 终端节点 → 购买终端节点
2. 服务类别选择「云服务」
3. 选择 SWR 服务
4. VPC 和子网选择与 CCE 集群相同的网络
5. 确认安全组放通 443 端口

## 其他发现

### CCI 限制（影响 compute 部署）

| 限制项 | CCI 约束 | compute Pod 需求 | 影响 |
|--------|----------|------------------|------|
| 特权模式 | 不支持 | 不需要 | 无影响 |
| hostNetwork | 不支持 | 不需要 | 无影响 |
| limits = requests | 必须相等 | 可适配 | 需调整 Pod spec |
| ulimit | 待验证 | `core = unlimited` | **关键路径** |
| 镜像来源 | 仅 SWR | 已推送到 SWR | 需 VPC Endpoint |

### CCE bursting 插件行为

- Pod 创建/删除通过 virtual-kubelet 代理到 CCI API v2
- `imagePullSecrets` 会传递给 CCI，但 CCI 的凭证机制与 CCE 不同
- CCI Pod 获得真实 VPC IP（如 `192.168.0.x`），理论上可与 CCE Pod 网络互通
- Pod 事件（Events）在 CCE 侧信息有限，需通过 `pod.status.containerStatuses[].state.waiting.message` 获取 CCI 侧详细错误

## 下一步

1. **购买 VPC 终端节点**（SWR），解决镜像拉取问题
2. 重新部署 busybox 到 CCI，验证 `ulimit -c` 值
3. 若 ulimit 通过，部署 `compute-node-v17` 镜像，验证 `compute_ctl` 启动
4. 验证 CCI Pod → pageserver / safekeeper 的网络连通性
5. 完整集成测试（compute 在 CCI，其余在 CCE）
