# Phase 2: JDBC Proxy Implementation - Research

**Researched:** 2026-04-22
**Domain:** Java JDBC proxy delegation, JSQLParser DDL AST rewriting, Keycloak/Liquibase SQL compatibility surface
**Confidence:** HIGH — all findings sourced from existing project research files (themselves verified against official docs and Keycloak source), existing Phase 1 code, and CONTEXT.md locked decisions.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** When JSQLParser cannot parse a SQL statement, the proxy **throws a `SQLException`** — silent passthrough is forbidden. Any unhandled SQL pattern must surface immediately.
- **D-02:** Every successful rewrite is **logged to `System.err` unconditionally** — original SQL and rewritten SQL on each interception. No log framework, no level gating.
- **D-03:** `CREATE SEQUENCE` and `ALTER SEQUENCE` are **stripped silently** (swallowed, return success).
- **D-04:** If `nextval(...)` appears in a query body, it is **treated as unhandled SQL → throws `SQLException`** (consistent with D-01).
- **D-05:** Phase 2 is split into **3 plans by concern**:
  - Plan 1: `CrateProxyConnection`, `CrateProxyStatement`, `CrateProxyPreparedStatement` wrappers + transaction swallowing (BEGIN/COMMIT/ROLLBACK/SAVEPOINT) + SELECT FOR UPDATE stripping
  - Plan 2: DDL rewrites — FK constraint stripping, UNIQUE constraint stripping, type remapping (CLOB/NCLOB/BINARY/TINYBLOB/NVARCHAR/TINYINT), ALTER TABLE/COLUMN unsupported op stripping, CREATE/ALTER SEQUENCE stripping
  - Plan 3: Index DDL rewrites — PostgreSQL cast expression stripping (`::varchar`), partial index WHERE clause stripping + CREATE TABLE WITH clause injection (`WITH (number_of_replicas = '1')`)

### Claude's Discretion

- **Rewriter class structure** — whether a single `SqlRewriter` class handles all rules or rules are split into per-concern classes is left to the planner. CLAUDE.md recommends a single rewriter with a visitor pattern.
- **Statement interception granularity** — whether `CrateProxyStatement` wraps `execute`, `executeQuery`, `executeUpdate` individually or funnels through a single intercept method is left to the planner.
- **NEXTVAL detection** — exact mechanism (string match vs JSQLParser Function visitor) is left to the planner.

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PRXY-01 | Custom JDBC driver wraps pgJDBC and registers via META-INF/services SPI | Already done in Phase 1; Phase 2 extends `connect()` to return `CrateProxyConnection` |
| PRXY-02 | All 4 JDBC execution paths intercepted (Statement.execute, executeQuery, executeUpdate, PreparedStatement) | Delegation pattern fully documented in ARCHITECTURE.md; CrateProxyStatement + CrateProxyPreparedStatement cover all 4 |
| PRXY-03 | BEGIN / COMMIT / ROLLBACK swallowed silently | String-match interception in CrateProxyConnection + CrateProxyStatement; confirmed against CrateDB failure pattern |
| PRXY-04 | SELECT FOR UPDATE stripped from all queries | JSQLParser PlainSelect visitor; remove `forUpdate` / lock clause; confirmed as first SQL Keycloak issues (before any changeset) |
| PRXY-05 | FOREIGN KEY constraints stripped from CREATE TABLE DDL | JSQLParser CreateTable visitor — filter Index list entries of type FOREIGN_KEY; confirmed 195 FK additions in Keycloak changelogs |
| PRXY-06 | UNIQUE constraints stripped from DDL | JSQLParser AlterExpression visitor for ADD CONSTRAINT UNIQUE; confirmed 63 unique constraint additions |
| PRXY-07 | Unsupported types remapped — CLOB→TEXT, NCLOB→TEXT, BINARY(64)→BLOB, TINYBLOB→BLOB, NVARCHAR→VARCHAR, TINYINT→SMALLINT | JSQLParser CreateTable/AlterTable column definition visitor; type inventory fully enumerated from Keycloak changelog grep |
| PRXY-08 | ALTER TABLE / ALTER COLUMN unsupported operations stripped | JSQLParser AlterTable visitor — detect ALTER COLUMN TYPE, SET NOT NULL, DROP NOT NULL, ADD/DROP CONSTRAINT FOREIGN KEY, ADD/DROP CONSTRAINT UNIQUE |
| PRXY-09 | PostgreSQL cast expressions (::varchar) stripped from CREATE INDEX DDL | String-level preprocessing or JSQLParser CreateIndex visitor — strip `::type` tokens from index column expressions |
| PRXY-10 | Partial index WHERE clauses stripped from CREATE INDEX DDL | JSQLParser CreateIndex visitor — remove `where` property; confirmed in 26.5.0 OFFLINE_CLIENT_SESSION changesets |
| PRXY-11 | CREATE TABLE statements rewritten to include `WITH (number_of_replicas = '1')` | JSQLParser CreateTable — append Table Options; required for cluster replication in 3-node setup |
</phase_requirements>

