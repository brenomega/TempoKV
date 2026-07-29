**English** | [Português (Brasil)](performance-ptBR.md)

# Performance, Profiling, and Benchmarks

## Scope

TempoKV's benchmark harness covers in-process storage, temporal operations,
commit processing, persistence, protocols, transactions, replication, and real
loopback sockets. The published numeric results below are limited to runs whose
raw data remained identifiable and whose measured path was not invalidated by a
later change.

These results characterize one development machine. They are not an SLA,
capacity promise, or compatibility guarantee. Do not extrapolate them to a
different CPU, heap, filesystem, durability policy, network, dataset, or
concurrency level. No multi-host deployment, sustained production workload, or
maximum-capacity envelope was measured.

## Test environment

The retained result metadata and machine inspection identify this environment:

| Item | Confirmed value |
| --- | --- |
| Measurement date | 2026-07-29 |
| CPU | AMD Ryzen 5 7520U with Radeon Graphics |
| CPU topology | 4 physical cores, 8 hardware threads |
| Memory visible to the OS | 7.0 GiB |
| Operating system | Manjaro Linux, x86_64, kernel 6.12.77-1-MANJARO |
| Project filesystem | Btrfs with zstd compression on NVMe |
| Temporary-memory filesystem | tmpfs, used where a benchmark state requested a temporary directory |
| Benchmark JDK | Eclipse Temurin 25.0.3 |
| JMH | 1.37 |
| Published history heap | fixed 768 MiB (`-Xms768m -Xmx768m`) |
| Published SQL compiler heap | fixed 512 MiB (`-Xms512m -Xmx512m`) |
| Published forks and threads | 1 fork, 1 thread |
| Durability | Not applicable: the published history and SQL compiler results are in-memory microbenchmarks |

Filesystem and tmpfs details are recorded for reproducibility, but neither is
on the measured path of the published in-memory results.

## Methodology

JMH performs warmup before measurement and consumes returned values. Benchmarks
that emit multiple results use `Blackhole` where appropriate, preventing the
measured work from being removed as dead code. Class annotations define the
normal defaults; a command-line run may override them.

The historical before/after comparison used `Mode.AverageTime`, one
300 ms warmup iteration, three 500 ms measurement iterations, one fork, one
thread, and `ns/op`. The retained SQL compiler run used `Mode.Throughput`, three
500 ms warmup iterations, five 1 s measurement iterations, one fork, one
thread, and the JMH GC profiler.

`Mode.SampleTime` results include a latency distribution and percentiles;
`Mode.Throughput` reports operations per unit of time; `Mode.AverageTime`
reports mean time per operation. These metrics are not interchangeable. One
fork and short iterations are useful engineering evidence, but they do not
establish statistical significance or a strong confidence interval.

The harness has three distinct levels:

- in-process microbenchmarks isolate Java components and exclude socket and
  process overhead;
- `NetworkProtocolBenchmark` uses real loopback TCP sockets and includes
  framing, the NIO endpoint, and client/server scheduling;
- integration tests exercise complete behavior, but are correctness tests and
  do not publish timing assertions.

`FsyncPolicy.NEVER` and `FsyncPolicy.ALWAYS` provide different durability
guarantees. Their throughput or latency must not be presented as a like-for-like
performance comparison.

Known noise sources include CPU frequency scaling, thermal state, background
processes, garbage collection, filesystem cache state, tmpfs versus persistent
storage, compressed filesystem behavior, and loopback scheduling. No claim of
statistical significance is made.

## Benchmark coverage

