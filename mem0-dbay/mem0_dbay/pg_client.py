import psycopg2
import psycopg2.extras
from pgvector.psycopg2 import register_vector


class PgClient:
    """Thin wrapper around psycopg2 for graph operations."""

    def __init__(self, connection_string: str):
        self.conn = psycopg2.connect(connection_string)
        self.conn.autocommit = True
        register_vector(self.conn)

    def query(self, sql: str, params: tuple = None) -> list[dict]:
        """Execute SQL and return list of dicts."""
        with self.conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
            return [dict(row) for row in cur.fetchall()]

    def execute(self, sql: str, params: tuple = None) -> None:
        """Execute SQL without returning results."""
        with self.conn.cursor() as cur:
            cur.execute(sql, params)

    def close(self):
        if self.conn and not self.conn.closed:
            self.conn.close()
