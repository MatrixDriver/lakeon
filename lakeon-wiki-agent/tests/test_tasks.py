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
    started = [asyncio.Event(), asyncio.Event()]
    release = [asyncio.Event(), asyncio.Event()]
    finished = []

    async def slow(i: int):
        started[i].set()
        await release[i].wait()
        finished.append(i)
        return {"i": i}

    await reg.submit("ingest", slow(0))
    await reg.submit("ingest", slow(1))

    # Wait for task 0 to start (semaphore admitted it)
    await started[0].wait()
    # Task 1 must still be waiting on the semaphore
    assert not started[1].is_set()
    assert finished == []

    # Release task 0 → semaphore frees → task 1 starts
    release[0].set()
    await started[1].wait()
    # By the time task 1 has started, task 0's `finished.append(0)` must have run
    # because it happens before `return` which releases the semaphore
    assert 0 in finished

    # Release task 1 and wait for it
    release[1].set()
    for _ in range(50):
        if len(finished) == 2:
            break
        await asyncio.sleep(0.01)
    assert finished == [0, 1]


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


@pytest.mark.asyncio
async def test_evict_older_than_removes_terminal_snapshots():
    import time as _time

    reg = TaskRegistry(max_concurrent=2)

    async def work():
        return {}

    tid = await reg.submit("ingest", work())
    # Wait for completion
    for _ in range(50):
        if reg.get(tid)["status"] == "completed":
            break
        await asyncio.sleep(0.01)
    assert reg.get(tid)["status"] == "completed"

    # Manually backdate finished_at so it appears old
    snap = reg.get(tid)
    snap["finished_at"] = _time.time() - 100  # 100 seconds ago

    evicted = reg.evict_older_than(max_age_seconds=60)
    assert evicted == 1
    assert reg.get(tid) is None


@pytest.mark.asyncio
async def test_evict_leaves_running_tasks_alone():
    reg = TaskRegistry(max_concurrent=2)
    release = asyncio.Event()

    async def held():
        await release.wait()
        return {}

    tid = await reg.submit("ingest", held())
    await asyncio.sleep(0.01)
    assert reg.get(tid)["status"] == "running"

    # Evict with tiny TTL — should NOT drop running tasks
    evicted = reg.evict_older_than(max_age_seconds=0)
    assert evicted == 0
    assert reg.get(tid) is not None

    release.set()


def test_invalid_max_concurrent_raises():
    import pytest as _pytest
    with _pytest.raises(ValueError):
        TaskRegistry(max_concurrent=0)
    with _pytest.raises(ValueError):
        TaskRegistry(max_concurrent=-1)
