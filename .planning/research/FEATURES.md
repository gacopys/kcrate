# Feature Landscape: Keycloak SQL Patterns for JDBC Proxy

**Domain:** Keycloak + CrateDB JDBC proxy PoC
**Researched:** 2026-04-22
**Sources:** Local Keycloak source clone (all 74 Liquibase changelogs + JPA entities), CrateDB documentation, CrateDB community forum

---

## Table Stakes

Features the JDBC proxy MUST handle or Keycloak will not start at all. Failure here means exception before the first table is created.

### 1. Transaction Statement Swallowing

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Swallow BEGIN / START TRANSACTION | Liquibase wraps every changeset in a transaction; Hibernate wraps every operation | Low | CrateDB accepts BEGIN silently as of 4.x — but historically threw a parse error on ROLLBACK |
| Swallow COMMIT | Emitted after every changeset and after every Hibernate flush | Low | CrateDB ignores COMMIT silently |
| Swallow ROLLBACK | Emitted on changeset failure and by Liquibase lock service | Low | CrateDB threw `mismatched input 'ROLLBACK'` parse error in the community-tested scenario — this is the first hard failure |
| Swallow SAVEPOINT / RELEASE SAVEPOINT | Hibernate emits savepoints for nested flushes | Low | No evidence Keycloak uses savepoints; standard Hibernate does not use them for PostgreSQL by default |
| Swallow SET TRANSACTION | Hibernate sets isolation level at connection time | Low | CrateDB ignores it, but the proxy should not forward it to avoid parse errors in older CrateDB versions |

**Confidence: HIGH** (confirmed from community forum failure log and CrateDB docs)

The Liquibase lock service (`CustomLockDatabaseChangeLogGenerator.java`) generates `SELECT id FROM DATABASECHANGELOGLOCK WHERE id=1 FOR UPDATE`. This is the very first SQL issued on startup (before any changelog runs). CrateDB does not support `SELECT FOR UPDATE`. See section 5.

---

### 2. Foreign Key Constraint Stripping

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Strip ADD FOREIGN KEY from CREATE TABLE | Liquibase emits FK clauses inside CREATE TABLE for some changesets | Low | Regex or parse of `REFERENCES` clause |
| Strip ALTER TABLE ADD CONSTRAINT ... FOREIGN KEY | 195 `addForeignKeyConstraint` Liquibase operations across all changelogs | Low | Most are separate ALTER TABLE statements |
| Strip DROP FOREIGN KEY / DROP CONSTRAINT for FK | 53 `dropForeignKeyConstraint` operations | Low | Safe to no-op — CrateDB has no FK state to drop |

**Confidence: HIGH** (counted directly from source: 195 FK additions, 53 FK drops across all changelogs through 26.7.0)

---

### 3. Unsupported Type Remapping (DDL phase)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Remap CLOB → TEXT | 20 `CLOB` column declarations across changelogs | Medium | CLOB appears in: `POLICY_CONFIG.VALUE`, `CREDENTIAL.SECRET_DATA`, `CREDENTIAL.CREDENTIAL_DATA`, `FED_USER_CREDENTIAL.SECRET_DATA/CREDENTIAL_DATA`, `REALM_LOCALIZATIONS.TEXTS`, `SERVER_CONFIG.VALUE`, `OFFLINE_USER/CLIENT_SESSION.DATA` | 
| Remap NCLOB → TEXT | 9 `NCLOB` column declarations | Medium | NCLOB appears in: `COMPONENT_CONFIG.VALUE` (23.0.0), `EVENT_ENTITY.DETAILS_JSON_LONG_VALUE` (23.0.0), `CLIENT_ATTRIBUTES.VALUE` (20.0.0), `USER_ATTRIBUTE.LONG_VALUE`, `FED_USER_ATTRIBUTE.LONG_VALUE` (24.0.0) |
| Remap BINARY(64) → TEXT or BYTEA substitute | 4 `BINARY(64)` column declarations (24.0.0) | Medium | Used for `USER_ATTRIBUTE.LONG_VALUE_HASH`, `USER_ATTRIBUTE.LONG_VALUE_HASH_LOWER_CASE`, `FED_USER_ATTRIBUTE.LONG_VALUE_HASH`, `FED_USER_ATTRIBUTE.LONG_VALUE_HASH_LOWER_CASE`. CrateDB has no BINARY column type. These are SHA-256 hashes stored as raw bytes. Remap to TEXT (hex-encoded) or drop the column since it's an index optimization |
| Remap TINYBLOB(16) → TEXT or drop | 3 `TINYBLOB(16)` declarations (all in v1.0 changelog) | Low | Used for deprecated `CREDENTIAL.SALT` column. The column is populated by v8.0.0 migration only on upgrade paths. For a fresh install the column still needs to be created. Remap to TEXT |
| Remap NVARCHAR(n) → VARCHAR(n) | 4 `NVARCHAR` declarations (26.7.0 adds `REALM.DISPLAY_NAME NVARCHAR(255)`, earlier: `ROLE_ATTRIBUTE.VALUE`, plus 26.3.0 group description) | Low | CrateDB has no NVARCHAR. Map to VARCHAR |
| Remap TINYINT → SMALLINT | 4 `TINYINT` column declarations | Low | CrateDB supports SMALLINT. Direct rename |

