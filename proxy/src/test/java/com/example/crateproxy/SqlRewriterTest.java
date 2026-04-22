package com.example.crateproxy;

import java.sql.SQLException;

/**
 * Unit-style tests for SqlRewriter Plan 1 rules.
 * Run via: java -cp target/crate-proxy-1.0-SNAPSHOT.jar com.example.crateproxy.SqlRewriterTest
 */
public class SqlRewriterTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;

        // PRXY-03: Transaction swallowing
        failures += check("BEGIN", null, "BEGIN should be swallowed");
        failures += check("begin", null, "begin (lower) should be swallowed");
        failures += check("COMMIT", null, "COMMIT should be swallowed");
        failures += check("ROLLBACK", null, "ROLLBACK should be swallowed");
        failures += check("ROLLBACK TO SAVEPOINT sp1", null, "ROLLBACK TO SAVEPOINT should be swallowed");
        failures += check("SAVEPOINT sp1", null, "SAVEPOINT should be swallowed");
        failures += check("RELEASE SAVEPOINT sp1", null, "RELEASE SAVEPOINT should be swallowed");
        failures += check("START TRANSACTION", null, "START TRANSACTION should be swallowed");
        failures += check("SET TRANSACTION ISOLATION LEVEL READ COMMITTED", null, "SET TRANSACTION should be swallowed");

        // PRXY-04: SELECT FOR UPDATE stripping
        String forUpdateSql = "SELECT id FROM databasechangeloglock WHERE id=1 FOR UPDATE";
        String rewritten;
        try {
            rewritten = SqlRewriter.rewrite(forUpdateSql);
        } catch (SQLException e) {
            System.err.println("FAIL: FOR UPDATE threw: " + e.getMessage());
            rewritten = null;
            failures++;
        }
        if (rewritten != null && rewritten.toUpperCase().contains("FOR UPDATE")) {
            System.err.println("FAIL: FOR UPDATE not stripped. Got: " + rewritten);
            failures++;
        } else if (rewritten != null) {
            System.out.println("PASS: FOR UPDATE stripped. Result: " + rewritten);
        }

        // D-01: Parse failure throws SQLException
        boolean threw = false;
        try {
            SqlRewriter.rewrite("THIS IS NOT VALID SQL AT ALL @@##");
            System.err.println("FAIL: Parse failure should have thrown SQLException");
        } catch (SQLException e) {
            if (e.getMessage().startsWith("CRATE PROXY: Cannot parse SQL:")) {
                System.out.println("PASS: Parse failure threw SQLException with correct message");
                threw = true;
            } else {
                System.err.println("FAIL: Wrong exception message: " + e.getMessage());
            }
        }
        if (!threw) failures++;

        // Normal SELECT passes through unchanged
        String select = "SELECT id, name FROM users WHERE id = 1";
        String result = SqlRewriter.rewrite(select);
        if (!select.equals(result)) {
            System.err.println("FAIL: Plain SELECT was modified. Got: " + result);
            failures++;
        } else {
            System.out.println("PASS: Plain SELECT passed through unchanged");
        }

        if (failures > 0) {
            System.err.println("TOTAL FAILURES: " + failures);
            System.exit(1);
        } else {
            System.out.println("ALL PLAN 1 TESTS PASSED");
        }
    }

    private static int check(String sql, String expected, String label) {
        try {
            String result = SqlRewriter.rewrite(sql);
            if (expected == null && result == null) {
                System.out.println("PASS: " + label);
                return 0;
            } else if (expected != null && expected.equals(result)) {
                System.out.println("PASS: " + label);
                return 0;
            } else {
                System.err.println("FAIL: " + label + " — expected=" + expected + " got=" + result);
                return 1;
            }
        } catch (Exception e) {
            System.err.println("FAIL: " + label + " — threw: " + e.getMessage());
            return 1;
        }
    }
}
