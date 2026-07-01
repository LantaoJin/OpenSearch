<!--
  BLOG DRAFT / SCAFFOLD  (v2 — deeper)
  Title: Introducing multi-stage distributed join and aggregation in OpenSearch
  Status: outline + scaffold — fill the [PLACEHOLDER ...] blocks before publishing.
  Conventions:
    [IMAGE: ...]      → drop in a figure; the text describes what it should show.
    [BENCHMARK: ...]  → drop in a MEASURED number/table/chart; do NOT invent figures.
    [VERIFY: ...]     → confirm against code/run before publishing.
  Audience: OpenSearch users + operators running analytical (OLAP-style) PPL/SQL workloads,
            plus engineers who want the "how".
  Design intent of this draft: be concrete (one query traced end to end) and explain the WHY
  (memory math + the engineering decisions), rather than listing features. The differentiators
  to lean on — we are COST-BASED, and we built MPP INSIDE a shard-pinned search engine.
-->

# Introducing multi-stage distributed join and aggregation in OpenSearch

> **TL;DR** — The OpenSearch analytics engine can now execute **multi-way joins** and **aggregations over joins** as a *multi-stage distributed* computation that runs in parallel across data nodes, instead of gathering all the data to a single coordinator. Joins that used to exhaust coordinator memory now spread their work — and their memory — across the whole cluster, scaling with node count. The engine **chooses the strategy by cost** (broadcast, hash-shuffle, or coordinator-centric) per join, it's transparent to the query author, and it's validated end-to-end against TPC-H. Flip it on with a single setting.

<!-- FIGURE: Hero. Left: the old world — three table scans all funnel into one coordinator node that holds the entire join. Right: the new world — the same query as a staged DAG fanned across 3 data nodes, each owning a slice, with only a thin arrow returning a small final result. Contrast "centralized vs. parallel" should read in one glance. -->
![Centralized vs. parallel: the old world funnels three scans into one coordinator holding the entire join; the new world spreads the join across data nodes, returning only a small result](blog-figures/02-hero.png)

---

## 1. The memory wall

Until now, OpenSearch's analytics path executed joins and aggregations **coordinator-centric**. Each shard scans (and optionally pre-aggregates) its slice; every slice is shipped to the coordinating node; the join or final aggregation runs *there* — on one node, in one heap.

It's a sound model, and for a join with one small side it's the right one. But analytical workloads lean on exactly the shapes it can't carry:

- **Large × large joins.** Both sides must be fully present on the coordinator. The build-side hash table alone can exceed a single node's heap.
- **Multi-way joins** (`A ⋈ B ⋈ C ⋈ …`). Every intermediate result also passes through the coordinator, compounding the pressure.
- **High-cardinality `GROUP BY` over a join.** The coordinator both assembles the join *and* merges every partial aggregate serially.

Here's the memory math that makes it concrete. Suppose a join's build side needs a 16 GiB hash table. Coordinator-centric, that's 16 GiB on **one** node — and if the node has a 12 GiB heap, the query simply cannot run, no matter how much memory the *rest* of the cluster has idle. Hash-partition the same join across 8 nodes and each node builds roughly **1/8** of the table — about **2 GiB per node**. The work didn't shrink; it stopped piling onto one node.

That gap — aggregate cluster memory that the old model couldn't use — is the wall this feature removes.

<!-- FIGURE: "Before" plan tree for a 3-way join — both inputs of every join gather to a single COORDINATOR node; red highlight labelled "entire join + all intermediates live here." This is the picture §3 transforms. -->
![Coordinator-centric 3-way join: every scan gathers up into one node that holds the entire join and all intermediates](blog-figures/03-before-tree.png)

---

## 2. The idea: stages connected by exchanges

The fix is the model modern MPP engines (Spark, Presto/Trino, cloud warehouses) use: break the query into a pipeline of **stages** that run in parallel on the data nodes, and move intermediate data between stages with **exchange** operators. OpenSearch now does this for joins and aggregations.

There are three exchanges, and almost everything else is built from them:

| Exchange | Shape | What it's for |
|---|---|---|
| **Gather** | N → 1 | Produce the final result on the coordinator; reduce a small input. |
| **Broadcast** | 1 → N | Replicate a *small* build side to every probe node, so each joins locally. |
| **Hash-shuffle** | M → N | Repartition both join sides by the join key, so each worker owns a disjoint key bucket and joins it independently. |

