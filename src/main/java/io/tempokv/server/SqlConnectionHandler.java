package io.tempokv.server;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.protocol.sql.PlanExecutor;
import io.tempokv.protocol.sql.SqlCompiler;
import io.tempokv.protocol.sql.SqlException;
import io.tempokv.protocol.sql.SqlResultEncoder;
import io.tempokv.security.Authenticator;
import io.tempokv.transaction.CommitFailedException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Frames semicolon-terminated SQL text and coordinates compilation, dispatch, and table encoding.
 */
public final class SqlConnectionHandler
        implements ClientConnection.ConnectionProcessor {
    private final Authenticator authenticator;
    private final int maxStatementBytes;
    private final int maxUsernameBytes;
    private final int maxCredentialBytes;
    private final Session session = new Session();
    private final SqlCompiler compiler;
    private final PlanExecutor executor;
    private final SqlResultEncoder encoder;
    private final MetricsRegistry metrics;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean insideString;
    private boolean pendingStringQuote;
    private boolean discardingOversizedStatement;

    /** Creates a handler and authenticates its isolated SQL session. */
    public SqlConnectionHandler(
            Authenticator authenticator,
            SqlCompiler compiler,
            PlanExecutor executor,
            SqlResultEncoder encoder,
            MetricsRegistry metrics) {
        this(
                authenticator,
                compiler,
                executor,
                encoder,
                metrics,
                16 * 1_048_576,
                128,
                4_096);
    }

    /** Creates a SQL handler with explicit command and credential limits. */
    public SqlConnectionHandler(
            Authenticator authenticator,
            SqlCompiler compiler,
            PlanExecutor executor,
            SqlResultEncoder encoder,
            MetricsRegistry metrics,
            int maxCommandBytes,
            int maxUsernameBytes,
            int maxCredentialBytes) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.authenticator.authenticate(session);
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.maxStatementBytes = Math.min(maxCommandBytes, 1_048_576);
        this.maxUsernameBytes = maxUsernameBytes;
        this.maxCredentialBytes = maxCredentialBytes;
    }

    /**
     * Accepts arbitrary UTF-8 chunks and emits one ordered response for every complete statement.
     */
    @Override
    public void onBytes(byte[] bytes, Consumer<byte[]> responses) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(responses, "responses");
        for (byte current : bytes) {
            process(current, responses);
        }
    }

    private void process(byte current, Consumer<byte[]> responses) {
        if (discardingOversizedStatement) {
            discard(current);
            return;
        }
        if (pendingStringQuote) {
            pendingStringQuote = false;
            if (current == '\'') {
                pending.write(current);
                ensureBound(responses);
                return;
            }
            insideString = false;
        }

        if (current == '\'') {
            pending.write(current);
            if (insideString) {
                pendingStringQuote = true;
            } else {
                insideString = true;
            }
            ensureBound(responses);
            return;
        }

        pending.write(current);
        if (current == ';' && !insideString) {
            executePending(responses);
        } else {
            ensureBound(responses);
        }
    }

    private void ensureBound(Consumer<byte[]> responses) {
        if (pending.size() <= maxStatementBytes) {
            return;
        }
        responses.accept(encoder.encodeError(new SqlException(
                SqlException.Kind.LEXICAL,
                "statement exceeds configured limit",
                1,
                1)));
        metrics.incrementCounter("sql.errors.lexical");
        pending.reset();
        discardingOversizedStatement = true;
    }

    private void executePending(Consumer<byte[]> responses) {
        long started = System.nanoTime();
        try {
            String sql = decodeUtf8(pending.toByteArray());
            if (isAuthentication(sql)) {
                authenticate(sql, responses);
            } else {
                responses.accept(encoder.encode(
                        executor.execute(compiler.compile(sql), session)));
            }
            metrics.incrementCounter("sql.statements");
        } catch (SqlException exception) {
            metrics.incrementCounter(
                    "sql.errors." + exception.kind().name().toLowerCase(
                            java.util.Locale.ROOT));
            responses.accept(encoder.encodeError(exception));
        } catch (CommitFailedException | IllegalArgumentException exception) {
            metrics.incrementCounter("sql.errors.execution");
            responses.accept(encoder.encodeError(new SqlException(
                    SqlException.Kind.EXECUTION,
                    exception.getMessage())));
        } catch (RuntimeException exception) {
            metrics.incrementCounter("sql.errors.execution");
            responses.accept(encoder.encodeError(new SqlException(
                    SqlException.Kind.EXECUTION,
                    "statement execution failed")));
        } finally {
            metrics.recordLatency(
                    "sql.latency",
                    Duration.ofNanos(System.nanoTime() - started));
            reset();
        }
    }

    private static boolean isAuthentication(String sql) {
        return sql.stripLeading().regionMatches(
                true, 0, "AUTH ", 0, "AUTH ".length());
    }

    private void authenticate(String sql, Consumer<byte[]> responses) {
        String statement = sql.strip();
        if (!statement.endsWith(";")) {
            throw new IllegalArgumentException(
                    "AUTH statement must end with semicolon");
        }
        String[] credentials = statement
                .substring("AUTH".length(), statement.length() - 1)
                .trim()
                .split("\\s+", 2);
        if (credentials.length != 2
                || credentials[0].getBytes(StandardCharsets.UTF_8).length
                        > maxUsernameBytes
                || credentials[1].getBytes(StandardCharsets.UTF_8).length
                        > maxCredentialBytes) {
            throw new IllegalArgumentException(
                    "Invalid AUTH credentials");
        }
        boolean accepted = authenticator.authenticate(
                session,
                credentials[0],
                credentials[1].getBytes(StandardCharsets.UTF_8));
        if (!accepted) {
            throw new IllegalArgumentException("Invalid AUTH credentials");
        }
        responses.accept(encoder.encode(
                io.tempokv.protocol.sql.SqlResult.row(
                        java.util.List.of("status"), "OK")));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new SqlException(
                    SqlException.Kind.LEXICAL,
                    "statement is not valid UTF-8",
                    1,
                    1);
        }
    }

    private void reset() {
        pending.reset();
        insideString = false;
        pendingStringQuote = false;
        discardingOversizedStatement = false;
    }

    private void discard(byte current) {
        if (pendingStringQuote) {
            pendingStringQuote = false;
            if (current == '\'') {
                return;
            }
            insideString = false;
        }
        if (current == '\'') {
            if (insideString) {
                pendingStringQuote = true;
            } else {
                insideString = true;
            }
            return;
        }
        if (current == ';' && !insideString) {
            reset();
        }
    }

    /** Aborts and releases an active transaction if the SQL connection is lost. */
    @Override public void onClose() {
        session.close();
    }
}