**Confidence: HIGH** (counted directly from source)

Key insight: Keycloak does NOT use `bytea`, `uuid`, or `jsonb` PostgreSQL types in its DDL. IDs are `VARCHAR(36)` strings. All large text storage uses Liquibase's cross-database `CLOB`/`NCLOB` type names (which Liquibase maps per-database; the proxy must intercept before that mapping is used).

---

### 4. modifyDataType (ALTER COLUMN TYPE) Interception

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Strip or no-op ALTER TABLE ... ALTER COLUMN ... TYPE | 56 `modifyDataType` operations across changelogs | Medium | Liquibase on PostgreSQL generates `ALTER TABLE t ALTER COLUMN c TYPE newtype`. CrateDB does not support ALTER COLUMN type changes. All 56 are VARCHAR widening (e.g., VARCHAR(36) → VARCHAR(255)) or NVARCHAR conversions. Since CrateDB will accept any VARCHAR length at insert time, these can be safely no-opped. |

**Confidence: HIGH**

The 56 modifyDataType operations are all benign type widenings — VARCHAR to wider VARCHAR, or adding NVARCHAR. None change semantics. Safe to swallow.

---

### 5. SELECT FOR UPDATE Interception

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Rewrite or strip `FOR UPDATE` clause | Keycloak's Liquibase lock service always issues `SELECT id FROM DATABASECHANGELOGLOCK WHERE id=N FOR UPDATE` before running any changelog. Runtime: ~31 sites across JPA providers also use `LockModeType.PESSIMISTIC_WRITE` | High | CrateDB has no `SELECT FOR UPDATE` support. The proxy must strip the `FOR UPDATE` suffix before forwarding. Without transactions this lock is meaningless anyway, but the SQL must not be rejected. |

**Confidence: HIGH** (confirmed from `CustomLockDatabaseChangeLogGenerator.java` source)

This is the second hard failure after ROLLBACK. It happens before the first changeset runs. Without stripping `FOR UPDATE`, Keycloak never acquires its Liquibase lock and migration never begins.

---

### 6. DATABASECHANGELOGLOCK Table Pre-initialization

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Allow Liquibase lock table creation | Keycloak's `CustomLockService` creates `DATABASECHANGELOGLOCK` table, then inserts lock rows, then issues `SELECT FOR UPDATE` | Low | The CREATE TABLE itself is standard DDL. The INSERT uses standard SQL. Only the SELECT FOR UPDATE is problematic (see section 5). |

**Confidence: HIGH**

---

## Differentiators

Features needed for Keycloak functionality beyond startup — specifically, for realm creation and user management to work after migration completes.

### 7. Unique Constraint Stripping

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Strip ADD UNIQUE CONSTRAINT | 63 unique constraint additions across all changelogs. Recent example: 26.2.6 adds `UK_MIGRATION_VERSION` and `UK_MIGRATION_UPDATE_TIME` on `MIGRATION_MODEL` | Medium | CrateDB has no UNIQUE constraint support (only PRIMARY KEY guarantees uniqueness). These must be silently dropped. This is a data integrity tradeoff acceptable for a PoC. |
| Strip DROP UNIQUE CONSTRAINT | Several drop operations across changelogs | Low | Safe to no-op if we never created the constraint |

