# TempoKV

TempoKV is a durable temporal key-value server. Stages E1–E6 provide
configuration and lifecycle management, a non-blocking RESP2 endpoint, current
key-value commands, MVCC history, binary diffs, append-only restoration, and
configurable history retention. E5 adds a segmented WAL, recovery, active
expiration, validated snapshots, and conservative WAL compaction. E6 adds a
JFlex/Java CUP SQL front end that compiles into the same commands and handlers
used by RESP.

## Requirements

- JDK 25 for local Gradle builds.
- Docker with Compose for the container workflow.

## Build and verification

```bash
./gradlew clean build
./gradlew test
./gradlew integrationTest
```

`check` runs unit tests and every integration smoke test implemented through
E6. The executable JAR is written to `build/libs/tempokv-0.1.0.jar`.
Combined unit/integration coverage is written to
`build/reports/jacoco/jacocoAllReport/html/index.html`.

## Run locally

```bash
java -jar build/libs/tempokv-0.1.0.jar --data-dir=./data
```

The process accepts RESP clients on `--resp-port` (default `6379`) and textual
SQL clients on `--sql-port` (default `6380`) until normal shutdown, when it
closes network resources and releases the data-directory lock. Both endpoints
bind all interfaces; use host firewall/container port publishing to limit
exposure.

## Run with Docker

```bash
docker compose up --build
redis-cli -p 6379 PING
printf "SELECT value FROM tempokv WHERE key = 'profile';" | nc 127.0.0.1 6380
```

Compose publishes RESP on `127.0.0.1:6379` and SQL on
`127.0.0.1:6380`, enables persistence, and persists `/data` in a named volume.
Stop the node with `Ctrl+C` or `docker compose down`.

## Configuration

Configuration precedence is command-line option, environment variable,
optional UTF-8 `.properties` file, then default.

| Option | Environment variable | Default |
| --- | --- | --- |
| `--resp-port` | `TEMPOKV_RESP_PORT` | `6379` |
| `--sql-port` | `TEMPOKV_SQL_PORT` | `6380` |
| `--data-dir` | `TEMPOKV_DATA_DIR` | `data` |
| `--node-role` | `TEMPOKV_NODE_ROLE` | `PRIMARY` |
| `--history-retention` | `TEMPOKV_HISTORY_RETENTION` | `PT720H` |
| `--persistence-enabled` | `TEMPOKV_PERSISTENCE_ENABLED` | `false` |
| `--authentication-enabled` | `TEMPOKV_AUTHENTICATION_ENABLED` | `false` |

Use `--config=/path/to/tempokv.properties` or `TEMPOKV_CONFIG` to select the
optional file. File keys use the `tempokv.*` names accepted by
`ServerConfiguration`.

## RESP commands through E5

Current-state commands:

```text
PING
SET key value
GET key
DEL key
EXPIRE key seconds
TTL key
```

Temporal commands:

```text
GETAT key VERSION version
GETAT key TIMESTAMP 2026-01-01T00:00:00Z
HISTORY key [offset [limit]]
DIFF key first-version second-version
RESTOREAT key version
```

The exact response shapes are documented in
[the RESP protocol note](docs/04_Protocolo_RESP_Suportado.md).

## SQL through E6

Each SQL statement is terminated by `;`. The endpoint supports point
`SELECT`, `UPSERT`, `DELETE`, `AS OF VERSION|TIMESTAMP`, projected and bounded
`HISTORY`, `DIFF`, and append-only `RESTORE`. For example:

```sql
UPSERT INTO tempokv (key, value) VALUES ('profile', 'first');
SELECT value FROM tempokv AS OF VERSION 1 WHERE key = 'profile';
SELECT version, value FROM HISTORY('profile')
ORDER BY version DESC LIMIT 10;
RESTORE 'profile' TO VERSION 1;
```

The exact grammar and tabular response framing are documented in
[the SQL language note](docs/06_Linguagem_SQL_Suportada.md).
