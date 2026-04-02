# 自托管 GPU 推理服务设计（Embedding + LLM）

> 日期：2026-04-02
> 状态：待实施

## 1. 目标

在 CCE 集群内部署自托管的 embedding 和 LLM 推理服务，彻底替掉硅基流云（SiliconFlow）外部依赖。解决硅基流云 API key 过期/欠费导致 memory-svc 和 knowledge job 全面不可用的问题。

## 2. 背景

### 2.1 当前架构（外部依赖）

```
memory-svc ──→ api.siliconflow.cn/v1/embeddings  (bge-m3)
memory-svc ──→ api.siliconflow.cn/v1/chat/completions  (Qwen2.5-7B)
knowledge job ──→ api.siliconflow.cn/v1/embeddings  (bge-m3)
```

所有 embedding 和 chat 请求都依赖硅基流云的同一个 API key，单点故障。

### 2.2 影响范围

| 组件 | Embedding | Chat LLM |
|---|---|---|
| memory-svc ingest/recall | 核心路径，阻塞 | — |
| memory-svc background_extract | — | 后台异步提取记忆 |
| memory-svc digest | — | 手动触发生成 traits |
| knowledge job | 核心路径，阻塞 | 未使用（L1/L2 摘要待实施） |

### 2.3 已有基础

- `knowledge/embedding-service/` 已有完整代码（bge-m3 + reranker），Helm 模板已就绪但 `enabled: false`
- Helm values 中 `embedding.apiUrl` 和 `embedding.model` 已参数化
- 数据库 schema 统一 `vector(1024)`，无需迁移

## 3. GPU 基础设施

| 项目 | 值 |
|---|---|
| 节点 | 192.168.0.224 (p2sne.2xlarge.8) |
| GPU | NVIDIA V100 32GB PCIe |
| CPU/内存 | 8C / 64GB |
| 标签 | `lakeon/role=gpu`, `accelerator=nvidia-v100-pcie-32gb` |
| NVIDIA 插件 | driver-installer + device-plugin + operator 已安装 |
| 节点池 | dbay-cce-gpu-v100-37370 |

## 4. 服务架构

### 4.1 目标架构

```
                    ┌─────────────────────────────────────────────┐
                    │         GPU 节点 (V100 32GB)                 │
                    │                                              │
                    │  ┌──────────────┐   ┌─────────────────────┐ │
                    │  │ embedding-svc│   │      llm-svc        │ │
                    │  │ bge-m3 FP16  │   │ Qwen3.5-9B FP16    │ │
                    │  │ ~2.5GB VRAM  │   │ ~18GB VRAM          │ │
                    │  │ :8000        │   │ :8080               │ │
                    │  └──────┬───────┘   └──────┬──────────────┘ │
                    └─────────┼──────────────────┼────────────────┘
                              │                  │
              ┌───────────────┼──────────────────┼───────────┐
              │               │                  │           │
     ┌────────▼──┐   ┌───────▼──┐   ┌───────────▼──┐  ┌────▼──────┐
     │ memory-svc│   │knowledge │   │ knowledge    │  │ (未来     │
     │ embedding │   │   job    │   │ L1/L2 摘要   │  │  调用方)  │
     │ + chat    │   │embedding │   │ (待实施)     │  │           │
     └───────────┘   └──────────┘   └──────────────┘  └───────────┘
```

### 4.2 VRAM 预算

| 组件 | VRAM |
|---|---|
| bge-m3 (568M, FP16) | ~2.5GB |
| Qwen3.5-9B (FP16) | ~18GB |
| vLLM KV cache / overhead | ~8GB |
| **合计** | ~28.5GB / 32GB |

### 4.3 两个服务的职责

**embedding-svc（已有代码，需适配）**
- 模型：BAAI/bge-m3，FP16，GPU 推理
- 端口：8000
- 接口：
  - `POST /v1/embeddings`（新增，OpenAI 兼容格式，调用方零改动）
  - `POST /embed`（保留，原有批量接口）
  - `POST /rerank`（保留，bge-reranker-v2-m3，按需启用）
  - `GET /health`
- 调度：`nodeSelector: lakeon/role: gpu`

**llm-svc（新服务）**
- 模型：Qwen/Qwen3.5-9B，FP16，vLLM 推理引擎
- 端口：8080
- 接口：`/v1/chat/completions`（vLLM 原生 OpenAI 兼容）
- 调度：`nodeSelector: lakeon/role: gpu`，`resources.limits: nvidia.com/gpu: 1`
- 配置：关闭 thinking mode（摘要/提取任务不需要）

## 5. 改动清单

### 5.1 embedding-svc 改动

