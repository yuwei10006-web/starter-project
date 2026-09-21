\echo '===== Q1b rare keyword (exactly 1 match), sort by createdAt desc ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%583921%' ESCAPE '!'
ORDER BY created_at DESC, id DESC LIMIT 21 OFFSET 0;

\echo '===== Q6 rare keyword (exactly 1 match), sort by price ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%583921%' ESCAPE '!'
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;

\echo '===== Q7 diagnostic: 2-char Chinese keyword, force GIN ====='
SET enable_seqscan = off;
SET enable_indexscan = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%鍵盤%' ESCAPE '!'
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;
RESET enable_seqscan;
RESET enable_indexscan;