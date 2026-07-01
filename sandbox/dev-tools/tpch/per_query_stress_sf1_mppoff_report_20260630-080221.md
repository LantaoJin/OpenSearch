# Per-query sf=1 stress report — MPP OFF (coordinator-centric baseline) (authoritative — fresh cluster per query)

Each query run 1x in its OWN fresh 3-node sf=1 cluster (relaunched per query).
Compared vs DuckDB sf=1 ref (row count + first row). analytics.mpp.enabled=false. Coordinator buffer 4 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.7 | yes | 17-17% |  |
| q2 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.0 | yes | 16-16% |  |
| q3 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.9 | yes | 16-16% |  |
| q4 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.5 | yes | 15-15% |  |
| q5 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 5.0 | yes | 17-17% |  |
| q6 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.0 | yes | 15-15% |  |
| q7 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.6 | yes | 16-16% |  |
| q8 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 5.5 | yes | 18-18% |  |
| q9 | FAIL | 0/1 | COORDINATOR_CENTRIC | 0/1 | 0/0 | 0/0 | 4.8 | yes | 17-17% | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q10 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.0 | yes | 15-15% |  |
| q11 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.7 | yes | 15-15% |  |
| q12 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.0 | yes | 15-15% |  |
| q13 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.7 | yes | 15-15% |  |
| q14 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.8 | yes | 16-16% |  |
| q15 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.4 | yes | 15-15% |  |
| q16 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 5.1 | yes | 16-16% | capped at 10,000 (ref 18,314; correct top-N prefix, by design) |
| q17 | FAIL | 0/1 | COORDINATOR_CENTRIC | 0/1 | 0/0 | 0/0 | 5.1 | yes | 18-18% | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q18 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 5.7 | yes | 17-17% |  |
| q19 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 4.7 | yes | 16-16% |  |
| q20 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.9 | yes | 16-16% |  |
| q21 | FAIL | 0/1 | COORDINATOR_CENTRIC | 0/1 | 0/0 | 0/0 | 5.9 | yes | 19-19% | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q22 | PASS | 1/1 | COORDINATOR_CENTRIC | 1/1 | 1/1 | 1/1 | 3.3 | yes | 15-15% |  |

**PASS 19/22 ran** (22 requested) · 1 runs/query · sum of P50s 96.3s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.