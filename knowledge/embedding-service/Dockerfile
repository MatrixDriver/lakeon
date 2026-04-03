ARG BASE_IMAGE=python:3.12-slim
FROM ${BASE_IMAGE}

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Model will be mounted at /models/bge-m3 via PVC or downloaded at startup
ENV MODEL_NAME=/models/bge-m3
ENV RERANK_ENABLED=false

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
