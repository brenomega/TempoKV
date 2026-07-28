# TempoKV

TempoKV is an in-memory temporal key-value server. Stages E1–E4 provide
configuration and lifecycle management, a non-blocking RESP2 endpoint, current
key-value commands, MVCC history, binary diffs, append-only restoration, and
configurable history retention.

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
E4. The executable JAR is written to `build/libs/tempokv-0.1.0.jar`.
Combined unit/integration coverage is written to
`build/reports/jacoco/jacocoAllReport/html/index.html`.

## Run locally

```bash
java -jar build/libs/tempokv-0.1.0.jar --data-dir=./data
```

The process accepts RESP clients on `--resp-port` (default `6379`) until normal
shutdown, when it closes network resources and releases the data-directory
lock.

## Run with Docker

```bash
docker compose up --build
redis-cli -p 6379 PING
```

Compose publishes RESP on `127.0.0.1:6379`/`6379` and persists `/data` in a
named volume. Stop the node with `Ctrl+C` or `docker compose down`.

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

## RESP commands through E4

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
