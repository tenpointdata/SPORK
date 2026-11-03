#!/usr/bin/env bash
# OrientDB 3.2.24 baseline of the retrieval benchmark. Same dataset (records, seed) and same nine queries as
# benchmarks/bin/retrieval-bench.sh, same JVM flags and JMH windows, so the result tables compare row for row.
#
#   benchmarks-orientdb/bin/orientdb-bench.sh generate [-Darcadedb.retrievalBench.records=1000000] ...
#   benchmarks-orientdb/bin/orientdb-bench.sh parity   # row totals over fixed parameters + EXPLAIN of each query
#   benchmarks-orientdb/bin/orientdb-bench.sh warm     [-Darcadedb.retrievalBench.threads=1,8] ...
#   benchmarks-orientdb/bin/orientdb-bench.sh fixed    # same 50 parameter sets per query as retrieval-bench.sh fixed
#
# Not part of the root reactor (OrientDB's Lucene 7 and ArcadeDB's Lucene 10 cannot share a classpath): it builds with
# its own pom. Every -D argument is passed to the JVM.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
STAGE="${1:-warm}"
shift || true

HEAP="${RETRIEVAL_BENCH_HEAP:-16g}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PKG=com.arcadedb.benchmarks.orientdb

if [[ ! -f "$HERE/target/runtime-classpath.txt" || "${RETRIEVAL_BENCH_REBUILD:-1}" == "1" ]]; then
  mvn -o -q -f "$HERE/pom.xml" compile -Dmaven.javadoc.skip=true
fi

run() {
  local main="$1"; shift
  local cp="$HERE/target/classes:$(cat "$HERE/target/runtime-classpath.txt")"
  # OrientDB keeps its disk cache in direct memory: give it room above the cache size
  (cd "$ROOT" && exec "$JAVA_BIN" -Xmx"$HEAP" -XX:+UseZGC -XX:MaxDirectMemorySize=12g \
      --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
      --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
      -Dstorage.useWAL=true -Dlog.console.level=warning ${@+"$@"} -cp "$cp" "$PKG.$main")
}

case "$STAGE" in
  generate) run OrientReconGenerator "$@" ;;
  parity)   run OrientRetrievalBenchmark -Darcadedb.retrievalBench.mode=parity "$@" ;;
  warm)     run OrientRetrievalBenchmark -Darcadedb.retrievalBench.mode=warm "$@" ;;
  fixed)    run OrientRetrievalBenchmark -Darcadedb.retrievalBench.mode=fixed "$@" ;;
  *) echo "unknown stage '$STAGE' (generate|parity|warm|fixed)" >&2; exit 2 ;;
esac
