FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml .
COPY src/ src/
COPY components/ components/

RUN pip install --no-cache-dir .

ENV PYTHONPATH=/app/src:/app
EXPOSE 8090

CMD ["python", "-m", "uvicorn", "lakeon_orchestrator.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]
