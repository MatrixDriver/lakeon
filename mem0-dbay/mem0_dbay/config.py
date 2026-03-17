from pydantic import BaseModel, Field


class DbayGraphConfig(BaseModel):
    connection_string: str = Field(..., description="PostgreSQL connection URL (e.g. postgresql://user:pass@host:5432/db)")
    embedding_dimension: int = Field(default=1536, description="Embedding vector dimension")
    sslmode: str = Field(default="prefer", description="PostgreSQL SSL mode")
