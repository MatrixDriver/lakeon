# Knowledge Pipeline MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users upload PDF/DOCX/Markdown documents, which are automatically parsed, chunked, embedded, and stored in their PG database. A search API provides hybrid retrieval (pgvector + pg_search + RRF).

**Architecture:** Three components — (1) API layer in Java (document management + OBS presigned URL + search endpoint), (2) Python Job Pod (parse + chunk + call embedding service + write PG), (3) Python Embedding Service (BGE-M3 + FastAPI). All use the existing Job Framework for orchestration.

**Tech Stack:** Java 17 / Spring Boot 3.3.5 (API), Python 3.12 (Job Pod + Embedding), Fabric8 K8s, Marker (PDF), python-docx (DOCX), sentence-transformers + BGE-M3, psycopg2, boto3, FastAPI

**Spec:** `docs/superpowers/specs/2026-03-18-knowledge-pipeline-design.md`

---

## File Structure

### API Layer (Java, lakeon-api)

| File | Responsibility |
|------|---------------|
| Create: `src/main/java/com/lakeon/knowledge/DocumentEntity.java` | JPA entity for documents table |
| Create: `src/main/java/com/lakeon/knowledge/DocumentRepository.java` | Spring Data repository |
| Create: `src/main/java/com/lakeon/knowledge/DocumentStatus.java` | Enum: PENDING, PROCESSING, READY, FAILED |
| Create: `src/main/java/com/lakeon/knowledge/KnowledgeService.java` | Business logic: upload URL, process, search |
| Create: `src/main/java/com/lakeon/knowledge/KnowledgeController.java` | REST endpoints |
| Modify: `src/main/resources/application.yml` | Add knowledge config (embedding service URL) |
| Modify: `src/main/java/com/lakeon/config/LakeonProperties.java` | Add KnowledgeConfig |

All `src/main/java` paths relative to `lakeon-api/`.

### Embedding Service (Python, new directory)

| File | Responsibility |
|------|---------------|
| Create: `knowledge/embedding-service/main.py` | FastAPI app with /embed endpoint |
| Create: `knowledge/embedding-service/Dockerfile` | Image with BGE-M3 baked in |
| Create: `knowledge/embedding-service/requirements.txt` | Dependencies |
| Create: `deploy/helm/lakeon/templates/embedding-service.yaml` | K8s Deployment + Service |

### Knowledge Job Pod (Python, new directory)

| File | Responsibility |
|------|---------------|
| Create: `knowledge/job/main.py` | Entry point: orchestrate parse → chunk → embed → write |
| Create: `knowledge/job/parser.py` | PDF (Marker) / DOCX / Markdown parsing |
| Create: `knowledge/job/chunker.py` | Structure-aware chunking |
| Create: `knowledge/job/writer.py` | Write chunks to user PG + create indexes |
| Create: `knowledge/job/callback.py` | HTTP callback to API |
| Create: `knowledge/job/Dockerfile` | Image |
| Create: `knowledge/job/requirements.txt` | Dependencies |

---

### Task 1: Embedding Service

**Files:**
- Create: `knowledge/embedding-service/main.py`
- Create: `knowledge/embedding-service/requirements.txt`
- Create: `knowledge/embedding-service/Dockerfile`

- [ ] **Step 1: Create requirements.txt**

```
sentence-transformers==3.4.1
fastapi==0.115.0
uvicorn==0.34.0
```

- [ ] **Step 2: Create main.py**

```python
import os
import logging
from typing import List
from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

MODEL_NAME = os.environ.get("MODEL_NAME", "BAAI/bge-m3")
BATCH_SIZE = int(os.environ.get("BATCH_SIZE", "32"))

app = FastAPI()
model = None

class EmbedRequest(BaseModel):
    texts: List[str]

class EmbedResponse(BaseModel):
    embeddings: List[List[float]]

@app.on_event("startup")
def load_model():
    global model
    logger.info(f"Loading model {MODEL_NAME}...")
    model = SentenceTransformer(MODEL_NAME)
    logger.info(f"Model loaded. Dimension: {model.get_sentence_embedding_dimension()}")

@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "ready": model is not None}

@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        return EmbedResponse(embeddings=[])
    embeddings = model.encode(req.texts, batch_size=BATCH_SIZE, normalize_embeddings=True)
    return EmbedResponse(embeddings=embeddings.tolist())
```

