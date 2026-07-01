# Per-query sf=10 stress report (authoritative — fresh cluster per query)

Each query run 10x in its OWN fresh 3-node sf=10 cluster (relaunched per query).
Compared vs DuckDB sf=10 ref (row count + first row). General MPP scheduler (analytics.mpp.enabled). Coordinator buffer 2 GiB. Heap = max across nodes per run. wall = P50 of the query round-trip (excludes per-query cluster boot).
A sorted result larger than the 10,000-row engine cap passes when it returns the correct top-10,000 prefix (first row matches) — noted 'capped'.

| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |
|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|
| q7 | FAIL | 0/4 | HASH_SHUFFLE | 0/4 | 0/0 | 0/0 | 17.6 | **NO** | 71-79% (first~71 last~77) | CLUSTER DIED mid-run |
| q19 | FAIL | 0/10 | COORDINATOR_CENTRIC | 0/10 | 0/0 | 0/0 | 0.8 | yes | 11-23% (first~12 last~21) | Internal error [task_id=56] |
| q20 | FAIL | 0/10 | HASH_SHUFFLE | 0/10 | 0/0 | 0/0 | 1.4 | yes | 16-56% (first~22 last~53) | Internal error [task_id=51] |

**PASS 0/3 ran** (3 requested) · 10 runs/query · sum of P50s 19.7s

## Leak / stability callouts
- **q7: cluster DIED** after 4 run(s) — single-query OOM or crash.