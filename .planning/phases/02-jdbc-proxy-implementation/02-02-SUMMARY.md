---
phase: 02-jdbc-proxy-implementation
plan: "02"
subsystem: jdbc-proxy
tags: [jdbc, proxy, sql-rewriting, ddl, jsqlparser, create-table, alter-table, type-remapping]
dependency_graph:
  requires:
    - 02-01-SUMMARY.md (SqlRewriter rewrite() pipeline, parseSql, logRewrite)
  provides:
    - SqlRewriter.rewriteCreateTable()
    - SqlRewriter.rewriteAlter()
    - SqlRewriter.remapColumnType()
    - SqlRewriter.shouldStripAlterExpression()
  affects:
    - proxy/src/main/java/com/example/crateproxy/SqlRewriter.java
    - proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java
tech_stack:
  added: []
  patterns:
    - JSQLParser 5.3 Index subtype dispatch (instanceof ForeignKeyIndex + getType())
    - JSQLParser 5.3 AlterOperation enum switch for ALTER TABLE filtering
    - Type base-name extraction via split on space/paren to handle embedded-length type tokens
key_files:
  created: []
  modified:
    - proxy/src/main/java/com/example/crateproxy/SqlRewriter.java
    - proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java
decisions:
  - "JSQLParser 5.3 Index.getIndexType() does not exist — correct method is getType(); ForeignKeyIndex is a typed subclass of Index and is the reliable FK detection path"
  - "JSQLParser 5.3 may embed length in the type name token (BINARY (64) rather than BINARY + args list) — base type extracted via split on space/paren before switch"
  - "AlterOperation enum has DROP_FOREIGN_KEY and DROP_UNIQUE values — used in addition to DROP + constraintName check for complete constraint drop coverage"
  - "NVARCHAR(n) remapping reconstructs VARCHAR(n) by extracting the parenthesized length from the embedded type name string, then setting data type to VARCHAR + length"
metrics:
  duration_minutes: 4
  completed_date: "2026-04-22"
  tasks_completed: 2
  files_created: 0
  files_modified: 2
---

# Phase 02 Plan 02: DDL Rewrite Rules Summary

**One-liner:** CREATE TABLE and ALTER TABLE DDL rewriting via JSQLParser 5.3 AST mutation — FK/UNIQUE stripping, 7 type remappings, and ALTER operation filtering with empty-statement swallowing.

## What Was Built

Extended `SqlRewriter.java` with four new private methods that handle all Keycloak/Liquibase DDL patterns incompatible with CrateDB:

1. **`rewriteCreateTable()`** (PRXY-05, PRXY-06, PRXY-07) — Filters the JSQLParser `CreateTable.getIndexes()` list to strip `ForeignKeyIndex` instances (PRXY-05) and `UNIQUE` type indexes (PRXY-06). Iterates column definitions calling `remapColumnType()` for each. Returns original SQL unchanged if no modification was needed (avoids JSQLParser round-trip noise).

2. **`remapColumnType()`** (PRXY-07) — Maps 7 Keycloak/Liquibase abstract types to CrateDB equivalents: CLOB/NCLOB→TEXT, BINARY→BLOB, TINYBLOB→BLOB, NVARCHAR→VARCHAR (preserving length), TINYINT→SMALLINT, TEXT(n)→TEXT. Uses base-name extraction via `split("\\s|\\(")[0]` to handle JSQLParser 5.3's behavior of embedding the length parameter directly in the type name token (e.g., `BINARY (64)` not `BINARY` + args).

3. **`rewriteAlter()`** (PRXY-08) — Filters `Alter.getAlterExpressions()` through `shouldStripAlterExpression()`. If all expressions are stripped, swallows the entire statement (returns null) to avoid forwarding a bare `ALTER TABLE tablename`. Logs each stripped expression to System.err.

4. **`shouldStripAlterExpression()`** — Switch on `AlterOperation` enum: strips ADD with FK (`getFkSourceColumns()`) or UNIQUE (`getUk()`), DROP with constraint name, DROP_FOREIGN_KEY, DROP_UNIQUE, MODIFY, ALTER, CHANGE.

