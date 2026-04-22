# Phase 1: Infrastructure Bootstrap - Context

**Gathered:** 2026-04-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Stand up the full Docker Compose stack: a healthy 3-node CrateDB cluster with Keycloak connected to it via the custom JDBC proxy driver JAR. The JAR is built automatically during compose startup — no manual Maven step required.

Phase 1 is complete when all containers reach running state, CrateDB shows 3 nodes joined, and Keycloak connects through the proxy JAR without ClassNotFoundException. SQL rewriting is NOT in scope for Phase 1.

</domain>

<decisions>
## Implementation Decisions

### Proxy Stub Scope
- **D-01:** Phase 1 proxy is a **pure passthrough stub** — the driver registers via SPI, establishes a connection, and forwards all SQL to CrateDB unchanged. No SQL rewriting at all.
- **D-02:** Keycloak crashing on its first `BEGIN` statement is **acceptable for Phase 1** — the success criterion is only "no ClassNotFoundException". SQL rewriting begins in Phase 2.

### JAR Delivery Mechanism
- **D-03:** Custom Keycloak Docker image is **permitted** — `FROM quay.io/keycloak/keycloak:latest` with the proxy JAR added. This is not a fork and involves no Keycloak source changes.
- **D-04:** Hard constraint: **no Keycloak source code changes, no Keycloak fork**. The image customization is limited to copying the pre-built proxy JAR into `/opt/keycloak/providers/`.
- **D-05:** Choice between volume mount (simpler, documented in CLAUDE.md) and custom image COPY is **Claude's discretion** — planner picks the cleaner approach for the PoC.

### Claude's Discretion
- **Source directory layout** — Where proxy source lives (`proxy/`, `jdbc-proxy/`, etc.) is left to the planner.
- **Keycloak start mode** — `start-dev` vs `start` is left to the planner; `start-dev` is likely correct for a PoC.
- **JAR delivery mechanism** — Volume mount vs custom image COPY; planner picks simpler.
- **Docker network topology** — Custom bridge network vs default; planner decides.
- **Port exposure** — Which ports to expose on host for debugging; planner decides.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Specs
- `CLAUDE.md` — Full tech stack decisions: CrateDB cluster settings (`network.host=_site_`, discovery config, heap), Maven dependencies (pgJDBC 42.7.4, JSQLParser 5.3, maven-shade 3.6.2), Keycloak JAR injection mechanism (`/opt/keycloak/providers/`, `KC_DB_DRIVER`)
- `research.md` — CrateDB PostgreSQL compatibility gaps; explains WHY the proxy is needed and what SQL patterns will fail
- `.planning/REQUIREMENTS.md` §Infrastructure — INFRA-01 through INFRA-04 are the acceptance criteria for this phase

### Upstream Sources (from CLAUDE.md)
- CrateDB multi-node setup: https://cratedb.com/docs/crate/howtos/en/latest/clustering/multi-node-setup.html
- Keycloak JDBC driver injection: https://github.com/keycloak/keycloak/discussions/11265
- Keycloak KC_DB_DRIVER fix (KC 24+): https://github.com/keycloak/keycloak/issues/26168

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None — greenfield project. No existing source code.

### Established Patterns
- None yet. Phase 1 establishes the foundational patterns.

### Integration Points
- Proxy JAR → `/opt/keycloak/providers/` (Keycloak SPI provider directory)
- CrateDB PostgreSQL wire protocol on port 5432 (internal Docker network only)
- CrateDB admin UI on port 4200 (may be exposed to host for debugging)
- Keycloak HTTP on port 8080 (exposed to host)

</code_context>

<specifics>
## Specific Ideas

- User explicitly confirmed: custom Docker image based on official Keycloak image is acceptable, as long as there are no source code changes and no fork of Keycloak. A `FROM quay.io/keycloak/keycloak:latest` Dockerfile that only COPYs the proxy JAR is within constraints.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 01-infrastructure-bootstrap*
*Context gathered: 2026-04-22*
