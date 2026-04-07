# KB Wiki Engine Phase 2 设计文档

> Phase 2 为 DBay 知识库增加 Web Clipper 浏览器扩展和团队协作能力，分两个子阶段独立交付。

## 背景

Phase 1 完成了 Wiki 引擎的核心能力（自动生成/维护 wiki 页面、图谱可视化、对话问答、知识沉淀）。Phase 2 的目标是让 10 人团队能共享知识库，并通过浏览器扩展快速收集文章。

## 阶段划分

| 阶段 | 内容 | 依赖 |
|------|------|------|
| Phase 2a | Web Clipper Chrome 扩展 | 无，独立交付 |
| Phase 2b | 团队协作 + 权限 | 无，独立交付 |

优先级：Web Clipper > 团队协作。两者无依赖，可独立开发。

---

## Phase 2a：Web Clipper Chrome 扩展

### 1. 产品设计

用户在 Chrome 浏览器看到一篇文章，点击扩展图标，选择目标知识库，一键保存。文章自动进入 ingest 流水线（抓取正文 → 切片 → embedding → wiki 更新）。

### 2. 扩展架构

Manifest V3 Chrome 扩展，三个组成部分：

- **Popup**（点击扩展图标弹出）：显示当前页面 URL、KB 下拉列表、"保存"按钮、状态反馈
- **Background Service Worker**：处理 API 调用（获取 KB 列表、提交 URL ingest）
- **无需 Options Page**：登录/登出集成在 popup 中

### 3. 登录流程

使用 `chrome.identity.launchWebAuthFlow()` 实现 Web 登录：

1. 用户点击扩展 → popup 显示"登录 DBay"按钮
2. 点击后打开 `console.dbay.cloud/ext-login` 页面
3. 用户输入 username/password
4. 登录成功 → redirect 到 `https://<extension-id>.chromiumapp.org/#key=lk_xxx`
5. 扩展从 URL 提取 API Key，存入 `chrome.storage.local`
6. 之后 popup 直接显示 KB 列表和保存功能

### 4. 保存流程

1. popup 自动填入当前 tab 的 URL
2. 用户从下拉列表选择目标 KB（记住上次选择）
3. 点击"保存" → 调用 `POST /api/v1/knowledge/{kbId}/ingest-url`
4. 显示保存状态（进行中 / 成功 / 失败）

### 5. 文件结构

```
lakeon-clipper/
  manifest.json          # Manifest V3
  popup.html             # 弹窗 UI
  popup.js               # 弹窗逻辑
  background.js          # Service Worker
  icons/                 # 扩展图标 16/48/128
```

### 6. 权限声明（manifest.json）

- `activeTab`：读取当前 tab 的 URL
- `storage`：存储 API Key 和上次选择的 KB
- `identity`：launchWebAuthFlow
- `host_permissions`：`https://api.dbay.cloud:8443/*`

### 7. Popup UI 设计

- **未登录态**：DBay logo + "登录"按钮
- **已登录态**：当前页面 URL（只读）、KB 下拉选择器、"保存到知识库"按钮、状态提示
- 港湾暖色调风格，与 console 一致

### 8. 后端变更

无需新增 API。复用现有端点：
- `POST /api/v1/auth/login`：登录获取 API Key
- `GET /api/v1/knowledge`：获取 KB 列表
- `POST /api/v1/knowledge/{kbId}/ingest-url`：URL ingest

### 9. Console 新增页面

新增 `/ext-login` 路由页面：
- 精简登录表单（username + password）
- 登录成功后 `window.location = redirectUri + '#key=' + apiKey`
- redirectUri 由扩展通过 launchWebAuthFlow 传入

### 10. 分发方式

先以开发者模式分发（10 人团队手动安装），稳定后上架 Chrome Web Store。代码完全相同，随时可上架。

---

## Phase 2b：团队协作 + 权限

### 1. 协作模型

采用 **KB 分享表** 方案：每个用户是完整的 DBay 租户（拥有自己的数据库、知识库等全部功能），KB owner 可以邀请其他租户访问自己的知识库。

不引入组织/团队实体，保持现有 tenant 模型不变。

### 2. 数据模型

新增 `kb_shares` 表：

```sql
CREATE TABLE kb_shares (
  id VARCHAR(32) PRIMARY KEY,
  kb_id VARCHAR(32) NOT NULL REFERENCES knowledge_bases(id),
  tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
  role VARCHAR(16) NOT NULL DEFAULT 'member',
  invited_by VARCHAR(64) NOT NULL REFERENCES tenants(id),
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(kb_id, tenant_id)
);
CREATE INDEX idx_kb_shares_tenant ON kb_shares(tenant_id);
CREATE INDEX idx_kb_shares_kb ON kb_shares(kb_id);
```

角色只有两级：`admin` 和 `member`。

KB owner 隐含 admin 角色，不需要在 kb_shares 表中存记录。

### 3. 权限矩阵

