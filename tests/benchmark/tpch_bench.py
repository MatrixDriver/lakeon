#!/usr/bin/env python3
"""TPC-H Benchmark for Dbay (SF=0.1, ~100MB)

Runs inside a cluster pod. Results are written to benchmark_results table
in the same database for persistent viewing via Dbay SQL Editor.

Usage (as k8s pod):
  python3 tpch_bench.py <connstr>
"""

import sys
import time
import psycopg2
from datetime import datetime, timezone

SCALE_FACTOR = 0.1

# TPC-H 22 queries
TPCH_QUERIES = {
    1: """SELECT l_returnflag, l_linestatus, sum(l_quantity), sum(l_extendedprice),
  sum(l_extendedprice*(1-l_discount)), sum(l_extendedprice*(1-l_discount)*(1+l_tax)),
  avg(l_quantity), avg(l_extendedprice), avg(l_discount), count(*)
FROM lineitem WHERE l_shipdate <= DATE '1998-12-01' - INTERVAL '90 DAY'
GROUP BY l_returnflag, l_linestatus ORDER BY l_returnflag, l_linestatus""",

    2: """SELECT s_acctbal, s_name, n_name, p_partkey, p_mfgr, s_address, s_phone, s_comment
FROM part, supplier, partsupp, nation, region
WHERE p_partkey=ps_partkey AND s_suppkey=ps_suppkey AND p_size=15 AND p_type LIKE '%BRASS'
  AND s_nationkey=n_nationkey AND n_regionkey=r_regionkey AND r_name='EUROPE'
  AND ps_supplycost=(SELECT min(ps_supplycost) FROM partsupp, supplier, nation, region
    WHERE p_partkey=ps_partkey AND s_suppkey=ps_suppkey AND s_nationkey=n_nationkey
    AND n_regionkey=r_regionkey AND r_name='EUROPE')
ORDER BY s_acctbal DESC, n_name, s_name, p_partkey LIMIT 100""",

    3: """SELECT l_orderkey, sum(l_extendedprice*(1-l_discount)) as revenue, o_orderdate, o_shippriority
FROM customer, orders, lineitem
WHERE c_mktsegment='BUILDING' AND c_custkey=o_custkey AND l_orderkey=o_orderkey
  AND o_orderdate < DATE '1995-03-15' AND l_shipdate > DATE '1995-03-15'
GROUP BY l_orderkey, o_orderdate, o_shippriority ORDER BY revenue DESC, o_orderdate LIMIT 10""",

    4: """SELECT o_orderpriority, count(*) FROM orders
WHERE o_orderdate >= DATE '1993-07-01' AND o_orderdate < DATE '1993-07-01' + INTERVAL '3 MONTH'
  AND EXISTS (SELECT * FROM lineitem WHERE l_orderkey=o_orderkey AND l_commitdate < l_receiptdate)
GROUP BY o_orderpriority ORDER BY o_orderpriority""",

    5: """SELECT n_name, sum(l_extendedprice*(1-l_discount)) as revenue
FROM customer, orders, lineitem, supplier, nation, region
WHERE c_custkey=o_custkey AND l_orderkey=o_orderkey AND l_suppkey=s_suppkey
  AND c_nationkey=s_nationkey AND s_nationkey=n_nationkey AND n_regionkey=r_regionkey
  AND r_name='ASIA' AND o_orderdate >= DATE '1994-01-01' AND o_orderdate < DATE '1994-01-01' + INTERVAL '1 YEAR'
GROUP BY n_name ORDER BY revenue DESC""",

    6: """SELECT sum(l_extendedprice*l_discount) as revenue FROM lineitem
WHERE l_shipdate >= DATE '1994-01-01' AND l_shipdate < DATE '1994-01-01' + INTERVAL '1 YEAR'
  AND l_discount BETWEEN 0.06-0.01 AND 0.06+0.01 AND l_quantity < 24""",

    7: """SELECT supp_nation, cust_nation, l_year, sum(volume) as revenue FROM (
  SELECT n1.n_name as supp_nation, n2.n_name as cust_nation, extract(year from l_shipdate) as l_year,
    l_extendedprice*(1-l_discount) as volume
  FROM supplier, lineitem, orders, customer, nation n1, nation n2
  WHERE s_suppkey=l_suppkey AND o_orderkey=l_orderkey AND c_custkey=o_custkey
    AND s_nationkey=n1.n_nationkey AND c_nationkey=n2.n_nationkey
    AND ((n1.n_name='FRANCE' AND n2.n_name='GERMANY') OR (n1.n_name='GERMANY' AND n2.n_name='FRANCE'))
    AND l_shipdate BETWEEN DATE '1995-01-01' AND DATE '1996-12-31'
) AS shipping GROUP BY supp_nation, cust_nation, l_year ORDER BY supp_nation, cust_nation, l_year""",

    8: """SELECT o_year, sum(CASE WHEN nation='BRAZIL' THEN volume ELSE 0 END)/sum(volume) as mkt_share FROM (
  SELECT extract(year from o_orderdate) as o_year, l_extendedprice*(1-l_discount) as volume, n2.n_name as nation
  FROM part, supplier, lineitem, orders, customer, nation n1, nation n2, region
  WHERE p_partkey=l_partkey AND s_suppkey=l_suppkey AND l_orderkey=o_orderkey AND o_custkey=c_custkey
    AND c_nationkey=n1.n_nationkey AND n1.n_regionkey=r_regionkey AND r_name='AMERICA'
    AND s_nationkey=n2.n_nationkey AND o_orderdate BETWEEN DATE '1995-01-01' AND DATE '1996-12-31'
    AND p_type='ECONOMY ANODIZED STEEL'
) AS all_nations GROUP BY o_year ORDER BY o_year""",

    9: """SELECT nation, o_year, sum(amount) as sum_profit FROM (
  SELECT n_name as nation, extract(year from o_orderdate) as o_year,
    l_extendedprice*(1-l_discount) - ps_supplycost*l_quantity as amount
  FROM part, supplier, lineitem, partsupp, orders, nation
  WHERE s_suppkey=l_suppkey AND ps_suppkey=l_suppkey AND ps_partkey=l_partkey AND p_partkey=l_partkey
    AND o_orderkey=l_orderkey AND s_nationkey=n_nationkey AND p_name LIKE '%green%'
) AS profit GROUP BY nation, o_year ORDER BY nation, o_year DESC""",

    10: """SELECT c_custkey, c_name, sum(l_extendedprice*(1-l_discount)) as revenue,
  c_acctbal, n_name, c_address, c_phone, c_comment
FROM customer, orders, lineitem, nation
WHERE c_custkey=o_custkey AND l_orderkey=o_orderkey AND o_orderdate >= DATE '1993-10-01'
  AND o_orderdate < DATE '1993-10-01' + INTERVAL '3 MONTH' AND l_returnflag='R' AND c_nationkey=n_nationkey
GROUP BY c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment ORDER BY revenue DESC LIMIT 20""",

    11: """SELECT ps_partkey, sum(ps_supplycost*ps_availqty) as value FROM partsupp, supplier, nation
WHERE ps_suppkey=s_suppkey AND s_nationkey=n_nationkey AND n_name='GERMANY'
GROUP BY ps_partkey HAVING sum(ps_supplycost*ps_availqty) > (
  SELECT sum(ps_supplycost*ps_availqty)*0.0001 FROM partsupp, supplier, nation
  WHERE ps_suppkey=s_suppkey AND s_nationkey=n_nationkey AND n_name='GERMANY') ORDER BY value DESC""",

    12: """SELECT l_shipmode,
  sum(CASE WHEN o_orderpriority='1-URGENT' OR o_orderpriority='2-HIGH' THEN 1 ELSE 0 END) as high_line_count,
  sum(CASE WHEN o_orderpriority<>'1-URGENT' AND o_orderpriority<>'2-HIGH' THEN 1 ELSE 0 END) as low_line_count
FROM orders, lineitem WHERE o_orderkey=l_orderkey AND l_shipmode IN ('MAIL','SHIP')
  AND l_commitdate < l_receiptdate AND l_shipdate < l_commitdate
  AND l_receiptdate >= DATE '1994-01-01' AND l_receiptdate < DATE '1994-01-01' + INTERVAL '1 YEAR'
GROUP BY l_shipmode ORDER BY l_shipmode""",

    13: """SELECT c_count, count(*) as custdist FROM (
  SELECT c_custkey, count(o_orderkey) as c_count FROM customer
  LEFT OUTER JOIN orders ON c_custkey=o_custkey AND o_comment NOT LIKE '%special%requests%'
  GROUP BY c_custkey) AS c_orders GROUP BY c_count ORDER BY custdist DESC, c_count DESC""",

    14: """SELECT 100.00*sum(CASE WHEN p_type LIKE 'PROMO%' THEN l_extendedprice*(1-l_discount) ELSE 0 END)
  / sum(l_extendedprice*(1-l_discount)) as promo_revenue
FROM lineitem, part WHERE l_partkey=p_partkey
  AND l_shipdate >= DATE '1995-09-01' AND l_shipdate < DATE '1995-09-01' + INTERVAL '1 MONTH'""",

    15: """WITH revenue AS (SELECT l_suppkey as supplier_no, sum(l_extendedprice*(1-l_discount)) as total_revenue
  FROM lineitem WHERE l_shipdate >= DATE '1996-01-01' AND l_shipdate < DATE '1996-01-01' + INTERVAL '3 MONTH'
  GROUP BY l_suppkey)
SELECT s_suppkey, s_name, s_address, s_phone, total_revenue FROM supplier, revenue
WHERE s_suppkey=supplier_no AND total_revenue=(SELECT max(total_revenue) FROM revenue) ORDER BY s_suppkey""",

    16: """SELECT p_brand, p_type, p_size, count(DISTINCT ps_suppkey) as supplier_cnt
FROM partsupp, part WHERE p_partkey=ps_partkey AND p_brand<>'Brand#45' AND p_type NOT LIKE 'MEDIUM POLISHED%'
  AND p_size IN (49,14,23,45,19,3,36,9)
  AND ps_suppkey NOT IN (SELECT s_suppkey FROM supplier WHERE s_comment LIKE '%Customer%Complaints%')
GROUP BY p_brand, p_type, p_size ORDER BY supplier_cnt DESC, p_brand, p_type, p_size""",

    17: """SELECT sum(l_extendedprice)/7.0 as avg_yearly FROM lineitem, part
WHERE p_partkey=l_partkey AND p_brand='Brand#23' AND p_container='MED BOX'
  AND l_quantity < (SELECT 0.2*avg(l_quantity) FROM lineitem WHERE l_partkey=p_partkey)""",

    18: """SELECT c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice, sum(l_quantity)
FROM customer, orders, lineitem
WHERE o_orderkey IN (SELECT l_orderkey FROM lineitem GROUP BY l_orderkey HAVING sum(l_quantity)>300)
  AND c_custkey=o_custkey AND o_orderkey=l_orderkey
GROUP BY c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice ORDER BY o_totalprice DESC, o_orderdate LIMIT 100""",

    19: """SELECT sum(l_extendedprice*(1-l_discount)) as revenue FROM lineitem, part
WHERE (p_partkey=l_partkey AND p_brand='Brand#12' AND p_container IN ('SM CASE','SM BOX','SM PACK','SM PKG')
    AND l_quantity>=1 AND l_quantity<=11 AND p_size BETWEEN 1 AND 5 AND l_shipmode IN ('AIR','AIR REG') AND l_shipinstruct='DELIVER IN PERSON')
  OR (p_partkey=l_partkey AND p_brand='Brand#23' AND p_container IN ('MED BAG','MED BOX','MED PKG','MED PACK')
    AND l_quantity>=10 AND l_quantity<=20 AND p_size BETWEEN 1 AND 10 AND l_shipmode IN ('AIR','AIR REG') AND l_shipinstruct='DELIVER IN PERSON')
  OR (p_partkey=l_partkey AND p_brand='Brand#34' AND p_container IN ('LG CASE','LG BOX','LG PACK','LG PKG')
    AND l_quantity>=20 AND l_quantity<=30 AND p_size BETWEEN 1 AND 15 AND l_shipmode IN ('AIR','AIR REG') AND l_shipinstruct='DELIVER IN PERSON')""",

    20: """SELECT s_name, s_address FROM supplier, nation
WHERE s_suppkey IN (SELECT ps_suppkey FROM partsupp
    WHERE ps_partkey IN (SELECT p_partkey FROM part WHERE p_name LIKE 'forest%')
    AND ps_availqty > (SELECT 0.5*sum(l_quantity) FROM lineitem
      WHERE l_partkey=ps_partkey AND l_suppkey=ps_suppkey
      AND l_shipdate >= DATE '1994-01-01' AND l_shipdate < DATE '1994-01-01' + INTERVAL '1 YEAR'))
  AND s_nationkey=n_nationkey AND n_name='CANADA' ORDER BY s_name""",

    21: """SELECT s_name, count(*) as numwait FROM supplier, lineitem l1, orders, nation
WHERE s_suppkey=l1.l_suppkey AND o_orderkey=l1.l_orderkey AND o_orderstatus='F'
  AND l1.l_receiptdate > l1.l_commitdate
  AND EXISTS (SELECT * FROM lineitem l2 WHERE l2.l_orderkey=l1.l_orderkey AND l2.l_suppkey<>l1.l_suppkey)
  AND NOT EXISTS (SELECT * FROM lineitem l3 WHERE l3.l_orderkey=l1.l_orderkey AND l3.l_suppkey<>l1.l_suppkey AND l3.l_receiptdate>l3.l_commitdate)
  AND s_nationkey=n_nationkey AND n_name='SAUDI ARABIA'
GROUP BY s_name ORDER BY numwait DESC, s_name LIMIT 100""",

    22: """SELECT cntrycode, count(*) as numcust, sum(c_acctbal) as totacctbal FROM (
  SELECT substring(c_phone from 1 for 2) as cntrycode, c_acctbal FROM customer
  WHERE substring(c_phone from 1 for 2) IN ('13','31','23','29','30','18','17')
    AND c_acctbal > (SELECT avg(c_acctbal) FROM customer WHERE c_acctbal>0.00
      AND substring(c_phone from 1 for 2) IN ('13','31','23','29','30','18','17'))
    AND NOT EXISTS (SELECT * FROM orders WHERE o_custkey=c_custkey)
) AS custsale GROUP BY cntrycode ORDER BY cntrycode""",
}

