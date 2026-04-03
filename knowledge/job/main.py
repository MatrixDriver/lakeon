"""Knowledge Pipeline Job: parse -> chunk -> embed -> write -> callback."""
import os
import sys
import json
import logging
import tempfile
import time

import boto3
import requests

from parser import parse_document
from chunker import chunk_document, assign_pages, detect_duplicates
from callback import StageTracker, report_success, report_success_batch, report_failure, report_progress
from writer import write_chunks

try:
    from lakeon_log import setup_logging
    logger = setup_logging(component="knowledge-pipeline")
except ImportError:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    logger = logging.getLogger("knowledge-pipeline")

ANOMALY_SHORT_THRESHOLD = 80
ANOMALY_LONG_THRESHOLD = 800


def _parse_iso(iso_str):
    """Parse ISO timestamp to epoch seconds."""
    from datetime import datetime, timezone
    dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
    return dt.timestamp()


def _ensure_compute_ready(connstr, retries=5, wait=15):
    """Try connecting to the database; if compute pod was suspended, the API's
    orphan-check or KbWriteQueue will re-wake it. We just need to wait and retry."""
    import psycopg2

    # First: trigger a wake via the callback API (best-effort)
    try:
        callback_url = os.environ.get("JOB_CALLBACK_URL", "")
        if callback_url:
            # Extract base URL from callback URL (remove /api/v1/jobs/.../callback)
            base = callback_url.rsplit("/api/v1/jobs/", 1)[0]
            job_id = os.environ.get("JOB_ID", "")
            token = os.environ.get("JOB_CALLBACK_TOKEN", "")
            # Send a progress update — this keeps the job alive and API can detect
            # the compute pod needs to be ready
            requests.post(callback_url, json={
                "token": token, "status": "RUNNING",
                "result": {"progress": 0.88, "message": "Waking compute pod for write..."}
            }, timeout=10, verify=False)
    except Exception as e:
        logger.debug(f"Wake hint failed (non-fatal): {e}")

    for attempt in range(retries):
        try:
            conn = psycopg2.connect(connstr, connect_timeout=30)
            conn.close()
            logger.info("Compute pod is ready")
            return
        except Exception as e:
            if attempt < retries - 1:
                logger.warning(f"Compute not ready ({attempt+1}/{retries}): {e}, retry in {wait}s")
                time.sleep(wait)
            else:
                logger.error(f"Compute not ready after {retries} attempts")
                raise RuntimeError(f"Database connection failed after {retries} retries: {e}")


MAX_EMBED_CHARS = 8000  # BGE-M3 supports ~8192 tokens; truncate to stay under API payload limits


