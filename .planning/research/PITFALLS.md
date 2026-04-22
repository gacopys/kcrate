# Domain Pitfalls: Keycloak + CrateDB JDBC Proxy PoC

**Domain:** JDBC proxy bridging Keycloak to CrateDB
**Researched:** 2026-04-22
**Overall confidence:** HIGH for SQL gaps (official docs + community confirmation); MEDIUM for JDBC class-loading (community + Keycloak docs, no direct proxy-pattern confirmation); HIGH for clustering; HIGH for Liquibase behavior

---

## Critical Pitfalls

These will cause the PoC to not start at all, or produce silent data corruption.

---

### Pitfall C-1: Keycloak Requires a Build Phase Before Custom Drivers Work

**What goes wrong:** Placing the proxy JAR in `/opt/keycloak/providers/` and setting `KC_DB_DRIVER` is not sufficient on its own. The Quarkus-based Keycloak performs a build-time augmentation step (`kc.sh build`) that compiles driver awareness into the optimized image. If you skip this step, Keycloak cannot find the driver class at runtime and throws `ClassNotFoundException: <YourDriverClass>` on startup.

**Why it happens:** Quarkus augmentation is not a normal classloader — it statically wires dependencies at build time. Externally-placed JARs that are not present during the build phase are invisible at runtime.

**Evidence:** CrateDB community thread (`Using Crate JDBC in Dockerfile for Keycloak`) documented the exact `ClassNotFoundException: io.crate.client.jdbc.CrateDriver` failure. Keycloak's own docs state: "Overriding the built-in database drivers or supplying your own drivers is considered unsupported" — only Oracle and Aurora are officially documented exceptions.

**Consequences:** Keycloak fails to start. No useful error beyond the ClassNotFoundException.

**Prevention:**
- Build a custom Keycloak image that places the proxy JAR in `/opt/keycloak/providers/` and then runs `kc.sh build --db=postgres --db-driver=<YourProxyDriverClass>` inside the Dockerfile. The resulting image is already augmented.
- Do NOT rely on volume-mounting the JAR alone without the build step.
- Verify: the `KC_DB_DRIVER` env var must name the class registered via `java.sql.Driver` SPI in your proxy JAR's `META-INF/services/java.sql.Driver` file.

**Warning signs:** `ClassNotFoundException` or `Unable to find the JDBC driver` in Keycloak startup logs.

**Phase:** Must be resolved in Phase 1 (environment bootstrap). Nothing else can be tested until Keycloak starts.

---

### Pitfall C-2: CrateDB Rejects ROLLBACK, SAVEPOINT, and BEGIN — They Cannot Be Suppressed at the Wire Level

**What goes wrong:** Keycloak sends `BEGIN`, `COMMIT`, `ROLLBACK`, and `SAVEPOINT` statements. CrateDB throws `mismatched input 'ROLLBACK'` and similar parse errors. Using the plain PostgreSQL JDBC driver without a proxy produces this failure immediately. The `--transaction-xa-enabled=false` Keycloak flag does NOT eliminate these statements — they are embedded in Liquibase and Hibernate behavior.

**Why it happens:** CrateDB has no transaction implementation. It parses these keywords and rejects them at the SQL parser level. They are not silently ignored.

**Evidence:** CrateDB community thread confirmed `PSQLException: ERROR: line 1:1: mismatched input 'ROLLBACK'` even after transaction disabling flags. GitHub issue #6863 (2018) confirmed no transaction support; no evidence this has changed.

**Consequences:** Every Keycloak operation that crosses a transaction boundary (which is most of them under Hibernate) fails immediately. The entire schema migration fails on the first `BEGIN`.