- [ ] **Step 3: Create Dockerfile**

```dockerfile
FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download model at build time
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

COPY main.py .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 4: Build and test locally**

```bash
cd knowledge/embedding-service
docker build -t lakeon-embedding:local .
docker run --rm -p 8000:8000 lakeon-embedding:local &
sleep 30  # model loading takes time
curl -s -X POST http://localhost:8000/embed \
  -H "Content-Type: application/json" \
  -d '{"texts":["hello world","你好世界"]}' | python3 -c "
import sys,json; d=json.load(sys.stdin)
print(f'Vectors: {len(d[\"embeddings\"])}, Dim: {len(d[\"embeddings\"][0])}')
assert len(d['embeddings']) == 2
assert len(d['embeddings'][0]) == 1024
print('OK')
"
docker stop $(docker ps -q --filter ancestor=lakeon-embedding:local)
```

Expected: `Vectors: 2, Dim: 1024` then `OK`

- [ ] **Step 5: Commit**

```bash
git add knowledge/embedding-service/
git commit -m "feat(knowledge): embedding service with BGE-M3 + FastAPI"
```

---

### Task 2: Embedding Service Helm template

**Files:**
- Create: `deploy/helm/lakeon/templates/embedding-service.yaml`
- Modify: `deploy/helm/lakeon/values.yaml` (or site values)

- [ ] **Step 1: Create K8s manifests**

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
        lakeon/role: compute
      containers:
        - name: embedding
          image: {{ .Values.embedding.image }}
          ports:
            - containerPort: 8000
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

- [ ] **Step 2: Add embedding values**

Add to values files:
```yaml
embedding:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:latest
  cpu: "2"
  memory: "4Gi"
```

- [ ] **Step 3: Commit**

```bash
git add deploy/helm/lakeon/templates/embedding-service.yaml
git commit -m "feat(knowledge): embedding service Helm template"
```

---

### Task 3: Knowledge Job Pod — parser, chunker, writer

**Files:**
- Create: `knowledge/job/main.py`
- Create: `knowledge/job/parser.py`
- Create: `knowledge/job/chunker.py`
- Create: `knowledge/job/writer.py`
- Create: `knowledge/job/callback.py`
- Create: `knowledge/job/requirements.txt`
- Create: `knowledge/job/Dockerfile`

- [ ] **Step 1: Create requirements.txt**

```
marker-pdf==1.6.2
python-docx==1.1.2
psycopg2-binary==2.9.10
boto3==1.36.0
requests==2.32.3
```

- [ ] **Step 2: Create parser.py**

```python
"""Document parsing: PDF (Marker), DOCX (python-docx), Markdown (direct read)."""
import os
import logging

logger = logging.getLogger(__name__)

def parse_document(file_path: str, format: str) -> str:
    """Parse a document and return Markdown text."""
    format = format.upper()
    if format == "PDF":
        return _parse_pdf(file_path)
    elif format == "DOCX":
        return _parse_docx(file_path)
    elif format == "MARKDOWN":
        return _parse_markdown(file_path)
    else:
        raise ValueError(f"Unsupported format: {format}")

def _parse_pdf(file_path: str) -> str:
    from marker.converters.pdf import PdfConverter
    from marker.models import create_model_dict

    models = create_model_dict()
    converter = PdfConverter(artifact_dict=models)
    rendered = converter(file_path)
    return rendered.markdown

def _parse_docx(file_path: str) -> str:
    from docx import Document
    doc = Document(file_path)
    parts = []
    for para in doc.paragraphs:
        style = para.style.name if para.style else ""
        text = para.text.strip()
        if not text:
            continue
        if "Heading 1" in style:
            parts.append(f"# {text}")
        elif "Heading 2" in style:
            parts.append(f"## {text}")
        elif "Heading 3" in style:
            parts.append(f"### {text}")
        else:
            parts.append(text)
    # Tables
    for table in doc.tables:
        rows = []
        for row in table.rows:
            cells = [cell.text.strip() for cell in row.cells]
            rows.append("| " + " | ".join(cells) + " |")
        if rows:
            header_sep = "| " + " | ".join(["---"] * len(table.rows[0].cells)) + " |"
            parts.append(rows[0])
            parts.append(header_sep)
            parts.extend(rows[1:])
    return "\n\n".join(parts)

def _parse_markdown(file_path: str) -> str:
    with open(file_path, "r", encoding="utf-8") as f:
        return f.read()
