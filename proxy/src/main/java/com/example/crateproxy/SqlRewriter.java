package com.example.crateproxy;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

import java.sql.SQLException;

/**
 * Central SQL rewrite pipeline.
 * Rules are applied sequentially. Returns null for swallowed statements.
 * Plans 2 and 3 will add DDL rewrite rules to this class.
 *
 * Decisions honored:
 *   D-01: throw SQLException on parse failure
 *   D-02: log every rewrite to System.err unconditionally
 *   D-03: CREATE/ALTER SEQUENCE swallowed (added in Plan 2)
 *   D-04: nextval() throws SQLException (added in Plan 2)
 */
public class SqlRewriter {

    /**
     * Rewrite sql for CrateDB compatibility.
     * @return null if the statement should be swallowed (caller returns no-op),
     *         or the (possibly modified) SQL string to forward to CrateDB.
     * @throws SQLException on parse failure (D-01) or nextval() detection (D-04).
     */
    public static String rewrite(String sql) throws SQLException {
        if (sql == null) return null;
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) return sql;

        // Rule 1: Transaction swallowing (string match — fast path, high volume)
        // Per D-03 / PRXY-03: BEGIN, COMMIT, ROLLBACK and variants never reach CrateDB
        if (isTransactionCommand(trimmed)) {
            return null;
        }

        // Rule 2: SELECT FOR UPDATE stripping (PRXY-04)
        // Liquibase lock service issues SELECT ... FOR UPDATE as the very first SQL.
        // Must be intercepted before any DDL handling or CrateDB rejects it.
        // In JSQLParser 5.3, FOR UPDATE properties live on the Select statement, not PlainSelect.
        String upper = trimmed.toUpperCase();
        if (upper.contains("FOR UPDATE")) {
            Statement stmt = parseSql(sql);
            if (stmt instanceof Select select) {
                if (select.getForUpdateTable() != null || select.getForMode() != null) {
                    select.setForUpdateTable(null);
                    select.setForMode(null);
                    select.setNoWait(false);
                    select.setSkipLocked(false);
                    select.setWait(null);
                    String rewritten = select.toString();
                    logRewrite(sql, rewritten);
                    return rewritten;
                }
            }
            // Not a plain SELECT FOR UPDATE — fall through to DDL rules below
        }

        // Rules 3-9: DDL rewrites are added by Plans 2 and 3.
        // For now, pass all non-transaction SQL through unchanged.
        // Plans 2 and 3 insert their rewrite branches here (CreateTable, Alter, CreateIndex, etc.)

        return sql;
    }

    // Package-private so Plans 2 and 3 can call from the same class.
    static Statement parseSql(String sql) throws SQLException {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SQLException("CRATE PROXY: Cannot parse SQL: " + sql, e);
        }
    }

    static void logRewrite(String original, String rewritten) {
        // D-02: unconditional System.err logging of every rewrite
        if (!rewritten.equals(original)) {
            System.err.println("[CRATE PROXY] REWRITE:\n  ORIGINAL: " + original + "\n  REWRITTEN: " + rewritten);
        }
    }

    private static boolean isTransactionCommand(String trimmed) {
        // Per RESEARCH.md §SqlRewriter — Transaction Command Detection
        // Covers: BEGIN, COMMIT, ROLLBACK, ROLLBACK TO SAVEPOINT, SAVEPOINT name,
        //         RELEASE SAVEPOINT, START TRANSACTION, SET TRANSACTION ISOLATION LEVEL ...
        String upper = trimmed.toUpperCase();
        return upper.equals("BEGIN")
            || upper.equals("COMMIT")
            || upper.equals("ROLLBACK")
            || upper.startsWith("ROLLBACK TO")
            || upper.startsWith("SAVEPOINT")
            || upper.startsWith("RELEASE SAVEPOINT")
            || upper.startsWith("START TRANSACTION")
            || upper.startsWith("SET TRANSACTION");
    }
}
