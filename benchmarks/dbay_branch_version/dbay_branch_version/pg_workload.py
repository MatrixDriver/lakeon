from __future__ import annotations

from dataclasses import dataclass

import psycopg


@dataclass(frozen=True)
class DatasetSpec:
    name: str
    scale: int


DATASETS: dict[str, DatasetSpec] = {
    "S": DatasetSpec("S", 10_000),
    "M": DatasetSpec("M", 100_000),
    "L": DatasetSpec("L", 1_000_000),
}


SCHEMA_SQL = """
DROP TABLE IF EXISTS bench_events;
DROP TABLE IF EXISTS bench_jsonb;
DROP TABLE IF EXISTS bench_oltp;

CREATE TABLE bench_oltp (
    id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bench_jsonb (
    id BIGINT PRIMARY KEY,
    payload JSONB NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bench_events (
    id BIGINT PRIMARY KEY,
    marker TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bench_oltp_account_id ON bench_oltp(account_id);
CREATE INDEX idx_bench_jsonb_payload ON bench_jsonb USING GIN(payload);
CREATE INDEX idx_bench_events_marker ON bench_events(marker);
"""


def dataset_row_counts(dataset: str) -> dict[str, int]:
    scale = DATASETS[dataset].scale
    return {
        "bench_oltp": scale,
        "bench_jsonb": scale,
        "bench_events": scale,
    }


def load_dataset(connstr: str, dataset: str) -> None:
    scale = DATASETS[dataset].scale
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute(SCHEMA_SQL)
            cur.execute(
                """
                INSERT INTO bench_oltp (id, account_id, amount, status)
                SELECT g, g % 1000, (g % 10000) / 100.0, CASE WHEN g % 2 = 0 THEN 'open' ELSE 'closed' END
                FROM generate_series(1, %s) AS g
                """,
                (scale,),
            )
            cur.execute(
                """
                INSERT INTO bench_jsonb (id, payload, note)
                SELECT g,
                       jsonb_build_object('id', g, 'group', g % 100, 'tags', ARRAY['dbay', 'bench', (g % 10)::text]),
                       repeat('x', 128)
                FROM generate_series(1, %s) AS g
                """,
                (scale,),
            )
            cur.execute(
                """
                INSERT INTO bench_events (id, marker, payload)
                SELECT g, 'base', repeat(md5(g::text), 8)
                FROM generate_series(1, %s) AS g
                """,
                (scale,),
            )
            cur.execute("ANALYZE")
        conn.commit()


def fetch_checksums(connstr: str) -> dict[str, str]:
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute(checksum_sql())
            row = cur.fetchone()
            if row is None:
                raise RuntimeError("checksum query returned no rows")
            return {
                "bench_oltp": row[0],
                "bench_jsonb": row[1],
                "bench_events": row[2],
            }


def checksum_sql() -> str:
    return """
    SELECT
      (SELECT md5(string_agg(id::text || ':' || account_id::text || ':' || amount::text || ':' || status, ',' ORDER BY id)) FROM bench_oltp) AS bench_oltp,
      (SELECT md5(string_agg(id::text || ':' || payload::text || ':' || note, ',' ORDER BY id)) FROM bench_jsonb) AS bench_jsonb,
      (SELECT md5(string_agg(id::text || ':' || marker || ':' || payload, ',' ORDER BY id)) FROM bench_events) AS bench_events
    """


def isolation_insert_sql(marker: str) -> str:
    safe_marker = marker.replace("'", "''")
    return f"""
    INSERT INTO bench_events (id, marker, payload)
    SELECT COALESCE(MAX(id), 0) + 1, '{safe_marker}', repeat(md5('{safe_marker}'), 8)
    FROM bench_events
    """


def marker_count(connstr: str, marker: str) -> int:
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM bench_events WHERE marker = %s", (marker,))
            row = cur.fetchone()
            if row is None:
                raise RuntimeError("marker count query returned no rows")
            return int(row[0])


def execute_isolation_insert(connstr: str, marker: str) -> None:
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO bench_events (id, marker, payload)
                SELECT COALESCE(MAX(id), 0) + 1, %s, repeat(md5(%s), 8)
                FROM bench_events
                """,
                (marker, marker),
            )
        conn.commit()