```

- [ ] **Step 3: Create chunker.py**

```python
"""Structure-aware document chunking."""
import re
import logging
from typing import List, Dict, Any

logger = logging.getLogger(__name__)

MAX_CHUNK_TOKENS = 400
OVERLAP_RATIO = 0.15

def chunk_document(markdown: str, filename: str, format: str) -> List[Dict[str, Any]]:
    """Split markdown into structure-aware chunks with metadata."""
    sections = _split_by_headings(markdown)
    chunks = []
    chunk_index = 0

    for section in sections:
        heading = section["heading"]
        content = section["content"].strip()
        if not content:
            continue

        # Split into blocks (paragraphs, code blocks, tables)
        blocks = _split_into_blocks(content)

        current_chunk = ""
        for block in blocks:
            # Code blocks and tables are kept intact
            if block.startswith("```") or block.startswith("|"):
                if current_chunk.strip():
                    chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format, chunk_index))
                    chunk_index += 1
                chunks.append(_make_chunk(block, heading, filename, format, chunk_index))
                chunk_index += 1
                current_chunk = ""
                continue

            # Check if adding this block exceeds limit
            combined = (current_chunk + "\n\n" + block).strip() if current_chunk else block
            if _estimate_tokens(combined) > MAX_CHUNK_TOKENS and current_chunk.strip():
                chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format, chunk_index))
                chunk_index += 1
                # Overlap: keep last ~15% of previous chunk
                overlap = _get_overlap(current_chunk)
                current_chunk = overlap + "\n\n" + block if overlap else block
            else:
                current_chunk = combined

        if current_chunk.strip():
            chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format, chunk_index))
            chunk_index += 1

    logger.info(f"Chunked '{filename}' into {len(chunks)} chunks")
    return chunks

def _split_by_headings(markdown: str) -> List[Dict[str, str]]:
    """Split markdown by heading boundaries."""
    lines = markdown.split("\n")
    sections = []
    current_heading = ""
    current_lines = []

    for line in lines:
        if re.match(r"^#{1,3}\s+", line):
            if current_lines:
                sections.append({"heading": current_heading, "content": "\n".join(current_lines)})
            current_heading = line.strip().lstrip("#").strip()
            current_lines = []
        else:
            current_lines.append(line)

    if current_lines:
        sections.append({"heading": current_heading, "content": "\n".join(current_lines)})

    return sections

def _split_into_blocks(content: str) -> List[str]:
    """Split content into paragraphs, code blocks, and tables."""
    blocks = []
    lines = content.split("\n")
    current_block = []
    in_code = False

    for line in lines:
        if line.startswith("```"):
            if in_code:
                current_block.append(line)
                blocks.append("\n".join(current_block))
                current_block = []
                in_code = False
            else:
                if current_block:
                    text = "\n".join(current_block).strip()
                    if text:
                        blocks.append(text)
                    current_block = []
                current_block.append(line)
                in_code = True
        elif in_code:
            current_block.append(line)
        elif line.startswith("|"):
            current_block.append(line)
        elif not line.strip():
            if current_block:
                text = "\n".join(current_block).strip()
                if text:
                    blocks.append(text)
                current_block = []
        else:
            if current_block and current_block[-1].startswith("|") and not line.startswith("|"):
                blocks.append("\n".join(current_block))
                current_block = [line]
            else:
                current_block.append(line)

    if current_block:
        text = "\n".join(current_block).strip()
        if text:
            blocks.append(text)

    return blocks

def _make_chunk(content: str, section: str, filename: str, format: str, index: int) -> Dict[str, Any]:
    return {
        "content": content,
        "chunk_index": index,
        "metadata": {
            "filename": filename,
            "section": section,
            "format": format,
        }
    }

def _estimate_tokens(text: str) -> int:
    """Rough token estimate: ~0.75 tokens per char for English, ~1.5 for CJK."""
    cjk = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    non_cjk = len(text) - cjk
    return int(non_cjk * 0.25 + cjk * 1.5)

def _get_overlap(text: str) -> str:
    """Return last ~15% of text for chunk overlap."""
    target = int(len(text) * OVERLAP_RATIO)
    if target < 50:
        return ""
    return text[-target:]
```

- [ ] **Step 4: Create writer.py**

```python
"""Write chunks to user's PostgreSQL database."""
import json
import logging
from typing import List, Dict, Any