---

## Summary

Phase 2 replaces the passthrough stub in `CrateProxyDriver.connect()` with a full interception chain. The chain introduces three new classes (`CrateProxyConnection`, `CrateProxyStatement`, `CrateProxyPreparedStatement`) and a central `SqlRewriter` that routes each SQL statement through up to 9 rewrite rules.

The rewrite surface is completely enumerated. A local grep of all 74 Keycloak Liquibase changelogs identified every non-standard SQL pattern: 195 FK additions, 63 unique constraint additions, 56 modifyDataType operations, 34 NOT NULL additions, 20 CLOB columns, 9 NCLOB columns, 14 index cast expressions, and 2 partial index WHERE clauses. There are no sequences in Keycloak 26.x (UUIDs are generated in Java). The proxy does not need to handle `bytea`, `uuid`, or `jsonb` — Keycloak uses `VARCHAR(36)` for IDs and Liquibase abstract types (CLOB/NCLOB) for large text.

The build system (Maven + shade plugin + ServicesResourceTransformer) and dependencies (JSQLParser 5.3, pgJDBC 42.7.4) are already in place from Phase 1 — no pom.xml changes are needed.

**Primary recommendation:** Implement a single `SqlRewriter` class with sequential rule application. Use string matching for transaction keywords (high-volume hot path) and JSQLParser AST for DDL (lower volume but complex structure). Route all four JDBC execution paths through `CrateProxyStatement` and `CrateProxyPreparedStatement` delegation wrappers that call the rewriter before forwarding.

---

## Project Constraints (from CLAUDE.md)

All of the following are hard constraints — the planner MUST NOT propose approaches that contradict them:

| Directive | Requirement |
|-----------|-------------|
| Official Docker images only | No changes to Keycloak or CrateDB source or images |
| Language: Java 21 | Proxy must target `--release 21`; already set in pom.xml |
| JDBC proxy pattern | Hand-rolled delegation wrapper — NOT P6Spy, NOT datasource-proxy, NOT crate-jdbc |
| SQL parser | JSQLParser 5.3 — NOT Calcite, NOT ANTLR, NOT regex for DDL |
| Build | Maven + maven-shade-plugin 3.6.2 with ServicesResourceTransformer |
| Type remapping | Per REQUIREMENTS.md PRXY-07 (takes precedence over CLAUDE.md): CLOB→TEXT, NCLOB→TEXT, BINARY(64)→BLOB, TINYBLOB→BLOB, NVARCHAR→VARCHAR, TINYINT→SMALLINT |
| Scope | Proof of concept — correctness over performance |

---

## Standard Stack

### Core (already in pom.xml — no changes needed)

| Library | Version | Purpose | Source |
|---------|---------|---------|--------|
| `com.github.jsqlparser:jsqlparser` | 5.3 | SQL AST parsing, DDL mutation and serialization | [VERIFIED: pom.xml in repo] |
| `org.postgresql:postgresql` | 42.7.4 | Real JDBC driver; proxy delegates all network I/O to this | [VERIFIED: pom.xml in repo] |
| `maven-shade-plugin` | 3.6.2 | Fat JAR with ServicesResourceTransformer for META-INF/services merging | [VERIFIED: pom.xml in repo] |

### New Java Classes to Create

