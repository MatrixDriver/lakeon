"""Callback to Lakeon API after job completion."""
import os
import resource
import sys
import time
import logging
import requests

logger = logging.getLogger(__name__)


class StageTracker:
    """Tracks per-stage timing and memory for pipeline monitoring."""

    def __init__(self):
        self.stages = {}
        self._current_stage = None
        self._stage_start = None
        self.metrics = {}

    def _get_memory_mb(self):
        """Get current RSS in MB via getrusage (zero overhead)."""
        usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        if sys.platform == "darwin":
            return usage / (1024 * 1024)
        return usage / 1024

    def begin(self, stage_id):
        """Mark stage start."""
        if self._current_stage:
            self.end(self._current_stage)
        self._current_stage = stage_id
        self._stage_start = time.time()
        self.stages[stage_id] = {
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self._stage_start)),
        }

    def end(self, stage_id=None):
        """Mark stage completion."""
        sid = stage_id or self._current_stage
        if sid and sid in self.stages and self._stage_start:
            now = time.time()
            self.stages[sid]["completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now))
            self.stages[sid]["duration_ms"] = int((now - self._stage_start) * 1000)
            mem_now = self._get_memory_mb()
            self.stages[sid]["memory_mb"] = round(mem_now, 1)
            if sid == self._current_stage:
                self._current_stage = None
                self._stage_start = None

    def set_metric(self, key, value):
        self.metrics[key] = value

    def build_result(self):
        """Build stages + metrics dict for callback payload."""
        peak = max((s.get("memory_mb", 0) for s in self.stages.values()), default=0)
        stage_memory = {sid: {"memory_mb": s.get("memory_mb", 0)} for sid, s in self.stages.items() if "memory_mb" in s}
        return {
            "stages": dict(self.stages),
            "metrics": {
                **self.metrics,
                "peak_memory_mb": round(peak, 1),
                "stage_memory": stage_memory,
            },
        }


def _is_exec_mode():
    return "--exec-mode" in sys.argv


def _send_callback(payload):
    """Send callback payload to Lakeon API."""
    url = os.environ["JOB_CALLBACK_URL"]
    token = os.environ["JOB_CALLBACK_TOKEN"]
    payload["token"] = token
    resp = requests.post(url, json=payload, timeout=30, verify=False)
    return resp


def report_success(chunks_count, quality_stats=None, tracker=None):
    """Report successful completion with optional quality statistics.

    quality_stats: {anomaly_count, duplicate_count, avg_char_count}
    """
    if _is_exec_mode():
        return
    result = {"chunks_count": chunks_count}
    if quality_stats:
        result["quality_stats"] = quality_stats
    if tracker:
        result.update(tracker.build_result())
    payload = {"status": "SUCCEEDED", "result": result}
    resp = _send_callback(payload)
    logger.info(f"Callback SUCCEEDED: {resp.status_code}")


def report_success_batch(documents, tracker=None):
    """Report successful batch completion.

    documents: [{"document_id": "...", "chunks_count": N}, ...]
    """
    if _is_exec_mode():
        return
    result = {"documents": documents}
    if tracker:
        result.update(tracker.build_result())
    payload = {"status": "SUCCEEDED", "result": result}
    resp = _send_callback(payload)
    logger.info(f"Callback SUCCEEDED (batch {len(documents)} docs): {resp.status_code}")

def report_progress(message, progress=0, tracker=None):
    if _is_exec_mode():
        logger.info(f"Progress: {message} ({progress:.0%})")
        return
    try:
        result = {"progress": progress, "message": message}
        if tracker:
            result.update(tracker.build_result())
        payload = {"status": "RUNNING", "result": result}
        _send_callback(payload)
    except Exception as e:
        logger.warning(f"Progress callback failed: {e}")

def report_failure(error, error_category="PERMANENT", failed_stage=None, tracker=None):
    try:
        url = os.environ.get("JOB_CALLBACK_URL")
        token = os.environ.get("JOB_CALLBACK_TOKEN")
        if not url or not token:
            logger.warning("JOB_CALLBACK_URL or JOB_CALLBACK_TOKEN not set, skipping failure callback")
            return
        payload = {"token": token, "status": "FAILED", "error": error, "error_category": error_category}
        if failed_stage:
            payload["failed_stage"] = failed_stage
        if tracker:
            payload["result"] = tracker.build_result()
        resp = requests.post(url, json=payload, timeout=30, verify=False)
        logger.info(f"Callback FAILED: {resp.status_code}")
    except Exception as e:
        logger.error(f"Failed to report failure callback: {e}")
