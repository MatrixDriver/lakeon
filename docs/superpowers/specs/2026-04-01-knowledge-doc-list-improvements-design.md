# 知识库文档列表页改进设计

**日期**: 2026-04-01
**状态**: 已确认

## 背景

知识库"古文知识库"上传了2673个文档，当前页面存在以下问题：
1. 上传进度和处理进度混在一起，无法独立观察
2. 上传没有速率/预估时间显示
3. 文档列表无分页，840条全部加载到前端
4. 680个"处理中"文档无法按状态筛选查看
5. 表头不可排序

## 设计方案

### 1. 进度卡片（单卡片双行布局）

在文件列表上方显示一个进度卡片，包含上传和处理两行：

```
┌─────────────────────────────────────────────────────────────────┐
│ 上传  ████████░░░░░░░░░  860/2673   12.3 MB/s · 预计还需 4 分钟  │
│ 处理  ███░░░░░░░░░░░░░░  160/840    ~3.2 文档/分 · 8 失败        │
│       ▸ 排队 668 · 解析中 4 · 已完成 160 · 失败 8                 │
└─────────────────────────────────────────────────────────────────┘
```

**上传行**：
- 进度条：已完成文件数/总文件数
- 速率：通过追踪 `XMLHttpRequest` 或 `fetch` 已传输字节数，每秒计算一次移动平均速率
- 预估时间：剩余字节数 / 当前速率
- 上传完成后该行显示 "上传完成 ✓"，3秒后隐藏

**处理行**：
- 进度条：已完成(READY+FAILED) / 总文档数
- 速率：根据最近N个文档的完成时间计算
- 可展开明细：显示各阶段文档数（排队/解析中/已完成/失败）

**显示/隐藏逻辑**：
- 正在上传时：两行都显示
- 上传完成但有处理中文档：只显示处理行
- 无上传且无处理中文档：整个卡片隐藏

**数据来源**：
- 上传速率：纯前端计算，不需要后端
- 处理统计：调用新增的 `GET /knowledge/documents/stats` 接口，复用现有的8秒轮询机制

### 2. 状态筛选Tab

在文件列表上方（进度卡片下方、搜索框上方）增加状态筛选Tab：

```
[ 全部 (840) | 处理中 (668) | 已就绪 (160) | 失败 (8) ]
```

- 每个Tab显示对应状态的文档数量（从stats接口获取）
- 点击Tab切换 `status` 筛选参数，重新请求后端
- "全部"不传status参数
- Tab切换时重置到第1页

### 3. 后端分页

**API变更** — `GET /api/v1/knowledge/documents`：

新增查询参数：
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 1 | 页码，从1开始 |
| `page_size` | int | 50 | 每页条数，最大200 |
| `status` | string | (无) | 状态筛选：PROCESSING/READY/FAILED |
| `sort_by` | string | upload_time | 排序字段：upload_time/status/size_bytes/chunks_count |
| `sort_order` | string | desc | 排序方向：asc/desc |

返回格式变更：
```json
{
  "documents": [...],
  "total": 840,
  "page": 1,
  "page_size": 50
}
```

**注意**：这是一个**破坏性变更**，当前返回的是数组，改为对象。前端需要同步修改。

### 4. 新增统计接口

**`GET /api/v1/knowledge/documents/stats?kb_id=xxx`**

返回：
```json
{
  "total": 840,
  "processing": 668,
  "ready": 160,
  "failed": 8,
  "pending": 4
}
```

此接口供进度卡片和状态Tab使用，轻量查询（只做 `GROUP BY status` 聚合），避免每次加载全量文档列表。

### 5. 可排序表头

支持点击以下列头排序：
- 大小（size_bytes）
- Chunks（chunks_count）
- 状态（status）
- 上传时间（upload_time，默认排序列）

点击表头切换：无排序 → 升序 → 降序 → 无排序。当前排序列显示 ↑ 或 ↓ 箭头。

排序通过 `sort_by` + `sort_order` 参数传给后端，后端SQL层面排序。

### 6. 前端上传速率计算

在 `runBatchUpload` 函数中追踪上传字节数：

```typescript
// 新增响应式状态
const uploadStats = reactive({
  totalBytes: 0,        // 总字节数
  uploadedBytes: 0,     // 已上传字节数
  startTime: 0,         // 开始时间戳
  speed: 0,             // 当前速率 (bytes/s)
  eta: 0,               // 预估剩余秒数
})
```

- 上传前计算 `totalBytes = sum(file.size)`
- 每个文件上传完成后累加 `uploadedBytes += file.size`
- 每秒更新一次速率：`speed = uploadedBytes / (now - startTime)`
- 预估时间：`eta = (totalBytes - uploadedBytes) / speed`

不需要单文件进度（用户只要整体速率），所以不需要改 `fetch` 为 `XMLHttpRequest`。

## 不在范围内

- 处理并发数优化（当前2-3个job pod，属于后端调优，独立议题）
- 上传并发数调整（当前3并发，暂不改）
- 文档内容预览
- 批量重试失败文档（已有此功能在admin面板）

## 技术影响

### 前端文件
- `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` — 主要改动文件
- `lakeon-console/src/api/knowledge.ts` — 新增stats接口，修改listDocuments参数

### 后端文件
- `KnowledgeController.java` — 修改listDocuments接口，新增stats接口
- `DocumentRepository.java` — 新增分页查询和统计查询
- `KnowledgeService.java` — 新增分页和统计的service方法
