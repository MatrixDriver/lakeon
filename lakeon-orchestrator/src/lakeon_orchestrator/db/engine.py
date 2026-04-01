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
