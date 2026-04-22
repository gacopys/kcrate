.PHONY: help up down restart logs build rebuild clean verify \
        status nodes kc-logs kc-errors builder-logs proxy-check shell-crate

CRATE_URL := http://localhost:4200/_sql
KC_URL    := http://localhost:8080

help:
	@echo "Stack management"
	@echo "  up            Start all services (detached)"
	@echo "  down          Stop and remove containers"
	@echo "  restart       down + up"
	@echo "  rebuild       Force-rebuild proxy JAR (removes proxy-jar volume), then up"
	@echo "  clean         down + remove all volumes"
	@echo ""
	@echo "Status / verification"
	@echo "  status        Show container states"
	@echo "  verify        Run all Phase 2 acceptance checks"
	@echo "  nodes         Query CrateDB cluster node count"
	@echo "  proxy-check   Confirm KC_DB_DRIVER and proxy-jar volume are wired"
	@echo ""
	@echo "Logs"
	@echo "  logs          Tail all service logs"
	@echo "  builder-logs  Show proxy-builder output (Maven build)"
	@echo "  kc-logs       Tail Keycloak logs"
	@echo "  kc-errors     Filter Keycloak logs for ERROR / Exception lines"
	@echo ""
	@echo "Debug"
	@echo "  shell-crate   Open psql session on cratedb1 (requires psql)"

# ---------------------------------------------------------------------------
# Stack management
# ---------------------------------------------------------------------------

up:
	@echo "==> [1/2] Building proxy JAR (foreground — errors visible)"
	docker compose up proxy-builder
	@docker compose ps -a proxy-builder | grep -q "Exited (0)" \
		|| (echo "ERROR: proxy-builder failed — run 'make builder-logs'"; exit 1)
	@echo "==> [2/2] Starting CrateDB cluster + Keycloak"
	docker compose up -d cratedb1 cratedb2 cratedb3 keycloak

down:
	docker compose down

restart: down up

rebuild:
	docker compose down
	docker volume rm crate_proxy-jar 2>/dev/null || true
	@echo "==> [1/2] Building proxy JAR"
	docker compose up proxy-builder
	@docker compose ps -a proxy-builder | grep -q "Exited (0)" \
		|| (echo "ERROR: proxy-builder failed — run 'make builder-logs'"; exit 1)
	@echo "==> [2/2] Starting CrateDB cluster + Keycloak"
	docker compose up -d cratedb1 cratedb2 cratedb3 keycloak

clean:
	docker compose down -v

# ---------------------------------------------------------------------------
# Status / verification
# ---------------------------------------------------------------------------

status:
	docker compose ps

