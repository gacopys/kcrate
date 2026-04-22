# Technology Stack

**Project:** Keycloak on CrateDB — JDBC Proxy PoC
**Researched:** 2026-04-22
**Overall confidence:** HIGH (all critical claims verified against official docs or primary sources)

---

## 1. JDBC Proxy Driver Pattern

### Recommended Approach: Hand-rolled delegation wrapper (no framework)

**Do NOT use P6Spy or datasource-proxy as the base.** Both are observability tools designed to intercept and log; their extension model is not designed for SQL rewriting. Adapting either to silently rewrite DDL and swallow transaction commands would require fighting against their architecture.

**Do NOT use the CrateDB JDBC driver (`io.crate.client.jdbc.CrateDriver`).** It exists (crate-jdbc, last release 2.7.0, April 2023) but is a legacy driver and marked as such by CrateDB's own docs. More critically: Keycloak has already been reported to throw `ClassNotFoundException: io.crate.client.jdbc.CrateDriver` even with the JAR in providers (GitHub Discussion #21315) — the crate driver uses a non-standard URL scheme (`jdbc:crate://`) that Keycloak's Quarkus Agroal pool does not expect alongside `KC_DB=postgres`.

**The standard pattern for a JDBC proxy driver** (used by P6Spy, AWS JDBC Wrapper, etc.) is:

1. Implement `java.sql.Driver`. Register via `META-INF/services/java.sql.Driver` (JDBC 4.0 SPI).
2. In `acceptsURL(String url)`, claim a custom URL prefix, e.g. `jdbc:crateproxy:postgresql://`.
3. In `connect(String url, Properties info)`, strip the proxy prefix, construct the real URL `jdbc:postgresql://...`, and call the underlying `org.postgresql.Driver.connect()` directly (not via `DriverManager` — avoids classloader conflicts).
4. Wrap the returned `java.sql.Connection` in a delegating proxy class.
5. In the `Connection` wrapper, override:
   - `createStatement()` / `prepareStatement()` → return a `Statement`/`PreparedStatement` wrapper
   - `setAutoCommit()` → no-op (always true for CrateDB)
   - `commit()` / `rollback()` → no-op
6. In the `Statement`/`PreparedStatement` wrapper, intercept `execute*()` methods, run SQL through the rewriter, then delegate to the real statement.

**Why direct delegation over reflection proxies (java.lang.reflect.Proxy):** `java.lang.reflect.Proxy` requires interfaces only, but `PreparedStatement` implementations often require casting to vendor-specific subtypes. Concrete delegation classes (one per JDBC interface) avoid this pitfall and are easier to debug. This is the same approach used by the AWS JDBC Wrapper.

**Key classes to implement (minimum viable set):**

| Class | Implements | Purpose |
|-------|-----------|---------|
| `CrateProxyDriver` | `java.sql.Driver` | Entry point; URL claiming; wraps real driver |
| `CrateProxyConnection` | `java.sql.Connection` | Swallows `commit`/`rollback`/`setAutoCommit`; delegates everything else |
| `CrateProxyStatement` | `java.sql.Statement` | Intercepts `execute*`; runs SQL through rewriter |
| `CrateProxyPreparedStatement` | `java.sql.PreparedStatement` | Same for prepared statements; rewrites at prepare time |

**Reference implementation to study:** `p6spy/src/main/java/com/p6spy/engine/spy/P6SpyDriver.java` (GitHub) shows the URL-stripping and delegation pattern clearly, even though P6Spy itself is not used here.

**Confidence:** HIGH — pattern is documented in JDBC spec and replicated across multiple production drivers.

---

## 2. SQL Parsing and Rewriting

### Recommended Library: JSQLParser 5.3

**Coordinates:** `com.github.jsqlparser:jsqlparser:5.3`
**Released:** May 17, 2025
**Java requirement:** 11+ (Java 21 is supported)
**License:** Apache 2.0 / LGPL 2.1 dual

**Why JSQLParser over alternatives:**

