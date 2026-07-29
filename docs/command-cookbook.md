# Command cookbook

The RESP examples use `redis-cli -p 6379`. Commands are shown in the form typed
at its prompt. SQL examples can be sent to the textual endpoint on port `6380`;
each statement must end with `;`, and each response is a tab-separated table
terminated by a blank line.

Examples assume either an explicitly unauthenticated loopback node or an
already authenticated session.

## Administration and authentication

### Authenticate a RESP session

```text
AUTH operator <password>
```

Success returns `OK`. Invalid credentials return an error and do not change the
current session identity. Authentication must be configured at startup; there
is no default user or password.

SQL sessions use:

```sql
AUTH operator <password>;
```

The result has a single `status` column containing `OK`.

### Check connectivity

RESP:

```text
PING
```

Expected result: `PONG`.

SQL:

```sql
PING;
```

Expected shape:

```text
status
PONG
```

## Current key-value operations

### Set and get

```text
SET profile Ada
GET profile
```

`SET` returns `OK`. `GET` returns the stored bulk string or nil when no current
value is visible. Every successful `SET` outside a transaction creates one new
global commit version.

### Delete

```text
DEL profile
```

Returns `1` when a visible value was deleted and `0` when no current value
existed. Deletion appends a tombstone; it does not erase retained history.

### SQL equivalents

```sql
UPSERT INTO tempokv (key, value) VALUES ('profile', 'Ada');
SELECT key, value FROM tempokv WHERE key = 'profile';
DELETE FROM tempokv WHERE key = 'profile';
```

`UPSERT` returns `status=OK`, point `SELECT` returns zero or one row, and
`DELETE` returns an `affected` count. Only the logical table `tempokv` is
supported, and point reads require `WHERE key = '...'`.

## TTL

### Add an expiration

```text
SET session value
EXPIRE session 60
TTL session
```

`EXPIRE` returns `1` for an existing visible key and `0` otherwise. `TTL`
returns remaining whole seconds, `-1` for a value without expiration, and `-2`
for a missing or expired current value.

Expiration creates a version carrying the deadline. Active expiration later
commits an expiration tombstone through the normal commit/WAL path. There is no
SQL TTL syntax.

## Historical reads

### Read by version

```text
GETAT profile VERSION 1
```

Returns the newest retained value whose global commit version is at most the
requested version. Global versions for one key need not be contiguous.

### Read by timestamp

```text
GETAT profile TIMESTAMP 2026-01-01T00:00:00Z
```

Returns the newest retained value committed at or before the ISO-8601 instant.
A point before the key existed returns nil. A tombstone returns a deletion
error; a point removed by retention returns a distinct history-unavailable
error.

SQL equivalents:

```sql
SELECT value FROM tempokv AS OF VERSION 1 WHERE key = 'profile';
SELECT value FROM tempokv
AS OF TIMESTAMP '2026-01-01T00:00:00Z'
WHERE key = 'profile';
```

## History

```text
HISTORY profile
HISTORY profile 10 25
```

Arguments are `HISTORY key [offset [limit]]`. The default limit is 100 and the
maximum is 1000. Results are newest first; each entry contains:

1. commit version;
2. commit timestamp as epoch milliseconds;
3. the value, or the simple string `TOMBSTONE`.

An unknown key returns an error. An offset beyond the retained list returns an
empty array.

SQL recipes:

```sql
HISTORY 'profile' LIMIT 25 OFFSET 10;
```

```sql
SELECT version, committed_at, state, value
FROM HISTORY('profile')
WHERE version >= 1
ORDER BY version DESC
LIMIT 25 OFFSET 0;
```

SQL history can project `version`, `committed_at`, `state`, and `value`, filter
with `version >=`, order by version, and paginate. At most 1000 retained
versions are examined for one statement.

## Diff

```text
DIFF profile 1 2
```

RESP `DIFF` compares version coordinates and returns five fields:

1. before state: `VALUE`, `DELETED`, or `MISSING`;
2. after state;
3. common binary-prefix length;
4. remaining before suffix, or nil;
5. remaining after suffix, or nil.

SQL supports version or timestamp coordinates:

```sql
DIFF 'profile' BETWEEN VERSION 1 AND VERSION 2;
```

```sql
DIFF 'profile'
BETWEEN TIMESTAMP '2026-01-01T00:00:00Z'
AND TIMESTAMP '2026-01-02T00:00:00Z';
```

The SQL result columns are `before_state`, `after_state`, `common_prefix`,
`before_suffix`, and `after_suffix`.

## Restoration

```text
RESTOREAT profile 1
```

Outside a transaction, the result is the new commit version. Restoration
copies the retained value, tombstone, and applicable expiration metadata into
a new head commit; it never removes the versions created after the source.

SQL:

```sql
RESTORE 'profile' TO VERSION 1;
```

The result contains the new `version`. Timestamp restoration is not part of
the SQL grammar.

## Transactions

```text
BEGIN
SET left L
SET right R
GET left
COMMIT
```

For the recipe above, `BEGIN`, both `SET` operations, and a successful
`COMMIT` return `OK`; reads see a stable snapshot plus the session's own staged
writes. Other staged commands retain their normal response shape
(`RESTOREAT`, for example, returns `QUEUED`). All staged mutations are published
in one commit version.

To discard the write set:

```text
ROLLBACK
```

Concurrent transactions that write the same key are checked at commit. A
conflicting commit returns an error and publishes neither a version nor WAL
data. Nested transactions and `COMMIT`/`ROLLBACK` without an active transaction
are rejected. Closing the connection rolls back its active transaction.

SQL uses the same transaction manager:

```sql
BEGIN;
UPSERT INTO tempokv (key, value) VALUES ('left', 'L');
UPSERT INTO tempokv (key, value) VALUES ('right', 'R');
SELECT value FROM tempokv WHERE key = 'left';
COMMIT;
```

## Health and diagnostics

```text
HEALTH
INFO
```

`HEALTH` returns name/value pairs including `status`, `code`, `reason`, and
`updated_at`. `INFO` returns ordered name/value pairs for role, current version,
connections, persistence, transactions, replication, counters, gauges, and
latency percentiles. Neither command reads user keys or values.

SQL equivalents:

```sql
HEALTH;
INFO;
```

Both return `name` and `value` columns.

## Replication inspection

When replication is configured, `INFO` includes:

- `replication.role`;
- `replication.state`;
- `replication.applied_version`;
- `replication.acknowledged_version`;
- `replication.primary_version`;
- `replication.lag`;
- `replication.replicas_connected`.

Replication configuration is startup-only. Replica public endpoints allow
reads and diagnostics but reject mutations with a read-only error. TempoKV does
not implement automatic promotion, election, or failover.

## Protocol and data limits

- RESP requests must be arrays whose command and arguments are bulk strings.
- Keys are limited to 1 MiB and values to 16 MiB.
- History pages are limited to 1000 entries and temporal response payloads to
  16 MiB.
- SQL statements are limited to 1 MiB and do not support full-table scans.
- SQL strings escape an apostrophe as `''`.
- SQL result cells use `\N` for null and `base64:` for non-UTF-8 bytes.
