# Self-Hosted GPU Inference (Embedding + LLM) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy self-hosted bge-m3 embedding and Qwen3.5-9B LLM services on V100 GPU node, replacing SiliconFlow external dependency.

**Architecture:** Two GPU pods on the V100 node (192.168.0.224, `lakeon/role=gpu`): embedding-svc (port 8000, OpenAI-compatible `/v1/embeddings`) and llm-svc (port 8080, vLLM `/v1/chat/completions`). memory-svc and knowledge job switch to internal URLs via env vars — zero code changes for callers.

**Tech Stack:** sentence-transformers (embedding), vLLM (LLM serving), Helm, Docker/SWR

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `knowledge/embedding-service/main.py` | Add `/v1/embeddings` OpenAI-compatible endpoint |
| Modify | `knowledge/embedding-service/Dockerfile` | Enable model pre-bake, add GPU support |
| Modify | `knowledge/embedding-service/requirements.txt` | Pin torch with CUDA |
| Create | `deploy/helm/lakeon/templates/llm-service.yaml` | Helm template for vLLM LLM service |
| Modify | `deploy/helm/lakeon/templates/embedding-service.yaml` | GPU nodeSelector, env vars |
| Modify | `deploy/helm/lakeon/templates/memory-service.yaml` | Point to internal embedding/chat URLs |
| Modify | `deploy/helm/lakeon/templates/deployment-api.yaml` | Add `LAKEON_EMBEDDING_API_URL` env var |
| Modify | `deploy/cce/sites/hwstaff/values.yaml` | Enable embedding + llm, set images and URLs |
| Create | `deploy/cce/build-and-push-embedding.sh` | Build script for embedding-svc image |

---

### Task 1: Add OpenAI-compatible `/v1/embeddings` endpoint to embedding-svc

**Files:**
- Modify: `knowledge/embedding-service/main.py`

- [ ] **Step 1: Add OpenAI-compatible endpoint to main.py**

Add the following after the existing `/embed` endpoint (after line 51) in `knowledge/embedding-service/main.py`:

```python
# ── OpenAI-compatible endpoint (drop-in for SiliconFlow / vLLM) ─────

class OpenAIEmbedRequest(BaseModel):
    input: str | list[str]
    model: str = "bge-m3"

@app.post("/v1/embeddings")
def openai_embeddings(req: OpenAIEmbedRequest):
    texts = [req.input] if isinstance(req.input, str) else req.input
    if not texts:
        return {"data": [], "model": req.model, "usage": {"prompt_tokens": 0, "total_tokens": 0}}
    embeddings = model.encode(texts, batch_size=BATCH_SIZE, normalize_embeddings=True)
    data = [
        {"object": "embedding", "embedding": emb.tolist(), "index": i}
        for i, emb in enumerate(embeddings)
    ]
    return {
        "object": "list",
        "data": data,
        "model": MODEL_NAME,
        "usage": {"prompt_tokens": sum(len(t) for t in texts), "total_tokens": sum(len(t) for t in texts)},
    }
```

- [ ] **Step 2: Test locally**

```bash
cd knowledge/embedding-service
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 &
sleep 30  # model loading

# Test OpenAI-compatible endpoint
curl -s http://localhost:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input": "hello world", "model": "bge-m3"}' | python3 -c "
import sys, json
r = json.load(sys.stdin)
assert r['object'] == 'list'
assert len(r['data']) == 1
assert len(r['data'][0]['embedding']) == 1024
print('OK: 1024-dim embedding returned')
"

# Test batch
curl -s http://localhost:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input": ["hello", "world"], "model": "bge-m3"}' | python3 -c "
import sys, json
r = json.load(sys.stdin)
assert len(r['data']) == 2
print('OK: batch of 2 embeddings returned')
"

kill %1
```

Expected: Both return 1024-dim embeddings in OpenAI format.

- [ ] **Step 3: Commit**

