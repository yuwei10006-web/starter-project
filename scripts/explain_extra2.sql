\echo '===== Q8 2-char Chinese, NO match, sort by price ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%鍵鍵%' ESCAPE '!'
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;

\echo '===== Q9 3-char Chinese, NO match, sort by price ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%鍵鍵鍵%' ESCAPE '!'
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;