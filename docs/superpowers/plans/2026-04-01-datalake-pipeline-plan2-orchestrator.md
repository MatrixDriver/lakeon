# 数据生产线 Plan 2: Pipeline Orchestrator + 组件框架

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Pipeline Orchestrator（Python 独立服务），实现 DAG 状态机、组件框架、Ray 交互、fan-out/in、条件分支、暂停/恢复，为数据生产线提供编排引擎。

**Architecture:** Orchestrator 是独立 Python Pod（FastAPI），与 lakeon-api 共享 RDS（PostgreSQL）。Orchestrator 直接读写 pipeline_runs / pipeline_step_runs 表，lakeon-api 通过内部 HTTP 触发运行/恢复/取消。步骤间数据通过 Ray object store 传递（持久集群），暂停时写 OBS checkpoint。

**Tech Stack:** Python 3.11, FastAPI, SQLAlchemy 2.0, asyncpg, Ray 2.x, boto3 (S3-compatible OBS), PyYAML, pytest, Docker

**Spec:** `docs/superpowers/specs/2026-04-01-datalake-pipeline-design.md`

**Depends on:** Plan 1（数据库表已创建：pipeline_components, pipeline_component_versions, pipelines, pipeline_versions, pipeline_runs, pipeline_step_runs, dataset_versions）

---

## File Structure

```
lakeon-orchestrator/
├── pyproject.toml
├── Dockerfile
├── .env.example
├── src/
│   └── lakeon_orchestrator/
│       ├── __init__.py
│       ├── main.py                    # FastAPI app + lifespan
│       ├── config.py                  # Settings from env
│       ├── api/
│       │   ├── __init__.py
│       │   └── runs.py                # POST /runs, /runs/{id}/resume, /runs/{id}/cancel
│       ├── db/
│       │   ├── __init__.py
│       │   ├── engine.py              # async engine + session factory
│       │   ├── models.py              # SQLAlchemy ORM models (map Plan 1 tables)
│       │   └── state_manager.py       # Read/write pipeline_runs, pipeline_step_runs
│       ├── dag/
│       │   ├── __init__.py
│       │   ├── parser.py              # YAML → DAG graph
│       │   ├── scheduler.py           # Topological sort + ready-step detection
│       │   ├── fan_out_handler.py     # 1→N expansion
│       │   ├── fan_in_handler.py      # N→1 merge
│       │   └── branch_router.py       # Conditional routing
│       ├── component/
│       │   ├── __init__.py
│       │   ├── decorator.py           # @Component decorator
│       │   ├── context.py             # ComponentContext
│       │   └── loader.py              # Dynamic component loading
│       ├── ray_client/
│       │   ├── __init__.py
│       │   └── client.py              # Ray cluster lifecycle + task submission
│       ├── checkpoint/
│       │   ├── __init__.py
│       │   └── manager.py             # OBS checkpoint read/write
│       ├── pause/
│       │   ├── __init__.py
│       │   └── manager.py             # HUMAN_REVIEW pause/resume
│       └── orchestrator.py            # Main orchestration loop
├── tests/
│   ├── __init__.py
│   ├── conftest.py                    # Shared fixtures
│   ├── test_dag_parser.py
│   ├── test_dag_scheduler.py
│   ├── test_fan_out_handler.py
│   ├── test_fan_in_handler.py
│   ├── test_branch_router.py
│   ├── test_component_decorator.py
│   ├── test_component_context.py
│   ├── test_component_loader.py
│   ├── test_state_manager.py
│   ├── test_ray_client.py
│   ├── test_checkpoint_manager.py
│   ├── test_pause_manager.py
│   ├── test_orchestrator.py
│   └── test_api_runs.py
```

---

## Task 1: 项目脚手架

**Files:**
- Create: `lakeon-orchestrator/pyproject.toml`
- Create: `lakeon-orchestrator/Dockerfile`
- Create: `lakeon-orchestrator/.env.example`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/__init__.py`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/config.py`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/main.py`
- Create: `lakeon-orchestrator/tests/__init__.py`
- Create: `lakeon-orchestrator/tests/conftest.py`

- [ ] **Step 1: 创建 pyproject.toml**

```toml
# lakeon-orchestrator/pyproject.toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "lakeon-orchestrator"
version = "0.1.0"
description = "Pipeline Orchestrator for Lakeon data pipeline"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.32.0",
    "sqlalchemy[asyncio]>=2.0.36",
    "asyncpg>=0.30.0",
    "pyyaml>=6.0.2",
    "ray[default]>=2.40.0",
    "boto3>=1.35.0",
    "pydantic>=2.10.0",
    "pydantic-settings>=2.6.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.3.0",
    "pytest-asyncio>=0.24.0",
    "pytest-cov>=6.0.0",
    "httpx>=0.28.0",
    "aiosqlite>=0.20.0",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 2: 创建 config.py**

```python
# src/lakeon_orchestrator/config.py
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Database (shared RDS with lakeon-api)
    database_url: str = "postgresql+asyncpg://lakeon:lakeon@localhost:5432/lakeon"

    # OBS / S3-compatible storage
    obs_endpoint: str = "https://obs.cn-north-4.myhuaweicloud.com"
    obs_access_key: str = ""
    obs_secret_key: str = ""
    obs_bucket: str = "lakeon-data"

    # Ray
    ray_address: str = "auto"
    ray_namespace: str = "lakeon-pipeline"

    # Server
    host: str = "0.0.0.0"
    port: int = 8090

    model_config = {"env_prefix": "LAKEON_ORCH_"}


settings = Settings()
```

- [ ] **Step 3: 创建 main.py (FastAPI app)**

```python
# src/lakeon_orchestrator/main.py
from contextlib import asynccontextmanager

from fastapi import FastAPI

from lakeon_orchestrator.config import settings
from lakeon_orchestrator.db.engine import init_db, close_db


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    yield
    await close_db()


app = FastAPI(
    title="Lakeon Pipeline Orchestrator",
    version="0.1.0",
    lifespan=lifespan,
)


def create_app() -> FastAPI:
    from lakeon_orchestrator.api.runs import router as runs_router

    app.include_router(runs_router, prefix="/runs", tags=["runs"])
    return app


if __name__ == "__main__":
    import uvicorn

    create_app()
    uvicorn.run(app, host=settings.host, port=settings.port)
```

- [ ] **Step 4: 创建 __init__.py 和目录结构**

为所有包创建 `__init__.py`（空文件）：
- `src/lakeon_orchestrator/__init__.py`
- `src/lakeon_orchestrator/api/__init__.py`
- `src/lakeon_orchestrator/db/__init__.py`
- `src/lakeon_orchestrator/dag/__init__.py`
- `src/lakeon_orchestrator/component/__init__.py`
- `src/lakeon_orchestrator/ray_client/__init__.py`
- `src/lakeon_orchestrator/checkpoint/__init__.py`
- `src/lakeon_orchestrator/pause/__init__.py`
- `tests/__init__.py`

- [ ] **Step 5: 创建 .env.example**

```env
LAKEON_ORCH_DATABASE_URL=postgresql+asyncpg://lakeon:lakeon@localhost:5432/lakeon
LAKEON_ORCH_OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com
LAKEON_ORCH_OBS_ACCESS_KEY=
LAKEON_ORCH_OBS_SECRET_KEY=
LAKEON_ORCH_OBS_BUCKET=lakeon-data
LAKEON_ORCH_RAY_ADDRESS=auto
LAKEON_ORCH_RAY_NAMESPACE=lakeon-pipeline
LAKEON_ORCH_HOST=0.0.0.0
LAKEON_ORCH_PORT=8090
```

- [ ] **Step 6: 创建 Dockerfile**

```dockerfile
# lakeon-orchestrator/Dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml .
RUN pip install --no-cache-dir .

COPY src/ src/

ENV PYTHONPATH=/app/src
EXPOSE 8090

CMD ["python", "-m", "uvicorn", "lakeon_orchestrator.main:app", "--host", "0.0.0.0", "--port", "8090"]
```

- [ ] **Step 7: 创建 conftest.py**

```python
# tests/conftest.py
import asyncio
from typing import AsyncGenerator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker

from lakeon_orchestrator.db.models import Base

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"


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
```

- [ ] **Step 8: 验证脚手架**

Run:
```bash
cd lakeon-orchestrator && pip install -e ".[dev]" && python -c "from lakeon_orchestrator.config import Settings; print('OK')"
```

- [ ] **Step 9: Commit**

```bash
git add lakeon-orchestrator/
git commit -m "feat(orchestrator): scaffold Python project with FastAPI + SQLAlchemy"
```

---

## Task 2: 数据库连接 — SQLAlchemy 模型 + State Manager

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/db/engine.py`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/db/models.py`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/db/state_manager.py`
- Create: `lakeon-orchestrator/tests/test_state_manager.py`

- [ ] **Step 1: 编写 State Manager 测试**

```python
# tests/test_state_manager.py
import pytest
from datetime import datetime, timezone

from lakeon_orchestrator.db.state_manager import StateManager
from lakeon_orchestrator.db.models import PipelineRun, PipelineStepRun


@pytest.mark.asyncio
async def test_create_run(db_session):
    sm = StateManager(db_session)
    run = await sm.create_run(
        run_id="run_test001",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
        input_dataset_id="ds_input",
        input_dataset_version=1,
    )
    assert run.id == "run_test001"
    assert run.status == "PENDING"


@pytest.mark.asyncio
async def test_update_run_status(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test002",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    updated = await sm.update_run_status("run_test002", "RUNNING")
    assert updated.status == "RUNNING"
    assert updated.started_at is not None


@pytest.mark.asyncio
async def test_create_step_run(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test003",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    step = await sm.create_step_run(
        step_run_id="sr_test001",
        run_id="run_test003",
        step_id="normalize",
        component_id="comp_normalize",
        component_version=1,
    )
    assert step.id == "sr_test001"
    assert step.status == "PENDING"


@pytest.mark.asyncio
async def test_update_step_status(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test004",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    await sm.create_step_run(
        step_run_id="sr_test002",
        run_id="run_test004",
        step_id="normalize",
    )
    updated = await sm.update_step_status(
        "sr_test002", "SUCCEEDED", output_ref='{"video": "ref_123"}', metrics='{"input_count": 1}'
    )
    assert updated.status == "SUCCEEDED"
    assert updated.finished_at is not None


@pytest.mark.asyncio
async def test_get_step_runs_by_run_id(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test005",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    await sm.create_step_run(step_run_id="sr_a", run_id="run_test005", step_id="step_a")
    await sm.create_step_run(step_run_id="sr_b", run_id="run_test005", step_id="step_b")
    steps = await sm.get_step_runs("run_test005")
    assert len(steps) == 2


@pytest.mark.asyncio
async def test_get_active_runs(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_active1", pipeline_id="pipe_abc", pipeline_version=1, tenant_id="tn_test"
    )
    await sm.update_run_status("run_active1", "RUNNING")
    await sm.create_run(
        run_id="run_done1", pipeline_id="pipe_abc", pipeline_version=1, tenant_id="tn_test"
    )
    await sm.update_run_status("run_done1", "SUCCEEDED")
    active = await sm.get_active_runs()
    assert len(active) == 1
    assert active[0].id == "run_active1"
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_state_manager.py -v`

Expected: ImportError — models and state_manager not yet implemented.

- [ ] **Step 3: 实现 engine.py**

```python
# src/lakeon_orchestrator/db/engine.py
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker

from lakeon_orchestrator.config import settings

_engine = None
_session_factory = None


async def init_db():
    global _engine, _session_factory
    _engine = create_async_engine(settings.database_url, pool_size=10, max_overflow=5)
    _session_factory = async_sessionmaker(_engine, expire_on_commit=False)


async def close_db():
    global _engine
    if _engine:
        await _engine.dispose()


def get_session_factory() -> async_sessionmaker[AsyncSession]:
    if _session_factory is None:
        raise RuntimeError("Database not initialized. Call init_db() first.")
    return _session_factory
```

- [ ] **Step 4: 实现 models.py**

映射 Plan 1 创建的表（不创建表，只映射）：

```python
# src/lakeon_orchestrator/db/models.py
from datetime import datetime, timezone

from sqlalchemy import String, Integer, BigInteger, Boolean, Text, DateTime, Index
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class PipelineRun(Base):
    __tablename__ = "pipeline_runs"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    pipeline_id: Mapped[str] = mapped_column(String(64), nullable=False)
    pipeline_version: Mapped[int] = mapped_column(Integer, nullable=False)
    tenant_id: Mapped[str] = mapped_column(String(64), nullable=False)
    input_dataset_id: Mapped[str | None] = mapped_column(String(64))
    input_dataset_version: Mapped[int | None] = mapped_column(Integer)
    output_dataset_version_id: Mapped[str | None] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="PENDING")
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        Index("idx_run_pipeline", "pipeline_id"),
        Index("idx_run_tenant", "tenant_id"),
        Index("idx_run_status", "status"),
    )