```bash
git add knowledge/embedding-service/main.py
git commit -m "feat(embedding): add OpenAI-compatible /v1/embeddings endpoint"
```

---

### Task 2: Update embedding-svc Dockerfile and requirements for GPU

**Files:**
- Modify: `knowledge/embedding-service/Dockerfile`
- Modify: `knowledge/embedding-service/requirements.txt`

- [ ] **Step 1: Update requirements.txt**

Replace the contents of `knowledge/embedding-service/requirements.txt` with:

```
sentence-transformers==3.4.1
fastapi==0.115.0
uvicorn==0.34.0
```

Note: PyTorch with CUDA comes pre-installed in the NVIDIA base image. For CPU fallback, `sentence-transformers` pulls in torch automatically.

- [ ] **Step 2: Update Dockerfile for GPU + model pre-bake**

Replace `knowledge/embedding-service/Dockerfile` with:

```dockerfile
FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download model into image (avoids startup download, ~2.2GB)
ENV HF_ENDPOINT=https://hf-mirror.com
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

# Disable reranker by default (saves ~1.5GB memory)
ENV RERANK_ENABLED=false

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

Key changes:
- `HF_ENDPOINT=https://hf-mirror.com` for China access during build
- Model pre-bake enabled (was commented out)
- `RERANK_ENABLED=false` by default

- [ ] **Step 3: Commit**

```bash
git add knowledge/embedding-service/Dockerfile knowledge/embedding-service/requirements.txt
git commit -m "feat(embedding): pre-bake bge-m3 model in Docker image"
```

---

### Task 3: Build and push embedding-svc image to SWR

**Files:**
- Create: `deploy/cce/build-and-push-embedding.sh`

- [ ] **Step 1: Create build script**

Create `deploy/cce/build-and-push-embedding.sh`:

```bash
#!/usr/bin/env bash
#
# 构建 lakeon-embedding 镜像并推送到华为云 SWR
#
# 用法:
#   ./deploy/cce/build-and-push-embedding.sh
#   IMAGE_TAG=0.3.1 ./deploy/cce/build-and-push-embedding.sh
#

set -euo pipefail

export no_proxy="*"
export NO_PROXY="*"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${SWR_ORG:-}" ] && [ -f "$SCRIPT_DIR/site.sh" ]; then
  source "$SCRIPT_DIR/site.sh"
fi

SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_ORG="${SWR_ORG:-flex}"
IMAGE_TAG="${IMAGE_TAG:-0.3.0}"
IMAGE="swr.${SWR_REGION}.myhuaweicloud.com/${SWR_ORG}/lakeon-embedding:${IMAGE_TAG}"
EMBED_DIR="$(cd "$SCRIPT_DIR/../../knowledge/embedding-service" && pwd)"

echo "=== 构建 lakeon-embedding 并推送到 SWR ==="
echo "镜像: $IMAGE"
echo ""

echo "[1/2] Docker 构建..."
docker build -t "$IMAGE" "$EMBED_DIR"
echo "  构建完成"

echo "[2/2] 推送到 SWR..."
docker push "$IMAGE"
echo ""
echo "=== 完成: $IMAGE ==="
```

- [ ] **Step 2: Make executable and build**

```bash
chmod +x deploy/cce/build-and-push-embedding.sh
./deploy/cce/build-and-push-embedding.sh
```

Expected: Image `swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:0.3.0` pushed to SWR.

- [ ] **Step 3: Commit**

```bash
git add deploy/cce/build-and-push-embedding.sh
git commit -m "feat(deploy): add build-and-push script for embedding-svc"
```

---

### Task 4: Create llm-svc Helm template

**Files:**
- Create: `deploy/helm/lakeon/templates/llm-service.yaml`

- [ ] **Step 1: Create the Helm template**

Create `deploy/helm/lakeon/templates/llm-service.yaml`:

