CREATE TABLE IF NOT EXISTS products (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    price       DOUBLE PRECISION,
    stock       INT,
    created_at  TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(255) UNIQUE,
    password    VARCHAR(255),
    role        VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    product_id  BIGINT,
    quantity    INT,
    total_price DOUBLE PRECISION,
    created_at  TIMESTAMP DEFAULT now()
);

-- ===== 商品查詢效能索引（模糊搜尋 / 價格區間 / 排序）=====
-- pg_trgm：讓 name ILIKE '%keyword%' 這種前後模糊比對也能走索引（B-tree 做不到）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING gin (name gin_trgm_ops);
-- 價格區間 + 依價格排序；多帶 id 讓排序有穩定的 tie-breaker
CREATE INDEX IF NOT EXISTS idx_products_price_id ON products (price, id);
-- 預設排序（最新優先）
CREATE INDEX IF NOT EXISTS idx_products_created_at_id ON products (created_at DESC, id DESC);
