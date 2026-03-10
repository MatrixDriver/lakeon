---
description: "功能实施计划: pg-connection-tls"
status: completed
created_at: 2026-03-10T12:30:00
updated_at: 2026-03-10T13:00:00
archived_at: null
related_files:
  - rpiv/requirements/prd-pg-connection-tls.md
---

# 功能：PG 连接 TLS 加密

以下计划应该是完整的，但在开始实施之前，验证文档和代码库模式以及任务合理性非常重要。

## 功能描述

为 Lakeon/DBay 的 PostgreSQL 客户端连接提供 TLS 加密能力。当前 PG 连接全程明文（客户端 → ELB → Neon Proxy → Compute），本功能在客户端到 Proxy 这一段启用 TLS。TLS 为可选模式——默认 `sslmode=prefer` 的客户端自动升级到加密，不破坏现有明文连接。

同时将连接串中的 ELB IP 替换为域名 `pg.dbay.cloud`，使连接串更稳定、更直观。

## 用户故事

```
作为 DBay 数据库用户
我想要通过加密连接访问数据库
以便保护传输中的敏感数据
```

```
作为 DBay 数据库用户
我想要不改任何配置就能继续连接
以便 TLS 升级对我完全透明
```

## 问题陈述

PG 客户端连接全程明文传输，数据在网络中暴露。API 层已有 HTTPS，但承载实际业务数据的数据库连接缺少加密。

## 解决方案陈述

在 Neon Proxy 层配置 TLS 证书（Let's Encrypt），使 Proxy 支持 PG 协议的 TLS 握手。ELB TCP:4432 保持四层透传，TLS 在 Proxy 终止。新建 `pg.dbay.cloud` DNS 记录指向 ELB IP，用于证书签发和连接串。

## 功能元数据

**功能类型**：新功能
**估计复杂度**：低
**主要受影响的系统**：Proxy Helm 模板、values 配置、连接串
**依赖项**：DNS 记录（手动）、Let's Encrypt 证书（手动签发）

---

## 上下文参考

### 相关代码库文件 — 实施前必须阅读！

- `deploy/helm/lakeon/templates/deployment-proxy.yaml` (全文) — Proxy Deployment 模板，需要添加 TLS volumeMount 和 volume
- `deploy/helm/lakeon/templates/deployment-api.yaml` (第 76-110 行) — API 的 TLS 挂载模式，作为参考镜像
- `deploy/helm/lakeon/values.yaml` (第 44-56 行) — Proxy 默认配置，需添加 tls 块
- `deploy/cce/values-cce.yaml` (第 87-100 行) — CCE Proxy 配置，需启用 TLS 并更新 externalHost
- `deploy/cce/smoke-test.sh` (全文) — 冒烟测试，需添加 TLS 连接验证

### 要修改的文件

1. `deploy/helm/lakeon/templates/deployment-proxy.yaml` — 添加 TLS volume/mount 和启动参数
2. `deploy/helm/lakeon/values.yaml` — 添加 `proxy.tls` 配置块
3. `deploy/cce/values-cce.yaml` — 启用 TLS、更新 externalHost 为 `pg.dbay.cloud`
4. `deploy/cce/smoke-test.sh` — 添加 PG TLS 连接验证

### 无需创建新文件

所有变更都在现有文件上进行。

### 要遵循的模式

**API TLS 挂载模式**（从 `deployment-api.yaml` 第 76-110 行镜像）：

```yaml
# volumeMount（容器级）
{{- if .Values.api.ssl.enabled }}
- name: tls-keystore
  mountPath: /app/secrets
  readOnly: true
{{- end }}

# volume（Pod 级）
{{- if .Values.api.ssl.enabled }}
- name: tls-keystore
  secret:
    secretName: api-keystore
{{- end }}
```

**Proxy 的区别**：
- Neon Proxy 使用 PEM 格式（`.crt` + `.key`），不需要 PKCS12
- K8s `kubernetes.io/tls` Secret 自动提供 `tls.crt` 和 `tls.key`
- 挂载路径用 `/tls/`（与 API 的 `/app/secrets/` 区分）

---

## 实施计划

### 阶段 1：Helm 模板与配置

修改 Helm 模板和 values，使 Proxy 支持可选的 TLS 证书挂载。

### 阶段 2：CCE 配置与部署

更新 CCE values 启用 TLS，更新 externalHost，部署验证。

### 阶段 3：冒烟测试

添加 PG TLS 连接验证到冒烟测试脚本。

---

## 逐步任务

### 任务 1：UPDATE `deploy/helm/lakeon/values.yaml` — 添加 proxy.tls 配置块