import psycopg2
from psycopg2.extras import execute_values

logger = logging.getLogger(__name__)

SETUP_SQL = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id SERIAL PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON knowledge_chunks
    USING hnsw (embedding vector_cosine_ops);
"""

def write_chunks(connstr: str, document_id: str, chunks: List[Dict[str, Any]], embeddings: List[List[float]]):
    """Write chunks + embeddings to user's PG. Creates table if not exists."""
    conn = psycopg2.connect(connstr)
    try:
        conn.autocommit = False
        with conn.cursor() as cur:
            # Setup table and indexes
            cur.execute(SETUP_SQL)

            # Delete any existing chunks for this document (idempotent re-run)
            cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))

            # Batch insert
            values = []
            for chunk, embedding in zip(chunks, embeddings):
                values.append((
                    document_id,
                    chunk["chunk_index"],
                    chunk["content"],
                    str(embedding),  # pgvector accepts string format [0.1, 0.2, ...]
                    json.dumps(chunk["metadata"]),
                ))

            execute_values(
                cur,
                """INSERT INTO knowledge_chunks
                   (document_id, chunk_index, content, embedding, metadata)
                   VALUES %s""",
                values,
                template="(%s, %s, %s, %s::vector, %s::jsonb)",
            )

            conn.commit()
            logger.info(f"Wrote {len(values)} chunks for document {document_id}")

    except Exception:
        conn.rollback()
        # Clean up partial writes
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
                conn.commit()
        except Exception:
            pass
        raise
    finally:
        conn.close()

def delete_chunks(connstr: str, document_id: str):
    """Delete all chunks for a document."""
    conn = psycopg2.connect(connstr)
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
            conn.commit()
    finally:
        conn.close()
```

- [ ] **Step 5: Create callback.py**

```python
"""Callback to Lakeon API after job completion."""
import os
import json
import logging
import requests

logger = logging.getLogger(__name__)

def report_success(chunks_count: int):
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    payload = {
        "token": token,
        "status": "SUCCEEDED",
        "result": {"chunks_count": chunks_count},
    }
    resp = requests.post(url, json=payload, timeout=30)
    logger.info(f"Callback SUCCEEDED: {resp.status_code}")

def report_progress(message: str, progress: float = 0):
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    payload = {
        "token": token,
        "status": "RUNNING",
        "result": {"progress": progress, "message": message},
    }
    try:
        requests.post(url, json=payload, timeout=10)
    except Exception as e:
        logger.warning(f"Progress callback failed: {e}")

def report_failure(error: str):
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    payload = {
        "token": token,
        "status": "FAILED",
        "error": error,
    }
    resp = requests.post(url, json=payload, timeout=30)
    logger.info(f"Callback FAILED: {resp.status_code}")
```

- [ ] **Step 6: Create main.py**

```python
"""Knowledge Pipeline Job: parse → chunk → embed → write → callback."""
import os
import sys
import json
import logging
import tempfile

import boto3
import requests

from parser import parse_document
from chunker import chunk_document
from callback import report_success, report_failure, report_progress

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logger = logging.getLogger("knowledge-job")