| Class | Implements | Purpose |
|-------|-----------|---------|
| `CrateProxyConnection` | `java.sql.Connection` | Swallows commit/rollback/setAutoCommit; wraps createStatement/prepareStatement factories |
| `CrateProxyStatement` | `java.sql.Statement` | Intercepts all 3 execute variants; routes SQL through SqlRewriter |
| `CrateProxyPreparedStatement` | `java.sql.PreparedStatement` | Rewrites SQL at prepare time; wraps execute paths; handles no-op case for swallowed statements |
| `SqlRewriter` | (stateless utility) | Single entry point for all rewrite rules; routes by statement type |

### No New Dependencies

All dependencies are already shaded into the fat JAR from Phase 1. No pom.xml changes needed for Phase 2.

---

## Architecture Patterns

### Recommended Project Structure

```
proxy/src/main/java/com/example/crateproxy/
├── CrateProxyDriver.java          # EXISTS — modify connect() to return CrateProxyConnection
├── CrateProxyConnection.java      # NEW — Plan 1
├── CrateProxyStatement.java       # NEW — Plan 1
├── CrateProxyPreparedStatement.java # NEW — Plan 1
└── SqlRewriter.java               # NEW — Plans 1-3 (all rewrite rules in one class)
```

### Pattern 1: Delegation Wrapper (all proxy classes)

Every proxy class holds a reference to the real JDBC object and delegates all methods except the ones being intercepted. This avoids implementing the full JDBC interface from scratch — only intercepted methods need logic.

```java
// Source: STACK.md (project research, HIGH confidence)
public class CrateProxyConnection implements java.sql.Connection {
    private final Connection real;

    CrateProxyConnection(Connection real) { this.real = real; }

    @Override public void commit() throws SQLException { /* no-op */ }
    @Override public void rollback() throws SQLException { /* no-op */ }
    @Override public void setAutoCommit(boolean b) throws SQLException { /* always no-op */ }
    @Override public boolean getAutoCommit() throws SQLException { return true; }

    @Override
    public Statement createStatement() throws SQLException {
        return new CrateProxyStatement(real.createStatement());
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        String rewritten = SqlRewriter.rewrite(sql);
        if (rewritten == null) {
            // Swallowed statement — return a no-op PreparedStatement
            return new CrateProxyPreparedStatement(null, true);
        }
        return new CrateProxyPreparedStatement(real.prepareStatement(rewritten), false);
    }

    // All other methods delegate to real
    @Override public DatabaseMetaData getMetaData() throws SQLException { return real.getMetaData(); }
    // ... etc.
}
```

### Pattern 2: SqlRewriter — Sequential Rule Application

`SqlRewriter.rewrite(String sql)` is the single entry point. It applies rules in order. Returns `null` for swallowed statements (caller returns no-op). Throws `SQLException` on parse failure (D-01). Logs every non-trivial rewrite to `System.err` (D-02).

```java
// Source: CONTEXT.md D-01, D-02, D-03, D-04 + ARCHITECTURE.md pipeline
public class SqlRewriter {

    public static String rewrite(String sql) throws SQLException {
        String trimmed = sql.trim();

        // Rule 1: Transaction swallowing (string match — fast path, high volume)
        if (isTransactionCommand(trimmed)) {
            return null;  // caller returns no-op
        }

        // Rule 2: SELECT FOR UPDATE stripping (PRXY-04)
        if (trimmedUpper.contains("FOR UPDATE")) {
            // strip FOR UPDATE suffix — use JSQLParser PlainSelect
        }

        // Rule 3-9: DDL rewrites via JSQLParser
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SQLException("CRATE PROXY: Cannot parse SQL: " + sql, e);  // D-01
        }

        String result = sql;
        if (stmt instanceof CreateTable ct) {
            result = rewriteCreateTable(ct);
        } else if (stmt instanceof Alter alter) {
            result = rewriteAlter(alter);
        } else if (stmt instanceof CreateIndex ci) {
            result = rewriteCreateIndex(ci);
        } else if (stmt instanceof CreateSequence || stmt instanceof AlterSequence) {
            return null;  // D-03: swallow sequence DDL silently
        }

        // D-04: detect nextval() in any surviving statement
        if (result.toLowerCase().contains("nextval(")) {
            throw new SQLException("CRATE PROXY: nextval() not supported: " + sql);
        }

        // D-02: log every rewrite
        if (!result.equals(sql)) {
            System.err.println("[CRATE PROXY] REWRITE:\n  ORIGINAL: " + sql + "\n  REWRITTEN: " + result);
        }

        return result;
    }

    private static boolean isTransactionCommand(String sql) {
        String upper = sql.toUpperCase();
        return upper.equals("BEGIN") || upper.equals("COMMIT") || upper.equals("ROLLBACK")
            || upper.startsWith("ROLLBACK TO") || upper.startsWith("SAVEPOINT")
            || upper.startsWith("RELEASE SAVEPOINT") || upper.startsWith("START TRANSACTION")
            || upper.startsWith("SET TRANSACTION");
    }
}
```

