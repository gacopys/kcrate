---
phase: 02-jdbc-proxy-implementation
fixed_at: 2026-04-22T00:00:00Z
review_path: .planning/phases/02-jdbc-proxy-implementation/02-REVIEW.md
iteration: 1
findings_in_scope: 5
fixed: 5
skipped: 0
status: all_fixed
---

# Phase 02: Code Review Fix Report

**Fixed at:** 2026-04-22
**Source review:** .planning/phases/02-jdbc-proxy-implementation/02-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 5 (1 Critical, 4 Warning)
- Fixed: 5
- Skipped: 0

## Fixed Issues

### CR-01: `executeQuery(String sql)` returns null, violating JDBC contract

**Files modified:** `proxy/src/main/java/com/example/crateproxy/CrateProxyPreparedStatement.java`
**Commit:** affc124
**Applied fix:** Replaced the null return with a `throw new SQLException(...)` for the case where `real == null` and the statement is swallowed. When `real != null`, the existing `SELECT 1 WHERE 1=0` fallback is preserved. This ensures `executeQuery` never returns null, satisfying the JDBC contract.

---

### WR-01: Double-logging from `rewriteCreateIndex` — every index rewrite logged twice

**Files modified:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java`
**Commit:** 8d04b5d
**Applied fix:** Removed the `logRewrite(originalSql, serialized)` call at the end of `rewriteCreateIndex`. The top-level `logRewrite(originalSql, result)` call in `rewrite()` now handles all logging uniformly, consistent with how `rewriteCreateTable` and `rewriteAlter` work.

---

### WR-02: Pre-processing mutates `sql` before `logRewrite`, causing misleading logs

**Files modified:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java`
**Commit:** 5e897b2
**Applied fix:** Added `final String originalSql = sql;` immediately before the pre-processing block that may reassign `sql`. Updated the bottom-of-method `logRewrite(sql, result)` call to `logRewrite(originalSql, result)` so the log always shows the true original input SQL, not the intermediate pre-processed form.

---

### WR-03: `nextval()` detection is a string search that can false-positive on SQL literals

**Files modified:** `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java`
**Commit:** a0e655d
**Applied fix:** Added an `instanceof Select` guard to the `nextval()` check. The detection now only triggers for SELECT statements (`if (stmt instanceof Select && sql.toLowerCase().contains("nextval("))`), which is the only legitimate execution context for `nextval()`. INSERT/UPDATE/DELETE statements with string values containing `nextval(` text are no longer falsely rejected. The existing D-04 test (`SELECT nextval('hibernate_sequence')`) continues to verify the check still fires for SELECT.

---

### WR-04: UNIQUE index stripping in `rewriteCreateIndex` is unconditional and untested

**Files modified:** `proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java`
**Commit:** f32f343
**Applied fix:** Added a test case for `CREATE UNIQUE INDEX idx_user_email ON user_entity (email)` that asserts: (1) the statement is not swallowed, (2) the `UNIQUE` keyword is stripped, and (3) the result starts with `CREATE INDEX`. The test documents the current proxy behaviour (strip UNIQUE, assumption A3) and will catch any regression if the stripping logic changes.

---

_Fixed: 2026-04-22_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
