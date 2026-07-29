**English** | [Português (Brasil)](README-ptBR.md)

# TempoKV Benchmark Harness

## Requirements

- JDK 25; the Gradle toolchain downloads or selects the configured JDK when
  available;
- the repository's Gradle Wrapper (`./gradlew`);
- enough free memory for the selected dataset and forked heap.

Most benchmark classes set `-Xms512m -Xmx512m`.
`HistoricalLookupBenchmark` sets `-Xms768m -Xmx768m` for the 100,000-version
case. There is no verified general minimum RAM requirement. Docker is not used
by the JMH harness.

Compile the production, generated SQL, and JMH sources with:

```bash
./gradlew jmhClasses
```

## Benchmark inventory

| Class | Purpose | Important parameters |
| --- | --- | --- |
| `StorageReadBenchmark` | Current, historical, history-page, and `DIFF` reads | `datasetSize=100,1000,10000`; `historyDepth=10,100,1000` |
| `StorageWriteBenchmark` | Mutations, commits, append, restore, and history GC | `datasetSize=100,1000,10000`; `historyDepth=10,100,1000` |
| `HistoricalLookupBenchmark` | Focused sparse-checkpoint scalability | `depth=1000,10000,100000` |
| `ProtocolBenchmark` | In-process RESP and SQL paths | `pipelineSize=1,16,128` |
| `NetworkProtocolBenchmark` | Real loopback RESP/SQL endpoints | `pipelineSize=1,16,128`; fixed methods with `@Threads(2)`, `@Threads(4)`, and `@Threads(8)` |
| `PersistenceBenchmark` | WAL encode/append and snapshot write | `policy=NEVER,ALWAYS` |
| `PersistenceLifecycleBenchmark` | Replay, recovery, rotation, and compaction | `records=100,1000` for replay and recovery |
| `TransactionBenchmark` | Transaction lifecycle, conflicts, and concurrency | fixed methods with `@Threads(2)`, `@Threads(4)`, and `@Threads(8)` |
| `ReplicationBenchmark` | Primary commit with and without a replica ACK | no `@Param` |
| `ReplicationDataBenchmark` | Apply, full snapshot install, and incremental catch-up | no `@Param` |

`BenchmarkFixtures` provides deterministic setup data and is not itself a
benchmark.

## Quick smoke run

This is the shortest verified command for compiling and executing one small
benchmark:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.currentVersion -p depth=1000 -wi 0 -i 1 -r 200ms -f 1'
```

Smoke runs verify the harness; they are not performance evidence. Zero warmup,
one short iteration, and one fork are deliberately insufficient for publishing
a score.

## Running selected benchmarks

Run a current-storage read:

```bash
./gradlew jmh \
  -PjmhArgs='StorageReadBenchmark.currentGetHit -p datasetSize=10000 -p historyDepth=1000 -wi 2 -i 3 -f 1'
```

Run selected historical operations:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.(middleVersion|oldestTimestamp|diffDistant) -p depth=10000 -wi 2 -i 4 -f 1'
```

Run an in-process RESP pipeline:

```bash
./gradlew jmh \
  -PjmhArgs='ProtocolBenchmark.respGetPipeline -p pipelineSize=16 -wi 2 -i 3 -f 1'
```

Run the real loopback RESP endpoint:

```bash
./gradlew jmh \
  -PjmhArgs='NetworkProtocolBenchmark.respPipeline -p pipelineSize=16 -wi 2 -i 3 -f 1'
```

Run WAL append with an explicit durability policy:

```bash
./gradlew jmh \
  -PjmhArgs='PersistenceBenchmark.walAppend -p policy=ALWAYS -wi 2 -i 4 -f 1'
```

Run snapshot-plus-WAL recovery:

```bash
./gradlew jmh \
  -PjmhArgs='PersistenceLifecycleBenchmark.recoverySnapshotAndWal -p records=1000 -wi 1 -i 3 -f 1'
```

Run one transaction workload:

