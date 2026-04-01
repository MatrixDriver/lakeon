FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml .
RUN pip install --no-cache-dir .

COPY src/ src/

ENV PYTHONPATH=/app/src
EXPOSE 8090

CMD ["python", "-m", "uvicorn", "lakeon_orchestrator.main:app", "--host", "0.0.0.0", "--port", "8090"]