| Library | Verdict | Reason |
|---------|---------|--------|
| JSQLParser 5.3 | **Use this** | Lightweight, visitor-pattern AST, covers DDL+DML, no query planning overhead, actively maintained |
| Apache Calcite | Reject | Full query planner/optimizer framework — brings in 10+ MB of dependencies, requires schema registration, designed for implementing databases not rewriting SQL. Massive overkill for a proxy. |
| ANTLR grammar | Reject | Would require maintaining a custom PostgreSQL grammar. JSQLParser already does this. |
| Regex rewriting | Reject for DDL | Safe for trivial cases (`BEGIN`, `COMMIT`, `ROLLBACK` are fine with regex) but breaks on nested parens in DDL and multi-statement batches. Cannot reliably parse `CREATE TABLE ... FOREIGN KEY (...)` clauses with regex. |

**Practical rewriting approach with JSQLParser:**

```java
// Parse
Statement stmt = CCJSqlParserUtil.parse(sql);

// Use StatementDeParser + visitor overrides to transform
// Example: strip FK constraints from CreateTable
if (stmt instanceof CreateTable ct) {
    ct.getColumnDefinitions().forEach(col -> col.setColumnSpecs(...));
    ct.setIndexes(filterFKs(ct.getIndexes()));
    // Serialize back
    return ct.toString();
}
```

JSQLParser's `StatementDeParser` provides `toString()` on the AST, so you parse → mutate → serialize. The round-trip fidelity is good for the SQL patterns Keycloak/Liquibase emits (standard PostgreSQL DDL).

**What to handle with simple regex/string matching instead of JSQLParser** (avoids parse overhead on hot paths):

- `BEGIN` → swallow (exact match or `sql.trim().equalsIgnoreCase("BEGIN")`)
- `COMMIT` → swallow
- `ROLLBACK` → swallow
- `ROLLBACK TO SAVEPOINT ...` → swallow

**What requires JSQLParser:**

- `CREATE TABLE` — strip `FOREIGN KEY` index entries and `REFERENCES` column specs
- `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY` — strip entire statement
- `CREATE SEQUENCE` / `ALTER SEQUENCE` — remap or stub
- Type remapping (`bytea → TEXT`, `uuid → TEXT`, `jsonb → OBJECT`) — requires inspecting column type tokens in `CreateTable`

**Note on JSQLParser 4.9 vs 5.x:** 4.9 was the last JDK 8 release. Since the target is Java 21, use 5.3.

**Confidence:** HIGH — verified via official JSQLParser docs and Maven Central.

---

## 3. Keycloak JDBC Driver Injection

### Exact Mechanism (Keycloak 24+, Quarkus-based)

**Image:** `quay.io/keycloak/keycloak:latest` (currently 26.5.2 as of April 2026, Java 21 OpenJDK headless on UBI9-micro)

**Injection via volume mount (no custom image build required for PoC):**

```yaml
# docker-compose.yml
keycloak:
  image: quay.io/keycloak/keycloak:latest
  volumes:
    - ./proxy-driver.jar:/opt/keycloak/providers/proxy-driver.jar:ro
  environment:
    KC_DB: postgres
    KC_DB_URL: jdbc:crateproxy:postgresql://cratedb01:5432/keycloak
    KC_DB_DRIVER: com.example.crateproxy.CrateProxyDriver
    KC_DB_USERNAME: crate
    KC_DB_PASSWORD: ""
    KC_TRANSACTIONS_XA_ENABLED: "false"   # CRITICAL: prevents Agroal stripping URL params
    KC_BOOTSTRAP_ADMIN_USERNAME: admin
    KC_BOOTSTRAP_ADMIN_PASSWORD: admin
  command: ["start-dev"]
```

**Key points:**

1. **`KC_DB: postgres`** — Must be set to `postgres` (not a custom value). This tells Keycloak which Liquibase dialect to use and which connection pool configuration to apply. Keycloak does not have a "cratedb" dialect. The proxy must make CrateDB look like PostgreSQL to Liquibase.

2. **`KC_DB_URL`** — Full JDBC URL. Keycloak 24+ passes this directly to Agroal. The proxy driver's `acceptsURL()` must claim the prefix (e.g., `jdbc:crateproxy:postgresql://`). Keycloak does NOT generate the URL from host/port/db if `KC_DB_URL` is set.

