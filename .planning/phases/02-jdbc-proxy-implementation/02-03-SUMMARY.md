---
phase: 02-jdbc-proxy-implementation
plan: "03"
subsystem: jdbc-proxy
tags: [jdbc, proxy, sql-rewriting, create-index, partial-index, cast-expression, with-clause, replication]
dependency_graph:
  requires:
    - 02-02-SUMMARY.md (rewriteCreateTable, rewriteAlter, remapColumnType)
  provides:
    - SqlRewriter.rewriteCreateIndex()
    - SqlRewriter (complete — all 9 rewrite rules implemented)
    - WITH (number_of_replicas = '1') injection for all CREATE TABLE statements
  affects:
    - proxy/src/main/java/com/example/crateproxy/SqlRewriter.java
    - proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java
tech_stack:
  added: []
  patterns:
    - Pre-parse regex stripping for JSQLParser-incompatible SQL patterns (::cast, WHERE on CREATE INDEX)
    - String-append WITH clause injection (JSQLParser 5.3 setTableOptionsStrings omits WITH keyword)
key_files:
  created: []
  modified:
    - proxy/src/main/java/com/example/crateproxy/SqlRewriter.java
    - proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java
decisions:
  - "JSQLParser 5.3 CreateIndex has no getWhere()/setWhere() — Plan 3 Assumption A2 was wrong; used regex pre-processing on raw SQL before parse instead"
  - "JSQLParser 5.3 cannot parse CREATE INDEX with ::cast expressions — must strip via regex before CCJSqlParserUtil.parse() to avoid D-01 parse failure"
  - "JSQLParser 5.3 setTableOptionsStrings() serializes without WITH keyword — used string append to end of ct.toString() instead of JSQLParser API"
  - "PRXY-11 runs unconditionally (not gated on modified flag) — every CREATE TABLE needs WITH clause regardless of other rewrites"
metrics:
  duration_minutes: 25
  completed_date: "2026-04-22"
  tasks_completed: 2
  files_created: 0
  files_modified: 2
---

# Phase 02 Plan 03: CREATE INDEX Rewriting and WITH Clause Injection Summary

**One-liner:** CREATE INDEX DDL rewriting via pre-parse regex for cast expressions and partial index WHERE clauses; CREATE TABLE WITH clause injection via string append for CrateDB cluster replication.

## What Was Built

Completed `SqlRewriter.java` with the final three rewrite rules, making the proxy feature-complete for Phase 2:

1. **PRXY-09: PostgreSQL cast expression stripping from CREATE INDEX** — `::type` cast tokens (e.g. `email::varchar(250)`, `col::text`) stripped via regex `\\s*::\\w+(\\(\\d+\\))?` applied to the raw SQL BEFORE JSQLParser parsing. JSQLParser 5.3 throws a parse exception on `::` inside index column expressions, so pre-parse stripping is mandatory.

2. **PRXY-10: Partial index WHERE clause stripping** — `WHERE col != 'value'` tails stripped via regex `(?i)\\s+WHERE\\s+.+$` applied to the raw SQL BEFORE JSQLParser parsing. JSQLParser 5.3 cannot parse `CREATE INDEX ... WHERE col != 'x'` (unexpected token `!=`), so pre-parse stripping is mandatory. Plan 3 Assumption A2 (that `ci.setWhere(null)` would work) was incorrect.

3. **PRXY-11: CREATE TABLE WITH clause injection** — `WITH (number_of_replicas = '1')` appended to every `CREATE TABLE` statement that doesn't already contain `NUMBER_OF_REPLICAS`. Applied unconditionally (not gated on `modified` flag) so plain `CREATE TABLE` statements with no other rewrites still get the WITH clause. Uses string append on `ct.toString()` output rather than `setTableOptionsStrings()` (which omits the `WITH` keyword in JSQLParser 5.3 serialization — Assumption A1 was partially wrong).

4. **`rewriteCreateIndex()` private method** — handles UNIQUE keyword stripping from `CREATE UNIQUE INDEX` (Assumption A3) and is dispatched from the main `rewrite()` via `instanceof CreateIndex ci`. The pre-parse step already handles ::cast and WHERE; `rewriteCreateIndex()` handles the UNIQUE stripping which doesn't block parsing.

5. **Extended `SqlRewriterTest.java`** — 6 new test cases: PRXY-09, PRXY-10, combined PRXY-09+10, PRXY-11, PRXY-11 idempotency, and combined PRXY-05+PRXY-11 regression. Final success message updated to `ALL PROXY REWRITE TESTS PASSED`.

## Tasks

