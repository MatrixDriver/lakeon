# 记忆库客户端加密模式设计

## 概述

为高隐私需求用户提供端到端加密的记忆库模式。记忆内容在本地 MCP 端加密后上传，DBay 服务端（包括 SRE、控制台、数据库）均无法看到记忆明文。

## 威胁模型

**防护目标**：服务端任何人（SRE、其他用户）无法通过控制台、Admin 面板、数据库直连等方式读取加密记忆库的明文内容。

**不防护**：本地设备被完全控制（此时密码也会被窃取）。

## 核心设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 加密范围 | content 加密，embedding 明文 | 保留服务端向量搜索能力 |
| 密钥管理 | 公私钥 + 密码 + DEK 三因素 | 配置文件可公开传播，任何单一泄露无法解密 |
| 加密粒度 | 每个 memory_base 一个 DEK | 与现有独立数据库架构匹配，库间隔离 |
| 搜索能力 | 纯向量搜索，放弃 BM25 | 加密后无法全文搜索，语义搜索足够 |
| 存量处理 | 仅新建记忆库支持加密 | 老库不动，不做迁移 |
| DEK 存储 | DEK 密文存服务端 | 跨设备恢复只需密码 + 配置文件 |
| Embedding 生成 | 本地 MCP 端生成 | 避免明文经过服务端 |
| 创建入口 | dbay-cli 命令行 | 密码输入需要安全交互（getpass） |
| 日常使用 | dbay-mcp 透明加解密 | 接口不变，对 AI agent 无感 |
| 密码存储 | `~/.dbay/secret` 文件（权限 600） | MCP 启动时自动读取，无需交互输入 |

## 密钥体系架构

### 三因素分离

解密需要同时具备三个因素，任何单一泄露无法解密：

```
配置文件（可公开传播）               服务端 DBay
┌───────────────────────┐         ┌──────────────────┐
│ public_key            │         │ encrypted_dek    │
│ encrypted_private_key │         │ (公钥加密)        │
│ (密码加密)             │         │ salt             │
└───────────────────────┘         └──────────────────┘
          │                                │
          │ 用户输入密码                     │
          ↓                                ↓
     private_key ──────────────────→ 解密 DEK
      (仅在内存中)                    (仅在内存中)
                                          │
                                          ↓
                                   解密记忆密文
```

| 泄露场景 | 能否解密 | 原因 |
|---------|---------|------|
| 配置文件泄露（如上传 GitHub） | 不能 | 没有密码，解不开私钥 |
| 服务端被攻破 | 不能 | 没有私钥，解不开 DEK |
| 密码泄露 | 不能 | 没有配置文件 |
| 配置文件 + 密码 | 不能 | 还需要服务端的 encrypted_dek |
| 配置文件 + 服务端 | 不能 | 还需要密码解开私钥 |
| 三者同时泄露 | **能** | 门槛已非常高 |

### 密钥层次

```
用户密码 "mypassword"
  → PBKDF2(密码, salt) → 密码派生密钥
    → AES-256-GCM 加密 private_key → encrypted_private_key（存配置文件）

RSA-4096 密钥对
  → public_key（存配置文件）
  → private_key（仅创建时和解锁时短暂存在于内存）

DEK（256-bit 随机密钥）
  → RSA-OAEP(public_key, DEK) → encrypted_dek（存服务端）
  → AES-256-GCM(DEK, content) → encrypted_content（存服务端）
```

### 加密算法选择

| 用途 | 算法 | 说明 |
|------|------|------|
| 密码派生 | PBKDF2-HMAC-SHA256 (600K iterations) | 浏览器 Web Crypto API 和 Python cryptography 统一支持 |
| 私钥加密 | AES-256-GCM | 对称加密，带认证 |
| DEK 加密 | RSA-4096 + OAEP | 非对称加密，配置文件可安全传播 |
| 内容加密 | AES-256-GCM | 对称加密，每条记忆独立 nonce |

## Embedding 三种模式

加密记忆库的 embedding 在本地 MCP 端生成（避免明文经过服务端），支持三种 provider：

| 模式 | 说明 | 配置 |
|------|------|------|
| **DBay 提供** | 调 DBay 的 embedding API（用户 apikey 鉴权） | 默认，无需额外配置 |
| **外部 API** | 用户指定 embedding endpoint/key/model | 需提供 endpoint、api_key、model |
| **本地模型** | MCP 启动时下载开源模型，本地 CPU/GPU 推理 | 自动下载，零配置 |

