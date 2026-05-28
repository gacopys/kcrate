# kcrate — Keycloak on CrateDB

**Run Keycloak backed by a 3-node CrateDB cluster — no forks, no patches, just Docker containers.**

kcrate proves that Keycloak (the industry-standard open-source IAM) can run on [CrateDB](https://crate.io/) — a distributed SQL database built for scale — using only a custom JDBC proxy driver that rewrites SQL on the fly. Drop in the proxy JAR, point Keycloak at the cluster, and it works.

## Why?

- **Scale out, not up.** CrateDB is a shared-nothing, multi-master distributed database. Unlike PostgreSQL's single-master replication, every CrateDB node can handle writes.
- **Leverage your CrateDB investment.** If your stack already runs on CrateDB for time-series or analytics, Keycloak can now share the same infrastructure.
- **Zero code changes.** Keycloak and CrateDB are used as-is from their official Docker images. The proxy JAR is volume-mounted in — no custom builds, no forks to maintain.

## How it works

```
┌─────────────────────────────────────────────────────────────────┐
│                        KEYCLOAK CONTAINER                       │
│  jdbc:postgresql://crate:5432/keycloak                          │
│         │                                                       │
│  ┌──────┴──────────────┐                                        │
│  │  CrateProxyDriver   │  JDBC proxy (fat JAR, ~5 MB)           │
│  │  - swallows BEGIN   │                                        │
│  │  - rewrites DDL     │                                        │
│  │  - remaps types     │                                        │
│  │  - injects replicas │                                        │
│  └──────┬──────────────┘                                        │
└─────────┼───────────────────────────────────────────────────────┘
          │ PostgreSQL wire protocol
    ┌─────┴─────────────────────────────────────┐
    │         Two-tier Caddy LB (×3 + 1)        │
    │  caddy-lb → caddy{1..3} → cratedb{1..3}  │
    └─────┬─────────────────────────────────────┘
          │
    ┌─────┴───────┬───────────────┬──────────────┐
    │  cratedb1   │   cratedb2    │   cratedb3   │
    │             ◄──────────────►               │
    └─────────────┘               └──────────────┘
        3-node, multi-master cluster
        Tolerates 1 node failure
```

Keycloak connects via PostgreSQL wire protocol. The proxy intercepts every SQL statement, rewrites CrateDB-incompatible constructs (transactions, foreign keys, sequences, unsupported types, partial indexes, `SELECT ... FOR UPDATE`), and forwards the transformed query to the cluster through a redundant Caddy load-balancing tier.

## SQL rewriting at a glance

| What the proxy does | Why |
|---------------------|-----|
| Swallows `BEGIN` / `COMMIT` / `ROLLBACK` | CrateDB has no transactions |
| Strips `SELECT ... FOR UPDATE` | Liquibase lock service |
| Drops `FOREIGN KEY` from `CREATE TABLE` | Not supported |
| Drops `UNIQUE` constraints | Not enforced |
| Remaps types (`CLOB`→`TEXT`, `TINYINT`→`SMALLINT`, etc.) | CrateDB type system |
| Strips unsupported `ALTER TABLE` ops | FK, NOT NULL, type changes |
| Strips PostgreSQL casts (`::varchar`) from `CREATE INDEX` | Parser limitation |
| Strips `WHERE` from partial indexes | Not supported |
| Injects `WITH (number_of_replicas = '1')` on every `CREATE TABLE` | Cluster replication |
| Swallows `CREATE` / `ALTER SEQUENCE` | No sequence support |
| Throws on `nextval()` | Fails fast on unsupported calls |

## Quick start

```bash
# Start the full stack
make up

# Run the smoke test
make test

# Check logs
make logs

# Simulate a node failure
make stop1
make start1

# Tear everything down (preserves volumes)
make down

# Full teardown including volumes
make clean
```

The CrateDB HTTP API and Admin UI are available at `http://localhost:4200`. The PostgreSQL wire protocol is available at `localhost:5432`.

## Architecture

- **3-node CrateDB 6.2 cluster** — multi-master, quorum-based (tolerates 1 node loss)
- **Two-tier Caddy load balancing** — top-level LB (`caddy-lb`) → 3 mid-tier LBs → 3 CrateDB nodes; passive health checks at every tier
- **Custom JDBC proxy** — Java 21, JSQLParser 5.3, pgJDBC 42.7.4, packaged as a shaded fat JAR

See [topology.md](topology.md) for the full architecture diagram and failure-mode table.

## Project structure

```
├── caddy/              # Custom Caddy build with layer4 module (Dockerfile + Caddyfile)
├── caddy-lb/           # Top-level load balancer Caddyfile
├── proxy/              # JDBC proxy source (Java/Maven) — in git history
├── test/               # Integration smoke test (Python + psycopg2)
├── docker-compose.yml  # Full stack orchestration
├── Makefile            # up/down/logs/test/node-control targets
└── topology.md         # Architecture diagram and failure tolerance
```

## Requirements

- Docker & Docker Compose
- `make`
- `psql` (optional, for `make shell-crate`)

The JDBC proxy requires Java 21 + Maven 3.9+ to build.

## Roadmap

- **Phase 1** — Infrastructure: Docker Compose, CrateDB cluster, Caddy load balancing
- **Phase 2** — JDBC proxy: 11 SQL rewriting rules, integration tests
- **Phase 3** — Keycloak Liquibase schema migration and tuning
- **Phase 4** — Functional validation (admin UI, realms, auth, sessions) and cluster resilience

## Contributing

Contributions welcome. The proxy's SQL rewriter (`SqlRewriter.java`) is the core of the project — if you find a Keycloak SQL statement that breaks CrateDB, open an issue or add a rewrite rule.

## License

MIT