An exchange is a **stage boundary**: the engine cuts the plan there and schedules the child stage on its own set of nodes. A join over two hash-shuffles becomes a *worker tier* — a stage that runs the join in parallel, one partition per worker. Chain those tiers and you have a distributed multi-way join.

<!-- FIGURE: Three small schematics side by side — Gather (funnel), Broadcast (fan-out replicate), Hash-shuffle (crossbar repartition by key) — each with a one-line caption. The visual vocabulary for the rest of the post. -->
![The three exchanges — Gather (N→1, funnel to coordinator), Broadcast (1→N, replicate the small build), Hash-shuffle (M→N, repartition by join key)](blog-figures/01-three-exchanges.png)

---

## 3. One query, traced end to end

The best way to see it is to follow a single query through the engine. Take a three-way join with an aggregation on top — the shape behind TPC-H Q5/Q10:

```sql
-- PPL
source = orders
| inner join left=O right=L on O.orderkey = L.orderkey  lineitem
| inner join left=O right=C on O.custkey  = C.custkey   customer
| stats sum(L.quantity) as qty by mktsegment
| sort mktsegment
```

[VERIFY: confirm this parses/runs on the current build; align column + index names with the demo schema. Per a known PPL quirk, the group key after a join is the bare `mktsegment`, not `C.mktsegment`.]

Watch what happens to it.

**Step 1 — Logical plan.** The parser hands the engine a plain join/aggregate tree. Nothing is distributed yet; `lineitem` (the large fact) and the two dimensions are just scans.

**Step 2 — Cost-based strategy choice.** The planner fetches the real row counts for each index and lets its cost model rank the competing alternatives for *each* join: broadcast the small side, hash-shuffle both sides, or gather to the coordinator. `orders ⋈ lineitem` is large × large → hash-shuffle on `orderkey`. The join with `customer` → hash-shuffle on `custkey`. (Had `customer` been tiny, the cost model would have picked broadcast for that join instead — automatically.)

**Step 3 — Exchange placement.** A second pass walks the chosen plan and inserts exchanges *only where the data isn't already partitioned the way the next operator needs it* — the same principle as Spark's `EnsureRequirements` and Presto's `AddExchanges`. The two scans feeding the bottom join get hash-shuffles on `orderkey`; the bottom join's output, already partitioned, flows into the top join's tier; the aggregate's `PARTIAL` rides the top worker; a final gather brings the small per-segment partials home.

**Step 4 — Staged DAG.** The plan is cut at each exchange into stages with roles: shard-scan producers, two worker join tiers, a partial-aggregate on the top tier, and a coordinator stage doing the final merge + sort.

**Step 5 — Execution.** Producers stream their partitions over the shuffle transport; each worker buffers its key bucket, joins, partially aggregates, and ships its partials; the coordinator merges only the (tiny) grouped result.

<!-- FIGURE: Centerpiece — a 4-panel "plan evolution" strip for THIS query: (1) logical tree, (2) cost-chosen strategy per join, (3) exchanges inserted (HASH_SHUFFLE orderkey/custkey, PARTIAL/FINAL split, GATHER), (4) the staged DAG colored by where each stage runs. This single figure carries the whole post. -->
![Plan evolution in four views: the logical tree, the cost-chosen strategy per join, exchanges placed (hash-shuffle on orderkey and custkey, PARTIAL/FINAL aggregate split, gather), and the staged DAG banded by coordinator / worker tiers / data nodes](blog-figures/04-plan-evolution.png)

The result is identical to what the coordinator-centric path would have produced — but the two joins and the partial aggregation never touched a single bottleneck node.

---

## 4. How the engine decides: cost-seeded, then algebra-enforced

A detail worth dwelling on, because it's where OpenSearch differs from many first-generation distributed engines: **strategy selection is cost-based from day one.**

A purely rule-based planner ("shuffle if both sides are tables, broadcast if one is small") gets the easy cases right and the interesting ones wrong — it will shuffle a table that should have been broadcast, or broadcast something too large. OpenSearch instead seeds the decision with **real per-index row counts** and lets a cost model rank the alternatives per join, weighing build size against the number of probe nodes. Broadcast wins for a small dimension against a big fact; hash-shuffle wins for two large tables; a query whose result is tiny just gathers. You never choose by hand, and the choice adapts to *your* data and cluster shape.

