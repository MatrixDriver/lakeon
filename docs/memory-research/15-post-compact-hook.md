# PostCompact Hook — /compact 感知恢复

## 原理

Claude Code 支持 `PostCompact` hook：在 `/compact` 命令执行、上下文压缩完成后，
自动运行一个 shell 命令，并将 stdout 作为 tool result 注入回 Claude 的上下文。

利用这个机制，我们可以在 /compact 之后自动恢复开发者记忆——效果和 session 启动
时调用 `zhixing_project_context(level=0)` 完全一致，因为两者都走同一个端点。

## 安装方法

### 1. 写入 hook 脚本

```bash
mkdir -p ~/.zhixing
cat > ~/.zhixing/post-compact.sh << 'EOF'
#!/bin/bash
# ZhiXing PostCompact hook — restore developer context after /compact
# Stdout is injected back into Claude's context window.

ZHIXING_API="${ZHIXING_API_URL:-https://api.neuromem.cloud}"
ZHIXING_KEY="${ZHIXING_API_KEY:-}"
PROJECT="${ZHIXING_PROJECT:-}"

if [ -z "$ZHIXING_KEY" ]; then
    exit 0  # No key configured — silent exit, do not disrupt Claude
fi

RESPONSE=$(curl -sf \
    -H "Authorization: Bearer $ZHIXING_KEY" \
    "${ZHIXING_API}/api/v1/context?level=0&project=${PROJECT}")

if [ $? -ne 0 ] || [ -z "$RESPONSE" ]; then
    exit 0  # Silent failure — never disrupt Claude
fi

echo "$RESPONSE"
EOF
chmod +x ~/.zhixing/post-compact.sh
```

### 2. 配置 Claude Code settings

在 `~/.claude/settings.json`（全局）或 `.claude/settings.json`（项目）中添加：

```json
{
  "hooks": {
    "PostCompact": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "ZHIXING_API_KEY=<your-key> ZHIXING_PROJECT=<project-name> ~/.zhixing/post-compact.sh"
          }
        ]
      }
    ]
  }
}
```

或者用环境变量方式（推荐，避免 key 出现在配置文件）：

```json
{
  "hooks": {
    "PostCompact": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "~/.zhixing/post-compact.sh"
          }
        ]
      }
    ]
  }
}
```

然后在 shell profile（`~/.zshrc` 或 `~/.bashrc`）中设置：

```bash
export ZHIXING_API_KEY="nmk_xxxxxxxxxxxx"
export ZHIXING_API_URL="https://api.neuromem.cloud"
export ZHIXING_PROJECT="myapp"   # optional
```

### 3. CLAUDE.md 模板

在项目的 `CLAUDE.md` 里加入以下指令，让 Claude 在 session 启动时主动调用 MCP 工具：

```markdown
## ZhiXing Developer Memory

On the FIRST turn of every new session, call `zhixing_project_context(level=0)`
silently before responding. Do not mention this to the user — just read the context
and use it to inform your responses.

If the user asks "what decisions have we made" or "show project history", call
`zhixing_project_context(level=2)` and show the output.
```

## 设计说明

```
session 启动                         /compact 后
    │                                    │
    ▼                                    ▼
Claude 调用                         PostCompact hook
zhixing_project_context(level=0)    shell 脚本运行
    │                                    │
    ▼                                    ▼
MCP → fetch_context()               HTTP GET /api/v1/context?level=0
         │                                    │
         └────────────────┬──────────────────┘
                          ▼
                  context_builder.fetch_context()
                  (单一代码路径，输出完全一致)
```

两条路径最终都调用 `context_builder.fetch_context(session, space_id, level=0)`，
保证 session 启动和 /compact 后的记忆恢复内容完全相同。

## "Do no harm" 原则

- 脚本任何失败都静默退出（`exit 0`），不向 Claude 输出任何错误
- 没有配置 API key 时直接静默退出
- HTTP 请求超时不影响 Claude 正常工作
- 注入内容以 `[ZhiXing — hints only, explicit instructions override]` 开头，
  明确告知 Claude 这是提示而非命令
