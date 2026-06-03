# Storage-Controller 部署 (Phase 2) 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 部署 Neon storage-controller，pageserver 重启后自动 re-attach 全量 tenant，彻底解决 tenant 静默丢失问题

**Architecture:** storage-controller 使用 Neon 镜像内已有的 `storage_controller` 二进制，连接 lakeon 控制面 PG 存储 tenant→pageserver 分配表。pageserver 启动时调 `POST /upcall/v1/re-attach` 获取完整 tenant 清单。lakeon-api 的 L2 reconcile 保留作为兜底。

**Tech Stack:** Neon storage_controller (Rust binary), PostgreSQL (Diesel migrations), Helm, K8s

**前置条件：** Phase 1 已完成（L2 reconcile + L3 SRE 监控已上线）

---

## 文件结构

```
deploy/helm/lakeon/templates/
  deployment-storage-controller.yaml     # 新建：K8s Deployment
  service-storage-controller.yaml        # 新建：K8s Service
  configmap-pageserver.yaml              # 修改：control_plane_api 指向 storage-controller
```

---

### Task 1: 创建 storage-controller 数据库 schema

**Files:**
- 无代码文件，直接在 PG 上执行 SQL

storage-controller 使用 Diesel ORM 内建 migration，但我们的 PG 是共享的控制面数据库，需要手动建表以避免 Diesel migration runner 和 lakeon-api JPA 冲突。直接用与 Neon 源码一致的 DDL。

- [ ] **Step 1: 连接控制面 PG 创建表**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl run pg-sc-init --rm -i --restart=Never \
  --image=postgres:17-alpine --env=PGPASSWORD=Admin@2026 -n lakeon -- \
  psql -h 192.168.0.176 -U lakeon -d lakeon -c "
-- diesel migration tracking table
CREATE TABLE IF NOT EXISTS __diesel_schema_migrations (
  version VARCHAR(50) PRIMARY KEY NOT NULL,
  run_on TIMESTAMP NOT NULL DEFAULT NOW()
);

-- tenant_shards (core table)
CREATE TABLE IF NOT EXISTS tenant_shards (
  tenant_id VARCHAR NOT NULL,
  shard_number INTEGER NOT NULL,
  shard_count INTEGER NOT NULL,
  PRIMARY KEY(tenant_id, shard_number, shard_count),
  shard_stripe_size INTEGER NOT NULL,
  generation INTEGER,
  generation_pageserver BIGINT,
  placement_policy VARCHAR NOT NULL,
  splitting SMALLINT NOT NULL,
  config TEXT NOT NULL,
  scheduling_policy VARCHAR NOT NULL DEFAULT '\"Active\"',
  preferred_az_id VARCHAR
);
CREATE INDEX IF NOT EXISTS tenant_shards_tenant_id ON tenant_shards (tenant_id);

-- nodes (pageserver registry)
CREATE TABLE IF NOT EXISTS nodes (
  node_id BIGINT PRIMARY KEY NOT NULL,
  scheduling_policy VARCHAR NOT NULL,
  listen_http_addr VARCHAR NOT NULL,
  listen_http_port INTEGER NOT NULL,
  listen_pg_addr VARCHAR NOT NULL,
  listen_pg_port INTEGER NOT NULL,
  availability_zone_id VARCHAR,
  listen_https_port INTEGER
);

-- Mark all migrations as applied so Diesel won't try to re-run them
INSERT INTO __diesel_schema_migrations (version) VALUES
  ('00000000000000'),
  ('2024-01-07-211257'),
  ('2024-01-07-212945'),
  ('2024-02-29-094122'),
  ('2024-03-18-184429'),
  ('2024-03-27-133204'),
  ('2024-08-23-170149'),
  ('2024-08-27-184400'),
  ('2024-09-05-104500'),
  ('2025-02-11-144848')