3. **`KC_DB_DRIVER`** — Fully qualified class name of the proxy driver. Required when using a non-default driver class. Fixed in KC 24.0 (was broken in a nightly, PR #26171).

4. **`/opt/keycloak/providers/`** — The flat classpath location for custom JARs. The directory is on the Quarkus classloader's path, shared (not isolated) with core Keycloak libraries. JARs placed here are available both at `kc.sh build` time and at runtime.

5. **`KC_TRANSACTIONS_XA_ENABLED: "false"`** — CRITICAL. When XA transactions are enabled (the default), Agroal's XA connection wrapper strips custom parameters from the JDBC URL before passing it to the driver. Disabling XA ensures the full URL reaches `CrateProxyDriver.connect()`. This is documented in Keycloak Discussion #11265.

6. **Build step with `start-dev`:** `start-dev` skips the build phase, which means it does NOT require a pre-built image and picks up providers from the volume mount at startup. This is the right mode for the PoC. `start` (production mode) would require `kc.sh build` to have run inside the image with the JAR present.

7. **`META-INF/services/java.sql.Driver`** — The proxy JAR MUST include this file containing the fully qualified driver class name. Keycloak's Quarkus runtime uses the Java ServiceLoader to discover JDBC drivers. Without it, the driver class is never registered with `DriverManager`, and Keycloak cannot find it regardless of `KC_DB_DRIVER`.

**JDBC URL the proxy must accept:**
```
jdbc:crateproxy:postgresql://cratedb01:5432/keycloak
```

**The real URL the proxy passes to pgJDBC:**
```
jdbc:postgresql://cratedb01:5432/keycloak?defaultRowFetchSize=0
```

The `defaultRowFetchSize=0` parameter is recommended to disable cursor-based fetching (which requires transactions in pgJDBC but CrateDB ignores transaction boundaries anyway). Alternatively, ensure `autoCommit=true` is always set on the underlying connection.

**Classloader caveat:** Keycloak's Quarkus classloader is flat — providers share the same classpath as Keycloak core. This means the proxy JAR's classes can shadow Keycloak internals if package names collide. Use a unique package name (e.g., `io.example.crateproxy`).

**Confidence:** HIGH — verified against Keycloak Discussion #21315, #11265, Issue #26168, and Red Hat KC 26.0 Server Configuration Guide.

---

## 4. CrateDB Docker Clustering

### Image and Version

**Official image:** `crate` (Docker Hub official library)
**Current stable:** `6.2.6` (tags: `6.2.6`, `6.2`, `latest`)
**Architecture support:** `amd64`, `arm64v8`

Use `crate:6.2` (not `crate:latest`) in the PoC to pin the minor series and avoid unexpected upgrades.

### PostgreSQL Wire Protocol Note

CrateDB 6.x emulates PostgreSQL server version 14 on the wire. The recommended pgJDBC version is `42.7.4` (stable, before the 42.7.5–42.7.7 regression with CrateDB 5.x metadata methods — though CrateDB 6.x does not have this issue, pinning to `42.7.4` avoids any ambiguity). The current latest pgJDBC is `42.7.10` and is compatible with CrateDB 6.x.

### 3-Node Docker Compose Configuration

```yaml
services:
  cratedb01:
    image: crate:6.2
    ports:
      - "4200:4200"   # HTTP/Admin UI (node 1 only exposed)
      - "5432:5432"   # PostgreSQL wire protocol (node 1 only exposed)
    environment:
      CRATE_HEAP_SIZE: 1g
    command: >
      crate
        -Ccluster.name=kc-crate-cluster
        -Cnode.name=cratedb01
        -Cnode.data=true
        -Cnetwork.host=_site_
        -Cdiscovery.seed_hosts=cratedb02,cratedb03
        -Ccluster.initial_master_nodes=cratedb01,cratedb02,cratedb03
        -Cgateway.expected_data_nodes=3
        -Cgateway.recover_after_data_nodes=2
    networks:
      - crate-net

  cratedb02:
    image: crate:6.2
    environment:
      CRATE_HEAP_SIZE: 1g
    command: >
      crate
        -Ccluster.name=kc-crate-cluster
        -Cnode.name=cratedb02
        -Cnode.data=true
        -Cnetwork.host=_site_
        -Cdiscovery.seed_hosts=cratedb01,cratedb03
        -Ccluster.initial_master_nodes=cratedb01,cratedb02,cratedb03
        -Cgateway.expected_data_nodes=3
        -Cgateway.recover_after_data_nodes=2
    networks:
      - crate-net

  cratedb03:
    image: crate:6.2
    environment:
      CRATE_HEAP_SIZE: 1g
    command: >
      crate
        -Ccluster.name=kc-crate-cluster
        -Cnode.name=cratedb03
        -Cnode.data=true
        -Cnetwork.host=_site_
        -Cdiscovery.seed_hosts=cratedb01,cratedb02
        -Ccluster.initial_master_nodes=cratedb01,cratedb02,cratedb03
        -Cgateway.expected_data_nodes=3
        -Cgateway.recover_after_data_nodes=2
    networks:
      - crate-net

networks:
  crate-net:
    driver: bridge
```

**Configuration parameter rationale:**

| Parameter | Value | Why |
|-----------|-------|-----|
| `network.host=_site_` | Site-local address | Binds to the container's Docker network interface, not loopback |
| `discovery.seed_hosts` | Other two nodes | Unicast discovery; each node lists the peers it knows at startup |
| `cluster.initial_master_nodes` | All three node names | Required for cluster bootstrap; identical on all nodes; CrateDB 4+ auto-calculates quorum from this |
| `gateway.expected_data_nodes=3` | 3 | Cluster waits for all 3 nodes before starting shard recovery |
| `gateway.recover_after_data_nodes=2` | 2 | Allows recovery to proceed if at least 2 of 3 nodes are up (quorum) |
| `CRATE_HEAP_SIZE=1g` | 1 GB | Minimum viable for PoC; production would be ~50% of available RAM |

**Do NOT use `discovery.zen.*` settings** — those are CrateDB 3.x / Elasticsearch-era settings. CrateDB 4+ uses the `discovery.seed_hosts` / `cluster.initial_master_nodes` pattern.

**Keycloak only needs to connect to one CrateDB node.** CrateDB is multi-master (shared-nothing); all nodes accept both reads and writes. Point `KC_DB_URL` at `cratedb01:5432`. For PoC purposes, this is sufficient — there is no need for a load balancer.

**Confidence:** HIGH — pattern confirmed against CrateDB Guide multi-node-setup docs and Docker Hub official image page (version verified April 2026).

---

## 5. Build Tooling

### Recommended: Maven 3.9+ with maven-shade-plugin 3.6.2

**Why Maven over Gradle:** Maven's shade plugin has better-documented behavior for the specific case of JDBC driver fat JARs. The `ServicesResourceTransformer` correctly merges `META-INF/services/java.sql.Driver` entries from multiple JARs into one — this is essential because the proxy JAR bundles both pgJDBC (which registers `org.postgresql.Driver`) and the proxy driver (which registers `CrateProxyDriver`). Gradle's shadow plugin does the same but with less documentation on JDBC-specific edge cases. For a PoC with a single developer, Maven's verbosity is acceptable.

**Do NOT use maven-assembly-plugin** — it does not handle `META-INF/services` merging; multiple `java.sql.Driver` registrations from bundled dependencies will be clobbered, leaving only one driver registered.

**pom.xml key sections:**

```xml
<properties>
  <java.version>21</java.version>
  <maven.compiler.source>21</maven.compiler.source>
  <maven.compiler.target>21</maven.compiler.target>
</properties>

<dependencies>
  <!-- PostgreSQL JDBC driver — the real driver the proxy delegates to -->
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.4</version>
  </dependency>

  <!-- SQL parser for DDL rewriting -->
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
              <!-- CRITICAL: merges META-INF/services entries from pgJDBC + proxy driver -->
              <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
            </transformers>
            <filters>
              <filter>
                <!-- Remove signatures that will be invalidated by shading -->
                <artifact>*:*</artifact>
                <excludes>
                  <exclude>META-INF/*.SF</exclude>
                  <exclude>META-INF/*.DSA</exclude>
                  <exclude>META-INF/*.RSA</exclude>
                </excludes>
              </filter>
            </filters>
            <!-- Do NOT relocate org.postgresql — pgJDBC uses dynamic class loading internally -->
            <!-- Relocation would break driver class discovery -->
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

**Critical shading warning:** Do NOT relocate the `org.postgresql` package. pgJDBC loads some of its classes dynamically (e.g., SSL factory classes) using string-based class names. If you relocate the package, those string references break at runtime with `ClassNotFoundException`. Shading without relocation is safe here because the proxy JAR is the only JAR in Keycloak's providers directory adding pgJDBC — no version conflict exists.

**Build command:**
```bash
mvn package -DskipTests
# Output: target/crate-proxy-1.0-SNAPSHOT.jar (fat JAR ~5MB with pgJDBC + JSQLParser)
```

**Volume mount into Docker Compose:**
```yaml
volumes:
  - ./target/crate-proxy-1.0-SNAPSHOT.jar:/opt/keycloak/providers/crate-proxy.jar:ro
```

**maven-shade-plugin 3.6.2** is the current stable release (March 5, 2025). This version is compatible with Java 21 and Maven 3.9+.

**Confidence:** HIGH — verified against Apache Maven Shade Plugin release page and Maven Central.

---

## Complete Dependency Summary

| Artifact | Version | Purpose | Scope |
|----------|---------|---------|-------|
| `org.postgresql:postgresql` | `42.7.4` | Real JDBC driver; proxy delegates to this | Compile (shaded into fat JAR) |
| `com.github.jsqlparser:jsqlparser` | `5.3` | SQL AST parsing and DDL rewriting | Compile (shaded into fat JAR) |
| `org.apache.maven.plugins:maven-shade-plugin` | `3.6.2` | Fat JAR build with service merging | Maven plugin |
| `quay.io/keycloak/keycloak` | `latest` (26.5.2) | Keycloak server | Docker image |
| `crate` | `6.2` | CrateDB cluster nodes | Docker image |

**Java target: 21** (matches Keycloak's JVM; JSQLParser 5.3 requires 11+, pgJDBC 42.7.4 requires 8+; no conflicts)

---

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| SQL proxy framework | Hand-rolled delegation | P6Spy 3.9.x | Observability tool, not designed for rewriting; no clean hook for DDL transformation |
| SQL proxy framework | Hand-rolled delegation | datasource-proxy | Same as P6Spy; listener model, not a URL-claiming Driver implementation |
| SQL proxy framework | Hand-rolled delegation | CrateDB crate-jdbc 2.7.0 | Legacy, non-standard URL scheme, ClassNotFoundException reported with Keycloak (Discussion #21315) |
| SQL parser | JSQLParser 5.3 | Apache Calcite | Full query planner, 10+ MB deps, schema registration required — overkill for rewriting |
| SQL parser | JSQLParser 5.3 | ANTLR grammar | Requires writing and maintaining a PostgreSQL grammar file |
| SQL parser | JSQLParser 5.3 | Regex | Sufficient for BEGIN/COMMIT/ROLLBACK but not for nested DDL structures |
| Build tool | Maven + shade 3.6.2 | Gradle + shadow plugin | Both valid; Maven shade has better JDBC `META-INF/services` merge documentation |
| CrateDB JDBC connection | pgJDBC 42.7.4 | crate-jdbc 2.7.0 | Legacy driver; pgJDBC 42.7.x is officially recommended by CrateDB docs for 6.x |

---

## Sources

- CrateDB Docker Hub official image: https://hub.docker.com/_/crate (verified April 2026: version 6.2.6)
- CrateDB multi-node setup: https://cratedb.com/docs/crate/howtos/en/latest/clustering/multi-node-setup.html
- CrateDB PostgreSQL JDBC guide: https://cratedb.com/docs/guide/connect/java/postgresql-jdbc.html
- Keycloak Red Hat 26.0 DB config guide: https://docs.redhat.com/en/documentation/red_hat_build_of_keycloak/26.0/html/server_configuration_guide/db-
- Keycloak Discussion #11265 (Cloud SQL JDBC wrapper injection): https://github.com/keycloak/keycloak/discussions/11265
- Keycloak Discussion #21315 (ClassNotFoundException with custom driver): https://github.com/keycloak/keycloak/discussions/21315
- Keycloak Issue #26168 (KC_DB_DRIVER propagation fix in KC 24): https://github.com/keycloak/keycloak/issues/26168
- Keycloak Quarkus classloader behavior: https://github.com/keycloak/keycloak/discussions/10496
- JSQLParser 5.3 documentation: https://jsqlparser.github.io/JSqlParser/
- JSQLParser changelog (5.3 released May 17, 2025): https://jsqlparser.github.io/JSqlParser/changelog.html
- maven-shade-plugin 3.6.2 release (March 5, 2025): https://github.com/apache/maven-shade-plugin/releases
- maven-shade-plugin ServicesResourceTransformer: https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html
- pgJDBC 42.7.x releases: https://jdbc.postgresql.org/changelogs/2026-01-15-42/
- P6Spy driver URL pattern (reference only): https://p6spy.readthedocs.io/en/latest/install.html
- CrateDB SQL compatibility reference: https://cratedb.com/docs/crate/reference/en/latest/appendices/compatibility.html
