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
	@echo "  verify        Run all Phase 1 acceptance checks"
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
	@echo "--- builder exit code ---"
	@docker compose ps proxy-builder | grep -q "Exited (0)" \
		&& echo "PASS: proxy-builder exited 0" \
		|| echo "FAIL: proxy-builder did not exit cleanly"
	@echo "--- CrateDB node count ---"
	@NODES=$$(curl -sf -X POST $(CRATE_URL) \
		-H "Content-Type: application/json" \
		-d '{"stmt":"SELECT count(*) FROM sys.nodes"}' \
		| python3 -c "import sys,json; d=json.load(sys.stdin); print(d['rows'][0][0])" 2>/dev/null); \
	[ -n "$$NODES" ] \
		&& echo "PASS: $$NODES CrateDB node(s) responding" \
		|| echo "FAIL: CrateDB not responding on port 4200"
	@echo "--- Keycloak ClassNotFoundException ---"
	@FOUND=$$(docker compose logs keycloak 2>&1 | grep -ic "classnotfound"); \
	[ "$$FOUND" -eq 0 ] \
		&& echo "PASS: no ClassNotFoundException in Keycloak logs" \
		|| echo "FAIL: $$FOUND ClassNotFoundException line(s) in Keycloak logs"
	@echo "--- Provider load evidence ---"
	@docker compose logs keycloak 2>&1 | grep -i "provider\|crateproxy\|crate-proxy" | head -5 || echo "(no provider lines found)"

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
