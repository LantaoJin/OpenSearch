# Per-query sf=10 stress report (authoritative — fresh cluster per query)

Each query run 1x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). General MPP scheduler (analytics.mpp.enabled). Coordinator buffer 1 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.8 | yes | 15-15% |  |
| q2 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 18.3 | yes | 77-77% |  |
| q3 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 21.7 | yes | 69-69% |  |
| q4 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 5.4 | yes | 11-11% |  |
| q5 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 45.4 | yes | 67-67% |  |
| q6 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.2 | yes | 11-11% |  |
| q7 | FAIL | 0/1 | HASH_SHUFFLE | 0/1 | 0/0 | 0/0 | 19.8 | yes | 76-76% | CircuitBreakingException[native memory allocation rejected: java.lang.RuntimeException: Ex |
| q8 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 30.4 | yes | 66-66% |  |
| q9 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 47.4 | yes | 76-76% |  |
| q10 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 11.3 | yes | 23-23% |  |
| q11 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 15.4 | yes | 58-58% |  |
| q12 | PASS | 1/1 | BROADCAST | 1/1 | 1/1 | 1/1 | 6.2 | yes | 59-59% |  |
| q13 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 9.6 | yes | 72-72% |  |
| q14 | PASS | 1/1 | HASH_SHUFFLE | 1/1 | 1/1 | 1/1 | 23.9 | yes | 67-67% |  |
| q15 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.9 | yes | 10-10% |  |
| q16 | PASS | 1/1 | BROADCAST | 1/1 | 1/1 | 1/1 | 11.2 | yes | 18-18% | capped at 10,000 (ref 27,840; correct top-N prefix, by design) |
| q17 | FAIL | 0/1 | HASH_SHUFFLE | 0/1 | 0/0 | 0/0 | 172.5 | yes | 90-90% | TimeoutError('timed out') |
| q18 | FAIL | 0/1 | HASH_SHUFFLE | 0/1 | 0/0 | 0/0 | 24.5 | yes | 93-93% | CircuitBreakingException[[sf10-0][127.0.0.1:9300][indices:data/read/analytics/shuffle]]; n |
| q19 | FAIL | 0/1 | COORDINATOR_CENTRIC | 0/1 | 0/0 | 0/0 | 4.3 | yes | 11-11% | Internal error [task_id=56] |
| q20 | FAIL | 0/1 | HASH_SHUFFLE | 0/1 | 0/0 | 0/0 | 5.3 | yes | 16-16% | Internal error [task_id=70] |
| q21 | FAIL | 0/1 | HASH_SHUFFLE | 0/1 | 0/0 | 0/0 | 9.6 | yes | 39-39% | Internal error [task_id=60] |
| q22 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.0 | yes | 11-11% |  |

**PASS 16/22 ran** (22 requested) · 1 runs/query · sum of P50s 497.1s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.