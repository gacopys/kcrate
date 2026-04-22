# Architecture Patterns

**Project:** Keycloak + CrateDB JDBC Proxy PoC
**Researched:** 2026-04-22
**Confidence:** MEDIUM-HIGH (CrateDB cluster mechanics HIGH, Keycloak provider loading MEDIUM, SQL compatibility surface HIGH via community forum confirmation)

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  Docker Compose Network                                         │
│                                                                 │
│  ┌──────────────┐     JDBC (PostgreSQL wire)                   │
│  │  Keycloak    │────────────────────────┐                     │
│  │  (official)  │                        ▼                     │
│  │              │              ┌──────────────────┐            │
│  │  /providers/ │              │  HAProxy / nginx  │            │
│  │  proxy.jar   │              │  (optional LB)    │            │
│  └──────────────┘              └────────┬─────────┘            │
│                                         │  round-robin          │
│                          ┌──────────────┼──────────────┐       │
│                          ▼              ▼              ▼        │
│                    ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│                    │ crate01  │  │ crate02  │  │ crate03  │   │
│                    │ :5432    │  │ :5432    │  │ :5432    │   │
│                    │ :4200    │  │ :4200    │  │ :4200    │   │
│                    │ :4300    │  │ :4300    │  │ :4300    │   │
│                    └────┬─────┘  └─────┬────┘  └────┬─────┘   │
│                         └──────────────┴─────────────┘         │
│                              transport layer :4300              │
└─────────────────────────────────────────────────────────────────┘
```

The proxy JAR is not a separate process. It runs inside Keycloak's JVM and intercepts calls in-process before they reach the network layer. Keycloak believes it is talking to PostgreSQL. The proxy rewrites and forwards to CrateDB.

---

## Component 1: JDBC Proxy Driver

### Internal Structure

The proxy is a single self-contained JAR with no external runtime dependencies (aside from wrapping a downstream PostgreSQL JDBC driver). It implements the JDBC SPI and delegates everything to the real `pgjdbc` driver after rewriting SQL.

```
proxy.jar
├── META-INF/services/java.sql.Driver        ← SPI registration file
│   └── (contains: com.example.proxy.ProxyDriver)
├── com/example/proxy/
│   ├── ProxyDriver.java       ← implements java.sql.Driver
│   ├── ProxyConnection.java   ← implements java.sql.Connection
│   ├── ProxyStatement.java    ← implements java.sql.Statement
│   ├── ProxyPreparedStatement.java  ← implements java.sql.PreparedStatement
│   └── SqlRewriter.java       ← stateless SQL transformation pipeline
└── (shade: org.postgresql.Driver + transitive deps)
```

The downstream `pgjdbc` JAR is shaded (embedded) inside `proxy.jar` with a relocated package prefix (e.g., `shaded.org.postgresql`). This avoids classloader conflicts when Keycloak's own classpath also contains a postgresql driver.

### Driver Registration

JDBC 4.0 SPI: `DriverManager` auto-discovers drivers by reading `META-INF/services/java.sql.Driver` from every JAR on the classpath. When Keycloak drops the proxy JAR into `/opt/keycloak/providers/` and runs `kc.sh build`, Quarkus augmentation indexes the providers directory and the driver becomes available.

**Confidence: HIGH.** This is the documented JDBC 4.0 SPI mechanism (Java SE 6+). CrateDB's own JDBC driver uses the same approach (confirmed via pgjdbc fork at https://github.com/crate/pgjdbc).

**Critical caveat:** Keycloak is Quarkus-based. Quarkus uses a closed-world assumption at build time. The proxy JAR must be present at `kc.sh build` time, not just at runtime. Volume-mounting the JAR and then running `kc.sh start --optimized` (without a prior build) will fail because the augmentation index does not include the driver.

**Resolution for PoC:** The Docker Compose `keycloak` service runs `kc.sh start-dev` (which auto-builds) or a Dockerfile-based approach that bakes the JAR at image build time. For a pure volume-mount approach, use `kc.sh start` (not `--optimized`) which triggers auto-augmentation at startup at the cost of ~30s boot time.

### URL Routing

The proxy driver registers for a custom URL prefix, e.g., `jdbc:crateproxy://`. Keycloak is configured with:
```
KC_DB=postgres
KC_DB_DRIVER=com.example.proxy.ProxyDriver
KC_DB_URL=jdbc:crateproxy://crate01:5432,crate02:5432,crate03:5432/crate
```