verify:
	@trap 'echo ""; echo "--- tearing down stack ---"; docker compose down 2>/dev/null || true' EXIT; \
	echo "=== Phase 2 verification: JDBC Proxy Implementation ==="; \
	echo ""; \
	echo "--- starting stack ---"; \
	docker compose up proxy-builder; \
	docker compose ps -a proxy-builder | grep -q "Exited (0)" \
	  || { echo "ERROR: proxy-builder failed — aborting"; exit 1; }; \
	docker compose up -d cratedb1 cratedb2 cratedb3 keycloak; \
	echo "--- waiting for Keycloak to be ready (up to 120s) ---"; \
	i=0; \
	while [ $$i -lt 40 ]; do \
	  docker compose logs keycloak 2>/dev/null | grep -qE "started in|Listening on:" && break; \
	  i=$$((i+1)); \
	  echo "  $${i}x3s elapsed..."; \
	  sleep 3; \
	done; \
	docker compose logs keycloak 2>/dev/null | grep -qE "started in|Listening on:" \
	  || { echo "FAIL: Keycloak not ready after 120s — check: make kc-errors"; exit 1; }; \
	echo "PASS: stack ready"; \
	echo ""; \
	echo "--- [1/6] proxy-builder exited 0 ---"; \
	docker compose ps -a proxy-builder | grep -q "Exited (0)" \
	  && echo "PASS: proxy-builder exited 0" \
	  || echo "FAIL: proxy-builder did not exit cleanly"; \
	echo "--- [2/6] proxy JAR in volume (>1 MB, shaded) ---"; \
	docker run --rm -v crate_proxy-jar:/vol alpine \
	  sh -c 'S=$$(stat -c%s /vol/crate-proxy.jar 2>/dev/null || echo 0); \
	         [ "$$S" -gt 1048576 ] \
	           && echo "PASS: crate-proxy.jar present ($$S bytes)" \
	           || echo "FAIL: missing or <1 MB ($$S bytes)"'; \
	echo "--- [3/6] all 5 proxy classes in JAR ---"; \
	docker run --rm -v crate_proxy-jar:/vol alpine \
	  sh -c 'apk add -q --no-cache unzip >/dev/null 2>&1; \
	         C=$$(unzip -l /vol/crate-proxy.jar 2>/dev/null \
	                | grep -cE "crateproxy/(CrateProxyDriver|CrateProxyConnection|CrateProxyStatement|CrateProxyPreparedStatement|SqlRewriter)\.class"); \
	         [ "$$C" -eq 5 ] \
	           && echo "PASS: all 5 proxy classes present" \
	           || echo "FAIL: found $$C/5 classes"'; \
	echo "--- [4/6] SqlRewriterTest: all 9 rewrite rules ---"; \
	docker compose exec -T keycloak \
	  java -cp /opt/keycloak/providers/crate-proxy.jar \
	  com.example.crateproxy.SqlRewriterTest 2>&1 \
	  | grep -qF "ALL PROXY REWRITE TESTS PASSED" \
	  && echo "PASS: ALL PROXY REWRITE TESTS PASSED" \
	  || echo "FAIL: rewrite tests failed  →  run: docker compose exec keycloak java -cp /opt/keycloak/providers/crate-proxy.jar com.example.crateproxy.SqlRewriterTest"; \
	echo "--- [5/6] CrateDB 3-node cluster ---"; \
	NODES=$$(curl -sf -X POST $(CRATE_URL) \
	  -H "Content-Type: application/json" \
	  -d '{"stmt":"SELECT count(*) FROM sys.nodes"}' \
	  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['rows'][0][0])" 2>/dev/null); \
	[ "$$NODES" = "3" ] \
	  && echo "PASS: 3 CrateDB nodes in cluster" \
	  || echo "FAIL: expected 3 nodes, got '$$NODES'"; \
	echo "--- [6/6] no ClassNotFoundException in Keycloak logs ---"; \
	FOUND=$$(docker compose logs keycloak 2>&1 | grep -ic "classnotfound"); \
	[ "$$FOUND" -eq 0 ] \
	  && echo "PASS: no ClassNotFoundException in Keycloak logs" \
	  || echo "FAIL: $$FOUND ClassNotFoundException line(s) found"; \
	echo ""; \
	echo "--- proxy rewrite activity (last 5 [CRATE PROXY] lines) ---"; \
	docker compose logs keycloak 2>&1 | grep "\[CRATE PROXY\]" | tail -5 \
	  || echo "(none yet — Liquibase migration may not have run)"; \
	echo ""

nodes:
	@curl -sf -X POST $(CRATE_URL) \
		-H "Content-Type: application/json" \
		-d '{"stmt":"SELECT name, hostname, version['"'"'number'"'"'] FROM sys.nodes"}' \
		| python3 -m json.tool 2>/dev/null \
		|| echo "CrateDB not reachable at $(CRATE_URL)"

proxy-check:
	@echo "--- KC_DB_DRIVER in compose ---"
	@grep "KC_DB_DRIVER" docker-compose.yml
	@echo "--- proxy-jar volume contents ---"
	@docker run --rm -v crate_proxy-jar:/vol alpine ls -lh /vol 2>/dev/null \
		|| echo "Volume not found (stack not started or volume name differs)"

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------

logs:
	docker compose logs -f

builder-logs:
	docker compose logs proxy-builder

kc-logs:
	docker compose logs -f keycloak

kc-errors:
	docker compose logs keycloak 2>&1 | grep -iE "error|exception|classnotfound|fatal" | head -50

# ---------------------------------------------------------------------------
# Debug
# ---------------------------------------------------------------------------

shell-crate:
	psql "postgresql://crate@localhost:5432/doc"