| Area | Benchmark class | Representative workloads |
| --- | --- | --- |
| Current storage reads | `StorageReadBenchmark` | `currentGetHit`, `currentGetMiss` |
| Temporal reads and diff | `StorageReadBenchmark` | newest/oldest version, oldest timestamp, shallow/deep history, near/distant `DIFF` |
| Focused historical scaling | `HistoricalLookupBenchmark` | current, middle, oldest, timestamp, near/distant `DIFF`, append at depths 1,000–100,000 |
| Storage writes and maintenance | `StorageWriteBenchmark` | new/existing `SET`, tombstone, expiry, multi-mutation commit, deep append, restore, GC |
| In-process protocols | `ProtocolBenchmark` | RESP pipelines, SQL parse/plan, complete current SQL handler path |
| Real loopback protocol | `NetworkProtocolBenchmark` | RESP `PING`/`GET`/`SET`, pipelines with 1–8 clients, current and historical SQL |
| WAL and snapshot write | `PersistenceBenchmark` | WAL encode/append under `NEVER` and `ALWAYS`, snapshot write |
| Recovery and compaction | `PersistenceLifecycleBenchmark` | WAL replay/rotation/compaction, snapshot load, snapshot-plus-WAL recovery |
| Transactions | `TransactionBenchmark` | empty/single/multi-key commit, rollback, deterministic conflict, conflict-free 1–8 clients |
| Replication commit path | `ReplicationBenchmark` | primary commit without replica and commit plus replica ACK |
| Replication data path | `ReplicationDataBenchmark` | replica apply, full snapshot install, incremental catch-up plan |

`BenchmarkFixtures` is shared setup code, not a benchmark class.

## Representative results

### Historical lookup before and after sparse checkpoints

The baseline is commit `2ea843b`; the post-change result is commit `5f72f9f`.
Both result sets used the same benchmark source and command-line JMH settings
described above. “Reduction” is the reduction in measured mean time, not a
statistical confidence claim.

| Workload | Depth | Before | After | Mean-time reduction |
| --- | ---: | ---: | ---: | ---: |
| `currentVersion` | 1,000 | 33.5 ns/op | 24.1 ns/op | 28.2% |
| `currentVersion` | 10,000 | 37.1 ns/op | 22.8 ns/op | 38.7% |
| `currentVersion` | 100,000 | 40.1 ns/op | 22.6 ns/op | 43.6% |
| `middleVersion` | 1,000 | 1,237.8 ns/op | 76.6 ns/op | 93.8% |
| `middleVersion` | 10,000 | 9,008.2 ns/op | 471.8 ns/op | 94.8% |
| `middleVersion` | 100,000 | 708,491.8 ns/op | 3,146.0 ns/op | 99.6% |
| `oldestVersion` | 1,000 | 1,816.7 ns/op | 170.1 ns/op | 90.6% |
| `oldestVersion` | 10,000 | 18,861.5 ns/op | 677.7 ns/op | 96.4% |
| `oldestVersion` | 100,000 | 1,288,275.9 ns/op | 8,743.1 ns/op | 99.3% |
| `middleTimestamp` | 1,000 | 2,365.2 ns/op | 181.3 ns/op | 92.3% |
| `middleTimestamp` | 10,000 | 45,386.6 ns/op | 519.2 ns/op | 98.9% |
| `middleTimestamp` | 100,000 | 2,038,080.4 ns/op | 4,205.5 ns/op | 99.8% |
| `oldestTimestamp` | 1,000 | 4,319.0 ns/op | 252.5 ns/op | 94.2% |
| `oldestTimestamp` | 10,000 | 29,744.8 ns/op | 923.2 ns/op | 96.9% |
| `oldestTimestamp` | 100,000 | 3,333,885.6 ns/op | 13,154.6 ns/op | 99.6% |
| `diffNear` | 1,000 | 6,455.3 ns/op | 1,499.7 ns/op | 76.8% |
| `diffNear` | 10,000 | 1,318.1 ns/op | 572.8 ns/op | 56.5% |
| `diffNear` | 100,000 | 2,562.0 ns/op | 1,629.8 ns/op | 36.4% |
| `diffDistant` | 1,000 | 5,530.9 ns/op | 928.0 ns/op | 83.2% |
| `diffDistant` | 10,000 | 28,677.1 ns/op | 1,471.5 ns/op | 94.9% |
| `diffDistant` | 100,000 | 1,796,573.0 ns/op | 11,122.6 ns/op | 99.4% |
| `append` | 1,000 | 3,573.3 ns/op | 655.4 ns/op | 81.7% |
| `append` | 10,000 | 857.1 ns/op | 684.9 ns/op | 20.1% |
| `append` | 100,000 | 3,023.1 ns/op | 805.5 ns/op | 73.4% |

