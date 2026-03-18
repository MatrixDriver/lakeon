# Chunk Management Design — 知识库切片管理

## Overview

为 Lakeon 知识库添加切片查看、管理和质量分析功能，让用户能检查切片质量、编辑切片、重新切片，并利用 Neon 时间旅行实现无损回滚。

**核心场景：**
1. 文档上传后检查切片效果（语义完整性、长度分布、重复检测）
2. 搜索调优时查看切片内容和原文上下文

**差异化：** Neon 时间旅行提供竞品（火山 Viking、阿里百炼）不具备的切片版本管理和一键回滚能力。

## Phase 划分

- **Phase 1**：切片列表 + 原文定位 + 质量指标 + 编辑/删除/新增 + 重新切片 + 阈值展示
- **Phase 2**：搜索结果跳转切片上下文 + PDF 原文预览（坐标高亮） + RAPTOR L0/L1/L2

---

## 1. 数据架构

**方案 A + OBS**：单一数据源，用户库存切片全量数据，OBS 存文档全文。

| 存储位置 | 内容 | 用途 |
|----------|------|------|
| 用户库 `knowledge_chunks` | content, embedding, metadata, offsets | 检索 + 管理 |
| OBS `{kb_id}/{doc_id}/fulltext.md` | 解析后的完整 Markdown | 原文定位高亮 |
| 管控面 RDS | 无新增 chunks 数据 | 零负担 |

**选择理由：**
- 单一数据源，无双写一致性风险
- Neon 时间旅行可回看/回滚任意历史版本切片
- 3-4s 冷启动可接受，进入知识库详情页时预热 compute 隐藏延迟
- 质量指标在 pipeline 阶段预算好存入 metadata，列表查询不需要重新聚合
- RDS 零负担，即使万级文档也不影响

## 2. 数据模型变更

### knowledge_chunks 表新增字段

```sql
-- 原文定位
char_offset_start  INT          -- 在全文 Markdown 中的起始字符位置
char_offset_end    INT          -- 在全文 Markdown 中的结束字符位置

-- 质量指标（pipeline 预算）
char_count         INT          -- 字符数
overlap_prev       INT          -- 与前一个切片重叠的字符数

-- Marker 位置元数据（PDF 原文预览预留）
page_start         INT          -- 起始页码
page_end           INT          -- 结束页码
bbox               JSONB        -- Marker bounding boxes [{page, x0, y0, x1, y1}, ...]

-- RAPTOR 预留
level              SMALLINT DEFAULT 0   -- 0=原始切片, 1=L1摘要, 2=L2摘要
source_chunks      INT[]                -- L1/L2 引用的下级 chunk id 列表

-- 编辑支持
edited             BOOLEAN DEFAULT FALSE  -- 手动编辑过的切片标记（区分 pipeline 生成 vs 人工修改）
updated_at         TIMESTAMPTZ            -- 编辑切片时更新
```

### OBS 新增路径

```
{bucket}/knowledge/{tenant_id}/{kb_id}/{doc_id}/fulltext.md
```

与现有文档上传路径 `knowledge/{tenant_id}/{kb_id}/{doc_id}/{filename}` 保持一致。

## 3. API 端点

所有切片端点嵌套在 KB 路径下，确保 API 能解析到正确的 Neon compute 数据库连接。

### 切片查询类（需要 compute running）

```
GET  /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks?level=0&offset=0&limit=50
     → 文档切片列表（含 char_count, overlap_prev, section, level）
     → 分页：offset/limit，默认 limit=50，最大 200

GET  /api/v1/knowledge/bases/{kbId}/chunks?offset=0&limit=50&doc_id={docId}&status={status}
     → 知识库全局切片列表，支持按文档和状态筛选
     → 分页：offset/limit，默认 limit=50，最大 200

GET  /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex}
     → 单个切片详情（含 content, metadata, offsets）

GET  /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex}/context
     → 前后相邻切片（chunk_index ± 1 的 content）

GET  /api/v1/knowledge/bases/{kbId}/documents/{docId}/fulltext
     → 从 OBS 读取 fulltext.md（不经过 compute）

GET  /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunk-stats
     → 质量指标（见 Section 7 数据来源表）
```

### 切片修改类（需要 compute + embedding service）

```
PUT    /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex}
       → 编辑切片 content，即时重新生成 embedding，更新 char_count
       → 设置 edited=true, updated_at=now()

DELETE /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks/{chunkIndex}
       → 删除切片，更新 document 的 chunks_count
       → 后续切片 chunk_index 自动重排（单事务内完成，几百行 UPDATE 毫秒级）

POST   /api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks
       → 新增切片（指定 content, insert_after_index），生成 embedding
       → 插入后所有后续切片 chunk_index +1（单事务，INT 类型保持不变）
       → 设置 edited=true
```

### 重新切片（需要 compute + embedding service）

