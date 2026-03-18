# 通用 Job 框架设计

> 2026-03-18 | 状态：已批准

## 目标

为 Knowledge Pipeline 和数据飞轮提供通用的异步 Job 执行基础设施。基于 CCE 弹性节点池，API 直接编排 Job Pod。

## 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 与 Import 系统的关系 | 并行共存，不重构 Import | Import 已稳定（31/31 测试通过），不碰 |
| 回调机制 | Pod 主动 HTTP 回调 + 孤儿检测兜底 | 已有成熟模式，实时性好 |
| 参数传递 | ConfigMap（params）+ 环境变量（ID/凭据） | params 可能较大且含敏感信息，不宜暴露在 env/kubectl describe |
| 租户关联 | 只关联 tenant_id，不关联 database_id | 保持框架通用，具体 Job 类型在 params 里放关联 ID |
| Console UI | 不在框架层做 | 等具体 Job 类型（Knowledge Pipeline）时再设计对应 UI |

## 架构

### 包结构

```
com.lakeon.job/
├── JobEntity.java              # JPA 实体
├── JobRepository.java          # 数据访问
├── JobType.java                # 枚举: DOCUMENT_PARSE, EMBEDDING, EXPORT_PARQUET, TRAINING
├── JobStatus.java              # 枚举: PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED
├── JobService.java             # 提交/查询/取消
├── JobPodManager.java          # K8s Pod 生命周期
├── JobCallbackController.java  # 回调端点
└── JobScheduledTasks.java      # 孤儿检测、超时清理
```

与现有 Import 系统（`com.lakeon.service.ImportService` + `com.lakeon.k8s.ImportJobPodManager`）并行，互不依赖。

### 数据模型

表名 `jobs`：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(32) | `job_` + 12 char（@PrePersist） |
| tenant_id | VARCHAR(32) | 租户 ID，必填 |
| type | VARCHAR(32) | JobType 枚举值 |
| status | VARCHAR(16) | JobStatus 枚举值 |
| params | TEXT | 输入参数 JSON |
| result | TEXT | 输出结果 JSON（回调写入） |
| error | TEXT | 失败原因 |
| callback_token | VARCHAR(64) | 回调验证 UUID（@PrePersist 生成） |
| pod_name | VARCHAR(128) | K8s Pod 名称 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 最后更新 |
| started_at | TIMESTAMP | Pod 开始运行 |
| completed_at | TIMESTAMP | 完成时间 |

生命周期钩子：`@PrePersist` 生成 id + callbackToken，`@PreUpdate` 自动更新 updatedAt。

索引：
- `idx_jobs_tenant_id` on `tenant_id`
- `idx_jobs_status` on `status`
- `idx_jobs_type_status` on `(type, status)`

### REST API

```
POST   /api/v1/jobs                  # 提交 Job (需认证)
GET    /api/v1/jobs/{id}             # 查询单个
GET    /api/v1/jobs?type=&status=    # 列表（过滤，分页）
POST   /api/v1/jobs/{id}/cancel      # 取消（删 Pod + 标记 CANCELLED）
POST   /api/v1/jobs/{id}/callback    # Pod 回调（内部端点，需 token 验证）
```

#### POST /api/v1/jobs

请求：
```json
{
  "type": "DOCUMENT_PARSE",
  "params": {
    "obs_key": "tenant/xxx/uploads/doc.pdf",
    "database_id": "db_abc123"
  }
}
```

响应：
```json
{
  "id": "job_a1b2c3d4",
  "type": "DOCUMENT_PARSE",
  "status": "PENDING",
  "createdAt": "2026-03-18T10:00:00Z"
}
```

#### POST /api/v1/jobs/{id}/callback

回调需携带 `token`（创建 Job 时生成的 UUID），用于验证调用方身份。

进度上报（可选，Pod 运行期间多次调用）：
```json
{
  "token": "uuid-...",
  "status": "RUNNING",
  "result": {
    "progress": 0.75,
    "message": "Processing page 150/200"
  }
}
```

完成：
```json
{
  "token": "uuid-...",
  "status": "SUCCEEDED",
  "result": {
    "chunks_count": 42,
    "embedding_dimensions": 1024
  }
}
```

失败：
```json
{
  "token": "uuid-...",
  "status": "FAILED",
  "error": "Marker parse failed: unsupported format"
}
```

### 数据流