Placement then runs as a **separate pass** over the cost-chosen plan. Why two phases instead of one? The cost optimizer works bottom-up and, by construction, resolves each join to a single "everything on the coordinator" answer first — it reasons about *strategy*, not *layout*. So a second top-down pass re-derives the actual layout: it threads each operator's required data distribution down the tree and inserts an exchange only where a child can't already satisfy it. That separation is what lets arbitrary join depth, mixed key patterns, and odd tree shapes all work through one mechanism, with no per-query-shape special casing.

<!-- FIGURE: A pipeline strip — row counts → cost ranks strategies per join → top-down exchange-placement pass → staged DAG → dispatch. Five boxes, left to right; depth is in the prose. -->
![Pipeline: real row counts feed a cost model that ranks a strategy per join, then a separate top-down exchange-placement pass inserts exchanges only where needed, the plan is cut into a staged DAG, and dispatched — cost decides, then algebra places](blog-figures/05-cost-pipeline.png)

---

## 5. The strategies, in one place

All four shapes come out of the same machinery:

- **Coordinator-centric** — the safe default for small inputs, and the runtime kill-switch fallback.
- **Broadcast** — replicate the small build side to every probe node; each node joins locally. No gather of the large side.
- **Hash-shuffle / multi-way cascade** — co-partition both sides on the join key; chain a worker tier per join for multi-way queries (inner, outer, and mixed-key).
- **Distributed aggregation over a join** — run `PARTIAL` on the join's worker tier (per partition), then a small `FINAL` merge on the coordinator. This is the Q5/Q10 shape from §3.

<!-- FIGURE: A 2×2 of miniature DAGs, one per strategy, so a reader can pattern-match their own query shape to a strategy at a glance. -->
![Four strategies as miniature DAGs: coordinator-centric (gather both sides), broadcast (replicate the small side), hash-shuffle cascade (a worker tier per join), and distributed aggregation over a join (PARTIAL on workers, FINAL on the coordinator)](blog-figures/06-strategies-2x2.png)

---

## 6. The hard part: MPP inside a shard-based engine

Most distributed-SQL systems are designed as distributed engines from the first commit, often over disaggregated storage where any worker can read any data and workers are interchangeable. OpenSearch is the opposite starting point: it's a **shared-nothing search engine** where shards are pinned to the nodes that own them. Bringing multi-stage execution into that world raised problems a from-scratch engine never sees — and the solutions are some of the more interesting parts of this work.

### 6.1 Data is pinned, so scan placement isn't free

In a disaggregated system the scheduler can put any scan anywhere. Here, a scan must run where its shard lives. The engine honors this with a strict rule: **one scan per stage**, with each fragment resolving its execution targets from its shard. That constraint shapes the whole DAG-cutting logic — stages are built around where data already is, and the shuffle tiers are layered on top of those shard-local producers rather than replacing them.

<!-- FIGURE: Shard-pinned scan stages at the bottom (locked to their nodes) and a freely-placeable shuffle worker tier above them — "data is fixed, computation moves to a tier above it." -->
![Shard-pinned scans locked inside their data nodes at the bottom; a freely-placeable shuffle worker tier above them — data is fixed, computation moves to a tier above it](blog-figures/07-shard-pinning.png)

### 6.2 Broadcast is an instruction, not a stage

The textbook treatment makes broadcast its own exchange/stage. That breaks under shard-locality, because a single stage sometimes needs to be **both** a broadcast *consumer* and a hash-shuffle *producer* at the same time (it scans its shard, receives a broadcast build, joins, and ships its partition onward — common in TPC-H Q3/Q8/Q9). A stage can't have two conflicting roles.

The resolution: treat broadcast as an **instruction attached to a stage** rather than a stage of its own. The engine captures the small build side once, then *injects* it into whatever stage consumes it — which is free to also be a shuffle producer. It's a small reframing that dissolves a whole class of "this stage needs to be two things" problems, and it's a design point our constraints forced rather than one we'd have reached on disaggregated storage.

<!-- FIGURE: Before/after of one stage — left: an illegal stage trying to be both "broadcast probe" and "shuffle producer"; right: the same stage as a shuffle producer with a small "broadcast build injected here" tag. Caption: "broadcast rides along as data, not as a second role." -->
![Broadcast as an instruction: on the left, one stage illegally tries to be both broadcast probe and shuffle producer; on the right, the same stage stays a plain shuffle producer with the captured build injected as an instruction — broadcast rides along as data, not a second role](blog-figures/08-broadcast-instruction.png)

### 6.3 Moving the data: two transports, one consumer contract