```yaml
{{- if .Values.llm.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llm-svc
  namespace: {{ .Release.Namespace }}
  labels:
    app: lakeon-llm
spec:
  replicas: 1
  selector:
    matchLabels:
      app: lakeon-llm
  template:
    metadata:
      labels:
        app: lakeon-llm
    spec:
      nodeSelector:
        lakeon/role: gpu
      containers:
        - name: llm
          image: {{ .Values.llm.image }}
          args:
            - "--model"
            - "{{ .Values.llm.model | default "Qwen/Qwen3.5-9B" }}"
            - "--port"
            - "8080"
            - "--tensor-parallel-size"
            - "1"
            - "--max-model-len"
            - "{{ .Values.llm.maxModelLen | default 8192 | toString }}"
            - "--gpu-memory-utilization"
            - "{{ .Values.llm.gpuMemoryUtilization | default 0.6 | toString }}"
            - "--trust-remote-code"
          {{- if .Values.llm.hfMirror }}
          env:
            - name: HF_ENDPOINT
              value: "{{ .Values.llm.hfMirror }}"
          {{- end }}
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "2"
              memory: "8Gi"
              nvidia.com/gpu: "1"
            limits:
              cpu: "4"
              memory: "16Gi"
              nvidia.com/gpu: "1"
          readinessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 120
            periodSeconds: 15
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 180
            periodSeconds: 30
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: llm-svc
  namespace: {{ .Release.Namespace }}
spec:
  selector:
    app: lakeon-llm
  ports:
    - port: 8080
      targetPort: 8080
{{- end }}
```

Notes:
- `nvidia.com/gpu: "1"` ensures this pod gets the GPU
- Long `initialDelaySeconds` because vLLM needs time to load the 9B model
- Model is downloaded at first startup (cached on node); future: PVC for persistence

- [ ] **Step 2: Commit**

```bash
git add deploy/helm/lakeon/templates/llm-service.yaml
git commit -m "feat(deploy): add Helm template for vLLM LLM service"
```

---

### Task 5: Update embedding-service.yaml Helm template for GPU scheduling

**Files:**
- Modify: `deploy/helm/lakeon/templates/embedding-service.yaml`

- [ ] **Step 1: Update the template**

Replace the full contents of `deploy/helm/lakeon/templates/embedding-service.yaml` with:

```yaml
{{- if .Values.embedding.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: embedding-svc
  namespace: {{ .Release.Namespace }}
  labels:
    app: lakeon-embedding
spec:
  replicas: 1
  selector:
    matchLabels:
      app: lakeon-embedding
  template:
    metadata:
      labels:
        app: lakeon-embedding
    spec:
      nodeSelector:
        lakeon/role: gpu
      containers:
        - name: embedding
          image: {{ .Values.embedding.image }}
          ports:
            - containerPort: 8000
          env:
            - name: RERANK_ENABLED
              value: {{ .Values.embedding.rerankEnabled | default "false" | quote }}
          resources:
            requests:
              cpu: {{ .Values.embedding.cpu | default "2" | quote }}
              memory: {{ .Values.embedding.memory | default "4Gi" | quote }}
            limits:
              cpu: {{ .Values.embedding.cpu | default "2" | quote }}
              memory: {{ .Values.embedding.memory | default "4Gi" | quote }}
          readinessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 60
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 120
            periodSeconds: 30
      {{- with .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: embedding-svc
  namespace: {{ .Release.Namespace }}
spec:
  selector:
    app: lakeon-embedding
  ports:
    - port: 8000
      targetPort: 8000
{{- end }}
```

Changes from original:
- `nodeSelector: lakeon/role: gpu` (was `compute`)
- Added `RERANK_ENABLED` env var

- [ ] **Step 2: Commit**

```bash
git add deploy/helm/lakeon/templates/embedding-service.yaml
git commit -m "feat(deploy): update embedding-svc template for GPU node"
```

---

### Task 6: Update memory-service.yaml to use internal URLs

**Files:**
- Modify: `deploy/helm/lakeon/templates/memory-service.yaml`

