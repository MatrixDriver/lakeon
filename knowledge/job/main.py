"""Knowledge Pipeline Job: parse -> chunk -> embed -> write -> callback."""
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
    tmp_path = None
    try:
        with open("/etc/job/params.json") as f:
            params = json.load(f)

        document_id = params["document_id"]
        obs_key = params["obs_key"]
        fmt = params["format"]
        database_connstr = params["database_connstr"]
        embedding_service_url = params["embedding_service_url"]
        filename = params.get("filename", os.path.basename(obs_key))

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
        markdown = parse_document(tmp_path, fmt)
        logger.info(f"Parsed document: {len(markdown)} chars")

        report_progress("Chunking document", 0.4)
        chunks = chunk_document(markdown, filename, fmt)
        logger.info(f"Created {len(chunks)} chunks")

        if not chunks:
            report_success(0)
            return

        report_progress(f"Generating embeddings for {len(chunks)} chunks", 0.5)
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

        from writer import write_chunks
        write_chunks(database_connstr, document_id, chunks, all_embeddings)

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
        if tmp_path:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass

if __name__ == "__main__":
    main()
