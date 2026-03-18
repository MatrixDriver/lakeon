"""Callback to Lakeon API after job completion."""
import os
import logging
import requests

logger = logging.getLogger(__name__)

def report_success(chunks_count, quality_stats=None):
    """Report successful completion with optional quality statistics.

    quality_stats: {anomaly_count, duplicate_count, avg_char_count}
    """
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    result = {"chunks_count": chunks_count}
    if quality_stats:
        result["quality_stats"] = quality_stats
    resp = requests.post(url, json={"token": token, "status": "SUCCEEDED", "result": result}, timeout=30)
    logger.info(f"Callback SUCCEEDED: {resp.status_code}")

def report_progress(message, progress=0):
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    try:
        requests.post(url, json={"token": token, "status": "RUNNING", "result": {"progress": progress, "message": message}}, timeout=10)
    except Exception as e:
        logger.warning(f"Progress callback failed: {e}")

def report_failure(error):
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    resp = requests.post(url, json={"token": token, "status": "FAILED", "error": error}, timeout=30)
    logger.info(f"Callback FAILED: {resp.status_code}")
