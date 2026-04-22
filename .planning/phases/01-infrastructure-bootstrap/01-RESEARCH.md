# Phase 1: Infrastructure Bootstrap - Research

**Researched:** 2026-04-22
**Domain:** Docker Compose, CrateDB cluster, Keycloak JDBC injection, Maven fat JAR build
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Phase 1 proxy is a **pure passthrough stub** — the driver registers via SPI, establishes a connection, and forwards all SQL to CrateDB unchanged. No SQL rewriting at all.
- **D-02:** Keycloak crashing on its first `BEGIN` statement is **acceptable for Phase 1** — the success criterion is only "no ClassNotFoundException". SQL rewriting begins in Phase 2.
- **D-03:** Custom Keycloak Docker image is **permitted** — `FROM quay.io/keycloak/keycloak:latest` with the proxy JAR added. This is not a fork and involves no Keycloak source changes.
- **D-04:** Hard constraint: **no Keycloak source code changes, no Keycloak fork**. The image customization is limited to copying the pre-built proxy JAR into `/opt/keycloak/providers/`.

### Claude's Discretion

- **Source directory layout** — Where proxy source lives (`proxy/`, `jdbc-proxy/`, etc.) is left to the planner.
- **Keycloak start mode** — `start-dev` vs `start` is left to the planner; `start-dev` is likely correct for a PoC.
- **JAR delivery mechanism** — Volume mount vs custom image COPY; planner picks simpler.
- **Docker network topology** — Custom bridge network vs default; planner decides.
- **Port exposure** — Which ports to expose on host for debugging; planner decides.

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-01 | 3-node CrateDB cluster forms successfully in Docker Compose (all nodes joined, cluster health green) | CrateDB 6.2.6 image verified; cluster bootstrap parameters confirmed from CLAUDE.md; node count verified via `sys.nodes` |
| INFRA-02 | Docker Compose setup is self-contained — single `docker compose up` starts the full stack | Docker Compose 2.27 confirmed installed; `service_completed_successfully` and `service_healthy` conditions verified supported |
| INFRA-03 | JDBC proxy JAR is built automatically as part of Docker Compose startup (Maven builder service) | `maven:3.9-eclipse-temurin-21` image confirmed available; named volume JAR sharing pattern documented |
| INFRA-04 | Keycloak connects to CrateDB via the custom JDBC proxy driver (no ClassNotFoundException) | Keycloak 26.6.1 image confirmed; `/opt/keycloak/providers/` is writable; `KC_DB_DRIVER` / `--db-driver` flag confirmed in `kc.sh --help` |
</phase_requirements>

---

## Summary

Phase 1 stands up the full Docker Compose stack: a 3-node CrateDB cluster, a one-shot Maven builder service that compiles the proxy stub JAR, and Keycloak loading that JAR from a shared volume. The success criterion is narrow — no ClassNotFoundException; Keycloak crashing on `BEGIN` is explicitly acceptable.

All tooling is confirmed present on the host (Docker 26.1.3, Docker Compose 2.27.1, Maven 3.8.7, Java 21.0.5). Both target images are pulled and verified: `crate:6.2` (CrateDB 6.2.6) and `quay.io/keycloak/keycloak:latest` (Keycloak 26.6.1, Java 21 LTS). The Maven builder image `maven:3.9-eclipse-temurin-21` is confirmed pulled and ready.

The core orchestration challenge is startup ordering: CrateDB nodes must form a cluster before Keycloak attempts connection, and the proxy JAR must exist in the shared volume before Keycloak starts. Docker Compose `depends_on` with `condition: service_healthy` and `condition: service_completed_successfully` handles both requirements cleanly with Compose 2.27.

**Primary recommendation:** Use a named Docker volume to share the built JAR; Maven builder service writes the fat JAR to `/output` on the volume, Keycloak mounts the same volume at `/opt/keycloak/providers/`. Use `start-dev` mode and the default network. This is simpler than building a custom Keycloak image and avoids a separate `docker build` step.

---

## Standard Stack

### Core

