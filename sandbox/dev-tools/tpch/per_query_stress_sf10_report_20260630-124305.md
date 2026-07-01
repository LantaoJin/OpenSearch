# Per-query sf=10 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 1x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.8 | yes | 15-15% |  |
| q2 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 27.9 | yes | 60-60% |  |
| q3 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 35.3 | yes | 58-58% |  |

**PASS 3/3 ran** (3 requested) · 1 runs/query · sum of P50s 67.0s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.