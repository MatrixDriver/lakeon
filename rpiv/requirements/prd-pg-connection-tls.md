---
description: "产品需求文档: PG 连接端到端 TLS"
status: completed
created_at: 2026-03-10T12:00:00
updated_at: 2026-03-10T12:30:00
archived_at: null
---

# PG 连接 TLS 加密

## 1. 执行摘要

当前 Lakeon/DBay 的 PostgreSQL 客户端连接全程明文传输（客户端 → ELB → Neon Proxy → Compute Pod）。虽然 API 层已实现 HTTPS 加密，但数据库连接——承载实际业务数据的通道——仍暴露在网络中。

本需求为客户端到 Proxy 这一段链路提供 TLS 加密能力。通过为 `pg.dbay.cloud` 签发 Let's Encrypt 证书并配置 Neon Proxy 的 TLS 参数，使客户端可以选择加密连接。此变更对现有用户透明——默认 `sslmode=prefer` 的客户端会自动升级到 TLS，无需任何修改。

**MVP 目标**：客户端通过 `pg.dbay.cloud:4432` 连接时，Proxy 支持 TLS 握手，连接串使用域名替代 IP。

## 2. 使命

**使命声明**：为 DBay 数据库连接提供传输层加密，保护用户数据在网络传输中的安全。

**核心原则**：
1. **向后兼容**：不破坏现有明文连接，TLS 为可选能力
2. **零配置升级**：默认 `sslmode=prefer` 的客户端自动加密，无需用户操作
3. **最小变更**：只改动 Proxy 层配置和证书管理，不涉及 Compute/Pageserver
4. **运维一致性**：证书管理方式与现有 API HTTPS 证书保持一致（acme.sh + K8s Secret）

## 3. 目标用户

**主要用户**：DBay 数据库使用者
- 通过 psql、JDBC、Python psycopg2 等客户端连接数据库
- 技术水平：中高级开发者
- 关键需求：数据传输安全、连接简单、不增加额外配置负担

**次要用户**：DBay SRE 运维
- 负责证书续期、Proxy 配置管理
- 需要证书到期前及时更新

## 4. MVP 范围

### 范围内

**基础设施**
- ✅ 新建 DNS A 记录 `pg.dbay.cloud` → ELB IP
- ✅ 使用 acme.sh 为 `pg.dbay.cloud` 签发 Let's Encrypt 证书
- ✅ 证书存储为 K8s Secret（如 `proxy-tls-cert`）

**Proxy 配置**
- ✅ Proxy 启动参数添加 `--tls-cert` / `--tls-key`
- ✅ Helm 模板支持 TLS 证书挂载
- ✅ TLS 为可选模式：客户端可选择加密或明文

**连接串更新**
- ✅ `buildConnectionUri()` 使用 `pg.dbay.cloud` 替代 ELB IP
- ✅ Helm values 中 `proxy.externalHost` 更新为 `pg.dbay.cloud`

### 范围外

- ❌ Proxy → Compute 内部 TLS（集群内网，风险低）
- ❌ Compute → Pageserver/Safekeeper TLS（组件间通信）
- ❌ 强制 TLS（所有客户端必须加密）
- ❌ Console 前端连接文档中 sslmode 说明更新
- ❌ 证书自动续期自动化（手动续期，与 API 证书一致）
- ❌ mTLS（双向证书认证）

## 5. 用户故事

1. **作为数据库用户**，我想要通过加密连接访问数据库，以便保护传输中的敏感数据
   - 示例：`psql "postgres://user@pg.dbay.cloud:4432/mydb?sslmode=require"`

2. **作为数据库用户**，我想要不改任何配置就能继续连接，以便升级对我完全透明
   - 示例：现有连接串 `postgres://user@114.116.210.49:4432/mydb` 仍然可用（IP 不变），新连接串使用域名

