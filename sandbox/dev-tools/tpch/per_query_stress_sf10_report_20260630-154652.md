# Per-query sf=10 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 2x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 2/2 | COORDINATOR_CENTRIC | 2/2 | 2/2 | 2/2 | 0.9 | yes | 15-15% (first~15 last~15) |  |
| q2 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 24.7 | yes | 28-71% (first~28 last~71) |  |
| q3 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 32.6 | yes | 44-72% (first~44 last~72) |  |
| q4 | PASS | 2/2 | COORDINATOR_CENTRIC | 2/2 | 2/2 | 2/2 | 2.2 | yes | 11-12% (first~11 last~12) |  |
| q5 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 59.6 | yes | 28-75% (first~28 last~75) |  |
| q6 | PASS | 2/2 | COORDINATOR_CENTRIC | 2/2 | 2/2 | 2/2 | 0.4 | yes | 11-11% (first~11 last~11) |  |

**PASS 6/6 ran** (6 requested) · 2 runs/query · sum of P50s 120.4s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.