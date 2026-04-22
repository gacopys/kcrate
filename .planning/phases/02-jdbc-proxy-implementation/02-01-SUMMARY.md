---
phase: 02-jdbc-proxy-implementation
plan: "01"
subsystem: jdbc-proxy
tags: [jdbc, proxy, sql-rewriting, transaction-swallowing, jsqlparser]
dependency_graph:
  requires: []
  provides:
    - CrateProxyConnection
    - CrateProxyStatement
    - CrateProxyPreparedStatement
    - SqlRewriter
  affects:
    - proxy/src/main/java/com/example/crateproxy/
tech_stack:
  added: []
  patterns:
    - JDBC delegation wrapper (hand-rolled, no framework)
    - Sequential SQL rewrite pipeline with null-for-swallow contract
    - JSQLParser 5.3 AST mutation for SELECT FOR UPDATE stripping
key_files:
  created:
    - proxy/src/main/java/com/example/crateproxy/SqlRewriter.java
    - proxy/src/main/java/com/example/crateproxy/CrateProxyConnection.java
    - proxy/src/main/java/com/example/crateproxy/CrateProxyStatement.java
    - proxy/src/main/java/com/example/crateproxy/CrateProxyPreparedStatement.java
    - proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java
  modified:
    - proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java
    - .gitignore
decisions:
  - "D-01 enforced in Plan 1: all non-transaction SQL parsed through JSQLParser; parse failure throws SQLException with 'CRATE PROXY: Cannot parse SQL:' prefix"
  - "JSQLParser 5.3 stores FOR UPDATE properties on Select (not PlainSelect) — use select.getForMode()/setForMode() and select.setForUpdateTable(null)"
  - "setTransactionIsolation swallowed silently in CrateProxyConnection (not just no-op setAutoCommit) to prevent Hibernate/Keycloak startup errors"
metrics:
  duration_minutes: 25
  completed_date: "2026-04-22"
  tasks_completed: 2
  files_created: 5
  files_modified: 2
---

# Phase 02 Plan 01: JDBC Interception Chain and SqlRewriter Summary

**One-liner:** Full JDBC delegation chain (CrateProxyConnection → CrateProxyStatement → CrateProxyPreparedStatement) with transaction swallowing and SELECT FOR UPDATE stripping via JSQLParser 5.3.

## What Was Built

Replaced the Phase 1 passthrough stub in `CrateProxyDriver.connect()` with a complete four-class interception hierarchy:

1. **SqlRewriter** — central rewrite pipeline. Rule 1: transaction swallowing via string matching (BEGIN/COMMIT/ROLLBACK/SAVEPOINT/RELEASE SAVEPOINT/START TRANSACTION/SET TRANSACTION). Rule 2: SELECT FOR UPDATE stripping via JSQLParser AST mutation. All other non-transaction SQL is parsed through JSQLParser (D-01 compliance) and passed through unchanged. Plans 2 and 3 will insert DDL rewrite branches before the final return.

2. **CrateProxyConnection** — full `java.sql.Connection` delegation wrapper. Intercepts: commit/rollback/setAutoCommit (no-op), getAutoCommit (returns true), setSavepoint (returns stub), setTransactionIsolation (swallowed), createStatement (wraps in CrateProxyStatement), prepareStatement all 7 overloads (rewrites SQL, returns CrateProxyPreparedStatement).

3. **CrateProxyStatement** — full `java.sql.Statement` delegation wrapper. Intercepts all 9 execute* variants and addBatch — each calls SqlRewriter.rewrite() and returns false/0/empty on null (swallowed).

4. **CrateProxyPreparedStatement** — full `java.sql.PreparedStatement` delegation wrapper. Constructor takes `(PreparedStatement real, boolean swallowed)`. All setXxx methods are no-ops when swallowed. All execute methods return safe defaults when swallowed.

5. **CrateProxyDriver** — updated connect() to return `new CrateProxyConnection(real)` instead of raw passthrough.

## Tasks

| Task | Name | Commit | Status |
|------|------|--------|--------|
| 1 | Create SqlRewriter, proxy wrappers, update CrateProxyDriver | e6bb49e | Done |
| 2 | Build fat JAR + SqlRewriterTest | 35c0e2f | Done |

## Verification Results