class PipelineStepRun(Base):
    __tablename__ = "pipeline_step_runs"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(String(64), nullable=False)
    step_id: Mapped[str] = mapped_column(String(128), nullable=False)
    component_id: Mapped[str | None] = mapped_column(String(64))
    component_version: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="PENDING")
    input_ref: Mapped[str | None] = mapped_column(Text)
    output_ref: Mapped[str | None] = mapped_column(Text)
    checkpoint_path: Mapped[str | None] = mapped_column(String(512))
    metrics: Mapped[str | None] = mapped_column(Text)
    error: Mapped[str | None] = mapped_column(Text)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    __table_args__ = (
        Index("idx_sr_run", "run_id"),
        Index("idx_sr_status", "status"),
    )


class PipelineVersion(Base):
    """Read-only: Orchestrator reads dag_yaml from this table."""
    __tablename__ = "pipeline_versions"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    pipeline_id: Mapped[str] = mapped_column(String(64), nullable=False)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    dag_yaml: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(16), default="DRAFT")
    changelog: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )


class PipelineComponentVersion(Base):
    """Read-only: Orchestrator reads entrypoint to load component."""
    __tablename__ = "pipeline_component_versions"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    component_id: Mapped[str] = mapped_column(String(64), nullable=False)
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    entrypoint: Mapped[str] = mapped_column(String(256), nullable=False)
    params_schema: Mapped[str | None] = mapped_column(Text)
    input_schema: Mapped[str | None] = mapped_column(Text)
    output_schema: Mapped[str | None] = mapped_column(Text)
    output_branches: Mapped[str | None] = mapped_column(Text)
    requires_gpu: Mapped[bool] = mapped_column(Boolean, default=False)
    requires_model: Mapped[str | None] = mapped_column(String(128))
    execution_mode: Mapped[str] = mapped_column(String(20), default="FUNCTION")
    status: Mapped[str] = mapped_column(String(16), default="DRAFT")
    changelog: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
```

- [ ] **Step 5: 实现 state_manager.py**

```python
# src/lakeon_orchestrator/db/state_manager.py
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from lakeon_orchestrator.db.models import PipelineRun, PipelineStepRun, PipelineVersion


class StateManager:
    def __init__(self, session: AsyncSession):
        self._session = session

    async def create_run(
        self,
        run_id: str,
        pipeline_id: str,
        pipeline_version: int,
        tenant_id: str,
        input_dataset_id: Optional[str] = None,
        input_dataset_version: Optional[int] = None,
    ) -> PipelineRun:
        run = PipelineRun(
            id=run_id,
            pipeline_id=pipeline_id,
            pipeline_version=pipeline_version,
            tenant_id=tenant_id,
            input_dataset_id=input_dataset_id,
            input_dataset_version=input_dataset_version,
            status="PENDING",
        )
        self._session.add(run)
        await self._session.flush()
        return run

    async def get_run(self, run_id: str) -> Optional[PipelineRun]:
        result = await self._session.get(PipelineRun, run_id)
        return result

    async def update_run_status(
        self,
        run_id: str,
        status: str,
        output_dataset_version_id: Optional[str] = None,
    ) -> PipelineRun:
        run = await self.get_run(run_id)
        if run is None:
            raise ValueError(f"Run {run_id} not found")
        run.status = status
        now = datetime.now(timezone.utc)
        if status == "RUNNING" and run.started_at is None:
            run.started_at = now
        if status in ("SUCCEEDED", "FAILED", "CANCELLED"):
            run.finished_at = now
        if output_dataset_version_id:
            run.output_dataset_version_id = output_dataset_version_id
        await self._session.flush()
        return run

    async def create_step_run(
        self,
        step_run_id: str,
        run_id: str,
        step_id: str,
        component_id: Optional[str] = None,
        component_version: Optional[int] = None,
    ) -> PipelineStepRun:
        step = PipelineStepRun(
            id=step_run_id,
            run_id=run_id,
            step_id=step_id,
            component_id=component_id,
            component_version=component_version,
            status="PENDING",
        )
        self._session.add(step)
        await self._session.flush()
        return step

    async def update_step_status(
        self,
        step_run_id: str,
        status: str,
        output_ref: Optional[str] = None,
        checkpoint_path: Optional[str] = None,
        metrics: Optional[str] = None,
        error: Optional[str] = None,
    ) -> PipelineStepRun:
        step = await self._session.get(PipelineStepRun, step_run_id)
        if step is None:
            raise ValueError(f"StepRun {step_run_id} not found")
        step.status = status
        now = datetime.now(timezone.utc)
        if status == "RUNNING" and step.started_at is None:
            step.started_at = now
        if status in ("SUCCEEDED", "FAILED", "SKIPPED"):
            step.finished_at = now
        if output_ref is not None:
            step.output_ref = output_ref
        if checkpoint_path is not None:
            step.checkpoint_path = checkpoint_path
        if metrics is not None:
            step.metrics = metrics
        if error is not None:
            step.error = error
        await self._session.flush()
        return step

    async def get_step_runs(self, run_id: str) -> list[PipelineStepRun]:
        result = await self._session.execute(
            select(PipelineStepRun).where(PipelineStepRun.run_id == run_id)
        )
        return list(result.scalars().all())

    async def get_active_runs(self) -> list[PipelineRun]:
        """Get RUNNING and PAUSED runs (for recovery on restart)."""
        result = await self._session.execute(
            select(PipelineRun).where(PipelineRun.status.in_(["RUNNING", "PAUSED"]))
        )
        return list(result.scalars().all())

    async def get_pipeline_version(
        self, pipeline_id: str, version: int
    ) -> Optional[PipelineVersion]:
        result = await self._session.execute(
            select(PipelineVersion).where(
                PipelineVersion.pipeline_id == pipeline_id,
                PipelineVersion.version == version,
            )
        )
        return result.scalar_one_or_none()
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_state_manager.py -v`

- [ ] **Step 7: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/db/ lakeon-orchestrator/tests/test_state_manager.py
git commit -m "feat(orchestrator): add SQLAlchemy models and StateManager for pipeline state"
```

---

## Task 3: DAG 解析器 — YAML 转内存 DAG 图

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/dag/parser.py`
- Create: `lakeon-orchestrator/tests/test_dag_parser.py`

- [ ] **Step 1: 编写 DAG 解析器测试**

```python
# tests/test_dag_parser.py
import pytest
from lakeon_orchestrator.dag.parser import DAGParser, DAGNode, DAG


SIMPLE_YAML = """
name: test-pipeline
data_type: VIDEO
steps:
  - id: normalize
    component: video_normalize
    component_version: 1
    params: { target_resolution: "1080p" }
    inputs: { video: "$input.dataset" }
    outputs: { video: normalized }

  - id: scene_split
    component: video_scene_split
    component_version: 1
    params: { threshold: 27 }
    inputs: { video: normalize.video }
    fan_out: true
    checkpoint: true
    outputs: { clips: split_clips }
"""

BRANCH_YAML = """
name: branch-pipeline
data_type: VIDEO
steps:
  - id: filter
    component: rule_filter
    component_version: 1
    inputs: { clip: scene_split.clips }
    output_branches: [passed, needs_crop, dropped]
    outputs: { passed: passed_clip, needs_crop: crop_clip }

  - id: crop
    component: video_crop
    component_version: 1
    condition: "filter.needs_crop"
    inputs: { clip: filter.crop_clip }
    outputs: { clip: cropped_clip }

  - id: merge
    type: merge
    inputs: [filter.passed_clip, crop.clip]
    outputs: { clips: merged_clips }
"""


def test_parse_simple_dag():
    dag = DAGParser.parse(SIMPLE_YAML)
    assert isinstance(dag, DAG)
    assert dag.name == "test-pipeline"
    assert len(dag.nodes) == 2
    assert "normalize" in dag.nodes
    assert "scene_split" in dag.nodes


def test_parse_edges():
    dag = DAGParser.parse(SIMPLE_YAML)
    # scene_split depends on normalize (inputs reference normalize.video)
    assert "normalize" in dag.edges
    assert "scene_split" in dag.edges["normalize"]


def test_parse_fan_out_flag():
    dag = DAGParser.parse(SIMPLE_YAML)
    assert dag.nodes["scene_split"].fan_out is True
    assert dag.nodes["normalize"].fan_out is False


def test_parse_checkpoint_flag():
    dag = DAGParser.parse(SIMPLE_YAML)
    assert dag.nodes["scene_split"].checkpoint is True
    assert dag.nodes["normalize"].checkpoint is False


def test_parse_node_attributes():
    dag = DAGParser.parse(SIMPLE_YAML)
    node = dag.nodes["normalize"]
    assert node.component == "video_normalize"
    assert node.component_version == 1
    assert node.params == {"target_resolution": "1080p"}
    assert node.inputs == {"video": "$input.dataset"}
    assert node.outputs == {"video": "normalized"}


def test_parse_condition_branch():
    dag = DAGParser.parse(BRANCH_YAML)
    crop = dag.nodes["crop"]
    assert crop.condition == "filter.needs_crop"


def test_parse_output_branches():
    dag = DAGParser.parse(BRANCH_YAML)
    filter_node = dag.nodes["filter"]
    assert filter_node.output_branches == ["passed", "needs_crop", "dropped"]


def test_parse_merge_node():
    dag = DAGParser.parse(BRANCH_YAML)
    merge_node = dag.nodes["merge"]
    assert merge_node.node_type == "merge"
    assert merge_node.component is None


def test_roots():
    dag = DAGParser.parse(SIMPLE_YAML)
    roots = dag.get_roots()
    assert len(roots) == 1
    assert roots[0] == "normalize"


def test_invalid_yaml():
    with pytest.raises(ValueError, match="steps"):
        DAGParser.parse("name: bad\nno_steps: true")
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_dag_parser.py -v`

- [ ] **Step 3: 实现 DAG 解析器**

```python
# src/lakeon_orchestrator/dag/parser.py
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

import yaml


@dataclass
class DAGNode:
    id: str
    component: Optional[str] = None
    component_version: Optional[int] = None
    params: dict[str, Any] = field(default_factory=dict)
    inputs: dict[str, str] | list[str] = field(default_factory=dict)
    outputs: dict[str, str] = field(default_factory=dict)
    fan_out: bool = False
    checkpoint: bool = False
    condition: Optional[str] = None
    output_branches: list[str] = field(default_factory=list)
    execution_mode: str = "FUNCTION"
    depends_on: list[str] = field(default_factory=list)
    node_type: str = "component"  # "component" | "merge"
    output_dataset: Optional[dict[str, str]] = None


@dataclass
class DAG:
    name: str
    data_type: str
    description: str = ""
    nodes: dict[str, DAGNode] = field(default_factory=dict)
    edges: dict[str, list[str]] = field(default_factory=dict)  # parent -> [children]
    reverse_edges: dict[str, list[str]] = field(default_factory=dict)  # child -> [parents]

    def get_roots(self) -> list[str]:
        """Return node IDs with no incoming edges."""
        return [nid for nid in self.nodes if not self.reverse_edges.get(nid)]

    def get_children(self, node_id: str) -> list[str]:
        return self.edges.get(node_id, [])

    def get_parents(self, node_id: str) -> list[str]:
        return self.reverse_edges.get(node_id, [])


class DAGParser:
    @staticmethod
    def parse(yaml_str: str) -> DAG:
        data = yaml.safe_load(yaml_str)
        if not data or "steps" not in data:
            raise ValueError("Invalid pipeline YAML: missing 'steps' key")

        dag = DAG(
            name=data.get("name", ""),
            data_type=data.get("data_type", ""),
            description=data.get("description", ""),
        )

        # Pass 1: create nodes
        for step in data["steps"]:
            node = DAGNode(
                id=step["id"],
                component=step.get("component"),
                component_version=step.get("component_version"),
                params=step.get("params", {}),
                inputs=step.get("inputs", {}),
                outputs=step.get("outputs", {}),
                fan_out=step.get("fan_out", False),
                checkpoint=step.get("checkpoint", False),
                condition=step.get("condition"),
                output_branches=step.get("output_branches", []),
                execution_mode=step.get("execution_mode", "FUNCTION"),
                depends_on=step.get("depends_on", []),
                node_type=step.get("type", "component"),
                output_dataset=step.get("output_dataset"),
            )
            dag.nodes[node.id] = node

        # Pass 2: build edges from input references and depends_on
        for node in dag.nodes.values():
            parents = set(node.depends_on)

            # Parse input references like "normalize.video" -> depends on "normalize"
            input_values = (
                node.inputs.values() if isinstance(node.inputs, dict) else node.inputs
            )
            for ref in input_values:
                if isinstance(ref, str) and "." in ref and not ref.startswith("$"):
                    parent_id = ref.split(".")[0]
                    if parent_id in dag.nodes:
                        parents.add(parent_id)

            # Parse condition reference like "filter.needs_crop"
            if node.condition and "." in node.condition:
                parent_id = node.condition.split(".")[0]
                if parent_id in dag.nodes:
                    parents.add(parent_id)

            for parent_id in parents:
                dag.edges.setdefault(parent_id, [])
                if node.id not in dag.edges[parent_id]:
                    dag.edges[parent_id].append(node.id)
                dag.reverse_edges.setdefault(node.id, [])
                if parent_id not in dag.reverse_edges[node.id]:
                    dag.reverse_edges[node.id].append(parent_id)

        return dag
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_dag_parser.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/dag/parser.py lakeon-orchestrator/tests/test_dag_parser.py
git commit -m "feat(orchestrator): implement DAG parser — YAML to in-memory DAG graph"
```

---

## Task 4: DAG 调度器 — 拓扑排序 + 可执行步骤检测

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/dag/scheduler.py`
- Create: `lakeon-orchestrator/tests/test_dag_scheduler.py`