SCHEMA_SQL = """
DROP TABLE IF EXISTS benchmark_results CASCADE;
DROP TABLE IF EXISTS lineitem, orders, partsupp, part, supplier, customer, nation, region CASCADE;

CREATE TABLE region (r_regionkey INTEGER PRIMARY KEY, r_name CHAR(25) NOT NULL, r_comment VARCHAR(152));
CREATE TABLE nation (n_nationkey INTEGER PRIMARY KEY, n_name CHAR(25) NOT NULL, n_regionkey INTEGER NOT NULL REFERENCES region(r_regionkey), n_comment VARCHAR(152));
CREATE TABLE supplier (s_suppkey INTEGER PRIMARY KEY, s_name CHAR(25) NOT NULL, s_address VARCHAR(40) NOT NULL, s_nationkey INTEGER NOT NULL REFERENCES nation(n_nationkey), s_phone CHAR(15) NOT NULL, s_acctbal DECIMAL(15,2) NOT NULL, s_comment VARCHAR(101));
CREATE TABLE part (p_partkey INTEGER PRIMARY KEY, p_name VARCHAR(55) NOT NULL, p_mfgr CHAR(25) NOT NULL, p_brand CHAR(10) NOT NULL, p_type VARCHAR(25) NOT NULL, p_size INTEGER NOT NULL, p_container CHAR(10) NOT NULL, p_retailprice DECIMAL(15,2) NOT NULL, p_comment VARCHAR(23));
CREATE TABLE partsupp (ps_partkey INTEGER NOT NULL REFERENCES part(p_partkey), ps_suppkey INTEGER NOT NULL REFERENCES supplier(s_suppkey), ps_availqty INTEGER NOT NULL, ps_supplycost DECIMAL(15,2) NOT NULL, ps_comment VARCHAR(199), PRIMARY KEY (ps_partkey, ps_suppkey));
CREATE TABLE customer (c_custkey INTEGER PRIMARY KEY, c_name VARCHAR(25) NOT NULL, c_address VARCHAR(40) NOT NULL, c_nationkey INTEGER NOT NULL REFERENCES nation(n_nationkey), c_phone CHAR(15) NOT NULL, c_acctbal DECIMAL(15,2) NOT NULL, c_mktsegment CHAR(10) NOT NULL, c_comment VARCHAR(117));
CREATE TABLE orders (o_orderkey INTEGER PRIMARY KEY, o_custkey INTEGER NOT NULL REFERENCES customer(c_custkey), o_orderstatus CHAR(1) NOT NULL, o_totalprice DECIMAL(15,2) NOT NULL, o_orderdate DATE NOT NULL, o_orderpriority CHAR(15) NOT NULL, o_clerk CHAR(15) NOT NULL, o_shippriority INTEGER NOT NULL, o_comment VARCHAR(79));
CREATE TABLE lineitem (l_orderkey INTEGER NOT NULL REFERENCES orders(o_orderkey), l_partkey INTEGER NOT NULL, l_suppkey INTEGER NOT NULL, l_linenumber INTEGER NOT NULL, l_quantity DECIMAL(15,2) NOT NULL, l_extendedprice DECIMAL(15,2) NOT NULL, l_discount DECIMAL(15,2) NOT NULL, l_tax DECIMAL(15,2) NOT NULL, l_returnflag CHAR(1) NOT NULL, l_linestatus CHAR(1) NOT NULL, l_shipdate DATE NOT NULL, l_commitdate DATE NOT NULL, l_receiptdate DATE NOT NULL, l_shipinstruct CHAR(25) NOT NULL, l_shipmode CHAR(10) NOT NULL, l_comment VARCHAR(44), PRIMARY KEY (l_orderkey, l_linenumber), FOREIGN KEY (l_partkey, l_suppkey) REFERENCES partsupp(ps_partkey, ps_suppkey));

CREATE TABLE benchmark_results (
    id SERIAL PRIMARY KEY,
    run_id TEXT NOT NULL,
    phase TEXT NOT NULL,
    item TEXT NOT NULL,
    duration_ms INTEGER,
    rows_affected BIGINT,
    details TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
"""

