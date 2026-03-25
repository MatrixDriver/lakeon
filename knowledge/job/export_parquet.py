"""Export data from user PG to Parquet on OBS via DuckDB."""
import json
import os
import logging

import boto3
import duckdb

from callback import report_progress, report_failure

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
logger = logging.getLogger("export-parquet")


def main():
    try:
        with open("/etc/job/params.json") as f:
            params = json.load(f)

        connstr = params["database_connstr"]
        source_sql = params["source_sql"]
        obs_path = params["obs_output_path"]

        obs_endpoint = os.environ["OBS_ENDPOINT"]
        obs_ak = os.environ["OBS_ACCESS_KEY"]
        obs_sk = os.environ["OBS_SECRET_KEY"]
        obs_bucket = os.environ.get("OBS_BUCKET", "lakeon-storage")

        report_progress("Initializing DuckDB", 0.1)

        conn = duckdb.connect()
        conn.execute("INSTALL postgres; LOAD postgres;")

        report_progress("Attaching PostgreSQL database", 0.2)

        # Retry ATTACH — compute pod may need a moment after wakeCompute
        safe_connstr = connstr.replace("'", "''")
        for attempt in range(5):
            try:
                conn.execute(f"ATTACH '{safe_connstr}' AS src (TYPE postgres, READ_ONLY)")
                # Verify attachment works
                conn.execute("SELECT 1 FROM postgres_query('src', 'SELECT 1')").fetchone()
                logger.info(f"ATTACH succeeded on attempt {attempt + 1}")
                break
            except Exception as e:
                if attempt < 4:
                    logger.warning(f"ATTACH attempt {attempt + 1} failed: {e}, retrying in 5s...")
                    try:
                        conn.execute("DETACH src")
                    except Exception:
                        pass
                    import time
                    time.sleep(5)
                else:
                    raise RuntimeError(f"Failed to attach PostgreSQL after 5 attempts: {e}")

        # Escape single quotes in source_sql for DuckDB string embedding
        safe_sql = source_sql.replace("'", "''")

        report_progress("Counting rows", 0.3)

        # Count rows first
        count_result = conn.execute(
            f"SELECT COUNT(*) FROM postgres_query('src', '{safe_sql}')"
        ).fetchone()
        row_count = count_result[0]
        logger.info(f"Row count: {row_count}")

        report_progress(f"Exporting {row_count} rows to Parquet", 0.4)

        # Export to local Parquet file first, then upload via boto3
        # (DuckDB httpfs has compatibility issues with Huawei OBS S3)
        local_path = "/tmp/export.parquet"
        conn.execute(f"""
            COPY (
                SELECT * FROM postgres_query('src', '{safe_sql}')
            )
            TO '{local_path}'
            (FORMAT PARQUET, ROW_GROUP_SIZE 100000, COMPRESSION ZSTD)
        """)

        report_progress("Uploading to OBS", 0.8)

        # Upload via boto3 (same config as knowledge job — proven compatible with OBS)
        from botocore.config import Config as BotoConfig
        s3 = boto3.client("s3",
            endpoint_url=obs_endpoint,
            aws_access_key_id=obs_ak,
            aws_secret_access_key=obs_sk,
            region_name=os.environ.get("OBS_REGION", "cn-north-4"),
            config=BotoConfig(
                s3={"addressing_style": "virtual"},
                signature_version="s3",
            ))
        s3.upload_file(local_path, obs_bucket, obs_path)

        report_progress("Getting file size", 0.9)

        file_size = os.path.getsize(local_path)
        os.remove(local_path)

        logger.info(f"Export complete: {row_count} rows, {file_size} bytes -> {obs_path}")

        # Call callback API directly (report_success in callback.py has knowledge-specific signature)
        import requests
        url = os.environ["JOB_CALLBACK_URL"]
        token = os.environ["JOB_CALLBACK_TOKEN"]
        result = {
            "row_count": row_count,
            "file_size": file_size,
            "obs_path": obs_path
        }
        requests.post(url, json={"token": token, "status": "SUCCEEDED", "result": result}, timeout=30)
        logger.info("Callback SUCCEEDED sent")

    except Exception as e:
        logger.error(f"Export failed: {e}", exc_info=True)
        report_failure(str(e))
