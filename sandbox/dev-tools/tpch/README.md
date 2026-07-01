# TPC-H manual-cluster harness (unified sf=1 / sf=10)

One folder, one sweep harness, both scales. Replaces the old `dev-tools/tpch-sf1/` +
`dev-tools/tpch-sf10/` split — the two sweeps differed only in a handful of constants
(node names, ports, ref file, default run count, coordinator buffer), now a `--sf` flag.

## The authoritative sweep

`per_query_stress.py` — fresh-cluster-per-query TPC-H sweep. For each query it relaunches a fresh
3-node cluster, runs the query N times, and records correctness vs the DuckDB ref + P50 wall-time +
heap trajectory. Fresh-cluster-per-query is the point: it stops one heavy query's off-heap pressure
from poisoning later queries (which the shared-cluster runner over-reports as OOM).

```bash
python3 per_query_stress.py --sf 1                  # all 22, 1 run each (fast, authoritative)
python3 per_query_stress.py --sf 10                 # all 22, 10 runs each (leak/stability stress)
python3 per_query_stress.py --sf 10 --runs 1 q3 q9  # q3,q9 once each at sf=10
python3 per_query_stress.py --sf 1 --no-stop q19    # keep the last query's cluster running
```

Report → `per_query_stress_sf{1,10}_report.md` (next to the script). Columns: PASS/FAIL, correct/runs
(rowmatch ∧ firstmatch), strategy, ok, rowmatch, firstmatch, **wall P50 (s)** (query round-trip,
excludes cluster boot), cluster survived, heap band (+ first/last-third leak signal), notes.

### Scale profiles (in `SCALE_PROFILES`)

| sf | nodes | HTTP ports | ref | default runs | coord buffer |
|----|-------|-----------|-----|--------------|--------------|
| 1  | `tpch-sf1-node{,-1,-2}`  | 9210/1/2 | `tpch_duckdb_ref_sf1.json`  | 1  | 4 GiB |
| 10 | `tpch-sf10-node{,-1,-2}` | 9200/1/2 | `tpch_duckdb_ref_sf10.json` | 10 | 1 GiB |

### Behaviors baked in (hard-won)

- **`apply_toggles` sets ONLY `analytics.coordinator.buffer_limit`.** The general-scheduler end-state
  removed the per-strategy MPP toggles (`cascade.enabled` / `aggregate_over_join.enabled` /
  `cbo_native_cascade`); including one makes the whole atomic settings PUT fail 400 and silently drops
  the buffer raise. MPP is on via each node's `opensearch.yml` (`analytics.mpp.enabled` +
  `analytics.mpp.distribute.min_rows`).
- **10k result cap is a PASS by design.** A sorted result larger than `DEFAULT_MAX_ROWS` returns the
  top-N prefix (q16). It counts as a row-match iff it returns exactly the cap AND the ref exceeds it AND
  the first row matches — a correct sorted prefix, not a truncation defect (noted `capped`).
- **`relaunch_missing_nodes`** recovers from the transient one-node boot hiccup
  (`java.io.tmpdir does not exist` / `Could not find or load main class`).

## Cluster provisioning (all `--sf`-parametrized)

Each provisioning script takes `--sf 1|10`; the scale profile (ports, data dir, heap, shard counts,
DF pool, coordinator buffer) is selected internally — no more per-scale copies:

- `setup_cluster.sh --sf 1|10` — clone the testcluster distro into 3 node dirs + write per-node config.
  The generated `opensearch.yml` is already correct for the general scheduler: MPP via
  `analytics.mpp.enabled` + `analytics.mpp.distribute.min_rows: 1`, and the DELETED per-strategy toggles
  (`shuffle.cascade.enabled` / `cbo_native_cascade` / `aggregate_over_join.enabled`, which current code
  rejects at boot) are NOT written. Creates `data/df_spill` + `data/shuffle_spill`.
- `create_tpch_indices.py --sf 1|10` — create the 8 composite (parquet-primary) indices with mappings.
- `ingest_tpch.py --sf 1|10 [table ...]` — bulk-load the 8 tables (or just the named ones) from source parquet (auto-id).
- `duckdb_tpch_ref.py` — regenerate a DuckDB reference (row count + first row).
- `tpch_duckdb_ref_sf{1,10}.json` — the two reference datasets (same 22 query keys, different values per
  scale; kept as separate files since they're data, not code).

## Notes

- `gradlew` is at the REPO ROOT (`/workplace/ltjin/mustang/OpenSearch`), not in `sandbox/`.
- The cluster loads the native `.so` from `sandbox/libs/dataformat-native/rust/target/release/`.
- After an upstream merge / code bump, redeploy HEAD `analytics-engine` + `analytics-framework` +
  `analytics-backend-datafusion` jars to each node's `plugins/`, and ensure the node's core
  `opensearch-*.jar` is current (a stale distro core lacks newer plugin-API classes and won't boot).
