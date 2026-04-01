"""Shared test fixtures for the orchestrator test suite."""

import asyncio
import os
import tempfile
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker

from lakeon_orchestrator.db.models import Base

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"


# ============================================================
# Async DB fixtures (for integration / API tests)
# ============================================================

@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture
async def async_engine():
    engine = create_async_engine(TEST_DB_URL, echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest_asyncio.fixture
async def db_session(async_engine) -> AsyncGenerator[AsyncSession, None]:
    session_factory = async_sessionmaker(async_engine, expire_on_commit=False)
    async with session_factory() as session:
        yield session
        await session.rollback()


# ============================================================
# Fake ComponentContext for E2E component testing
# ============================================================

@dataclass
class MockObs:
    """Mock OBS client for testing."""
    stored: dict = field(default_factory=dict)

    def write(self, local_path: str, obs_path: str) -> None:
        self.stored[obs_path] = local_path

    def read(self, obs_path: str, local_path: str) -> str:
        return self.stored.get(obs_path, local_path)


@dataclass
class FakeComponentContext:
    """A fake ComponentContext that records all calls for E2E testing.

    Provides real fan_out/classify/checkpoint behavior so the full pipeline
    can execute end-to-end without mocking individual calls.
    """
    input_data: dict = field(default_factory=dict)
    params: dict = field(default_factory=dict)
    obs: MockObs = field(default_factory=MockObs)

    # Identity fields (match real ComponentContext signature)
    step_id: str = "test_step"
    run_id: str = "test_run"

    # Recorded state
    logs: list = field(default_factory=list)
    metrics: dict = field(default_factory=dict)
    checkpoint_data: Any = None

    @property
    def input(self) -> dict:
        return self.input_data

    def log(self, msg: str) -> None:
        self.logs.append(msg)

    def report(self, metrics: dict) -> None:
        self.metrics.update(metrics)

    def checkpoint(self, data: Any = None) -> None:
        self.checkpoint_data = data

    def fan_out(self, items: list) -> dict:
        self.report({"output_count": len(items)})
        return {"__fan_out__": True, "items": items}

    def classify(self, data: Any, label: str) -> dict:
        return {"__branch__": label, "data": data}


@pytest.fixture
def fake_ctx():
    """Create a FakeComponentContext for testing."""
    return FakeComponentContext()


@pytest.fixture
def test_video_path():
    """Generate a small test video using ffmpeg (10 seconds, 640x360).

    Returns the path to the video file. Cleaned up after test.
    """
    out_dir = tempfile.mkdtemp(prefix="test_video_")
    out_path = os.path.join(out_dir, "test_input.mp4")

    # Generate a test video with multiple distinct scenes using ffmpeg:
    # 3 color segments (red, green, blue) of ~3.3s each = 10s total
    import subprocess
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i",
        "color=c=red:size=640x360:duration=3.3:rate=30,"
        "drawtext=text='Scene 1':fontsize=30:fontcolor=white:x=250:y=170",
        "-f", "lavfi", "-i",
        "color=c=green:size=640x360:duration=3.3:rate=30,"
        "drawtext=text='Scene 2':fontsize=30:fontcolor=white:x=250:y=170",
        "-f", "lavfi", "-i",
        "color=c=blue:size=640x360:duration=3.4:rate=30,"
        "drawtext=text='Scene 3':fontsize=30:fontcolor=white:x=250:y=170",
        "-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[outv]",
        "-map", "[outv]",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
        out_path,
    ]

    try:
        subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=30)
    except (subprocess.CalledProcessError, FileNotFoundError):
        pytest.skip("ffmpeg not available for E2E test")

    yield out_path

    # Cleanup
    import shutil
    shutil.rmtree(out_dir, ignore_errors=True)
