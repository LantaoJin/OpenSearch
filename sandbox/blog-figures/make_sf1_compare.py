import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

# sf=1 P50 (seconds). value, failed?  None = same as value but flag fail.
queries = [f"q{i}" for i in range(1, 23)]

# feature branch + mpp = ON  (FAIL → P50 set to 0)
on = {
    "q1":(0.2,0),"q2":(1.4,0),"q3":(1.7,0),"q4":(0.3,0),"q5":(3.3,0),"q6":(0.1,0),
    "q7":(13.5,0),"q8":(0.9,0),"q9":(2.2,0),"q10":(0.8,0),"q11":(1.1,0),"q12":(0.5,0),
    "q13":(0.6,0),"q14":(1.9,0),"q15":(0.2,0),"q16":(2.8,0),"q17":(0.6,0),"q18":(0.0,1),
    "q19":(0.9,0),"q20":(0.4,0),"q21":(0.0,1),"q22":(0.2,0),
}
# feature branch + mpp = OFF  (FAIL → P50 set to 0)
off = {
    "q1":(3.7,0),"q2":(4.0,0),"q3":(4.9,0),"q4":(3.5,0),"q5":(5.0,0),"q6":(3.0,0),
    "q7":(4.6,0),"q8":(5.5,0),"q9":(0.0,1),"q10":(4.0,0),"q11":(3.7,0),"q12":(4.0,0),
    "q13":(3.7,0),"q14":(4.8,0),"q15":(3.4,0),"q16":(5.1,0),"q17":(0.0,1),"q18":(5.7,0),
    "q19":(4.7,0),"q20":(3.9,0),"q21":(0.0,1),"q22":(3.3,0),
}
# main branch  (FAIL → P50 set to 0)
main = {
    "q1":(3.3,0),"q2":(4.4,0),"q3":(5.1,0),"q4":(3.4,0),"q5":(5.1,0),"q6":(2.8,0),
    "q7":(4.9,0),"q8":(5.5,0),"q9":(5.4,0),"q10":(4.0,0),"q11":(3.8,0),"q12":(4.0,0),
    "q13":(3.7,0),"q14":(4.7,0),"q15":(0.0,1),"q16":(6.1,0),"q17":(6.7,0),"q18":(5.5,0),
    "q19":(4.7,0),"q20":(3.9,0),"q21":(0.0,1),"q22":(3.3,0),
}

YCAP = 15.0  # headroom for the tall q7 bar

series = [
    ("feature + MPP on",  on,   "#3b82f6", "#1e3a5f"),
    ("feature + MPP off",  off,  "#f59e0b", "#b45309"),
    ("main branch",        main, "#94a3b8", "#475569"),
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
            ax.text(cx, 0.15, "✗", ha="center", va="bottom",
                    fontsize=9, color="#b91c1c", fontweight="bold", zorder=5)

ax.set_xticks(range(len(queries)))
ax.set_xticklabels(queries, fontsize=10)
ax.set_ylabel("P50 latency (seconds)", fontsize=12, color="#374151")
ax.set_title("TPC-H sf=1 — P50 latency by query: feature branch (MPP on / off) vs. main",
             fontsize=15, color="#1e40af", pad=14)
ax.set_ylim(0, YCAP + 1.2)
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

note = ("Failed runs are shown at 0s and marked ✗. "
        "Latencies across conditions are indicative, not a controlled A/B — read completion (✗) and strategy mix, not small deltas.")
fig.text(0.5, -0.02, note, ha="center", fontsize=9.5, color="#64748b")

plt.tight_layout()
out = "/workplace/ltjin/mustang/OpenSearch/sandbox/blog-figures/11-sf1-three-way.png"
plt.savefig(out, dpi=200, bbox_inches="tight", facecolor="white")
print(out)