ON CONFLICT DO NOTHING;
"
```

预期：所有表创建成功，无报错。

- [ ] **Step 2: 预填充当前 tenant 数据**

从 `database_instances` 表导入所有活跃 tenant 到 `tenant_shards`，node_id=1（我们的单 pageserver）：

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl run pg-sc-seed --rm -i --restart=Never \
  --image=postgres:17-alpine --env=PGPASSWORD=Admin@2026 -n lakeon -- \
  psql -h 192.168.0.176 -U lakeon -d lakeon -c "
-- Register our pageserver as node 1
INSERT INTO nodes (node_id, scheduling_policy, listen_http_addr, listen_http_port, listen_pg_addr, listen_pg_port, availability_zone_id)
VALUES (1, '\"Active\"', 'pageserver', 9898, 'pageserver', 6400, 'cn-north-4a')
ON CONFLICT (node_id) DO NOTHING;

-- Import all active tenants from database_instances
INSERT INTO tenant_shards (tenant_id, shard_number, shard_count, shard_stripe_size, generation, generation_pageserver, placement_policy, splitting, config, scheduling_policy)
SELECT
  neon_tenant_id,
  0,           -- shard_number (single shard)
  1,           -- shard_count
  0,           -- shard_stripe_size (not sharded)
  1,           -- generation
  1,           -- generation_pageserver (node_id=1)
  '{\"Attached\": 0}',  -- placement_policy: AttachedSingle
  0,           -- splitting: not splitting
  '{}',        -- config: default
  '\"Active\"' -- scheduling_policy
FROM database_instances
WHERE deleted_at IS NULL AND neon_tenant_id IS NOT NULL
ON CONFLICT DO NOTHING;
"
```

预期：~21 行插入到 `tenant_shards`，1 行到 `nodes`。

- [ ] **Step 3: 验证数据**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl run pg-sc-check --rm -i --restart=Never \
  --image=postgres:17-alpine --env=PGPASSWORD=Admin@2026 -n lakeon -- \
  psql -h 192.168.0.176 -U lakeon -d lakeon -c "
