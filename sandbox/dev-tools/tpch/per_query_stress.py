#!/usr/bin/env python3
"""
Per-query TPC-H stress harness — the AUTHORITATIVE fresh-cluster-per-query sweep.

ONE harness for BOTH scales (the sf=1 / sf=10 split is now a `--sf` flag, not two files).
For EACH TPC-H query it relaunches a FRESH 3-node cluster, runs the query N times, and records
correctness vs the DuckDB ref + wall-time (P50) + heap trajectory. Fresh-cluster-per-query is the
whole point: the shared-cluster run_tpch.py over-reports OOM on later queries because one heavy
query's off-heap pressure poisons the rest. Isolation gives a CLEAN number per query.

Scale profiles (selected by --sf):
  sf=1  → nodes tpch-sf1-node{,-1,-2},  HTTP 9210/1/2, ref tpch_duckdb_ref_sf1.json,  coord buffer 4 GiB
  sf=10 → nodes tpch-sf10-node{,-1,-2}, HTTP 9200/1/2, ref tpch_duckdb_ref_sf10.json, coord buffer 1 GiB
The default run count differs by scale (sf=1: 1 = fast authoritative; sf=10: 10 = leak/stability stress);
override with --runs N.

Robustness (learned across sessions):
  - relaunch_missing_nodes(): a node launch occasionally hits a transient
    "java.io.tmpdir does not exist / Could not find or load main class" boot failure on ONE node,
    leaving the cluster red/2. Detect not-green, relaunch just the down node(s) before giving up.
  - apply_toggles() raises only analytics.coordinator.buffer_limit. The general-scheduler end-state
    removed the per-strategy MPP toggles (cascade.enabled / aggregate_over_join.enabled /
    cbo_native_cascade); including one makes the WHOLE atomic settings PUT fail 400, silently dropping
    the buffer raise. MPP is on via each node's opensearch.yml (analytics.mpp.enabled + distribute.min_rows).
  - 10k result cap: a sorted result larger than DEFAULT_MAX_ROWS returns the top-N prefix BY DESIGN
    (q16). It counts as a row-match iff it returns exactly the cap AND the ref exceeds it AND the first
    row matches — a correct sorted prefix, not a truncation defect. Noted 'capped'.

Run:  python3 per_query_stress.py --sf 1                 # all 22, 1 run each (fast, authoritative)
      python3 per_query_stress.py --sf 10                # all 22, 10 runs each (leak/stability stress)
      python3 per_query_stress.py --sf 10 --runs 1 q3 q9 # q3,q9 once each at sf=10
      python3 per_query_stress.py --sf 1 --no-stop q19   # keep the last query's cluster running
Report: ./per_query_stress_sf{1,10}_report.md  (next to this script)
"""
import json, os, sys, time, subprocess, urllib.request, urllib.error, math
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
# This script lives at <repo>/sandbox/dev-tools/tpch/, so sandbox/ is two levels up.
SANDBOX = os.path.normpath(os.path.join(HERE, "..", ".."))
QDIR = os.path.join(SANDBOX, "qa/analytics-engine-rest/src/test/resources/datasets/tpch/ppl")
BUILD = os.path.join(SANDBOX, "qa/analytics-engine-rest/build")

# Engine's default per-query result cap (RowProducingSink.DEFAULT_MAX_ROWS / OpenSearchTopKRewriter).
DEFAULT_MAX_ROWS = 10000

# ── Scale profiles ────────────────────────────────────────────────────────────────────────────────
SCALE_PROFILES = {
    1: {
        "nodes": ["tpch-sf1-node", "tpch-sf1-node-1", "tpch-sf1-node-2"],
        "ports": [9210, 9211, 9212],
        "ref": "tpch_duckdb_ref_sf1.json",
        "default_runs": 1,
        "coord_buffer": 4294967296,   # 4 GiB — q19's coord-centric gather trips the shipped 1 GiB by ~70 MB
    },
    10: {
        "nodes": ["tpch-sf10-node", "tpch-sf10-node-1", "tpch-sf10-node-2"],
        "ports": [9200, 9201, 9202],
        "ref": "tpch_duckdb_ref_sf10.json",
        "default_runs": 10,
        "coord_buffer": 4294967296,   # 4 GiB (aligned to sf=1 for the apples-to-apples run) — q19/q20 gather ~1.074 GiB at sf=10 (over a 1 GiB cap); 4 GiB is too much of the 12g heap
    },
}