```
POST   /api/v1/knowledge/bases/{kbId}/documents/{docId}/rechunk
       → body: {max_tokens, overlap_ratio, custom_separator}
       → 自动创建 Neon branch 作为回滚点
       → 重跑 chunking + embedding pipeline
       → 返回新的切片统计 + branch_id

POST   /api/v1/knowledge/bases/{kbId}/documents/{docId}/rechunk/rollback
       → body: {branch_id}
       → 回滚到指定 branch 的切片状态
```

## 4. Console UI

### 三个入口

#### 入口 1：文档下钻（主入口）

Documents tab 点击文档行 → 进入文档详情页：

- **面包屑**：知识库 / {kb_name} / {filename}
- **顶栏**：文档信息 + [重新切片] + [新增切片] 按钮
- **左侧**：切片列表（按 chunk_index 排序）
  - 每个切片卡片：序号、字符数、section、overlap 比例、内容预览（2 行）
  - 异常短切片（< 80 字）橙色左边框 + ⚠️ 标记
  - 疑似重复切片（相似度 > 0.92）红色左边框 + 🔴 标记 + 显示重复对象
  - 选中切片蓝色高亮
- **右侧**三个 tab：
  - **切片内容**：完整内容展示 + [编辑] [删除] 按钮
  - **原文定位**：Markdown 全文渲染，当前切片高亮，自动滚动到位
  - **相邻切片**：前一个和后一个切片的完整内容

#### 入口 2：知识库全局 Chunks Tab

KnowledgeBaseDetail 新增第四个 tab "切片"：

- **统计卡片**：总切片数、平均字数、异常切片数、疑似重复数
- **长度分布直方图**：柱状图展示切片长度分布
- **阈值说明**：`⚠️ 过短 < 80 字 | 过长 > 800 字 | 疑似重复 > 92% 相似度`
- **切片表格**：可按文档、状态筛选，点击跳转到文档下钻页
- **列**：序号、文档名、内容预览、字数、状态

#### 入口 3：搜索结果跳转（Phase 2）

Search tab 搜索结果卡片加 [查看上下文] 按钮，跳转到文档详情页定位到该切片。

## 5. 重新切片 + Neon 时间旅行

### 流程

```
用户点击 [重新切片]
    → 弹出参数面板（max_tokens, overlap_ratio, custom_separator）
    → 确认后：
      1. 从当前 timeline 创建 Neon branch（这是旧数据的快照/回滚点）
      2. 在主 timeline 上删除旧切片，重跑 chunking + embedding pipeline
      3. 显示进度
    → 完成后展示对比视图：
      - 旧版本统计（从 branch 查询）vs 新版本统计（从主 timeline 查询）
    → 用户选择：
      [保留新版本] → 记录 branch_id 供后续回滚，完成
      [回滚到旧版本] → 立即执行回滚
```

### 回滚实现

Neon branch 不支持直接 merge 回 parent。回滚通过**数据复制**实现：

1. 连接到 branch 的 compute（临时启动），读取旧切片数据
2. 在主 timeline 上：`DELETE FROM knowledge_chunks WHERE document_id = $1`
3. 将 branch 的切片数据 `INSERT` 回主 timeline
4. 标记回滚完成，可选择保留或删除该 branch

这种方式的代价是一次全量复制（几百条切片，毫秒级），但逻辑简单可靠。

### Rechunk 原子性

Rechunk 在主 timeline 上的操作（删除旧切片 + 写入新切片）必须在单个事务中完成。Pipeline job 的写入流程：

1. 在内存中完成所有 chunking + embedding 计算
2. 开启事务：`DELETE FROM knowledge_chunks WHERE document_id = $1 AND level = 0`
3. 同一事务：`INSERT` 所有新切片
4. 提交事务

如果 pipeline job 失败（步骤 1 中），主 timeline 数据不受影响，branch 作为备份仍然存在。

### Rechunk 超时清理

如果 rechunk 启动后用户放弃（关闭浏览器/超时），`rechunk_status` 会卡在 IN_PROGRESS。处理方式：
- `DocumentEntity.rechunk_started_at` 记录开始时间
- API 查询文档时检查：如果 `rechunk_status = IN_PROGRESS` 且 `rechunk_started_at` 超过 30 分钟，自动重置为 IDLE
- 对应的 branch 保留（不删除），用户下次可手动清理或系统在下次 rechunk 时按"保留 3 个"规则清理

### 对比维度

- 切片总数
- 平均长度
- 异常切片数
- 重复切片数

### 版本管理

- Branch 命名：`rechunk/{doc_id}/{timestamp}`
- **保留最近 3 个版本**：每次重新切片时检查该文档的 rechunk branch 数量，超过 3 个删除最早的
- 不需要定时清理任务，上界确定，存储可控
- 并发保护：`DocumentEntity` 上加 `rechunk_status` 字段（IDLE / IN_PROGRESS）+ `rechunk_started_at`。使用行级 CAS：`UPDATE documents SET rechunk_status = 'IN_PROGRESS', rechunk_started_at = now() WHERE id = $1 AND rechunk_status = 'IDLE'`，返回 0 行则拒绝请求