| Task | Name | Commit | Status |
|------|------|--------|--------|
| 1 | Add CREATE INDEX rewriting and WITH clause injection | 52a1d57 | Done |
| 2 | Extend test suite with Plan 3 cases; rebuild fat JAR | 9c82159 | Done |

## Verification Results

- `mvn package -q` exits 0, produces `target/crate-proxy-1.0-SNAPSHOT.jar` at 5.2 MB
- All Plan 1 and Plan 2 tests still pass (no regression)
- PASS PRXY-09: Cast expression stripped from CREATE INDEX
- PASS PRXY-10: WHERE clause stripped from partial CREATE INDEX
- PASS PRXY-09+10 combined: Cast and WHERE both stripped
- PASS PRXY-11: WITH clause injected into plain CREATE TABLE
- PASS PRXY-11 idempotent: WITH clause not duplicated on second rewrite pass
- PASS combined: FK stripped AND WITH clause injected together
- Output: `ALL PROXY REWRITE TESTS PASSED`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] JSQLParser 5.3 CreateIndex has no getWhere()/setWhere() — Plan Assumption A2 wrong**
- **Found during:** Task 1 compilation
- **Issue:** Plan specified `ci.setWhere(null)` for PRXY-10. JSQLParser 5.3 `CreateIndex` class does not expose `getWhere()`/`setWhere()` — inspecting the class bytecode confirmed no such methods exist.
- **Fix:** Replaced with pre-parse regex `(?i)\\s+WHERE\\s+.+$` applied to the raw SQL string before `CCJSqlParserUtil.parse()`. Additionally discovered JSQLParser 5.3 cannot even parse `CREATE INDEX ... WHERE col != 'x'` (throws ParseException on `!=`), making pre-parse stripping mandatory.
- **Files modified:** `SqlRewriter.java`
- **Commit:** 52a1d57

**2. [Rule 1 - Bug] JSQLParser 5.3 cannot parse CREATE INDEX with ::cast expressions**
- **Found during:** Task 2 test run (PRXY-09 test threw D-01 parse failure)
- **Issue:** JSQLParser 5.3 throws `ParseException: Encountered unexpected token: "::"` when parsing `CREATE INDEX idx ON t (col::varchar(250))`. The `::` PostgreSQL cast operator is not part of JSQLParser's index column grammar.
- **Fix:** Added ::cast stripping to the pre-parse block (alongside WHERE stripping) so both are applied before `CCJSqlParserUtil.parse()` is called.
- **Files modified:** `SqlRewriter.java`
- **Commit:** 52a1d57

**3. [Rule 1 - Bug] JSQLParser 5.3 setTableOptionsStrings() serializes WITHOUT "WITH" keyword**
- **Found during:** Task 2 test run (PRXY-11 showed double-injection: `... (number_of_replicas = '1') WITH (number_of_replicas = '1')`)
- **Issue:** Plan Assumption A1 was partially wrong. `setTableOptionsStrings(["(number_of_replicas = '1')"])` followed by `ct.toString()` serializes as `... (number_of_replicas = '1')` (bare option string, no `WITH` keyword). Then the fallback check `!ctResult.contains("WITH (NUMBER_OF_REPLICAS")` was true, so it appended `WITH (number_of_replicas = '1')` again, producing double injection.
- **Fix:** Removed the `setTableOptionsStrings()` call entirely. Instead: serialize `ct.toString()` (or use `originalSql` if unmodified), then check for `NUMBER_OF_REPLICAS` absence and do a clean string append of `WITH (number_of_replicas = '1')`.
- **Additional fix:** PRXY-11 was gated on `modified` flag, so a plain `CREATE TABLE` with no FK/UNIQUE/type issues would NOT get the WITH clause. Moved PRXY-11 to run unconditionally.
- **Files modified:** `SqlRewriter.java`
- **Commit:** 52a1d57

## Known Stubs

None — all rewrite rules in scope for Plan 3 are fully implemented and tested. SqlRewriter is feature-complete.

## Threat Flags

None — no new network endpoints, auth paths, file access patterns, or schema changes introduced. All changes are pure SQL string transformation logic.

## Self-Check

- [x] `proxy/src/main/java/com/example/crateproxy/SqlRewriter.java` — FOUND
- [x] `proxy/src/test/java/com/example/crateproxy/SqlRewriterTest.java` — FOUND
- [x] `.planning/phases/02-jdbc-proxy-implementation/02-03-SUMMARY.md` — FOUND
- [x] commit 52a1d57 — Task 1 (CREATE INDEX rewriting + WITH clause injection)
- [x] commit 9c82159 — Task 2 (extended test suite + fat JAR rebuild)

## Self-Check: PASSED