# Populated by configure() once --sf is parsed.
SF = None
NODES = []
NODE_PORTS = {}
BASE = ""
REF = {}
COORD_BUFFER = 0
RUNS_PER_QUERY = 1
REPORT = ""
DISTRO_GREP = ""
MPP_ENABLED = True   # flipped to False by --no-mpp (coordinator-centric baseline); set BEFORE configure()


def configure(sf):
    global SF, NODES, NODE_PORTS, BASE, REF, COORD_BUFFER, RUNS_PER_QUERY, REPORT, DISTRO_GREP
    if sf not in SCALE_PROFILES:
        sys.exit(f"unknown --sf {sf}; choose one of {sorted(SCALE_PROFILES)}")
    p = SCALE_PROFILES[sf]
    SF = sf
    NODES = p["nodes"]
    NODE_PORTS = dict(zip(p["nodes"], p["ports"]))
    BASE = f"http://localhost:{p['ports'][0]}"
    REF = json.load(open(os.path.join(HERE, p["ref"])))
    COORD_BUFFER = p["coord_buffer"]
    RUNS_PER_QUERY = p["default_runs"]
    # Timestamp the report filename so each run keeps its own artifact instead of clobbering the last
    # (e.g. a 3-query re-run no longer overwrites a full 22-query sweep). Also refresh a stable
    # per_query_stress_sf{sf}_report.md symlink to the latest, for "the current report" convenience.
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    tag = "_mppoff" if not MPP_ENABLED else ""
    REPORT = os.path.join(HERE, f"per_query_stress_sf{sf}{tag}_report_{ts}.md")
    DISTRO_GREP = NODES[0] + r".*bootstrap.OpenSearch"


def sh(cmd, timeout=120):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)


def kill_cluster():
    sh(f"pkill -9 -f '{NODES[0]}.*bootstrap.OpenSearch' 2>/dev/null; true")
    time.sleep(5)
    sh(f"find {BUILD} -name '*.hprof' -delete 2>/dev/null; true")


def start_node(n):
    sh(f"setsid bash {BUILD}/{n}/start-node.sh > {BUILD}/{n}/restart.out 2>&1 < /dev/null & disown", timeout=15)


def start_cluster():
    for n in NODES:
        start_node(n)
    time.sleep(8)


def port_up(port):
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/_cluster/health", timeout=3) as resp:
            json.loads(resp.read().decode())
            return True
    except Exception:
        return False


def relaunch_missing_nodes():
    """Relaunch any node whose HTTP port is down (the transient tmpdir/port boot hiccup)."""
    relaunched = 0
    for n, port in NODE_PORTS.items():
        if not port_up(port):
            start_node(n)
            relaunched += 1
    if relaunched:
        time.sleep(8)
    return relaunched


def health():
    try:
        with urllib.request.urlopen(f"{BASE}/_cluster/health", timeout=3) as resp:
            d = json.loads(resp.read().decode())
            return d.get("status"), d.get("number_of_data_nodes")
    except Exception:
        return None, None


def wait_green(max_wait=300):
    deadline = time.time() + max_wait
    relaunch_attempts = 0
    while time.time() < deadline:
        st, n = health()
        if st == "green" and n == 3:
            return True
        # Coordinator up but not green-3 after a bit → a node likely failed to boot; relaunch it (≤2×).
        if relaunch_attempts < 2 and (time.time() - (deadline - max_wait)) > 30:
            if relaunch_missing_nodes():
                relaunch_attempts += 1
        time.sleep(6)
    return False