- [ ] **Step 1: 编写调度器测试**

```python
# tests/test_dag_scheduler.py
import pytest
from lakeon_orchestrator.dag.parser import DAGParser
from lakeon_orchestrator.dag.scheduler import DAGScheduler

LINEAR_YAML = """
name: linear
data_type: TEXT
steps:
  - id: step_a
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: step_b
    component: comp_b
    component_version: 1
    inputs: { text: step_a.text }
    outputs: { text: b_out }
  - id: step_c
    component: comp_c
    component_version: 1
    inputs: { text: step_b.text }
    outputs: { text: c_out }
"""

DIAMOND_YAML = """
name: diamond
data_type: TEXT
steps:
  - id: start
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: branch_1
    component: comp_b
    component_version: 1
    inputs: { text: start.text }
    outputs: { text: b1_out }
  - id: branch_2
    component: comp_c
    component_version: 1
    inputs: { text: start.text }
    outputs: { text: b2_out }
  - id: join
    type: merge
    inputs: [branch_1.text, branch_2.text]
    outputs: { text: merged }
"""


def test_topological_sort_linear():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    order = scheduler.topological_sort()
    assert order == ["step_a", "step_b", "step_c"]


def test_topological_sort_diamond():
    dag = DAGParser.parse(DIAMOND_YAML)
    scheduler = DAGScheduler(dag)
    order = scheduler.topological_sort()
    assert order.index("start") < order.index("branch_1")
    assert order.index("start") < order.index("branch_2")
    assert order.index("branch_1") < order.index("join")
    assert order.index("branch_2") < order.index("join")


def test_get_ready_steps_initial():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {"step_a": "PENDING", "step_b": "PENDING", "step_c": "PENDING"}
    ready = scheduler.get_ready_steps(step_statuses)
    assert ready == ["step_a"]


def test_get_ready_steps_after_first():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {"step_a": "SUCCEEDED", "step_b": "PENDING", "step_c": "PENDING"}
    ready = scheduler.get_ready_steps(step_statuses)
    assert ready == ["step_b"]


def test_get_ready_steps_parallel():
    dag = DAGParser.parse(DIAMOND_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {
        "start": "SUCCEEDED",
        "branch_1": "PENDING",
        "branch_2": "PENDING",
        "join": "PENDING",
    }
    ready = scheduler.get_ready_steps(step_statuses)
    assert set(ready) == {"branch_1", "branch_2"}


def test_get_ready_steps_join_waits():
    dag = DAGParser.parse(DIAMOND_YAML)
    scheduler = DAGScheduler(dag)
    step_statuses = {
        "start": "SUCCEEDED",
        "branch_1": "SUCCEEDED",
        "branch_2": "RUNNING",
        "join": "PENDING",
    }
    ready = scheduler.get_ready_steps(step_statuses)
    assert ready == []


def test_is_dag_complete():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    assert scheduler.is_complete({"step_a": "SUCCEEDED", "step_b": "SUCCEEDED", "step_c": "SUCCEEDED"})
    assert not scheduler.is_complete({"step_a": "SUCCEEDED", "step_b": "RUNNING", "step_c": "PENDING"})


def test_is_dag_failed():
    dag = DAGParser.parse(LINEAR_YAML)
    scheduler = DAGScheduler(dag)
    assert scheduler.has_failed({"step_a": "SUCCEEDED", "step_b": "FAILED", "step_c": "PENDING"})
    assert not scheduler.has_failed({"step_a": "SUCCEEDED", "step_b": "RUNNING", "step_c": "PENDING"})


def test_cycle_detection():
    cycle_yaml = """
name: cycle
data_type: TEXT
steps:
  - id: a
    component: comp_a
    component_version: 1
    inputs: { text: b.text }
    outputs: { text: a_out }
  - id: b
    component: comp_b
    component_version: 1
    inputs: { text: a.text }
    outputs: { text: b_out }
"""
    dag = DAGParser.parse(cycle_yaml)
    scheduler = DAGScheduler(dag)
    with pytest.raises(ValueError, match="cycle"):
        scheduler.topological_sort()
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_dag_scheduler.py -v`

- [ ] **Step 3: 实现调度器**

```python
# src/lakeon_orchestrator/dag/scheduler.py
from __future__ import annotations

from collections import deque

from lakeon_orchestrator.dag.parser import DAG

TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"}
DONE_STATUSES = {"SUCCEEDED", "SKIPPED"}


class DAGScheduler:
    def __init__(self, dag: DAG):
        self._dag = dag

    def topological_sort(self) -> list[str]:
        """Kahn's algorithm. Raises ValueError on cycle."""
        in_degree: dict[str, int] = {nid: 0 for nid in self._dag.nodes}
        for parent, children in self._dag.edges.items():
            for child in children:
                in_degree[child] = in_degree.get(child, 0) + 1

        queue = deque(nid for nid, deg in in_degree.items() if deg == 0)
        result: list[str] = []

        while queue:
            node_id = queue.popleft()
            result.append(node_id)
            for child in self._dag.get_children(node_id):
                in_degree[child] -= 1
                if in_degree[child] == 0:
                    queue.append(child)

        if len(result) != len(self._dag.nodes):
            raise ValueError("DAG contains a cycle — topological sort impossible")

        return result

    def get_ready_steps(self, step_statuses: dict[str, str]) -> list[str]:
        """Return step IDs that are PENDING and all parents are done."""
        ready = []
        for node_id, node in self._dag.nodes.items():
            if step_statuses.get(node_id) != "PENDING":
                continue
            parents = self._dag.get_parents(node_id)
            if all(step_statuses.get(p) in DONE_STATUSES for p in parents):
                # Check condition: if this node has a condition, verify the branch was active
                if node.condition:
                    # condition format: "parent_id.branch_name"
                    # The BranchRouter handles actual routing; here we just check parent done
                    pass
                ready.append(node_id)
        return ready

    def is_complete(self, step_statuses: dict[str, str]) -> bool:
        """All steps are in a terminal success state."""
        return all(
            step_statuses.get(nid) in DONE_STATUSES for nid in self._dag.nodes
        )

    def has_failed(self, step_statuses: dict[str, str]) -> bool:
        """Any step has failed."""
        return any(
            step_statuses.get(nid) == "FAILED" for nid in self._dag.nodes
        )

    def has_paused(self, step_statuses: dict[str, str]) -> bool:
        """Any step is paused (HUMAN_REVIEW)."""
        return any(
            step_statuses.get(nid) == "PAUSED" for nid in self._dag.nodes
        )

    def aggregate_status(self, step_statuses: dict[str, str]) -> str:
        """Compute overall pipeline run status from step statuses."""
        if self.is_complete(step_statuses):
            return "SUCCEEDED"
        if self.has_failed(step_statuses):
            return "FAILED"
        if self.has_paused(step_statuses) and not any(
            step_statuses.get(nid) == "RUNNING" for nid in self._dag.nodes
        ):
            return "PAUSED"
        if any(step_statuses.get(nid) == "RUNNING" for nid in self._dag.nodes):
            return "RUNNING"
        return "RUNNING"
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_dag_scheduler.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/dag/scheduler.py lakeon-orchestrator/tests/test_dag_scheduler.py
git commit -m "feat(orchestrator): implement DAG scheduler with topological sort and ready-step detection"
```

---

## Task 5: 组件框架核心 — @Component 装饰器 + ComponentContext

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/component/decorator.py`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/component/context.py`
- Create: `lakeon-orchestrator/tests/test_component_decorator.py`
- Create: `lakeon-orchestrator/tests/test_component_context.py`

- [ ] **Step 1: 编写装饰器测试**

```python
# tests/test_component_decorator.py
import pytest
from lakeon_orchestrator.component.decorator import Component, get_component_meta


def test_component_decorator_stores_metadata():
    @Component(
        name="test_comp",
        display_name="Test Component",
        category="EXTRACT",
        data_type="VIDEO",
        params_schema={"threshold": {"type": "number", "default": 27}},
        input_schema={"type": "video"},
        output_schema={"type": "video_clips"},
    )
    def my_component(ctx):
        return {"result": "ok"}

    meta = get_component_meta(my_component)
    assert meta["name"] == "test_comp"
    assert meta["display_name"] == "Test Component"
    assert meta["category"] == "EXTRACT"
    assert meta["data_type"] == "VIDEO"
    assert meta["params_schema"]["threshold"]["default"] == 27


def test_component_decorator_with_branches():
    @Component(
        name="filter",
        display_name="Filter",
        category="FILTER",
        data_type="VIDEO",
        output_branches=["passed", "dropped"],
    )
    def my_filter(ctx):
        pass

    meta = get_component_meta(my_filter)
    assert meta["output_branches"] == ["passed", "dropped"]


def test_component_decorator_preserves_callable():
    @Component(name="simple", display_name="Simple", category="DATA_PREP", data_type="TEXT")
    def simple_comp(ctx):
        return {"data": ctx.input}

    # The decorated function should still be callable
    assert callable(simple_comp)


def test_get_component_meta_undecorated():
    def plain_func(ctx):
        pass

    with pytest.raises(ValueError, match="not a registered component"):
        get_component_meta(plain_func)
```

- [ ] **Step 2: 编写 ComponentContext 测试**

```python
# tests/test_component_context.py
import pytest
from lakeon_orchestrator.component.context import ComponentContext


def test_context_input_and_params():
    ctx = ComponentContext(
        step_id="normalize",
        run_id="run_001",
        input_data={"video": "/path/to/video.mp4"},
        params={"target_resolution": "1080p"},
    )
    assert ctx.input["video"] == "/path/to/video.mp4"
    assert ctx.params["target_resolution"] == "1080p"


def test_context_report():
    ctx = ComponentContext(step_id="split", run_id="run_001", input_data={}, params={})
    ctx.report({"input_count": 1, "output_count": 42})
    assert ctx.metrics == {"input_count": 1, "output_count": 42}


def test_context_fan_out():
    ctx = ComponentContext(step_id="split", run_id="run_001", input_data={}, params={})
    result = ctx.fan_out(["clip1.mp4", "clip2.mp4", "clip3.mp4"])
    assert result["__fan_out__"] is True
    assert len(result["items"]) == 3


def test_context_classify():
    ctx = ComponentContext(step_id="filter", run_id="run_001", input_data={}, params={})
    result = ctx.classify({"clip": "a.mp4"}, "passed")
    assert result["__branch__"] == "passed"
    assert result["data"]["clip"] == "a.mp4"


def test_context_log():
    ctx = ComponentContext(step_id="step1", run_id="run_001", input_data={}, params={})
    ctx.log("Processing started")
    ctx.log("Step complete")
    assert len(ctx.logs) == 2
    assert "Processing started" in ctx.logs[0]


def test_context_checkpoint():
    ctx = ComponentContext(step_id="step1", run_id="run_001", input_data={}, params={})
    ctx.checkpoint({"intermediate": "data"})
    assert ctx.checkpoint_data == {"intermediate": "data"}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_component_decorator.py tests/test_component_context.py -v`

- [ ] **Step 4: 实现 @Component 装饰器**

```python
# src/lakeon_orchestrator/component/decorator.py
from __future__ import annotations

import functools
from typing import Any, Callable, Optional

_COMPONENT_META_ATTR = "__component_meta__"


def Component(
    name: str,
    display_name: str,
    category: str,
    data_type: str,
    params_schema: Optional[dict[str, Any]] = None,
    input_schema: Optional[dict[str, Any]] = None,
    output_schema: Optional[dict[str, Any]] = None,
    output_branches: Optional[list[str]] = None,
    requires_gpu: bool = False,
    requires_model: Optional[str] = None,
    execution_mode: str = "FUNCTION",
) -> Callable:
    """Decorator that attaches component metadata to a function."""

    def decorator(func: Callable) -> Callable:
        meta = {
            "name": name,
            "display_name": display_name,
            "category": category,
            "data_type": data_type,
            "params_schema": params_schema or {},
            "input_schema": input_schema or {},
            "output_schema": output_schema or {},
            "output_branches": output_branches or [],
            "requires_gpu": requires_gpu,
            "requires_model": requires_model,
            "execution_mode": execution_mode,
        }
        setattr(func, _COMPONENT_META_ATTR, meta)

        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            return func(*args, **kwargs)

        setattr(wrapper, _COMPONENT_META_ATTR, meta)
        return wrapper

    return decorator


def get_component_meta(func: Callable) -> dict[str, Any]:
    """Retrieve component metadata from a decorated function."""
    meta = getattr(func, _COMPONENT_META_ATTR, None)
    if meta is None:
        raise ValueError(f"'{func.__name__}' is not a registered component (missing @Component decorator)")
    return meta
```