3. **作为数据库用户**，我想要用域名而不是 IP 连接数据库，以便连接串更直观且不受 IP 变更影响
   - 示例：`pg.dbay.cloud:4432` 替代 `114.116.210.49:4432`

4. **作为 SRE 运维**，我想要证书管理方式与 API 证书一致，以便不增加额外的运维学习成本
   - 示例：acme.sh 签发 → kubectl create secret → Proxy Pod 挂载

## 6. 核心架构与模式

### 当前连接路径（明文）

```
客户端 ──TCP──→ ELB:4432 ──TCP──→ Proxy:4432 ──TCP──→ Compute:55433
```

### 目标连接路径（可选 TLS）

```
客户端 ──TLS──→ ELB:4432 ──TCP──→ Proxy:4432 ──TCP──→ Compute:55433
         ↑                          ↑
     TLS 握手在此建立          TLS 在 Proxy 终止
     (sslmode=require)        (持有证书和私钥)
```

- ELB 保持 TCP:4432 四层透传，不做 TLS 终止
- TLS 在 Proxy Pod 层终止
- Proxy → Compute 保持明文（集群内网）

### 关键设计决策

- **证书类型**：Let's Encrypt RSA（与 API 证书一致）
- **证书格式**：PEM 文件（Neon Proxy 原生支持），不需要 PKCS12 转换
- **挂载方式**：K8s Secret → Volume Mount 到 Proxy Pod

## 7. 功能规范

### 7.1 DNS 配置

| 记录 | 类型 | 值 |
|------|------|------|
| `pg.dbay.cloud` | A | ELB IP（当前 `114.116.210.49`） |

### 7.2 证书签发

使用 acme.sh DNS 验证模式签发：
```bash
acme.sh --issue -d pg.dbay.cloud --dns dns_dp  # 或对应 DNS 提供商
```

产出文件：
- `pg.dbay.cloud.cer`（证书）
- `pg.dbay.cloud.key`（私钥）

### 7.3 K8s Secret

```bash
kubectl create secret tls proxy-tls-cert \
  --cert=pg.dbay.cloud.cer \
  --key=pg.dbay.cloud.key \
  -n lakeon
```

### 7.4 Proxy 启动参数

在现有参数基础上添加：
```
--tls-cert=/tls/tls.crt
--tls-key=/tls/tls.key
```

### 7.5 Helm 模板变更

- `deployment-proxy.yaml`：添加 TLS Secret Volume 和 VolumeMount
- `values.yaml`：添加 `proxy.tls.enabled` 和 `proxy.tls.secretName` 配置项
- `values-cce.yaml`：启用 TLS 并指定 Secret 名称

### 7.6 连接串更新

`DatabaseService.buildConnectionUri()` 中 host 从 ELB IP 改为 `pg.dbay.cloud`：
```
postgres://user@pg.dbay.cloud:4432/mydb?options=endpoint%3Dmydb
```

通过 `proxy.externalHost` 配置项控制，values-cce.yaml 更新即可。

## 8. 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| Proxy | Neon Proxy（Rust） | 原生支持 `--tls-cert` / `--tls-key` |
| 证书 | Let's Encrypt + acme.sh | RSA 证书，DNS 验证 |
| 存储 | K8s Secret (`kubernetes.io/tls`) | PEM 格式，tls.crt + tls.key |
| 部署 | Helm Chart | 模板添加条件挂载 |
| DNS | dbay.cloud 域名提供商 | A 记录 |

无新增依赖。

## 9. 安全与配置

### 安全范围

**范围内**：
- 客户端 → Proxy 传输加密（TLS 1.2+）
- 证书私钥仅存储在 K8s Secret 中，Pod 只读挂载
- 证书通过 Let's Encrypt 签发，浏览器/客户端默认信任

**范围外**：
- 集群内部流量加密
- 客户端证书验证（mTLS）
- 证书自动续期

### 配置项

