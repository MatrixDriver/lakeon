import pytest
from mem0_dbay.config import DbayGraphConfig


def test_config_defaults():
    cfg = DbayGraphConfig(connection_string="postgresql://localhost/test")
    assert cfg.connection_string == "postgresql://localhost/test"
    assert cfg.embedding_dimension == 1536


def test_config_custom_dimension():
    cfg = DbayGraphConfig(connection_string="postgresql://localhost/test", embedding_dimension=768)
    assert cfg.embedding_dimension == 768


def test_config_requires_connection_string():
    with pytest.raises(Exception):
        DbayGraphConfig()