5. **D-03 (CREATE/ALTER SEQUENCE swallowing)** and **D-04 (nextval() detection)** added to the main `rewrite()` dispatch, before the CREATE TABLE / ALTER TABLE branches.

Also extended `SqlRewriterTest.java` with 6 new test cases covering PRXY-05, PRXY-06, PRXY-07, PRXY-08, D-03, and D-04. All Plan 1 and Plan 2 tests pass.

## Tasks

| Task | Name | Commit | Status |
|------|------|--------|--------|
| 1 | Add CREATE TABLE DDL rewrite rules (PRXY-05, PRXY-06, PRXY-07) | 21ef705 | Done |
| 2 | Add ALTER TABLE rewrite rules (PRXY-08) and extend tests | db31480 | Done |

## Verification Results

- `mvn package -q` exits 0, produces `target/crate-proxy-1.0-SNAPSHOT.jar` at 5.2 MB
- All Plan 1 tests still pass (no regression)
- PASS PRXY-05: FOREIGN KEY stripped from CREATE TABLE
- PASS PRXY-06: UNIQUE constraint stripped from CREATE TABLE
- PASS PRXY-07: All 5 types remapped (TINYBLOB→BLOB, CLOB→TEXT, NCLOB→TEXT, TINYINT→SMALLINT, BINARY(64)→BLOB)
- PASS PRXY-08: ALTER TABLE ADD FK swallowed entirely (null returned)
- PASS D-03: CREATE SEQUENCE swallowed
- PASS D-04: nextval() threw SQLException with correct message

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] JSQLParser 5.3 Index class has no `getIndexType()` method**
- **Found during:** Task 1 compilation
- **Issue:** Plan specified `idx.getIndexType()` for filtering FK and UNIQUE index entries in `rewriteCreateTable()`. JSQLParser 5.3's `Index` class exposes `getType()` not `getIndexType()`. Also, FK indexes are instances of `ForeignKeyIndex` (a typed subclass), making `instanceof ForeignKeyIndex` the more reliable detection path.
- **Fix:** Changed filter to `!(idx instanceof ForeignKeyIndex) && !"FOREIGN KEY".equalsIgnoreCase(idx.getType())` for PRXY-05, and `!"UNIQUE".equalsIgnoreCase(idx.getType())` for PRXY-06. Added `import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex`.
- **Files modified:** `SqlRewriter.java`
- **Commit:** 21ef705

**2. [Rule 1 - Bug] JSQLParser 5.3 embeds length in the type name token for BINARY(n)**
- **Found during:** Task 2 test run (PRXY-07 FAIL — BINARY not remapped)
- **Issue:** JSQLParser 5.3 parses `BINARY(64)` with `dt.getDataType()` returning `"BINARY (64)"` (length embedded in the type name string, not in a separate arguments list). The switch case `"BINARY"` never matched because `upper` was `"BINARY (64)"`.
- **Fix:** Changed type extraction to `typeName.split("\\s|\\(")[0].toUpperCase()` to isolate the base type name before the switch. Also applied to `NVARCHAR` handling where the length must be reconstructed in the new VARCHAR type name. Applied to `TEXT(n)` which checks `typeName.contains("(")` as additional path.
- **Files modified:** `SqlRewriter.java`
- **Commit:** db31480

## Known Stubs

None — all rewrite rules in scope for Plan 2 are fully implemented and tested.

## Threat Flags

None — no new network endpoints, auth paths, file access patterns, or schema changes introduced. All changes are pure SQL string transformation logic within the existing proxy pipeline.

## Self-Check

- [x] `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java` — FOUND
- [x] `proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java` — FOUND
- [x] commit 21ef705 — Task 1 (CREATE TABLE DDL rewrite rules)
- [x] commit db31480 — Task 2 (ALTER TABLE rewrite rules + tests)

## Self-Check: PASSED
