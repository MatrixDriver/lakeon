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
RUN uv pip install --system "hermes-agent @ git+https://github.com/NousResearch/hermes-agent@${HERMES_REF}"

# Install dbay-sre-mcp from the lakeon GitHub repo (subdirectory install).
# DBAY_SRE_MCP_REF defaults to "main"; pin to a commit SHA for reproducibility.
ARG DBAY_SRE_MCP_REF=main
ENV DBAY_SRE_MCP_REF=${DBAY_SRE_MCP_REF}
RUN uv pip install --system \
    "dbay-sre-mcp @ git+https://github.com/MatrixDriver/lakeon.git@${DBAY_SRE_MCP_REF}#subdirectory=dbay-sre-mcp"

# Install our agent_session_log package + its deps.
# Context is sre-agent/ so paths are relative — no sre-agent/ prefix.
COPY pyproject.toml /app/pyproject.toml
COPY agent_session_log /app/agent_session_log
COPY skills /app/skills
RUN uv pip install --system /app

# Copy runtime assets (config, scripts, entrypoint).
COPY hermes_config /app/hermes_config
COPY scripts /app/scripts
COPY entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV HERMES_CONFIG=/app/hermes_config/config.yaml

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