- [ ] **Step 5: 实现 ComponentContext**

```python
# src/lakeon_orchestrator/component/context.py
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional


@dataclass
class ComponentContext:
    """Runtime context passed to every component function.

    Attributes:
        input: Upstream output data (dict of named values).
        params: User-configured parameters for this step.
    """

    step_id: str
    run_id: str
    input_data: dict[str, Any]
    params: dict[str, Any]

    # Populated by the component at runtime
    metrics: dict[str, Any] = field(default_factory=dict)
    logs: list[str] = field(default_factory=list)
    checkpoint_data: Optional[Any] = None

    @property
    def input(self) -> dict[str, Any]:
        return self.input_data

    def report(self, metrics: dict[str, Any]) -> None:
        """Report step-level metrics (e.g. input_count, output_count)."""
        self.metrics.update(metrics)

    def fan_out(self, items: list[Any]) -> dict[str, Any]:
        """Signal that this step produces N output items for fan-out expansion."""
        self.report({"output_count": len(items)})
        return {
            "__fan_out__": True,
            "items": items,
        }

    def classify(self, data: Any, label: str) -> dict[str, Any]:
        """Classify an item into a named branch for conditional routing."""
        return {
            "__branch__": label,
            "data": data,
        }

    def checkpoint(self, data: Any) -> None:
        """Mark intermediate data for async persistence to OBS."""
        self.checkpoint_data = data

    def log(self, msg: str) -> None:
        """Append a timestamped log message."""
        ts = datetime.now(timezone.utc).isoformat()
        self.logs.append(f"[{ts}] {msg}")
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_component_decorator.py tests/test_component_context.py -v`

- [ ] **Step 7: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/component/ lakeon-orchestrator/tests/test_component_decorator.py lakeon-orchestrator/tests/test_component_context.py
git commit -m "feat(orchestrator): implement @Component decorator and ComponentContext"
```

---

## Task 6: 组件加载器 — 动态加载组件函数

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/component/loader.py`
- Create: `lakeon-orchestrator/tests/test_component_loader.py`

- [ ] **Step 1: 编写加载器测试**

```python
# tests/test_component_loader.py
import pytest
from lakeon_orchestrator.component.loader import ComponentLoader


def test_load_builtin_component():
    """Load a module-level function by dotted path."""
    # Use a stdlib function as test target
    func = ComponentLoader.load("json.dumps")
    assert callable(func)
    assert func({"a": 1}) == '{"a": 1}'


def test_load_nonexistent_module():
    with pytest.raises(ImportError):
        ComponentLoader.load("nonexistent.module.func")


def test_load_nonexistent_function():
    with pytest.raises(AttributeError):
        ComponentLoader.load("json.nonexistent_func")


def test_load_caches_result():
    func1 = ComponentLoader.load("json.dumps")
    func2 = ComponentLoader.load("json.dumps")
    assert func1 is func2


def test_clear_cache():
    ComponentLoader.load("json.dumps")
    ComponentLoader.clear_cache()
    # After clear, should still work but load fresh
    func = ComponentLoader.load("json.dumps")
    assert callable(func)
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_component_loader.py -v`

- [ ] **Step 3: 实现加载器**

```python
# src/lakeon_orchestrator/component/loader.py
from __future__ import annotations

import importlib
from typing import Any, Callable


class ComponentLoader:
    """Dynamically load component functions from entrypoint paths.

    Entrypoint format: "module.path.function_name"
    e.g. "lakeon.components.video.scene_split.video_scene_split"
    """

    _cache: dict[str, Callable] = {}

    @classmethod
    def load(cls, entrypoint: str) -> Callable:
        """Load and cache a component function by its dotted entrypoint path."""
        if entrypoint in cls._cache:
            return cls._cache[entrypoint]

        module_path, _, func_name = entrypoint.rpartition(".")
        if not module_path:
            raise ImportError(f"Invalid entrypoint '{entrypoint}': must be 'module.function'")

        module = importlib.import_module(module_path)
        func = getattr(module, func_name)

        cls._cache[entrypoint] = func
        return func

    @classmethod
    def clear_cache(cls) -> None:
        cls._cache.clear()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_component_loader.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/component/loader.py lakeon-orchestrator/tests/test_component_loader.py
git commit -m "feat(orchestrator): implement component loader with dynamic entrypoint resolution"
```

---

## Task 7: Ray Client — 集群管理 + Task 提交

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/ray_client/client.py`
- Create: `lakeon-orchestrator/tests/test_ray_client.py`

- [ ] **Step 1: 编写 Ray Client 测试**

```python
# tests/test_ray_client.py
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from lakeon_orchestrator.ray_client.client import RayClient


@pytest.fixture
def ray_client():
    return RayClient(address="local", namespace="test")


def test_ray_client_init(ray_client):
    assert ray_client._address == "local"
    assert ray_client._namespace == "test"
    assert ray_client._connected is False


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_connect(mock_ray, ray_client):
    mock_ray.is_initialized.return_value = False
    ray_client.connect()
    mock_ray.init.assert_called_once_with(
        address="local", namespace="test", ignore_reinit_error=True
    )
    assert ray_client._connected is True


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_connect_already_initialized(mock_ray, ray_client):
    mock_ray.is_initialized.return_value = True
    ray_client.connect()
    mock_ray.init.assert_not_called()
    assert ray_client._connected is True


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_disconnect(mock_ray, ray_client):
    ray_client._connected = True
    ray_client.disconnect()
    mock_ray.shutdown.assert_called_once()
    assert ray_client._connected is False


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_submit_task(mock_ray, ray_client):
    ray_client._connected = True

    mock_func = MagicMock()
    mock_remote = MagicMock()
    mock_ref = MagicMock()
    mock_remote.remote.return_value = mock_ref
    mock_ray.remote.return_value = mock_remote

    ref = ray_client.submit_task(mock_func, "arg1", key="val1")
    mock_ray.remote.assert_called_once_with(mock_func)
    mock_remote.remote.assert_called_once_with("arg1", key="val1")
    assert ref == mock_ref


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_submit_task_not_connected(mock_ray, ray_client):
    ray_client._connected = False
    with pytest.raises(RuntimeError, match="Not connected"):
        ray_client.submit_task(lambda: None)


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_get_result(mock_ray, ray_client):
    ray_client._connected = True
    mock_ref = MagicMock()
    mock_ray.get.return_value = {"result": "ok"}
    result = ray_client.get_result(mock_ref, timeout=30)
    mock_ray.get.assert_called_once_with(mock_ref, timeout=30)
    assert result == {"result": "ok"}


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_put_object(mock_ray, ray_client):
    ray_client._connected = True
    mock_ray.put.return_value = "obj_ref_123"
    ref = ray_client.put_object({"data": "test"})
    mock_ray.put.assert_called_once_with({"data": "test"})
    assert ref == "obj_ref_123"
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_ray_client.py -v`

- [ ] **Step 3: 实现 Ray Client**

```python
# src/lakeon_orchestrator/ray_client/client.py
from __future__ import annotations

import logging
from typing import Any, Callable, Optional

import ray

logger = logging.getLogger(__name__)


class RayClient:
    """Manages a persistent Ray cluster connection for pipeline execution.

    Lifecycle:
    - connect() at pipeline run start (or reuse existing)
    - submit_task() for each component step
    - get_result() to retrieve output (blocking or async)
    - put_object() / get_object() for inter-step data in object store
    - disconnect() when pipeline completes or pauses for HUMAN_REVIEW
    """

    def __init__(self, address: str = "auto", namespace: str = "lakeon-pipeline"):
        self._address = address
        self._namespace = namespace
        self._connected = False

    def connect(self) -> None:
        if ray.is_initialized():
            logger.info("Ray already initialized, reusing connection")
            self._connected = True
            return
        ray.init(address=self._address, namespace=self._namespace, ignore_reinit_error=True)
        self._connected = True
        logger.info(f"Connected to Ray cluster at {self._address}")

    def disconnect(self) -> None:
        if self._connected:
            ray.shutdown()
            self._connected = False
            logger.info("Disconnected from Ray cluster")

    @property
    def is_connected(self) -> bool:
        return self._connected

    def _ensure_connected(self) -> None:
        if not self._connected:
            raise RuntimeError("Not connected to Ray cluster. Call connect() first.")

    def submit_task(
        self,
        func: Callable,
        *args: Any,
        num_cpus: Optional[int] = None,
        num_gpus: Optional[int] = None,
        **kwargs: Any,
    ) -> ray.ObjectRef:
        """Submit a function as a Ray remote task.

        The function is wrapped with ray.remote() and submitted. Returns an ObjectRef
        that can be used to retrieve the result or pass to downstream steps.
        """
        self._ensure_connected()
        remote_options = {}
        if num_cpus is not None:
            remote_options["num_cpus"] = num_cpus
        if num_gpus is not None:
            remote_options["num_gpus"] = num_gpus

        if remote_options:
            remote_func = ray.remote(**remote_options)(func)
        else:
            remote_func = ray.remote(func)

        return remote_func.remote(*args, **kwargs)

    def get_result(self, ref: ray.ObjectRef, timeout: Optional[int] = None) -> Any:
        """Block until a Ray task completes and return its result."""
        self._ensure_connected()
        return ray.get(ref, timeout=timeout)

    def put_object(self, data: Any) -> ray.ObjectRef:
        """Put data into the Ray object store. Returns an ObjectRef."""
        self._ensure_connected()
        return ray.put(data)

    def get_object(self, ref: ray.ObjectRef) -> Any:
        """Get data from the Ray object store."""
        self._ensure_connected()
        return ray.get(ref)
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_ray_client.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/ray_client/ lakeon-orchestrator/tests/test_ray_client.py
git commit -m "feat(orchestrator): implement Ray client for cluster lifecycle and task submission"
```

---

## Task 8: Fan-out/Fan-in Handler

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/dag/fan_out_handler.py`
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/dag/fan_in_handler.py`
- Create: `lakeon-orchestrator/tests/test_fan_out_handler.py`
- Create: `lakeon-orchestrator/tests/test_fan_in_handler.py`

- [ ] **Step 1: 编写 Fan-out Handler 测试**

```python
# tests/test_fan_out_handler.py
import pytest
from lakeon_orchestrator.dag.fan_out_handler import FanOutHandler


def test_expand_fan_out_result():
    handler = FanOutHandler()
    result = {
        "__fan_out__": True,
        "items": ["clip1.mp4", "clip2.mp4", "clip3.mp4"],
    }
    expanded = handler.expand(
        fan_out_result=result,
        source_step_id="scene_split",
        downstream_step_id="rule_filter",
        run_id="run_001",
    )
    assert len(expanded) == 3
    assert expanded[0]["step_run_id"] == "sr_run_001_rule_filter_0"
    assert expanded[0]["input_data"] == "clip1.mp4"
    assert expanded[1]["step_run_id"] == "sr_run_001_rule_filter_1"
    assert expanded[2]["input_data"] == "clip3.mp4"


def test_expand_empty_items():
    handler = FanOutHandler()
    result = {"__fan_out__": True, "items": []}
    expanded = handler.expand(
        fan_out_result=result,
        source_step_id="split",
        downstream_step_id="filter",
        run_id="run_002",
    )
    assert expanded == []


def test_is_fan_out_result():
    handler = FanOutHandler()
    assert handler.is_fan_out({"__fan_out__": True, "items": []}) is True
    assert handler.is_fan_out({"data": "normal"}) is False
    assert handler.is_fan_out(None) is False


def test_generate_step_run_id():
    handler = FanOutHandler()
    sid = handler.generate_step_run_id("run_001", "filter", 5)
    assert sid == "sr_run_001_filter_5"
```

- [ ] **Step 2: 编写 Fan-in Handler 测试**

```python
# tests/test_fan_in_handler.py
import pytest
from lakeon_orchestrator.dag.fan_in_handler import FanInHandler


def test_merge_results():
    handler = FanInHandler()
    branch_results = [
        {"data": "clip1.mp4", "source": "filter_0"},
        {"data": "clip3.mp4", "source": "filter_2"},
    ]
    merged = handler.merge(branch_results)
    assert len(merged["items"]) == 2
    assert merged["items"][0] == "clip1.mp4"
    assert merged["items"][1] == "clip3.mp4"


def test_merge_empty():
    handler = FanInHandler()
    merged = handler.merge([])
    assert merged["items"] == []


def test_merge_preserves_order():
    handler = FanInHandler()
    results = [
        {"data": "c", "source": "s_2"},
        {"data": "a", "source": "s_0"},
        {"data": "b", "source": "s_1"},
    ]
    merged = handler.merge(results)
    # Order preserved as given
    assert merged["items"] == ["c", "a", "b"]