`ProxyDriver.connect()` accepts `jdbc:crateproxy://` URLs, strips the prefix, rewrites to `jdbc:postgresql://`, and delegates to the shaded `pgjdbc` driver.

### Connection Delegation Pattern

Every JDBC interface is wrapped with a delegating proxy. The delegation is thin: every method call forwards to the underlying real object, except the methods that handle SQL string arguments.

```java
// Pattern: wrap every factory method, intercept SQL strings
class ProxyConnection implements java.sql.Connection {
    private final Connection real;

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        String rewritten = SqlRewriter.rewrite(sql);
        return new ProxyPreparedStatement(real.prepareStatement(rewritten));
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new ProxyStatement(real.createStatement());
    }

    // All other methods: delegate directly to real
    @Override
    public void commit() throws SQLException { /* no-op */ }

    @Override
    public void rollback() throws SQLException { /* no-op */ }

    @Override
    public void setAutoCommit(boolean b) throws SQLException { /* no-op, always auto-commit */ }
}
```

### Statement Interception Layer: Where to Intercept

Intercept at **both** `prepareStatement(String sql)` and `Statement.execute(String sql)` / `Statement.executeQuery(String sql)` / `Statement.executeUpdate(String sql)`.

Keycloak/Liquibase uses both:
- `PreparedStatement` for parameterized DML (INSERT, UPDATE, DELETE, SELECT)
- `Statement.execute()` for DDL (CREATE TABLE, ALTER TABLE, DROP TABLE) and Liquibase migration SQL

Do NOT intercept only one path — both are exercised.

**Intercept order of priority:**
1. `Connection.prepareStatement(String)` — catches all parameterized SQL before compilation
2. `Statement.execute(String)` — catches raw DDL and Liquibase batch statements
3. `Statement.executeQuery(String)` — catches raw SELECT (rare, but exists)
4. `Statement.executeUpdate(String)` — catches raw DML updates

### SQL Rewriting Pipeline

```
raw SQL string
    │
    ▼
┌─────────────────────────────────────┐
│ 1. Transaction NOP filter           │  BEGIN/COMMIT/ROLLBACK/SAVEPOINT
│    → return empty string or no-op   │  → swallow entirely, return null
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 2. DDL strip filter                 │  FOREIGN KEY / REFERENCES clauses
│    → regex remove FK constraint     │  ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY
│      definitions from CREATE TABLE  │  → strip clause, keep rest of statement
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 3. Type remapping                   │  bytea → BYTE
│    → string replace on type names   │  uuid  → TEXT (or store as TEXT)
│      in column definitions          │  jsonb → OBJECT or TEXT
│                                     │  serial / bigserial → INTEGER / BIGINT
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 4. ALTER TABLE compatibility        │  ALTER TABLE ... ALTER COLUMN → strip/no-op
│    → detect unsupported ALTER ops   │  ALTER TABLE ... ADD COLUMN   → pass through
│      and either no-op or rewrite    │  (CrateDB supports ADD COLUMN)
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 5. Sequence / ID generation         │  NEXTVAL('seq') → workaround
│    → replace sequence calls with    │  Or: pre-create sequences if CrateDB 5.x
│      alternative ID strategies      │  supports them (verify at test time)
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 6. pg_catalog / system table        │  SELECT ... FROM pg_catalog.pg_class
│    strip filter                     │  → return empty ResultSet or no-op
│    (Hibernate schema detection)     │
└─────────────────────────────────────┘
    │
    ▼
rewritten SQL → forward to real pgjdbc driver
```

**Implementation note on "swallow":** When a transaction command (BEGIN, COMMIT, ROLLBACK) is intercepted, the proxy must not call the underlying driver at all. Return without delegating. For `execute()` calls, return `false` (no ResultSet). For `executeUpdate()`, return 0. For `prepareStatement()`, return a no-op PreparedStatement implementation whose `execute()` also returns false.

### Error Handling

