FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN PIP_EXTRA_INDEX_URL=https://download.pytorch.org/whl/cpu \
    pip install --no-cache-dir -r requirements.txt

# Copy pre-downloaded bge-m3 model into image
COPY .model-cache/bge-m3 /models/bge-m3

# Point to local model path (sentence-transformers accepts local dirs)
ENV MODEL_NAME=/models/bge-m3

# Disable reranker by default (saves ~1.5GB memory)
ENV RERANK_ENABLED=false

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
