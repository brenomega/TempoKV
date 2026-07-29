# Changelog

## [Unreleased]

## [0.1.0] - 2026-07-29

### Added

- RESP2 and bounded SQL interfaces over a shared command and storage path.
- Immutable MVCC history with current and historical reads, `HISTORY`, `DIFF`,
  and append-only restoration.
- Optional segmented WAL persistence, validated snapshots, recovery, TTL
  expiration, and conservative WAL compaction.
- Snapshot transactions with write-write conflict detection.
- Client authentication with command- and key-prefix ACLs.
- Primary-replica replication with incremental catch-up, full synchronization,
  acknowledgements, reconnect backoff, and heartbeat liveness checks.
- JMH benchmark coverage and bilingual performance and profiling guidance.
- Docker primary-replica demonstration and GitHub Actions CI.
- English and Brazilian Portuguese public documentation.

### Changed

- Centralized bounded operational controls for connections, protocols,
  credentials, transactions, replication queues, snapshots, and timeouts.
- Docker Compose now waits for a functional primary RESP healthcheck before
  starting the replica.

### Security

- Authentication requires explicit credentials when enabled; no default
  username or password is provided.
- Replication requires an explicit non-trivial shared secret.
- Non-loopback cleartext transport requires explicit opt-in.
- Secrets are redacted from configuration rendering and excluded from
  operational responses.
- Added the Apache License 2.0 and a vulnerability reporting policy.

### Performance

- Added sparse historical checkpoints while preserving the immutable version
  chain and persistent formats.
- Removed avoidable deep-chain append copying and reduced selected hot-path
  allocations.
- Added focused storage, protocol, persistence, transaction, and replication
  benchmarks.

### Reliability

- Added bounded replication queues, slow-peer disconnection, and heartbeat
  timeout handling.
- Enforced snapshot size during serialization, cleaned failed temporary files,
  and preserved the previous valid snapshot on failure.
- Added concurrency, integration, fault-injection, recovery, and container
  validation.
