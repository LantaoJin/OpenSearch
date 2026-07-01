#!/usr/bin/env bash
# Reconstruct a manual 3-node TPC-H cluster (sf=1 OR sf=10) from the surviving testcluster distro.
# Clones the complete (plugins-installed) distro into 3 node dirs and configures each. The two scales
# coexist on isolated ports/cluster-names/heaps; pick one with --sf.
#
# Idempotent-ish: refuses to clobber existing node dirs (rm them first to rebuild). Run from anywhere;
# paths are absolute. Does NOT start the nodes (use start-node.sh per node, or the launch line printed).
#
# Usage:  ./setup_cluster.sh --sf 1      # tpch-sf1-node{,-1,-2},  http 9210/1/2, 8g heap, DF pool 3g
#         ./setup_cluster.sh --sf 10     # tpch-sf10-node{,-1,-2}, http 9200/1/2, 12g heap, DF pool 4g
set -euo pipefail

SF=1
if [ "${1:-}" = "--sf" ]; then SF="${2:-}"; fi
case "$SF" in
  1)
    NODES=(tpch-sf1-node tpch-sf1-node-1 tpch-sf1-node-2)
    HTTP_PORTS=(9210 9211 9212); TRANSPORT_PORTS=(9310 9311 9312); NODE_NAMES=(sf1-0 sf1-1 sf1-2)
    CLUSTER_NAME=tpch-sf1
    HEAP=8g; MAXDIRECT=2g
    DF_POOL_LIMIT=3221225472   # 3 GiB native off-heap pool (sf=1)
    DF_POOL_MIN=1073741824     # 1 GiB
    COORD_BUFFER=4294967296    # 4 GiB coordinator buffer (q19)
    ;;
  10)
    NODES=(tpch-sf10-node tpch-sf10-node-1 tpch-sf10-node-2)
    HTTP_PORTS=(9200 9201 9202); TRANSPORT_PORTS=(9300 9301 9302); NODE_NAMES=(sf10-0 sf10-1 sf10-2)
    CLUSTER_NAME=tpch-sf10
    # heap 12g + the HAND-ADDED MaxDirectMemorySize=4g (CLAUDE.md: removing/raising it risks the
    # refault-storm freeze on the 61GB host).
    HEAP=12g; MAXDIRECT=4g
    # UNBOUNDED native pool (CLAUDE.md documents -Xmx12g + MaxDirect 4g + UNBOUNDED native for sf=10).
    # A 4 GiB pool cap was tried and FAILED — it trips HashJoinInput/pool exhaustion on the big joins
    # (q5/q17/q18). Empty → no datafusion.memory_pool_* lines emitted → native defaults to unbounded;
    # the 16 GiB swapfile + parent heap breaker are the safety net. Native disk spill is still configured.
    DF_POOL_LIMIT=""; DF_POOL_MIN=""
    COORD_BUFFER=2147483648    # 2 GiB coordinator buffer (q19/q20 gather ~1.074 GiB, over a 1 GiB cap; 4 GiB is too much of the 12g heap)
    ;;
  *)
    echo "FATAL: pass --sf 1 or --sf 10 (got '${SF}')"; exit 1 ;;
esac

# Derive sandbox/ from this script's own location (<repo>/sandbox/dev-tools/tpch/ → two levels up).
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SANDBOX="$(cd "$HERE/../.." && pwd)"
BUILD="$SANDBOX/qa/analytics-engine-rest/build"
DISTRO="$BUILD/testclusters/integTest-0/distro/3.8.0-INTEG_TEST"
NATIVE_LIB_DIR="$SANDBOX/libs/dataformat-native/rust/target/release"

[ -d "$DISTRO" ] || { echo "FATAL: distro not found at $DISTRO"; exit 1; }
[ -f "$NATIVE_LIB_DIR/libopensearch_native.so" ] || { echo "FATAL: native .so missing"; exit 1; }

SEED_HOSTS="[\"127.0.0.1:${TRANSPORT_PORTS[0]}\",\"127.0.0.1:${TRANSPORT_PORTS[1]}\",\"127.0.0.1:${TRANSPORT_PORTS[2]}\"]"
INITIAL_CM="[\"${NODE_NAMES[0]}\",\"${NODE_NAMES[1]}\",\"${NODE_NAMES[2]}\"]"

