\set ON_ERROR_STOP on

DO $$
BEGIN
  IF current_database() <> 'nemo_benchmark' THEN
    RAISE EXCEPTION 'Refusing to explain against database %', current_database();
  END IF;
END
$$;

EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT p.*
FROM photos p
WHERE p.user_id = 1
  AND p.deleted = false
ORDER BY p.taken_at DESC
LIMIT 20 OFFSET 0;

EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT p.*
FROM photos p
WHERE p.user_id = 1
  AND p.deleted = false
ORDER BY p.taken_at DESC;
