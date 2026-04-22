---
phase: 02-jdbc-proxy-implementation
reviewed: 2026-04-22T00:00:00Z
depth: standard
files_reviewed: 6
files_reviewed_list:
  - proxy/src/main/java/com/example/crateproxy/SqlRewriter.java
  - proxy/src/main/java/com/example/crateproxy/CrateProxyConnection.java
  - proxy/src/main/java/com/example/crateproxy/CrateProxyStatement.java
  - proxy/src/main/java/com/example/crateproxy/CrateProxyPreparedStatement.java
  - proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java
  - proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java
findings:
  critical: 1
  warning: 4
  info: 3
  total: 8
status: issues_found
---

# Phase 02: Code Review Report

**Reviewed:** 2026-04-22
**Depth:** standard
**Files Reviewed:** 6
**Status:** issues_found

## Summary

Six files reviewed: the SQL rewriter, four proxy wrapper classes, and the test harness. The overall structure is sound — the delegation pattern is well-applied, the rewrite rules cover the stated Keycloak DDL inventory, and the swallowed-statement handling is consistent across Statement and PreparedStatement. One JDBC contract violation in `CrateProxyPreparedStatement` is the critical finding (null returned from `executeQuery` could cause NPE in calling code). The remaining findings are bugs in logging correctness, a false-positive risk in `nextval()` detection, and an uncovered code path for UNIQUE index stripping.

---

## Critical Issues

### CR-01: `executeQuery(String sql)` returns null, violating JDBC contract

**File:** `proxy/src/main/java/com/example/crateproxy/CrateProxyPreparedStatement.java:81`

**Issue:** When `rewritten == null || swallowed` is true AND `real` is null (i.e., `swallowed=true` with no real statement), the method returns `null`. The JDBC spec requires `executeQuery` to always return a non-null `ResultSet`. Any caller that does `rs = ps.executeQuery(sql); rs.next()` without a null guard will throw a NullPointerException. Keycloak's Liquibase integration calls this path during lock table queries.

```java
// Current — can return null
if (rewritten == null || swallowed) {
    return real != null ? real.executeQuery("SELECT 1 WHERE 1=0") : null;
}
```

**Fix:** Mirror the pattern used in `CrateProxyStatement.executeQuery(String sql)` which always calls the real statement. For the swallowed case, use a dedicated empty-result sentinel or throw an explicit `SQLException` (which is at least better than silent NPE):

```java
@Override
public ResultSet executeQuery(String sql) throws SQLException {
    String rewritten = SqlRewriter.rewrite(sql);
    if (rewritten == null || swallowed) {
        // Use a real connection to produce an empty ResultSet.
        // If real is null (swallowed), re-use the no-arg executeQuery fallback.
        if (real != null) return real.executeQuery("SELECT 1 WHERE 1=0");
        throw new SQLException("CRATE PROXY: executeQuery on swallowed PreparedStatement — no connection available");
    }
    return real.executeQuery(rewritten);
}
```

---

## Warnings

### WR-01: Double-logging from `rewriteCreateIndex` — every index rewrite logged twice

**File:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java:127` and `322-357`

**Issue:** `rewriteCreateIndex` calls `logRewrite(originalSql, serialized)` internally (line 355). After it returns, `rewrite()` calls `logRewrite(sql, result)` again at line 127 with the same values. Every CREATE INDEX rewrite therefore emits two identical log lines to stderr.

**Fix:** Remove the `logRewrite` call inside `rewriteCreateIndex` and let the top-level call at line 127 handle it — consistent with how `rewriteCreateTable` and `rewriteAlter` work (neither calls `logRewrite` internally).

```java
// In rewriteCreateIndex — remove this line:
logRewrite(originalSql, serialized);   // line 355 — DELETE THIS
return serialized;
```

---

### WR-02: Pre-processing mutates `sql` before `logRewrite`, causing misleading logs

**File:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java:81-88`

**Issue:** Lines 81 and 86 reassign the `sql` parameter variable (stripping `::` casts and WHERE clauses from CREATE INDEX statements before parsing). By the time `logRewrite(sql, result)` is called at line 127, `sql` is the pre-processed version — the truly original SQL is lost from the log. The log will show the already-stripped SQL as "ORIGINAL:", hiding the actual transformation.

**Fix:** Capture the original SQL before any mutation:

```java
public static String rewrite(String sql) throws SQLException {
    if (sql == null) return null;
    String trimmed = sql.trim();
    if (trimmed.isEmpty()) return sql;
    final String originalSql = sql;  // preserve before any pre-processing

    // ... pre-processing may reassign `sql` ...

    // At the bottom:
    logRewrite(originalSql, result);  // log the true original
    return result;
}
```

---

### WR-03: `nextval()` detection is a string search that can false-positive on SQL literals

