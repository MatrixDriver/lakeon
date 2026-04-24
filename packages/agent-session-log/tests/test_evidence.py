from agent_session_log.evidence import hash_bytes, Blob


def test_hash_bytes_deterministic():
    h1 = hash_bytes(b"hello")
    h2 = hash_bytes(b"hello")
    assert h1 == h2
    assert len(h1) == 64  # sha256 hex


def test_hash_bytes_different_inputs():
    assert hash_bytes(b"a") != hash_bytes(b"b")


def test_blob_from_bytes_log():
    blob = Blob.from_bytes(b"2026-04-23 09:12:30 INFO started", mime="text/plain", source="log_search")
    assert blob.ext == "log"
    assert blob.size == len(b"2026-04-23 09:12:30 INFO started")
    assert blob.source == "log_search"
    assert blob.mime == "text/plain"


def test_blob_from_bytes_json():
    blob = Blob.from_bytes(b'{"key":1}', mime="application/json")
    assert blob.ext == "json"


def test_blob_short_hash():
    blob = Blob.from_bytes(b"xyz", mime="text/plain")
    assert len(blob.short_hash) == 8
    assert blob.short_hash == blob.sha256[:8]
