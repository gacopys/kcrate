.PHONY: up down restart clean logs shell-crate

CRATE_URL := http://localhost:4200/_sql

# ---------------------------------------------------------------------------
# Stack management
# ---------------------------------------------------------------------------

up:
	docker compose up -d

down:
	docker compose down

restart: down up

clean:
	docker compose down -v

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------

logs:
	docker compose logs -f

# ---------------------------------------------------------------------------
# Debug
# ---------------------------------------------------------------------------

shell-crate:
	psql "postgresql://crate@localhost:5432/doc"
