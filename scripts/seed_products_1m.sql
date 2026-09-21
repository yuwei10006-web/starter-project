-- 用法：docker compose exec -T db psql -U trading -d trading < scripts/seed_products_1m.sql
INSERT INTO products (name, price, stock, created_at, version)
SELECT
    (ARRAY['機械','無線','人體工學','電競','靜音','便攜','智慧','4K','藍牙','輕量'])[floor(random() * 10)::int + 1]
 || (ARRAY['鍵盤','滑鼠','螢幕','耳機','椅子','桌子','喇叭','充電器','攝影機','擴充座'])[floor(random() * 10)::int + 1]
 || ' ' || (ARRAY['Pro','Max','Lite','Plus','Air'])[floor(random() * 5)::int + 1]
 || ' ' || g,
    round((random() * 49990 + 10)::numeric, 2),
    floor(random() * 500)::int,
    now() - make_interval(days => floor(random() * 365)::int),
    0
FROM generate_series(1, 1000000) AS g;

ANALYZE products;