**Confidence: HIGH** (counted directly: 63 addUniqueConstraint, 12+ dropUniqueConstraint)

---

### 8. NOT NULL Constraint Operations

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Strip ADD NOT NULL CONSTRAINT | 25 `addNotNullConstraint` operations across changelogs; Liquibase generates `ALTER TABLE t ALTER COLUMN c SET NOT NULL` | Medium | CrateDB does not support ALTER COLUMN. Safe to no-op for PoC. |
| Strip DROP NOT NULL CONSTRAINT | 9 `dropNotNullConstraint` operations; 26.5.0 uses this extensively for IDENTITY_PROVIDER nullable booleans | Low | Liquibase generates `ALTER TABLE t ALTER COLUMN c DROP NOT NULL`. Safe to no-op. |

**Confidence: HIGH**

---

### 9. Partial Index Support (PostgreSQL-specific WHERE clause)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Handle or strip WHERE clause on CREATE INDEX | 26.5.0 adds two partial indexes on `OFFLINE_CLIENT_SESSION` with `WHERE CLIENT_ID != 'external'` and `WHERE CLIENT_STORAGE_PROVIDER != 'internal'`. Liquibase `modifySql dbms="postgresql"` appends the WHERE clause | High | CrateDB does not support partial (filtered) indexes. The proxy must strip the `WHERE ...` suffix from the CREATE INDEX statement. Without this the index creation will fail and the changeset will error. |
| Handle PostgreSQL cast expressions in index definitions | 14.0.0, 20.0.0, 24.0.0 use `value::varchar(250)` cast expression in index definitions (via `modifySql dbms="postgresql"`) | High | CrateDB does not support PostgreSQL `::` cast operator in index expressions. These must be simplified to a plain column reference or the index must be dropped entirely. |

**Confidence: HIGH** (confirmed from changelog source + CrateDB ALTER TABLE docs)

The `::` cast syntax appearing in index definitions is a PostgreSQL-ism. Since CrateDB reports itself as PostgreSQL via pgjdbc, Liquibase's `<modifySql dbms="postgresql">` blocks WILL be applied. The proxy must intercept the resulting SQL.

---

### 10. Partial Index and Expression Index Fallback

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Rewrite `CREATE INDEX ... (value::varchar(250))` to plain column | CLIENT_ATTRIBUTES, GROUP_ATTRIBUTE, USER_ATTRIBUTE indexes use expression casts | Medium | Simplest approach: strip everything inside parens that contains `::`. This loses some index efficiency but allows the statement to succeed. |

**Confidence: HIGH**

---

### 11. OPTIMISTIC LockMode Handling (@Version columns)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Allow UPDATE ... WHERE version=x patterns | Hibernate generates `UPDATE entity SET ..., version=v+1 WHERE id=? AND version=v` for `@Version`-annotated entities. CredentialEntity (added 26.2.0), PersistentUserSessionEntity, PersistentClientSessionEntity, ServerConfigEntity use @Version | Medium | CrateDB supports UPDATE with WHERE clause including version checks. This works. No proxy intervention needed, but the proxy must NOT be swallowing these WHERE clauses. |

**Confidence: MEDIUM** (CrateDB UPDATE support confirmed; @Version behavior extrapolated from Hibernate spec)

---

### 12. Runtime JPQL DELETE with Subqueries

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Allow DELETE ... WHERE id IN (SELECT ...) | Session cleanup queries like `deleteExpiredClientSessions` use a DELETE with a subquery: `DELETE FROM OFFLINE_CLIENT_SESSION WHERE USER_SESSION_ID IN (SELECT userSessionId FROM OFFLINE_USER_SESSION WHERE ...)` | Medium | CrateDB supports DELETE with WHERE clauses including subqueries as of recent versions. Verify at test time. |

**Confidence: MEDIUM** (CrateDB DELETE support confirmed; subquery support not explicitly verified)

---

