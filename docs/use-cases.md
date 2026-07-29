# TempoKV — Complete Use Cases and Flows

> Product flows linked directly to architecture classes.

| Target architecture: single-node server in Java 25, distributed by JAR and Docker, with Java NIO, MVCC storage, WAL, SQL via JFlex/CUP and primary-replica replication. |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|

This document describes the behavior of TempoKV 0.1.0.

# 1. Scope and conventions

This document describes the functional flows that define the product. The names in brackets are exact classes from the conceptual diagram.

> Reading Note
> RESP and SQL are different front-ends. After mapping or planning, both use CommandDispatcher, handlers, CommitCoordinator and StorageEngine. No use case creates parallel storage for SQL.

# 2. Summary catalog

| **ID** | **Use case** | **Main actor** |
|--------|--------------------------------------------------------------|----------------------------------------------------- |
| UC-00 | Launch a local or Docker instance | Container operator/platform |
| UC-01 | Recover state after restart or crash | TempoKvServer / operator |
| UC-02 | Connect via RESP and PING | Redis Client |
| UC-03 | Write, read, and delete the current value with TTL | Redis client application |
| UC-04 | Execute query or mutation through the SQL interface | Operator, developer or administrative application |
| UC-05 | Read a value at a historical version or point in time | Redis or SQL client |
| UC-06 | Inspect history and compare versions | Auditor or operator |
| UC-07 | Restore a historical version | Authorized operator |
| UC-08 | Execute transaction with consistent snapshot | Client application |
| UC-09 | Detect and abort concurrent conflict | Two or more client applications |
| UC-10 | Expire key automatically preserving historical event | TempoKvServer / client application |
| UC-11 | Create a snapshot and compact the WAL | TempoKvServer / operator |
| UC-12 | Query health, metrics and administrative information | Authorized operator, monitor or client |
| UC-13 | Replicate commits to a replica and serve read-only | Primary node, replica node and reader application |

# UC-00 — Launch a local or Docker instance

| **Objective** | Provide a configured TempoKV node, with exclusive data directory and observable health state. |
|--------------------|----------------------------------------------------------------------------------------------------------|
| **Actors** | Container operator/platform |
| **Trigger** | The operator runs the JAR or starts the container.                                                          |

## Preconditions

- JDK or Docker image available.

- Defined ports and data directory.

## Main flow

**1.** TempoKvApplication loads and validates ServerConfiguration. [TempoKvApplication, ServerConfiguration]

**2.** DatabaseLock requests uniqueness over the directory through FileSystemAdapter. [DatabaseLock, FileSystemAdapter]

**3.** TempoKvApplication builds TempoKvServer with the components enabled. [TempoKvApplication, TempoKvServer]

**4.** ServerHealthService marks the node as STARTING; MetricsRegistry starts the basic indicators. [ServerHealthService, MetricsRegistry]

**5.** TempoKvServer invokes RecoveryManager when persisted state exists; the details are in UC-01. [TempoKvServer, RecoveryManager]

**6.** TempoKvServer starts RespServer, SqlServer and the workers enabled by the configuration. [TempoKvServer, RespServer, SqlServer, ExpirationWorker, HistoryGarbageCollector, ReplicationManager]