def main():
    try:
        # 1. Read params
        with open("/etc/job/params.json") as f:
            params = json.load(f)

        document_id = params["document_id"]
        obs_key = params["obs_key"]
        format = params["format"]
        database_connstr = params["database_connstr"]
        embedding_service_url = params["embedding_service_url"]
        filename = params.get("filename", os.path.basename(obs_key))

        logger.info(f"Processing document {document_id}: {filename} ({format})")
        report_progress("Downloading document", 0.1)

        # 2. Download from OBS
        obs_endpoint = os.environ.get("OBS_ENDPOINT", "https://obs.cn-north-4.myhuaweicloud.com")
        obs_ak = os.environ["OBS_ACCESS_KEY"]
        obs_sk = os.environ["OBS_SECRET_KEY"]

        # Extract bucket from obs_key (format: "bucket/path/to/file") or use env
        obs_bucket = os.environ.get("OBS_BUCKET", "lakeon-storage")

        s3 = boto3.client("s3",
            endpoint_url=obs_endpoint,
            aws_access_key_id=obs_ak,
            aws_secret_access_key=obs_sk,
        )

        with tempfile.NamedTemporaryFile(suffix=f".{format.lower()}", delete=False) as tmp:
            tmp_path = tmp.name
            s3.download_file(obs_bucket, obs_key, tmp_path)
            logger.info(f"Downloaded {obs_key} to {tmp_path}")

        report_progress("Parsing document", 0.2)

        # 3. Parse
        markdown = parse_document(tmp_path, format)
        logger.info(f"Parsed document: {len(markdown)} chars")

        report_progress("Chunking document", 0.4)

        # 4. Chunk
        chunks = chunk_document(markdown, filename, format)
        logger.info(f"Created {len(chunks)} chunks")

        if not chunks:
            report_success(0)
            return

        report_progress(f"Generating embeddings for {len(chunks)} chunks", 0.5)

        # 5. Embed via embedding service
        texts = [c["content"] for c in chunks]
        batch_size = 32
        all_embeddings = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i:i + batch_size]
            resp = requests.post(embedding_service_url, json={"texts": batch}, timeout=120)
            resp.raise_for_status()
            all_embeddings.extend(resp.json()["embeddings"])
            progress = 0.5 + 0.3 * min(i + batch_size, len(texts)) / len(texts)
            report_progress(f"Embedding {min(i + batch_size, len(texts))}/{len(texts)}", progress)

        logger.info(f"Generated {len(all_embeddings)} embeddings")

        report_progress("Writing to database", 0.9)

        # 6. Write to user PG
        from writer import write_chunks
        write_chunks(database_connstr, document_id, chunks, all_embeddings)

        # 7. Success
        report_success(len(chunks))
        logger.info(f"Done: {len(chunks)} chunks written")

    except Exception as e:
        logger.exception(f"Job failed: {e}")
        try:
            report_failure(str(e))
        except Exception:
            logger.exception("Failed to report failure")
        sys.exit(1)
    finally:
        # Cleanup temp file
        try:
            os.unlink(tmp_path)
        except Exception:
            pass

if __name__ == "__main__":
    main()
```

- [ ] **Step 7: Create Dockerfile**

```dockerfile
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1-mesa-glx libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py .

CMD ["python", "main.py"]
```

- [ ] **Step 8: Commit**

```bash
git add knowledge/job/
git commit -m "feat(knowledge): job pod — parse, chunk, embed, write pipeline"
```

---

### Task 4: API Layer — DocumentEntity, Repository, Status

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentStatus.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java`

- [ ] **Step 1: Create DocumentStatus enum**

```java
package com.lakeon.knowledge;

public enum DocumentStatus {
    PENDING,      // Upload URL generated, waiting for file
    PROCESSING,   // Job pod running
    READY,        // Chunks written to user PG
    FAILED        // Processing failed
}
```

- [ ] **Step 2: Create DocumentEntity**

Follow JobEntity pattern. Fields: id (`doc_` + 12 char), tenantId, databaseId, filename, obsKey, format, sizeBytes, status, jobId, chunksCount, error, createdAt, updatedAt.

```java
package com.lakeon.knowledge;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents", indexes = {
    @Index(name = "idx_documents_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_documents_database_id", columnList = "database_id"),
    @Index(name = "idx_documents_status", columnList = "status")
})
public class DocumentEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "database_id", nullable = false, length = 32)
    private String databaseId;

    @Column(nullable = false, length = 256)
    private String filename;

    @Column(name = "obs_key", length = 512)
    private String obsKey;

    @Column(length = 16)
    private String format;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentStatus status;

    @Column(name = "job_id", length = 32)
    private String jobId;

    @Column(name = "chunks_count")
    private Integer chunksCount;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters for all fields
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDatabaseId() { return databaseId; }
    public void setDatabaseId(String databaseId) { this.databaseId = databaseId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getObsKey() { return obsKey; }
    public void setObsKey(String obsKey) { this.obsKey = obsKey; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public Integer getChunksCount() { return chunksCount; }
    public void setChunksCount(Integer chunksCount) { this.chunksCount = chunksCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: Create DocumentRepository**

```java
package com.lakeon.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
    Optional<DocumentEntity> findByIdAndTenantId(String id, String tenantId);
    List<DocumentEntity> findAllByTenantIdAndDatabaseIdOrderByCreatedAtDesc(String tenantId, String databaseId);
    List<DocumentEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
}
```

- [ ] **Step 4: Compile check**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/
git commit -m "feat(knowledge): DocumentEntity, Repository, Status"
```

---

