#!/usr/bin/env bash
set -euo pipefail

# Seed demo knowledge base and memory base for trial users.
# Run AFTER setup-demo.sh has created the demo tenant and database.
#
# Usage:
#   API_URL=https://api.dbay.cloud:8443 API_KEY=lk_xxx ./deploy/demo/seed-kb-memory.sh

API_URL="${API_URL:?Set API_URL (e.g. https://api.dbay.cloud:8443)}"
API_KEY="${API_KEY:?Set API_KEY for the demo tenant}"
AUTH="Authorization: Bearer $API_KEY"

echo "=== DBay Demo KB & Memory Seed ==="
echo "API: $API_URL"

# --- Helper ---
api() {
  local method=$1 path=$2; shift 2
  curl -sf -X "$method" -H "$AUTH" -H "Content-Type: application/json" "$@" "$API_URL$path"
}

# ============================================
# 1. Knowledge Base
# ============================================
echo ""
echo "--- Knowledge Base ---"

# Check if demo KB already exists
EXISTING_KB=$(api GET /api/v1/knowledge/bases | jq -r '[.[] | select(.name == "DBay 快速入门")] | length')
if [ "$EXISTING_KB" -gt "0" ]; then
  echo "Demo KB already exists, skipping creation"
  KB_ID=$(api GET /api/v1/knowledge/bases | jq -r '[.[] | select(.name == "DBay 快速入门")][0].id')
else
  echo "Creating demo knowledge base..."
  KB_RESPONSE=$(api POST /api/v1/knowledge/bases -d '{
    "name": "DBay 快速入门",
    "description": "DBay 产品文档和使用指南，包含快速入门、API 参考、集成指南等",
    "type": "DOCUMENT"
  }')
  KB_ID=$(echo "$KB_RESPONSE" | jq -r '.id')
  echo "KB created: $KB_ID"

  # Wait for KB to be ready (needs backing database)
  echo "Waiting for KB to be ready..."
  for i in $(seq 1 60); do
    KB_STATUS=$(api GET "/api/v1/knowledge/bases/$KB_ID" | jq -r '.status')
    if [ "$KB_STATUS" = "READY" ]; then
      echo "  KB is READY"
      break
    elif [ "$KB_STATUS" = "FAILED" ]; then
      echo "  KB creation FAILED"
      exit 1
    fi
    echo "  Status: $KB_STATUS ($i/60)"
    sleep 3
  done
fi
echo "KB ID: $KB_ID"

# Upload demo documents
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOC_DIR="$SCRIPT_DIR/demo-docs"

