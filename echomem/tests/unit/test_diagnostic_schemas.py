from echomem.api.schemas import (
    DaemonHealth, OllamaHealth, WorkerStatus,
    DiagnosticCounts, DeadLetterEntry, DiagnosticResponse,
)


def test_diagnostic_response_round_trip():
    payload = {
        "daemon": {
            "status": "ok", "version": "0.1.0",
            "data_dir": "/tmp/echomem", "db_size_bytes": 1024,
        },
        "ollama": {
            "status": "ok", "latency_ms": 12,
            "generate_model": "gemma2:2b",
            "embedding_model": "nomic-embed-text",
            "embedding_dim": 768,
        },
        "workers": {
            "embedder": {"queue_depth": 0, "last_run_at": 0,
                         "processed_total": 0, "throttle": None},
        },
        "counts": {"memories": 0, "cognitions": 0, "entities": 0, "skills": 0},
        "dead_letter": [],
    }
    parsed = DiagnosticResponse.model_validate(payload)
    assert parsed.daemon.version == "0.1.0"
    assert parsed.ollama.embedding_dim == 768
    assert parsed.counts.memories == 0
