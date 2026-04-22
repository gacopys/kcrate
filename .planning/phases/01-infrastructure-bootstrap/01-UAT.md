---
status: complete
phase: 01-infrastructure-bootstrap
source: [01-01-SUMMARY.md, 01-02-SUMMARY.md]
started: 2026-04-22T10:00:00Z
updated: 2026-04-22T10:05:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Kill any running containers (docker compose down -v). Start the stack from scratch with `docker compose up`. The proxy-builder service should compile the fat JAR and exit 0 (no manual build step). All five services reach a running state. No startup crash before Keycloak begins Liquibase migration.
result: pass

### 2. CrateDB 3-Node Cluster Health
expected: After `docker compose up`, querying `http://localhost:4200/_sql` with `{"stmt":"SELECT count(*) FROM sys.nodes"}` returns `{"rows":[[3]]...}` — confirming all three nodes joined the cluster.
result: pass

### 3. Keycloak Loads Proxy JAR (No ClassNotFoundException)
expected: Scanning Keycloak container logs shows no `ClassNotFoundException` or `NoClassDefFoundError` for `com.example.crateproxy.CrateProxyDriver`. Keycloak logs show it scanned `/opt/keycloak/providers` and loaded the JAR.
result: pass

### 4. Proxy JAR Auto-Built by Maven Builder
expected: The `proxy-builder` service exits 0 after a successful Maven build. Keycloak starts only after the builder exits. No manual build step required.
result: pass

### 5. Keycloak Crashes on BEGIN/ROLLBACK (Expected for Phase 1)
expected: Keycloak attempts Liquibase schema migration, hits CrateDB with transaction commands (BEGIN/ROLLBACK), CrateDB rejects them with a parse error. This is expected per D-02 — proxy is pure passthrough in Phase 1.
result: pass

## Summary

total: 5
passed: 5
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none]
