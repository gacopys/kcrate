---
phase: 01-infrastructure-bootstrap
reviewed: 2026-04-22T00:00:00Z
depth: standard
files_reviewed: 4
files_reviewed_list:
  - proxy/pom.xml
  - proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java
  - proxy/src/main/resources/META-INF/services/java.sql.Driver
  - docker-compose.yml
findings:
  critical: 1
  warning: 3
  info: 3
  total: 7
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-04-22T00:00:00Z
**Depth:** standard
**Files Reviewed:** 4
**Status:** issues_found

## Summary

Four files reviewed covering the JDBC proxy stub and Docker Compose cluster definition. The Maven build configuration is correctly structured: dependencies match the CLAUDE.md spec (pgJDBC 42.7.4, JSQLParser 5.3, maven-shade-plugin 3.6.2), the `ServicesResourceTransformer` is present for SPI merging, and Java 21 is correctly targeted.

One critical bug exists in `CrateProxyDriver`: instantiating `org.postgresql.Driver` directly causes pgJDBC to register itself with `DriverManager` before the proxy registers, which means the proxy is silently bypassed for every connection. Three warnings cover: incomplete cluster health gating in `docker-compose.yml` (only `cratedb1` is awaited), the `latest` Keycloak image tag breaking reproducibility, and the SPI services file compounding the double-registration problem. Three info items address hardcoded credentials, missing `mainClass` in `ManifestResourceTransformer`, and port exposure.

## Critical Issues

### CR-01: Proxy silently bypassed — pgJDBC self-registers before CrateProxyDriver

**File:** `proxy/src/main/java/com/example/crateproxy/CrateProxyDriver.java:18`

**Issue:** `new org.postgresql.Driver()` triggers pgJDBC's own static initializer, which calls `DriverManager.registerDriver(new org.postgresql.Driver())` internally. Immediately after, line 22 registers `CrateProxyDriver`. Both drivers claim `jdbc:postgresql:` URLs via `acceptsURL`. `DriverManager.getConnection()` iterates registered drivers in order and returns on the first successful `connect()` — which will be the pgJDBC instance registered at line 18, not the proxy. Every Keycloak connection bypasses the proxy entirely, making Phase 2 SQL rewriting unreachable.

**Fix:** Use `Class.forName` to load pgJDBC without triggering its static self-registration, then construct the real driver instance reflectively, or obtain it from `DriverManager` after it is loaded. The cleanest approach for a proxy:

```java
// Replace line 18:
private static final Driver REAL;

static {
    try {
        // Load pgJDBC class so its Driver is available, but deregister it
        // so only CrateProxyDriver answers jdbc:postgresql: URLs.
        Class.forName("org.postgresql.Driver");
        // Grab the real driver instance before deregistering it.
        Driver pgDriver = DriverManager.getDriver("jdbc:postgresql://localhost/");
        DriverManager.deregisterDriver(pgDriver);
        REAL = pgDriver;
        // Now register only our proxy.
        DriverManager.registerDriver(new CrateProxyDriver());
    } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
    }
}
```

