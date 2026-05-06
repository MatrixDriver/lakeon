# Phase 3 · Context API

> **状态**：进行中（2026-05-06 启动）
> **目标**：把云端 AgentFS 的关键能力剥到本地——`add_url` / `ls` / `read` / `write` / `mv`。让 Agent 不止能写文本记忆，还能把"指向外部世界"的引用（URL / PDF / repo 文件）灌进 echomem，复用 Phase 2 的衍生物 pipeline 自动消化。

---

## 一句话目标

让 `echomem ctx add_url https://...` 把网页拉下来、解析成文本、自动 ingest 进 memory，并和原始 blob 关联。

## 为什么需要

Phase 1+2 让 agent 会"记文本"，但实际工作里 agent 经常需要：
- 把一个 PR 链接灌进来分析
- 把一份 PDF spec 灌进来引用
- 把 repo 里的某个文件抓出来评论
- 之后 "回忆" 时能拿到原文出处而不只是摘要

这些都需要一个**内容寻址的 blob 层 + 解析器 + 路径别名机制**。Context API 就是这层。

## 设计前提

- **blob 内容寻址**：每个文件按 `sha256` 存 `~/.echomem/blobs/ab/cd/abcd...`，去重免费
- **path_alias 表**：用户友好的路径（"我那份 spec"）映射到 blob hash
- **解析后内容自动进 ingest pipeline**：复用 Phase 2 的衍生物 worker，零额外成本
- **memory.source_ref 关联到 blob**：Phase 1 已预留字段；不用改 schema，只加新表

## 交付清单（规划中）

### 1. 数据层
- [ ] m003 迁移
  - `blob_ref`：`sha256` PK + `mime` + `size` + `meta_json`
  - `path_alias`：用户路径 → `sha256` 映射 + 时间戳
- [ ] BlobStore：`put_file / put_bytes / get_path / exists`，全部 content-addressed
- [ ] SQLiteDriver 扩展 4 个 CRUD（`upsert_blob` / `resolve_alias` / `list_aliases` / `delete_alias`）

### 2. Fetcher / Parser
- [ ] URL fetcher：HTTPS GET，遵循 `robots.txt`，超时 30s
- [ ] HTML 解析：`trafilatura` 抽正文（备选 `readability-lxml`）
- [ ] PDF 解析：`pypdf` + 文本提取（图像 OCR 不在本期）
- [ ] Markdown：直接读
- [ ] 解析后调 `memory.upsert` 让 Phase 2 worker 自动消化

### 3. Context API（HTTP / CLI / MCP）
- [ ] `POST /context/add_url` — 拉链接 → 落 blob → 解析 → ingest
- [ ] `POST /context/write` — 直接写本地文件到 blob 层
- [ ] `GET /context/ls` — 列 path_alias
- [ ] `GET /context/read` — 按 alias 或 sha256 取原文
- [ ] `POST /context/mv` — 改 alias，不动底层 blob

### 4. CLI / MCP 入口
- [ ] `echomem ctx add_url <URL>` / `ls` / `read <alias>` / `write` / `mv`
- [ ] MCP 工具：`context_add_url` / `context_read` / `context_ls`

### 5. 端到端验证
- [ ] e2e：`add_url` 一篇博客 → blob 落地 → 触发 ingest → 几秒后可在 timeline / graph 查到
- [ ] e2e：`write` 一份 markdown → 后续 `recall` 能命中其中关键词
- [ ] 失败路径：URL 404、PDF 损坏、超时 → dead_letter 不崩 daemon

## 关键技术决策

| 决策 | 理由 |
|------|------|
| **content-addressed FS** 而非 UUID 命名 | 去重免费；同一份文件不管被引用多少次，磁盘只占一份 |
| **path_alias 与 blob 解耦** | "我那份 spec.pdf" 改名不动底层 blob；alias 删除不影响其他引用 |
| **解析后调 memory.upsert** 而非新写 pipeline | 复用 Phase 2 的 4 类衍生物，blob 上传完几秒就有 timeline / summary / graph |
| **不做 OCR / 视觉解析** | 本期专注 text-first；图片/截图留给 Phase 6+ |
| **遵循 robots.txt** | 同事 demo 拉网页时少踩公共服务边界 |

## 验收（待完成）

- [ ] e2e 全链路：URL → blob → memory → summary → graph
- [ ] `recall` 能从 blob 内容中命中关键词
- [ ] 异常路径全进 dead_letter，daemon 不崩
- [ ] CLI / HTTP / MCP 三入口行为一致

## 边界 / 暂不做

- 图片 OCR、视频转写
- repo 整体抓取（git clone 集成）
- 远程 blob 同步（CloudDriver Phase 5+ 才做）
- 加密 blob（本期明文存盘；加密在 Phase 5 onboarding 时一起考虑）

## 当前进度

- [x] 实施计划已写完（约 1500 行内部 spec）
- [ ] m003 迁移脚本
- [ ] BlobStore 实现
- [ ] URL fetcher + 解析器
- [ ] Context API 路由
- [ ] e2e 测试

---

## 阅读延伸

- [架构图](/architecture/) — Context API 在分层中的位置（虚线框）
- [Phase 2 · Derivatives](/docs-viewer/#/plans/phase2-derivatives) — Phase 3 复用的衍生物 pipeline
- [路线图](/roadmap/) — Phase 3 之后还有 Dashboard 与 Onboarding