The non-monotonic baseline values for `append` and `diffNear` show why these
short, single-fork runs must be interpreted as engineering evidence rather
than universal ratios. The after values are reported without claiming a
confidence level that was not measured.

### SQL compiler microbenchmarks

No later commit changed the lexer, parser, semantic analyzer, or planner path
measured by these two methods.

| Workload | Dataset/history | Concurrency | Durability | Metric | Result | Caveat |
| --- | --- | ---: | --- | --- | ---: | --- |
| `sqlParsePlanCurrent` | One point `SELECT` statement | 1 thread | N/A | Throughput | 92,848.5 ops/s | One fork; in-process compile only |
| `sqlParsePlanCurrent` | One point `SELECT` statement | 1 thread | N/A | Allocation | 35,218.7 B/op | JMH GC profiler estimate |
| `sqlParsePlanHistorical` | One `AS OF VERSION` statement | 1 thread | N/A | Throughput | 86,572.4 ops/s | One fork; in-process compile only |
| `sqlParsePlanHistorical` | One `AS OF VERSION` statement | 1 thread | N/A | Allocation | 35,684.3 B/op | JMH GC profiler estimate |

### Results deliberately not published

| Area | Reason |
| --- | --- |
| RESP pipeline and allocation | Retained raw data predates later authentication, decoder-limit, and handler changes |
| Real TCP after `TCP_NODELAY` | The final numeric artifact was not retained |
| `SET` after immutable-chain append | The retained baseline predates the linked-chain change; no comparable final JSON remains |
| WAL, recovery, snapshot, and compaction | Later bounded-snapshot changes affect the path, and no comparable final result remains |
| Transactions | No final result artifact remains |
| Replication | No final result artifact remains after heartbeat and queue-limit changes |

The raw JSON for the historical comparison and older diagnostic runs survived
only as local temporary artifacts. They are not versioned or linked from this
document; the tables above preserve the reviewed values.

## Historical lookup scalability

`VersionChain` remains an immutable newest-first linked chain. It now adds one
sparse checkpoint for every 64 versions. A lookup walks checkpoints toward the
requested coordinate and then walks the remaining nodes. Current reads remain
an O(1) head access, and append remains O(1), including atomic head
publication.

Version and monotonic-timestamp lookups have expected work
O(depth / 64 + 64). This is still O(depth) asymptotically, not a logarithmic
index, but the measured deep lookups show a substantial constant-factor
reduction. If recovered timestamps are not monotonic, timestamp lookup safely
falls back to an O(depth) chain scan. `DIFF` performs two point lookups plus a
comparison proportional to the compared value bytes. `HISTORY` remains
proportional to the number of materialized and returned versions.

The additional memory shape is O(depth / 64): one checkpoint object containing
two references for each 64-version block. Exact retained bytes were not
measured, so no byte-per-version estimate is claimed. Checkpoints are rebuilt
from the immutable chain during recovery and are not serialized into WAL or
snapshot formats.

The measured depths were 1,000, 10,000, and 100,000. Larger histories,
non-monotonic timestamp distributions, and high-contention concurrent append
were not characterized for publication.

## Profiling findings