- [ ] **Step 1: Update the env section**

In `deploy/helm/lakeon/templates/memory-service.yaml`, replace the env block (lines 29-48) with:

```yaml
          env:
            - name: EMBEDDING_API_URL
              value: {{ .Values.embedding.apiUrl | default "https://api.siliconflow.cn/v1/embeddings" }}
            {{- if not .Values.embedding.enabled }}
            - name: EMBEDDING_API_KEY
              valueFrom:
                secretKeyRef:
                  name: api-credentials
                  key: ai-api-key
                  optional: true
            {{- end }}
            - name: EMBEDDING_MODEL
              value: {{ .Values.embedding.model | default "BAAI/bge-m3" }}
            - name: CHAT_API_URL
              value: {{ .Values.memory.chatApiUrl | default "https://api.siliconflow.cn/v1" }}
            - name: CHAT_MODEL
              value: {{ .Values.memory.chatModel | default "Qwen/Qwen2.5-7B-Instruct" }}
            {{- if not .Values.llm.enabled }}
            - name: CHAT_API_KEY
              valueFrom:
                secretKeyRef:
                  name: api-credentials
                  key: ai-api-key
                  optional: true
            {{- end }}
```

Key change: When `embedding.enabled` or `llm.enabled` is true, API keys are not injected (internal services don't need auth). When false, falls back to external API with key from secret.

- [ ] **Step 2: Commit**

```bash
git add deploy/helm/lakeon/templates/memory-service.yaml
git commit -m "feat(deploy): conditionally inject API keys for memory-svc"
```

---

### Task 7: Update API deployment to pass embedding URL to knowledge jobs

**Files:**
- Modify: `deploy/helm/lakeon/templates/deployment-api.yaml`

- [ ] **Step 1: Add LAKEON_EMBEDDING_API_URL env var**

In `deploy/helm/lakeon/templates/deployment-api.yaml`, after the `LAKEON_AI_API_KEY` block (after line 66), add:

```yaml
            {{- if .Values.embedding.apiUrl }}
            - name: LAKEON_EMBEDDING_API_URL
              value: {{ .Values.embedding.apiUrl | quote }}
            {{- end }}
```

This makes the API server pass the internal embedding URL to knowledge job pods via its `LakeonProperties.knowledge.embeddingApiUrl` config.

- [ ] **Step 2: Commit**

```bash
git add deploy/helm/lakeon/templates/deployment-api.yaml
git commit -m "feat(deploy): pass embedding API URL to API server for knowledge jobs"
```

---

### Task 8: Update hwstaff site values to enable services

**Files:**
- Modify: `deploy/cce/sites/hwstaff/values.yaml`

- [ ] **Step 1: Update the embedding, llm, and memory sections**

In `deploy/cce/sites/hwstaff/values.yaml`, replace the embedding and memory sections (lines 236-244) with:

```yaml
embedding:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:0.3.0
  cpu: "2"
  memory: "6Gi"
  apiUrl: "http://embedding-svc:8000/v1/embeddings"
  model: "BAAI/bge-m3"

llm:
  enabled: true
  image: vllm/vllm-openai:latest
  model: "Qwen/Qwen3.5-9B"
  maxModelLen: 8192
  gpuMemoryUtilization: 0.6
  hfMirror: "https://hf-mirror.com"

memory:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.2.4
  chatApiUrl: "http://llm-svc:8080/v1"
  chatModel: "Qwen/Qwen3.5-9B"
```

Key decisions:
- `llm.image: vllm/vllm-openai:latest` — use official vLLM image directly (no custom build needed, model downloaded at startup)
- `llm.hfMirror` — China mirror for HuggingFace model download
- `embedding.apiUrl` — internal URL, used by both memory-svc and API (for knowledge jobs)
- `memory.chatApiUrl` — internal vLLM URL

- [ ] **Step 2: Commit**

```bash
git add deploy/cce/sites/hwstaff/values.yaml
git commit -m "feat(deploy): enable self-hosted embedding + LLM on hwstaff"
```

---

### Task 9: Build embedding image, deploy, and verify

This task is manual/operational — run commands and verify.

- [ ] **Step 1: Build and push embedding-svc image**

```bash
./deploy/cce/build-and-push-embedding.sh
```

Expected: `swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:0.3.0` pushed.

- [ ] **Step 2: Deploy with Helm**

```bash
./deploy/cce/deploy.sh --skip-test
```

Expected: Helm upgrade succeeds. Three new/updated pods:
- `embedding-svc` — should start within 2 min (model baked in)
- `llm-svc` — may take 5-10 min (downloading Qwen3.5-9B ~18GB on first start)
- `memory-svc` — restarts with new env vars

- [ ] **Step 3: Wait for pods to be ready**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pods -n lakeon -w | grep -E "embedding|llm|memory"
```

Wait until all three show `Running` and `1/1 Ready`.

- [ ] **Step 4: Verify embedding-svc**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/lakeon-api -- \
  curl -s http://embedding-svc:8000/v1/embeddings \
    -H "Content-Type: application/json" \
    -d '{"input": "test embedding", "model": "bge-m3"}' | python3 -c "
import sys, json
r = json.load(sys.stdin)
assert len(r['data'][0]['embedding']) == 1024
print('embedding-svc OK: 1024-dim')
"
```

- [ ] **Step 5: Verify llm-svc**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/lakeon-api -- \
  curl -s http://llm-svc:8080/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{"model": "Qwen/Qwen3.5-9B", "messages": [{"role": "user", "content": "Say hello"}], "max_tokens": 32}' | python3 -c "
import sys, json
r = json.load(sys.stdin)
print('llm-svc OK:', r['choices'][0]['message']['content'][:80])
"
```

- [ ] **Step 6: Verify memory-svc ingest (end-to-end)**

```bash
API_KEY=$(python3 -c 'import json; print(json.load(open("/Users/jacky/.dbay/config.json"))["api_key"])')
MEM_ID=$(python3 -c 'import json; print(json.load(open("/Users/jacky/.dbay/config.json"))["memory_base"])')

curl -s -k -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -X POST "https://api.dbay.cloud:8443/api/v1/memory/bases/$MEM_ID/ingest" \
  -d '{"content": "self-hosted embedding test", "signal": "memory", "source": "test", "memory_type": "fact", "importance": 0.1}'
```

Expected: `{"status": "stored", "memory_id": ...}` — no more 500 error.

- [ ] **Step 7: Verify memory-svc recall**

```bash
curl -s -k -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -X POST "https://api.dbay.cloud:8443/api/v1/memory/bases/$MEM_ID/recall" \
  -d '{"query": "self-hosted embedding", "limit": 3}'
```

Expected: Returns matching memories.

- [ ] **Step 8: Commit all changes (if any fixups needed)**

```bash
git add -A && git commit -m "fix: deployment adjustments after GPU inference verification"
```

---

### Task 10: Clean up test memory and verify GPU resource usage

- [ ] **Step 1: Delete test memory**

```bash
# Find and delete the test memory created in Task 9
curl -s -k -H "Authorization: Bearer $API_KEY" \
  "https://api.dbay.cloud:8443/api/v1/memory/bases/$MEM_ID/memories?limit=5" | python3 -c "
import sys, json
memories = json.load(sys.stdin)
for m in memories:
    if 'self-hosted embedding test' in m.get('content', ''):
        print(f'Delete memory {m[\"id\"]}')
"
```

- [ ] **Step 2: Verify GPU utilization**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/llm-svc -- nvidia-smi
```

Expected: Shows V100 with vLLM process using ~18-20GB, embedding-svc process using ~2-3GB (if GPU mode), total under 32GB.

- [ ] **Step 3: Final commit**

```bash
git add -A && git commit -m "feat: self-hosted GPU inference (embedding + LLM) deployed"
```