| Library / Image | Version | Purpose | Why Standard |
|-----------------|---------|---------|--------------|
| `crate` Docker image | 6.2.6 | CrateDB cluster nodes | Official image from Docker Hub; only supported distribution |
| `quay.io/keycloak/keycloak` | 26.6.1 (latest) | Keycloak IAM server | Official Red Hat image; constraint from CLAUDE.md |
| `maven:3.9-eclipse-temurin-21` | 3.9 / Java 21 | Builder service in Compose | Matches Keycloak's Java 21 runtime; official Maven image |
| `org.postgresql:postgresql` | 42.7.7 | pgJDBC — real driver wrapped by proxy | CLAUDE.md specifies 42.7.4; 42.7.7 is latest [VERIFIED: Maven Central] |
| `com.github.jsqlparser:jsqlparser` | 5.3 | SQL AST rewriting (Phase 2); declared in pom.xml now | CLAUDE.md specifies 5.3; confirmed latest [VERIFIED: Maven Central] |
| `maven-shade-plugin` | 3.6.2 | Fat JAR with SPI service merging | CLAUDE.md specifies 3.6.2; confirmed latest [VERIFIED: Maven Central] |

**Note on pgJDBC version:** CLAUDE.md specifies 42.7.4 as the locked decision. Maven Central shows 42.7.7 as latest [VERIFIED: Maven Central]. The plan should use 42.7.4 as specified to stay aligned with documented decisions, but 42.7.7 is also valid.

### Installation (pom.xml dependencies)

```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>42.7.4</version>
</dependency>
<dependency>
  <groupId>com.github.jsqlparser</groupId>
  <artifactId>jsqlparser</artifactId>
  <version>5.3</version>
</dependency>
```

---

## Architecture Patterns

### Recommended Project Structure

```
.                           # repo root
├── docker-compose.yml      # full stack definition
├── proxy/                  # JDBC proxy source module
│   ├── pom.xml             # Maven build (shade plugin)
│   └── src/main/
│       ├── java/com/example/crateproxy/
│       │   ├── CrateProxyDriver.java
│       │   ├── CrateProxyConnection.java
│       │   ├── CrateProxyStatement.java
│       │   └── CrateProxyPreparedStatement.java
│       └── resources/META-INF/services/
│           └── java.sql.Driver     # SPI registration file
└── .planning/              # GSD planning artifacts
```

### Pattern 1: Maven Builder Service (One-Shot)

**What:** A `docker compose` service that runs Maven, builds the fat JAR, writes it to a named volume, then exits successfully.

**When to use:** Any time a build artifact must be produced at `compose up` time without a pre-build step.

**Key mechanism:** `condition: service_completed_successfully` in the downstream service's `depends_on` block waits for the builder to exit 0. Verified supported in Compose 2.27 [VERIFIED: manual test, compose config validation passed].

```yaml
services:
  proxy-builder:
    image: maven:3.9-eclipse-temurin-21
    working_dir: /build
    volumes:
      - ./proxy:/build             # source code (bind mount, read-only)
      - proxy-jar:/output          # output volume (writable)
      - maven-cache:/root/.m2      # cache dependencies between runs
    command: >
      mvn package -q
      -DskipTests
      && cp target/crate-proxy-1.0-SNAPSHOT.jar /output/crate-proxy.jar

  keycloak:
    image: quay.io/keycloak/keycloak:latest
    depends_on:
      proxy-builder:
        condition: service_completed_successfully
      cratedb1:
        condition: service_healthy
    volumes:
      - proxy-jar:/opt/keycloak/providers  # JAR lands directly in providers dir

volumes:
  proxy-jar:
  maven-cache:
```

**Note:** The `proxy-jar` volume is mounted at `/opt/keycloak/providers` in Keycloak. The builder copies only the fat JAR there. Keycloak's providers directory is `drwxrwxr-x` (group-writable) [VERIFIED: docker inspect]. Maven runs as root, writes world-readable files — Keycloak (UID 1000) can read them.

### Pattern 2: CrateDB 3-Node Cluster

**What:** Three CrateDB containers forming a cluster via unicast discovery.

**Key parameters** (from CLAUDE.md, verified against CrateDB docs):

```yaml
services:
  cratedb1:
    image: crate:6.2
    environment:
      CRATE_HEAP_SIZE: 1g
    command: >
      crate
      -Cnetwork.host=_site_
      -Cdiscovery.seed_hosts=cratedb2,cratedb3
      -Ccluster.initial_master_nodes=cratedb1,cratedb2,cratedb3
      -Cgateway.expected_data_nodes=3
      -Cgateway.recover_after_data_nodes=2
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:4200/_sql", "-X", "POST",
             "-H", "Content-Type: application/json",
             "--data", "{\"stmt\":\"SELECT count(*) FROM sys.nodes\"}"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
```

**Critical:** `/_cluster/health` (Elasticsearch-style) does NOT exist in CrateDB 6.2.6 [VERIFIED: manual test — returns 404/no response]. Use `/_sql` with `SELECT count(*) FROM sys.nodes` instead [VERIFIED: returns `{"cols":["count"],"rows":[[1]],...}`].

