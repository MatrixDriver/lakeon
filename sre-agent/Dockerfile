# sre-agent/Dockerfile
# Build context: lakeon/ repo root (Railway rootDirectory=/).
# Reading the workspace structure to install path-source packages.
#
# OPERATOR NOTE: Set Railway dashboard `Service → Settings → Source → Root Directory`
# to `/` (not `/sre-agent`) so the build context includes `packages/`.
FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv==0.4.18

# Install hermes-agent from public GitHub.
#
# Pinned to 13038dc7 (2026-04-24 main HEAD) which includes 93a2d6b3
# "fix: add DeepSeek reasoning_content echo for tool-call messages" —
# required for DeepSeek V4 thinking-mode models (e.g. deepseek-v4-flash).
# Without it, replaying any tool-call turn (and SRE agent calls
# log_search/log_trace/etc constantly) triggers HTTP 400:
#   "The reasoning_content in the thinking mode must be passed back to the API."
ARG HERMES_REF=13038dc7
ENV HERMES_REF=${HERMES_REF}
RUN uv pip install --system "hermes-agent[feishu,mcp] @ git+https://github.com/NousResearch/hermes-agent@${HERMES_REF}"
# Explicit install of mcp + lark-oapi as safety net — uv extras parsing with
# PEP 508 direct references can silently drop extras in some versions.
RUN uv pip install --system "mcp>=1.2.0,<2" "lark-oapi>=1.5.3,<2" "qrcode>=7.0,<8"

# Install dbay-sre-mcp from PyPI. Package published from lakeon/dbay-sre-mcp/.
# Pin a specific version to keep builds reproducible.
ARG DBAY_SRE_MCP_VERSION=0.2.1
RUN uv pip install --system "dbay-sre-mcp==${DBAY_SRE_MCP_VERSION}"

# Install workspace packages (agent-session-log + hermes-agent-utils).
# We also copy the workspace root pyproject.toml so uv can resolve workspace sources.
COPY pyproject.toml /app/pyproject.toml
COPY packages /app/packages
RUN uv pip install --system /app/packages/agent-session-log
RUN uv pip install --system /app/packages/hermes-agent-utils

# Install sre-agent itself (skills package).
# Use --no-deps to skip workspace-source resolution for agent-session-log
# (already installed above via /app/packages/agent-session-log).
COPY sre-agent/pyproject.toml /app/sre-agent/pyproject.toml
COPY sre-agent/skills /app/sre-agent/skills
RUN uv pip install --system --no-deps /app/sre-agent && \
    uv pip install --system \
      "pyyaml>=6.0" "httpx>=0.27" "croniter>=2.0" \
      "esdk-obs-python>=3.24" "trafilatura>=1.6"

# Copy runtime assets.
COPY sre-agent/hermes_config /app/hermes_config
COPY sre-agent/scripts /app/scripts
COPY sre-agent/main.py /app/main.py
COPY sre-agent/entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV HERMES_CONFIG=/app/hermes_config/config.yaml
ENV PYTHONPATH=/app

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