### Embedding 维度管理

- 每个 memory_base 有独立数据库，建表时动态指定维度：`embedding vector(${dim})`
- `memory_bases` 表增加 `embedding_dim` 字段
- **创建时自动探测**：CLI 用测试文本调一次 embedding API，自动获取实际维度，不需要用户手填
- **运行时双重校验**：MCP 端检查向量维度匹配 + pgvector 插入时数据库层校验
- **同一 memory_base 内不可换模型**，维度不一致会导致向量空间混乱

## 数据流

### Ingest（写入）

```
用户: "记住我的服务器IP是 10.0.1.5"
  → Claude 调 memory_ingest(content="我的服务器IP是 10.0.1.5")
  → MCP 检测到加密库：
    1. DEK + AES-256-GCM 加密 content → encrypted_content
    2. 本地 embedding provider 生成向量（用明文 content）
    3. POST /memory/bases/{id}/ingest
       body: { content: encrypted_content, embedding: [...] }
  → 服务端存储密文 + 向量
```

### Recall（检索）

```
用户: "我的服务器IP是多少？"
  → Claude 调 memory_recall(query="服务器IP")
  → MCP 检测到加密库：
    1. 本地 embedding provider 生成 query 向量
    2. POST /memory/bases/{id}/recall
       body: { query_embedding: [...], top_k: 10 }
    3. 服务端向量搜索，返回 top_k 条 { encrypted_content, ... }
    4. DEK 解密每条 content
    5. 返回明文给 Claude
```

### Recall 接口变化

加密记忆库的 recall 请求需要改为传 `query_embedding` 而非 `query`（因为服务端无法对密文做 BM25，也不需要服务端生成 embedding）：

```python
# 非加密库（现有逻辑不变）
POST /recall  { "query": "服务器IP", "top_k": 10 }

# 加密库（MCP 端传向量）
POST /recall  { "query_embedding": [0.12, -0.34, ...], "top_k": 10 }
```

服务端 recall 逻辑需要支持接收 `query_embedding` 参数，跳过 embedding 生成和 BM25，直接走向量搜索。

## 各层改动

### 1. dbay-cli（Python）— 创建与密钥管理

**新增命令：**

```bash
# 创建加密记忆库
dbay mem create --encrypted
# 交互流程：
# 1. 输入记忆库名称
# 2. 设置加密密码（getpass 双次确认）
# 3. 生成 RSA-4096 密钥对（cryptography 库）
# 4. Argon2id(密码, random_salt) → 派生密钥 → AES-256-GCM 加密私钥
# 5. 生成随机 DEK (256-bit)
# 6. RSA-OAEP(公钥, DEK) → encrypted_dek
# 7. 选择 embedding provider（DBay / 外部 API / 本地模型）
# 8. 探测 embedding 维度
# 9. 调 API 创建 memory_base（encrypted=true, encrypted_dek, salt, embedding_dim）
# 10. 写入密码到 ~/.dbay/secret
# 11. 写入密钥配置到 ~/.dbay/encrypted_bases.json

# 修改密码
dbay mem change-password <memory_base_id>
# 1. 输入旧密码 → 解密私钥 → 验证
# 2. 输入新密码 → 重新加密私钥
# 3. 更新 ~/.dbay/encrypted_bases.json 和 ~/.dbay/secret
```

**密码文件 `~/.dbay/secret`（权限 600，不可传播）：**

```
DBAY_ENCRYPTION_PASSWORD=mypassword
```

CLI 创建加密记忆库时自动写入。MCP 启动时自动读取，无需交互输入密码。
新设备上用户手动创建此文件即可。

**密钥配置文件 `~/.dbay/encrypted_bases.json`（可安全传播，如上传 GitHub）：**

```json
{
  "mem_a1b2c3d4": {
    "public_key": "-----BEGIN PUBLIC KEY-----\n...",
    "encrypted_private_key": "base64_encoded_aes_gcm_ciphertext",
    "kdf_salt": "base64_encoded_salt",
    "kdf_algorithm": "pbkdf2",
    "embedding_provider": "dbay",
    "embedding_dim": 1024
  },
  "mem_x7y8z9w0": {
    "public_key": "-----BEGIN PUBLIC KEY-----\n...",
    "encrypted_private_key": "base64_encoded_aes_gcm_ciphertext",
    "kdf_salt": "base64_encoded_salt",
    "kdf_algorithm": "pbkdf2",
    "embedding_provider": "external",
    "embedding_endpoint": "https://api.example.com/v1/embeddings",
    "embedding_api_key": "sk-...",
    "embedding_model": "bge-m3",
    "embedding_dim": 1024
  }
}
```

