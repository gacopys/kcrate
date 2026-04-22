# Phase 1: Infrastructure Bootstrap - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-22
**Phase:** 01-infrastructure-bootstrap
**Areas discussed:** Proxy stub scope, JAR delivery mechanism

---

## Proxy Stub Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Pure passthrough stub | Driver registers, connects, forwards all SQL unchanged. Keycloak will crash on BEGIN — acceptable. | ✓ |
| Minimal viable rewriting | Stub + swallow BEGIN/COMMIT/ROLLBACK so Keycloak gets past first transaction. | |
| Full stub + transaction + FK strip | Handle three most common failures in Phase 1 for earlier signal. | |

**User's choice:** Pure passthrough stub
**Notes:** Phase 1 success = no ClassNotFoundException only. SQL rewriting is Phase 2's job.

---

## JAR Delivery Mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Volume mount at runtime | Official Keycloak image + JAR mounted via compose volume. Documented in CLAUDE.md. | |
| Custom image with COPY | FROM official image + COPY jar. No source changes. | |
| Either — Claude decides | Planner picks simpler approach. | ✓ |

**User's choice:** Claude's discretion
**Notes:** User clarified during gray area selection: custom Docker image is permitted as long as it's FROM the official Keycloak image with no source changes and no fork. This is a slight expansion of the original "volume mount" approach described in CLAUDE.md — both are now valid options.

---

## Claude's Discretion

- Source directory layout (proxy/, jdbc-proxy/, etc.) — left to planner
- Keycloak start mode (start-dev vs start) — left to planner
- JAR delivery (volume mount vs custom image COPY) — left to planner
- Docker network topology — left to planner
- Host port exposure — left to planner

## Deferred Ideas

None.
