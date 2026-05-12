#!/bin/bash
# Performance comparison: Maven vs Mill test execution
# Based on methodology from https://mill-build.org/blog/11-jvm-test-parallelism.html
set -e

ZINGG_DIR="$(cd "$(dirname "$0")" && pwd)"
MVN_MODULES="common/client,common/core,spark/client,spark/core"

echo "=== System Info ==="
echo "CPU: $(sysctl -n hw.ncpu) cores (P: $(sysctl -n hw.perflevel0.logicalcpu 2>/dev/null || echo '?'), E: $(sysctl -n hw.perflevel1.logicalcpu 2>/dev/null || echo '?'))"
if [ -z "$MILL_JAVA_HOME" ]; then MILL_JAVA_HOME="$JAVA_HOME"; fi
if [ -z "$MVN_JAVA_HOME" ]; then MVN_JAVA_HOME="$JAVA_HOME"; fi
echo "Mill Java: $($MILL_JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "Maven Java: $($MVN_JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo ""

cd "$ZINGG_DIR"
>bench_results.txt
echo "Benchmark Results - $(date)" >>bench_results.txt
echo "" >>bench_results.txt

run_bench() {
	local label="$1"
	shift
	local cmd="$@"
	echo "--- $label ---"
	local start
	start=$(date +%s.%N)
	eval "$cmd" 2>&1 | grep -E "SUCCESS|BUILD|Total time|Test run finished" | tail -5
	local end
	end=$(date +%s.%N)
	local elapsed
	elapsed=$(echo "$end - $start" | bc)
	echo "  Wall-clock: ${elapsed}s"
	echo "$label: ${elapsed}s" >>bench_results.txt
	echo ""
}

# ============================================================
# MILL benchmarks
# ============================================================
echo "=== MILL: Clean compile ==="
JAVA_HOME=$MILL_JAVA_HOME mill clean 2>&1 | tail -1
JAVA_HOME=$MILL_JAVA_HOME mill __.compile 2>&1 | tail -3
echo "Compile done, running test benchmarks..."
echo ""

# --- testParallelism = false (module sharding) ---
run_bench "Mill j1  (serial)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 1 __.test"
run_bench "Mill j4  (module sharding)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 4 __.test"
run_bench "Mill j8  (module sharding)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 8 __.test"
run_bench "Mill j16 (module sharding)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 16 __.test"

# --- Enable testParallelism ---
sed -i '' 's/def testParallelism = false/def testParallelism = true/' mill-build/src/ProjectBaseModule.scala
JAVA_HOME=$MILL_JAVA_HOME mill clean 2>&1 | tail -1
JAVA_HOME=$MILL_JAVA_HOME mill __.compile 2>&1 | tail -3

run_bench "Mill j4  (dynamic sharding)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 4 __.test"
run_bench "Mill j8  (dynamic sharding)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 8 __.test"
run_bench "Mill j16 (dynamic sharding)" "JAVA_HOME=$MILL_JAVA_HOME mill -j 16 __.test"

# Restore
sed -i '' 's/def testParallelism = true/def testParallelism = false/' mill-build/src/ProjectBaseModule.scala

# ============================================================
# MAVEN benchmarks
# ============================================================
echo "=== MAVEN: Clean install ==="
JAVA_HOME=$MVN_JAVA_HOME mvn clean install -DskipTests -q 2>&1 | tail -1
echo ""

# --- Current surefire: parallel=classes, forkCount=4 ---
run_bench "Maven T1  (parallel-surefire)" "JAVA_HOME=$MVN_JAVA_HOME mvn test -pl $MVN_MODULES -T 1"
run_bench "Maven T4  (parallel-surefire)" "JAVA_HOME=$MVN_JAVA_HOME mvn test -pl $MVN_MODULES -T 4"
run_bench "Maven T8  (parallel-surefire)" "JAVA_HOME=$MVN_JAVA_HOME mvn test -pl $MVN_MODULES -T 8"
run_bench "Maven T16 (parallel-surefire)" "JAVA_HOME=$MVN_JAVA_HOME mvn test -pl $MVN_MODULES -T 16"

# --- Serial surefire: force forkCount=1 ---
run_bench "Maven T1  (serial-surefire)" "JAVA_HOME=$MVN_JAVA_HOME mvn test -pl $MVN_MODULES -T 1 -DforkCount=1"
run_bench "Maven T4  (serial-surefire)" "JAVA_HOME=$MVN_JAVA_HOME mvn test -pl $MVN_MODULES -T 4 -DforkCount=1"

echo ""
echo "=============================================="
echo "  RESULTS"
echo "=============================================="
cat bench_results.txt
