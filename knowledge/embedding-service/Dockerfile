FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN PIP_EXTRA_INDEX_URL=https://download.pytorch.org/whl/cpu \
    pip install --no-cache-dir -r requirements.txt

# Pre-download model into image (avoids startup download, ~2.2GB)
ENV HF_ENDPOINT=https://hf-mirror.com
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

# Disable reranker by default (saves ~1.5GB memory)
ENV RERANK_ENABLED=false

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
