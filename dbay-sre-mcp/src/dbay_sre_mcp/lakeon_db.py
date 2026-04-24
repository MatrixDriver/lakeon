"""Read-only PG connection to lakeon-api's production DB (NOT dbay-logs).

LOG_DB_DSN connects to dbay-logs (4 log_* tools).
LAKEON_DB_DSN connects to lakeon-api's tenants/databases/knowledge tables (data_consistency_check).
"""
from __future__ import annotations

import os

import psycopg2


def connect():
    return psycopg2.connect(os.environ["LAKEON_DB_DSN"])
