#!/usr/bin/env bash
# Retrieval benchmark harness driver. Builds the module (and the reactor modules it depends on) once, then runs the
# requested stage with the classpath Maven resolved, so a JMH fork sees exactly what the reactor compiled against.
#
#   benchmarks/bin/retrieval-bench.sh generate   [-Darcadedb.retrievalBench.records=5000000] ...
#   benchmarks/bin/retrieval-bench.sh embedded   [-Darcadedb.retrievalBench.mode=warm|cold|profile] ...
#   benchmarks/bin/retrieval-bench.sh http       [-Darcadedb.retrievalBench.threads=1,8,32] ...
#   benchmarks/bin/retrieval-bench.sh fixed      # same 50 parameter sets per query as orientdb-bench.sh fixed, best of 3
#   benchmarks/bin/retrieval-bench.sh jfr <recording.jfr> [topN]
#   benchmarks/bin/retrieval-bench.sh all        # generate (skipped when the dataset is there) + warm + cold + http, 1 and 8
#                                                # clients, short windows: the under-20-minutes-at-5M run
#   benchmarks/bin/retrieval-bench.sh full       # the same at 1, 8 and 32 clients with the default windows, then the JFR
#                                                # profiles and the EXPLAIN plans: the numbers in the workstream doc
#
# Every -D argument is passed to the JVM. Knobs are documented in docs/spork/workstreams/retrieval-bench.md.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
STAGE="${1:-all}"
shift || true

HEAP="${RETRIEVAL_BENCH_HEAP:-16g}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PKG=com.arcadedb.benchmarks.retrieval

build() {
  if [[ ! -f "$HERE/target/runtime-classpath.txt" || "${RETRIEVAL_BENCH_REBUILD:-1}" == "1" ]]; then
    (cd "$ROOT" && mvn -o -q -pl benchmarks -am compile -DskipTests -Dmaven.javadoc.skip=true)
  fi
}

# Arguments starting with '-' are JVM options (-D knobs, -XX flags), anything else is a program argument (the jfr stage's
# recording path and topN). The ${arr[@]+...} form keeps 'set -u' quiet on an empty array under the macOS bash 3.2.
run() {
  local main="$1"; shift
  local cp="$HERE/target/classes:$(cat "$HERE/target/runtime-classpath.txt")"
  local jvm=() prog=()
  for a in "$@"; do
    case "$a" in -*) jvm+=("$a") ;; *) prog+=("$a") ;; esac
  done
  (cd "$ROOT" && exec "$JAVA_BIN" -Xmx"$HEAP" -XX:+UseZGC --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
      -Djava.util.logging.config.file="$HERE/bin/logging.properties" ${jvm[@]+"${jvm[@]}"} -cp "$cp" "$PKG.$main" ${prog[@]+"${prog[@]}"})
}

build
case "$STAGE" in
  generate) run ReconDataGenerator "$@" ;;
  embedded) run EmbeddedRetrievalBenchmark "$@" ;;
  fixed)    run EmbeddedRetrievalBenchmark -Darcadedb.retrievalBench.mode=fixed "$@" ;;
  http)     run HttpRetrievalBenchmark "$@" ;;
  explain)  run ExplainPlans "$@" ;;
  jfr)      run JfrTopFrames "$@" ;;
  all)
    # Later -D flags win in the JVM, so anything passed on the command line overrides these defaults
    run ReconDataGenerator "$@"
    run EmbeddedRetrievalBenchmark -Darcadedb.retrievalBench.mode=warm -Darcadedb.retrievalBench.threads=1,8 \
        -Darcadedb.retrievalBench.warmupSeconds=2 -Darcadedb.retrievalBench.measureIterations=1 \
        -Darcadedb.retrievalBench.measureSeconds=3 "$@"
    run EmbeddedRetrievalBenchmark -Darcadedb.retrievalBench.mode=cold "$@"
    run HttpRetrievalBenchmark -Darcadedb.retrievalBench.threads=1,8 -Darcadedb.retrievalBench.warmupSeconds=1 \
        -Darcadedb.retrievalBench.measureSeconds=2 "$@"
    ;;
  full)
    run ReconDataGenerator "$@"
    run EmbeddedRetrievalBenchmark -Darcadedb.retrievalBench.mode=warm "$@"
    run EmbeddedRetrievalBenchmark -Darcadedb.retrievalBench.mode=cold "$@"
    run HttpRetrievalBenchmark "$@"
    run EmbeddedRetrievalBenchmark -Darcadedb.retrievalBench.mode=profile "$@"
    run ExplainPlans "$@"
    ;;
  *) echo "unknown stage '$STAGE' (generate|embedded|fixed|http|explain|jfr|all|full)" >&2; exit 2 ;;
esac
