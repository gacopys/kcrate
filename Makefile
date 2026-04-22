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
# Logs
# ---------------------------------------------------------------------------

logs:
	docker compose logs -f

builder-logs:
	docker compose logs proxy-builder

# ---------------------------------------------------------------------------
# Debug
# ---------------------------------------------------------------------------

shell-crate:
	psql "postgresql://crate@localhost:5432/doc"