INDEX_SQL = """
CREATE INDEX idx_lineitem_shipdate ON lineitem(l_shipdate);
CREATE INDEX idx_lineitem_orderkey ON lineitem(l_orderkey);
CREATE INDEX idx_lineitem_partkey ON lineitem(l_partkey);
CREATE INDEX idx_lineitem_suppkey ON lineitem(l_suppkey);
CREATE INDEX idx_orders_custkey ON orders(o_custkey);
CREATE INDEX idx_orders_orderdate ON orders(o_orderdate);
CREATE INDEX idx_customer_nationkey ON customer(c_nationkey);
CREATE INDEX idx_supplier_nationkey ON supplier(s_nationkey);
CREATE INDEX idx_nation_regionkey ON nation(n_regionkey);
CREATE INDEX idx_partsupp_suppkey ON partsupp(ps_suppkey);
ANALYZE;
"""

TABLES = ["region", "nation", "supplier", "part", "partsupp", "customer", "orders", "lineitem"]


def log(msg):
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] {msg}", flush=True)


def record(conn, run_id, phase, item, duration_ms, rows=None, details=None):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO benchmark_results (run_id, phase, item, duration_ms, rows_affected, details) VALUES (%s,%s,%s,%s,%s,%s)",
            (run_id, phase, item, duration_ms, rows, details),
        )
    conn.commit()