### 13. Custom Java Changeset Classes

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Allow custom Java Liquibase changesets to execute | Several changelogs use `<customChange class="org.keycloak.connections.jpa.updater.liquibase.custom.JpaUpdate*"/>` which run Java code that issues raw JDBC queries | High | The proxy intercepts at JDBC level so the SQL these classes emit goes through the proxy. Key ones: `JpaUpdate26_5_0_OrgTablesSetDefaultCharsetAndCollation` (MySQL/MariaDB only, won't run), `JpaUpdate25_0_0_ConsentConstraints` (PostgreSQL only, will run — issues constraint manipulation SQL that will need stripping), `FederatedUserAttributeTextColumnMigration` (runs standard INSERT/UPDATE/SELECT which should work) |

**Confidence: MEDIUM** (source reviewed; SQL content of Java custom classes not fully inspected)

---

## ID Generation

Keycloak uses an **assigned** (pre-generated application-level UUID) strategy for all entity IDs. There are NO database sequences. NO `serial` columns. NO `nextval()` calls.

Every `ID` column in the schema is `VARCHAR(36)`. UUIDs are generated in Java (`java.util.UUID.randomUUID().toString()`) before the INSERT. The database never needs to generate an ID.

This means the proxy does NOT need to handle sequences at all.

**Confidence: HIGH** (confirmed from RealmEntity, UserEntity, CredentialEntity JPA entity source — no @GeneratedValue annotations; confirmed from GitHub discussion #26578)

Note: Existing research.md stated sequences were used — this is INCORRECT. Keycloak does not use database sequences. The schema has no CREATE SEQUENCE statements in any of the 74 changelogs.

---

## Transaction Patterns

| Pattern | What Keycloak Does | Proxy Action |
|---------|-------------------|--------------|
| BEGIN | Emitted by Hibernate connection pool and by Liquibase before every changeset | Swallow silently |
| COMMIT | Emitted after every changeset and after every Hibernate flush | Swallow silently |
| ROLLBACK | Emitted by Liquibase on changeset failure; Keycloak's lock service emits in `releaseLock()` and `acquireLock()` error paths | Swallow silently |
| SET TRANSACTION ISOLATION LEVEL | Emitted by Hibernate at connection startup | Swallow silently |
| SAVEPOINT / RELEASE SAVEPOINT | Not observed in Keycloak JPA code; Hibernate may emit for nested transactions but not for PostgreSQL by default | Swallow if seen |
| SELECT ... FOR UPDATE | Keycloak Liquibase lock service: `SELECT id FROM DATABASECHANGELOGLOCK WHERE id=N FOR UPDATE`. Runtime: ~31 JPA sites using PESSIMISTIC_WRITE | Strip `FOR UPDATE` suffix |

**Confidence: HIGH** (lock service source confirmed; PESSIMISTIC_WRITE sites counted from grep)

---

## Data Type Inventory

Complete mapping of all non-standard types Keycloak's Liquibase uses (Liquibase abstract type → what the proxy should emit to CrateDB):

| Liquibase/DDL Type | Used In | CrateDB Equivalent | Action |
|--------------------|---------|-------------------|--------|
| `VARCHAR(n)` | ~770 columns, dominant type (IDs, names, values) | `TEXT` | No change needed — CrateDB accepts VARCHAR |
| `BOOLEAN` | ~130 columns (flags, enabled states) | `BOOLEAN` | No change needed |
| `INT` | ~100 columns (timestamps as epoch seconds, iteration counts) | `INTEGER` | No change needed |
| `BIGINT` | ~32 columns (time values, event times) | `BIGINT` | No change needed |
| `CLOB` | 20 columns: SECRET_DATA, CREDENTIAL_DATA, POLICY_CONFIG.VALUE, OFFLINE sessions DATA, REALM_LOCALIZATIONS.TEXTS, SERVER_CONFIG.VALUE | `TEXT` | Remap in CREATE TABLE and ADD COLUMN statements |
| `NCLOB` | 9 columns: COMPONENT_CONFIG.VALUE, EVENT_ENTITY.DETAILS_JSON_LONG_VALUE, CLIENT_ATTRIBUTES.VALUE, USER_ATTRIBUTE.LONG_VALUE, FED_USER_ATTRIBUTE.LONG_VALUE | `TEXT` | Remap in CREATE TABLE and ADD COLUMN statements |
| `BINARY(64)` | 4 columns: USER_ATTRIBUTE and FED_USER_ATTRIBUTE hash columns (SHA-256 of long attribute values) | `TEXT` or drop index | Remap to TEXT (store hex) or drop column — it exists only for index optimization of long values |
| `TINYBLOB(16)` | 3 columns: deprecated CREDENTIAL.SALT (v1.0 only) | `TEXT` | Remap; column is deprecated since v8.0 but must exist for schema creation |
| `NVARCHAR(n)` | 4 columns: ROLE_ATTRIBUTE.VALUE (v4.6), KEYCLOAK_GROUP.DESCRIPTION (26.3.0), REALM.DISPLAY_NAME (26.7.0), plus 2.5.0 oracle-targeted migrations | `VARCHAR(n)` | Strip the N prefix |
| `TINYINT` | 4 columns | `SMALLINT` | Rename |
| `TEXT` | 3 columns (older changesets) | `TEXT` | No change needed |
| `TEXT(25500)` | 1 column | `TEXT` | Strip length parameter |
| `bytea` | Not used in Keycloak DDL | — | No action needed |
| `uuid` | Not used in Keycloak DDL (VARCHAR(36) used instead) | — | No action needed |
| `jsonb` / `json` | Not used in Keycloak DDL | — | No action needed |

**Confidence: HIGH** — type inventory compiled directly from grep across all 74 changelogs.

---

## Anti-Features

Things the proxy cannot make work. Shipping these would require fundamental changes to Keycloak or CrateDB.

### Anti-Feature 1: True ACID Transactions

**What goes wrong:** CrateDB has no transactions. Individual statements auto-commit. If a Keycloak write sequence partially succeeds and then fails, there is no rollback. Data will be partially written.

**Why avoid:** Implementing transaction simulation in the proxy requires tracking all writes, buffering them, and applying on COMMIT or discarding on ROLLBACK. This is complex, error-prone, and defeats the PoC purpose of simplicity.

**What to do instead:** Accept auto-commit semantics. For the PoC this means corruption is possible but unlikely during normal operation. The PROJECT.md already documents this decision.

---

### Anti-Feature 2: Referential Integrity

**What goes wrong:** CrateDB has no FK enforcement. Orphaned records will accumulate over time. Keycloak's DELETE operations assume FK CASCADE will clean up child rows.

**Why avoid:** Implementing FK constraint tracking in the proxy requires maintaining an in-memory FK map and enforcing it on every DML. Complexity is equivalent to writing a mini-database engine.

**What to do instead:** Strip FK declarations silently. Accept that orphaned rows may appear. The PROJECT.md already documents this decision.

---

### Anti-Feature 3: Unique Constraint Enforcement

**What goes wrong:** CrateDB only enforces uniqueness via PRIMARY KEY. The 63 unique constraints in Keycloak's schema (e.g., unique email per realm, unique role name per realm) will not be enforced. Duplicate data can be inserted.

**Why avoid:** Enforcing uniqueness in the proxy requires a lookup before every INSERT. This adds latency, is not atomic (race conditions under concurrency), and is beyond PoC scope.

**What to do instead:** Strip UNIQUE constraint DDL silently. Accept that uniqueness is unenforced. For the PoC this is acceptable.

---

### Anti-Feature 4: ALTER COLUMN Type Changes at Runtime

**What goes wrong:** Even after stripping `modifyDataType` from Liquibase migrations, any future Keycloak upgrade that requires modifying a column type (from e.g. VARCHAR(36) to VARCHAR(255)) will silently do nothing. Queries inserting longer values may fail.

**Why avoid:** The proxy cannot retroactively change column types on existing CrateDB tables without `ALTER TABLE ... ADD COLUMN` + data migration + rename, which is high-risk.

**What to do instead:** Strip all `modifyDataType` operations. In practice the 56 existing type widening operations are all safe to no-op because CrateDB will accept VARCHAR values of any length regardless of the declared column width.

---

### Anti-Feature 5: Keycloak Upgrade Path

**What goes wrong:** Running a Keycloak upgrade (e.g., 26.x to 27.x) against CrateDB will re-run all the above interceptions for the new changelogs. New changelogs may introduce SQL patterns the proxy does not handle yet.

**Why avoid:** The proxy is built against a specific Keycloak version's changelog. New versions introduce new SQL patterns that may require proxy updates.

**What to do instead:** Pin the Keycloak version for the PoC. Document that upgrades require proxy re-verification.

---

## Phase-Specific Warnings for Roadmap

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Proxy startup (JDBC connection) | Liquibase issues `SELECT FOR UPDATE` before any changelog runs — first SQL that will fail | Strip `FOR UPDATE` must be the first interception implemented and tested |
| DATABASECHANGELOGLOCK creation | `CREATE TABLE DATABASECHANGELOGLOCK` uses no FK, no CLOB — should work cleanly | Low risk |
| First changeset (v1.0.0.Final) | Creates ~23 tables with FK constraints and `TINYBLOB(16)`. First FK add will fail if not intercepted | Must implement FK stripping before running any changesets |
| Type remapping | CLOB/NCLOB appear starting at v1.2.0.CR1 (DB2 only) and v8.0.0 (all). BINARY(64) at v24.0.0 | Implement full type map before attempting full migration |
| Index creation with casts | `value::varchar(250)` expressions in CREATE INDEX appear at v14.0.0, v20.0.0, v24.0.0 | Strip `::` cast expressions from index definitions |
| Partial indexes with WHERE | Appear in 26.5.0 for OFFLINE_CLIENT_SESSION | Strip `WHERE ...` suffix from CREATE INDEX |
| Liquibase checksum verification | Liquibase checks checksums of changesets on re-run; any SQL rewriting may invalidate checksums stored in DATABASECHANGELOG | The proxy rewrites SQL after Liquibase checksums it — Liquibase computes checksums on the XML changeset, not the final SQL, so this should NOT be a problem |
| Runtime user creation | Hibernate issues `INSERT INTO USER_ENTITY ...` — no FK, no sequence, should work | Low risk |
| Runtime authentication | `SELECT ... FOR UPDATE` on consent entities via PESSIMISTIC_WRITE lock mode | Must be stripped at runtime, not just during migration |
| Custom Java changesets (JpaUpdate25_0_0_ConsentConstraints) | This runs only on PostgreSQL (preCondition). It issues constraint manipulation SQL that the proxy must intercept | Needs testing; may issue ALTER TABLE ADD/DROP CONSTRAINT statements |

---

## Sources

- Local Keycloak source: `/home/pawel/repo/crate/keycloak/model/jpa/src/main/resources/META-INF/` (all 74 changelogs, directly grepped)
- Local Keycloak JPA entities: `CustomLockDatabaseChangeLogGenerator.java`, `CredentialEntity.java`, `UserEntity.java`, `RealmEntity.java`, `JpaUserFederatedStorageProvider.java`
- Existing project research: `/home/pawel/repo/crate/research.md`
- CrateDB community forum: [Using Crate JDBC in Dockerfile for Keycloak](https://community.cratedb.com/t/using-crate-jdbc-in-dockerfile-for-keycloak/1524)
- CrateDB SQL compatibility: https://cratedb.com/docs/crate/reference/en/latest/appendices/compatibility.html
- CrateDB transaction docs: https://cratedb.com/docs/crate/reference/en/latest/sql/statements/begin.html
- CrateDB ALTER TABLE docs: https://cratedb.com/docs/crate/reference/en/5.6/sql/statements/alter-table.html
- CrateDB INSERT (ON CONFLICT): https://cratedb.com/docs/crate/reference/en/latest/sql/statements/insert.html
- Keycloak UUID discussion: https://github.com/keycloak/keycloak/discussions/26578
- CrateDB unique constraint status: https://community.cratedb.com/t/unique-constraint/673
- CrateDB pgjdbc vs crate-jdbc: https://community.cratedb.com/t/connecting-to-cratedb-with-crate-jdbc-and-pgjdbc/1851
