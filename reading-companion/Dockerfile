# reading-companion/Dockerfile
# Build context: lakeon/ repo root (Railway rootDirectory=/).
# Workspace packages (agent-session-log, hermes-agent-utils) installed via path.
#
# Notable absences vs. sre-agent/Dockerfile:
#   - NO hermes-agent install (no inbound feishu / no LLM gateway needed)
#   - NO dbay-sre-mcp install (reading doesn't query dbay logs)
#   - Adds trafilatura for HTML body extraction
FROM python:3.11-slim

# trafilatura needs libxml2 + libxslt for the lxml C extension.
RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates libxml2 libxslt1.1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv==0.4.18

# Install workspace packages.
# Copy workspace root pyproject.toml so uv can resolve workspace sources
# (hermes-agent-utils depends on agent-session-log via workspace reference).
COPY pyproject.toml /app/pyproject.toml
COPY packages /app/packages
RUN uv pip install --system /app/packages/agent-session-log
RUN uv pip install --system /app/packages/hermes-agent-utils

# Install reading-companion itself.
# Use --no-deps to skip workspace-source resolution for agent-session-log and
# hermes-agent-utils (already installed above via /app/packages/).
COPY reading-companion/pyproject.toml /app/reading-companion/pyproject.toml
COPY reading-companion/skills /app/reading-companion/skills
RUN uv pip install --system --no-deps /app/reading-companion && \
    uv pip install --system "httpx>=0.27" "trafilatura>=1.6"

# Runtime assets.
COPY reading-companion/scripts /app/scripts
COPY reading-companion/main.py /app/main.py
COPY reading-companion/entrypoint.sh /app/entrypoint.sh

ENV HERMES_HOME=/data/hermes
ENV PYTHONPATH=/app

RUN chmod +x /app/entrypoint.sh

CMD ["/app/entrypoint.sh"]