```bash
./gradlew jmh \
  -PjmhArgs='TransactionBenchmark.singleKeyCommit -wi 2 -i 4 -f 1'
```

Run primary commit plus replica acknowledgement:

```bash
./gradlew jmh \
  -PjmhArgs='ReplicationBenchmark.primaryCommitAndReplicaAck -wi 1 -i 3 -f 1'
```

The text passed to `-PjmhArgs` is split into JMH arguments by the Gradle task.
Use one quoted Gradle property exactly as shown. JMH benchmark selection is a
regular expression.

## Running a longer measurement

This example measures the focused history class with additional warmup, two
forks, the GC profiler, and JSON output:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.* -p depth=10000 -wi 3 -w 1s -i 5 -r 1s -f 2 -prof gc -rf json -rff build/benchmarks/history.json'
```

`HistoricalLookupBenchmark` supplies the fixed 768 MiB heap through its
`@Fork` annotation. The other benchmark classes supply a fixed 512 MiB heap.
Record both the annotation and every command-line override when comparing
results.

With no `-PjmhArgs`, the Gradle task uses two warmup iterations, three
measurement iterations, one fork, and writes JSON to
`build/benchmarks/jmh-result.json`. Class annotations still determine benchmark
mode and heap.

## Profiling with JFR

JDK 25 and JMH 1.37 provide the JFR profiler. A short diagnostic run can be
started with:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.middleVersion -p depth=10000 -wi 1 -i 2 -r 1s -f 1 -prof jfr:dir=build/benchmarks/jfr'
```

JMH writes the recording below `build/benchmarks/jfr`. The entire build
directory is ignored by Git. A JFR sample helps locate CPU, allocation, lock,
and I/O activity; it does not replace a comparable before/after benchmark.
Never commit a `.jfr` recording.

## Persistence precautions

- Use only benchmark-created disposable directories.
- Never point a benchmark at a production or otherwise valuable data directory.
- Record whether the temporary directory is tmpfs or a persistent filesystem.
- Record filesystem type, mount options relevant to durability, and cache
  state.
- Record `FsyncPolicy` for every WAL result.
- Do not compare `NEVER` and `ALWAYS` as equivalent durability.
- Prefer `./gradlew clean` to remove repository build outputs. Check the exact
  target before deleting any separately configured benchmark directory.

## Comparing results

Before interpreting two result files as a before/after comparison, keep these
constant:

- source commit, except for the intentional change under test;
- JDK vendor and version;
- heap and other JVM arguments;
- benchmark parameters and dataset;
- filesystem and temporary-directory policy;
- durability and fsync policy;
- concurrency and JMH thread count;
- warmup, measurement time, iterations, and forks;
- profiler configuration.

Compare the same metric and unit. Include absolute values, percentiles for
sample-time workloads, allocation when available, and the direction of the
change. Do not infer a regression or improvement from one noisy filesystem
sample.

## Output and cleanup

The default result is `build/benchmarks/jmh-result.json`. Commands in this
guide place JSON and JFR output below `build/benchmarks`. Benchmark-created
databases use JMH temporary directories and are removed by their teardown
methods.

The repository ignores `build/`, `.gradle/`, logs, and `data/`; therefore the
documented result files, profiler recordings, generated classes, and temporary
databases are not tracked. Check `git status --short` before finishing a run.
Do not remove the sources under `src/jmh`.

## Adding a benchmark

- Keep dataset construction and file creation in `@Setup`, outside the measured
  method.
- Consume outputs by return value or `Blackhole`.
- Use deterministic seeds, timestamps, keys, and payloads.
- Keep `@Param` sets small enough for a smoke selection; document extended
  local sizes separately.
- Do not add timing assertions to the normal test suite.
- Do not add benchmark-specific behavior to production code.
- Use teardown to close sockets and remove only the disposable state created by
  the benchmark.

For methodology, reviewed results, and limitations, see
[Performance, Profiling, and Benchmarks](../docs/performance.md). TempoKV uses
[Apache License 2.0](../LICENSE), SPDX identifier `Apache-2.0`.