if [ -d "$DOC_DIR" ]; then
  for doc in "$DOC_DIR"/*.md; do
    [ -f "$doc" ] || continue
    FILENAME=$(basename "$doc")
    echo "Uploading $FILENAME..."

    # Generate upload URL
    UPLOAD_INFO=$(api POST "/api/v1/knowledge/bases/$KB_ID/documents/upload-url" \
      -d "$(jq -n --arg fn "$FILENAME" '{filename: $fn, tags: ["demo"]}')")
    DOC_ID=$(echo "$UPLOAD_INFO" | jq -r '.document_id')
    UPLOAD_URL=$(echo "$UPLOAD_INFO" | jq -r '.upload_url')

    # Upload to OBS
    curl -sf -X PUT -H "Content-Type: text/markdown" --data-binary "@$doc" "$UPLOAD_URL" > /dev/null
    echo "  Uploaded, doc_id: $DOC_ID"

    # Trigger processing
    api POST "/api/v1/knowledge/bases/$KB_ID/documents/$DOC_ID/process" > /dev/null
    echo "  Processing triggered"
  done
else
  echo "No demo-docs directory found, skipping document upload"
fi

# ============================================
# 2. Memory Base
# ============================================
echo ""
echo "--- Memory Base ---"

EXISTING_MEM=$(api GET /api/v1/memory/bases | jq -r '[.[] | select(.name == "AI 助手对话记忆")] | length')
if [ "$EXISTING_MEM" -gt "0" ]; then
  echo "Demo Memory Base already exists, skipping creation"
  MEM_ID=$(api GET /api/v1/memory/bases | jq -r '[.[] | select(.name == "AI 助手对话记忆")][0].id')
else
  echo "Creating demo memory base..."
  MEM_RESPONSE=$(api POST /api/v1/memory/bases -d '{
    "name": "AI 助手对话记忆",
    "description": "模拟 AI Agent 与用户的多轮对话记忆，包含事实、事件和行为特征",
    "type": "BUILTIN",
    "scene": "CHAT_ASSISTANT"
  }')
  MEM_ID=$(echo "$MEM_RESPONSE" | jq -r '.id')
  echo "Memory Base created: $MEM_ID"

  # Wait for provisioning
  echo "Waiting for Memory Base to be active..."
  for i in $(seq 1 60); do
    MEM_STATUS=$(api GET "/api/v1/memory/bases/$MEM_ID" | jq -r '.status')
    if [ "$MEM_STATUS" = "ACTIVE" ]; then
      echo "  Memory Base is ACTIVE"
      break
    elif [ "$MEM_STATUS" = "FAILED" ]; then
      echo "  Memory Base provisioning FAILED"
      exit 1
    fi
    echo "  Status: $MEM_STATUS ($i/60)"
    sleep 3
  done
fi
echo "Memory Base ID: $MEM_ID"

# Ingest demo memories
echo "Ingesting demo memories..."
api POST "/api/v1/memory/bases/$MEM_ID/ingest_extracted" -d '{
  "memories": [
    {
      "content": "用户是一名后端开发工程师，主要使用 Python 和 Go，在一家 AI 创业公司工作，负责 Agent 基础设施建设。",
      "memory_type": "fact",
      "importance": 0.9
    },
    {
      "content": "用户偏好简洁的代码风格，不喜欢过度抽象。喜欢用 PostgreSQL 作为主数据库，对 NoSQL 持谨慎态度。",
      "memory_type": "fact",
      "importance": 0.8
    },
    {
      "content": "用户的 AI Agent 产品需要支持多轮对话记忆，当前使用 Redis 存储会话状态，但重启后数据丢失，正在寻找持久化方案。",
      "memory_type": "fact",
      "importance": 0.95
    },
    {
      "content": "2026-03-15 用户首次接入 DBay MCP 服务，在 Claude Code 中配置了记忆库，测试了 ingest 和 recall 功能，反馈检索速度很快。",
      "memory_type": "episode",
      "importance": 0.85
    },
    {
      "content": "2026-03-18 用户将生产环境的 Agent 记忆从 Redis 迁移到 DBay，迁移了约 5000 条对话记录，过程顺利，未出现数据丢失。",
      "memory_type": "episode",
      "importance": 0.9
    },
    {
      "content": "2026-03-20 用户反馈其 Agent 的用户满意度提升了 23%，因为 Agent 现在能记住用户的偏好和历史上下文，不再重复提问。",
      "memory_type": "episode",
      "importance": 0.95
    },
    {
      "content": "用户倾向于在遇到技术问题时先查文档再提问，是自驱型学习者。提问时通常会附带已尝试的方案和错误日志。",
      "memory_type": "trait",
      "importance": 0.7
    },
    {
      "content": "用户对 API 响应速度非常敏感，多次强调延迟对 Agent 体验的影响。在做技术选型时，性能是首要考虑因素。",
      "memory_type": "trait",
      "importance": 0.8
    }
  ]
}' > /dev/null
echo "  Ingested 8 demo memories (3 facts, 3 episodes, 2 traits)"

echo ""
echo "=== KB & Memory seed complete ==="
echo "  Knowledge Base: $KB_ID"
echo "  Memory Base:    $MEM_ID"
