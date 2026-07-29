**English** | [Português (Brasil)](configuration-ptBR.md)

# TempoKV configuration reference

## Configuration sources and precedence

TempoKV resolves configuration from lowest to highest precedence:

1. built-in defaults;
2. a UTF-8 Java properties file;
3. environment variables;
4. command-line arguments.

Select the optional properties file with `--config=/path/to/tempokv.properties`
or `TEMPOKV_CONFIG=/path/to/tempokv.properties`. The CLI selector wins when
both are present. The file must be a regular, non-symbolic-link file no larger
than 1 MiB. Unknown properties, unknown CLI options, duplicate CLI options, and
blank CLI values stop startup. CLI arguments use the form `--name=value`.

The authentication variables with the `TEMPOKV_SECURITY_` prefix are supported
aliases. If both forms of the same authentication variable are set, the
`TEMPOKV_SECURITY_` form takes precedence.

## Complete option reference

Durations use ISO-8601 syntax, such as `PT5S`, `PT15S`, and `PT720H`. Byte
limits are decimal integers. A port value of `0` requests an ephemeral port
where noted.

| Purpose | CLI option | Environment variable | Properties key | Default | Validation/notes |
| --- | --- | --- | --- | --- | --- |
| Bind address for RESP, SQL, and primary replication | `--bind-address` | `TEMPOKV_BIND_ADDRESS` | `tempokv.bind.address` | `127.0.0.1` | Must resolve; a non-loopback address requires the insecure-transport opt-in. |
| Permit cleartext non-loopback transport | `--allow-insecure-remote-transport` | `TEMPOKV_ALLOW_INSECURE_REMOTE_TRANSPORT` | `tempokv.transport.allow.insecure.remote` | `false` | Boolean. Also required when a replica connects to a non-loopback primary host. |
| RESP port | `--resp-port` | `TEMPOKV_RESP_PORT` | `tempokv.resp.port` | `6379` | `0..65535`; `0` requests an ephemeral port. Must not equal another explicit server port. |
| SQL port | `--sql-port` | `TEMPOKV_SQL_PORT` | `tempokv.sql.port` | `6380` | `0..65535`; `0` requests an ephemeral port. Must not equal another explicit server port. |
| Primary replication listener port | `--replication-port` | `TEMPOKV_REPLICATION_PORT` | `tempokv.replication.port` | `6381` | `0..65535`; `0` requests an ephemeral port. Must not equal another explicit server port. |
| Data directory | `--data-dir` | `TEMPOKV_DATA_DIR` | `tempokv.data.dir` | `data` | Normalized to an absolute path; the filesystem root is rejected. One process may lock a directory at a time. |
| Node role | `--node-role` | `TEMPOKV_NODE_ROLE` | `tempokv.node.role` | `PRIMARY` | `PRIMARY` or `REPLICA`, case-insensitive. A replica requires replication. |
| Enable replication | `--replication-enabled` | `TEMPOKV_REPLICATION_ENABLED` | `tempokv.replication.enabled` | `false` | Boolean. Requires persistence and a valid replication token. |
| Node identifier | `--node-id` | `TEMPOKV_NODE_ID` | `tempokv.node.id` | `tempokv-node` | Nonblank UTF-8 text, bounded by `max-username-bytes`. |
| Primary host used by a replica | `--primary-host` | `TEMPOKV_PRIMARY_HOST` | `tempokv.primary.host` | `127.0.0.1` | Nonblank. A non-loopback host on a replica requires the insecure-transport opt-in. |
| Primary replication port used by a replica | `--primary-replication-port` | `TEMPOKV_PRIMARY_REPLICATION_PORT` | `tempokv.primary.replication.port` | `6381` | `1..65535`. |
| Shared replication secret | `--replication-token` | `TEMPOKV_REPLICATION_TOKEN` | `tempokv.replication.token` | none | Required only when replication is enabled. Must be 16–4096 UTF-8 bytes, within `max-credential-bytes`, contain at least three distinct code points, and not be a rejected trivial value. Never logged by TempoKV. |
| Historical retention age | `--history-retention` | `TEMPOKV_HISTORY_RETENTION` | `tempokv.history.retention` | `PT720H` | Positive ISO-8601 duration. |
| Enable WAL, snapshots, and recovery | `--persistence-enabled` | `TEMPOKV_PERSISTENCE_ENABLED` | `tempokv.persistence.enabled` | `false` | Boolean. Must be `true` when replication is enabled. |
| Enable client authentication | `--authentication-enabled` | `TEMPOKV_AUTHENTICATION_ENABLED` or `TEMPOKV_SECURITY_AUTHENTICATION_ENABLED` | `tempokv.security.authentication.enabled` | `true` | Boolean. Enabled mode requires explicit username and password. Disabled mode rejects supplied credentials. |
| Authentication username | `--authentication-username` | `TEMPOKV_AUTHENTICATION_USERNAME` or `TEMPOKV_SECURITY_AUTHENTICATION_USERNAME` | `tempokv.security.authentication.username` | none | Required when authentication is enabled; UTF-8 length must not exceed `max-username-bytes`. |
| Authentication password | `--authentication-password` | `TEMPOKV_AUTHENTICATION_PASSWORD` or `TEMPOKV_SECURITY_AUTHENTICATION_PASSWORD` | `tempokv.security.authentication.password` | none | Required when authentication is enabled; UTF-8 length must not exceed `max-credential-bytes`. Never logged by TempoKV. |
| Maximum client connections per public protocol | `--max-connections-per-protocol` | `TEMPOKV_MAX_CONNECTIONS_PER_PROTOCOL` | `tempokv.limits.connections.per.protocol` | `4096` | `1..100000`, applied separately to RESP and SQL. |
| Maximum RESP array elements | `--max-resp-array-elements` | `TEMPOKV_MAX_RESP_ARRAY_ELEMENTS` | `tempokv.limits.resp.array.elements` | `1024` | `1..65536`. |
| Maximum command bytes | `--max-command-bytes` | `TEMPOKV_MAX_COMMAND_BYTES` | `tempokv.limits.command.bytes` | `16777216` | `1024..67108864`. SQL statements are additionally capped at 1 MiB by the SQL handler. |
| Maximum username bytes | `--max-username-bytes` | `TEMPOKV_MAX_USERNAME_BYTES` | `tempokv.limits.username.bytes` | `128` | `1..4096`; also bounds the node identifier. |
| Maximum credential bytes | `--max-credential-bytes` | `TEMPOKV_MAX_CREDENTIAL_BYTES` | `tempokv.limits.credential.bytes` | `4096` | `8..1048576`; replication tokens are additionally capped at 4096 bytes. |
| Maximum mutations in one transaction | `--max-transaction-mutations` | `TEMPOKV_MAX_TRANSACTION_MUTATIONS` | `tempokv.limits.transaction.mutations` | `4096` | `1..100000`. |
| Maximum transaction write-set bytes | `--max-transaction-write-bytes` | `TEMPOKV_MAX_TRANSACTION_WRITE_BYTES` | `tempokv.limits.transaction.write.bytes` | `33554432` | `1024..268435456`. |
| Maximum connected replication peers | `--max-replication-peers` | `TEMPOKV_MAX_REPLICATION_PEERS` | `tempokv.limits.replication.peers` | `64` | `1..1024`. |
| Maximum queued commits per replica | `--max-pending-replica-commits` | `TEMPOKV_MAX_PENDING_REPLICA_COMMITS` | `tempokv.limits.replication.pending.commits` | `1024` | `1..100000`; a peer exceeding the bounded queue is disconnected. |
| Maximum queued commit bytes per replica | `--max-pending-replica-bytes` | `TEMPOKV_MAX_PENDING_REPLICA_BYTES` | `tempokv.limits.replication.pending.bytes` | `67108864` | `1024..536870912` and at least `max-command-bytes`. |
| Maximum snapshot payload bytes | `--max-snapshot-bytes` | `TEMPOKV_MAX_SNAPSHOT_BYTES` | `tempokv.limits.snapshot.bytes` | `67108864` | `1024..134217728`; also bounds full-sync snapshot payloads. |
| Replication synchronization timeout | `--replication-sync-timeout` | `TEMPOKV_REPLICATION_SYNC_TIMEOUT` | `tempokv.timeouts.replication.sync` | `PT15S` | ISO-8601 duration from `PT0.1S` through `PT10M`. |
| Replication heartbeat interval | `--replication-heartbeat-interval` | `TEMPOKV_REPLICATION_HEARTBEAT_INTERVAL` | `tempokv.timeouts.replication.heartbeat.interval` | `PT5S` | ISO-8601 duration from `PT0.05S` through `PT1M`. |
| Replication heartbeat timeout | `--replication-heartbeat-timeout` | `TEMPOKV_REPLICATION_HEARTBEAT_TIMEOUT` | `tempokv.timeouts.replication.heartbeat` | `PT15S` | ISO-8601 duration from `PT0.1S` through `PT10M`; must be at least twice the heartbeat interval. |