for i in 0 1 2; do
  N="${NODES[$i]}"
  DIR="$BUILD/$N"
  if [ -e "$DIR" ]; then
    echo "SKIP $N: $DIR already exists (rm -rf it to rebuild)"; continue
  fi
  echo "=== cloning distro → $N ==="
  cp -a "$DISTRO" "$DIR"
  mkdir -p "$DIR/data" "$DIR/logs" "$DIR/data/shuffle_spill" "$DIR/data/df_spill"

  # Native DataFusion pool cap: only emit the limit/min lines when DF_POOL_LIMIT is set (sf=1). For sf=10
  # the pool is UNBOUNDED (empty) — a cap trips HashJoinInput exhaustion on the big joins.
  POOL_LINES=""
  if [ -n "$DF_POOL_LIMIT" ]; then
    POOL_LINES="datafusion.memory_pool_limit_bytes: $DF_POOL_LIMIT
datafusion.memory_pool_min_bytes: $DF_POOL_MIN"
  fi

  # opensearch.yml — discovery + the persistent analytics settings. The GENERAL post-CBO scheduler is the
  # only MPP path now: MPP is gated by analytics.mpp.enabled + the analytics.mpp.distribute.min_rows size
  # floor. The old per-strategy toggles (shuffle.cascade.enabled / cbo_native_cascade /
  # aggregate_over_join.enabled) were DELETED — current code REJECTS them at boot, so they are NOT written
  # here. Hash-shuffle disk spill must be persistent in yml (a transient PUT doesn't reliably fire the
  # settings-update consumer); node_budget_percent=80 gives concurrent shuffle producers headroom before
  # REJECT_RETRY while spill still catches the truly-large shuffles.
  cat > "$DIR/config/opensearch.yml" <<YML
cluster.name: $CLUSTER_NAME
node.name: ${NODE_NAMES[$i]}
network.host: 127.0.0.1
http.port: ${HTTP_PORTS[$i]}
transport.port: ${TRANSPORT_PORTS[$i]}
path.data: $DIR/data
path.logs: $DIR/logs
discovery.seed_hosts: $SEED_HOSTS
cluster.initial_cluster_manager_nodes: $INITIAL_CM

cluster.pluggable.dataformat: composite
analytics.delegation.lucene.blocked_predicates: []
analytics.mpp.enabled: true
analytics.mpp.distribute.min_rows: 1
analytics.planner.prefer_metadata_driver: false
cluster.routing.rebalance.enable: none

analytics.mpp.shuffle.spill.enabled: true
analytics.mpp.shuffle.spill.directory: $DIR/data/shuffle_spill
analytics.mpp.shuffle.node_budget_percent: 80
analytics.coordinator.buffer_limit: $COORD_BUFFER

# Native DataFusion disk spill directory (enables DiskManagerMode::Directories). Pool cap (if any) below.
datafusion.spill_directory: $DIR/data/df_spill
$POOL_LINES
YML

  # jvm.options.d overlay — heap + the hand-added MaxDirectMemorySize (overlay so the stock jvm.options
  # is untouched).
  cat > "$DIR/config/jvm.options.d/tpch.options" <<JVM
-Xms$HEAP
-Xmx$HEAP
-XX:MaxDirectMemorySize=$MAXDIRECT
JVM

  # start-node.sh — the working flag set captured from the testcluster JVM args (arrow/netty/stream/
  # native-access/pluggable-dataformat + java.library.path to the .so). Heap/direct come from
  # jvm.options.d above; the experimental feature flags + add-opens must be on the launch line.
  cat > "$DIR/start-node.sh" <<SH
#!/usr/bin/env bash
# The testcluster distro has NO bundled JDK (-Dopensearch.bundled_jdk=false); point at system Corretto 25.
export OPENSEARCH_JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto
export OPENSEARCH_PATH_CONF="$DIR/config"
export OPENSEARCH_JAVA_OPTS="\
--add-modules=jdk.incubator.vector \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--enable-native-access=ALL-UNNAMED \
-Djava.library.path=$NATIVE_LIB_DIR \
-Dio.netty.tryUnsafe=true \
-Dio.netty.noUnsafe=false \
-Dio.netty.tryReflectionSetAccessible=true \
-Dio.netty.allocator.numDirectArenas=1 \
-Dopensearch.experimental.feature.transport.stream.enabled=true \
-Dopensearch.experimental.feature.pluggable.dataformat.enabled=true"
exec "$DIR/bin/opensearch"
SH
  chmod +x "$DIR/start-node.sh"
  echo "  configured $N (http ${HTTP_PORTS[$i]}, transport ${TRANSPORT_PORTS[$i]}, name ${NODE_NAMES[$i]})"
done

echo "=== DONE (sf=$SF). Launch: for n in ${NODES[*]}; do setsid bash $BUILD/\$n/start-node.sh > $BUILD/\$n/restart.out 2>&1 < /dev/null & disown; done ==="
echo "=== Cluster will be at http://localhost:${HTTP_PORTS[0]} ==="
