# Requirements: Keycloak on CrateDB

**Defined:** 2026-04-22
**Core Value:** Keycloak runs with full functionality on a 3-node CrateDB cluster via a custom JDBC proxy JAR, using only official Docker images

## v1 Requirements

### Infrastructure

- [x] **INFRA-01**: 3-node CrateDB cluster forms successfully in Docker Compose (all nodes joined, cluster health green)
- [x] **INFRA-02**: Docker Compose setup is self-contained — single `docker compose up` starts the full stack
- [x] **INFRA-03**: JDBC proxy JAR is built automatically as part of Docker Compose startup (Maven builder service)
- [x] **INFRA-04**: Keycloak connects to CrateDB via the custom JDBC proxy driver (no ClassNotFoundException)

### Proxy Core

- [ ] **PRXY-01**: Custom JDBC driver wraps pgJDBC and registers via META-INF/services SPI
- [ ] **PRXY-02**: All 4 JDBC execution paths intercepted (Statement.execute, executeQuery, executeUpdate, PreparedStatement)
- [ ] **PRXY-03**: BEGIN / COMMIT / ROLLBACK swallowed silently (CrateDB rejects them at parse level)
- [ ] **PRXY-04**: SELECT FOR UPDATE stripped from all queries (Liquibase lock service issues this as the very first SQL)
- [ ] **PRXY-05**: FOREIGN KEY constraints stripped from CREATE TABLE DDL (195 FK additions in Keycloak schema)
- [ ] **PRXY-06**: UNIQUE constraints stripped from DDL (63 unique constraint additions)
- [ ] **PRXY-07**: Unsupported types remapped — CLOB→TEXT, NCLOB→TEXT, BINARY(64)→BLOB, TINYBLOB→BLOB, NVARCHAR→VARCHAR, TINYINT→SMALLINT
- [ ] **PRXY-08**: ALTER TABLE / ALTER COLUMN unsupported operations stripped (56 column type changes, 34 NOT NULL additions)
- [ ] **PRXY-09**: PostgreSQL cast expressions (::varchar) stripped from CREATE INDEX DDL
- [ ] **PRXY-10**: Partial index WHERE clauses stripped from CREATE INDEX DDL (Keycloak v26.5+ changesets)
- [ ] **PRXY-11**: CREATE TABLE statements rewritten to include `WITH (number_of_replicas = '1')` for cluster replication

### Schema Migration

- [ ] **MIGR-01**: Keycloak Liquibase schema migration runs to completion against CrateDB (all 74 changelogs applied)
- [ ] **MIGR-02**: Post-migration: refresh_interval set to 0 on Keycloak session and user tables for consistent reads

### Functional Validation

- [ ] **FUNC-01**: Keycloak admin UI loads and is accessible
- [ ] **FUNC-02**: Admin can create a realm via Keycloak admin UI
- [ ] **FUNC-03**: Admin can create a user within a realm
- [ ] **FUNC-04**: User can authenticate (login flow completes successfully)
- [ ] **FUNC-05**: Session persists across requests (authenticated user can access protected resource)

### Cluster Resilience

- [ ] **CLST-01**: CrateDB cluster remains operational and Keycloak stays functional when one of three nodes is stopped

## v2 Requirements

### Observability

- **OBSV-01**: Proxy logs every SQL rewrite (original vs rewritten) for debugging
- **OBSV-02**: CrateDB admin UI (port 4200) accessible from host for cluster inspection

### Production Hardening

- **PROD-01**: TLS between Keycloak and CrateDB
- **PROD-02**: Keycloak HA mode (multiple Keycloak instances)
- **PROD-03**: Secrets management (no plaintext passwords in compose file)
- **PROD-04**: Persistent CrateDB data volumes survive compose restart

## Out of Scope

| Feature | Reason |
|---------|--------|
| Modifying Keycloak source code | Hard constraint — official image only |
| Modifying CrateDB source code | Hard constraint — official image only |
| In-memory FK tracking by proxy | Too complex for PoC; no referential integrity needed |
| Transaction simulation / rollback | Too complex; auto-commit acceptable |
| Referential integrity guarantees | CrateDB has none; accepted limitation |
| Wire protocol proxy (Option B) | Chose JDBC proxy approach (Option A) |
| Production TLS / secrets | PoC scope only |
| pg_catalog stub queries | Monitor empirically during implementation; add only if Keycloak fails on them |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 | Phase 1 | Complete |
| INFRA-02 | Phase 1 | Complete |
| INFRA-03 | Phase 1 | Complete |
| INFRA-04 | Phase 1 | Complete |
| PRXY-01 | Phase 2 | Pending |
| PRXY-02 | Phase 2 | Pending |
| PRXY-03 | Phase 2 | Pending |
| PRXY-04 | Phase 2 | Pending |
| PRXY-05 | Phase 2 | Pending |
| PRXY-06 | Phase 2 | Pending |
| PRXY-07 | Phase 2 | Pending |
| PRXY-08 | Phase 2 | Pending |
| PRXY-09 | Phase 2 | Pending |
| PRXY-10 | Phase 2 | Pending |
| PRXY-11 | Phase 2 | Pending |
| MIGR-01 | Phase 3 | Pending |
| MIGR-02 | Phase 3 | Pending |
| FUNC-01 | Phase 3 | Pending |
| FUNC-02 | Phase 4 | Pending |
| FUNC-03 | Phase 4 | Pending |
| FUNC-04 | Phase 4 | Pending |
| FUNC-05 | Phase 4 | Pending |
| CLST-01 | Phase 4 | Pending |

**Coverage:**
- v1 requirements: 23 total
- Mapped to phases: 23
- Unmapped: 0 ✓

---
*Requirements defined: 2026-04-22*
*Last updated: 2026-04-22 after initial definition*