### Pattern 3: CrateProxyStatement — Four Execution Paths

All four JDBC execution paths are covered by wrapping Statement and PreparedStatement:

| Path | Method | Intercept Point |
|------|--------|----------------|
| 1 | `Statement.execute(String sql)` | CrateProxyStatement |
| 2 | `Statement.executeQuery(String sql)` | CrateProxyStatement |
| 3 | `Statement.executeUpdate(String sql)` | CrateProxyStatement |
| 4 | `PreparedStatement.execute()` / `executeQuery()` / `executeUpdate()` | CrateProxyPreparedStatement (SQL rewritten at prepare time in `Connection.prepareStatement()`) |

```java
// Source: PITFALLS.md M-4, ARCHITECTURE.md §Statement Interception Layer
public class CrateProxyStatement implements java.sql.Statement {
    private final Statement real;

    @Override
    public boolean execute(String sql) throws SQLException {
        String rewritten = SqlRewriter.rewrite(sql);
        if (rewritten == null) return false;  // swallowed
        return real.execute(rewritten);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        String rewritten = SqlRewriter.rewrite(sql);
        if (rewritten == null) return emptyResultSet();
        return real.executeQuery(rewritten);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        String rewritten = SqlRewriter.rewrite(sql);
        if (rewritten == null) return 0;
        return real.executeUpdate(rewritten);
    }
    // ... all other Statement methods delegate to real
}
```

### Pattern 4: CrateProxyDriver.connect() — Wrap Returned Connection

The only change to Phase 1's `CrateProxyDriver`:

```java
// Source: CONTEXT.md §Existing Code, CrateProxyDriver.java in repo
@Override
public Connection connect(String url, Properties info) throws SQLException {
    Connection real = REAL.connect(url, info);
    if (real == null) return null;
    return new CrateProxyConnection(real);   // CHANGED: was just `return REAL.connect(...)`
}
```

### Pattern 5: JSQLParser — CreateTable Rewriting (Plans 2 and 3)

```java
// Source: STACK.md §SQL Parsing, JSQLParser docs https://jsqlparser.github.io/JSqlParser/
private static String rewriteCreateTable(CreateTable ct) {
    // PRXY-05: strip FOREIGN KEY index entries
    if (ct.getIndexes() != null) {
        ct.setIndexes(ct.getIndexes().stream()
            .filter(idx -> !"FOREIGN KEY".equalsIgnoreCase(idx.getIndexType()))
            .collect(Collectors.toList()));
    }

    // PRXY-06: strip UNIQUE constraints from index list
    if (ct.getIndexes() != null) {
        ct.setIndexes(ct.getIndexes().stream()
            .filter(idx -> !"UNIQUE".equalsIgnoreCase(idx.getIndexType()))
            .collect(Collectors.toList()));
    }

    // PRXY-07: remap column types
    if (ct.getColumnDefinitions() != null) {
        ct.getColumnDefinitions().forEach(col -> remapColumnType(col));
    }

    // PRXY-11: inject WITH (number_of_replicas = '1')
    List<String> opts = ct.getTableOptionsStrings();
    if (opts == null) opts = new ArrayList<>();
    // Only add if not already present
    if (opts.stream().noneMatch(o -> o.contains("number_of_replicas"))) {
        opts.add("(number_of_replicas = '1')");
        ct.setTableOptionsStrings(opts);
    }

    return ct.toString();
}
```