def test_merge_with_branch_labels():
    handler = FanInHandler()
    results = [
        {"data": "clip1.mp4", "branch": "passed"},
        {"data": "clip2.mp4", "branch": "passed"},
    ]
    merged = handler.merge(results, expected_branches=["passed"])
    assert len(merged["items"]) == 2
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_fan_out_handler.py tests/test_fan_in_handler.py -v`

- [ ] **Step 4: 实现 Fan-out Handler**

```python
# src/lakeon_orchestrator/dag/fan_out_handler.py
from __future__ import annotations

from typing import Any, Optional


class FanOutHandler:
    """Handles 1→N expansion when a component returns fan_out results.

    When a step calls ctx.fan_out(items), the Orchestrator:
    1. Creates N new step_runs for the downstream step (one per item)
    2. Each step_run receives one item as input
    3. All run in parallel on the Ray cluster
    """

    @staticmethod
    def is_fan_out(result: Any) -> bool:
        """Check if a component result signals fan-out."""
        if not isinstance(result, dict):
            return False
        return result.get("__fan_out__") is True

    @staticmethod
    def generate_step_run_id(run_id: str, step_id: str, index: int) -> str:
        return f"sr_{run_id}_{step_id}_{index}"

    def expand(
        self,
        fan_out_result: dict[str, Any],
        source_step_id: str,
        downstream_step_id: str,
        run_id: str,
    ) -> list[dict[str, Any]]:
        """Expand a fan-out result into individual step run descriptors.

        Returns a list of dicts, each containing:
        - step_run_id: unique ID for the expanded step run
        - step_id: the downstream step ID (with index suffix)
        - input_data: the individual item
        - index: the item index
        """
        items = fan_out_result.get("items", [])
        expanded = []
        for i, item in enumerate(items):
            expanded.append({
                "step_run_id": self.generate_step_run_id(run_id, downstream_step_id, i),
                "step_id": f"{downstream_step_id}",
                "input_data": item,
                "index": i,
                "source_step_id": source_step_id,
            })
        return expanded
```

- [ ] **Step 5: 实现 Fan-in Handler**

```python
# src/lakeon_orchestrator/dag/fan_in_handler.py
from __future__ import annotations

from typing import Any, Optional


class FanInHandler:
    """Handles N→1 merge when multiple parallel branches converge.

    Collects results from all upstream branches/fan-out expansions
    and merges them into a single input for the downstream step.
    """

    def merge(
        self,
        branch_results: list[dict[str, Any]],
        expected_branches: Optional[list[str]] = None,
    ) -> dict[str, Any]:
        """Merge results from multiple branches into a single collection.

        Args:
            branch_results: List of result dicts, each with "data" key.
            expected_branches: If provided, only include results from these branches.

        Returns:
            Dict with "items" key containing the merged data list.
        """
        items = []
        for result in branch_results:
            data = result.get("data")
            if expected_branches:
                branch = result.get("branch")
                if branch and branch not in expected_branches:
                    continue
            if data is not None:
                items.append(data)

        return {
            "__merged__": True,
            "items": items,
            "count": len(items),
        }

    @staticmethod
    def is_all_complete(
        step_statuses: dict[str, str], expected_step_ids: list[str]
    ) -> bool:
        """Check if all expected upstream steps have completed."""
        done = {"SUCCEEDED", "SKIPPED"}
        return all(step_statuses.get(sid) in done for sid in expected_step_ids)
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_fan_out_handler.py tests/test_fan_in_handler.py -v`

- [ ] **Step 7: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/dag/fan_out_handler.py lakeon-orchestrator/src/lakeon_orchestrator/dag/fan_in_handler.py lakeon-orchestrator/tests/test_fan_out_handler.py lakeon-orchestrator/tests/test_fan_in_handler.py
git commit -m "feat(orchestrator): implement fan-out expansion and fan-in merge handlers"
```

---

## Task 9: 条件分支路由 — BranchRouter

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/dag/branch_router.py`
- Create: `lakeon-orchestrator/tests/test_branch_router.py`

- [ ] **Step 1: 编写 BranchRouter 测试**

```python
# tests/test_branch_router.py
import pytest
from lakeon_orchestrator.dag.parser import DAGParser
from lakeon_orchestrator.dag.branch_router import BranchRouter

BRANCH_YAML = """
name: branch-test
data_type: VIDEO
steps:
  - id: filter
    component: rule_filter
    component_version: 1
    inputs: { clip: "$input.clip" }
    output_branches: [passed, needs_crop, dropped]
    outputs: { passed: passed_clip, needs_crop: crop_clip }

  - id: crop
    component: video_crop
    component_version: 1
    condition: "filter.needs_crop"
    inputs: { clip: filter.crop_clip }
    outputs: { clip: cropped_clip }

  - id: merge
    type: merge
    inputs: [filter.passed_clip, crop.clip]
    outputs: { clips: merged }

  - id: drop_log
    component: logger
    component_version: 1
    condition: "filter.dropped"
    inputs: { clip: filter.passed_clip }
    outputs: { log: drop_log_out }
"""


@pytest.fixture
def router():
    dag = DAGParser.parse(BRANCH_YAML)
    return BranchRouter(dag)


def test_route_to_matching_branch(router):
    classify_result = {"__branch__": "needs_crop", "data": {"clip": "a.mp4"}}
    routes = router.route("filter", classify_result)
    assert "crop" in routes
    assert "drop_log" not in routes


def test_route_passed_branch(router):
    classify_result = {"__branch__": "passed", "data": {"clip": "b.mp4"}}
    routes = router.route("filter", classify_result)
    # "passed" items go to merge (no condition = accepts passed)
    # "crop" has condition "filter.needs_crop" -> not matched
    assert "crop" not in routes


def test_route_dropped_branch(router):
    classify_result = {"__branch__": "dropped", "data": {"clip": "c.mp4"}}
    routes = router.route("filter", classify_result)
    assert "drop_log" in routes
    assert "crop" not in routes


def test_get_skipped_steps(router):
    # If no items were routed to "crop", it should be skipped
    active_branches = {"passed"}
    skipped = router.get_skipped_steps("filter", active_branches)
    assert "crop" in skipped
    assert "drop_log" in skipped


def test_is_branch_result():
    assert BranchRouter.is_branch_result({"__branch__": "passed", "data": {}}) is True
    assert BranchRouter.is_branch_result({"data": "normal"}) is False
    assert BranchRouter.is_branch_result(None) is False
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_branch_router.py -v`

- [ ] **Step 3: 实现 BranchRouter**

```python
# src/lakeon_orchestrator/dag/branch_router.py
from __future__ import annotations

from typing import Any, Optional

from lakeon_orchestrator.dag.parser import DAG


class BranchRouter:
    """Routes classified items to the correct downstream step based on branch labels.

    When a component uses ctx.classify(item, label), the BranchRouter checks
    which downstream steps have a `condition` matching that label and routes
    the item accordingly.
    """

    def __init__(self, dag: DAG):
        self._dag = dag

    @staticmethod
    def is_branch_result(result: Any) -> bool:
        if not isinstance(result, dict):
            return False
        return "__branch__" in result

    def route(self, source_step_id: str, classify_result: dict[str, Any]) -> list[str]:
        """Determine which downstream steps should receive this classified item.

        Args:
            source_step_id: The step that produced the classification.
            classify_result: Dict with "__branch__" and "data" keys.

        Returns:
            List of downstream step IDs that match the branch label.
        """
        branch_label = classify_result.get("__branch__")
        if not branch_label:
            return []

        children = self._dag.get_children(source_step_id)
        matching = []
        for child_id in children:
            child_node = self._dag.nodes[child_id]
            if child_node.condition:
                # condition format: "source_step_id.branch_name"
                parts = child_node.condition.split(".")
                if len(parts) == 2 and parts[0] == source_step_id and parts[1] == branch_label:
                    matching.append(child_id)
            # merge nodes don't have conditions — they collect from multiple sources
            # They are not directly routed to by branch results
        return matching

    def get_skipped_steps(
        self, source_step_id: str, active_branches: set[str]
    ) -> list[str]:
        """Identify downstream conditional steps that should be SKIPPED.

        Steps whose condition branch was never activated should be marked SKIPPED.

        Args:
            source_step_id: The branching step.
            active_branches: Set of branch labels that had at least one item.

        Returns:
            List of step IDs to skip.
        """
        children = self._dag.get_children(source_step_id)
        skipped = []
        for child_id in children:
            child_node = self._dag.nodes[child_id]
            if child_node.condition:
                parts = child_node.condition.split(".")
                if len(parts) == 2 and parts[0] == source_step_id:
                    branch_name = parts[1]
                    if branch_name not in active_branches:
                        skipped.append(child_id)
        return skipped
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_branch_router.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/dag/branch_router.py lakeon-orchestrator/tests/test_branch_router.py
git commit -m "feat(orchestrator): implement BranchRouter for conditional step routing"
```

---

## Task 10: 暂停/恢复管理 — PauseManager

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/pause/manager.py`
- Create: `lakeon-orchestrator/tests/test_pause_manager.py`

- [ ] **Step 1: 编写 PauseManager 测试**

```python
# tests/test_pause_manager.py
import pytest
from unittest.mock import AsyncMock, MagicMock
from lakeon_orchestrator.pause.manager import PauseManager


@pytest.fixture
def checkpoint_mgr():
    mgr = MagicMock()
    mgr.save = AsyncMock(return_value="obs://bucket/checkpoints/run_001/qc/data.pkl")
    mgr.load = AsyncMock(return_value={"clips": ["a.mp4", "b.mp4"]})
    return mgr


@pytest.fixture
def state_mgr():
    mgr = MagicMock()
    mgr.update_step_status = AsyncMock()
    mgr.update_run_status = AsyncMock()
    return mgr


@pytest.fixture
def ray_client():
    client = MagicMock()
    client.get_result.return_value = {"clips": ["a.mp4", "b.mp4"]}
    client.disconnect = MagicMock()
    client.connect = MagicMock()
    client.put_object.return_value = "obj_ref_123"
    return client


@pytest.mark.asyncio
async def test_pause_step(checkpoint_mgr, state_mgr, ray_client):
    pm = PauseManager(
        checkpoint_manager=checkpoint_mgr,
        state_manager=state_mgr,
        ray_client=ray_client,
    )
    await pm.pause_step(
        run_id="run_001",
        step_run_id="sr_qc",
        step_id="qc",
        data_ref="ray_obj_ref",
    )
    # Should save checkpoint
    checkpoint_mgr.save.assert_awaited_once()
    # Should update step status to PAUSED
    state_mgr.update_step_status.assert_awaited_with(
        "sr_qc", "PAUSED",
        checkpoint_path="obs://bucket/checkpoints/run_001/qc/data.pkl",
    )
    # Should update run status to PAUSED
    state_mgr.update_run_status.assert_awaited_with("run_001", "PAUSED")
    # Should disconnect Ray (release cluster)
    ray_client.disconnect.assert_called_once()


@pytest.mark.asyncio
async def test_resume_step(checkpoint_mgr, state_mgr, ray_client):
    pm = PauseManager(
        checkpoint_manager=checkpoint_mgr,
        state_manager=state_mgr,
        ray_client=ray_client,
    )
    data_ref = await pm.resume_step(
        run_id="run_001",
        step_run_id="sr_qc",
        step_id="qc",
        checkpoint_path="obs://bucket/checkpoints/run_001/qc/data.pkl",
    )
    # Should reconnect Ray
    ray_client.connect.assert_called_once()
    # Should load checkpoint from OBS
    checkpoint_mgr.load.assert_awaited_once_with(
        "obs://bucket/checkpoints/run_001/qc/data.pkl"
    )
    # Should put data back into Ray object store
    ray_client.put_object.assert_called_once_with({"clips": ["a.mp4", "b.mp4"]})
    assert data_ref == "obj_ref_123"
    # Should update step status to RUNNING
    state_mgr.update_step_status.assert_awaited_with("sr_qc", "RUNNING")
    # Should update run status to RUNNING
    state_mgr.update_run_status.assert_awaited_with("run_001", "RUNNING")
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_pause_manager.py -v`

- [ ] **Step 3: 实现 PauseManager**