**Prevention:**
- The proxy must intercept `Connection.setAutoCommit(false)`, `Connection.commit()`, `Connection.rollback()`, and any `Statement.execute()` call whose SQL matches `BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `RELEASE SAVEPOINT`. These must be swallowed silently (return success without forwarding to CrateDB).
- For `Connection.getAutoCommit()`, always return `true` to prevent the application from sending transactional SQL.
- Test interception coverage with Liquibase-specific patterns: Liquibase wraps each changeset in an explicit transaction by default.

**Warning signs:** `PSQLException` containing `mismatched input` on `ROLLBACK` or `BEGIN` in Keycloak startup logs.

**Phase:** Phase 1/2. This is the first blocking incompatibility the proxy must solve.

---

### Pitfall C-3: CrateDB Has No Foreign Key Support — Liquibase's `addForeignKeyConstraint` Will Fail

**What goes wrong:** Keycloak's Liquibase changelogs issue roughly 30+ `ADD CONSTRAINT ... FOREIGN KEY` statements. CrateDB silently ignores FK definitions in `CREATE TABLE`, but `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY` is an unsupported syntax. The Liquibase changeset fails mid-run.

**Why it happens:** CrateDB's DDL parser recognizes `REFERENCES` syntax inside `CREATE TABLE` (it is parsed but not enforced), but `ALTER TABLE ADD CONSTRAINT FOREIGN KEY` is not implemented.

**Evidence:** CrateDB `information_schema.table_constraints` only exposes `PRIMARY_KEY` constraints — no FK entries exist. Metabase issue #6699 ("CrateDB driver: foreign keys not supported") confirms FK is absent. The Keycloak changelog v1.0 already defines 30+ FK constraints via both inline `REFERENCES` and separate `addForeignKeyConstraint` changesets.

**Consequences:** Liquibase gets stuck on the first FK `ALTER TABLE`. The changeset is marked failed (no entry in `DATABASECHANGELOG`), so it retries on the next start and fails again — infinite retry loop.

**Prevention:**
- The proxy must detect `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY` patterns in SQL strings and silently swallow them (return success without forwarding).
- Also swallow `DROP CONSTRAINT` for FK constraints (Liquibase issues these on schema upgrades).
- Be aware that Liquibase also queries `information_schema.table_constraints` to check FK existence before dropping — CrateDB returns no rows for FK queries, which is correct behavior.

**Warning signs:** Liquibase log shows `Migration failed for change set ... addForeignKeyConstraint`; Keycloak restart shows the same changeset failing repeatedly.

**Phase:** Phase 2 (schema migration). Critical prerequisite for all further testing.

---

### Pitfall C-4: CrateDB DDL Is Non-Transactional — Partial Migrations Leave Inconsistent State

**What goes wrong:** CrateDB does not wrap DDL in transactions. If a Liquibase changeset creates three tables and fails on the fourth, tables 1-3 are committed and table 4 does not exist. Liquibase does not write a `DATABASECHANGELOG` entry for the failed changeset, so on next startup it retries the entire changeset — but tables 1-3 already exist, causing `TABLE ALREADY EXISTS` errors, which causes Liquibase to fail again. This is a permanent stuck state without manual intervention.

**Why it happens:** Liquibase's retry logic assumes that if no `DATABASECHANGELOG` entry exists, nothing from that changeset was applied. This assumption is only valid for transactional DDL databases. CrateDB commits each DDL statement as it is executed.

**Evidence:** General Liquibase documentation confirms: "If a changeset fails before completion, no record is inserted in DATABASECHANGELOG, so Liquibase will treat it as unrun on the next update... the initial DDLs now error ('table already exists'), so it fails again."

**Consequences:** PoC is in an irrecoverable state requiring manual cleanup of partially-created tables in CrateDB.

**Prevention:**
- Make all proxy SQL rewrites idempotent: use `CREATE TABLE IF NOT EXISTS` rewrites, or ensure that `TABLE ALREADY EXISTS` errors from CREATE TABLE are swallowed and returned as success.
- Monitor Liquibase logs carefully on first run — do not restart Keycloak if migration is partially through without first checking what tables were created.
- Keep a cleanup script that can drop all Keycloak tables from CrateDB to allow a clean retry.

**Warning signs:** Liquibase log shows `Table 'X' already exists` on a restart following a failed migration.

**Phase:** Phase 2. Have a `DROP TABLE` cleanup script ready before starting the first schema migration run.

---

### Pitfall C-5: CrateDB Sequences Do Not Exist — `nextval()` Calls Will Fail

**What goes wrong:** CrateDB has no `CREATE SEQUENCE` or `nextval()` / `currval()` functions. Any SQL containing these calls will fail with an `Unknown function` error. Keycloak itself uses UUID VARCHAR(36) primary keys (not sequences), but Liquibase's PostgreSQL dialect may generate sequence DDL for certain changeset types, and some Keycloak changelogs (version 2.x era) contain sequence-related statements.

**Why it happens:** CrateDB was designed for time-series/analytics and omitted sequences as an OLTP construct.

**Evidence:** CrateDB documentation explicitly states no sequence support. CrateDB's recommended alternative is `gen_random_text_uuid()` for string IDs or timestamp-based defaults for numeric IDs.

**Consequences:** Any changeset that issues `CREATE SEQUENCE` or `SELECT nextval(...)` fails. If these appear in Keycloak changelogs, Liquibase gets stuck (see Pitfall C-4 for the retry loop).

**Prevention:**
- Inspect all Keycloak jpa-changelog XML files for `createSequence` Liquibase tags before starting the PoC.
- If found, the proxy must intercept `CREATE SEQUENCE` statements and return success without forwarding, and intercept `nextval()` calls and substitute a UUID generation strategy.
- Modern Keycloak (v24+) uses UUID PKs throughout — sequences are unlikely but must be verified.

**Warning signs:** `Unknown function: nextval` in CrateDB logs.

**Phase:** Phase 2 verification step — scan changelogs before first run.

---

## Moderate Pitfalls

These will cause failures that require targeted fixes but won't permanently block the PoC.

---

### Pitfall M-1: `pg_catalog` Function Gaps Break Liquibase Introspection

**What goes wrong:** Liquibase's PostgreSQL dialect queries `pg_catalog` tables to introspect the existing schema (check if tables, indexes, and constraints exist before creating them). CrateDB is missing several `pg_catalog` functions that these queries rely on: `pg_catalog.pg_encoding_to_char()` and `pg_catalog.pg_table_is_visible()` are confirmed absent. Additional catalog functions may also be missing.

**Why it happens:** CrateDB implements the PostgreSQL wire protocol but only partially emulates `pg_catalog`. It has made incremental improvements (e.g., `pg_index.indnkeyatts` was added) but the catalog is incomplete.

**Consequences:** Liquibase introspection queries fail, causing Liquibase to either crash or fall back to incorrect assumptions about schema state.

**Prevention:**
- The proxy must intercept queries containing `pg_catalog.pg_encoding_to_char` and `pg_catalog.pg_table_is_visible` and return plausible stub results (e.g., empty result sets or hardcoded values).
- Alternatively, configure Liquibase to skip snapshot introspection where possible.
- Test with `psql` connected to CrateDB before wiring Liquibase — run `\l` and `\d` commands and observe which functions fail; those are the ones the proxy must stub.

**Warning signs:** Liquibase startup error containing `Unknown function: pg_catalog.*` before any changeset is applied.

**Phase:** Phase 2. Likely surfaces on first Liquibase run before any schema migration happens.

---

### Pitfall M-2: `ALTER COLUMN` Is Not Supported — Liquibase `modifyDataType` Will Fail

**What goes wrong:** CrateDB supports `ALTER TABLE ... ADD COLUMN` but does NOT support `ALTER TABLE ... ALTER COLUMN` (changing a column's data type). Keycloak's Liquibase changelogs (especially the 2.5.0 unicode migration) issue `modifyDataType` changesets that emit `ALTER COLUMN` SQL.

**Why it happens:** CrateDB's column schema is tied to its Lucene index structure; changing a column type requires rebuilding the index, which CrateDB does not support in-place.

**Evidence:** GitHub issue #13874 on crate/crate ("Ability to ALTER COLUMN changing data type") is open with no resolution. The Liquibase `jpa-changelog-2.5.0.xml` contains multiple `modifyDataType` operations for Unicode support.

**Consequences:** The 2.5.0 unicode changeset fails. Liquibase gets stuck (retry loop per Pitfall C-4).

**Prevention:**
- The proxy must detect `ALTER TABLE ... ALTER COLUMN ... TYPE` and `ALTER TABLE ... MODIFY COLUMN` patterns and swallow them (return success).
- Accept the trade-off: the column type change is not applied, but the data already in CrateDB is stored as TEXT anyway (CrateDB maps most string types to TEXT internally), so the practical impact is likely zero for a PoC.

**Warning signs:** `Liquibase error: Cannot alter column type` or `syntax error near ALTER COLUMN`.

**Phase:** Phase 2, specifically during unicode-related changelogs.

---

### Pitfall M-3: CrateDB Cluster Fails to Form If `cluster.initial_master_nodes` Is Misconfigured

**What goes wrong:** Each CrateDB node in a Docker Compose setup must know the names of all other master-eligible nodes at startup via `cluster.initial_master_nodes`. If even one node starts with a misconfigured or missing value, it enters a "master not discovered yet" loop and never joins the cluster. Because Compose starts containers near-simultaneously, there is a timing window where nodes try to discover each other before the network is ready.

**Why it happens:** CrateDB's cluster bootstrap requires a quorum of master-eligible nodes to agree on the initial cluster state. If a node is started without `cluster.initial_master_nodes` (or with `discovery.type=single-node`), it permanently refuses to join a cluster even if the setting is later corrected.

**Evidence:** GitHub issue #12018 on crate/crate showed the "master not discovered yet...have discovered []" error pattern from a port mismatch (inter-node uses port 4300, not 4200). Docker Compose documentation confirms `service_healthy` dependency ordering is needed.

**Consequences:** The 3-node cluster never forms. CrateDB health check never passes. Keycloak's `depends_on` condition is never satisfied. Everything stalls.

**Prevention:**
- Every node must set `cluster.initial_master_nodes=cratedb01,cratedb02,cratedb03` (all three node names).
- Every node must expose port 4300 for inter-node transport (distinct from 4200 for HTTP and 5432 for PostgreSQL wire).
- Use `depends_on: condition: service_healthy` in Keycloak's Compose service definition, pointing to all three CrateDB nodes.
- Define a health check on CrateDB nodes that hits the `/_cluster/health` HTTP endpoint and waits for `status: green` (not just process start).
- Do NOT use `discovery.type=single-node` on any node in a multi-node cluster.

**Warning signs:** CrateDB logs showing repeated `master not discovered yet` or `this node has not previously joined a bootstrapped cluster`.

**Phase:** Phase 1 (infrastructure). Must pass before any JDBC work begins.

---

### Pitfall M-4: Prepared Statement Interception Requires Handling Both `execute()` and `addBatch()`

**What goes wrong:** JDBC has two statement execution paths: simple `Statement.execute(sql)` and `PreparedStatement` with `addBatch()` / `executeBatch()`. Keycloak and Hibernate use prepared statements heavily. A proxy that only intercepts `execute()` will miss transaction and FK SQL that arrives via `addBatch()`. Similarly, `PreparedStatement` does not pass the SQL at execution time — only at prepare time — so the proxy must intercept at `Connection.prepareStatement(sql)`, not at `execute()`.

**Why it happens:** The JDBC API has three interface levels: `Connection`, `Statement`, and `PreparedStatement`. A proxy that only wraps `Statement.execute()` leaves `PreparedStatement` flows unwrapped.

**Evidence:** Hibernate's `StatementInspector` documentation notes it "does not mix well with JDBC batching, as batching requires intercepting the addBatch and executeBatch method calls." General JDBC proxy pattern (datasource-proxy, P6Spy) confirm all three interception points are required.

**Consequences:** Transaction SQL (`BEGIN`/`ROLLBACK`) or FK DDL that reaches CrateDB via prepared statement paths causes failures that appear random (work sometimes, fail other times depending on Hibernate's batching strategy).

**Prevention:**
- Proxy must wrap all three JDBC entry points: `Statement.execute*()`, `PreparedStatement.execute*()`, and `PreparedStatement.addBatch()` / `executeBatch()`.
- At `Connection.prepareStatement(sql)`, inspect the SQL immediately and decide whether to return a no-op stub (for swallowed statements) or a real `PreparedStatement` wrapper.
- Test explicitly with Hibernate batch operations (set `hibernate.jdbc.batch_size` to a non-zero value in test).

**Warning signs:** Inconsistent failures — FK or transaction errors that only appear during certain Keycloak operations (login flow, realm creation) but not others.

**Phase:** Phase 2/3. Likely surface during functional testing after schema migration succeeds.

---

### Pitfall M-5: CrateDB's 1-Second Refresh Interval Causes Stale Reads for Non-PK Queries

**What goes wrong:** CrateDB is eventually consistent for queries that do not use a full primary key in the WHERE clause. By default, the Lucene index refresh interval is 1000ms. A Keycloak query like `SELECT * FROM USER_SESSION WHERE ... LIKE '%token%'` may return stale results (missing a session that was just written) for up to 1 second after the write.

**Why it happens:** CrateDB uses Lucene IndexReaders that cache shard data. Writes are committed to the write-ahead log immediately but are only visible to search queries after a refresh cycle. Primary key lookups bypass this (direct shard + row lookup), but range queries, LIKE queries, and joins do not.

**Evidence:** CrateDB documentation explicitly states: "Data written with a former statement is not guaranteed to be fetched with the next following SELECT statement for the affected rows... until after a table refresh (default: 1 second)." CrateDB's own feature page confirms strong consistency only for full-PK WHERE clauses.

**Keycloak impact:** Keycloak's session store queries look up sessions by token value, not just by a primary key UUID. Some queries (e.g., email lookup during login) have been documented to trigger full table scans even with indexes. These queries will be subject to the 1-second staleness window.

**Consequences for PoC:** Authentication flows that write a session and immediately query it by a non-PK column may fail or return empty results. Token refresh operations are at risk. The PoC may appear to "work" on slow flows but fail on rapid automation tests.

**Prevention:**
- For the PoC, set `refresh_interval = '0ms'` on Keycloak's session and credential tables at schema creation time (or via a post-migration ALTER TABLE). This disables the refresh delay at a performance cost.
- Alternatively, test only with deliberate pauses between write and read operations during PoC validation.
- Do NOT present PoC results from load tests without acknowledging consistency limitations.

**Warning signs:** Login succeeds but subsequent token refresh returns "session not found"; user creation succeeds but user lookup returns empty immediately after.

**Phase:** Phase 3 (functional validation). Set `refresh_interval='0ms'` as a proxy post-migration step.

---

## Minor Pitfalls

These are annoying but have straightforward workarounds.

---

### Pitfall m-1: `DATABASECHANGELOGLOCK` Table Can Dead-Lock If Keycloak Is Killed Mid-Migration

**What goes wrong:** Liquibase acquires a row-level lock in `DATABASECHANGELOGLOCK` at migration start and releases it at completion. If Keycloak is force-killed during migration, the lock row remains set to `LOCKED=1`. On the next start, Liquibase waits indefinitely for the lock to clear (it will not).

**Why it happens:** Standard Liquibase behavior — the lock is not automatically expired.

**Prevention:**
- During PoC development, after any forced Keycloak restart: manually run `UPDATE DATABASECHANGELOGLOCK SET LOCKED=0, LOCKGRANTED=NULL, LOCKEDBY=NULL WHERE ID=1` against CrateDB before restarting Keycloak.
- Or add a Compose health check / init script that clears the lock on startup.

**Warning signs:** Keycloak startup log shows "Waiting for changelog lock..." and never proceeds.

**Phase:** Phase 2 operational concern. Document the unlock command in the project runbook.

---

### Pitfall m-2: CrateDB `bytea` Type Does Not Exist — Keycloak's Binary Columns Will Fail

**What goes wrong:** CrateDB has no `bytea` type. Keycloak's schema includes binary columns (e.g., for credential hash storage) that Hibernate maps to `bytea` on PostgreSQL. Liquibase changeset DDL with `type="bytea"` will fail when forwarded to CrateDB.

**Why it happens:** CrateDB stores binary data in separate BLOB tables, not as inline column types. The `bytea` keyword is not part of CrateDB's type system.

**Prevention:**
- The proxy must rewrite `bytea` to `BLOB` in DDL SQL strings, or to `TEXT` if Base64-encoded storage is acceptable for the PoC.
- Check Keycloak changelogs for `bytea`, `BINARY`, `VARBINARY`, and `LONGVARBINARY` type declarations and add them to the type remap table.

**Warning signs:** `Unknown data type: bytea` or similar parse error on CREATE TABLE.

**Phase:** Phase 2 — type remapping is part of the initial proxy implementation.

---

### Pitfall m-3: Split-Brain Risk Is Low in Compose But the Recovery Path Is Destructive

**What goes wrong:** In a 3-node CrateDB cluster in Docker Compose, a split-brain scenario (where nodes cannot agree on who the master is) is unlikely but possible if a node crashes and restarts during migration. CrateDB's documented behavior in partition scenarios: it prefers stale data over no data, promoting a replica with stale records to primary, causing data loss when the original primary rejoins.

**Why it happens:** CrateDB's resiliency model tolerates temporary inconsistency in partition scenarios. It acknowledges writes that may be lost if the primary is partitioned before replicating (up to a 90-second window with default ping settings).

**Consequences for PoC:** A Keycloak table row (e.g., a realm configuration) could silently disappear or duplicate after a node restart during migration.

**Prevention:**
- Set replication factor to 1 for the PoC (no replicas) to avoid the split-primary scenario — accept that node loss means data loss rather than silent corruption.
- Alternatively, set replicas=2 but use `minimum_master_nodes=2` to prevent split-brain writes from being acknowledged.
- Never kill individual CrateDB nodes during active Keycloak operations in PoC testing.

**Warning signs:** After a node restart, Keycloak reports realm or user data missing despite previously successful creation.

**Phase:** Phase 1 Compose configuration. Set replication strategy explicitly.

---

### Pitfall m-4: `JSONB` and `UUID` PostgreSQL Types Need Remapping in DDL

**What goes wrong:** Liquibase changelogs for newer Keycloak versions may use `uuid` or `jsonb` column types. CrateDB does not have a `uuid` type (use `TEXT`) or a `jsonb` type (use `OBJECT(DYNAMIC)` or `TEXT`). These will fail on CREATE TABLE if not remapped.

**Why it happens:** CrateDB has its own type system distinct from PostgreSQL. While it accepts VARCHAR(36) for UUID storage, it does not recognize the type keyword `uuid`.

**Prevention:**
- Proxy SQL rewriter must replace `uuid` type keyword with `TEXT` and `jsonb` with `TEXT` or `OBJECT(DYNAMIC)` in DDL statements.
- Verify using `SHOW COLUMNS FROM <table>` after migration to confirm types are applied.

**Warning signs:** `Unknown data type: uuid` or `Unknown data type: jsonb` in Keycloak startup logs.

**Phase:** Phase 2 — part of the core type-remapping implementation.

---

## Phase-Specific Warning Summary

| Phase | Topic | Most Likely Pitfall | Mitigation |
|-------|-------|---------------------|------------|
| Phase 1: Infrastructure | CrateDB cluster formation | M-3: cluster never forms due to misconfigured `initial_master_nodes` | Set all three node names; health check on `/_cluster/health?wait_for_status=green` |
| Phase 1: Infrastructure | Port exposure | M-3 variant: port 4300 not exposed breaks inter-node transport | Expose 4200, 4300, 5432 per node |
| Phase 2: Proxy + Schema | Driver class loading | C-1: ClassNotFoundException at Keycloak start | Build custom image with `kc.sh build` after adding JAR |
| Phase 2: Proxy + Schema | Transaction SQL | C-2: ROLLBACK rejected by CrateDB | Proxy must swallow all transaction lifecycle calls |
| Phase 2: Proxy + Schema | FK constraints | C-3: ALTER TABLE ADD FOREIGN KEY fails | Proxy swallows FK DDL; also intercept DROP CONSTRAINT FK |
| Phase 2: Proxy + Schema | Partial migration | C-4: stuck retry loop after partial DDL | Use CREATE TABLE IF NOT EXISTS rewrites; have cleanup script |
| Phase 2: Proxy + Schema | Type remapping | m-2, m-4: bytea/uuid/jsonb unknown | Proxy rewrites type keywords in DDL before forwarding |
| Phase 2: Proxy + Schema | pg_catalog gaps | M-1: Liquibase introspection fails | Proxy stubs `pg_encoding_to_char`, `pg_table_is_visible` |
| Phase 2: Proxy + Schema | ALTER COLUMN | M-2: modifyDataType changeset fails | Proxy swallows ALTER COLUMN TYPE statements |
| Phase 2: Proxy + Schema | Sequences | C-5: nextval() fails | Scan changelogs first; proxy swallows CREATE SEQUENCE |
| Phase 2: Proxy + Schema | Liquibase lock | m-1: stuck on LOCKED=1 after kill | Document manual unlock command in runbook |
| Phase 3: Functional | Prepared statements | M-4: batch/prepared path misses proxy interception | Proxy wraps prepareStatement() not just execute() |
| Phase 3: Functional | Eventual consistency | M-5: stale reads on non-PK queries | Set refresh_interval='0ms' on Keycloak session/user tables |
| Phase 3: Functional | Node restarts | m-3: silent data loss after node failure | Avoid killing nodes during active test; set explicit replication |

---

## Hard Limits the Proxy Cannot Fix

These are operations the proxy cannot rewrite into CrateDB-compatible SQL because CrateDB has no equivalent:

| Operation | CrateDB Behavior | Proxy Options |
|-----------|-----------------|---------------|
| `SELECT ... FOR UPDATE` | Not supported | Swallow lock hint, return plain SELECT — risks lost updates in concurrent scenarios |
| `LOCK TABLE` | Not supported | Swallow silently — no concurrent safety |
| `SAVEPOINT` / `RELEASE SAVEPOINT` | Not supported | Swallow silently — nested rollback semantics lost |
| Referential integrity enforcement | Not implemented | Strip FK DDL — application-level integrity only |
| Column type change in-place | Not supported | Swallow ALTER COLUMN TYPE — original type persists |
| Partial index (`CREATE INDEX ... WHERE`) | Not supported | No workaround; CrateDB indexes everything by default |
| True ACID multi-row transactions | Not supported | Auto-commit mode only — no rollback on failure |

For the PoC, swallowing all of these and accepting their semantic consequences is the declared approach. The PoC validates that Keycloak can complete its schema migration and basic auth flows, not that it maintains ACID guarantees.

---

## Sources

- [CrateDB Community: Using Crate JDBC in Dockerfile for Keycloak](https://community.cratedb.com/t/using-crate-jdbc-in-dockerfile-for-keycloak/1524) — confirmed ROLLBACK failure and ClassNotFoundException
- [Keycloak Discussion #21315: Cannot find Driver Class for DB Driver](https://github.com/keycloak/keycloak/discussions/21315) — Quarkus build-phase requirement for custom drivers
- [Keycloak Server DB Configuration](https://www.keycloak.org/server/db) — "Overriding built-in drivers is unsupported"; KC_DB_DRIVER documented
- [CrateDB GitHub Issue #6863: Will CrateDB ever support transactions?](https://github.com/crate/crate/issues/6863) — transaction support absent since inception
- [CrateDB GitHub Issue #10950: Support basic operations when using psql](https://github.com/crate/crate/issues/10950) — missing pg_catalog functions confirmed
- [CrateDB GitHub Issue #13874: Ability to ALTER COLUMN changing data type](https://github.com/crate/crate/issues/13874) — ALTER COLUMN not supported
- [Metabase Issue #6699: CrateDB driver foreign keys not supported](https://github.com/metabase/metabase/issues/6699) — FK absence confirmed
- [CrateDB Resiliency Issues (official docs)](https://cratedb.com/docs/crate/reference/en/latest/appendices/resiliency.html) — data loss in partition scenarios
- [CrateDB Eventual Consistency (official)](https://cratedb.com/product/features/eventual-consistency) — 1-second refresh interval, PK vs non-PK consistency
- [CrateDB Refresh documentation](https://cratedb.com/docs/crate/reference/en/latest/general/dql/refresh.html) — default 1000ms refresh_interval
- [CrateDB Primary Key strategies](https://cratedb.com/docs/guide/start/modelling/primary-key.html) — no sequences; gen_random_text_uuid() as alternative
- [CrateDB Multi-node setup](https://cratedb.com/docs/guide/admin/clustering/multi-node-setup.html) — cluster.initial_master_nodes configuration
- [CrateDB GitHub Issue #12018: master not discovered yet](https://github.com/crate/crate/issues/12018) — cluster bootstrapping failure pattern
- [Liquibase: How to resolve checksum errors](https://support.liquibase.com/hc/en-us/articles/29383059554331-How-to-resolve-checksum-errors) — partial failure and retry loop behavior
- [Hibernate StatementInspector and batching](https://in.relation.to/2016/06/14/how-to-intercept-jdbc-prepared-statement-calls/) — batch interception requirements
- [Keycloak jpa-changelog-1.0.0.Final.xml](https://github.com/keycloak/keycloak/blob/main/model/jpa/src/main/resources/META-INF/jpa-changelog-1.0.0.Final.xml) — UUID-based PKs, 30+ FK constraints confirmed
