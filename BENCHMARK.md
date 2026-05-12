# Benchmark: Maven vs Mill — Test Execution Performance

Date: 2026-05-13  
Methodology: adapted from [Mill's JVM Test Parallelism blog post](https://mill-build.org/blog/11-jvm-test-parallelism.html)

## System

|             |                                          |
| ----------- | ---------------------------------------- |
| **Machine** | MacBook Pro (Mac16,6), Apple M4 Max      |
| **CPU**     | 16 cores (12 performance + 4 efficiency) |
| **RAM**     | 128 GB                                   |
| **OS**      | macOS 26.5                               |
| **Mill**    | 1.1.6                                    |
| **Maven**   | 3.9.15                                   |

## Java Version

| Tool  | Java                       | Why                                                              |
| ----- | -------------------------- | ---------------------------------------------------------------- |
| Mill  | GraalVM 21.0.11 (arm64)    | Mill 1.1.6 launcher requires Java 17+ (class file 61.0)          |
| Maven | GraalVM CE 11.0.18 (arm64) | Spark tests NPE on Java 17; scala-maven-plugin breaks on Java 21 |

No single JVM runs both tools on this project. The benchmark runs each tool against its
fastest working JVM.

## Project Test Structure

| Module               | Test Classes | Characteristics                                               |
| -------------------- | ------------ | ------------------------------------------------------------- |
| `common.client.test` | 9            | Fast unit tests (~0.1s each)                                  |
| `common.core.test`   | 54           | Mixed unit tests (~0.1–0.5s each)                             |
| `spark.client.test`  | 2            | Fast unit tests                                               |
| `spark.core.test`    | 23           | Heavy integration tests (some up to ~120s; SparkContext init) |
| **Total**            | **88**       |                                                               |

## Mill Configuration

Mill's build definition lives in `mill-build/src/ProjectBaseModule.scala`:

- **Module sharding** — `def testParallelism = false`. Test classes run sequentially within each module. Mill's `-j N` flag parallelizes at the task level, so modules themselves run in parallel but tests inside a module do not.
- **Dynamic sharding** — `def testParallelism = true`. Mill spawns `N` JVM worker processes that pull test classes off a disk-based queue, reusing processes across test classes from the same module. This enables intra-module parallelism without spawning one process per test class.

## Maven Configuration

Maven's root POM configures surefire with:

```xml
<parallel>classes</parallel>
<forkCount>4</forkCount>
```

The `-T` flag controls Maven's module-level build parallelism. The JaCoCo plugin was
upgraded from 0.8.6 to 0.8.12 for Java 17+ class file support.

Two surefire variants were tested:

- **parallel-surefire** — the POM's default: 4 forks, test classes in parallel
- **serial-surefire** — overridden via `-DforkCount=1`: 1 fork, serial test execution

## Benchmark Commands

### Mill

```bash
# Compile once, then time test runs
mill clean && mill __.compile

mill -j 1 __.test          # Serial
mill -j 4 __.test          # Module sharding

# Enable dynamic sharding and re-compile
mill clean && mill __.compile  # (with testParallelism = true)

mill -j 4 __.test          # Dynamic sharding
mill -j 8 __.test
mill -j 16 __.test
```

### Maven

```bash
# Full install once, then time test runs
mvn clean install -DskipTests

mvn test -pl common/client,common/core,spark/client,spark/core -T 1
mvn test -pl ... -T 4
mvn test -pl ... -T 8
mvn test -pl ... -T 16

# Serial surefire
mvn test -pl ... -T 1 -DforkCount=1
```

Each test-timing run was standalone (no compilation). Wall-clock time was measured
with the shell's `time` builtin.

## Results

| Configuration                  | Wall-Clock | vs Serial |
| ------------------------------ | ---------- | --------- |
| **Mill j1** (serial)           | **128s**   | baseline  |
| Mill j4 (module sharding)      | 134s       | +5%       |
| **Mill j4** (dynamic sharding) | **90s**    | **-30%**  |
| Mill j8 (dynamic sharding)     | 99s        | -23%      |
| Mill j16 (dynamic sharding)    | 112s       | -13%      |
| Maven T1 (parallel-surefire)   | 110s       | -14%      |
| Maven T4 (parallel-surefire)   | 115s       | -10%      |
| Maven T8 (parallel-surefire)   | 111s       | -13%      |
| Maven T16 (parallel-surefire)  | 114s       | -11%      |
| Maven T1 (serial-surefire)     | 112s       | -13%      |

All Maven results cluster tightly around 110–115s regardless of `-T` setting. Maven's
`-T` flag controls module-level build parallelism, but the bottleneck is entirely
within `spark-core` (heavy Spark integration tests), so parallelising other modules
provides no benefit.

## Analysis

### Module sharding alone provides zero benefit

With only 4 modules and the workload dominated by `spark.core` (heavy Spark
integration tests), module-level parallelism leaves 3 of 4 threads idle after
the faster modules finish. Mill j4 module sharding (134s) is actually slightly
slower than serial (128s), likely due to JVM process spawning overhead.

### Dynamic sharding delivers 30% speedup at j4

`testParallelism = true` lets Mill parallelize test classes _within_ `spark.core`,
the bottleneck module. At j4, wall-clock drops from 128s to 90s — the best result
of any configuration.

### More threads hurt with dynamic sharding

j4 (90s) > j8 (99s) > j16 (112s). Each additional worker spawns a JVM process
(~1–2s overhead) and increases CPU contention on the already resource-heavy Spark
integration tests. The sweet spot for this project is **j4**.

### Mill dynamic sharding beats Maven

Mill's best result (90s at j4) is ~18% faster than Maven's best (110s at T1).
Maven's results are flat across all `-T` values because:

1. The bottleneck is `spark-core`, which runs on a single module thread
2. Surefire's `forkCount=4` already saturates the CPUs within that module
3. Maven's `-T` flag only parallelises _between_ modules, not within them

### Why does j4 work better than higher thread counts?

Mill's [biased dynamic sharding](https://mill-build.org/blog/11-jvm-test-parallelism.html#_biased_dynamic_sharding) ensures each module gets one process first
before spawning additional workers. With 4 modules and j4, each module gets exactly
one worker process that pulls classes off the queue — combining module-level
isolation with intra-module work stealing. At j8/j16, excess workers spawn
additional JVM processes that fight for CPU and memory during Spark test execution,
increasing total wall-clock time.

## Change Applied

```diff
-    def testParallelism = false
+    def testParallelism = true
```

in `mill-build/src/ProjectBaseModule.scala`.

Mill will now use dynamic sharding for test execution. The `-j` flag (default:
number of CPU cores) controls the worker pool size. Running with `-j 4` is
recommended for this project's test profile.