def _put_setting(key, value):
    body = json.dumps({"transient": {key: value}}).encode()
    try:
        req = urllib.request.Request(f"{BASE}/_cluster/settings", data=body, method="PUT",
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp.read()
    except Exception as e:
        # e.g. a base build that doesn't register analytics.mpp.enabled returns 400 — fine, skip it.
        print(f"  WARN: set {key}={value} failed: {e!r}", flush=True)


def apply_toggles():
    """Raise the coordinator buffer for the distributed-agg FINAL gather, and set analytics.mpp.enabled
    (dynamic) per fresh cluster — so --no-mpp runs the coordinator-centric baseline without editing the node
    yml. Sent as SEPARATE PUTs (not one atomic body): a cluster that doesn't register mpp.enabled (an old
    base build) rejects only that key while the buffer raise still applies."""
    _put_setting("analytics.coordinator.buffer_limit", COORD_BUFFER)
    _put_setting("analytics.mpp.enabled", MPP_ENABLED)


def heap_percents():
    try:
        with urllib.request.urlopen(f"{BASE}/_cat/nodes?h=heap.percent", timeout=5) as resp:
            return [int(x) for x in resp.read().decode().split()]
    except Exception:
        return []


def strategies():
    try:
        with urllib.request.urlopen(f"{BASE}/_analytics/_strategies", timeout=5) as resp:
            return json.loads(resp.read().decode()).get("strategies", {})
    except Exception:
        return {}


def _summarize_error(raw):
    """Pull a human-meaningful one-liner out of an OpenSearch error response. The body is JSON like
    {"error":{"reason":..,"details":..,"type":..},"status":N}; the raw first line is just '{', which made
    the report notes useless. Prefer details/reason/type; fall back to the raw text."""
    try:
        e = json.loads(raw).get("error", {})
        msg = e.get("details") or e.get("reason") or e.get("type") or ""
        # The interesting cause (CircuitBreaking / Resources exhausted / ReduceSizeExceeded / a native
        # planning error) is often nested in details; keep it but trim.
        return (msg or raw).replace("\n", " ").strip()[:140]
    except Exception:
        return raw.replace("\n", " ").strip()[:140]


def run_ppl(q, timeout=150):
    body = json.dumps({"query": q}).encode()
    r = urllib.request.Request(f"{BASE}/_plugins/_ppl", data=body, method="POST",
                               headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return time.time() - t0, json.loads(resp.read().decode()), None
    except urllib.error.HTTPError as e:
        return time.time() - t0, None, _summarize_error(e.read().decode())
    except Exception as e:
        return time.time() - t0, None, repr(e).replace("\n", " ")[:140]


def percentile(values, p):
    """Linear-interpolated percentile over a list of numbers; None if empty. p in [0,100]."""
    xs = sorted(v for v in values if v is not None)
    if not xs:
        return None
    if len(xs) == 1:
        return xs[0]
    rank = (p / 100.0) * (len(xs) - 1)
    lo = int(math.floor(rank))
    hi = int(math.ceil(rank))
    if lo == hi:
        return xs[lo]
    return xs[lo] + (xs[hi] - xs[lo]) * (rank - lo)


def num_close(a, b, rel=1e-4, absol=1e-2):
    try:
        a, b = float(a), float(b)
    except (TypeError, ValueError):
        return str(a) == str(b)
    if math.isnan(a) or math.isnan(b):
        return math.isnan(a) and math.isnan(b)
    return abs(a - b) <= max(absol, rel * max(abs(a), abs(b)))


def _norm_str(v):
    s = str(v)
    if s.endswith(" 00:00:00"):
        s = s[: -len(" 00:00:00")]
    return s


def first_row_ok(schema, datarows, ref_first):
    if ref_first is None:
        return len(datarows) == 0
    if not datarows:
        return False
    names = [c["name"] if isinstance(c, dict) else c for c in schema]
    row = dict(zip(names, datarows[0]))
    for col, refv in ref_first.items():
        if col not in row:
            return False
        ev = row[col]
        if isinstance(refv, (int, float)) and not isinstance(refv, bool):
            if not num_close(ev, refv):
                return False
        elif _norm_str(ev) != _norm_str(refv):
            return False
    return True


def stress_query(n):
    qf = f"{QDIR}/q{n}.ppl"
    if not os.path.exists(qf):
        return {"q": n, "status": "MISSING_FILE"}
    q = open(qf).read().strip()
    ref = REF.get(str(n), {})
    ref_rows = ref.get("rows")
    ref_first = ref.get("first")

    kill_cluster()
    start_cluster()
    if not wait_green():
        return {"q": n, "status": "CLUSTER_BOOT_FAILED"}
    apply_toggles()

    runs = []
    heaps = []
    fired = "-"
    for i in range(RUNS_PER_QUERY):
        before = strategies()
        took, j, err = run_ppl(q)
        after = strategies()
        delta = {k: after.get(k, 0) - before.get(k, 0) for k in after}
        fired = ",".join(k for k, v in delta.items() if v > 0) or "-"
        st, dn = health()
        cluster_up = st is not None
        hp = heap_percents() if cluster_up else []
        if hp:
            heaps.append(max(hp))
        if err:
            runs.append({"run": i + 1, "ok": False, "err": err.splitlines()[0][:90] if err else "",
                         "took": round(took, 1), "cluster_up": cluster_up})
        else:
            schema = j.get("schema", [])
            datarows = j.get("datarows", [])
            rows = len(datarows)
            capped_ok = (ref_rows is not None and ref_rows > DEFAULT_MAX_ROWS and rows == DEFAULT_MAX_ROWS)
            runs.append({
                "run": i + 1, "ok": True, "rows": rows,
                "rowmatch": (rows == ref_rows or capped_ok),
                "capped": capped_ok,
                "firstmatch": first_row_ok(schema, datarows, ref_first),
                "took": round(took, 1), "cluster_up": cluster_up,
            })
        if not cluster_up:
            break  # cluster died — stop hammering

    ok_runs = [r for r in runs if r.get("ok")]
    n_ok = len(ok_runs)
    n_rowmatch = sum(1 for r in ok_runs if r.get("rowmatch"))
    n_firstmatch = sum(1 for r in ok_runs if r.get("firstmatch"))
    n_correct = sum(1 for r in ok_runs if r.get("rowmatch") and r.get("firstmatch"))
    cluster_survived = all(r.get("cluster_up") for r in runs) and len(runs) == RUNS_PER_QUERY
    # Heap band + crude leak signal (first-third vs last-third mean) over the runs.
    heap_trend = "n/a"
    if len(heaps) >= 2:
        third = max(1, len(heaps) // 3)
        first_mean = sum(heaps[:third]) / third
        last_mean = sum(heaps[-third:]) / third
        heap_trend = f"{min(heaps)}-{max(heaps)}% (first~{round(first_mean)} last~{round(last_mean)})"
    elif heaps:
        heap_trend = f"{min(heaps)}-{max(heaps)}%"
    # Wall-time of the query round-trip over SUCCESSFUL runs (excludes cluster boot). The FIRST run on
    # a fresh cluster pays cold-start costs the steady state never repeats — JIT warmup, class-loading
    # the compression codec, dlopen of the native lz4/zstd libs on first compress. With >=2 successful
    # runs, drop run 1 and report the P50 of the WARM runs (the representative steady-state latency);
    # with a single run, use it as-is.
    ok_timed = [r["took"] for r in ok_runs if "took" in r]
    warm = ok_timed[1:] if len(ok_timed) >= 2 else ok_timed
    timed = warm or ([r["took"] for r in runs if "took" in r])
    took_p50 = percentile(timed, 50)
    return {
        "q": n, "status": "RAN", "ref_rows": ref_rows, "strategy": fired,
        "n_runs": len(runs), "n_ok": n_ok, "n_rowmatch": n_rowmatch, "n_firstmatch": n_firstmatch,
        "n_correct": n_correct, "cluster_survived": cluster_survived, "heap_trend": heap_trend,
        "took_p50": took_p50, "took_n": len(timed), "runs": runs,
    }


def fmt_report(results):
    lines = []
    mode = "MPP ON (general scheduler)" if MPP_ENABLED else "MPP OFF (coordinator-centric baseline)"
    lines.append(f"# Per-query sf={SF} stress report — {mode} (authoritative — fresh cluster per query)")
    lines.append("")
    lines.append(f"Each query run {RUNS_PER_QUERY}x in its OWN fresh 3-node sf={SF} cluster (relaunched per query).")
    lines.append(f"Compared vs DuckDB sf={SF} ref (row count + first row). analytics.mpp.enabled={str(MPP_ENABLED).lower()}. "
                 f"Coordinator buffer {COORD_BUFFER // (1024**3)} GiB. Heap = max across nodes per run. "
                 "wall = P50 of the query round-trip over WARM runs (run 1 dropped when >=2 runs, "
                 "to exclude JIT/codec/native-lib cold start; excludes per-query cluster boot).")
    lines.append(f"A sorted result larger than the {DEFAULT_MAX_ROWS:,}-row engine cap passes when it returns the "
                 f"correct top-{DEFAULT_MAX_ROWS:,} prefix (first row matches) — noted 'capped'.")
    lines.append("")
    lines.append("| q | result | correct/runs | strategy | ok | rowmatch | firstmatch | wall P50 (s) | survived | heap band | notes |")
    lines.append("|---|--------|--------------|----------|----|----------|------------|--------------|----------|-----------|-------|")
    npass = 0
    nran = 0
    for r in results:
        if r["status"] != "RAN":
            lines.append(f"| q{r['q']} | {r['status']} | - | - | - | - | - | - | - | - | - |")
            continue
        nran += 1
        passed = r["n_correct"] == r["n_runs"] and r["cluster_survived"]
        if passed:
            npass += 1
        note = ""
        if not r["cluster_survived"]:
            note = "CLUSTER DIED mid-run"
        elif r["n_ok"] < r["n_runs"]:
            errs = [run.get("err", "") for run in r["runs"] if not run.get("ok")]
            note = (errs[0] if errs else "exec fail")[:120]
        elif r["n_rowmatch"] < r["n_ok"]:
            note = f"row mismatch (got vs ref {r['ref_rows']})"
        elif r["n_firstmatch"] < r["n_ok"]:
            note = "first-row mismatch"
        elif passed and any(run.get("capped") for run in r["runs"] if run.get("ok")):
            note = f"capped at {DEFAULT_MAX_ROWS:,} (ref {r['ref_rows']:,}; correct top-N prefix, by design)"
        survived = "yes" if r["cluster_survived"] else "**NO**"
        took_str = f"{r['took_p50']:.1f}" if r.get("took_p50") is not None else "-"
        lines.append(
            f"| q{r['q']} | {'PASS' if passed else 'FAIL'} | {r['n_correct']}/{r['n_runs']} | "
            f"{r['strategy']} | {r['n_ok']}/{r['n_runs']} | {r['n_rowmatch']}/{r['n_ok']} | "
            f"{r['n_firstmatch']}/{r['n_ok']} | {took_str} | {survived} | {r['heap_trend']} | {note} |"
        )
    lines.append("")
    total_wall = sum(r["took_p50"] for r in results if r.get("took_p50") is not None)
    lines.append(f"**PASS {npass}/{nran} ran** ({len(results)} requested) · {RUNS_PER_QUERY} runs/query · sum of P50s {total_wall:.1f}s")
    lines.append("")
    # Leak / stability callouts (most useful at sf=10, --runs 10).
    lines.append("## Leak / stability callouts")
    any_call = False
    for r in results:
        if r["status"] == "RAN" and not r["cluster_survived"]:
            lines.append(f"- **q{r['q']}: cluster DIED** after {r['n_runs']} run(s) — single-query OOM or crash.")
            any_call = True
    if not any_call:
        lines.append("- No cluster deaths; every query that ran kept the cluster up across all its runs.")
    return "\n".join(lines)


def write_report(results):
    """Write the timestamped report and refresh the stable per_query_stress_sf{sf}[_mppoff]_report.md symlink → it."""
    open(REPORT, "w").write(fmt_report(results))
    tag = "_mppoff" if not MPP_ENABLED else ""
    latest = os.path.join(HERE, f"per_query_stress_sf{SF}{tag}_report.md")
    try:
        if os.path.islink(latest) or os.path.exists(latest):
            os.remove(latest)
        os.symlink(os.path.basename(REPORT), latest)
    except OSError:
        pass  # symlink is a convenience; the timestamped file is the source of truth


def main():
    global RUNS_PER_QUERY, MPP_ENABLED
    argv = list(sys.argv[1:])
    no_stop = "--no-stop" in argv
    argv = [a for a in argv if a != "--no-stop"]
    # --no-mpp: coordinator-centric baseline (analytics.mpp.enabled=false per fresh cluster). Set BEFORE
    # configure() so the report filename/symlink get the _mppoff tag.
    MPP_ENABLED = "--no-mpp" not in argv
    argv = [a for a in argv if a != "--no-mpp"]

    sf = 1
    if "--sf" in argv:
        i = argv.index("--sf")
        sf = int(argv[i + 1])
        del argv[i:i + 2]
    configure(sf)

    if "--runs" in argv:
        i = argv.index("--runs")
        RUNS_PER_QUERY = int(argv[i + 1])
        del argv[i:i + 2]

    qnums = [int(x.lstrip("q")) for x in argv] if argv else list(range(1, 23))
    results = []
    try:
        for n in qnums:
            print(f"=== stressing q{n} (fresh sf={SF} cluster) ===", flush=True)
            res = stress_query(n)
            results.append(res)
            print(
                f"  q{n}: {res.get('status')} correct={res.get('n_correct')}/{res.get('n_runs')} "
                f"ok={res.get('n_ok')}/{res.get('n_runs')} p50={res.get('took_p50')}s "
                f"strategy={res.get('strategy')} survived={res.get('cluster_survived')} heap={res.get('heap_trend')}",
                flush=True,
            )
            write_report(results)
        write_report(results)
        print(f"\nReport: {REPORT}", flush=True)
    finally:
        if not no_stop:
            kill_cluster()
            print("=== cluster stopped; pass --no-stop to keep the last query's cluster running ===", flush=True)
        else:
            print("=== --no-stop: leaving last query's cluster running ===", flush=True)


if __name__ == "__main__":
    main()