### Pattern 3: JDBC Driver SPI Registration

**What:** The `META-INF/services/java.sql.Driver` file in the fat JAR registers the proxy driver class with `java.util.ServiceLoader`.

**File:** `src/main/resources/META-INF/services/java.sql.Driver`
**Content:** `com.example.crateproxy.CrateProxyDriver`

**Maven shade plugin must use `ServicesResourceTransformer`** to merge the SPI file from pgJDBC and the proxy into one without one overwriting the other [ASSUMED — standard practice, per CLAUDE.md].

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

### Pattern 4: Keycloak JDBC Driver Configuration

**What:** Tell Keycloak to use the proxy driver class instead of the default pgJDBC driver.

**Environment variables:**

```yaml
environment:
  KC_DB: postgres
  KC_DB_URL: jdbc:postgresql://cratedb1:5432/keycloak
  KC_DB_USERNAME: crate
  KC_DB_PASSWORD: ""
  KC_DB_DRIVER: com.example.crateproxy.CrateProxyDriver
```

**Confirmed:** `--db-driver` flag is present in Keycloak 26.6.1 `kc.sh --help` output [VERIFIED: `docker run quay.io/keycloak/keycloak:latest start-dev --help`].

**Keycloak start mode:** Use `start-dev` for PoC. It skips production-mode TLS/certificate requirements and reduces startup complexity. `start` mode requires a built optimized image, which adds friction.

**Command in compose:**
```yaml
command: start-dev
```

### Pattern 5: CrateDB Database Name

CrateDB does not have traditional databases but accepts any database name in a pgJDBC URL — it routes to the `doc` schema internally. `jdbc:postgresql://cratedb1:5432/keycloak` is valid [ASSUMED — consistent with CrateDB PostgreSQL compatibility docs behavior; not directly tested in this session].

### Anti-Patterns to Avoid

- **Using `/_cluster/health` for CrateDB healthcheck:** This is an Elasticsearch endpoint. CrateDB 6.2.6 does not expose it. Use `/_sql` with `SELECT count(*) FROM sys.nodes` [VERIFIED: manual test].
- **Relying on `depends_on` without conditions:** `depends_on: cratedb1` without `condition: service_healthy` only waits for the container to start, not for CrateDB to be ready to accept connections. The cluster needs ~15-30s to form.
- **Omitting `ServicesResourceTransformer` from shade plugin:** Without it, only one `META-INF/services/java.sql.Driver` file survives — either the proxy or pgJDBC. Both are needed in the fat JAR.
- **Using `start` mode for Keycloak in PoC:** Requires `--optimized` image or certificate configuration. `start-dev` is correct for PoC.
- **Mounting the volume at a path OTHER than `/opt/keycloak/providers/`:** Keycloak only scans this specific directory for SPI provider JARs at startup.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Fat JAR with shaded deps | Custom zip/merge script | `maven-shade-plugin` with `ServicesResourceTransformer` | Service file merging is non-trivial; shade handles META-INF correctly |
| JDBC driver URL claiming | Custom `DriverManager` | Standard SPI via `META-INF/services/java.sql.Driver` | DriverManager discovers drivers via ServiceLoader automatically |
| Startup ordering | Sleep loops in entrypoints | Docker Compose `depends_on` + healthchecks | Deterministic, no arbitrary sleep durations |
| CrateDB cluster formation | Manual node joining scripts | CrateDB's built-in unicast discovery (`discovery.seed_hosts`) | CrateDB handles election and shard recovery internally |

---

## Common Pitfalls

### Pitfall 1: CrateDB Cluster Not Forming Before Keycloak Starts

**What goes wrong:** Keycloak starts before all 3 CrateDB nodes form a cluster. Connection succeeds (port is open on node 1) but shard allocation is incomplete, causing the first SQL to fail or return errors.

**Why it happens:** `depends_on` without `condition: service_healthy` only waits for container start, not cluster formation. CrateDB single-node startup takes ~10s; 3-node cluster formation takes ~20-35s.

**How to avoid:** Use `condition: service_healthy` with a healthcheck that verifies cluster node count via `sys.nodes`. For Phase 1, checking that at least one node is up is sufficient (passthrough stub does minimal SQL).

**Warning signs:** Log message "no discovery configuration found, will perform best-effort cluster bootstrapping" persists — means the node hasn't found peers yet.

### Pitfall 2: ClassNotFoundException for Proxy Driver