## Valid configurations

The examples use separate data directories. Remove those directories only after
stopping their nodes.

### Local node with authentication explicitly disabled

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/local-open \
  --authentication-enabled=false
```

### Local node with authentication enabled

```bash
: "${TEMPOKV_AUTHENTICATION_USERNAME:?set a username}"
: "${TEMPOKV_AUTHENTICATION_PASSWORD:?set a password}"
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/local-authenticated
```

The two credentials are read from the environment. Do not place them directly
in a command that may be retained in shell history.

### Persistent primary with replication

```bash
: "${TEMPOKV_REPLICATION_TOKEN:?set a non-trivial secret of at least 16 bytes}"
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/primary \
  --persistence-enabled=true \
  --authentication-enabled=false \
  --replication-enabled=true \
  --node-role=PRIMARY \
  --node-id=primary
```

### Persistent replica

```bash
: "${TEMPOKV_REPLICATION_TOKEN:?set the same secret used by the primary}"
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/replica \
  --persistence-enabled=true \
  --authentication-enabled=false \
  --replication-enabled=true \
  --node-role=REPLICA \
  --node-id=replica \
  --primary-host=127.0.0.1 \
  --primary-replication-port=6381 \
  --resp-port=7379 \
  --sql-port=7380 \
  --replication-port=7381
