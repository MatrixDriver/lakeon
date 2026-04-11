"""TaskRegistry runs coroutines under semaphore with status tracking."""
import asyncio

import pytest

from app.tasks import TaskRegistry


@pytest.mark.asyncio
async def test_registry_tracks_running_then_completed():
    reg = TaskRegistry(max_concurrent=2)

    async def work(x: int) -> dict:
        await asyncio.sleep(0.01)
        return {"x": x}

    task_id = await reg.submit("ingest", work(1))
    # Immediately after submit, status may already be running
    snap = reg.get(task_id)
    assert snap is not None
    assert snap["task_id"] == task_id
    assert snap["run_type"] == "ingest"
    assert snap["status"] == "running"

    # Wait for completion
    await asyncio.sleep(0.1)
    snap = reg.get(task_id)
    assert snap["status"] == "completed"
    assert snap["result"] == {"x": 1}
    assert snap["error"] is None
    assert "finished_at" in snap


@pytest.mark.asyncio
async def test_registry_captures_error():
    reg = TaskRegistry(max_concurrent=2)

    async def boom():
        raise RuntimeError("nope")

    task_id = await reg.submit("ingest", boom())
    await asyncio.sleep(0.05)
    snap = reg.get(task_id)
    assert snap["status"] == "error"
    assert "nope" in snap["error"]
    assert snap["result"] is None


@pytest.mark.asyncio
async def test_semaphore_limits_concurrency():
    reg = TaskRegistry(max_concurrent=1)
    started = []
    finished = []

    async def slow(i: int):
        started.append(i)
        await asyncio.sleep(0.1)
        finished.append(i)
        return {"i": i}

    await reg.submit("ingest", slow(1))
    await reg.submit("ingest", slow(2))

    # With max_concurrent=1, only task 1 can be running at first
    await asyncio.sleep(0.02)
    assert started == [1]
    assert finished == []

    # After task 1 finishes and task 2 starts
    await asyncio.sleep(0.15)
    assert started == [1, 2]
    assert finished == [1]

    # After task 2 finishes
    await asyncio.sleep(0.15)
    assert finished == [1, 2]


@pytest.mark.asyncio
async def test_registry_get_returns_none_for_unknown_id():
    reg = TaskRegistry(max_concurrent=2)
    assert reg.get("task_nope") is None


@pytest.mark.asyncio
async def test_registry_generates_unique_task_ids():
    reg = TaskRegistry(max_concurrent=2)

    async def noop():
        return {}

    ids = set()
    for _ in range(5):
        ids.add(await reg.submit("ingest", noop()))
    assert len(ids) == 5
    assert all(tid.startswith("task_") for tid in ids)


@pytest.mark.asyncio
async def test_count_running_reflects_active_tasks():
    reg = TaskRegistry(max_concurrent=4)

    release = asyncio.Event()

    async def held():
        await release.wait()
        return {}

    # Submit 3 tasks that will block
    for _ in range(3):
        await reg.submit("ingest", held())

    await asyncio.sleep(0.01)
    assert reg.count_running() == 3

    release.set()
    await asyncio.sleep(0.05)
    assert reg.count_running() == 0