### Pattern 6: Type Remapping (PRXY-07)

Applied in-place on JSQLParser `ColumnDefinition.colDataType`:

| Keycloak/Liquibase Type | CrateDB Equivalent | Notes |
|------------------------|-------------------|-------|
| `CLOB` | `TEXT` | 20 columns in changelogs |
| `NCLOB` | `TEXT` | 9 columns in changelogs |
| `BINARY(n)` | `BLOB` | 4 hash columns (64-byte SHA-256) |
| `TINYBLOB` | `BLOB` | 3 columns (deprecated CREDENTIAL.SALT) |
| `NVARCHAR(n)` | `VARCHAR(n)` | 4 columns — strip N prefix, keep length |
| `TINYINT` | `SMALLINT` | 4 columns |
| `TEXT(n)` | `TEXT` | 1 column — strip length parameter |

Note: `bytea`, `uuid`, `jsonb` do NOT appear in Keycloak DDL — no remapping needed for them. [VERIFIED: FEATURES.md — grep across all 74 changelogs]

### Pattern 7: ALTER TABLE Rewriting (PRXY-08)

Intercept `Alter` statements and strip:
- `ALTER COLUMN ... TYPE` (56 modifyDataType operations — all safe VARCHAR widenings)
- `ALTER COLUMN ... SET NOT NULL` (25 addNotNullConstraint operations)
- `ALTER COLUMN ... DROP NOT NULL` (9 dropNotNullConstraint operations)
- `ADD CONSTRAINT ... FOREIGN KEY` (195 FK additions)
- `DROP CONSTRAINT` for FK constraints
- `ADD CONSTRAINT ... UNIQUE` (63 unique constraint additions)
- `DROP CONSTRAINT` for unique constraints

If all alter operations in a statement are stripped, the entire statement should be swallowed (return null) to avoid sending a bare `ALTER TABLE tablename` to CrateDB.

### Pattern 8: CREATE INDEX Rewriting (Plan 3)

For `::type` cast expressions (PRXY-09):
```
CREATE INDEX idx ON table (value::varchar(250))
→ CREATE INDEX idx ON table (value)
```
Strategy: string-level regex `\s*::\w+(\(\d+\))?` applied to column expression string after extracting it from the JSQLParser CreateIndex AST.

For partial index WHERE clauses (PRXY-10):
```
CREATE INDEX idx ON table (col) WHERE col != 'external'
→ CREATE INDEX idx ON table (col)
```
Strategy: `CreateIndex.setWhere(null)` via JSQLParser before serializing.

### Anti-Patterns to Avoid

- **Intercepting only prepareStatement**: Liquibase uses `Statement.execute()` for all DDL. Both paths must be wrapped.
- **Regex for CREATE TABLE FK stripping**: Multi-line DDL with nested parentheses breaks regex. Use JSQLParser AST.
- **Forwarding a null result from rewrite()**: Callers must check for null (swallowed) and return a no-op response rather than delegating to the real driver.
- **Throwing exceptions for swallowed statements**: Returning null / no-op is the correct signal for swallowed statements. Exceptions are only for parse failures (D-01) and nextval() (D-04).
- **Relocating org.postgresql in shade**: pgJDBC loads SSL factory classes dynamically by string name — relocation breaks this. Do NOT add relocation for `org.postgresql`. [VERIFIED: STACK.md §Build Tooling critical note]
- **Implementing java.sql.Connection from scratch**: Use delegation — only override the ~10 intercepted methods; delegate all others to `real`. The full Connection interface has 40+ methods.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQL DDL parsing | Regex-based CREATE TABLE parser | JSQLParser 5.3 (already on classpath) | Nested parens, multi-line DDL, quoted identifiers all break regex |
| Fat JAR with merged services | Custom manifest manipulation | maven-shade-plugin ServicesResourceTransformer (already configured) | Without proper merge, only one JDBC driver SPI entry survives |
| JDBC interface delegation boilerplate | Hand-writing 40+ Connection method stubs | Use the delegation pattern — only override intercepted methods, `throw new UnsupportedOperationException` for truly unused ones | JDBC interfaces are large; total delegation is manageable if the pattern is consistent |
| URL claiming logic | Custom URL prefix scheme | Extend existing `acceptsURL("jdbc:postgresql:")` in CrateProxyDriver | Phase 1 already claims this prefix correctly; no new URL scheme needed |

