# Per-query sf=1 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 10x in its OWN fresh 3-node sf=1 cluster (relaunched per query).
Compared vs DuckDB sf=1 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 4 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.2 | yes | 17-43% (first~17 last~34) |  |
| q2 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 1.4 | yes | 64-82% (first~73 last~73) |  |
| q3 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 1.8 | yes | 20-59% (first~27 last~51) |  |
| q4 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.3 | yes | 16-18% (first~16 last~18) |  |
| q5 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 3.2 | yes | 20-62% (first~51 last~35) |  |
| q6 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.1 | yes | 15-22% (first~15 last~17) |  |
| q7 | FAIL | 0/10 | - | 0/10 | 0/0 | 0/0 | 0.0 | yes | n/a | Internal error [task_id=46] |
| q8 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.9 | yes | 39-54% (first~49 last~49) |  |
| q9 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 2.2 | yes | 30-77% (first~66 last~56) |  |
| q10 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 0.9 | yes | 27-62% (first~45 last~37) |  |
| q11 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 1.1 | yes | 30-75% (first~44 last~68) |  |
| q12 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.5 | yes | 10-43% (first~30 last~34) |  |
| q13 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 0.7 | yes | 22-59% (first~27 last~56) |  |
| q14 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 2.0 | yes | 30-76% (first~40 last~65) |  |
| q15 | FAIL | 6/10 | COORDINATOR_CENTRIC | 10/10 | 6/10 | 6/10 | 0.2 | yes | 15-17% (first~15 last~17) | row mismatch (got vs ref 1) |
| q16 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 2.6 | yes | 16-35% (first~19 last~33) | capped at 10,000 (ref 18,314; correct top-N prefix, by design) |
| q17 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.6 | yes | 9-19% (first~16 last~12) |  |
| q18 | FAIL | 0/10 | HASH_SHUFFLE | 0/10 | 0/0 | 0/0 | 2.8 | yes | 44-78% (first~73 last~57) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q19 | FAIL | 6/10 | COORDINATOR_CENTRIC | 6/10 | 6/6 | 6/6 | 0.9 | yes | 16-29% (first~17 last~27) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q20 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 0.4 | yes | 15-20% (first~18 last~16) |  |
| q21 | FAIL | 0/10 | HASH_SHUFFLE | 0/10 | 0/0 | 0/0 | 2.5 | yes | 42-70% (first~48 last~67) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q22 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.2 | yes | 15-17% (first~15 last~17) |  |

**PASS 17/22 ran** (22 requested) · 10 runs/query · sum of P50s 25.5s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.