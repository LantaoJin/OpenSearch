#!/usr/bin/env python3
"""Create the 8 composite (parquet-primary) TPC-H indices with mappings, for either scale.

Usage:  python3 create_tpch_indices.py --sf 1     # -> http://localhost:9210
        python3 create_tpch_indices.py --sf 10    # -> http://localhost:9200
"""
import json, os, urllib.request, urllib.error, sys

PORTS = {1: 9210, 10: 9200}
# This script lives at <repo>/sandbox/dev-tools/tpch/, so sandbox/ is two levels up.
HERE = os.path.dirname(os.path.abspath(__file__))
SANDBOX = os.path.normpath(os.path.join(HERE, "..", ".."))
MAPDIR = os.path.join(SANDBOX, "qa/analytics-engine-rest/src/test/resources/datasets/tpch")
# shards per table — 3 shards on big tables so each spreads one-per-node across the
# 3-node cluster (new empty primaries allocate across nodes; no peer recovery needed).
SHARDS = {"lineitem": 3, "orders": 3, "partsupp": 3, "part": 3, "customer": 3,
          "supplier": 1, "nation": 1, "region": 1}


def req(base, method, path, body=None):
    data = body.encode() if body else None
    r = urllib.request.Request(base + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    sf = int(sys.argv[sys.argv.index("--sf") + 1]) if "--sf" in sys.argv else 1
    if sf not in PORTS:
        sys.exit(f"unknown --sf {sf}; choose one of {sorted(PORTS)}")
    base = f"http://localhost:{PORTS[sf]}"
    print(f"creating TPC-H indices on {base} (sf={sf})")
    for table, shards in SHARDS.items():
        req(base, "DELETE", "/" + table)  # drop if exists
        m = json.load(open(f"{MAPDIR}/mapping_{table}.json"))
        props = m["mappings"]["properties"]
        body = {
            "settings": {
                "index.pluggable.dataformat.enabled": True,
                "index.pluggable.dataformat": "composite",
                "index.composite.primary_data_format": "parquet",
                "index.composite.secondary_data_formats": ["lucene"],
                "number_of_shards": shards,
                "number_of_replicas": 0,
            },
            "mappings": {"properties": props},
        }
        st, resp = req(base, "PUT", "/" + table, json.dumps(body))
        print(f"{table:10s} shards={shards}  -> {st}  {resp[:120]}")


if __name__ == "__main__":
    main()