| Workload | Evidence | Change | Post-change observation | Remaining cost |
| --- | --- | --- | --- | --- |
| Metric-name validation | Profile correlation plus code inspection identified per-call `String.matches` regex work | Replaced regex matching with a direct character scan | Regex engine setup is absent from the current path; no retained post-change profiler number | Metric-map updates and aggregation remain |
| RESP pipelines | JMH GC-profiler diagnostics showed allocation increasing with pipeline size | Defensive input limits, bounded pending writes, and error-state cleanup were added | Old numeric allocation values are not published because later handler changes affect comparability | `ByteArrayOutputStream.toByteArray`, frame byte-array copies, response arrays, and queue buffers remain |
| SQL parsing and planning | Current compiler path plus retained JMH GC-profiler output | No broad lexer/parser rewrite was attempted | Approximately 35 KiB/op remained in the retained compiler microbenchmarks | Lexer, CUP parser, AST, semantic analysis, and plan objects are allocated per statement |
| Historical point lookup | Comparable JMH before/after results at three depths | Added sparse 64-version checkpoints without changing the immutable chain or persistent formats | Deep version, timestamp, and distant `DIFF` means fell substantially in the tested range | Lookup remains O(depth) asymptotically; `HISTORY` must materialize returned records |
| Deep-chain append | Code review confirmed full-list copying on every immutable append | Replaced full-list copy with immutable linked-node prepend | The focused after run stayed below 806 ns/op through depth 100,000 | Commit construction, value defensive copies, and checkpoint creation remain |
| Snapshot and recovery | Code inspection found late size checks and full-buffer materialization | Added bounded serialization, early abort, temporary-file cleanup, and direct temporary-file writing | Failure is bounded earlier; no comparable final timing artifact remains | Snapshot encode/load and WAL replay still materialize byte arrays |
| Loopback TCP | Socket benchmark and code inspection exposed delayed small writes | Enabled `TCP_NODELAY` on accepted client and replication sockets | No retained final numeric result; no performance claim is made | One NIO selector serves each public protocol, and scheduling noise remains |
| Contention | Transaction and network concurrency benchmarks exist, but no retained profiler trace establishes a universal hotspot | No global read lock was added | Correctness tests and benchmark coverage remain | A single selector per protocol and synchronized commit/storage sections can serialize work |

No retained NMT or async-profiler recording was available. No `.jfr` recording
is published. The table distinguishes code-level causes, profiler correlation,
and measured improvement rather than treating one sample as universal proof.

## Performance-related hardening

The current configuration validates bounded connections per protocol, RESP
array elements, command and credential sizes, transaction mutations and
write-set bytes, replication peers, pending replica commits and bytes, and
snapshot bytes. Invalid or incoherent values fail during startup.

Additional load-safety behavior includes:

- bounded client pending-write queues and NIO read backpressure;
- per-key TTL deduplication so superseded expirations do not accumulate;
- bounded replica queues with slow-replica disconnection;
- early snapshot-size enforcement and removal of failed temporary snapshots;
- heartbeat timeouts that release dead or half-open replication connections;
- bounded replication frames and synchronization timeouts.

These controls trade unbounded resource retention for explicit rejection or
disconnection. They are stability behavior, not evidence of higher throughput.

## Known limitations

- RESP still performs avoidable byte-array and framing copies.
- SQL allocates lexer, parser, AST, semantic, and planning objects per
  statement.
- Each public protocol uses one selector; selector sharding was not measured.
- Published scaling stops at a 100,000-version history and one benchmark
  thread.
- Snapshot and full-sync paths still materialize bounded snapshots rather than
  streaming the entire codec.
- Published results come from one CPU, OS, JDK, and storage configuration.
- One-fork runs and short iterations do not provide strong statistical
  intervals.
- NMT, async-profiler, and `perf` evidence was not retained.
- No current publishable numbers remain for real TCP, recovery, transaction,
  or replication workloads.

## Reproducibility

See the [benchmark harness guide](../benchmarks/README.md). The code under
`src/jmh` and the JMH annotations are the executable source of truth. Temporary
result files are deliberately written below the ignored build directory.

TempoKV is licensed under [Apache License 2.0](../LICENSE), SPDX identifier
`Apache-2.0`.

## Interpreting results

- Do not compare throughput and latency as equivalent metrics.
- Do not compare `FsyncPolicy.NEVER` and `FsyncPolicy.ALWAYS` as though they
  offered the same durability.
- Consider percentiles and allocation alongside a mean or throughput score.
- Repeat measurements on the intended deployment machine and filesystem.
- Treat the first local run as a baseline, not a promise.
