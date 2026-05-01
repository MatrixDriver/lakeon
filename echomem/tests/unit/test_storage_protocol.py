from echomem.drivers.base import StorageDriver, Memory, RecallHit


def test_protocol_has_required_methods():
    assert hasattr(StorageDriver, "upsert_memory")
    assert hasattr(StorageDriver, "get_memory")
    assert hasattr(StorageDriver, "list_memories")
    assert hasattr(StorageDriver, "delete_memory")
    assert hasattr(StorageDriver, "recall")
    assert hasattr(StorageDriver, "close")


def test_memory_dataclass_round_trip():
    m = Memory(
        id="01HX",
        agent_id="cc",
        source_kind="explicit",
        source_ref=None,
        text="hello",
        meta={"k": "v"},
        created_at=1,
        updated_at=1,
        deleted_at=None,
    )
    assert m.text == "hello"
    assert m.meta == {"k": "v"}


def test_recall_hit_dataclass():
    h = RecallHit(memory_id="01HX", text="t", score=0.9, source_kind="explicit", source_ref=None)
    assert h.score == 0.9
