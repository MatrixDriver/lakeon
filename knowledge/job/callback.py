"""Callback to Lakeon API after job completion."""
import os
import sys
import logging
import requests

logger = logging.getLogger(__name__)

def _is_exec_mode():
    return "--exec-mode" in sys.argv

def report_success(chunks_count, quality_stats=None):
    """Report successful completion with optional quality statistics.

    quality_stats: {anomaly_count, duplicate_count, avg_char_count}
    """
    if _is_exec_mode():
        return
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    result = {"chunks_count": chunks_count}
    if quality_stats:
        result["quality_stats"] = quality_stats
    resp = requests.post(url, json={"token": token, "status": "SUCCEEDED", "result": result}, timeout=30)
    logger.info(f"Callback SUCCEEDED: {resp.status_code}")


def report_success_batch(documents):
    """Report successful batch completion.

    documents: [{"document_id": "...", "chunks_count": N}, ...]
    """
    if _is_exec_mode():
        return
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    result = {"documents": documents}
    resp = requests.post(url, json={"token": token, "status": "SUCCEEDED", "result": result}, timeout=30)
    logger.info(f"Callback SUCCEEDED (batch {len(documents)} docs): {resp.status_code}")

def report_progress(message, progress=0):
    if _is_exec_mode():
        logger.info(f"Progress: {message} ({progress:.0%})")
        return
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    try:
        requests.post(url, json={"token": token, "status": "RUNNING", "result": {"progress": progress, "message": message}}, timeout=10)
    except Exception as e:
        logger.warning(f"Progress callback failed: {e}")

def report_failure(error):
    try:
        url = os.environ.get("JOB_CALLBACK_URL")
        token = os.environ.get("JOB_CALLBACK_TOKEN")
        if not url or not token:
            logger.warning("JOB_CALLBACK_URL or JOB_CALLBACK_TOKEN not set, skipping failure callback")
            return
        resp = requests.post(url, json={"token": token, "status": "FAILED", "error": error}, timeout=30)
        logger.info(f"Callback FAILED: {resp.status_code}")
    except Exception as e:
        logger.error(f"Failed to report failure callback: {e}")
