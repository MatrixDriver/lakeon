import pytest
from echomem.context.blob_store import BlobStore


def test_write_returns_sha256_and_persists(tmp_path):
    store = BlobStore(tmp_path)
    sha = store.write(b"hello world")
    assert len(sha) == 64
    assert all(c in "0123456789abcdef" for c in sha)
    assert store.exists(sha)
    assert store.read(sha) == b"hello world"


def test_write_is_idempotent(tmp_path):
    store = BlobStore(tmp_path)
    sha1 = store.write(b"same content")
    sha2 = store.write(b"same content")
    assert sha1 == sha2


def test_read_missing_raises(tmp_path):
    store = BlobStore(tmp_path)
    with pytest.raises(FileNotFoundError):
        store.read("00" * 32)


def test_path_for_uses_2byte_prefix_dirs(tmp_path):
    store = BlobStore(tmp_path)
    sha = store.write(b"x")
    p = store.path_for(sha)
    # blobs/<sha[:2]>/<sha>
    assert p.parent.name == sha[:2]
    assert p.parent.parent.name == "blobs"
    assert p.name == sha