SELECT count(*) as tenant_count FROM tenant_shards;
SELECT * FROM nodes;
"
```

预期：tenant_count = 21（与 Phase 1 手动 attach 的数量一致），nodes 有 1 行 (node_id=1)。

- [ ] **Step 4: Commit（无代码文件，跳过）**

---

### Task 2: 创建 storage-controller K8s Deployment + Service

**Files:**
- Create: `deploy/helm/lakeon/templates/deployment-storage-controller.yaml`
- Create: `deploy/helm/lakeon/templates/service-storage-controller.yaml`

- [ ] **Step 1: 创建 Deployment**

```yaml
# deploy/helm/lakeon/templates/deployment-storage-controller.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: storage-controller
  namespace: {{ .Values.global.namespace }}
  labels:
    app: storage-controller
    {{- include "lakeon.labels" . | nindent 4 }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: storage-controller
  template:
    metadata:
      labels:
        app: storage-controller
    spec:
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: storage-controller
          image: "{{ .Values.neon.image.repository }}:{{ .Values.neon.image.tag }}"
          imagePullPolicy: {{ .Values.neon.image.pullPolicy }}
          command: ["storage_controller"]
          args:
            - "--dev"
            - "--listen"
            - "0.0.0.0:1234"
            - "--database-url"
            - "$(DATABASE_URL)"
          env:
            - name: DATABASE_URL
              value: "postgresql://lakeon:Admin@2026@metadata-db:5432/lakeon"
          ports:
            - containerPort: 1234
              name: http
          livenessProbe:
            httpGet:
              path: /status
              port: http
            initialDelaySeconds: 5
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /status
              port: http
            initialDelaySeconds: 3
            periodSeconds: 5
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 256Mi
```

注意：
- 使用和 pageserver 相同的 Neon 镜像（`storage_controller` 二进制已在里面）
- `--dev` 跳过 JWT 认证（集群内网，和 pageserver/safekeeper 一样没配 auth）
- `DATABASE_URL` 指向集群内 metadata-db Service（和 lakeon-api 用同一个 PG）

- [ ] **Step 2: 创建 Service**

```yaml
# deploy/helm/lakeon/templates/service-storage-controller.yaml
apiVersion: v1
kind: Service
metadata:
  name: storage-controller
  namespace: {{ .Values.global.namespace }}
  labels:
    app: storage-controller
    {{- include "lakeon.labels" . | nindent 4 }}
spec:
  selector:
    app: storage-controller
  ports:
    - port: 1234
      targetPort: 1234
      name: http
```

- [ ] **Step 3: Commit**

```bash
git add deploy/helm/lakeon/templates/deployment-storage-controller.yaml deploy/helm/lakeon/templates/service-storage-controller.yaml
git commit -m "feat(deploy): add storage-controller Deployment and Service"
```

---

### Task 3: 部署 storage-controller 并验证

**Files:** 无代码改动，运维操作。

- [ ] **Step 1: 用 Helm 或 kubectl apply 部署**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl apply -f deploy/helm/lakeon/templates/deployment-storage-controller.yaml
KUBECONFIG=~/.kube/cce-lakeon-config kubectl apply -f deploy/helm/lakeon/templates/service-storage-controller.yaml
```

注意：因为模板用了 Helm 变量，可能需要先 `helm template` 渲染或手动替换变量后 apply。如果 Helm 部署流程已有，直接 `helm upgrade`。

- [ ] **Step 2: 等待 Pod Ready**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/storage-controller -n lakeon --timeout=120s
```

预期：`deployment "storage-controller" successfully rolled out`

- [ ] **Step 3: 验证 storage-controller 启动正常**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/storage-controller -- curl -s http://localhost:1234/status
```

预期：返回 JSON 表示状态正常。

- [ ] **Step 4: 注册 pageserver 节点（如果 seed 时没自动注册）**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/storage-controller -- \
  curl -s -X POST http://localhost:1234/control/v1/node \
  -H "Content-Type: application/json" \
  -d '{"node_id": 1, "listen_http_addr": "pageserver", "listen_http_port": 9898, "listen_pg_addr": "pageserver", "listen_pg_port": 6400, "availability_zone_id": "cn-north-4a"}'
```

- [ ] **Step 5: 验证 tenant 列表**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/storage-controller -- \
  curl -s http://localhost:1234/v1/tenant | python3 -c "import sys,json;d=json.loads(sys.stdin.read());print(len(d),'tenants')"
```

预期：约 21 个 tenant。

---

### Task 4: 切换 pageserver → storage-controller

**Files:**
- Modify: `deploy/helm/lakeon/templates/configmap-pageserver.yaml`

这是关键步骤。切换后 pageserver 重启时会调 storage-controller 而不是靠本地磁盘。

- [ ] **Step 1: 修改 pageserver ConfigMap**

在 `deploy/helm/lakeon/templates/configmap-pageserver.yaml` 中：

```diff
-    control_plane_api = 'http://lakeon-api:{{ .Values.api.port }}'
-    control_plane_emergency_mode = true
+    control_plane_api = 'http://storage-controller:1234'
```

- [ ] **Step 2: Commit**

```bash
git add deploy/helm/lakeon/templates/configmap-pageserver.yaml
git commit -m "feat(deploy): switch pageserver control_plane_api to storage-controller"
```

- [ ] **Step 3: 应用配置变更（但先不重启 pageserver）**

```bash
# 更新 ConfigMap
KUBECONFIG=~/.kube/cce-lakeon-config kubectl apply -f <rendered-configmap>

# 验证 ConfigMap 更新
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get configmap pageserver-config -n lakeon -o yaml | grep control_plane
```

预期：看到 `control_plane_api = 'http://storage-controller:1234'`，不再有 `emergency_mode`。

- [ ] **Step 4: 重启 pageserver 触发 re-attach**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/pageserver -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/pageserver -n lakeon --timeout=300s
```

预期：pageserver 重启成功。

- [ ] **Step 5: 验证 re-attach 成功**

```bash
# 检查 pageserver tenant 数量
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/pageserver -c pageserver -- \
  curl -s http://127.0.0.1:9898/v1/tenant | python3 -c "import sys,json;d=json.loads(sys.stdin.read());print(len(d),'tenants attached')"

# 检查 storage-controller 日志有 re-attach
KUBECONFIG=~/.kube/cce-lakeon-config kubectl logs -n lakeon deploy/storage-controller --tail=50 | grep -i "re-attach\|attach\|node.*register"
```

预期：21+ 个 tenant attached，storage-controller 日志显示 re-attach 处理记录。

- [ ] **Step 6: 端到端验证——resume 一个 SUSPENDED 库**

```bash
curl -s -X POST -H "Authorization: Bearer lk_b185ed74eda372d39cc9220017334a00b289a26aeba460b9905fce2de3e66fb9" \
  https://api.dbay.cloud:8443/api/v1/databases/db_a01f1cb6/schemas
```

预期：hilobster-metadata 的 schema 列表秒级返回。

- [ ] **Step 7: 验证 SRE 控制台 tenant-health 仍正常**

```bash
curl -s -H "Authorization: Bearer lakeon-sre-2026" \
  https://api.dbay.cloud:8443/api/v1/admin/pageserver/tenant-health
```

预期：`"health": "HEALTHY"`

---

### Task 5: 压力测试——模拟 pageserver 宕机恢复

**Files:** 无代码改动，运维验证。

这一步模拟 Phase 1 的事故场景，验证 storage-controller 是否真的能自愈。

- [ ] **Step 1: 记录当前 tenant 数量**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/pageserver -c pageserver -- \
  curl -s http://127.0.0.1:9898/v1/tenant | python3 -c "import sys,json;print(len(json.loads(sys.stdin.read())))"
```

- [ ] **Step 2: 删除 pageserver Pod 模拟宕机**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl delete pod -n lakeon -l app=pageserver
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/pageserver -n lakeon --timeout=300s
```

- [ ] **Step 3: 等待新 Pod 启动后验证 tenant 全部恢复**

```bash
# 等 pageserver 完全 ready
sleep 15

# 检查 tenant 数量是否和 Step 1 一致
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/pageserver -c pageserver -- \
  curl -s http://127.0.0.1:9898/v1/tenant | python3 -c "import sys,json;print(len(json.loads(sys.stdin.read())))"

# 检查 SRE tenant-health
curl -s -H "Authorization: Bearer lakeon-sre-2026" \
  https://api.dbay.cloud:8443/api/v1/admin/pageserver/tenant-health | python3 -c "import sys,json;d=json.loads(sys.stdin.read());print('health:', d['health'], 'missing:', d['missing_count'])"
```

预期：tenant 数量一致，health=HEALTHY，missing=0。

- [ ] **Step 4: resume 一个 SUSPENDED 库验证全链路**

```bash
curl -s -w "\nHTTP %{http_code} time=%{time_total}s\n" --max-time 60 \
  -H "Authorization: Bearer lk_b185ed74eda372d39cc9220017334a00b289a26aeba460b9905fce2de3e66fb9" \
  https://api.dbay.cloud:8443/api/v1/databases/db_51bf898f/schemas
```

预期：tpch-bench 返回 schema 列表（paradedb, pdb, public），不再 hang。

---

### Task 6: 更新 design doc + memory

**Files:**
- Modify: `docs/superpowers/specs/2026-04-17-pageserver-tenant-resilience-design.md`

- [ ] **Step 1: 在 design doc Phase 2 状态标记为已完成**

在 design doc 末尾添加：

```markdown
## 实施记录

| 阶段 | 完成时间 | 结果 |
|---|---|---|
| Phase 1 (L2+L3) | 2026-04-17 | TenantReconcileService 每60s扫描 + SRE控制台tenant健康卡片 |
| Phase 2 (L1) | 2026-04-17 | storage-controller 部署，pageserver re-attach 自愈验证通过 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-04-17-pageserver-tenant-resilience-design.md
git commit -m "docs: mark Phase 2 storage-controller deployment complete"
```
