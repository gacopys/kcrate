# Keycloak on CrateDB

## What This Is

A Docker Compose proof-of-concept that runs Keycloak backed by a 3-node CrateDB cluster. Because CrateDB is not natively supported by Keycloak, this project includes a custom JDBC proxy driver (Java) that intercepts Keycloak's SQL traffic and rewrites it into CrateDB-compatible statements — swallowing transaction commands and stripping unsupported DDL — before forwarding to CrateDB.

## Core Value

Keycloak runs with full functionality on a 3-node CrateDB cluster, with the proxy JAR injected via volume mount into the official Keycloak image (no code changes to either Keycloak or CrateDB).

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] 3-node CrateDB cluster running in Docker Compose with master-master replication
- [ ] Custom JDBC proxy driver JAR that rewrites Keycloak's SQL for CrateDB compatibility
- [ ] JDBC proxy swallows BEGIN/COMMIT/ROLLBACK silently (all ops auto-commit)
- [ ] JDBC proxy strips FOREIGN KEY constraints from DDL before forwarding
- [ ] JDBC proxy remaps unsupported PostgreSQL types (bytea, uuid, jsonb) to CrateDB equivalents
- [ ] JDBC proxy handles sequence-based ID generation (rewrite or alternative strategy)
- [ ] JDBC proxy handles unsupported ALTER TABLE / ALTER COLUMN operations
- [ ] Keycloak uses official Docker image with JAR injected via volume mount
- [ ] Keycloak schema migration (Liquibase) completes successfully against CrateDB
- [ ] Full Keycloak functionality: realm creation, user management, authentication

### Out of Scope

- Modifying Keycloak source code — constraint: official image only
- Modifying CrateDB source code — constraint: official image only
- Production hardening (TLS, secrets management, HA Keycloak) — PoC only
- In-memory FK tracking by the proxy — too complex, no referential integrity needed for PoC
- Transaction simulation with rollback — too complex, auto-commit acceptable for PoC

## Context

- Research doc at `research.md` covers the compatibility gaps between Keycloak and CrateDB in detail
- CrateDB exposes the PostgreSQL wire protocol on port 5432; Keycloak's PostgreSQL JDBC driver connects but fails on unsupported SQL
- Keycloak (Quarkus-based, v17+) allows custom JDBC driver JARs via `/opt/keycloak/providers/` volume mount and `KC_DB_DRIVER` env var
- CrateDB clustering is shared-nothing; all nodes accept reads and writes (true multi-master)
- Keycloak's schema is managed by Liquibase and generates ~100 tables with heavy FK and transaction usage
- Java version must match Keycloak's JVM (currently Java 21 in keycloak:latest)

## Constraints

- **Compatibility**: No changes to Keycloak or CrateDB code — official Docker images only
- **Language**: JDBC proxy must be Java (JDBC is a Java API), targeting Java 21 to match Keycloak
- **Scope**: Proof of concept — correctness over production-readiness
- **SQL rewriting**: Proxy must handle all Keycloak/Liquibase SQL patterns; unknown gaps will surface during testing

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| JDBC proxy JAR over wire protocol proxy | Less code, no need to implement PostgreSQL wire protocol, intercepted at statement level | — Pending |
| Swallow transactions silently | Simplest viable approach for PoC; auto-commit acceptable, no rollback safety needed | — Pending |
| Strip FK constraints | CrateDB has no FK implementation; application-level integrity is out of scope for PoC | — Pending |
| Volume-mount JAR into official Keycloak image | Avoids building a custom Keycloak image; uses supported provider extension point | — Pending |
| 3-node CrateDB cluster | Validates the replication aspect of the PoC; CrateDB's native shared-nothing clustering | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-22 after initialization*