```

### Properties file

```properties
tempokv.bind.address=127.0.0.1
tempokv.data.dir=./data/properties-node
tempokv.persistence.enabled=true
tempokv.security.authentication.enabled=true
tempokv.security.authentication.username=operator
tempokv.security.authentication.password=replace-with-a-private-value
tempokv.history.retention=PT720H
```

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --config=./tempokv.properties
```

Protect a configuration file containing credentials with filesystem
permissions and do not commit it.

### Environment variables for Docker

```bash
export TEMPOKV_REPLICATION_TOKEN='<replace-with-a-private-secret-of-16+-bytes>'
docker compose up --build
```

The Compose demonstration explicitly disables client authentication, opts into
cleartext transport inside its isolated network, persists primary and replica
data in separate volumes, and publishes client ports only on host loopback.

## Invalid configurations and startup failures

- Authentication enabled without both credentials fails before the server
  starts. Authentication disabled while credentials are supplied also fails.
- Replication enabled without a valid explicit token fails. Empty, short,
  trivial, low-variety, and over-limit tokens are rejected.
- A `REPLICA` with replication disabled is rejected.
- Replication without persistence is rejected.
- A non-loopback bind, or a replica using a non-loopback primary host, is
  rejected unless insecure remote transport is explicitly allowed.
- RESP, SQL, and replication listener ports must be different when nonzero.
- Malformed, overflowing, out-of-range, or incoherent limits and timeouts are
  rejected with the relevant option name.
- Startup fails when another process already holds the data-directory lock.

## Security boundary

TempoKV has no native TLS and does not support direct exposure to the public
Internet. Loopback is the default. Cleartext remote transport requires explicit
opt-in and should be limited to a trusted private network. For other networks,
place TempoKV behind a proxy, tunnel, or service mesh that terminates TLS.

There are no default client credentials or usable default replication tokens.
Authentication is enabled by default, so the default values intentionally fail
startup until credentials are supplied or authentication is explicitly
disabled. Do not commit passwords, replication tokens, or credential-bearing
properties files.

## Docker Compose

The primary-replica demonstration requires `TEMPOKV_REPLICATION_TOKEN`. Provide
the same value to both services through the shell:

```bash
TEMPOKV_REPLICATION_TOKEN='<replace-with-a-private-secret-of-16+-bytes>' \
  docker compose up --build
```

The primary healthcheck sends a functional RESP `PING`. Compose starts the
replica only after the primary reports healthy.