- `mvn compile -q` exits 0 (via Docker Maven container — local JDK not installed)
- `mvn package -q` exits 0, produces `target/crate-proxy-1.0-SNAPSHOT.jar` at 5.2 MB
- `SqlRewriterTest` prints `ALL PLAN 1 TESTS PASSED` and exits 0
- All acceptance criteria met: grep checks for `new CrateProxyConnection`, `new CrateProxyStatement`, `new CrateProxyPreparedStatement`, `SqlRewriter.rewrite`, `isTransactionCommand`, `FOR UPDATE`, `[CRATE PROXY] REWRITE:`, `if (swallowed)` all confirmed

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] JSQLParser 5.3 FOR UPDATE API changed from PlainSelect to Select**
- **Found during:** Task 1 implementation
- **Issue:** Plan code examples used `ps.getForUpdateTable()`, `ps.isForUpdate()`, `ps.setForUpdate(false)` on `PlainSelect`. In JSQLParser 5.3, these methods moved to the top-level `Select` class. `PlainSelect` has no `forUpdate` methods.
- **Fix:** Used `select.getForMode()`, `select.setForMode(null)`, `select.setForUpdateTable(null)`, `select.setNoWait(false)`, `select.setSkipLocked(false)`, `select.setWait(null)` on the `Select` instance directly.
- **Files modified:** `SqlRewriter.java`
- **Commit:** e6bb49e

**2. [Rule 2 - Missing critical functionality] D-01 parse-failure path not triggered in Plan 1**
- **Found during:** Task 2 (SqlRewriterTest revealed FAIL on parse failure test)
- **Issue:** Original Plan 1 `SqlRewriter` only called `parseSql()` for `FOR UPDATE` SQL. The test (and must_haves) required that truly invalid SQL throws `SQLException`. The `parseSql()` call path was never reached for generic invalid SQL.
- **Fix:** Added a final `parseSql(sql)` call at the end of `rewrite()` for all non-transaction SQL (after FOR UPDATE check). This enforces D-01 and also provides the parse hook for Plans 2 and 3 to add DDL branch logic.
- **Files modified:** `SqlRewriter.java`
- **Commit:** 35c0e2f

**3. [Rule 2 - Missing critical functionality] `setTransactionIsolation` needed swallowing**
- **Found during:** Task 1 (RESEARCH.md Pitfall 7 noted Hibernate issues this at connection startup)
- **Issue:** `setTransactionIsolation` is called by Hibernate/Keycloak at connection startup. CrateDB doesn't support changing isolation level. The plan mentioned swallowing it but didn't include it explicitly in the intercepted list.
- **Fix:** `CrateProxyConnection.setTransactionIsolation()` swallows silently; `getTransactionIsolation()` returns `TRANSACTION_READ_COMMITTED`.
- **Files modified:** `CrateProxyConnection.java`
- **Commit:** e6bb49e

**4. [Rule 3 - Blocking issue] proxy/target/ owned by root (Docker build artifacts)**
- **Found during:** Task 1 verification (`mvn compile -q` failed with "Permission denied" writing to `/proxy/target/`)
- **Issue:** Previous Phase 1 build ran in Docker container as root, leaving `proxy/target/` and all subdirs owned by root. No local JDK installed — only JRE.
- **Fix:** Used `docker run maven:3.9-eclipse-temurin-21` with volume mounts to compile/package. Added `proxy/target/` to `.gitignore`.
- **Files modified:** `.gitignore`
- **Commit:** e6bb49e

## Known Stubs

None — all rewrite rules in scope for Plan 1 are fully implemented.

## Threat Flags

None — no new network endpoints, auth paths, file access patterns, or schema changes. The proxy runs entirely within Keycloak's JVM.

## Self-Check

- [x] `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java` — FOUND
- [x] `proxy/src/main/java/com/example/crateproxy/CrateProxyConnection.java` — FOUND
- [x] `proxy/src/main/java/com/example/crateproxy/CrateProxyStatement.java` — FOUND
- [x] `proxy/src/main/java/com/example/crateproxy/CrateProxyPreparedStatement.java` — FOUND
- [x] `proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java` — FOUND
- [x] commit e6bb49e — FOUND
- [x] commit 35c0e2f — FOUND

## Self-Check: PASSED