Alternatively, skip `DriverManager` entirely and always instantiate the real driver directly without `Class.forName` — but then the SPI file must not list pgJDBC (ensured by `ServicesResourceTransformer` merging, which does include pgJDBC's SPI entry, so deregistration is still required).

## Warnings

### WR-01: Keycloak only waits for cratedb1 — cratedb2 and cratedb3 may be mid-join

**File:** `docker-compose.yml:115-119`

**Issue:** The `depends_on` block gates Keycloak's start on `cratedb1: condition: service_healthy` only. The Keycloak JDBC URL lists all three nodes with `loadBalanceHosts=true`. pgJDBC will round-robin across all three; if cratedb2 or cratedb3 have not yet joined the cluster quorum when Keycloak starts, connections routed to them will fail or return incomplete cluster state, causing Liquibase schema bootstrap to error out.

**Fix:** Add health checks to `cratedb2` and `cratedb3` (they are already present) and add them to Keycloak's `depends_on`:

```yaml
keycloak:
  depends_on:
    proxy-builder:
      condition: service_completed_successfully
    cratedb1:
      condition: service_healthy
    cratedb2:
      condition: service_healthy
    cratedb3:
      condition: service_healthy
```

The health checks on cratedb2/cratedb3 query `sys.nodes` for a count, which implicitly verifies the node has joined the cluster. This ensures all three nodes are ready before Keycloak attempts schema creation.

### WR-02: Keycloak pinned to `latest` image tag — reproducibility broken

**File:** `docker-compose.yml:114`

**Issue:** `image: quay.io/keycloak/keycloak:latest` will silently resolve to a different image on each fresh `docker compose pull`. CLAUDE.md documents the intended version as 26.5.2. A future `latest` could change the Quarkus classloader behavior, driver injection mechanism, or SQL patterns, breaking the proxy in ways that are difficult to diagnose.

**Fix:**
```yaml
image: quay.io/keycloak/keycloak:26.5.2
```

### WR-03: SPI services file compounds double-registration

**File:** `proxy/src/main/resources/META-INF/services/java.sql.Driver:1`

**Issue:** `ServicesResourceTransformer` merges this file with pgJDBC's own `META-INF/services/java.sql.Driver` (which lists `org.postgresql.Driver`). The shaded JAR's merged services file will contain both entries. When loaded via the Java SPI `ServiceLoader`, both drivers register via their static blocks, then `CrateProxyDriver`'s static block registers a third time. This results in three registrations across two distinct driver instances competing for `jdbc:postgresql:` URLs, compounding CR-01.

**Fix:** After fixing CR-01 (deregistering pgJDBC after load), ensure the services file approach is consistent. If the proxy's static block deregisters pgJDBC and registers only itself, the merged SPI file is harmless — pgJDBC will be loaded by SPI, deregistered by the proxy's static block, and the proxy will be the sole registered driver. Document this dependency explicitly in a comment in the static block so the invariant is not accidentally removed.

## Info

### IN-01: Hardcoded admin credential in docker-compose.yml

**File:** `docker-compose.yml:129`

**Issue:** `KC_BOOTSTRAP_ADMIN_PASSWORD: admin` is committed in plain text. For a PoC with no external exposure this is low risk, but if the compose file is shared or the host port 8080 is reachable, the Keycloak admin console is trivially accessible.

**Fix:** Use an environment variable with a default:
```yaml
KC_BOOTSTRAP_ADMIN_PASSWORD: ${KC_ADMIN_PASSWORD:-admin}
```
Add a `.env` file (git-ignored) for overrides in shared environments.

### IN-02: ManifestResourceTransformer has no mainClass configured

**File:** `proxy/pom.xml:45`

**Issue:** The `ManifestResourceTransformer` is included but has no `<configuration>` block specifying a `mainClass`. Without it, the transformer only merges manifest entries from dependencies, which is harmless but provides no value for a library JAR. The shade plugin will emit a warning during build.

**Fix:** Either remove the transformer (it adds no value for a library fat JAR) or suppress the build warning by adding an explicit empty configuration:
```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
  <!-- Library JAR; no main class needed -->
</transformer>
```

### IN-03: CrateDB PostgreSQL wire port (5432) exposed to host on cratedb1 only

**File:** `docker-compose.yml:34`

**Issue:** `5432:5432` is exposed on `cratedb1` for host debugging. `cratedb2` and `cratedb3` have no host port mappings. This is fine for a PoC but means debugging connections always hit node 1 regardless of `loadBalanceHosts`. There is no auth on CrateDB by default, so the exposed port allows unauthenticated PostgreSQL wire access from localhost.

**Fix:** No action needed for PoC. Document the intent in a comment so reviewers understand the asymmetry is deliberate.

---

_Reviewed: 2026-04-22T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
