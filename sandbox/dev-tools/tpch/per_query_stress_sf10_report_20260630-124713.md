# Per-query sf=10 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 2x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 2/2 | COORDINATOR_CENTRIC | 2/2 | 2/2 | 2/2 | 0.8 | yes | 15-15% (first~15 last~15) |  |
| q2 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 24.2 | yes | 26-74% (first~26 last~74) |  |
| q3 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 30.8 | yes | 21-33% (first~21 last~33) |  |
| q4 | PASS | 2/2 | COORDINATOR_CENTRIC | 2/2 | 2/2 | 2/2 | 2.0 | yes | 11-12% (first~11 last~12) |  |
| q5 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 57.7 | yes | 63-76% (first~76 last~63) |  |
| q6 | PASS | 2/2 | COORDINATOR_CENTRIC | 2/2 | 2/2 | 2/2 | 0.4 | yes | 11-11% (first~11 last~11) |  |
| q7 | FAIL | 0/2 | HASH_SHUFFLE | 0/2 | 0/0 | 0/0 | 40.7 | yes | 45-74% (first~45 last~74) | CircuitBreakingException[native memory allocation rejected: java.lang.RuntimeException: Ex |
| q8 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 47.5 | yes | 36-59% (first~59 last~36) |  |
| q9 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 72.1 | yes | 64-72% (first~64 last~72) |  |
| q10 | PASS | 2/2 | HASH_SHUFFLE | 2/2 | 2/2 | 2/2 | 13.9 | yes | 57-58% (first~58 last~57) |  |

**PASS 9/10 ran** (10 requested) · 2 runs/query · sum of P50s 290.1s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.