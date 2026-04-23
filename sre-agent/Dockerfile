# sre-agent/Dockerfile
# Build context: lakeon repo root (railway.toml sets rootDirectory/builder config).
# All COPY paths are relative to lakeon/ repo root so both sre-agent/ and
# dbay-sre-mcp/ (siblings) are reachable.
#
# hermes-agent install: https://github.com/NousResearch/hermes-agent is public
# (verified 2026-04-23). The local clone at ~/code/hermes-agent also tracks
# origin = https://github.com/NousResearch/hermes-agent.git, confirming the URL.
# We install directly from that public GitHub URL at the pinned HERMES_REF
# (default: main).  No PyPI package exists for hermes-agent; vendoring into the
# build context is not feasible because Railway builds from the lakeon repo root,
# not the developer's home directory.

FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install uv
RUN pip install --no-cache-dir uv==0.4.18

# Install hermes-agent from public GitHub repo.
# HERMES_REF defaults to "main"; override at build time via:
#   docker build --build-arg HERMES_REF=<sha> ...
ARG HERMES_REF=main
ENV HERMES_REF=${HERMES_REF}
RUN uv pip install --system "hermes-agent @ git+https://github.com/NousResearch/hermes-agent@${HERMES_REF}"

# Install our agent_session_log package + its deps.
# Copy only metadata first so the package install layer is cached independently
# from source-code changes.
COPY sre-agent/pyproject.toml /app/pyproject.toml
COPY sre-agent/agent_session_log /app/agent_session_log
COPY sre-agent/skills /app/skills
RUN uv pip install --system /app

# Install dbay-sre-mcp (sibling package at lakeon repo root).
# Installed after agent_session_log so agent_session_log pins win on any
# shared dependency (e.g. psycopg2, httpx).
COPY dbay-sre-mcp /app/dbay-sre-mcp
RUN uv pip install --system /app/dbay-sre-mcp

# Copy runtime assets (config, scripts, entrypoint).
COPY sre-agent/hermes_config /app/hermes_config
COPY sre-agent/scripts /app/scripts
COPY sre-agent/entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV HERMES_CONFIG=/app/hermes_config/config.yaml

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
