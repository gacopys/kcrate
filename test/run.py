import psycopg2

DSN = "postgresql://crate@localhost:5432/doc"

def run(cur, label, sql, params=None):
    cur.execute(sql, params)
    result = cur.fetchall() if cur.description else None
    print(f"[OK] {label}" + (f": {result}" if result else ""))

conn = psycopg2.connect(DSN)
conn.autocommit = True
cur = conn.cursor()

run(cur, "version",       "SELECT version()")
run(cur, "create table",  "CREATE TABLE IF NOT EXISTS _test (id INT, name TEXT) WITH (number_of_replicas = 0)")
run(cur, "insert rows",   "INSERT INTO _test VALUES (1, 'hello'), (2, 'world'), (3, 'crate')")
run(cur, "refresh",       "REFRESH TABLE _test")
run(cur, "select all",    "SELECT id, name FROM _test ORDER BY id")
run(cur, "count",         "SELECT count(*) FROM _test")
# run(cur, "drop table",    "DROP TABLE _test")

cur.close()
conn.close()
print("\nAll tests passed.")
