from urllib.parse import quote_plus

from pydantic import model_validator
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Database (shared RDS with lakeon-api)
    database_url: str = "postgresql+asyncpg://lakeon:lakeon@localhost:5432/lakeon"

    # Individual DB fields (used when password contains special chars)
    db_host: str = ""
    db_port: int = 5432
    db_name: str = "lakeon"
    db_user: str = "lakeon"
    db_password: str = ""

    @model_validator(mode="after")
    def _build_database_url(self):
        """If individual DB fields are set, build database_url from them with URL-encoded password."""
        if self.db_host and self.db_password:
            pwd = quote_plus(self.db_password)
            self.database_url = (
                f"postgresql+asyncpg://{self.db_user}:{pwd}"
                f"@{self.db_host}:{self.db_port}/{self.db_name}"
            )
        return self

    # OBS / S3-compatible storage
    obs_endpoint: str = "https://obs.cn-north-4.myhuaweicloud.com"
    obs_access_key: str = ""
    obs_secret_key: str = ""
    obs_bucket: str = "lakeon-data"
    obs_region: str = "cn-north-4"

    # Python single-pod execution
    python_image: str = "swr.cn-north-4.myhuaweicloud.com/flex/lakeon-pipeline-job:0.1.0"

    # Ray / KubeRay
    ray_image: str = "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data"
    k8s_namespace: str = "lakeon-pipeline"
    image_pull_secrets: str = ""  # comma-separated list of image pull secret names

    # CCI Virtual Kubelet scheduling
    vk_node_selector_key: str = "type"
    vk_node_selector_value: str = "virtual-kubelet"

    # Server
    host: str = "0.0.0.0"
    port: int = 8090

    model_config = {"env_prefix": "LAKEON_ORCH_"}

    def get_image_pull_secrets_list(self) -> list[str]:
        """Parse comma-separated image pull secrets into a list."""
        if not self.image_pull_secrets:
            return []
        return [s.strip() for s in self.image_pull_secrets.split(",") if s.strip()]


settings = Settings()