```python
# src/lakeon_orchestrator/pause/manager.py
from __future__ import annotations

import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)


class PauseManager:
    """Manages HUMAN_REVIEW pause/resume lifecycle.

    On pause:
    1. Materialize data from Ray object store to OBS checkpoint
    2. Update step status to PAUSED
    3. Update run status to PAUSED
    4. Disconnect Ray cluster (release resources)

    On resume:
    1. Reconnect Ray cluster
    2. Load checkpoint from OBS
    3. Put data back into Ray object store
    4. Update step/run status to RUNNING
    5. Continue orchestration from the paused step
    """

    def __init__(self, checkpoint_manager, state_manager, ray_client):
        self._checkpoint = checkpoint_manager
        self._state = state_manager
        self._ray = ray_client

    async def pause_step(
        self,
        run_id: str,
        step_run_id: str,
        step_id: str,
        data_ref: Any,
    ) -> None:
        """Pause a step for human review.

        Args:
            run_id: Pipeline run ID.
            step_run_id: Step run ID.
            step_id: DAG step ID.
            data_ref: Ray ObjectRef or data to checkpoint.
        """
        logger.info(f"Pausing step {step_id} in run {run_id} for HUMAN_REVIEW")

        # Materialize data from Ray and save to OBS
        if hasattr(data_ref, '__class__') and 'ObjectRef' in type(data_ref).__name__:
            data = self._ray.get_result(data_ref)
        else:
            data = data_ref

        checkpoint_path = await self._checkpoint.save(
            run_id=run_id,
            step_id=step_id,
            data=data,
        )

        # Update state
        await self._state.update_step_status(
            step_run_id, "PAUSED", checkpoint_path=checkpoint_path
        )
        await self._state.update_run_status(run_id, "PAUSED")

        # Release Ray cluster
        self._ray.disconnect()
        logger.info(f"Step {step_id} paused. Checkpoint: {checkpoint_path}")

    async def resume_step(
        self,
        run_id: str,
        step_run_id: str,
        step_id: str,
        checkpoint_path: str,
        approved_data: Optional[Any] = None,
    ) -> Any:
        """Resume a paused step after human review.

        Args:
            run_id: Pipeline run ID.
            step_run_id: Step run ID.
            step_id: DAG step ID.
            checkpoint_path: OBS path to saved checkpoint.
            approved_data: Optional filtered/approved data from human review.
                          If None, loads full checkpoint.

        Returns:
            Ray ObjectRef for the resumed data.
        """
        logger.info(f"Resuming step {step_id} in run {run_id}")

        # Reconnect Ray cluster
        self._ray.connect()

        # Load data: use approved_data if provided, otherwise load checkpoint
        if approved_data is not None:
            data = approved_data
        else:
            data = await self._checkpoint.load(checkpoint_path)

        # Put data back into Ray object store
        data_ref = self._ray.put_object(data)

        # Update state
        await self._state.update_step_status(step_run_id, "RUNNING")
        await self._state.update_run_status(run_id, "RUNNING")

        logger.info(f"Step {step_id} resumed with data ref {data_ref}")
        return data_ref
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_pause_manager.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/pause/ lakeon-orchestrator/tests/test_pause_manager.py
git commit -m "feat(orchestrator): implement PauseManager for HUMAN_REVIEW pause/resume"
```

---

## Task 11: Checkpoint Manager — OBS 读写

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/checkpoint/manager.py`
- Create: `lakeon-orchestrator/tests/test_checkpoint_manager.py`

- [ ] **Step 1: 编写 Checkpoint Manager 测试**

```python
# tests/test_checkpoint_manager.py
import pytest
import pickle
from unittest.mock import AsyncMock, MagicMock, patch
from lakeon_orchestrator.checkpoint.manager import CheckpointManager


@pytest.fixture
def mock_obs_client():
    client = MagicMock()
    client.put_object = MagicMock()
    client.get_object = MagicMock()
    return client


@pytest.fixture
def ckpt_mgr(mock_obs_client):
    return CheckpointManager(
        obs_client=mock_obs_client,
        bucket="lakeon-data",
        prefix="checkpoints",
    )


@pytest.mark.asyncio
async def test_save_checkpoint(ckpt_mgr, mock_obs_client):
    data = {"clips": ["a.mp4", "b.mp4"], "metrics": {"count": 2}}
    path = await ckpt_mgr.save(run_id="run_001", step_id="scene_split", data=data)

    assert path == "obs://lakeon-data/checkpoints/run_001/scene_split/checkpoint.pkl"
    mock_obs_client.put_object.assert_called_once()
    call_args = mock_obs_client.put_object.call_args
    assert call_args[1]["Bucket"] == "lakeon-data"
    assert "run_001/scene_split/checkpoint.pkl" in call_args[1]["Key"]


@pytest.mark.asyncio
async def test_load_checkpoint(ckpt_mgr, mock_obs_client):
    original_data = {"clips": ["a.mp4", "b.mp4"]}
    pickled = pickle.dumps(original_data)

    body_mock = MagicMock()
    body_mock.read.return_value = pickled
    mock_obs_client.get_object.return_value = {"Body": body_mock}

    loaded = await ckpt_mgr.load(
        "obs://lakeon-data/checkpoints/run_001/scene_split/checkpoint.pkl"
    )
    assert loaded == original_data


@pytest.mark.asyncio
async def test_save_generates_correct_path(ckpt_mgr):
    path = await ckpt_mgr.save(run_id="run_abc", step_id="normalize", data={"v": 1})
    assert "run_abc" in path
    assert "normalize" in path


def test_parse_obs_path(ckpt_mgr):
    bucket, key = ckpt_mgr.parse_obs_path(
        "obs://lakeon-data/checkpoints/run_001/step/checkpoint.pkl"
    )
    assert bucket == "lakeon-data"
    assert key == "checkpoints/run_001/step/checkpoint.pkl"
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_checkpoint_manager.py -v`

- [ ] **Step 3: 实现 Checkpoint Manager**

```python
# src/lakeon_orchestrator/checkpoint/manager.py
from __future__ import annotations

import logging
import pickle
from typing import Any, Optional
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


class CheckpointManager:
    """Manages checkpoint persistence to OBS (S3-compatible).

    Checkpoints are used for:
    1. Async intermediate data snapshots (non-blocking, for preview)
    2. HUMAN_REVIEW pause — full data materialization before releasing Ray
    3. Fault recovery — resume from last checkpoint on failure

    Storage layout:
        obs://{bucket}/{prefix}/{run_id}/{step_id}/checkpoint.pkl
    """

    def __init__(self, obs_client, bucket: str, prefix: str = "checkpoints"):
        self._client = obs_client
        self._bucket = bucket
        self._prefix = prefix

    def _make_key(self, run_id: str, step_id: str) -> str:
        return f"{self._prefix}/{run_id}/{step_id}/checkpoint.pkl"

    def _make_obs_path(self, key: str) -> str:
        return f"obs://{self._bucket}/{key}"

    async def save(self, run_id: str, step_id: str, data: Any) -> str:
        """Serialize data and upload to OBS.

        Returns the full obs:// path for the checkpoint.
        """
        key = self._make_key(run_id, step_id)
        body = pickle.dumps(data)

        logger.info(f"Saving checkpoint: {key} ({len(body)} bytes)")
        self._client.put_object(
            Bucket=self._bucket,
            Key=key,
            Body=body,
        )

        path = self._make_obs_path(key)
        logger.info(f"Checkpoint saved: {path}")
        return path

    async def load(self, obs_path: str) -> Any:
        """Download and deserialize checkpoint from OBS."""
        bucket, key = self.parse_obs_path(obs_path)

        logger.info(f"Loading checkpoint: {obs_path}")
        response = self._client.get_object(Bucket=bucket, Key=key)
        body = response["Body"].read()
        data = pickle.loads(body)

        logger.info(f"Checkpoint loaded: {obs_path}")
        return data

    @staticmethod
    def parse_obs_path(obs_path: str) -> tuple[str, str]:
        """Parse obs://bucket/key into (bucket, key)."""
        parsed = urlparse(obs_path)
        bucket = parsed.netloc
        key = parsed.path.lstrip("/")
        return bucket, key
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_checkpoint_manager.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/checkpoint/ lakeon-orchestrator/tests/test_checkpoint_manager.py
git commit -m "feat(orchestrator): implement CheckpointManager for OBS-based data persistence"
```

---

## Task 12: FastAPI 服务 — 运行触发/恢复/取消 API

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/api/runs.py`
- Create: `lakeon-orchestrator/tests/test_api_runs.py`

- [ ] **Step 1: 编写 API 测试**

```python
# tests/test_api_runs.py
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from httpx import AsyncClient, ASGITransport
from lakeon_orchestrator.main import app, create_app


@pytest.fixture
def configured_app():
    return create_app()


@pytest.fixture
def mock_orchestrator():
    orch = AsyncMock()
    orch.start_run = AsyncMock(return_value="run_new001")
    orch.resume_run = AsyncMock()
    orch.cancel_run = AsyncMock()
    return orch


@pytest.mark.asyncio
async def test_create_run(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs", json={
                "pipeline_id": "pipe_abc",
                "pipeline_version": 1,
                "tenant_id": "tn_test",
                "input_dataset_id": "ds_001",
                "input_dataset_version": 1,
            })
        assert resp.status_code == 202
        data = resp.json()
        assert data["run_id"] == "run_new001"
        assert data["status"] == "PENDING"


@pytest.mark.asyncio
async def test_create_run_missing_fields(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs", json={"pipeline_id": "pipe_abc"})
        assert resp.status_code == 422


@pytest.mark.asyncio
async def test_resume_run(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs/run_001/resume", json={
                "approved_items": ["clip1.mp4", "clip3.mp4"],
            })
        assert resp.status_code == 202
        mock_orchestrator.resume_run.assert_awaited_once()


@pytest.mark.asyncio
async def test_cancel_run(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs/run_001/cancel")
        assert resp.status_code == 202
        mock_orchestrator.cancel_run.assert_awaited_once_with("run_001")
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_api_runs.py -v`

- [ ] **Step 3: 实现 API 路由**

```python
# src/lakeon_orchestrator/api/runs.py
from __future__ import annotations

import uuid
from typing import Any, Optional

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel

router = APIRouter()


# --- Request / Response models ---

class CreateRunRequest(BaseModel):
    pipeline_id: str
    pipeline_version: int
    tenant_id: str
    input_dataset_id: Optional[str] = None
    input_dataset_version: Optional[int] = None


class CreateRunResponse(BaseModel):
    run_id: str
    status: str = "PENDING"


class ResumeRunRequest(BaseModel):
    approved_items: Optional[list[Any]] = None


class RunActionResponse(BaseModel):
    run_id: str
    status: str


# --- Orchestrator dependency (set at startup) ---

_orchestrator = None


def set_orchestrator(orch):
    global _orchestrator
    _orchestrator = orch


def get_orchestrator():
    if _orchestrator is None:
        raise RuntimeError("Orchestrator not initialized")
    return _orchestrator


# --- Endpoints ---

@router.post("", response_model=CreateRunResponse, status_code=202)
async def create_run(req: CreateRunRequest, background_tasks: BackgroundTasks):
    """Trigger a new pipeline run.

    Called by lakeon-api when user clicks "Run Pipeline".
    The actual orchestration runs in the background.
    """
    orch = get_orchestrator()
    run_id = f"run_{uuid.uuid4().hex[:12]}"

    # Start orchestration in background
    background_tasks.add_task(
        orch.start_run,
        run_id=run_id,
        pipeline_id=req.pipeline_id,
        pipeline_version=req.pipeline_version,
        tenant_id=req.tenant_id,
        input_dataset_id=req.input_dataset_id,
        input_dataset_version=req.input_dataset_version,
    )

    return CreateRunResponse(run_id=run_id, status="PENDING")


@router.post("/{run_id}/resume", response_model=RunActionResponse, status_code=202)
async def resume_run(run_id: str, req: ResumeRunRequest, background_tasks: BackgroundTasks):
    """Resume a paused pipeline run after human review.

    Called by lakeon-api when user approves/modifies data in HUMAN_REVIEW step.
    """
    orch = get_orchestrator()

    background_tasks.add_task(
        orch.resume_run,
        run_id=run_id,
        approved_data=req.approved_items,
    )

    return RunActionResponse(run_id=run_id, status="RUNNING")


@router.post("/{run_id}/cancel", response_model=RunActionResponse, status_code=202)
async def cancel_run(run_id: str):
    """Cancel a running or paused pipeline run."""
    orch = get_orchestrator()
    await orch.cancel_run(run_id)
    return RunActionResponse(run_id=run_id, status="CANCELLED")
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_api_runs.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/api/ lakeon-orchestrator/tests/test_api_runs.py
git commit -m "feat(orchestrator): implement FastAPI endpoints for run trigger/resume/cancel"
```

---

## Task 13: 主编排循环 — Orchestrator

**Files:**
- Create: `lakeon-orchestrator/src/lakeon_orchestrator/orchestrator.py`
- Create: `lakeon-orchestrator/tests/test_orchestrator.py`

- [ ] **Step 1: 编写 Orchestrator 测试**

