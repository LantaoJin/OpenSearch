# Per-query sf=10 stress report — BASE 2bc7dc6 (coordinator-centric, NO MPP) — sf=1-aligned config (heap 8g, DF pool 3g, coord 1g)

Each query run 4x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 4 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 0.9 | yes | 18-25% (first~18 last~25) |  |
| q2 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 2.5 | yes | 20-58% (first~35 last~41) |  |
| q3 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 9.3 | yes | 22-58% (first~58 last~22) |  |
| q4 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 2.0 | yes | 8-59% (first~10 last~54) |  |
| q5 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 10.5 | yes | 21-32% (first~28 last~32) |  |
| q6 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 0.4 | yes | 14-17% (first~14 last~17) |  |
| q7 | FAIL | 0/4 | - | 0/4 | 0/0 | 0/0 | 4.8 | yes | 9-40% (first~9 last~40) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 332800 bytes (l |
| q8 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 10.4 | yes | 14-47% (first~14 last~47) |  |
| q9 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 12.5 | yes | 38-60% (first~38 last~60) |  |
| q10 | FAIL | 2/4 | - | 2/4 | 2/2 | 2/2 | 4.5 | yes | 9-47% (first~9 last~16) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 4980736 bytes ( |
| q11 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 2.0 | yes | 12-17% (first~15 last~12) |  |
| q12 | FAIL | 0/4 | - | 0/4 | 0/0 | 0/0 | 2.6 | yes | 9-57% (first~57 last~30) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 1245184 bytes ( |
| q13 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 2.1 | yes | 10-55% (first~36 last~19) |  |
| q14 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 6.7 | yes | 35-45% (first~35 last~45) |  |
| q15 | PASS | 3/4 | - | 4/4 | 3/4 | 3/4 | 0.8 | yes | 21-36% (first~36 last~21) |  |
| q16 | FAIL | 0/4 | - | 0/4 | 0/0 | 0/0 | 0.3 | yes | 37-37% (first~37 last~37) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 64 bytes (limit |
| q17 | FAIL | 0/4 | - | 0/4 | 0/0 | 0/0 | 4.7 | yes | 12-52% (first~18 last~16) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 196608 bytes (l |
| q18 | FAIL | 0/4 | - | 0/4 | 0/0 | 0/0 | 1.9 | yes | 9-47% (first~25 last~47) | CircuitBreakingException[[analytics_backend_datafusion] Failed to allocate 1441792 bytes ( |
| q19 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 6.0 | yes | 34-44% (first~34 last~44) |  |
| q20 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 2.1 | yes | 10-49% (first~32 last~20) |  |
| q21 | FAIL | 0/4 | - | 0/4 | 0/0 | 0/0 | 150.0 | yes | 18-32% (first~20 last~18) | TimeoutError('timed out') |
| q22 | PASS | 4/4 | - | 4/4 | 4/4 | 4/4 | 0.8 | yes | 7-57% (first~7 last~9) |  |

**PASS 15/22 ran** (22 requested) · 4 runs/query · sum of P50s 237.7s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.