---

## Common Pitfalls

### Pitfall 1: NULL-checking the Swallow Return Signal

**What goes wrong:** `SqlRewriter.rewrite()` returns `null` for swallowed statements. If the caller passes `null` directly to `real.execute(null)`, pgJDBC throws a NullPointerException (not a SQLException).

**How to avoid:** Every caller of `SqlRewriter.rewrite()` MUST check for `null` before delegating to the real driver. Return `false` / `0` / empty ResultSet immediately.

**Warning signs:** `NullPointerException` in proxy code during BEGIN/COMMIT/ROLLBACK handling.

### Pitfall 2: PreparedStatement Rewrite Happens at prepare() Time, Not execute() Time

**What goes wrong:** If the SQL rewrite happens in `PreparedStatement.execute()` (too late), the statement has already been compiled by the underlying driver with the original SQL. DDL rewriting must happen in `Connection.prepareStatement(String sql)` before the SQL reaches pgJDBC.

**How to avoid:** `CrateProxyConnection.prepareStatement(String sql)` calls `SqlRewriter.rewrite(sql)` BEFORE calling `real.prepareStatement(rewritten)`. `CrateProxyPreparedStatement` then wraps a real PreparedStatement that was already prepared with the rewritten SQL.

**Warning signs:** FK or type errors from CrateDB on DDL that should have been stripped.

### Pitfall 3: SELECT FOR UPDATE Must Be Stripped Before Delegation, Not After

**What goes wrong:** `SELECT ... FOR UPDATE` is the FIRST SQL issued by Keycloak (Liquibase lock service acquireLock). If it is not intercepted, CrateDB returns an error and Keycloak never starts its migration. This must be working before any DDL rewriting matters.

**How to avoid:** Implement `FOR UPDATE` stripping in Plan 1, not Plan 3. Test by verifying Keycloak gets past the lock acquisition step.

**Warning signs:** Keycloak log shows error before any `CREATE TABLE` — indicates the `FOR UPDATE` stripping is missing.

### Pitfall 4: ALTER TABLE Where All Operations Are Stripped Must Not Be Forwarded

**What goes wrong:** An `ALTER TABLE users ADD CONSTRAINT fk_role FOREIGN KEY (...)` statement has its FK operation stripped, leaving nothing. If the proxy serializes this as `ALTER TABLE users` and forwards it, CrateDB may error on the incomplete syntax.

**How to avoid:** After stripping all operations from an AlterTable statement, check if the remaining operation list is empty. If empty, return `null` (swallow the entire statement).

**Warning signs:** CrateDB errors like `unexpected end of statement` on ALTER TABLE.

### Pitfall 5: JSQLParser toString() May Not Preserve Original Formatting

**What goes wrong:** JSQLParser parses then serializes SQL. The serialized form may differ from the original in whitespace, quoting, or identifier case. If downstream code or Liquibase checksum validation depends on exact SQL form, this can cause issues.

**How to avoid:** Liquibase computes checksums on the XML changeset, NOT on the final SQL string — so SQL rewriting does not invalidate Liquibase checksums. [VERIFIED: FEATURES.md §Phase-Specific Warnings] The JSQLParser round-trip is safe for this use case.

### Pitfall 6: UNIQUE Constraint Distinction — ADD CONSTRAINT UNIQUE vs CREATE UNIQUE INDEX

**What goes wrong:** Unique constraints appear in two forms: (1) `ADD CONSTRAINT name UNIQUE (col)` in ALTER TABLE, and (2) `CREATE UNIQUE INDEX` (separate statement). The ALTER TABLE form is handled in `rewriteAlter()`. The CREATE INDEX form is a separate code path.

**How to avoid:** Handle both: strip ADD CONSTRAINT UNIQUE from ALTER statements (PRXY-06/PRXY-08), and for CREATE UNIQUE INDEX, either strip the UNIQUE keyword or allow it (CrateDB may or may not support unique indexes — verify). The REQUIREMENTS say to strip UNIQUE from DDL, so dropping the UNIQUE modifier from CREATE INDEX is the safe approach.

