FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN PIP_EXTRA_INDEX_URL=https://download.pytorch.org/whl/cpu \
    pip install --no-cache-dir -r requirements.txt

# Download model inside Docker (faster than COPY from macOS host via virtiofs)
# Disable xet protocol for reliable HTTP download
ENV HF_HUB_ENABLE_HF_TRANSFER=0
ENV HF_HUB_DISABLE_XET=1
RUN python -c "\
from huggingface_hub import snapshot_download; \
snapshot_download('BAAI/bge-m3', \
  local_dir='/models/bge-m3', \
  ignore_patterns=['onnx/*', 'imgs/*'])"

ENV MODEL_NAME=/models/bge-m3
ENV RERANK_ENABLED=false

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
