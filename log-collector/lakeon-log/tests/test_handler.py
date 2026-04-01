"""Tests for lakeon_log handler and setup_logging."""
import json
import logging
import os

import pytest

from lakeon_log.handler import HttpBatchHandler, LakeonJsonFormatter


def make_record(msg="hello", level=logging.INFO, name="test.logger", **kwargs):
    """Helper to create a LogRecord with optional extra attributes."""
    record = logging.LogRecord(
        name=name,
        level=level,
        pathname="",
        lineno=0,
        msg=msg,
        args=(),
        exc_info=None,
    )
    for k, v in kwargs.items():
        setattr(record, k, v)
    return record


class TestLakeonJsonFormatter:
    def test_json_formatter_output(self):
        formatter = LakeonJsonFormatter(
            component="knowledge-pipeline",
            request_id="req-123",
            tenant_id="tenant-abc",
        )
        record = make_record(msg="processing chunk", level=logging.WARNING)
        output = formatter.format(record)
        data = json.loads(output)

        assert data["component"] == "knowledge-pipeline"
        assert data["requestId"] == "req-123"
        assert data["tenantId"] == "tenant-abc"
        assert data["level"] == "WARNING"
        assert data["msg"] == "processing chunk"
        assert data["logger"] == "test.logger"
        assert "ts" in data
        # ts should be ISO UTC format
        assert data["ts"].endswith("Z") or "T" in data["ts"]

    def test_json_formatter_with_duration(self):
        formatter = LakeonJsonFormatter(component="datalake-job")
        record = make_record(msg="task complete", duration_ms=342.5)
        output = formatter.format(record)
        data = json.loads(output)

        assert data["durationMs"] == 342.5
        assert data["component"] == "datalake-job"

    def test_json_formatter_optional_fields_absent_when_empty(self):
        formatter = LakeonJsonFormatter(component="notebook")
        record = make_record(msg="started")
        output = formatter.format(record)
        data = json.loads(output)

        assert "requestId" not in data
        assert "tenantId" not in data
        assert "durationMs" not in data
        assert "extra" not in data


class TestSetupLogging:
    def test_setup_logging_reads_env(self, monkeypatch):
        monkeypatch.setenv("LAKEON_REQUEST_ID", "req-env-1")
        monkeypatch.setenv("LAKEON_TENANT_ID", "tenant-env-1")
        monkeypatch.delenv("LAKEON_LOG_ENDPOINT", raising=False)

        from lakeon_log import setup_logging

        logger = setup_logging("my-component", level=logging.DEBUG)

        assert logger.name == "my-component"
        assert logger.level == logging.DEBUG
        # Should have exactly one handler (StreamHandler) since no endpoint
        assert len(logger.handlers) == 1
        assert isinstance(logger.handlers[0], logging.StreamHandler)
        # Formatter should be LakeonJsonFormatter
        fmt = logger.handlers[0].formatter
        assert isinstance(fmt, LakeonJsonFormatter)
        assert fmt.component == "my-component"
        assert fmt.request_id == "req-env-1"
        assert fmt.tenant_id == "tenant-env-1"

        # Cleanup
        logger.handlers.clear()

    def test_setup_logging_adds_http_handler_when_endpoint_set(self, monkeypatch):
        monkeypatch.setenv("LAKEON_LOG_ENDPOINT", "http://localhost:9999/logs")
        monkeypatch.delenv("LAKEON_REQUEST_ID", raising=False)
        monkeypatch.delenv("LAKEON_TENANT_ID", raising=False)

        from lakeon_log import setup_logging

        logger = setup_logging("http-component")

        assert len(logger.handlers) == 2
        handler_types = {type(h) for h in logger.handlers}
        assert logging.StreamHandler in handler_types
        assert HttpBatchHandler in handler_types

        # Cleanup
        for h in logger.handlers:
            h.close()
        logger.handlers.clear()


class TestHttpBatchHandler:
    def test_http_handler_batches(self):
        sent_batches = []

        handler = HttpBatchHandler(
            endpoint="http://localhost:9999/logs",
            batch_size=3,
            flush_interval=60.0,  # long interval so timer doesn't interfere
        )
        handler._send = lambda entries: sent_batches.append(entries)

        formatter = LakeonJsonFormatter(component="test-job")
        handler.setFormatter(formatter)

        for i in range(3):
            record = make_record(msg=f"event {i}")
            handler.emit(record)

        # After 3 emits with batch_size=3, _send should have been called once
        assert len(sent_batches) == 1
        assert len(sent_batches[0]) == 3

        handler.close()

    def test_http_handler_sends_on_close(self):
        sent_batches = []

        handler = HttpBatchHandler(
            endpoint="http://localhost:9999/logs",
            batch_size=100,
            flush_interval=60.0,
        )
        handler._send = lambda entries: sent_batches.append(entries)

        formatter = LakeonJsonFormatter(component="test-job")
        handler.setFormatter(formatter)

        # Emit 2 records (below batch_size)
        for i in range(2):
            record = make_record(msg=f"partial {i}")
            handler.emit(record)

        assert len(sent_batches) == 0  # not flushed yet

        handler.close()  # should flush remaining

        assert len(sent_batches) == 1
        assert len(sent_batches[0]) == 2
