"""Callback to Lakeon API after job completion."""
import os
import logging
import requests

logger = logging.getLogger(__name__)

def report_success(chunks_count):
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    resp = requests.post(url, json={"token": token, "status": "SUCCEEDED", "result": {"chunks_count": chunks_count}}, timeout=30)
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