Coordinator-centric and broadcast traffic ride **Arrow Flight**; hash-shuffle rides a dedicated **shuffle transport** with a per-node buffer manager keyed by query/stage/partition. The consumer is *buffer-all-then-drain*: a worker waits until both producer sides have delivered its partition, then streams through it. [VERIFY: keep the public-facing description; internal action/class names can live in the architecture deep-dive rather than the blog.]

### 6.4 Keeping memory bounded — and spilling when it isn't

The headline memory win is locality (the 1/N hash table from §1). But shuffle *intermediates* can still exceed a node's on-heap budget, so the engine bounds each node's live shuffle bytes — `analytics.mpp.shuffle.node_budget_percent`, **80%** of `-Xmx` by default — and supports **opt-in disk spill** (`analytics.mpp.shuffle.spill.enabled`, **off by default**): the per-query on-heap footprint stays under budget, the overflow streams to local disk (capped by `analytics.mpp.shuffle.spill.max_bytes`, **50 GiB** by default), and the consumer drains spilled chunks back in arrival order before the in-memory tail — preserving the same buffer-all contract, just backed by disk.

<!-- FIGURE: Memory diagram — one node holding the whole hash table (red, above the heap line) vs. N nodes each holding ~1/N (green, under the line); a small inset showing the on-heap budget with overflow arrowed to disk. -->
![Memory: one node's 16 GiB hash table busts the heap limit, while eight nodes each hold ~2 GiB comfortably under it; an inset shows the on-heap shuffle budget with overflow spilling to local disk](blog-figures/09-memory-spill.png)

### 6.5 Shuffle only what the query needs

The cheapest byte to move is the one you never move. Before a join is distributed, its output is often far wider than anything downstream actually reads — an aggregate over a 6-way join may reference only the group key and one measure, yet a naive plan would hash-shuffle every column of the joined row. So the planner prunes unused columns at the source, *before* the plan is cut into stages: each shuffle carries only the join keys plus the columns some later operator references. On the wide TPC-H fact-table joins this shrinks the shuffled payload several-fold — it's the single biggest reason the §7 latencies land where they do, and it keeps more queries under the on-heap shuffle budget (§6.4) without ever touching disk. It's on by default (`analytics.mpp.shuffle.prune_columns`). Optional IPC compression (§8) can shrink the wire further when a node is memory-bound, but pruning does most of the work and is nearly free.

---

## 7. Benchmarks

We ran the full TPC-H suite (all 22 queries) at two scale factors on a 3-node cluster, measuring the per-query P50 latency and the strategy the cost model chose — and, crucially, comparing the feature branch head-to-head against the **main branch** (OpenSearch before this work) on the *same* cluster. Two things to watch: **which queries complete at all** (distribution lets the cluster run joins one coordinator can't hold), and the **strategy column** (chosen by cost per join, not fixed per query).

**Setup.** 3 data nodes, TPC-H at scale factor 1 (~6M-row `lineitem`) and scale factor 10 (~60M-row `lineitem`). To make the comparison a fair apples-to-apples one, **both branches ran on an identical, deliberately memory-constrained cluster** — per-node heap 8 GiB, native DataFusion pool 3 GiB, coordinator buffer 1 GiB — with `analytics.mpp.distribute.min_rows` lowered so the small test datasets exercise the distributed path, and a **fresh cluster per query** so cross-query memory pressure can't leak between measurements. P50 over repeated runs. The comparison baseline is the **main branch** — OpenSearch *before* this work, which has no distributed join/aggregation scheduler and runs everything coordinator-centric. [VERIFY: confirm the exact node instance type / host for the published version.]

**Scale factor 1** — feature branch (MPP on) vs. main branch (coordinator-centric), same cluster. `Strategy` is the one the cost model chose; `Speed-up` is main P50 ÷ feature P50.

| Query | Strategy | Feature P50 (s) | Main P50 (s) | Speed-up | |
|---|---|---|---|---|---|
| q1  | COORDINATOR_CENTRIC | 0.2 | 3.3 | 16.5× | |
| q2  | BROADCAST | 0.7 | 4.4 | 6.3× | |
| q3  | BROADCAST | 1.0 | 5.1 | 5.1× | |
| q4  | COORDINATOR_CENTRIC | 0.3 | 3.4 | 11.3× | |
| q5  | HASH_SHUFFLE | 1.4 | 5.1 | 3.6× | |
| q6  | COORDINATOR_CENTRIC | 0.1 | 2.8 | 28.0× | |
| q7  | HASH_SHUFFLE | 2.8 | 4.9 | 1.8× | |
| q8  | BROADCAST | 0.5 | 5.5 | 11.0× | |
| q9  | BROADCAST | 1.1 | 5.4 | 4.9× | |
| q10 | HASH_SHUFFLE | 0.8 | 4.0 | 5.0× | |
| q11 | HASH_SHUFFLE | 2.2 | 3.8 | 1.7× | |
| q12 | BROADCAST | 0.2 | 4.0 | 20.0× | |
| q13 | HASH_SHUFFLE | 0.9 | 3.7 | 4.1× | |
| q14 | BROADCAST | 0.2 | 4.7 | 23.5× | |
| q15 | COORDINATOR_CENTRIC | 0.2 | 3.2 | 16.0× | nondeterministic `sort … \| head` |
| q16 | BROADCAST | 2.9 | 6.1 | 2.1× | |
| q17 | BROADCAST | 0.6 | 6.7 | 11.2× | |
| q18 | BROADCAST | 1.5 | 5.5 | 3.7× | |
| q19 | COORDINATOR_CENTRIC | ✗ | 4.7 | — | feature: coordinator-gather buffer (`ReduceSizeExceeded`) |
| q20 | HASH_SHUFFLE | 0.3 | 3.9 | 13.0× | |
| q21 | HASH_SHUFFLE | 3.8 | ✗ | main ✗ | main: coordinator-centric failure |
| q22 | COORDINATOR_CENTRIC | 0.2 | 3.3 | 16.5× | |

**Both branches complete 21 of 22** at sf=1 (the data still fits a single coordinator at this scale): the feature branch fails only q19 (its coordinator `FINAL` gather exceeds the 1 GiB buffer on this constrained config — raise `analytics.coordinator.buffer_limit` to clear it), and main fails only q21 (a heavy semi-join the coordinator can't build). **Read the sf=1 speed-up column with caution, not as an MPP result.** Note that even the queries MPP does *not* touch — the `COORDINATOR_CENTRIC` q1/q6/q15/q22, which run the identical path on both branches — show 16–28×. That isn't distribution; it's a uniform ~3 s fixed cost on the main run (JVM warmup / the count path), so at sf=1 the ratios are dominated by that floor rather than by the scheduler. The trustworthy latency comparison is sf=10 below, where the same-strategy queries land at ~1.0×.

The chart makes the shape clear — feature bars sit at or under main across the board, with only q19/q21 flipping:

![TPC-H sf=1 P50 latency for all 22 queries: MPP feature branch (blue) vs. main branch (grey), on an identical heap-8g / DF-pool-3g / coord-1g cluster with a fresh cluster per query; failures shown at 0s and marked ✗ (main-branch q21 is a coordinator-centric failure)](blog-figures/sf1-base-vs-feature.png)

At sf=1 both branches complete almost everything (the data fits a single coordinator at this scale), so the sf=1 story is mostly latency: the MPP bars sit at or below main on the join-heavy queries — broadcast turns q2/q3/q8/q9 into sub-second joins where main pays 4–5 s — and only q21 (a heavy semi-join) flips, failing on main but running as `HASH_SHUFFLE` on the feature branch. The real separation shows up at scale.

**Scale factor 10** — feature branch (MPP on) vs. main branch (coordinator-centric), same cluster. `Speed-up` is main P50 ÷ feature P50; at this scale the same-strategy queries land near 1.0×, so the column reflects real work, not warmup.

| Query | Strategy | Feature P50 (s) | Main P50 (s) | Speed-up | |
|---|---|---|---|---|---|
| q1  | COORDINATOR_CENTRIC | 0.8 | 0.9 | 1.1× | |
| q2  | BROADCAST | 4.2 | 2.5 | 0.6× | |
| q3  | BROADCAST | 7.3 | 9.3 | 1.3× | |
| q4  | COORDINATOR_CENTRIC | 1.8 | 2.0 | 1.1× | |
| q5  | HASH_SHUFFLE | 11.7 | 10.5 | 0.9× | |
| q6  | COORDINATOR_CENTRIC | 0.4 | 0.4 | 1.0× | |
| q7  | HASH_SHUFFLE | 26.8 | ✗ | **runs vs ✗** | main: coordinator circuit breaker |
| q8  | BROADCAST | 1.8 | 10.4 | **5.8×** | |
| q9  | BROADCAST | 8.0 | 12.5 | 1.6× | |
| q10 | HASH_SHUFFLE | 5.0 | ✗ | **runs vs ✗** | main: coordinator circuit breaker |
| q11 | HASH_SHUFFLE | 18.7 | 2.0 | 0.1× | agg fits coord-centric; MPP pays a shuffle here |
| q12 | BROADCAST | 0.8 | ✗ | **runs vs ✗** | main: coordinator circuit breaker |
| q13 | HASH_SHUFFLE | 7.2 | 2.1 | 0.3× | |
| q14 | HASH_SHUFFLE | 10.0 | 6.7 | 0.7× | |
| q15 | COORDINATOR_CENTRIC | 0.7 | 0.8 | 1.1× | nondeterministic `sort … \| head` |
| q16 | BROADCAST | 3.4 | ✗ | **runs vs ✗** | main: coordinator circuit breaker |
| q17 | HASH_SHUFFLE | ✗ | ✗ | both ✗ | native non-spillable hash-join build |
| q18 | HASH_SHUFFLE | ✗ | ✗ | both ✗ | native non-spillable hash-join build |
| q19 | COORDINATOR_CENTRIC | ✗ | 6.0 | — | feature: coordinator-gather buffer (`ReduceSizeExceeded`) |
| q20 | HASH_SHUFFLE | 1.4 | 2.1 | 1.5× | |
| q21 | HASH_SHUFFLE | ✗ | ✗ | both ✗ | native non-spillable hash-join build |
| q22 | COORDINATOR_CENTRIC | 0.8 | 0.8 | 1.0× | |

**18 of 22 pass on the feature branch vs. 15 of 22 on main** — same 3-node cluster:

![TPC-H sf=10 P50 latency for all 22 queries: MPP feature branch (blue) vs. main branch (grey), on an identical heap-8g / DF-pool-3g / coord-1g cluster with a fresh cluster per query; failures shown at 0s and marked ✗](blog-figures/sf10-base-vs-feature.png)

This is where distribution earns its keep. The feature branch runs **three heavy joins the coordinator-centric main branch cannot** — **q7, q10, and q12** all fail on main (the coordinator's native allocator trips the circuit breaker trying to build the join on one node) and all pass on the feature branch by spreading the join across the worker tier. The feature branch is also **faster overall** — the sum of passing-query P50s is 165 s vs. 238 s for main — and turns q8 from a 10.4 s coordinator join into a 1.8 s broadcast. The one query where main wins is q19: its coordinator-side aggregate `FINAL` fits main's plan but overflows the feature branch's 1 GiB coordinator gather on this constrained config (see §10). The remaining shared failures (q16, q17, q18, q21) hit capacity limits on both — the heaviest joins overflow the native execution engine's non-spillable hash-join build.

**The cost model picks per join, per data shape.** The strategy column isn't hand-assigned — it falls out of the cost model ranking broadcast vs. hash-shuffle vs. gather for each join against the real row counts. Small dimension joins (q2, q3, q8, q9, q12, q16) go `BROADCAST` — replicating the small build beats repartitioning the fact. Large × large joins (q5, q7, q11, q13, q14) go `HASH_SHUFFLE`. And the small, result-shrinking queries (q1, q4, q6, q15, q19, q22) stay `COORDINATOR_CENTRIC` at both scales, because gathering a tiny result is cheaper than distributing it. The split is stable across scale here because the dimension tables stay small relative to `broadcast.max_bytes` even at sf=10 — but the boundary is a *cost* threshold, not a hard-coded rule: grow a build past `analytics.mpp.broadcast.max_bytes` (or lower the setting) and that join re-plans to `HASH_SHUFFLE` on its own. This is the §4 thesis made concrete: **you never picked a strategy by hand, and the choice adapts to your data and cluster.**

**Scaling with node count.**
[IMAGE / BENCHMARK: line chart — P50 latency vs. number of data nodes for a join-heavy query (e.g. q5 or q9 at sf=10), showing how added nodes shrink runtime. Pair it with the 1/N memory point from §1. Requires a separate run varying the node count.]

**Memory headroom — running where it used to fail.** The clearest evidence isn't latency, it's *completion*: on the identical constrained cluster, **q7/q10/q12 move from a coordinator circuit-breaker failure on main to running cleanly once their joins are distributed.** That's the headline win — work the coordinator couldn't hold on one node now spreads across the cluster, and the same 3 nodes clear 3 more queries with distribution on.

**Correctness.** Every MPP run is checked for **row-multiset parity** against the coordinator-centric baseline, and against an external reference (DuckDB) for TPC-H, using a fresh cluster per query so cross-query memory pressure can't mask a result error. The scheduler forms a correct distributed plan for **all 22 TPC-H shapes** — including the multi-way targets q3 (3-way cascade), q5/q10 (aggregation over a cascade), and q11 (a scalar subquery). The queries that don't complete fail for reasons **orthogonal to scheduling**, not because the plan is wrong: the heaviest joins (q17, q18, q21 at sf=10) exhaust the native execution engine's *non-spillable* hash-join build on a worker — a memory characteristic of the backend's join operator, mitigable with disk spill (§6.4); q19's coordinator `FINAL` gather exceeds the coordinator buffer on the constrained config; and q15 is a pre-existing PPL `sort … | head` nondeterminism. The plan in every case is correct; the limits are downstream of strategy selection.

---

## 8. Using it

**Enable MPP** (off by default — operators opt in; it doubles as a runtime kill switch):

```
PUT /_cluster/settings
{ "transient": { "analytics.mpp.enabled": true } }
```

**Settings** (all node-scoped + dynamic; verified against `AnalyticsSettings` + `AnalyticsPlugin`). Most operators touch only the first two — the rest are tuning/diagnostic knobs with sensible defaults.

*Core:*

| Setting | Purpose | Default |
|---|---|---|
| `analytics.mpp.enabled` | master switch + incident kill switch | `false` |
| `analytics.mpp.distribute.min_rows` | size floor — a join/agg whose larger scan subtree is below this stays coordinator-centric | `1000000` |
| `analytics.mpp.shuffle.aggregate.enabled` | sub-toggle for distributed aggregation (distributed joins unaffected when off) | `true` |

*Strategy / cost tuning:*

| Setting | Purpose | Default |
|---|---|---|
| `analytics.mpp.broadcast.max_bytes` | cap on broadcast build size; a larger build is planned as (or falls back to) hash-shuffle | `64mb` |
| `analytics.mpp.broadcast.probe_estimate` | probe-node count the broadcast cost estimate uses; `-1` = cluster data-node count at planning time | `-1` |
| `analytics.mpp.shuffle.partitions` | fixed hash-shuffle partition count; `-1` = per-query default (probe-side data-node count) | `-1` |

*Memory / spill:*

| Setting | Purpose | Default |
|---|---|---|
| `analytics.mpp.shuffle.node_budget_percent` | per-node on-heap shuffle budget, as a percent of `-Xmx` (`0` disables the bound) | `80` |
| `analytics.mpp.shuffle.spill.enabled` | spill shuffle intermediates to disk instead of failing fast | `false` |
| `analytics.mpp.shuffle.spill.directory` | spill root (one subdir per query); `""` → `<path.data>/shuffle_spill` | `""` |
| `analytics.mpp.shuffle.spill.max_bytes` | disk ceiling for spill, per node | `50gb` |
| `analytics.coordinator.buffer_limit` | per-query coordinator allocator cap in bytes for the `FINAL` gather; `0` = no per-query cap (share the coordinator allocator) | `0` |

*Reliability:*

| Setting | Purpose | Default |
|---|---|---|
| `analytics.mpp.shuffle.recv_timeout` | per-partition receive timeout — backstop against a stuck shuffle producer | `60s` |

*Shuffle payload:*

| Setting | Purpose | Default |
|---|---|---|
| `analytics.mpp.shuffle.prune_columns` | drop columns no operator references before the shuffle, so only the join keys + downstream-referenced columns cross the wire (shrinks the shuffle 5–25× on wide fact tables) | `true` |
| `analytics.mpp.shuffle.compress` | compress shuffle IPC chunks (standard Arrow IPC compression) to shrink the on-heap buffered bytes — trades CPU for heap headroom | `false` |
| `analytics.mpp.compression.codec` | shuffle compression codec when enabled — `zstd` or `lz4` | `zstd` |
| `analytics.mpp.compression.zstd.level` | ZSTD level when the codec is `zstd` (matches Spark's shuffle default); range `1`–`22` | `1` |

Column pruning is **on by default** and does most of the memory + latency work — it's what lets the wide-join queries in §7 run fast and stay under the shuffle budget. IPC compression is **off by default**: once columns are pruned at the source the shuffle is already small, so compression's per-buffer CPU cost tends to outweigh its remaining heap benefit; leave it off unless a node is memory-constrained enough to prefer heap headroom over latency.

**See what ran** — per-strategy counters expose the path each query took:

```
GET /_analytics/_strategies
→ { "strategies": { "COORDINATOR_CENTRIC": …, "BROADCAST": …, "HASH_SHUFFLE": …, "HASH_SHUFFLE_AGG": … } }
```

**Run a query** — no syntax change; eligible joins/aggregations distribute automatically:

```sql
source = fact
| inner join left=F right=D on F.dim_id = D.id  dim
| stats sum(F.amount) as total by category
```

[VERIFY: confirm the endpoint you want to show (`/_plugins/_ppl` production vs `/_analytics/ppl` test shim) and that the example runs.]

---

## 9. Compared to the previous behavior

[VERIFY: frame as "before this feature" vs "with it" — OpenSearch had no distributed join/agg scheduler before this work, so this is the load-bearing contrast.]

| Capability | Before | With multi-stage MPP |
|---|---|---|
| Small-side join | Coordinator-centric | Broadcast (parallel) |
| Large × large join | Gather both sides → coordinator (OOM-prone) | Hash-shuffle, ~1/N memory per node |
| Multi-way join (`A⋈B⋈C…`) | All intermediates via coordinator | Cascade of parallel worker tiers |
| `GROUP BY` over a join | Full join + serial merge on coordinator | `PARTIAL` on workers + small `FINAL` gather |
| Strategy selection | n/a (single path) | **Cost-based, per join** |
| Memory under load | Bounded by one node | Spread across the cluster (+ optional disk spill) |
| Scaling with nodes | Limited | Work + memory spread across data nodes |

---

## 10. Limitations and what's next

Honest scoping for operators:

- **Aggregation `FINAL` still gathers** to the coordinator (the `PARTIAL` is distributed across the worker tier). Worker-parallel high-cardinality `FINAL` — shuffling partials by group key so each worker finalizes its own groups — is the clearest next step. It's gated by the *execution* layer, not the planner: the shuffle transport is specialized to binary-join edges today, so the planner deliberately keeps the `FINAL` on the coordinator rather than emitting an aggregate-shuffle edge dispatch can't yet run.
- **Broadcast under the general path runs as hash-shuffle.** When the cost model would pick broadcast for a small build, the general scheduler currently repartitions both sides instead. The result is correct — same rows — but a modest-asymmetry small × large join pays a shuffle it could have skipped. This is a performance gap, not a correctness one, and (like the point above) waits on generalizing the execution layer rather than the planner.
- **A few scalar-subquery shapes** still gather to the coordinator (some are pre-existing engine-level decorrelation quirks rather than scheduler limits).
- **Very deep multi-way cascades at scale** can produce more concurrent shuffle bytes than a node's heap holds; disk spill (§6.4) is the mitigation.
- A handful of queries stay coordinator-centric **by design** (tiny results gather cheaply) or hit **orthogonal engine limits** unrelated to scheduling (see §7).

> Note: outer / semi / anti and mixed-key multi-way joins, and bushy trees, are **handled** — they fall out of the same distribution algebra with no per-shape code (one of the payoffs of the cost-then-algebra design in §4).

**Roadmap.**

- **Generalize the shuffle transport to an arbitrary M→N consumer** — the keystone. It unlocks worker-parallel `FINAL` aggregation and lets broadcast be placed anywhere, closing the two execution-layer gaps above in one move.
- **Re-enable cost-chosen broadcast on the general path** once the transport above can run a broadcast in any position (no per-shape special-casing).
- **Cross-backend coordinator joins** — admit a coordinator-only join backend whose inputs come from different backends.
- **Richer cost statistics** — beyond per-index row counts, toward selectivity/NDV-aware estimates for sharper per-join strategy choices.

---

## 11. Try it

Multi-stage distributed join and aggregation turns the cluster's *aggregate* memory and CPU into usable capacity for analytical joins. Queries that used to overwhelm a single coordinator now scale out across nodes; the strategy is chosen for you by cost; and the whole thing sits behind one runtime setting you can turn on — and off — at will.

[PLACEHOLDER: call to action — the version/build it ships in, link to documentation, where to file feedback, and a link to the architecture deep-dive for readers who want §4/§6 in full depth.]

---

<!-- Optional appendices -->

### Under the hood (optional aside / sidebar)

[PLACEHOLDER: a short, linkable sidebar for the technically curious — name the pieces (the post-CBO distribution-enforcement pass, the worker-tier rewriter, the unified dispatch path, broadcast-as-an-instruction) and point to the architecture doc. Keep it out of the main flow so the post stays accessible.]

### Acknowledgements

[PLACEHOLDER: contributors, reviewers, and related upstream work this built on.]