### Neon 时间旅行优势

1. **零风险重新切片**：branch 是 copy-on-write，创建毫秒级，不满意瞬间回滚
2. **版本对比**：不同参数的切片效果可对比
3. **审计/调试**：通过 LSN 查看历史时间点的切片状态

## 6. 原文定位高亮

### 实现方式

```
OBS fulltext.md → 前端缓存 → markdown-it 渲染
                                    ↓
              根据 char_offset 在 Markdown 源文本中插入 <mark> 标记
                                    ↓
                        渲染为 HTML，高亮段自动滚动到可视区
```

**关键**：高亮在 Markdown 源文本阶段插入，不在渲染后的 HTML 上操作，避免 offset 错位。

### 缓存策略

fulltext.md 拉一次缓存在前端，切换切片时不需要重复请求 OBS。

### 后续升级路径（Phase 2）

Pipeline 已保存 Marker 的 page/bbox 元数据，后续可用 pdf.js 渲染原始 PDF + 坐标高亮。

## 7. 质量指标

### 数据来源

| 指标 | 来源 | 说明 |
|------|------|------|
| 长度分布 | 查询时聚合 | `GROUP BY width_bucket(char_count, ...)` |
| 异常切片列表 | 查询时过滤 | `WHERE char_count < 80 OR char_count > 800` |
| 平均 overlap | 查询时聚合 | `AVG(overlap_prev)` |
| 相邻语义相似度 | 查询时计算 | 相邻 chunk 的 `1 - (embedding <=> next)` |
| 疑似重复对 | **Pipeline 预算** | 处理阶段计算并存入 metadata `{duplicate_of: chunk_index, similarity: 0.95}` |
| char_count | Pipeline 预算 | 独立字段 |
| overlap_prev | Pipeline 预算 | 独立字段 |

重复检测在 pipeline 阶段预算的原因：全量两两比较是 O(N^2)，不适合作为同步 API 查询。Pipeline 阶段可使用 batch embedding 后在内存中计算，存入 metadata。chunk-stats API 直接读取预算结果。

### 查询时实时计算

```sql
-- 长度分布
SELECT width_bucket(char_count, 0, 1200, 12) AS bucket, count(*)
FROM knowledge_chunks WHERE document_id = $1 AND level = 0
GROUP BY bucket ORDER BY bucket;

-- 异常切片
SELECT id, chunk_index, char_count FROM knowledge_chunks
WHERE document_id = $1 AND level = 0
  AND (char_count < 80 OR char_count > 800);

-- 相邻切片语义相似度
SELECT a.chunk_index,
       1 - (a.embedding <=> b.embedding) AS similarity
FROM knowledge_chunks a
JOIN knowledge_chunks b ON b.document_id = a.document_id
  AND b.chunk_index = a.chunk_index + 1 AND b.level = 0
WHERE a.document_id = $1 AND a.level = 0;

-- 疑似重复：从 metadata 读取 pipeline 预算结果
SELECT id, chunk_index, metadata->'duplicate_of' AS duplicate_of,
       (metadata->>'similarity')::float AS similarity
FROM knowledge_chunks
WHERE document_id = $1 AND level = 0
  AND metadata ? 'duplicate_of';
```

### 阈值

| 指标 | 阈值 | 展示 |
|------|------|------|
| 异常短 | < 80 字 | ⚠️ 橙色标记 |
| 异常长 | > 800 字 | ⚠️ 橙色标记 |
| 疑似重复 | 相似度 > 0.92 | 🔴 红色标记 |

阈值在页面上明确展示：`⚠️ 过短 < 80 字 | 过长 > 800 字 | 疑似重复 > 92% 相似度`

Phase 1 硬编码，后续可开放配置。

## 8. Pipeline 改造

现有 pipeline 需要以下改造：

1. **parser.py**：输出 Markdown 全文 + Marker 位置信息（page, bbox per block）
2. **chunker.py**：计算每个 chunk 的 `char_offset_start/end` 和 `overlap_prev`
3. **writer.py**：写入新增字段（offsets, char_count, overlap_prev, page, bbox, level, source_chunks）
4. **新增步骤**：上传 `fulltext.md` 到 OBS
5. **callback.py**：回调中包含质量统计摘要

## 9. Compute 预热策略

切片查看需要 compute running（3-4s 冷启动），通过预热隐藏延迟：

- 用户进入知识库详情页时立即触发 compute 预热（如果 suspended）
- 等用户切换到 Documents tab、点击文档时，compute 已经 ready
- 前端展示 "正在连接数据库..." 加载状态兜底
