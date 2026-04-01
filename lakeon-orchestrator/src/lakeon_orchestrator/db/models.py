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
