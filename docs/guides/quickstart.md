# DBay 快速入门

5 分钟内创建你的第一个 Serverless PostgreSQL 数据库。

## 方式一：一键体验（无需注册）

```bash
# 创建临时试用账号 + 数据库（24 小时有效）
curl -X POST https://api.dbay.cloud:8443/api/v1/trial

# 返回:
# {
#   "tenant_id": "tn_abc12345",
#   "api_key": "lk_...",
#   "database": {
#     "name": "trial-db",
#     "connection_uri": "postgres://user_xxx@pg.dbay.cloud:4432/trial-db?options=endpoint%3Dtrial-db",
#     "password": "xxxxxxxx"
#   },
#   "expires_in_hours": 24
# }

# 立即连接
psql "postgres://user_xxx:PASSWORD@pg.dbay.cloud:4432/trial-db?options=endpoint%3Dtrial-db"
```

## 方式二：正式注册

### 1. 创建账号

```bash
curl -X POST https://api.dbay.cloud:8443/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "username": "myteam",
    "password": "MySecurePass123!",
    "inviteCode": "YOUR_INVITE_CODE"
  }'
```

保存返回的 `api_key`，后续所有 API 调用都需要它。

### 2. 创建数据库

```bash
curl -X POST https://api.dbay.cloud:8443/api/v1/databases \
  -H "Authorization: Bearer lk_YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "my-app-db"}'
```

保存返回的 `password` 和 `connection_uri`（密码仅显示一次）。

### 3. 连接数据库

```bash
psql "postgres://user_xxx:PASSWORD@pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db"
```

## 免费额度

| 资源 | 免费额度 |
|------|---------|
| 数据库数量 | 1 个 |
| 存储 | 1 GB |
| 算力 | 1 CU (1 vCPU / 2 GB RAM) |
| 自动休眠 | 5 分钟无活动后休眠，连接时自动唤醒 |

休眠后不消耗算力。数据持久保存在华为云 OBS，不会丢失。

## 核心特性

- **自动休眠/唤醒**：无连接时自动释放算力，连接到来时 3 秒内恢复
- **数据库分支**：类似 Git 的 copy-on-write 快照，零成本创建开发/测试环境
- **数据在中国**：华为云北京四区，符合数据合规要求

## 下一步

- [JavaScript/Prisma 连接指南](connect-javascript.md)
- [Python 连接指南](connect-python.md)
- [Java/Spring Boot 连接指南](connect-java.md)
