# 数据湖 Ray Job + 多租安全加固设计

> 状态：待实现
> 日期：2026-03-26

---

## 背景

数据湖 Python Job 已端到端可用。现需：
1. 启用 Ray Job 支持（分布式计算）
2. 修复安全审计发现的 4 个关键漏洞
3. 优化作业启动速度

---

## 安全加固（4 项）

### 1. 跨租户数据集访问修复

**漏洞**：`DatalakeService.java:82` 用 `findById()` 查数据集，不验证 tenantId。租户 A 可以在 `input_dataset_ids` 中传租户 B 的数据集 ID。

**修复**：改为 `datasetRepository.findByIdAndTenantId(dsId, tenantId)`。

### 2. 网络隔离（NetworkPolicy）

**漏洞**：`datalake-tn-*` namespace 无 NetworkPolicy，CCI pod 之间可互通，租户 A 的 pod 可以连接租户 B 的 pod。

**修复**：创建 namespace 时注入 NetworkPolicy：

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: tenant-isolation
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  ingress:
  - from:
    - podSelector: {}  # 同 namespace 内放行（Ray head↔worker 需要）
  egress:
  - to: []  # 允许所有 egress（OBS、DNS、外网 pip install）
```

效果：同 namespace（同租户）内的 pod 可互通（Ray 集群需要），跨 namespace（跨租户）的 ingress 被拒绝。

### 3. 资源配额（ResourceQuota）

**漏洞**：无配额限制，一个租户可以提交大量作业耗尽 CCI 资源。

**修复**：创建 namespace 时注入 ResourceQuota：

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: tenant-quota
spec:
  hard:
    requests.cpu: "20"
    requests.memory: "40Gi"
    pods: "20"
```

### 4. 数据集租户校验

与第 1 项相同，一行代码修复。

---

## Ray Job 支持

### 基础设施

**KubeRay Operator**：安装到 `lakeon` namespace，运行在 CCE 固定节点。

```bash
# 推镜像到 SWR
docker pull quay.io/kuberay/operator:v1.3.0
docker tag ... swr.cn-north-4.myhuaweicloud.com/flex/kuberay-operator:v1.3.0
docker push ...

# Helm 安装
helm install kuberay-operator kuberay/kuberay-operator \
  -n lakeon \
  --set image.repository=swr.cn-north-4.myhuaweicloud.com/flex/kuberay-operator \
  --set image.tag=v1.3.0
```

**Ray 镜像**：推 `rayproject/ray:2.44.1-py311` 到 SWR `flex/ray:2.44-py311`。

### RayJobRunner 改造

当前实现基本完整，需调整：

1. **CCI 资源约束**：head/worker CPU limits >= 250m，requests = limits
2. **runtime_env 支持**：用户的 `requirements` 字段转为 Ray `runtime_env.pip` 列表，不再用 shell `pip install`
3. **inline_script 注入**：通过 ConfigMap 挂载到 head pod `/app/main.py`，entrypoint 设为 `python /app/main.py`
4. **namespace 创建**：复用 PythonJobRunner 的 namespace 创建逻辑（swr-secret、NetworkPolicy、ResourceQuota）
5. **OBS STS 凭据**：通过 runtime_env.env_vars 注入（pyobsfs 环境变量）

**RayJob CRD spec 示例**：
```yaml
apiVersion: ray.io/v1
kind: RayJob
metadata:
  name: ray-{jobId}
  namespace: datalake-tn-{tenantId}
spec:
  entrypoint: "python /app/main.py"
  shutdownAfterJobFinishes: true
  ttlSecondsAfterFinished: 300
  runtimeEnvYAML: |
    pip:
      - pandas
      - pyobsfs
    env_vars:
      OBS_ACCESS_KEY_ID: "..."
      OBS_SECRET_ACCESS_KEY: "..."
      OBS_SECURITY_TOKEN: "..."
      OBS_ENDPOINT: "..."
      DATASET_PATH: "obs://..."
      OUTPUT_PATH: "obs://..."
  rayClusterSpec:
    headGroupSpec:
      rayStartParams:
        dashboard-host: '0.0.0.0'
      template:
        spec:
          nodeSelector: {type: virtual-kubelet}
          tolerations: [{key: virtual-kubelet.io/provider, operator: Exists}]
          imagePullSecrets: [{name: swr-secret}]
          containers:
          - name: ray-head
            image: swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311
            resources:
              requests: {cpu: "2", memory: "4Gi"}
              limits: {cpu: "2", memory: "4Gi"}
            volumeMounts:
            - name: script-vol
              mountPath: /app/main.py
              subPath: main.py
          volumes:
          - name: script-vol
            configMap:
              name: dl-script-{jobId}
    workerGroupSpecs:
    - groupName: workers
      replicas: 2
      rayStartParams: {}
      template:
        spec:
          nodeSelector: {type: virtual-kubelet}
          tolerations: [{key: virtual-kubelet.io/provider, operator: Exists}]
          imagePullSecrets: [{name: swr-secret}]
          containers:
          - name: ray-worker
            image: swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311
            resources:
              requests: {cpu: "2", memory: "4Gi"}
              limits: {cpu: "2", memory: "4Gi"}
```

### 前端 — Ray 资源配置

`DatalakeJobNewResources.vue` 替换"即将推出"占位符：

**Ray 类型显示**：
- Head 配置：CPU 下拉（1/2/4 核）、内存下拉（2Gi/4Gi/8Gi）
- Worker 配置：副本数（1-8 滑块或下拉）、CPU（1/2/4 核）、内存（2Gi/4Gi/8Gi）

`DatalakeJobNew.vue` form 新增 `head` 和 `workers` 字段，提交时传给后端。

### E2E 测试

新增测试用例：
- `test_ray_job_submit_and_succeed`：提交简单 Ray job → 等待 SUCCEEDED
- `test_ray_job_cancel`：提交 Ray job → 取消 → CANCELLED
- `test_ray_job_with_workers`：提交带 2 worker 的 Ray job → SUCCEEDED

---

## 快启动优化

1. **Ray 镜像预热**：首次提交 Ray 作业时 CCI 拉镜像较慢（~30s），后续有缓存
2. **runtime_env pip 缓存**：Ray 在 head 节点缓存已安装的 pip 包，同一 session 内的 job 不重复安装
3. **Python Job 也可用 runtime_env 思路**：当前 shell `pip install` 每次都重装，后续可考虑用预构建镜像或 init container 缓存

---

## 影响范围

| 文件 | 改动 |
|------|------|
| `DatalakeService.java` | findById → findByIdAndTenantId |
| `PythonJobRunner.java` | 创建 namespace 时注入 NetworkPolicy + ResourceQuota（提取为公共方法） |
| `RayJobRunner.java` | CCI 约束、runtime_env、ConfigMap、STS 凭据、namespace 创建 |
| `DatalakeJobNewResources.vue` | Ray head/worker 资源配置 UI |
| `DatalakeJobNew.vue` | 新增 head/workers form 字段 |
| `datalake.ts` | 新增 head/workers 到 submit request |
| `test_datalake.py` | 新增 Ray job E2E 测试 |
| CCE 集群 | 安装 KubeRay Operator |
| SWR | 推 Ray 镜像 + KubeRay Operator 镜像 |

---

## 非目标

- GPU / 微调支持
- Ray Dashboard 外部暴露
- Ray 集群持久化（每个 Job 创建新集群）
- 自定义 Ray runtime_env（如 working_dir、py_modules）
