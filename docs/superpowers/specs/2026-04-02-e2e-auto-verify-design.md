# 端到端自动验证系统 (E2E Auto-Verify)

## 目标

确保 console 每次上线时功能可用。建立自动化闭环：编写代码 → Playwright 浏览器测试 → 自动定位/修复问题 → 重跑直到通过 → 部署 → 部署后验证。

除产品决策和外部系统变更外，所有问题（包括后端数据模型、API 字段、前端渲染）由系统自行定位和修复，不需要用户介入。

## 架构

```
lakeon-console/
├── e2e/
│   ├── fixtures/
│   │   └── test-setup.ts       # 全局 fixture：租户创建/清理、auth 注入
│   ├── pipeline.spec.ts        # 生产线模块 E2E
│   ├── components.spec.ts      # 组件库页面 E2E
│   └── ...                     # 后续扩展
├── playwright.config.ts        # Playwright 配置
└── src/                        # 业务代码（不受影响）
```

## 环境

- **测试目标**：本地 dev server (`npm run dev`, localhost:5173)
- **API 后端**：线上 hwstaff (`https://api.dbay.cloud:8443`)
- **浏览器**：Chromium（Playwright 内置，headless）
- **Dev server 管理**：Playwright `webServer` 配置自动启动/关闭

## 测试租户隔离

每次测试 session 创建一个临时租户，测试结束自动删除：

1. **Global setup** — 通过 admin API 创建邀请码 → 注册租户 → 获取 api_key
2. **Auth 注入** — 将 `lakeon_api_key`、`lakeon_tenant_id`、`lakeon_tenant_name` 写入 `storageState` JSON 文件
3. **每个测试** — 自动加载 storageState，无需重复登录
4. **Global teardown** — 删除测试租户创建的所有 pipeline、知识库、数据库，然后通过 admin API 删除租户本身

Admin token：`lakeon-sre-2026`（与 pytest E2E 一致）。

## 第一批测试用例：生产线模块

### pipeline.spec.ts

| 用例 | 操作 | 验证 |
|------|------|------|
| 查看生产线列表 | 导航到 `/datalake/pipelines` | 页面渲染无报错，显示"生产线"标题 |
| 从模板创建文本生产线 | 点击"创建" → 选择 TEXT → 填写名称 → 选择模板 → 保存 | 跳转到详情页，名称和数据类型正确 |
| 从模板创建视频生产线 | 同上，选择 VIDEO | 同上 |
| 查看生产线详情 | 点击已创建的 pipeline | 显示版本列表、DAG YAML 内容 |
| 发布新版本 | 进入编辑器 → 修改 YAML → 发布 | 版本号递增，changelog 显示 |
| 触发运行 | 点击"运行" → 确认 | 运行记录出现，状态为 PENDING 或 RUNNING |
| 查看运行详情 | 点击运行记录 | 显示步骤列表、状态 |
| 删除生产线 | 点击删除 → 确认 | 从列表消失 |

### components.spec.ts

| 用例 | 操作 | 验证 |
|------|------|------|
| 查看组件库 | 导航到 `/datalake/components` | 显示至少 12 个平台内置组件 |
| 按数据类型筛选 | 点击 TEXT / VIDEO / UNIVERSAL 筛选 | 列表只显示对应类型 |
| 按处理阶段筛选 | 点击 CLEAN / FILTER / QC 等 | 列表只显示对应类别 |
| 搜索组件 | 输入"清洗" | 显示匹配组件 |

## Playwright 配置

```typescript
// playwright.config.ts 关键配置
{
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5173',
    storageState: 'e2e/.auth/state.json',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
  retries: 1,
  reporter: [['html', { open: 'never' }]],
}
```

关键点：
- `screenshot: 'only-on-failure'` — 失败时自动截图，用于定位问题
- `trace: 'on-first-retry'` — 首次重试记录完整 trace，可回放操作链
- `reuseExistingServer` — 本地开发时复用已有 dev server，CI 中自动启动
- `retries: 1` — 允许一次重试，排除偶发网络抖动

## 闭环流程

### 开发阶段（部署前）

```
1. 编写/修改代码
2. npm run test:e2e               # 自动启动 dev server + 跑 Playwright
3. 如果失败：
   a. 分析截图 + trace + DOM 快照
   b. 定位问题（前端/后端/数据模型）
   c. 修复代码
   d. 回到步骤 2
4. 全过 → 进入部署
```

### 部署阶段

```
5. build-and-push-api.sh (SITE=hwstaff)
6. kubectl rollout restart + wait
7. git push (触发 Railway 部署 admin/console 前端)
```

### 部署后验证

```
8. 等待 Railway 部署完成
9. 跑一轮 pytest E2E (tests/e2e/test_pipeline.py) 验证线上 API
10. 用 Playwright 打开线上 URL 跑 smoke 级别验证（可选）
```

## 自修复边界

| 分类 | 自行修复 | 示例 |
|------|---------|------|
| 前端渲染 | Yes | 字段名不匹配、组件报错、路由坏了 |
| API 层 | Yes | 路径错误、参数不对、返回格式变了 |
| 后端数据模型 | Yes | Entity 缺字段、Repository 方法缺失、Service 逻辑错 |
| 数据库 migration | Yes | 缺表/缺列 |
| CSS/布局 | Yes | 元素不可见、不可点击 |
| 产品决策 | **No — 询问用户** | 功能应该怎么表现、交互逻辑取舍 |
| 外部系统 | **No — 询问用户** | 华为云配置、第三方服务 |

## 文件变更清单

新增：
- `lakeon-console/playwright.config.ts`
- `lakeon-console/e2e/fixtures/test-setup.ts`
- `lakeon-console/e2e/pipeline.spec.ts`
- `lakeon-console/e2e/components.spec.ts`

修改：
- `lakeon-console/package.json` — 添加 `@playwright/test` devDependency 和 `test:e2e` script
- `lakeon-console/.gitignore` — 添加 `test-results/`, `playwright-report/`, `e2e/.auth/`

## 扩展规划

第一批只覆盖生产线和组件库。后续按模块扩展：
- 数据库管理（创建、列表、SQL 编辑器）
- 知识库（创建、上传文档、搜索）
- 记忆库（创建、ingest、recall）
- 数据湖作业（提交、日志、取消）
- 登录/注册流程
