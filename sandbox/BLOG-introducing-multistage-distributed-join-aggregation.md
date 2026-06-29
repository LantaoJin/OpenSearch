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

The headline memory win is locality (the 1/N hash table from §1). But shuffle *intermediates* can still exceed a node's on-heap budget, so the engine bounds each node's live shuffle bytes and supports **opt-in disk spill**: the per-query on-heap footprint stays under budget, the overflow streams to local disk, and the consumer drains spilled chunks back in arrival order before the in-memory tail — preserving the same buffer-all contract, just backed by disk. [VERIFY: name the exact settings + defaults — spill is off by default; node on-heap budget is a percent of heap; there's a disk ceiling.]

<!-- FIGURE: Memory diagram — one node holding the whole hash table (red, above the heap line) vs. N nodes each holding ~1/N (green, under the line); a small inset showing the on-heap budget with overflow arrowed to disk. -->
![Memory: one node's 16 GiB hash table busts the heap limit, while eight nodes each hold ~2 GiB comfortably under it; an inset shows the on-heap shuffle budget with overflow spilling to local disk](blog-figures/09-memory-spill.png)

---

## 7. Benchmarks

> Every number below is a placeholder. Fill from a measured run; do not estimate.

**Setup.** [BENCHMARK: cluster shape — node count, instance type, heap + direct memory per node; dataset = TPC-H at scale factor [e.g. sf=10] with table row counts; the two conditions = MPP-off (coordinator-centric baseline) vs MPP-on (multi-stage).]

**Latency — MPP-off vs MPP-on (selected join / agg queries).**

| Query | Shape | MPP-off | MPP-on | Speedup |
|---|---|---|---|---|
| Q3  | 3-way join | [BENCHMARK] | [BENCHMARK] | [BENCHMARK] |
| Q5  | join + agg | [BENCHMARK] | [BENCHMARK] | [BENCHMARK] |
| Q10 | join + agg | [BENCHMARK] | [BENCHMARK] | [BENCHMARK] |
| …   | … | … | … | … |

[VERIFY: pull the query set + which queries are MPP-eligible vs. coordinator-centric-by-design from the TPC-H harness.]

**Scaling with node count.**
[IMAGE / BENCHMARK: line chart — latency (or throughput) vs. number of data nodes for a join-heavy query, showing how added nodes shrink runtime. This is the "it scales out" story; pair it with the 1/N memory point from §1.]

**Memory headroom — running where it used to fail.**
[IMAGE / BENCHMARK: bar chart — peak coordinator heap, MPP-off vs MPP-on, for a query that OOMs the coordinator without MPP. The most compelling chart in the post: "this query did not run before."]

**Correctness.** Every MPP run is checked for **row-multiset parity** against the coordinator-centric baseline, and against an external reference for TPC-H, using a fresh cluster per query so cross-query memory pressure can't mask a result error. [VERIFY: state X/22 TPC-H queries passing and that the non-passes are orthogonal engine limits, not scheduler bugs.]

---

## 8. Using it

**Enable MPP** (off by default — operators opt in; it doubles as a runtime kill switch):

```
PUT /_cluster/settings
{ "transient": { "analytics.mpp.enabled": true } }
```

**Key settings** [VERIFY every name + default against `AnalyticsSettings`]:

| Setting | Purpose | Default |
|---|---|---|
| `analytics.mpp.enabled` | master switch + incident kill switch | `false` |
| `analytics.mpp.distribute.min_rows` | size floor — joins/aggs below this stay coordinator-centric | [VERIFY: 1,000,000] |
| `analytics.mpp.shuffle.aggregate.enabled` | sub-toggle for distributed aggregation (joins unaffected when off) | `true` |
| `analytics.mpp.shuffle.spill.enabled` | spill shuffle intermediates to disk | `false` |
| `analytics.mpp.broadcast.max_bytes` | cap on broadcast build size | [VERIFY: 64 MiB] |

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
| Scaling with nodes | Limited | [BENCHMARK: near-linear for join-heavy shapes] |

---

## 10. Limitations and what's next

Honest scoping for operators [VERIFY against the current "Known gaps" list]:

- **Outer / semi / anti multi-way cascades** — [VERIFY current support; some shapes still route coordinator-centric by design].
- **Aggregation `FINAL` still gathers** to the coordinator (the `PARTIAL` is distributed). Worker-parallel high-cardinality `FINAL` — shuffling partials by group key so each worker finalizes its own groups — is the clearest next step; it's gated today by the shuffle transport being specialized to binary joins, not the planner.
- **Some scalar-subquery shapes** still gather. [VERIFY with specifics.]
- A handful of queries stay coordinator-centric **by design** (tiny results) or hit **orthogonal engine limits** unrelated to scheduling.

**Roadmap.** [PLACEHOLDER: 3–4 bullets — generalize the shuffle transport to an arbitrary M→N consumer (unlocking worker-parallel final aggregation), broaden outer-join cascades, cross-backend coordinator joins, richer cost statistics.]

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
