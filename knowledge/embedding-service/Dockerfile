FROM python:3.12-slim

WORKDIR /app

# Install CPU-only PyTorch first, then sentence-transformers (skips CUDA torch)
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir --no-deps sentence-transformers==3.4.1 \
    && pip install --no-cache-dir fastapi==0.115.0 uvicorn==0.34.0 \
    && pip install --no-cache-dir transformers tqdm numpy scipy scikit-learn pillow huggingface-hub

# Pre-download model into image (avoids startup download, ~2.2GB)
ENV HF_ENDPOINT=https://hf-mirror.com
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

# Disable reranker by default (saves ~1.5GB memory)
ENV RERANK_ENABLED=false

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