**7.** After the endpoints are accepting connections, ServerHealthService publishes READY. [ServerHealthService, RespServer, SqlServer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|------------------------|----------------------------------------------------------------------------|-----------------------------------------|
| Invalid configuration | The application exits before opening ports and reports the invalid fields. | ServerConfiguration, TempoKvApplication |
| Data directory already locked | DatabaseLock prevents a second instance from using the same files. | DatabaseLock, FileSystemAdapter |
| Recovery fails | The node remains DEGRADED and does not accept writes.                             | RecoveryManager, ServerHealthService |

## Postconditions

- There is a maximum of one writer instance per directory.

- Active endpoints match the configuration.

- The health status is consultable.

## Participating components

TempoKvApplication, ServerConfiguration, TempoKvServer, FileSystemAdapter, DatabaseLock, RecoveryManager, RespServer, SqlServer, ExpirationWorker, HistoryGarbageCollector, ReplicationManager, MetricsRegistry, ServerHealthService

# UC-01 — Recover state after restart or failure

| **Objective** | Exactly rebuild the last durable state before accepting traffic. |
|--------------------|--------------------------------------------------------------------------|
| **Actors** | TempoKvServer / operator |
| **Trigger** | TempoKvServer starts and detects persisted data.                        |

## Preconditions

- DatabaseLock acquired.

- Data directory contains zero or more snapshots and WAL segments.

## Main flow

**1.** ServerHealthService changes to RECOVERING. [ServerHealthService]

**2.** RecoveryManager requests the most recent valid snapshot from SnapshotStore. [RecoveryManager, SnapshotStore]

**3.** SnapshotStore uses FileSystemAdapter to read and validate the artifact. [SnapshotStore, FileSystemAdapter]

**4.** RecoveryManager restores StorageSnapshot to StorageEngine/MvccStore. [RecoveryManager, StorageSnapshot, StorageEngine, MvccStore]

**5.** RecoveryManager loops through FileWriteAheadLog; WalRecordCodec discards incomplete tail and decodes valid CommitRecords. [RecoveryManager, FileWriteAheadLog, WriteAheadLog, WalRecordCodec, CommitRecord]

**6.** Each CommitRecord after the snapshot is applied in order, rebuilding KeyIndex, VersionChain and TtlIndex. [StorageEngine, KeyIndex, VersionChain, VersionedValue, TtlIndex]

**7.** RecoveryManager restores VersionGenerator to the next available version. [RecoveryManager, VersionGenerator]

**8.** TempoKvServer enables the endpoints and ServerHealthService publishes READY. [TempoKvServer, RespServer, SqlServer, ServerHealthService]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|------------------------------------|------------------------------------------------------------------------------------|---------------------------------|
| Invalid Snapshot | The snapshot is skipped and replay starts from the full WAL when possible.          | SnapshotStore, RecoveryManager |
| Corrupt registry in the middle of WAL | Recovery fails safely; only a truncated tail can be ignored. | WalRecordCodec, RecoveryManager |
| Empty directory | The storage starts at version zero.                                                   | RecoveryManager, StorageEngine |

## Postconditions

- The global version is monotonic.

- The current state and retained history are identical to the durable state.

- No client observed partial recovery.

## Participating components

TempoKvServer, RecoveryManager, SnapshotStore, FileSystemAdapter, StorageSnapshot, StorageEngine, MvccStore, FileWriteAheadLog, WriteAheadLog, WalRecordCodec, CommitRecord, KeyIndex, VersionChain, VersionedValue, TtlIndex, VersionGenerator, RespServer, SqlServer, ServerHealthService, DatabaseLock

# UC-02 — Connect via RESP and PING

| **Objective** | Validate connectivity, RESP parsing, common pipeline and compatible response. |
|--------------------|----------------------------------------------------------------------------|
| **Actors** | Redis Client |
| **Trigger** | The client opens a connection and sends PING.                                   |

## Preconditions

- RespServer in READY.

## Main flow

**1.** RespServer registers the socket in the NioEventLoop. [RespServer, NioEventLoop]

**2.** NioEventLoop creates ClientConnection and Session. [NioEventLoop, ClientConnection, Session]

**3.** RespConnectionHandler delivers the bytes to the RespDecoder. [RespConnectionHandler, RespDecoder]

**4.** RespDecoder produces RespFrame; RespCommandMapper creates AdminCommand. [RespDecoder, RespFrame, RespCommandMapper, AdminCommand, Command]

**5.** Authenticator associates the default identity and AccessController authorizes PING. [Authenticator, AccessController, Session]

**6.** CommandValidator validates the command; CommandDispatcher selects AdminCommandHandler. [CommandValidator, CommandDispatcher, CommandHandler, AdminCommandHandler]

**7.** AdminCommandHandler produces CommandResult PONG and records the operation in MetricsRegistry. [AdminCommandHandler, CommandResult, MetricsRegistry]

**8.** RespEncoder serializes the response and NioEventLoop sends it without blocking other connections. [RespEncoder, NioEventLoop]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|------------------|----------------------------------------------------------------------------------|-------------------------------------------------|
| Incomplete frame | The connection preserves the bytes and waits for continuation.                             | RespDecoder, ClientConnection |
| Invalid RESP | RespEncoder returns a protocol error and the connection is closed when necessary. | RespDecoder, RespEncoder, RespConnectionHandler |

## Postconditions

- The connection can continue processing new commands.

- The response is understood by redis-cli.

## Participating components

RespServer, NioEventLoop, ClientConnection, Session, RespConnectionHandler, RespDecoder, RespFrame, RespCommandMapper, Command, AdminCommand, Authenticator, AccessController, CommandValidator, CommandDispatcher, CommandHandler, AdminCommandHandler, CommandResult, MetricsRegistry, RespEncoder

# UC-03 — Write, read, and delete the current value with TTL

| **Objective** | Run the current key-value cycle preserving versioning, authorization, configured durability and expiration. |
|--------------------|----------------------------------------------------------------------------------------------------------------|
| **Actors** | Redis client application |
| **Trigger** | The client sends SET, GET, TTL, EXPIRE, or DEL.                                                                  |

## Preconditions

- Active RESP session.

## Main flow

**1.** RespConnectionHandler decodes and RespCommandMapper creates KeyValueCommand. [RespConnectionHandler, RespDecoder, RespCommandMapper, KeyValueCommand, Command]

**2.** Authenticator and AccessController validate identity and key scope. [Authenticator, AccessController, Session]

**3.** CommandValidator checks arguments, sizes, options, and session state. [CommandValidator]

**4.** CommandDispatcher forwards to KeyValueCommandHandler. [CommandDispatcher, CommandHandler, KeyValueCommandHandler]

**5.** For GET/TTL, the handler queries StorageEngine, which resolves the current version to MvccStore/VersionChain and checks TtlIndex. [KeyValueCommandHandler, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, TtlIndex]

**6.** For SET/EXPIRE/DEL, the handler creates Mutation and requests commit to the CommitCoordinator. [KeyValueCommandHandler, Mutation, CommitCoordinator]

**7.** CommitCoordinator gets a version from VersionGenerator, creates CommitRecord, and appends it to WriteAheadLog when persistence is enabled. [CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog]

**8.** After the FsyncPolicy policy, CommitCoordinator applies the commit to StorageEngine and updates TtlIndex. [FsyncPolicy, CommitCoordinator, StorageEngine, TtlIndex]

**9.** CommandResult returns value, OK, integer or null; RespEncoder sends the response. [CommandResult, RespEncoder, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-----------------------------|---------------------------------------------------------------------------------------|---------------------------------------|
| NX/XX condition not met | No Mutation is created and the response indicates that there was no change.                | KeyValueCommandHandler, CommandResult |
| Key expired while reading | The read considers the key missing and the expiration remains for asynchronous cleanup. | StorageEngine, TtlIndex |
| WAL failure | The commit is not published to storage and the client receives an error.                          | WriteAheadLog, CommitCoordinator |

## Postconditions

- Committed writes receive a new version.

- DEL creates tombstone; does not destroy history.

- GET never observes partially applied commit.

## Participating components

RespConnectionHandler, RespDecoder, RespCommandMapper, KeyValueCommand, Command, Authenticator, AccessController, Session, CommandValidator, CommandDispatcher, CommandHandler, KeyValueCommandHandler, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, TtlIndex, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy, CommandResult, RespEncoder, MetricsRegistry, CommandTracer

# UC-04 — Execute query or mutation through the SQL interface

| **Objective** | Provide time-bound SQL without duplicating execution or storage rules. |
|--------------------|----------------------------------------------------------------------------------|
| **Actors** | Operator, developer or administrative application |
| **Trigger** | The client sends SELECT, UPSERT, DELETE, or a transactional statement.            |

## Preconditions

- SqlServer in READY.

- Statement belonging to the supported SQL subset.

## Main flow

**1.** SqlServer accepts the connection in NioEventLoop and creates ClientConnection/Session. [SqlServer, NioEventLoop, ClientConnection, Session]

**2.** SqlConnectionHandler sends the text to TempoLexer. [SqlConnectionHandler, TempoLexer]

**3.** TempoLexer produces tokens and TempoParser constructs Statement/Expression. [TempoLexer, TempoParser, Statement, Expression]

**4.** SqlSemanticAnalyzer validates types, names, temporal clauses, and authorization. [SqlSemanticAnalyzer, AccessController, Authenticator]

**5.** SqlPlanner converts the AST into ExecutionPlan. [SqlPlanner, ExecutionPlan]

**6.** PlanExecutor converts terminal operations to Command and uses CommandDispatcher; projection and filter operators process CommandResult. [PlanExecutor, Command, CommandDispatcher, CommandResult]

**7.** The specialized handler accesses StorageEngine or CommitCoordinator depending on read or mutation. [KeyValueCommandHandler, TemporalCommandHandler, TransactionCommandHandler, StorageEngine, CommitCoordinator]

**8.** SqlResultEncoder serializes columns, rows, counts, or errors. [SqlResultEncoder, SqlConnectionHandler, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|--------------------------|--------------------------------------------------------------------------------|-------------------------------------------|
| Lexical or syntactic error | The response reports unexpected row, column and token; no plan is executed. | TempoLexer, TempoParser, SqlResultEncoder |
| Semantic error | The response reports an incompatible column, type, or clause.                      | SqlSemanticAnalyzer, SqlResultEncoder |
| Plan not supported | SqlPlanner rejects out-of-scope operations such as JOIN.                        | SqlPlanner, SqlResultEncoder |

## Postconditions

- SQL and RESP produce the same semantics for equivalent operations.

- The storage does not know SQL.

## Participating components

SqlServer, NioEventLoop, ClientConnection, Session, SqlConnectionHandler, TempoLexer, TempoParser, Statement, Expression, SqlSemanticAnalyzer, SqlPlanner, ExecutionPlan, PlanExecutor, Command, CommandDispatcher, CommandResult, Authenticator, AccessController, KeyValueCommandHandler, TemporalCommandHandler, TransactionCommandHandler, StorageEngine, CommitCoordinator, SqlResultEncoder, MetricsRegistry, CommandTracer

# UC-05 — Read a value at a historical version or point in time

| **Objective** | Read the visible value of a key at a previous point without changing the current state. |
|--------------------|-----------------------------------------------------------------------------------|
| **Actors** | Redis or SQL client |
| **Trigger** | The client sends GETAT or SELECT ... AS OF.                                        |

## Preconditions

- The key has or may have retained versions.

## Main flow

**1.** The RESP or SQL front end converts the request to the equivalent TemporalCommand or ExecutionPlan. [RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan]

**2.** CommandDispatcher forwards the operation to TemporalCommandHandler. [CommandDispatcher, TemporalCommandHandler]

**3.** CommandValidator and AccessController validate selector, retention, and historical permission. [CommandValidator, AccessController]

**4.** TemporalCommandHandler queries StorageEngine with version or timestamp. [TemporalCommandHandler, StorageEngine]

**5.** MvccStore finds the KeyIndex and VersionChain of the key. [MvccStore, KeyIndex, VersionChain]

**6.** VersionChain selects the latest VersionedValue visible at the requested point. [VersionChain, VersionedValue]

**7.** CommandResult is adapted by RespEncoder or SqlResultEncoder. [CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|----------------------------|--------------------------------------------------------------------------------|-----------------------------------------|
| Version before retention | The response indicates unavailable history, without being confused with a non-existent key. | RetentionPolicy, TemporalCommandHandler |
| Tombstone visible | The result tells you that the key was deleted at that point.                 | VersionedValue, CommandResult |

## Postconditions

- The current state is not modified.

- The selection respects retention and commit timestamp.

## Participating components

RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, CommandDispatcher, TemporalCommandHandler, CommandValidator, AccessController, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, RetentionPolicy, CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer

# UC-06 — Inspect history and compare versions

| **Objective** | List retained versions and calculate differences between two states of the same key. |
|--------------------|---------------------------------------------------------------------------------|
| **Actors** | Auditor or operator |
| **Trigger** | The client sends HISTORY/DIFF or equivalent SQL query.                       |

## Preconditions

- Historical reading permission.

## Main flow

**1.** The front end creates TemporalCommand or ExecutionPlan from historical scan. [RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan]

**2.** TemporalCommandHandler requests the string from StorageEngine. [TemporalCommandHandler, StorageEngine]

**3.** MvccStore traverses VersionChain respecting logical HistoryOptions and RetentionPolicy. [MvccStore, VersionChain, VersionedValue, RetentionPolicy]

**4.** For DIFF, the handler selects two VersionedValue and calculates byte difference or textual representation. [TemporalCommandHandler, VersionedValue]

**5.** PlanExecutor applies filter, projection, ordering and limit when the source is SQL. [PlanExecutor, ExecutionPlan]

**6.** CommandResult is registered in MetricsRegistry and serialized by the protocol. [CommandResult, MetricsRegistry, CommandTracer, RespEncoder, SqlResultEncoder]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-----------------------------------------|--------------------------------------------------------------------------------|-----------------------------------------|
| Empty history | The answer distinguishes never-existing key from history removed by retention. | RetentionPolicy, TemporalCommandHandler |
| Incompatible versions for textual diff | The result provides binary comparison or metadata, without corrupting the values. | TemporalCommandHandler, CommandResult |

## Postconditions

- No version is modified.

- Pagination/limit prevents unlimited responses.

## Participating components

RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, TemporalCommandHandler, StorageEngine, MvccStore, VersionChain, VersionedValue, RetentionPolicy, PlanExecutor, CommandResult, MetricsRegistry, CommandTracer, RespEncoder, SqlResultEncoder, HistoryGarbageCollector

# UC-07 — Restore a historical version

| **Objective** | Make an old value current again without deleting previous versions. |
|--------------------|------------------------------------------------------------------------|
| **Actors** | Authorized operator |
| **Trigger** | The client sends RESTOREAT or equivalent SQL.                          |

## Preconditions

- The requested version is on hold.

- Identity can write the key.

## Main flow

**1.** The front end generates TemporalCommand and the dispatcher selects TemporalCommandHandler. [RespCommandMapper, SqlPlanner, TemporalCommand, CommandDispatcher, TemporalCommandHandler]

**2.** AccessController authorizes historical reads and writes for the key. [AccessController, Session]

**3.** TemporalCommandHandler reads the historical VersionedValue via StorageEngine. [TemporalCommandHandler, StorageEngine, VersionChain, VersionedValue]

**4.** The handler creates a new Mutation containing the historical value and restoration metadata. [TemporalCommandHandler, Mutation]

**5.** CommitCoordinator generates new version, creates CommitRecord and records it in WriteAheadLog. [CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog]

**6.** After FsyncPolicy, the commit is applied to MvccStore as the new head of the VersionChain. [FsyncPolicy, StorageEngine, MvccStore, VersionChain]

**7.** The client receives the new version; intermediate versions remain queryable. [CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-----------------------|------------------------------------------------|-----------------------------------------|
| Version not retained | No commits are created.                        | RetentionPolicy, TemporalCommandHandler |
| Conflict in transaction | The restore follows UC-09 and can be aborted. | ConflictDetector, TransactionManager |

## Postconditions

- The restored version is a new version.

- History remains append-only.

## Participating components

RespCommandMapper, SqlPlanner, TemporalCommand, CommandDispatcher, TemporalCommandHandler, AccessController, Session, StorageEngine, VersionChain, VersionedValue, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy, MvccStore, CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer, RetentionPolicy, ConflictDetector, TransactionManager

# UC-08 — Execute transaction with consistent snapshot

| **Objective** | Perform multiple reads and writes with a stable view and atomic commit. |
|--------------------|--------------------------------------------------------------------------------|
| **Actors** | Client application |
| **Trigger** | The client sends BEGIN, and COMMIT or ROLLBACK commands.                          |

## Preconditions

- Authenticated session without active transaction.

## Main flow

**1.** TransactionCommand is forwarded to the TransactionCommandHandler. [TransactionCommand, CommandDispatcher, TransactionCommandHandler]

**2.** TransactionManager requests a snapshot version from SnapshotManager and creates TransactionContext in the Session. [TransactionManager, SnapshotManager, TransactionContext, Session]

**3.** Reads within the transaction use StorageEngine limited to the snapshot version. [KeyValueCommandHandler, TemporalCommandHandler, StorageEngine, VersionChain, TransactionContext]

**4.** Writes are represented by Mutation and accumulated in the write set, without immediate publication. [Mutation, TransactionContext, KeyValueCommandHandler]

**5.** In COMMIT, TransactionManager requests validation from ConflictDetector. [TransactionManager, ConflictDetector]

**6.** Without conflicts, CommitCoordinator creates a single CommitRecord with all mutations. [CommitCoordinator, CommitRecord, VersionGenerator]

**7.** WAL and StorageEngine receive the atomic commit; SnapshotManager releases the snapshot. [WriteAheadLog, StorageEngine, SnapshotManager]

**8.** In ROLLBACK, the write set is discarded and no version is created. [TransactionManager, TransactionContext, CommandResult, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-------------------------|-------------------------------------------------------------|---------------------------------------------|
| COMMIT without transaction | CommandValidator returns status error.                    | CommandValidator, TransactionCommandHandler |
| Durable commit failure | No mutations are published and the transaction ends with an error. | CommitCoordinator, WriteAheadLog |
| ROLLBACK | The context is closed without touching the storage.                | TransactionManager, TransactionContext |

## Postconditions

- All commit mutations share the same version.

- Reads were consistent with the snapshot.

## Participating components

TransactionCommand, CommandDispatcher, TransactionCommandHandler, TransactionManager, SnapshotManager, TransactionContext, Session, KeyValueCommandHandler, TemporalCommandHandler, StorageEngine, VersionChain, Mutation, ConflictDetector, CommitCoordinator, CommitRecord, VersionGenerator, WriteAheadLog, CommandValidator, CommandResult, MetricsRegistry, CommandTracer

# UC-09 — Detect and abort concurrent conflict

| **Objective** | Prevent silent update loss when concurrent transactions write the same key. |
|--------------------|------------------------------------------------------------------------------------------------|
| **Actors** | Two or more client applications |
| **Trigger** | The second transaction attempts to commit a key modified after its snapshot.                    |

## Preconditions

- Two sessions have active snapshots.

## Main flow

**1.** Each Session maintains its TransactionContext and snapshotVersion. [Session, TransactionContext, SnapshotManager]

**2.** The first transaction is committed by the CommitCoordinator and creates a new version. [TransactionManager, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine]

**3.** The second transaction requests COMMIT from the TransactionCommandHandler. [TransactionCommand, TransactionCommandHandler]

**4.** ConflictDetector compares the write set keys with the current versions in StorageEngine. [ConflictDetector, TransactionContext, StorageEngine, VersionChain]

**5.** When finding a version higher than the snapshot, ConflictDetector rejects the commit before the WAL. [ConflictDetector, WriteAheadLog]

**6.** TransactionManager marks the context as aborted and SnapshotManager releases the snapshot. [TransactionManager, TransactionContext, SnapshotManager]

**7.** CommandResult identifies conflicting keys without exposing values. [CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-------------------------|-------------------------------------------------------------|-------------------------------------|
| Different keys | Both transactions can commit.                          | ConflictDetector, CommitCoordinator |
| Re-execution by the client | A new transaction receives current snapshot and repeats the logic. | TransactionManager, SnapshotManager |

## Postconditions

- No CommitRecord is created for the aborted transaction.

- The state committed by the first transaction remains intact.

## Participating components

Session, TransactionContext, SnapshotManager, TransactionManager, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine, TransactionCommand, TransactionCommandHandler, ConflictDetector, VersionChain, WriteAheadLog, CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer

# UC-10 — Automatically expire key preserving historical event

| **Objective** | Make a key invisible after TTL and record expiration as new version. |
|--------------------|---------------------------------------------------------------------------------|
| **Actors** | TempoKvServer / client application |
| **Trigger** | The clock reaches the next TtlIndex expiration date.                             |

## Preconditions

- There is a current version with an expiration date.

## Main flow

**1.** ExpirationWorker queries the TtlIndex at the configured intervals. [ExpirationWorker, TtlIndex]

**2.** The worker verifies with StorageEngine that the entry still matches the current version. [ExpirationWorker, StorageEngine, VersionChain]

**3.** ExpirationWorker creates Mutation tombstone with EXPIRED motif. [ExpirationWorker, Mutation]

**4.** CommitCoordinator generates version and CommitRecord. [CommitCoordinator, VersionGenerator, CommitRecord]

**5.** The commit is appended to WriteAheadLog and synchronized by FsyncPolicy. [WriteAheadLog, FsyncPolicy]

**6.** StorageEngine publishes the tombstone and removes the expired entry from the TtlIndex. [StorageEngine, VersionedValue, TtlIndex]

**7.** Current reads return missing; HISTORY continues to show the value and expiration. [KeyValueCommandHandler, TemporalCommandHandler, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-------------------------------|---------------------------------------------------------------------------------|---------------------------------------------|
| Obsolete entry in TtlIndex | The worker discards it because another version changed or removed the key.             | ExpirationWorker, TtlIndex, VersionChain |
| Server stopped at expiration date | UC-01 rebuilds the TTL and the first read/worker applies the pending expiration. | RecoveryManager, TtlIndex, ExpirationWorker |

## Postconditions

- The key is not visible after the deadline.

- The expiration event is auditable.

## Participating components

ExpirationWorker, TtlIndex, StorageEngine, VersionChain, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy, VersionedValue, KeyValueCommandHandler, TemporalCommandHandler, MetricsRegistry, CommandTracer, RecoveryManager

# UC-11 — Create a snapshot and compact the WAL

| **Objective** | Reduce recovery cost and WAL space without losing retained versions or replica data. |
|--------------------|--------------------------------------------------------------------------------------------------|
| **Actors** | TempoKvServer / operator |
| **Trigger** | WAL limit, schedule or administrative command requests snapshot.                               |

## Preconditions

- StorageEngine in operation.

- There is no concurrent snapshot publication.

## Main flow

**1.** SnapshotWriter asks SnapshotManager or StorageEngine for a consistent cut version. [SnapshotWriter, SnapshotManager, StorageEngine]

**2.** MvccStore materializes StorageSnapshot containing KeyIndex, retained VersionChain and TtlIndex. [MvccStore, StorageSnapshot, KeyIndex, VersionChain, VersionedValue, TtlIndex]

**3.** SnapshotStore writes temporary file by FileSystemAdapter and publishes it by atomic rename. [SnapshotStore, FileSystemAdapter]

**4.** SnapshotWriter validates the publication and records metrics. [SnapshotWriter, MetricsRegistry, CommandTracer]

**5.** HistoryGarbageCollector applies RetentionPolicy only on versions not needed by active snapshots. [HistoryGarbageCollector, RetentionPolicy, SnapshotManager]

**6.** WalCompactor calculates the smallest point needed per snapshot and AckTracker. [WalCompactor, AckTracker]

**7.** WalCompactor removes covered segments from FileWriteAheadLog. [WalCompactor, FileWriteAheadLog, WriteAheadLog]

**8.** ServerHealthService flags DEGRADED if compaction fails, without invalidating the previous snapshot. [ServerHealthService, SnapshotStore]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-----------------------|-----------------------------------------------------------------------------------------|-------------------------------------------|
| Failed before rename | The temporary file is ignored and the previous snapshot remains valid.                 | SnapshotStore, FileSystemAdapter |
| Delayed replication | WalCompactor preserves segments still needed or forces complete resynchronization. | AckTracker, WalCompactor, SyncCoordinator |
| Old Active Snapshot | HistoryGarbageCollector defers removal of visible versions.                              | HistoryGarbageCollector, SnapshotManager |

## Postconditions

- There is at least one valid snapshot.

- The remaining WAL is sufficient for recovery and replication.

## Participating components

SnapshotWriter, SnapshotManager, StorageEngine, MvccStore, StorageSnapshot, KeyIndex, VersionChain, VersionedValue, TtlIndex, SnapshotStore, FileSystemAdapter, MetricsRegistry, CommandTracer, HistoryGarbageCollector, RetentionPolicy, WalCompactor, AckTracker, FileWriteAheadLog, WriteAheadLog, ServerHealthService, SyncCoordinator

# UC-12 — Query health, metrics and administrative information

| **Objective** | Expose sufficient operational state for debugging, demonstration, and integration with monitoring. |
|--------------------|--------------------------------------------------------------------------------------------------|
| **Actors** | Authorized operator, monitor, or client |
| **Trigger** | The client sends PING, HEALTH or INFO.                                                            |

## Preconditions

- Active endpoint.

## Main flow

**1.** The front end creates AdminCommand. [RespCommandMapper, SqlPlanner, AdminCommand]

**2.** Authenticator and AccessController check administrative permission. [Authenticator, AccessController, Session]

**3.** CommandDispatcher selects AdminCommandHandler. [CommandDispatcher, AdminCommandHandler]

**4.** AdminCommandHandler queries ServerHealthService and MetricsRegistry. [AdminCommandHandler, ServerHealthService, MetricsRegistry]

**5.** CommandTracer provides latency and error aggregates without exposing key content. [CommandTracer]

**6.** CommandResult is serialized by RespEncoder or SqlResultEncoder. [CommandResult, RespEncoder, SqlResultEncoder]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-----------------------|-------------------------------------------------------------------------------|------------------------------------------|
| User without permission | AccessController returns authorization error.                                 | AccessController, CommandResult |
| Degraded subsystem | HEALTH reports DEGRADED with stable codes, keeping the endpoint responsive. | ServerHealthService, AdminCommandHandler |

## Postconditions

- The operator knows role, version, clients, WAL, snapshots, replica and latencies.

- No user value is returned by INFO.

## Participating components

RespCommandMapper, SqlPlanner, AdminCommand, Authenticator, AccessController, Session, CommandDispatcher, AdminCommandHandler, ServerHealthService, MetricsRegistry, CommandTracer, CommandResult, RespEncoder, SqlResultEncoder

# UC-13 — Replicate commits to a replica and serve read-only

| **Objective** | Maintain an ordered copy of the state and allow replica reading without independent generation of versions. |
|--------------------|--------------------------------------------------------------------------------------------------------|
| **Actors** | Primary node, replica node and reader application |
| **Trigger** | ReplicaClient connects to the PrimaryReplicationEndpoint.                                                   |

## Preconditions

- Primary and replica have compatible configuration.

- The replica does not accept client mutations.

## Main flow

**1.** ReplicationManager initializes ReplicaState according to the configured role. [ReplicationManager, ReplicaState, ServerConfiguration]

**2.** ReplicaClient establishes handshake with PrimaryReplicationEndpoint and reports the last applied version. [ReplicaClient, PrimaryReplicationEndpoint, ReplicaState]

**3.** SyncCoordinator decides between full snapshot via SnapshotStore or incremental commits via WriteAheadLog. [SyncCoordinator, SnapshotStore, WriteAheadLog, WalRecordCodec]

**4.** On full sync, ReplicaApplier installs StorageSnapshot before flushing reads. [ReplicaApplier, StorageSnapshot, StorageEngine, ServerHealthService]

**5.** In incremental synchronization, the primary transmits CommitRecords in order. [PrimaryReplicationEndpoint, CommitRecord, ReplicationManager]

**6.** ReplicaClient delivers each record to ReplicaApplier, which validates and applies it to StorageEngine without calling VersionGenerator. [ReplicaClient, ReplicaApplier, StorageEngine, VersionGenerator]

**7.** ReplicaState updates the applied version and AckTracker records the commit on the primary. [ReplicaState, AckTracker, ReplicationManager]

**8.** RespServer and SqlServer of the replica allow reads; AccessController rejects mutations. [RespServer, SqlServer, AccessController, ServerHealthService]

**9.** New CommitCoordinator commits are published by the ReplicationManager after local commit. [CommitCoordinator, ReplicationManager, MetricsRegistry, CommandTracer]

## Alternative flows and failures

| **Condition** | **Behavior** | **Components** |
|-----------------------------------|---------------------------------------------------------------|---------------------------------|
| WAL does not cover replica version | SyncCoordinator requires full snapshot.                      | SyncCoordinator, SnapshotStore |
| Connection interrupted | ReplicaClient reconnects from the last committed version. | ReplicaClient, ReplicaState |
| Commit out of order | ReplicaApplier rejects registration and restarts synchronization.   | ReplicaApplier, SyncCoordinator |
| Write sent to replica | AccessController returns READONLY.                              | AccessController, CommandResult |

## Postconditions

- The replica applies exactly the order of the primary.

- The replica never generates its own versions.

- The lag is observable.

## Participating components

ReplicationManager, ReplicaState, ServerConfiguration, ReplicaClient, PrimaryReplicationEndpoint, SyncCoordinator, SnapshotStore, WriteAheadLog, WalRecordCodec, ReplicaApplier, StorageSnapshot, StorageEngine, ServerHealthService, CommitRecord, VersionGenerator, AckTracker, RespServer, SqlServer, AccessController, CommitCoordinator, MetricsRegistry, CommandTracer, CommandResult

# Traceability matrix

| **UC** | **Flow** | **Classes** | **Main components** |
|--------|--------------------------------------------------------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| UC-00 | Launch a local or Docker instance | 13 | TempoKvApplication, ServerConfiguration, TempoKvServer, FileSystemAdapter, DatabaseLock, RecoveryManager, RespServer, SqlServer, ExpirationWorker, HistoryGarbageCollector… |
| UC-01 | Recover state after restart or crash | 20 | TempoKvServer, RecoveryManager, SnapshotStore, FileSystemAdapter, StorageSnapshot, StorageEngine, MvccStore, FileWriteAheadLog, WriteAheadLog, WalRecordCodec… |
| UC-02 | Connect via RESP and PING | 19 | RespServer, NioEventLoop, ClientConnection, Session, RespConnectionHandler, RespDecoder, RespFrame, RespCommandMapper, Command, AdminCommand… |
| UC-03 | Write, read, and delete the current value with TTL | 28 | RespConnectionHandler, RespDecoder, RespCommandMapper, KeyValueCommand, Command, Authenticator, AccessController, Session, CommandValidator, CommandDispatcher… |
| UC-04 | Execute query or mutation through the SQL interface | 26 | SqlServer, NioEventLoop, ClientConnection, Session, SqlConnectionHandler, TempoLexer, TempoParser, Statement, Expression, SqlSemanticAnalyzer… |
| UC-05 | Read a value at a historical version or point in time | 19 | RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, CommandDispatcher, TemporalCommandHandler, CommandValidator, AccessController, StorageEngine, MvccStore… |
| UC-06 | Inspect history and compare versions | 17 | RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, TemporalCommandHandler, StorageEngine, MvccStore, VersionChain, VersionedValue, RetentionPolicy… |
| UC-07 | Restore a historical version | 25 | RespCommandMapper, SqlPlanner, TemporalCommand, CommandDispatcher, TemporalCommandHandler, AccessController, Session, StorageEngine, VersionChain, VersionedValue… |
| UC-08 | Execute transaction with consistent snapshot | 21 | TransactionCommand, CommandDispatcher, TransactionCommandHandler, TransactionManager, SnapshotManager, TransactionContext, Session, KeyValueCommandHandler, TemporalCommandHandler, StorageEngine… |
| UC-09 | Detect and abort concurrent conflict | 18 | Session, TransactionContext, SnapshotManager, TransactionManager, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine, TransactionCommand, TransactionCommandHandler… |
| UC-10 | Expire key automatically preserving historical event | 16 | ExpirationWorker, TtlIndex, StorageEngine, VersionChain, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy… |
| UC-11 | Create a snapshot and compact the WAL | 21 | SnapshotWriter, SnapshotManager, StorageEngine, MvccStore, StorageSnapshot, KeyIndex, VersionChain, VersionedValue, TtlIndex, SnapshotStore… |
| UC-12 | Query health, metrics, and administrative information | 14 | RespCommandMapper, SqlPlanner, AdminCommand, Authenticator, AccessController, Session, CommandDispatcher, AdminCommandHandler, ServerHealthService, MetricsRegistry… |
| UC-13 | Replicate commits to a replica and serve read-only | 23 | ReplicationManager, ReplicaState, ServerConfiguration, ReplicaClient, PrimaryReplicationEndpoint, SyncCoordinator, SnapshotStore, WriteAheadLog, WalRecordCodec, ReplicaApplier… |

Consistency rule: all mentioned classes exist in the conceptual diagram.