**File:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java:106`

**Issue:** `sql.toLowerCase().contains("nextval(")` will match a string literal that contains the text `nextval(` — for example an INSERT with a string value like `'call nextval() later'` would incorrectly throw `SQLException`. This is a correctness risk, not just academic: Keycloak stores arbitrary text in its `EVENT_ENTITY` and `ADMIN_EVENT_ENTITY` tables, and if any event description happened to contain `nextval(`, the proxy would reject a valid INSERT.

**Fix:** For a PoC, the risk is low but the fix is straightforward — check after parsing, using JSQLParser to confirm the actual function call presence, or at minimum scope the check to SELECT statements only (which is the only legitimate usage context for `nextval()`):

```java
// Scope check: nextval() is only meaningful in SELECT/expression context
// For safety, only block it when it appears outside a string literal.
// Simplest PoC-viable approach: restrict to SELECT statements
if (stmt instanceof Select && sql.toLowerCase().contains("nextval(")) {
    throw new SQLException("CRATE PROXY: nextval() not supported: " + sql);
}
```

---

### WR-04: UNIQUE index stripping in `rewriteCreateIndex` is unconditional and untested

**File:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java:347-350`

**Issue:** The code strips the UNIQUE keyword from all `CREATE UNIQUE INDEX` statements with the rationale that "CrateDB behavior is unverified." If CrateDB 6.2 does support `CREATE UNIQUE INDEX` (it does support unique constraints via `ALTER TABLE`), stripping UNIQUE silently removes the data-integrity guarantee. The comment marks this as assumption A3 but there are no tests for this path in `SqlRewriterTest.java`.

**Fix:** Add a test case. If CrateDB 6.2 is confirmed to support `CREATE UNIQUE INDEX`, remove the strip. If it is not supported, document the tradeoff explicitly and keep the strip:

```java
// Test to add in SqlRewriterTest.java:
String createUniqueIdx = "CREATE UNIQUE INDEX idx_user_email ON user_entity (email)";
String uniqueIdxResult = SqlRewriter.rewrite(createUniqueIdx);
// Assert: either UNIQUE is stripped (if CrateDB rejects it) or kept (if supported)
if (uniqueIdxResult != null && uniqueIdxResult.toUpperCase().startsWith("CREATE UNIQUE")) {
    System.out.println("INFO: UNIQUE INDEX kept — CrateDB supports it");
} else if (uniqueIdxResult != null) {
    System.out.println("INFO: UNIQUE stripped from CREATE INDEX");
}
```

---

## Info

### IN-01: Redundant `upper2` variable — duplicate of `upper`

**File:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java:77`

**Issue:** `String upper2 = trimmed.toUpperCase()` at line 77 is identical to `String upper = trimmed.toUpperCase()` computed at line 56. Both hold the same value; `upper2` is used only at line 78 and line 85 (`sql.toUpperCase()` at line 85 is yet another variant).

**Fix:** Replace `upper2` with the existing `upper` variable. Also replace `sql.toUpperCase().contains(" WHERE ")` with `upper.contains(" WHERE ")` (though note `upper` is based on `trimmed` not `sql` — after reassigning `sql` at line 81 they diverge; refactor accordingly).

---

### IN-02: `isAlterSequence` uses class name reflection instead of an import

**File:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java:359-364`

**Issue:** The method uses `stmt.getClass().getSimpleName().equals("AlterSequence")` as a fallback because the correct import path for `AlterSequence` in JSQLParser 5.3 was uncertain. JSQLParser 5.3 defines `net.sf.jsqlparser.statement.alter.AlterSequence` — the class exists and can be imported directly. The reflection approach will silently miss the class if JSQLParser ever renames it, and it bypasses compile-time type checking.

**Fix:** Add the direct import and use `instanceof`:

```java
import net.sf.jsqlparser.statement.alter.AlterSequence;

private static boolean isAlterSequence(Statement stmt) {
    return stmt instanceof AlterSequence;
}
```

---

### IN-03: `execute(String sql)` in `CrateProxyPreparedStatement` has a dead code path

**File:** `proxy/src/main/java/com/example/crateproxy/CrateProxyPreparedStatement.java:70-75`

**Issue:** Line 72 returns `false` when `rewritten == null`. Line 73 then checks `if (swallowed) return false` — but this line is only reachable when `rewritten != null`, and in that case `swallowed` being true still calls `real.execute(rewritten)` at line 74 (because `swallowed` is checked at line 73 but `real` would be null, causing NPE). The logic should be `if (rewritten == null || swallowed) return false`.

```java
// Current — line 73 never reached when rewritten==null; NPE if rewritten!=null && swallowed==true
@Override
public boolean execute(String sql) throws SQLException {
    String rewritten = SqlRewriter.rewrite(sql);
    if (rewritten == null) return false;    // line 72 — early return
    if (swallowed) return false;            // line 73 — reachable, but real is null here!
    return real.execute(rewritten);         // line 74 — NPE if swallowed==true
}

// Fix:
@Override
public boolean execute(String sql) throws SQLException {
    String rewritten = SqlRewriter.rewrite(sql);
    if (rewritten == null || swallowed) return false;
    return real.execute(rewritten);
}
```

Note: the same structural issue appears in `executeUpdate(String sql)` at lines 87-92, though that version already uses `if (rewritten == null) return 0; if (swallowed) return 0;` — the NPE path does not trigger there because `return 0` precedes the `real` call.

---

_Reviewed: 2026-04-22_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
