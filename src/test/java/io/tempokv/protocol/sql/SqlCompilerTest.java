package io.tempokv.protocol.sql;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java_cup.runtime.Symbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the generated SQL front end and the boundary between syntax, semantics, and planning. */
class SqlCompilerTest {
    /** Produces stable token kinds, values, and one-based source positions from the JFlex lexer. */
    @Test
    void tokenizesPointSelectWithPositions() throws Exception {
        TempoLexer lexer = new TempoLexer(new StringReader(
                "SELECT value FROM tempokv WHERE key = 'Ada';"));
        List<Symbol> tokens = new ArrayList<>();
        Symbol token;
        do {
            token = lexer.next_token();
            tokens.add(token);
        } while (token.sym != SqlSymbols.EOF);

        assertEquals(
                List.of(
                        SqlSymbols.SELECT,
                        SqlSymbols.VALUE,
                        SqlSymbols.FROM,
                        SqlSymbols.IDENT,
                        SqlSymbols.WHERE,
                        SqlSymbols.KEY,
                        SqlSymbols.EQ,
                        SqlSymbols.STRING,
                        SqlSymbols.SEMICOLON,
                        SqlSymbols.EOF),
                tokens.stream().map(symbol -> symbol.sym).toList());
        assertEquals("Ada", tokens.get(7).value);
        assertEquals(1, tokens.getFirst().left);
        assertEquals(1, tokens.getFirst().right);
        assertEquals(8, tokens.get(1).right);
    }

    /** Builds an immutable AST for temporal SELECT and transaction-control grammar. */
    @Test
    void parsesTemporalAndTransactionStatements() {
        Statement.Select select = assertInstanceOf(
                Statement.Select.class,
                parse("SELECT value FROM tempokv AS OF VERSION 7 WHERE key = 'profile';"));
        assertEquals("profile", select.predicate().value().value());
        assertEquals(7L, select.asOf().version());

        Statement.TransactionControl begin = assertInstanceOf(
                Statement.TransactionControl.class,
                parse("BEGIN;"));
        assertEquals(Statement.TransactionControl.Kind.BEGIN, begin.kind());
    }

    /** Produces a bounded relational history plan with explicit projection and operators. */
    @Test
    void plansProjectedFilteredHistoryWithoutFullScan() {
        ExecutionPlan.HistoryLookup plan = assertInstanceOf(
                ExecutionPlan.HistoryLookup.class,
                new SqlCompiler().compile(
                        "SELECT version, value FROM HISTORY('profile') "
                                + "WHERE version >= 2 ORDER BY version ASC LIMIT 3 OFFSET 1;"));

        assertEquals(List.of("version", "value"), plan.columns());
        assertEquals(2L, plan.minimumVersion());
        assertEquals(Statement.Order.Direction.ASC, plan.direction());
        assertEquals(3, plan.limit());
        assertEquals(1, plan.offset());
        assertEquals(1_000, plan.command().limit());
    }

    /** Keeps lexical, syntactic, and semantic failures distinct and rejects point full scans. */
    @Test
    void distinguishesCompilationFailures() {
        SqlException lexical = assertThrows(
                SqlException.class,
                () -> new SqlCompiler().compile("SELECT @;"));
        assertEquals(SqlException.Kind.LEXICAL, lexical.kind());
        assertEquals(1, lexical.line());
        assertEquals(8, lexical.column());

        SqlException syntax = assertThrows(
                SqlException.class,
                () -> new SqlCompiler().compile("SELECT value FROM;"));
        assertEquals(SqlException.Kind.SYNTAX, syntax.kind());

        SqlException semantic = assertThrows(
                SqlException.class,
                () -> new SqlCompiler().compile(
                        "SELECT value FROM tempokv;"));
        assertEquals(SqlException.Kind.SEMANTIC, semantic.kind());
        assertEquals(
                "point lookup requires WHERE key = '...'",
                semantic.getMessage());

        SqlException bounded = assertThrows(
                SqlException.class,
                () -> new SqlCompiler().compile(
                        "HISTORY 'profile' LIMIT 1001;"));
        assertEquals(SqlException.Kind.SEMANTIC, bounded.kind());
        assertEquals(
                "Invalid SQL history offset or limit",
                bounded.getMessage());
    }

    private static Statement parse(String sql) {
        return new TempoParser(new TempoLexer(new StringReader(sql)))
                .parseStatement();
    }
}