When the underlying CrateDB rejects a statement the proxy did not rewrite:
1. Catch `SQLException` from the real driver.
2. Log the original SQL, the rewritten SQL, and the error at WARN level.
3. Re-throw as-is — do not suppress unknown errors. Surfacing them during testing is the mechanism for discovering new rewrite rules.

Do not attempt to catch-and-retry. Fail fast, fix the rewriter, iterate.

---

## Component 2: CrateDB 3-Node Cluster

### Node Discovery

CrateDB uses Elasticsearch-style unicast discovery. Each node is told the addresses of its peers via `discovery.seed_hosts`. All three nodes are master-eligible (required for a 3-node cluster to hold quorum elections).

**Minimum required settings per node:**
```
cluster.name=keycloak-cluster
node.name=crate01                          # unique per node
discovery.seed_hosts=crate01,crate02,crate03
cluster.initial_master_nodes=crate01,crate02,crate03
network.host=0.0.0.0
```

**Gateway settings for clean startup (all 3 must be up before recovery starts):**
```
gateway.expected_data_nodes=3
gateway.recover_after_data_nodes=2
```

`recover_after_data_nodes=2` means the cluster starts recovery once 2 of 3 nodes are present, but waits up to `gateway.recover_after_time` for the third. This prevents split-brain on restarts without requiring all 3 to be up simultaneously.

**Confidence: HIGH.** Sourced from CrateDB official multi-node setup documentation.

### Docker Compose Network Topology

All CrateDB nodes and Keycloak share one Docker bridge network. Service DNS names serve as `discovery.seed_hosts` values. Docker's embedded DNS resolves `crate01`, `crate02`, `crate03` to container IPs.

Ports exposed:
- `:5432` — PostgreSQL wire protocol (Keycloak connects here)
- `:4200` — HTTP admin UI (for debugging during PoC)
- `:4300` — inter-node transport (peer discovery and replication)

### Load Balancing

**For PoC: connect Keycloak to all nodes via a single HAProxy service.**

Keycloak's JDBC connection pool (Agroal) opens connections to a single JDBC URL. To distribute across 3 nodes, put HAProxy in front:

```
keycloak → jdbc:crateproxy://haproxy:5432/ → HAProxy round-robin → crate01/crate02/crate03
```

HAProxy configuration (stream mode, TCP passthrough — HAProxy does not speak PostgreSQL wire protocol):
```
frontend crate_psql
    bind *:5432
    default_backend crate_nodes
    mode tcp

backend crate_nodes
    mode tcp
    balance roundrobin
    option tcp-check
    server crate01 crate01:5432 check
    server crate02 crate02:5432 check
    server crate03 crate03:5432 check
```

**Alternative (simpler for PoC):** Point Keycloak directly at `crate01:5432` and skip HAProxy entirely. CrateDB is shared-nothing — `crate01` can handle all writes and will replicate to the others. This works for the PoC but loses the "connect to cluster" aspect. Use HAProxy if validating failover.

**Confidence: MEDIUM.** HAProxy round-robin with TCP check is a verified pattern from the CrateDB community (statusengine.org tutorial). The PostgreSQL wire protocol is stateful (connection per client), so HAProxy must operate in TCP (stream) mode, not HTTP mode.

### Consistency Guarantees

CrateDB does NOT support multi-statement transactions. Each statement auto-commits.

Write path:
1. Statement hits the node Keycloak is connected to (the coordinating node).
2. Coordinating node routes the write to the primary shard owner for that table/row.
3. Primary shard executes the write.
4. Primary synchronously replicates to all configured replicas before returning success.
5. Response returns to Keycloak.

**Replication default:** 0 replicas per shard (no HA). For PoC with a 3-node cluster, set at least 1 replica:
```sql
CREATE TABLE ... WITH (number_of_replicas = '1');
```
Keycloak's Liquibase DDL does not set `number_of_replicas`. The proxy must either intercept `CREATE TABLE` and append `WITH (number_of_replicas = '1-2')`, or set it as a session/cluster default.

**Read consistency:** CrateDB can serve reads from primary or replica shards. With 1 replica and `READ UNCOMMITTED`-equivalent semantics (no isolation levels), reads are consistent in practice for Keycloak's use case. Dirty reads are possible in the narrow window between a primary write and master heartbeat (default: 1 second).