```
1. POST /api/v1/jobs {type, params}
   → JobService 创建 JobEntity (PENDING)，生成 callbackToken (UUID)
   → TransactionSynchronization 事务提交后异步启动

2. JobPodManager 创建:
   a) ConfigMap: job-{job_id}-params（params JSON，Pod 完成后删除）
   b) Pod:
      - 镜像: 按 JobType 从配置映射
      - nodeSelector: lakeon/role=compute
      - /dev/shm: emptyDir Memory 2Gi（Ray 需要）
      - 环境变量: JOB_ID, JOB_TYPE, JOB_CALLBACK_URL, JOB_CALLBACK_TOKEN
      - 环境变量: OBS_ACCESS_KEY/SECRET_KEY/ENDPOINT (从 Secret 引用)
      - volumeMount: ConfigMap 挂载到 /etc/job/params.json
      - restartPolicy: Never
      - labels: app=lakeon-job, lakeon.io/job-id, lakeon.io/tenant-id

3. Pod 执行完成:
   → POST callback URL with {token, status, result} 或 {token, status, error}
   → JobCallbackController 验证 token 匹配
   → JobService 更新 status + result/error + completedAt
   → 删除 Pod + ConfigMap

4. 孤儿检测 (@Scheduled 每 60s):
   → 查询 status IN (PENDING, RUNNING) 的 Job
   → PENDING 超过 5min 且无对应 Pod → 标记 FAILED
   → RUNNING 超过 timeout-minutes 且 Pod 已终止但未回调 → 标记 FAILED
   → 清理残留 Pod + ConfigMap
```

### Pod Spec

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: job-{job_id}
  namespace: lakeon-compute
  labels:
    app: lakeon-job
    lakeon.io/job-id: job_a1b2c3d4
    lakeon.io/tenant-id: tenant_xxx
    lakeon.io/job-type: document-parse     # type.name().toLowerCase().replace("_", "-")
spec:
  nodeSelector:
    lakeon/role: compute
  containers:
    - name: job
      image: <按 JobType 配置>
      env:
        - name: JOB_ID
          value: job_a1b2c3d4
        - name: JOB_TYPE
          value: DOCUMENT_PARSE
        - name: JOB_CALLBACK_URL
          value: http://lakeon-api.lakeon.svc.cluster.local:8090/api/v1/jobs/job_a1b2c3d4/callback
        - name: JOB_CALLBACK_TOKEN
          value: <per-job UUID>
        # OBS 凭据从 Secret 引用
        - name: OBS_ACCESS_KEY
          valueFrom:
            secretKeyRef:
              name: obs-credentials
              key: access-key
        - name: OBS_SECRET_KEY
          valueFrom:
            secretKeyRef:
              name: obs-credentials
              key: secret-key
        - name: OBS_ENDPOINT
          value: https://obs.cn-north-4.myhuaweicloud.com
      resources:
        requests:
          cpu: <按 JobType 配置>
          memory: <按 JobType 配置>
        limits:
          cpu: <同 requests>
          memory: <同 requests>
      volumeMounts:
        - name: dshm
          mountPath: /dev/shm
        - name: job-params
          mountPath: /etc/job
          readOnly: true
  volumes:
    - name: dshm
      emptyDir:
        medium: Memory
        sizeLimit: 2Gi
    - name: job-params
      configMap:
        name: job-{job_id}-params
  restartPolicy: Never
  imagePullSecrets:
    - name: swr-secret
```

### 配置

`application.yml` 新增：

```yaml
lakeon:
  job:
    timeout-minutes: 30
    pending-timeout-minutes: 5
    orphan-check-interval-ms: 60000
    types:
      document-parse:
        image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge:latest
        cpu: "2"
        memory: "4Gi"
      embedding:
        image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge:latest
        cpu: "2"
        memory: "4Gi"
      export-parquet:
        image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-data:latest
        cpu: "2"
        memory: "4Gi"
      training:
        image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-training:latest
        cpu: "4"
        memory: "8Gi"
```

### 前置条件

以下资源必须在 `lakeon-compute` namespace 中预先存在：
- `obs-credentials` Secret（OBS AK/SK）
- `swr-secret` imagePullSecret（SWR 镜像拉取凭据）
- `lakeon-api` ServiceAccount 需要 RBAC 权限：Pod + ConfigMap 的 create/delete/get/list

### 不做的事

- 不重构 Import 系统
- 不做 Console UI（等 Knowledge Pipeline MVP 时做）
- 不做 Job 依赖链 / DAG 编排（Phase 1 单 Pod 够用）
- 不做重试机制（失败后用户手动重新提交）
- 不做 CCI 支持（Phase 3 再加）
