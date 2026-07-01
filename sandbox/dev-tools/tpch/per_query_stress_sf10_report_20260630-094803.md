# Per-query sf=10 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 1x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.0 | yes | 15-15% |  |
| q2 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 17.9 | yes | 68-68% |  |
| q3 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 21.4 | yes | 38-38% |  |
| q4 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 5.5 | yes | 11-11% |  |
| q5 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 43.9 | yes | 56-56% |  |
| q6 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.2 | yes | 11-11% |  |
| q7 | FAIL | 0/1 | HASH_SHUFFLE | 0/1 | 0/0 | 0/0 | 19.7 | yes | 77-77% | CircuitBreakingException[native memory allocation rejected: java.lang.RuntimeException: Ex |
| q8 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 30.5 | yes | 65-65% |  |
| q9 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 46.9 | yes | 57-57% |  |

**PASS 8/9 ran** (9 requested) · 1 runs/query · sum of P50s 193.0s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.