Se você quer ler a documentação em português acesse: [README-ptBR.md](README-ptBR.md).

# TempoKV

TempoKV is a temporal key-value database that keeps immutable version history.
It supports current reads, historical reads, comparisons, and restoration by
creating a new commit instead of rewriting earlier history.

## The problem

Conventional key-value storage optimizes for the latest state. Operational
systems may also need to answer what a value was at an earlier version or
timestamp, what changed between two points, and how to restore an earlier value
without losing the audit trail.

## What TempoKV provides

- immutable per-key history over an MVCC storage engine;
- `GETAT`, `HISTORY`, `DIFF`, and append-only `RESTOREAT` operations;
- RESP2 and a bounded SQL language over the same command and storage path;
- session-scoped transactions with snapshot reads and write-write conflict
  detection;
- optional WAL persistence, validated snapshots, recovery, TTL expiration, and
  conservative WAL compaction;
- authenticated command/prefix ACLs and primary-to-replica replication.

## Architecture at a glance

RESP and SQL are protocol adapters. Both produce protocol-neutral commands that
pass through authorization, validation, application handlers, the commit
coordinator, and the MVCC storage engine. When persistence is enabled, commits
reach the WAL before becoming visible; snapshots and WAL replay rebuild the
retained state.

See the [conceptual class diagram](docs/class-diagram.md) and
[use cases](docs/use-cases.md) for the complete view.

## Quick run

TempoKV requires JDK 25. Build the executable JAR:

```bash
./gradlew clean build
```

Start a loopback-only node with authentication explicitly disabled:

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/quickstart \
  --authentication-enabled=false
```

In another terminal:

```bash
redis-cli -p 6379 PING
```

Expected output:

```text
PONG
```

Continue with the [first-use tutorial](docs/getting-started.md) or go directly
to the [command cookbook](docs/command-cookbook.md).

## Demonstration

<!-- TODO: add the demonstration GIF at docs/assets/demo.gif -->

## Documentation

- [First-use tutorial](docs/getting-started.md)
- [Command cookbook](docs/command-cookbook.md)
- [Use cases](docs/use-cases.md)
- [Conceptual class diagram](docs/class-diagram.md)
- [Configuration reference](docs/configuration.md)
- [Performance, profiling, and benchmark results](docs/performance.md)
- [Benchmark harness guide](benchmarks/README.md)

## Build and tests

```bash
./gradlew check
```

`check` runs unit tests, concurrency checks, integration tests, SQL
lexer/parser generation, and combined JaCoCo reporting.

## Project status

TempoKV is a finished technical project and reference implementation, not a
claim of production readiness. Native TLS is not implemented: keep endpoints on
loopback or a trusted private network, or place them behind a TLS-terminating
proxy, tunnel, or service mesh. Non-loopback cleartext transport requires an
explicit configuration opt-in.

## License

TempoKV is licensed under the [Apache License 2.0](LICENSE).