**What goes wrong:** Keycloak starts, `KC_DB_DRIVER` is set, but Keycloak throws `ClassNotFoundException: com.example.crateproxy.CrateProxyDriver`.

**Why it happens:** The JAR is not in `/opt/keycloak/providers/` when Keycloak starts, OR the JAR is there but the class name in `KC_DB_DRIVER` doesn't match the actual class name in the JAR.

**How to avoid:** Ensure `condition: service_completed_successfully` on `proxy-builder`. Verify the fully qualified class name matches exactly. The `providers/` directory is scanned by Keycloak at boot only.

**Warning signs:** Keycloak log shows `Caused by: java.lang.ClassNotFoundException` immediately after startup.

### Pitfall 3: ServicesResourceTransformer Not Configured

**What goes wrong:** Fat JAR is built, driver loads, but connection fails because pgJDBC SPI entry is overwritten by the proxy's SPI file (or vice versa). Both drivers need to be registered.

**Why it happens:** `maven-shade-plugin` without `ServicesResourceTransformer` uses "last one wins" for identical file paths. Both pgJDBC and the proxy write to `META-INF/services/java.sql.Driver`.

**How to avoid:** Add `ServicesResourceTransformer` to the shade plugin configuration. Verify the merged file in the output JAR: `unzip -p crate-proxy.jar META-INF/services/java.sql.Driver`.

### Pitfall 4: Maven Cache Cold Start Timeout

**What goes wrong:** First `docker compose up` fails because Maven downloads all dependencies (pgJDBC ~1.5MB, JSQLParser ~1.3MB plus transitive) and the healthcheck timeout for the Keycloak dependency expires.

**Why it happens:** `maven-cache` volume is empty on first run. Maven downloads from Maven Central. This can take 2-5 minutes on slow connections.

**How to avoid:** Add a `maven-cache` named volume for `/root/.m2`. Subsequent runs use the cache. For Phase 1 the startup timeout is not critical since the proxy-builder only needs to complete before Keycloak starts (not within a healthcheck window).

### Pitfall 5: CrateDB Accepts Empty Password But Requires Superuser

**What goes wrong:** pgJDBC connection to CrateDB fails with authentication error.

**Why it happens:** CrateDB default superuser is `crate` with no password. pgJDBC may send `MD5` or `SCRAM` auth that CrateDB doesn't support in the same way.

**How to avoid:** Use `KC_DB_USERNAME=crate`, `KC_DB_PASSWORD=""` (empty). The `crate` superuser has no password by default in the official Docker image [ASSUMED — consistent with CrateDB Docker image defaults].

---

## Code Examples

### Minimal Passthrough Driver (Phase 1 stub)

```java
// Source: standard java.sql.Driver SPI pattern [ASSUMED — training knowledge]
package com.example.crateproxy;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

public class CrateProxyDriver implements Driver {
    private static final Driver REAL = new org.postgresql.Driver();

    static {
        try {
            DriverManager.registerDriver(new CrateProxyDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return REAL.connect(url, info);  // passthrough — no rewriting in Phase 1
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return REAL.getPropertyInfo(url, info);
    }

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.example.crateproxy"); }
}
```

### CrateDB Healthcheck SQL

```bash
# Source: VERIFIED via manual docker run test (2026-04-22)
# Use /_sql endpoint — /_cluster/health does NOT exist in CrateDB 6.2.6
curl -sf -X POST http://localhost:4200/_sql \
  -H "Content-Type: application/json" \
  -d '{"stmt":"SELECT count(*) FROM sys.nodes"}'
# Returns: {"cols":["count"],"rows":[[1]],...}  (1 = single-node; expect 3 for full cluster)
```

### Maven pom.xml Skeleton

