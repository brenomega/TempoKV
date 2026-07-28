# TempoKV

TempoKV is a temporal key-value server. Stage 1 provides the executable bootstrap,
configuration, exclusive data-directory lock, lifecycle health, and basic metrics.
Stage 2 adds a RESP2 TCP endpoint with the administrative `PING` command;
storage commands remain planned for later stages.

## Requirements

- JDK 25 for local Gradle builds.
- Docker with Compose for the container workflow.

## Build and verification

```bash
./gradlew clean build
./gradlew integrationTest
```

`check` runs unit tests and the UC-00 integration suite. The executable JAR is
written to `build/libs/tempokv-0.1.0.jar`.

## Run locally

```bash
java -jar build/libs/tempokv-0.1.0.jar --data-dir=./data
```

The process accepts RESP clients on `--resp-port` (default `6379`). It remains running until it
receives a normal shutdown signal, then releases the data-directory lock.

## Run with Docker

```bash
docker compose up --build
```

The Compose volume persists `/data`. Stop the node with `Ctrl+C` or
`docker compose down`; the lock is released during shutdown.

## Configuration

Configuration precedence is: command-line option, environment variable,
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
optional file. Its keys use the `tempokv.*` names documented in
`ServerConfiguration`.

## RESP in Stage 2

Use an official Redis client to verify the UC-02 flow:

```bash
redis-cli -p 6379 PING
```

The response is `PONG`. The currently supported wire format and command scope
are documented in [the RESP protocol note](docs/04_Protocolo_RESP_Suportado.md).
