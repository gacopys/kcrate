.PHONY: up down restart clean logs stop1 stop2 stop3 start1 start2 start3 shell-crate test

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
# Node control (simulates node failure/recovery)
# ---------------------------------------------------------------------------

stop1:
	docker compose stop cratedb1

stop2:
	docker compose stop cratedb2

stop3:
	docker compose stop cratedb3

start1:
	docker compose start cratedb1

start2:
	docker compose start cratedb2

start3:
	docker compose start cratedb3

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

test:
	docker build -t crate-test ./test
	docker run --rm --network host crate-test

# ---------------------------------------------------------------------------
# Debug
# ---------------------------------------------------------------------------

shell-crate:
	psql "postgresql://crate@localhost:5432/doc"