### Task 5: API Layer — KnowledgeService + KnowledgeController

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add KnowledgeConfig to LakeonProperties**

Read `LakeonProperties.java` first. Add:

```java
private KnowledgeConfig knowledge = new KnowledgeConfig();
public KnowledgeConfig getKnowledge() { return knowledge; }
public void setKnowledge(KnowledgeConfig knowledge) { this.knowledge = knowledge; }

public static class KnowledgeConfig {
    private String embeddingServiceUrl = "http://embedding-svc.lakeon.svc.cluster.local:8000/embed";
    private int presignExpireSeconds = 900;  // 15 minutes
    private long maxFileSizeBytes = 104857600;  // 100MB

    public String getEmbeddingServiceUrl() { return embeddingServiceUrl; }
    public void setEmbeddingServiceUrl(String embeddingServiceUrl) { this.embeddingServiceUrl = embeddingServiceUrl; }
    public int getPresignExpireSeconds() { return presignExpireSeconds; }
    public void setPresignExpireSeconds(int presignExpireSeconds) { this.presignExpireSeconds = presignExpireSeconds; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
}
```

- [ ] **Step 2: Add knowledge config to application.yml**

```yaml
  knowledge:
    embedding-service-url: ${LAKEON_EMBEDDING_SERVICE_URL:http://embedding-svc.lakeon.svc.cluster.local:8000/embed}
    presign-expire-seconds: ${LAKEON_KNOWLEDGE_PRESIGN_EXPIRE:900}
    max-file-size-bytes: ${LAKEON_KNOWLEDGE_MAX_FILE_SIZE:104857600}
```

- [ ] **Step 3: Create KnowledgeService**

Handles: OBS presigned URL generation, document CRUD, Job submission, search.

Read these files for patterns before writing:
- `JobService.java` — how to submit jobs
- `LakeonProperties.java` — how to access OBS config (`props.getObs()`)
- `DatabaseService.java` — how to resolve database connection info

KnowledgeService needs:
- `DocumentRepository` — document CRUD
- `JobService` — submit DOCUMENT_PARSE job
- `LakeonProperties` — OBS config, knowledge config
- `DatabaseRepository` — resolve database for connection info
- `ComputePodManager` — get compute pod connection info for user PG

Key methods:
- `generateUploadUrl(tenant, databaseId, filename)` — create doc record + OBS presigned PUT URL
- `processDocument(tenant, documentId)` — submit Job with params (document_id, obs_key, format, database_connstr, embedding_service_url)
- `listDocuments(tenantId, databaseId)` — list docs
- `getDocument(tenantId, documentId)` — get doc + sync Job status
- `deleteDocument(tenantId, documentId)` — delete doc + chunks (via user PG) + OBS file
- `search(tenantId, databaseId, query, topK, documentIds)` — connect to user PG, execute RRF SQL

For OBS presigned URL: use AWS SDK (S3Presigner) since OBS is S3-compatible. Or use boto3-style in Java (software.amazon.awssdk:s3).

**IMPORTANT**: For search, API needs to:
1. Call embedding service to get query vector
2. Connect to user PG via compute pod
3. Execute RRF SQL
4. Return results

- [ ] **Step 4: Create KnowledgeController**

REST controller at `/api/v1/knowledge`:
- `GET /upload-url?filename=X&database_id=Y` — returns presigned URL + document_id
- `POST /documents/{id}/process` — trigger processing
- `GET /documents?database_id=Y` — list
- `GET /documents/{id}` — detail
- `DELETE /documents/{id}` — delete
- `POST /search` — body: {database_id, query, top_k, document_ids}

- [ ] **Step 5: Compile check**

Run: `cd lakeon-api && mvn compile -q`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java
git add lakeon-api/src/main/resources/application.yml
git commit -m "feat(knowledge): KnowledgeService and KnowledgeController"
```

---

### Task 6: OBS Presigned URL Generation

**Files:**
- Modify: `lakeon-api/pom.xml` (add AWS S3 SDK dependency if not present)
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`

- [ ] **Step 1: Check if AWS S3 SDK is already in pom.xml**

