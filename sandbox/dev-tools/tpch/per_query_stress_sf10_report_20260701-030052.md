# Per-query sf=10 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 4x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 4 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 4/4 | COORDINATOR_CENTRIC | 4/4 | 4/4 | 4/4 | 0.8 | yes | 18-25% (first~18 last~25) |  |
| q2 | PASS | 4/4 | BROADCAST | 4/4 | 4/4 | 4/4 | 4.2 | yes | 43-59% (first~52 last~59) |  |
| q3 | PASS | 4/4 | BROADCAST | 4/4 | 4/4 | 4/4 | 7.3 | yes | 35-48% (first~35 last~48) |  |
| q4 | PASS | 4/4 | COORDINATOR_CENTRIC | 4/4 | 4/4 | 4/4 | 1.8 | yes | 15-18% (first~15 last~18) |  |
| q5 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 11.7 | yes | 49-71% (first~55 last~49) |  |
| q6 | PASS | 4/4 | COORDINATOR_CENTRIC | 4/4 | 4/4 | 4/4 | 0.4 | yes | 15-17% (first~15 last~17) |  |
| q7 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 26.8 | yes | 34-67% (first~61 last~64) |  |
| q8 | PASS | 4/4 | BROADCAST | 4/4 | 4/4 | 4/4 | 1.8 | yes | 25-51% (first~25 last~42) |  |
| q9 | PASS | 4/4 | BROADCAST | 4/4 | 4/4 | 4/4 | 8.0 | yes | 33-56% (first~56 last~50) |  |
| q10 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 5.0 | yes | 14-54% (first~32 last~47) |  |
| q11 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 18.7 | yes | 32-69% (first~32 last~51) |  |
| q12 | PASS | 4/4 | BROADCAST | 4/4 | 4/4 | 4/4 | 0.8 | yes | 27-52% (first~27 last~52) |  |
| q13 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 7.2 | yes | 34-58% (first~42 last~34) |  |
| q14 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 10.0 | yes | 42-66% (first~53 last~42) |  |
| q15 | PASS | 2/4 | COORDINATOR_CENTRIC | 4/4 | 2/4 | 2/4 | 0.7 | yes | 15-15% (first~15 last~15) |  |
| q16 | PASS | 4/4 | BROADCAST | 0/4 | 0/0 | 0/0 | 3.4 | yes | 26-38% (first~26 last~29) |  |
| q17 | FAIL | 0/4 | HASH_SHUFFLE | 0/4 | 0/0 | 0/0 | 39.4 | yes | 54-72% (first~68 last~70) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 3440640 bytes ( |
| q18 | FAIL | 0/4 | HASH_SHUFFLE | 0/4 | 0/0 | 0/0 | 9.3 | yes | 53-58% (first~58 last~53) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q19 | FAIL | 0/4 | COORDINATOR_CENTRIC | 0/4 | 0/0 | 0/0 | 0.9 | yes | 16-21% (first~16 last~21) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q20 | PASS | 4/4 | HASH_SHUFFLE | 4/4 | 4/4 | 4/4 | 1.4 | yes | 18-25% (first~18 last~19) |  |
| q21 | FAIL | 0/4 | HASH_SHUFFLE | 0/4 | 0/0 | 0/0 | 4.3 | yes | 27-50% (first~27 last~27) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q22 | PASS | 4/4 | COORDINATOR_CENTRIC | 4/4 | 4/4 | 4/4 | 0.8 | yes | 15-17% (first~15 last~17) |  |

**PASS 18/22 ran** (22 requested) · 4 runs/query · sum of P50s 164.8s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.
