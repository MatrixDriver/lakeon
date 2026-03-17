import os
import pytest

TEST_DB_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql://localhost:5432/mem0_dbay_test"
)

# Use small embedding dimension for tests
TEST_EMBEDDING_DIM = 4


@pytest.fixture(scope="session")
def pg_client():
    from mem0_dbay.pg_client import PgClient
    from mem0_dbay.schema import ensure_schema
    client = PgClient(TEST_DB_URL)
    ensure_schema(client, TEST_EMBEDDING_DIM)
    yield client
    client.close()


@pytest.fixture()
def clean_db(pg_client):
    """Use this fixture in tests that need a clean database."""
    yield pg_client
    pg_client.execute("DELETE FROM graph_edges")
    pg_client.execute("DELETE FROM graph_nodes")