Read `lakeon-api/pom.xml` and search for `software.amazon.awssdk` or `com.amazonaws`. If not present, add:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.29.0</version>
</dependency>
```

- [ ] **Step 2: Implement presigned URL in KnowledgeService**

```java
private String generatePresignedPutUrl(String obsKey) {
    S3Presigner presigner = S3Presigner.builder()
        .endpointOverride(URI.create(props.getObs().getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
        .region(Region.of("cn-north-4"))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();

    PutObjectRequest putReq = PutObjectRequest.builder()
        .bucket(props.getObs().getBucket())
        .key(obsKey)
        .build();

    PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
        .signatureDuration(Duration.ofSeconds(props.getKnowledge().getPresignExpireSeconds()))
        .putObjectRequest(putReq)
        .build();

    return presigner.presignPutObject(presignReq).url().toString();
}
```

- [ ] **Step 3: Compile check and commit**

```bash
cd lakeon-api && mvn compile -q
git add lakeon-api/pom.xml lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(knowledge): OBS presigned URL generation"
```

---

### Task 7: Search Endpoint — RRF Fusion

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`

- [ ] **Step 1: Implement search method**

Search flow:
1. Call embedding service HTTP to get query vector
2. Resolve user PG connection (via DatabaseEntity → compute pod → JDBC connstr)
3. Execute RRF SQL
4. Return results

```java
public List<Map<String, Object>> search(String tenantId, String databaseId,
                                         String query, int topK, List<String> documentIds) {
    // 1. Get query embedding from embedding service
    float[] queryVector = getQueryEmbedding(query);

    // 2. Resolve user PG connection
    String connstr = resolveUserPgConnection(tenantId, databaseId);

    // 3. Execute RRF SQL via JDBC
    String vectorStr = arrayToVectorString(queryVector);
    String sql = buildRrfSql(topK, documentIds);

    try (Connection conn = DriverManager.getConnection(connstr);
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, vectorStr);  // semantic query
        ps.setString(2, vectorStr);  // for ORDER BY
        ps.setString(3, query);      // BM25 query text
        // ... bind doc_ids if provided
        ResultSet rs = ps.executeQuery();
        // ... map results
    }
}

private float[] getQueryEmbedding(String query) {
    // HTTP POST to embedding service
    RestTemplate rest = new RestTemplate();
    Map<String, Object> body = Map.of("texts", List.of(query));
    Map response = rest.postForObject(props.getKnowledge().getEmbeddingServiceUrl(), body, Map.class);
    List<List<Number>> embeddings = (List<List<Number>>) response.get("embeddings");
    // Convert to float[]
}
```

- [ ] **Step 2: Compile and commit**

```bash
cd lakeon-api && mvn compile -q
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(knowledge): search endpoint with RRF fusion"
```

---

### Task 8: Integration — build images, deploy, smoke test

- [ ] **Step 1: Build embedding service image and push to SWR**

```bash
cd knowledge/embedding-service
docker build -t swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:0.1.0 .
docker push swr.cn-north-4.myhuaweicloud.com/flex/lakeon-embedding:0.1.0
```

- [ ] **Step 2: Build knowledge job image and push to SWR**

```bash
cd knowledge/job
docker build -t swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge-job:0.1.0 .
docker push swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge-job:0.1.0
```

- [ ] **Step 3: Update application.yml job image config**

Set `document-parse` image to the knowledge job image:
```yaml
types:
  document-parse:
    image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-knowledge-job:0.1.0
```

- [ ] **Step 4: Build API and deploy**

```bash
IMAGE_TAG=0.x.x ./deploy/cce/build-and-push-api.sh
./deploy/cce/deploy.sh
```

- [ ] **Step 5: Smoke test — upload and process**

```bash
# 1. Get upload URL
curl -s "$API/api/v1/knowledge/upload-url?filename=test.pdf&database_id=$DB_ID" \
  -H "Authorization: Bearer $KEY" | jq .

# 2. Upload file to presigned URL
curl -X PUT "<upload_url>" --upload-file test.pdf

# 3. Trigger processing
curl -s -X POST "$API/api/v1/knowledge/documents/$DOC_ID/process" \
  -H "Authorization: Bearer $KEY" | jq .

# 4. Check status (poll until READY)
curl -s "$API/api/v1/knowledge/documents/$DOC_ID" \
  -H "Authorization: Bearer $KEY" | jq .

# 5. Search
curl -s -X POST "$API/api/v1/knowledge/search" \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"database_id":"'$DB_ID'","query":"test query","top_k":5}' | jq .
```

- [ ] **Step 6: Final commit**

```bash
git add -A
git commit -m "feat(knowledge): Knowledge Pipeline MVP complete"
```