**If a node dies mid-write:** The write fails with an error. There is no rollback — Keycloak must retry. CrateDB does not guarantee atomicity across multiple rows unless they are on the same shard. For Keycloak's Liquibase migrations (which are idempotent by design), this is acceptable for PoC.

**Confidence: HIGH.** Sourced from CrateDB storage/consistency reference documentation.

---

## Component 3: Data Flow — Keycloak Write

End-to-end path for a single Keycloak entity write (e.g., user creation):

```
1. Keycloak application code
   └─ calls EntityManager / Hibernate (JPA)

2. Hibernate generates SQL
   └─ e.g.: INSERT INTO user_entity (id, username, ...) VALUES (?, ?, ...)

3. Hibernate calls Connection.prepareStatement(sql)
   └─ ProxyConnection.prepareStatement() intercepts

4. SqlRewriter.rewrite(sql)
   └─ Stage 1: not a transaction command → pass
   └─ Stage 2: no FK constraints in INSERT → pass
   └─ Stage 3: no type literals in INSERT → pass
   └─ Stage 4-6: not DDL → pass
   └─ Returns SQL unchanged (INSERT is usually compatible)

5. real pgjdbc PreparedStatement.executeUpdate()
   └─ sends to CrateDB node (via HAProxy or direct)

6. CrateDB coordinating node
   └─ routes to primary shard for user_entity table
   └─ executes write on primary shard
   └─ synchronously replicates to 1 replica shard on another node

7. Success response → back through pgjdbc → ProxyPreparedStatement → Hibernate → Keycloak
```

For DDL (Liquibase migration, runs once at startup):

```
1. Liquibase reads changelog, generates CREATE TABLE statement
2. Calls Statement.execute(sql)
3. ProxyStatement.execute() intercepts
4. SqlRewriter.rewrite(sql):
   └─ Strip FOREIGN KEY clauses
   └─ Remap column types (bytea→BYTE, uuid→TEXT, etc.)
   └─ Append WITH (number_of_replicas='1') if CREATE TABLE
5. Forward rewritten DDL to CrateDB
6. CrateDB creates table, distributes shards across 3 nodes
```

---

## Component 4: Build & Delivery Pipeline

### Recommended Approach: Local Build + Volume Mount

Build the proxy JAR locally (or in a Docker builder container) and mount the artifact into Keycloak at startup. This is the simplest approach for a PoC and avoids building a custom Keycloak image.

```
┌─────────────────────────────────────────────┐
│  Host filesystem                            │
│                                             │
│  ./proxy/                                   │
│    src/...          ← Java source           │
│    pom.xml          ← Maven build           │
│    target/          ← built artifacts       │
│      proxy-1.0.jar  ← shaded JAR            │
│                                             │
│  docker-compose.yml                         │
│    keycloak:                                │
│      volumes:                               │
│        - ./proxy/target/proxy-1.0.jar:      │
│          /opt/keycloak/providers/proxy.jar  │
└─────────────────────────────────────────────┘
```

**Keycloak startup mode:** Use `command: start-dev` for the PoC. `start-dev` disables the optimized build requirement and forces auto-augmentation on every boot. This means:
- Keycloak rebuilds its augmentation index every time it starts (~30-60 seconds)
- The proxy JAR is discovered fresh via SPI scan
- No need for a baked image or separate `kc.sh build` step

**Confidence: MEDIUM.** Confirmed via Keycloak Quarkus provider documentation and community discussions. The `start-dev` auto-augmentation behavior is documented. The limitation is that `start-dev` is not suitable for production — acceptable for a PoC.

### Alternative: Docker Compose Builder Service

If a local Maven/JDK install is not desirable, add a builder service to `docker-compose.yml`:

```yaml
services:
  proxy-builder:
    image: maven:3.9-eclipse-temurin-21
    volumes:
      - ./proxy:/workspace
      - proxy-jar:/output
    working_dir: /workspace
    command: >
      sh -c "mvn -q package -DskipTests &&
             cp target/proxy-*.jar /output/proxy.jar"

  keycloak:
    depends_on:
      proxy-builder:
        condition: service_completed_successfully
    volumes:
      - proxy-jar:/opt/keycloak/providers
    ...

volumes:
  proxy-jar:
```