```python
# tests/test_orchestrator.py
import pytest
from unittest.mock import AsyncMock, MagicMock, patch, PropertyMock
from lakeon_orchestrator.orchestrator import Orchestrator
from lakeon_orchestrator.dag.parser import DAGParser


SIMPLE_YAML = """
name: simple-test
data_type: TEXT
steps:
  - id: step_a
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: step_b
    component: comp_b
    component_version: 1
    inputs: { text: step_a.text }
    outputs: { text: b_out }
"""


@pytest.fixture
def mock_state_manager():
    sm = AsyncMock()
    sm.create_run = AsyncMock()
    sm.update_run_status = AsyncMock()
    sm.create_step_run = AsyncMock()
    sm.update_step_status = AsyncMock()
    sm.get_step_runs = AsyncMock(return_value=[])
    sm.get_active_runs = AsyncMock(return_value=[])

    # Mock get_pipeline_version to return YAML
    version_mock = MagicMock()
    version_mock.dag_yaml = SIMPLE_YAML
    sm.get_pipeline_version = AsyncMock(return_value=version_mock)
    return sm


@pytest.fixture
def mock_ray_client():
    rc = MagicMock()
    rc.connect = MagicMock()
    rc.disconnect = MagicMock()
    rc.is_connected = True
    rc.submit_task = MagicMock(return_value="obj_ref_result")
    rc.get_result = MagicMock(return_value={"text": "processed"})
    rc.put_object = MagicMock(return_value="obj_ref_input")
    return rc


@pytest.fixture
def mock_checkpoint_mgr():
    cm = AsyncMock()
    cm.save = AsyncMock(return_value="obs://bucket/ckpt/path")
    cm.load = AsyncMock(return_value={"data": "restored"})
    return cm


@pytest.fixture
def mock_component_loader():
    loader = MagicMock()

    def mock_component(ctx):
        return {"text": "processed"}

    loader.load = MagicMock(return_value=mock_component)
    return loader


@pytest.fixture
def mock_session_factory():
    factory = MagicMock()
    session = AsyncMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=False)
    session.commit = AsyncMock()
    factory.return_value = session
    return factory


@pytest.fixture
def orchestrator(
    mock_state_manager,
    mock_ray_client,
    mock_checkpoint_mgr,
    mock_component_loader,
    mock_session_factory,
):
    return Orchestrator(
        session_factory=mock_session_factory,
        ray_client=mock_ray_client,
        checkpoint_manager=mock_checkpoint_mgr,
        component_loader=mock_component_loader,
        _state_manager_override=mock_state_manager,
    )


@pytest.mark.asyncio
async def test_start_run_creates_run_and_steps(orchestrator, mock_state_manager):
    await orchestrator.start_run(
        run_id="run_001",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Should create the run
    mock_state_manager.create_run.assert_awaited_once()
    # Should create step runs for each DAG node
    assert mock_state_manager.create_step_run.await_count == 2


@pytest.mark.asyncio
async def test_start_run_connects_ray(orchestrator, mock_ray_client):
    await orchestrator.start_run(
        run_id="run_002",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    mock_ray_client.connect.assert_called_once()


@pytest.mark.asyncio
async def test_start_run_updates_status_to_running(orchestrator, mock_state_manager):
    await orchestrator.start_run(
        run_id="run_003",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Should transition to RUNNING
    calls = mock_state_manager.update_run_status.await_args_list
    statuses = [c.args[1] for c in calls]
    assert "RUNNING" in statuses


@pytest.mark.asyncio
async def test_cancel_run(orchestrator, mock_state_manager):
    await orchestrator.cancel_run("run_004")
    mock_state_manager.update_run_status.assert_awaited_with("run_004", "CANCELLED")


@pytest.mark.asyncio
async def test_start_run_disconnects_ray_on_completion(orchestrator, mock_ray_client):
    await orchestrator.start_run(
        run_id="run_005",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    mock_ray_client.disconnect.assert_called()
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd lakeon-orchestrator && pytest tests/test_orchestrator.py -v`

- [ ] **Step 3: 实现 Orchestrator 主循环**

```python
# src/lakeon_orchestrator/orchestrator.py
from __future__ import annotations

import json
import logging
import uuid
from typing import Any, Optional

from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.loader import ComponentLoader
from lakeon_orchestrator.dag.parser import DAGParser, DAG, DAGNode
from lakeon_orchestrator.dag.scheduler import DAGScheduler
from lakeon_orchestrator.dag.fan_out_handler import FanOutHandler
from lakeon_orchestrator.dag.fan_in_handler import FanInHandler
from lakeon_orchestrator.dag.branch_router import BranchRouter
from lakeon_orchestrator.db.state_manager import StateManager
from lakeon_orchestrator.ray_client.client import RayClient

logger = logging.getLogger(__name__)


class Orchestrator:
    """Main orchestration engine — the heart of the Pipeline Orchestrator.

    Orchestration loop:
    1. Parse DAG from pipeline version YAML
    2. Create step_runs for all DAG nodes
    3. Connect to Ray cluster
    4. Loop:
       a. Determine ready steps (all parents SUCCEEDED/SKIPPED)
       b. For each ready step: load component, build context, submit to Ray
       c. Wait for results
       d. Handle fan-out (expand), fan-in (merge), branch (route)
       e. Handle HUMAN_REVIEW (pause + checkpoint)
       f. Update step/run status in RDS
    5. On completion/failure: disconnect Ray, update final status
    """

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        ray_client: RayClient,
        checkpoint_manager,
        component_loader: Optional[ComponentLoader] = None,
        _state_manager_override=None,
    ):
        self._session_factory = session_factory
        self._ray = ray_client
        self._checkpoint = checkpoint_manager
        self._loader = component_loader or ComponentLoader()
        self._fan_out = FanOutHandler()
        self._fan_in = FanInHandler()
        self._state_override = _state_manager_override

        # Runtime state per run (keyed by run_id)
        self._run_data: dict[str, dict[str, Any]] = {}

    def _get_state_manager(self, session: AsyncSession) -> StateManager:
        if self._state_override:
            return self._state_override
        return StateManager(session)

    async def start_run(
        self,
        run_id: str,
        pipeline_id: str,
        pipeline_version: int,
        tenant_id: str,
        input_dataset_id: Optional[str] = None,
        input_dataset_version: Optional[int] = None,
    ) -> None:
        """Entry point: start a new pipeline run."""
        logger.info(f"Starting pipeline run {run_id} for pipeline {pipeline_id} v{pipeline_version}")

        async with self._session_factory() as session:
            sm = self._get_state_manager(session)

            # 1. Create run record
            await sm.create_run(
                run_id=run_id,
                pipeline_id=pipeline_id,
                pipeline_version=pipeline_version,
                tenant_id=tenant_id,
                input_dataset_id=input_dataset_id,
                input_dataset_version=input_dataset_version,
            )

            # 2. Load and parse DAG
            pv = await sm.get_pipeline_version(pipeline_id, pipeline_version)
            if pv is None:
                await sm.update_run_status(run_id, "FAILED")
                logger.error(f"Pipeline version not found: {pipeline_id} v{pipeline_version}")
                return

            dag = DAGParser.parse(pv.dag_yaml)
            scheduler = DAGScheduler(dag)
            branch_router = BranchRouter(dag)

            # Validate DAG (cycle detection)
            scheduler.topological_sort()

            # 3. Create step_runs for all nodes
            step_statuses: dict[str, str] = {}
            step_run_ids: dict[str, str] = {}
            for node_id, node in dag.nodes.items():
                sr_id = f"sr_{run_id}_{node_id}"
                await sm.create_step_run(
                    step_run_id=sr_id,
                    run_id=run_id,
                    step_id=node_id,
                    component_id=node.component,
                    component_version=node.component_version,
                )
                step_statuses[node_id] = "PENDING"
                step_run_ids[node_id] = sr_id

            # 4. Connect Ray
            self._ray.connect()
            await sm.update_run_status(run_id, "RUNNING")

            # Initialize data store for this run
            output_refs: dict[str, Any] = {}
            if input_dataset_id:
                output_refs["$input"] = {"dataset": input_dataset_id}

            try:
                await self._run_loop(
                    run_id=run_id,
                    dag=dag,
                    scheduler=scheduler,
                    branch_router=branch_router,
                    sm=sm,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )
            except Exception as e:
                logger.exception(f"Pipeline run {run_id} failed: {e}")
                await sm.update_run_status(run_id, "FAILED")
            finally:
                self._ray.disconnect()
                await session.commit()

    async def _run_loop(
        self,
        run_id: str,
        dag: DAG,
        scheduler: DAGScheduler,
        branch_router: BranchRouter,
        sm: StateManager,
        step_statuses: dict[str, str],
        step_run_ids: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Core scheduling loop: find ready steps, execute, repeat."""
        max_iterations = len(dag.nodes) * 10  # safety bound for fan-out expansion
        iteration = 0

        while iteration < max_iterations:
            iteration += 1

            # Check termination conditions
            if scheduler.is_complete(step_statuses):
                await sm.update_run_status(run_id, "SUCCEEDED")
                logger.info(f"Pipeline run {run_id} completed successfully")
                return

            if scheduler.has_failed(step_statuses):
                await sm.update_run_status(run_id, "FAILED")
                logger.info(f"Pipeline run {run_id} failed")
                return

            if scheduler.has_paused(step_statuses):
                # PAUSED — exit loop, will resume via resume_run()
                logger.info(f"Pipeline run {run_id} paused for human review")
                return

            # Find ready steps
            ready = scheduler.get_ready_steps(step_statuses)
            if not ready:
                logger.warning(f"No ready steps but pipeline not complete. Statuses: {step_statuses}")
                await sm.update_run_status(run_id, "FAILED")
                return

            # Execute ready steps (could be parallel)
            for step_id in ready:
                node = dag.nodes[step_id]
                sr_id = step_run_ids[step_id]

                # Handle merge nodes
                if node.node_type == "merge":
                    await self._execute_merge(
                        node=node,
                        sr_id=sr_id,
                        sm=sm,
                        step_statuses=step_statuses,
                        output_refs=output_refs,
                    )
                    continue

                # Handle HUMAN_REVIEW
                if node.execution_mode == "HUMAN_REVIEW":
                    await self._execute_pause(
                        node=node,
                        sr_id=sr_id,
                        run_id=run_id,
                        sm=sm,
                        step_statuses=step_statuses,
                        output_refs=output_refs,
                    )
                    continue

                # Execute component step
                await self._execute_step(
                    node=node,
                    sr_id=sr_id,
                    run_id=run_id,
                    dag=dag,
                    sm=sm,
                    branch_router=branch_router,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )

        logger.error(f"Pipeline run {run_id} exceeded max iterations")
        await sm.update_run_status(run_id, "FAILED")

    async def _execute_step(
        self,
        node: DAGNode,
        sr_id: str,
        run_id: str,
        dag: DAG,
        sm: StateManager,
        branch_router: BranchRouter,
        step_statuses: dict[str, str],
        step_run_ids: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Execute a single component step via Ray."""
        logger.info(f"Executing step {node.id} (component: {node.component})")
        await sm.update_step_status(sr_id, "RUNNING")
        step_statuses[node.id] = "RUNNING"

        try:
            # Resolve input data from upstream outputs
            input_data = self._resolve_inputs(node, output_refs)

            # Build ComponentContext
            ctx = ComponentContext(
                step_id=node.id,
                run_id=run_id,
                input_data=input_data,
                params=node.params,
            )

            # Load and execute component via Ray
            # The component function is submitted as a Ray remote task
            func = self._loader.load(f"placeholder.{node.component}")
            # In real execution: ref = self._ray.submit_task(func, ctx)
            # For now, simulate:
            result = self._ray.submit_task(func, ctx)
            result_data = self._ray.get_result(result)

            # Handle fan-out
            if self._fan_out.is_fan_out(result_data):
                await self._handle_fan_out(
                    node=node,
                    result=result_data,
                    run_id=run_id,
                    dag=dag,
                    sm=sm,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )
            # Handle branch classification
            elif branch_router.is_branch_result(result_data):
                branch_label = result_data["__branch__"]
                output_refs[node.id] = result_data
                # Route to correct downstream
                targets = branch_router.route(node.id, result_data)
                logger.info(f"Step {node.id} classified as '{branch_label}', routing to {targets}")
            else:
                output_refs[node.id] = result_data

            # Checkpoint if configured
            if node.checkpoint:
                await self._checkpoint.save(
                    run_id=run_id, step_id=node.id, data=result_data
                )

            # Update metrics
            metrics_json = json.dumps(ctx.metrics) if ctx.metrics else None
            await sm.update_step_status(
                sr_id, "SUCCEEDED",
                output_ref=json.dumps({"ref": str(result)}),
                metrics=metrics_json,
            )
            step_statuses[node.id] = "SUCCEEDED"
            logger.info(f"Step {node.id} succeeded")

        except Exception as e:
            logger.exception(f"Step {node.id} failed: {e}")
            await sm.update_step_status(sr_id, "FAILED", error=str(e))
            step_statuses[node.id] = "FAILED"

    async def _execute_merge(
        self,
        node: DAGNode,
        sr_id: str,
        sm: StateManager,
        step_statuses: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Execute a merge (fan-in) node."""
        logger.info(f"Executing merge step {node.id}")
        await sm.update_step_status(sr_id, "RUNNING")
        step_statuses[node.id] = "RUNNING"

        # Collect results from input references
        input_refs = node.inputs if isinstance(node.inputs, list) else list(node.inputs.values())
        branch_results = []
        for ref in input_refs:
            if isinstance(ref, str) and "." in ref:
                source_id = ref.split(".")[0]
                data = output_refs.get(source_id)
                if data is not None:
                    branch_results.append({"data": data, "source": source_id})

        merged = self._fan_in.merge(branch_results)
        output_refs[node.id] = merged

        await sm.update_step_status(
            sr_id, "SUCCEEDED",
            metrics=json.dumps({"merged_count": merged["count"]}),
        )
        step_statuses[node.id] = "SUCCEEDED"
        logger.info(f"Merge step {node.id} completed with {merged['count']} items")

    async def _execute_pause(
        self,
        node: DAGNode,
        sr_id: str,
        run_id: str,
        sm: StateManager,
        step_statuses: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Pause for HUMAN_REVIEW — checkpoint data and wait."""
        logger.info(f"Pausing step {node.id} for HUMAN_REVIEW")

        # Resolve input
        input_data = self._resolve_inputs(node, output_refs)

        # Save checkpoint
        checkpoint_path = await self._checkpoint.save(
            run_id=run_id, step_id=node.id, data=input_data
        )

        await sm.update_step_status(sr_id, "PAUSED", checkpoint_path=checkpoint_path)
        step_statuses[node.id] = "PAUSED"

        await sm.update_run_status(run_id, "PAUSED")
        self._ray.disconnect()

    async def _handle_fan_out(
        self,
        node: DAGNode,
        result: dict[str, Any],
        run_id: str,
        dag: DAG,
        sm: StateManager,
        step_statuses: dict[str, str],
        step_run_ids: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Handle fan-out: create expanded step_runs for downstream."""
        children = dag.get_children(node.id)
        items = result.get("items", [])
        logger.info(f"Fan-out from {node.id}: {len(items)} items -> {children}")

        output_refs[node.id] = result

        # For each downstream step, the Orchestrator will process items in parallel
        # The actual fan-out expansion happens when downstream steps execute

    def _resolve_inputs(
        self, node: DAGNode, output_refs: dict[str, Any]
    ) -> dict[str, Any]:
        """Resolve a node's input references to actual data."""
        if isinstance(node.inputs, list):
            # Merge node with list inputs
            resolved = []
            for ref in node.inputs:
                if isinstance(ref, str) and "." in ref:
                    source_id, _, key = ref.partition(".")
                    data = output_refs.get(source_id, {})
                    if isinstance(data, dict):
                        resolved.append(data.get(key, data))
                    else:
                        resolved.append(data)
            return {"items": resolved}

        resolved = {}
        for key, ref in node.inputs.items():
            if isinstance(ref, str) and ref.startswith("$"):
                # Pipeline-level input (e.g. "$input.dataset")
                parts = ref[1:].split(".")
                data = output_refs
                for p in parts:
                    data = data.get(p, {}) if isinstance(data, dict) else {}
                resolved[key] = data
            elif isinstance(ref, str) and "." in ref:
                # Upstream reference (e.g. "normalize.video")
                source_id, _, output_key = ref.partition(".")
                source_data = output_refs.get(source_id, {})
                if isinstance(source_data, dict):
                    resolved[key] = source_data.get(output_key, source_data)
                else:
                    resolved[key] = source_data
            else:
                resolved[key] = ref
        return resolved

    async def resume_run(self, run_id: str, approved_data: Any = None) -> None:
        """Resume a paused run after human review."""
        logger.info(f"Resuming pipeline run {run_id}")

        async with self._session_factory() as session:
            sm = self._get_state_manager(session)

            run = await sm.get_run(run_id)
            if run is None:
                raise ValueError(f"Run {run_id} not found")
            if run.status != "PAUSED":
                raise ValueError(f"Run {run_id} is not paused (status: {run.status})")

            # Find paused step
            step_runs = await sm.get_step_runs(run_id)
            paused_step = next((sr for sr in step_runs if sr.status == "PAUSED"), None)
            if paused_step is None:
                raise ValueError(f"No paused step found in run {run_id}")

            # Reconnect Ray
            self._ray.connect()

            # Load checkpoint or use approved data
            if approved_data is not None:
                data = approved_data
            elif paused_step.checkpoint_path:
                data = await self._checkpoint.load(paused_step.checkpoint_path)
            else:
                raise ValueError("No checkpoint or approved data available")

            # Put data into Ray object store
            data_ref = self._ray.put_object(data)

            # Update statuses
            await sm.update_step_status(paused_step.id, "SUCCEEDED")
            await sm.update_run_status(run_id, "RUNNING")

            # Reload DAG and continue
            pv = await sm.get_pipeline_version(run.pipeline_id, run.pipeline_version)
            dag = DAGParser.parse(pv.dag_yaml)
            scheduler = DAGScheduler(dag)
            branch_router = BranchRouter(dag)

            # Rebuild step statuses from DB
            step_statuses = {}
            step_run_ids = {}
            for sr in step_runs:
                step_statuses[sr.step_id] = sr.status
                step_run_ids[sr.step_id] = sr.id

            # Mark the paused step as succeeded (data approved)
            step_statuses[paused_step.step_id] = "SUCCEEDED"

            # Rebuild output refs (simplified — in production, load from checkpoints)
            output_refs = {paused_step.step_id: data}

            try:
                await self._run_loop(
                    run_id=run_id,
                    dag=dag,
                    scheduler=scheduler,
                    branch_router=branch_router,
                    sm=sm,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )
            except Exception as e:
                logger.exception(f"Pipeline run {run_id} failed after resume: {e}")
                await sm.update_run_status(run_id, "FAILED")
            finally:
                self._ray.disconnect()
                await session.commit()

    async def cancel_run(self, run_id: str) -> None:
        """Cancel a running or paused pipeline run."""
        logger.info(f"Cancelling pipeline run {run_id}")
        async with self._session_factory() as session:
            sm = self._get_state_manager(session)
            await sm.update_run_status(run_id, "CANCELLED")
            await session.commit()
        self._ray.disconnect()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd lakeon-orchestrator && pytest tests/test_orchestrator.py -v`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/orchestrator.py lakeon-orchestrator/tests/test_orchestrator.py
