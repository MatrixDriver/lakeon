"""Knowledge Pipeline Job: parse -> chunk -> embed -> write -> callback."""
import os
import sys
import json
import logging
import tempfile

import boto3
import requests

from parser import parse_document
from chunker import chunk_document, assign_pages, detect_duplicates
from callback import report_success, report_failure, report_progress

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logger = logging.getLogger("knowledge-job")

def main():
    tmp_path = None
    try:
        with open("/etc/job/params.json") as f:
            params = json.load(f)

        document_id = params["document_id"]
        obs_key = params["obs_key"]
        fmt = params["format"]
        database_connstr = params["database_connstr"]
        embedding_api_url = params.get("embedding_api_url", "https://api.siliconflow.cn/v1/embeddings")
        embedding_api_key = params.get("embedding_api_key", "")
        embedding_model = params.get("embedding_model", "BAAI/bge-m3")
        filename = params.get("filename", os.path.basename(obs_key))
        tenant_id = params.get("tenant_id")
        kb_id = params.get("kb_id")

        logger.info(f"Processing document {document_id}: {filename} ({fmt})")
        report_progress("Downloading document", 0.1)

        obs_endpoint = os.environ.get("OBS_ENDPOINT", "https://obs.cn-north-4.myhuaweicloud.com")
        obs_ak = os.environ["OBS_ACCESS_KEY"]
        obs_sk = os.environ["OBS_SECRET_KEY"]
        obs_bucket = os.environ.get("OBS_BUCKET", "lakeon-storage")

        s3 = boto3.client("s3", endpoint_url=obs_endpoint, aws_access_key_id=obs_ak, aws_secret_access_key=obs_sk)

        suffix = f".{fmt.lower()}" if fmt else ""
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp_path = tmp.name
            s3.download_file(obs_bucket, obs_key, tmp_path)
            logger.info(f"Downloaded {obs_key} to {tmp_path}")

        report_progress("Parsing document", 0.2)
        markdown, page_metadata = parse_document(tmp_path, fmt)
        logger.info(f"Parsed document: {len(markdown)} chars, {len(page_metadata)} pages")

        # Upload fulltext to OBS
        if tenant_id and kb_id:
            fulltext_key = f"knowledge/{tenant_id}/{kb_id}/{document_id}/fulltext.md"
            s3.put_object(Bucket=obs_bucket, Key=fulltext_key, Body=markdown.encode('utf-8'))
            logger.info(f"Uploaded fulltext to {fulltext_key}")

        report_progress("Chunking document", 0.4)
        chunks = chunk_document(markdown, filename, fmt)
        assign_pages(chunks, page_metadata)
        logger.info(f"Created {len(chunks)} chunks")

        if not chunks:
            report_success(0)
            return

        report_progress(f"Generating embeddings for {len(chunks)} chunks", 0.5)
        texts = [c["content"] for c in chunks]
        batch_size = 32
        all_embeddings = []
        headers = {"Content-Type": "application/json"}
        if embedding_api_key:
            headers["Authorization"] = f"Bearer {embedding_api_key}"
        for i in range(0, len(texts), batch_size):
            batch = texts[i:i + batch_size]
            # OpenAI-compatible embedding API
            resp = requests.post(embedding_api_url, json={
                "model": embedding_model,
                "input": batch,
                "encoding_format": "float"
            }, headers=headers, timeout=120)
            resp.raise_for_status()
            data = resp.json()["data"]
            all_embeddings.extend([item["embedding"] for item in data])
            progress = 0.5 + 0.3 * min(i + batch_size, len(texts)) / len(texts)
            report_progress(f"Embedding {min(i + batch_size, len(texts))}/{len(texts)}", progress)

        logger.info(f"Generated {len(all_embeddings)} embeddings")

        report_progress("Detecting duplicates", 0.85)
        detect_duplicates(chunks, all_embeddings)

        report_progress("Writing to database", 0.9)

        from writer import write_chunks
        write_chunks(database_connstr, document_id, chunks, all_embeddings)

        # Compute quality stats
        quality_stats = {
            "anomaly_count": sum(1 for c in chunks if len(c["content"]) < 80 or len(c["content"]) > 800),
            "duplicate_count": sum(1 for c in chunks if "duplicate_of" in c["metadata"]),
            "avg_char_count": sum(len(c["content"]) for c in chunks) // len(chunks) if chunks else 0,
        }

        report_success(len(chunks), quality_stats)
        logger.info(f"Done: {len(chunks)} chunks written, quality_stats={quality_stats}")

    except Exception as e:
        logger.exception(f"Job failed: {e}")
        try:
            report_failure(str(e))
        except Exception:
            logger.exception("Failed to report failure")
        sys.exit(1)
    finally:
        if tmp_path:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass

if __name__ == "__main__":
    main()
