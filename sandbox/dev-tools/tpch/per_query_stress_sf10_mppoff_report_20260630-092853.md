# Per-query sf=10 stress report — MPP OFF (coordinator-centric baseline) (authoritative — fresh cluster per query)

Each query run 1x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). analytics.mpp.enabled=false. Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q1 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.8 | yes | 15-15% |  |
| q2 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 6.8 | yes | 56-56% |  |
| q3 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 13.8 | yes | 27-27% |  |
| q4 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 5.6 | yes | 6-6% |  |
| q5 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 15.0 | yes | 41-41% |  |
| q6 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 3.2 | yes | 10-10% |  |
| q7 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 12.1 | yes | 47-47% |  |
| q8 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 14.7 | yes | 48-48% |  |
| q9 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 17.2 | yes | 9-9% |  |
| q10 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 8.5 | yes | 20-20% |  |
| q11 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 6.1 | yes | 17-17% |  |
| q12 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 8.2 | yes | 45-45% |  |
| q13 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 6.3 | yes | 15-15% |  |
| q14 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 11.5 | yes | 47-47% |  |
| q15 | FAIL | 0/1 | - | 1/1 | 0/1 | 0/1 | 3.8 | yes | 39-39% | row mismatch (got vs ref 1) |
| q16 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 10.0 | yes | 23-23% | capped at 10,000 (ref 27,840; correct top-N prefix, by design) |
| q17 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 25.6 | yes | 19-19% |  |
| q18 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 21.6 | yes | 19-19% |  |
| q19 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 10.9 | yes | 47-47% |  |
| q20 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 6.4 | yes | 25-25% |  |
| q21 | FAIL | 0/1 | - | 0/1 | 0/0 | 0/0 | 150.0 | yes | 14-14% | TimeoutError('timed out') |
| q22 | PASS | 1/1 | - | 1/1 | 1/1 | 1/1 | 4.3 | yes | 18-18% |  |

**PASS 20/22 ran** (22 requested) · 1 runs/query · sum of P50s 365.4s

## Leak / stability callouts
- No cluster deaths; every query that ran kept the cluster up across all its runs.