```xml
<!-- Source: CLAUDE.md technology stack decisions [CITED: CLAUDE.md] -->
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>crate-proxy</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <version>42.7.4</version>
    </dependency>
    <dependency>
      <groupId>com.github.jsqlparser</groupId>
      <artifactId>jsqlparser</artifactId>
      <version>5.3</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.6.2</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"/>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | All services | Yes | 26.1.3 | — |
| Docker Compose | Stack orchestration | Yes | 2.27.1 | — |
| Maven | Host build (not needed — builder runs in container) | Yes | 3.8.7 | Builder image handles it |
| Java 21 | JAR compatibility check | Yes | 21.0.5 (OpenJDK) | — |
| `maven:3.9-eclipse-temurin-21` image | proxy-builder service | Yes | Pulled | — |
| `crate:6.2` image | CrateDB nodes | Yes | 6.2.6 | — |
| `quay.io/keycloak/keycloak:latest` image | Keycloak | Yes | 26.6.1, Java 21 LTS | — |
| Internet / Maven Central | First-run dependency download | Assumed yes | — | Pre-seed `maven-cache` volume if offline |

**Missing dependencies with no fallback:** None — all required tools are present.

**Missing dependencies with fallback:** Maven Central access (assumed; if offline, pre-populate the `maven-cache` volume manually).

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | CrateDB accepts any database name in pgJDBC URL, routing to `doc` schema | Architecture Patterns (Pattern 4) | If CrateDB rejects `keycloak` as database name, use empty string or `doc` instead |
| A2 | CrateDB default `crate` superuser has no password in the official Docker image | Pitfall 5 | If auth is required, add `KC_DB_PASSWORD` and CrateDB password env var |
| A3 | `ServicesResourceTransformer` required for correct SPI merging | Don't Hand-Roll / Code Examples | Without it, one SPI entry overwrites the other; easily verified by inspecting JAR |
| A4 | `start-dev` mode in Keycloak 26.6.1 allows unsecured HTTP without additional config | Architecture Patterns | If `start-dev` requires extra flags, consult `kc.sh start-dev --help` |

---

## Open Questions

1. **Healthcheck threshold for 3-node cluster**
   - What we know: `SELECT count(*) FROM sys.nodes` returns node count; single-node returns 1
   - What's unclear: Should healthcheck pass only when all 3 nodes are joined (`count = 3`), or when at least 1 is up? For Phase 1, even 1 node responding is sufficient to unblock Keycloak startup.
   - Recommendation: Use `count(*) > 0` for the healthcheck (node is up and accepting SQL). Cluster formation is a success criterion verifiable via logs, not a startup gate for Phase 1.

2. **CrateDB database name in JDBC URL**
   - What we know: CrateDB uses schemas, not databases; `doc` is the default schema
   - What's unclear: Whether `jdbc:postgresql://cratedb1:5432/keycloak` will error or silently use `doc`
   - Recommendation: Use `doc` as the database name in the JDBC URL to be explicit; if it works with `keycloak`, either value is fine.

---

## Sources

### Primary (HIGH confidence)
- `CLAUDE.md` — Tech stack decisions, driver versions, CrateDB cluster parameters, Keycloak injection mechanism [CITED: CLAUDE.md]
- Docker Hub `crate:6.2` — Image pulled, version 6.2.6 confirmed [VERIFIED: docker pull + docker run]
- `quay.io/keycloak/keycloak:latest` — Image pulled, version 26.6.1, Java 21 LTS confirmed [VERIFIED: docker inspect + docker run]
- `maven:3.9-eclipse-temurin-21` — Image pulled and confirmed available [VERIFIED: docker pull]
- CrateDB `/_sql` health endpoint — Confirmed working via manual test; `/_cluster/health` confirmed NOT available [VERIFIED: manual docker run test]
- `sys.nodes` table — Confirmed returns node count [VERIFIED: manual SQL query]
- Keycloak `--db-driver` flag — Confirmed present in kc.sh `--help` output [VERIFIED: docker run start-dev --help]
- Keycloak `/opt/keycloak/providers/` — Confirmed exists, `drwxrwxr-x` permissions [VERIFIED: docker run ls -la]
- Keycloak UID 1000 — Confirmed [VERIFIED: docker inspect]
- Docker Compose `service_completed_successfully` — Confirmed accepted by compose config validation [VERIFIED: docker compose config]
- Maven Central pgJDBC 42.7.7, JSQLParser 5.3, shade-plugin 3.6.2 — All confirmed as latest versions [VERIFIED: Maven Central search API]

### Secondary (MEDIUM confidence)
- CrateDB multi-node cluster parameters (`network.host=_site_`, `discovery.seed_hosts`, `cluster.initial_master_nodes`) — from CLAUDE.md which cites official CrateDB multi-node setup docs

### Tertiary (LOW confidence)
- CrateDB authentication behavior (empty password for `crate` superuser) — [ASSUMED] based on official image defaults, not directly tested

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions verified against Maven Central and Docker Hub
- Architecture: HIGH — Docker Compose patterns verified, Keycloak providers dir verified
- Pitfalls: HIGH — CrateDB health endpoint failure verified hands-on; others from CLAUDE.md canonical sources
- Environment: HIGH — all tools verified installed and working

**Research date:** 2026-04-22
**Valid until:** 2026-05-22 (stable infrastructure domain; Docker image versions may change on `latest` tag)