def embed_texts(texts, embedding_api_url, embedding_api_key, embedding_model, batch_size=16):
    """Embed texts via OpenAI-compatible API. Auto-halves batch on 413/timeout.
    Truncates texts exceeding MAX_EMBED_CHARS to avoid payload limits."""
    headers = {"Content-Type": "application/json"}
    if embedding_api_key:
        headers["Authorization"] = f"Bearer {embedding_api_key}"

    all_embeddings = []
    i = 0
    current_batch_size = batch_size
    while i < len(texts):
        batch = [t[:MAX_EMBED_CHARS] for t in texts[i:i + current_batch_size]]
        try:
            resp = requests.post(embedding_api_url, json={
                "model": embedding_model,
                "input": batch,
                "encoding_format": "float"
            }, headers=headers, timeout=300)
        except requests.exceptions.ReadTimeout:
            if current_batch_size > 1:
                current_batch_size = max(1, current_batch_size // 2)
                logger.warning(f"Embedding timeout, reducing batch_size to {current_batch_size}")
                continue
            raise

        if resp.status_code == 413 and current_batch_size > 1:
            current_batch_size = max(1, current_batch_size // 2)
            logger.warning(f"413 payload too large, reducing batch_size to {current_batch_size}")
            continue

        resp.raise_for_status()
        data = resp.json()["data"]
        all_embeddings.extend([item["embedding"] for item in data])
        i += current_batch_size

    return all_embeddings


def process_single_document(s3, obs_bucket, doc_params, database_connstr,
                             embedding_api_url, embedding_api_key, embedding_model,
                             doc_index=None, total_docs=None, tracker=None):
    """Process a single document: download → parse → chunk → embed → write.

    Returns {"document_id": ..., "chunks_count": N}.
    doc_index/total_docs used for batch progress reporting.
    tracker: optional StageTracker for per-stage timing/memory.
    """
    document_id = doc_params["document_id"]
    obs_key = doc_params["obs_key"]
    fmt = doc_params["format"]
    filename = doc_params.get("filename", os.path.basename(obs_key))
    tenant_id = doc_params.get("tenant_id")
    kb_id = doc_params.get("kb_id")

    # Optional rechunk params (single-doc mode only)
    max_tokens = doc_params.get("max_tokens")
    overlap_ratio = doc_params.get("overlap_ratio")

    prefix = f"[{doc_index+1}/{total_docs}] " if doc_index is not None else ""
    logger.info(f"{prefix}Processing document {document_id}: {filename} ({fmt})")

    tmp_path = None
    try:
        # ── DOWNLOAD ──
        if tracker:
            tracker.begin("DOWNLOAD")
        suffix = f".{fmt.lower()}" if fmt else ""
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp_path = tmp.name
            s3.download_file(obs_bucket, obs_key, tmp_path)
            logger.info(f"{prefix}Downloaded {obs_key}")
        if tracker:
            tracker.set_metric("file_size_bytes", os.path.getsize(tmp_path))
            tracker.end("DOWNLOAD")

        # ── PARSE ──
        if tracker:
            tracker.begin("PARSE")
        markdown, page_metadata = parse_document(tmp_path, fmt)
        logger.info(f"{prefix}Parsed: {len(markdown)} chars, {len(page_metadata)} pages")
        if tracker:
            tracker.set_metric("parsed_markdown_chars", len(markdown))
            tracker.end("PARSE")

        # Upload fulltext to OBS (best-effort)
        if tenant_id and kb_id:
            fulltext_key = f"knowledge/{tenant_id}/{kb_id}/{document_id}/fulltext.md"
            try:
                s3.put_object(Bucket=obs_bucket, Key=fulltext_key, Body=markdown.encode('utf-8'))
            except Exception as e:
                logger.error(f"{prefix}Failed to upload fulltext (non-fatal): {e}")

        # ── CHUNK ──
        if tracker:
            tracker.begin("CHUNK")
        chunk_kwargs = {}
        if max_tokens is not None:
            chunk_kwargs["max_tokens"] = int(max_tokens)
        if overlap_ratio is not None:
            chunk_kwargs["overlap_ratio"] = float(overlap_ratio)
        chunks = chunk_document(markdown, filename, fmt, **chunk_kwargs)
        assign_pages(chunks, page_metadata)
        logger.info(f"{prefix}Created {len(chunks)} chunks")
        if tracker:
            tracker.set_metric("chunks_count", len(chunks))
            tracker.end("CHUNK")

        if not chunks:
            return {"document_id": document_id, "chunks_count": 0}

        # ── EMBED ──
        if tracker:
            tracker.begin("EMBED")
        texts = [c["content"] for c in chunks]
        all_embeddings = embed_texts(texts, embedding_api_url, embedding_api_key, embedding_model)
        if tracker:
            tracker.set_metric("embeddings_count", len(all_embeddings))
            tracker.end("EMBED")

        detect_duplicates(chunks, all_embeddings)

        # ── WRITE ──
        connstr_refresh_url = doc_params.get("connstr_refresh_url")
        write_chunks(database_connstr, doc_params["document_id"], chunks, all_embeddings,
                     connstr_refresh_url=connstr_refresh_url, tracker=tracker)

        logger.info(f"{prefix}Done: {len(chunks)} chunks written for {document_id}")
        return {"document_id": document_id, "chunks_count": len(chunks)}

    finally:
        if tmp_path:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass


def main():
    job_type = os.environ.get("JOB_TYPE", "DOCUMENT_PARSE")
    if job_type == "EXPORT_PARQUET":
        from export_parquet import main as export_main
        export_main()
        return

    tracker = None
    try:
        with open("/etc/job/params.json") as f:
            params = json.load(f)

        database_connstr = params["database_connstr"]
        embedding_api_url = params.get("embedding_api_url", "https://api.siliconflow.cn/v1/embeddings")
        embedding_api_key = params.get("embedding_api_key", "")
        embedding_model = params.get("embedding_model", "BAAI/bge-m3")

        obs_endpoint = os.environ.get("OBS_ENDPOINT", "https://obs.cn-north-4.myhuaweicloud.com")
        obs_ak = os.environ["OBS_ACCESS_KEY"]
        obs_sk = os.environ["OBS_SECRET_KEY"]
        obs_bucket = os.environ.get("OBS_BUCKET", "dbay-mainstore")

        from botocore.config import Config as BotoConfig
        s3 = boto3.client("s3", endpoint_url=obs_endpoint,
                          aws_access_key_id=obs_ak, aws_secret_access_key=obs_sk,
                          aws_session_token=os.environ.get("OBS_SESSION_TOKEN"),
                          region_name="cn-north-4",
                          config=BotoConfig(
                              s3={"addressing_style": "virtual", "payload_signing_enabled": False},
                              signature_version="s3v4",
                          ))

        tracker = StageTracker()

        # Record JOB_POD stage from submitted timestamp
        job_submitted_at = params.get("job_submitted_at")
        if job_submitted_at:
            tracker.stages["JOB_POD"] = {
                "started_at": job_submitted_at,
                "completed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "duration_ms": int((time.time() - _parse_iso(job_submitted_at)) * 1000),
            }

        # ── Batch mode: document_ids list ─────────────────────────────
        document_ids = params.get("document_ids")
        if document_ids:
            doc_specs = params.get("documents", [])
            total = len(doc_specs)
            logger.info(f"Batch mode: processing {total} documents")
            report_progress(f"Starting batch processing of {total} documents", 0.05, tracker=tracker)

            results = []
            for idx, doc_params in enumerate(doc_specs):
                doc_params_full = dict(doc_params)
                doc_params_full["tenant_id"] = doc_params_full.get("tenant_id") or params.get("tenant_id")
                if params.get("connstr_refresh_url"):
                    doc_params_full.setdefault("connstr_refresh_url", params["connstr_refresh_url"])
                progress = 0.05 + 0.9 * idx / total
                report_progress(f"Processing {doc_params_full.get('filename', doc_params_full['document_id'])} ({idx+1}/{total})", progress, tracker=tracker)
                doc_tracker = StageTracker()
                result = process_single_document(
                    s3, obs_bucket, doc_params_full, database_connstr,
                    embedding_api_url, embedding_api_key, embedding_model,
                    doc_index=idx, total_docs=total, tracker=doc_tracker
                )
                results.append(result)

            report_success_batch(results, tracker=tracker)
            logger.info(f"Batch done: {total} documents processed")
            return

        # ── Single mode: backward-compatible ─────────────────────────
        document_id = params["document_id"]
        obs_key = params["obs_key"]
        fmt = params["format"]
        filename = params.get("filename", os.path.basename(obs_key))
        tenant_id = params.get("tenant_id")
        kb_id = params.get("kb_id")
        max_tokens = params.get("max_tokens")
        overlap_ratio = params.get("overlap_ratio")

        logger.info(f"Processing document {document_id}: {filename} ({fmt})")

        # ── DOWNLOAD ──
        tracker.begin("DOWNLOAD")
        report_progress("Downloading document", 0.1, tracker=tracker)

        tmp_path = None
        try:
            suffix = f".{fmt.lower()}" if fmt else ""
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
                tmp_path = tmp.name
                s3.download_file(obs_bucket, obs_key, tmp_path)
                logger.info(f"Downloaded {obs_key} to {tmp_path}")
            tracker.set_metric("file_size_bytes", os.path.getsize(tmp_path))
            tracker.end("DOWNLOAD")

            # ── PARSE ──
            tracker.begin("PARSE")
            report_progress("Parsing document", 0.2, tracker=tracker)
            markdown, page_metadata = parse_document(tmp_path, fmt)
            logger.info(f"Parsed document: {len(markdown)} chars, {len(page_metadata)} pages")
            tracker.set_metric("parsed_markdown_chars", len(markdown))
            tracker.end("PARSE")

            if tenant_id and kb_id:
                fulltext_key = f"knowledge/{tenant_id}/{kb_id}/{document_id}/fulltext.md"
                try:
                    s3.put_object(Bucket=obs_bucket, Key=fulltext_key, Body=markdown.encode('utf-8'))
                    logger.info(f"Uploaded fulltext to {fulltext_key}")
                except Exception as e:
                    logger.error(f"Failed to upload fulltext to OBS (non-fatal): {e}")

            # ── CHUNK ──
            tracker.begin("CHUNK")
            report_progress("Chunking document", 0.4, tracker=tracker)
            chunk_kwargs = {}
            if max_tokens is not None:
                chunk_kwargs["max_tokens"] = int(max_tokens)
            if overlap_ratio is not None:
                chunk_kwargs["overlap_ratio"] = float(overlap_ratio)
            chunks = chunk_document(markdown, filename, fmt, **chunk_kwargs)
            assign_pages(chunks, page_metadata)
            logger.info(f"Created {len(chunks)} chunks")
            tracker.set_metric("chunks_count", len(chunks))
            tracker.end("CHUNK")

            if not chunks:
                report_success(0, tracker=tracker)
                return

            # ── EMBED ──
            tracker.begin("EMBED")
            report_progress(f"Generating embeddings for {len(chunks)} chunks", 0.5, tracker=tracker)
            texts = [c["content"] for c in chunks]
            all_embeddings = embed_texts(texts, embedding_api_url, embedding_api_key, embedding_model)
            logger.info(f"Generated {len(all_embeddings)} embeddings")
            tracker.set_metric("embeddings_count", len(all_embeddings))
            tracker.end("EMBED")

            report_progress("Detecting duplicates", 0.85, tracker=tracker)
            detect_duplicates(chunks, all_embeddings)

            # ── WRITE ──
            report_progress("Writing to database", 0.9, tracker=tracker)
            connstr_refresh_url = params.get("connstr_refresh_url")
            write_chunks(database_connstr, document_id, chunks, all_embeddings,
                         connstr_refresh_url=connstr_refresh_url, tracker=tracker)

            quality_stats = {
                "anomaly_count": sum(1 for c in chunks if len(c["content"]) < ANOMALY_SHORT_THRESHOLD or len(c["content"]) > ANOMALY_LONG_THRESHOLD),
                "duplicate_count": sum(1 for c in chunks if "duplicate_of" in c["metadata"]),
                "avg_char_count": sum(len(c["content"]) for c in chunks) // len(chunks) if chunks else 0,
            }

            report_success(len(chunks), quality_stats, tracker=tracker)
            logger.info(f"Done: {len(chunks)} chunks written, quality_stats={quality_stats}")

        finally:
            if tmp_path:
                try:
                    os.unlink(tmp_path)
                except Exception:
                    pass

    except Exception as e:
        error_msg = str(e)
        error_category = "PERMANENT"
        failed_stage = tracker._current_stage if tracker else None

        if failed_stage == "DOWNLOAD":
            if "404" in error_msg or "NoSuchKey" in error_msg:
                error_category = "PERMANENT"
            else:
                error_category = "TRANSIENT"
        elif failed_stage == "PARSE":
            error_category = "PERMANENT"
        elif failed_stage == "CHUNK":
            error_category = "PERMANENT"
        elif failed_stage == "EMBED":
            if "429" in error_msg or "413" in error_msg or "rate" in error_msg.lower():
                error_category = "RATE_LIMIT"
            elif "401" in error_msg or "403" in error_msg:
                error_category = "PERMANENT"
            else:
                error_category = "TRANSIENT"
        elif failed_stage == "WRITE":
            if "OperationalError" in error_msg or "connection" in error_msg.lower():
                error_category = "TRANSIENT"
            else:
                error_category = "PERMANENT"

        if tracker and tracker._current_stage:
            tracker.end()

        logger.error(f"Job failed at stage {failed_stage}: {error_msg}", exc_info=True)
        report_failure(error_msg, error_category=error_category, failed_stage=failed_stage, tracker=tracker)
        sys.exit(1)

if __name__ == "__main__":
    main()
