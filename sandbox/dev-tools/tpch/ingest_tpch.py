#!/usr/bin/env python3
"""Bulk-load the 8 TPC-H tables from source parquet into the manual cluster, for either scale.

Usage:  python3 ingest_tpch.py --sf 1                 # all 8 tables from .../tpch/sf=1 -> :9210
        python3 ingest_tpch.py --sf 10 lineitem       # just lineitem from .../tpch/sf=10 -> :9200
"""
import os, sys, json, time, gzip, datetime, decimal, urllib.request, urllib.error
import pyarrow.parquet as pq

PORTS = {1: 9210, 10: 9200}
# Source parquet lives OUTSIDE the repo (generated TPC-H data). Override the base dir with
# $TPCH_DATA_DIR (the per-scale dir is then $TPCH_DATA_DIR/sf=<N>); falls back to the data location
# used on this host. Not repo-relative because the dataset isn't checked in.
TPCH_DATA_DIR = os.environ.get("TPCH_DATA_DIR", os.path.expanduser("~/workplace/mustang/tpch"))
TABLES = ["region", "nation", "supplier", "customer", "part", "partsupp", "orders", "lineitem"]
BATCH_ROWS = 20000   # docs per _bulk request


def default(o):
    if isinstance(o, (datetime.date, datetime.datetime)):
        return o.isoformat()
    if isinstance(o, decimal.Decimal):
        return float(o)
    if isinstance(o, bytes):
        return o.decode("utf-8", "replace")
    raise TypeError(repr(o))


def post_bulk(base, table, ndjson_bytes):
    body = gzip.compress(ndjson_bytes)
    r = urllib.request.Request(f"{base}/{table}/_bulk", data=body, method="POST",
                               headers={"Content-Type": "application/x-ndjson", "Content-Encoding": "gzip"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(r, timeout=300) as resp:
                j = json.loads(resp.read().decode())
                if j.get("errors"):
                    for it in j["items"]:
                        v = next(iter(it.values()))
                        if v.get("status", 200) >= 300:
                            raise RuntimeError(f"bulk item error: {json.dumps(v)[:300]}")
                return
        except urllib.error.HTTPError:
            if attempt == 4:
                raise
            time.sleep(2 * (attempt + 1))
        except urllib.error.URLError:
            if attempt == 4:
                raise
            time.sleep(2 * (attempt + 1))


def ingest(base, datadir, table):
    pf = pq.ParquetFile(f"{datadir}/{table}.parquet")
    total = pf.metadata.num_rows
    done = 0
    t0 = time.time()
    print(f"=== {table}: {total:,} rows ===", flush=True)
    action = b'{"index":{}}\n'
    for rb in pf.iter_batches(batch_size=BATCH_ROWS):
        rows = rb.to_pylist()
        buf = bytearray()
        for row in rows:
            buf += action
            buf += json.dumps(row, default=default, separators=(",", ":")).encode()
            buf += b"\n"
        post_bulk(base, table, bytes(buf))
        done += len(rows)
        if done % 200000 == 0 or done == total:
            rate = done / (time.time() - t0)
            print(f"  {table}: {done:,}/{total:,} ({rate:,.0f} rows/s)", flush=True)
    # flush so parquet files commit to the shard dir
    urllib.request.urlopen(urllib.request.Request(f"{base}/{table}/_flush?force=true", method="POST"), timeout=300).read()
    print(f"  {table}: DONE in {time.time() - t0:.0f}s", flush=True)


def main():
    argv = list(sys.argv[1:])
    sf = 1
    if "--sf" in argv:
        i = argv.index("--sf")
        sf = int(argv[i + 1])
        del argv[i:i + 2]
    if sf not in PORTS:
        sys.exit(f"unknown --sf {sf}; choose one of {sorted(PORTS)}")
    base = f"http://localhost:{PORTS[sf]}"
    datadir = os.path.join(TPCH_DATA_DIR, f"sf={sf}")
    if not os.path.isdir(datadir):
        sys.exit(f"source parquet dir not found: {datadir} (set $TPCH_DATA_DIR to its parent)")
    only = argv if argv else TABLES
    print(f"ingesting {only} from {datadir} -> {base} (sf={sf})", flush=True)
    for t in only:
        ingest(base, datadir, t)
    print("ALL DONE", flush=True)


if __name__ == "__main__":
    main()
