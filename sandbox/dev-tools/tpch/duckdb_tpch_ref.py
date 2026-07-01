#!/usr/bin/env python3
"""Regenerate the DuckDB TPC-H reference (row count + first row per query) for a scale.

Reads the source parquet ($TPCH_DATA_DIR/sf=<N>, same external dataset ingest_tpch.py uses) and writes
tpch_duckdb_ref_sf<N>.json next to this script — the per_query_stress.py harness compares against it.

Usage:  python3 duckdb_tpch_ref.py --sf 1
        python3 duckdb_tpch_ref.py --sf 10
"""
import duckdb, json, time, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
TPCH_DATA_DIR = os.environ.get("TPCH_DATA_DIR", os.path.expanduser("~/workplace/mustang/tpch"))

sf = int(sys.argv[sys.argv.index("--sf") + 1]) if "--sf" in sys.argv else 1
datadir = os.path.join(TPCH_DATA_DIR, f"sf={sf}")
if not os.path.isdir(datadir):
    sys.exit(f"source parquet dir not found: {datadir} (set $TPCH_DATA_DIR to its parent)")
out = os.path.join(HERE, f"tpch_duckdb_ref_sf{sf}.json")

con = duckdb.connect()
con.execute("INSTALL tpch; LOAD tpch")
for t in ["lineitem", "orders", "customer", "part", "partsupp", "supplier", "nation", "region"]:
    con.execute(f"CREATE VIEW {t} AS SELECT * FROM read_parquet('{datadir}/{t}.parquet')")
qs = con.execute("SELECT query_nr, query FROM tpch_queries() ORDER BY query_nr").fetchall()
ref = {}
for qn, sql in qs:
    t0 = time.time()
    try:
        df = con.execute(sql).df()
        ref[qn] = {"rows": len(df), "took": time.time() - t0,
                   "first": df.iloc[0].to_dict() if len(df) else None,
                   "cols": list(df.columns)}
        print(f"q{qn:2d}: {len(df)} rows ({ref[qn]['took']:.1f}s)")
    except Exception as e:
        ref[qn] = {"error": repr(e)[:300]}
        print(f"q{qn:2d}: ERROR {e}")
json.dump(ref, open(out, "w"), indent=2, default=str)
print(f"\nreference saved to {out}")
