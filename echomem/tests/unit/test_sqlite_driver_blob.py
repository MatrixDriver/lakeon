import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import BlobRef


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def test_upsert_and_get_blob_ref(driver):
    b = BlobRef(sha256="a" * 64, mime="text/plain", byte_size=11,
                origin_url="https://x.com", meta={"title": "T"}, created_at=1)
    driver.upsert_blob_ref(b)
    got = driver.get_blob_ref("a" * 64)
    assert got is not None
    assert got.mime == "text/plain"
    assert got.meta == {"title": "T"}


def test_list_blob_refs_filter_by_origin(driver):
    for i in range(3):
        b = BlobRef(sha256=str(i) * 64, mime="text/html", byte_size=i, origin_url=f"https://x.com/{i}",
                    meta=None, created_at=i)
        driver.upsert_blob_ref(b)
    rows = driver.list_blob_refs(origin_prefix="https://x.com")
    assert len(rows) == 3


def test_path_alias_set_get_mv(driver):
    b = BlobRef(sha256="b" * 64, mime="text/plain", byte_size=5, origin_url=None, meta=None, created_at=1)
    driver.upsert_blob_ref(b)
    driver.set_path_alias(path="notes/a.md", sha256="b" * 64, created_at=1)
    assert driver.resolve_path("notes/a.md") == "b" * 64
    driver.move_path_alias(old="notes/a.md", new="archive/a.md")
    assert driver.resolve_path("notes/a.md") is None
    assert driver.resolve_path("archive/a.md") == "b" * 64


def test_list_paths_with_prefix(driver):
    b = BlobRef(sha256="c" * 64, mime="text/plain", byte_size=1, origin_url=None, meta=None, created_at=1)
    driver.upsert_blob_ref(b)
    driver.set_path_alias("a/x.md", "c" * 64, 1)
    driver.set_path_alias("a/y.md", "c" * 64, 1)
    driver.set_path_alias("b/z.md", "c" * 64, 1)
    rows = driver.list_paths(prefix="a/")
    assert {r["path"] for r in rows} == {"a/x.md", "a/y.md"}


def test_move_path_alias_returns_false_when_missing(driver):
    assert driver.move_path_alias(old="nope", new="also-nope") is False