**main.py**：新增 `/v1/embeddings` 端点，接收 OpenAI 格式请求，返回 OpenAI 格式响应：
```
请求：{"model": "bge-m3", "input": "text" | ["text1", "text2"]}
响应：{"data": [{"embedding": [...], "index": 0}], "model": "bge-m3", "usage": {...}}
```

**Dockerfile**：取消注释预烘焙模型行，构建时下载模型到镜像内。

**Helm template (embedding-service.yaml)**：
- 添加 `nvidia.com/gpu` 资源请求（可选，bge-m3 用 GPU 更快但不强制）
- nodeSelector 改为 `lakeon/role: gpu`

### 5.2 llm-svc（新增）

**Helm template (llm-service.yaml)**：新建
- 容器镜像：`vllm/vllm-openai:latest`（官方镜像）或自建镜像
- 启动命令：
  ```
  vllm serve Qwen/Qwen3.5-9B \
    --port 8080 \
    --tensor-parallel-size 1 \
    --max-model-len 8192 \
    --gpu-memory-utilization 0.6 \
    --chat-template-kwargs '{"enable_thinking": false}'
  ```
- `nvidia.com/gpu: 1`
- nodeSelector: `lakeon/role: gpu`
- 模型通过 PVC 或镜像内预下载

**说明**：
- `--max-model-len 8192`：摘要/提取任务不需要长 context，省 KV cache 显存
- `--gpu-memory-utilization 0.6`：给 embedding-svc 留 GPU 显存空间

### 5.3 Helm values 改动 (values-cce.yaml)

```yaml
embedding:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:0.3.0
  cpu: "2"
  memory: "4Gi"
  apiUrl: "http://embedding-svc:8000/v1/embeddings"  # 内网地址
  model: "BAAI/bge-m3"

llm:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-llm:0.1.0
  model: "Qwen/Qwen3.5-9B"
  maxModelLen: 8192
  gpuMemoryUtilization: 0.6

memory:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.2.5
  chatApiUrl: "http://llm-svc:8080/v1"
  chatModel: "Qwen/Qwen3.5-9B"
```

### 5.4 memory-svc 改动

**无代码改动**。通过环境变量切换：
- `EMBEDDING_API_URL` → `http://embedding-svc:8000/v1/embeddings`
- `EMBEDDING_API_KEY` → 空（内网无需认证）
- `CHAT_API_URL` → `http://llm-svc:8080/v1`
- `CHAT_API_KEY` → 空

### 5.5 knowledge job 改动

**无代码改动**。job 启动参数中 `embedding_api_url` 指向内网地址即可。

## 6. 镜像构建

### 6.1 embedding-svc 镜像

基于现有 `knowledge/embedding-service/Dockerfile`，启用模型预烘焙：
```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"
COPY main.py .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

镜像大小约 3-4GB（含模型权重）。

### 6.2 llm-svc 镜像

方案 A（推荐）：基于 vLLM 官方镜像，预下载模型
```dockerfile
FROM vllm/vllm-openai:latest
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('Qwen/Qwen3.5-9B')"
```

方案 B：直接用官方镜像 + PVC 挂载模型（避免大镜像推送到 SWR）

镜像大小约 20-25GB（含模型权重），推荐方案 B 减少 SWR 存储成本。

## 7. 模型下载策略

由于模型文件较大（bge-m3 ~2.2GB，Qwen3.5-9B ~18GB），推荐：

1. **在 GPU 节点上创建 PVC** 挂载到 `/models`
2. **Init container** 或一次性 Job 下载模型到 PVC
3. embedding-svc 和 llm-svc 都挂载同一个 PVC 的不同子路径
4. HuggingFace 在国内可能需要镜像源（hf-mirror.com）

## 8. 不变的部分

- 数据库 schema 不变（`vector(1024)` 维度不变）
- memory-svc 代码不变（纯环境变量切换）
- knowledge job 代码不变（纯参数切换）
- 现有 CPU 节点上的服务不受影响

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| GPU 节点故障导致 embedding/LLM 不可用 | 保留硅基流云配置作为 fallback（充值后可快速切回） |
| 模型下载慢/失败（HuggingFace 国内访问） | 使用 hf-mirror.com 镜像源，或提前下载到 OBS |
| V100 不支持 BF16 | 使用 FP16，vLLM 自动适配 |
| 两个服务争抢 GPU 显存 | vLLM `--gpu-memory-utilization 0.6` 限制上限 |

## 10. 后续迭代

1. **知识库 L1/L2 摘要**（已有设计：2026-03-28-knowledge-hierarchical-summary-design.md）：llm-svc 就绪后可直接实施
2. **Reranker 启用**：embedding-svc 已内置 bge-reranker-v2-m3，需要时设 `RERANK_ENABLED=true`
3. **模型升级**：后续可升级到 gte-Qwen2-1.5B（embedding）或更大 LLM，只需换镜像/模型路径
