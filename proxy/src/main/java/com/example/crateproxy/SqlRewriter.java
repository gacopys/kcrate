package com.example.crateproxy;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.sequence.CreateSequence;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.select.Select;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

        // Parse the SQL for DDL analysis (only when not already handled above)
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SQLException("CRATE PROXY: Cannot parse SQL: " + sql, e);  // D-01
        }

        // D-03: Swallow CREATE SEQUENCE and ALTER SEQUENCE silently
        if (stmt instanceof CreateSequence || isAlterSequence(stmt)) {
            System.err.println("[CRATE PROXY] SWALLOW SEQUENCE: " + sql);
            return null;
        }

        // D-04: nextval() in any surviving statement is forbidden
        if (sql.toLowerCase().contains("nextval(")) {
            throw new SQLException("CRATE PROXY: nextval() not supported: " + sql);
        }

        String result = sql;

        // PRXY-05, PRXY-06, PRXY-07: CREATE TABLE rewriting
        if (stmt instanceof CreateTable ct) {
            result = rewriteCreateTable(ct, sql);
        }
        // PRXY-08: ALTER TABLE rewriting
        else if (stmt instanceof Alter alter) {
            result = rewriteAlter(alter, sql);
        }
        // Plan 3: CREATE INDEX rewriting (added in Plan 3)

        // D-02: log every rewrite
        logRewrite(sql, result);
        return result;
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
        if (rewritten != null && !rewritten.equals(original)) {
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

    private static String rewriteCreateTable(CreateTable ct, String originalSql) {
        boolean modified = false;

        // PRXY-05: Strip FOREIGN KEY index entries from CREATE TABLE
        // Keycloak DDL includes inline FK declarations like:
        //   CONSTRAINT fk_role FOREIGN KEY (role_id) REFERENCES keycloak_role(id)
        // JSQLParser 5.3: ForeignKeyIndex is a subclass of Index; also check getType() as fallback
        if (ct.getIndexes() != null) {
            List<Index> filtered = ct.getIndexes().stream()
                .filter(idx -> !(idx instanceof ForeignKeyIndex)
                            && !"FOREIGN KEY".equalsIgnoreCase(idx.getType()))
                .collect(Collectors.toList());
            if (filtered.size() != ct.getIndexes().size()) {
                ct.setIndexes(filtered);
                modified = true;
            }
        }

        // PRXY-06: Strip UNIQUE constraints from CREATE TABLE index list
        // Inline UNIQUE constraints like: CONSTRAINT uc_name UNIQUE (col)
        if (ct.getIndexes() != null) {
            List<Index> filtered = ct.getIndexes().stream()
                .filter(idx -> !"UNIQUE".equalsIgnoreCase(idx.getType())
                            && !"UNIQUE KEY".equalsIgnoreCase(idx.getType()))
                .collect(Collectors.toList());
            if (filtered.size() != ct.getIndexes().size()) {
                ct.setIndexes(filtered);
                modified = true;
            }
        }

        // PRXY-07: Remap unsupported column types
        if (ct.getColumnDefinitions() != null) {
            for (ColumnDefinition col : ct.getColumnDefinitions()) {
                if (remapColumnType(col)) {
                    modified = true;
                }
            }
        }

        // Note: WITH (number_of_replicas = '1') injection is PRXY-11, added in Plan 3.

        if (!modified) return originalSql;  // unchanged — avoid JSQLParser round-trip noise

        return ct.toString();
    }

    /**
     * Remaps Keycloak/Liquibase abstract types to CrateDB-compatible equivalents.
     * Per REQUIREMENTS.md PRXY-07 (takes precedence over CLAUDE.md type table).
     * Returns true if the type was changed.
     *
     * Type inventory (from grep of all 74 Keycloak 26.5.2 changelogs):
     *   CLOB        → TEXT     (20 columns: large text fields)
     *   NCLOB       → TEXT     (9 columns: unicode large text)
     *   BINARY(n)   → BLOB     (4 columns: 64-byte SHA-256 hashes — strip length)
     *   TINYBLOB    → BLOB     (3 columns: deprecated CREDENTIAL.SALT)
     *   NVARCHAR(n) → VARCHAR(n) (4 columns: strip N prefix, keep length)
     *   TINYINT     → SMALLINT (4 columns: boolean-style int flags)
     *   TEXT(n)     → TEXT     (1 column: strip length parameter)
     *
     * NOTE: bytea, uuid, jsonb do NOT appear in Keycloak DDL — no remapping needed.
     */
    private static boolean remapColumnType(ColumnDefinition col) {
        ColDataType dt = col.getColDataType();
        if (dt == null) return false;
        String typeName = dt.getDataType();
        if (typeName == null) return false;
        // JSQLParser 5.3 may embed length in the type name token (e.g. "BINARY (64)" or "NVARCHAR (255)")
        // so we extract the base type name by trimming at the first space or paren.
        String base = typeName.split("\\s|\\(")[0].toUpperCase();
        switch (base) {
            case "CLOB":
            case "NCLOB":
                dt.setDataType("TEXT");
                dt.setArgumentsStringList(null);  // TEXT has no length parameter
                return true;
            case "BINARY":
                // BINARY(n) → BLOB (no length parameter in CrateDB)
                dt.setDataType("BLOB");
                dt.setArgumentsStringList(null);
                return true;
            case "TINYBLOB":
                dt.setDataType("BLOB");
                dt.setArgumentsStringList(null);
                return true;
            case "NVARCHAR":
                // NVARCHAR(n) → VARCHAR(n) — strip N prefix, keep length
                // JSQLParser may store as "NVARCHAR (255)" — extract length and rebuild
                {
                    String args = typeName.contains("(") ? typeName.substring(typeName.indexOf('(')) : null;
                    if (args != null) {
                        dt.setDataType("VARCHAR " + args);
                    } else {
                        dt.setDataType("VARCHAR");
                    }
                    dt.setArgumentsStringList(null);
                }
                return true;
            case "TINYINT":
                dt.setDataType("SMALLINT");
                return true;
            case "TEXT":
                // Strip spurious length parameter: TEXT(25500) → TEXT
                // May appear as "TEXT (25500)" in the type name token or as separate args
                if (typeName.contains("(")) {
                    dt.setDataType("TEXT");
                    dt.setArgumentsStringList(null);
                    return true;
                }
                if (dt.getArgumentsStringList() != null && !dt.getArgumentsStringList().isEmpty()) {
                    dt.setArgumentsStringList(null);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private static boolean isAlterSequence(Statement stmt) {
        // JSQLParser 5.x: AlterSequence may be net.sf.jsqlparser.statement.alter.AlterSequence
        // or net.sf.jsqlparser.statement.create.sequence.AlterSequence depending on version.
        // Use class name check as fallback if import doesn't resolve.
        return stmt.getClass().getSimpleName().equals("AlterSequence");
    }

    /**
     * Strips unsupported ALTER TABLE operations for CrateDB compatibility (PRXY-08).
     *
     * Operations stripped:
     *   - ADD CONSTRAINT ... FOREIGN KEY (195 in Keycloak changelogs)
     *   - ADD CONSTRAINT ... UNIQUE (63 in Keycloak changelogs)
     *   - DROP CONSTRAINT (FK and UNIQUE)
     *   - MODIFY / CHANGE COLUMN type (56 modifyDataType operations)
     *   - SET NOT NULL / ADD NOT NULL CONSTRAINT (34 addNotNullConstraint operations)
     *   - DROP NOT NULL
     *   - ALTER COLUMN ... TYPE
     *
     * If all operations in the ALTER TABLE are stripped, the entire statement is swallowed
     * (returns null) to avoid forwarding a bare ALTER TABLE tablename to CrateDB.
     * Per RESEARCH.md Pitfall 4.
     *
     * Returns null if the entire statement is swallowed, else the rewritten SQL string.
     */
    private static String rewriteAlter(Alter alter, String originalSql) {
        List<AlterExpression> expressions = alter.getAlterExpressions();
        if (expressions == null || expressions.isEmpty()) {
            return null;  // nothing to forward
        }

        List<AlterExpression> keepList = new ArrayList<>();
        boolean anyStripped = false;

        for (AlterExpression expr : expressions) {
            if (shouldStripAlterExpression(expr)) {
                anyStripped = true;
                System.err.println("[CRATE PROXY] STRIP ALTER EXPR: " + expr.getOperation()
                    + (expr.getConstraintName() != null ? " CONSTRAINT " + expr.getConstraintName() : ""));
            } else {
                keepList.add(expr);
            }
        }

        if (!anyStripped) return originalSql;  // unchanged — avoid round-trip noise

        if (keepList.isEmpty()) {
            // All operations stripped — swallow the entire ALTER TABLE statement
            System.err.println("[CRATE PROXY] SWALLOW ALTER TABLE (all ops stripped): " + originalSql);
            return null;
        }

        // Replace expressions with the filtered list and re-serialize
        alter.setAlterExpressions(keepList);
        String result = alter.toString();
        logRewrite(originalSql, result);
        return result;
    }

    /**
     * Returns true if this AlterExpression should be stripped (not forwarded to CrateDB).
     *
     * JSQLParser 5.3 AlterOperation enum values relevant here:
     *   ADD         — check for FK (getFkSourceColumns) or UNIQUE (getUk()) to detect constraint additions
     *   MODIFY      — column type change (MySQL syntax) — strip all
     *   ALTER       — ALTER COLUMN ... TYPE or SET/DROP NOT NULL (PostgreSQL syntax) — strip all
     *   DROP        — DROP CONSTRAINT (when constraint name present — FK or UNIQUE)
     *   DROP_FOREIGN_KEY — explicit FK drop operation
     *   DROP_UNIQUE      — explicit UNIQUE drop operation
     *   CHANGE      — MySQL CHANGE COLUMN — strip all
     */
    private static boolean shouldStripAlterExpression(AlterExpression expr) {
        if (expr == null || expr.getOperation() == null) return false;

        switch (expr.getOperation()) {
            case ADD:
                // ADD CONSTRAINT ... FOREIGN KEY: getFkSourceColumns() is non-null and non-empty
                if (expr.getFkSourceColumns() != null && !expr.getFkSourceColumns().isEmpty()) {
                    return true;  // FOREIGN KEY constraint
                }
                // ADD CONSTRAINT ... UNIQUE: getUk() returns true
                if (expr.getUk()) {
                    return true;  // UNIQUE constraint
                }
                // Fallback: check string representation for FOREIGN KEY or UNIQUE keyword
                {
                    String exprStr = expr.toString().toUpperCase();
                    if (exprStr.contains("FOREIGN KEY") || exprStr.contains(" UNIQUE")) {
                        return true;
                    }
                }
                return false;

            case DROP:
                // DROP CONSTRAINT fk_name or DROP CONSTRAINT uk_name
                if (expr.getConstraintName() != null) {
                    return true;
                }
                return false;

            case DROP_FOREIGN_KEY:
            case DROP_UNIQUE:
                // Explicit FK/UNIQUE drop operations
                return true;

            case MODIFY:
                // All column type modifications (MySQL: ALTER TABLE t MODIFY COLUMN col TYPE)
                return true;

            case ALTER:
            case CHANGE:
                // PostgreSQL ALTER COLUMN: type changes, SET NOT NULL, DROP NOT NULL
                // MySQL CHANGE COLUMN: column rename + type change
                return true;

            default:
                return false;
        }
    }
}
