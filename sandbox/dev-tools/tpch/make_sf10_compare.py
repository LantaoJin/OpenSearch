import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

# sf=10 P50 (seconds), on the SAME sf=1-aligned cluster config (heap 8g, DF pool 3g, coord 1g),
# 4 runs/query, fresh cluster per query. value, failed?  FAIL → P50 set to 0 + ✗ marker.
# PASS/FAIL taken verbatim from the report `result` column (== the report summary):
#   feature = dev-tools/tpch/per_query_stress_sf10_report_20260701-030052.md   (prune-on, compress-off; 18/22)
#   base    = dev-tools/tpch/per_query_stress_sf10_BASE-2bc7dc6_aligned_report.md (coord-centric, no MPP; 15/22)
queries = [f"q{i}" for i in range(1, 23)]

# feature branch, MPP on (prune-on) — report FAILs: q17,q18,q19,q21 → (0.0, 1)
on = {
    "q1":(0.8,0),"q2":(4.2,0),"q3":(7.3,0),"q4":(1.8,0),"q5":(11.7,0),"q6":(0.4,0),
    "q7":(26.8,0),"q8":(1.8,0),"q9":(8.0,0),"q10":(5.0,0),"q11":(18.7,0),"q12":(0.8,0),
    "q13":(7.2,0),"q14":(10.0,0),"q15":(0.7,0),"q16":(3.4,0),"q17":(0.0,1),"q18":(0.0,1),
    "q19":(0.0,1),"q20":(1.4,0),"q21":(0.0,1),"q22":(0.8,0),
}
# base 2bc7dc6, coordinator-centric (no MPP) — report FAILs: q7,q10,q12,q16,q17,q18,q21 → (0.0, 1)
main = {
    "q1":(0.9,0),"q2":(2.5,0),"q3":(9.3,0),"q4":(2.0,0),"q5":(10.5,0),"q6":(0.4,0),
    "q7":(0.0,1),"q8":(10.4,0),"q9":(12.5,0),"q10":(0.0,1),"q11":(2.0,0),"q12":(0.0,1),
    "q13":(2.1,0),"q14":(6.7,0),"q15":(0.8,0),"q16":(0.0,1),"q17":(0.0,1),"q18":(0.0,1),
    "q19":(6.0,0),"q20":(2.1,0),"q21":(0.0,1),"q22":(0.8,0),
}

YCAP = 30.0  # headroom for the tall q7 bar (feature 26.8s)

series = [
    ("MPP feature", on,   "#3b82f6", "#1e3a5f"),
    ("main branch", main, "#94a3b8", "#475569"),
]

fig, ax = plt.subplots(figsize=(15, 6))
n = len(series)
group_w = 0.82
bar_w = group_w / n

for si, (label, dat, fc, ec) in enumerate(series):
    xs, heights, raws, fails = [], [], [], []
    for qi, q in enumerate(queries):
        v, fail = dat[q]
        xs.append(qi + (si - (n - 1) / 2) * bar_w)
        heights.append(min(v, YCAP))
        raws.append(v)
        fails.append(fail)
    bars = ax.bar(xs, heights, width=bar_w * 0.92, color=fc, edgecolor=ec,
                  linewidth=1.0, zorder=3, label=label)
    for b, raw, fail in zip(bars, raws, fails):
        cx = b.get_x() + b.get_width() / 2
        if fail:
            # failed runs are plotted at 0; mark the baseline with an ✗
            ax.text(cx, 0.3, "✗", ha="center", va="bottom",
                    fontsize=9, color="#b91c1c", fontweight="bold", zorder=5)

ax.set_xticks(range(len(queries)))
ax.set_xticklabels(queries, fontsize=10)
ax.set_ylabel("P50 latency (seconds)", fontsize=12, color="#374151")
ax.set_title("TPC-H sf=10 — P50 latency by query: MPP feature branch vs. main",
             fontsize=15, color="#1e40af", pad=14)
ax.set_ylim(0, YCAP + 2.4)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
ax.spines["left"].set_color("#cbd5e1")
ax.spines["bottom"].set_color("#cbd5e1")
ax.tick_params(colors="#64748b")
ax.yaxis.grid(True, color="#e2e8f0", zorder=0)
ax.set_axisbelow(True)

handles = [Patch(facecolor=fc, edgecolor=ec, label=lbl) for lbl, _, fc, ec in series]
handles.append(Patch(facecolor="white", edgecolor="#b91c1c", hatch="////", label="✗ failed"))
ax.legend(handles=handles, loc="upper center", ncol=4, frameon=False, fontsize=11,
          bbox_to_anchor=(0.5, 1.0))

note = ("Cluster: heap 8g / DF pool 3g / coord buffer 1g, fresh cluster per query. Failed runs are shown at 0s and marked ✗.")
fig.text(0.5, -0.03, note, ha="center", fontsize=9, color="#64748b", wrap=True)

plt.tight_layout()
out = "/workplace/ltjin/mustang/OpenSearch/sandbox/blog-figures/12-sf10-base-vs-feature.png"
import os
os.makedirs(os.path.dirname(out), exist_ok=True)
plt.savefig(out, dpi=200, bbox_inches="tight", facecolor="white")
print(out)
