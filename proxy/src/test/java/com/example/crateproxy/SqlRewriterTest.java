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

        System.out.println("\n--- Plan 2 DDL Rewrite Tests ---");

        // PRXY-05: FOREIGN KEY stripping from CREATE TABLE
        String createWithFk = "CREATE TABLE user_role_mapping (user_id VARCHAR(36) NOT NULL, "
            + "role_id VARCHAR(36) NOT NULL, CONSTRAINT fk_c4fqv6p3oqd6scf FOREIGN KEY (user_id) REFERENCES keycloak_user(id))";
        String fkResult = SqlRewriter.rewrite(createWithFk);
        if (fkResult != null && fkResult.toUpperCase().contains("FOREIGN KEY")) {
            System.err.println("FAIL PRXY-05: FOREIGN KEY not stripped. Got: " + fkResult);
            failures++;
        } else if (fkResult == null) {
            System.err.println("FAIL PRXY-05: CREATE TABLE was swallowed (should not be)");
            failures++;
        } else {
            System.out.println("PASS PRXY-05: FOREIGN KEY stripped. Result: " + fkResult);
        }

        // PRXY-06: UNIQUE constraint stripping from CREATE TABLE
        String createWithUnique = "CREATE TABLE realm (id VARCHAR(36) NOT NULL, "
            + "name VARCHAR(255), CONSTRAINT uk_orvsdmla56612eaefiq6wl5oi UNIQUE (name))";
        String uniqueResult = SqlRewriter.rewrite(createWithUnique);
        if (uniqueResult != null && uniqueResult.toUpperCase().contains("UNIQUE")) {
            System.err.println("FAIL PRXY-06: UNIQUE constraint not stripped. Got: " + uniqueResult);
            failures++;
        } else if (uniqueResult == null) {
            System.err.println("FAIL PRXY-06: CREATE TABLE was swallowed (should not be)");
            failures++;
        } else {
            System.out.println("PASS PRXY-06: UNIQUE constraint stripped. Result: " + uniqueResult);
        }

        // PRXY-07: Type remapping
        String createWithTypes = "CREATE TABLE credential (id VARCHAR(36) NOT NULL, "
            + "salt TINYBLOB, secret_data CLOB, credential_data NCLOB, priority TINYINT, hash_iterations BINARY(64))";
        String typeResult = SqlRewriter.rewrite(createWithTypes);
        if (typeResult == null) {
            System.err.println("FAIL PRXY-07: CREATE TABLE was swallowed (should not be)");
            failures++;
        } else {
            String typeUpper = typeResult.toUpperCase();
            if (typeUpper.contains("TINYBLOB") || typeUpper.contains("CLOB")
                    || typeUpper.contains("NCLOB") || typeUpper.contains("TINYINT")
                    || typeUpper.contains("BINARY")) {
                System.err.println("FAIL PRXY-07: Unmapped types still present. Got: " + typeResult);
                failures++;
            } else {
                System.out.println("PASS PRXY-07: Types remapped. Result: " + typeResult);
            }
        }

        // PRXY-08: ALTER TABLE ADD CONSTRAINT FOREIGN KEY — swallowed entirely
        String alterFk = "ALTER TABLE user_role_mapping ADD CONSTRAINT fk_c4fqv6p3oqd6scf "
            + "FOREIGN KEY (user_id) REFERENCES keycloak_user(id)";
        String alterFkResult = SqlRewriter.rewrite(alterFk);
        if (alterFkResult != null) {
            System.err.println("FAIL PRXY-08: ALTER FK not swallowed. Got: " + alterFkResult);
            failures++;
        } else {
            System.out.println("PASS PRXY-08: ALTER TABLE ADD FK swallowed (returned null)");
        }

        // D-03: CREATE SEQUENCE swallowed
        String createSeq = "CREATE SEQUENCE hibernate_sequence START WITH 1 INCREMENT BY 1";
        String seqResult = SqlRewriter.rewrite(createSeq);
        if (seqResult != null) {
            System.err.println("FAIL D-03: CREATE SEQUENCE not swallowed. Got: " + seqResult);
            failures++;
        } else {
            System.out.println("PASS D-03: CREATE SEQUENCE swallowed");
        }

        // D-04: nextval() throws SQLException
        boolean nextvalThrew = false;
        try {
            SqlRewriter.rewrite("SELECT nextval('hibernate_sequence')");
            System.err.println("FAIL D-04: nextval() should throw SQLException");
        } catch (SQLException e) {
            if (e.getMessage().contains("nextval()")) {
                System.out.println("PASS D-04: nextval() threw SQLException with correct message");
                nextvalThrew = true;
            } else {
                System.err.println("FAIL D-04: Wrong exception: " + e.getMessage());
            }
        }
        if (!nextvalThrew) failures++;

        if (failures > 0) {
            System.err.println("TOTAL FAILURES: " + failures);
            System.exit(1);
        } else {
            System.out.println("ALL PLAN 1 AND PLAN 2 TESTS PASSED");
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