def main():
    connstr = sys.argv[1]
    run_id = f"tpch-sf{SCALE_FACTOR}-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}"
    log(f"TPC-H Benchmark  SF={SCALE_FACTOR}  run_id={run_id}")

    # Connect
    conn = psycopg2.connect(connstr)
    conn.autocommit = True
    log("Connected to database")

    # Schema
    log("Creating schema...")
    t0 = time.time()
    with conn.cursor() as cur:
        cur.execute(SCHEMA_SQL)
    schema_ms = int((time.time() - t0) * 1000)
    log(f"Schema created in {schema_ms}ms")
    record(conn, run_id, "setup", "schema", schema_ms)

    # Generate data with dbgen
    import os, subprocess, shutil
    os.makedirs("/tmp/tpch-data", exist_ok=True)
    os.chdir("/tmp/tpch-data")
    # dists.dss must be in CWD for dbgen
    if not os.path.exists("dists.dss"):
        shutil.copy("/usr/local/bin/dists.dss", "dists.dss")
    log(f"Generating SF={SCALE_FACTOR} data with dbgen...")
    subprocess.run(["dbgen", "-s", str(SCALE_FACTOR), "-f"], check=True, capture_output=True)
    log("Data generated")

    # Load data via COPY
    log("Loading data...")
    load_total_ms = 0
    for table in TABLES:
        tbl_file = f"/tmp/tpch-data/{table}.tbl"
        # Remove trailing pipe
        subprocess.run(["sed", "-i", "s/|$//", tbl_file], capture_output=True, check=True)

        t0 = time.time()
        with conn.cursor() as cur:
            with open(tbl_file, "r") as f:
                cur.copy_expert(f"COPY {table} FROM STDIN WITH (FORMAT csv, DELIMITER '|')", f)
        conn.commit()
        elapsed_ms = int((time.time() - t0) * 1000)

        with conn.cursor() as cur:
            cur.execute(f"SELECT count(*) FROM {table}")
            rows = cur.fetchone()[0]

        load_total_ms += elapsed_ms
        log(f"  {table}: {rows:,} rows in {elapsed_ms}ms")
        record(conn, run_id, "load", table, elapsed_ms, rows=rows)

    record(conn, run_id, "load", "TOTAL", load_total_ms)
    log(f"Total load: {load_total_ms}ms")

    # Indexes
    log("Creating indexes...")
    t0 = time.time()
    with conn.cursor() as cur:
        cur.execute(INDEX_SQL)
    idx_ms = int((time.time() - t0) * 1000)
    log(f"Indexes created in {idx_ms}ms")
    record(conn, run_id, "setup", "indexes", idx_ms)

    # Storage size
    with conn.cursor() as cur:
        cur.execute("SELECT pg_database_size(current_database())")
        db_bytes = cur.fetchone()[0]
        cur.execute("""SELECT relname, pg_total_relation_size(oid)
            FROM pg_class WHERE relkind='r' AND relnamespace=(SELECT oid FROM pg_namespace WHERE nspname='public')
            AND relname NOT LIKE 'benchmark%' ORDER BY pg_total_relation_size(oid) DESC""")
        table_sizes = cur.fetchall()

    db_mb = db_bytes / 1024 / 1024
    log(f"Database size: {db_mb:.1f} MB")
    record(conn, run_id, "storage", "database", 0, details=f"{db_mb:.1f} MB")
    for tname, tbytes in table_sizes:
        record(conn, run_id, "storage", tname, 0, details=f"{tbytes/1024/1024:.1f} MB")

    # Run queries
    log("")
    log("=" * 50)
    log("  Running TPC-H Queries")
    log("=" * 50)

    total_query_ms = 0
    for qnum in sorted(TPCH_QUERIES.keys()):
        sql = TPCH_QUERIES[qnum]

        # Get CPU before
        t0 = time.time()
        with conn.cursor() as cur:
            cur.execute("EXPLAIN (ANALYZE, FORMAT JSON) " + sql)
            plan = cur.fetchone()[0]
        elapsed_ms = int((time.time() - t0) * 1000)
        total_query_ms += elapsed_ms

        # Extract rows from plan
        rows = plan[0].get("Plan", {}).get("Actual Rows", 0) if plan else 0
        planning_ms = int(plan[0].get("Planning Time", 0)) if plan else 0
        execution_ms = int(plan[0].get("Execution Time", 0)) if plan else 0

        log(f"  Q{qnum:2d}: {elapsed_ms:6d}ms  (plan:{planning_ms}ms exec:{execution_ms}ms rows:{rows})")
        record(conn, run_id, "query", f"Q{qnum}", elapsed_ms, rows=rows,
               details=f"planning={planning_ms}ms execution={execution_ms}ms")

    record(conn, run_id, "query", "TOTAL", total_query_ms)
    log(f"\nTotal query time: {total_query_ms}ms")

    # Summary
    log("")
    log("=" * 50)
    log("  Summary")
    log("=" * 50)
    log(f"  Scale Factor:    {SCALE_FACTOR}")
    log(f"  Database Size:   {db_mb:.1f} MB")
    log(f"  Data Load:       {load_total_ms}ms")
    log(f"  Index Creation:  {idx_ms}ms")
    log(f"  22 Queries:      {total_query_ms}ms")
    log(f"  Run ID:          {run_id}")
    log("")
    log("Results saved to benchmark_results table.")
    log("View with: SELECT phase, item, duration_ms, rows_affected, details FROM benchmark_results ORDER BY id;")

    conn.close()


if __name__ == "__main__":
    main()
