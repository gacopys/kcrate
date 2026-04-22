# Keycloak + CrateDB via PostgreSQL Wire Protocol — Compatibility Research

## Goal

Run Keycloak and CrateDB together using official Docker images in Docker Compose, pointing Keycloak at CrateDB as its database backend (CrateDB exposes the PostgreSQL wire protocol, so Keycloak would treat it as a PostgreSQL database).

**Constraint**: No changes to Keycloak or CrateDB source code. Official images only.

---

## How Keycloak Connects to PostgreSQL

Keycloak uses **Liquibase** for schema migrations and **Hibernate/JPA** for runtime data access. When configured for PostgreSQL:

- JDBC URL: `jdbc:postgresql://<host>:<port>/<dbname>`
- Driver: `org.postgresql.Driver` (bundled in the Keycloak image)
- Schema is created/migrated on startup via Liquibase changesets
- Runtime operations use Hibernate with the PostgreSQL dialect
- All multi-step operations rely on standard ACID transactions (BEGIN / COMMIT / ROLLBACK)

Relevant environment variables in the official Keycloak image:
```
KC_DB=postgres
KC_DB_URL=jdbc:postgresql://...
KC_DB_USERNAME=...
KC_DB_PASSWORD=...
```

---

## CrateDB PostgreSQL Compatibility

CrateDB supports the **PostgreSQL wire protocol v3**, which means clients that speak Postgres (psql, JDBC driver, etc.) can connect. However, protocol compatibility is not the same as SQL compatibility.

### What CrateDB supports
- Basic DDL: `CREATE TABLE`, `DROP TABLE`, basic `ALTER TABLE` (add/drop columns, rename)
- Standard DML: `SELECT`, `INSERT`, `UPDATE`, `DELETE`
- `PRIMARY KEY`, `NOT NULL`, `CHECK` constraints
- `information_schema` and `pg_catalog` introspection schemas
- PostgreSQL JDBC driver connections (with caveats)
- Data types: `VARCHAR(n)`, `TEXT`, `INTEGER`, `BIGINT`, `BOOLEAN`, `DOUBLE PRECISION`, `TIMESTAMP`, `ARRAY`, `OBJECT`

### Critical limitations

| Feature | CrateDB Status | Keycloak Dependency |
|---|---|---|
| **Transactions** (BEGIN / COMMIT / ROLLBACK) | Not supported — statements auto-commit | **CRITICAL** — all schema migrations and runtime writes |
| **Foreign key constraints** | Not supported | **CRITICAL** — Keycloak schema uses FK heavily |
| **Sequences** (`CREATE SEQUENCE`) | Not supported | **HIGH** — used for ID generation |
| **Unique constraints** | Not supported (only PK uniqueness) | **HIGH** — data integrity |
| **`ALTER COLUMN`** (change type/nullability) | Not supported | **HIGH** — Liquibase migrations |
| **`bytea`** type | Not supported | **MEDIUM** — binary blob storage |
| **`UUID`** type | Not natively supported | **MEDIUM** — ID columns |
| **`jsonb` / `json`** type | Uses `OBJECT` type instead | **MEDIUM** — attribute storage |
| **Stored procedures / functions** | Not supported | Low impact |

Sources:
- https://cratedb.com/docs/crate/reference/en/latest/appendices/compatibility.html
- https://cratedb.com/docs/crate/reference/en/latest/interfaces/postgres.html
- https://cratedb.com/docs/crate/reference/en/latest/sql/general/constraints.html

---

## What Happens When You Try It

Someone in the CrateDB community forum attempted exactly this — running Keycloak against CrateDB using a custom image with the CrateDB JDBC driver. It failed at startup with:

1. **`PSQLException: ERROR: line 1:1: mismatched input 'ROLLBACK'`**  
   Liquibase wraps every changeset in a transaction. CrateDB does not support ROLLBACK and throws a parse error.

2. **`addForeignKeyConstraint` changeset failures**  
   Keycloak's Liquibase changesets include foreign key creation steps. CrateDB has no implementation for this DDL.

3. **JDBC driver loading issues**  
   Even injecting the CrateDB JDBC driver into the Keycloak image was non-trivial; Keycloak's driver discovery didn't pick it up automatically.

Workarounds like `KC_DB_TX_TYPE=disabled` partially bypass the transaction framing but don't fix the underlying SQL incompatibilities.

Source: https://community.cratedb.com/t/using-crate-jdbc-in-dockerfile-for-keycloak/1524

---

## Docker Compose Feasibility

Running both services in Docker Compose is straightforward wiring — the hard part is SQL compatibility, not networking.

A minimal compose sketch would look like:

```yaml
services:
  cratedb:
    image: crate:latest
    ports:
      - "4200:4200"   # HTTP/admin UI
      - "5432:5432"   # PostgreSQL wire protocol

  keycloak:
    image: quay.io/keycloak/keycloak:latest
    command: start-dev
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://cratedb:5432/keycloak
      KC_DB_USERNAME: crate
      KC_DB_PASSWORD: ""
    depends_on:
      - cratedb
```

CrateDB's PostgreSQL port is 5432 by default (mapped from internal 5432). The connection itself will succeed — the Keycloak image includes the `org.postgresql` JDBC driver which speaks the wire protocol CrateDB understands.

**The failure will happen immediately after connection**, when Keycloak's Liquibase migration runner issues its first `BEGIN` / `ROLLBACK` or `CREATE TABLE ... FOREIGN KEY` statement.

---

## Summary of Blockers

| Blocker | Severity | Fixable without code changes? |
|---|---|---|
| No transaction support | Critical | No |
| No foreign key constraints | Critical | No |
| No sequences | High | No |
| Missing data types (bytea, uuid, jsonb) | High | No |
| No `ALTER COLUMN` | High | No |

---

## Conclusion

**It will not work with official images and no code changes.**

The three hard blockers are architectural:

1. **Transactions** — CrateDB ignores `BEGIN`/`ROLLBACK`. Keycloak's Liquibase migrations wrap every changeset in a transaction and expect rollback on failure. This causes a SQL parse error on first migration.

2. **Foreign keys** — Keycloak's relational schema assumes FK constraints. CrateDB does not implement them at all.

3. **Sequences / serial columns** — Keycloak's ID generation strategy depends on database sequences, which CrateDB does not support.

Making this work would require forking and modifying Keycloak's database layer — specifically the Liquibase changelogs, the Hibernate dialect, and possibly the ID generation strategy. That is well outside the scope of "official images, no code changes."

**Officially supported Keycloak databases**: PostgreSQL, MySQL, MariaDB, Oracle, MSSQL. CrateDB is not on this list.