| 操作 | admin (owner) | member |
|------|:---:|:---:|
| 浏览 Wiki / 文档 / 图谱 | Y | Y |
| 对话提问 | Y | Y |
| 上传文档 / URL ingest | Y | Y |
| 沉淀对话到 Wiki | Y | Y |
| 删除文档 | Y | N |
| 删除 KB | Y | N |
| 管理分享（邀请/移除成员） | Y | N |
| 修改 KB 设置 | Y | N |

### 4. API 端点

**新增：**

| 端点 | 方法 | 功能 | 权限 |
|------|------|------|------|
| `/knowledge/{kbId}/shares` | GET | 列出分享成员 | admin |
| `/knowledge/{kbId}/shares` | POST | 邀请成员（by username） | admin |
| `/knowledge/{kbId}/shares/{shareId}` | DELETE | 移除成员 | admin |

**邀请流程：**
1. admin 输入对方 username
2. 后端查找该 tenant，存在则创建 kb_shares 记录
3. 不存在则返回错误"用户不存在"
4. 分享即生效，无需对方接受（10 人信任团队）

### 5. 访问控制改造

**现有模式：**
```java
kb = kbRepo.findByIdAndTenantId(kbId, tenantId);
```

**改为：**
```java
kb = kbRepo.findById(kbId);
role = kbAccessService.checkAccess(kbId, tenantId);
// role: admin / member / none
// none → 403
```

抽取 `KbAccessService.checkAccess(kbId, tenantId)` 方法：
1. `kb.tenantId == tenantId` → admin
2. 查 kb_shares 表 → 对应 role
3. 都不满足 → none（403）

各 controller 方法根据角色决定是否放行（admin-only 操作检查 role == admin）。

**KB 列表查询改造：**

现有 `findAllByTenantIdOrderByCreatedAtDesc` 只返回自己的 KB。改为返回：自己的 KB + 被分享的 KB（通过 kb_shares join 查询），前端分组显示。

### 6. 前端变更

**KB 列表页：**
- 分两组显示："我的知识库"和"共享知识库"
- 共享 KB 显示 owner 名称和我的角色
- 共享 KB 卡片带标记区分

**KB 详情页 — 分享面板：**
- 仅 admin 可见，入口在 KB 设置区域
- 成员列表：用户名 + 角色 + 邀请时间
- "邀请成员"：输入 username → 点击邀请
- "移除"按钮：每个成员旁边

**权限在前端的体现：**
- member 隐藏：删除 KB、删除文档、KB 设置、分享管理
- member 可用：浏览、上传、对话、沉淀

**Web Clipper 联动：**
- Clipper 获取 KB 列表时，后端已返回自己的 + 共享的 KB
- 用户在 Clipper 中也能选择共享 KB 保存文章

---

## E2E 测试

### Phase 2a 测试

- ext-login 页面：Playwright 测试登录 → redirect → URL 带 key
- URL ingest API 已有 E2E 覆盖，无需重复
- Chrome 扩展交互：手动验证

### Phase 2b API E2E 测试

使用两个临时租户（tenant_a 作 admin，tenant_b 作 member），测试后自动清理。

| 测试 | 真实验证 |
|------|---------|
| 创建分享 | admin 邀请 tenant_b → 断言 kb_shares 记录存在 |
| 列出成员 | 返回正确的成员列表和角色 |
| 被分享方看到 KB | tenant_b 的 KB 列表包含共享 KB |
| member 上传文档 | 上传 → 轮询 status=READY → 断言 documentCount 增加 → 搜索能命中 chunk |
| member 对话 | 基于已有文档提问 → 断言回答包含文档实际内容 |
| member 沉淀到 Wiki | save-response → 断言 wiki 页面创建 → wikiPageCount 增加 → 搜索能命中 |
| member 浏览 Wiki/图谱 | 获取 wiki pages → 断言页面列表正确 → 获取 graph → 断言 nodes/edges 正确 |
| member 不可删 KB | 调 delete KB → 断言 403 且 KB 仍存在 |
| member 不可删文档 | 调 delete doc → 断言 403 且文档仍存在 |
| member 不可管分享 | 调 shares API → 断言 403 |
| 移除分享后隔离 | admin 移除 member → member 再访问 → 断言 403 → admin 仍可正常访问 |
| 非相关租户无法访问 | 未被分享的第三方租户 → 断言 403 |

---

## 与 Phase 1 的关系

| Phase 1 能力 | Phase 2 变化 |
|-------------|-------------|
| 文档上传/ingest | 不变，member 也可上传 |
| Wiki 自动维护 | 不变，member 上传的文档同样触发 wiki 更新 |
| 对话/沉淀 | 不变，member 也可对话和沉淀 |
| 图谱 | 不变 |
| Admin Wiki 管理 | 不变，仍通过 SRE 控制台管理 |

## 依赖

- Phase 1 已完成（API 0.9.213）
- Chrome 扩展开发无额外依赖
- 数据库迁移：一个 migration 文件（kb_shares 表）
- 无需额外基础设施