### 2. dbay-mcp（Python）— 透明加解密

**MCP 工具接口不变**，加密逻辑在内部透明处理：

```python
# 接口完全不变
memory_ingest(content, memory_type, importance, source, memory_base)
memory_recall(query, memory_types, top_k, memory_base)
memory_list(memory_base)
memory_delete(memory_id, memory_base)
```

**内部变化：**

- 启动时加载 `~/.dbay/encrypted_bases.json` 和 `~/.dbay/secret`
- 首次使用加密库时：从 secret 读密码 → 解密 private_key → 从服务端拉 encrypted_dek → private_key 解密 → DEK 缓存在进程内存
- `memory_ingest`：检测加密库 → DEK 加密 content → 本地生成 embedding → 上传密文+向量
- `memory_recall`：本地生成 query embedding → 服务端向量搜索 → 返回密文 → DEK 解密
- `memory_list`：拿到密文列表 → 逐条解密 → 返回明文
- 非加密库走现有逻辑，完全不变

**MCP 启动时自动解锁：**

MCP 由 Claude Code/Cursor 自动拉起，没有交互式终端。通过读取 `~/.dbay/secret` 文件获取密码，自动完成解锁：

```
MCP 启动
  → 读 ~/.dbay/secret → 拿到 DBAY_ENCRYPTION_PASSWORD
  → 读 ~/.dbay/encrypted_bases.json → 拿到 encrypted_private_key + salt
  → PBKDF2(密码, salt) → 解密 private_key（内存中）
  → 从服务端拉 encrypted_dek → private_key 解密 → DEK（内存中）
  → 进程生命周期内 DEK 缓存在内存，进程退出即消失
```

如果 `~/.dbay/secret` 不存在或密码错误，返回错误提示用户创建 secret 文件。

### 3. lakeon-api（Java）— 服务端元数据

**memory_bases 表新增字段：**

```sql
ALTER TABLE memory_bases ADD COLUMN encrypted BOOLEAN DEFAULT false;
ALTER TABLE memory_bases ADD COLUMN encrypted_dek TEXT;        -- RSA 加密后的 DEK 密文
ALTER TABLE memory_bases ADD COLUMN kdf_salt TEXT;             -- Argon2id salt
ALTER TABLE memory_bases ADD COLUMN embedding_dim INT;         -- embedding 向量维度
```

**API 变化：**

- 创建 memory_base 时支持 `encrypted=true` 及相关字段
- 建表时用 `embedding_dim` 动态指定向量维度：`vector(${dim})`
- recall API 支持 `query_embedding` 参数（直接传向量，跳过 embedding 生成和 BM25）

### 4. memory 微服务（Python）— 最小改动

- Schema 建表时用动态维度
- recall 端点支持接收 `query_embedding`，跳过 embedding 生成，直接向量搜索
- ingest 端点：当收到的 content 是密文时，跳过服务端 embedding 生成（由 MCP 端传入 embedding）
- 其余逻辑不变

### 5. console / admin — 展示层

- 加密记忆库在列表中显示 `[加密]` 标识
- 加密记忆库的记忆内容显示为密文（无法解密），提示"此记忆库已加密，内容仅可通过 MCP 客户端查看"
- Admin SRE 控制台同样无法查看明文

## 密码丢失与恢复

**密码丢失 = 数据永久丢失**。这是端到端加密的固有特性。

用户须知：
- 没有"忘记密码"功能
- DBay 无法帮助恢复数据
- 建议用户将密码存储在密码管理器中

## 安全边界说明

| 方面 | 保护级别 | 说明 |
|------|---------|------|
| 记忆内容 | 端到端加密 | 服务端只有密文 |
| Embedding 向量 | 明文 | 服务端可搜索，理论上可被近似还原语义信息 |
| 记忆元数据 | 明文 | memory_type、importance、时间戳等不加密 |
| 记忆数量 | 可见 | 服务端知道用户有多少条记忆 |

Embedding 明文上传意味着这不是严格的"零知识"加密。攻击者如果获得 embedding 向量，理论上可以通过向量反推大致的语义方向（但无法还原原文）。这是在隐私和可用性之间的权衡。
