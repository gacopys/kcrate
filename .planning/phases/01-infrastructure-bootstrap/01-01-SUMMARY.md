---
phase: 01-infrastructure-bootstrap
plan: "01"
subsystem: proxy-driver, orchestration
tags: [java, maven, docker-compose, jdbc, cratedb, keycloak]
dependency_graph:
  requires: []
  provides:
    - proxy/pom.xml (Maven fat JAR build definition)
    - proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java (passthrough JDBC driver stub)
    - proxy/src/main/resources/META-INF/services/java.sql.Driver (SPI registration)
    - docker-compose.yml (5-service stack orchestration)
  affects:
    - Phase 2 proxy implementation (builds on this stub driver)
    - Phase 3 schema migration (depends on compose stack)
tech_stack:
  added:
    - Java 21 (Maven compiler target)
    - Maven 3.9 + maven-shade-plugin 3.6.2 (fat JAR build)
    - pgJDBC 42.7.4 (real JDBC driver delegated to)
    - JSQLParser 5.3 (included in fat JAR for Phase 2)
    - CrateDB 6.2 (3-node cluster)
    - Keycloak latest (quay.io/keycloak/keycloak:latest)
  patterns:
    - JDBC Driver SPI registration via META-INF/services
    - maven-shade-plugin ServicesResourceTransformer for SPI merging
    - One-shot builder service pattern (proxy-builder exits after JAR copy)
    - Named volume JAR injection into Keycloak providers directory
    - depends_on with service_completed_successfully and service_healthy conditions
key_files:
  created:
    - proxy/pom.xml
    - proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java
    - proxy/src/main/resources/META-INF/services/java.sql.Driver
    - docker-compose.yml
  modified: []
decisions:
  - id: D-01
    summary: "Phase 1 proxy is pure passthrough — no SQL rewriting; rewriting starts in Phase 2"
  - id: D-02
    summary: "Keycloak expected to crash on BEGIN in Phase 1; acceptable for infrastructure validation"
  - id: D-03
    summary: "Database name 'doc' used in JDBC URL (CrateDB default schema) rather than 'keycloak'"
  - id: D-04
    summary: "Keycloak runs in start-dev mode to avoid TLS/cert requirements for PoC"
  - id: D-05
    summary: "JAR delivery via named volume mount, not custom Keycloak image COPY"
metrics:
  duration_minutes: 5
  completed_date: "2026-04-22"
  tasks_completed: 2
  tasks_total: 2
  files_created: 4
  files_modified: 0
---

# Phase 1 Plan 1: Infrastructure Bootstrap — Maven Proxy Stub + Docker Compose Summary

**One-liner:** Passthrough JDBC proxy stub (Java 21, Maven fat JAR) and 5-service Docker Compose stack wiring CrateDB 3-node cluster to Keycloak via named volume JAR injection.

## What Was Built

### Task 1: Maven Proxy Stub Project

Created the Maven project for the JDBC proxy driver:

- **proxy/pom.xml** — Fat JAR build with pgJDBC 42.7.4, JSQLParser 5.3, and maven-shade-plugin 3.6.2 with ServicesResourceTransformer ensuring both the proxy driver and pgJDBC's own SPI entries are merged into the fat JAR.
- **proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java** — Phase 1 passthrough stub implementing `java.sql.Driver`. All JDBC calls delegate directly to `org.postgresql.Driver`. No SQL rewriting (D-01 compliant). Registers itself via `DriverManager.registerDriver()` in a static initializer.
- **proxy/src/main/resources/META-INF/services/java.sql.Driver** — SPI registration file containing exactly `com.example.crateproxy.CrateProxyDriver`.

### Task 2: Docker Compose Orchestration

Created **docker-compose.yml** at repo root with five services:

| Service | Image | Role |
|---------|-------|------|
| proxy-builder | maven:3.9-eclipse-temurin-21 | One-shot builder: compiles fat JAR, copies to proxy-jar volume, exits |
| cratedb1 | crate:6.2 | CrateDB node 1 (exposes ports 4200, 5432 for debugging) |
| cratedb2 | crate:6.2 | CrateDB node 2 |
| cratedb3 | crate:6.2 | CrateDB node 3 |
| keycloak | quay.io/keycloak/keycloak:latest | Keycloak in start-dev mode |

Key configuration decisions:
- CrateDB healthchecks use `/_sql` with `SELECT count(*) FROM sys.nodes` (NOT `/_cluster/health` which returns 404 in CrateDB 6.2.6)
- All CrateDB nodes use `network.host=_site_` for Docker network binding
- Keycloak `depends_on` proxy-builder with `service_completed_successfully` AND cratedb1 with `service_healthy`
- `proxy-jar` named volume mounted at `/opt/keycloak/providers` — the only directory Keycloak scans for SPI JARs
- `KC_DB_DRIVER: com.example.crateproxy.CrateProxyDriver` matches SPI file exactly
- `maven-cache` named volume caches Maven dependencies across builds

## Verification Results

All automated checks passed:
- `docker compose config --quiet` exits 0 (YAML syntactically valid)
- SPI file contains exactly `com.example.crateproxy.CrateProxyDriver`
- `KC_DB_DRIVER` in compose matches SPI file class name exactly
- `ServicesResourceTransformer` present in pom.xml
- pgJDBC 42.7.4, JSQLParser 5.3, maven-shade-plugin 3.6.2 versions confirmed
- No SQL rewriting code in CrateProxyDriver.java (D-01 compliant)

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

- **CrateProxyDriver.java (entire file)** — This is an intentional passthrough stub. All JDBC calls delegate directly to pgJDBC with no SQL rewriting. Phase 2 will replace this with full SQL rewriting layers (transaction swallowing, DDL transformation, type remapping). This stub is intentional per D-01.

## Threat Flags

No new threat surface beyond what was modeled in the plan's threat register.

## Self-Check: PASSED

- proxy/pom.xml: FOUND
- proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java: FOUND
- proxy/src/main/resources/META-INF/services/java.sql.Driver: FOUND
- docker-compose.yml: FOUND
- docker compose config --quiet: exit 0