This keeps the build inside Docker, removes the host JDK dependency, and guarantees the Java version matches. The named volume `proxy-jar` is the handoff point between the builder and Keycloak.

**Build order for this approach:**
1. `proxy-builder` runs Maven, exits 0
2. `crate01`, `crate02`, `crate03` start in parallel
3. `keycloak` starts after `proxy-builder` completes and waits for at least one CrateDB node to be healthy

### Maven Build Configuration for the Proxy JAR

The proxy must be a fat/shaded JAR (all dependencies embedded). Use `maven-shade-plugin` to shade and relocate `pgjdbc`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <configuration>
    <relocations>
      <relocation>
        <pattern>org.postgresql</pattern>
        <shadedPattern>shaded.org.postgresql</shadedPattern>
      </relocation>
    </relocations>
  </configuration>
</plugin>
```

Without relocation, if Keycloak's own classpath contains a `pgjdbc` version, classloader conflicts will cause `ClassCastException` or wrong driver being loaded.

---

## Component Boundaries

| Component | Responsibility | Communicates With | Technology |
|-----------|---------------|-------------------|------------|
| Keycloak | IAM server, schema migration (Liquibase), JPA persistence | JDBC → ProxyDriver | Official Docker image, Java 21 JVM |
| ProxyDriver (in-process) | SQL interception, rewriting, type remapping, transaction suppression | Keycloak JVM (caller), pgjdbc (downstream) | Java 21, in-JAR |
| pgjdbc (shaded) | PostgreSQL wire protocol, actual network I/O | CrateDB via TCP :5432 | Shaded inside proxy JAR |
| HAProxy | TCP load balancing across CrateDB nodes | crate01/02/03 :5432 | Official HAProxy image |
| crate01/02/03 | SQL storage, distributed query execution, shard replication | Each other via :4300, Keycloak/HAProxy via :5432 | Official CrateDB image |

---

## Suggested Build Order

1. **CrateDB nodes** — start all three simultaneously; they discover each other via `discovery.seed_hosts` and elect a master before Keycloak connects
2. **HAProxy** — starts after CrateDB nodes have health checks passing; sits in front of the cluster
3. **Proxy JAR builder** (if using builder service) — runs in parallel with CrateDB startup; must complete before Keycloak starts
4. **Keycloak** — starts last; runs Liquibase migrations on first boot, which exercises the full SQL rewriting pipeline

`docker-compose.yml` dependency chain:
```
proxy-builder ──┐
                ├──► keycloak
crate01,02,03 ──┤
       └── haproxy ──┘
