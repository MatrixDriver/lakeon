from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Database (shared RDS with lakeon-api)
    database_url: str = "postgresql+asyncpg://lakeon:lakeon@localhost:5432/lakeon"

    # OBS / S3-compatible storage
    obs_endpoint: str = "https://obs.cn-north-4.myhuaweicloud.com"
    obs_access_key: str = ""
    obs_secret_key: str = ""
    obs_bucket: str = "lakeon-data"
    obs_region: str = "cn-north-4"

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