- **IMPLEMENT**：在 `proxy` 块（第 44-56 行）末尾添加 TLS 配置：
  ```yaml
  proxy:
    # ... existing fields ...
    tls:
      enabled: false
      secretName: proxy-tls-cert
  ```
- **PATTERN**：镜像 `api.ssl.enabled` 模式（第 63-64 行），但用更具描述性的结构
- **VALIDATE**：`helm template lakeon deploy/helm/lakeon -f deploy/local/values-local.yaml | grep -A5 'name: proxy' | head -20`

### 任务 2：UPDATE `deploy/helm/lakeon/templates/deployment-proxy.yaml` — 添加 TLS 挂载和启动参数

- **IMPLEMENT**：
  1. 在 `args` 列表（第 37 行之后）添加条件 TLS 参数：
     ```yaml
     {{- if .Values.proxy.tls.enabled }}
     - "--tls-key=/tls/tls.key"
     - "--tls-cert=/tls/tls.crt"
     {{- end }}
     ```
  2. 在 `resources` 之前（第 49 行之前）添加 volumeMounts：
     ```yaml
     {{- if .Values.proxy.tls.enabled }}
     volumeMounts:
       - name: tls-cert
         mountPath: /tls
         readOnly: true
     {{- end }}
     ```
  3. 在 spec 末尾添加 volumes：
     ```yaml
     {{- if .Values.proxy.tls.enabled }}
     volumes:
       - name: tls-cert
         secret:
           secretName: {{ .Values.proxy.tls.secretName }}
     {{- end }}
     ```
- **PATTERN**：镜像 `deployment-api.yaml` 第 76-80 行（volumeMount）和第 106-110 行（volume）
- **GOTCHA**：Neon Proxy 的 TLS 参数是 `--tls-key` 和 `--tls-cert`（注意顺序：key 在前，cert 在后，这是 Neon 约定）。实施前先确认：`kubectl exec -n lakeon deploy/proxy -- proxy --help 2>&1 | grep tls` 查看实际参数名
- **VALIDATE**：
  ```bash
  # 不启用 TLS 时，模板不应包含 tls 相关内容
  helm template lakeon deploy/helm/lakeon -f deploy/local/values-local.yaml | grep -c tls
  # 应输出 0
  ```

### 任务 3：UPDATE `deploy/cce/values-cce.yaml` — 启用 TLS 并更新域名

- **IMPLEMENT**：在 proxy 块（第 87-100 行）中：
  1. 将 `externalHost` 从 `"114.116.210.49"` 改为 `"pg.dbay.cloud"`
  2. 添加 TLS 配置：
     ```yaml
     proxy:
       replicas: 1
       externalHost: "pg.dbay.cloud"
       serviceType: LoadBalancer
       tls:
         enabled: true
         secretName: proxy-tls-cert
       # ... rest unchanged
     ```
- **GOTCHA**：更改 externalHost 会影响新创建的数据库连接串。已有数据库的连接串存储在 DB 中，不会自动更新。旧的 IP 连接仍然可用（ELB IP 不变）。
- **VALIDATE**：`grep -A3 'tls:' deploy/cce/values-cce.yaml`

### 任务 4：UPDATE `deploy/cce/smoke-test.sh` — 添加 PG TLS 验证

- **IMPLEMENT**：在 PG 端口探测测试（现约第 74-83 行）之后，添加 TLS 握手验证：
  ```bash
  # 7. PG TLS 握手
  PG_HOST=$(grep 'externalHost:' "$SCRIPT_DIR/values-cce.yaml" 2>/dev/null | head -1 | sed 's/.*"\(.*\)".*/\1/')
  if [ -n "$PG_HOST" ]; then
    TLS_OK=$(echo | openssl s_client -connect "$PG_HOST:4432" -starttls postgres 2>/dev/null | grep -c "Verify return code: 0")
    if [ "$TLS_OK" -ge 1 ]; then
      check "PG TLS 握手成功 ($PG_HOST:4432)" "ok"
    else
      check "PG TLS 握手 ($PG_HOST:4432)" "TLS 不可用或证书无效"
    fi
  fi
  ```
- **PATTERN**：镜像现有 check 函数模式（第 15-25 行）
- **GOTCHA**：`openssl s_client -starttls postgres` 需要 OpenSSL 1.1.1+。macOS 自带的 LibreSSL 可能不支持 `-starttls postgres`，CCE 节点上的 OpenSSL 应该支持。
- **VALIDATE**：`bash -n deploy/cce/smoke-test.sh`（语法检查）

---

## 手动操作（实施前需完成）

以下操作需要在代码变更之前手动完成：

### M1：DNS 记录

