# First use

This tutorial starts one local TempoKV node, writes two versions of a value, and
reads both its current and historical state.

## Prerequisites

- JDK 25
- a POSIX-like shell
- `redis-cli`

Docker is not required for this tutorial.

## 1. Build TempoKV

From the repository root:

```bash
./gradlew clean build
```

The executable JAR is created at `build/libs/tempokv-0.1.0.jar`.

## 2. Start a local node

Use a new data directory so the first commit version is predictable:

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/tutorial \
  --persistence-enabled=true \
  --authentication-enabled=false
```

This is a deliberate local-only setup:

- the default bind address is `127.0.0.1`;
- RESP listens on port `6379`;
- SQL listens on port `6380`;
- authentication is disabled explicitly;
- committed changes are persisted in the selected data directory.

Leave this process running.

## 3. Connect

Open another terminal:

```bash
redis-cli -p 6379
```

Check the connection:

```text
127.0.0.1:6379> PING
PONG
```

## 4. Write and read a value

Create the first version:

```text
127.0.0.1:6379> SET profile first
OK
127.0.0.1:6379> GET profile
"first"
```

Create a second version:

```text
127.0.0.1:6379> SET profile second
OK
127.0.0.1:6379> GET profile
"second"
```

Read the value as it existed at version 1:

```text
127.0.0.1:6379> GETAT profile VERSION 1
"first"
```

`GETAT` does not change the current value. `GET profile` still returns
`"second"`.

If the data directory was not empty, run `HISTORY profile` and use one of the
reported retained versions instead of assuming version 1.

## 5. Stop safely

Exit `redis-cli`, then press `Ctrl+C` in the server terminal. The shutdown hook
closes the network endpoints, stops background workers, publishes a final
snapshot when persistence is enabled, and releases the data-directory lock.

## Troubleshooting

### Startup requires authentication credentials

Authentication is enabled by default and has no default credentials. For a
secured local node, replace `--authentication-enabled=false` with explicit
values:

```bash
--authentication-enabled=true \
--authentication-username=operator \
--authentication-password='<choose-a-secret>'
```

Then authenticate with `AUTH operator <choose-a-secret>` before other commands.

### Port already in use

Select unused ports with `--resp-port=<port>` and `--sql-port=<port>`, then pass
the RESP port to `redis-cli`.

### Non-loopback bind is rejected

TempoKV has no native TLS. A non-loopback bind must explicitly set
`--allow-insecure-remote-transport=true` and should be used only on a trusted
network or behind TLS termination.

### A second node cannot use the same directory

Each running node needs an exclusive data directory. Stop the first node or
choose a different `--data-dir`.

## Next steps

- Try more operations in the [command cookbook](command-cookbook.md).
- Read the end-to-end behavior in the [use cases](use-cases.md).
