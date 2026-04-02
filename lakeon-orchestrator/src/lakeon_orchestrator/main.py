from contextlib import asynccontextmanager

import boto3
from fastapi import FastAPI

from lakeon_orchestrator.config import settings
from lakeon_orchestrator.db.engine import init_db, close_db, get_session_factory
from lakeon_orchestrator.ray_client.client import RayClient
from lakeon_orchestrator.ray_client.python_job_client import PythonJobClient
from lakeon_orchestrator.checkpoint.manager import CheckpointManager
from lakeon_orchestrator.orchestrator import Orchestrator
from lakeon_orchestrator.api.runs import set_orchestrator


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()

    # Create OBS client
    obs_client = boto3.client(
        "s3",
        endpoint_url=settings.obs_endpoint,
        aws_access_key_id=settings.obs_access_key,
        aws_secret_access_key=settings.obs_secret_key,
    )

    ray_client = RayClient(
        ray_image=settings.ray_image,
        k8s_namespace=settings.k8s_namespace,
        image_pull_secrets=settings.get_image_pull_secrets_list(),
        obs_endpoint=settings.obs_endpoint,
        obs_access_key=settings.obs_access_key,
        obs_secret_key=settings.obs_secret_key,
        obs_bucket=settings.obs_bucket,
        obs_region=settings.obs_region,
        vk_node_selector_key=settings.vk_node_selector_key,
        vk_node_selector_value=settings.vk_node_selector_value,
    )
    python_client = PythonJobClient(
        python_image=settings.python_image,
        k8s_namespace=settings.k8s_namespace,
        image_pull_secrets=settings.get_image_pull_secrets_list(),
        obs_endpoint=settings.obs_endpoint,
        obs_access_key=settings.obs_access_key,
        obs_secret_key=settings.obs_secret_key,
        obs_bucket=settings.obs_bucket,
        obs_region=settings.obs_region,
        vk_node_selector_key=settings.vk_node_selector_key,
        vk_node_selector_value=settings.vk_node_selector_value,
    )
    checkpoint_mgr = CheckpointManager(obs_client=obs_client, bucket=settings.obs_bucket)
    orchestrator = Orchestrator(
        session_factory=get_session_factory(),
        ray_client=ray_client,
        checkpoint_manager=checkpoint_mgr,
        python_job_client=python_client,
    )
    set_orchestrator(orchestrator)

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
