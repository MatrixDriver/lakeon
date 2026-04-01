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
