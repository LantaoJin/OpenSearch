# Per-query sf=1 stress report — MPP OFF (coordinator-centric baseline) (authoritative — fresh cluster per query)

Each query run 1x in its OWN fresh 3-node sf=1 cluster (relaunched per query).
Compared vs DuckDB sf=1 ref (row count + first row). analytics.mpp.enabled=false. Coordinator buffer 4 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.3 | yes | 17-17% |  |
| q2 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.4 | yes | 52-52% |  |
| q3 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 5.1 | yes | 56-56% |  |
| q4 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.4 | yes | 33-33% |  |
| q5 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 5.1 | yes | 44-44% |  |
| q6 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 2.8 | yes | 15-15% |  |
| q7 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.9 | yes | 46-46% |  |
| q8 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 5.5 | yes | 8-8% |  |
| q9 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 5.4 | yes | 36-36% |  |
| q10 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.0 | yes | 42-42% |  |
| q11 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.8 | yes | 45-45% |  |
| q12 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.0 | yes | 8-8% |  |
| q13 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.7 | yes | 8-8% |  |
| q14 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.7 | yes | 36-36% |  |
| q15 | FAIL | 0/1 | - | 1/1 | 0/1 | 0/1 | 3.2 | yes | 17-17% | row mismatch (got vs ref 1) |
| q16 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 6.1 | yes | 15-15% | capped at 10,000 (ref 18,314; correct top-N prefix, by design) |
| q17 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 6.7 | yes | 20-20% |  |
| q18 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 5.5 | yes | 51-51% |  |
| q19 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.7 | yes | 35-35% |  |
| q20 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.9 | yes | 42-42% |  |
| q21 | FAIL | 0/1 | - | 0/1 | 0/0 | 0/0 | 150.1 | yes | 36-36% | TimeoutError('timed out') |
| q22 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.3 | yes | 18-18% |  |

**PASS 20/22 ran** (22 requested) · 1 runs/query · sum of P50s 243.6s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.