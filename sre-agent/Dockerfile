# sre-agent/Dockerfile
# Build context: sre-agent/ (so this works with Railway's rootDirectory=/sre-agent
# as well as running `docker build -f sre-agent/Dockerfile sre-agent/` locally).
#
# dbay-sre-mcp is a sibling package in the same repo but outside the build
# context. We install it from the public GitHub repo instead of COPY.
#
# hermes-agent install: https://github.com/NousResearch/hermes-agent is public
# (verified 2026-04-23). No PyPI package exists; install via git+URL.

FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install uv
RUN pip install --no-cache-dir uv==0.4.18

# Install hermes-agent from public GitHub repo.
ARG HERMES_REF=main
ENV HERMES_REF=${HERMES_REF}
RUN uv pip install --system "hermes-agent[feishu,mcp] @ git+https://github.com/NousResearch/hermes-agent@${HERMES_REF}"
# Explicit install of mcp + lark-oapi as safety net — uv extras parsing with
# PEP 508 direct references can silently drop extras in some versions.
RUN uv pip install --system "mcp>=1.2.0,<2" "lark-oapi>=1.5.3,<2" "qrcode>=7.0,<8"

# Install dbay-sre-mcp from PyPI. Package published from lakeon/dbay-sre-mcp/.
# Pin a specific version to keep builds reproducible.
ARG DBAY_SRE_MCP_VERSION=0.1.0
RUN uv pip install --system "dbay-sre-mcp==${DBAY_SRE_MCP_VERSION}"

# Install our agent_session_log package + its deps.
# Context is sre-agent/ so paths are relative — no sre-agent/ prefix.
COPY pyproject.toml /app/pyproject.toml
COPY agent_session_log /app/agent_session_log
COPY skills /app/skills
RUN uv pip install --system /app

# Copy runtime assets (config, scripts, main.py, entrypoint).
COPY hermes_config /app/hermes_config
COPY scripts /app/scripts
COPY main.py /app/main.py
COPY entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV HERMES_CONFIG=/app/hermes_config/config.yaml

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
