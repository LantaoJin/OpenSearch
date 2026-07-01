# Per-query sf=10 stress report — MPP ON (general scheduler) (authoritative — fresh cluster per query)

Each query run 3x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=true. Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 3/3 | COORDINATOR_CENTRIC | 3/3 | 3/3 | 3/3 | 0.8 | yes | 15-25% (first~15 last~25) |  |
| q2 | PASS | 3/3 | BROADCAST | 3/3 | 3/3 | 3/3 | 3.1 | yes | 41-56% (first~56 last~41) |  |
| q3 | PASS | 3/3 | BROADCAST | 3/3 | 3/3 | 3/3 | 6.7 | yes | 47-64% (first~57 last~47) |  |
| q4 | PASS | 3/3 | COORDINATOR_CENTRIC | 3/3 | 3/3 | 3/3 | 2.1 | yes | 11-12% (first~11 last~12) |  |
| q5 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 7.1 | yes | 57-64% (first~57 last~61) |  |
| q6 | PASS | 3/3 | COORDINATOR_CENTRIC | 3/3 | 3/3 | 3/3 | 0.4 | yes | 11-11% (first~11 last~11) |  |
| q7 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 13.9 | yes | 34-68% (first~63 last~34) |  |
| q8 | PASS | 3/3 | BROADCAST | 3/3 | 3/3 | 3/3 | 2.1 | yes | 56-59% (first~58 last~59) |  |
| q9 | PASS | 3/3 | BROADCAST | 3/3 | 3/3 | 3/3 | 6.0 | yes | 38-50% (first~50 last~38) |  |
| q10 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 3.8 | yes | 52-63% (first~52 last~56) |  |
| q11 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 10.6 | yes | 55-74% (first~55 last~74) |  |
| q12 | PASS | 3/3 | BROADCAST | 3/3 | 3/3 | 3/3 | 0.8 | yes | 25-55% (first~25 last~55) |  |
| q13 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 3.5 | yes | 57-73% (first~57 last~73) |  |
| q14 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 4.3 | yes | 31-45% (first~31 last~45) |  |
| q15 | PASS | 2/3 | COORDINATOR_CENTRIC | 3/3 | 2/3 | 2/3 | 0.8 | yes | 11-12% (first~11 last~12) |  |
| q16 | PASS | 3/3 | BROADCAST | 3/3 | 3/3 | 3/3 | 7.8 | yes | 18-35% (first~18 last~35) | capped at 10,000 (ref 27,840; correct top-N prefix, by design) |
| q17 | FAIL | 0/3 | - | 0/3 | 0/0 | 0/0 | 152.1 | yes | 89-89% | TimeoutError('timed out') |
| q18 | FAIL | 0/3 | HASH_SHUFFLE | 0/3 | 0/0 | 0/0 | 5.9 | yes | 53-73% (first~53 last~73) | Internal error [task_id=112] |
| q19 | FAIL | 0/3 | COORDINATOR_CENTRIC | 0/3 | 0/0 | 0/0 | 1.1 | yes | 11-13% (first~11 last~13) | Internal error [task_id=66] |
| q20 | PASS | 3/3 | HASH_SHUFFLE | 3/3 | 3/3 | 3/3 | 1.5 | yes | 15-19% (first~15 last~17) |  |
| q21 | FAIL | 0/3 | HASH_SHUFFLE | 0/3 | 0/0 | 0/0 | 5.3 | yes | 22-44% (first~37 last~44) | Internal error [task_id=92] |
| q22 | PASS | 3/3 | COORDINATOR_CENTRIC | 3/3 | 3/3 | 3/3 | 0.9 | yes | 11-12% (first~11 last~12) |  |

**PASS 17/22 ran** (22 requested) · 3 runs/query · sum of P50s 240.4s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.
