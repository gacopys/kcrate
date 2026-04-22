# Phase 2: JDBC Proxy Implementation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-22
**Phase:** 02-jdbc-proxy-implementation
**Areas discussed:** Parse failure behavior, SQL rewrite logging, Sequence handling, Implementation structure

---

## Parse Failure Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Silent passthrough | Forward unchanged, no logging | |
| Log + passthrough | Log original SQL to stderr, then forward unchanged | |
| Throw exception | Fail loudly, stop Keycloak with SQLException | ✓ |

**User's choice:** Throw exception
**Notes:** Loud failures preferred — any unhandled SQL pattern must surface immediately during Phase 3 rather than silently reaching CrateDB and producing a confusing wire-protocol error.

---

## SQL Rewrite Logging

| Option | Description | Selected |
|--------|-------------|----------|
| No logging | Exceptions are enough signal | |
| Log to stderr unconditionally | Always visible; original → rewritten SQL on every interception | ✓ |
| java.util.logging at FINE level | Off by default, enable when debugging | |

**User's choice:** Log to stderr unconditionally
**Notes:** Verbose but maximally visible during Phase 3 migration. Every rewrite visible in `docker compose logs keycloak`.

---

## Sequence Handling

| Option | Description | Selected |
|--------|-------------|----------|
| Strip CREATE/ALTER SEQUENCE silently + throw on nextval() | Strip DDL (CrateDB can't create sequences), surface nextval calls loudly | ✓ |
| Strip CREATE/ALTER SEQUENCE, let nextval() pass through | CrateDB produces its own error if called | |
| Strip everything sequence-related silently | Opaque failures if sequences are actually needed | |

**User's choice:** Strip sequence DDL silently + throw exception if nextval() appears
**Notes:** User questioned whether Keycloak actually requires sequences. Modern Keycloak v26.5.x generates UUIDs in Java (not database sequences), so nextval() likely never appears. Throw on nextval is consistent with the Area 1 exception policy.

---

## Implementation Structure

| Option | Description | Selected |
|--------|-------------|----------|
| One plan | All 11 rewrite layers end-to-end | |
| Split by concern | Plan 1: wrappers + transaction swallowing; Plan 2: DDL rewrites; Plan 3: index rewrites + WITH clause | ✓ |
| Split by risk | Plan 1: all known rewrites; Plan 2: sequence handling + integration smoke | |

**User's choice:** Split by concern (3 plans)
**Notes:** Localized failures; meaningful checkpoint after Plan 1 when Keycloak stops crashing on ROLLBACK.

---

## Claude's Discretion

- Rewriter class structure (single SqlRewriter vs per-concern classes)
- Statement interception granularity (individual execute methods vs single intercept funnel)
- NEXTVAL detection mechanism (string match vs JSQLParser Function visitor)
