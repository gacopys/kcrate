<!-- GSD:project-start source:PROJECT.md -->
## Project

**Keycloak on CrateDB**

A Docker Compose proof-of-concept that runs Keycloak backed by a 3-node CrateDB cluster. Because CrateDB is not natively supported by Keycloak, this project includes a custom JDBC proxy driver (Java) that intercepts Keycloak's SQL traffic and rewrites it into CrateDB-compatible statements — swallowing transaction commands and stripping unsupported DDL — before forwarding to CrateDB.

**Core Value:** Keycloak runs with full functionality on a 3-node CrateDB cluster, with the proxy JAR injected via volume mount into the official Keycloak image (no code changes to either Keycloak or CrateDB).

### Constraints

- **Compatibility**: No changes to Keycloak or CrateDB code — official Docker images only
- **Language**: JDBC proxy must be Java (JDBC is a Java API), targeting Java 21 to match Keycloak
- **Scope**: Proof of concept — correctness over production-readiness
- **SQL rewriting**: Proxy must handle all Keycloak/Liquibase SQL patterns; unknown gaps will surface during testing
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## 1. JDBC Proxy Driver Pattern
### Recommended Approach: Hand-rolled delegation wrapper (no framework)
| Class | Implements | Purpose |
|-------|-----------|---------|
| `CrateProxyDriver` | `java.sql.Driver` | Entry point; URL claiming; wraps real driver |
| `CrateProxyConnection` | `java.sql.Connection` | Swallows `commit`/`rollback`/`setAutoCommit`; delegates everything else |
| `CrateProxyStatement` | `java.sql.Statement` | Intercepts `execute*`; runs SQL through rewriter |
| `CrateProxyPreparedStatement` | `java.sql.PreparedStatement` | Same for prepared statements; rewrites at prepare time |
## 2. SQL Parsing and Rewriting
### Recommended Library: JSQLParser 5.3
| Library | Verdict | Reason |
|---------|---------|--------|
| JSQLParser 5.3 | **Use this** | Lightweight, visitor-pattern AST, covers DDL+DML, no query planning overhead, actively maintained |
| Apache Calcite | Reject | Full query planner/optimizer framework — brings in 10+ MB of dependencies, requires schema registration, designed for implementing databases not rewriting SQL. Massive overkill for a proxy. |
| ANTLR grammar | Reject | Would require maintaining a custom PostgreSQL grammar. JSQLParser already does this. |
| Regex rewriting | Reject for DDL | Safe for trivial cases (`BEGIN`, `COMMIT`, `ROLLBACK` are fine with regex) but breaks on nested parens in DDL and multi-statement batches. Cannot reliably parse `CREATE TABLE ... FOREIGN KEY (...)` clauses with regex. |
- `BEGIN` → swallow (exact match or `sql.trim().equalsIgnoreCase("BEGIN")`)
- `COMMIT` → swallow
- `ROLLBACK` → swallow
- `ROLLBACK TO SAVEPOINT ...` → swallow
- `CREATE TABLE` — strip `FOREIGN KEY` index entries and `REFERENCES` column specs
- `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY` — strip entire statement
- `CREATE SEQUENCE` / `ALTER SEQUENCE` — remap or stub
- Type remapping (`bytea → TEXT`, `uuid → TEXT`, `jsonb → OBJECT`) — requires inspecting column type tokens in `CreateTable`
## 3. Keycloak JDBC Driver Injection
### Exact Mechanism (Keycloak 24+, Quarkus-based)
# docker-compose.yml
## 4. CrateDB Docker Clustering
### Image and Version
### PostgreSQL Wire Protocol Note
### 3-Node Docker Compose Configuration
| Parameter | Value | Why |
|-----------|-------|-----|
| `network.host=_site_` | Site-local address | Binds to the container's Docker network interface, not loopback |
| `discovery.seed_hosts` | Other two nodes | Unicast discovery; each node lists the peers it knows at startup |
| `cluster.initial_master_nodes` | All three node names | Required for cluster bootstrap; identical on all nodes; CrateDB 4+ auto-calculates quorum from this |
| `gateway.expected_data_nodes=3` | 3 | Cluster waits for all 3 nodes before starting shard recovery |
| `gateway.recover_after_data_nodes=2` | 2 | Allows recovery to proceed if at least 2 of 3 nodes are up (quorum) |
| `CRATE_HEAP_SIZE=1g` | 1 GB | Minimum viable for PoC; production would be ~50% of available RAM |
## 5. Build Tooling
### Recommended: Maven 3.9+ with maven-shade-plugin 3.6.2
# Output: target/crate-proxy-1.0-SNAPSHOT.jar (fat JAR ~5MB with pgJDBC + JSQLParser)
## Complete Dependency Summary
| Artifact | Version | Purpose | Scope |
|----------|---------|---------|-------|
| `org.postgresql:postgresql` | `42.7.4` | Real JDBC driver; proxy delegates to this | Compile (shaded into fat JAR) |
| `com.github.jsqlparser:jsqlparser` | `5.3` | SQL AST parsing and DDL rewriting | Compile (shaded into fat JAR) |
| `org.apache.maven.plugins:maven-shade-plugin` | `3.6.2` | Fat JAR build with service merging | Maven plugin |
| `quay.io/keycloak/keycloak` | `latest` (26.5.2) | Keycloak server | Docker image |
| `crate` | `6.2` | CrateDB cluster nodes | Docker image |
## Alternatives Considered
| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| SQL proxy framework | Hand-rolled delegation | P6Spy 3.9.x | Observability tool, not designed for rewriting; no clean hook for DDL transformation |
| SQL proxy framework | Hand-rolled delegation | datasource-proxy | Same as P6Spy; listener model, not a URL-claiming Driver implementation |
| SQL proxy framework | Hand-rolled delegation | CrateDB crate-jdbc 2.7.0 | Legacy, non-standard URL scheme, ClassNotFoundException reported with Keycloak (Discussion #21315) |
| SQL parser | JSQLParser 5.3 | Apache Calcite | Full query planner, 10+ MB deps, schema registration required — overkill for rewriting |
| SQL parser | JSQLParser 5.3 | ANTLR grammar | Requires writing and maintaining a PostgreSQL grammar file |
| SQL parser | JSQLParser 5.3 | Regex | Sufficient for BEGIN/COMMIT/ROLLBACK but not for nested DDL structures |
| Build tool | Maven + shade 3.6.2 | Gradle + shadow plugin | Both valid; Maven shade has better JDBC `META-INF/services` merge documentation |
| CrateDB JDBC connection | pgJDBC 42.7.4 | crate-jdbc 2.7.0 | Legacy driver; pgJDBC 42.7.x is officially recommended by CrateDB docs for 6.x |
## Sources
- CrateDB Docker Hub official image: https://hub.docker.com/_/crate (verified April 2026: version 6.2.6)
- CrateDB multi-node setup: https://cratedb.com/docs/crate/howtos/en/latest/clustering/multi-node-setup.html
- CrateDB PostgreSQL JDBC guide: https://cratedb.com/docs/guide/connect/java/postgresql-jdbc.html
- Keycloak Red Hat 26.0 DB config guide: https://docs.redhat.com/en/documentation/red_hat_build_of_keycloak/26.0/html/server_configuration_guide/db-
- Keycloak Discussion #11265 (Cloud SQL JDBC wrapper injection): https://github.com/keycloak/keycloak/discussions/11265
- Keycloak Discussion #21315 (ClassNotFoundException with custom driver): https://github.com/keycloak/keycloak/discussions/21315
- Keycloak Issue #26168 (KC_DB_DRIVER propagation fix in KC 24): https://github.com/keycloak/keycloak/issues/26168
- Keycloak Quarkus classloader behavior: https://github.com/keycloak/keycloak/discussions/10496
- JSQLParser 5.3 documentation: https://jsqlparser.github.io/JSqlParser/
- JSQLParser changelog (5.3 released May 17, 2025): https://jsqlparser.github.io/JSqlParser/changelog.html
- maven-shade-plugin 3.6.2 release (March 5, 2025): https://github.com/apache/maven-shade-plugin/releases
- maven-shade-plugin ServicesResourceTransformer: https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html
- pgJDBC 42.7.x releases: https://jdbc.postgresql.org/changelogs/2026-01-15-42/
- P6Spy driver URL pattern (reference only): https://p6spy.readthedocs.io/en/latest/install.html
- CrateDB SQL compatibility reference: https://cratedb.com/docs/crate/reference/en/latest/appendices/compatibility.html
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
