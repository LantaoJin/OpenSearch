import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

# sf=1, MPP on: (query, strategy, P50 seconds, failed?)
data = [
    ("q1",  "COORDINATOR_CENTRIC", 0.2, False),
    ("q2",  "BROADCAST",           1.4, False),
    ("q3",  "BROADCAST",           1.7, False),
    ("q4",  "COORDINATOR_CENTRIC", 0.3, False),
    ("q5",  "HASH_SHUFFLE",        3.3, False),
    ("q6",  "COORDINATOR_CENTRIC", 0.1, False),
    ("q7",  "HASH_SHUFFLE",       13.5, False),
    ("q8",  "BROADCAST",           0.9, False),
    ("q9",  "BROADCAST",           2.2, False),
    ("q10", "HASH_SHUFFLE",        0.8, False),
    ("q11", "HASH_SHUFFLE",        1.1, False),
    ("q12", "BROADCAST",           0.5, False),
    ("q13", "HASH_SHUFFLE",        0.6, False),
    ("q14", "HASH_SHUFFLE",        1.9, False),
    ("q15", "COORDINATOR_CENTRIC", 0.2, False),
    ("q16", "BROADCAST",           2.8, False),
    ("q17", "BROADCAST",           0.6, False),
    ("q18", "HASH_SHUFFLE",        0.0, True),
    ("q19", "COORDINATOR_CENTRIC", 0.9, False),
    ("q20", "HASH_SHUFFLE",        0.4, False),
    ("q21", "HASH_SHUFFLE",        0.0, True),
    ("q22", "COORDINATOR_CENTRIC", 0.2, False),
]

# Colors aligned with the blog figures' vocabulary
fill = {
    "COORDINATOR_CENTRIC": "#a7f3d0",  # green
    "BROADCAST":           "#fed7aa",  # orange
    "HASH_SHUFFLE":        "#93c5fd",  # blue
}
edge = {
    "COORDINATOR_CENTRIC": "#047857",
    "BROADCAST":           "#c2410c",
    "HASH_SHUFFLE":        "#1e3a5f",
}

queries = [d[0] for d in data]
vals    = [d[2] for d in data]
strats  = [d[1] for d in data]
failed  = [d[3] for d in data]

fig, ax = plt.subplots(figsize=(13, 5.5))
x = range(len(data))
bars = ax.bar(
    x, vals,
    color=[fill[s] for s in strats],
    edgecolor=[edge[s] for s in strats],
    linewidth=1.6,
    width=0.72,
    zorder=3,
)

# Mark the two failures with a hatch + edge emphasis and an "✗ fail" label
for i, (b, fail) in enumerate(zip(bars, failed)):
    if fail:
        b.set_hatch("////")
        b.set_edgecolor("#b91c1c")
        b.set_linewidth(2.0)

# Value labels above each bar
for i, (b, v, fail) in enumerate(zip(bars, vals, failed)):
    cx = b.get_x() + b.get_width() / 2
    if fail:
        ax.text(cx, 0.18, "✗ fail", ha="center", va="bottom", fontsize=8.5,
                color="#b91c1c", fontweight="bold", zorder=4)
    else:
        ax.text(cx, v + 0.18, f"{v:g}", ha="center", va="bottom",
                fontsize=9, color="#374151", zorder=4)

ax.set_xticks(list(x))
ax.set_xticklabels(queries, fontsize=10)
ax.set_ylabel("P50 latency (seconds)", fontsize=12, color="#374151")
ax.set_title("TPC-H sf=1 with MPP on — latency per query, colored by the cost-chosen strategy",
             fontsize=14, color="#1e40af", pad=14)
ax.set_ylim(0, 15)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
ax.spines["left"].set_color("#cbd5e1")
ax.spines["bottom"].set_color("#cbd5e1")
ax.tick_params(colors="#64748b")
ax.yaxis.grid(True, color="#e2e8f0", zorder=0)
ax.set_axisbelow(True)

legend_elems = [
    Patch(facecolor=fill["COORDINATOR_CENTRIC"], edgecolor=edge["COORDINATOR_CENTRIC"], label="Coordinator-centric"),
    Patch(facecolor=fill["BROADCAST"],           edgecolor=edge["BROADCAST"],           label="Broadcast"),
    Patch(facecolor=fill["HASH_SHUFFLE"],        edgecolor=edge["HASH_SHUFFLE"],        label="Hash-shuffle"),
    Patch(facecolor="white", edgecolor="#b91c1c", hatch="////", label="Failed (native hash-join build)"),
]
ax.legend(handles=legend_elems, loc="upper right", frameon=False, fontsize=10.5)

note = ("Strategy is chosen by cost, not fixed per query: small/result-shrinking queries gather "
        "(coordinator-centric); a small build side broadcasts; large × large repartitions (hash-shuffle).")
fig.text(0.5, -0.02, note, ha="center", fontsize=10, color="#64748b")

plt.tight_layout()
out = "/workplace/ltjin/mustang/OpenSearch/sandbox/blog-figures/10-sf1-latency.png"
plt.savefig(out, dpi=200, bbox_inches="tight", facecolor="white")
print(out)