在域名提供商添加 A 记录：
```
pg.dbay.cloud  →  114.116.210.49（ELB IP）
```

等待 DNS 传播（通常 5-30 分钟），验证：
```bash
dig pg.dbay.cloud +short
# 应返回 114.116.210.49
```

### M2：证书签发

使用 acme.sh 签发证书（DNS 验证模式）：
```bash
acme.sh --issue -d pg.dbay.cloud --dns <dns_provider>
```

### M3：创建 K8s Secret

```bash
kubectl create secret tls proxy-tls-cert \
  --cert=/path/to/pg.dbay.cloud.cer \
  --key=/path/to/pg.dbay.cloud.key \
  -n lakeon
```

验证：
```bash
kubectl get secret proxy-tls-cert -n lakeon -o jsonpath='{.data.tls\.crt}' | base64 -d | openssl x509 -noout -subject -dates
```

---

## 测试策略

### 无需单元测试

本功能无 Java/前端代码变更（连接串通过 values 配置变化，无代码改动），不需要新增单元测试。

### 集成验证

部署后通过以下命令验证：

1. **TLS 连接**：
   ```bash
   psql "host=pg.dbay.cloud port=4432 dbname=<test-db> user=<test-user> sslmode=require" -c "SELECT 1"
   ```

2. **明文连接（向后兼容）**：
   ```bash
   psql "host=pg.dbay.cloud port=4432 dbname=<test-db> user=<test-user> sslmode=disable" -c "SELECT 1"
   ```

3. **IP 连接（向后兼容）**：
   ```bash
   psql "host=114.116.210.49 port=4432 dbname=<test-db> user=<test-user>" -c "SELECT 1"
   ```

4. **TLS 握手详情**：
   ```bash
   echo | openssl s_client -connect pg.dbay.cloud:4432 -starttls postgres 2>/dev/null | grep -E "Protocol|Cipher|Verify"
   ```

### 边缘情况

- TLS Secret 不存在时，Proxy 应拒绝启动（K8s 会报 volume mount error，不会静默忽略）
- 证书过期后，新 TLS 连接会报错但明文连接仍可用

---

## 验证命令

### 级别 1：模板渲染验证

```bash
# 本地渲染确认 TLS 未启用时无变化
helm template lakeon deploy/helm/lakeon -f deploy/local/values-local.yaml | grep -c tls

# CCE 渲染确认 TLS 已启用
helm template lakeon deploy/helm/lakeon -f deploy/cce/values-cce.yaml | grep -A2 'tls-cert'
```

### 级别 2：部署验证

```bash
# Proxy Pod 正常运行
kubectl get pods -n lakeon -l app=proxy

# 证书已挂载
kubectl exec -n lakeon deploy/proxy -- ls /tls/

# Proxy 进程参数包含 TLS
kubectl exec -n lakeon deploy/proxy -- ps aux | grep tls
```

### 级别 3：端到端验证

```bash
# TLS 握手
echo | openssl s_client -connect pg.dbay.cloud:4432 -starttls postgres 2>/dev/null | head -20

# 冒烟测试
./deploy/cce/smoke-test.sh
```

---

## 验收标准

- [ ] DNS `pg.dbay.cloud` 解析到 ELB IP
- [ ] Let's Encrypt 证书已签发且有效
- [ ] K8s Secret `proxy-tls-cert` 已创建
- [ ] Proxy Pod 正常启动，进程参数含 `--tls-cert` / `--tls-key`
- [ ] `sslmode=require` 连接成功，`\conninfo` 显示 SSL 加密
- [ ] `sslmode=disable` 连接成功（向后兼容）
- [ ] 旧 IP 连接串仍然可用
- [ ] 冒烟测试全部通过
- [ ] Helm 模板在 TLS 未启用时无变化（本地环境不受影响）

---

## 完成检查清单

- [ ] DNS A 记录已创建并传播
- [ ] 证书已签发
- [ ] K8s Secret 已创建
- [ ] Helm 模板已更新
- [ ] values-cce.yaml 已更新
- [ ] 冒烟测试已更新
- [ ] 部署到 CCE
- [ ] 端到端验证通过
- [ ] 所有验收标准满足

---

## 备注

- **不改 Java 代码**：连接串的 host 来自 `proxy.externalHost` 配置，values-cce.yaml 从 IP 改为域名即可，`DatabaseService.buildConnectionUri()` 不需要改
- **证书续期**：Let's Encrypt 90 天有效期，需手动续期（与 API 证书同时），后续可考虑 cert-manager 自动化
- **Neon Proxy TLS 参数确认**：实施前需先在 CCE 上确认 `proxy --help` 的实际 TLS 参数名，避免参数名不匹配导致启动失败
