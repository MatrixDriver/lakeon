# syntax=docker/dockerfile:1.6
FROM python:3.11-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Install uv for fast deps resolution (pinned for reproducibility)
RUN pip install --no-cache-dir uv==0.4.15

# Copy dep manifest first for better layer caching
COPY pyproject.toml uv.lock* ./

# Install runtime deps only (no dev extras in the image)
RUN uv sync --frozen --no-dev 2>/dev/null || uv sync --no-dev

# Copy source
COPY app ./app

EXPOSE 8090

# Non-root user for security
RUN useradd --create-home --uid 10001 wikiagent && chown -R wikiagent:wikiagent /app
USER wikiagent

CMD ["uv", "run", "uvicorn", "app.main:app", \
     "--host", "0.0.0.0", "--port", "8090", \
     "--log-level", "info"]