### Pitfall 7: SET TRANSACTION Needs Swallowing Too

**What goes wrong:** Hibernate issues `SET TRANSACTION ISOLATION LEVEL READ COMMITTED` at connection startup. This is not a `BEGIN` variant — it matches none of the standard transaction swallow patterns. CrateDB may reject it.

**How to avoid:** Add `upper.startsWith("SET TRANSACTION")` to the `isTransactionCommand()` check in `SqlRewriter`. [VERIFIED: FEATURES.md §Transaction Patterns table]

---

## Code Examples

### CrateProxyDriver.connect() Change (the only change to Phase 1 code)

```java
// Source: CONTEXT.md §Existing Code Insights
@Override
public Connection connect(String url, Properties info) throws SQLException {
    Connection real = REAL.connect(url, info);
    if (real == null) return null;
    return new CrateProxyConnection(real);
}
```

### SqlRewriter — Transaction Command Detection

```java
// Source: FEATURES.md §Transaction Patterns, ARCHITECTURE.md §SQL Rewriting Pipeline
private static boolean isTransactionCommand(String trimmed) {
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
```

### SELECT FOR UPDATE Stripping via JSQLParser

```java
// Source: FEATURES.md §5. SELECT FOR UPDATE Interception
// JSQLParser PlainSelect has a getForUpdate() / setForUpdate() API
if (stmt instanceof Select select && select.getSelectBody() instanceof PlainSelect ps) {
    if (ps.getForUpdateTable() != null || ps.isUseBrackets()) {
        ps.setForUpdateTable(null);
        ps.setForUpdate(false);
        ps.setWait(null);
        ps.setSkipLocked(false);
        return select.toString();
    }
}
```

### Type Remapping Helper

```java
// Source: FEATURES.md §Data Type Inventory + REQUIREMENTS.md PRXY-07
private static void remapColumnType(ColumnDefinition col) {
    ColDataType dt = col.getColDataType();
    String name = dt.getDataType().toUpperCase();
    switch (name) {
        case "CLOB":    case "NCLOB":   dt.setDataType("TEXT");    dt.setArgumentsStringList(null); break;
        case "BINARY":  case "TINYBLOB":dt.setDataType("BLOB");    dt.setArgumentsStringList(null); break;
        case "NVARCHAR":                dt.setDataType("VARCHAR"); break;  // keep length args
        case "TINYINT":                 dt.setDataType("SMALLINT"); break;
        case "TEXT":
            // Strip length parameter: TEXT(25500) → TEXT
            if (dt.getArgumentsStringList() != null) dt.setArgumentsStringList(null);
            break;
    }
}
```

---

## State of the Art

| Old Pattern | Current Pattern | When Changed | Impact |
|------------|-----------------|--------------|--------|
| `java.lang.reflect.Proxy` for JDBC wrapping | Concrete delegation classes | N/A — both valid | Concrete classes avoid `PreparedStatement` cast failures when callers downcast to vendor types |
| Regex-based SQL rewriting | JSQLParser AST mutation | JSQLParser 5.3 (2025) | Handles nested parens, multiline DDL, quoted identifiers |
| CrateDB JDBC driver (`crate-jdbc`) | pgJDBC 42.7.4 as the underlying connection | CrateDB 4+ | Official CrateDB recommendation; crate-jdbc is legacy |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | JSQLParser 5.3 `CreateTable.setTableOptionsStrings()` accepts raw `(number_of_replicas = '1')` string and serializes it correctly in `toString()` | Code Examples — PRXY-11 | Wrong: `WITH` clause may need special handling via a different JSQLParser API — fallback is appending the WITH clause as a string after `ct.toString()` |
| A2 | `PlainSelect.setForUpdate(false)` and clearing `forUpdateTable` is sufficient to strip the FOR UPDATE clause in JSQLParser 5.3 | Code Examples — PRXY-04 | Wrong: JSQLParser API may have changed — fallback is regex `\\s+FOR UPDATE.*$` after JSQLParser serialization |
| A3 | CrateDB 6.2 accepts `CREATE UNIQUE INDEX` (unique keyword on index) or silently ignores uniqueness | Pitfall 6 | Wrong: CrateDB may reject CREATE UNIQUE INDEX — fallback is stripping the UNIQUE keyword and creating a plain index |

