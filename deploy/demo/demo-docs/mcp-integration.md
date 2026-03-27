# MCP 集成指南

## 什么是 MCP

MCP（Model Context Protocol）是一种让 AI 应用访问外部工具和数据源的协议。DBay 通过 MCP 让 Claude Code、OpenClaw 等 AI 工具直接访问你的知识库和记忆库。

## 快速接入 Claude Code

### 安装

```bash
pip install dbay-cli
dbay login
claude mcp add --scope user dbay -- uvx dbay-mcp
```

API Key 存放在 `~/.dbay/config.json`，不会进入 Claude 配置文件或代码仓库。

### 可用工具

安装后，Claude Code 将获得以下能力：

**知识库工具：**
- `knowledge_search` — 搜索知识库中的文档
- `knowledge_list_bases` — 列出所有知识库
- `knowledge_list_documents` — 列出知识库中的文档
- `knowledge_get_chunk` — 获取文档片段详情

**记忆库工具：**
- `memory_recall` — 检索记忆（根据上下文自动搜索）
- `memory_ingest` — 存储对话记忆
- `memory_ingest_extracted` — 存储预提取的结构化记忆
- `memory_list` — 列出记忆条目
- `memory_delete` — 删除指定记忆

## 使用场景

### 1. 项目上下文持久化

Claude Code 可以记住你的项目架构、技术决策和编码偏好，跨会话保持上下文。

### 2. 文档知识检索

将项目文档上传到知识库，Claude Code 可以直接搜索和引用。

### 3. 团队知识共享

多个团队成员共享同一个知识库和记忆库，AI 助手了解整个团队的上下文。

## 其他 AI 工具集成

DBay MCP 同样支持：
- **Claude Desktop** — 桌面客户端记忆持久化
- **Cursor** — 代码库知识检索
- **Gemini CLI** — 命令行 AI 长期记忆
- **OpenClaw** — 龙虾 AI 助手，原生记忆集成