| 配置 | 位置 | 默认值 | 说明 |
|------|------|--------|------|
| `proxy.tls.enabled` | values.yaml | `false` | 是否启用 TLS |
| `proxy.tls.secretName` | values.yaml | `proxy-tls-cert` | 证书 Secret 名称 |
| `proxy.externalHost` | values-cce.yaml | ELB IP | 改为 `pg.dbay.cloud` |

## 10. 成功标准

### 功能要求
- ✅ `psql "sslmode=require host=pg.dbay.cloud port=4432"` 连接成功
- ✅ `psql "sslmode=disable host=pg.dbay.cloud port=4432"` 连接成功（明文仍可用）
- ✅ 现有使用 IP 的连接串仍然可用
- ✅ TLS 握手完成后 `\conninfo` 显示 SSL 加密信息

### 质量指标
- TLS 握手不增加超过 50ms 的连接延迟
- 证书信息正确（域名匹配、有效期、签发机构）

## 11. 实施阶段

### Phase 1：基础设施准备
- ✅ DNS A 记录 `pg.dbay.cloud` → ELB IP
- ✅ acme.sh 签发证书
- ✅ 创建 K8s Secret `proxy-tls-cert`
- **验证**：`dig pg.dbay.cloud` 返回正确 IP，证书文件有效

### Phase 2：Proxy TLS 配置
- ✅ Helm 模板添加 TLS Volume/VolumeMount（条件渲染）
- ✅ Proxy 启动参数添加 `--tls-cert` / `--tls-key`
- ✅ values.yaml 添加 `proxy.tls` 配置块
- ✅ values-cce.yaml 启用 TLS
- **验证**：`openssl s_client -connect pg.dbay.cloud:4432 -starttls postgres` 握手成功

### Phase 3：连接串与部署
- ✅ values-cce.yaml `proxy.externalHost` 改为 `pg.dbay.cloud`
- ✅ 部署到 CCE
- ✅ 端到端验证：psql TLS 连接 + 明文连接 + SQL 操作
- **验证**：冒烟测试通过，TLS 和明文均可连接

## 12. 未来考虑

- **证书自动续期**：CronJob 或 cert-manager 自动续期 Let's Encrypt 证书
- **强制 TLS**：proxy 添加 `--require-client-tls` 参数，拒绝明文连接
- **集群内部 TLS**：Proxy → Compute、Compute → Pageserver 链路加密
- **mTLS**：客户端证书认证，替代或增强密码认证
- **Console 文档更新**：连接示例中显示 sslmode 参数说明

## 13. 风险与缓解措施

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| DNS 传播延迟 | 新域名暂时无法解析 | 提前创建 DNS 记录，等待传播完成后再部署 |
| 证书过期未续 | TLS 连接失败 | 设日历提醒（90 天），与 API 证书同时续期 |
| Neon Proxy TLS 参数不兼容 | Proxy 启动失败 | 先在本地/dev 环境验证参数，确认 Neon Proxy 版本支持 |
| 现有 IP 连接串失效 | 用户连接中断 | IP 连接仍然可用（ELB IP 不变），只是新建数据库使用域名 |

## 14. 附录

### 相关文件
- API HTTPS 配置：`deploy/helm/lakeon/templates/deployment-api.yaml`
- Proxy 部署模板：`deploy/helm/lakeon/templates/deployment-proxy.yaml`
- Proxy Service 模板：`deploy/helm/lakeon/templates/service-proxy.yaml`
- CCE 部署配置：`deploy/cce/values-cce.yaml`
- 连接串构建：`lakeon-api/src/main/java/com/lakeon/service/DatabaseService.java`

### PG TLS 握手流程
```
Client                    Proxy
  │                         │
  │── SSLRequest ──────────→│
  │                         │
  │←── 'S' (支持 SSL) ─────│
  │                         │
  │←── TLS Handshake ──────→│  (证书验证、密钥交换)
  │                         │
  │── PG StartupMessage ───→│  (加密通道内)
  │                         │
  │←── AuthenticationOk ───│
```
