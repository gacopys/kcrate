# Roadmap: Keycloak on CrateDB

## Overview

This project proves that Keycloak can run on a 3-node CrateDB cluster using only official Docker images. The path runs from standing up the cluster and injecting the proxy JAR, through implementing every SQL rewrite layer the proxy needs, through completing the Liquibase schema migration, to confirming full authentication workflows survive a node failure.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Infrastructure Bootstrap** - 3-node CrateDB cluster running with Keycloak wired to the custom JDBC proxy driver
- [ ] **Phase 2: JDBC Proxy Implementation** - All SQL rewrite layers implemented; proxy JAR is complete and verifiably correct
- [ ] **Phase 3: Schema Migration** - Liquibase migration runs to completion against CrateDB; Keycloak admin UI loads
- [ ] **Phase 4: Functional Validation + Cluster Resilience** - Full auth workflows pass; cluster survives one-node-down scenario

## Phase Details

### Phase 1: Infrastructure Bootstrap
**Goal**: The full Docker Compose stack starts with a healthy 3-node CrateDB cluster and Keycloak connected to it via the custom JDBC proxy driver
**Depends on**: Nothing (first phase)
**Requirements**: INFRA-01, INFRA-02, INFRA-03, INFRA-04
**Success Criteria** (what must be TRUE):
  1. `docker compose up` completes without errors and all containers reach a running state
  2. CrateDB cluster shows 3 nodes joined and cluster health green (visible via CrateDB HTTP API or logs)
  3. Keycloak container starts and connects to CrateDB through the proxy JAR with no ClassNotFoundException in logs
  4. Proxy JAR is built automatically by a Maven builder service during compose startup — no manual build step required
**Plans**: 2 plans

Plans:
- [x] 01-01-PLAN.md — Create Maven proxy stub project and docker-compose.yml
- [x] 01-02-PLAN.md — Start stack and verify Phase 1 success criteria (human checkpoint)

### Phase 2: JDBC Proxy Implementation
**Goal**: The proxy JAR intercepts all Keycloak/Liquibase SQL and rewrites it into CrateDB-compatible statements across all 9 rewrite layers
**Depends on**: Phase 1
**Requirements**: PRXY-01, PRXY-02, PRXY-03, PRXY-04, PRXY-05, PRXY-06, PRXY-07, PRXY-08, PRXY-09, PRXY-10, PRXY-11
**Success Criteria** (what must be TRUE):
  1. BEGIN / COMMIT / ROLLBACK statements are swallowed silently — CrateDB never receives them
  2. CREATE TABLE DDL reaching CrateDB contains no FOREIGN KEY or UNIQUE constraint clauses, and includes `WITH (number_of_replicas = '1')`
  3. ALTER TABLE / ALTER COLUMN statements that CrateDB cannot execute are stripped before forwarding
  4. All four JDBC execution paths (Statement.execute, executeQuery, executeUpdate, PreparedStatement) pass through the rewrite pipeline
**Plans**: TBD

### Phase 3: Schema Migration
**Goal**: Liquibase applies all 74 Keycloak changelogs against CrateDB without error, and post-migration fixups leave session/user tables in a consistent-read state
**Depends on**: Phase 2
**Requirements**: MIGR-01, MIGR-02, FUNC-01
**Success Criteria** (what must be TRUE):
  1. Keycloak logs show Liquibase migration completing all changelogs with no changelog failures
  2. All expected Keycloak tables exist in CrateDB after migration (verifiable via CrateDB SQL)
  3. `refresh_interval` is set to 0 on session and user tables (verifiable via CrateDB SQL)
  4. Keycloak admin UI loads at the expected port without a 500 error or boot-loop
**Plans**: TBD
**UI hint**: yes

### Phase 4: Functional Validation + Cluster Resilience
**Goal**: A complete Keycloak authentication workflow succeeds end-to-end, and the system remains functional when one CrateDB node is stopped
**Depends on**: Phase 3
**Requirements**: FUNC-02, FUNC-03, FUNC-04, FUNC-05, CLST-01
**Success Criteria** (what must be TRUE):
  1. Admin can create a realm and a user via the Keycloak admin UI; both persist after page reload
  2. A user can complete a login flow and the resulting session persists across requests to a protected resource
  3. After stopping one CrateDB node, Keycloak continues to authenticate users and serve the admin UI without restart
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Infrastructure Bootstrap | 1/2 | In Progress|  |
| 2. JDBC Proxy Implementation | 0/TBD | Not started | - |
| 3. Schema Migration | 0/TBD | Not started | - |
| 4. Functional Validation + Cluster Resilience | 0/TBD | Not started | - |
