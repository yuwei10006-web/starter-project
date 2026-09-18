CREATE TABLE IF NOT EXISTS products (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    price       DOUBLE PRECISION,
    stock       INT,
    created_at  TIMESTAMP DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(255),
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
