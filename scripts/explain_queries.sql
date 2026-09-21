\echo '===== Q1 rare keyword (about 1 match), sort by createdAt desc ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%777777%' ESCAPE '!'
ORDER BY created_at DESC, id DESC LIMIT 21 OFFSET 0;

\echo '===== Q2 2-char Chinese keyword (about 10% match), sort by price ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%鍵盤%' ESCAPE '!'
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;

\echo '===== Q3 4-char Chinese keyword (about 1% match) + price range ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE name ILIKE '%機械鍵盤%' ESCAPE '!' AND price >= 1000 AND price <= 5000
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;

\echo '===== Q4 price range only, sort by price ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products WHERE price >= 100 AND price <= 200
ORDER BY price ASC, id ASC LIMIT 21 OFFSET 0;

\echo '===== Q5 no filter, default sort (newest first) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM products
ORDER BY created_at DESC, id DESC LIMIT 21 OFFSET 0;