---

## Open Questions

1. **JSQLParser API for WITH clause injection (PRXY-11)**
   - What we know: JSQLParser `CreateTable` has `tableOptionsStrings` / `tableOptionsList`
   - What's unclear: Whether `toString()` serializes these correctly with the `WITH` keyword, or requires explicit formatting
   - Recommendation: Implement and test; fallback is string append after `ct.toString()` if the AST path doesn't produce correct output

2. **CREATE UNIQUE INDEX handling**
   - What we know: Keycloak uses 63 UNIQUE constraints via ALTER TABLE ADD CONSTRAINT, not CREATE UNIQUE INDEX
   - What's unclear: Whether any Liquibase changeset generates a standalone `CREATE UNIQUE INDEX` statement
   - Recommendation: Search the changelogs for `CREATE UNIQUE INDEX` before Plan 2; handle if found

3. **JpaUpdate25_0_0_ConsentConstraints custom Java changeset**
   - What we know: This changeset runs on PostgreSQL and issues constraint manipulation SQL via direct JDBC
   - What's unclear: Exact SQL statements it issues — may include ADD/DROP CONSTRAINT operations not covered by standard rewrite rules
   - Recommendation: Monitor logs in Phase 3; add rewrite rules if it surfaces a new pattern

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 (inside Maven Docker image) | Building proxy JAR | Already in docker-compose.yml | `maven:3.9-eclipse-temurin-21` | — |
| JSQLParser 5.3 | SQL parsing/rewriting | Already in pom.xml | 5.3 | — |
| pgJDBC 42.7.4 | Underlying JDBC driver | Already in pom.xml | 42.7.4 | — |
| Maven shade plugin 3.6.2 | Fat JAR build | Already in pom.xml | 3.6.2 | — |

All build dependencies are already declared and cached in the `maven-cache` Docker volume from Phase 1. No new packages to download.

---

## Security Domain

> This is a local PoC with no external exposure. The proxy JAR runs inside Keycloak's JVM within a Docker network. No authentication surface is added by the proxy itself. Security enforcement is not applicable to this implementation phase.

The proxy logs all SQL to `System.err` by design (D-02). In a production context this would be a security concern (SQL may contain credential data). For this PoC it is intentional and documented.

---

## Sources

### Primary (HIGH confidence)
- `proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java` — Phase 1 stub; Phase 2 modifies `connect()` only
- `proxy/pom.xml` — Dependency versions verified directly
- `.planning/research/FEATURES.md` — Keycloak SQL pattern inventory (grepped from all 74 changelogs)
- `.planning/research/STACK.md` — JDBC proxy delegation pattern, JSQLParser usage examples
- `.planning/research/ARCHITECTURE.md` — SQL rewriting pipeline stages, delegation pattern code
- `.planning/research/PITFALLS.md` — M-4 (PreparedStatement interception), C-2 (ROLLBACK), C-3 (FK)
- `02-CONTEXT.md` — Locked decisions D-01 through D-05

### Secondary (MEDIUM confidence)
- `research.md` — Initial CrateDB SQL compatibility survey
- JSQLParser 5.3 docs: https://jsqlparser.github.io/JSqlParser/ — visitor pattern, AST structure

### Tertiary (LOW confidence — not verified in this session)
- A1-A3 in Assumptions Log: exact JSQLParser 5.3 API behavior for WITH clause and FOR UPDATE stripping

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all from existing verified project research and in-repo code
- Architecture: HIGH — delegation pattern from STACK.md + ARCHITECTURE.md (both HIGH confidence)
- Rewrite rules: HIGH — SQL patterns enumerated from Keycloak source grep in FEATURES.md
- JSQLParser API specifics: MEDIUM — pattern is well-understood, exact API calls need verification during implementation

**Research date:** 2026-04-22
**Valid until:** Indefinite — no external dependencies changed; all findings from project's own source files and already-verified research
