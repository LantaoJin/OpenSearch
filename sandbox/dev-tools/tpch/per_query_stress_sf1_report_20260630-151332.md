# Per-query sf=1 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 10x in its OWN fresh 3-node sf=1 cluster (relaunched per query).
Compared vs DuckDB sf=1 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 4 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.2 | yes | 17-31% (first~17 last~31) |  |
| q2 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.7 | yes | 45-59% (first~50 last~48) |  |
| q3 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 1.0 | yes | 13-58% (first~48 last~44) |  |
| q4 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.3 | yes | 15-18% (first~15 last~17) |  |
| q5 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 1.4 | yes | 19-59% (first~50 last~49) |  |
| q6 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.1 | yes | 15-22% (first~15 last~17) |  |
| q7 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 2.8 | yes | 28-60% (first~32 last~53) |  |
| q8 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.5 | yes | 29-58% (first~44 last~53) |  |
| q9 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 1.1 | yes | 33-56% (first~47 last~41) |  |
| q10 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 0.8 | yes | 34-57% (first~50 last~38) |  |
| q11 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 2.2 | yes | 23-57% (first~50 last~45) |  |
| q12 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.2 | yes | 17-31% (first~17 last~31) |  |
| q13 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 0.9 | yes | 25-59% (first~45 last~53) |  |
| q14 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.2 | yes | 18-37% (first~18 last~37) |  |
| q15 | FAIL | 6/10 | COORDINATOR_CENTRIC | 10/10 | 6/10 | 6/10 | 0.2 | yes | 15-17% (first~15 last~17) | row mismatch (got vs ref 1) |
| q16 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 2.9 | yes | 17-35% (first~19 last~33) | capped at 10,000 (ref 18,314; correct top-N prefix, by design) |
| q17 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 0.6 | yes | 15-19% (first~16 last~19) |  |
| q18 | PASS | 10/10 | BROADCAST | 10/10 | 10/10 | 10/10 | 1.5 | yes | 16-46% (first~36 last~26) |  |
| q19 | FAIL | 4/10 | COORDINATOR_CENTRIC | 4/10 | 4/4 | 4/4 | 0.9 | yes | 16-29% (first~17 last~27) | CircuitBreakingException[native memory allocation rejected: Failure allocating buffer.]; n |
| q20 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 0.3 | yes | 13-20% (first~16 last~13) |  |
| q21 | PASS | 10/10 | HASH_SHUFFLE | 10/10 | 10/10 | 10/10 | 3.8 | yes | 28-49% (first~41 last~45) |  |
| q22 | PASS | 10/10 | COORDINATOR_CENTRIC | 10/10 | 10/10 | 10/10 | 0.2 | yes | 15-17% (first~15 last~17) |  |

**PASS 20/22 ran** (22 requested) · 10 runs/query · sum of P50s 22.8s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.