# Phase 2: JDBC Proxy Implementation - Context

**Gathered:** 2026-04-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the Phase 1 passthrough stub with a full SQL interception and rewriting engine. The proxy must wrap pgJDBC's Connection, Statement, and PreparedStatement across all 4 JDBC execution paths and implement 9 rewrite layers covering transaction swallowing, DDL transformation, type remapping, and index rewriting.

Phase 2 is complete when all 11 PRXY requirements pass: BEGIN/COMMIT/ROLLBACK are swallowed, FK/UNIQUE constraints are stripped, types are remapped, ALTER TABLE unsupported ops are stripped, index DDL is cleaned, and CREATE TABLE includes `WITH (number_of_replicas = '1')`.

</domain>

<decisions>
## Implementation Decisions

### Parse Failure Behavior
- **D-01:** When JSQLParser cannot parse a SQL statement, the proxy **throws a `SQLException`** — it does NOT silently pass through. Any unhandled SQL pattern must surface immediately rather than reaching CrateDB and producing a confusing wire-protocol error.

### SQL Rewrite Logging
- **D-02:** Every successful rewrite is **logged to `System.err` unconditionally** — original SQL and rewritten SQL on each interception. No log framework, no level gating. Verbose but maximally visible during Phase 3 migration testing.

### Sequence Handling
- **D-03:** `CREATE SEQUENCE` and `ALTER SEQUENCE` are **stripped silently** (swallowed, return success) — CrateDB cannot create sequences regardless.
- **D-04:** If `nextval(...)` appears in a query body, it is **treated as unhandled SQL → throws `SQLException`** (consistent with D-01). Modern Keycloak v26.5.x generates UUIDs in Java and likely never calls NEXTVAL, but the exception will surface it immediately if it does.

### Plan Structure
- **D-05:** Phase 2 is split into **3 plans by concern**:
  - Plan 1: `CrateProxyConnection`, `CrateProxyStatement`, `CrateProxyPreparedStatement` wrappers + transaction swallowing (BEGIN/COMMIT/ROLLBACK/SAVEPOINT) + SELECT FOR UPDATE stripping
  - Plan 2: DDL rewrites — FK constraint stripping, UNIQUE constraint stripping, type remapping (CLOB/NCLOB/BINARY/TINYBLOB/NVARCHAR/TINYINT), ALTER TABLE/COLUMN unsupported op stripping, CREATE/ALTER SEQUENCE stripping
  - Plan 3: Index DDL rewrites — PostgreSQL cast expression stripping (`::varchar`), partial index WHERE clause stripping + CREATE TABLE WITH clause injection (`WITH (number_of_replicas = '1')`)

### Claude's Discretion
- **Rewriter class structure** — whether a single `SqlRewriter` class handles all rules or rules are split into per-concern classes is left to the planner. CLAUDE.md recommends a single rewriter with a visitor pattern.
- **Statement interception granularity** — whether `CrateProxyStatement` wraps `execute`, `executeQuery`, `executeUpdate` individually or funnels through a single intercept method is left to the planner.
- **NEXTVAL detection** — exact mechanism (string match vs JSQLParser Function visitor) is left to the planner.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Specs
- `CLAUDE.md` — Full tech stack decisions: JSQLParser 5.3 (use this, not Calcite/ANTLR/regex for DDL), hand-rolled delegation wrapper pattern (CrateProxyDriver → CrateProxyConnection → CrateProxyStatement), maven-shade-plugin ServicesResourceTransformer, exact type remapping table (bytea→TEXT, uuid→TEXT, jsonb→OBJECT — note: REQUIREMENTS.md PRXY-07 has different mappings; REQUIREMENTS.md takes precedence for the PoC)
- `research.md` — Full list of CrateDB SQL incompatibilities and why each rewrite is needed
- `.planning/REQUIREMENTS.md` §Proxy Core — PRXY-01 through PRXY-11 are the acceptance criteria; each must be traceable to a plan

### Existing Code (extend, don't replace)
- `proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java` — Phase 1 passthrough stub; Phase 2 changes `connect()` to return a `CrateProxyConnection` instead of the raw pgJDBC connection
- `proxy/pom.xml` — Already contains JSQLParser 5.3 and pgJDBC 42.7.4; no dependency changes needed
- `proxy/src/main/resources/META-INF/services/java.sql.Driver` — SPI registration; unchanged in Phase 2

### JSQLParser
- JSQLParser 5.3 docs: https://jsqlparser.github.io/JSqlParser/ — visitor pattern for DDL AST traversal

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `CrateProxyDriver.java` — entry point; `connect()` currently returns raw pgJDBC Connection; Phase 2 wraps it with `CrateProxyConnection`
- `proxy/pom.xml` — JSQLParser 5.3 already on classpath; no build changes

### Established Patterns
- Driver self-registration via static initializer (already in CrateProxyDriver)
- pgJDBC deregistration trick to prevent URL dispatch conflict (already implemented)
- Named volume JAR injection into `/opt/keycloak/providers/` (Phase 1 established)

### Integration Points
- `CrateProxyDriver.connect()` → wraps returned `Connection` in `CrateProxyConnection`
- `CrateProxyConnection.createStatement()` → returns `CrateProxyStatement`
- `CrateProxyConnection.prepareStatement()` → returns `CrateProxyPreparedStatement`
- All 4 execution paths route through a single `SqlRewriter` before forwarding to pgJDBC

</code_context>

<specifics>
## Specific Ideas

- D-01 (throw on parse failure) + D-04 (throw on nextval) are intentionally strict — Phase 3 migration is the real integration test, and loud failures are better than silent wrong behavior for a PoC
- D-02 (unconditional stderr logging) means every SQL rewrite will be visible in `docker compose logs keycloak` during Phase 3 — useful for verifying each rewrite rule fires correctly

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 02-jdbc-proxy-implementation*
*Context gathered: 2026-04-22*