git commit -m "feat(orchestrator): implement main orchestration loop with DAG execution engine"
```

---

## Task 14: 单元测试 + 集成测试完善

**Files:**
- Modify: `lakeon-orchestrator/tests/conftest.py`
- Verify all existing tests pass
- Add integration test

- [ ] **Step 1: 运行全量测试**

Run: `cd lakeon-orchestrator && pytest tests/ -v --tb=short`

确认所有 13 个测试文件通过。

- [ ] **Step 2: 添加端到端集成测试**

Create: `lakeon-orchestrator/tests/test_integration.py`

```python
# tests/test_integration.py
"""Integration test: DAG parse → schedule → component execute (in-memory, no Ray)."""
import pytest
from lakeon_orchestrator.dag.parser import DAGParser
from lakeon_orchestrator.dag.scheduler import DAGScheduler
from lakeon_orchestrator.dag.fan_out_handler import FanOutHandler
from lakeon_orchestrator.dag.fan_in_handler import FanInHandler
from lakeon_orchestrator.dag.branch_router import BranchRouter
from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component, get_component_meta


# Define test components inline
@Component(
    name="double",
    display_name="Double",
    category="DATA_PREP",
    data_type="TEXT",
)
def double_component(ctx: ComponentContext) -> dict:
    value = ctx.input.get("value", 0)
    result = value * 2
    ctx.report({"input": value, "output": result})
    return {"value": result}


@Component(
    name="splitter",
    display_name="Splitter",
    category="EXTRACT",
    data_type="TEXT",
)
def splitter_component(ctx: ComponentContext) -> dict:
    items = ctx.input.get("items", [])
    return ctx.fan_out(items)


@Component(
    name="classifier",
    display_name="Classifier",
    category="FILTER",
    data_type="TEXT",
    output_branches=["big", "small"],
)
def classifier_component(ctx: ComponentContext) -> dict:
    value = ctx.input.get("value", 0)
    if value > 5:
        return ctx.classify({"value": value}, "big")
    return ctx.classify({"value": value}, "small")


PIPELINE_YAML = """
name: integration-test
data_type: TEXT
steps:
  - id: double
    component: double
    component_version: 1
    inputs: { value: "$input.value" }
    outputs: { value: doubled }
  - id: double_again
    component: double
    component_version: 1
    inputs: { value: double.value }
    outputs: { value: quadrupled }
"""


def test_full_dag_parse_and_schedule():
    """Test complete flow: parse YAML → topological sort → identify ready steps."""
    dag = DAGParser.parse(PIPELINE_YAML)
    scheduler = DAGScheduler(dag)

    # Topological sort
    order = scheduler.topological_sort()
    assert order == ["double", "double_again"]

    # Initial: only "double" is ready
    statuses = {"double": "PENDING", "double_again": "PENDING"}
    assert scheduler.get_ready_steps(statuses) == ["double"]

    # After double succeeds: double_again is ready
    statuses["double"] = "SUCCEEDED"
    assert scheduler.get_ready_steps(statuses) == ["double_again"]

    # After both succeed: complete
    statuses["double_again"] = "SUCCEEDED"
    assert scheduler.is_complete(statuses)
    assert scheduler.aggregate_status(statuses) == "SUCCEEDED"


def test_component_execution_with_context():
    """Test component decorated function execution via ComponentContext."""
    ctx = ComponentContext(
        step_id="double",
        run_id="run_integ_001",
        input_data={"value": 5},
        params={},
    )
    result = double_component(ctx)
    assert result == {"value": 10}
    assert ctx.metrics["output"] == 10


def test_fan_out_flow():
    """Test fan-out: splitter → expansion."""
    ctx = ComponentContext(
        step_id="split",
        run_id="run_integ_002",
        input_data={"items": [1, 2, 3]},
        params={},
    )
    result = splitter_component(ctx)

    handler = FanOutHandler()
    assert handler.is_fan_out(result)

    expanded = handler.expand(
        fan_out_result=result,
        source_step_id="split",
        downstream_step_id="process",
        run_id="run_integ_002",
    )
    assert len(expanded) == 3
    assert expanded[0]["input_data"] == 1


def test_branch_routing_flow():
    """Test classification → branch routing → merge."""
    # Classify big
    ctx_big = ComponentContext(
        step_id="classify",
        run_id="run_integ_003",
        input_data={"value": 10},
        params={},
    )
    result_big = classifier_component(ctx_big)
    assert BranchRouter.is_branch_result(result_big)
    assert result_big["__branch__"] == "big"

    # Classify small
    ctx_small = ComponentContext(
        step_id="classify",
        run_id="run_integ_003",
        input_data={"value": 3},
        params={},
    )
    result_small = classifier_component(ctx_small)
    assert result_small["__branch__"] == "small"

    # Fan-in merge
    fan_in = FanInHandler()
    merged = fan_in.merge([
        {"data": result_big["data"], "branch": "big"},
        {"data": result_small["data"], "branch": "small"},
    ])
    assert merged["count"] == 2


def test_aggregate_status_transitions():
    """Test pipeline-level status aggregation."""
    dag = DAGParser.parse(PIPELINE_YAML)
    scheduler = DAGScheduler(dag)

    # RUNNING: at least one RUNNING
    assert scheduler.aggregate_status({"double": "RUNNING", "double_again": "PENDING"}) == "RUNNING"

    # FAILED: any FAILED
    assert scheduler.aggregate_status({"double": "FAILED", "double_again": "PENDING"}) == "FAILED"

    # SUCCEEDED: all done
    assert scheduler.aggregate_status({"double": "SUCCEEDED", "double_again": "SUCCEEDED"}) == "SUCCEEDED"
```

- [ ] **Step 3: 运行全量测试**

Run: `cd lakeon-orchestrator && pytest tests/ -v --tb=short`

- [ ] **Step 4: 运行覆盖率报告**

Run: `cd lakeon-orchestrator && pytest tests/ --cov=lakeon_orchestrator --cov-report=term-missing`

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/tests/test_integration.py
git commit -m "test(orchestrator): add integration tests for full DAG parse-schedule-execute flow"
```

- [ ] **Step 6: 最终 Commit — 更新 main.py 连接 Orchestrator**

更新 `main.py` 将 Orchestrator 实例化并注入 API：

```python
# 在 main.py 的 lifespan 中添加：
# (在 await init_db() 之后)

from lakeon_orchestrator.db.engine import get_session_factory
from lakeon_orchestrator.ray_client.client import RayClient
from lakeon_orchestrator.checkpoint.manager import CheckpointManager
from lakeon_orchestrator.orchestrator import Orchestrator
from lakeon_orchestrator.api.runs import set_orchestrator

import boto3

# Create OBS client
obs_client = boto3.client(
    "s3",
    endpoint_url=settings.obs_endpoint,
    aws_access_key_id=settings.obs_access_key,
    aws_secret_access_key=settings.obs_secret_key,
)

ray_client = RayClient(address=settings.ray_address, namespace=settings.ray_namespace)
checkpoint_mgr = CheckpointManager(obs_client=obs_client, bucket=settings.obs_bucket)
orchestrator = Orchestrator(
    session_factory=get_session_factory(),
    ray_client=ray_client,
    checkpoint_manager=checkpoint_mgr,
)
set_orchestrator(orchestrator)
```

```bash
git add lakeon-orchestrator/src/lakeon_orchestrator/main.py
git commit -m "feat(orchestrator): wire up Orchestrator with FastAPI lifespan and dependencies"
```
