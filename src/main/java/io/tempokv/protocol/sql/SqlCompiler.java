package io.tempokv.protocol.sql;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Compiles one bounded SQL statement through JFlex, Java CUP, semantic analysis, and planning.
 */
public final class SqlCompiler {
    private static final int MAX_STATEMENT_BYTES = 1_048_576;
    private final SqlSemanticAnalyzer analyzer;
    private final SqlPlanner planner;

    /** Creates a compiler using the production semantic analyzer and logical planner. */
    public SqlCompiler() {
        this(new SqlSemanticAnalyzer(), new SqlPlanner());
    }

    /** Creates a compiler with explicit stateless compilation phases. */
    public SqlCompiler(SqlSemanticAnalyzer analyzer, SqlPlanner planner) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /** Compiles exactly one SQL statement and rejects blank or oversized input before lexing. */
    public ExecutionPlan compile(String sql) {
        String source = Objects.requireNonNull(sql, "sql");
        if (source.isBlank()) {
            throw new SqlException(SqlException.Kind.SYNTAX, "statement must not be blank", 1, 1);
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_STATEMENT_BYTES) {
            throw new SqlException(
                    SqlException.Kind.LEXICAL,
                    "statement exceeds 1 MiB",
                    1,
                    1);
        }
        Statement statement = new TempoParser(
                new TempoLexer(new StringReader(source)))
                .parseStatement();
        return planner.plan(analyzer.analyze(statement));
    }
}