```

Use `healthcheck` on CrateDB nodes (HTTP GET `/_cluster/health` on port 4200) and `depends_on: condition: service_healthy` for Keycloak to avoid connection failures during cluster formation.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Intercepting Only prepareStatement
**What goes wrong:** Liquibase uses `Statement.execute()` for DDL. If only `PreparedStatement` is proxied, CREATE TABLE statements with FK constraints reach CrateDB unmodified and fail.
**Instead:** Proxy both `Statement` and `PreparedStatement`.

### Anti-Pattern 2: Volume-Mounting JAR with --optimized Startup
**What goes wrong:** `kc.sh start --optimized` uses a pre-built augmentation index. A JAR mounted after the build step is not in the index. The driver is not found. Keycloak fails to start.
**Instead:** Use `start-dev` (auto-augmentation) or build the image with the JAR already present.

### Anti-Pattern 3: Not Shading pgjdbc
**What goes wrong:** Keycloak's own `pgjdbc` version is on the classpath. If two versions coexist without relocation, `DriverManager` may pick the wrong one, or class casting fails when the proxy casts returned objects to its own imported types.
**Instead:** Shade and relocate `org.postgresql` to `shaded.org.postgresql` inside the proxy JAR.

### Anti-Pattern 4: Setting number_of_replicas=0 (default)
**What goes wrong:** CrateDB's default is 0 replicas per shard (no replication). In a 3-node cluster, all shards live on one node. Losing that node loses the data. This defeats the purpose of a 3-node cluster.
**Instead:** Intercept `CREATE TABLE` and append `WITH (number_of_replicas = '1')`, or set a cluster-level default.

### Anti-Pattern 5: Parsing SQL with String.contains() / simple regex
**What goes wrong:** SQL strings are not regular. A regex for `FOREIGN KEY` will miss quoted identifiers, comments, multiline strings, or alternative whitespace. It will also incorrectly match `FOREIGN KEY` inside a comment or string literal.
**Instead:** For the PoC, use tested regex patterns that cover Keycloak's actual Liquibase output (which is mechanically generated and predictable). Log every rewrite. Extend rules as failures surface. Do not attempt a full SQL parser — the cost is not justified for a PoC.

---

## Open Questions / Flags for Phase-Specific Research

| Topic | Question | Risk |
|-------|----------|------|
| Sequence support | Does CrateDB 5.x support `CREATE SEQUENCE` / `NEXTVAL()`? If not, Keycloak's ID generation needs a proxy-level workaround (UUID substitution or table-based sequence emulation) | HIGH — blocks schema migration |
| pg_catalog queries | Does Liquibase or Hibernate query `pg_catalog.*` tables at startup? CrateDB does not have `pg_catalog` — these queries must be intercepted and return empty ResultSets | HIGH — blocks startup |
| `information_schema` coverage | Liquibase uses `information_schema.tables` to detect existing tables. CrateDB has a partial `information_schema` — verify which columns Liquibase queries are available | MEDIUM |
| Keycloak 26+ DB driver loading | Confirm `KC_DB_DRIVER` still works in Keycloak 26.x (latest as of 2026-04-22) — the GitHub issue #9133 reported it was broken in 15.x but it appears fixed in later versions | MEDIUM |
| CrateDB `COPY TO` / bulk write | Liquibase may use batch inserts; verify CrateDB handles `executeBatch()` the same as individual `executeUpdate()` calls via pgjdbc | LOW |

---

## Sources

- CrateDB multi-node setup: https://cratedb.com/docs/crate/howtos/en/latest/clustering/multi-node-setup.html
- CrateDB storage and consistency: https://cratedb.com/docs/crate/reference/en/5.8/concepts/storage-consistency.html
- CrateDB replication: https://cratedb.com/storage/replication
- CrateDB Docker guide: https://cratedb.com/docs/guide/install/container/docker.html
- CrateDB cluster-wide settings (gateway.*): https://cratedb.com/docs/crate/reference/en/latest/config/cluster.html
- CrateDB session settings (search_path, application_name): https://cratedb.com/docs/crate/reference/en/latest/config/session.html
- CrateDB SET statement: https://cratedb.com/docs/crate/reference/en/latest/sql/statements/set.html
- CrateDB transaction support (BEGIN/COMMIT/ROLLBACK ignored): https://cratedb.com/docs/crate/reference/en/latest/sql/statements/begin.html
- CrateDB PostgreSQL wire protocol: https://cratedb.com/docs/crate/reference/en/latest/interfaces/postgres.html
- CrateDB JDBC (pgjdbc fork): https://github.com/crate/pgjdbc
- CrateDB community: Keycloak integration discussion: https://community.cratedb.com/t/using-crate-jdbc-in-dockerfile-for-keycloak/1524
- Keycloak database configuration (KC_DB_DRIVER): https://www.keycloak.org/server/db
- Keycloak container guide (providers directory): https://www.keycloak.org/server/containers
- Keycloak custom driver discussion (aws-wrapper pattern): https://github.com/keycloak/keycloak/discussions/15292
- Keycloak Quarkus build-time provider loading: https://keycloak.ch/keycloak-tutorials/tutorial-custom-keycloak/
- JDBC 4.0 SPI registration (META-INF/services): https://baeldung.com/java-jdbc-loading-drivers
- pgjdbc META-INF/services reference: https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/resources/META-INF/services/java.sql.Driver
- JDBC proxy / classloader issues in containers: https://northcoder.com/post/class-loaders-service-providers-and/
- HAProxy for CrateDB cluster: https://statusengine.org/tutorials/cratedb-cluster-ubuntu/
- Oracle JDBC proxy pattern (SQL interception reference): https://github.com/averemee-si/orajdbc-proxy
- datasource-proxy (statement interception reference): https://jdbc-observations.github.io/datasource-proxy/docs/snapshot/user-guide/
